package io.unilitix.sdk.core

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.unilitix.sdk.util.Logger
import java.util.UUID

internal data class Session(
    val id: String = UUID.randomUUID().toString(),
    val startedAt: Long = System.currentTimeMillis(),
    var foregroundTimeMs: Long = 0L,
    var backgroundTimeMs: Long = 0L,
    var crashed: Boolean = false,
    var offlineEventCount: Int = 0,
    var onlineEventCount: Int = 0,
    var networkTransitions: Int = 0
)

internal class SessionManager(
    private val sessionTimeoutSeconds: Int,
    private val onSessionStart: (Session) -> Unit,
    private val onSessionEnd: (Session) -> Unit
) : DefaultLifecycleObserver {

    var currentSession: Session? = null
        private set

    private var backgroundedAt: Long = -1L
    private var foregroundedAt: Long = System.currentTimeMillis()

    // Tracks last observed network type so only genuine type changes increment networkTransitions.
    // "INITIAL" sentinel means we haven't seen the first callback yet — don't count that as a transition.
    private var lastObservedNetwork: String = "INITIAL"

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        startNewSession()
    }

    override fun onStart(owner: LifecycleOwner) {
        val backgroundDuration = if (backgroundedAt > 0) System.currentTimeMillis() - backgroundedAt else 0L
        Logger.d("SessionManager: app foregrounded, backgroundDuration=${backgroundDuration}ms")

        if (backgroundDuration > sessionTimeoutSeconds * 1000L) {
            endCurrentSession()
            startNewSession()
        } else {
            currentSession?.backgroundTimeMs = (currentSession?.backgroundTimeMs ?: 0L) + backgroundDuration
        }

        foregroundedAt = System.currentTimeMillis()
        backgroundedAt = -1L
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = System.currentTimeMillis()
        val foregroundDuration = System.currentTimeMillis() - foregroundedAt
        currentSession?.foregroundTimeMs = (currentSession?.foregroundTimeMs ?: 0L) + foregroundDuration
        Logger.d("SessionManager: app backgrounded")
    }

    fun startNewSession() {
        val prev = currentSession?.id?.take(8)
        val session = Session()
        currentSession = session
        foregroundedAt = System.currentTimeMillis()
        lastObservedNetwork = "INITIAL"
        Logger.d("SessionManager: new session started id=${session.id.take(8)} (previous: $prev)")
        onSessionStart(session)
    }

    fun endCurrentSession() {
        val session = currentSession ?: return
        currentSession = null
        Logger.d("SessionManager: session ended id=${session.id.take(8)} crashed=${session.crashed}")
        onSessionEnd(session)
    }

    fun markCrashed() {
        currentSession?.let {
            it.crashed = true
            Logger.d("SessionManager: session ${it.id.take(8)} marked as crashed")
        }
    }

    fun onNetworkTypeChanged(newType: String) {
        if (lastObservedNetwork == "INITIAL") {
            lastObservedNetwork = newType
            return
        }
        if (newType == lastObservedNetwork) return
        lastObservedNetwork = newType
        currentSession?.networkTransitions = (currentSession?.networkTransitions ?: 0) + 1
        Logger.d("SessionManager: network → $newType, transitions=${currentSession?.networkTransitions}")
    }
}
