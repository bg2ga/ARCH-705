package uk.hamdigital.core

/** A common amateur-band entry with the IC-705 mode used when the band is selected. */
data class BandPreset(
    val label: String,
    val hz: Long,
    val modeCode: Int,
    val squelchRaw: Int = 0,
    val preampRaw: Int = 0,
)

/**
 * IC-705 default band behaviour.
 *
 * The rules follow normal amateur operation rather than one fixed mode for every
 * frequency: 160/80/40 m use LSB, 60 m and 20-6 m use USB, 30 m uses CW, and
 * 2 m/70 cm use FM with squelch enabled. The preamp default is 1 on every band.
 */
object BandPlan {
    const val DEFAULT_PREAMP = 0
    const val VHF_UHF_SQUELCH = 77

    const val LSB = 0x00
    const val USB = 0x01
    const val CW = 0x03
    const val AM = 0x02
    const val FM = 0x05
    const val WFM = 0x06

    val presets = listOf(
        BandPreset("160m", 1_840_000L, LSB),
        BandPreset("80m", 3_850_000L, LSB),
        BandPreset("60m", 5_351_500L, USB),
        BandPreset("40m", 7_050_000L, LSB),
        BandPreset("30m", 10_120_000L, CW),
        BandPreset("20m", 14_270_000L, USB),
        BandPreset("17m", 18_130_000L, USB),
        BandPreset("15m", 21_200_000L, USB),
        BandPreset("12m", 24_950_000L, USB),
        BandPreset("10m", 28_500_000L, USB),
        BandPreset("6m", 50_150_000L, USB),
        BandPreset("广播", 88_500_000L, WFM),
        BandPreset("航空", 118_000_000L, AM, VHF_UHF_SQUELCH, 1),
        BandPreset("2m", 145_500_000L, FM, VHF_UHF_SQUELCH, 1),
        BandPreset("70cm", 438_500_000L, FM, VHF_UHF_SQUELCH, 1),
    )

    fun labelFor(hz: Long): String = when {
        inRange(hz, 1_800_000L, 2_000_000L) -> "160m"
        inRange(hz, 3_500_000L, 4_000_000L) -> "80m"
        inRange(hz, 5_250_000L, 5_450_000L) -> "60m"
        inRange(hz, 7_000_000L, 7_300_000L) -> "40m"
        inRange(hz, 10_100_000L, 10_150_000L) -> "30m"
        inRange(hz, 14_000_000L, 14_350_000L) -> "20m"
        inRange(hz, 18_068_000L, 18_168_000L) -> "17m"
        inRange(hz, 21_000_000L, 21_450_000L) -> "15m"
        inRange(hz, 24_890_000L, 24_990_000L) -> "12m"
        inRange(hz, 28_000_000L, 29_700_000L) -> "10m"
        inRange(hz, 50_000_000L, 54_000_000L) -> "6m"
        inRange(hz, 87_500_000L, 108_000_000L) -> "广播"
        inRange(hz, 108_000_000L, 137_000_000L) -> "航空"
        inRange(hz, 144_000_000L, 148_000_000L) -> "2m"
        inRange(hz, 430_000_000L, 450_000_000L) -> "70cm"
        inRange(hz, 1_240_000_000L, 1_300_000_000L) -> "23cm"
        else -> "其他"
    }

    fun modeFor(hz: Long): Int = when {
        inRange(hz, 1_800_000L, 2_000_000L) -> LSB
        inRange(hz, 3_500_000L, 4_000_000L) -> LSB
        inRange(hz, 5_250_000L, 5_450_000L) -> USB
        inRange(hz, 7_000_000L, 7_300_000L) -> LSB
        inRange(hz, 10_100_000L, 10_150_000L) -> CW
        inRange(hz, 14_000_000L, 14_350_000L) -> USB
        inRange(hz, 18_068_000L, 18_168_000L) -> USB
        inRange(hz, 21_000_000L, 21_450_000L) -> USB
        inRange(hz, 24_890_000L, 24_990_000L) -> USB
        inRange(hz, 28_000_000L, 29_700_000L) -> USB
        inRange(hz, 50_000_000L, 54_000_000L) -> USB
        inRange(hz, 87_500_000L, 108_000_000L) -> WFM
        inRange(hz, 108_000_000L, 137_000_000L) -> AM
        inRange(hz, 144_000_000L, 148_000_000L) -> FM
        inRange(hz, 430_000_000L, 450_000_000L) -> FM
        inRange(hz, 1_240_000_000L, 1_300_000_000L) -> FM
        hz <= 10_150_000L -> LSB
        hz < 30_000_000L -> USB
        else -> FM
    }

    fun squelchFor(hz: Long): Int = if (usesSquelch(hz)) VHF_UHF_SQUELCH else 0

    fun usesSquelch(hz: Long): Boolean = inRange(hz, 108_000_000L, 137_000_000L) ||
        inRange(hz, 144_000_000L, 148_000_000L) || inRange(hz, 430_000_000L, 450_000_000L) ||
        inRange(hz, 1_240_000_000L, 1_300_000_000L)

    private fun inRange(value: Long, from: Long, to: Long): Boolean = value in from..to
}
