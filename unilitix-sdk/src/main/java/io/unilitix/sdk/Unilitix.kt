package io.unilitix.sdk

import android.app.Application
import android.view.View
import io.unilitix.sdk.BuildConfig
import io.unilitix.sdk.capture.CrashHandler
import io.unilitix.sdk.capture.NativeCrash
import io.unilitix.sdk.capture.NativeCrashFile
import io.unilitix.sdk.capture.NativeCrashRecord
import io.unilitix.sdk.capture.NativeCrashReporter
import io.unilitix.sdk.capture.NativeSignalHandler
import io.unilitix.sdk.capture.ProcessExit
import io.unilitix.sdk.capture.ProcessExits
import io.unilitix.sdk.capture.PerformanceMonitor
import io.unilitix.sdk.capture.RageTapDetector
import io.unilitix.sdk.capture.ScreenTracker
import io.unilitix.sdk.capture.ScreenshotCapture
import io.unilitix.sdk.capture.SnapshotCapture
import io.unilitix.sdk.capture.TouchTracker
import io.unilitix.sdk.context.BatteryInfo
import io.unilitix.sdk.context.NetworkMonitor
import io.unilitix.sdk.context.collectDeviceInfo
import io.unilitix.sdk.context.collectNetworkInfo
import io.unilitix.sdk.core.Breadcrumbs
import io.unilitix.sdk.core.ConsentController
import io.unilitix.sdk.core.ConsentEffects
import io.unilitix.sdk.core.ConsentStore
import io.unilitix.sdk.core.SharedPreferencesStore
import io.unilitix.sdk.core.ErrorReportLimiter
import io.unilitix.sdk.core.EventBuffer
import io.unilitix.sdk.core.Fingerprint
import io.unilitix.sdk.core.Identity
import io.unilitix.sdk.core.Session
import io.unilitix.sdk.core.SessionHistory
import io.unilitix.sdk.core.SessionManager
import io.unilitix.sdk.core.SnapshotBuffer
import io.unilitix.sdk.core.UnilitixEvent
import io.unilitix.sdk.flush.FlushScheduler
import io.unilitix.sdk.network.ApiClient
import io.unilitix.sdk.network.UploadGate
import io.unilitix.sdk.storage.EventDatabase
import io.unilitix.sdk.storage.PendingEvent
import io.unilitix.sdk.storage.PendingScreenshot
import io.unilitix.sdk.storage.ScreenshotQueueCap
import io.unilitix.sdk.util.Json
import java.io.File
import io.unilitix.sdk.util.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.math.roundToInt

object Unilitix {

    @Volatile
    internal var instance: UnilitixInternal? = null

    /**
     * Initializes the SDK. Call once, in `Application.onCreate`.
     * Consent starts as the persisted decision, else
     * [UnilitixConfig.Builder.consent] (default [UnilitixConsent.GRANTED]).
     */
    @JvmStatic
    @JvmOverloads
    fun init(application: Application, apiKey: String, configure: UnilitixConfig.Builder.() -> Unit = {}) {
        initWith(application, apiKey, null, configure)
    }

    /**
     * Initializes the SDK with an initial [consent] state, used only when the
     * user has not decided on this device yet: a decision persisted by
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
        if (instance != null) {
            Logger.w("Unilitix.init() called more than once — ignoring")
            return
        }
        val builder = UnilitixConfig.Builder().apply(configure)
        val initialConsent = consent ?: builder.consent
        val config = builder.build()
        // Applies in release builds of the library too (it used to be gated on
        // the library's own BuildConfig.DEBUG, which is false in the AAR).
        Logger.configure(config.debugLogging)
        if (config.sessionTimeoutSeconds < 60) {
            Logger.w("Unilitix: sessionTimeoutSeconds=${config.sessionTimeoutSeconds} is very low — consider 1800 (30 min) to avoid session fragmentation")
        }

        val internal = UnilitixInternal(application, apiKey, config, initialConsent)
        instance = internal
        internal.start()
        Logger.d("✅ Unilitix SDK initialized | key=${Logger.keyLabel(apiKey)} | consent=${internal.consent.state.value} | session=${internal.sessionManager.currentSession?.id?.take(8)} | v${BuildConfig.SDK_VERSION}")
    }

    @JvmStatic
    fun identify(userId: String, traits: Map<String, Any> = emptyMap()) {
        instance?.identity?.identify(userId, traits)
    }

    @JvmStatic
    fun trackEvent(name: String, properties: Map<String, Any> = emptyMap()) {
        val internal = instance ?: run {
            Logger.w("Unilitix.trackEvent() called before init()")
            return
        }
        if (internal.optedOut) return
        if (!internal.shouldSample()) return

        internal.scope.launch {
            internal.emitEvent(UnilitixEvent(type = name, properties = properties))
        }
    }

    @JvmStatic
    fun trackScreen(screenName: String) {
        val internal = instance ?: return
        if (internal.optedOut) return

        internal.scope.launch {
            internal.emitEvent(UnilitixEvent(type = "NAVIGATE", screen = screenName))
        }
    }

    /**
     * Records an error the host app (or a bridge such as React Native) caught
     * itself. [fatal] = false emits a non-fatal `EXCEPTION`; [fatal] = true
     * emits a `CRASH`, marks the session crashed and persists it before
     * returning (bounded to [FATAL_PERSIST_TIMEOUT_MS]), so a caller that is
     * about to let the process die can do so safely.
     *
     * [platform] names where the error came from (`"android"`, `"js"`) and
     * travels in the exception message prefix, as the Flutter SDK does for
     * native crashes.
     */
    @JvmStatic
    @JvmOverloads
    fun recordException(
        type: String,
        message: String,
        stackTrace: String,
        fatal: Boolean,
        platform: String = "android"
    ) {
        val internal = instance ?: run {
            Logger.w("Unilitix.recordException() called before init()")
            return
        }
        if (internal.optedOut) return
        internal.recordException(type, message, stackTrace, fatal, platform)
    }

    /**
     * Adds a breadcrumb to the trail attached to the next crash or exception
     * report (last 50 kept). Use it for app state that explains a crash —
     * never for personal data.
     */
    @JvmStatic
    @JvmOverloads
    fun addBreadcrumb(message: String, category: String = "custom") {
        val internal = instance ?: return
        if (internal.optedOut) return
        internal.breadcrumbs.add(category, internal.breadcrumbs.lastScreen(), message)
    }

    /** True once [init] has run; bridges use it to decide whether to forward calls. */
    @JvmStatic
    val isInitialized: Boolean
        get() = instance != null

    internal const val FATAL_PERSIST_TIMEOUT_MS = 2_000L

    @JvmStatic
    fun startSession() {
        instance?.sessionManager?.startNewSession()
    }

    @JvmStatic
    fun endSession() {
        instance?.sessionManager?.endCurrentSession()
    }

    @JvmStatic
    fun flush() {
        val internal = instance ?: return
        internal.scope.launch {
            internal.flushNow()
        }
    }

    /**
     * The current consent state. Before [init] this is
     * [UnilitixConsent.GRANTED] (the contract default).
     */
    @JvmStatic
    val consent: UnilitixConsent
        get() = instance?.consent?.state ?: UnilitixConsent.GRANTED

    /**
     * Records the user's consent decision (persisted across launches) and acts
     * on it immediately:
     * * [UnilitixConsent.GRANTED]: uploads resume and everything held while
     *   pending is sent.
     * * [UnilitixConsent.PENDING]: capture continues into the on-device queue;
     *   nothing is uploaded or deleted.
     * * [UnilitixConsent.DENIED]: capture stops and everything queued on the
     *   device (events, snapshots, screenshots, pending crash) is deleted.
     */
    @JvmStatic
    fun setConsent(consent: UnilitixConsent) {
        val internal = instance ?: run {
            Logger.w("Unilitix.setConsent() called before init() — ignoring; pass consent to init() instead")
            return
        }
        val before = internal.consent.set(consent)
        Logger.d("Unilitix: consent ${before.value} → ${consent.value} (persisted)")
    }

    /** Stops all tracking and deletes queued data. Same as `setConsent(UnilitixConsent.DENIED)`. */
    @JvmStatic
    fun optOut() {
        setConsent(UnilitixConsent.DENIED)
    }

    /** Resumes tracking. Same as `setConsent(UnilitixConsent.GRANTED)`. */
    @JvmStatic
    fun optIn() {
        setConsent(UnilitixConsent.GRANTED)
    }

    /**
     * Shows [view] and everything inside it in session replay even when
     * [UnilitixConfig.Builder.maskAllText] or [UnilitixConfig.Builder.maskImages]
     * is on. Use it for content with no personal data that is needed to
     * understand the screen: headings, button labels, navigation, static copy.
     * Pass `false` to mask it again. Can be called before [init].
     *
     * It never overrides [UnilitixPrivate], `maskedViewIds` or input masking
     * (`maskInputs`): an `EditText` inside an unmasked view is still masked.
     *
     * In layout XML, the same marker is a keyed tag:
     * ```xml
     * <TextView ...>
     *     <tag android:id="@id/unilitix_unmasked" android:value="true" />
     * </TextView>
     * ```
     */
    @JvmStatic
    @JvmOverloads
    fun unmask(view: View, unmasked: Boolean = true) {
        view.setTag(R.id.unilitix_unmasked, if (unmasked) true else null)
    }

    /** True when [view] itself carries the [unmask] marker (ancestors are checked by the walkers). */
    internal fun isUnmasked(view: View): Boolean {
        val tag = view.getTag(R.id.unilitix_unmasked) ?: return false
        return tag == true || tag.toString().equals("true", ignoreCase = true)
    }

    @JvmStatic
    fun reset() {
        val internal = instance ?: return
        internal.identity.reset()
        internal.sessionManager.endCurrentSession()
        internal.sessionManager.startNewSession()
        Logger.d("Unilitix: reset complete")
    }
}

internal class UnilitixInternal(
    private val application: Application,
    private val apiKey: String,
    internal val config: UnilitixConfig,
    initialConsent: UnilitixConsent = UnilitixConsent.GRANTED
) : ConsentEffects {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val identity = Identity(application)
    internal val apiClient = ApiClient(config.apiUrl, apiKey)
    internal val networkMonitor = NetworkMonitor(application)

    private val database = EventDatabase.getInstance(application)
    private val flushScheduler = FlushScheduler(application)
    internal val breadcrumbs = Breadcrumbs()
    private val performanceMonitor = PerformanceMonitor()
    internal val batteryInfo = BatteryInfo(application)

    private val prefs = application.getSharedPreferences("unilitix_prefs", android.content.Context.MODE_PRIVATE)

    /** Consent state; persisted in unilitix_prefs (migrates the 1.5.0 `opt_out` flag). */
    internal val consent = ConsentController(ConsentStore(SharedPreferencesStore(prefs)), initialConsent, this)

    /** True when nothing may be captured (consent denied). */
    internal val optedOut: Boolean
        get() = !consent.canCapture

    /** Serializes writes to the on-device queue with the consent purge. */
    private val queueMutex = Mutex()

    // In-memory until the next successful batch; bounded separately from the
    // per-session cap so a long offline or consent-pending stretch cannot hold
    // 1500 view trees in memory.
    internal val snapshotBuffer = SnapshotBuffer(config.maxSnapshotsPerSession.coerceAtMost(SnapshotBuffer.MAX_HELD))

    /** Backoff window and 401/403 latch shared by every uploader (contract "Upload and retry"). */
    internal val uploadGate = UploadGate()

    /** maxReportsPerFingerprint / maxErrorReportsPerSession; reset on every session start. */
    internal val errorLimiter = ErrorReportLimiter()

    /** Recent sessions, for attributing a native crash on the next launch. */
    private val sessionHistory = SessionHistory(SharedPreferencesStore(prefs))

    /** Where the native signal handler (API 21-29) writes: files/unilitix/native_crash.json. */
    private val nativeCrashFile = NativeCrashFile.locate(application.filesDir)

    /**
     * Completed once native crashes of earlier processes are in the queue:
     * flushNow waits for it, so they go before this session's events.
     */
    private val nativeCrashesQueued = CompletableDeferred<Unit>()

    private var snapshotCapture: SnapshotCapture? = null
    private var screenshotCapture: ScreenshotCapture? = null

    internal val sessionManager = SessionManager(
        sessionTimeoutSeconds = config.sessionTimeoutSeconds,
        onSessionStart = { session ->
            Logger.d("✅ Session started | id=${session.id}")
            if (consent.canCapture) sessionHistory.recordStart(session.id, session.startedAt)
            screenshotCapture?.resetOrdinal()
            snapshotCapture?.resetSession()
            errorLimiter.resetSession()
        },
        onSessionEnd = { session ->
            Logger.d("Session ended: ${session.id}")
            scope.launch { flushNow(session) }
        }
    )

    private val eventBuffer = EventBuffer(
        flushBatchSize = config.flushBatchSize,
        onFlushNeeded = { flushNow() }
    )

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "unilitix-scheduler").also { it.isDaemon = true }
    }

    fun start() {
        if (config.autoTrackScreens) {
            application.registerActivityLifecycleCallbacks(
                ScreenTracker { screenName ->
                    scope.launch { emitEvent(UnilitixEvent(type = "NAVIGATE", screen = screenName)) }
                }
            )
        }

        if (config.autoTrackTaps) {
            val rageTapDetector = if (config.autoTrackRageTaps) {
                RageTapDetector { screen, cx, cy ->
                    // Cluster centre, rounded to whole logical px like TAP.
                    val x = cx.roundToInt()
                    val y = cy.roundToInt()
                    scope.launch {
                        emitEvent(
                            UnilitixEvent(
                                type = "RAGE_TAP",
                                screen = screen,
                                properties = mapOf("x" to x, "y" to y)
                            )
                        )
                    }
                    Logger.d("RageTap detected on $screen at ($x, $y)")
                }
            } else null

            application.registerActivityLifecycleCallbacks(
                TouchTracker(
                    maskInputs = config.maskInputs,
                    maskedViewIds = config.maskedViewIds
                ) { screen, x, y, viewId ->
                    val isRage = rageTapDetector?.recordTap(x.toFloat(), y.toFloat(), screen) ?: false
                    if (!isRage) {
                        scope.launch {
                            emitEvent(
                                UnilitixEvent(
                                    type = "TAP",
                                    screen = screen,
                                    properties = mapOf("x" to x, "y" to y, "view" to viewId)
                                )
                            )
                        }
                    }
                    true
                }
            )
        }

        // Before any handler of this process can write a new one: the record an
        // earlier process left (API 21-29), read and deleted exactly once.
        val nativeRecord = NativeCrashFile.consume(nativeCrashFile)

        if (config.autoTrackCrashes) {
            CrashHandler(
                context = application,
                breadcrumbs = breadcrumbs,
                database = database,
                getCurrentSessionJson = { buildSessionJson(sessionManager.currentSession) },
                onCrash = { sessionManager.markCrashed() },
                isEnabled = { consent.canCapture },
                admitReport = { fingerprint -> errorLimiter.admit(fingerprint) != null }
            ).install()
            // API 21-29 only; API 30+ reads ApplicationExitInfo instead.
            NativeSignalHandler.install(nativeCrashFile)
        }

        recoverPendingCrash()

        scope.launch {
            try {
                reportNativeCrashes(nativeRecord)
            } catch (e: Exception) {
                Logger.w("Unilitix: native crash report failed: ${e.message}")
            } finally {
                nativeCrashesQueued.complete(Unit)
            }
        }

        if (config.captureSnapshots) {
            snapshotCapture = SnapshotCapture(
                config,
                isEnabled = { consent.canCapture },
                sessionId = { sessionManager.currentSession?.id }
            ) { snapshot ->
                if (consent.canCapture) snapshotBuffer.add(snapshot)
            }
            application.registerActivityLifecycleCallbacks(snapshotCapture!!)
        }

        if (config.captureScreenshots) {
            screenshotCapture = ScreenshotCapture(
                config,
                isEnabled = { consent.canCapture },
                sessionId = { sessionManager.currentSession?.id }
            ) { screenshot ->
                scope.launch {
                    val sessionId = sessionManager.currentSession?.id?.toString() ?: return@launch
                    queueMutex.withLock {
                        if (!consent.canCapture) return@withLock
                        database.screenshotDao().insert(
                            PendingScreenshot(
                                sessionId = sessionId,
                                ordinal = screenshot.ordinal,
                                screenName = screenshot.screenName,
                                viewportWidth = screenshot.viewportWidth,
                                viewportHeight = screenshot.viewportHeight,
                                capturedAt = screenshot.capturedAt,
                                imageBytes = screenshot.imageBytes,
                            )
                        )
                        // Bound the on-device queue by count and bytes, oldest first:
                        // frames can wait for Wi-Fi across many sessions.
                        val evict = ScreenshotQueueCap.idsToEvict(
                            database.screenshotDao().sizesNewestFirst(),
                            maxCount = config.maxScreenshotsPerSession
                        )
                        if (evict.isNotEmpty()) {
                            evict.chunked(500).forEach { database.screenshotDao().deleteByIds(it) }
                            Logger.d("Unilitix: screenshot queue full, dropped ${evict.size} oldest")
                        }
                    }
                }
            }
            application.registerActivityLifecycleCallbacks(screenshotCapture!!)
        }

        if (!consent.canCapture) batteryInfo.stopObserving()

        performanceMonitor.start()

        networkMonitor.observeNetworkTransitions { newType ->
            scope.launch { sessionManager.onNetworkTypeChanged(newType) }
        }

        scheduler.scheduleAtFixedRate(
            { scope.launch { flushNow() } },
            config.flushIntervalSeconds.toLong(),
            config.flushIntervalSeconds.toLong(),
            TimeUnit.SECONDS
        )

        scheduler.scheduleAtFixedRate(
            { performanceMonitor.sampleCpu() },
            5L, 5L, TimeUnit.SECONDS
        )

        Logger.d("UnilitixInternal: started")
    }

    private fun recoverPendingCrash() {
        scope.launch {
            val file = File(application.cacheDir, "unilitix_crash_pending.json")
            if (!file.exists()) return@launch
            if (!consent.canCapture) {
                file.delete()
                return@launch
            }
            try {
                val obj = org.json.JSONObject(file.readText())
                database.eventDao().insert(
                    PendingEvent(
                        sessionJson = obj.optString("sessionJson", "{}"),
                        eventsJson  = obj.optString("eventsJson", "[]")
                    )
                )
                file.delete()
                Logger.d("Unilitix: recovered pending crash report from disk")
            } catch (e: Exception) {
                Logger.w("Unilitix: failed to recover pending crash: ${e.message}")
            }
        }
    }

    /**
     * Reports native crashes of earlier processes (UnilitixCore 0.5.0
     * behaviour): the record the signal handler left (API 21-29) and, on
     * API 30+, `REASON_CRASH_NATIVE` exits newer than the stored watermark.
     * Each goes to the session that was running when the process died
     * (marked crashed, ended at the crash), or to the current session when
     * none matches. Denied consent drops them; pending holds them on the
     * device. Runs before this session's first batch is persisted.
     */
    private suspend fun reportNativeCrashes(record: String?) {
        val crashes = mutableListOf<NativeCrash>()
        if (record != null) {
            val parsed = NativeCrashRecord.parse(record)
            if (parsed != null) crashes.add(parsed)
            else Logger.w("Unilitix: native crash record could not be read; dropped")
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // Always scanned, so the watermark moves even when nothing is reported.
            val exits = readProcessExits()
            crashes += ProcessExits.scan(SharedPreferencesStore(prefs), exits, System.currentTimeMillis()).crashes
        }
        if (crashes.isEmpty()) return
        if (!config.autoTrackCrashes) return

        val placements = NativeCrashReporter(sessionHistory)
            .place(crashes, consent.state, sessionManager.currentSession?.id)
        if (placements.isEmpty()) {
            Logger.d("Unilitix: ${crashes.size} native crash(es) from an earlier launch dropped (consent denied)")
            return
        }
        val now = System.currentTimeMillis()
        val persisted = queueMutex.withLock {
            if (!consent.canCapture) return@withLock false
            for (p in placements) {
                val event = NativeCrashRecord.toEvent(p.crash, now, ::isoTimestamp)
                val sessionJson = if (p.session != null) {
                    Logger.d("Unilitix: native crash ${p.crash.fingerprint} reported against session ${p.session.id.take(8)}")
                    crashedSessionJson(p.session, p.crash.timestampMs)
                } else {
                    Logger.d("Unilitix: native crash ${p.crash.fingerprint}: no stored session matches; reported in the current one")
                    sessionManager.markCrashed()
                    buildSessionJson(sessionManager.currentSession)
                }
                database.eventDao().insert(
                    PendingEvent(sessionJson = sessionJson, eventsJson = Json.toJson(listOf(event)))
                )
            }
            true
        }
        if (persisted && consent.canUpload && !uploadGate.isPaused) flushScheduler.scheduleFlush()
    }

    /** The crashed session's record: `crashed`, and its duration ending at the crash. */
    private fun crashedSessionJson(entry: SessionHistory.Entry, crashedAt: Long?): String {
        val base = entry.sessionJson
            ?: buildSessionJson(Session(id = entry.id, startedAt = entry.startedAt))
        return try {
            val obj = org.json.JSONObject(base)
            val endedAt = SessionHistory.endedAt(entry, crashedAt)
            obj.put("crashed", true)
            obj.put("endedAt", isoTimestamp(endedAt))
            obj.put("durationMs", (endedAt - entry.startedAt).coerceAtLeast(0L))
            obj.toString()
        } catch (e: Exception) {
            base
        }
    }

    @androidx.annotation.RequiresApi(30)
    private fun readProcessExits(): List<ProcessExit> {
        return try {
            val am = application.getSystemService(android.app.ActivityManager::class.java) ?: return emptyList()
            am.getHistoricalProcessExitReasons(application.packageName, 0, ProcessExits.MAX_QUERIED).map { info ->
                ProcessExit(
                    reason = info.reason,
                    timestampMs = info.timestamp,
                    pid = info.pid,
                    description = info.description,
                    readTrace = {
                        // Native crashes carry the tombstone (protobuf) from API 31.
                        if (info.reason == ProcessExits.REASON_CRASH_NATIVE) {
                            info.traceInputStream?.use { readPrefix(it, MAX_TRACE_BYTES) }
                        } else null
                    }
                )
            }
        } catch (e: Exception) {
            Logger.w("Unilitix: could not read process exit reasons: ${e.message}")
            emptyList()
        }
    }

    private fun readPrefix(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** See [Unilitix.recordException]. */
    internal fun recordException(
        type: String,
        message: String,
        stackTrace: String,
        fatal: Boolean,
        platform: String
    ) {
        val fingerprint = Fingerprint.of(type, stackTrace)
        if (errorLimiter.admit(fingerprint) == null) {
            // Same error reported maxReportsPerFingerprint times this session
            // (or the session's error budget is spent): counted, not sent.
            Logger.d("Unilitix: repeat ${if (fatal) "CRASH" else "EXCEPTION"} $fingerprint suppressed (per-session cap)")
            if (fatal) {
                sessionManager.markCrashed()
                NativeSignalHandler.markExceptionHandled()
            }
            return
        }
        val title = "$type: $message".take(200)
        val props = mapOf<String, Any>(
            "exception_type" to type,
            "exception_message" to "[$platform] $message",
            "stack_trace" to stackTrace.take(16_000),
            "breadcrumbs" to breadcrumbs.toWire(),
            "fatal" to fatal,
            "title" to title,
            "fingerprint" to fingerprint
        )
        val event = UnilitixEvent(
            type = if (fatal) "CRASH" else "EXCEPTION",
            screen = breadcrumbs.lastScreen(),
            properties = props
        )
        if (!fatal) {
            scope.launch { emitEvent(event) }
            return
        }
        sessionManager.markCrashed()
        // A SIGABRT that follows (the bridge aborting) is this crash, not a new one.
        NativeSignalHandler.markExceptionHandled()
        // The caller is about to hand the error to a handler that kills the
        // process: get the report into the on-device queue first, bounded so
        // a stuck disk cannot hold the crash open.
        val persisted = runBlocking {
            withTimeoutOrNull(Unilitix.FATAL_PERSIST_TIMEOUT_MS) {
                emitEvent(event)
                flushNow()
                true
            }
        }
        if (persisted != true) Logger.w("Unilitix: fatal report not persisted within budget")
    }

    internal suspend fun emitEvent(event: UnilitixEvent) {
        if (optedOut) return
        val networkType = networkMonitor.currentType()
        val isOffline = networkType == "OFFLINE"
        val enriched = enrichEvent(event).copy(
            capturedOffline = isOffline,
            networkAtCapture = networkType
        )
        breadcrumbs.add(enriched.type, enriched.screen)
        val session = sessionManager.currentSession
        if (session != null) {
            if (isOffline) session.offlineEventCount++ else session.onlineEventCount++
        }
        eventBuffer.emit(enriched)
    }

    internal suspend fun flushNow(session: Session? = null) {
        // Native crashes of earlier processes are queued first (bounded wait).
        if (!nativeCrashesQueued.isCompleted) {
            withTimeoutOrNull(NATIVE_CRASH_WAIT_MS) { nativeCrashesQueued.await() }
        }
        val events = eventBuffer.drain()
        if (!consent.canCapture) return // denied: drop whatever was buffered
        if (events.isEmpty() && session == null) return

        val activeSession = session ?: sessionManager.currentSession ?: return

        val sessionJson = buildSessionJson(activeSession)
        val eventsJson = Json.toJson(events.map { eventToMap(it) })

        val batchNetwork = networkMonitor.currentType()
        val persisted = queueMutex.withLock {
            // Re-checked under the lock: a denial purges under the same lock.
            if (!consent.canCapture) return@withLock false
            val count = database.eventDao().count()
            if (count >= config.maxOfflineEvents) {
                val overflow = count - config.maxOfflineEvents + 1
                database.eventDao().deleteOldest(overflow)
                Logger.w("Unilitix: event DB overflow, dropped $overflow oldest events")
            }
            database.eventDao().insert(
                PendingEvent(
                    sessionJson = sessionJson,
                    eventsJson = eventsJson,
                    capturedOffline = events.any { it.capturedOffline },
                    networkAtCapture = batchNetwork
                )
            )
            sessionHistory.recordBatch(activeSession.id, activeSession.startedAt, sessionJson, System.currentTimeMillis())
            true
        }
        if (!persisted) return

        // While consent is pending the batch stays queued; releaseHeld()
        // schedules the upload once consent is granted.
        // While a backoff window is open a delayed flush is already queued; after
        // a 401/403 nothing is sent until the next launch. The batch stays queued.
        if (consent.canUpload && !uploadGate.isPaused) flushScheduler.scheduleFlush()
        Logger.d("Unilitix: persisted ${events.size} events for session ${activeSession.id}")
    }

    // ── ConsentEffects ────────────────────────────────────────────────

    override fun startCapture() {
        batteryInfo.startObserving()
    }

    override fun stopCapture() {
        batteryInfo.stopObserving()
    }

    override fun purgeLocalData() {
        breadcrumbs.clear()
        snapshotBuffer.drain()
        flushScheduler.cancelPending()
        scope.launch {
            eventBuffer.drain()
            queueMutex.withLock {
                try {
                    database.eventDao().deleteAll()
                    database.screenshotDao().deleteAll()
                    File(application.cacheDir, "unilitix_crash_pending.json").delete()
                    nativeCrashFile.delete()
                    sessionHistory.clear()
                    Logger.d("Unilitix: consent denied — on-device queue purged")
                } catch (e: Exception) {
                    Logger.w("Unilitix: failed to purge on-device queue: ${e.message}")
                }
            }
        }
    }

    override fun releaseHeld() {
        scope.launch {
            flushNow()
            if (!uploadGate.isPaused) flushScheduler.scheduleFlush()
        }
    }

    private companion object {
        /** Cap on reading a native crash's tombstone (signal_info is near the start). */
        const val MAX_TRACE_BYTES = 256 * 1024
        /** How long a flush waits for earlier native crashes to be queued. */
        const val NATIVE_CRASH_WAIT_MS = 5_000L
    }

    fun shouldSample(): Boolean {
        return config.sampleRate >= 1.0 || Random.nextDouble() < config.sampleRate
    }

    private fun enrichEvent(event: UnilitixEvent): UnilitixEvent {
        val perfProps = mapOf(
            "memory_usage_mb" to performanceMonitor.memoryUsageMb,
            "cpu_usage_pct" to performanceMonitor.cpuUsagePct,
            "frame_drops" to performanceMonitor.frameDropCount
        )
        return event.copy(properties = perfProps + event.properties)
    }

    internal fun buildSessionJson(session: Session?): String {
        if (session == null) return "{}"

        val deviceInfo = collectDeviceInfo(application)
        val networkInfo = collectNetworkInfo(application)
        val battery = batteryInfo.getBatteryLevel().let { if (it >= 0) it.toDouble() else null }

        // Flat structure matching IngestSession in the backend
        val sessionMap = mutableMapOf<String, Any?>(
            "anonymousId"     to identity.anonymousId,
            "userId"          to (identity.userId ?: ""),
            "customUserId"    to (identity.userId ?: ""),
            "deviceType"      to "phone",
            "manufacturer"    to deviceInfo.manufacturer,
            "deviceModel"     to deviceInfo.model,
            "os"              to deviceInfo.os,
            "osVersion"       to deviceInfo.osVersion,
            "screenWidth"     to deviceInfo.screenWidth,
            "screenHeight"    to deviceInfo.screenHeight,
            "screenDensity"   to deviceInfo.screenDensity.toDouble(),
            "appVersion"      to deviceInfo.appVersion,
            "buildNumber"     to deviceInfo.buildNumber,
            "packageName"     to deviceInfo.packageName,
            "sdkVersion"      to deviceInfo.sdkVersion,
            "networkType"     to when (networkInfo.connectionType) {
                "NONE", "UNKNOWN", "ETHERNET", "" -> "OFFLINE"
                else -> networkInfo.connectionType
            },
            "carrierName"     to (networkInfo.carrier ?: ""),
            "orientation"     to deviceInfo.orientation,
            "locale"          to deviceInfo.locale,
            "timezone"        to deviceInfo.timezone,
            "country"         to "",
            "city"            to "",
            "installId"       to deviceInfo.installId,
            "totalStorageGb"  to deviceInfo.totalStorageGb,
            "batteryLevel"    to battery,
            "startedAt"       to isoTimestamp(session.startedAt),
            "endedAt"         to isoTimestamp(System.currentTimeMillis()),
            "durationMs"      to (System.currentTimeMillis() - session.startedAt),
            "foregroundTimeMs" to session.foregroundTimeMs.toInt(),
            "backgroundTimeMs" to session.backgroundTimeMs.toInt(),
            "crashed"         to session.crashed,
            "capturedOffline"    to (session.offlineEventCount > 0),
            "offlineEventCount"  to session.offlineEventCount,
            "onlineEventCount"   to session.onlineEventCount,
            "networkTransitions" to session.networkTransitions,
            "sessionId"    to session.id.toString()
            // syncAttempts / syncFailedBatches are injected by FlushWorker at upload time
        )

        identity.userTraits?.let { sessionMap["sessionData"] = it }

        return Json.toJson(sessionMap)
    }

    private fun eventToMap(event: UnilitixEvent): Map<String, Any?> {
        val knownTypes = setOf("TAP", "NAVIGATE", "RAGE_TAP", "CRASH", "EXCEPTION", "SCROLL")
        val type = if (event.type in knownTypes) event.type else "CUSTOM"

        val props = event.properties
        val map = LinkedHashMap<String, Any?>()
        map["type"]             = type
        map["screen"]           = event.screen ?: ""
        map["x"]                = (props["x"] as? Number)?.toDouble() ?: 0.0
        map["y"]                = (props["y"] as? Number)?.toDouble() ?: 0.0
        map["timestamp"]        = isoTimestamp(event.timestamp)
        map["memoryUsageMb"]    = (props["memory_usage_mb"] as? Number)?.toDouble()
        map["cpuUsagePct"]      = (props["cpu_usage_pct"] as? Number)?.toDouble()
        map["frameDrops"]       = (props["frame_drops"] as? Number)?.toInt()
        map["capturedOffline"]  = event.capturedOffline
        map["networkAtCapture"] = event.networkAtCapture

        if (type == "CRASH" || type == "EXCEPTION") {
            map["stackTrace"]    = props["stack_trace"] as? String ?: ""
            map["exceptionType"] = props["exception_type"] as? String ?: ""
            map["exceptionMessage"] = props["exception_message"] as? String ?: ""
            map["breadcrumbs"]   = props["breadcrumbs"]
            map["fatal"]         = props["fatal"] as? Boolean ?: (type == "CRASH")
            (props["title"] as? String)?.let { map["title"] = it }
            (props["fingerprint"] as? String)?.let {
                map["metadata"] = mapOf("fingerprint" to it)
            }
        }

        // For CUSTOM events, put the original name + remaining props into metadata
        if (type == "CUSTOM") {
            val meta = mutableMapOf<String, Any?>("name" to event.type)
            for ((k, v) in props) {
                if (k !in setOf("x", "y", "memory_usage_mb", "cpu_usage_pct", "frame_drops")) {
                    meta[k] = v
                }
            }
            map["metadata"] = meta
        }

        return map
    }

    private fun isoTimestamp(epochMs: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(epochMs))
    }
}
