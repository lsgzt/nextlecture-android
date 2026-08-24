package com.gndec.timetable.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Stores the user-provided Gemini API key with Android Keystore-backed encryption. */
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

    fun getGeminiKey(): String? = prefs.getString(KEY_GEMINI, null)?.takeIf { it.isNotBlank() }

    fun setGeminiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI, key.trim()).apply()
    }

    fun removeGeminiKey() {
        prefs.edit().remove(KEY_GEMINI).apply()
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
        private const val KEY_GEMINI = "gemini_api_key"
        private const val KEY_ATTENDANCE_INSTALLATION_ID = "attendance_installation_id"
        private const val KEY_ATTENDANCE_TOKEN = "attendance_access_token"
        private const val KEY_ATTENDANCE_PROFILE_FINGERPRINT = "attendance_profile_fingerprint"
    }
}
