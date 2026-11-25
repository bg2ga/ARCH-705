package uk.hamdigital.rig

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Ic705CodecTest {
    @Test fun levelEncodingMatchesIcomTwoByteBcd() {
        assertArrayEquals(intArrayOf(0x01, 0x00), Ic705Codec.levelBcd(100))
        assertArrayEquals(intArrayOf(0x01, 0x28), Ic705Codec.levelBcd(128))
        assertArrayEquals(intArrayOf(0x02, 0x55), Ic705Codec.levelBcd(255))
    }

    @Test fun levelDecodingHandlesOneAndTwoByteReplies() {
        assertEquals(100, Ic705Codec.levelValue(listOf(0x01, 0x00)))
        assertEquals(128, Ic705Codec.levelValue(listOf(0x01, 0x28)))
        assertEquals(0, Ic705Codec.levelValue(listOf(0x00)))
    }

    @Test fun filterWidthTableAndLimits() {
        assertEquals(50, Ic705Codec.filterWidth("USB", 0))
        assertEquals(550, Ic705Codec.filterWidth("USB", 10))
        assertEquals(700, Ic705Codec.filterWidth("CW", 11))
        assertEquals(3600, Ic705Codec.filterWidth("USB", 99))
        assertEquals(200, Ic705Codec.filterWidth("AM", 0))
        assertEquals(10000, Ic705Codec.filterWidth("AM", 99))
        assertEquals(31, Ic705Codec.filterLimit("RTTY"))
    }

    @Test fun frequencyBcdRoundTripsEveryScopeSpan() {
        listOf(2_500L, 5_000L, 10_000L, 25_000L, 50_000L, 100_000L, 250_000L, 500_000L,
            14_074_000L, 14_200_000L, 145_500_000L, 438_500_000L).forEach { hz ->
            assertEquals(hz, Ic705Codec.frequencyValue(Ic705Codec.frequencyBcd(hz).toList()))
        }
    }

    @Test fun percentAndNrScalesAreBounded() {
        assertEquals(0, Ic705Codec.levelPercent(0))
        assertEquals(100, Ic705Codec.levelPercent(255))
        assertEquals(0, Ic705Codec.rawFromPercent(0))
        assertEquals(255, Ic705Codec.rawFromPercent(100))
        assertEquals(0, Ic705Codec.nrDisplay(0))
        assertEquals(9, Ic705Codec.nrDisplay(255))
        assertEquals(0, Ic705Codec.nrRaw(0))
        assertEquals(255, Ic705Codec.nrRaw(9))
    }

    @Test fun supplyMetersUseIc705VoltageAndCurrentCalibration() {
        assertEquals(13.809f, Ic705Codec.supplyVoltage(208), 0.001f)
        assertEquals(16f, Ic705Codec.supplyVoltage(241), 0.001f)
        assertEquals(4f, Ic705Codec.supplyCurrent(241), 0.001f)
        assertEquals(0f, Ic705Codec.supplyCurrent(0), 0.001f)
    }
}
