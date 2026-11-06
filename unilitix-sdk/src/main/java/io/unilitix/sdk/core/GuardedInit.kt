package io.unilitix.sdk.core

import io.unilitix.sdk.util.Logger

/**
 * The exception barrier around `Unilitix.init` (1.10.2). Building or starting
 * the SDK can throw on some devices (`registerDefaultNetworkCallback` throws
 * `SecurityException` on some Android 11 builds and `TooManyRequestsException`
 * when the app holds too many callbacks; a KeyStore or database error while
 * opening storage), and none of that may reach the host app's
 * `Application.onCreate`.
 *
 * [run] returns the started instance, or null when [create] or [start] threw:
 * then whatever [start] had already registered is undone by [teardown] (best
 * effort), the SDK stays disabled for this launch, and the caller does not
 * publish the instance, so a later `init` call can try again.
 */
internal object GuardedInit {

    fun <T : Any> run(
        create: () -> T,
        start: (T) -> Unit,
        teardown: (T) -> Unit
    ): T? {
        val created = try {
            create()
        } catch (t: Throwable) {
            report("could not be created", t)
            return null
        }
        return try {
            start(created)
            created
        } catch (t: Throwable) {
            report("could not start", t)
            try {
                teardown(created)
            } catch (t2: Throwable) {
                Logger.d("Unilitix: cleanup after a failed init also failed: ${t2.javaClass.simpleName}: ${t2.message}")
            }
            null
        }
    }

    private fun report(what: String, t: Throwable) {
        try {
            // One line, always: the integrator needs to know the SDK is off.
            // The details (and the stack trace) only with debugLogging.
            Logger.w("Unilitix: SDK $what (${t.javaClass.simpleName}); analytics disabled for this launch")
            Logger.d("Unilitix: init failure detail: ${t.stackTraceToString().take(4_000)}")
        } catch (_: Throwable) {
            // Logging must never be the thing that crashes the host.
        }
    }
}
