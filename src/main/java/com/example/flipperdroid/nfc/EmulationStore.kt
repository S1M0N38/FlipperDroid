package com.example.flipperdroid.nfc

import android.content.Context
import com.example.flipperdroid.util.SecureStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence of a captured ISO-DEP card for HCE emulation (APDU replay).
 * Stores the command->response table (hex) and the AIDs the card answered to.
 *
 * APDU responses can contain cardholder data (PAN, Track2, name): they are
 * therefore encrypted through [SecureStore] (Android Keystore), and no longer in clear text in
 * SharedPreferences. The old cleartext storage ("hce_emulation") is migrated then erased.
 */
object EmulationStore {
    /** Old cleartext preferences file: migrated then deleted. */
    private const val LEGACY_PREFS = "hce_emulation"
    /** (Encrypted) key holding the complete JSON of the capture. */
    private const val KEY_DATA = "hce_emulation_v2"
    // Field names: identical to the old storage, for the migration.
    private const val F_LABEL = "label"
    private const val F_AIDS = "aids"
    private const val F_MAP = "apdu_map"

    fun save(context: Context, label: String, aids: List<String>, map: Map<String, String>) {
        val mapObj = JSONObject()
        map.forEach { (k, v) -> mapObj.put(k, v) }
        val obj = JSONObject()
            .put(F_LABEL, label)
            .put(F_AIDS, JSONArray(aids))
            .put(F_MAP, mapObj)
        SecureStore.putString(context, KEY_DATA, obj.toString())
        legacyPrefs(context).edit().clear().apply()
    }

    fun label(context: Context): String? =
        load(context)?.optString(F_LABEL, "")?.takeIf { it.isNotEmpty() }

    fun aids(context: Context): List<String> {
        val arr = load(context)?.optJSONArray(F_AIDS) ?: return emptyList()
        return try {
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    fun map(context: Context): Map<String, String> {
        val obj = load(context)?.optJSONObject(F_MAP) ?: return emptyMap()
        val out = HashMap<String, String>()
        try {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optString(k, "")
            }
        } catch (e: Exception) { /* corrupt entry: keep whatever was read */ }
        return out
    }

    fun isConfigured(context: Context): Boolean = aids(context).isNotEmpty()

    fun clear(context: Context) {
        SecureStore.putString(context, KEY_DATA, null)
        legacyPrefs(context).edit().clear().apply()
    }

    /** Decrypts and parses the capture; null if absent or unreadable. */
    private fun load(context: Context): JSONObject? {
        migrateLegacy(context)
        val json = SecureStore.getString(context, KEY_DATA) ?: return null
        return try { JSONObject(json) } catch (e: Exception) { null }
    }

    /** Migrates the old cleartext storage to [SecureStore], then deletes the old preferences. */
    private fun migrateLegacy(context: Context) {
        val legacy = legacyPrefs(context)
        if (legacy.all.isEmpty()) return
        try {
            if (SecureStore.getString(context, KEY_DATA) == null) {
                val obj = JSONObject()
                    .put(F_LABEL, legacy.getString(F_LABEL, "") ?: "")
                    .put(F_AIDS, JSONArray(legacy.getString(F_AIDS, "[]") ?: "[]"))
                    .put(F_MAP, JSONObject(legacy.getString(F_MAP, "{}") ?: "{}"))
                SecureStore.putString(context, KEY_DATA, obj.toString())
            }
        } catch (e: Exception) {
            // Old unreadable content: delete it without migrating.
        }
        legacy.edit().clear().apply()
    }

    private fun legacyPrefs(context: Context) =
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
}
