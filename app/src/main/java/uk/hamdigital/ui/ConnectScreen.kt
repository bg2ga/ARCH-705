package uk.hamdigital.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.hamdigital.MainViewModel
import uk.hamdigital.Screen
import uk.hamdigital.core.AudioChoice
import uk.hamdigital.core.RadioProfile
import uk.hamdigital.core.RadioProfiles
import uk.hamdigital.rig.Ic705
import uk.hamdigital.rig.IcomNet
import uk.hamdigital.rig.RigState

@Composable
fun ConnectScreen(vm: MainViewModel) {
    val saved by vm.settings.collectAsStateWithLifecycle()
    val settingsReady by vm.settingsReady.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var active by remember { mutableStateOf<RadioProfile?>(null) }
    var profiles by remember { mutableStateOf(emptyList<RadioProfile>()) }
    val rig by Ic705.state.collectAsStateWithLifecycle()
    val status by IcomNet.status.collectAsStateWithLifecycle()
    val loggedIn by IcomNet.loginState.collectAsStateWithLifecycle()
    val authAcceptedGeneration by IcomNet.authAcceptedGeneration.collectAsStateWithLifecycle()
    val authenticatedGeneration by IcomNet.authenticatedGeneration.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf(active?.address ?: saved.wifiIp) }
    var user by rememberSaveable { mutableStateOf(active?.user ?: saved.wifiUser) }
    var password by remember { mutableStateOf(active?.password ?: "") }
    var defaults by rememberSaveable { mutableStateOf(active?.useDefaultPorts ?: saved.useDefaultPorts) }
    var control by rememberSaveable { mutableStateOf((active?.controlPort ?: saved.wifiPort).toString()) }
    var serial by rememberSaveable { mutableStateOf((active?.serialPort ?: saved.wifiCivPort).toString()) }
    var audio by rememberSaveable { mutableStateOf((active?.audioPort ?: saved.wifiAudioPort).toString()) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pendingProfile by remember { mutableStateOf<RadioProfile?>(null) }
    var pendingGeneration by remember { mutableStateOf(0L) }
    var pendingSavedGeneration by remember { mutableStateOf(0L) }
    var loginStartedMs by remember { mutableLongStateOf(0L) }
    var powerWaitExpired by remember { mutableStateOf(false) }
    var profileSaveFailed by remember { mutableStateOf(false) }
    var profileSaveWarning by remember { mutableStateOf(false) }
    var proceedWithoutSavedProfile by remember { mutableStateOf(false) }
    var suppressAutoNavigation by remember { mutableStateOf(false) }

    LaunchedEffect(rig.link, authAcceptedGeneration, authenticatedGeneration, pendingProfile, pendingGeneration, profileSaveFailed, proceedWithoutSavedProfile, suppressAutoNavigation) {
        val profile = pendingProfile
        if (profile != null && pendingGeneration > 0L && authAcceptedGeneration == pendingGeneration &&
            pendingSavedGeneration != pendingGeneration) {
            val ok = withContext(Dispatchers.IO) { RadioProfiles.save(ctx, profile) }
            profileSaveFailed = !ok
            if (!ok) error = "登录成功，但系统密钥库无法安全保存密码。"
            pendingSavedGeneration = pendingGeneration
        }
        if (rig.link == RigState.Link.CONNECTED && pendingGeneration > 0L &&
            authenticatedGeneration == pendingGeneration) {
            if (profile != null && pendingSavedGeneration != pendingGeneration) {
                val ok = withContext(Dispatchers.IO) { RadioProfiles.save(ctx, profile) }
                profileSaveFailed = !ok
                if (!ok) error = "登录成功，但系统密钥库无法安全保存密码。"
            }
            if (profileSaveFailed && !proceedWithoutSavedProfile) {
                busy = false
                profileSaveWarning = true
                return@LaunchedEffect
            }
            if (suppressAutoNavigation) return@LaunchedEffect
            pendingProfile = null
            pendingGeneration = 0L
            pendingSavedGeneration = 0L
            busy = false
            vm.show(Screen.Control)
        }
    }
    LaunchedEffect(loggedIn, pendingGeneration, suppressAutoNavigation) {
        if (!suppressAutoNavigation && loggedIn && pendingGeneration == 0L) {
            busy = false
            vm.show(Screen.Control)
        }
    }
    LaunchedEffect(status, loggedIn) {
        if (busy && !loggedIn && status.isNotBlank()) {
            when {
                status.contains("认证未通过") || status.contains("拒绝用户名或密码") -> {
                    IcomNet.disconnect()
                    busy = false
                    error = "用户名或密码错误，请修改后重新登录。"
                    pendingProfile = null
                    pendingGeneration = 0L
                }
                status.contains("尚未释放") || status.contains("拒绝传输会话") || status.contains("0x50") ->
                    error = "传输会话被拒绝：请检查端口，或等待电台释放上一次异常关闭的会话。正在自动重试。"
                status.contains("网络错误") || status.contains("未建立") || status.contains("连接失败") ->
                    error = "网络暂时不可用，正在自动重试…"
                status.contains("软开机超时") -> {
                    IcomNet.disconnect()
                    busy = false
                    error = status
                    pendingProfile = null
                    pendingGeneration = 0L
                }
            }
        }
    }
    LaunchedEffect(busy, loginStartedMs) {
        if (busy && loginStartedMs > 0L) {
            while (busy && !loggedIn && System.currentTimeMillis() - loginStartedMs < 90_000L) kotlinx.coroutines.delay(1000)
            if (busy && !loggedIn && System.currentTimeMillis() - loginStartedMs >= 90_000L) {
                if (pendingGeneration > 0L && IcomNet.isAuthenticated(pendingGeneration)) {
                    busy = false
                    pendingProfile = null
                    pendingGeneration = 0L
                    vm.show(Screen.Control)
                    return@LaunchedEffect
                }
                IcomNet.disconnect()
                busy = false
                error = "登录或开机超时。可能是用户名/密码错误，也可能是 IC-705 仍占用上一次异常关闭的会话；请稍后重试。"
                pendingProfile = null
                pendingGeneration = 0L
            }
        }
    }
    LaunchedEffect(busy, powerWaitExpired) {
        if (busy && !powerWaitExpired) {
            kotlinx.coroutines.delay(10_000)
            powerWaitExpired = true
        }
    }

    fun connect(wake: Boolean = true) {
        val cp = if (defaults) 50001 else control.toIntOrNull() ?: 0
        val sp = if (defaults) 50002 else serial.toIntOrNull() ?: 0
        val ap = if (defaults) 50003 else audio.toIntOrNull() ?: 0
        error = when {
            address.isBlank() -> "请输入电台地址。"
            user.isBlank() -> "请输入用户名。"
            password.isBlank() -> "请输入密码。"
            cp !in 1..65535 || sp !in 1..65535 || ap !in 1..65535 -> "端口必须是 1–65535。"
            cp == sp || cp == ap || sp == ap -> "三个端口不能重复。"
            else -> ""
        }
        if (error.isNotEmpty()) return
        busy = true
        loginStartedMs = System.currentTimeMillis()
        powerWaitExpired = false
        error = ""
        profileSaveFailed = false
        profileSaveWarning = false
        proceedWithoutSavedProfile = false
        suppressAutoNavigation = false
        vm.updateSettings {
            it.copy(
                wifiIp = address.trim(), wifiUser = user.trim(), wifiPass = "",
                wifiPort = cp, wifiCivPort = sp, wifiAudioPort = ap,
                useDefaultPorts = defaults, audio = AudioChoice.WIFI,
            )
        }
        val host = address.trim()
        val name = active?.takeIf { it.address == host && it.user == user.trim() }?.name ?: host
        val existing = profiles.firstOrNull { it.address == host && it.user == user.trim() }
        val profile = (existing ?: RadioProfile(name = name, address = host, user = user.trim(), password = password)).copy(
            address = host, user = user.trim(), password = password,
            controlPort = cp, serialPort = sp, audioPort = ap, useDefaultPorts = defaults,
        )
        pendingProfile = profile
        pendingGeneration = IcomNet.connect(ctx, host, cp, user.trim(), password, if (defaults) -1 else sp, if (defaults) -1 else ap, powerOn = wake)
    }

    LaunchedEffect(settingsReady) {
        if (!settingsReady) return@LaunchedEffect
        val (snapshot, activeId) = withContext(Dispatchers.IO) {
            RadioProfiles.loadSnapshot(ctx) to RadioProfiles.activeId(ctx)
        }
        if (snapshot.loadFailed) {
            error = "保存的电台档案读取失败，为避免覆盖数据，本次不会自动登录。请重新输入或修复应用存储。"
            return@LaunchedEffect
        }
        profiles = snapshot.profiles
        val activeProfile = snapshot.profiles.firstOrNull { it.id == activeId }
        val activeUnreadable = activeId.isNotBlank() && activeId in snapshot.unreadableIds
        val profile = if (activeUnreadable) activeProfile
            else activeProfile?.takeIf { it.password.isNotBlank() }
                ?: snapshot.profiles.firstOrNull { it.password.isNotBlank() }
                ?: activeProfile
        active = profile
        if (profile != null) {
            address = profile.address
            user = profile.user
            password = profile.password
            defaults = profile.useDefaultPorts
            control = profile.controlPort.toString()
            serial = profile.serialPort.toString()
            audio = profile.audioPort.toString()
        }
        if (activeUnreadable) {
            password = ""
            error = "已保存的密码无法从系统密钥库解密，请重新输入密码。"
            return@LaunchedEffect
        }
        if (!IcomNet.autoLoginAllowed()) return@LaunchedEffect
        if (IcomNet.hasActiveTarget()) {
            busy = true
            loginStartedMs = System.currentTimeMillis()
            return@LaunchedEffect
        }
        if (profile != null && profile.address.isNotBlank() && profile.user.isNotBlank() && profile.password.isNotBlank()) connect(wake = true)
    }

    if (profileSaveWarning) AlertDialog(
        onDismissRequest = { },
        title = { uk.hamdigital.ui.Text("密码未保存") },
        text = { uk.hamdigital.ui.Text("本次已经登录，但系统密钥库无法安全保存密码，下一次启动将需要重新输入。可以继续本次控制，或断开后检查设备安全设置。") },
        confirmButton = {
            TextButton({
                profileSaveWarning = false
                proceedWithoutSavedProfile = true
            }) { uk.hamdigital.ui.Text("仅本次继续") }
        },
        dismissButton = {
            TextButton({
                profileSaveWarning = false
                suppressAutoNavigation = true
                busy = false
                pendingProfile = null
                pendingGeneration = 0L
                pendingSavedGeneration = 0L
                IcomNet.disconnect()
            }) { uk.hamdigital.ui.Text("断开连接", color = Pal.Red) }
        },
    )

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B101A), Pal.Bg, Color(0xFF020305))))
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                uk.hamdigital.ui.Text("ARCH-705", color = Pal.Text, fontFamily = OrbitronFamily, fontWeight = FontWeight.Bold, fontSize = 25.sp, letterSpacing = (-0.4).sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                uk.hamdigital.ui.Text("IC-705 直连控制台", color = Pal.Cyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            }
            ArchNavAction("设置") { vm.show(Screen.SettingsPage) }
        }
        Spacer(Modifier.height(30.dp))
        uk.hamdigital.ui.Text("连接电台", color = Pal.Text, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-0.7).sp)
        uk.hamdigital.ui.Text("手机与 IC-705 直连。登录信息仅保存在本机。", color = Pal.Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 7.dp, bottom = 18.dp))
        ArchCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CompactField(address, { address = it.trim() }, "地址 / IP 或域名", Modifier.fillMaxWidth())
                CompactField(user, { user = it }, "用户名", Modifier.fillMaxWidth())
                CompactField(password, { password = it }, "密码", Modifier.fillMaxWidth(), password = true)
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        uk.hamdigital.ui.Text("默认端口", color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        uk.hamdigital.ui.Text("50001 / 50002 / 50003", color = Pal.Muted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = defaults,
                        onCheckedChange = { defaults = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Pal.Green, uncheckedThumbColor = Pal.Text2, uncheckedTrackColor = Pal.Surface3),
                    )
                }
                if (!defaults) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        PortField("控制", control, { control = it }, Modifier.weight(1f))
                        PortField("串口", serial, { serial = it }, Modifier.weight(1f))
                        PortField("音频", audio, { audio = it }, Modifier.weight(1f))
                    }
                }
                if (error.isNotEmpty()) uk.hamdigital.ui.Text(error, color = Pal.Red, fontSize = 12.sp, lineHeight = 17.sp)
                if (busy) uk.hamdigital.ui.Text(
                    if (!powerWaitExpired) "正在软开机，请等待约 10 秒，不要反复按开机键。"
                    else "正在建立远程会话；旧会话释放后会自动重试。",
                    color = if (powerWaitExpired) Pal.Text2 else Pal.Cyan,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                ArchPressable(onClick = { connect(wake = true) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.fillMaxSize().clip(ArchControlShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        if (busy) Pal.Accent.copy(alpha = 0.35f) else Pal.Accent,
                                        if (busy) Pal.Accent.copy(alpha = 0.16f) else Pal.Cyan,
                                    ),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) { uk.hamdigital.ui.Text(if (busy && !powerWaitExpired) "请等待约 10 秒，正在开机…" else if (busy) "正在连接或等待旧会话释放…" else "登录并开机", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
                if (busy) {
                    ArchPressable(onClick = {
                        IcomNet.disconnect()
                        vm.updateSettings { it.copy(audio = AudioChoice.AUTO) }
                        busy = false
                        pendingProfile = null
                        pendingGeneration = 0L
                        pendingSavedGeneration = 0L
                        error = "已取消连接，请检查地址、端口和 VPN 后重新登录"
                    }, modifier = Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
                        uk.hamdigital.ui.Text("取消连接", color = Pal.Amber, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        if (status.isNotBlank()) {
            ArchCard(Modifier.fillMaxWidth().padding(top = 12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp), color = Pal.Surface2) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (status.contains("成功") || status.contains("已连接")) Pal.Green else Pal.Amber))
                    uk.hamdigital.ui.Text(status, color = Pal.Text2, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun PortField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    CompactField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(5)) },
        label = label,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
