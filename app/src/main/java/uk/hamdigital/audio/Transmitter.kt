// Transmitting: keys the IC-705 over CI-V (PTT), plays the audio to the radio's USB sound card, and unkeys it. One
// transmission at a time, on its own thread. Safety: nothing is sent unless the radio's CI-V is connected (so PTT can
// be released), the USB sound card is there (never the phone's speaker), and a callsign is set; a watchdog ends any
// transmission after 2 minutes 10 s (WSPR's 110.6 s is the longest); Halt stops at once. The link is checked again when
// keying (a transmission is often arranged seconds ahead) and all through: if the radio's CI-V or the WiFi link goes,
// the transmission stops there (0.10.4: a WSPR beacon "sent" 110 s with the link closed, then carried on when it was
// back). [owner] says which mode is sending, so opening another mode can stop it (TxControl).
package uk.hamdigital.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.hamdigital.core.PttLock
import uk.hamdigital.core.TxControl
import uk.hamdigital.rig.Ic705
import uk.hamdigital.rig.RigState

object Transmitter {
    private val _on = MutableStateFlow(false)         // transmitting now
    val on: StateFlow<Boolean> = _on
    val lastError = MutableStateFlow("")              // why the last transmission did not happen
    @Volatile private var stop = false                // Halt
    @Volatile private var thread: Thread? = null      // the transmission
    @Volatile var owner = ""; private set             // the mode sending (Mode.name: "FT8", "WSPR" ...)
    val threadRunning: Boolean get() = thread != null || streamOn
    private const val WATCHDOG_MS = 130_000L          // longest allowed transmission - or the transmission's own length and 10 s, if longer (SSTV's PD 290 is 289 s)

    /** The IC-705's (or any) USB sound card's output, if plugged in. */
    fun usbOutput(ctx: Context): AudioDeviceInfo? =
        ctx.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }

    /** Why sending is not possible now, or null if it is. */
    fun blocked(ctx: Context, callsign: String): String? = when {
        uk.hamdigital.core.PttLock.locked.value -> "PTT 已锁定，请先解锁"
        callsign.isBlank() -> "请先在设置中填写呼号"
        !uk.hamdigital.core.TxControl.licensed -> "请先在设置中确认发射许可提示"
        Ic705.state.value.link != RigState.Link.CONNECTED -> "IC-705 控制链路未连接，无法发射"
        !uk.hamdigital.rig.IcomNet.radioStateReady() -> "正在读取电台状态，请稍候"
        Ic705.net -> if (uk.hamdigital.rig.IcomNet.txAudioReady()) null else "IC-705 音频通道尚未就绪"
        usbOutput(ctx) == null -> "未发现 IC-705 USB 声卡"
        else -> null
    }

    /**
     * Send [audio] ([rate] Hz mono) starting at [startAtMs] (UTC epoch ms; 0 = now): PTT on, the audio, PTT off.
     * [tag] is the mode sending (Mode.name). [onDone] runs afterwards (true if it was all sent). Returns false (with
     * lastError set) if it cannot start.
     */
    fun send(ctx: Context, callsign: String, audio: ShortArray, rate: Int, startAtMs: Long = 0, tag: String = "", onDone: (Boolean) -> Unit = {}): Boolean {
        blocked(ctx, callsign)?.let { lastError.value = it; return false } // safety first
        if (thread != null || streamOn) { lastError.value = "已有发射任务"; return false }
        val txToken = TxControl.beginTx()
        owner = tag
        if (Ic705.net) return sendNet(audio, rate, startAtMs, onDone, txToken) // over WiFi
        val dev = usbOutput(ctx)!!                    // the radio's sound card
        stop = false; lastError.value = ""
        thread = Thread({
            var ok = false
            var track: AudioTrack? = null
            try {
                val wait = startAtMs - System.currentTimeMillis() - 60 // key 60 ms before the audio starts
                if (wait > 0) Thread.sleep(wait)
                if (stop || !TxControl.isCurrent(txToken)) return@Thread
                if (Ic705.state.value.link != RigState.Link.CONNECTED) { lastError.value = "发射前控制链路已断开，未发送"; return@Thread } // (checked again: arranged seconds ago)
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 5 * 2)) // 0.2 s
                    .build()
                if (PttLock.locked.value || !TxControl.isCurrent(txToken)) { lastError.value = "PTT 已锁定，未发射"; return@Thread }
                track.setPreferredDevice(dev)         // the radio, never the speaker
                Ic705.ptt(true); keyed(true)          // key the transmitter
                Thread.sleep(maxOf(0L, minOf(60L, startAtMs - System.currentTimeMillis()))) // (the rest of the keying lead)
                track.play()
                val t0 = System.currentTimeMillis()
                var i = 0
                val watchdog = maxOf(WATCHDOG_MS, audio.size * 1000L / rate + 10_000) // (the audio's own length, with time to spare)
                while (i < audio.size && !stop && System.currentTimeMillis() - t0 < watchdog) { // in 50 ms pieces, so Halt is quick
                    if (Ic705.state.value.link != RigState.Link.CONNECTED) { lastError.value = "发射期间控制链路断开，已停止"; break }
                    val n = minOf(rate / 20, audio.size - i)
                    val w = track.write(audio, i, n)   // blocks while the buffer is full
                    if (w <= 0) break
                    i += w
                }
                if (!stop) { val tail = 250L; Thread.sleep(tail) } // let the buffered audio play out
                ok = i >= audio.size && !stop
            } catch (e: Exception) {
                lastError.value = "发射失败：${e.message ?: "未知错误"}"
            } finally {
                Ic705.ptt(false); Ic705.ptt(false)    // unkey (twice: CI-V has no acknowledgement here)
                try { track?.stop() } catch (e: Exception) { }; track?.release()
                keyed(false); thread = null
                onDone(ok)
            }
        }, "tx").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    /** Over WiFi: PTT (which opens the transmit stream), the audio as 12 kHz floats to the protocol code, which sends it
     *  in real time, then PTT off when it has had time to go. */
    private fun sendNet(audio: ShortArray, rate: Int, startAtMs: Long, onDone: (Boolean) -> Unit, txToken: Long): Boolean {
        stop = false; lastError.value = ""
        val n = (audio.size.toLong() * 12000 / rate).toInt() // samples at 12 kHz
        val f = FloatArray(n) { i -> val p = i.toDouble() * rate / 12000; val k = p.toInt(); val a = audio[minOf(k, audio.size - 1)]; val b = audio[minOf(k + 1, audio.size - 1)]; ((a + (b - a) * (p - k)) / 32768.0).toFloat() } // resampled
        thread = Thread({
            var ok = false
            try {
                val wait = startAtMs - System.currentTimeMillis() - 60; if (wait > 0) Thread.sleep(wait) // key 60 ms before
                if (stop || !TxControl.isCurrent(txToken)) return@Thread
                val link = uk.hamdigital.rig.IcomNet.rig // the link it goes out on
                if (link == null || !uk.hamdigital.rig.IcomNet.radioStateReady() || !uk.hamdigital.rig.IcomNet.txAudioReady()) { lastError.value = "IC-705 参数或音频/控制通道未就绪，未发送"; return@Thread } // (checked again: arranged seconds ago)
                if (PttLock.locked.value || !TxControl.isCurrent(txToken)) { lastError.value = "PTT 已锁定，未发射"; return@Thread }
                Ic705.ptt(true); keyed(true)          // PTT over the network
                Thread.sleep(60)
                if (!uk.hamdigital.rig.IcomNet.sendAudio(f)) { lastError.value = "IC-705 音频通道未就绪，已停止发射"; return@Thread } // streamed by the protocol code in 20 ms packages
                val end = System.currentTimeMillis() + n * 1000L / 12000 + 200 // its length, plus the protocol's lead-in
                var dropped = false
                while (!stop && System.currentTimeMillis() < end) { // (at most 110.6 s + 0.2: within the watchdog)
                    if (!TxControl.isCurrent(txToken) || uk.hamdigital.rig.IcomNet.rig !== link || !uk.hamdigital.rig.IcomNet.txAudioReady()) { dropped = true; lastError.value = "IC-705 WiFi/音频链路中断，已停止发射"; break }
                    Thread.sleep(20)
                }
                ok = !stop && !dropped
            } catch (e: Exception) { lastError.value = "发射失败：${e.message ?: "未知错误"}" }
            finally { Ic705.ptt(false); keyed(false); thread = null; onDone(ok) }
        }, "tx-net").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    private val sent = ArrayDeque<LongArray>()        // the last few transmissions: [keyed, unkeyed] UTC ms (unkeyed MAX while on)

    private fun keyed(on: Boolean) {                  // PTT on / off: noted for sentDuring
        synchronized(sent) {
            if (on) { sent.addLast(longArrayOf(System.currentTimeMillis(), Long.MAX_VALUE)); while (sent.size > 8) sent.removeFirst() }
            else sent.lastOrNull()?.let { if (it[1] == Long.MAX_VALUE) it[1] = System.currentTimeMillis() }
        }
        _on.value = on
    }

    /** Whether the app transmitted at any time from [fromMs] to [toMs] (UTC ms). The radio passes its own transmit audio
     *  back while keyed, so a slot we sent in decodes as our own message at +40 dB (found on the air, 0.8.6): the
     *  decoders skip such slots, as WSJT-X does - the radio hears nobody else while it transmits. */
    fun sentDuring(fromMs: Long, toMs: Long): Boolean = synchronized(sent) { sent.any { it[0] < toMs && it[1] > fromMs } }

    // ---- A stream: audio made as it goes (FreeDV voice while the talk button is held) ----
    @Volatile private var streamOn = false            // streaming now
    private var streamTrack: AudioTrack? = null       // USB: the radio's sound card
    private var streamRate = 8000                     // the stream's sample rate
    private var rsT = 0.0; private var rsPrev: Short = 0 // WiFi: resampling to 12 kHz (position, last sample)
    private var streamWatch: java.util.Timer? = null  // the time-out and the link check
    private var streamFocusManager: AudioManager? = null
    private var streamFocusRequest: AudioFocusRequest? = null
    private const val STREAM_MAX_MS = 120_000L        // longest voice stream (2 minutes): a stuck PTT releases here
    private val streamLock = Any()

    /** Key the radio for a stream of [rate] Hz audio from mode [tag] (then streamWrite, stopStream). False (with
     *  lastError) if it cannot start - the same checks as send. */
    fun startStream(ctx: Context, callsign: String, rate: Int, tag: String): Boolean = synchronized(streamLock) {
        blocked(ctx, callsign)?.let { lastError.value = it; return false }
        if (thread != null || streamOn) { lastError.value = "已有发射任务"; return false }
        if (Ic705.net && !uk.hamdigital.rig.IcomNet.streamAvailable()) { lastError.value = "IC-705 音频/CI-V 通道未就绪"; return false }
        if (!acquireStreamFocus(ctx)) { lastError.value = "音频通道被其他应用占用，未发射"; return false }
        val txToken = TxControl.beginTx()
        owner = tag; stop = false; lastError.value = ""; streamRate = rate; rsT = 0.0; rsPrev = 0; streamFrames = 0; streamGeneration = txToken
        val link = uk.hamdigital.rig.IcomNet.rig
        var track: AudioTrack? = null
        try {
            if (PttLock.locked.value || !TxControl.isCurrent(txToken)) throw IllegalStateException("PTT 已锁定")
            if (Ic705.net) {
                Ic705.ptt(true)                       // set PTT before the stream thread checks isPttOn
                uk.hamdigital.rig.IcomNet.streamStart()
            } else {
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 5 * 2))
                    .build()
                track.setPreferredDevice(usbOutput(ctx))
                track.play()
            }
            streamOn = true
            if (!Ic705.net) Ic705.ptt(true)
            keyed(true)
        } catch (e: Exception) {
            streamOn = false
            runCatching { track?.stop() }; runCatching { track?.release() }
            Ic705.ptt(false); Ic705.ptt(false); keyed(false)
            releaseStreamFocus()
            lastError.value = "发射启动失败：" + (e.message ?: "未知错误")
            return false
        }
        streamTrack = track
        val startMs = System.currentTimeMillis()
        streamWatch = java.util.Timer("tx-stream", true).apply {
            scheduleAtFixedRate(object : java.util.TimerTask() { override fun run() {
                val why = when {
                    System.currentTimeMillis() - startMs > STREAM_MAX_MS -> "发射超过 2 分钟，已自动停止"
                    !TxControl.isCurrent(streamGeneration) -> "发射已被后台安全机制停止"
                    Ic705.net && (uk.hamdigital.rig.IcomNet.rig !== link || !uk.hamdigital.rig.IcomNet.loggedIn) -> "IC-705 WiFi 链路断开，已停止发射"
                    !Ic705.net && Ic705.state.value.link != RigState.Link.CONNECTED -> "IC-705 控制链路断开，已停止发射"
                    else -> null
                }
                if (why != null) { lastError.value = why; stopStream() }
            } }, 200, 200)
        }
        true
    }

    /** The stream's next samples (at its rate). Blocks briefly over USB while the sound card's buffer is full. */
    fun streamWrite(s: ShortArray, n: Int = s.size) {
        if (!streamOn) return
        if (Ic705.net) {                              // to 12 kHz (linear), into the network queue
            val step = streamRate / 12000.0; val out = ShortArray((n / step).toInt() + 2); var m = 0
            for (i in 0 until n) { val cur = s[i]
                while (rsT <= 1.0 && m < out.size) { out[m++] = (rsPrev + (cur - rsPrev) * rsT).toInt().toShort(); rsT += step }
                rsT -= 1.0; rsPrev = cur }
            uk.hamdigital.rig.IcomNet.streamPush(out, m)
        } else streamTrack?.let { t -> try { val w = t.write(s, 0, n); if (w > 0) streamFrames += w } catch (e: Exception) { } }
    }
    private var streamFrames = 0L                     // USB: samples written to the sound card this stream
    @Volatile private var streamGeneration = 0L

    /** Wait (up to 3 s) until the audio written so far has gone out to the radio - before stopStream, so the end of
     *  an over (RADE: the end-of-over frame with the callsign) is not cut off. */
    fun drainStream() {
        val until = System.currentTimeMillis() + 3000
        while (streamOn && System.currentTimeMillis() < until) {
            val left = if (Ic705.net) uk.hamdigital.rig.IcomNet.streamQueued() // WiFi: samples still in the queue
                       else streamTrack?.let { t -> (streamFrames - (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)).toInt() } ?: 0 // USB: not yet played
            if (left <= 0) break
            Thread.sleep(20)
        }
        if (streamOn) Thread.sleep(60)               // (the last packet / buffer on its way)
    }

    /** End the stream: always force PTT off, even if the stream was already marked stopped. */
    fun stopStream(expectedOwner: String? = null) {
        synchronized(streamLock) {
            if (expectedOwner != null && owner != expectedOwner) return
            val wasOn = streamOn
            streamOn = false; streamWatch?.cancel(); streamWatch = null
            if (wasOn && Ic705.net) uk.hamdigital.rig.IcomNet.streamStop()
            streamTrack?.let { t -> try { t.stop() } catch (e: Exception) { }; t.release() }; streamTrack = null
            if (Ic705.net) uk.hamdigital.rig.IcomNet.forceUnkey()
            else repeat(2) { Ic705.ptt(false) }
            keyed(false)
            releaseStreamFocus()
        }
    }

    private fun acquireStreamFocus(ctx: Context): Boolean {
        val manager = ctx.getSystemService(AudioManager::class.java) ?: return false
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) stopStream()
            }
            .build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        streamFocusManager = manager
        streamFocusRequest = request
        return true
    }

    private fun releaseStreamFocus() {
        val request = streamFocusRequest
        val manager = streamFocusManager
        streamFocusRequest = null
        streamFocusManager = null
        if (request != null) runCatching { manager?.abandonAudioFocusRequest(request) }
    }

    /** Streaming now. */
    val streaming get() = streamOn

    /** Stop transmitting now (and drop a transmission waiting to start, or end a stream). */
    fun halt() { TxControl.cancelTx(); stop = true; if (streamOn) stopStream() else if (thread == null) { if (Ic705.net) uk.hamdigital.rig.IcomNet.forceUnkey() else Ic705.ptt(false) } }
}
