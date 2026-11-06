package io.unilitix.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.unilitix.sdk.capture.Anr
import io.unilitix.sdk.capture.AnrTrace
import io.unilitix.sdk.capture.AnrWatchdog
import io.unilitix.sdk.capture.CrashHandler
import io.unilitix.sdk.capture.NativeCrash
import io.unilitix.sdk.capture.NativeCrashFile
import io.unilitix.sdk.capture.NativeCrashRecord
import io.unilitix.sdk.capture.NativeSignalHandler
import io.unilitix.sdk.capture.PerformanceMonitor
import io.unilitix.sdk.capture.PrivacyTags
import io.unilitix.sdk.capture.ProcessExit
import io.unilitix.sdk.capture.ProcessExits
import io.unilitix.sdk.capture.RageTapDetector
import io.unilitix.sdk.capture.ReplayState
import io.unilitix.sdk.capture.ScreenTracker
import io.unilitix.sdk.capture.ScreenshotCapture
import io.unilitix.sdk.capture.SnapshotCapture
import io.unilitix.sdk.capture.TouchTracker
import io.unilitix.sdk.context.BatteryInfo
import io.unilitix.sdk.context.NetworkMonitor
import io.unilitix.sdk.context.isWifi
import io.unilitix.sdk.context.takeLegacyInstallId
import io.unilitix.sdk.core.KeyValueStore
import io.unilitix.sdk.core.SharedPreferencesStore
import io.unilitix.sdk.engine.Engine
import io.unilitix.sdk.engine.EngineConfig
import io.unilitix.sdk.engine.EngineDeps
import io.unilitix.sdk.engine.EngineHooks
import io.unilitix.sdk.engine.EngineRandom
import io.unilitix.sdk.engine.EngineStore
import io.unilitix.sdk.engine.MemoryEngineStore
import io.unilitix.sdk.engine.PriorBatch
import io.unilitix.sdk.engine.StartInputs
import io.unilitix.sdk.engine.UnilitixCore
import io.unilitix.sdk.flush.FlushScheduler
import io.unilitix.sdk.network.ApiClient
import io.unilitix.sdk.platform.AndroidClock
import io.unilitix.sdk.platform.AndroidDeviceSource
import io.unilitix.sdk.platform.ReplayPipeline
import io.unilitix.sdk.platform.ThreadEngineExecutor
import io.unilitix.sdk.platform.ThreadEngineScheduler
import io.unilitix.sdk.storage.EventDatabase
import io.unilitix.sdk.storage.RoomEngineStore
import io.unilitix.sdk.util.Logger
import java.security.MessageDigest
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The Android shell around the [Engine]: capture components (taps, screens,
 * replay, crashes, ANRs, performance), storage, network and lifecycle.
 *
 * [startOnMainThread] does only what must happen on the main thread or
 * immediately (the JVM crash handler, lifecycle registrations); everything
 * that touches disk, the key store or native code runs on a background
 * thread ([startInBackground]), after which the engine starts and the
 * buffered calls are replayed.
 */
internal class UnilitixInternal(
    private val application: Application,
    private val apiKey: String,
    internal val config: UnilitixConfig,
    private val initialConsent: UnilitixConsent,
    private val core: UnilitixCore,
) {
    internal val apiClient = ApiClient(config.apiUrl, apiKey).also { it.wrapper = Unilitix.wrapperLabel }
    private val networkMonitor = NetworkMonitor(application)
    private val performanceMonitor = PerformanceMonitor()
    private val batteryInfo = BatteryInfo(application)
    private val flushScheduler = FlushScheduler(application)

    internal val replayState = ReplayState(
        maxScreenshotsPerSession = config.maxScreenshotsPerSession,
        maxSnapshotsPerSession = config.maxSnapshotsPerSession,
        maxScreenshotsPerMinute = config.maxScreenshotsPerMinute,
    ).also { it.externalCapture = Unilitix.externalCaptureRequested }

    @Volatile private var replay: ReplayPipeline? = null
    @Volatile private var engineRef: Engine? = null
    private var crashHandler: CrashHandler? = null
    private var screenshotCapture: ScreenshotCapture? = null
    private var anrWatchdog: AnrWatchdog? = null

    /** Activity callbacks the SDK registered, so a late init can attach them to the current Activity. */
    private val lifecycleCallbacks = mutableListOf<Application.ActivityLifecycleCallbacks>()

    /** CPU sampling every 5 s while the app is in the foreground (1.11.0: paused in the background). */
    private val perfTimer = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "unilitix-perf").also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY }
    }.apply { removeOnCancelPolicy = true }
    private var cpuSampling: ScheduledFuture<*>? = null

    /** See [Unilitix.nativeCrashCaptureActive]. */
    @Volatile
    internal var nativeCrashCaptureActive: Boolean = false
        private set

    private val nativeCrashFile by lazy { NativeCrashFile.locate(application.filesDir) }

    private fun engine(): Engine? = engineRef

    private fun replayAllowed(): Boolean {
        val e = engine() ?: return false
        return e.consentState() != UnilitixConsent.DENIED && e.isReplaySampled()
    }

    private fun registerCallbacks(cb: Application.ActivityLifecycleCallbacks) {
        lifecycleCallbacks += cb
        application.registerActivityLifecycleCallbacks(cb)
    }

    /**
     * Replays created/started/resumed for an Activity that already existed when
     * the SDK started (React Native and other late inits).
     */
    internal fun attachToActivity(activity: Activity) {
        if (activity.isFinishing) return
        for (cb in lifecycleCallbacks.toList()) {
            try {
                cb.onActivityCreated(activity, null)
                cb.onActivityStarted(activity)
                cb.onActivityResumed(activity)
            } catch (t: Throwable) {
                Logger.e("Unilitix: attach to current activity failed: ${t.message}")
            }
        }
    }

    internal fun setWrapperLabel(label: String) {
        apiClient.wrapper = label
    }

    // ── Start ───────────────────────────────────────────────────────────

    /** Main thread: crash handler (synchronous), lifecycle and capture registrations, then the background start. */
    fun startOnMainThread() {
        if (config.autoTrackCrashes) {
            crashHandler = CrashHandler(
                context = application,
                core = core,
                isEnabled = { (engine()?.consentState() ?: initialConsent) != UnilitixConsent.DENIED },
            ).also { it.install() }
        }

        if (config.autoTrackScreens) {
            registerCallbacks(ScreenTracker { name -> core.screen(name) })
        }

        if (config.autoTrackTaps) {
            val rage = if (config.autoTrackRageTaps) {
                RageTapDetector { screen, cx, cy ->
                    core.recordInteraction("RAGE_TAP", linkedMapOf(
                        "screen" to screen, "x" to cx.roundToInt().toDouble(), "y" to cy.roundToInt().toDouble(),
                    ))
                }
            } else null
            registerCallbacks(
                TouchTracker(maskInputs = config.maskInputs, maskedViewIds = config.maskedViewIds) { screen, x, y, viewId ->
                    val isRage = rage?.recordTap(x.toFloat(), y.toFloat(), screen) ?: false
                    if (!isRage) {
                        core.recordInteraction("TAP", linkedMapOf(
                            "screen" to screen, "x" to x.toDouble(), "y" to y.toDouble(),
                            "metadata" to linkedMapOf("view" to viewId),
                        ))
                    }
                    true
                }
            )
        }

        // @UnilitixPrivate fields of Activities and Fragments.
        registerCallbacks(PrivateFieldTracker())

        if (config.captureSnapshots) {
            registerCallbacks(
                SnapshotCapture(
                    config,
                    isEnabled = { replayAllowed() },
                    sessionId = { engine()?.currentSessionId() },
                    replayState = replayState,
                ) { sessionId, snapshot -> replay?.addSnapshot(sessionId, snapshot) }
            )
        }

        if (config.captureScreenshots) {
            screenshotCapture = ScreenshotCapture(
                config,
                isEnabled = { replayAllowed() },
                sessionId = { engine()?.currentSessionId() },
                replayState = replayState,
            ) { sessionId, shot -> replay?.addScreenshot(sessionId, shot) }.also { registerCallbacks(it) }
        }

        // Frame-drop tracking runs only while an activity is resumed (1.10.2).
        registerCallbacks(performanceMonitor)

        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycle)

        val thread = Thread({ startInBackground() }, "unilitix-init")
        thread.isDaemon = true
        thread.start()
    }

    /** Background thread: storage, key store, native library, earlier crashes; then the engine. */
    private fun startInBackground() {
        val started = SystemClock.elapsedRealtime()
        try {
            val prefs = application.getSharedPreferences("unilitix_prefs", Context.MODE_PRIVATE)
            val prefsKv = SharedPreferencesStore(prefs)
            val deviceKv = SharedPreferencesStore(application.getSharedPreferences("unilitix_device", Context.MODE_PRIVATE))
            val identityKv = SharedPreferencesStore(identityPrefs())
            migrateLegacyIds(deviceKv, identityKv)

            val db: EventDatabase? = try {
                EventDatabase.getInstance(application).also { it.openHelper.writableDatabase }
            } catch (t: Throwable) {
                Logger.w("Unilitix: event database unavailable (${t.javaClass.simpleName}); events are kept in memory for this launch")
                null
            }
            val roomStore = db?.let { RoomEngineStore(it, prefsKv, deviceKv, identityKv) }
            val store: EngineStore = roomStore ?: MemoryEngineStore()
            val prior = ArrayList<PriorBatch>()
            roomStore?.drainLegacyBatches()?.let { prior.addAll(it) }
            CrashHandler.recoverPending(application)?.let { prior.add(it) }

            // Native crash of the last process (API 21-29), read before this
            // process's handler can write a new one.
            val nativeCrashes = ArrayList<NativeCrash>()
            NativeCrashFile.consume(nativeCrashFile)?.let { text ->
                NativeCrashRecord.parse(text)?.let { nativeCrashes.add(it) }
                    ?: Logger.w("Unilitix: native crash record could not be read; dropped")
            }
            var anrs = emptyList<Anr>()
            if (Build.VERSION.SDK_INT >= 30) {
                // Always scanned, so the watermark moves even when nothing is reported.
                val scan = ProcessExits.scan(prefsKv, readProcessExits(), System.currentTimeMillis(), watchdogPids(prefsKv))
                if (config.autoTrackCrashes) {
                    nativeCrashes += scan.crashes
                    anrs = scan.anrs
                }
            }
            if (config.autoTrackCrashes) {
                val installed = NativeSignalHandler.install(nativeCrashFile)
                nativeCrashCaptureActive = installed || Build.VERSION.SDK_INT > NativeSignalHandler.LAST_API_LEVEL
            }

            replay = db?.let { ReplayPipeline(config, it, apiClient, { engineRef }, { isWifi(application) }) }

            val engineConfig = EngineConfig(
                flushIntervalMs = config.flushIntervalSeconds * 1000L,
                flushBatchSize = config.flushBatchSize,
                maxOfflineEvents = config.maxOfflineEvents,
                sessionTimeoutMs = config.sessionTimeoutSeconds * 1000L,
                replaySampleRate = config.sampleRate,
                replayCapture = config.captureSnapshots || config.captureScreenshots,
                initialConsent = initialConsent,
            )
            val deps = EngineDeps(
                clock = AndroidClock,
                scheduler = ThreadEngineScheduler(),
                executor = ThreadEngineExecutor(),
                transport = apiClient,
                network = networkMonitor,
                device = AndroidDeviceSource(application, performanceMonitor, batteryInfo),
                random = EngineRandom.Default,
                store = store,
                hooks = hooks,
            )
            val engine = Engine(engineConfig, deps)
            engineRef = engine
            core.start(engine, StartInputs(nativeCrashes, anrs, prior))

            networkMonitor.observe { core.onNetworkChanged() }
            if (config.anrWatchdogTimeoutMs > 0 && config.autoTrackCrashes) {
                anrWatchdog = AnrWatchdog(
                    timeoutMs = config.anrWatchdogTimeoutMs,
                    onAnr = { message, stack -> engine.recordAnr(message, stack, linkedMapOf("source" to "watchdog", "foreground" to true)) },
                    onReported = { rememberWatchdogPid(prefsKv) },
                )
                if (foreground) anrWatchdog?.start()
            }
            Logger.d("Unilitix: started in ${SystemClock.elapsedRealtime() - started} ms (off the main thread)")
        } catch (t: Throwable) {
            Logger.w("Unilitix: SDK could not start (${t.javaClass.simpleName}); analytics disabled for this launch")
            Logger.d("Unilitix: start failure: ${t.stackTraceToString().take(4_000)}")
        }
    }

    /** user_id and pending identify requests are personal data: encrypted at rest. */
    private fun identityPrefs(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(application).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            application,
            "unilitix_identity_enc",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        // A broken or unavailable Keystore (or keys lost in a backup restore).
        Logger.w("Unilitix: encrypted storage unavailable (${t.javaClass.simpleName}); using private preferences")
        application.getSharedPreferences("unilitix_identity", Context.MODE_PRIVATE)
    }

    /**
     * 1.10 → 1.11 moves: the install id into the device store, the plain-text
     * user id of very old installs into the encrypted store. Then the restore
     * check: Auto Backup may copy the SDK's preferences to a new device; the
     * ids are tied to a hash of this device's ANDROID_ID (per app and device)
     * and replaced when it differs, so two devices never share an anonymous
     * id, an install id or a user id.
     */
    private fun migrateLegacyIds(device: KeyValueStore, identity: KeyValueStore) {
        try {
            if (device.getString(Engine.KEY_INSTALL_ID).isNullOrEmpty()) {
                takeLegacyInstallId(application)?.let { device.putString(Engine.KEY_INSTALL_ID, it) }
            }
            val legacy = application.getSharedPreferences("unilitix_identity", Context.MODE_PRIVATE)
            legacy.getString(Engine.KEY_USER_ID, null)?.let { uid ->
                if (identity.getString(Engine.KEY_USER_ID).isNullOrEmpty()) identity.putString(Engine.KEY_USER_ID, uid)
                legacy.edit().remove(Engine.KEY_USER_ID).apply()
            }
            val fingerprint = deviceFingerprint() ?: return
            val stored = device.getString(KEY_DEVICE_FINGERPRINT)
            if (stored != null && stored.isNotEmpty() && stored != fingerprint) {
                device.remove(Engine.KEY_ANONYMOUS_ID)
                device.remove(Engine.KEY_INSTALL_ID)
                identity.remove(Engine.KEY_USER_ID)
                identity.remove(Engine.KEY_PENDING_IDENTIFIES)
                Logger.d("Unilitix: restored from a backup of another device; ids reset")
            }
            device.putString(KEY_DEVICE_FINGERPRINT, fingerprint)
        } catch (t: Throwable) {
            Logger.d("Unilitix: id migration skipped: ${t.message}")
        }
    }

    private fun deviceFingerprint(): String? {
        return try {
            val androidId = Settings.Secure.getString(application.contentResolver, Settings.Secure.ANDROID_ID)
                ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest("unilitix:$androidId".toByteArray())
            digest.take(8).joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun watchdogPids(kv: KeyValueStore): Set<Int> =
        kv.getString(KEY_WATCHDOG_PIDS)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()

    private fun rememberWatchdogPid(kv: KeyValueStore) {
        val pids = (watchdogPids(kv) + Process.myPid()).toList().takeLast(8)
        kv.putString(KEY_WATCHDOG_PIDS, pids.joinToString(","))
    }

    @androidx.annotation.RequiresApi(30)
    private fun readProcessExits(): List<ProcessExit> = try {
        val am = application.getSystemService(android.app.ActivityManager::class.java)
        am?.getHistoricalProcessExitReasons(application.packageName, 0, ProcessExits.MAX_QUERIED)?.map { info ->
            ProcessExit(
                reason = info.reason,
                timestampMs = info.timestamp,
                pid = info.pid,
                description = info.description,
                importance = info.importance,
                readTrace = {
                    // Native crashes carry the tombstone (protobuf) from API 31;
                    // ANRs the text thread dump from API 30.
                    when (info.reason) {
                        ProcessExits.REASON_CRASH_NATIVE -> info.traceInputStream?.use { readPrefix(it, MAX_TRACE_BYTES) }
                        ProcessExits.REASON_ANR -> info.traceInputStream?.use { readPrefix(it, AnrTrace.MAX_TRACE_BYTES) }
                        else -> null
                    }
                }
            )
        } ?: emptyList()
    } catch (e: Exception) {
        Logger.w("Unilitix: could not read process exit reasons: ${e.message}")
        emptyList()
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

    // ── Engine hooks (engine thread) ────────────────────────────────────

    private val hooks = object : EngineHooks {
        override fun onPurge() {
            replay?.purge()
            try { CrashHandler.pendingFile(application).delete() } catch (_: Throwable) {}
            try { nativeCrashFile.delete() } catch (_: Throwable) {}
        }

        override fun onCaptureAllowed(allowed: Boolean) {
            if (allowed && foreground) batteryInfo.startObserving() else batteryInfo.stopObserving()
        }

        override fun onSessionStart(sessionId: String, replaySampled: Boolean) {
            // frame_drops counts this session's janky frames, not the process's.
            performanceMonitor.resetFrameDrops()
        }

        override fun onUploadCycleFinished() {
            replay?.scheduleUpload()
        }
    }

    // ── Process lifecycle ───────────────────────────────────────────────

    @Volatile private var foreground = false

    private val appLifecycle = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            foreground = true
            core.onForeground()
            if (engine()?.consentState() != UnilitixConsent.DENIED) batteryInfo.startObserving()
            startCpuSampling()
            anrWatchdog?.start()
        }

        override fun onStop(owner: LifecycleOwner) {
            foreground = false
            core.onBackground()
            batteryInfo.stopObserving()
            stopCpuSampling()
            anrWatchdog?.stop()
            // Finishes the upload if the process is kept alive or restarted, with
            // a network constraint (WorkManager); the engine uploads right away too.
            try { flushScheduler.scheduleFlush() } catch (t: Throwable) { Logger.d("Unilitix: background flush not scheduled: ${t.message}") }
        }
    }

    private fun startCpuSampling() {
        if (cpuSampling != null) return
        cpuSampling = try {
            perfTimer.scheduleAtFixedRate({
                try { performanceMonitor.sampleCpu() } catch (_: Throwable) {}
            }, CPU_SAMPLE_SECONDS, CPU_SAMPLE_SECONDS, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            null
        }
    }

    private fun stopCpuSampling() {
        cpuSampling?.cancel(false)
        cpuSampling = null
    }

    /** Marks `@UnilitixPrivate` fields of Activities and their Fragments. */
    private inner class PrivateFieldTracker : Application.ActivityLifecycleCallbacks {
        private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: android.view.View, savedInstanceState: Bundle?) {
                PrivacyTags.scanFragment(f)
            }

            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                PrivacyTags.scanFragment(f)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            try {
                (activity as? FragmentActivity)?.supportFragmentManager?.registerFragmentLifecycleCallbacks(fragments, true)
            } catch (_: Throwable) {}
        }

        override fun onActivityResumed(activity: Activity) = PrivacyTags.scanActivity(activity)
        override fun onActivityDestroyed(activity: Activity) {
            try {
                (activity as? FragmentActivity)?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
            } catch (_: Throwable) {}
        }
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    // ── Wrapper replay input ────────────────────────────────────────────

    fun addExternalScreenshot(image: ByteArray, width: Int, height: Int, timestampMs: Long, screen: String?) {
        if (!config.captureScreenshots || image.isEmpty() || image.size > MAX_EXTERNAL_FRAME_BYTES) return
        if (!replayAllowed()) return
        val sessionId = engine()?.currentSessionId() ?: return
        if (!replayState.admitFrame(sessionId, SystemClock.elapsedRealtime())) return
        val ordinal = replayState.nextScreenshotOrdinal(sessionId) ?: return
        val shot = Unilitix.externalScreenshotOf(image, width, height, timestampMs, screen, ordinal)
        replay?.addScreenshot(sessionId, shot, Unilitix.EXTERNAL_FORMAT)
    }

    fun addExternalSnapshot(json: String) {
        if (!config.captureSnapshots || json.length > MAX_EXTERNAL_SNAPSHOT_CHARS) return
        if (!replayAllowed()) return
        val sessionId = engine()?.currentSessionId() ?: return
        val element = Unilitix.parseExternalSnapshot(json) ?: run {
            Logger.w("Unilitix.addExternalSnapshot: not a snapshot object (needs a hierarchy); dropped")
            return
        }
        val ordinal = replayState.nextSnapshotOrdinal(sessionId) ?: return
        // capturedAt: RFC 3339 string, or ms since 1970.
        val at = element.opt("capturedAt")
        if (at is Number) element.put("capturedAt", io.unilitix.sdk.engine.IsoTime.format(at.toLong()))
        else if (at !is String) element.put("capturedAt", io.unilitix.sdk.engine.IsoTime.format(System.currentTimeMillis()))
        replay?.addSnapshotJson(sessionId, ordinal, element)
    }

    // ── Background flush (FlushWorker) ──────────────────────────────────

    /** Uploads queued events and replay media, waiting at most [timeoutMs]. Worker thread. */
    fun flushForWorker(timeoutMs: Long) {
        engine()?.flushAndWait(timeoutMs)
        replay?.scheduleUpload()
    }

    /**
     * Undoes what [startOnMainThread] registered, after it failed part way
     * (see GuardedInit). Best effort: each step is independent and none throws.
     */
    internal fun shutdown() {
        fun step(name: String, block: () -> Unit) {
            try { block() } catch (t: Throwable) { Logger.d("Unilitix: shutdown step $name failed: ${t.message}") }
        }
        step("activity callbacks") {
            for (cb in lifecycleCallbacks.toList()) {
                try { application.unregisterActivityLifecycleCallbacks(cb) } catch (_: Throwable) {}
            }
            lifecycleCallbacks.clear()
        }
        step("screenshots") { screenshotCapture?.stop() }
        step("performance monitor") { performanceMonitor.stop() }
        step("perf timer") { perfTimer.shutdownNow() }
        step("network callback") { networkMonitor.stopObserving() }
        step("battery receiver") { batteryInfo.stopObserving() }
        step("anr watchdog") { anrWatchdog?.stop() }
        step("lifecycle observer") { ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycle) }
        step("crash handler") { crashHandler?.uninstall() }
        crashHandler = null
        step("replay") { replay?.shutdown() }
        step("engine") { engineRef?.shutdown() }
    }

    private companion object {
        /** Cap on reading a native crash's tombstone (signal and threads come first). */
        const val MAX_TRACE_BYTES = 1024 * 1024
        const val CPU_SAMPLE_SECONDS = 5L
        const val KEY_DEVICE_FINGERPRINT = "device_fingerprint"
        const val KEY_WATCHDOG_PIDS = "anr_watchdog_pids"
        /** The API keeps frames up to 512 KB by default. */
        const val MAX_EXTERNAL_FRAME_BYTES = 512 * 1024
        const val MAX_EXTERNAL_SNAPSHOT_CHARS = 2 * 1024 * 1024
    }
}
