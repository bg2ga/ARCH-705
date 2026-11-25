package uk.hamdigital

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.audio.VoiceTx
import uk.hamdigital.core.PttLock
import uk.hamdigital.core.Settings as AppSettings
import uk.hamdigital.rig.Ic705
import uk.hamdigital.rig.RigState
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class ArchFloatingPttService : AccessibilityService() {
    companion object {
        private const val PREFS = "floating_ptt_overlay"
        private const val KEY_SUPPRESSED = "suppressed"
        @Volatile private var live: ArchFloatingPttService? = null

        private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private fun isSuppressed(ctx: Context) = prefs(ctx).getBoolean(KEY_SUPPRESSED, false)

        fun isEnabled(ctx: Context): Boolean {
            val expected = ComponentName(ctx, ArchFloatingPttService::class.java)
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val on = Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            return on && enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }

        fun openSettings(ctx: Context) {
            val component = ComponentName(ctx, ArchFloatingPttService::class.java)
            val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra("android.intent.extra.COMPONENT_NAME", component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!runCatching { ctx.startActivity(details) }.isSuccess) {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        fun refresh(ctx: Context) {
            prefs(ctx).edit().putBoolean(KEY_SUPPRESSED, false).apply()
            live?.syncFromSettings()
        }

        fun resumeFromForeground(ctx: Context) {
            prefs(ctx).edit().putBoolean(KEY_SUPPRESSED, false).apply()
            live?.syncFromSettings()
        }

        fun disableFromExit(ctx: Context) {
            prefs(ctx).edit().putBoolean(KEY_SUPPRESSED, true).apply()
            live?.hideOverlay()
            ArchOverlayService.stop(ctx)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var overlay: ArchPttOverlayView? = null
    private var settings = AppSettings()
    private var collectJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        live = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        collectSettings()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        emergencyStop()
        overlay?.setActive(false)
    }

    private fun collectSettings() {
        if (collectJob != null) return
        collectJob = scope.launch {
            (application as HamDigitalApp).settings.flow.collect { value ->
                settings = value
                if (!value.floatingPtt || isSuppressed(this@ArchFloatingPttService)) {
                    hideOverlay()
                } else {
                    ensureOverlay()
                }
            }
        }
        scope.launch { PttLock.locked.collect { overlay?.setLocked(it) } }
        scope.launch { Transmitter.on.collect { overlay?.setActive(it) } }
        scope.launch { Ic705.state.collect { overlay?.setConnected(it.link == RigState.Link.CONNECTED) } }
    }

    private fun syncFromSettings() {
        if (!isEnabled(this)) return
        if (settings.floatingPtt && !isSuppressed(this)) ensureOverlay() else hideOverlay()
    }

    private fun ensureOverlay() {
        val current = overlay
        if (current != null) {
            current.setLocked(PttLock.locked.value)
            current.setActive(Transmitter.on.value)
            current.setConnected(Ic705.state.value.link == RigState.Link.CONNECTED)
            return
        }
        val wm = windowManager ?: return
        val view = ArchPttOverlayView(
            this,
            wm,
            onPttStart = {
                val ok = VoiceTx.start(this, settings)
                if (!ok) toast(VoiceTx.lastError.value.ifBlank { "PTT 无法启动" })
                ok
            },
            onPttStop = { VoiceTx.stop() },
            onToggleLock = {
                VoiceTx.stop()
                PttLock.toggle()
            },
        )
        val size = view.windowSizePx
        val dm = resources.displayMetrics
        val prefs = getSharedPreferences("floating_ptt_overlay", MODE_PRIVATE)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", (dm.widthPixels - size - view.marginPx).coerceAtLeast(view.marginPx))
            y = prefs.getInt("y", ((dm.heightPixels - size) / 2).coerceAtLeast(view.marginPx))
        }
        view.attach(lp)
        view.setLocked(PttLock.locked.value)
        view.setActive(Transmitter.on.value)
        view.setConnected(Ic705.state.value.link == RigState.Link.CONNECTED)
        runCatching { wm.addView(view, lp) }
        overlay = view
    }

    private fun hideOverlay() {
        val view = overlay ?: return
        view.cancelPendingStart()
        VoiceTx.stop()
        runCatching { windowManager?.removeView(view) }
        overlay = null
    }

    private fun emergencyStop() {
        VoiceTx.stop()
        PttLock.lock()
        uk.hamdigital.core.TxControl.stopAll()
        uk.hamdigital.rig.IcomNet.forceUnkey()
    }

    private fun toast(message: String) {
        if (message.isBlank()) return
        Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        hideOverlay()
        emergencyStop()
        collectJob?.cancel()
        scope.cancel()
        live = null
        super.onDestroy()
    }
}

internal class ArchPttOverlayView(
    context: Context,
    private val wm: WindowManager,
    private val onPttStart: () -> Boolean,
    private val onPttStop: () -> Unit,
    private val onToggleLock: () -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density
    private fun dpInt(value: Float): Int = (value * density).roundToInt()

    val windowSizePx: Int get() = dpInt(108f)
    val marginPx: Int get() = dpInt(12f)
    private var locked = true
    private var active = false
    private var connected = false
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var lockPress = false
    private var pttStarted = false
    private val slop = dp(9f)
    private val startRunnable = Runnable {
        if (!moved && !lockPress && !locked && !pttStarted) {
            pttStarted = onPttStart()
            invalidate()
        }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.8f) }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        elevation = dp(8f)
    }

    fun attach(layoutParams: WindowManager.LayoutParams) { params = layoutParams }
    fun cancelPendingStart() {
        handler.removeCallbacks(startRunnable)
        if (pttStarted || active) onPttStop()
        pttStarted = false
    }
    fun setLocked(value: Boolean) { locked = value; invalidate() }
    fun setActive(value: Boolean) { active = value; invalidate() }
    fun setConnected(value: Boolean) { connected = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f + dp(3f)
        val radius = min(width, height) / 2f - dp(11f)
        val body = when {
            active -> Color.rgb(225, 55, 52)
            !connected -> Color.rgb(92, 48, 52)
            locked -> Color.rgb(47, 56, 68)
            else -> Color.rgb(22, 86, 119)
        }
        val edge = when {
            active -> Color.rgb(255, 164, 158)
            !connected -> Color.rgb(199, 82, 82)
            locked -> Color.rgb(235, 166, 52)
            else -> Color.rgb(64, 214, 143)
        }
        fill.color = Color.argb(72, 0, 0, 0)
        canvas.drawCircle(cx, cy + dp(5f), radius, fill)
        fill.color = body
        canvas.drawCircle(cx, cy, radius, fill)
        ring.color = edge
        canvas.drawCircle(cx, cy, radius - dp(1f), ring)
        textPaint.color = when { active -> Color.WHITE; locked -> Color.rgb(184, 192, 204); else -> Color.rgb(83, 221, 170) }
        textPaint.textSize = if (active) dp(18f) else dp(17f)
        val label = when { active -> "发射"; locked -> "锁定"; else -> "PTT" }
        val baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, cx, baseline, textPaint)

        val bx = width - dp(18f)
        val by = dp(18f)
        val badgeColor = if (locked) Color.rgb(235, 166, 52) else Color.rgb(110, 122, 140)
        badgeFill.color = Color.argb(238, 26, 31, 40)
        canvas.drawCircle(bx, by, dp(13f), badgeFill)
        lockPaint.color = badgeColor
        drawLock(canvas, bx, by, locked)
    }

    private fun drawLock(canvas: Canvas, cx: Float, cy: Float, isLocked: Boolean) {
        val body = RectF(cx - dp(5f), cy - dp(1f), cx + dp(5f), cy + dp(7f))
        lockPaint.style = Paint.Style.STROKE
        val shackle = RectF(cx - dp(3.6f), cy - dp(7f), cx + dp(3.6f), cy + dp(1f))
        canvas.drawArc(shackle, 190f, 160f, false, lockPaint)
        if (!isLocked) {
            lockPaint.style = Paint.Style.FILL
            canvas.drawRect(cx + dp(3.2f), cy - dp(7f), cx + dp(5.6f), cy - dp(2.6f), lockPaint)
            lockPaint.style = Paint.Style.STROKE
        }
        lockPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(body, dp(2f), dp(2f), lockPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX; downRawY = event.rawY
                startX = params?.x ?: 0; startY = params?.y ?: 0
                moved = false
                pttStarted = false
                lockPress = event.x >= width - dp(34f) && event.y <= dp(34f)
                if (!lockPress && !locked) handler.postDelayed(startRunnable, 120)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                    moved = true
                    handler.removeCallbacks(startRunnable)
                    if (pttStarted) {
                        onPttStop()
                        pttStarted = false
                    }
                }
                if (moved) {
                    params?.let { p ->
                        val maxX = (resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - height).coerceAtLeast(0)
                        p.x = (startX + dx.toInt()).coerceIn(0, maxX)
                        p.y = (startY + dy.toInt()).coerceIn(0, maxY)
                        runCatching { wm.updateViewLayout(this, p) }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(startRunnable)
                if (pttStarted || active) onPttStop()
                pttStarted = false
                if (!moved && lockPress) onToggleLock()
                if (moved) params?.let { p ->
                    context.getSharedPreferences("floating_ptt_overlay", Context.MODE_PRIVATE).edit().putInt("x", p.x).putInt("y", p.y).apply()
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(startRunnable)
                if (pttStarted || active) onPttStop()
                pttStarted = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
