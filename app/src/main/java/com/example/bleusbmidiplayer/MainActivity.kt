package com.example.bleusbmidiplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.midi.MidiDeviceInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bleusbmidiplayer.midi.MidiDeviceSession
import com.example.bleusbmidiplayer.midi.MidiFileItem
import com.example.bleusbmidiplayer.midi.PlaybackEngineState
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val missing = REQUIRED_BLUETOOTH_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    companion object {
        private val REQUIRED_BLUETOOTH_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MidiPlayerApp(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
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
            FloatingActionButton(onClick = { folderLauncher.launch(uiState.library.selectedFolder) }) {
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
                PlaybackSection(
                    playbackState = uiState.playbackState,
                    onStop = viewModel::stopPlayback
                )
            }
            item {
                LibrarySection(
                    title = "Bundled songs",
                    subtitle = "MIDI files stored in assets/midi",
                    files = uiState.library.bundled,
                    currentFileId = uiState.playbackState.currentFileId(),
                    onPlay = viewModel::play,
                    onStop = viewModel::stopPlayback
                )
            }
            item {
                UserFolderSection(
                    libraryState = uiState.library,
                    currentFileId = uiState.playbackState.currentFileId(),
                    onPickFolder = { folderLauncher.launch(uiState.library.selectedFolder) },
                    onPlay = viewModel::play,
                    onStop = viewModel::stopPlayback
                )
            }
        }
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
                    Text(session.info.displayName(), fontWeight = FontWeight.Bold)
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
    onStop: () -> Unit,
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
                    Text("Select a MIDI file to start playback.")
                }

                is PlaybackEngineState.Playing -> {
                    Text(playbackState.file.title, fontWeight = FontWeight.Bold)
                    Text(playbackState.file.subtitle, style = MaterialTheme.typography.bodySmall)
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
                    Button(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop")
                    }
                }

                is PlaybackEngineState.Completed -> {
                    Text("Finished playing ${playbackState.file.title}")
                }

                is PlaybackEngineState.Error -> {
                    Text(
                        text = "Playback error on ${playbackState.file.title}: ${playbackState.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onStop) {
                        Text("Dismiss")
                    }
                }
            }
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
                        onStop = onStop
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
    onPlay: (MidiFileItem) -> Unit,
    onStop: () -> Unit,
) {
    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("User folder", fontWeight = FontWeight.SemiBold)
                    val folderText = libraryState.selectedFolder?.path ?: "No folder selected"
                    Text(folderText, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = onPickFolder) {
                    Text(if (libraryState.selectedFolder == null) "Choose folder" else "Change folder")
                }
                if (libraryState.isLoading) {
                    Spacer(Modifier.width(12.dp))
                    LinearProgressIndicator(modifier = Modifier.width(72.dp))
                }
            }
            libraryState.error?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            if (libraryState.external.isEmpty() && libraryState.selectedFolder != null && !libraryState.isLoading) {
                Text("No MIDI files found in the selected folder.")
            } else {
                libraryState.external.forEachIndexed { index, item ->
                    MidiRow(
                        midi = item,
                        isActive = currentFileId == item.id,
                        onPlay = { onPlay(item) },
                        onStop = onStop
                    )
                    if (index != libraryState.external.lastIndex) {
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MidiRow(
    midi: MidiFileItem,
    isActive: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
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
            if (isActive) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            } else {
                IconButton(onClick = onPlay) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                }
            }
        }
    )
}

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
    val name = listOfNotNull(manufacturer, product).joinToString(" ").ifBlank { product ?: manufacturer }
    return name ?: "MIDI device ${id}"
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
