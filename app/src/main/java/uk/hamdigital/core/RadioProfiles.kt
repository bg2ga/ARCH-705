package uk.hamdigital.core

import android.content.Context
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

object RadioProfiles {
    private const val PREFS = "arch705_radio_profiles"
    private const val KEY_LIST = "profiles"
    private const val KEY_LIST_V2 = "profiles_v2"
    private const val KEY_ACTIVE = "active_id"
    private const val KEY_MIGRATED = "passwords_encrypted_v1"

    fun load(ctx: Context): List<RadioProfile> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val vault = SecretStore(ctx)
        if (!prefs.getBoolean(KEY_MIGRATED, false)) migrate(prefs, vault)
        val raw = prefs.getString(KEY_LIST_V2, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
                    add(RadioProfile(
                        id = id,
                        name = item.optString("name"),
                        address = item.optString("address"),
                        user = item.optString("user"),
                        password = vault.get(id),
                        controlPort = item.optInt("control", 50001),
                        serialPort = item.optInt("serial", 50002),
                        audioPort = item.optInt("audio", 50003),
                        useDefaultPorts = item.optBoolean("defaults", true),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, profile: RadioProfile) {
        val list = load(ctx).toMutableList()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) list[index] = profile else list.add(0, profile)
        write(ctx, list, profile.id)
    }

    fun delete(ctx: Context, id: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        SecretStore(ctx).remove(id)
        val list = load(ctx).filterNot { it.id == id }
        write(ctx, list, if (activeId(ctx) == id) "" else activeId(ctx))
    }

    fun markActive(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun activeId(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE, "") ?: ""

    fun active(ctx: Context): RadioProfile? = load(ctx).firstOrNull { it.id == activeId(ctx) }

    /** Forget saved passwords and the active session without deleting the radio entries. */
    fun clearPasswords(ctx: Context) {
        SecretStore(ctx).clear()
        val list = load(ctx).map { it.copy(password = "") }
        write(ctx, list, "")
    }

    private fun write(ctx: Context, list: List<RadioProfile>, active: String) {
        val vault = SecretStore(ctx)
        val array = JSONArray()
        list.forEach { item ->
            vault.put(item.id, item.password)
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
        edit.commit()
    }

    private fun migrate(prefs: android.content.SharedPreferences, vault: SecretStore) {
        val old = runCatching { JSONArray(prefs.getString(KEY_LIST, "[]")) }.getOrDefault(JSONArray())
        val list = ArrayList<RadioProfile>()
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
            val password = item.optString("password")
            if (password.isNotEmpty()) vault.put(id, password)
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
        prefs.edit().putString(KEY_LIST, clean.toString()).putString(KEY_LIST_V2, clean.toString())
            .putBoolean(KEY_MIGRATED, true).commit()
    }
}

fun RadioProfile.applyTo(s: Settings): Settings = s.copy(
    wifiIp = address, wifiUser = user, wifiPass = "",
    wifiPort = controlPort, wifiCivPort = serialPort, wifiAudioPort = audioPort,
    useDefaultPorts = useDefaultPorts, audio = AudioChoice.WIFI,
)
