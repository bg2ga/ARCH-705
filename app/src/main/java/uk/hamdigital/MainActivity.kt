// The one activity: draws edge to edge and shows the app - the startup screen fading to the mode menu.
package uk.hamdigital

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import uk.hamdigital.audio.AudioIn
import uk.hamdigital.audio.AudioOut
import uk.hamdigital.core.PttLock
import uk.hamdigital.core.TxControl
import uk.hamdigital.ui.AppScreens
import uk.hamdigital.ui.HamDigitalTheme
import uk.hamdigital.ui.SplashScreen

/** The app's version, for the startup screen and Settings (kept here so it is not tied to the generated BuildConfig). */
object BuildConfigInfo { const val VERSION = BuildConfig.VERSION_NAME } // from versionName in app/build.gradle.kts (one place to change)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()     // state (survives rotation)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)            // Android's part
        enableEdgeToEdge()                            // draw under the system bars (the screens pad)
        setContent { HamDigitalTheme {                // the app, with the startup screen over it while it starts
            val screen by vm.screen.collectAsState()  // the screen under it
            BackHandler { if (screen == Screen.Connect) finish() else if (!vm.back()) finish() } // Back on a page: to the menu; on the menu: close the app - finished, so onDestroy logs out of the radio (Android 12+ only sends the app to the background on Back, leaving the link up)
            Box {
                AppScreens(vm)                        // menu / mode page / Settings / Guide
                val splash by vm.splash.collectAsState() // startup screen shown?
                AnimatedVisibility(splash, enter = EnterTransition.None, exit = fadeOut(tween(600))) { SplashScreen(vm) } // fades away
            }
        } }
        devCommand(intent)                            // (debug builds) a development check asked for by adb
    }

    /** Debug builds only: "--ez dev_test true" runs the decoders on test recordings (DevTest, docs/DEV_COMMANDS.md). */
    private fun devCommand(i: android.content.Intent?) {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && i?.getBooleanExtra("dev_test", false) == true) DevTest.run(this)
        if (debuggable && i?.getBooleanExtra("dev_menu", false) == true) vm.show(Screen.Functions)
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
        if (debuggable) i?.getStringExtra("dev_mode")?.let { name ->
            uk.hamdigital.core.Mode.entries.firstOrNull { it.name.equals(name, true) }?.let { vm.show(Screen.ModePage(it)) }
        }
        if (debuggable && i?.getBooleanExtra("sstv_audio", false) == true) // "--ez sstv_audio true": the SSTV page's last 3 minutes as a WAV
            android.util.Log.i("HDTEST", "sstv audio saved: ${uk.hamdigital.core.SstvRx.saveAudio(this).name}")
    }

    override fun onDestroy() {
        if (isFinishing) {
            val app = applicationContext
            ArchFloatingPttService.disableFromExit(this)
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
        if (vm.settings.value.backgroundKeepAlive) RadioService.start(this) else RadioService.stop(this)
        ArchFloatingPttService.resumeFromForeground(this)
        if (vm.settings.value.floatingPtt) {
            if (ArchFloatingPttService.isEnabled(this)) ArchOverlayService.stop(this)
            else if (ArchOverlayService.canDraw(this)) ArchOverlayService.start(this)
        } else {
            ArchOverlayService.stop(this)
        }
        uk.hamdigital.Updater(this).resumePendingInstall() // continue an OTA install after the user grants unknown-source permission
        uk.hamdigital.rig.Ic705.connect(this)         // the radio's CI-V, if plugged in (asks for USB access the first time)
        uk.hamdigital.rig.IcomNet.enterForeground()   // keep the existing session; only recover it if the network actually failed
    }

    private fun stopTransmitForBackground() {
        uk.hamdigital.audio.VoiceTx.stop()
        PttLock.lock()
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)                     // Android's part
        devCommand(intent)                            // (debug builds) adb commands while running
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) uk.hamdigital.rig.Ic705.connect(this) // plugged in while open
    }
}

