package uk.hamdigital

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
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
import uk.hamdigital.rig.IcomNet
import uk.hamdigital.rig.RigState

class ArchOverlayService : Service() {
    companion object {
        private const val CHANNEL = "floating_overlay"
        private const val ID = 2
        @Volatile private var live: ArchOverlayService? = null

        fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

        fun start(ctx: Context) {
            if (!canDraw(ctx)) return
            runCatching { ctx.startForegroundService(Intent(ctx, ArchOverlayService::class.java)) }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ArchOverlayService::class.java)) }
        fun refresh(ctx: Context) { live?.sync() }

        fun openPermissionSettings(ctx: Context) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var overlay: ArchPttOverlayView? = null
    private var settings = AppSettings()
    private var collectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        live = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "悬浮 PTT", NotificationManager.IMPORTANCE_LOW).apply {
            description = "ARCH-705 系统悬浮 PTT"
        })
        if (!startForegroundCompat()) return
        collect()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("ARCH-705 · 悬浮 PTT")
            .setContentText("系统悬浮 PTT 已开启")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(ID, buildNotification(), type)
        } else startForeground(ID, buildNotification())
        true
    } catch (_: Exception) {
        stopSelf()
        false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundCompat()) return START_NOT_STICKY
        sync()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        hideOverlay()
        emergencyStop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun collect() {
        if (collectJob != null) return
        collectJob = scope.launch {
            (application as HamDigitalApp).settings.flow.collect { value ->
                settings = value
                if (value.floatingPtt && canDraw(this@ArchOverlayService)) ensureOverlay() else hideOverlay()
            }
        }
        scope.launch { PttLock.locked.collect { overlay?.setLocked(it) } }
        scope.launch { Transmitter.on.collect { overlay?.setActive(it) } }
        scope.launch { Ic705.state.collect { overlay?.setConnected(it.link == RigState.Link.CONNECTED) } }
    }

    private fun sync() {
        if (settings.floatingPtt && canDraw(this)) ensureOverlay() else hideOverlay()
    }

    private fun ensureOverlay() {
        val current = overlay
        if (current != null) {
            current.setLocked(PttLock.locked.value)
            current.setActive(Transmitter.on.value)
            current.setConnected(Ic705.state.value.link == RigState.Link.CONNECTED)
            return
        }
        if (!canDraw(this)) { stopSelf(); return }
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
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
        val added = runCatching { wm.addView(view, lp) }
        if (added.isFailure) {
            toast("悬浮 PTT 无法显示，请检查“显示在其他应用上层”权限")
            stopSelf()
            return
        }
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
        IcomNet.forceUnkey()
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
