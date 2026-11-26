package uk.hamdigital.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PttHoldTest {
    @Test fun successfulPressReturnsExactSessionToken() {
        assertEquals(7L, beginMomentaryPtt(start = { true }, currentSession = { 7L }))
    }

    @Test fun failedStartReturnsZeroSoReleaseStillForcesOff() {
        assertEquals(0L, beginMomentaryPtt(start = { false }, currentSession = { 7L }))
    }

    @Test fun cancelInvalidatesAPendingGenerationSnapshot() {
        val generation = uk.hamdigital.core.TxControl.snapshot()
        uk.hamdigital.core.TxControl.cancelTx()
        org.junit.Assert.assertFalse(uk.hamdigital.core.TxControl.isCurrent(generation))
    }
}
