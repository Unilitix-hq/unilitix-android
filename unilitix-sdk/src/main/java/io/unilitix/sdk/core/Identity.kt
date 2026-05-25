package io.unilitix.sdk.core

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.unilitix.sdk.util.Logger
import java.security.MessageDigest

internal class Identity(private val context: Context) {

    // Anonymous ID is a salted hash of ANDROID_ID — not PII, plain prefs fine.
    private val devicePrefs = context.getSharedPreferences("unilitix_device", Context.MODE_PRIVATE)

    // user_id and traits are PII — encrypted at rest.
    private val identityPrefs: SharedPreferences by lazy { buildEncryptedPrefs() }

    var anonymousId: String
        private set

    var userId: String? = null
        private set

    var userTraits: Map<String, Any>? = null
        private set

    init {
        anonymousId = devicePrefs.getString(KEY_ANONYMOUS_ID, null) ?: generateAndSaveAnonymousId()
        userId = try { identityPrefs.getString(KEY_USER_ID, null) } catch (e: Exception) { null }
        migrateUserIdFromPlainPrefsIfNeeded()
    }

    fun identify(userId: String, traits: Map<String, Any>) {
        this.userId = userId
        this.userTraits = traits
        try {
            identityPrefs.edit().putString(KEY_USER_ID, userId).apply()
        } catch (e: Exception) {
            Log.w("Unilitix", "Identity: failed to persist userId: ${e.message}")
        }
        Logger.d("Identity: identified as $userId")
    }

    fun reset() {
        userId = null
        userTraits = null
        try {
            identityPrefs.edit().remove(KEY_USER_ID).apply()
        } catch (e: Exception) {
            Log.w("Unilitix", "Identity: failed to clear userId: ${e.message}")
        }
        devicePrefs.edit().remove(KEY_ANONYMOUS_ID).apply()
        anonymousId = generateAndSaveAnonymousId()
        Logger.d("Identity: reset, new anonymousId=$anonymousId")
    }

    private fun buildEncryptedPrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "unilitix_identity_enc",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Fallback for devices with a broken or unavailable Keystore.
            Log.w("Unilitix", "EncryptedSharedPreferences unavailable, falling back to plain prefs: ${e.message}")
            context.getSharedPreferences("unilitix_identity", Context.MODE_PRIVATE)
        }
    }

    // One-time migration: move user_id from the old plain prefs file to the encrypted store.
    private fun migrateUserIdFromPlainPrefsIfNeeded() {
        val legacyPrefs = context.getSharedPreferences("unilitix_identity", Context.MODE_PRIVATE)
        val legacyUserId = legacyPrefs.getString(KEY_USER_ID, null) ?: return
        try {
            identityPrefs.edit().putString(KEY_USER_ID, legacyUserId).apply()
            legacyPrefs.edit().remove(KEY_USER_ID).apply()
            userId = legacyUserId
        } catch (e: Exception) {
            Log.w("Unilitix", "Identity: migration failed: ${e.message}")
        }
    }

    private fun generateAndSaveAnonymousId(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val packageName = context.packageName
        val installTime = try {
            context.packageManager.getPackageInfo(packageName, 0).firstInstallTime.toString()
        } catch (e: Exception) {
            "0"
        }
        val id = sha256("$androidId$packageName$installTime").take(24)
        devicePrefs.edit().putString(KEY_ANONYMOUS_ID, id).apply()
        return id
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_ANONYMOUS_ID = "anonymous_id"
        private const val KEY_USER_ID = "user_id"
    }
}
