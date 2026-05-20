package io.unilitix.sdk.core

import android.content.Context
import android.provider.Settings
import io.unilitix.sdk.util.Logger
import java.security.MessageDigest

internal class Identity(private val context: Context) {

    private val prefs = context.getSharedPreferences("unilitix_identity", Context.MODE_PRIVATE)

    var anonymousId: String
        private set

    var userId: String? = null
        private set

    var userTraits: Map<String, Any>? = null
        private set

    init {
        anonymousId = prefs.getString(KEY_ANONYMOUS_ID, null) ?: generateAndSaveAnonymousId()
        userId = prefs.getString(KEY_USER_ID, null)
    }

    fun identify(userId: String, traits: Map<String, Any>) {
        this.userId = userId
        this.userTraits = traits
        prefs.edit().putString(KEY_USER_ID, userId).apply()
        Logger.d("Identity: identified as $userId")
    }

    fun reset() {
        userId = null
        userTraits = null
        prefs.edit()
            .remove(KEY_USER_ID)
            .remove(KEY_ANONYMOUS_ID)
            .apply()
        anonymousId = generateAndSaveAnonymousId()
        Logger.d("Identity: reset, new anonymousId=$anonymousId")
    }

    private fun generateAndSaveAnonymousId(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val packageName = context.packageName
        val installTime = try {
            context.packageManager.getPackageInfo(packageName, 0).firstInstallTime.toString()
        } catch (e: Exception) {
            "0"
        }

        val raw = "$androidId$packageName$installTime"
        val hash = sha256(raw)
        val id = hash.take(24)

        prefs.edit().putString(KEY_ANONYMOUS_ID, id).apply()
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
