// The one activity: draws edge to edge and shows the app - the startup screen fading to the mode menu.
package uk.hamdigital

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.hamdigital.audio.AudioIn
import uk.hamdigital.audio.AudioOut
import uk.hamdigital.core.TxControl
import uk.hamdigital.ui.AppScreens
import uk.hamdigital.ui.HamDigitalTheme

/** The app's version, for the startup screen and Settings (kept here so it is not tied to the generated BuildConfig). */
object BuildConfigInfo { const val VERSION = BuildConfig.VERSION_NAME } // from versionName in app/build.gradle.kts (one place to change)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()     // state (survives rotation)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)            // Android's part
        enableEdgeToEdge()                            // draw under the system bars (the screens pad)
        setContent { HamDigitalTheme {
            val screen by vm.screen.collectAsState()
            val settings by vm.settings.collectAsStateWithLifecycle()
            val settingsReady by vm.settingsReady.collectAsStateWithLifecycle()
            var notificationAsked by rememberSaveable { mutableStateOf(false) }
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(settingsReady, settings.backgroundKeepAlive, settings.floatingPtt) {
                if (settingsReady) syncBackgroundServices(settings)
            }
            LaunchedEffect(settingsReady, settings.backgroundKeepAlive, settings.floatingPtt) {
                if (settingsReady && Build.VERSION.SDK_INT >= 33 && !notificationAsked &&
                    (settings.backgroundKeepAlive || settings.floatingPtt) &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationAsked = true
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            BackHandler { if (screen == Screen.Connect) finish() else if (!vm.back()) showExitConfirmation() }
            AppScreens(vm)
        } }
        devCommand(intent)                            // (debug builds) a development check asked for by adb
    }

    private fun devCommand(i: android.content.Intent?) {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && i?.getBooleanExtra("dev_settings", false) == true) vm.show(Screen.SettingsPage)
        if (debuggable && i?.getBooleanExtra("dev_control_screen", false) == true) vm.show(Screen.Control)
        if (debuggable && i?.getBooleanExtra("dev_floating", false) == true) {
            vm.updateSettings { it.copy(floatingPtt = true, backgroundKeepAlive = true) }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (ArchOverlayService.canDraw(this)) ArchOverlayService.start(this)
            }, 500)
        }
        if (debuggable && i?.getBooleanExtra("dev_connect", false) == true) {
            val host = i.getStringExtra("dev_host") ?: ""
            val user = i.getStringExtra("dev_user") ?: ""
            val password = i.getStringExtra("dev_password") ?: ""
            val control = i.getIntExtra("dev_control", 50001)
            val serial = i.getIntExtra("dev_serial", 50002)
            val audio = i.getIntExtra("dev_audio", 50003)
            if (host.isNotBlank()) uk.hamdigital.rig.IcomNet.connect(this, host, control, user, password, serial, audio)
        }
        if (debuggable && i?.getBooleanExtra("dev_levels", false) == true) {
            uk.hamdigital.rig.Ic705.setRfGain(i.getIntExtra("dev_rf", 128))
            uk.hamdigital.rig.Ic705.setSquelch(i.getIntExtra("dev_sql", 0))
            uk.hamdigital.rig.Ic705.setPower(i.getIntExtra("dev_power", 128))
            uk.hamdigital.rig.Ic705.setFilterIndex(i.getIntExtra("dev_filter", 10))
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            val app = applicationContext
            ArchFloatingPttService.disableFromExit(this)
            uk.hamdigital.core.PttLock.lock()
            uk.hamdigital.audio.VoiceTx.stop()
            uk.hamdigital.core.TxControl.stopAll()
            uk.hamdigital.rig.IcomNet.disconnect { RadioService.stop(app) }
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()                              // Android's part
        AudioOut.resume()
        AudioIn.resumeAfterBackground()
        ArchFloatingPttService.resumeFromForeground(this)
        if (vm.settingsReady.value) syncBackgroundServices(vm.settings.value)
        val updater = uk.hamdigital.Updater(this)
        if (!updater.resumePendingInstall()) updater.check(true, null)
        uk.hamdigital.rig.Ic705.connect(this)
        uk.hamdigital.rig.IcomNet.enterForeground()   // keep the existing session; only recover it if the network actually failed
    }

    private fun syncBackgroundServices(settings: uk.hamdigital.core.Settings) {
        if (settings.backgroundKeepAlive) RadioService.start(this) else RadioService.stop(this)
        syncFloatingPtt(settings.floatingPtt)
    }

    private fun syncFloatingPtt(enabled: Boolean) {
        if (!enabled) {
            ArchOverlayService.stop(this)
        } else if (ArchFloatingPttService.isEnabled(this)) {
            ArchOverlayService.stop(this)
        } else if (ArchOverlayService.canDraw(this)) {
            ArchOverlayService.start(this)
        }
    }

    private fun stopTransmitForBackground() {
        uk.hamdigital.audio.VoiceTx.stop()
        TxControl.stopAll()
        uk.hamdigital.rig.IcomNet.forceUnkey()
        uk.hamdigital.rig.Ic705.ptt(false)
        if (vm.settings.value.backgroundKeepAlive && vm.settings.value.backgroundAudio) {
            AudioIn.resumeAfterBackground()
            AudioOut.resume()
        } else {
            AudioIn.pauseForBackground()
            AudioOut.pause()
        }
    }

    override fun onPause() {
        uk.hamdigital.rig.IcomNet.enterBackground()
        stopTransmitForBackground()
        super.onPause()
    }

    override fun onStop() {
        stopTransmitForBackground()
        super.onStop()
    }

    override fun onUserLeaveHint() {
        stopTransmitForBackground()
        super.onUserLeaveHint()
    }

    private fun showExitConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("退出 ARCH-705")
            .setMessage("退出后会停止后台助手并通知 IC-705 结束远程会话，但不会清除已保存的地址和密码。确定退出吗？")
            .setPositiveButton("退出") { _, _ -> finishRemoteApplication() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun finishRemoteApplication() {
        ArchFloatingPttService.disableFromExit(this)
        uk.hamdigital.core.PttLock.lock()
        uk.hamdigital.audio.VoiceTx.stop()
        TxControl.stopAll()
        uk.hamdigital.rig.IcomNet.forceUnkey()
        val app = applicationContext
        uk.hamdigital.rig.IcomNet.disconnect {
            RadioService.stop(app)
            runOnUiThread { finishAffinity() }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)                     // Android's part
        devCommand(intent)                            // (debug builds) adb commands while running
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) uk.hamdigital.rig.Ic705.connect(this) // plugged in while open
    }
}

