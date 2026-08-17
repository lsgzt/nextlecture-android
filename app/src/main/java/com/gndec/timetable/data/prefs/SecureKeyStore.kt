package com.gndec.timetable.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the user-provided Groq API key with Android Keystore-backed encryption.
 * The key is NEVER logged, never sent to our backend, never written to analytics.
 */
class SecureKeyStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getGroqKey(): String? =
        prefs.getString(KEY_GROQ, null)?.takeIf { it.isNotBlank() }

    fun setGroqKey(key: String) {
        prefs.edit().putString(KEY_GROQ, key.trim()).apply()
    }

    fun removeGroqKey() {
        prefs.edit().remove(KEY_GROQ).apply()
    }

    companion object { private const val KEY_GROQ = "groq_api_key" }
}
