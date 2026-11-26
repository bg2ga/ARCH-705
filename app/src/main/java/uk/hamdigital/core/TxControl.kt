package uk.hamdigital.core

import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.rig.Ic705

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
    fun snapshot(): Long = synchronized(this) { generation }
    fun beginTxIfCurrent(expected: Long): Long? = synchronized(this) { if (expected >= 0L && expected != generation) null else { generation++; generation } }
    fun isCurrent(token: Long): Boolean = token == generation

    fun stopAll() {
        cancelTx()
        Transmitter.halt()
        Ic705.stopCw()
    }
}
