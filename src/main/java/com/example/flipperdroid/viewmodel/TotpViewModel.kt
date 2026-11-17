package com.example.flipperdroid.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import com.example.flipperdroid.security.TotpGenerator
import com.example.flipperdroid.util.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A 2FA account (label + Base32 secret, TOTP parameters). */
data class TotpAccount(
    val label: String,
    val secret: String,
    val digits: Int = 6,
    val period: Int = 30,
    val algorithm: String = "SHA1"
)

/**
 * 2FA/TOTP vault: accounts are stored encrypted (AES-GCM via the Android Keystore,
 * see [SecureStore]) and codes are generated on the fly. Secrets never leave
 * the device.
 */
class TotpViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext: Context = app

    private companion object {
        const val TAG = "TotpViewModel"
        /** Key of the encrypted vault in SecureStore. */
        const val STORE_KEY = "totp_accounts"
        /** Former plaintext storage (SharedPreferences "totp_vault", key "accounts"), migrated then deleted. */
        const val LEGACY_PREFS = "totp_vault"
        const val LEGACY_KEY = "accounts"
    }

    private val legacyPrefs = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    private val _accounts = MutableStateFlow<List<TotpAccount>>(emptyList())
    val accounts: StateFlow<List<TotpAccount>> = _accounts

    /** Last error message to display (null = none). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init { load() }

    fun dismissError() {
        _error.value = null
    }

    private fun load() {
        // Current encrypted data
        val hasEncrypted = appContext
            .getSharedPreferences(SecureStore.PREFS, Context.MODE_PRIVATE)
            .contains(STORE_KEY)
        val decrypted = try {
            SecureStore.getString(appContext, STORE_KEY)
        } catch (e: Exception) {
            Log.e(TAG, "Vault read failed", e)
            null
        }

        var list: List<TotpAccount> = when {
            decrypted != null -> parseAccounts(decrypted) ?: run {
                Log.e(TAG, "Corrupted vault: starting with an empty vault")
                _error.value = "Corrupted vault: data ignored"
                emptyList()
            }
            hasEncrypted -> {
                Log.e(TAG, "Unreadable vault (Keystore key lost or data altered)")
                _error.value = "Unreadable vault (Keystore key lost or data altered)"
                emptyList()
            }
            else -> emptyList()
        }

        // Migration of the former plaintext storage
        val legacyJson = try {
            legacyPrefs.getString(LEGACY_KEY, null)
        } catch (e: Exception) {
            Log.e(TAG, "Reading the old vault failed", e)
            null
        }
        if (legacyJson != null) {
            val legacy = parseAccounts(legacyJson)
            if (legacy == null) {
                // Unreadable data: we leave it in place, without migrating it
                Log.e(TAG, "Old vault corrupted, migration skipped")
            } else {
                val merged = list + legacy.filter { l ->
                    list.none { it.label == l.label && it.secret == l.secret }
                }
                // Only erase the old storage if the new write is verified
                if (persistList(merged)) {
                    try {
                        legacyPrefs.edit { remove(LEGACY_KEY) }
                        Log.i(TAG, "TOTP vault migration complete (${legacy.size} accounts)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Deleting the old vault failed", e)
                    }
                }
                list = merged
            }
        }

        _accounts.value = list
    }

    /**
     * Parses the vault JSON. Invalid accounts are skipped.
     * @return null if the JSON itself is corrupted.
     */
    private fun parseAccounts(json: String): List<TotpAccount>? = try {
        val arr = JSONArray(json)
        val out = mutableListOf<TotpAccount>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) {
                Log.w(TAG, "Entry skipped at index $i (unexpected format)")
                continue
            }
            val account = TotpAccount(
                label = o.optString("label", "Account").ifBlank { "Account" },
                secret = o.optString("secret", ""),
                digits = o.optInt("digits", 6),
                period = o.optInt("period", 30),
                algorithm = o.optString("algorithm", "SHA1")
            )
            val problem = validationError(account)
            if (problem == null) out.add(account) else Log.w(TAG, "Account skipped at index $i: $problem")
        }
        out
    } catch (e: Exception) {
        Log.e(TAG, "Invalid vault JSON", e)
        null
    }

    /**
     * Saves the list into the encrypted vault and verifies the read-back.
     * @return true if the write succeeded.
     */
    private fun persistList(list: List<TotpAccount>): Boolean = try {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("label", it.label)
                put("secret", it.secret)
                put("digits", it.digits)
                put("period", it.period)
                put("algorithm", it.algorithm)
            })
        }
        val json = arr.toString()
        SecureStore.putString(appContext, STORE_KEY, json)
        if (SecureStore.getString(appContext, STORE_KEY) != json) {
            throw IllegalStateException("Vault read-back differs from the write")
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Saving the vault failed", e)
        _error.value = "Unable to save the encrypted vault"
        false
    }

    /** Validation error message for an account, or null if it is valid. */
    private fun validationError(a: TotpAccount): String? = when {
        !TotpGenerator.isValidBase32(a.secret) -> "Invalid Base32 secret"
        a.digits !in 6..8 -> "Unsupported digit count: ${a.digits} (6 to 8)"
        a.period <= 0 -> "Invalid period: ${a.period} (must be > 0)"
        !TotpGenerator.isSupportedAlgorithm(a.algorithm) ->
            "Unsupported algorithm: ${a.algorithm} (SHA1, SHA256, SHA512)"
        else -> null
    }

    private fun fail(message: String): Boolean {
        _error.value = message
        return false
    }

    /**
     * Adds an account from a label + secret, or from an otpauth:// URI.
     * @return true if the account was added; otherwise [error] holds the reason.
     */
    fun addAccount(labelOrUri: String, secret: String): Boolean {
        val input = labelOrUri.trim()
        val account: TotpAccount
        if (input.startsWith("otpauth://", ignoreCase = true)) {
            val data = TotpGenerator.parseOtpauthUri(input)
                ?: return fail("Invalid otpauth:// URI (secret required, numeric digits/period)")
            if (data.type != "totp") {
                return fail("Type '${data.type}' not supported (TOTP only)")
            }
            account = TotpAccount(
                label = data.label,
                secret = data.secret,
                digits = data.digits,
                period = data.period,
                algorithm = data.algorithm
            )
        } else {
            if (secret.isBlank()) return fail("Base32 secret required")
            account = TotpAccount(label = input.ifBlank { "Account" }, secret = secret)
        }

        val normalized = account.copy(
            secret = account.secret.filterNot { it.isWhitespace() }.uppercase(),
            algorithm = TotpGenerator.normalizeAlgorithm(account.algorithm)
        )
        validationError(normalized)?.let { return fail(it) }

        val updated = _accounts.value + normalized
        if (!persistList(updated)) return false
        _accounts.value = updated
        _error.value = null
        return true
    }

    fun removeAccount(account: TotpAccount) {
        val updated = _accounts.value.filterNot { it.label == account.label && it.secret == account.secret }
        if (persistList(updated)) {
            _accounts.value = updated
            _error.value = null
        }
    }

    fun codeFor(account: TotpAccount, nowMillis: Long): String = try {
        TotpGenerator.generate(account.secret, nowMillis / 1000, account.digits, account.period, account.algorithm)
    } catch (e: IllegalArgumentException) {
        "-".repeat(account.digits.coerceIn(6, 8))
    }

    fun secondsRemaining(account: TotpAccount, nowMillis: Long): Int =
        TotpGenerator.secondsRemaining(nowMillis / 1000, account.period)
}
