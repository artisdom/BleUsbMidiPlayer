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

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        _state.value = PlaybackEngineState.Idle
    }

    fun play(
        sequence: MidiSequence,
        receiver: MidiReceiver,
        file: MidiFileItem,
    ) {
        playbackJob?.cancel()
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
}

private const val PROGRESS_EMIT_MS = 45L
