package uk.hamdigital.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BandPlanTest {
    @Test fun amateurBandsUseExpectedSidebandAndCarrierModes() {
        assertEquals(BandPlan.LSB, BandPlan.modeFor(1_840_000L))
        assertEquals(BandPlan.LSB, BandPlan.modeFor(3_850_000L))
        assertEquals(BandPlan.USB, BandPlan.modeFor(5_351_500L))
        assertEquals(BandPlan.LSB, BandPlan.modeFor(7_050_000L))
        assertEquals(BandPlan.CW, BandPlan.modeFor(10_120_000L))
        assertEquals(BandPlan.USB, BandPlan.modeFor(14_200_000L))
        assertEquals(BandPlan.USB, BandPlan.modeFor(18_130_000L))
        assertEquals(BandPlan.USB, BandPlan.modeFor(21_200_000L))
        assertEquals(BandPlan.USB, BandPlan.modeFor(24_950_000L))
        assertEquals(BandPlan.USB, BandPlan.modeFor(28_500_000L))
        assertEquals(BandPlan.USB, BandPlan.modeFor(50_150_000L))
        assertEquals(BandPlan.WFM, BandPlan.modeFor(88_500_000L))
        assertEquals(BandPlan.AM, BandPlan.modeFor(118_000_000L))
        assertEquals(BandPlan.FM, BandPlan.modeFor(145_500_000L))
        assertEquals(BandPlan.FM, BandPlan.modeFor(438_500_000L))
    }

    @Test fun squelchDefaultsOnlyOnVhfAndUhf() {
        assertFalse(BandPlan.usesSquelch(14_200_000L))
        assertFalse(BandPlan.usesSquelch(50_150_000L))
        assertTrue(BandPlan.usesSquelch(118_000_000L))
        assertTrue(BandPlan.usesSquelch(135_000_000L))
        assertTrue(BandPlan.usesSquelch(145_500_000L))
        assertTrue(BandPlan.usesSquelch(438_500_000L))
        assertEquals(0, BandPlan.squelchFor(14_200_000L))
        assertEquals(BandPlan.VHF_UHF_SQUELCH, BandPlan.squelchFor(145_500_000L))
    }

    @Test fun labelsCoverEveryPreset() {
        BandPlan.presets.forEach { preset ->
            assertEquals(preset.label, BandPlan.labelFor(preset.hz))
        }
    }

    @Test fun everyPresetFrequencyUsesItsDeclaredModeAndRange() {
        BandPlan.presets.forEach { preset ->
            assertEquals(preset.modeCode, BandPlan.modeFor(preset.hz))
            assertEquals(preset.squelchRaw, BandPlan.squelchFor(preset.hz))
        }
        assertEquals(0, BandPlan.DEFAULT_PREAMP)
        assertEquals(1, BandPlan.presets.first { it.label == "航空" }.preampRaw)
        assertEquals(1, BandPlan.presets.first { it.label == "2m" }.preampRaw)
        assertEquals(1, BandPlan.presets.first { it.label == "70cm" }.preampRaw)
        assertTrue(BandPlan.presets.filterNot { it.label in setOf("航空", "2m", "70cm") }.all { it.preampRaw == 0 })
    }
}
