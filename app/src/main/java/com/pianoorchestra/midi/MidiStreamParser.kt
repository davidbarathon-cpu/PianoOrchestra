package com.pianoorchestra.midi

/**
 * Découpe un flux d'octets MIDI en messages (statut, donnée 1, donnée 2).
 * Gère le « running status » (statut implicite), ignore l'horloge, le sysex et les messages système.
 */
class MidiStreamParser(private val emit: (status: Int, d1: Int, d2: Int) -> Unit) {
    private var status = 0
    private var need = 0
    private var d1 = -1
    private var inSysex = false

    fun feed(bytes: ByteArray, offset: Int, count: Int) {
        for (i in offset until offset + count) byte(bytes[i].toInt() and 0xFF)
    }

    private fun byte(x: Int) {
        if (x >= 0xF8) return                         // horloge, start/stop… : ignorés
        if (x == 0xF0) { inSysex = true; return }
        if (x == 0xF7) { inSysex = false; return }
        if (inSysex) {
            if (x and 0x80 == 0) return
            inSysex = false
        }
        if (x and 0x80 != 0) {
            if (x >= 0xF0) { status = 0; return }     // messages système communs : ignorés
            status = x
            d1 = -1
            need = if ((x and 0xF0) == 0xC0 || (x and 0xF0) == 0xD0) 1 else 2
            return
        }
        if (status == 0) return
        if (need == 1) {
            emit(status, x, 0)
        } else if (d1 < 0) {
            d1 = x
        } else {
            emit(status, d1, x)
            d1 = -1
        }
    }
}
