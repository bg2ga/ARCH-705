package uk.hamdigital.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import uk.hamdigital.icom.IcomScopeAssembler
import uk.hamdigital.rig.IcomNet
import kotlin.math.roundToInt

private const val WATERFALL_ROWS = 120
private const val AMPLITUDE_MAX = 160f

private fun scopePixel(value: Int): Int {
    val v = value.coerceIn(0, 160) / AMPLITUDE_MAX
    val k = Math.pow(v.toDouble(), 0.58).toFloat().coerceIn(0f, 1f)
    val (r, g, b) = when {
        k < 0.10f -> Triple(0, 4, 28)
        k < 0.24f -> Triple(0, 18, 78)
        k < 0.40f -> Triple(0, 58, 155)
        k < 0.56f -> Triple(0, 142, 220)
        k < 0.70f -> Triple(12, 230, 235)
        k < 0.82f -> Triple(96, 245, 210)
        k < 0.91f -> Triple(255, 224, 55)
        k < 0.97f -> Triple(255, 132, 18)
        else -> Triple(255, 30, 20)
    }
    return android.graphics.Color.rgb(r, g, b)
}

@Composable
fun IcomScopePanel(
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
    showHeader: Boolean = true,
    spanText: String = "±25 kHz",
    speedText: String = "",
    refreshFps: Int = 30,
    enabled: Boolean = true,
    tuneLocked: Boolean = false,
    lockText: String? = null,
    onLockClick: (() -> Unit)? = null,
    onSpanClick: (() -> Unit)? = null,
    onSpeedClick: (() -> Unit)? = null,
    onRefreshClick: (() -> Unit)? = null,
    onFrequencyPreview: ((Long) -> Unit)? = null,
    onFrequencyDragChange: ((Boolean) -> Unit)? = null,
    onFrequencyTap: ((Long) -> Unit)? = null,
) {
    var frame by remember { mutableStateOf<IcomScopeAssembler.Frame?>(null) }
    val pixels = remember { IntArray(475 * WATERFALL_ROWS) { scopePixel(0) } }
    val buffers = remember { Array(2) { Bitmap.createBitmap(475, WATERFALL_ROWS, Bitmap.Config.ARGB_8888) } }
    DisposableEffect(buffers) { onDispose { buffers.forEach { if (!it.isRecycled) it.recycle() } } }
    var bufferSlot by remember { mutableIntStateOf(0) }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var frameCount by remember { mutableIntStateOf(0) }
    var fps by remember { mutableIntStateOf(0) }
    var trail1 by remember { mutableStateOf<IntArray?>(null) }
    var trail2 by remember { mutableStateOf<IntArray?>(null) }
    var trail3 by remember { mutableStateOf<IntArray?>(null) }
    var trail4 by remember { mutableStateOf<IntArray?>(null) }
    var trail5 by remember { mutableStateOf<IntArray?>(null) }
    LaunchedEffect(refreshFps) {
        var lastFrameMs = 0L
        val minFrameMs = (1000L / refreshFps.coerceIn(1, 60)).coerceAtLeast(16L)
        IcomNet.scope.collect { f ->
            if (f == null) {
                frame = null
                image = null
                trail1 = null; trail2 = null; trail3 = null; trail4 = null; trail5 = null
                frameCount = 0
                lastFrameMs = 0L
                return@collect
            }
            val now = System.currentTimeMillis()
            if (now - lastFrameMs < minFrameMs) return@collect
            lastFrameMs = now
            frame = f
            frameCount++
            trail5 = trail4
            trail4 = trail3
            trail3 = trail2
            trail2 = trail1
            trail1 = f.amplitudes.copyOf()
            val width = f.amplitudes.size.coerceAtMost(475)
            if (width > 0) {
                val slot = bufferSlot
                image = withContext(Dispatchers.Default) {
                    System.arraycopy(pixels, 0, pixels, width, pixels.size - width)
                    for (x in 0 until width) pixels[x] = scopePixel(f.amplitudes[x])
                    buffers[slot].setPixels(pixels, 0, 475, 0, 0, 475, WATERFALL_ROWS)
                    buffers[slot]
                }
                bufferSlot = 1 - slot
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            fps = frameCount
            frameCount = 0
        }
    }
    val f = frame
    val displayFrame by rememberUpdatedState(frame)
    val latestPreview by rememberUpdatedState(onFrequencyPreview)
    val latestTap by rememberUpdatedState(onFrequencyTap)
    val latestDragChange by rememberUpdatedState(onFrequencyDragChange)
    var cursorHz by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(enabled, tuneLocked) {
        if (!enabled || tuneLocked) {
            cursorHz = null
            latestDragChange?.invoke(false)
        }
    }
    fun frequencyAt(x: Float, width: Float): Long? {
        val scope = displayFrame ?: return null
        if (width <= 0f || scope.endMhz <= scope.startMhz) return null
        val mhz = scope.startMhz + (scope.endMhz - scope.startMhz) * (x / width).coerceIn(0f, 1f)
        val hz = (mhz * 1_000_000.0).toLong()
        return hz.takeIf { it in 30_000L..1_300_000_000L }
    }
    fun markerX(width: Float): Float? {
        val hz = cursorHz ?: return null
        val scope = f ?: return null
        if (width <= 0f || scope.endMhz <= scope.startMhz) return null
        val mhz = hz / 1_000_000.0
        if (mhz !in scope.startMhz..scope.endMhz) return null
        return ((mhz - scope.startMhz) / (scope.endMhz - scope.startMhz) * width).toFloat()
    }
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF070A10))
            .border(1.dp, Pal.Separator, RoundedCornerShape(20.dp)),
    ) {
        if (showHeader) Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = ArchPillShape,
                color = Pal.Accent.copy(alpha = 0.16f),
                modifier = if (onSpanClick != null) Modifier.clickable { onSpanClick() } else Modifier,
            ) { uk.hamdigital.ui.Text(spanText, color = Pal.Cyan, fontSize = 9.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) }
            if (speedText.isNotBlank()) Surface(
                shape = ArchPillShape,
                color = Color.White.copy(alpha = 0.07f),
                modifier = if (onSpeedClick != null) Modifier.clickable { onSpeedClick() } else Modifier,
            ) { uk.hamdigital.ui.Text(speedText, color = Pal.Text2, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) }
            if (lockText != null) Surface(
                shape = ArchPillShape,
                color = if (tuneLocked) Pal.Amber.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f),
                modifier = if (onLockClick != null) Modifier.clickable { onLockClick() } else Modifier,
            ) { uk.hamdigital.ui.Text(lockText, color = if (tuneLocked) Pal.Amber else Pal.Text2, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) }
            Spacer(Modifier.weight(1f))
            uk.hamdigital.ui.Text("${fps} FPS", color = Pal.Muted, fontSize = 9.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Surface(
                shape = ArchPillShape,
                color = Color.White.copy(alpha = 0.07f),
                modifier = if (onRefreshClick != null) Modifier.clickable { onRefreshClick() } else Modifier,
            ) { uk.hamdigital.ui.Text("刷新 ${refreshFps}", color = Pal.Text2, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)) }
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).then(
                if (enabled && !tuneLocked && latestTap != null) Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var selectedHz: Long? = null
                        var completed = false
                        fun moveTo(x: Float) {
                            val clamped = x.coerceIn(0f, size.width.toFloat())
                            val hz = frequencyAt(clamped, size.width.toFloat()) ?: return
                            if (hz == selectedHz) return
                            selectedHz = hz
                            cursorHz = hz
                            latestPreview?.invoke(hz)
                        }
                        latestDragChange?.invoke(true)
                        moveTo(down.position.x)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                moveTo(change.position.x)
                                change.consume()
                                if (!change.pressed) {
                                    completed = true
                                    break
                                }
                            }
                        } finally {
                            if (completed) selectedHz?.let { latestTap?.invoke(it) }
                            else cursorHz = null
                            latestDragChange?.invoke(false)
                        }
                    }
                } else Modifier,
            ),
        ) {
            Column(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxWidth().weight(1.4f)) {
            drawRect(Color(0xFF070A10))
            markerX(size.width)?.let { x ->
                drawLine(Color(0x3330D158), Offset(x, 0f), Offset(x, size.height), 7f)
                drawLine(Color(0xFF30D158), Offset(x, 0f), Offset(x, size.height), 2f)
                drawCircle(Color(0xFF30D158), 4.5f, Offset(x, 5f))
            }
            for (i in 0..10) {
                val x = size.width * i / 10f
                drawLine(Color(0xFF1D252B), Offset(x, 0f), Offset(x, size.height), 1f)
            }
            for (i in 0..4) {
                val y = size.height * i / 4f
                drawLine(Color(0xFF1D252B), Offset(0f, y), Offset(size.width, y), 1f)
            }
            fun drawTrace(a: IntArray?, alpha: Float, width: Float) {
                if (a == null || a.size < 2) return
                for (i in 1 until a.size) {
                    val x1 = (i - 1).toFloat() / (a.size - 1) * size.width
                    val x2 = i.toFloat() / (a.size - 1) * size.width
                    val y1 = size.height - (a[i - 1].coerceIn(0, 160) / AMPLITUDE_MAX) * size.height * 0.94f
                    val y2 = size.height - (a[i].coerceIn(0, 160) / AMPLITUDE_MAX) * size.height * 0.94f
                    val color = Color(scopePixel(maxOf(a[i - 1], a[i])))
                    drawLine(color.copy(alpha = alpha * 0.35f), Offset(x1, y1), Offset(x2, y2), width * 3.5f, StrokeCap.Round)
                    drawLine(color.copy(alpha = alpha), Offset(x1, y1), Offset(x2, y2), width, StrokeCap.Round)
                }
            }
            drawTrace(trail5, 0.10f, 1f)
            drawTrace(trail4, 0.15f, 1f)
            drawTrace(trail3, 0.22f, 1f)
            drawTrace(trail2, 0.32f, 1f)
            drawTrace(trail1, 0.52f, 1f)
            f?.let { scope ->
                val count = scope.amplitudes.size.coerceAtLeast(2)
                val step = size.width / (count - 1)
                val barWidth = step.coerceAtLeast(1f) * 1.15f
                for (i in 0 until count) {
                    val amplitude = scope.amplitudes[i].coerceIn(0, 160)
                    if (amplitude <= 2) continue
                    val x = i.toFloat() / (count - 1) * size.width
                    val y = size.height - (amplitude / AMPLITUDE_MAX) * size.height * 0.94f
                    drawLine(Color(scopePixel(amplitude)).copy(alpha = 0.30f), Offset(x, y), Offset(x, size.height), barWidth, StrokeCap.Round)
                }
                for (i in 1 until count) {
                    val x1 = (i - 1).toFloat() / (count - 1) * size.width
                    val x2 = i.toFloat() / (count - 1) * size.width
                    val y1 = size.height - (scope.amplitudes[i - 1].coerceIn(0, 160) / AMPLITUDE_MAX) * size.height * 0.94f
                    val y2 = size.height - (scope.amplitudes[i].coerceIn(0, 160) / AMPLITUDE_MAX) * size.height * 0.94f
                    val color = Color(scopePixel(maxOf(scope.amplitudes[i - 1], scope.amplitudes[i])))
                    drawLine(color.copy(alpha = 0.28f), Offset(x1, y1), Offset(x2, y2), 4.2f, StrokeCap.Round)
                    drawLine(color, Offset(x1, y1), Offset(x2, y2), 1.55f, StrokeCap.Round)
                }
                drawLine(Color(0x66FFFFFF), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1f)
            }
        }
        Canvas(Modifier.fillMaxWidth().weight(1.0f)) {
            drawRect(Color(0xFF020814))
            markerX(size.width)?.let { x ->
                drawLine(Color(0x3330D158), Offset(x, 0f), Offset(x, size.height), 7f)
                drawLine(Color(0xFF30D158), Offset(x, 0f), Offset(x, size.height), 2f)
                drawCircle(Color(0xFF30D158), 4.5f, Offset(x, 5f))
            }
            image?.let { bmp ->
                drawImage(bmp.asImageBitmap(), IntOffset.Zero, IntSize(475, WATERFALL_ROWS), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            }
            for (i in 1..9) {
                val x = size.width * i / 10f
                drawLine(Color(0x3A1E8BC0), Offset(x, 0f), Offset(x, size.height), 1f)
            }
            for (i in 1..5) {
                val y = size.height * i / 6f
                drawLine(Color(0x221E8BC0), Offset(0f, y), Offset(size.width, y), 1f)
            }
            drawLine(Color(0xAAE8F8FF), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1f)
        }
            }
        }
        if (showLabels) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                uk.hamdigital.ui.Text(f?.startMhz?.let { "%.6f".format(it) } ?: "—", color = Pal.Muted, fontSize = 9.sp)
                uk.hamdigital.ui.Text(f?.endMhz?.let { "%.6f".format(it) } ?: "—", color = Pal.Muted, fontSize = 9.sp)
            }
        }
    }
}
