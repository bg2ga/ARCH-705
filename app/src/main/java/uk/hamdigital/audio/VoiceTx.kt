package uk.hamdigital.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.core.Settings
import uk.hamdigital.rig.Ic705

object VoiceTx {
    val active = MutableStateFlow(false)
    val lastError = MutableStateFlow("")

    private val lock = Any()
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private var session = 0L

    fun start(ctx: Context, settings: Settings): Boolean = synchronized(lock) {
        if (active.value) {
            lastError.value = "已有发射任务"
            return false
        }
        lastError.value = ""
        Transmitter.blocked(ctx, settings.callsign)?.let { why ->
            lastError.value = why
            return false
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lastError.value = "请先在 App 设置中授予麦克风权限"
            return false
        }
        val mySession = ++session
        val min = AudioRecord.getMinBufferSize(12000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val input = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, 12000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4800))
        } catch (e: Exception) {
            lastError.value = "麦克风初始化失败：" + (e.message ?: "未知错误")
            return false
        }
        if (input.state != AudioRecord.STATE_INITIALIZED) {
            input.release()
            lastError.value = "麦克风初始化失败"
            return false
        }
        try { input.startRecording() } catch (e: Exception) {
            input.release()
            lastError.value = "无法启动麦克风：" + (e.message ?: "未知错误")
            return false
        }
        if (!Transmitter.startStream(ctx, settings.callsign, 12000, "VOICE", mySession) || !Transmitter.streaming) {
            runCatching { input.stop() }
            input.release()
            lastError.value = Transmitter.lastError.value.ifBlank { "PTT 启动失败" }
            return false
        }
        recorder = input
        active.value = true
        thread = Thread({
            val pcm = ShortArray(240)
            try {
                while (active.value && session == mySession) {
                    val n = input.read(pcm, 0, pcm.size)
                    if (n < 0) throw IllegalStateException("AudioRecord.read=$n")
                    if (n > 0 && active.value && session == mySession) Transmitter.streamWrite(pcm, n)
                }
            } catch (e: Exception) {
                if (active.value && session == mySession) {
                    lastError.value = "麦克风中断，已停止发射：" + (e.message ?: "未知错误")
                    stopSession(mySession, releaseTransmitter = true)
                }
            }
        }, "arch705-voice-tx").also { it.start() }
        true
    }

    fun stop() {
        val current = synchronized(lock) { session }
        stopSession(current, releaseTransmitter = true)
    }

    internal fun currentSession(): Long = synchronized(lock) { session }

    internal fun release(expected: Long) {
        if (expected <= 0L) stop() else stopSession(expected, releaseTransmitter = true)
    }

    internal fun cancelCapture(expected: Long = -1L) {
        if (expected <= 0L) return
        stopSession(expected, releaseTransmitter = false)
    }

    private fun stopSession(expected: Long, releaseTransmitter: Boolean) {
        val input: AudioRecord?
        val oldThread: Thread?
        var wasStreaming = false
        var stoppedSession = 0L
        synchronized(lock) {
            if (session != expected) return
            active.value = false
            session++
            stoppedSession = session
            input = recorder
            recorder = null
            oldThread = thread
            thread = null
            oldThread?.interrupt()
            wasStreaming = Transmitter.streaming
        }
        runCatching { input?.stop() }
        runCatching { input?.release() }
        if (releaseTransmitter) {
            if (wasStreaming) Transmitter.stopStream("VOICE", expected)
            else if (synchronized(lock) { session == stoppedSession }) {
                if (Ic705.net) uk.hamdigital.rig.IcomNet.forceUnkey() else Ic705.ptt(false)
            }
        }
        if (oldThread !== Thread.currentThread()) runCatching { oldThread?.join(300) }
    }
}
