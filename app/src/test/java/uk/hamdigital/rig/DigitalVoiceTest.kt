package uk.hamdigital.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DigitalVoiceTest {
    private fun frame(mode: Int, row: String, phase: Int = 2): List<Int> {
        val body = row.map { it.code } + List(48 - row.length) { 0 }
        return listOf(0xf0, 0x01, 'A'.code, '7'.code, 'D'.code, 'V'.code, 4, mode, phase, row.length) + body
    }

    @Test
    fun dmrMetadata() {
        val info = DigitalVoiceDecoder.parseFrame(frame(1, "GRP CC 7 TS 2 ID 1234567 TG 9"))!!
        assertEquals(DigitalVoiceProtocol.DMR, info.protocol)
        assertEquals(DigitalVoicePhase.RECEIVING, info.phase)
        assertEquals(DigitalVoiceCallKind.GROUP, info.callKind)
        assertEquals(7, info.colorCode)
        assertEquals(2, info.slot)
        assertEquals(1234567L, info.sourceId)
        assertEquals(9L, info.targetId)
    }

    @Test
    fun ysfMetadata() {
        val info = DigitalVoiceDecoder.parseFrame(frame(2, "GRP DN DG-ID 00 RX BI1BIF"))!!
        assertEquals(DigitalVoiceProtocol.YSF, info.protocol)
        assertEquals(DigitalVoiceCallKind.GROUP, info.callKind)
        assertEquals(0, info.colorCode)
        assertEquals("BI1BIF", info.callsign)
    }

    @Test
    fun p25Metadata() {
        val info = DigitalVoiceDecoder.parseFrame(frame(3, "GRP NAC 293 ID 12345 TG 99"))!!
        assertEquals(DigitalVoiceProtocol.P25, info.protocol)
        assertEquals(DigitalVoiceCallKind.GROUP, info.callKind)
        assertEquals(0x293, info.colorCode)
        assertEquals(12345L, info.sourceId)
        assertEquals(99L, info.targetId)
    }

    @Test
    fun blankRowIsSearch() {
        val info = DigitalVoiceDecoder.parseFrame(frame(1, "", phase = 0))!!
        assertEquals(DigitalVoicePhase.SEARCH, info.phase)
        assertNull(info.colorCode)
        assertEquals("", info.row)
    }

    @Test
    fun rejectsUnknownFrame() {
        assertNull(DigitalVoiceDecoder.parseFrame(listOf(0x1a, 0x04, 1, 2, 3)))
        assertNull(DigitalVoiceDecoder.parseFrame(frame(9, "GRP CC 1")))
    }
}
