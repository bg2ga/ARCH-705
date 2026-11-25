package uk.hamdigital.rig

/** Pure CI-V and meter conversions, kept separate so they can be tested without a radio. */
internal object Ic705Codec {
    fun levelBcd(value: Int): IntArray {
        val n = value.coerceIn(0, 255)
        return intArrayOf(n / 100, ((n % 100 / 10) shl 4) or (n % 10))
    }

    fun levelValue(data: List<Int>, at: Int = 0): Int {
        if (data.size <= at) return 0
        val hundreds = data[at] and 0xFF
        val tail = if (data.size > at + 1) data[at + 1] and 0xFF else 0
        return hundreds * 100 + (tail shr 4) * 10 + (tail and 0x0F)
    }

    fun frequencyBcd(hz: Long): IntArray {
        var value = hz.coerceAtLeast(0L)
        return IntArray(5) {
            val lo = (value % 10).toInt()
            value /= 10
            val hi = (value % 10).toInt()
            value /= 10
            (hi shl 4) or lo
        }
    }

    fun frequencyValue(data: List<Int>, at: Int = 0): Long {
        var hz = 0L
        var multiplier = 1L
        repeat(5) { i ->
            if (at + i < data.size) {
                val byte = data[at + i] and 0xFF
                hz += ((byte and 0x0F) + 10 * (byte shr 4)) * multiplier
            }
            multiplier *= 100L
        }
        return hz
    }

    fun filterLimit(mode: String): Int = when {
        mode.startsWith("AM") || mode.startsWith("WFM") -> 49
        mode.startsWith("RTTY") -> 31
        else -> 40
    }

    fun filterWidth(mode: String, index: Int): Int {
        val i = index.coerceIn(0, filterLimit(mode))
        return if (mode.startsWith("AM") || mode.startsWith("WFM")) 200 + i * 200
        else if (i <= 10) 50 + i * 50 else 600 + (i - 10) * 100
    }

    fun levelPercent(raw: Int): Int = ((raw.coerceIn(0, 255) * 100 + 127) / 255).coerceIn(0, 100)

    fun rawFromPercent(value: Int): Int = ((value.coerceIn(0, 100) * 255 + 50) / 100).coerceIn(0, 255)

    fun nrDisplay(raw: Int): Int = if (raw <= 0) 0 else (((raw.coerceIn(1, 255) * 9 + 127) / 255).coerceIn(1, 9))

    fun nrRaw(value: Int): Int = if (value <= 0) 0 else ((value.coerceIn(1, 9) * 255 + 4) / 9).coerceIn(1, 255)

    /** IC-705 CI-V meter calibration: 0..241 maps to 0..16 V. */
    fun supplyVoltage(raw: Int): Float = raw.coerceIn(0, 241) * 16f / 241f

    /** IC-705 CI-V meter calibration: 0..241 maps to 0..4 A. */
    fun supplyCurrent(raw: Int): Float = raw.coerceIn(0, 241) * 4f / 241f
}
