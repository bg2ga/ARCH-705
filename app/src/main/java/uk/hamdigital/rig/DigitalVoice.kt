package uk.hamdigital.rig

enum class DigitalVoiceProtocol(val label: String) {
    DMR("DMR"),
    YSF("YSF/C4FM"),
    P25("P25");

    companion object {
        fun fromCode(code: Int): DigitalVoiceProtocol? = when (code) {
            1 -> DMR
            2 -> YSF
            3 -> P25
            else -> null
        }
    }
}

enum class DigitalVoiceCallKind(val label: String) {
    UNKNOWN("未知呼型"),
    GROUP("组呼"),
    PRIVATE("个呼"),
    ALL_CALL("全呼");

    companion object {
        fun fromToken(token: String?): DigitalVoiceCallKind = when (token) {
            "GRP" -> GROUP
            "PVT" -> PRIVATE
            "ALL" -> ALL_CALL
            else -> UNKNOWN
        }
    }
}

enum class DigitalVoicePhase(val label: String) {
    SEARCH("搜索"),
    SYNC("同步"),
    RECEIVING("接收中"),
    ENDED("结束"),
    ERROR("异常");

    companion object {
        fun fromCode(code: Int): DigitalVoicePhase = when (code) {
            0 -> SEARCH
            1 -> SYNC
            2 -> RECEIVING
            3 -> ENDED
            else -> ERROR
        }
    }
}

data class DigitalVoiceInfo(
    val protocol: DigitalVoiceProtocol,
    val phase: DigitalVoicePhase,
    val callKind: DigitalVoiceCallKind,
    val colorCode: Int? = null,
    val slot: Int? = null,
    val sourceId: Long? = null,
    val targetId: Long? = null,
    val callsign: String? = null,
    val row: String = "",
    val parsed: Boolean = false,
    val receivedAtMs: Long = System.currentTimeMillis(),
)

object DigitalVoiceDecoder {
    private val dmr = Regex("""^(?:(GRP|PVT|ALL)\s+)?CC\s+(\d+)(?:\s+TS\s+([12]))?(?:\s+ID\s+(\d+))?(?:\s+(TG|TO)\s+(\d+))?""")
    private val ysf = Regex("""^(?:(GRP|PVT|ALL)\s+)?(DN|VW)\s+DG-ID\s+(\d+)(?:\s+RX\s+(.+))?""")
    private val p25 = Regex("""^(?:(GRP|PVT|ALL)\s+)?NAC\s+([0-9A-Fa-f]+)(?:\s+ID\s+(\d+))?(?:\s+(TG|TO)\s+(\d+))?""")

    fun decode(protocolCode: Int, phaseCode: Int, row: String): DigitalVoiceInfo? {
        val protocol = DigitalVoiceProtocol.fromCode(protocolCode) ?: return null
        val text = row.trim().uppercase()
        return when (protocol) {
            DigitalVoiceProtocol.DMR -> {
                val match = dmr.find(text)
                DigitalVoiceInfo(
                    protocol = protocol,
                    phase = DigitalVoicePhase.fromCode(phaseCode),
                    callKind = DigitalVoiceCallKind.fromToken(match?.groupValues?.get(1)),
                    colorCode = match?.groupValues?.get(2)?.toIntOrNull(),
                    slot = match?.groupValues?.get(3)?.toIntOrNull(),
                    sourceId = match?.groupValues?.get(4)?.toLongOrNull(),
                    targetId = match?.groupValues?.get(6)?.toLongOrNull(),
                    row = row,
                    parsed = match != null,
                )
            }
            DigitalVoiceProtocol.YSF -> {
                val match = ysf.find(text)
                DigitalVoiceInfo(
                    protocol = protocol,
                    phase = DigitalVoicePhase.fromCode(phaseCode),
                    callKind = DigitalVoiceCallKind.fromToken(match?.groupValues?.get(1)),
                    colorCode = match?.groupValues?.get(3)?.toIntOrNull(),
                    callsign = match?.groupValues?.get(4)?.trim()?.take(10)?.ifBlank { null },
                    row = row,
                    parsed = match != null,
                )
            }
            DigitalVoiceProtocol.P25 -> {
                val match = p25.find(text)
                DigitalVoiceInfo(
                    protocol = protocol,
                    phase = DigitalVoicePhase.fromCode(phaseCode),
                    callKind = DigitalVoiceCallKind.fromToken(match?.groupValues?.get(1)),
                    colorCode = match?.groupValues?.get(2)?.toIntOrNull(16),
                    sourceId = match?.groupValues?.get(3)?.toLongOrNull(),
                    targetId = match?.groupValues?.get(5)?.toLongOrNull(),
                    row = row,
                    parsed = match != null,
                )
            }
        }
    }

    fun parseFrame(data: List<Int>): DigitalVoiceInfo? {
        val archFrame = data.size == 58 && data[0] == 0xf0 && data[1] == 0x01
        val legacyFrame = data.size == 58 && data[0] == 0x1a && data[1] == 0x04
        if (!archFrame && !legacyFrame) return null
        if (data[2] != 'A'.code || data[3] != '7'.code || data[4] != 'D'.code || data[5] != 'V'.code) return null
        if (data[6] != 4) return null
        val rowLength = data[9]
        if (rowLength !in 0..48) return null
        val row = data.subList(10, 10 + rowLength)
            .map { it.toChar() }
            .filter { it.code in 0x20..0x7e }
            .joinToString("")
            .trim()
        return decode(data[7], data[8], row)
    }
}
