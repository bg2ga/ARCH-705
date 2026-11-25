package uk.hamdigital.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.MainViewModel
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.audio.VoiceTx
import uk.hamdigital.core.PttLock
import uk.hamdigital.rig.Ic705
import uk.hamdigital.rig.RigState


@Composable
fun InlinePttButton(vm: MainViewModel, modifier: Modifier = Modifier, height: Dp = 60.dp) {
    VoicePttButton(vm, modifier.fillMaxWidth(), compact = false, buttonHeight = height, onDrag = null)
}

@Composable
fun FloatingPttButton(vm: MainViewModel, modifier: Modifier = Modifier, onDrag: (Float, Float) -> Unit) {
    VoicePttButton(vm, modifier, compact = true, buttonHeight = 46.dp, onDrag = onDrag)
}

@Composable
private fun VoicePttButton(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    compact: Boolean,
    buttonHeight: Dp,
    onDrag: ((Float, Float) -> Unit)?,
) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val gate = rememberTxGate(vm)
    val rig by Ic705.state.collectAsStateWithLifecycle()
    val txOn by Transmitter.on.collectAsStateWithLifecycle()
    val locked by PttLock.locked.collectAsStateWithLifecycle()
    val latestOnDrag by rememberUpdatedState(onDrag)
    val active by VoiceTx.active.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf("") }
    LaunchedEffect(error) {
        if (error == "PTT 已锁定" || error == "PTT 已解锁") {
            delay(1000)
            error = ""
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) error = "需要麦克风权限"
    }

    fun stop() {
        if (!VoiceTx.active.value) return
        VoiceTx.stop()
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun start() {
        error = ""
        if (locked) { error = "PTT 已锁定，请先点右上角解锁"; return }
        if (rig.link != RigState.Link.CONNECTED) { error = "电台未连接"; return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!VoiceTx.start(ctx, settings)) {
            error = VoiceTx.lastError.value.ifBlank { "PTT 启动失败" }
            return
        }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val buttonScale by animateFloatAsState(if (active && txOn) 0.975f else 1f, spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow), label = "ptt-scale")
    val buttonColor by animateColorAsState(
        targetValue = if (active && txOn) Pal.Red else if (locked) Color(0xFF2A3038) else if (compact) Color(0xFF1B3A4A) else Color(0xFF17384A),
        animationSpec = androidx.compose.animation.core.tween(160),
        label = "ptt-color",
    )
    LaunchedEffect(txOn) {
        if (active && !txOn) {
            VoiceTx.stop()
            error = "发射已由安全机制停止"
        }
    }
    DisposableEffect(Unit) { onDispose { VoiceTx.stop() } }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (onDrag != null) {
            Box(
                Modifier.width(if (compact) 112.dp else 180.dp).height(24.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            latestOnDrag?.invoke(drag.x, drag.y)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                uk.hamdigital.ui.Text("↔", color = Pal.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (error.isNotBlank()) uk.hamdigital.ui.Text(error, color = Pal.Red, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp))
        val buttonSize = if (compact) Modifier.width(112.dp).height(46.dp) else Modifier.fillMaxWidth().height(buttonHeight)
        Box(buttonSize) {
            Box(
                Modifier.fillMaxSize().semantics { contentDescription = if (locked) "PTT 已锁定" else "按住发射" }
                    .graphicsLayer { scaleX = buttonScale; scaleY = buttonScale }
                    .clip(RoundedCornerShape(if (compact) 16.dp else 18.dp))
                    .background(buttonColor)
                    .border(1.dp, if (active && txOn) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(if (compact) 16.dp else 18.dp))
                    .then(if (!locked) Modifier.pointerInput(settings.txOk, gate) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            if (settings.txOk) start() else gate.ask { error = "已接受发射许可，请再次按住 PTT" }
                            waitForUpOrCancellation()
                            if (active) stop()
                        }
                    } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                uk.hamdigital.ui.Text(
                    when { active && txOn -> "发射"; locked -> "已锁定"; else -> "PTT" },
                    color = when { active -> Color.White; locked -> Pal.Muted; else -> Pal.Amber },
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 14.sp else 17.sp,
                )
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(if (compact) 27.dp else 30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (locked) Pal.Amber.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f))
                    .border(1.dp, if (locked) Pal.Amber.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                    .clickable {
                        if (active) stop()
                        PttLock.toggle()
                        error = if (PttLock.locked.value) "PTT 已锁定" else "PTT 已解锁"
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen, null, tint = if (locked) Pal.Amber else Pal.Text2, modifier = Modifier.size(if (compact) 15.dp else 17.dp)) }
        }
    }
}
