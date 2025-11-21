package com.example.bleusbmidiplayer

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bleusbmidiplayer.data.MidiSettingsStore
import com.example.bleusbmidiplayer.data.UserCollectionsSnapshot
import com.example.bleusbmidiplayer.data.UserCollectionsStore
import com.example.bleusbmidiplayer.midi.FolderNodeState
import com.example.bleusbmidiplayer.midi.FolderTreeChildren
import com.example.bleusbmidiplayer.midi.FolderTreeUiState
import com.example.bleusbmidiplayer.midi.MidiDeviceController
import com.example.bleusbmidiplayer.midi.MidiDeviceSession
import com.example.bleusbmidiplayer.midi.MidiFileItem
import com.example.bleusbmidiplayer.midi.MidiPlaylist
import com.example.bleusbmidiplayer.midi.MidiPlaybackEngine
import com.example.bleusbmidiplayer.midi.MidiRepository
import com.example.bleusbmidiplayer.midi.PlaybackEngineState
import com.example.bleusbmidiplayer.midi.MidiSequence
import com.example.bleusbmidiplayer.midi.MidiEvent
import com.example.bleusbmidiplayer.midi.TrackReference
import com.example.bleusbmidiplayer.midi.toMidiFileItem
import com.example.bleusbmidiplayer.midi.toTrackReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val midiManager: MidiManager = application.getSystemService(MidiManager::class.java)
        ?: throw IllegalStateException("MIDI not supported on this device")
    private val bluetoothManager: BluetoothManager? =
        application.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val repository = MidiRepository(application)
    private val settingsStore = MidiSettingsStore(application)
    private val collectionsStore = UserCollectionsStore(application)
    private val deviceController = MidiDeviceController(midiManager)
    private val playbackEngine = MidiPlaybackEngine(viewModelScope)
    private var scanCallback: ScanCallback? = null
    private var scanTimeoutJob: Job? = null
    private var inputReceiver: MidiReceiver? = null
    private val inboundEvents = MutableSharedFlow<MidiInputEvent>(extraBufferCapacity = 64)
    private var practiceState: PracticeSessionState = PracticeSessionState.Inactive
    private val chordState = ChordTracker()

    private val _uiState = MutableStateFlow(
        MainUiState(
            library = LibraryUiState(bundled = repository.listBundledMidi())
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

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
                    updateQueue { PlaybackQueueState() }
                    attachInputReceiver(null)
                }
                _uiState.update { it.copy(activeSession = session) }
                attachInputReceiver(session)
            }
        }
        viewModelScope.launch {
            playbackEngine.state.collectLatest { state ->
                if (state is PlaybackEngineState.Completed) {
                    playNextInQueueInternal(userAction = false)
                }
                _uiState.update { it.copy(playbackState = state) }
            }
        }
        viewModelScope.launch {
            collectionsStore.snapshot.collect { snapshot ->
                applyCollectionsSnapshot(snapshot)
            }
        }
        viewModelScope.launch {
            inboundEvents.collect { event ->
                handleInboundEvent(event)
            }
        }
    }

    private fun observeFolderSelection() {
        viewModelScope.launch {
            settingsStore.selectedFolder.collectLatest { uri ->
                resetFolderTree(uri)
            }
        }
    }

    private fun resetFolderTree(uri: Uri?) {
        val displayName = uri?.let { repository.resolveFolderName(it) }
            ?: uri?.lastPathSegment
        val rootNode = if (uri != null && displayName != null) {
            FolderNodeState(uri = uri, name = displayName)
        } else {
            null
        }
        val error = if (uri != null && rootNode == null) {
            "Unable to access selected folder"
        } else {
            null
        }
        updateFolderTree {
            FolderTreeUiState(
                selectedFolder = uri,
                root = rootNode,
                globalError = error,
            )
        }
    }

    private fun updateBleScan(transform: (BleScanUiState) -> BleScanUiState) {
        _uiState.update { state ->
            state.copy(bleScan = transform(state.bleScan))
        }
    }

    private fun updateFolderTree(transform: (FolderTreeUiState) -> FolderTreeUiState) {
        _uiState.update { state ->
            state.copy(
                library = state.library.copy(
                    folderTree = transform(state.library.folderTree)
                )
            )
        }
    }

    private fun updateQueue(transform: (PlaybackQueueState) -> PlaybackQueueState) {
        _uiState.update { state -> state.copy(queue = transform(state.queue)) }
    }

    private fun applyCollectionsSnapshot(snapshot: UserCollectionsSnapshot) {
        _uiState.update { state ->
            state.copy(
                favorites = snapshot.favorites,
                playlists = snapshot.playlists,
            )
        }
    }

    private fun attachInputReceiver(session: MidiDeviceSession?) {
        inputReceiver = null
        val port = session?.receivePort ?: return
        val receiver = object : MidiReceiver() {
            override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                val copy = data.copyOfRange(offset, offset + count)
                inboundEvents.tryEmit(MidiInputEvent(copy, timestamp))
            }
        }
        try {
            port.connect(receiver)
            inputReceiver = receiver
        } catch (t: Throwable) {
            Log.w("MainViewModel", "Unable to attach input receiver: ${t.message}")
        }
    }

    fun selectFolder(uri: Uri?) {
        viewModelScope.launch {
            settingsStore.updateFolder(uri)
        }
    }

    fun toggleFavorite(item: MidiFileItem) {
        val reference = item.toTrackReference()
        viewModelScope.launch {
            collectionsStore.updateFavorites { favorites ->
                if (favorites.any { it.id == reference.id }) {
                    favorites.filterNot { it.id == reference.id }
                } else {
                    favorites + reference
                }
            }
        }
    }

    fun removeFavorite(trackId: String) {
        viewModelScope.launch {
            collectionsStore.updateFavorites { favorites ->
                favorites.filterNot { it.id == trackId }
            }
        }
    }

    fun addFolderToFavorites(folderUri: Uri) {
        val root = _uiState.value.library.folderTree.selectedFolder ?: run {
            _uiState.update { it.copy(message = "Select a folder first") }
            return
        }
        viewModelScope.launch {
            val tracks = collectFolderTracks(folderUri, root).map { it.toTrackReference() }
            if (tracks.isEmpty()) {
                _uiState.update { it.copy(message = "No MIDI files found in folder") }
                return@launch
            }
            collectionsStore.updateFavorites { favorites ->
                val merged = favorites.associateBy { it.id }.toMutableMap()
                tracks.forEach { merged[it.id] = it }
                merged.values.toList()
            }
        }
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            collectionsStore.updatePlaylists { playlists ->
                if (playlists.any { it.name.equals(name, ignoreCase = true) }) {
                    playlists
                } else {
                    playlists + MidiPlaylist(id = UUID.randomUUID().toString(), name = name.trim())
                }
            }
        }
    }

    fun addTrackToPlaylist(track: MidiFileItem, playlistId: String?, newPlaylistName: String?) {
        val reference = track.toTrackReference()
        viewModelScope.launch {
            if (playlistId != null) {
                collectionsStore.updatePlaylists { playlists ->
                    playlists.map { playlist ->
                        if (playlist.id == playlistId) {
                            playlist.copy(tracks = playlist.tracks.addUnique(reference))
                        } else {
                            playlist
                        }
                    }
                }
                return@launch
            }
            val newName = newPlaylistName?.trim().orEmpty()
            if (newName.isNotEmpty()) {
                collectionsStore.updatePlaylists { playlists ->
                    playlists + MidiPlaylist(
                        id = UUID.randomUUID().toString(),
                        name = newName,
                        tracks = listOf(reference)
                    )
                }
            }
        }
    }

    fun addFolderToPlaylist(folderUri: Uri, playlistId: String?, newPlaylistName: String?) {
        val root = _uiState.value.library.folderTree.selectedFolder ?: run {
            _uiState.update { it.copy(message = "Select a folder first") }
            return
        }
        viewModelScope.launch {
            val refs = collectFolderTracks(folderUri, root).map { it.toTrackReference() }
            if (refs.isEmpty()) {
                _uiState.update { it.copy(message = "No MIDI files found in folder") }
                return@launch
            }
            if (playlistId != null) {
                collectionsStore.updatePlaylists { playlists ->
                    playlists.map { playlist ->
                        if (playlist.id == playlistId) {
                            val merged = playlist.tracks.associateBy { it.id }.toMutableMap()
                            refs.forEach { merged[it.id] = it }
                            playlist.copy(tracks = merged.values.toList())
                        } else {
                            playlist
                        }
                    }
                }
                return@launch
            }
            val newName = newPlaylistName?.trim().orEmpty()
            if (newName.isNotEmpty()) {
                collectionsStore.updatePlaylists { playlists ->
                    playlists + MidiPlaylist(
                        id = UUID.randomUUID().toString(),
                        name = newName,
                        tracks = refs.distinctBy { it.id }
                    )
                }
            }
        }
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            collectionsStore.updatePlaylists { playlists ->
                playlists.map { playlist ->
                    if (playlist.id == playlistId) {
                        playlist.copy(name = newName.trim())
                    } else {
                        playlist
                    }
                }
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            collectionsStore.updatePlaylists { playlists ->
                playlists.filterNot { it.id == playlistId }
            }
            val queue = _uiState.value.queue
            if (queue.mode is QueueMode.Playlist && queue.mode.playlistId == playlistId) {
                updateQueue { PlaybackQueueState() }
                playbackEngine.stop()
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            collectionsStore.updatePlaylists { playlists ->
                playlists.map { playlist ->
                    if (playlist.id == playlistId) {
                        playlist.copy(tracks = playlist.tracks.filterNot { it.id == trackId })
                    } else {
                        playlist
                    }
                }
            }
        }
    }

    fun shufflePlaylist(playlistId: String) {
        viewModelScope.launch {
            collectionsStore.updatePlaylists { playlists ->
                playlists.map { playlist ->
                    if (playlist.id == playlistId) {
                        playlist.copy(tracks = playlist.tracks.shuffled())
                    } else {
                        playlist
                    }
                }
            }
        }
    }

    fun playFavorites(shuffle: Boolean = false) {
        val favorites = _uiState.value.favorites
        if (favorites.isEmpty()) {
            _uiState.update { it.copy(message = "Favorites list is empty") }
            return
        }
        val tracks = if (shuffle) favorites.shuffled() else favorites
        playQueue(tracks, QueueMode.Favorites, startIndex = 0)
    }

    fun playPlaylist(playlistId: String, shuffle: Boolean = false) {
        val playlist = _uiState.value.playlists.firstOrNull { it.id == playlistId }
        if (playlist == null) {
            _uiState.update { it.copy(message = "Playlist not found") }
            return
        }
        if (playlist.tracks.isEmpty()) {
            _uiState.update { it.copy(message = "Playlist is empty") }
            return
        }
        val tracks = if (shuffle) playlist.tracks.shuffled() else playlist.tracks
        playQueue(tracks, QueueMode.Playlist(playlistId), startIndex = 0)
    }
    /** Practice mode selection controlling inbound-note handling */
    fun setPracticeMode(mode: PracticeMode) {
        practiceState = PracticeSessionState.Inactive
        _uiState.update { it.copy(practiceMode = mode, practiceProgress = PracticeProgress.Idle) }
    }

    fun playFavoriteAt(index: Int) {
        val favorites = _uiState.value.favorites
        if (index !in favorites.indices) return
        playQueue(favorites, QueueMode.Favorites, index)
    }

    fun playPlaylistTrack(playlistId: String, index: Int) {
        val playlist = _uiState.value.playlists.firstOrNull { it.id == playlistId } ?: return
        if (index !in playlist.tracks.indices) return
        playQueue(playlist.tracks, QueueMode.Playlist(playlistId), index)
    }

    fun generateRandomPlaylist() {
        val root = _uiState.value.library.folderTree.selectedFolder ?: run {
            _uiState.update { it.copy(message = "Select a folder first") }
            return
        }
        viewModelScope.launch {
            val refs = collectFolderTracks(root, root).map { it.toTrackReference() }
            if (refs.isEmpty()) {
                _uiState.update { it.copy(message = "No files available for playlist") }
                return@launch
            }
            val selection = refs.shuffled().take(50)
            val name = "Random Mix ${System.currentTimeMillis() % 1000}"
            collectionsStore.updatePlaylists { playlists ->
                playlists + MidiPlaylist(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    tracks = selection
                )
            }
        }
    }

    fun play(item: MidiFileItem) {
        val reference = item.toTrackReference()
        playQueue(listOf(reference), QueueMode.Single(reference.title), 0)
    }

    fun stopPlayback() {
        playbackEngine.stop()
        updateQueue { PlaybackQueueState() }
    }

    fun pausePlayback() {
        playbackEngine.pause()
    }

    fun resumePlayback() {
        val session = _uiState.value.activeSession ?: run {
            _uiState.update { it.copy(message = "Connect a MIDI device first") }
            return
        }
        playbackEngine.resume(session.sendPort)
    }

    fun playNextInQueue() {
        playNextInQueueInternal(userAction = true)
    }

    fun playPreviousInQueue() {
        val queue = _uiState.value.queue
        if (queue.currentIndex <= 0) {
            _uiState.update { it.copy(message = "Already at the beginning") }
            return
        }
        playQueue(queue.tracks, queue.mode, queue.currentIndex - 1)
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stopBleScan()
        deviceController.dispose()
    }

    fun startBleScan() {
        if (_uiState.value.bleScan.isScanning) return
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _uiState.update { it.copy(message = "Enable Bluetooth to scan for peripherals") }
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _uiState.update { it.copy(message = "BLE scanner unavailable") }
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                updateBleScan {
                    it.copy(
                        isScanning = false,
                        error = "Scan failed ($errorCode)"
                    )
                }
                stopBleScan()
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, callback)
            scanCallback = callback
            updateBleScan { it.copy(isScanning = true, error = null) }
            scanTimeoutJob?.cancel()
            scanTimeoutJob = viewModelScope.launch {
                delay(SCAN_TIMEOUT_MS)
                stopBleScan()
            }
        } catch (se: SecurityException) {
            updateBleScan { it.copy(isScanning = false, error = "Bluetooth permission denied") }
            scanCallback = null
        } catch (t: Throwable) {
            updateBleScan { it.copy(isScanning = false, error = t.message ?: "Unable to start scan") }
            scanCallback = null
        }
    }

    fun stopBleScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        val callback = scanCallback
        if (scanner != null && callback != null) {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
            } catch (_: Throwable) {
            }
        }
        scanCallback = null
        updateBleScan { it.copy(isScanning = false) }
    }

    fun connectBlePeripheral(peripheral: BlePeripheralItem) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _uiState.update { it.copy(message = "Bluetooth is disabled") }
            return
        }
        val device = try {
            adapter.getRemoteDevice(peripheral.address)
        } catch (e: IllegalArgumentException) {
            _uiState.update { it.copy(message = "Unknown BLE device") }
            return
        }
        deviceController.connectBluetoothDevice(device, peripheral.name.ifBlank { null }) { result ->
            result.onFailure { error ->
                _uiState.update { it.copy(message = error.message ?: "Unable to open BLE device") }
            }
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
        updateQueue { PlaybackQueueState() }
    }

    fun toggleFolder(uri: Uri) {
        val tree = _uiState.value.library.folderTree
        if (tree.root == null) return
        val isExpanded = tree.expanded.contains(uri)
        val newExpanded = if (isExpanded) tree.expanded - uri else tree.expanded + uri
        updateFolderTree { it.copy(expanded = newExpanded) }
        if (!isExpanded) {
            ensureFolderChildren(uri)
        }
    }

    private fun ensureFolderChildren(folderUri: Uri) {
        val tree = _uiState.value.library.folderTree
        val selectedRoot = tree.selectedFolder ?: return
        if (tree.childMap.containsKey(folderUri) || tree.loading.contains(folderUri)) return
        updateFolderTree {
            it.copy(
                loading = it.loading + folderUri,
                errors = it.errors - folderUri,
            )
        }
        viewModelScope.launch {
            try {
                val listing = repository.listFolderChildren(folderUri, selectedRoot)
                val children = FolderTreeChildren(
                    directories = listing.directories.map { dir ->
                        FolderNodeState(uri = dir.uri, name = dir.name)
                    },
                    files = listing.midiFiles
                )
                updateFolderTree {
                    it.copy(
                        loading = it.loading - folderUri,
                        childMap = it.childMap + (folderUri to children),
                        errors = it.errors - folderUri,
                    )
                }
            } catch (t: Throwable) {
                updateFolderTree {
                    it.copy(
                        loading = it.loading - folderUri,
                        errors = it.errors + (folderUri to (t.message ?: "Unable to read folder")),
                    )
                }
            }
        }
    }

    private suspend fun collectFolderTracks(folderUri: Uri, rootUri: Uri): List<MidiFileItem> {
        val items = mutableListOf<MidiFileItem>()
        val queue: ArrayDeque<Uri> = ArrayDeque()
        queue.add(folderUri)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val listing = repository.listFolderChildren(current, rootUri)
            items += listing.midiFiles
            listing.directories.forEach { queue.add(it.uri) }
        }
        return items
    }

    private fun playQueue(tracks: List<TrackReference>, mode: QueueMode, startIndex: Int) {
        if (tracks.isEmpty()) {
            _uiState.update { it.copy(message = "Nothing to play") }
            return
        }
        val session = _uiState.value.activeSession
        if (session == null) {
            _uiState.update { it.copy(message = "Connect a MIDI device first") }
            return
        }
        val targetIndex = startIndex.coerceIn(0, tracks.lastIndex)
        val reference = tracks[targetIndex]
        viewModelScope.launch {
            val item = reference.toMidiFileItem()
            val sequence = repository.loadSequence(item)
            if (sequence == null) {
                _uiState.update { it.copy(message = "Unable to load ${reference.title}") }
                return@launch
            }
            val practiceMode = _uiState.value.practiceMode
            if (practiceMode is PracticeMode.MelodyPractice) {
                startPracticeSession(sequence, session, item, practiceMode)
                updateQueue {
                    PlaybackQueueState(
                        mode = mode,
                        tracks = tracks,
                        currentIndex = targetIndex,
                    )
                }
            } else {
                playbackEngine.play(sequence, session.sendPort, item)
                updateQueue {
                    PlaybackQueueState(
                        mode = mode,
                        tracks = tracks,
                        currentIndex = targetIndex,
                    )
                }
            }
        }
    }

    private fun playNextInQueueInternal(userAction: Boolean) {
        val queue = _uiState.value.queue
        if (queue.currentIndex == -1 || queue.tracks.isEmpty()) return
        val nextIndex = queue.currentIndex + 1
        if (nextIndex >= queue.tracks.size) {
            if (userAction) {
                _uiState.update { it.copy(message = "Reached end of queue") }
            } else {
                updateQueue { it.copy(currentIndex = -1) }
            }
            return
        }
        playQueue(queue.tracks, queue.mode, nextIndex)
    }

    private fun handleScanResult(result: ScanResult) {
        Log.d("MainViewModel", "Found device: ${result.device?.name} - ${result.device?.address}")
        val device = result.device ?: return
        val name = device.name ?: result.scanRecord?.deviceName ?: "Unnamed"
        val hasMidiWord = name.contains("midi", ignoreCase = true)
        val hasMidiService = result.scanRecord?.serviceUuids
            ?.any { it.uuid == MIDI_SERVICE_UUID } == true
        if (!hasMidiWord && !hasMidiService) return
        val item = BlePeripheralItem(
            name = name,
            address = device.address,
            hasMidiService = hasMidiService,
            rssi = result.rssi
        )
        updateBleScan { current ->
            val filtered = current.peripherals.filterNot { it.address == item.address }
            current.copy(
                peripherals = (filtered + item).sortedWith(
                    compareByDescending<BlePeripheralItem> { it.hasMidiService }
                        .thenBy { it.name }
                ),
                error = null
            )
        }
    }

    private fun startPracticeSession(
        sequence: MidiSequence,
        session: MidiDeviceSession,
        file: MidiFileItem,
        mode: PracticeMode.MelodyPractice
    ) {
        val (targetNotes, autoNotes) = splitHands(sequence, mode.hand)
        practiceState = if (targetNotes.isEmpty()) {
            PracticeSessionState.Inactive
        } else {
            PracticeSessionState.Active(
                targetNotes = targetNotes,
                currentIndex = 0,
                total = targetNotes.size,
                file = file,
            )
        }
        _uiState.update { it.copy(practiceProgress = practiceState.toProgress()) }

        if (autoNotes.isNotEmpty()) {
            val autoSequence = MidiSequence(events = autoNotes.map { it.event }, durationMs = sequence.durationMs)
            playbackEngine.play(autoSequence, session.sendPort, file)
        } else {
            playbackEngine.stop()
        }
    }

    private fun splitHands(sequence: MidiSequence, hand: PracticeHand): Pair<List<NoteEvent>, List<NoteEvent>> {
        val target = mutableListOf<NoteEvent>()
        val auto = mutableListOf<NoteEvent>()
        sequence.events.forEach { evt ->
            val note = evt.toNoteEvent() ?: return@forEach
            val isLeft = note.pitch < 60
            val assignToTarget = when (hand) {
                PracticeHand.Left -> isLeft
                PracticeHand.Right -> !isLeft
                PracticeHand.Both -> true
            }
            if (assignToTarget) {
                target += note
            } else {
                auto += note
            }
        }
        return target to auto
    }

    private fun handleInboundEvent(event: MidiInputEvent) {
        // update chord detection
        val status = event.data.getOrNull(0)?.toInt() ?: return
        val command = status and 0xF0
        val noteNumber = event.data.getOrNull(1)?.toInt()?.and(0xFF) ?: -1
        val velocity = event.data.getOrNull(2)?.toInt()?.and(0xFF) ?: 0
        val isNoteOn = command == 0x90 && velocity > 0
        val isNoteOff = command == 0x80 || (command == 0x90 && velocity == 0)

        if (isNoteOn) {
            chordState.add(noteNumber, event.timestamp)
        } else if (isNoteOff) {
            chordState.remove(noteNumber)
        }
        _uiState.update { state ->
            state.copy(
                lastInboundEvent = event,
                chord = chordState.describe()
            )
        }
        if (isNoteOn) {
            advancePractice(noteNumber, event.data)
        }
    }

    private fun advancePractice(noteNumber: Int, rawData: ByteArray) {
        val sessionState = practiceState
        if (sessionState !is PracticeSessionState.Active) return
        val expected = sessionState.targetNotes.getOrNull(sessionState.currentIndex) ?: return
        if (expected.pitch != noteNumber) return
        val session = _uiState.value.activeSession ?: return
        try {
            session.sendPort.send(rawData, 0, rawData.size)
        } catch (_: Throwable) {
        }
        val nextIndex = sessionState.currentIndex + 1
        practiceState = if (nextIndex >= sessionState.total) {
            PracticeSessionState.Completed(sessionState.file)
        } else {
            sessionState.copy(currentIndex = nextIndex)
        }
        _uiState.update { it.copy(practiceProgress = practiceState.toProgress()) }
    }

    private fun List<TrackReference>.addUnique(track: TrackReference): List<TrackReference> {
        return if (any { it.id == track.id }) this else this + track
    }
}

data class MainUiState(
    val devices: List<MidiDeviceInfo> = emptyList(),
    val activeSession: MidiDeviceSession? = null,
    val library: LibraryUiState = LibraryUiState(),
    val playbackState: PlaybackEngineState = PlaybackEngineState.Idle,
    val bleScan: BleScanUiState = BleScanUiState(),
    val favorites: List<TrackReference> = emptyList(),
    val playlists: List<MidiPlaylist> = emptyList(),
    val queue: PlaybackQueueState = PlaybackQueueState(),
    val lastInboundEvent: MidiInputEvent? = null,
    val practiceMode: PracticeMode = PracticeMode.Off,
    val practiceProgress: PracticeProgress = PracticeProgress.Idle,
    val chord: String? = null,
    val message: String? = null,
)

data class LibraryUiState(
    val bundled: List<MidiFileItem> = emptyList(),
    val folderTree: FolderTreeUiState = FolderTreeUiState(),
)

data class BleScanUiState(
    val isScanning: Boolean = false,
    val peripherals: List<BlePeripheralItem> = emptyList(),
    val error: String? = null,
)

data class BlePeripheralItem(
    val name: String,
    val address: String,
    val hasMidiService: Boolean,
    val rssi: Int,
)

data class PlaybackQueueState(
    val mode: QueueMode = QueueMode.Idle,
    val tracks: List<TrackReference> = emptyList(),
    val currentIndex: Int = -1,
)

data class MidiInputEvent(
    val data: ByteArray,
    val timestamp: Long,
)

sealed interface PracticeMode {
    data object Off : PracticeMode
    data class MelodyPractice(val hand: PracticeHand) : PracticeMode
    data object FreePlay : PracticeMode
}

enum class PracticeHand { Left, Right, Both }

sealed interface PracticeProgress {
    data object Idle : PracticeProgress
    data class Active(val completed: Int, val total: Int, val nextPitch: Int?) : PracticeProgress
    data class Done(val fileTitle: String) : PracticeProgress
}

private sealed interface PracticeSessionState {
    data object Inactive : PracticeSessionState
    data class Active(
        val targetNotes: List<NoteEvent>,
        val currentIndex: Int,
        val total: Int,
        val file: MidiFileItem,
    ) : PracticeSessionState

    data class Completed(val file: MidiFileItem) : PracticeSessionState
}

private data class NoteEvent(
    val pitch: Int,
    val velocity: Int,
    val event: MidiEvent,
)

private class ChordTracker {
    private val pressed = mutableSetOf<Int>()

    fun add(note: Int, timestamp: Long) {
        pressed += note
    }

    fun remove(note: Int) {
        pressed -= note
    }

    fun describe(): String? {
        if (pressed.size < 3) return null
        val notes = pressed.sorted()
        val root = notes.first()
        val intervals = notes.map { (it - root) % 12 }.sorted()
        val name = when {
            intervals.containsAll(listOf(0, 4, 7)) -> "${pitchName(root)} major"
            intervals.containsAll(listOf(0, 3, 7)) -> "${pitchName(root)} minor"
            intervals.containsAll(listOf(0, 3, 6)) -> "${pitchName(root)} dim"
            intervals.containsAll(listOf(0, 4, 8)) -> "${pitchName(root)} aug"
            else -> "${pitchName(root)} chord"
        }
        return name
    }

    private fun pitchName(pitch: Int): String {
        val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        return names[pitch.mod(12)]
    }
}

private fun MidiEvent.toNoteEvent(): NoteEvent? {
    val status = data.getOrNull(0)?.toInt() ?: return null
    val command = status and 0xF0
    val note = data.getOrNull(1)?.toInt()?.and(0xFF) ?: return null
    val velocity = data.getOrNull(2)?.toInt()?.and(0xFF) ?: 0
    val isNoteOn = command == 0x90 && velocity > 0
    if (!isNoteOn) return null
    return NoteEvent(pitch = note, velocity = velocity, event = this)
}

private fun PracticeSessionState.toProgress(): PracticeProgress = when (this) {
    PracticeSessionState.Inactive -> PracticeProgress.Idle
    is PracticeSessionState.Completed -> PracticeProgress.Done(file.title)
    is PracticeSessionState.Active -> PracticeProgress.Active(
        completed = currentIndex,
        total = total,
        nextPitch = targetNotes.getOrNull(currentIndex)?.pitch
    )
}

sealed interface QueueMode {
    data object Idle : QueueMode
    data object Favorites : QueueMode
    data class Playlist(val playlistId: String) : QueueMode
    data class Single(val title: String? = null) : QueueMode
}

private val MIDI_SERVICE_UUID: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
private const val SCAN_TIMEOUT_MS = 15_000L
