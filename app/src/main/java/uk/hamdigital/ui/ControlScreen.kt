package uk.hamdigital.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import uk.hamdigital.MainViewModel
import uk.hamdigital.RadioService
import uk.hamdigital.audio.AudioOut
import uk.hamdigital.Screen
import uk.hamdigital.core.AudioChoice
import uk.hamdigital.core.BandPlan
import uk.hamdigital.core.TxControl
import uk.hamdigital.rig.Ic705
import uk.hamdigital.rig.Ic705Codec
import uk.hamdigital.rig.IcomNet
import uk.hamdigital.rig.RigMode
import uk.hamdigital.rig.RigState
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class SavedChannel(
    val name: String,
    val hz: Long,
    val modeCode: Int,
    val data: Boolean,
    val toneMode: Int = 0,
    val toneTenths: Int = 0,
    val tsqlTenths: Int = 0,
    val dtcsCode: Int = 0,
    val split: Int = 0,
    val offsetHz: Long = 0,
)
private data class BandMemoryEntry(val hz: Long, val modeCode: Int, val data: Boolean)
internal data class MainModeChoice(val label: String, val code: Int, val data: Boolean)
internal val MAIN_MODES = listOf(
    MainModeChoice("USB", 0x01, false), MainModeChoice("LSB", 0x00, false),
    MainModeChoice("AM", 0x02, false), MainModeChoice("FM", 0x05, false),
    MainModeChoice("CW", 0x03, false), MainModeChoice("CW-R", 0x07, false),
    MainModeChoice("RTTY", 0x04, false), MainModeChoice("WFM", 0x06, false),
    MainModeChoice("USB-D", 0x01, true), MainModeChoice("LSB-D", 0x00, true),
    MainModeChoice("FM-D", 0x05, true), MainModeChoice("DV", 0x17, false),
)
private val STEP_OPTIONS = listOf(1 to "1 Hz", 10 to "10 Hz", 100 to "100 Hz", 1000 to "1000 Hz", 6250 to "6.25 KHz", 12500 to "12.5 KHz", 25000 to "25 KHz")
private val CTCSS_TENTHS = listOf(670, 693, 719, 744, 770, 797, 825, 854, 885, 915, 948, 974, 1000, 1035, 1072, 1109, 1148, 1188, 1230, 1273, 1318, 1365, 1413, 1462, 1514, 1567, 1598, 1622, 1655, 1679, 1713, 1738, 1773, 1799, 1835, 1862, 1928, 1966, 1995, 2035, 2065, 2107, 2181, 2257, 2291, 2336, 2418, 2503, 2541)
private val DTCS_CODES = listOf(23, 25, 26, 31, 32, 36, 43, 47, 51, 53, 54, 65, 71, 72, 73, 74, 114, 115, 116, 122, 125, 131, 132, 134, 143, 145, 152, 155, 156, 162, 165, 172, 174, 205, 212, 223, 225, 226, 243, 244, 245, 246, 251, 252, 255, 261, 263, 265, 266, 271, 274, 306, 311, 315, 325, 331, 332, 343, 346, 351, 356, 364, 365, 371, 411, 412, 413, 423, 431, 432, 445, 446, 452, 454, 455, 462, 464, 465, 466, 503, 506, 516, 523, 526, 532, 546, 565, 606, 612, 624, 627, 631, 632, 654, 662, 664, 703, 712, 723, 731, 732, 734, 746, 754)
@Composable
fun ControlScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rig by Ic705.state.collectAsStateWithLifecycle()
    val wifiLogin by IcomNet.loginState.collectAsStateWithLifecycle()
    val rxChoice = if (wifiLogin) AudioChoice.WIFI else settings.audio
    val rx = rememberRx(12000, 240, rxChoice, monitorWifi = false) { b, n -> AudioOut.write12k(b, n) }
    val audioOwner = remember { Any() }
    val latency by IcomNet.latencyMs.collectAsStateWithLifecycle()
    var lossPercent by remember { mutableFloatStateOf(0f) }
    val clockFormat = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var utcClock by remember { mutableStateOf(LocalTime.now(ZoneOffset.UTC).format(clockFormat)) }
    var bjcClock by remember { mutableStateOf(LocalTime.now(ZoneId.of("Asia/Shanghai")).format(clockFormat)) }
    LaunchedEffect(Unit) {
        while (true) {
            utcClock = LocalTime.now(ZoneOffset.UTC).format(clockFormat)
            bjcClock = LocalTime.now(ZoneId.of("Asia/Shanghai")).format(clockFormat)
            delay(1000)
        }
    }
    DisposableEffect(Unit) {
        AudioOut.open(audioOwner)
        onDispose { AudioOut.close(audioOwner) }
    }
    LaunchedEffect(rig.link) {
        while (rig.link == RigState.Link.CONNECTED) {
            if (IcomNet.scope.value == null || !IcomNet.scopeFresh()) Ic705.requestScope()
            val stats = IcomNet.rxAudioStats()
            val received = stats?.getOrNull(0) ?: 0L
            val lost = stats?.getOrNull(1) ?: 0L
            lossPercent = if (received + lost > 0L) lost * 100f / (received + lost) else 0f
            delay(1000)
        }
        lossPercent = 0f
    }
    var spectrumPreviewHz by remember { mutableStateOf<Long?>(null) }
    var spectrumLocked by rememberSaveable { mutableStateOf(false) }
    var levelsLocked by rememberSaveable { mutableStateOf(true) }
    var frequencyEntryVfo by rememberSaveable { mutableStateOf<Int?>(null) }
    var frequencyEntryText by rememberSaveable { mutableStateOf("") }
    var frequencyEntryMode by remember { mutableStateOf<MainModeChoice?>(null) }
    var frequencyEntryError by rememberSaveable { mutableStateOf("") }
    var channelsShown by rememberSaveable { mutableIntStateOf(40) }
    val modeA = rig.modeA.ifBlank { if (rig.selectedVfo == 0) rig.modeText else "" }
    val modeB = rig.modeB.ifBlank { if (rig.selectedVfo == 1) rig.modeText else "" }
    var modeMenuVfo by rememberSaveable { mutableStateOf<Int?>(null) }
    var stepDialog by rememberSaveable { mutableStateOf(false) }
    var exitAsk by rememberSaveable { mutableStateOf(false) }
    var toneDialog by rememberSaveable { mutableStateOf(false) }
    var duplexDialog by rememberSaveable { mutableStateOf(false) }
    var saveChannelDialog by rememberSaveable { mutableStateOf(false) }
    var powerOffAsk by rememberSaveable { mutableStateOf(false) }
    var channelName by rememberSaveable { mutableStateOf("") }
    var meterMode by rememberSaveable { mutableIntStateOf(0) }
    val connected = rig.link == RigState.Link.CONNECTED
    val transmitting = rig.tx || rig.txRequested
    val controlsEnabled = connected && IcomNet.radioStateReady() && !transmitting && !rig.vfoSwitching
    val aHz = rig.freqAHz.takeIf { it > 0 } ?: if (rig.selectedVfo == 0) rig.freqHz else 0L
    val bHz = rig.freqBHz.takeIf { it > 0 } ?: if (rig.selectedVfo == 1) rig.freqHz else 0L
    val displayAHz = if (rig.selectedVfo == 0) spectrumPreviewHz ?: aHz else aHz
    val displayBHz = if (rig.selectedVfo == 1) spectrumPreviewHz ?: bHz else bHz
    val displayedMeterMode = if (transmitting && meterMode == 0) 1 else meterMode
    val ctx = LocalContext.current

    fun finishRemoteApp() {
        uk.hamdigital.ArchFloatingPttService.disableFromExit(ctx)
        uk.hamdigital.core.PttLock.lock()
        uk.hamdigital.audio.VoiceTx.stop()
        TxControl.stopAll()
        IcomNet.forceUnkey()
        IcomNet.disconnect {
            val app = ctx.applicationContext
            val activity = ctx as? Activity
            activity?.runOnUiThread {
                RadioService.stop(app)
                activity.finishAffinity()
            }
        }
    }

    fun stepFrequency(direction: Int) {
        if (!controlsEnabled || rig.freqHz <= 0) return
        val next = (rig.freqHz + direction.toLong() * settings.frequencyStepHz).coerceIn(30_000L, 1_300_000_000L)
        Ic705.setFrequency(next)
    }
    LaunchedEffect(transmitting) { if (transmitting) modeMenuVfo = null }
    LaunchedEffect(rig.selectedVfo, rig.link, spectrumLocked) { spectrumPreviewHz = null }

    fun selectVfo(vfo: Int) { if (controlsEnabled && rig.selectedVfo != vfo) if (vfo == 0) Ic705.selectVfoA() else Ic705.selectVfoB() }
    fun applyMainMode(vfo: Int, choice: MainModeChoice) {
        if (!controlsEnabled) return
        Ic705.setChannelModeForVfo(vfo, choice.code, choice.data)
        modeMenuVfo = null
    }

    fun openFrequencyEntry(vfo: Int) {
        if (transmitting) return
        if (rig.selectedVfo != vfo) {
            selectVfo(vfo)
            return
        }
        modeMenuVfo = null
        frequencyEntryText = ""
        frequencyEntryError = ""
        frequencyEntryMode = MAIN_MODES.firstOrNull { it.label == if (vfo == 0) modeA else modeB }
        frequencyEntryVfo = vfo
    }

    fun openModeMenu(vfo: Int) {
        if (transmitting || !connected || rig.vfoSwitching) return
        frequencyEntryVfo = null
        modeMenuVfo = vfo
    }

    fun editFrequencyEntry(key: String) {
        frequencyEntryError = ""
        frequencyEntryText = when (key) {
            "⌫" -> frequencyEntryText.dropLast(1)
            "清空" -> ""
            "." -> if (frequencyEntryText.contains('.') || frequencyEntryText.length >= 10) frequencyEntryText else if (frequencyEntryText.isEmpty()) "0." else frequencyEntryText + "."
            else -> if (key.length == 1 && key[0].isDigit() && frequencyEntryText.length < 10) frequencyEntryText + key else frequencyEntryText
        }
    }

    fun submitFrequencyEntry() {
        val vfo = frequencyEntryVfo ?: return
        val hz = frequencyEntryText.toDoubleOrNull()?.times(1_000_000.0)?.toLong()
        if (hz == null || hz !in 30_000L..1_300_000_000L) {
            frequencyEntryError = "请输入有效频率，例如 14.270"
            return
        }
        val choice = frequencyEntryMode
        if (!Ic705.setFrequencyForVfo(vfo, hz, choice?.code, choice?.data ?: false)) {
            frequencyEntryError = "正在切换 VFO，请稍候再试"
            return
        }
        spectrumPreviewHz = null
        frequencyEntryVfo = null
    }

    fun chooseBand(preset: uk.hamdigital.core.BandPreset) {
        if (!controlsEnabled) return
        val memories = parseBandMemory(settings.bandMemory).toMutableMap()
        if (rig.freqHz > 0 && rig.mode.isNotBlank()) {
            memories[BandPlan.labelFor(rig.freqHz)] = BandMemoryEntry(rig.freqHz, currentModeCode(rig), rig.data)
        }
        val saved = memories[preset.label]
        vm.updateSettings { it.copy(bandMemory = encodeBandMemory(memories)) }
        if (saved != null) {
            Ic705.setFrequency(saved.hz)
            Ic705.setChannelMode(saved.modeCode, saved.data)
        } else {
            Ic705.setFrequency(preset.hz)
            Ic705.setPreamp(preset.preampRaw)
            Ic705.setBasicMode(preset.modeCode)
            Ic705.setSquelch(preset.squelchRaw)
        }
    }

    fun saveCurrentChannel() {
        if (rig.freqHz <= 0) return
        val fallback = "${bandLabel(rig.freqHz)} ${rig.modeText.ifBlank { "频道" }}".trim()
        val name = channelName.trim().ifBlank { fallback }
        val modeCode = currentModeCode(rig)
        val current = SavedChannel(name.take(18), rig.freqHz, modeCode, rig.data,
            rig.toneMode.coerceAtLeast(0), rig.toneTenths, rig.tsqlTenths, rig.dtcsCode, rig.split, rig.offsetHz)
        val old = parseChannels(settings.savedChannels).filterNot {
            it.hz == current.hz && it.modeCode == current.modeCode && it.data == current.data &&
                it.toneMode == current.toneMode && it.toneTenths == current.toneTenths &&
                it.tsqlTenths == current.tsqlTenths && it.dtcsCode == current.dtcsCode &&
                it.split == current.split && it.offsetHz == current.offsetHz
        }
        val channels = listOf(current) + old
        vm.updateSettings { it.copy(savedChannels = encodeChannels(channels)) }
        saveChannelDialog = false
    }

    LaunchedEffect(rig.freqHz, rig.mode, rig.data) {
        if (rig.freqHz <= 0 || rig.mode.isBlank()) return@LaunchedEffect
        delay(350)
        val key = BandPlan.labelFor(rig.freqHz)
        val entry = BandMemoryEntry(rig.freqHz, currentModeCode(rig), rig.data)
        val old = parseBandMemory(settings.bandMemory)[key]
        if (old != entry) vm.updateSettings {
            it.copy(bandMemory = encodeBandMemory(parseBandMemory(it.bandMemory) + (key to entry)))
        }
    }

    if (saveChannelDialog) AlertDialog(
        onDismissRequest = { saveChannelDialog = false },
        title = { uk.hamdigital.ui.Text("保存频道") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                uk.hamdigital.ui.Text(formatHz(rig.freqHz) + "  " + rig.modeText, color = Pal.Text2, fontSize = 13.sp)
                OutlinedTextField(channelName, { channelName = it.take(18) }, label = { uk.hamdigital.ui.Text("频道名称") }, singleLine = true)
            }
        },
        confirmButton = { TextButton({ saveCurrentChannel() }) { uk.hamdigital.ui.Text("保存") } },
        dismissButton = { TextButton({ saveChannelDialog = false }) { uk.hamdigital.ui.Text("取消") } },
    )

    if (powerOffAsk) AlertDialog(
        onDismissRequest = { powerOffAsk = false },
        title = { uk.hamdigital.ui.Text("软关机") },
        text = { uk.hamdigital.ui.Text("确定要让 IC-705 软关机吗？关机后当前远程会话会断开。") },
        confirmButton = { TextButton({ powerOffAsk = false; Thread({ IcomNet.powerOff() }, "arch-power-off").start() }) { uk.hamdigital.ui.Text("关机", color = Pal.Red) } },
        dismissButton = { TextButton({ powerOffAsk = false }) { uk.hamdigital.ui.Text("取消") } },
    )


    BackHandler { exitAsk = true }

    if (stepDialog) StepPickerDialog(
        current = settings.frequencyStepHz,
        onChoose = { value -> vm.updateSettings { it.copy(frequencyStepHz = value) }; stepDialog = false },
        onDismiss = { stepDialog = false },
    )

    if (toneDialog) ToneControlDialog(
        rig = rig,
        enabled = controlsEnabled,
        onMode = { mode -> Ic705.setToneMode(mode) },
        onTone = { toneMode, tenths -> if (toneMode == 2) Ic705.setTsqlFrequency(tenths) else Ic705.setToneFrequency(tenths) },
        onDtcs = { code -> Ic705.setDtcsCode(code) },
        onDismiss = { toneDialog = false },
    )

    if (duplexDialog) DuplexControlDialog(
        rig = rig,
        enabled = controlsEnabled,
        onApply = { mode, offset -> Ic705.setSplit(mode); if (offset > 0) Ic705.setOffsetHz(offset) },
        onDismiss = { duplexDialog = false },
    )

    if (exitAsk) AlertDialog(
        onDismissRequest = { exitAsk = false },
        title = { uk.hamdigital.ui.Text("退出 ARCH-705") },
        text = { uk.hamdigital.ui.Text("退出后会停止后台助手并通知 IC-705 结束远程会话，但不会清除已保存的地址和密码。确定退出吗？") },
        confirmButton = { TextButton({ exitAsk = false; finishRemoteApp() }) { uk.hamdigital.ui.Text("退出", color = Pal.Red) } },
        dismissButton = { TextButton({ exitAsk = false }) { uk.hamdigital.ui.Text("取消") } },
    )

    modeMenuVfo?.let { vfo ->
        AlertDialog(
            onDismissRequest = { modeMenuVfo = null },
            title = { uk.hamdigital.ui.Text(if (vfo == 0) "VFO A 模式" else "VFO B 模式") },
            text = {
                ChoiceGrid(MAIN_MODES.map { choice ->
                    ControlChoice(choice.label, if (vfo == 0) modeA == choice.label else modeB == choice.label) {
                        applyMainMode(vfo, choice)
                    }
                }, columns = 3)
            },
            confirmButton = { TextButton({ modeMenuVfo = null }) { uk.hamdigital.ui.Text("取消") } },
        )
    }

    frequencyEntryVfo?.let { vfo ->
        FrequencyEntryDialog(
            vfo = vfo,
            currentHz = if (vfo == 0) displayAHz else displayBHz,
            currentMode = if (vfo == 0) modeA else modeB,
            value = frequencyEntryText,
            selectedMode = frequencyEntryMode,
            error = frequencyEntryError,
            enabled = controlsEnabled,
            onKey = ::editFrequencyEntry,
            onMode = { frequencyEntryMode = it },
            onConfirm = ::submitFrequencyEntry,
            onDismiss = { frequencyEntryVfo = null },
        )
    }

    ModeFrame("ARCH-705", { vm.back() }, showBack = false, actions = {
        val powerColor = if (connected) Pal.Green else Pal.Red
        ArchPressable(onClick = { if (connected) powerOffAsk = true else vm.show(Screen.Connect) }, modifier = Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(powerColor.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PowerSettingsNew, if (connected) "软关机" else "软开机", tint = powerColor, modifier = Modifier.size(20.dp))
            }
        }
        ArchNavAction("设置") { vm.show(Screen.SettingsPage) }
        ArchPressable(onClick = { exitAsk = true }, modifier = Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Pal.Red.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, "退出软件", tint = Pal.Red, modifier = Modifier.size(20.dp))
            }
        }
    }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 2.dp, bottom = if (settings.floatingPtt) 104.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ArchCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 9.dp, vertical = 7.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        uk.hamdigital.ui.Text(
                            if (connected) "IC-705 · 已连接 · 延迟 ${if (latency >= 0) "$latency ms" else "—"} · 丢包 ${"%.1f".format(lossPercent)}%" else "IC-705 · 等待连接",
                            color = if (connected) Pal.Green else Pal.Amber,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            uk.hamdigital.ui.Text("UTC $utcClock", color = Pal.Amber, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                            uk.hamdigital.ui.Text("BJC $bjcClock", color = Pal.Red, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VfoFrequencyCell(
                            label = "VFO A", hz = displayAHz, mode = modeA, selected = rig.selectedVfo == 0, enabled = controlsEnabled, frequencyEntryEnabled = !transmitting,
                            modifier = Modifier.weight(1f), onSelect = { selectVfo(0) }, onFrequency = { openFrequencyEntry(0) }, onMode = { openModeMenu(0) },
                        )
                        VfoFrequencyCell(
                            label = "VFO B", hz = displayBHz, mode = modeB, selected = rig.selectedVfo == 1, enabled = controlsEnabled, frequencyEntryEnabled = !transmitting,
                            modifier = Modifier.weight(1f), onSelect = { selectVfo(1) }, onFrequency = { openFrequencyEntry(1) }, onMode = { openModeMenu(1) },
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        uk.hamdigital.ui.Text(rig.modeText.ifBlank { "未连接" }, color = if (connected) Pal.Cyan else Pal.Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        if (rig.filter >= 1) uk.hamdigital.ui.Text("FIL ${rig.filter}", color = Pal.Muted, fontSize = 10.sp)
                        val topMeterRaw = if (transmitting) rig.powerMeter else rig.sMeter
                        val topMeterMode = if (transmitting) 1 else 0
                        if (topMeterRaw >= 0) uk.hamdigital.ui.Text(meterText(topMeterRaw, topMeterMode), color = if (!transmitting && topMeterRaw > 120) Pal.Red else Pal.Text2, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        if (rig.swrMeter >= 0) {
                            val swr = interpolate(rig.swrMeter, listOf(0 to 1f, 48 to 1.5f, 80 to 2f, 120 to 3f, 240 to 6f))
                            uk.hamdigital.ui.Text("SWR %.1f".format(swr), color = if (swr >= 2f) Pal.Red else if (swr >= 1.5f) Pal.Amber else Pal.Text2, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.weight(1f))
                        FrequencyStepControl(
                            stepHz = settings.frequencyStepHz,
                            enabled = controlsEnabled,
                            onStep = ::stepFrequency,
                            onChoose = { stepDialog = true },
                            modifier = Modifier.width(124.dp),
                            compact = true,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    MeterPanel(rig, displayedMeterMode, Modifier.fillMaxWidth())
                    MeterSelector(displayedMeterMode) { meterMode = it }
                    IcomScopePanel(
                        Modifier.fillMaxWidth().height(180.dp).padding(top = 3.dp),
                        showLabels = false,
                        showHeader = true,
                        spanText = scopeSpanLabel(rig.scopeSpan),
                        speedText = "谱速${when (rig.scopeSpeed) { 0 -> "快"; 1 -> "中"; 2 -> "慢"; else -> "—" }}",
                        refreshFps = settings.scopeRefreshFps,
                        enabled = controlsEnabled,
                        tuneLocked = spectrumLocked,
                        lockText = if (spectrumLocked) "已锁定" else "锁定",
                        onLockClick = { spectrumLocked = !spectrumLocked },
                        onSpanClick = if (controlsEnabled) ({ Ic705.setScopeSpan(nextScopeSpan(rig.scopeSpan)) }) else null,
                        onSpeedClick = if (controlsEnabled) ({ Ic705.setScopeSpeed(when (rig.scopeSpeed) { 0 -> 1; 1 -> 2; else -> 0 }) }) else null,
                        onRefreshClick = {
                            val values = listOf(30, 20, 15, 10, 5)
                            val next = values[(values.indexOf(settings.scopeRefreshFps).takeIf { it >= 0 }?.plus(1) ?: 0) % values.size]
                            vm.updateSettings { it.copy(scopeRefreshFps = next) }
                        },
                        onFrequencyPreview = { hz ->
                            if (!spectrumLocked) spectrumPreviewHz = hz
                        },
                        onFrequencyTap = { hz ->
                            if (controlsEnabled && !spectrumLocked) {
                                spectrumPreviewHz = null
                                Ic705.setFrequency(hz)
                            }
                        },
                    )
                    QuickFunctionRow(rig, controlsEnabled)
                    AdvancedFunctionRow(rig, controlsEnabled, onTone = { toneDialog = true }, onDuplex = { duplexDialog = true })
                }
            }

            ChoiceGrid(BandPlan.presets.map { preset ->
                ControlChoice(preset.label, BandPlan.labelFor(rig.freqHz) == preset.label) { chooseBand(preset) }
            }, columns = 5, enabled = controlsEnabled)

            ArchCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
                InlinePttButton(vm, height = 120.dp)
            }


            val channels = remember(settings.savedChannels) { parseChannels(settings.savedChannels) }
            val visibleChannels = channels.take(channelsShown)
            ArchCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
                Column {
                    ArchPressable(onClick = { channelName = ""; saveChannelDialog = true }, enabled = controlsEnabled, modifier = Modifier.fillMaxWidth().height(38.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxSize().clip(ArchControlShape).background(Pal.Accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            uk.hamdigital.ui.Text("信道存储", color = Pal.Cyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (visibleChannels.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        ChoiceGrid(visibleChannels.map { ch -> ControlChoice(ch.name) {
                            Ic705.setFrequency(ch.hz)
                            Ic705.setChannelMode(ch.modeCode, ch.data)
                            Ic705.applyChannelConfiguration(ch.toneMode, ch.toneTenths, ch.tsqlTenths, ch.dtcsCode, ch.split, ch.offsetHz)
                        } }, enabled = controlsEnabled)
                    }
                    if (visibleChannels.size < channels.size) {
                        TextButton({ channelsShown += 40 }, enabled = controlsEnabled) { uk.hamdigital.ui.Text("显示更多频道", color = Pal.Cyan, fontSize = 11.sp) }
                    }
                }
            }

            ArchCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RotaryKnob("RF 增益", if (rig.rfGain >= 0) percentValue(rig.rfGain) else 0, 0..100,
                            if (rig.rfGain >= 0) percentValue(rig.rfGain).toString() else "—", { Ic705.setRfGain(rawFromPercent(it)) }, controlsEnabled && !levelsLocked, Modifier.weight(1f))
                        RotaryKnob("静噪 SQL", if (rig.sql >= 0) percentValue(rig.sql) else 0, 0..100,
                            if (rig.sql >= 0) percentValue(rig.sql).toString() else "—", { Ic705.setSquelch(rawFromPercent(it)) }, controlsEnabled && !levelsLocked, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RotaryKnob("发射功率", if (rig.power >= 0) percentValue(rig.power) else 0, 0..100,
                            if (rig.power >= 0) percentValue(rig.power).toString() else "—", { Ic705.setPower(rawFromPercent(it)) }, controlsEnabled && !levelsLocked, Modifier.weight(1f))
                        RotaryKnob("NR 降噪", if (rig.nrLevel >= 0) nrValue(rig.nrLevel) else 0, 0..9,
                            if (rig.nr > 0 && rig.nrLevel > 0) nrValue(rig.nrLevel).toString() else "0", { Ic705.setNrLevel(rawFromNr(it)) }, controlsEnabled && !levelsLocked, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ Ic705.restoreBaselineLevels() }, enabled = controlsEnabled, modifier = Modifier.weight(1.1f)) { uk.hamdigital.ui.Text("恢复连接时数值", fontSize = 10.sp, maxLines = 1) }
                        TextButton(
                            onClick = { levelsLocked = !levelsLocked },
                            enabled = controlsEnabled,
                            modifier = Modifier.weight(0.8f),
                        ) { uk.hamdigital.ui.Text(if (levelsLocked) "锁定" else "已解锁", color = if (levelsLocked) Pal.Amber else Pal.Green, fontSize = 11.sp) }
                        TextButton({ Ic705.resetLevels() }, enabled = controlsEnabled, modifier = Modifier.weight(1.1f)) { uk.hamdigital.ui.Text("恢复安全值", fontSize = 10.sp, maxLines = 1) }
                    }
                }
            }

        }
    }
}

@Composable
private fun FrequencyStepControl(
    stepHz: Int,
    enabled: Boolean,
    onStep: (Int) -> Unit,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val gap = if (compact) 3.dp else 4.dp
    val corner = if (compact) 9.dp else 12.dp
    Row(
        modifier = modifier.padding(top = if (compact) 0.dp else 4.dp),
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("−", "降低频率", enabled, compact) { onStep(-1) }
        ArchPressable(
            onClick = onChoose,
            enabled = enabled,
            modifier = Modifier.width(if (compact) 52.dp else 82.dp).height(if (compact) 30.dp else 36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(corner)).background(Pal.IcomBlue.copy(alpha = 0.75f))
                    .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(corner)),
                contentAlignment = Alignment.Center,
            ) {
                uk.hamdigital.ui.Text(stepLabel(stepHz), color = Color.White, fontSize = if (compact) 9.sp else 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        StepButton("+", "提高频率", enabled, compact) { onStep(1) }
    }
}

@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, compact: Boolean, onStep: () -> Unit) {
    val latest by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    val buttonSize = if (compact) 32.dp else 38.dp
    val corner = if (compact) 9.dp else 12.dp
    Box(
        Modifier.size(buttonSize).clip(RoundedCornerShape(corner)).background(Color.White.copy(alpha = 0.075f))
            .border(1.dp, Pal.Border, RoundedCornerShape(corner))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    var repeated = false
                    val repeatJob = scope.launch {
                        delay(450)
                        repeated = true
                        while (isActive) {
                            latest()
                            delay(90)
                        }
                    }
                    val released = try {
                        tryAwaitRelease()
                    } finally {
                        repeatJob.cancelAndJoin()
                    }
                    if (released && !repeated) latest()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        uk.hamdigital.ui.Text(symbol, color = if (enabled) Pal.Text else Pal.Dim, fontSize = if (compact) 18.sp else 22.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StepPickerDialog(current: Int, onChoose: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { uk.hamdigital.ui.Text("频率步进") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                STEP_OPTIONS.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        pair.forEach { (value, label) ->
                            ArchPressable(onClick = { onChoose(value) }, modifier = Modifier.weight(1f).height(42.dp), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                        .background(if (current == value) Pal.IcomBlue else Pal.Surface2)
                                        .border(1.dp, if (current == value) Color.White.copy(alpha = 0.38f) else Pal.Border, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center,
                                ) { uk.hamdigital.ui.Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { uk.hamdigital.ui.Text("取消") } },
    )
}

private fun stepLabel(hz: Int): String = STEP_OPTIONS.firstOrNull { it.first == hz }?.second ?: "$hz Hz"

@Composable
internal fun FrequencyEntryDialog(
    vfo: Int,
    currentHz: Long,
    currentMode: String,
    value: String,
    selectedMode: MainModeChoice?,
    error: String,
    enabled: Boolean,
    showModes: Boolean = true,
    onKey: (String) -> Unit,
    onMode: (MainModeChoice) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val visibleModes = MAIN_MODES
    val enteredHz = value.toDoubleOrNull()?.times(1_000_000.0)?.toLong()
    val valid = enteredHz != null && enteredHz in 30_000L..1_300_000_000L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                uk.hamdigital.ui.Text(if (!showModes) (if (vfo == 0) "VFO A 频率" else "VFO B 频率") else (if (vfo == 0) "VFO A 频率与模式" else "VFO B 频率与模式"), modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "关闭", tint = Pal.Text2) }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(8.dp))
                        .background(Pal.Surface2).border(1.dp, if (valid) Pal.Cyan else Pal.Border, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    uk.hamdigital.ui.Text(
                        if (value.isBlank()) formatHz(currentHz) else value,
                        color = if (value.isBlank()) Pal.Muted else Color.White,
                        fontSize = 24.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    uk.hamdigital.ui.Text("MHz", color = Pal.Muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Column(Modifier.weight(if (showModes) 1.12f else 1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "⌫")).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { key ->
                                    ArchPressable(
                                        onClick = { onKey(key) },
                                        modifier = Modifier.weight(1f).height(44.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(
                                            Modifier.fillMaxSize().clip(RoundedCornerShape(7.dp))
                                                .background(Pal.Surface2)
                                                .border(1.dp, Pal.Border, RoundedCornerShape(7.dp)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            uk.hamdigital.ui.Text(key, color = Color.White, fontSize = if (key == "⌫") 17.sp else 20.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                        ArchPressable(onClick = { onKey("清空") }, modifier = Modifier.fillMaxWidth().height(38.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(7.dp))
                                    .background(Pal.Surface2)
                                    .border(1.dp, Pal.Border, RoundedCornerShape(7.dp)),
                                contentAlignment = Alignment.Center,
                            ) { uk.hamdigital.ui.Text("清空", color = Pal.Amber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                    if (showModes) Column(Modifier.weight(0.88f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        visibleModes.chunked(3).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                pair.forEach { choice ->
                                    val selected = selectedMode?.label == choice.label || (selectedMode == null && currentMode == choice.label)
                                    ArchPressable(onClick = { onMode(choice) }, modifier = Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.Center) {
                                        Box(
                                            Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))
                                                .background(if (selected) Pal.IcomBlue else Pal.Surface2)
                                                .border(1.dp, if (selected) Color.White.copy(alpha = 0.35f) else Pal.Border, RoundedCornerShape(4.dp)),
                                            contentAlignment = Alignment.Center,
                                        ) { uk.hamdigital.ui.Text(choice.label, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (error.isNotBlank()) uk.hamdigital.ui.Text(error, color = Pal.Red, fontSize = 10.sp)
            }
        },
        confirmButton = {
            ArchPressable(onClick = onConfirm, enabled = enabled && valid, modifier = Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                        .background(if (enabled && valid) Pal.IcomBlue else Pal.Surface3),
                    contentAlignment = Alignment.Center,
                ) { uk.hamdigital.ui.Text("调谐", color = if (enabled && valid) Color.White else Pal.Muted, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
        },
    )
}

@Composable
private fun VfoFrequencyCell(
    label: String,
    hz: Long,
    mode: String,
    selected: Boolean,
    enabled: Boolean,
    frequencyEntryEnabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onFrequency: () -> Unit,
    onMode: () -> Unit,
) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) Pal.Surface2 else Pal.Surface1)
            .border(1.dp, if (selected) Color.White.copy(alpha = 0.82f) else Pal.Separator, RoundedCornerShape(8.dp))
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(4.dp))
                .clickable(enabled = frequencyEntryEnabled) { if (selected) onFrequency() else onSelect() }
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            uk.hamdigital.ui.Text(label, color = Pal.Muted, fontSize = 7.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            uk.hamdigital.ui.Text(
                if (hz > 0) formatHz(hz) else "--.---.---",
                color = if (selected) Color.White else Pal.Muted,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                letterSpacing = (-0.7).sp,
                maxLines = 1,
            )
        }
        ArchPressable(onClick = onMode, enabled = enabled, modifier = Modifier.width(37.dp).height(28.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp))
                    .background(Pal.IcomBlue)
                    .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) { uk.hamdigital.ui.Text(mode.ifBlank { "—" }, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
    }
}

private data class ControlChoice(val label: String, val selected: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

@Composable
private fun ChoiceGrid(items: List<ControlChoice>, columns: Int = 4, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val shown = if (enabled) items else items.map { it.copy(enabled = false) }
    Column(modifier.fillMaxWidth()) {
        shown.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { item -> ControlChoiceCell(item, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ControlChoiceCell(item: ControlChoice, modifier: Modifier = Modifier) {
    val accent = when {
        item.label.contains("DV") || item.label.contains("D-STAR") -> Pal.Purple
        item.label.contains("CW") -> Pal.Amber
        item.label.contains("RTTY") || item.label.contains("PSK") -> Pal.Green
        item.label.contains("FM") || item.label.contains("AM") -> Color(0xFF70D4B4)
        item.label.contains("USB") || item.label.contains("LSB") -> Pal.Sky
        else -> Pal.Accent
    }
    ArchPressable(onClick = item.onClick, enabled = item.enabled, modifier = modifier.height(34.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(13.dp))
                .background(if (item.selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.055f))
                .border(1.dp, if (item.selected) accent.copy(alpha = 0.82f) else Pal.Border, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.selected) Box(Modifier.size(4.dp).clip(CircleShape).background(accent))
                uk.hamdigital.ui.Text(item.label, color = if (item.selected) Pal.Text else Pal.Text2, fontSize = 11.sp, fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MeterPanel(rig: RigState, mode: Int, modifier: Modifier = Modifier) {
    val raw = meterRaw(rig, mode)
    val level = when {
        raw < 0 -> 0f
        mode != 0 -> (raw / 255f).coerceIn(0f, 1f)
        raw <= 120 -> (raw / 120f) * 0.5f
        else -> 0.5f + ((raw - 120) / 121f).coerceIn(0f, 1f) * 0.5f
    }
    val valueColor = if (mode == 0 && raw > 120) Pal.Red else if (mode == 0) Pal.IcomBlueBright else Pal.Text
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            uk.hamdigital.ui.Text(meterName(mode), color = if (mode == 0) Pal.IcomBlueBright else Pal.Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            uk.hamdigital.ui.Text(meterText(raw, mode), color = valueColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Canvas(Modifier.fillMaxWidth().height(10.dp).padding(top = 2.dp)) {
            val segments = 30
            val gap = 1.dp.toPx()
            val w = (size.width - gap * (segments - 1)) / segments
            for (i in 0 until segments) {
                val x = i * (w + gap)
                val lit = (i + 0.5f) / segments <= level
                val color = when {
                    !lit -> if (mode == 0) Pal.IcomBlueDeep else Color.White.copy(alpha = 0.07f)
                    mode == 0 && i >= 15 -> Pal.Red
                    mode == 0 -> Pal.IcomBlueBright
                    i >= 24 -> Pal.Red
                    i >= 17 -> Pal.Amber
                    else -> Pal.Cyan
                }
                drawRoundRect(color, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mode == 0) {
                BoxWithConstraints(Modifier.weight(1f).height(18.dp)) {
                    listOf(
                        "S0" to 0.00f, "S3" to 0.17f, "S5" to 0.28f, "S7" to 0.39f,
                        "S9" to 0.49f, "+20" to 0.63f, "+40" to 0.76f, "+60" to 0.89f,
                    ).forEach { (label, fraction) ->
                        uk.hamdigital.ui.Text(
                            label,
                            color = if (label.startsWith("+")) Pal.Red else Pal.IcomBlueBright,
                            fontSize = 7.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.offset(x = maxWidth * fraction),
                        )
                    }
                }
            } else {
                uk.hamdigital.ui.Text(meterScale(mode), color = Pal.Muted, fontSize = 7.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AdvancedFunctionRow(rig: RigState, enabled: Boolean, onTone: () -> Unit, onDuplex: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        val notchLabel = when { rig.manualNotch == 1 -> "MN"; rig.autoNotch == 1 -> "AN"; else -> "NOTCH" }
        QuickCell(notchLabel, rig.autoNotch == 1 || rig.manualNotch == 1, enabled && supportsNotch(rig), Modifier.weight(1f)) {
            when {
                rig.manualNotch == 1 -> Ic705.setManualNotch(false)
                rig.autoNotch == 1 -> { Ic705.setAutoNotch(false); Ic705.setManualNotch(true) }
                else -> Ic705.setAutoNotch(true)
            }
        }
        QuickCell(if (rig.split == 0x01) "SPLIT ON" else "SPLIT", rig.split == 0x01, enabled && supportsSplit(rig), Modifier.weight(1f)) {
            Ic705.setSplit(if (rig.split == 0x01) 0 else 0x01)
        }
        QuickCell(toneLabel(rig), rig.toneMode > 0, enabled && supportsTone(rig), Modifier.weight(1f), onClick = onTone)
        QuickCell(if (supportsTbw(rig) && rig.tbw >= 0) "TBW${rig.tbw}" else "TBW", supportsTbw(rig) && rig.tbw >= 0, enabled && supportsTbw(rig), Modifier.weight(1f)) {
            Ic705.setTbw(if (rig.tbw in 0..1) rig.tbw + 1 else 0)
        }
        QuickCell(duplexLabel(rig), rig.split == 0x11 || rig.split == 0x12, enabled && supportsDup(rig), Modifier.weight(1f), onClick = {
            val next = when (rig.split) { 0 -> 0x11; 0x11 -> 0x12; else -> 0 }
            Ic705.setSplit(next)
            if (next != 0 && rig.offsetHz <= 0) Ic705.setOffsetHz(defaultOffset(rig.freqHz))
        }, onLongClick = onDuplex)
        QuickCell(if (rig.compressor == 1) "COMP ON" else "COMP", rig.compressor == 1, enabled && supportsComp(rig), Modifier.weight(1f)) {
            Ic705.setCompressor(rig.compressor != 1)
        }
        QuickCell(if (rig.monitor == 1) "MONI ON" else "MONI", rig.monitor == 1, enabled && supportsMoni(rig), Modifier.weight(1f)) {
            Ic705.setMonitor(rig.monitor != 1)
        }
    }
}

private fun toneLabel(rig: RigState): String = when (rig.toneMode) { 1 -> "TONE"; 2 -> "TSQL"; 3 -> "DTCS"; else -> "TONE" }
private fun duplexLabel(rig: RigState): String = when (rig.split) { 0x11 -> "DUP-"; 0x12 -> "DUP+"; else -> "DUP" }
private fun defaultOffset(hz: Long): Long = if (hz in 118_000_000L..174_000_000L || hz in 400_000_000L..470_000_000L) 600_000L else 5_000_000L
private fun supportsNotch(rig: RigState) = rig.mode.isNotBlank() && !rig.mode.startsWith("FM") && rig.mode != "WFM" && rig.mode != "DV"
private fun supportsSplit(rig: RigState) = rig.mode.isNotBlank() && rig.mode != "WFM" && rig.mode != "DV"
private fun supportsTone(rig: RigState) = rig.mode == "FM"
private fun supportsTbw(rig: RigState) = rig.mode == "USB" || rig.mode == "LSB"
private fun supportsNb(rig: RigState) = rig.mode.isNotBlank() && !rig.mode.startsWith("FM") && rig.mode != "WFM" && rig.mode != "DV"
private fun supportsDup(rig: RigState) = rig.mode == "FM"
private fun supportsComp(rig: RigState) = rig.mode.isNotBlank() && rig.mode != "WFM" && rig.mode != "DV"
private fun supportsMoni(rig: RigState) = rig.mode.isNotBlank() && rig.mode != "WFM" && rig.mode != "DV"

@Composable
private fun ToneControlDialog(
    rig: RigState,
    enabled: Boolean,
    onMode: (Int) -> Unit,
    onTone: (Int, Int) -> Unit,
    onDtcs: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember(rig.toneMode) { mutableIntStateOf(rig.toneMode.coerceAtLeast(0)) }
    val values = if (mode == 3) DTCS_CODES else CTCSS_TENTHS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { uk.hamdigital.ui.Text("TONE / 哑音频") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0 to "关", 1 to "TONE", 2 to "TSQL", 3 to "DTCS").forEach { (value, label) ->
                        ArchPressable(onClick = { mode = value; onMode(value) }, enabled = enabled, modifier = Modifier.weight(1f).height(38.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp))
                                    .background(if (mode == value) Pal.IcomBlue else Pal.Surface2)
                                    .border(1.dp, if (mode == value) Color.White.copy(alpha = 0.35f) else Pal.Border, RoundedCornerShape(9.dp)),
                                contentAlignment = Alignment.Center,
                            ) { uk.hamdigital.ui.Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                if (mode == 0) {
                    uk.hamdigital.ui.Text("关闭模拟和数字哑音频。", color = Pal.Muted, fontSize = 11.sp)
                } else {
                    uk.hamdigital.ui.Text(if (mode == 3) "DTCS 数字哑音" else "CTCSS 模拟哑音", color = Pal.Muted, fontSize = 11.sp)
                    values.chunked(if (mode == 3) 5 else 4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            row.forEach { value ->
                                ArchPressable(onClick = { if (mode == 3) onDtcs(value) else onTone(mode, value) }, enabled = enabled, modifier = Modifier.weight(1f).height(34.dp), contentAlignment = Alignment.Center) {
                                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(7.dp)).background(Pal.Surface2).border(1.dp, Pal.Border, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                                        uk.hamdigital.ui.Text(if (mode == 3) "%03d".format(value) else toneChoiceLabel(value), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            repeat((if (mode == 3) 5 else 4) - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { uk.hamdigital.ui.Text("完成") } },
    )
}

private fun toneChoiceLabel(tenths: Int): String = "%.1f".format(tenths / 10.0)

@Composable
private fun DuplexControlDialog(rig: RigState, enabled: Boolean, onApply: (Int, Long) -> Unit, onDismiss: () -> Unit) {
    var mode by remember(rig.split) { mutableIntStateOf(rig.split) }
    var offset by remember(rig.offsetHz, rig.freqHz) { mutableLongStateOf(if (rig.offsetHz > 0) rig.offsetHz else defaultOffset(rig.freqHz)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { uk.hamdigital.ui.Text("DUP / 频差") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                uk.hamdigital.ui.Text("当前频差：" + formatHz(if (rig.offsetHz > 0) rig.offsetHz else offset), color = Pal.Muted, fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0 to "OFF", 0x11 to "DUP-", 0x12 to "DUP+").forEach { (value, label) ->
                        ArchPressable(onClick = { mode = value }, enabled = enabled, modifier = Modifier.weight(1f).height(38.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)).background(if (mode == value) Pal.IcomBlue else Pal.Surface2).border(1.dp, if (mode == value) Color.White.copy(alpha = 0.35f) else Pal.Border, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                                uk.hamdigital.ui.Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                uk.hamdigital.ui.Text("选择频差", color = Pal.Text2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                listOf(600_000L, 1_600_000L, 5_000_000L, 7_600_000L).chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { value ->
                            ArchPressable(onClick = { offset = value }, enabled = enabled, modifier = Modifier.weight(1f).height(35.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(if (offset == value) Pal.IcomBlue else Pal.Surface2).border(1.dp, Pal.Border, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                    uk.hamdigital.ui.Text(stepLabelLong(value), color = Color.White, fontSize = 9.sp)
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onApply(mode, offset); onDismiss() }, enabled = enabled) { uk.hamdigital.ui.Text("应用") } },
        dismissButton = { TextButton(onDismiss) { uk.hamdigital.ui.Text("取消") } },
    )
}

private fun stepLabelLong(hz: Long): String = if (hz % 1_000_000L == 0L) "${hz / 1_000_000} MHz" else "${hz / 1000} KHz"

@Composable
private fun QuickFunctionRow(rig: RigState, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        QuickCell("PRE${when (rig.preamp) { 0 -> "关"; 1 -> "1"; 2 -> "2"; else -> "—" }}", rig.preamp > 0, enabled, Modifier.weight(1f)) {
            Ic705.setPreamp(if (rig.preamp in 1..2) (rig.preamp + 1) % 3 else 1)
        }
        QuickCell(if (rig.att == 20) "ATT20" else "ATT", rig.att == 20, enabled, Modifier.weight(1f)) {
            Ic705.setAttenuator(if (rig.att == 20) 0 else 20)
        }
        QuickCell("AGC${when (rig.agc) { 1 -> "快"; 2 -> "中"; 3 -> "慢"; else -> "—" }}", rig.agc > 0, enabled, Modifier.weight(1f)) {
            Ic705.setAgc(if (rig.agc in 1..2) rig.agc + 1 else 1)
        }
        QuickCell("NB", rig.nb == 1, enabled && supportsNb(rig), Modifier.weight(1f)) { Ic705.setNoiseBlanker(rig.nb != 1) }
        val nrCurrent = if (rig.nr > 0 && rig.nrLevel > 0) nrValue(rig.nrLevel) else 0
        QuickCell("NR$nrCurrent", rig.nr == 1, enabled, Modifier.weight(1f), onClick = {
            Ic705.setNrLevel(rawFromNr((nrCurrent + 1) % 10))
        }, onLongClick = { Ic705.setNrLevel(0) })
        QuickCell("VFO A/B", false, enabled, Modifier.weight(1f)) { Ic705.toggleVfo() }
        val filterAvailable = rig.mode.isNotEmpty() && !rig.mode.startsWith("FM") && rig.mode != "DV"
        QuickCell(if (filterAvailable && rig.filter > 0) "FIL${rig.filter}" else "FIL", filterAvailable && rig.filter > 0, enabled && filterAvailable, Modifier.weight(1f)) {
            Ic705.setFilter(if (rig.filter in 1..2) rig.filter + 1 else 1)
        }
    }
}

@Composable
private fun QuickCell(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    ArchPressable(onClick = onClick, enabled = enabled, modifier = modifier.height(32.dp), contentAlignment = Alignment.Center, onLongClick = onLongClick) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                .background(if (selected) Pal.Accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.055f))
                .border(1.dp, if (selected) Pal.Accent.copy(alpha = 0.72f) else Pal.Border, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            uk.hamdigital.ui.Text(label, color = if (!enabled) Pal.Dim else if (selected) Pal.Text else Pal.Text2, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun MeterSelector(selected: Int, onSelect: (Int) -> Unit) {
    val names = listOf("S表", "功率", "SWR", "ALC", "COMP", "电压", "电流")
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        names.forEachIndexed { i, name ->
            ArchPressable(onClick = { onSelect(i) }, modifier = Modifier.weight(1f).height(23.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxSize().clip(ArchPillShape)
                        .background(if (selected == i) Pal.Accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.045f))
                        .border(1.dp, if (selected == i) Pal.Accent.copy(alpha = 0.72f) else Pal.Border, ArchPillShape),
                    contentAlignment = Alignment.Center,
                ) { uk.hamdigital.ui.Text(name, color = if (selected == i) Pal.Text else Pal.Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
        }
    }
}

private fun meterRaw(r: RigState, mode: Int): Int = when (mode) {
    0 -> r.sMeter
    1 -> r.powerMeter
    2 -> r.swrMeter
    3 -> r.alcMeter
    4 -> r.compMeter
    5 -> r.vdMeter
    6 -> r.idMeter
    else -> -1
}

private fun meterName(mode: Int): String = when (mode) {
    0 -> "S 信号"
    1 -> "PO 功率"
    2 -> "SWR 驻波"
    3 -> "ALC"
    4 -> "COMP 压缩"
    5 -> "Vd 电压"
    6 -> "Id 电流"
    else -> "S 信号"
}

private fun meterScale(mode: Int): String = when (mode) {
    0 -> "S0    S3    S5    S9    +60"
    1 -> "0 W            10 W"
    2 -> "1.0            6.0"
    3 -> "0%             100%"
    4 -> "0 dB           30 dB"
    5 -> "10 V           16 V"
    6 -> "0 A             4 A"
    else -> "S1    S5    S9    +64"
}

private fun meterText(raw: Int, mode: Int): String {
    if (raw < 0) return "—"
    return when (mode) {
        0 -> when {
            raw <= 0 -> "S0"
            raw < 120 -> "S${(raw * 9 / 119).coerceIn(1, 9)}"
            raw == 120 -> "S9"
            else -> "S9+${((raw - 120) * 64 / 121).coerceAtLeast(1)}"
        }
        1 -> "%.1f W".format(interpolate(raw, listOf(0 to 0f, 21 to .5f, 43 to 1f, 65 to 1.5f, 83 to 2f, 95 to 2.5f, 105 to 3f, 114 to 3.5f, 124 to 4f, 143 to 5f, 183 to 7.5f, 213 to 10f, 255 to 12f)))
        2 -> "%.1f".format(interpolate(raw, listOf(0 to 1f, 48 to 1.5f, 80 to 2f, 120 to 3f, 240 to 6f)))
        3 -> "%.1f".format(interpolate(raw, listOf(0 to 0f, 120 to 1f, 255 to 2f)))
        4 -> "%.0f dB".format(interpolate(raw, listOf(0 to 0f, 130 to 15f, 241 to 30f)))
        5 -> "%.1f V".format(Ic705Codec.supplyVoltage(raw))
        6 -> "%.2f A".format(Ic705Codec.supplyCurrent(raw))
        else -> "—"
    }
}

private fun interpolate(raw: Int, points: List<Pair<Int, Float>>): Float {
    val x = raw.coerceIn(points.first().first, points.last().first)
    for (i in 0 until points.size - 1) {
        val a = points[i]; val b = points[i + 1]
        if (x <= b.first) {
            val f = (x - a.first).toFloat() / (b.first - a.first).coerceAtLeast(1)
            return a.second + (b.second - a.second) * f
        }
    }
    return points.last().second
}

@Composable
private fun RotaryKnob(
    label: String, value: Int, range: IntRange, valueText: String,
    onCommit: (Int) -> Unit, enabled: Boolean, modifier: Modifier = Modifier,
) {
    val min = range.first
    val max = range.last
    var local by remember { mutableIntStateOf(value.coerceIn(min, max)) }
    var holdUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(value, min, max) { if (System.currentTimeMillis() > holdUntil) local = value.coerceIn(min, max) }
    fun commit(n: Int) {
        local = n.coerceIn(min, max)
        holdUntil = System.currentTimeMillis() + 1500L
        if (enabled) onCommit(local)
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            uk.hamdigital.ui.Text(label, color = if (enabled) Pal.Text2 else Pal.Dim, fontSize = 10.sp, maxLines = 1)
            uk.hamdigital.ui.Text(valueText, color = if (enabled) Color(0xFFFFD89A) else Pal.Dim, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KnobButton("−", enabled) { commit(local - 1) }
            Box(Modifier.size(66.dp).pointerInput(enabled, min, max) {
                if (enabled) detectDragGestures(
                    onDragEnd = { commit(local) },
                    onDrag = { change, drag ->
                        change.consume()
                        val delta = ((-drag.y + drag.x) / 7f).roundToInt()
                        if (delta != 0) {
                            local = (local + delta).coerceIn(min, max)
                            holdUntil = System.currentTimeMillis() + 1500L
                        }
                    },
                )
            }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val radius = size.minDimension / 2f - 7.dp.toPx()
                    val center = center
                    val ratio = (local - min).toDouble() / (max - min).coerceAtLeast(1)
                    val angle = Math.toRadians(135.0 + 270.0 * ratio)
                    drawCircle(Color(0x66000000), radius + 4.dp.toPx(), center + Offset(0f, 4.dp.toPx()))
                    drawCircle(brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFE6E8EC), Color(0xFF6C727C), Color(0xFF1B1E24)),
                        center = center - Offset(radius * 0.35f, radius * 0.40f), radius = radius * 1.65f,
                    ), radius = radius, center = center)
                    drawCircle(Color.White.copy(alpha = 0.28f), radius, center, style = Stroke(width = 1.2.dp.toPx()))
                    for (k in 0..24) {
                        val a = Math.toRadians(135.0 + 270.0 * k / 24.0)
                        val outer = radius + 2.dp.toPx()
                        val inner = radius + if (k % 6 == 0) 6.dp.toPx() else 4.dp.toPx()
                        val p1 = Offset(center.x + cos(a).toFloat() * outer, center.y + sin(a).toFloat() * outer)
                        val p2 = Offset(center.x + cos(a).toFloat() * inner, center.y + sin(a).toFloat() * inner)
                        drawLine(if (k % 6 == 0) Color(0xFFBFCBCD) else Color(0xFF697477), p1, p2, if (k % 6 == 0) 1.3f else 0.7f, StrokeCap.Round)
                    }
                    drawCircle(brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF3A3F49), Color(0xFF12151A), Color(0xFF05070A)),
                        center = center - Offset(radius * 0.20f, radius * 0.20f), radius = radius * 0.9f,
                    ), radius = radius * 0.69f, center = center)
                    drawCircle(Color(0xFF060809), radius * 0.48f, center, style = Stroke(width = 1.dp.toPx()))
                    val start = Offset(center.x + cos(angle).toFloat() * radius * 0.10f, center.y + sin(angle).toFloat() * radius * 0.10f)
                    val tip = Offset(center.x + cos(angle).toFloat() * radius * 0.59f, center.y + sin(angle).toFloat() * radius * 0.59f)
                    drawLine(Color(0xFF071013), start, tip, 4.dp.toPx(), StrokeCap.Round)
                    drawLine(Pal.Cyan, start, tip, 2.dp.toPx(), StrokeCap.Round)
                    drawCircle(Color.White, 1.6.dp.toPx(), tip)
                }
            }
            KnobButton("+", enabled) { commit(local + 1) }
        }
    }
}

@Composable
private fun KnobButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    ArchPressable(onClick = onClick, enabled = enabled, modifier = Modifier.width(27.dp).height(32.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().clip(ArchControlShape).background(Pal.Surface2).border(1.dp, Pal.Border, ArchControlShape),
            contentAlignment = Alignment.Center,
        ) { uk.hamdigital.ui.Text(text, color = if (enabled) Pal.Text else Pal.Dim, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
    }
}

internal fun scopeSpanLabel(index: Int): String = listOf("±2.5k", "±5k", "±10k", "±25k", "±50k", "±100k", "±250k", "±500k").getOrNull(index) ?: "—"

internal fun nextScopeSpan(index: Int): Int = (index.coerceAtLeast(0) + 1) % 8
private fun percentValue(raw: Int): Int = Ic705Codec.levelPercent(raw)
private fun rawFromPercent(value: Int): Int = Ic705Codec.rawFromPercent(value)
private fun nrValue(raw: Int): Int = Ic705Codec.nrDisplay(raw)
private fun rawFromNr(value: Int): Int = Ic705Codec.nrRaw(value)

private fun formatHz(hz: Long): String = if (hz <= 0) "—" else "%d.%03d.%03d".format(hz / 1_000_000, hz / 1000 % 1000, hz % 1000)

private fun bandLabel(hz: Long): String = BandPlan.labelFor(hz)

private fun currentModeCode(s: RigState): Int = when (s.mode) {
    "LSB" -> 0x00; "USB" -> 0x01; "AM" -> 0x02; "CW" -> 0x03; "RTTY" -> 0x04
    "FM" -> 0x05; "WFM" -> 0x06; "CW-R" -> 0x07; "RTTY-R" -> 0x08; "DV" -> 0x17
    else -> 0x01
}

internal fun parseChannels(raw: String): List<SavedChannel> = raw.lineSequence().mapNotNull { line ->
    val parts = line.split('|')
    if (parts.size < 3) return@mapNotNull null
    val hz = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
    val code = if (parts.size >= 4) parts[2].toIntOrNull() ?: 1 else (RigMode.entries.firstOrNull { it.name == parts[2] }?.ordinal?.let { listOf(1, 0, 3, 5, 23)[it] } ?: 1)
    val data = parts.size >= 4 && parts[3].toBoolean()
    val toneMode = parts.getOrNull(4)?.toIntOrNull() ?: 0
    val toneTenths = parts.getOrNull(5)?.toIntOrNull() ?: 0
    val legacyNineFields = parts.size == 9
    val tsqlTenths = if (legacyNineFields) 0 else parts.getOrNull(6)?.toIntOrNull() ?: 0
    val dtcsCode = if (legacyNineFields) parts[6].toIntOrNull() ?: 0 else parts.getOrNull(7)?.toIntOrNull() ?: 0
    val split = if (legacyNineFields) parts[7].toIntOrNull() ?: 0 else parts.getOrNull(8)?.toIntOrNull() ?: 0
    val offsetHz = if (legacyNineFields) parts[8].toLongOrNull() ?: 0L else parts.getOrNull(9)?.toLongOrNull() ?: 0L
    SavedChannel(parts[0].ifBlank { formatHz(hz) }, hz, code, data,
        toneMode, toneTenths, tsqlTenths, dtcsCode, split, offsetHz)
}.toList()

internal fun encodeChannels(channels: List<SavedChannel>): String =
    channels.joinToString("\n") { "${it.name.replace('|', '/')}|${it.hz}|${it.modeCode}|${it.data}|${it.toneMode}|${it.toneTenths}|${it.tsqlTenths}|${it.dtcsCode}|${it.split}|${it.offsetHz}" }

private fun parseBandMemory(raw: String): Map<String, BandMemoryEntry> = raw.lineSequence().mapNotNull { line ->
    val parts = line.split('|')
    if (parts.size != 4) return@mapNotNull null
    val hz = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
    val mode = parts[2].toIntOrNull() ?: return@mapNotNull null
    parts[0] to BandMemoryEntry(hz, mode, parts[3].toBoolean())
}.toMap()

private fun encodeBandMemory(memory: Map<String, BandMemoryEntry>): String =
    memory.entries.joinToString("\n") { "${it.key.replace('|', '/')}|${it.value.hz}|${it.value.modeCode}|${it.value.data}" }

private fun filterText(hz: Int): String = when {
    hz <= 0 -> "—"
    hz < 1000 -> "$hz Hz"
    else -> "%.1f k".format(hz / 1000.0)
}

