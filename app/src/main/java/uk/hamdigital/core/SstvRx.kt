// SSTV receive, with Robot36's decoder (Ahmet Inan, 0BSD; xdsopl.robot36): the page's audio goes in, scan lines come
// out - into the "scope" (every line, a picture or not) and, once a VIS code has named the mode, the picture itself. A
// finished picture is saved as a PNG in the app's files (sstv/) and joins the list of pictures received, newest first.
// Kept while the app runs; our own transmissions are not decoded (the radio passes our audio back).
package uk.hamdigital.core

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.audio.Transmitter
import xdsopl.robot36.SstvDecoder
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime

object SstvRx {
    const val RATE = 12000                            // the decoder's audio rate (the WiFi link's own)
    val dec = SstvDecoder(RATE)                       // Robot36
    val lines = MutableStateFlow(0)                   // scan lines decoded so far (the page redraws when it moves)
    val mode = MutableStateFlow("")                   // the mode of the last picture that started ("" until one has: the decoder's own starting mode is not one heard)
    val pictures = MutableStateFlow<List<File>>(emptyList()) // pictures received, newest first
    val saved = MutableStateFlow("")                  // the last picture saved (its name)
    val finished = MutableStateFlow<Bitmap?>(null)    // the last picture completed (whole), for the page's frame
    private var dir: File? = null                     // where they go
    private var f = FloatArray(0)                     // (a block, as floats)

    fun attach(ctx: Context) {                        // (once: the pictures already received)
        if (dir != null) return
        dir = File(ctx.filesDir, "sstv").apply { mkdirs() }
        pictures.value = dir!!.listFiles { x -> x.name.endsWith(".png") }?.sortedByDescending { it.name } ?: emptyList()
    }

    // The last 3 minutes of audio, kept so a picture that went wrong can be saved as a WAV and looked into (saveAudio).
    private val ring = ShortArray(RATE * 180); private var ringPos = 0; private var ringFull = false

    /** Write the last 3 minutes of the page's audio as a WAV (12 kHz, 16-bit mono) into the app's files; its file. */
    fun saveAudio(ctx: Context): File {
        val t = ZonedDateTime.now(ZoneOffset.UTC)
        val f = File(ctx.filesDir, "sstv_audio_%04d%02d%02d_%02d%02d%02d.wav".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second))
        val n: Int; val s: ShortArray
        synchronized(ring) { n = if (ringFull) ring.size else ringPos; s = ShortArray(n) { i -> ring[if (ringFull) (ringPos + i) % ring.size else i] } } // oldest first
        val bb = java.nio.ByteBuffer.allocate(44 + 2 * n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + 2 * n).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(2 * n); for (v in s) bb.putShort(v)
        f.writeBytes(bb.array()); return f
    }

    /** Debug builds: save the audio every 3 minutes by itself, keeping the newest 10 files (to look into pictures that went wrong). */
    @Volatile var autoSave: Context? = null
    private var sinceSave = 0

    /** Audio in (the capture thread): 12 kHz mono. */
    fun feed(b: ShortArray, n: Int) {
        synchronized(ring) { for (i in 0 until n) { ring[ringPos] = b[i]; ringPos++; if (ringPos == ring.size) { ringPos = 0; ringFull = true } } } // (kept for saveAudio)
        autoSave?.let { c -> sinceSave += n; if (sinceSave >= ring.size) { sinceSave = 0; Thread {
            saveAudio(c); c.filesDir.listFiles { x -> x.name.startsWith("sstv_audio_") }?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() } }.start() } }
        val now = System.currentTimeMillis()
        if (Transmitter.sentDuring(now - 600, now)) return // (our own picture going out)
        if (f.size != n) f = FloatArray(n)
        for (i in 0 until n) f[i] = b[i] / 32768f
        if (!dec.process(f)) return                   // no new lines
        lines.value++
        val img = dec.image
        if (img.line in 0 until img.height) mode.value = dec.modeName() // a picture is coming in: its mode (from its VIS code or its line timing)
        if (img.line >= img.height && img.height > 0) { // a picture finished: keep it
            val timing = dec.foundByTiming()          // (no VIS code: found from its line timing)
            val bmp = Bitmap.createBitmap(img.pixels, img.width, img.height, Bitmap.Config.ARGB_8888) // the whole picture
            if (!timing || dec.syncedShare() >= 0.5f) // (one caught only at its end - mostly noise - is not kept)
                save(bmp, dec.modeName() + if (timing) " sync" else "")
            img.line = -1                             // (Robot36 does the same: so it is saved once)
            finished.value = bmp                      // the page shows it whole (its 5-a-second refresh may have missed the last lines)
        }
    }

    private fun save(bmp: Bitmap, modeName: String) {
        val d = dir ?: return
        val t = ZonedDateTime.now(ZoneOffset.UTC)
        val file = File(d, "SSTV_%04d%02d%02d_%02d%02d%02d_%s.png".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second, modeName.replace(' ', '_')))
        try {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            pictures.value = (listOf(file) + pictures.value).take(500); saved.value = file.name
        } catch (e: Exception) { saved.value = "Could not save the picture: ${e.message}" }
    }

    /** Keep what the frame shows now (the scan lines - a picture found from its line timing, whose VIS code was not
     *  heard: it is not saved by itself). */
    fun saveLive(bmp: Bitmap, modeName: String) = save(bmp, modeName + " live")

    /** Forget a picture (and delete its file). */
    fun delete(file: File) { file.delete(); pictures.value = pictures.value - file }
}
