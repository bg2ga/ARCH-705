package uk.hamdigital.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.hamdigital.ArchFloatingPttService
import uk.hamdigital.ArchOverlayService
import uk.hamdigital.BuildConfigInfo
import uk.hamdigital.MainViewModel
import uk.hamdigital.Screen
import uk.hamdigital.Updater
import uk.hamdigital.audio.AudioIn
import uk.hamdigital.RadioService
import uk.hamdigital.core.AudioChoice
import uk.hamdigital.core.Logbook
import uk.hamdigital.core.RadioProfile
import uk.hamdigital.core.RadioProfiles
import uk.hamdigital.core.applyTo
import uk.hamdigital.rig.Ic705
import uk.hamdigital.rig.IcomNet
import uk.hamdigital.rig.RigState

private val LOCATOR = Regex("^[A-Ra-r]{2}[0-9]{2}([A-Xa-x]{2})?$")
private const val CONNECTION = "connection"
private const val STATION = "station"
private const val AUDIO = "audio"
private const val DISPLAY = "display"
private const val FIRMWARE = "firmware"
private const val UPDATE = "update"
private const val ABOUT = "about"

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val active = RadioProfiles.active(ctx)
    val rig by Ic705.state.collectAsStateWithLifecycle()
    val net by IcomNet.status.collectAsStateWithLifecycle()
    val loggedIn by IcomNet.loginState.collectAsStateWithLifecycle()
    val authAcceptedGeneration by IcomNet.authAcceptedGeneration.collectAsStateWithLifecycle()
    val authenticatedGeneration by IcomNet.authenticatedGeneration.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    var call by remember { mutableStateOf(s.callsign) }
    var loc by remember { mutableStateOf(s.locator) }
    var profiles by remember { mutableStateOf(RadioProfiles.load(ctx)) }
    var saveName by remember { mutableStateOf(false) }
    var profileName by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(s.wifiIp.ifBlank { active?.address.orEmpty() }) }
    var user by remember { mutableStateOf(s.wifiUser.ifBlank { active?.user.orEmpty() }) }
    var password by remember { mutableStateOf(active?.password.orEmpty()) }
    var defaults by remember { mutableStateOf(s.useDefaultPorts) }
    var control by remember { mutableStateOf(s.wifiPort.toString()) }
    var serial by remember { mutableStateOf(s.wifiCivPort.toString()) }
    var audio by remember { mutableStateOf(s.wifiAudioPort.toString()) }
    var updateStatus by remember { mutableStateOf("") }
    var exitAsk by remember { mutableStateOf(false) }
    var pendingProfile by remember { mutableStateOf<RadioProfile?>(null) }
    var pendingGeneration by remember { mutableStateOf(0L) }
    val updater = remember { (ctx as? Activity)?.let { Updater(it) } }
    val logs by Logbook.qsos.collectAsStateWithLifecycle()
    var usbAudio by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { updater?.resumePendingInstall() }
    LaunchedEffect(Unit) { while (true) { usbAudio = AudioIn.usbInput(ctx)?.let { AudioIn.usbName(it) }; kotlinx.coroutines.delay(2000) } }
    BackHandler(enabled = section != null) { section = null }
    LaunchedEffect(authAcceptedGeneration, authenticatedGeneration, pendingProfile, pendingGeneration) {
        if (pendingProfile != null && pendingGeneration > 0L && (authAcceptedGeneration == pendingGeneration || authenticatedGeneration == pendingGeneration)) {
            pendingProfile?.let { RadioProfiles.save(ctx, it) }
            profiles = RadioProfiles.load(ctx)
            pendingProfile = null
            pendingGeneration = 0L
        }
    }

    if (saveName) AlertDialog(
        onDismissRequest = { saveName = false },
        title = { uk.hamdigital.ui.Text("保存电台档案") },
        text = { OutlinedTextField(profileName, { profileName = it }, label = { uk.hamdigital.ui.Text("电台名称") }, singleLine = true) },
        confirmButton = {
            TextButton({
                val cp = if (defaults) 50001 else control.toIntOrNull() ?: 50001
                val sp = if (defaults) 50002 else serial.toIntOrNull() ?: 50002
                val ap = if (defaults) 50003 else audio.toIntOrNull() ?: 50003
                val old = profiles.firstOrNull { it.address == address.trim() && it.user == user.trim() }
                val profile = RadioProfile(
                    id = old?.id ?: java.util.UUID.randomUUID().toString(),
                    name = profileName.ifBlank { address.ifBlank { "IC-705" } },
                    address = address.trim(), user = user.trim(), password = password,
                    controlPort = cp, serialPort = sp, audioPort = ap, useDefaultPorts = defaults,
                )
                RadioProfiles.save(ctx, profile)
                profiles = RadioProfiles.load(ctx)
                saveName = false
            }) { uk.hamdigital.ui.Text("保存") }
        },
        dismissButton = { TextButton({ saveName = false }) { uk.hamdigital.ui.Text("取消") } },
    )

    if (exitAsk) AlertDialog(
        onDismissRequest = { exitAsk = false },
        title = { uk.hamdigital.ui.Text("退出登录") },
        text = { uk.hamdigital.ui.Text("退出后将清除保存的电台密码。软关机和后台运行不会清除密码。") },
        confirmButton = {
            TextButton({
                IcomNet.disconnect()
                RadioProfiles.clearPasswords(ctx)
                vm.updateSettings { it.copy(wifiUser = "", audio = AudioChoice.AUTO) }
                exitAsk = false
                vm.show(Screen.Connect)
            }) { uk.hamdigital.ui.Text("退出并清除密码", color = Pal.Red) }
        },
        dismissButton = { TextButton({ exitAsk = false }) { uk.hamdigital.ui.Text("取消") } },
    )

    val title = when (section) {
        CONNECTION -> "电台连接"
        STATION -> "我的台站"
        AUDIO -> "音频与发射"
        DISPLAY -> "显示与频谱"
        FIRMWARE -> "固件兼容"
        UPDATE -> "检查更新"
        ABOUT -> "关于与免责声明"
        else -> "设置"
    }
    ModeFrame(title, { if (section == null) vm.back() else section = null }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (section == null) {
                SettingsHome(
                    version = BuildConfigInfo.VERSION,
                    connected = rig.link == RigState.Link.CONNECTED,
                    callsign = s.callsign,
                    address = address,
                    audio = s.audio,
                    floatingPtt = s.floatingPtt,
                    refreshFps = s.scopeRefreshFps,
                    logCount = logs.size,
                    onSection = { section = it },
                    onLog = { vm.show(Screen.Log) },
                    onExit = { exitAsk = true },
                )
            } else when (section) {
                CONNECTION -> ConnectionSection(
                    s = s, profiles = profiles, active = active, address = address, user = user,
                    password = password, defaults = defaults, control = control, serial = serial, audio = audio,
                    net = net, connected = rig.link == RigState.Link.CONNECTED,
                    onAddress = { address = it }, onUser = { user = it }, onPassword = { password = it },
                    onDefaults = { defaults = it }, onControl = { control = it }, onSerial = { serial = it }, onAudio = { audio = it },
                    onUseProfile = { p ->
                        RadioProfiles.markActive(ctx, p.id)
                        vm.updateSettings { p.applyTo(it) }
                        address = p.address; user = p.user; password = p.password
                        defaults = p.useDefaultPorts; control = p.controlPort.toString(); serial = p.serialPort.toString(); audio = p.audioPort.toString()
                        profiles = RadioProfiles.load(ctx)
                    },
                    onDeleteProfile = { p -> RadioProfiles.delete(ctx, p.id); profiles = RadioProfiles.load(ctx) },
                    onSaveProfile = { profileName = address.ifBlank { "IC-705" }; saveName = true },
                    onConnect = {
                        val cp = if (defaults) 50001 else control.toIntOrNull() ?: 0
                        val sp = if (defaults) 50002 else serial.toIntOrNull() ?: 0
                        val ap = if (defaults) 50003 else audio.toIntOrNull() ?: 0
                        if (address.isNotBlank() && user.isNotBlank() && password.isNotBlank() && cp in 1..65535 && sp in 1..65535 && ap in 1..65535 && cp != sp && cp != ap && sp != ap) {
                            vm.updateSettings { it.copy(wifiIp = address.trim(), wifiPort = cp, wifiCivPort = sp, wifiAudioPort = ap, useDefaultPorts = defaults, wifiUser = user.trim(), audio = AudioChoice.WIFI) }
                            val old = profiles.firstOrNull { it.address == address.trim() && it.user == user.trim() }
                            val profile = (old ?: RadioProfile(name = address.trim(), address = address.trim(), user = user.trim(), password = password)).copy(
                                address = address.trim(), user = user.trim(), password = password,
                                controlPort = cp, serialPort = sp, audioPort = ap, useDefaultPorts = defaults,
                            )
                            pendingProfile = profile
                            pendingGeneration = IcomNet.connect(ctx, address.trim(), cp, user.trim(), password, sp, ap, powerOn = true)
                        }
                    },
                    onDisconnect = {
                        pendingProfile = null
                        pendingGeneration = 0L
                        IcomNet.disconnect()
                        vm.updateSettings { it.copy(audio = AudioChoice.AUTO) }
                    },
                )
                STATION -> StationSection(call, loc, onCall = {
                    call = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '/' }.take(12)
                    vm.updateSettings { old -> old.copy(callsign = call) }
                }, onLoc = {
                    loc = it.filter(Char::isLetterOrDigit).take(6)
                    if (loc.isBlank() || LOCATOR.matches(loc)) vm.updateSettings { old -> old.copy(locator = loc) }
                })
                AUDIO -> AudioSection(vm, s, usbAudio)
                DISPLAY -> DisplaySection(vm, s)
                FIRMWARE -> FirmwareSection()
                UPDATE -> UpdateSection(updater) { updateStatus = it }
                ABOUT -> AboutSection()
            }
            if (section == UPDATE && updateStatus.isNotBlank()) uk.hamdigital.ui.Text(updateStatus, color = Pal.Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SettingsHome(
    version: String,
    connected: Boolean,
    callsign: String,
    address: String,
    audio: AudioChoice,
    floatingPtt: Boolean,
    refreshFps: Int,
    logCount: Int,
    onSection: (String) -> Unit,
    onLog: () -> Unit,
    onExit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)) {
        uk.hamdigital.ui.Text("ARCH-705  " + version, color = Pal.Cyan, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        uk.hamdigital.ui.Text(if (connected) "电台已连接 · " + address else "电台未连接 · " + address.ifBlank { "尚未保存地址" }, color = if (connected) Pal.Green else Pal.Muted, fontSize = 12.sp)
    }
    GroupTitle("电台与操作")
    SettingsRow(Icons.Filled.Wifi, "电台连接", address.ifBlank { "IP、账号、端口与电台档案" }, Pal.Cyan) { onSection(CONNECTION) }
    SettingsRow(Icons.Filled.Person, "我的台站", callsign.ifBlank { "呼号与网格" }, Pal.Amber) { onSection(STATION) }
    SettingsRow(Icons.AutoMirrored.Filled.VolumeUp, "音频与发射", audioLabel(audio) + " · 悬浮PTT " + if (floatingPtt) "开" else "关", Color(0xFF70D4B4)) { onSection(AUDIO) }
    SettingsRow(Icons.Filled.Palette, "显示与频谱", "刷新率 $refreshFps FPS", Color(0xFF65AFFF)) { onSection(DISPLAY) }
    SettingsRow(Icons.Filled.Memory, "固件兼容", "原版 1.40 / ARCH 数字状态扩展", Pal.Cyan) { onSection(FIRMWARE) }
    GroupTitle("记录与帮助")
    SettingsRow(Icons.AutoMirrored.Filled.ListAlt, "通联日志记录", "$logCount 条通联", Pal.Green, onLog)
    SettingsRow(Icons.Filled.SystemUpdate, "检查更新", "在线检查并安装新版本", Pal.Cyan) { onSection(UPDATE) }
    SettingsRow(Icons.Filled.Info, "关于作者与免责声明", "版本、作者、许可证和安全说明", Pal.Muted) { onSection(ABOUT) }
    GroupTitle("账户")
    SettingsRow(Icons.AutoMirrored.Filled.Logout, "退出登录并清除密码", "软关机、后台运行不会清除保存的密码", Pal.Red, onExit)
}

@Composable
private fun ConnectionSection(
    s: uk.hamdigital.core.Settings, profiles: List<RadioProfile>, active: RadioProfile?, address: String, user: String,
    password: String, defaults: Boolean, control: String, serial: String, audio: String, net: String, connected: Boolean,
    onAddress: (String) -> Unit, onUser: (String) -> Unit, onPassword: (String) -> Unit, onDefaults: (Boolean) -> Unit,
    onControl: (String) -> Unit, onSerial: (String) -> Unit, onAudio: (String) -> Unit,
    onUseProfile: (RadioProfile) -> Unit, onDeleteProfile: (RadioProfile) -> Unit, onSaveProfile: () -> Unit,
    onConnect: () -> Unit, onDisconnect: () -> Unit,
) {
    GroupTitle("已保存的电台")
    if (profiles.isEmpty()) uk.hamdigital.ui.Text("还没有保存电台。填写下方资料后点“保存为档案”。", color = Pal.Muted, fontSize = 12.sp)
    profiles.forEach { p ->
        val current = active?.id == p.id
        Surface(color = if (current) Pal.Accent.copy(alpha = 0.22f) else Pal.Tert, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    uk.hamdigital.ui.Text(p.displayName(), color = if (current) Pal.Cyan else Pal.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    uk.hamdigital.ui.Text(p.address + "   控制 " + p.controlPort + " / 串口 " + p.serialPort + " / 音频 " + p.audioPort, color = Pal.Muted, fontSize = 10.sp)
                }
                TextButton({ onUseProfile(p) }) { uk.hamdigital.ui.Text("使用", color = Pal.Cyan, fontSize = 11.sp) }
                TextButton({ onDeleteProfile(p) }) { uk.hamdigital.ui.Text("删除", color = Pal.Red, fontSize = 11.sp) }
            }
        }
    }
    OutlinedButton(onSaveProfile, Modifier.fillMaxWidth().height(38.dp)) { uk.hamdigital.ui.Text("保存当前资料为电台档案") }
    GroupTitle("网络地址")
    CompactField(address, onAddress, "地址（IP 或域名）", Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(defaults, onDefaults)
        uk.hamdigital.ui.Text("使用默认端口 50001 / 50002 / 50003", color = Pal.Text, fontSize = 12.sp)
    }
    if (!defaults) Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        CompactField(control, onControl, "控制", Modifier.weight(1f))
        CompactField(serial, onSerial, "串口", Modifier.weight(1f))
        CompactField(audio, onAudio, "音频", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        CompactField(user, onUser, "用户名", Modifier.weight(1f))
        CompactField(password, onPassword, "密码", Modifier.weight(1f), password = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Button(onConnect, Modifier.weight(1f).height(42.dp)) { uk.hamdigital.ui.Text("登录并开机") }
        OutlinedButton(onDisconnect, Modifier.weight(1f).height(42.dp)) { uk.hamdigital.ui.Text("断开") }
    }
    uk.hamdigital.ui.Text(net.ifBlank { if (connected) "已连接" else "未连接" }, color = if (connected) Pal.Green else Pal.Muted, fontSize = 11.sp)
    uk.hamdigital.ui.Text("外网使用请通过 WireGuard 或 Tailscale，不要直接暴露 UDP 50001–50003。", color = Pal.Amber, fontSize = 10.sp, lineHeight = 15.sp)
}

@Composable
private fun StationSection(call: String, loc: String, onCall: (String) -> Unit, onLoc: (String) -> Unit) {
    GroupTitle("本台资料")
    CompactField(call, onCall, "呼号", Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
    CompactField(loc, onLoc, "Maidenhead 网格", Modifier.fillMaxWidth(), isError = loc.isNotBlank() && !LOCATOR.matches(loc), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
    uk.hamdigital.ui.Text("示例：呼号 BG2GA，网格 PN35AA。日志、WSPR 和 APRS 会使用这些资料。", color = Pal.Muted, fontSize = 11.sp, lineHeight = 16.sp)
}

@Composable
private fun AudioSection(vm: MainViewModel, s: uk.hamdigital.core.Settings, usbAudio: String?) {
    val ctx = LocalContext.current
    GroupTitle("接收音频")
    AudioChoice.entries.forEach { choice -> ChoiceRow(audioLabel(choice), s.audio == choice) { vm.updateSettings { it.copy(audio = choice) } } }
    uk.hamdigital.ui.Text(usbAudio?.let { "已发现 USB 音频：" + it } ?: "未发现 USB 音频", color = if (usbAudio != null) Pal.Green else Pal.Muted, fontSize = 11.sp)
    GroupTitle("后台")
    ToggleRow("后台常驻", "息屏或切到其他应用时保持电台连接；默认开启", s.backgroundKeepAlive) { enabled ->
        vm.updateSettings { it.copy(backgroundKeepAlive = enabled) }
        if (enabled) RadioService.start(ctx) else RadioService.stop(ctx)
    }
    ToggleRow("后台音频", "APP 在后台时继续播放 IC-705 接收音频；默认关闭，需要耗电和流量", s.backgroundAudio) { enabled ->
        vm.updateSettings { it.copy(backgroundAudio = enabled, backgroundKeepAlive = if (enabled) true else it.backgroundKeepAlive) }
        if (enabled) RadioService.start(ctx)
    }
    GroupTitle("发射")
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val accessibilityEnabled = ArchFloatingPttService.isEnabled(ctx)
    val overlayPermission = ArchOverlayService.canDraw(ctx)
    ToggleRow("系统悬浮 PTT", "优先使用无障碍；小米等受限系统可改用悬浮窗权限。圆形 PTT 可拖到任意位置，切到桌面后仍可按住发射；松手、断线、锁定或退出时停发", s.floatingPtt) { enabled ->
        vm.updateSettings { it.copy(floatingPtt = enabled, backgroundKeepAlive = if (enabled) true else it.backgroundKeepAlive) }
        if (enabled) {
            RadioService.start(ctx)
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            if (accessibilityEnabled) {
                ArchOverlayService.stop(ctx)
                ArchFloatingPttService.refresh(ctx)
            } else if (overlayPermission) {
                ArchFloatingPttService.refresh(ctx)
                ArchOverlayService.start(ctx)
            } else {
                ArchOverlayService.openPermissionSettings(ctx)
            }
        } else {
            ArchFloatingPttService.refresh(ctx)
            ArchOverlayService.stop(ctx)
        }
    }
    uk.hamdigital.ui.Text(
        when {
            accessibilityEnabled -> "无障碍悬浮窗已授权；切到桌面后悬浮 PTT 仍可使用。"
            overlayPermission -> "无障碍不可用或未授权，将使用系统悬浮窗权限；切到桌面后仍可使用。"
            else -> "无障碍和悬浮窗权限都未授权；请选择下方任一权限设置。"
        },
        color = if (accessibilityEnabled || overlayPermission) Pal.Green else Pal.Amber,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )
    OutlinedButton({ ArchFloatingPttService.openSettings(ctx) }, Modifier.fillMaxWidth()) {
        uk.hamdigital.ui.Text("打开无障碍权限设置")
    }
    OutlinedButton({ ArchOverlayService.openPermissionSettings(ctx) }, Modifier.fillMaxWidth()) {
        uk.hamdigital.ui.Text("打开系统悬浮窗权限设置")
    }
    var txLevel by remember(s.txLevel) { mutableFloatStateOf(s.txLevel.toFloat()) }
    uk.hamdigital.ui.Text("发射音频电平 " + txLevel.toInt() + "%", color = Pal.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    Slider(txLevel, { txLevel = it }, valueRange = 1f..100f, onValueChangeFinished = { vm.updateSettings { it.copy(txLevel = txLevel.toInt()) } })
    OutlinedButton({ vm.updateSettings { it.copy(txOk = false) }; uk.hamdigital.core.TxControl.licensed = false }, Modifier.fillMaxWidth()) { uk.hamdigital.ui.Text("重新显示发射许可提示") }
}

@Composable
private fun DisplaySection(vm: MainViewModel, s: uk.hamdigital.core.Settings) {
    GroupTitle("频谱刷新")
    uk.hamdigital.ui.Text("网络较慢时降低刷新率，可减少卡顿与音频丢包。", color = Pal.Muted, fontSize = 11.sp)
    Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(30, 20, 15, 10, 5).forEach { fps ->
            FilterChip(s.scopeRefreshFps == fps, { vm.updateSettings { it.copy(scopeRefreshFps = fps) } }, { uk.hamdigital.ui.Text("$fps FPS", fontSize = 11.sp) }, modifier = Modifier.weight(1f))
        }
    }
    GroupTitle("界面")
    uk.hamdigital.ui.Text("主界面采用单页工作台布局；功能模式、设置和日志从右上角进入。", color = Pal.Text2, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
private fun FirmwareSection() {
    GroupTitle("兼容范围")
    uk.hamdigital.ui.Text("原版 IC-705 1.40：标准 CI-V / RS-BA1 控制、音频、频谱、PTT 正常工作。", color = Pal.Text2, fontSize = 12.sp, lineHeight = 17.sp)
    uk.hamdigital.ui.Text("BI1BIF v1.5.3：支持 DMR、YSF/C4FM、常规 P25 Phase 1 接收。SD 卡 IC-705 目录必须保留 DMRRX.BIN。", color = Pal.Text2, fontSize = 12.sp, lineHeight = 17.sp)
    uk.hamdigital.ui.Text("ARCH-705 数字状态扩展固件：在 v1.5.3 基础上增加只读 A7DV 状态帧，App 可显示色码、时隙、ID、组呼、DG-ID、呼号和 NAC。", color = Pal.Cyan, fontSize = 12.sp, lineHeight = 17.sp)
    uk.hamdigital.ui.Text("扩展固件不会修改解码结果，也不会伪造状态；未刷扩展固件时，原有功能不受影响。", color = Pal.Muted, fontSize = 11.sp, lineHeight = 16.sp)
}

@Composable
private fun UpdateSection(updater: Updater?, onStatus: (String) -> Unit) {
    GroupTitle("在线更新")
    Button({ updater?.check(false, onStatus) }, Modifier.fillMaxWidth().height(44.dp)) { uk.hamdigital.ui.Text("检查更新") }
    uk.hamdigital.ui.Text("更新会保留登录地址、用户名、密码、频道和波段记忆。", color = Pal.Muted, fontSize = 11.sp)
}

@Composable
private fun AboutSection() {
    GroupTitle("关于作者")
    uk.hamdigital.ui.Text("ARCH-705  " + BuildConfigInfo.VERSION, color = Pal.Cyan, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    uk.hamdigital.ui.Text("作者：BG2GA", color = Pal.Text, fontSize = 13.sp)
    uk.hamdigital.ui.Text("邮箱：bg2ga@by2aa.org.cn", color = Pal.Text2, fontSize = 12.sp)
    uk.hamdigital.ui.Text("项目主页：https://arch.hljrsa.cn", color = Pal.Text2, fontSize = 12.sp)
    GroupTitle("许可证")
    uk.hamdigital.ui.Text("GNU GPL v3.0。软件包含 GPL-3.0、MIT、BSD、LGPL 等开源代码，完整许可证与来源随源码包提供。", color = Pal.Muted, fontSize = 11.sp, lineHeight = 16.sp)
    GroupTitle("免责声明与使用风险")
    uk.hamdigital.ui.Text(
        "本软件为业余无线电远程控制和研究工具，按“现状”和“现有可用”状态提供，不附带任何明示或默示担保，包括但不限于适销性、特定用途适用性、无错误、持续可用、兼容性、安全性或不侵权担保。作者、贡献者、分发者和项目维护者不保证软件、固件、网络连接、音频、频谱、控制命令或更新在任何设备、网络、系统版本或使用环境下能够正常工作。\n\n" +
        "使用者必须自行确认并遵守所在国家或地区的全部适用法律、法规、无线电管理条例、频率划分、发射功率限制、执照等级、呼号使用规则、电磁兼容要求和射频暴露限制。使用者对以下事项承担全部责任：实际发射频率、功率、模式、天线、驻波比、内容、通联对象、日志、隐私、数据以及由此产生的任何干扰、投诉、处罚或其他法律后果。\n\n" +
        "远程控制具有固有风险，包括网络延迟、丢包、断网、服务器或设备故障、操作系统终止应用、音频异常、PTT 卡住、命令丢失或重复、频率或模式误设、设备过热、意外发射、干扰、数据损坏和人身或财产损害。使用者在每次发射前必须确认天线或假负载、功率和 SWR，开启电台自身的 TX Time-out Timer，并保持有人值守。作者无法保证断网、断电、系统终止应用或链路完全失效时 PTT OFF 能送达电台。\n\n" +
        "在法律允许的最大范围内，作者、贡献者、分发者和项目维护者不对因使用或无法使用本软件而产生的任何直接、间接、偶然、特殊、惩罚性、示范性或后果性损失承担责任，包括但不限于人身伤害、死亡、财产损失、设备损坏、数据丢失、通信中断、业务损失、利润损失、法律费用、行政处罚、第三方索赔或无线电干扰。即使已被告知发生此类损害的可能性，本责任限制仍然适用。\n\n" +
        "使用者一经安装、连接、发射或继续使用本软件，即表示已阅读、理解并自愿承担全部相关风险。若不同意上述条款，应立即停止使用并卸载本软件。",
        color = Pal.Text2,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )
    GroupTitle("第三方与商标")
    uk.hamdigital.ui.Text(
        "ARCH-705 与 Icom Inc.、Yaesu Musen、D-STAR 相关组织及其他设备厂商无隶属、赞助、认证或授权关系。IC-705、Icom、D-STAR 等名称和商标归其各自权利人所有。软件包含的第三方开源组件分别适用其原始许可证；开源许可证中的免责和责任限制条款同样适用于相应组件。\n\n" +
        "本软件不用于生命救援、医疗、航空、航海、公共安全、军事或其他要求高可靠性的关键通信场景。不得将本软件作为紧急通信或人身安全的唯一手段。",
        color = Pal.Muted,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )
}

@Composable
private fun GroupTitle(text: String) {
    uk.hamdigital.ui.Text(text, color = Pal.Muted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.4.sp, modifier = Modifier.padding(top = 14.dp, bottom = 5.dp, start = 4.dp))
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, accent: Color, action: () -> Unit) {
    ArchPressable(action, Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(ArchPanelShape)
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.075f), Color.White.copy(alpha = 0.035f))))
                .border(1.dp, Pal.Separator, ArchPanelShape)
                .heightIn(min = 62.dp)
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp)) }
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                uk.hamdigital.ui.Text(title, color = Pal.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                uk.hamdigital.ui.Text(subtitle, color = Pal.Muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Pal.Dim, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        uk.hamdigital.ui.Text(label, color = Pal.Text, fontSize = 13.sp)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            uk.hamdigital.ui.Text(title, color = Pal.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            uk.hamdigital.ui.Text(subtitle, color = Pal.Muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Switch(checked, onChecked)
    }
}

private fun audioLabel(choice: AudioChoice): String = when (choice) {
    AudioChoice.AUTO -> "自动：USB 优先"
    AudioChoice.USB -> "仅 IC-705 USB"
    AudioChoice.MIC -> "手机麦克风"
    AudioChoice.WIFI -> "IC-705 WiFi 直连"
}
