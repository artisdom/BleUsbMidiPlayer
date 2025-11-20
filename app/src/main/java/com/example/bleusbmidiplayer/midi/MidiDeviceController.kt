package com.example.bleusbmidiplayer.midi

import android.bluetooth.BluetoothDevice
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiInputPort
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MidiDeviceController(
    private val midiManager: MidiManager,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val _devices = MutableStateFlow<List<MidiDeviceInfo>>(midiManager.devices.toList())
    val devices: StateFlow<List<MidiDeviceInfo>> = _devices
    private val _session = MutableStateFlow<MidiDeviceSession?>(null)
    val session: StateFlow<MidiDeviceSession?> = _session

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            _devices.value = midiManager.devices.toList()
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            _devices.value = midiManager.devices.toList()
            val active = _session.value
            if (active?.info?.id == device.id) {
                closeActiveSession()
            }
        }
    }

    init {
        midiManager.registerDeviceCallback(callback, handler)
    }

    fun refreshDevices() {
        _devices.value = midiManager.devices.toList()
    }

    fun openDevice(deviceInfo: MidiDeviceInfo, onResult: (Result<MidiDeviceSession>) -> Unit) {
        midiManager.openDevice(deviceInfo, { device ->
            if (device == null) {
                onResult(Result.failure(IllegalStateException("Unable to open device")))
                return@openDevice
            }
            val ports = device.info.inputPortCount
            if (ports <= 0) {
                device.close()
                onResult(Result.failure(IllegalStateException("Selected device has no input ports")))
                return@openDevice
            }
            val inputPort = device.openInputPort(0)
            if (inputPort == null) {
                device.close()
                onResult(Result.failure(IllegalStateException("Cannot open input port")))
                return@openDevice
            }
            val session = MidiDeviceSession(deviceInfo, device, inputPort)
            closeActiveSession()
            _session.value = session
            onResult(Result.success(session))
        }, handler)
    }

    fun connectBluetoothDevice(
        device: BluetoothDevice,
        label: String? = null,
        onResult: (Result<MidiDeviceSession>) -> Unit,
    ) {
        midiManager.openBluetoothDevice(device, { midiDevice ->
            if (midiDevice == null) {
                onResult(Result.failure(IllegalStateException("Unable to open BLE device")))
                return@openBluetoothDevice
            }
            val ports = midiDevice.info.inputPortCount
            if (ports <= 0) {
                midiDevice.close()
                onResult(Result.failure(IllegalStateException("BLE device has no input ports")))
                return@openBluetoothDevice
            }
            val inputPort = midiDevice.openInputPort(0)
            if (inputPort == null) {
                midiDevice.close()
                onResult(Result.failure(IllegalStateException("Cannot open BLE input port")))
                return@openBluetoothDevice
            }
            val session = MidiDeviceSession(
                info = midiDevice.info,
                device = midiDevice,
                outputPort = inputPort,
                label = label ?: device.name ?: midiDevice.info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            )
            closeActiveSession()
            _session.value = session
            onResult(Result.success(session))
        }, handler)
    }

    fun disconnect() {
        closeActiveSession()
    }

    fun dispose() {
        disconnect()
        midiManager.unregisterDeviceCallback(callback)
    }

    private fun closeActiveSession() {
        _session.value?.let {
            try {
                it.outputPort.close()
            } catch (_: Throwable) {
            }
            try {
                it.device.close()
            } catch (_: Throwable) {
            }
        }
        _session.value = null
    }
}

data class MidiDeviceSession(
    val info: MidiDeviceInfo,
    val device: MidiDevice,
    val outputPort: MidiInputPort,
    val label: String? = null,
)
