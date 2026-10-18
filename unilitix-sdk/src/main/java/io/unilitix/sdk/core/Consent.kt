package io.unilitix.sdk.core

import io.unilitix.sdk.UnilitixConsent

/**
 * Minimal key-value persistence, so consent logic can be unit tested on the
 * JVM without SharedPreferences. See [SharedPreferencesStore].
 */
internal interface KeyValueStore {
    fun getString(key: String): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
}

/**
 * Persists the consent decision across launches (same semantics as the
 * Flutter SDK's OptManager).
 */
internal class ConsentStore(private val kv: KeyValueStore) {

    /**
     * The persisted decision, or [initial] when the user has never decided on
     * this device. Installs from SDK versions before 1.5.1 stored only an
     * opt-out flag: `true` migrates to [UnilitixConsent.DENIED]; `false` is
     * not treated as a decision (it was also written by plain `optIn()`
     * calls), so [initial] applies.
     */
    fun load(initial: UnilitixConsent): UnilitixConsent {
        UnilitixConsent.fromValue(kv.getString(KEY_CONSENT))?.let { return it }
        if (kv.getBoolean(KEY_LEGACY_OPT_OUT, false)) {
            kv.putString(KEY_CONSENT, UnilitixConsent.DENIED.value)
            return UnilitixConsent.DENIED
        }
        return initial
    }

    /** Persists [consent]; the legacy flag is kept in sync for downgrades. */
    fun save(consent: UnilitixConsent) {
        kv.putString(KEY_CONSENT, consent.value)
        kv.putBoolean(KEY_LEGACY_OPT_OUT, consent == UnilitixConsent.DENIED)
    }

    companion object {
        const val KEY_CONSENT = "consent"
        const val KEY_LEGACY_OPT_OUT = "opt_out"
    }
}

/** What the SDK does when consent changes; implemented by UnilitixInternal. */
internal interface ConsentEffects {
    /** Resume capture after a denial (pending or granted). */
    fun startCapture()

    /** Stop capture (denied). */
    fun stopCapture()

    /**
     * Delete everything held on the device: in-memory event, snapshot and
     * breadcrumb buffers, queued event batches, queued screenshots, the
     * pending-crash file, and any scheduled upload.
     */
    fun purgeLocalData()

    /** Upload everything that was held while consent was pending. */
    fun releaseHeld()
}

/**
 * The consent state machine. Pure Kotlin: every capture path asks
 * [canCapture], every uploader asks [canUpload].
 */
internal class ConsentController(
    private val store: ConsentStore,
    initial: UnilitixConsent,
    private val effects: ConsentEffects
) {
    @Volatile
    var state: UnilitixConsent = store.load(initial)
        private set

    /** Pending and granted capture; denied captures nothing. */
    val canCapture: Boolean
        get() = state != UnilitixConsent.DENIED

    /** Only granted uploads; pending holds everything on the device. */
    val canUpload: Boolean
        get() = state == UnilitixConsent.GRANTED

    /** Records and acts on a decision. Returns the previous state. */
    @Synchronized
    fun set(value: UnilitixConsent): UnilitixConsent {
        val before = state
        // Close the gates before any effect runs, so nothing new is captured
        // or uploaded while the purge is in progress.
        state = value
        store.save(value)
        when (value) {
            UnilitixConsent.DENIED -> {
                effects.stopCapture()
                effects.purgeLocalData()
            }
            UnilitixConsent.PENDING -> {
                if (before == UnilitixConsent.DENIED) effects.startCapture()
            }
            UnilitixConsent.GRANTED -> {
                if (before == UnilitixConsent.DENIED) effects.startCapture()
                if (before != UnilitixConsent.GRANTED) effects.releaseHeld()
            }
        }
        return before
    }
}
