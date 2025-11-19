package com.example.bleusbmidiplayer

import android.app.Application
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bleusbmidiplayer.data.MidiSettingsStore
import com.example.bleusbmidiplayer.midi.MidiDeviceController
import com.example.bleusbmidiplayer.midi.MidiDeviceSession
import com.example.bleusbmidiplayer.midi.MidiFileItem
import com.example.bleusbmidiplayer.midi.MidiPlaybackEngine
import com.example.bleusbmidiplayer.midi.MidiRepository
import com.example.bleusbmidiplayer.midi.PlaybackEngineState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val midiManager: MidiManager = application.getSystemService(MidiManager::class.java)
        ?: throw IllegalStateException("MIDI not supported on this device")
    private val repository = MidiRepository(application)
    private val settingsStore = MidiSettingsStore(application)
    private val deviceController = MidiDeviceController(midiManager)
    private val playbackEngine = MidiPlaybackEngine(viewModelScope)

    private val _uiState = MutableStateFlow(
        MainUiState(
            library = LibraryUiState(bundled = repository.listBundledMidi())
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var libraryJob: Job? = null

    init {
        observeFolderSelection()
        viewModelScope.launch {
            deviceController.devices.collect { devices ->
                _uiState.update { it.copy(devices = devices) }
            }
        }
        viewModelScope.launch {
            deviceController.session.collect { session ->
                if (session == null) {
                    playbackEngine.stop()
                }
                _uiState.update { it.copy(activeSession = session) }
            }
        }
        viewModelScope.launch {
            playbackEngine.state.collect { state ->
                _uiState.update { it.copy(playbackState = state) }
            }
        }
    }

    private fun observeFolderSelection() {
        viewModelScope.launch {
            settingsStore.selectedFolder.collectLatest { uri ->
                loadExternalFiles(uri)
            }
        }
    }

    private fun loadExternalFiles(uri: Uri?) {
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    library = it.library.copy(
                        isLoading = true,
                        selectedFolder = uri,
                        error = null
                    )
                )
            }
            try {
                val files = repository.listExternalMidi(uri)
                _uiState.update {
                    it.copy(
                        library = it.library.copy(
                            external = files,
                            selectedFolder = uri,
                            isLoading = false,
                            error = null
                        )
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        library = it.library.copy(
                            isLoading = false,
                            selectedFolder = uri,
                            error = t.message ?: "Unable to read folder"
                        )
                    )
                }
            }
        }
    }

    fun selectFolder(uri: Uri?) {
        viewModelScope.launch {
            settingsStore.updateFolder(uri)
        }
    }

    fun refreshDevices() {
        deviceController.refreshDevices()
    }

    fun connect(deviceInfo: MidiDeviceInfo) {
        deviceController.openDevice(deviceInfo) { result ->
            result.onFailure { error ->
                _uiState.update { it.copy(message = error.message ?: "Unable to open device") }
            }
        }
    }

    fun disconnectDevice() {
        deviceController.disconnect()
    }

    fun play(item: MidiFileItem) {
        val session = _uiState.value.activeSession
        if (session == null) {
            _uiState.update { it.copy(message = "Connect a MIDI device first") }
            return
        }
        viewModelScope.launch {
            val sequence = repository.loadSequence(item)
            if (sequence == null) {
                _uiState.update { it.copy(message = "Unable to load ${item.title}") }
                return@launch
            }
            playbackEngine.play(sequence, session.outputPort, item)
        }
    }

    fun stopPlayback() {
        playbackEngine.stop()
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    override fun onCleared() {
        super.onCleared()
        deviceController.dispose()
    }
}

data class MainUiState(
    val devices: List<MidiDeviceInfo> = emptyList(),
    val activeSession: MidiDeviceSession? = null,
    val library: LibraryUiState = LibraryUiState(),
    val playbackState: PlaybackEngineState = PlaybackEngineState.Idle,
    val message: String? = null,
)

data class LibraryUiState(
    val bundled: List<MidiFileItem> = emptyList(),
    val external: List<MidiFileItem> = emptyList(),
    val selectedFolder: Uri? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)
