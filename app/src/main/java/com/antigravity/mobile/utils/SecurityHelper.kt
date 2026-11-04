package com.antigravity.mobile.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.antigravity.mobile.data.model.Account
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Secure credential storage backed by Android Keystore (AES-256 GCM)
 * and EncryptedSharedPreferences.
 * Supports Android 10+ (API 29+).
 */
class SecurityHelper(context: Context) {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        "antigravity_secure_prefs",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun saveAccounts(accounts: List<Account>) {
        val serialized = json.encodeToString(accounts)
        securePrefs.edit().putString(KEY_ACCOUNTS, serialized).apply()
    }

    fun loadAccounts(): List<Account> {
        val raw = securePrefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<Account>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getActiveAccount(): Account? {
        val accounts = loadAccounts()
        return accounts.firstOrNull { it.isActive } ?: accounts.firstOrNull()
    }

    fun setActiveAccount(accountId: String) {
        val updated = loadAccounts().map {
            it.copy(isActive = (it.id == accountId))
        }
        saveAccounts(updated)
    }

    companion object {
        private const val KEY_ACCOUNTS = "encrypted_accounts_list"
    }
}
