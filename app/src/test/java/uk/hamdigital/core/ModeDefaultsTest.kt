package uk.hamdigital.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.hamdigital.rig.RigMode

class ModeDefaultsTest {
    @Test
    fun everyModeHasAUsableDefault() {
        val expected = mapOf(
            Mode.FT8 to 14074.0,
            Mode.FT4 to 14080.0,
            Mode.FT2 to 14084.0,
            Mode.WSPR to 14095.6,
            Mode.JS8 to 14078.0,
            Mode.RTTY to 14080.0,
            Mode.JTTY to 14090.0,
            Mode.PSK31 to 14070.0,
            Mode.OLIVIA to 14071.0,
            Mode.CW to 14060.0,
            Mode.SSTV to 14230.0,
            Mode.DSTAR to 145670.0,
            Mode.FREEDV to 14236.0,
            Mode.WEFAX to 3853.1,
            Mode.APRS to 144640.0,
            Mode.FREEDATA to 14093.0,
        )
        Mode.entries.forEach { mode ->
            assertTrue(mode.name, mode.dialsKHz.isNotEmpty())
            assertEquals(mode.name, expected.getValue(mode), mode.defaultDialKHz(), 0.0)
        }
    }

    @Test
    fun aprsUsesChinaCallingFrequencyInFmData() {
        assertEquals(144640.0, Mode.APRS.defaultDialKHz(), 0.0)
        assertEquals(RigMode.FM_D, Mode.APRS.rigMode(144640.0))
        assertEquals("2 m", Mode.APRS.dialsKHz.first().first)
    }
}
