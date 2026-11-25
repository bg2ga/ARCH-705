// Only one mode transmits: opening a mode's page stops every other mode's sending - an FT8 / FT4 contact, the WSPR
// beacon, JS8 messages still queued, an RTTY / PSK31 message going out, the CW keyer - and closing the app stops them
// all (asked for by the user, 0.10.4: a WSPR beacon carried on after the app was closed and while other modes were
// opened). Receiving needs nothing here: each page's decoder only gets audio while its page is open.
package uk.hamdigital.core

import uk.hamdigital.audio.Transmitter
import uk.hamdigital.rig.Ic705
import kotlinx.coroutines.flow.MutableStateFlow

object PttLock {
    val locked = MutableStateFlow(true)
    fun toggle() { if (locked.value) unlock() else lock() }
    fun unlock() { locked.value = false }
    fun lock() {
        if (!locked.value) {
            locked.value = true
            uk.hamdigital.audio.VoiceTx.stop()
            TxControl.stopAll()
        }
    }
}

object TxControl {
    @Volatile var licensed = false
    @Volatile var txLevel = 0.30f
    @Volatile private var generation = 0L

    fun beginTx(): Long = synchronized(this) { generation++; generation }
    fun cancelTx(): Long = synchronized(this) { generation++; generation }
    fun isCurrent(token: Long): Boolean = token == generation

    /** Stop the sending of every mode but [keep] (null: all of them). */
    fun stopOthers(keep: Mode?) {
        cancelTx()
        if (keep != Mode.FT8 && FtQso.FT8.state.value.enabled) FtQso.FT8.halt()       // an FT8 contact / CQ
        if (keep != Mode.FT4 && FtQso.FT4.state.value.enabled) FtQso.FT4.halt()       // FT4
        if (keep != Mode.FT2 && FtQso.FT2.state.value.enabled) FtQso.FT2.halt()       // FT2
        if (keep != Mode.WSPR && WsprBeacon.state.value.enabled) WsprBeacon.enable(false) // the beacon
        if (keep != Mode.JS8 && Js8Tx.queued.value > 0) Js8Tx.halt()                  // JS8 frames still to go
        if (keep == null) Transmitter.halt()
        else if (Transmitter.on.value || Transmitter.threadRunning || Transmitter.owner != keep.name) Transmitter.halt() // whatever is on the air or queued
        if (keep != Mode.CW) Ic705.stopCw()                                           // the IC-705's keyer (ignored when it is not sending)
    }

    /** Stop everything (the app is closing or going to the background). */
    fun stopAll() = stopOthers(null)
}

