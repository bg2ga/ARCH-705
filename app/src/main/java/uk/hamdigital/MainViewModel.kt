// The app's state for the screens: settings, the startup screen, and which screen is showing (the menu, a mode's
// page, Settings, the Guide or the Logbook). Survives the phone being turned.
package uk.hamdigital

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.hamdigital.core.Mode
import uk.hamdigital.core.RadioProfile
import uk.hamdigital.core.RadioProfiles
import uk.hamdigital.core.Settings

/** What is on the screen under the startup screen. */
sealed interface Screen {
    data object Connect : Screen                      // direct IC-705 login
    data object Functions : Screen                    // function modes
    data object Control : Screen                      // direct radio control
    data class ModePage(val mode: Mode) : Screen      // one mode
    data class DigitalPage(val mode: String) : Screen // DMR/C4FM/P25 receiver page
    data object SettingsPage : Screen                 // Settings
    data object Guide : Screen                        // the User Guide
    data object Log : Screen                          // the Logbook
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as HamDigitalApp).settings // the settings file
    val settings: StateFlow<Settings> = store.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings()) // current settings

    val splash = MutableStateFlow(false)              // splash disabled for ARCH-705
    val screen = MutableStateFlow<Screen>(Screen.Connect) // direct login first

    init {
        viewModelScope.launch {
            store.legacySettingsWithPassword()?.let { old ->
                val profiles = RadioProfiles.load(app)
                val existing = profiles.firstOrNull { it.address == old.wifiIp && it.user == old.wifiUser }
                if (existing == null) {
                    RadioProfiles.save(app, RadioProfile(
                        name = old.wifiIp,
                        address = old.wifiIp,
                        user = old.wifiUser,
                        password = old.wifiPass,
                        controlPort = old.wifiPort,
                        serialPort = old.wifiCivPort,
                        audioPort = old.wifiAudioPort,
                        useDefaultPorts = old.useDefaultPorts,
                    ))
                } else if (existing.password.isBlank()) {
                    RadioProfiles.save(app, existing.copy(password = old.wifiPass))
                }
            }
            store.scrubLegacyPassword()
        }
        viewModelScope.launch { settings.collect {
            uk.hamdigital.rig.IcomNet.syncCivAddress(it.civAddr)
            uk.hamdigital.core.TxControl.licensed = it.txOk
            uk.hamdigital.core.TxControl.txLevel = it.txLevel / 100f
        } } // the radio's CI-V address, as set
        viewModelScope.launch { splash.value = false }
    }

    fun splashDone() { splash.value = false }         // tapped
    fun show(s: Screen) { screen.value = s }          // open a screen
    fun back(): Boolean {
        when (screen.value) {
            Screen.Connect, Screen.Control -> return false
            Screen.Functions -> screen.value = Screen.Control
            Screen.SettingsPage -> screen.value = if (uk.hamdigital.rig.Ic705.net) Screen.Control else Screen.Connect
            Screen.Guide, Screen.Log -> screen.value = Screen.SettingsPage
            else -> screen.value = Screen.Functions
        }
        return true
    }
    fun updateSettings(f: (Settings) -> Settings) { viewModelScope.launch { store.update(f) } } // change and store
}





