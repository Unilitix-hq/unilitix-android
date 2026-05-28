package io.unilitix.sdk

import android.app.Application
import io.unilitix.sdk.BuildConfig
import io.unilitix.sdk.capture.CrashHandler
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
import io.unilitix.sdk.core.EventBuffer
import io.unilitix.sdk.core.Identity
import io.unilitix.sdk.core.Session
import io.unilitix.sdk.core.SessionManager
import io.unilitix.sdk.core.SnapshotBuffer
import io.unilitix.sdk.core.UnilitixEvent
import io.unilitix.sdk.flush.FlushScheduler
import io.unilitix.sdk.network.ApiClient
import io.unilitix.sdk.storage.EventDatabase
import io.unilitix.sdk.storage.PendingEvent
import io.unilitix.sdk.storage.PendingScreenshot
import io.unilitix.sdk.util.Json
import java.io.File
import io.unilitix.sdk.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object Unilitix {

    @Volatile
    internal var instance: UnilitixInternal? = null

    @JvmStatic
    fun init(application: Application, apiKey: String, configure: UnilitixConfig.Builder.() -> Unit = {}) {
        if (instance != null) {
            Logger.w("Unilitix.init() called more than once — ignoring")
            return
        }
        val config = UnilitixConfig.Builder().apply(configure).build()
        // In release builds BuildConfig.DEBUG is false, so this assignment is a no-op and
        // debug logs can never be enabled regardless of what the host app configures.
        if (BuildConfig.DEBUG) Logger.debugEnabled = config.debugLogging
        if (config.sessionTimeoutSeconds < 60) {
            Logger.w("Unilitix: sessionTimeoutSeconds=${config.sessionTimeoutSeconds} is very low — consider 1800 (30 min) to avoid session fragmentation")
        }

        val internal = UnilitixInternal(application, apiKey, config)
        instance = internal
        internal.start()
        Logger.d("✅ Unilitix SDK initialized | key=${apiKey.take(8)}... | session=${internal.sessionManager.currentSession?.id?.take(8)} | v${BuildConfig.SDK_VERSION}")
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

    @JvmStatic
    fun optOut() {
        val internal = instance ?: return
        internal.optedOut = true
        internal.persistOptOut(true)
        internal.batteryInfo.stopObserving()
        Logger.d("Unilitix: opted out (persisted)")
    }

    @JvmStatic
    fun optIn() {
        val internal = instance ?: return
        internal.optedOut = false
        internal.persistOptOut(false)
        internal.batteryInfo.startObserving()
        Logger.d("Unilitix: opted in (persisted)")
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
    internal val config: UnilitixConfig
) {
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

    @Volatile internal var optedOut = prefs.getBoolean("opt_out", false)

    internal fun persistOptOut(value: Boolean) {
        prefs.edit().putBoolean("opt_out", value).apply()
    }

    internal val snapshotBuffer = SnapshotBuffer(config.maxSnapshotsPerSession)
    private var snapshotCapture: SnapshotCapture? = null
    private var screenshotCapture: ScreenshotCapture? = null

    internal val sessionManager = SessionManager(
        sessionTimeoutSeconds = config.sessionTimeoutSeconds,
        onSessionStart = { session ->
            Logger.d("✅ Session started | id=${session.id}")
            screenshotCapture?.resetOrdinal()
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
                RageTapDetector { screen, x, y ->
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
                    val isRage = rageTapDetector?.recordTap(x, y, screen) ?: false
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

        if (config.autoTrackCrashes) {
            CrashHandler(
                context = application,
                breadcrumbs = breadcrumbs,
                database = database,
                getCurrentSessionJson = { buildSessionJson(sessionManager.currentSession) },
                onCrash = { sessionManager.markCrashed() }
            ).install()
        }

        recoverPendingCrash()

        if (config.captureSnapshots) {
            snapshotCapture = SnapshotCapture(config) { snapshot ->
                snapshotBuffer.add(snapshot)
            }
            application.registerActivityLifecycleCallbacks(snapshotCapture!!)
        }

        if (config.captureScreenshots) {
            screenshotCapture = ScreenshotCapture(config) { screenshot ->
                scope.launch {
                    val sessionId = sessionManager.currentSession?.id?.toString() ?: return@launch
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
                    val count = database.screenshotDao().count()
                    if (count > config.maxScreenshotsPerSession) {
                        database.screenshotDao().deleteOldest(count - config.maxScreenshotsPerSession)
                    }
                }
            }
            application.registerActivityLifecycleCallbacks(screenshotCapture!!)
        }

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
        val events = eventBuffer.drain()
        if (events.isEmpty() && session == null) return

        val activeSession = session ?: sessionManager.currentSession ?: return

        val sessionJson = buildSessionJson(activeSession)
        val eventsJson = Json.toJson(events.map { eventToMap(it) })

        val count = database.eventDao().count()
        if (count >= config.maxOfflineEvents) {
            val overflow = count - config.maxOfflineEvents + 1
            database.eventDao().deleteOldest(overflow)
            Logger.w("Unilitix: event DB overflow, dropped $overflow oldest events")
        }

        val batchNetwork = networkMonitor.currentType()
        database.eventDao().insert(
            PendingEvent(
                sessionJson = sessionJson,
                eventsJson = eventsJson,
                capturedOffline = events.any { it.capturedOffline },
                networkAtCapture = batchNetwork
            )
        )

        flushScheduler.scheduleFlush()
        Logger.d("Unilitix: persisted ${events.size} events for session ${activeSession.id}")
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
        val knownTypes = setOf("TAP", "NAVIGATE", "RAGE_TAP", "CRASH", "SCROLL")
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

        if (type == "CRASH") {
            map["stackTrace"]    = props["stack_trace"] as? String ?: ""
            map["exceptionType"] = props["exception_type"] as? String ?: ""
            map["exceptionMessage"] = props["exception_message"] as? String ?: ""
            map["breadcrumbs"]   = props["breadcrumbs"]
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
