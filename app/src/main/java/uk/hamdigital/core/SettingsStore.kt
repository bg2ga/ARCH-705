package uk.hamdigital.core

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

enum class AudioChoice(val label: String) {
    AUTO("自动：IC-705 USB 优先"),
    USB("仅 IC-705 USB"),
    MIC("手机麦克风"),
    WIFI("IC-705 WiFi 直连"),
}

data class Settings(
    val callsign: String = "",
    val locator: String = "",
    val audio: AudioChoice = AudioChoice.WIFI,
    val civAddr: Int = 0xA4,
    val txLevel: Int = 30,
    val txOk: Boolean = false,
    val wifiIp: String = "",
    val wifiPort: Int = 50001,
    val wifiCivPort: Int = 50002,
    val wifiAudioPort: Int = 50003,
    val useDefaultPorts: Boolean = true,
    val wifiUser: String = "",
    val wifiPass: String = "",
    val wsprUpload: Boolean = false,
    val wsprDbm: Int = 37,
    val scopeRefreshFps: Int = 30,
    val savedChannels: String = "",
    val floatingPtt: Boolean = false,
    val bandMemory: String = "",
    val frequencyStepHz: Int = 1000,
    val backgroundKeepAlive: Boolean = true,
    val backgroundAudio: Boolean = false,
)

class SettingsStore(private val ctx: Context) {
    private val kCall = stringPreferencesKey("callsign")
    private val kLoc = stringPreferencesKey("locator")
    private val kAudio = stringPreferencesKey("audio")
        private val kCiv = intPreferencesKey("civ_address")
    private val kTxLevel = intPreferencesKey("tx_level")
    private val kTxOk = booleanPreferencesKey("tx_ok")
    private val kWIp = stringPreferencesKey("wifi_ip")
    private val kWPort = intPreferencesKey("wifi_port")
    private val kWCiv = intPreferencesKey("wifi_civ_port")
    private val kWAud = intPreferencesKey("wifi_audio_port")
    private val kWDefault = booleanPreferencesKey("wifi_default_ports")
    private val kWUser = stringPreferencesKey("wifi_user")
    private val kWPass = stringPreferencesKey("wifi_pass")
    private val kWsprUp = booleanPreferencesKey("wspr_upload")
    private val kWsprDbm = intPreferencesKey("wspr_dbm")
    private val kScopeFps = intPreferencesKey("scope_refresh_fps")
    private val kChannels = stringPreferencesKey("saved_channels")
    private val kFloatingPtt = booleanPreferencesKey("floating_ptt")
    private val kBandMemory = stringPreferencesKey("band_memory")
    private val kFrequencyStep = intPreferencesKey("frequency_step_hz")
    private val kBackgroundKeepAlive = booleanPreferencesKey("background_keep_alive")
    private val kBackgroundAudio = booleanPreferencesKey("background_audio")

    val flow: Flow<Settings> = ctx.store.data.map { it.toSettings() }

    private fun Preferences.toSettings(): Settings = Settings(
        callsign = this[kCall] ?: "",
        locator = this[kLoc] ?: "",
        audio = AudioChoice.entries.firstOrNull { it.name == this[kAudio] } ?: AudioChoice.WIFI,
        civAddr = this[kCiv] ?: 0xA4,
        txLevel = this[kTxLevel] ?: 30,
        txOk = this[kTxOk] ?: false,
        wifiIp = this[kWIp] ?: "",
        wifiPort = this[kWPort] ?: 50001,
        wifiCivPort = this[kWCiv] ?: 50002,
        wifiAudioPort = this[kWAud] ?: 50003,
        useDefaultPorts = this[kWDefault] ?: true,
        wifiUser = this[kWUser] ?: "",
        wifiPass = "",
        wsprUpload = this[kWsprUp] ?: false,
        wsprDbm = this[kWsprDbm] ?: 37,
        scopeRefreshFps = (this[kScopeFps] ?: 30).coerceIn(5, 30),
        savedChannels = this[kChannels] ?: "",
        floatingPtt = this[kFloatingPtt] ?: false,
        bandMemory = this[kBandMemory] ?: "",
        frequencyStepHz = (this[kFrequencyStep] ?: 1000).coerceIn(1, 25_000),
        backgroundKeepAlive = this[kBackgroundKeepAlive] ?: true,
        backgroundAudio = this[kBackgroundAudio] ?: false,
    )

    private fun MutablePreferences.write(s: Settings) {
        this[kCall] = s.callsign.trim().uppercase()
        this[kLoc] = s.locator.trim().let {
            if (it.length >= 4) it.take(2).uppercase() + it.drop(2).take(2) + it.drop(4).lowercase() else it.uppercase()
        }
        this[kAudio] = s.audio.name
        this[kCiv] = s.civAddr
        this[kTxLevel] = s.txLevel
        this[kTxOk] = s.txOk
        this[kWIp] = s.wifiIp.trim()
        this[kWPort] = s.wifiPort
        this[kWCiv] = s.wifiCivPort
        this[kWAud] = s.wifiAudioPort
        this[kWDefault] = s.useDefaultPorts
        this[kWUser] = s.wifiUser
        this.remove(kWPass)
        this[kWsprUp] = s.wsprUpload
        this[kWsprDbm] = s.wsprDbm
        this[kScopeFps] = s.scopeRefreshFps.coerceIn(5, 30)
        this[kChannels] = s.savedChannels
        this[kFloatingPtt] = s.floatingPtt
        this[kBandMemory] = s.bandMemory
        this[kFrequencyStep] = s.frequencyStepHz.coerceIn(1, 25_000)
        this[kBackgroundKeepAlive] = s.backgroundKeepAlive
        this[kBackgroundAudio] = s.backgroundAudio
    }

    /** One-time bridge for builds that stored the WiFi password in DataStore before RadioProfiles existed. */
    suspend fun legacySettingsWithPassword(): Settings? {
        val prefs = ctx.store.data.first()
        val password = prefs[kWPass] ?: return null
        if (password.isBlank()) return null
        return prefs.toSettings().takeIf { it.wifiIp.isNotBlank() && it.wifiUser.isNotBlank() }
            ?.copy(wifiPass = password)
    }

    suspend fun scrubLegacyPassword() { ctx.store.edit { it.remove(kWPass) } }

    suspend fun save(s: Settings) {
        ctx.store.edit { it.write(s) }
    }

    suspend fun update(transform: (Settings) -> Settings) {
        ctx.store.edit { it.write(transform(it.toSettings())) }
    }
}
