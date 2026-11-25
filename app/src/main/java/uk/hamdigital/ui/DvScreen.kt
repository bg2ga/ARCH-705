package uk.hamdigital.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.hamdigital.audio.AudioOut
import uk.hamdigital.audio.Spectrum
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.core.Mode
import uk.hamdigital.rig.Ic705
import uk.hamdigital.rig.RigMode
import uk.hamdigital.rig.RigState

/** IC-705 internal DV / D-STAR voice: receive audio locally and stream microphone PCM directly to the radio. */
@Composable
fun DvScreen(vm: uk.hamdigital.MainViewModel, initialDigitalMode: String = "DV") {
    val s by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val rig by Ic705.state.collectAsStateWithLifecycle()
    val txOn by Transmitter.on.collectAsStateWithLifecycle()
    var txActive by remember { mutableStateOf(false) }
    var record by remember { mutableStateOf<AudioRecord?>(null) }
    var txThread by remember { mutableStateOf<Thread?>(null) }
    var txError by remember { mutableStateOf("") }
    val gate = rememberTxGate(vm)
    var digitalMode by remember(initialDigitalMode) { mutableStateOf(initialDigitalMode) }
    val spec = remember { Spectrum(12000, maxHz = 3000) }
    val audioError by AudioOut.error.collectAsStateWithLifecycle()
    val rx = rememberRx(12000, 240, s.audio) { b, n ->
        spec.feed(b, n)
        AudioOut.write12k(b, n)
    }

    DisposableEffect(Unit) {
        AudioOut.open()
        onDispose {
            AudioOut.close()
            runCatching { record?.stop() }; runCatching { record?.release() }
            if (Transmitter.streaming && Transmitter.owner == "DSTAR") Transmitter.stopStream("DSTAR")
        }
    }

    fun stopTx() {
        if (!txActive) return
        txActive = false
        val old = txThread
        txThread = null
        old?.interrupt()
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        if (Transmitter.streaming && Transmitter.owner == "DSTAR") Transmitter.stopStream("DSTAR")
        else if (Transmitter.owner == "DSTAR") Transmitter.stopStream("DSTAR")
    }

    fun startTx() {
        txError = ""
        if (!DigitalVoiceModes.appCanSelect(digitalMode)) { txError = "DMR/C4FM/P25 当前为接收模式"; return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { txError = "请先允许麦克风权限"; return }
        if (!Ic705.isIn(RigMode.DV, rig)) { txError = "请先切换到 DV / D-STAR 模式。"; return }
        val min = AudioRecord.getMinBufferSize(12000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try { AudioRecord(MediaRecorder.AudioSource.MIC, 12000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4800)) }
                catch (e: Exception) { txError = "麦克风初始化失败：" + (e.message ?: "未知错误"); return }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); txError = "麦克风初始化失败。"; return }
        try { r.startRecording() } catch (e: Exception) {
            r.release(); txError = "无法启动麦克风：" + (e.message ?: "未知错误"); Ic705.ptt(false); return
        }
        if (!Transmitter.startStream(ctx, s.callsign, 12000, "DSTAR")) {
            runCatching { r.stop() }; r.release(); txError = Transmitter.lastError.value; return
        }
        record = r
        txActive = true
        txThread = Thread({
            val pcm = ShortArray(240)
            try {
                while (txActive) {
                    val n = r.read(pcm, 0, pcm.size)
                    if (n < 0) throw IllegalStateException("AudioRecord.read=$n")
                    if (n > 0) Transmitter.streamWrite(pcm, n)
                }
            } catch (e: Exception) {
                if (txActive) {
                    txError = "发射麦克风中断，已强制停止 PTT：" + (e.message ?: "未知错误")
                    stopTx()
                }
            }
        }, "arch705-dv-tx").also { it.start() }
    }

    LaunchedEffect(txOn) {
        if (txActive && !txOn) {
            stopTx()
            txError = "发射已由安全机制停止"
        }
    }

    ModeFrame(if (initialDigitalMode == "DV") "DV / D-STAR" else "数字语音接收", { stopTx(); vm.back() }) {
        RigBar(Mode.DSTAR, autoTune = initialDigitalMode == "DV")
        val appVoice = DigitalVoiceModes.appCanSelect(digitalMode)
    LaunchedEffect(rig.freqHz, rig.sql) {
        if (!appVoice && rig.freqHz >= 144_000_000L && rig.sql == 0) Ic705.setSquelch(77)
    }
        RxStatus(rx, audioError ?: if (appVoice && rig.mode != "DV") "需要 DV 模式" else "")
        Spacer(Modifier.height(10.dp))
        uk.hamdigital.ui.Text(if (appVoice) "D-STAR 数字语音" else "DMR / C4FM / P25 接收", color = Pal.Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        uk.hamdigital.ui.Text(if (appVoice) "IC-705 内部完成 AMBE 数字语音编解码。手机通过 Wi-Fi 将麦克风 PCM 直接发送到电台，接收音频直接播放。" else "DMR/C4FM/P25 由 BI1BIF 固件内部解码；此页面仅接收，需在电台 MODE 菜单选择子模式。", color = Pal.Text2, fontSize = 13.sp, lineHeight = 18.sp)
        DigitalVoiceStatusPanel(rig.digitalVoice, rig.link == RigState.Link.CONNECTED)
        uk.hamdigital.ui.Text(if (rig.link == RigState.Link.CONNECTED) "电台：" + rig.freqText + "  " + rig.modeText else "电台未连接", color = if (rig.link == RigState.Link.CONNECTED) Pal.Green else Pal.Amber, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        uk.hamdigital.ui.Text("BI1BIF v1.5.x 数字语音接收（电台面板选择）", color = Pal.Cyan, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DigitalVoiceModes.options.forEach { item ->
                SmallChip(item, digitalMode == item) {
                    digitalMode = item
                    val modeName = DigitalVoiceModes.name(item)
                    if (DigitalVoiceModes.appCanSelect(item)) Ic705.setMode(RigMode.DV)
                    txError = if (modeName == "DV") "" else "请在电台 MODE 菜单中连续点按 DV，选择 " + modeName + " 接收"
                }
            }
        }
        if (!appVoice) uk.hamdigital.ui.Text(txError.ifBlank { "* 需在电台面板选择；使用前确认 SD 卡 IC-705/DMRRX.BIN 已就位" }, color = Pal.Amber, fontSize = 11.sp)
        if (txError.isNotEmpty()) uk.hamdigital.ui.Text(txError, color = Pal.Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Waterfall(spec, Modifier.fillMaxWidth().weight(1f), rows = 180)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(132.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(116.dp).background(if (txActive && txOn) Color(0xFFB00020) else if (appVoice) Pal.Tert else Color(0xFF252A2C), CircleShape)
                .then(if (appVoice) Modifier.pointerInput(gate) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        if (s.txOk) startTx() else gate.ask { txError = "已接受发射许可，请再次按住 PTT" }
                        waitForUpOrCancellation()
                        stopTx()
                    }
                } else Modifier), contentAlignment = Alignment.Center) {
                uk.hamdigital.ui.Text(if (!appVoice) "仅接收" else if (txActive && txOn) "发射中" else "按住 PTT", color = if (txActive) Color.White else Pal.Red, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        uk.hamdigital.ui.Text("松开 PTT 即恢复接收；最长发射时间由发射安全看门狗限制。", color = Pal.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
    }
}

