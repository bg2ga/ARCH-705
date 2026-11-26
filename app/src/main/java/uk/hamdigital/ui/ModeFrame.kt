// What every mode page shares: the frame (back arrow, title, UTC clock, where the audio comes from), the waterfall
// (a Spectrum's rows drawn into a bitmap, newest at the top), the input level bar, and the receive-audio controller
// that starts the capture when the page opens and stops it when the page closes.
package uk.hamdigital.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import uk.hamdigital.audio.AudioIn
import uk.hamdigital.audio.AudioOut
import uk.hamdigital.audio.Spectrum
import uk.hamdigital.core.AudioChoice
import kotlin.math.log10

/** 0..1 -> dark blue, blue, cyan, yellow, red (HF Propagation's and the Tab5's waterfall colours). */
fun wfColour(v: Float): Color {
    val k = arrayOf(floatArrayOf(8f, 12f, 30f), floatArrayOf(20f, 60f, 200f), floatArrayOf(0f, 220f, 255f), floatArrayOf(255f, 220f, 0f), floatArrayOf(255f, 50f, 30f))
    val x = v.coerceIn(0f, 1f) * 4; val i = x.toInt().coerceAtMost(3); val f = x - i // which pair, how far between
    return Color((k[i][0] + (k[i + 1][0] - k[i][0]) * f) / 255f, (k[i][1] + (k[i + 1][1] - k[i][1]) * f) / 255f, (k[i][2] + (k[i + 1][2] - k[i][2]) * f) / 255f)
}

private val WF_ARGB = IntArray(256) { val c = wfColour(it / 255f); android.graphics.Color.rgb((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()) } // the colours, as pixels

/** A page's frame: an iOS-style compact navigation bar, with optional UTC/BJT only on the main console. */
@Composable
fun ModeFrame(title: String, onBack: () -> Unit, showBack: Boolean = true, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A0D13), Pal.Bg, Color(0xFF020305))))
            .systemBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                ArchPressable(onClick = onBack, modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Pal.Text2, modifier = Modifier.size(21.dp))
                    }
                }
            } else Spacer(Modifier.width(2.dp))
            uk.hamdigital.ui.Text(
                title,
                fontFamily = OrbitronFamily,
                fontWeight = FontWeight.Bold,
                color = if (title == "ARCH-705") Pal.Text else Pal.Text2,
                fontSize = if (title == "ARCH-705") 18.sp else 21.sp,
                letterSpacing = (-0.3).sp,
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = if (showBack) 10.dp else 2.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(top = 2.dp), content = content)
    }
}

/** Receive audio while the page is open: asks for the permission the first time; returns the status for the page. */
class RxState { var error by mutableStateOf<String?>(null); var running by mutableStateOf(false) } // what went wrong / capturing

@Composable
fun rememberRx(rate: Int, block: Int, choice: AudioChoice, monitorWifi: Boolean = true, sink: (ShortArray, Int) -> Unit): RxState {
    val ctx = LocalContext.current
    val st = remember { RxState() }
    val audioOwner = remember { Any() }
    val usbPresent = remember(choice) { AudioIn.usbInput(ctx) != null }
    val wifiLoggedIn by uk.hamdigital.rig.IcomNet.loginState.collectAsStateWithLifecycle()
    val actualChoice = if (wifiLoggedIn || uk.hamdigital.rig.IcomNet.loggedIn) AudioChoice.WIFI else choice
    val useWifi = actualChoice == AudioChoice.WIFI || (actualChoice == AudioChoice.AUTO && uk.hamdigital.rig.IcomNet.loggedIn)
    val monitorRx = monitorWifi && (useWifi || actualChoice == AudioChoice.USB || (actualChoice == AudioChoice.AUTO && usbPresent))
    fun go(use: AudioChoice) {
        val monitored: (ShortArray, Int) -> Unit = { b, n ->
            if (monitorRx) AudioOut.writePcm(b, n, rate)
            sink(b, n)
        }
        st.error = AudioIn.start(ctx, st, use, rate, block, monitored)
        st.running = st.error == null
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) go(actualChoice) else st.error = "未允许音频权限，请到 Android 设置 > 应用 > ARCH-705 > 权限中开启"
    }
    DisposableEffect(actualChoice) {
        if (monitorRx) AudioOut.open(audioOwner)
        if (useWifi || AudioIn.allowed(ctx)) go(actualChoice)
        else ask.launch(android.Manifest.permission.RECORD_AUDIO)
        onDispose {
            AudioIn.stop(st)
            st.running = false
            if (monitorRx) AudioOut.close(audioOwner)
        }
    }
    return st
}

/** The status line under the top bar: where the audio is from, or what is wrong. */
@Composable
fun RxStatus(rx: RxState, extra: String = "") {
    var lvl by remember { mutableFloatStateOf(0f) }   // input level, refreshed
    var link by remember { mutableStateOf("") }       // the WiFi link's state while it is not up ("" = up, or not WiFi)
    var loss by remember { mutableStateOf("") }       // WiFi audio packets lost / recovered (shown once any are)
    val outError by AudioOut.error.collectAsStateWithLifecycle()
    val captureError by AudioIn.captureError.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { while (true) { lvl = AudioIn.level
        link = if (AudioIn.wifi && !uk.hamdigital.rig.IcomNet.loggedIn) "WiFi: ${uk.hamdigital.rig.IcomNet.status.value.ifEmpty { "未连接，请到设置页检查连接" }}" else ""
        loss = if (AudioIn.wifi) uk.hamdigital.rig.IcomNet.rxAudioStats()?.takeIf { it[1] + it[2] > 0 }?.let { "  •  WiFi 音频：丢失 %d，恢复 %d，共 %d".format(it[1], it[2], it[0]) } ?: "" else ""
        delay(100) } }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        uk.hamdigital.ui.Text(rx.error ?: outError ?: captureError ?: link.ifEmpty { if (rx.running) "音频：${AudioIn.sourceName}$loss" else "正在启动音频…" }, color = if (rx.error != null || outError != null || captureError != null) Pal.Red else if (link.isNotEmpty()) Pal.Amber else Pal.Text2,
            fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2)
        if (extra.isNotEmpty()) uk.hamdigital.ui.Text(extra, color = Pal.Amber, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp)) // the page's own note
        val db = if (lvl > 0.001f) 20 * log10(lvl) else -60f // level in dB
        LinearProgressIndicator({ ((db + 60) / 60).coerceIn(0f, 1f) }, Modifier.width(80.dp).height(6.dp),
            color = if (lvl > 0.9f) Pal.Red else Pal.Green, trackColor = Pal.Tert) // red: too loud
    }
}

/**
 * The waterfall: [spec]'s rows, newest at the top, [rows] high, with a frequency scale under it. [marks] are frequencies
 * (Hz) to draw a line at (e.g. the RX and TX offsets); [onTap] gets the frequency tapped.
 */
@Composable
fun Waterfall(spec: Spectrum, modifier: Modifier, rows: Int = 200, marks: List<Pair<Float, Color>> = emptyList(), onTap: ((Float) -> Unit)? = null) {
    val w = spec.bins                                 // one pixel a bin
    val px = remember(spec) { IntArray(w * rows) { WF_ARGB[0] } } // the picture's pixels
    val bmp = remember(spec) { Bitmap.createBitmap(w, rows, Bitmap.Config.ARGB_8888) } // and its bitmap
    var tick by remember { mutableIntStateOf(0) }     // redraw
    LaunchedEffect(spec) {                            // take the new rows about 20 times a second
        while (true) {
            var got = false
            while (true) { val r = spec.rows.poll() ?: break; System.arraycopy(px, 0, px, w, w * (rows - 1)); for (i in 0 until w) px[i] = WF_ARGB[(r[i] * 255).toInt()]; got = true } // shift down, new row on top
            if (got) { bmp.setPixels(px, 0, w, 0, 0, w, rows); tick++ } // into the bitmap
            delay(50)
        }
    }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f).then(if (onTap != null) Modifier.pointerTapHz(spec, onTap) else Modifier)) {
            tick                                      // (redraw on each new row)
            drawImage(bmp.asImageBitmap(), IntOffset.Zero, IntSize(w, rows), dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low) // stretched to fit
            marks.forEach { (hz, c) -> val x = (hz - spec.minHz) / (spec.maxHz - spec.minHz) * size.width; drawLine(c, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 2f) } // markers
        }
        Row(Modifier.fillMaxWidth()) { for (k in 0 until 6) { val hz = spec.minHz + k * (spec.maxHz - spec.minHz) / 6; uk.hamdigital.ui.Text(if (k == 0) "$hz Hz" else "$hz", Modifier.weight(1f), color = Pal.Dim, fontSize = 10.sp) } } // scale
    }
}

private fun Modifier.pointerTapHz(spec: Spectrum, onTap: (Float) -> Unit) =
    this.pointerInput(spec) { detectTapGestures { p -> onTap(spec.minHz + p.x / size.width * (spec.maxHz - spec.minHz)) } } // tap: that frequency

