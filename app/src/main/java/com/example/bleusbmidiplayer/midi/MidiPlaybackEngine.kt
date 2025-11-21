package com.example.bleusbmidiplayer.midi

import android.media.midi.MidiReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class MidiPlaybackEngine(
    private val scope: CoroutineScope,
) {
    private var playbackJob: Job? = null
    private val _state = MutableStateFlow<PlaybackEngineState>(PlaybackEngineState.Idle)
    val state: StateFlow<PlaybackEngineState> = _state

    private var pauseTimestamp: Long? = null
    private var sequenceCache: MidiSequence? = null
    private var currentFile: MidiFileItem? = null

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        _state.value = PlaybackEngineState.Idle
        pauseTimestamp = null
        sequenceCache = null
        currentFile = null
    }

    fun pause() {
        val currentState = _state.value
        if (currentState is PlaybackEngineState.Playing) {
            pauseTimestamp = currentState.positionMs
            _state.value = PlaybackEngineState.Paused(currentState.file, currentState.positionMs, currentState.durationMs)
            playbackJob?.cancel()
            playbackJob = null
        }
    }

    fun play(
        sequence: MidiSequence,
        receiver: MidiReceiver,
        file: MidiFileItem,
    ) {
        playbackJob?.cancel()
        sequenceCache = sequence
        currentFile = file
        pauseTimestamp = null
        val job = scope.launch(Dispatchers.IO) {
            try {
                _state.value = PlaybackEngineState.Playing(file, 0, sequence.durationMs)
                var lastTimestamp = 0L
                var lastProgressEmit = -1L
                sequence.events.forEach { event ->
                    val delta = max(0L, event.timestampMs - lastTimestamp)
                    if (delta > 0) {
                        delay(delta)
                    }
                    if (!isActive) return@launch
                    receiver.send(event.data, 0, event.data.size)
                    lastTimestamp = event.timestampMs
                    if (event.timestampMs - lastProgressEmit >= PROGRESS_EMIT_MS) {
                        _state.value = PlaybackEngineState.Playing(
                            file = file,
                            positionMs = min(event.timestampMs, sequence.durationMs),
                            durationMs = sequence.durationMs,
                        )
                        lastProgressEmit = event.timestampMs
                    }
                }
                _state.value = PlaybackEngineState.Completed(file)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.value = PlaybackEngineState.Error(file, t.message ?: "Playback error")
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                if (_state.value is PlaybackEngineState.Playing) {
                    _state.value = PlaybackEngineState.Idle
                }
            }
            if (playbackJob === job) {
                playbackJob = null
            }
        }
        playbackJob = job
    }

    fun resume(receiver: MidiReceiver) {
        val pausedState = _state.value as? PlaybackEngineState.Paused ?: return
        startPlaybackFrom(pausedState.positionMs, receiver)
    }

    fun seekTo(positionMs: Long, receiver: MidiReceiver) {
        startPlaybackFrom(positionMs, receiver)
    }

    private fun startPlaybackFrom(positionMs: Long, receiver: MidiReceiver) {
        val sequence = sequenceCache ?: return
        val file = currentFile ?: return
        val duration = sequence.durationMs
        val target = positionMs.coerceIn(0L, duration)
        playbackJob?.cancel()
        val job = scope.launch(Dispatchers.IO) {
            try {
                var lastTimestamp = target
                var lastProgressEmit = target
                _state.value = PlaybackEngineState.Playing(file, target, duration)
                sequence.events
                    .dropWhile { it.timestampMs < target }
                    .forEach { event ->
                        val delta = max(0L, event.timestampMs - lastTimestamp)
                        if (delta > 0) delay(delta)
                        if (!isActive) return@launch
                        receiver.send(event.data, 0, event.data.size)
                        lastTimestamp = event.timestampMs
                        if (event.timestampMs - lastProgressEmit >= PROGRESS_EMIT_MS) {
                            _state.value = PlaybackEngineState.Playing(
                                file = file,
                                positionMs = min(event.timestampMs, duration),
                                durationMs = duration,
                            )
                            lastProgressEmit = event.timestampMs
                        }
                    }
                _state.value = PlaybackEngineState.Completed(file)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.value = PlaybackEngineState.Error(file, t.message ?: "Playback error")
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                if (_state.value is PlaybackEngineState.Playing) {
                    _state.value = PlaybackEngineState.Idle
                }
            }
            if (playbackJob === job) playbackJob = null
        }
        playbackJob = job
    }
}

sealed interface PlaybackEngineState {
    data object Idle : PlaybackEngineState
    data class Playing(
        val file: MidiFileItem,
        val positionMs: Long,
        val durationMs: Long,
    ) : PlaybackEngineState

    data class Completed(val file: MidiFileItem) : PlaybackEngineState
    data class Error(val file: MidiFileItem, val message: String) : PlaybackEngineState
    data class Paused(
        val file: MidiFileItem,
        val positionMs: Long,
        val durationMs: Long,
    ) : PlaybackEngineState
}

private const val PROGRESS_EMIT_MS = 45L
