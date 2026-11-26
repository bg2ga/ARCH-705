// Receive audio for the open mode's page, on its own thread: from the IC-705's USB sound card when it is plugged in
// (Settings > Receive audio), else the phone's microphone. The radio's USB audio is 48 kHz; Android converts it to the
// rate the mode's decoder wants. Uses the "unprocessed" source where the phone offers it (no noise suppression or
// automatic gain, which would spoil weak digital signals), else voice recognition (lightly processed).
package uk.hamdigital.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.core.AudioChoice
import kotlin.math.abs

object AudioIn {
    private data class CaptureRequest(
        val ctx: Context,
        val who: Any,
        val choice: AudioChoice,
        val sampleRate: Int,
        val block: Int,
        val sink: (ShortArray, Int) -> Unit,
    )
    private var lastCapture: CaptureRequest? = null
    private var backgroundPaused = false
    @Volatile var running = false; private set        // capturing
    @Volatile var level = 0f; private set             // input peak, 0..1 of full scale (fast up, slow down)
    @Volatile var sourceName = ""; private set        // where the audio is from, for the page's status line
    @Volatile var wifi = false; private set           // the audio comes over the WiFi link (it may be down for a while: RxStatus says so)
    @Volatile var rate = 0; private set               // sample rate now (Hz)
    private var thread: Thread? = null
    @Volatile private var activeRecord: AudioRecord? = null
    private var activeStop = AtomicBoolean(false)
    @Volatile var owner: Any? = null; private set     // the page that started it (only it stops it)
    val captureError = MutableStateFlow<String?>(null)

    fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The IC-705's (or any) USB sound card's input, if one is plugged in. */
    fun usbInput(ctx: Context): AudioDeviceInfo? =
        ctx.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }

    /** A USB device's name ("IC-705" when it is the radio). */
    fun usbName(d: AudioDeviceInfo) = d.productName?.toString()?.ifBlank { null } ?: "USB audio"

    /**
     * Start capturing [sampleRate] Hz mono 16-bit audio in blocks of [block] samples, each handed to [sink] on the
     * capture thread; [who] is the page asking (see stop). Returns null when running, else what went wrong.
     */
    @Suppress("MissingPermission")
    @Synchronized
    fun start(ctx: Context, who: Any, choice: AudioChoice, sampleRate: Int, block: Int, sink: (ShortArray, Int) -> Unit): String? {
        if (!stopNow()) return "上一路音频仍在退出，请稍后重试"
        val useWifi = choice == AudioChoice.WIFI || (choice == AudioChoice.AUTO && uk.hamdigital.rig.IcomNet.loggedIn)
        if (useWifi) {             // the radio's audio over WiFi (IcomNet): no recording here
            lastCapture = null
            backgroundPaused = false
            captureError.value = null
            synchronized(netLock) {
                generation++
                netSink = sink; netBlock = ShortArray(block); netFill = 0; netPos = 0.0; netStep = 48000.0 / sampleRate
                netLast = 0
            }
            sourceName = "IC-705 (WiFi)"; rate = sampleRate; owner = who; running = true; wifi = true
            return null                               // (ready for the audio whenever the link is up: a link that is down, or reconnecting, is shown live by RxStatus - 0.9.0: it used to be a fixed error, so a page opened during a reconnect stayed "No audio")
        }
        wifi = false                                  // (a recording on the phone)
        if (!allowed(ctx)) return "未允许麦克风权限，请在系统设置中授权 ARCH-705"
        val usb = if (choice == AudioChoice.MIC) null else usbInput(ctx) // the radio's sound card?
        if (choice == AudioChoice.USB && usb == null) return "未发现 IC-705 USB 音频，请连接 USB 线或在设置中选择 WiFi"
        val am = ctx.getSystemService(AudioManager::class.java) // does the phone offer an unprocessed source?
        val src = if (am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") MediaRecorder.AudioSource.UNPROCESSED
                  else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) // smallest buffer
        val rec = try { AudioRecord(src, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, sampleRate)) } // 0.5 s of buffer
                  catch (e: Exception) { return "无法打开音频输入：${e.message}" }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return "无法打开音频输入" }
        if (usb != null) rec.setPreferredDevice(usb)  // the radio rather than the microphone
        try { rec.startRecording() } catch (e: Exception) { rec.release(); return "无法启动音频输入：${e.message}" }
        sourceName = if (usb != null) "${usbName(usb)} (USB)" else "手机麦克风" // for the status line
        rate = sampleRate; owner = who; running = true
        lastCapture = CaptureRequest(ctx.applicationContext, who, choice, sampleRate, block, sink)
        backgroundPaused = false
        captureError.value = null
        val stopFlag = AtomicBoolean(true)
        activeStop = stopFlag
        activeRecord = rec
        val myGeneration = generation
        thread = Thread({
            val buf = ShortArray(block)
            try {
                while (stopFlag.get() && generation == myGeneration) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) {
                        captureError.value = "音频输入读取失败（$n）"
                        break
                    }
                    if (n == 0 || !stopFlag.get() || generation != myGeneration) continue
                    var peak = 0
                    for (i in 0 until n) { val v = abs(buf[i].toInt()); if (v > peak) peak = v }
                    val l = peak / 32768f
                    if (generation == myGeneration) level = if (l > level) l else level * 0.95f
                    synchronized(netLock) { if (stopFlag.get() && generation == myGeneration) sink(buf, n) }
                }
            } catch (e: Exception) {
                if (generation == myGeneration) captureError.value = "音频输入异常：${e.message ?: "未知错误"}"
            } finally {
                stopFlag.set(false)
                runCatching { rec.stop() }
                runCatching { rec.release() }
                if (generation == myGeneration) {
                    level = 0f
                    running = false
                }
            }
        }, "audio-in").apply { priority = Thread.MAX_PRIORITY; start() }
        return null
    }

    /** Stop, if [who] started it (a page closing must not stop the next page's capture). */
    @Synchronized
    fun stop(who: Any) {
        if (owner === who || lastCapture?.who === who) {
            lastCapture = null
            backgroundPaused = false
            stopNow()
        }
    }

    /** Stop phone-side microphone/USB capture while the app is in the background. WiFi audio is not recorded here. */
    fun pauseForBackground() {
        if (wifi || !running) return
        backgroundPaused = true
        stopNow()
    }

    /** Restart the last local microphone/USB capture after returning to the foreground. */
    fun resumeAfterBackground() {
        if (!backgroundPaused) return
        val request = lastCapture ?: return
        start(request.ctx, request.who, request.choice, request.sampleRate, request.block, request.sink)
    }

    @Synchronized
    private fun stopNow(): Boolean {
        val rec: AudioRecord?
        synchronized(netLock) {
            running = false
            generation++
            activeStop.set(false)
            activeStop = AtomicBoolean(false)
            rec = activeRecord
            activeRecord = null
            netSink = null
            netFill = 0
        }
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        val old = thread
        old?.join(200)
        if (old?.isAlive == true) {
            thread = old
            return false
        }
        thread = null
        owner = null
        level = 0f
        return true
    }

    // ---- WiFi audio (IcomNet hands it in on its network thread): 48 kHz, converted to the page's rate ----
    @Volatile private var netSink: ((ShortArray, Int) -> Unit)? = null // the page's decoder and waterfall
    private val netLock = Any()
    @Volatile private var generation = 0L
    private var netBlock = ShortArray(0); private var netFill = 0      // the block being filled
    private var netPos = 0.0; private var netStep = 1.0                // resampling: position in the input, input samples a output sample
    private var netLast: Short = 0                                    // the previous input sample (for interpolation across packets)

    /** Receive audio from the radio over WiFi. */
    fun netAudio(s: ShortArray, n: Int) {
        val sink = netSink ?: return
        val completed = ArrayList<ShortArray>(2)
        synchronized(netLock) {
            if (!running || generation == 0L || n == 0 || netBlock.isEmpty()) return
            var peak = 0
            for (i in 0 until n) { val v = abs(s[i].toInt()); if (v > peak) peak = v }
            val l = peak / 32768f
            level = if (l > level) l else level * 0.95f
            while (netPos < n) {
                val i = netPos.toInt()
                val f = netPos - i
                val a = if (i == 0) { if (f > 0.0) netLast.toInt() else s[0].toInt() } else s[i - 1].toInt()
                val b = s[i].toInt()
                netBlock[netFill++] = (a + (b - a) * f).toInt().toShort()
                if (netFill == netBlock.size) {
                    completed.add(netBlock.copyOf())
                    netFill = 0
                }
                netPos += netStep
            }
            netPos -= n
            netLast = s[n - 1]
        }
        completed.forEach { block -> runCatching { sink(block, block.size) } }
    }
}
