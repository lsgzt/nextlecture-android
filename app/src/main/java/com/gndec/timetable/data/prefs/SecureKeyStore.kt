package com.gndec.timetable.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gndec.timetable.net.AiProvider

/** Stores the user's AI provider API keys with Android Keystore-backed encryption. */
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

    fun getApiKey(provider: AiProvider): String? =
        prefs.getString(provider.keyName, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(provider: AiProvider, key: String) {
        prefs.edit().putString(provider.keyName, key.trim()).apply()
    }

    fun removeApiKey(provider: AiProvider) {
        prefs.edit().remove(provider.keyName).apply()
    }

    fun getAttendanceInstallationId(): String? = prefs.getString(KEY_ATTENDANCE_INSTALLATION_ID, null)?.takeIf { it.isNotBlank() }
    fun getAttendanceToken(): String? = prefs.getString(KEY_ATTENDANCE_TOKEN, null)?.takeIf { it.isNotBlank() }
    fun getAttendanceProfileFingerprint(): String? = prefs.getString(KEY_ATTENDANCE_PROFILE_FINGERPRINT, null)?.takeIf { it.isNotBlank() }

    fun setAttendanceSession(installationId: String, token: String, profileFingerprint: String) {
        prefs.edit()
            .putString(KEY_ATTENDANCE_INSTALLATION_ID, installationId)
            .putString(KEY_ATTENDANCE_TOKEN, token)
            .putString(KEY_ATTENDANCE_PROFILE_FINGERPRINT, profileFingerprint)
            .apply()
    }

    fun removeAttendanceSession() {
        prefs.edit()
            .remove(KEY_ATTENDANCE_INSTALLATION_ID)
            .remove(KEY_ATTENDANCE_TOKEN)
            .remove(KEY_ATTENDANCE_PROFILE_FINGERPRINT)
            .apply()
    }

    companion object {
        private const val KEY_ATTENDANCE_INSTALLATION_ID = "attendance_installation_id"
        private const val KEY_ATTENDANCE_TOKEN = "attendance_access_token"
        private const val KEY_ATTENDANCE_PROFILE_FINGERPRINT = "attendance_profile_fingerprint"
    }
}
