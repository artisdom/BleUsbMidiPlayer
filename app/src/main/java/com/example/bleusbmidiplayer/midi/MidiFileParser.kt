package com.example.bleusbmidiplayer.midi

import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class MidiFileParser {
    fun parse(source: InputStream): MidiSequence {
        val input = if (source is BufferedInputStream) source else BufferedInputStream(source)
        val (headerId, headerLength) = readChunkHeader(input)
        require(headerId == HEADER_ID) { "Invalid MIDI header: $headerId" }
        require(headerLength >= HEADER_MIN_LENGTH) { "Invalid MIDI header length: $headerLength" }
        val headerData = input.readFully(headerLength)
        val formatType = readUInt16(headerData, 0)
        val trackCount = readUInt16(headerData, 2)
        val division = readUInt16(headerData, 4)
        require(division and 0x8000 == 0) { "SMPTE time code is not supported" }
        val ticksPerQuarter = division
        val rawEvents = mutableListOf<RawEvent>()
        val eventOrder = AtomicInteger()
        repeat(trackCount) {
            val (chunkId, chunkLength) = readChunkHeader(input)
            val data = input.readFully(chunkLength)
            if (chunkId == TRACK_ID) {
                parseTrack(data, rawEvents, eventOrder)
            } else {
                Log.w(TAG, "Skipping unknown chunk $chunkId")
            }
        }
        if (rawEvents.isEmpty()) {
            return MidiSequence(events = emptyList(), durationMs = 0)
        }
        val sorted = rawEvents.sortedWith(
            compareBy<RawEvent> { it.tick }
                .thenBy { it.order }
        )
        var microsPerQuarter = DEFAULT_TEMPO_US
        var lastTick = 0L
        var elapsedMicros = 0L
        val events = mutableListOf<MidiEvent>()
        sorted.forEach { raw ->
            val deltaTicks = raw.tick - lastTick
            if (deltaTicks < 0) {
                Log.w(TAG, "Ignoring negative delta at tick=${raw.tick}")
                return@forEach
            }
            elapsedMicros += (deltaTicks * microsPerQuarter) / ticksPerQuarter
            lastTick = raw.tick
            if (raw.tempoUsPerQuarter != null) {
                microsPerQuarter = raw.tempoUsPerQuarter
                return@forEach
            }
            val payload = raw.data ?: return@forEach
            events += MidiEvent(
                timestampMs = elapsedMicros / 1000,
                data = payload,
            )
        }
        val durationMs = elapsedMicros / 1000
        return MidiSequence(events = events, durationMs = durationMs)
    }

    private fun parseTrack(
        data: ByteArray,
        collector: MutableList<RawEvent>,
        order: AtomicInteger,
    ) {
        var index = 0
        var tick = 0L
        var runningStatus = -1
        while (index < data.size) {
            val (delta, deltaLength) = readVariableLength(data, index)
            index += deltaLength
            tick += delta
            if (index >= data.size) break
            var status = data[index].toInt() and 0xFF
            var newStatusByte = false
            if (status and 0x80 != 0) {
                newStatusByte = true
                index++
            } else if (runningStatus != -1) {
                status = runningStatus
            } else {
                throw IllegalStateException("Running status missing at index=$index")
            }
            when (status) {
                0xFF -> {
                    if (index >= data.size) break
                    val metaType = data[index].toInt() and 0xFF
                    index++
                    val (length, lenBytes) = readVariableLength(data, index)
                    index += lenBytes
                    val end = min(data.size, index + length.toInt())
                    val payload = data.copyOfRange(index, end)
                    if (metaType == 0x51 && payload.size == 3) {
                        val tempo = ((payload[0].toInt() and 0xFF) shl 16) or
                                ((payload[1].toInt() and 0xFF) shl 8) or
                                (payload[2].toInt() and 0xFF)
                        collector += RawEvent(
                            tick = tick,
                            order = order.getAndIncrement(),
                            tempoUsPerQuarter = tempo.toLong(),
                            data = null,
                        )
                    }
                    index = end
                    if (metaType == 0x2F) {
                        return
                    }
                    runningStatus = -1
                }
                0xF0, 0xF7 -> {
                    val (length, lenBytes) = readVariableLength(data, index)
                    index += lenBytes
                    val messageLength = min(length.toInt(), data.size - index)
                    val bytes = ByteArray(messageLength + 1)
                    bytes[0] = status.toByte()
                    System.arraycopy(data, index, bytes, 1, messageLength)
                    collector += RawEvent(
                        tick = tick,
                        order = order.getAndIncrement(),
                        tempoUsPerQuarter = null,
                        data = bytes,
                    )
                    index += messageLength
                    runningStatus = -1
                }
                else -> {
                    if (newStatusByte) {
                        runningStatus = if (status < 0xF0) status else -1
                    }
                    val dataBytes = when (status and 0xF0) {
                        0xC0, 0xD0 -> 1
                        else -> 2
                    }
                    if (index + dataBytes > data.size) break
                    val bytes = ByteArray(dataBytes + 1)
                    bytes[0] = status.toByte()
                    for (i in 0 until dataBytes) {
                        bytes[i + 1] = data[index + i]
                    }
                    index += dataBytes
                    collector += RawEvent(
                        tick = tick,
                        order = order.getAndIncrement(),
                        tempoUsPerQuarter = null,
                        data = bytes,
                    )
                }
            }
        }
    }

    private fun readChunkHeader(input: InputStream): Pair<String, Int> {
        val nameBytes = ByteArray(4)
        val nameRead = input.read(nameBytes)
        if (nameRead < 4) throw IllegalArgumentException("Unexpected end of MIDI file")
        val lengthBytes = input.readFully(4)
        val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).int
        return String(nameBytes, Charsets.US_ASCII) to length
    }

    private fun InputStream.readFully(length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val bytesRead = read(buffer, offset, length - offset)
            if (bytesRead == -1) {
                throw IllegalArgumentException("Unexpected EOF in MIDI stream")
            }
            offset += bytesRead
        }
        return buffer
    }

    private fun readVariableLength(source: ByteArray, startIndex: Int): Pair<Long, Int> {
        var index = startIndex
        var value = 0L
        var count = 0
        do {
            if (index >= source.size) break
            val current = source[index].toInt() and 0xFF
            index++
            count++
            value = (value shl 7) or (current and 0x7F).toLong()
            if (current and 0x80 == 0) break
        } while (count < 4)
        return value to count
    }

    private fun readUInt16(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or
                (buffer[offset + 1].toInt() and 0xFF)
    }

    private data class RawEvent(
        val tick: Long,
        val order: Int,
        val tempoUsPerQuarter: Long?,
        val data: ByteArray?,
    )

    companion object {
        private const val TAG = "MidiFileParser"
        private const val HEADER_ID = "MThd"
        private const val TRACK_ID = "MTrk"
        private const val HEADER_MIN_LENGTH = 6
        private const val DEFAULT_TEMPO_US = 500_000L
    }
}
