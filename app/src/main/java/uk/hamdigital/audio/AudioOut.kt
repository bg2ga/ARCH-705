package uk.hamdigital.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/** Plays the IC-705's 12 kHz receive stream through the phone speaker. */
object AudioOut {
    private const val TAG = "AudioOut"
    val error = MutableStateFlow<String?>(null)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var outputRate = 0
    @Volatile private var paused = false
    private var resamplePos = 0.0
    private var previous = 0

    @Synchronized
    fun open(): String? {
        if (track != null) {
            if (paused) {
                runCatching { track?.play() }
                paused = false
            }
            return null
        }
        var last = "手机不支持可用的音频输出格式"
        for (rate in listOf(48000, 44100, 24000, 12000)) {
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            try {
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))
                    .build()
                if (t.state != AudioTrack.STATE_INITIALIZED) {
                    t.release()
                    continue
                }
                t.play()
                track = t
                outputRate = rate
                paused = false
                resamplePos = 0.0
                previous = 0
                error.value = null
                Log.i(TAG, "音频输出已打开：${rate} Hz")
                return null
            } catch (e: Exception) {
                last = e.message ?: last
            }
        }
        error.value = "音频输出打开失败：$last"
        Log.w(TAG, error.value!!)
        return error.value
    }

    @Synchronized
    fun close() {
        val t = track
        track = null
        outputRate = 0
        paused = false
        resamplePos = 0.0
        previous = 0
        if (t != null) {
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.stop() }
            runCatching { t.release() }
        }
    }

    /** Pause phone-side playback while the app is in the background. */
    @Synchronized
    fun pause() {
        paused = true
        runCatching { track?.pause() }
    }

    /** Resume phone-side playback when the app returns to the foreground. */
    @Synchronized
    fun resume() {
        if (track == null) { open(); return }
        if (paused) {
            runCatching { track?.play() }
            paused = false
        }
    }

    /** Accepts the protocol's 12 kHz mono PCM and converts it to the device's working rate. */
    @Synchronized
    fun write12k(samples: ShortArray, count: Int) {
        if (paused) return
        val out = track ?: return
        if (count <= 0 || outputRate <= 0) return
        try {
            if (outputRate == 12000) {
                writeAvailable(out, samples, count)
                return
            }
            val step = 12000.0 / outputRate
            val target = (count / step).toInt() + 1
            val converted = ShortArray(target)
            var at = 0
            var generated = 0
            while (at < count && generated < converted.size) {
                val i = at.coerceAtMost(count - 1)
                val fraction = (resamplePos - at).coerceIn(0.0, 1.0)
                val a = if (at == 0) previous else samples[at - 1].toInt()
                val b = samples[i].toInt()
                converted[generated++] = (a + (b - a) * fraction).toInt().toShort()
                resamplePos += step
                at = resamplePos.toInt()
            }
            resamplePos -= count
            previous = samples[count - 1].toInt()
            writeAvailable(out, converted, generated)
        } catch (e: Exception) {
            error.value = "音频输出异常：${e.message ?: "未知错误"}"
            Log.w(TAG, error.value!!)
        }
    }

    private fun writeAvailable(out: AudioTrack, data: ShortArray, count: Int) {
        var offset = 0
        var retries = 0
        while (offset < count) {
            val written = out.write(data, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (written == AudioTrack.ERROR_DEAD_OBJECT) {
                Log.w(TAG, "音频输出对象失效，正在重建")
                close()
                open()
                return
            }
            if (written < 0) { fail(written); return }
            if (written == 0) {
                if (++retries > 2) return
                Thread.sleep(2)
                continue
            }
            retries = 0
            offset += written
        }
    }

    private fun fail(code: Int) {
        error.value = "音频输出异常（$code）"
        Log.w(TAG, "音频输出失败：$code")
        close()
    }
}
