// FT8 / FT4 / FT2 receive: SlotAudio keeps the last 30 s of 12 kHz audio with its UTC time, and when each slot (15 s FT8,
// 7.5 s FT4, 3.75 s FT2, starting on the UTC clock) is nearly over hands that slot's audio to ft8_lib on a background thread. The results go
// into a list (newest first) that the page shows; it is kept while the app runs, so leaving the page and coming back
// keeps what was heard.
package uk.hamdigital.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.hamdigital.engine.Ft8Native
import java.time.Instant
import java.time.ZoneOffset

/** One decoded message. */
data class FtDecode(
    val slotMs: Long,                                 // the slot's start (UTC, epoch ms)
    val snr: Int,                                     // dB in 2500 Hz
    val dt: Float,                                    // time offset (s)
    val freq: Int,                                    // audio frequency (Hz)
    val text: String,                                 // the message
    val to: String,                                   // first call ("CQ" for a CQ)
    val from: String,                                 // the sender
    val grid: String,                                 // the sender's locator, if sent
    val km: Int?,                                     // distance to it from your locator
    val cq: Boolean,                                  // a CQ call
    val toMe: Boolean,                                // addressed to your callsign
) {
    val utc: String get() = Instant.ofEpochMilli(slotMs).atZone(ZoneOffset.UTC).let { "%02d%02d%02d".format(it.hour, it.minute, it.second) } // "123045"
}

/** The FT modes: one decoder (ft8_lib), the same messages and contacts, three speeds. [code] is ft8_slot.c's mode;
 *  [txStartMs] when in its slot a transmission starts; [widthHz] the signal's width (4 or 8 tones). */
enum class FtMode(val code: Int, val periodMs: Long, val decodeAtMs: Long, val txStartMs: Long, val widthHz: Float, val mode: Mode) {
    FT8(0, 15000, 14700, 500, 50f, Mode.FT8),         // 8 tones of 6.25 Hz; decoded 14.7 s in
    FT4(1, 7500, 7300, 500, 83f, Mode.FT4),           // 4 tones of 20.8 Hz
    FT2(2, 3750, 3500, 150, 167f, Mode.FT2);          // 4 tones of 41.7 Hz; starts 0.15 s in (WSJT-X Improved), over by 2.7 s

    val seconds get() = if (periodMs % 1000 == 0L) "${periodMs / 1000}" else "${periodMs / 1000.0}" // "15", "7.5", "3.75"

    companion object { fun of(m: Mode) = when (m) { Mode.FT4 -> FT4; Mode.FT2 -> FT2; else -> FT8 } } // a page's mode
}

class SlotDecoder(val ft: FtMode) {
    val periodMs = ft.periodMs                        // slot length
    private val audio = SlotAudio(periodMs, ft.decodeAtMs) { slot, out -> decode(slot, out) } // the slot timing (shared with JS8); decoded once the transmissions are over

    private val _decodes = MutableStateFlow<List<FtDecode>>(emptyList()) // what was heard, newest first
    val decodes: StateFlow<List<FtDecode>> = _decodes
    val busy = MutableStateFlow(false)                // decoding now
    val lastCount = MutableStateFlow(-1)              // decodes in the last slot (-1: none decoded yet, -2: we transmitted in it)

    @Volatile var myCall = ""                         // for "to me"
    @Volatile var myGrid = ""                         // for distances

    /** Audio in (the capture thread): 12 kHz mono. */
    fun feed(b: ShortArray, n: Int) = audio.feed(b, n)

    /** A slot's audio, from its start (the background thread): decode it with ft8_lib. */
    private fun decode(slot: Long, out: ShortArray) {
        if (uk.hamdigital.audio.Transmitter.sentDuring(slot, slot + periodMs)) { lastCount.value = -2; return } // our own transmission: not decoded
        busy.value = true
        val lines = try { Ft8Native.decode(out, out.size, ft.code) } catch (e: Throwable) { emptyArray() } // ft8_lib
        val got = lines.mapNotNull { parse(slot, it) }.sortedBy { it.freq } // by frequency within the slot
        _decodes.value = (got + _decodes.value).take(600) // newest slot first; the most recent 600 kept
        FtQso.of(ft).onDecodes(got)                   // a contact in progress moves on (auto-sequencing)
        lastCount.value = got.size; busy.value = false
    }

    fun clear() { _decodes.value = emptyList(); lastCount.value = -1 } // empty the list

    /** "snr\tdt\tfreq\ttext" -> FtDecode, with the calls and locator picked out. */
    private fun parse(slot: Long, line: String): FtDecode? {
        val p = line.split('\t'); if (p.size < 4) return null
        val text = p[3].trim(); val w = text.split(' ').filter { it.isNotEmpty() } // the message, its words
        val cq = w.firstOrNull() == "CQ"              // a CQ
        val from: String; val to: String
        if (cq) {                                     // CQ [modifier] CALL [GRID]
            val mod = w.size >= 3 && w[1].length <= 4 && w[1].all { it.isLetter() } && !w[1].any { it.isDigit() } // "DX", "POTA", "NA" ...
            from = w.getOrNull(if (mod) 2 else 1) ?: ""; to = "CQ"
        } else { to = w.getOrNull(0) ?: ""; from = w.getOrNull(1) ?: "" } // TO FROM [extra]
        val last = w.lastOrNull() ?: ""
        val grid = if (w.size >= 3 && Locator.valid(last) && last != "RR73") last else "" // a locator at the end ("RR73" looks like one)
        val me = myCall.isNotEmpty() && to.trim('<', '>') == myCall // to you
        return FtDecode(slot, p[0].toFloatOrNull()?.toInt() ?: 0, p[1].toFloatOrNull() ?: 0f, p[2].toFloatOrNull()?.toInt() ?: 0, text,
            to.trim('<', '>'), from.trim('<', '>'), grid, if (grid.isNotEmpty() && myGrid.isNotEmpty()) Locator.km(myGrid, grid) else null, cq, me)
    }

    companion object {
        val FT8 by lazy { SlotDecoder(FtMode.FT8) }   // the app's FT8 decoder
        val FT4 by lazy { SlotDecoder(FtMode.FT4) }   // FT4
        val FT2 by lazy { SlotDecoder(FtMode.FT2) }   // and FT2
        fun of(ft: FtMode) = when (ft) { FtMode.FT8 -> FT8; FtMode.FT4 -> FT4; FtMode.FT2 -> FT2 } // a mode's decoder
    }
}
