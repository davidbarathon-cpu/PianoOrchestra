package com.pianoorchestra.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Le piano côté tablette : trouve le clavier branché en USB (ou déjà connu en Bluetooth),
 * s'y connecte tout seul, et redistribue ses messages MIDI aux écouteurs.
 * Se reconnecte quand on débranche puis rebranche le câble.
 */
class MidiInput(context: Context) {

    fun interface Listener {
        fun onMidi(status: Int, d1: Int, d2: Int)
    }

    private val manager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private val _deviceName = MutableStateFlow<String?>(null)
    /** Le nom du piano connecté, ou null. */
    val deviceName: StateFlow<String?> = _deviceName

    val available: Boolean get() = manager != null

    private var device: MidiDevice? = null
    private var port: MidiOutputPort? = null
    private var info: MidiDeviceInfo? = null

    private val parser = MidiStreamParser { s, a, b -> for (l in listeners) l.onMidi(s, a, b) }

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            parser.feed(msg, offset, count)
        }
    }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            if (info == null) tryOpen(device)
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            if (device.id == info?.id) {
                close()
                pickAny()
            }
        }
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    @Suppress("DEPRECATION")
    fun start() {
        val m = manager ?: return
        m.registerDeviceCallback(callback, handler)
        pickAny()
    }

    @Suppress("DEPRECATION")
    private fun pickAny() {
        val m = manager ?: return
        val candidates = m.devices
            .filter { it.outputPortCount > 0 && it.type != MidiDeviceInfo.TYPE_VIRTUAL }
            .sortedBy { if (it.type == MidiDeviceInfo.TYPE_USB) 0 else 1 }
        candidates.firstOrNull()?.let { tryOpen(it) }
    }

    private fun tryOpen(target: MidiDeviceInfo) {
        val m = manager ?: return
        if (target.outputPortCount == 0 || target.type == MidiDeviceInfo.TYPE_VIRTUAL) return
        m.openDevice(target, { dev ->
            if (dev == null) return@openDevice
            val p = dev.openOutputPort(0)
            if (p == null) {
                dev.close()
                return@openDevice
            }
            p.connect(receiver)
            device = dev
            port = p
            info = target
            _deviceName.value = nameOf(target)
            Log.i("PianoOrchestra", "MIDI connecté : ${nameOf(target)}")
        }, handler)
    }

    private fun close() {
        try { port?.disconnect(receiver) } catch (_: Exception) {}
        try { port?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        port = null
        device = null
        info = null
        _deviceName.value = null
    }

    private fun nameOf(i: MidiDeviceInfo): String {
        val p = i.properties
        return p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: p.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
            ?: "MIDI"
    }
}
