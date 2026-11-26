package uk.hamdigital

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.hamdigital.core.RadioProfile
import uk.hamdigital.core.RadioProfiles
import uk.hamdigital.core.Settings

sealed interface Screen {
    data object Connect : Screen
    data object Control : Screen
    data object SettingsPage : Screen
    data object Guide : Screen
    data object Log : Screen
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as HamDigitalApp).settings
    val settings: StateFlow<Settings> = store.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val settingsReady = MutableStateFlow(false)
    val screen = MutableStateFlow<Screen>(Screen.Connect)

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
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
                }
            } finally {
                runCatching { store.scrubLegacyPassword() }
                settingsReady.value = true
            }
        }
        viewModelScope.launch { settings.collect {
            uk.hamdigital.rig.IcomNet.syncCivAddress(it.civAddr)
            uk.hamdigital.core.TxControl.licensed = it.txOk
            uk.hamdigital.core.TxControl.txLevel = it.txLevel / 100f
        } }
    }

    fun show(s: Screen) { screen.value = s }
    fun back(): Boolean {
        when (screen.value) {
            Screen.Connect, Screen.Control -> return false
            Screen.SettingsPage -> screen.value = if (uk.hamdigital.rig.Ic705.net) Screen.Control else Screen.Connect
            Screen.Guide, Screen.Log -> screen.value = Screen.SettingsPage
        }
        return true
    }
    fun updateSettings(f: (Settings) -> Settings) { viewModelScope.launch { store.update(f) } }
}
