package uk.hamdigital.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainModesTest {
    @Test fun modePickerIncludesTwelveStandardModes() {
        assertEquals(12, MAIN_MODES.size)
        assertTrue(MAIN_MODES.chunked(3).all { it.size == 3 })
    }

    @Test fun decoderOnlyModesAreNotPresent() {
        val labels = MAIN_MODES.map { it.label }.toSet()
        listOf("DMR", "YSF", "P25", "NXDN", "M17", "dPMR").forEach { mode ->
            assertTrue(mode !in labels)
        }
    }
}
