package uk.hamdigital.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlChannelCodecTest {
    @Test
    fun parsesLegacyFourFieldChannel() {
        val channel = parseChannels("20m|14270000|1|false").single()
        assertEquals(14_270_000L, channel.hz)
        assertEquals(0, channel.toneMode)
        assertEquals(0L, channel.offsetHz)
    }

    @Test
    fun migratesLegacyNineFieldChannelWithoutShift() {
        val channel = parseChannels("UHF|439000000|5|false|3|885|23|1|5000000").single()
        assertEquals(3, channel.toneMode)
        assertEquals(885, channel.toneTenths)
        assertEquals(0, channel.tsqlTenths)
        assertEquals(23, channel.dtcsCode)
        assertEquals(1, channel.split)
        assertEquals(5_000_000L, channel.offsetHz)
    }

    @Test
    fun roundTripsCurrentTenFieldChannel() {
        val original = SavedChannel("Call", 145_500_000L, 5, false, 2, 1_000, 1_035, 754, 2, 600_000L)
        assertEquals(original, parseChannels(encodeChannels(listOf(original))).single())
    }
}
