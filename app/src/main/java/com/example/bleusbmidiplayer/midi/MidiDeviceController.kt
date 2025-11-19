package com.example.bleusbmidiplayer.midi

import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
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
            val ports = device.info.outputPortCount
            if (ports <= 0) {
                device.close()
                onResult(Result.failure(IllegalStateException("Selected device has no output ports")))
                return@openDevice
            }
            val outputPort = device.openOutputPort(0)
            if (outputPort == null) {
                device.close()
                onResult(Result.failure(IllegalStateException("Cannot open output port")))
                return@openDevice
            }
            val session = MidiDeviceSession(deviceInfo, device, outputPort)
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
    val outputPort: MidiOutputPort,
)
