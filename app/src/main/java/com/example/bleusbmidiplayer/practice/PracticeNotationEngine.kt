package com.example.bleusbmidiplayer.practice

import com.example.bleusbmidiplayer.midi.MidiEvent
import com.example.bleusbmidiplayer.midi.MidiSequence
import kotlin.math.max
import kotlin.math.min

class PracticeNotationEngine(sequence: MidiSequence) {
    val notes: List<RenderedNote>
    val measures: List<RenderedMeasure>
    val durationMs: Long = sequence.durationMs
    val minPitch: Int
    val maxPitch: Int

    init {
        val parsedNotes = mutableListOf<RenderedNote>()
        val activeNotes = mutableMapOf<Int, MidiEvent>()
        sequence.events.forEach { event ->
            val status = event.data.getOrNull(0)?.toInt() ?: return@forEach
            val command = status and 0xF0
            val pitch = event.data.getOrNull(1)?.toInt()?.and(0xFF) ?: return@forEach
            val velocity = event.data.getOrNull(2)?.toInt()?.and(0xFF) ?: 0
            val isNoteOn = command == 0x90 && velocity > 0
            val isNoteOff = command == 0x80 || (command == 0x90 && velocity == 0)
            if (isNoteOn) {
                activeNotes[pitch] = event
            } else if (isNoteOff) {
                val startEvent = activeNotes.remove(pitch)
                if (startEvent != null) {
                    val startVelocity = startEvent.data.getOrNull(2)?.toInt()?.and(0xFF) ?: 64
                    val duration = max(60L, event.timestampMs - startEvent.timestampMs)
                    parsedNotes += RenderedNote(
                        pitch = pitch,
                        startTime = startEvent.timestampMs,
                        duration = duration,
                        velocity = startVelocity
                    )
                }
            }
        }
        activeNotes.forEach { (pitch, start) ->
            val velocity = start.data.getOrNull(2)?.toInt()?.and(0xFF) ?: 64
            parsedNotes += RenderedNote(
                pitch = pitch,
                startTime = start.timestampMs,
                duration = 240L,
                velocity = velocity
            )
        }
        notes = parsedNotes.sortedBy { it.startTime }
        minPitch = notes.minOfOrNull { it.pitch } ?: 60
        maxPitch = notes.maxOfOrNull { it.pitch } ?: 72
        val measureDuration = 2_000L
        val totalDuration = durationMs.coerceAtLeast(1L)
        val measureCount = ((totalDuration + measureDuration - 1) / measureDuration).toInt().coerceAtLeast(1)
        measures = (0 until measureCount).map { index ->
            val start = index * measureDuration
            val end = min(totalDuration, start + measureDuration)
            val measureNotes = notes.filter { it.startTime in start until end }
            RenderedMeasure(
                startTime = start,
                endTime = end,
                notes = measureNotes
            )
        }
    }
}

data class RenderedMeasure(
    val startTime: Long,
    val endTime: Long,
    val notes: List<RenderedNote>,
)

data class RenderedNote(
    val pitch: Int,
    val startTime: Long,
    val duration: Long,
    val velocity: Int,
)
