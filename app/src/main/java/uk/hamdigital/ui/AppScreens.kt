package uk.hamdigital.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.hamdigital.MainViewModel
import uk.hamdigital.Screen
import uk.hamdigital.core.Mode

@Composable
fun AppScreens(vm: MainViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = Pal.Bg) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 14 }) togetherWith
                        (fadeOut(tween(140)) + slideOutHorizontally(tween(180)) { -it / 18 })
                },
                label = "arch-screen-transition",
            ) { current ->
                when (current) {
                    Screen.Connect -> ConnectScreen(vm)
                    Screen.Functions -> FunctionsScreen(vm)
                    Screen.Control -> ControlScreen(vm)
                    is Screen.ModePage -> ModePage(vm, current.mode)
                    is Screen.DigitalPage -> DvScreen(vm, current.mode)
                    Screen.SettingsPage -> SettingsScreen(vm)
                    Screen.Guide -> ChineseGuideScreen { vm.back() }
                    Screen.Log -> LogbookScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun ModePage(vm: MainViewModel, m: Mode) {
    remember(m) { uk.hamdigital.core.TxControl.stopOthers(m); 0 }
    DisposableEffect(m) {
        onDispose { uk.hamdigital.core.TxControl.stopAll() }
    }
    key(m) { ModePageFor(vm, m) }
}

@Composable
private fun ModePageFor(vm: MainViewModel, m: Mode) = when (m) {
    Mode.CW -> CwScreen(vm)
    Mode.FT8, Mode.FT4, Mode.FT2 -> Ft8Screen(vm, m)
    Mode.WSPR -> WsprScreen(vm)
    Mode.RTTY, Mode.PSK31, Mode.OLIVIA -> KeyboardScreen(vm, m)
    Mode.JS8 -> Js8Screen(vm)
    Mode.JTTY -> JttyScreen(vm)
    Mode.SSTV -> SstvScreen(vm)
    Mode.DSTAR -> DvScreen(vm)
    Mode.FREEDV -> FreeDvScreen(vm)
    Mode.WEFAX -> WefaxScreen(vm)
    Mode.APRS -> AprsScreen(vm)
    Mode.FREEDATA -> FreeDataScreen(vm)
}

@Composable
private fun FunctionsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
    val digital = listOf(Mode.CW, Mode.FT8, Mode.WSPR, Mode.JS8, Mode.RTTY, Mode.JTTY, Mode.PSK31, Mode.OLIVIA)
    val imageVoice = listOf(Mode.SSTV, Mode.FREEDV, Mode.WEFAX, Mode.APRS, Mode.FREEDATA, Mode.DSTAR)
    ModeFrame("功能", { vm.back() }) {
        LazyVerticalGrid(
            GridCells.Adaptive(if (compact) 145.dp else 170.dp),
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 90.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 2.dp)) {
                    uk.hamdigital.ui.Text(if (s.callsign.isBlank()) "选择功能模式" else s.callsign + " · 选择功能模式", color = Pal.Text2, fontSize = 13.sp)
                    uk.hamdigital.ui.Text("通信、图像与数字语音工具", color = Pal.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { FunctionSection("数据通信") }
            items(digital) { m -> ModeTile(m, compact) { vm.show(Screen.ModePage(m)) } }
            item(span = { GridItemSpan(maxLineSpan) }) { FunctionSection("图像与语音") }
            items(imageVoice) { m -> ModeTile(m, compact) { vm.show(Screen.ModePage(m)) } }
            item(span = { GridItemSpan(maxLineSpan) }) { FunctionSection("数字语音解码") }
            item { FunctionTile("DMR", "色码、时隙、ID、组呼", Icons.Filled.RecordVoiceOver, compact) { vm.show(Screen.DigitalPage("DMR*")) } }
            item { FunctionTile("YSF / C4FM", "DG-ID、呼号、组呼", Icons.Filled.GraphicEq, compact) { vm.show(Screen.DigitalPage("C4FM*")) } }
            item { FunctionTile("P25 Phase 1", "NAC、单位 ID、组呼", Icons.Filled.Radio, compact) { vm.show(Screen.DigitalPage("P25*")) } }
        }
    }
}

@Composable
private fun FunctionSection(title: String) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(Pal.Accent))
        uk.hamdigital.ui.Text(title, color = Pal.Text2, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun FunctionTile(title: String, blurb: String, icon: ImageVector, compact: Boolean, onClick: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val tileHeight = (if (compact) 96.dp else 112.dp) * fontScale
    ArchPressable(onClick, Modifier.fillMaxWidth().height(tileHeight)) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(19.dp))
                .background(Pal.Surface1)
                .border(1.dp, Pal.Separator, RoundedCornerShape(19.dp))
                .padding(if (compact) 11.dp else 14.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(if (compact) 32.dp else 38.dp).clip(RoundedCornerShape(11.dp)).background(Pal.Amber.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = Pal.Amber, modifier = Modifier.size(if (compact) 19.dp else 23.dp))
                    }
                    uk.hamdigital.ui.Text(title, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = if (compact) 15.sp else 18.sp, color = Pal.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 9.dp))
                }
                uk.hamdigital.ui.Text(blurb, color = Pal.Muted, fontSize = if (compact) 11.sp else 12.sp, lineHeight = if (compact) 15.sp else 17.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun ModeTile(m: Mode, compact: Boolean, onClick: () -> Unit) {
    val title = if (m == Mode.FT8) "FT8 / FT4 / FT2" else m.title
    val blurb = if (m == Mode.FT8) "弱信号数字通联" else m.blurb
    val accent = modeColor(m)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    ArchPressable(onClick, Modifier.fillMaxWidth().height((if (compact) 96.dp else 112.dp) * fontScale)) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(19.dp))
                .background(if (m.working) accent.copy(alpha = 0.075f) else Pal.Surface1)
                .border(1.dp, Pal.Separator, RoundedCornerShape(19.dp))
                .padding(if (compact) 11.dp else 14.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(if (compact) 32.dp else 38.dp).clip(RoundedCornerShape(11.dp)).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(iconFor(m), null, tint = accent, modifier = Modifier.size(if (compact) 19.dp else 23.dp))
                    }
                    uk.hamdigital.ui.Text(title, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = if (compact) 15.sp else 18.sp, color = Pal.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 9.dp))
                }
                uk.hamdigital.ui.Text(blurb, color = Pal.Muted, fontSize = if (compact) 11.sp else 12.sp, lineHeight = if (compact) 15.sp else 17.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

private fun modeColor(m: Mode) = when (m) {
    Mode.FT8, Mode.FT4, Mode.FT2 -> Pal.Accent
    Mode.WSPR -> Pal.Cyan
    Mode.JS8 -> Pal.Purple
    Mode.SSTV, Mode.WEFAX -> Pal.Amber
    Mode.DSTAR, Mode.FREEDV -> Pal.Green
    Mode.APRS, Mode.FREEDATA -> Pal.Sky
    else -> Pal.Accent
}

private fun iconFor(m: Mode) = when (m) {
    Mode.FT8, Mode.FT4, Mode.FT2 -> Icons.Filled.GraphicEq
    Mode.WSPR -> Icons.Filled.Public
    Mode.JS8 -> Icons.AutoMirrored.Filled.Chat
    Mode.RTTY, Mode.JTTY, Mode.PSK31, Mode.OLIVIA -> Icons.Filled.Keyboard
    Mode.CW -> Icons.Filled.Waves
    Mode.SSTV -> Icons.Filled.Image
    Mode.DSTAR -> Icons.Filled.RecordVoiceOver
    Mode.FREEDV -> Icons.Filled.Mic
    Mode.WEFAX -> Icons.Filled.Map
    Mode.APRS -> Icons.Filled.LocationOn
    Mode.FREEDATA -> Icons.Filled.Cloud
}
