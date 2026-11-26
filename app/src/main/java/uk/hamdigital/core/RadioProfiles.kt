package uk.hamdigital.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A saved direct IC-705 endpoint. Passwords are encrypted before they reach disk. */
data class RadioProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val user: String,
    val password: String,
    val controlPort: Int = 50001,
    val serialPort: Int = 50002,
    val audioPort: Int = 50003,
    val useDefaultPorts: Boolean = true,
) {
    fun displayName(): String = name.ifBlank { address.ifBlank { "IC-705" } }
}

data class RadioProfilesSnapshot(
    val profiles: List<RadioProfile>,
    val unreadableIds: Set<String>,
    val loadFailed: Boolean = false,
)

object RadioProfiles {
    private const val PREFS = "arch705_radio_profiles"
    private const val KEY_LIST = "profiles"
    private const val KEY_LIST_V2 = "profiles_v2"
    private const val KEY_ACTIVE = "active_id"
    private const val KEY_MIGRATED = "passwords_encrypted_v1"

    fun loadSnapshot(ctx: Context): RadioProfilesSnapshot {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val vault = SecretStore(ctx)
        if (!prefs.getBoolean(KEY_MIGRATED, false) && !migrate(prefs, vault)) {
            Log.e("RadioProfiles", "profile migration failed")
            return RadioProfilesSnapshot(emptyList(), emptySet(), loadFailed = true)
        }
        val raw = prefs.getString(KEY_LIST_V2, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            val profiles = ArrayList<RadioProfile>()
            val unreadable = HashSet<String>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
                val secret = vault.read(id)
                if (secret.failed) unreadable += id
                profiles += RadioProfile(
                    id = id,
                    name = item.optString("name"),
                    address = item.optString("address"),
                    user = item.optString("user"),
                    password = secret.password,
                    controlPort = item.optInt("control", 50001),
                    serialPort = item.optInt("serial", 50002),
                    audioPort = item.optInt("audio", 50003),
                    useDefaultPorts = item.optBoolean("defaults", true),
                )
            }
            RadioProfilesSnapshot(profiles, unreadable)
        } catch (error: Throwable) {
            Log.e("RadioProfiles", "profile list is corrupt", error)
            RadioProfilesSnapshot(emptyList(), emptySet(), loadFailed = true)
        }
    }

    fun load(ctx: Context): List<RadioProfile> = loadSnapshot(ctx).profiles

    fun save(ctx: Context, profile: RadioProfile): Boolean {
        val snapshot = loadSnapshot(ctx)
        if (snapshot.loadFailed) return false
        if ((snapshot.unreadableIds - profile.id).isNotEmpty()) {
            Log.e("RadioProfiles", "refusing to rewrite profiles because another password cannot be decrypted")
            return false
        }
        val list = snapshot.profiles.toMutableList()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) list[index] = profile else list.add(0, profile)
        return write(ctx, list, profile.id)
    }

    fun delete(ctx: Context, id: String): Boolean {
        val snapshot = loadSnapshot(ctx)
        if (snapshot.loadFailed) return false
        if ((snapshot.unreadableIds - id).isNotEmpty()) {
            Log.e("RadioProfiles", "refusing to rewrite profiles because another password cannot be decrypted")
            return false
        }
        val list = snapshot.profiles.filterNot { it.id == id }
        val written = write(ctx, list, if (activeId(ctx) == id) "" else activeId(ctx))
        if (written) SecretStore(ctx).remove(id)
        return written
    }

    fun markActive(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun activeId(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE, "") ?: ""

    fun active(ctx: Context): RadioProfile? = load(ctx).firstOrNull { it.id == activeId(ctx) }

    /** Forget saved passwords and the active session without deleting the radio entries. */
    fun clearPasswords(ctx: Context): Boolean {
        val snapshot = loadSnapshot(ctx)
        if (snapshot.loadFailed) return false
        val list = snapshot.profiles.map { it.copy(password = "") }
        return write(ctx, list, "")
    }

    private fun write(ctx: Context, list: List<RadioProfile>, active: String): Boolean {
        val vault = SecretStore(ctx)
        val backup = vault.snapshot()
        val array = JSONArray()
        for (item in list) {
            if (!vault.put(item.id, item.password)) {
                Log.e("RadioProfiles", "could not encrypt password for ${item.id}")
                if (!vault.restore(backup)) Log.e("RadioProfiles", "password rollback failed")
                return false
            }
            array.put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("address", item.address)
                put("user", item.user)
                put("control", item.controlPort)
                put("serial", item.serialPort)
                put("audio", item.audioPort)
                put("defaults", item.useDefaultPorts)
            })
        }
        val edit = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LIST_V2, array.toString())
            .putString(KEY_LIST, array.toString())
            .putBoolean(KEY_MIGRATED, true)
        if (active.isBlank()) edit.remove(KEY_ACTIVE) else edit.putString(KEY_ACTIVE, active)
        if (edit.commit()) return true
        if (!vault.restore(backup)) Log.e("RadioProfiles", "profile commit failed and password rollback failed")
        return false
    }

    private fun migrate(prefs: android.content.SharedPreferences, vault: SecretStore): Boolean {
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        val old = try {
            JSONArray(raw)
        } catch (error: Throwable) {
            Log.e("RadioProfiles", "legacy profile list is corrupt", error)
            return false
        }
        val backup = vault.snapshot()
        val list = ArrayList<RadioProfile>()
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
            val password = item.optString("password")
            if (password.isNotEmpty() && !vault.put(id, password)) {
                if (!vault.restore(backup)) Log.e("RadioProfiles", "migration password rollback failed")
                return false
            }
            list += RadioProfile(
                id = id,
                name = item.optString("name"),
                address = item.optString("address"),
                user = item.optString("user"),
                password = "",
                controlPort = item.optInt("control", 50001),
                serialPort = item.optInt("serial", 50002),
                audioPort = item.optInt("audio", 50003),
                useDefaultPorts = item.optBoolean("defaults", true),
            )
        }
        val clean = JSONArray()
        list.forEach { item ->
            clean.put(JSONObject().apply {
                put("id", item.id); put("name", item.name); put("address", item.address); put("user", item.user)
                put("control", item.controlPort); put("serial", item.serialPort); put("audio", item.audioPort)
                put("defaults", item.useDefaultPorts)
            })
        }
        val written = prefs.edit().putString(KEY_LIST, clean.toString()).putString(KEY_LIST_V2, clean.toString())
            .putBoolean(KEY_MIGRATED, true).commit()
        if (!written && !vault.restore(backup)) Log.e("RadioProfiles", "migration commit failed and rollback failed")
        return written
    }
}

fun RadioProfile.applyTo(s: Settings): Settings = s.copy(
    wifiIp = address, wifiUser = user, wifiPass = "",
    wifiPort = controlPort, wifiCivPort = serialPort, wifiAudioPort = audioPort,
    useDefaultPorts = useDefaultPorts, audio = AudioChoice.WIFI,
)
