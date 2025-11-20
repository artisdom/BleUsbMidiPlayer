package com.example.bleusbmidiplayer

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.media.midi.MidiDeviceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bleusbmidiplayer.BlePeripheralItem
import com.example.bleusbmidiplayer.BleScanUiState
import com.example.bleusbmidiplayer.PlaybackQueueState
import com.example.bleusbmidiplayer.QueueMode
import com.example.bleusbmidiplayer.midi.FolderNodeState
import com.example.bleusbmidiplayer.midi.FolderTreeRow
import com.example.bleusbmidiplayer.midi.MidiDeviceSession
import com.example.bleusbmidiplayer.midi.MidiFileItem
import com.example.bleusbmidiplayer.midi.MidiPlaylist
import com.example.bleusbmidiplayer.midi.PlaybackEngineState
import com.example.bleusbmidiplayer.midi.TrackReference
import com.example.bleusbmidiplayer.midi.TrackReferenceSource
import com.example.bleusbmidiplayer.ui.theme.BleUsbMidiPlayerTheme

class MainActivity : ComponentActivity() {
    private lateinit var bluetoothPermissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bluetoothPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
        ensureBluetoothPermissions()
        enableEdgeToEdge()
        setContent {
            BleUsbMidiPlayerTheme {
                MidiPlayerApp()
            }
        }
    }

    private fun ensureBluetoothPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += REQUIRED_BLUETOOTH_PERMISSIONS.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            needed += PRE_S_LOCATION_PERMISSIONS.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
        }
        if (needed.isNotEmpty()) {
            bluetoothPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    companion object {
        private val REQUIRED_BLUETOOTH_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
        private val PRE_S_LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MidiPlayerApp(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val favoriteIds = remember(uiState.favorites) { uiState.favorites.map { it.id }.toSet() }
    var playlistDialogTarget by remember { mutableStateOf<PlaylistTarget?>(null) }
    var managePlaylist by remember { mutableStateOf<MidiPlaylist?>(null) }
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
            }
            viewModel.selectFolder(uri)
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("BLE & USB MIDI Player") },
                actions = {
                    IconButton(onClick = viewModel::refreshDevices) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh devices")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { folderLauncher.launch(uiState.library.folderTree.selectedFolder) }) {
                Icon(Icons.Default.Folder, contentDescription = "Pick MIDI folder")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                DeviceSection(
                    devices = uiState.devices,
                    session = uiState.activeSession,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnectDevice
                )
            }
            item {
                BleScanSection(
                    scanState = uiState.bleScan,
                    onStartScan = viewModel::startBleScan,
                    onStopScan = viewModel::stopBleScan,
                    onConnect = viewModel::connectBlePeripheral
                )
            }
            item {
                PlaybackSection(
                    playbackState = uiState.playbackState,
                    queueState = uiState.queue,
                    onStop = viewModel::stopPlayback,
                    onNext = viewModel::playNextInQueue,
                    onPrevious = viewModel::playPreviousInQueue
                )
            }
            item {
                LibrarySection(
                    title = "Bundled songs",
                    subtitle = "MIDI files stored in assets/midi",
                    files = uiState.library.bundled,
                    currentFileId = uiState.playbackState.currentFileId(),
                    favoriteIds = favoriteIds,
                    onPlay = viewModel::play,
                    onStop = viewModel::stopPlayback,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onAddToPlaylist = { playlistDialogTarget = PlaylistTarget.Track(it) }
                )
            }
            item {
                FavoritesSection(
                    favorites = uiState.favorites,
                    currentFileId = uiState.playbackState.currentFileId(),
                    onPlayAll = { viewModel.playFavorites(shuffle = false) },
                    onShuffle = { viewModel.playFavorites(shuffle = true) },
                    onPlay = viewModel::playFavoriteAt,
                    onRemove = viewModel::removeFavorite
                )
            }
            item {
                PlaylistsSection(
                    playlists = uiState.playlists,
                    queueState = uiState.queue,
                    onCreate = viewModel::createPlaylist,
                    onPlay = { id -> viewModel.playPlaylist(id, shuffle = false) },
                    onShufflePlay = { id -> viewModel.playPlaylist(id, shuffle = true) },
                    onDelete = viewModel::deletePlaylist,
                    onRename = viewModel::renamePlaylist,
                    onManage = { managePlaylist = it },
                    onGenerateRandom = viewModel::generateRandomPlaylist
                )
            }
            item {
                UserFolderSection(
                    libraryState = uiState.library,
                    currentFileId = uiState.playbackState.currentFileId(),
                    onPickFolder = { folderLauncher.launch(uiState.library.folderTree.selectedFolder) },
                    onToggleFolder = viewModel::toggleFolder,
                    onPlay = viewModel::play,
                    onStop = viewModel::stopPlayback,
                    favoriteIds = favoriteIds,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onAddToPlaylist = { playlistDialogTarget = PlaylistTarget.Track(it) },
                    onFavoriteFolder = viewModel::addFolderToFavorites,
                    onAddFolderToPlaylist = { folder ->
                        playlistDialogTarget = PlaylistTarget.Folder(folder.uri, folder.name)
                    }
                )
            }
        }
    }

    playlistDialogTarget?.let { target ->
        PlaylistPickerDialog(
            playlists = uiState.playlists,
            targetLabel = target.displayName,
            onDismiss = { playlistDialogTarget = null },
            onConfirm = { playlistId, newName ->
                when (target) {
                    is PlaylistTarget.Track -> viewModel.addTrackToPlaylist(target.item, playlistId, newName)
                    is PlaylistTarget.Folder -> viewModel.addFolderToPlaylist(target.uri, playlistId, newName)
                }
                playlistDialogTarget = null
            }
        )
    }

    managePlaylist?.let { playlist ->
        PlaylistManageDialog(
            playlist = playlist,
            onDismiss = { managePlaylist = null },
            onRemoveTrack = { trackId ->
                viewModel.removeTrackFromPlaylist(playlist.id, trackId)
            },
            onRename = { newName ->
                viewModel.renamePlaylist(playlist.id, newName)
            },
            onPlayTrack = { index ->
                viewModel.playPlaylistTrack(playlist.id, index)
            }
        )
    }
}

@Composable
private fun DeviceSection(
    devices: List<MidiDeviceInfo>,
    session: MidiDeviceSession?,
    onConnect: (MidiDeviceInfo) -> Unit,
    onDisconnect: () -> Unit,
) {
    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Connected Piano",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            if (session != null) {
                Column {
                    Text(session.displayLabel(), fontWeight = FontWeight.Bold)
                    Text(session.info.connectionLabel(), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDisconnect) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Disconnect")
                    }
                }
            } else {
                Text("No MIDI device connected. Plug a USB cable or pair a BLE piano.")
            }
            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(16.dp))
            Text(
                "Available devices",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (devices.isEmpty()) {
                Text("No USB or BLE MIDI devices detected.")
            } else {
                Spacer(Modifier.height(8.dp))
                devices.forEach { info ->
                    DeviceRow(
                        info = info,
                        isConnected = session?.info?.id == info.id,
                        onConnect = onConnect
                    )
                }
            }
        }
    }
}

@Composable
private fun BleScanSection(
    scanState: BleScanUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BlePeripheralItem) -> Unit,
) {
    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bluetooth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("BLE MIDI discovery", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Scan nearby Bluetooth LE instruments that expose the MIDI service.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = if (scanState.isScanning) onStopScan else onStartScan) {
                    Text(if (scanState.isScanning) "Stop scan" else "Start BLE scan")
                }
                if (scanState.isScanning) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
            scanState.error?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            if (scanState.peripherals.isEmpty()) {
                Text(
                    if (scanState.isScanning) "Scanning..." else "No BLE peripherals found yet.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                scanState.peripherals.forEach { item ->
                    BlePeripheralRow(
                        item = item,
                        onConnect = onConnect
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun BlePeripheralRow(
    item: BlePeripheralItem,
    onConnect: (BlePeripheralItem) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Bluetooth, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name.ifBlank { "Unnamed" },
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${item.address} • RSSI ${item.rssi} dBm",
                style = MaterialTheme.typography.bodySmall
            )
            if (item.hasMidiService) {
                Text(
                    text = "BLE MIDI service detected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = "Name suggests MIDI support",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Button(onClick = { onConnect(item) }) {
            Text("Connect")
        }
    }
}

@Composable
private fun DeviceRow(
    info: MidiDeviceInfo,
    isConnected: Boolean,
    onConnect: (MidiDeviceInfo) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Bluetooth, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = info.displayName(),
                fontWeight = if (isConnected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(info.connectionLabel(), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Button(
            onClick = { onConnect(info) },
            enabled = !isConnected
        ) {
            Text(if (isConnected) "Connected" else "Connect")
        }
    }
}

@Composable
private fun PlaybackSection(
    playbackState: PlaybackEngineState,
    queueState: PlaybackQueueState,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Playback",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            when (playbackState) {
                PlaybackEngineState.Idle -> {
                    Text("Select a MIDI file or playlist to start playback.")
                }

                is PlaybackEngineState.Playing -> {
                    Text(playbackState.file.title, fontWeight = FontWeight.Bold)
                    Text(playbackState.file.subtitle, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("Queue: ${queueState.describe()}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    val duration = playbackState.durationMs.coerceAtLeast(1)
                    val progress = (playbackState.positionMs.toFloat() / duration.toFloat())
                        .coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = progress)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${formatTime(playbackState.positionMs)} / ${formatTime(playbackState.durationMs)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    PlaybackControls(onPrevious = onPrevious, onStop = onStop, onNext = onNext)
                }

                is PlaybackEngineState.Completed -> {
                    Text("Finished playing ${playbackState.file.title}")
                    Spacer(Modifier.height(8.dp))
                    PlaybackControls(onPrevious = onPrevious, onStop = onStop, onNext = onNext)
                }

                is PlaybackEngineState.Error -> {
                    Text(
                        text = "Playback error on ${playbackState.file.title}: ${playbackState.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    PlaybackControls(onPrevious = onPrevious, onStop = onStop, onNext = onNext)
                }
            }
        }
    }
}

@Composable
private fun PlaybackControls(
    onPrevious: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
        }
        IconButton(onClick = onStop) {
            Icon(Icons.Default.Stop, contentDescription = "Stop")
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next")
        }
    }
}

@Composable
private fun LibrarySection(
    title: String,
    subtitle: String,
    files: List<MidiFileItem>,
    currentFileId: String?,
    onPlay: (MidiFileItem) -> Unit,
    onStop: () -> Unit,
    favoriteIds: Set<String>,
    onToggleFavorite: (MidiFileItem) -> Unit,
    onAddToPlaylist: (MidiFileItem) -> Unit,
) {
    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LibraryMusic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (files.isEmpty()) {
                Text("No MIDI files available.")
            } else {
                files.forEachIndexed { index, item ->
                    MidiRow(
                        midi = item,
                        isActive = currentFileId == item.id,
                        onPlay = { onPlay(item) },
                        onStop = onStop,
                        isFavorite = favoriteIds.contains(item.id),
                        onToggleFavorite = { onToggleFavorite(item) },
                        onAddToPlaylist = { onAddToPlaylist(item) }
                    )
                    if (index != files.lastIndex) {
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun UserFolderSection(
    libraryState: LibraryUiState,
    currentFileId: String?,
    onPickFolder: () -> Unit,
    onToggleFolder: (Uri) -> Unit,
    onPlay: (MidiFileItem) -> Unit,
    onStop: () -> Unit,
    favoriteIds: Set<String>,
    onToggleFavorite: (MidiFileItem) -> Unit,
    onAddToPlaylist: (MidiFileItem) -> Unit,
    onFavoriteFolder: (Uri) -> Unit,
    onAddFolderToPlaylist: (FolderNodeState) -> Unit,
) {
    val treeState = libraryState.folderTree
    val rows = treeState.flatten()
    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("User folder", fontWeight = FontWeight.SemiBold)
                    val folderText = treeState.root?.name
                        ?: treeState.selectedFolder?.path
                        ?: "No folder selected"
                    Text(folderText, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = onPickFolder) {
                    Text(if (treeState.selectedFolder == null) "Choose folder" else "Change folder")
                }
            }
            Spacer(Modifier.height(16.dp))
            when {
                treeState.selectedFolder == null -> {
                    Text("Pick a folder to browse your MIDI collection.")
                }

                treeState.globalError != null -> {
                    Text(
                        treeState.globalError,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                treeState.root == null -> {
                    Text("Unable to access the selected folder. Try choosing it again.")
                }

                rows.isEmpty() -> {
                    Text("Tap the folder row below to expand and load its contents.")
                }

                else -> {
                    rows.forEach { row ->
                        when (row) {
                            is FolderTreeRow.DirectoryRow -> {
                                DirectoryTreeRow(
                                    row = row,
                                    onToggle = onToggleFolder,
                                    onFavoriteFolder = onFavoriteFolder,
                                    onAddFolderToPlaylist = onAddFolderToPlaylist
                                )
                            }

                            is FolderTreeRow.FileRow -> {
                                MidiTreeFileRow(
                                    row = row,
                                    isActive = currentFileId == row.file.id,
                                    onPlay = { onPlay(row.file) },
                                    onStop = onStop,
                                    isFavorite = favoriteIds.contains(row.file.id),
                                    onToggleFavorite = { onToggleFavorite(row.file) },
                                    onAddToPlaylist = { onAddToPlaylist(row.file) }
                                )
                            }

                            is FolderTreeRow.PlaceholderRow -> {
                                FolderPlaceholderRow(row)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesSection(
    favorites: List<TrackReference>,
    currentFileId: String?,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onPlay: (Int) -> Unit,
    onRemove: (String) -> Unit,
) {
    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Favorite, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Favorites", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            if (favorites.isEmpty()) {
                Text("Add some songs to your favorite list to access them quickly.")
            } else {
                Row {
                    Button(onClick = onPlayAll) {
                        Icon(Icons.Default.QueueMusic, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Play all")
                    }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = onShuffle) {
                        Icon(Icons.Default.Shuffle, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Shuffle")
                    }
                }
                Spacer(Modifier.height(8.dp))
                favorites.forEachIndexed { index, track ->
                    FavoriteRow(
                        reference = track,
                        isActive = currentFileId == track.id,
                        onPlay = { onPlay(index) },
                        onRemove = { onRemove(track.id) }
                    )
                    if (index != favorites.lastIndex) {
                        Divider(modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteRow(
    reference: TrackReference,
    isActive: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(reference.title, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
            Text(reference.displaySubtitle(), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        IconButton(onClick = onPlay) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play favorite")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Remove favorite")
        }
    }
}

@Composable
private fun PlaylistsSection(
    playlists: List<MidiPlaylist>,
    queueState: PlaybackQueueState,
    onCreate: (String) -> Unit,
    onPlay: (String) -> Unit,
    onShufflePlay: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onManage: (MidiPlaylist) -> Unit,
    onGenerateRandom: () -> Unit,
) {
    var newPlaylistName by remember { mutableStateOf("") }
    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QueueMusic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Playlists", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newPlaylistName,
                onValueChange = { newPlaylistName = it },
                label = { Text("New playlist name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    onClick = {
                        onCreate(newPlaylistName)
                        newPlaylistName = ""
                    },
                    enabled = newPlaylistName.isNotBlank()
                ) {
                    Text("Create playlist")
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onGenerateRandom) {
                    Icon(Icons.Default.Shuffle, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Random 50")
                }
            }
            Spacer(Modifier.height(12.dp))
            if (playlists.isEmpty()) {
                Text("Create a playlist or add songs from the library.")
            } else {
                playlists.forEach { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        isActive = (queueState.mode as? QueueMode.Playlist)?.playlistId == playlist.id,
                        onPlay = { onPlay(playlist.id) },
                        onShuffle = { onShufflePlay(playlist.id) },
                        onDelete = { onDelete(playlist.id) },
                        onManage = { onManage(playlist) }
                    )
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: MidiPlaylist,
    isActive: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDelete: () -> Unit,
    onManage: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold
                )
                Text("${playlist.tracks.size} tracks", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play playlist")
            }
            IconButton(onClick = onShuffle) {
                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle")
            }
            IconButton(onClick = onManage) {
                Icon(Icons.Default.Edit, contentDescription = "Manage")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun DirectoryTreeRow(
    row: FolderTreeRow.DirectoryRow,
    onToggle: (Uri) -> Unit,
    onFavoriteFolder: (Uri) -> Unit,
    onAddFolderToPlaylist: (FolderNodeState) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(row.directory.uri) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(TreeIndent * row.depth))
            Icon(
                imageVector = if (row.isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null
            )
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.Folder, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(row.directory.name, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onFavoriteFolder(row.directory.uri) }) {
                Icon(Icons.Default.FavoriteBorder, contentDescription = "Favorite folder")
            }
            IconButton(onClick = { onAddFolderToPlaylist(row.directory) }) {
                Icon(Icons.Default.PlaylistAdd, contentDescription = "Add folder to playlist")
            }
            when {
                row.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }

                row.isEmpty && row.isExpanded -> {
                    Text("Empty", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        row.error?.let { error ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(TreeIndent * (row.depth + 1)))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun MidiTreeFileRow(
    row: FolderTreeRow.FileRow,
    isActive: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(TreeIndent * row.depth))
        Icon(Icons.Default.MusicNote, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.file.title,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = row.file.subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Toggle favorite"
            )
        }
        IconButton(onClick = onAddToPlaylist) {
            Icon(Icons.Default.PlaylistAdd, contentDescription = "Add to playlist")
        }
        IconButton(onClick = if (isActive) onStop else onPlay) {
            Icon(
                imageVector = if (isActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (isActive) "Stop" else "Play"
            )
        }
    }
}

@Composable
private fun FolderPlaceholderRow(row: FolderTreeRow.PlaceholderRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(TreeIndent * row.depth))
        Text(
            row.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun MidiRow(
    midi: MidiFileItem,
    isActive: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                midi.title,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
            )
        },
        supportingContent = { Text(midi.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite"
                    )
                }
                IconButton(onClick = onAddToPlaylist) {
                    Icon(Icons.Default.PlaylistAdd, contentDescription = "Add to playlist")
                }
                IconButton(onClick = if (isActive) onStop else onPlay) {
                    Icon(
                        imageVector = if (isActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isActive) "Stop" else "Play"
                    )
                }
            }
        }
    )
}

private val TreeIndent = 18.dp

private fun MidiDeviceSession.displayLabel(): String = label ?: info.displayName()

private fun PlaybackEngineState.currentFileId(): String? = when (this) {
    is PlaybackEngineState.Playing -> file.id
    is PlaybackEngineState.Completed -> file.id
    is PlaybackEngineState.Error -> file.id
    PlaybackEngineState.Idle -> null
}

private fun MidiDeviceInfo.displayName(): String {
    val props = properties
    val manufacturer = props.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
    val product = props.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
    val productLabel = listOfNotNull(manufacturer, product)
        .joinToString(" ")
        .ifBlank { product ?: manufacturer }
    val reportedName = props.getString(MidiDeviceInfo.PROPERTY_NAME)
    val bluetoothName = props.getBluetoothDevice()?.name
    return listOfNotNull(reportedName, bluetoothName, productLabel)
        .firstOrNull { !it.isNullOrBlank() }
        ?: "MIDI device $id"
}

private fun MidiDeviceInfo.connectionLabel(): String {
    val typeLabel = when (type) {
        MidiDeviceInfo.TYPE_BLUETOOTH -> "Bluetooth LE"
        MidiDeviceInfo.TYPE_USB -> "USB"
        MidiDeviceInfo.TYPE_VIRTUAL -> "Virtual"
        else -> "External"
    }
    val ports = inputPortCount
    return "$typeLabel • $ports input port${if (ports == 1) "" else "s"}"
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun PlaylistPickerDialog(
    playlists: List<MidiPlaylist>,
    targetLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String?, String?) -> Unit,
) {
    var selectedPlaylist by remember(playlists) { mutableStateOf<String?>(playlists.firstOrNull()?.id) }
    var newPlaylistName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add \"$targetLabel\" to playlist") },
        text = {
            Column {
                Text(
                    "Choose an existing playlist or create a new one.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                playlists.forEach { playlist ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedPlaylist == playlist.id,
                            onClick = {
                                selectedPlaylist = playlist.id
                                newPlaylistName = ""
                            }
                        )
                        Text(playlist.name)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = {
                        newPlaylistName = it
                        if (it.isNotBlank()) {
                            selectedPlaylist = null
                        }
                    },
                    label = { Text("New playlist name") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(selectedPlaylist, newPlaylistName.takeIf { it.isNotBlank() })
            }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun PlaylistManageDialog(
    playlist: MidiPlaylist,
    onDismiss: () -> Unit,
    onRemoveTrack: (String) -> Unit,
    onRename: (String) -> Unit,
    onPlayTrack: (Int) -> Unit,
) {
    var tempName by remember { mutableStateOf(playlist.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage ${playlist.name}") },
        text = {
            Column {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    label = { Text("Playlist name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onRename(tempName) },
                    enabled = tempName.isNotBlank() && tempName != playlist.name
                ) {
                    Text("Save name")
                }
                Spacer(Modifier.height(12.dp))
                if (playlist.tracks.isEmpty()) {
                    Text("This playlist has no tracks.")
                } else {
                    playlist.tracks.forEachIndexed { index, track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(track.title, fontWeight = FontWeight.SemiBold)
                                Text(track.displaySubtitle(), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            IconButton(onClick = { onPlayTrack(index) }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Play track")
                            }
                            IconButton(onClick = { onRemoveTrack(track.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

private sealed interface PlaylistTarget {
    val displayName: String

    data class Track(val item: MidiFileItem) : PlaylistTarget {
        override val displayName: String = item.title
    }

    data class Folder(val uri: Uri, val name: String) : PlaylistTarget {
        override val displayName: String = name
    }
}

private fun PlaybackQueueState.describe(): String = when (val mode = mode) {
    QueueMode.Idle -> "Idle"
    QueueMode.Favorites -> "Favorites"
    is QueueMode.Playlist -> "Playlist"
    is QueueMode.Single -> mode.title ?: "Single"
}

private fun TrackReference.displaySubtitle(): String {
    return when (val src = source) {
        is TrackReferenceSource.Asset -> "Asset • ${src.assetPath.substringAfterLast('/')}"
        is TrackReferenceSource.Document -> Uri.parse(src.uri).lastPathSegment ?: "User file"
    }
}

private fun Bundle.getBluetoothDevice(): BluetoothDevice? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable(MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE) as? BluetoothDevice
    }
}
