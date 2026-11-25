// JTTY (WSJT-X 3.2's RTTY-style keyboard mode; the C port in cpp/jtty): receive on a worker thread of its own - the
// decoder searches every quarter frame (0.47 s) and is the heaviest in the app, too heavy for the audio thread - and
// a list of messages, newest first, each growing frame by frame (1.9 s a frame) until its last frame arrives. While we
// transmit the receiver gets silence, so its time line runs on unbroken and our own signal is not decoded.
// Sending: a typed message, or the contest keys (WSJT-X's F1-F8 defaults), on the transmit frequency, at once.
package uk.hamdigital.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.engine.JttyNative
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object Jtty {
    const val RATE = 12000                            // the decoder's audio rate
    const val WIDTH = 125f                            // a signal's width: 4 tones of 31.25 Hz

    /** A message: received ([mine] false) or sent. [startMs]: its first frame (UTC, epoch ms). */
    data class Msg(val id: Long, val freq: Float, val startMs: Long, val text: String, val complete: Boolean, val snr: Int, val mine: Boolean = false)

    val messages = MutableStateFlow<List<Msg>>(emptyList()) // newest first
    val behind = MutableStateFlow(0)                  // audio blocks waiting for the decoder (it is behind if this grows)
    @Volatile var rxHz = 1500f                        // receive (and transmit) frequency: the lowest tone
    @Volatile var ftol = 100f                         // search width either side of it (channels at 1350 / 1650 Hz search too)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jtty-rx").apply { isDaemon = true } } // the decoder's thread
    private val queued = AtomicInteger()              // blocks queued
    @Volatile private var fresh = true                // the receiver is to start afresh with the next block
    private var t0 = 0L                               // wall clock (ms) of the receiver's first sample
    private var mineId = -1L                          // ids for our own messages (negative)

    /** Start receiving afresh (the page opened): the audio before this is not continuous with what follows. */
    fun restart() { fresh = true }

    /** Audio in (the capture thread): 12 kHz mono. */
    fun feed(b: ShortArray, n: Int) {
        val now = System.currentTimeMillis()
        val copy = if (Transmitter.sentDuring(now - 600, now)) ShortArray(n) else b.copyOf(n) // silence while we send
        val f = rxHz; val t = ftol
        behind.value = queued.incrementAndGet()
        worker.execute {
            try {
                if (fresh) { JttyNative.reset(); t0 = now - n * 1000L / RATE; fresh = false } // its sample 0 = this block's start
                JttyNative.feed(copy, n, f, t, 200, 2800)  // (the band searched: WSJT-X's wide graph default)
                collect()
            } catch (_: Throwable) { } finally { behind.value = queued.decrementAndGet() }
        }
    }

    private fun collect() {                           // the receiver's updates into the list
        val ups = JttyNative.updates(); if (ups.isEmpty()) return
        val map = LinkedHashMap<Long, Msg>(); messages.value.forEach { map[it.id] = it }
        for (u in ups) {
            val p = u.split('\t', limit = 6); if (p.size < 6) continue
            val id = p[0].toLongOrNull() ?: continue
            val m = Msg(id, p[1].toFloatOrNull() ?: 0f, t0 + ((p[2].toDoubleOrNull() ?: 0.0) * 1000).toLong(), p[5], p[3] == "1", p[4].toIntOrNull() ?: 0)
            map[id] = m                               // new, or the same message grown
        }
        messages.value = map.values.sortedByDescending { it.startMs }.take(300)
    }

    fun clear() { messages.value = emptyList() }

    /** How [text] will go out: (frames, the text as received). */
    fun preview(text: String, profile: Int = JttyNative.UNKNOWN): Pair<Int, String> =
        JttyNative.pack(text, profile).split('\t', limit = 2).let { (it[0].toIntOrNull() ?: -1) to it.getOrElse(1) { "" } }

    /** Send [text] now on the transmit frequency at [level]; false (and Transmitter.lastError) if it did not start. */
    fun send(ctx: Context, myCall: String, text: String, profile: Int, level: Float): Boolean {
        val audio = JttyNative.encode(text, profile, rxHz, level) ?: return false
        val ok = Transmitter.send(ctx, myCall, audio, RATE, 0, Mode.JTTY.name)
        if (ok) messages.value = (listOf(Msg(mineId--, rxHz, System.currentTimeMillis(), preview(text, profile).second, true, 0, true)) + messages.value).take(300)
        return ok
    }

    /** The other station's callsign in a message (for "his call"): the first word that looks like a callsign, not [myCall]. */
    fun callIn(text: String, myCall: String): String? =
        text.split(' ').map { it.trim() }.firstOrNull { w -> w != myCall && w.length in 3..10 && w.any { it.isDigit() } && w.any { it.isLetter() } &&
            w.all { it.isLetterOrDigit() || it == '/' } && w != "599" && !w.all { it.isDigit() } && !Locator.valid(w) } // (not a locator)
}
