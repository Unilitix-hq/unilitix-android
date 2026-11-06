package io.unilitix.sdk

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import io.unilitix.sdk.capture.CrashHandler
import io.unilitix.sdk.capture.NativeSignalHandler
import io.unilitix.sdk.capture.PrivacyTags
import io.unilitix.sdk.core.GuardedInit
import io.unilitix.sdk.core.Screenshot
import io.unilitix.sdk.engine.ErrorReport
import io.unilitix.sdk.engine.UnilitixCore
import io.unilitix.sdk.platform.ProcessGuard
import io.unilitix.sdk.storage.PendingScreenshot
import io.unilitix.sdk.util.Logger
import org.json.JSONObject

/**
 * The Unilitix Android SDK. Every method is safe to call from any thread and
 * never throws into the caller; calls made before [init] completes are
 * buffered (up to 200, oldest dropped) and replayed once the first session has
 * started.
 */
object Unilitix {

    /** The engine entry point and its pre-init buffer: exists before [init]. */
    internal val core = UnilitixCore()

    @Volatile
    internal var instance: UnilitixInternal? = null

    /** Wrapper name/version set before [init] (applied to the User-Agent once it runs). */
    @Volatile
    internal var wrapperLabel: String? = null

    /** [setExternalScreenCapture] before [init]. */
    @Volatile
    internal var externalCaptureRequested: Boolean = false

    /**
     * Initializes the SDK. Call once, in `Application.onCreate`.
     * Consent starts as the persisted decision, else
     * [UnilitixConfig.Builder.consent] (default [UnilitixConsent.GRANTED]).
     *
     * Only the crash handler is installed on the calling thread; storage,
     * key store, database and native library loading happen on a background
     * thread, and calls made meanwhile are buffered. In a multi-process app
     * only the main process initialises; other processes ignore the call.
     */
    @JvmStatic
    @JvmOverloads
    fun init(application: Application, apiKey: String, configure: UnilitixConfig.Builder.() -> Unit = {}) {
        initWith(application, apiKey, null, configure)
    }

    /**
     * Initializes the SDK with an initial [consent] state, used only when the
     * user has not decided yet on this device: a decision persisted by
     * [setConsent] (or [optOut] / [optIn]) on an earlier launch wins.
     *
     * ```kotlin
     * Unilitix.init(this, "ul_live_xxx", consent = UnilitixConsent.PENDING) { }
     * // after the user accepts:
     * Unilitix.setConsent(UnilitixConsent.GRANTED)
     * ```
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        application: Application,
        apiKey: String,
        consent: UnilitixConsent,
        configure: UnilitixConfig.Builder.() -> Unit = {}
    ) {
        initWith(application, apiKey, consent, configure)
    }

    private fun initWith(
        application: Application,
        apiKey: String,
        consent: UnilitixConsent?,
        configure: UnilitixConfig.Builder.() -> Unit
    ) {
        try {
            // Lifecycle observers and Activity callbacks must be registered on the
            // main thread; a cross-platform bridge may call init from its own thread.
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Logger.w("Unilitix.init() called off the main thread; initialising on the main thread")
                Handler(Looper.getMainLooper()).post { initWith(application, apiKey, consent, configure) }
                return
            }
            if (instance != null) {
                Logger.w("Unilitix.init() called more than once — ignoring")
                return
            }
            if (!ProcessGuard.isMainProcess(application)) {
                Logger.d("Unilitix.init() ignored in process ${ProcessGuard.currentProcessName(application)}: the SDK runs in the main process only")
                return
            }
            val started = SystemClock.elapsedRealtime()
            // Exception barrier (1.10.2): nothing below may throw into the host's
            // Application.onCreate. On failure the SDK stays off and a later init can retry.
            val internal = GuardedInit.run(
                create = {
                    val builder = UnilitixConfig.Builder().apply(configure)
                    val initialConsent = consent ?: builder.consent
                    val config = builder.build() // clamps out-of-range values
                    Logger.configure(config.debugLogging)
                    if (config.sessionTimeoutSeconds < 60) {
                        Logger.w("Unilitix: sessionTimeoutSeconds=${config.sessionTimeoutSeconds} is very low — consider 1800 (30 min)")
                    }
                    UnilitixInternal(application, apiKey, config, initialConsent, core)
                },
                start = { it.startOnMainThread() },
                teardown = { it.shutdown() }
            ) ?: return
            instance = internal
            Logger.d("Unilitix SDK initialising | key=${Logger.keyLabel(apiKey)} | v${BuildConfig.SDK_VERSION} | main thread ${SystemClock.elapsedRealtime() - started} ms")
        } catch (t: Throwable) {
            Logger.w("Unilitix.init() failed: ${t.javaClass.simpleName}")
        }
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Logger.w("Unilitix.$what failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Attaches the SDK to an Activity that already existed when [init] ran.
     * Call this after a late init (React Native, Flutter add-to-app) so taps,
     * screens and replay start on the current screen instead of the next one.
     * Safe to call more than once; a no-op before init.
     */
    @JvmStatic
    fun attachToActivity(activity: Activity) = guard("attachToActivity") {
        instance?.attachToActivity(activity)
    }

    /**
     * Decides, per uncaught JVM exception, whether the SDK records it (true) or
     * only passes it on to the next handler (false). For wrapper SDKs that
     * report a crash themselves before the platform re-throws it, such as React
     * Native's fatal JS errors.
     */
    @JvmStatic
    fun setCrashFilter(filter: ((Throwable) -> Boolean)?) {
        CrashHandler.crashFilter = filter
    }

    /**
     * Links this device's anonymous id to your user id: sends
     * `POST /v1/ingest/identify {anonymousId, customUserId, traits}` once
     * (held while consent is pending), and later uploads carry the user id.
     * Traits are normalised (depth 5, 100 keys, 1024-character strings,
     * non-finite numbers become null) and are not stored on the device
     * beyond the pending request.
     */
    @JvmStatic
    @JvmOverloads
    fun identify(userId: String, traits: Map<String, Any?> = emptyMap()) = guard("identify") {
        core.identify(userId, traits)
    }

    /** Records a custom event (`CUSTOM`, `metadata.name` = [name], normalised [properties]). */
    @JvmStatic
    @JvmOverloads
    fun trackEvent(name: String, properties: Map<String, Any?> = emptyMap()) = guard("trackEvent") {
        core.track(name, properties)
    }

    /** Records a screen view (`NAVIGATE`); a repeat of the current screen is ignored. */
    @JvmStatic
    fun trackScreen(screenName: String) = guard("trackScreen") {
        core.screen(screenName)
    }

    /**
     * Records an error the host app (or a bridge) caught itself. [fatal] =
     * false emits a non-fatal `EXCEPTION`; [fatal] = true emits a `CRASH`,
     * marks the session crashed and persists and uploads it before
     * returning (bounded by the contract's 2 s budget), so a caller that is
     * about to let the process die can do so safely.
     *
     * [platform] names where the error came from (`"android"`, `"flutter"`,
     * `"react-native"`); it is sent as `metadata.platform`.
     */
    @JvmStatic
    @JvmOverloads
    fun recordException(
        type: String,
        message: String,
        stackTrace: String,
        fatal: Boolean,
        platform: String = "android"
    ) = recordExternalError(type, message, stackTrace, fatal, platform, null, null)

    /**
     * Adds a breadcrumb to the trail attached to the next crash or exception
     * report (last 50 kept). Use it for app state that explains a crash —
     * never for personal data.
     */
    @JvmStatic
    @JvmOverloads
    fun addBreadcrumb(message: String, category: String = "custom") = guard("addBreadcrumb") {
        core.addBreadcrumb(message, category)
    }

    /** True once [init] has run in this process (the SDK may still be finishing its start). */
    @JvmStatic
    val isInitialized: Boolean
        get() = instance != null

    /**
     * True when this SDK captures native (NDK) crashes in this process: [init]
     * has run with `autoTrackCrashes` on and, on Android 5.0-10 (API 21-29),
     * its signal handler is installed (on API 30+ the next launch reads the
     * system's exit records instead).
     *
     * For other Unilitix SDKs in the same app: the Unilitix Flutter plugin
     * (2.5.0+) checks it, without a compile-time dependency, and leaves native
     * crashes to this SDK when it is true, so one native crash is reported once.
     */
    @JvmStatic
    fun nativeCrashCaptureActive(): Boolean = try {
        instance?.nativeCrashCaptureActive == true
    } catch (t: Throwable) {
        false
    }

    @JvmStatic
    fun startSession() = guard("startSession") { core.startNewSession() }

    @JvmStatic
    fun endSession() = guard("endSession") { core.endCurrentSession() }

    /** Uploads queued events now, unless offline, paused, backing off or consent is not granted. */
    @JvmStatic
    fun flush() = guard("flush") { core.flush() }

    /**
     * The current consent state. Before [init] has finished this is
     * [UnilitixConsent.GRANTED] (the contract default).
     */
    @JvmStatic
    val consent: UnilitixConsent
        get() = try { core.engine?.consentState() } catch (t: Throwable) { null } ?: UnilitixConsent.GRANTED

    /**
     * Records the user's consent decision (persisted across launches) and acts
     * on it immediately:
     * * [UnilitixConsent.GRANTED]: uploads resume and everything held while
     *   pending is sent.
     * * [UnilitixConsent.PENDING]: capture continues into the on-device queue;
     *   nothing is uploaded or deleted.
     * * [UnilitixConsent.DENIED]: capture stops and everything queued on the
     *   device (events, pending identify, snapshots, screenshots, crash files,
     *   the install id) is deleted.
     * Before [init] the call is buffered and applied when the SDK starts.
     */
    @JvmStatic
    fun setConsent(consent: UnilitixConsent) = guard("setConsent") {
        core.setConsent(consent)
    }

    /** Stops all tracking and deletes queued data. Same as `setConsent(UnilitixConsent.DENIED)`. */
    @JvmStatic
    fun optOut() = setConsent(UnilitixConsent.DENIED)

    /** Resumes tracking. Same as `setConsent(UnilitixConsent.GRANTED)`. */
    @JvmStatic
    fun optIn() = setConsent(UnilitixConsent.GRANTED)

    /**
     * Shows [view] and everything inside it in session replay even when
     * [UnilitixConfig.Builder.maskAllText] or [UnilitixConfig.Builder.maskImages]
     * is on. Pass `false` to mask it again. Can be called before [init].
     *
     * It never overrides [UnilitixPrivate], [mask], `maskedViewIds` or input
     * masking: an `EditText` inside an unmasked view is still masked.
     */
    @JvmStatic
    @JvmOverloads
    fun unmask(view: View, unmasked: Boolean = true) = guard("unmask") {
        view.setTag(R.id.unilitix_unmasked, if (unmasked) true else null)
    }

    /**
     * Hides [view] and everything inside it in session replay and stops
     * recording taps on it, like [UnilitixPrivate] on its class. Pass
     * `false` to undo. Can be called before [init].
     */
    @JvmStatic
    @JvmOverloads
    fun mask(view: View, masked: Boolean = true) = guard("mask") {
        PrivacyTags.mark(view, masked)
    }

    /** True when [view] itself carries the [unmask] marker (ancestors are checked by the walkers). */
    internal fun isUnmasked(view: View): Boolean {
        val tag = view.getTag(R.id.unilitix_unmasked) ?: return false
        return tag == true || tag.toString().equals("true", ignoreCase = true)
    }

    /**
     * Forgets the user: what was captured so far is uploaded under the old
     * identity first, then the anonymous id and install id are replaced, the
     * user id and traits are dropped, and a new session starts.
     */
    @JvmStatic
    fun reset() = guard("reset") { core.reset() }

    // ── Wrapper SDKs (Flutter 3.0, React Native) ─────────────────────────

    /**
     * Names the cross-platform SDK that wraps this one (for example
     * `setWrapper("flutter", "3.0.0")`). Sent with every session as
     * `sdkWrapper` / `sdkWrapperVersion` (also in `sessionData`) and in the
     * User-Agent (`Unilitix-Android/1.11.0 (flutter/3.0.0)`). Call before
     * [init] or right after it.
     */
    @JvmStatic
    fun setWrapper(name: String, version: String) = guard("setWrapper") {
        val label = "${name.trim().take(32)}/${version.trim().take(32)}"
        wrapperLabel = label
        instance?.setWrapperLabel(label)
        core.setWrapper(name, version)
    }

    /**
     * An error raised in the wrapper's language (Dart, JavaScript).
     * [fingerprint], when given, is used as is (the wrapper computed the
     * contract fingerprint over its own stack); otherwise the SDK computes it
     * from [type] and [stackTrace]. [metadata] is added to the event's
     * `metadata` (normalised; `fingerprint` and `platform` are the SDK's).
     * A [fatal] error is a `CRASH`: the session is marked crashed and the
     * report is persisted and uploaded before this returns, within the
     * contract's 2 s budget.
     */
    @JvmStatic
    @JvmOverloads
    fun recordExternalError(
        type: String,
        message: String,
        stackTrace: String?,
        fatal: Boolean,
        platform: String,
        fingerprint: String? = null,
        metadata: Map<String, Any?>? = null
    ) = guard("recordExternalError") {
        val report = ErrorReport(
            type = type,
            message = message,
            stackTrace = stackTrace ?: "",
            fatal = fatal,
            platform = platform,
            fingerprint = fingerprint?.takeIf { it.isNotBlank() },
            metadata = metadata,
        )
        if (!fatal) {
            core.recordError(report)
            return@guard
        }
        // A SIGABRT that follows (the bridge aborting) is this crash, not a new one.
        NativeSignalHandler.markExceptionHandled()
        if (!core.recordFatal(report, CrashHandler.FATAL_FLUSH_BUDGET_MS) && core.isStarted) {
            Logger.w("Unilitix: fatal report not persisted within budget")
        }
    }

    /**
     * A JPEG frame the wrapper captured (already masked by it) for session
     * replay. It goes through the SDK's own screenshot pipeline: dropped
     * unless consent allows capture and this session is sampled for replay,
     * counted against `maxScreenshotsPerSession` and `maxScreenshotsPerMinute`,
     * stored on the device and uploaded with the session's other frames
     * (on Wi-Fi only when `uploadScreenshotsOnWifiOnly`). [width] and
     * [height] are the frame's viewport in logical pixels; [timestampMs] the
     * capture time (ms since 1970); [screen] the route name.
     */
    @JvmStatic
    fun captureExternalScreenshot(image: ByteArray, width: Int, height: Int, timestampMs: Long, screen: String?) =
        guard("captureExternalScreenshot") {
            instance?.addExternalScreenshot(image, width, height, timestampMs, screen)
        }

    /**
     * A wireframe snapshot from the wrapper, in the contract's snapshot JSON
     * shape (`{capturedAt, screenName, viewportWidth, viewportHeight,
     * hierarchy}`; `capturedAt` RFC 3339 or ms since 1970; any `ordinal` is
     * replaced by the SDK's). Same gates and caps as the SDK's own snapshots.
     */
    @JvmStatic
    fun addExternalSnapshot(json: String) = guard("addExternalSnapshot") {
        instance?.addExternalSnapshot(json)
    }

    /**
     * True while the wrapper supplies replay frames and snapshots: the SDK's
     * own view-based screenshot and snapshot capture stands down (a Flutter
     * or React Native screen is one native view, so the wrapper captures it
     * better). False gives capture back to the SDK.
     */
    @JvmStatic
    fun setExternalScreenCapture(enabled: Boolean) = guard("setExternalScreenCapture") {
        externalCaptureRequested = enabled
        instance?.replayState?.externalCapture = enabled
    }

    /**
     * A tap from the wrapper's own gesture layer (Flutter and React Native
     * draw into one native view, so the SDK's TouchTracker cannot see their
     * widgets). [type] is `TAP` or `RAGE_TAP` (anything else is ignored);
     * [x]/[y] are logical pixels, or null inside a private area so no
     * position is sent. The current screen is stamped by the SDK.
     */
    @JvmStatic
    fun recordExternalInteraction(type: String, x: Double?, y: Double?) = guard("recordExternalInteraction") {
        if (type != "TAP" && type != "RAGE_TAP") return@guard
        val fields = LinkedHashMap<String, Any?>()
        if (x != null && y != null && x.isFinite() && y.isFinite()) {
            fields["x"] = Math.round(x).toDouble()
            fields["y"] = Math.round(y).toDouble()
        }
        core.recordInteraction(type, fields)
    }

    /** The current session's id, or null before the SDK has started (or between sessions). */
    @JvmStatic
    fun currentSessionId(): String? = try { core.engine?.currentSessionId() } catch (t: Throwable) { null }

    /** Whether the current session was sampled for session replay (`sampleRate`). */
    @JvmStatic
    fun isReplaySampled(): Boolean = try { core.engine?.isReplaySampled() == true } catch (t: Throwable) { false }

    /** For [UnilitixInternal]: a frame or snapshot from a wrapper. */
    internal fun externalScreenshotOf(image: ByteArray, width: Int, height: Int, timestampMs: Long, screen: String?, ordinal: Int) =
        Screenshot(
            ordinal = ordinal,
            capturedAt = if (timestampMs > 0) timestampMs else System.currentTimeMillis(),
            screenName = screen?.take(256) ?: "",
            viewportWidth = width.coerceAtLeast(0),
            viewportHeight = height.coerceAtLeast(0),
            imageBytes = image,
        )

    internal const val EXTERNAL_FORMAT = PendingScreenshot.FORMAT_JPEG

    /** Parses a wrapper snapshot; null when it is not the contract shape. */
    internal fun parseExternalSnapshot(json: String): JSONObject? = try {
        val o = JSONObject(json)
        if (o.optJSONObject("hierarchy") == null) null else o
    } catch (e: Exception) {
        null
    }
}
