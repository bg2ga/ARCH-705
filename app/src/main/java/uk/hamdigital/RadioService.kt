// Keeps the radio link and the receive audio running while the app is in the background (another app on screen, or the
// screen off): a foreground service with an ongoing "connected to the IC-705" notification. Without it Android - and
// Samsung's background-app control in particular - cuts a background app off the network ("sendto failed: EPERM",
// found on the air 0.8.2) and stops its microphone / USB audio, so the WiFi link dropped as soon as another app was
// opened. Started with the app's screen; stopped when the app is closed.
package uk.hamdigital

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class RadioService : Service() {
    companion object {
        private const val CHANNEL = "radio"           // notification channel
        private const val ID = 1                      // notification id

        /** Start (or keep) the service - call while the app is on screen (Android allows starting it only then). */
        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, RadioService::class.java)) }
            catch (e: Exception) { android.util.Log.w("ARCH705", "RadioService start failed", e) }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, RadioService::class.java)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null // (started, not bound)

    /** The app swiped away from the recent apps: log out of the radio (while this service still keeps the network), then stop. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        ArchFloatingPttService.disableFromExit(this)
        uk.hamdigital.core.PttLock.lock()
        uk.hamdigital.audio.VoiceTx.stop()
        uk.hamdigital.core.TxControl.stopAll()           // nothing goes on sending (0.10.4)
        uk.hamdigital.rig.IcomNet.disconnect { stopSelf() }
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "电台连接", NotificationManager.IMPORTANCE_LOW).apply {
            description = "ARCH-705 正在保持电台连接和接收音频" }) // (quiet: no sound)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE) // tap: back to the app
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg).setContentTitle("ARCH-705")
            .setContentText("正在保持 IC-705 连接和接收音频").setContentIntent(open).setOngoing(true).build()
        var type = 0                                  // what the service does in the background (Android 14 asks)
        if (Build.VERSION.SDK_INT >= 29) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try { if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, type) else startForeground(ID, n) }
        catch (e: Exception) { android.util.Log.w("ARCH705", "RadioService foreground start failed", e); stopSelf() }
        if (intent == null && !uk.hamdigital.rig.IcomNet.hasActiveTarget()) {
            Thread({
                val profile = uk.hamdigital.core.RadioProfiles.active(this)
                if (profile == null || profile.address.isBlank() || profile.user.isBlank() || profile.password.isBlank()) stopSelf()
                else uk.hamdigital.rig.IcomNet.connect(
                    this, profile.address, profile.controlPort, profile.user, profile.password,
                    if (profile.useDefaultPorts) -1 else profile.serialPort,
                    if (profile.useDefaultPorts) -1 else profile.audioPort,
                    powerOn = true,
                )
            }, "radio-profile-resume").start()
        }
        return START_STICKY
    }
}
