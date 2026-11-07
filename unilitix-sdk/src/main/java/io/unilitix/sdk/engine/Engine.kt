package io.unilitix.sdk.engine

import io.unilitix.sdk.UnilitixConsent
import io.unilitix.sdk.capture.Anr
import io.unilitix.sdk.capture.AnrTrace
import io.unilitix.sdk.capture.NativeCrash
import io.unilitix.sdk.capture.NativeCrashRecord
import io.unilitix.sdk.core.Breadcrumbs
import io.unilitix.sdk.core.ConsentController
import io.unilitix.sdk.core.ConsentEffects
import io.unilitix.sdk.core.ConsentStore
import io.unilitix.sdk.core.ErrorReportLimiter
import io.unilitix.sdk.core.Fingerprint
import io.unilitix.sdk.network.ApiResult
import io.unilitix.sdk.network.UploadGate
import io.unilitix.sdk.network.UploadOutcome
import io.unilitix.sdk.util.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The options the engine reads (canonical contract names; Android maps its config onto them). */
internal data class EngineConfig(
    val flushIntervalMs: Long = 30_000L,
    val flushBatchSize: Int = 100,
    val maxOfflineEvents: Int = 1000,
    val sessionTimeoutMs: Long = 1_800_000L,
    /** Contract `replaySampleRate` (Android `sampleRate`). */
    val replaySampleRate: Double = 1.0,
    /** `captureSnapshots || captureScreenshots`: a sampling roll is made per session. */
    val replayCapture: Boolean = true,
    val initialConsent: UnilitixConsent = UnilitixConsent.GRANTED,
)

/** An error reported through the public API (app code, Dart, JS). */
internal class ErrorReport(
    val type: String,
    val message: String,
    val stackTrace: String?,
    val fatal: Boolean,
    val platform: String? = null,
    val fingerprint: String? = null,
    val metadata: Map<String, Any?>? = null,
    /** Preset eventId (the crash handler's disk copy carries the same one). */
    val eventId: String? = null,
)

/**
 * A batch left on the device by an earlier process: a JVM crash the crash
 * handler could only write to its fallback file, or a batch queued by SDK
 * 1.10 or older. [sessionFields] is that SDK's session record when it had one.
 */
internal class PriorBatch(
    val sessionId: String?,
    val sessionFields: Map<String, Any?>?,
    val events: List<Map<String, Any?>>,
)

/** What the platform found at launch about earlier processes. */
internal class StartInputs(
    val nativeCrashes: List<NativeCrash> = emptyList(),
    val anrs: List<Anr> = emptyList(),
    val priorBatches: List<PriorBatch> = emptyList(),
)

/**
 * The platform-independent core of the Android SDK: sessions, identity,
 * consent, the event queue and uploads, implementing the shared SDK contract
 * (1.4.0) and its behaviour fixtures. Pure Kotlin: the fixture runner drives
 * it on the JVM with fake [EngineDeps].
 *
 * Threading: every public method may be called from any thread; it hands its
 * work to [EngineDeps.executor] (one thread, in call order). Only
 * [currentSessionId], [isReplaySampled], [consentState] and [uploadsPaused]
 * read state directly (volatile).
 */
internal class Engine(
    val config: EngineConfig,
    private val deps: EngineDeps,
) : ConsentEffects {

    private val store = deps.store
    private val clock = deps.clock
    private val executor = deps.executor

    internal val gate = UploadGate(clock = { clock.monotonicMs() }, random = deps.random.jitter)
    internal val breadcrumbs = Breadcrumbs()
    private val limiter = ErrorReportLimiter()
    internal val consent = ConsentController(ConsentStore(store.prefs), config.initialConsent, this)

    // ── Engine-thread state ─────────────────────────────────────────────
    private var started = false
    private var anonymousId: String = ""
    private var userId: String? = null
    private var session: SessionRecord? = null
    private var seq = 0
    private var screen: String? = null
    private var foregroundSinceMono: Long = 0
    private var backgroundAtMono: Long? = null
    private var lastNetworkType: String? = null
    private var pendingIdentifies: MutableList<String> = mutableListOf()
    private var wrapper: String? = null
    private var wrapperVersion: String? = null
    private var inFlight = false
    private var uploadAgain = false
    private val idleWaiters = ArrayList<() -> Unit>()
    private var flushTimer: Cancellable? = null

    // ── Cross-thread reads ──────────────────────────────────────────────
    @Volatile private var sessionIdV: String? = null
    @Volatile private var sampledV: Boolean = false
    @Volatile private var screenV: String? = null
    @Volatile private var userIdV: String? = null
    @Volatile private var anonymousIdV: String? = null

    fun currentSessionId(): String? = sessionIdV
    fun isReplaySampled(): Boolean = sampledV
    fun consentState(): UnilitixConsent = consent.state
    fun uploadsPaused(): Boolean = gate.authRejected
    fun currentScreen(): String? = screenV
    fun currentUserId(): String? = userIdV
    fun currentAnonymousId(): String? = anonymousIdV
    /** Events on the device not yet acknowledged (contract `queueLength`). */
    fun queueLength(): Int = store.eventCount()

    // ── Public API (any thread) ─────────────────────────────────────────

    fun start(inputs: StartInputs = StartInputs()) = run("start") { doStart(inputs) }
    fun track(name: String, properties: Map<*, *>?) = run("track") { doTrack(name, properties) }
    fun screen(name: String) = run("screen") { doScreen(name) }
    fun identify(userId: String, traits: Map<*, *>?) = run("identify") { doIdentify(userId, traits) }
    fun reset() = run("reset") { doReset() }
    fun setConsent(value: UnilitixConsent) = run("setConsent") { consent.set(value) }
    fun flush() = run("flush") { upload() }
    fun onBackground() = run("background") { doBackground() }
    fun onForeground() = run("foreground") { doForeground() }
    fun onNetworkChanged() = run("network") { doNetworkChanged() }
    fun startNewSession() = run("startSession") { endSession(); startSession() }
    fun endCurrentSession() = run("endSession") { endSession() }
    fun addBreadcrumb(message: String, category: String) = run("breadcrumb") {
        if (consent.canCapture) breadcrumbs.add(category, screen, Normalize.string(message))
    }
    fun setWrapper(name: String, version: String) = run("wrapper") {
        wrapper = Normalize.string(name, 64)
        wrapperVersion = Normalize.string(version, 64)
        session?.let { it.wrapper = wrapper; it.wrapperVersion = wrapperVersion; persist(it) }
    }

    /** Records [report]. A fatal one marks the session crashed. */
    fun recordError(report: ErrorReport) = run("error") { doRecordError(report); if (report.fatal) upload() }

    /**
     * A fatal error about to end the process: records it, starts an upload,
     * and waits until both are done or [budgetMs] has passed (contract
     * `fatalFlushBudgetMs`). Returns true when the report was persisted.
     */
    fun recordFatalAndWait(report: ErrorReport, budgetMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        val persisted = CountDownLatch(1)
        val idle = CountDownLatch(1)
        run("fatal") {
            try { doRecordError(report) } finally { persisted.countDown() }
            upload()
            whenIdle { idle.countDown() }
        }
        if (executor.isEngineThread()) return false
        val ok = awaitLatch(persisted, deadline)
        if (ok) awaitLatch(idle, deadline)
        return ok
    }

    /** An interaction from the platform capture (TAP, RAGE_TAP, SCROLL…). */
    fun recordInteraction(type: String, fields: Map<String, Any?>) = run(type) {
        if (consent.canCapture) emit(type, fields)
    }

    /** A live ANR (main-thread watchdog). Counted against the error caps like any issue. */
    fun recordAnr(message: String, stackTrace: String, metadata: Map<String, Any?>) = run("anr") {
        val fp = Fingerprint.of(AnrTrace.TYPE, stackTrace)
        if (limiter.admit(fp) == null) return@run
        val meta = LinkedHashMap<String, Any?>(metadata)
        meta["fingerprint"] = fp
        meta["platform"] = AnrTrace.PLATFORM
        emit(AnrTrace.TYPE, linkedMapOf(
            "exceptionType" to AnrTrace.TYPE,
            "exceptionMessage" to message,
            "stackTrace" to stackTrace.take(AnrTrace.MAX_STACK),
            "breadcrumbs" to breadcrumbs.toWire(),
            "metadata" to meta,
        ))
        upload()
    }

    /** Waits until queued work and any in-flight upload are done, at most [timeoutMs]. */
    fun awaitIdle(timeoutMs: Long): Boolean {
        if (executor.isEngineThread()) return false
        val latch = CountDownLatch(1)
        run("await") { whenIdle { latch.countDown() } }
        return awaitLatch(latch, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs))
    }

    /** Uploads now (if allowed) and waits for the cycle, at most [timeoutMs]. */
    fun flushAndWait(timeoutMs: Long): Boolean {
        if (executor.isEngineThread()) return false
        val latch = CountDownLatch(1)
        run("flushWait") { upload(); whenIdle { latch.countDown() } }
        return awaitLatch(latch, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs))
    }

    /** True when replay media may be uploaded now (consent granted, not paused or backing off, online). */
    fun mediaUploadAllowed(): Boolean =
        consent.canUpload && !gate.authRejected && !gate.inBackoff && deps.network.current().reachable

    /** Whether the server has acknowledged a batch of [sessionId] (replay media needs the session to exist). */
    fun isSessionAcknowledged(sessionId: String): Boolean =
        store.session(sessionId)?.let { SessionRecord.fromJson(it)?.acknowledged } ?: false

    fun shutdown() {
        flushTimer?.cancel()
        flushTimer = null
    }

    // ── Engine thread ───────────────────────────────────────────────────

    private fun run(what: String, block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    // Never into the host: a storage failure costs this call only.
                    Logger.e("Unilitix: $what failed: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Logger.w("Unilitix: $what not scheduled: ${t.javaClass.simpleName}")
        }
    }

    private fun awaitLatch(latch: CountDownLatch, deadlineNanos: Long): Boolean {
        val left = deadlineNanos - System.nanoTime()
        return try {
            left > 0 && latch.await(left, TimeUnit.NANOSECONDS) || latch.count == 0L
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun whenIdle(callback: () -> Unit) {
        if (!inFlight) callback() else idleWaiters.add(callback)
    }

    private fun doStart(inputs: StartInputs) {
        if (started) return
        anonymousId = store.device.getString(KEY_ANONYMOUS_ID) ?: newAnonymousId()
        anonymousIdV = anonymousId
        userId = store.identity.getString(KEY_USER_ID)
        userIdV = userId
        pendingIdentifies = loadIdentifies()
        started = true

        val dropped = store.unparkEvents(MAX_PARK_CYCLES)
        if (dropped > 0) Logger.w("Unilitix: $dropped events dropped after failing in $MAX_PARK_CYCLES launches")

        var reported = false
        if (consent.canCapture) {
            for (b in inputs.priorBatches) reported = queuePriorBatch(b) || reported
            for (c in inputs.nativeCrashes.sortedBy { it.timestampMs ?: Long.MAX_VALUE }) reported = queueNativeCrash(c) || reported
            for (a in inputs.anrs.sortedBy { it.timestampMs ?: Long.MAX_VALUE }) reported = queueAnr(a) || reported
        }
        pruneSessions()

        lastNetworkType = deps.network.current().type
        startSession()
        flushPendingIntoNewSession()

        val interval = config.flushIntervalMs.coerceAtLeast(1_000L)
        flushTimer = deps.scheduler.schedule(interval, interval) { run("timer") { upload() } }

        // Crashes of the last run go out now rather than at the first timer tick.
        if (reported) upload()
    }

    private fun newAnonymousId(): String {
        val id = deps.random.uuid()
        store.device.putString(KEY_ANONYMOUS_ID, id)
        return id
    }

    private fun installId(): String {
        store.device.getString(KEY_INSTALL_ID)?.let { return it }
        val id = deps.random.uuid()
        store.device.putString(KEY_INSTALL_ID, id)
        return id
    }

    private fun startSession() {
        val now = clock.wallMs()
        val sampled = config.replayCapture && deps.random.replayRoll() < config.replaySampleRate
        val s = SessionRecord(
            id = deps.random.uuid(),
            anonymousId = anonymousId,
            userId = userId,
            installId = if (consent.canCapture) installId() else "",
            startedAt = now,
            lastSeenAt = now,
            wrapper = wrapper,
            wrapperVersion = wrapperVersion,
        )
        session = s
        seq = 0
        screen = null
        screenV = null
        limiter.resetSession()
        foregroundSinceMono = clock.monotonicMs()
        backgroundAtMono = null
        sessionIdV = s.id
        sampledV = sampled
        persist(s)
        emit(TYPE_SESSION_START, emptyMap())
        deps.hooks.onSessionStart(s.id, sampled)
        Logger.d("Unilitix: session ${s.id.take(8)} started (replay ${if (sampled) "sampled" else "not sampled"})")
    }

    private fun endSession() {
        val s = session ?: return
        val mono = clock.monotonicMs()
        if (backgroundAtMono == null) s.foregroundMs += (mono - foregroundSinceMono).coerceAtLeast(0)
        s.endedAt = clock.wallMs()
        s.lastSeenAt = s.endedAt!!
        s.nextSeq = seq
        persist(s)
        session = null
        sessionIdV = null
        sampledV = false
        screen = null
        screenV = null
    }

    private fun persist(s: SessionRecord) {
        store.putSession(s.id, s.startedAt, s.toJson())
    }

    /** Appends one event to the queue (the queue cap drops the oldest). Returns false when not captured. */
    private fun emit(type: String, fields: Map<String, Any?>, timestampMs: Long? = null): Boolean {
        if (!consent.canCapture) return false
        val s = session ?: return false
        val net = deps.network.current()
        val e = LinkedHashMap<String, Any?>()
        e["type"] = type
        e["eventId"] = deps.random.uuid()
        e["seq"] = seq++
        e["timestamp"] = IsoTime.format(timestampMs ?: clock.wallMs())
        for ((k, v) in fields) e[k] = v
        if (!fields.containsKey("screen")) screen?.let { e["screen"] = it }
        e["networkAtCapture"] = net.type
        e["capturedOffline"] = !net.online
        deps.device.eventFields()?.let { perf -> for ((k, v) in perf) if (!e.containsKey(k)) e[k] = v }
        if (net.online) s.onlineEvents++ else s.offlineEvents++
        s.lastSeenAt = clock.wallMs()
        append(s.id, e)
        if (type != TYPE_SESSION_START) breadcrumbs.add(type, e["screen"] as? String)
        if (store.eventCount() >= config.flushBatchSize) upload()
        return true
    }

    private fun append(sessionId: String, event: Map<String, Any?>) {
        store.appendEvent(sessionId, EngineJson.write(event))
        val over = store.eventCount() - config.maxOfflineEvents
        if (over > 0) {
            store.dropOldestEvents(over)
            Logger.d("Unilitix: queue full (maxOfflineEvents=${config.maxOfflineEvents}), dropped $over oldest")
        }
    }

    private fun doTrack(name: String, properties: Map<*, *>?) {
        val fields = LinkedHashMap<String, Any?>()
        fields["metadata"] = linkedMapOf<String, Any?>("name" to Normalize.string(name))
        val props = Normalize.properties(properties)
        if (!props.isNullOrEmpty()) fields["properties"] = props
        emit(TYPE_CUSTOM, fields)
    }

    private fun doScreen(rawName: String) {
        val name = Normalize.string(rawName)
        if (session == null || screen == name) return
        screen = name
        screenV = name
        emit(TYPE_NAVIGATE, linkedMapOf("screen" to name))
    }

    private fun doIdentify(rawUserId: String, traits: Map<*, *>?) {
        if (!consent.canCapture) return
        val uid = Normalize.string(rawUserId)
        userId = uid
        userIdV = uid
        store.identity.putString(KEY_USER_ID, uid)
        session?.let { it.userId = uid; persist(it) }
        val req = LinkedHashMap<String, Any?>()
        req["anonymousId"] = anonymousId
        req["customUserId"] = uid
        val t = Normalize.properties(traits)
        if (!t.isNullOrEmpty()) req["traits"] = t
        pendingIdentifies.add(EngineJson.write(req))
        saveIdentifies()
    }

    private fun doReset() {
        // The old user's data goes first, under the old identity (events are
        // bound to their session record, so the rotation below cannot relink them).
        upload()
        endSession()
        userId = null
        userIdV = null
        store.identity.remove(KEY_USER_ID)
        anonymousId = newAnonymousId()
        anonymousIdV = anonymousId
        store.device.putString(KEY_INSTALL_ID, deps.random.uuid())
        startSession()
        Logger.d("Unilitix: reset: new anonymous id, install id and session")
    }

    private fun doBackground() {
        if (backgroundAtMono != null) return
        val mono = clock.monotonicMs()
        backgroundAtMono = mono
        session?.let {
            it.foregroundMs += (mono - foregroundSinceMono).coerceAtLeast(0)
            it.lastSeenAt = clock.wallMs()
            it.nextSeq = seq
            persist(it)
        }
        upload()
    }

    private fun doForeground() {
        val bg = backgroundAtMono
        val mono = clock.monotonicMs()
        backgroundAtMono = null
        if (bg != null) {
            val away = mono - bg
            if (away > config.sessionTimeoutMs || session == null) {
                endSessionAt(bg)
                startSession()
            } else {
                session?.let { it.backgroundMs += away }
            }
        } else if (session == null) {
            startSession()
        }
        foregroundSinceMono = mono
    }

    /** Ends the current session as of the moment the app went to the background. */
    private fun endSessionAt(backgroundMono: Long) {
        val s = session ?: return
        val endWall = clock.wallMs() - (clock.monotonicMs() - backgroundMono)
        backgroundAtMono = backgroundMono // foreground time already counted at background
        endSession()
        s.endedAt = endWall.coerceAtLeast(s.startedAt)
        s.lastSeenAt = s.endedAt!!
        persist(s)
    }

    private fun doNetworkChanged() {
        val net = deps.network.current()
        val last = lastNetworkType
        if (last != null && last != net.type) session?.let { it.networkTransitions++ }
        lastNetworkType = net.type
        if (net.reachable && (store.eventCount() > 0 || pendingIdentifies.isNotEmpty())) upload()
    }

    private fun doRecordError(r: ErrorReport) {
        val stack = r.stackTrace ?: ""
        val fp = r.fingerprint?.takeIf { it.isNotBlank() } ?: Fingerprint.of(r.type, stack)
        if (r.fatal) markCrashed()
        if (!consent.canCapture) return
        if (limiter.admit(fp) == null) {
            Logger.d("Unilitix: repeat ${if (r.fatal) "CRASH" else "EXCEPTION"} $fp suppressed (per-session cap)")
            return
        }
        val meta = LinkedHashMap<String, Any?>()
        Normalize.properties(r.metadata)?.let { meta.putAll(it) }
        meta["fingerprint"] = fp
        if (r.platform != null) meta["platform"] = r.platform
        val fields = LinkedHashMap<String, Any?>()
        if (r.eventId != null) fields["eventId"] = r.eventId
        fields["exceptionType"] = Normalize.string(r.type)
        fields["exceptionMessage"] = r.message.take(MAX_MESSAGE)
        fields["stackTrace"] = stack.take(MAX_STACK)
        fields["fatal"] = r.fatal
        fields["title"] = "${r.type}: ${r.message}".take(200)
        fields["breadcrumbs"] = breadcrumbs.toWire()
        fields["metadata"] = meta
        emit(if (r.fatal) TYPE_CRASH else TYPE_EXCEPTION, fields)
    }

    private fun markCrashed() {
        session?.let {
            it.crashed = true
            it.nextSeq = seq
            persist(it)
        }
    }

    // ── Reports from earlier processes ──────────────────────────────────

    private fun record(id: String): SessionRecord? = store.session(id)?.let { SessionRecord.fromJson(it) }

    /** The stored session running at [atMs]: the latest start at or before it (+1 s slack). */
    private fun sessionAt(atMs: Long?): SessionRecord? {
        val latest = atMs?.plus(ATTRIBUTION_SLACK_MS)
        return store.recentSessions(RECENT_SESSIONS)
            .mapNotNull { SessionRecord.fromJson(it) }
            .filter { latest == null || it.startedAt <= latest }
            .maxByOrNull { it.startedAt }
    }

    /** Queues [event] (already in wire shape, without eventId/seq) into [s]. */
    private fun appendToSession(s: SessionRecord, event: Map<String, Any?>) {
        val e = LinkedHashMap<String, Any?>()
        e["type"] = event["type"]
        e["eventId"] = (event["eventId"] as? String) ?: deps.random.uuid()
        e["seq"] = (event["seq"] as? Number)?.toInt() ?: s.nextSeq++
        for ((k, v) in event) if (!e.containsKey(k)) e[k] = v
        if (e["networkAtCapture"] == null || e["networkAtCapture"] == "") e["networkAtCapture"] = NetworkTypes.UNKNOWN
        append(s.id, e)
    }

    private fun queueNativeCrash(crash: NativeCrash): Boolean {
        val event = NativeCrashRecord.toEvent(crash, clock.wallMs(), IsoTime::format)
        val target = sessionAt(crash.timestampMs)
        if (target != null) {
            target.crashed = true
            val at = crash.timestampMs
            if (at != null && at > target.lastSeenAt) target.lastSeenAt = at
            target.endedAt = target.lastSeenAt
            appendToSession(target, event)
            persist(target)
            Logger.d("Unilitix: native crash ${crash.fingerprint} reported against session ${target.id.take(8)}")
        } else {
            pendingCrashForNewSession += event
        }
        return true
    }

    private fun queueAnr(anr: Anr): Boolean {
        val event = AnrTrace.toEvent(anr, clock.wallMs(), IsoTime::format)
        val target = sessionAt(anr.timestampMs)
        if (target != null) {
            val at = anr.timestampMs
            if (at != null && at > target.lastSeenAt) target.lastSeenAt = at
            target.endedAt = target.lastSeenAt
            appendToSession(target, event)
            persist(target)
        } else {
            pendingAnrForNewSession += event
        }
        return true
    }

    private fun queuePriorBatch(b: PriorBatch): Boolean {
        if (b.events.isEmpty()) return false
        val fields = b.sessionFields
        val id = b.sessionId ?: (fields?.get("sessionId") as? String)?.takeIf { it.isNotEmpty() }
        var target = id?.let { record(it) }
        if (target == null && id != null && fields != null) {
            val now = clock.wallMs()
            target = SessionRecord(
                id = id,
                anonymousId = fields["anonymousId"] as? String ?: anonymousId,
                userId = (fields["customUserId"] as? String)?.takeIf { it.isNotEmpty() },
                installId = fields["installId"] as? String ?: "",
                startedAt = now,
                lastSeenAt = now,
                crashed = fields["crashed"] == true,
                acknowledged = true,
                legacy = fields,
            )
        }
        if (target == null) {
            // Unknown session (crash before the SDK finished starting): the new one.
            for (e in b.events) pendingPriorForNewSession += e
            return true
        }
        if (b.events.any { it["type"] == TYPE_CRASH }) target.crashed = true
        for (e in b.events) appendToSession(target, e)
        persist(target)
        return true
    }

    private val pendingCrashForNewSession = ArrayList<Map<String, Any?>>()
    private val pendingAnrForNewSession = ArrayList<Map<String, Any?>>()
    private val pendingPriorForNewSession = ArrayList<Map<String, Any?>>()

    /** Reports with no stored session to go to are placed in the new session, after SESSION_START. */
    private fun flushPendingIntoNewSession() {
        val s = session ?: return
        if (pendingCrashForNewSession.isNotEmpty() || pendingPriorForNewSession.any { it["type"] == TYPE_CRASH }) s.crashed = true
        for (e in pendingPriorForNewSession + pendingCrashForNewSession + pendingAnrForNewSession) {
            val copy = LinkedHashMap(e)
            copy.remove("eventId"); copy.remove("seq")
            copy["seq"] = seq++
            appendToSession(s, copy)
        }
        pendingCrashForNewSession.clear(); pendingAnrForNewSession.clear(); pendingPriorForNewSession.clear()
        persist(s)
    }

    private fun pruneSessions() {
        val keep = HashSet(store.sessionIdsWithEvents())
        store.recentSessions(RECENT_SESSIONS).mapNotNullTo(keep) { SessionRecord.fromJson(it)?.id }
        store.deleteSessionsExcept(keep)
    }

    // ── Identify queue ──────────────────────────────────────────────────

    private fun loadIdentifies(): MutableList<String> {
        val raw = store.identity.getString(KEY_PENDING_IDENTIFIES) ?: return mutableListOf()
        val list = (try { EngineJson.parse(raw) } catch (e: Exception) { null }) as? List<*> ?: return mutableListOf()
        return list.mapNotNull { (it as? Map<*, *>)?.let { m -> EngineJson.write(m) } }.toMutableList()
    }

    private fun saveIdentifies() {
        if (pendingIdentifies.isEmpty()) store.identity.remove(KEY_PENDING_IDENTIFIES)
        else store.identity.putString(KEY_PENDING_IDENTIFIES, "[" + pendingIdentifies.joinToString(",") + "]")
    }

    // ── Uploads ─────────────────────────────────────────────────────────

    private fun canUploadNow(): Boolean =
        started && consent.canUpload && !gate.authRejected && gate.remainingBackoffMs() == 0L &&
            deps.network.current().reachable

    /** Starts an upload cycle (or asks the running one to continue). */
    private fun upload() {
        if (inFlight) {
            uploadAgain = true
            return
        }
        sendNext()
    }

    private fun sendNext() {
        if (!canUploadNow()) {
            endCycle()
            return
        }
        pendingIdentifies.firstOrNull()?.let { body ->
            inFlight = true
            post(PATH_IDENTIFY, body) { outcome ->
                if (outcome == UploadOutcome.SENT || outcome == UploadOutcome.DROPPED) {
                    if (pendingIdentifies.isNotEmpty()) pendingIdentifies.removeAt(0)
                    saveIdentifies()
                    sendNext()
                } else {
                    endCycle()
                }
            }
            return
        }
        val head = store.oldestEvents(config.flushBatchSize)
        if (head.isEmpty()) {
            endCycle()
            return
        }
        val sid = head[0].sessionId
        var batch = head.takeWhile { it.sessionId == sid }
        val live = session
        val record = if (live != null && live.id == sid) live.also { it.nextSeq = seq }
            else record(sid) ?: SessionRecord(sid, anonymousId, null, "", clock.wallMs(), clock.wallMs())
        record.syncAttempts++
        var body = body(record, batch)
        // Never send a body the API would refuse as too large: halve the batch.
        while (body.length > MAX_BODY_CHARS && batch.size > 1) {
            batch = batch.subList(0, batch.size / 2)
            body = body(record, batch)
        }
        if (body.length > MAX_BODY_CHARS) {
            Logger.w("Unilitix: one event is larger than the API accepts; dropped")
            store.deleteEvents(batch.map { it.id })
            sendNext()
            return
        }
        persist(record)
        val ids = batch.map { it.id }
        inFlight = true
        post(PATH_SESSION, body) { outcome ->
            when (outcome) {
                UploadOutcome.SENT -> {
                    store.deleteEvents(ids)
                    if (!record.acknowledged) {
                        record.acknowledged = true
                        persist(record)
                    }
                    sendNext()
                }
                UploadOutcome.DROPPED -> {
                    store.deleteEvents(ids)
                    sendNext()
                }
                UploadOutcome.RETRY_LATER -> {
                    store.recordFailedAttempt(ids, MAX_BATCH_ATTEMPTS)
                    record.syncFailedBatches++
                    persist(record)
                    endCycle()
                }
                UploadOutcome.AUTH_REJECTED, UploadOutcome.PAYMENT_PAUSED -> endCycle()
            }
        }
    }

    private fun post(path: String, body: String, onOutcome: (UploadOutcome) -> Unit) {
        try {
            deps.transport.post(path, body) { response ->
                run("response") {
                    inFlight = false
                    val result = toResult(response)
                    val outcome = gate.note(result, if (path == PATH_IDENTIFY) "Identify" else "Batch")
                    onOutcome(outcome)
                }
            }
        } catch (t: Throwable) {
            inFlight = false
            gate.note(ApiResult.NetworkError(t), "Batch")
            endCycle()
        }
    }

    private fun toResult(r: HttpResponse): ApiResult = when {
        r.status == 0 -> ApiResult.NetworkError(java.io.IOException("no response"))
        r.status in 200..299 -> ApiResult.Success(
            (r.body?.let { EngineJson.parseObject(it) }?.get("sessionId") as? String) ?: ""
        )
        else -> ApiResult.failure(r.status, r.header("Retry-After"), r.body?.take(200) ?: "")
    }

    private fun endCycle() {
        inFlight = false
        if (uploadAgain) {
            uploadAgain = false
            if (canUploadNow() && (pendingIdentifies.isNotEmpty() || store.oldestEvents(1).isNotEmpty())) {
                sendNext()
                return
            }
        }
        val waiters = ArrayList(idleWaiters)
        idleWaiters.clear()
        for (w in waiters) try { w() } catch (_: Throwable) {}
        try { deps.hooks.onUploadCycleFinished() } catch (_: Throwable) {}
    }

    /** The `/v1/ingest/session` body: the session record at the top level plus [events]. */
    private fun body(r: SessionRecord, events: List<StoredEvent>): String {
        val now = clock.wallMs()
        val live = r.id == session?.id
        val endedAt = if (live) now else (r.endedAt ?: r.lastSeenAt)
        val m = LinkedHashMap<String, Any?>()
        r.legacy?.let { m.putAll(it) }
        m.putAll(deps.device.sessionFields())
        m["sessionId"] = r.id
        m["anonymousId"] = r.anonymousId
        m["userId"] = r.userId ?: ""
        m["customUserId"] = r.userId ?: ""
        m["installId"] = r.installId
        m["networkType"] = deps.network.current().type
        m["startedAt"] = IsoTime.format(r.startedAt)
        m["endedAt"] = IsoTime.format(endedAt.coerceAtLeast(r.startedAt))
        m["durationMs"] = (endedAt - r.startedAt).coerceAtLeast(0)
        val bgAt = backgroundAtMono
        m["foregroundTimeMs"] = if (live && bgAt == null) r.foregroundMs + (clock.monotonicMs() - foregroundSinceMono).coerceAtLeast(0) else r.foregroundMs
        m["backgroundTimeMs"] = if (live && bgAt != null) r.backgroundMs + (clock.monotonicMs() - bgAt).coerceAtLeast(0) else r.backgroundMs
        m["crashed"] = r.crashed
        m["capturedOffline"] = r.offlineEvents > 0
        m["offlineEventCount"] = r.offlineEvents
        m["onlineEventCount"] = r.onlineEvents
        m["networkTransitions"] = r.networkTransitions
        m["syncAttempts"] = r.syncAttempts
        m["syncFailedBatches"] = r.syncFailedBatches
        if (r.wrapper != null) {
            m["sdkWrapper"] = r.wrapper
            m["sdkWrapperVersion"] = r.wrapperVersion
            m["sessionData"] = linkedMapOf("sdkWrapper" to r.wrapper, "sdkWrapperVersion" to r.wrapperVersion)
        }
        m["events"] = events.map { RawJson(it.json) }
        return EngineJson.write(m)
    }

    // ── ConsentEffects (engine thread: ConsentController.set runs in setConsent's task) ──

    override fun startCapture() {
        deps.hooks.onCaptureAllowed(true)
    }

    override fun stopCapture() {
        deps.hooks.onCaptureAllowed(false)
    }

    override fun purgeLocalData() {
        breadcrumbs.clear()
        store.deleteAllEvents()
        pendingIdentifies.clear()
        saveIdentifies()
        // Opt-out deletes the install id; a new one is made if capture resumes.
        store.device.remove(KEY_INSTALL_ID)
        store.deleteSessionsExcept(setOfNotNull(session?.id))
        try { deps.hooks.onPurge() } catch (t: Throwable) { Logger.w("Unilitix: purge of replay data failed: ${t.message}") }
        Logger.d("Unilitix: consent denied — on-device queue purged")
    }

    override fun releaseHeld() {
        upload()
    }

    companion object {
        const val TYPE_SESSION_START = "SESSION_START"
        const val TYPE_CUSTOM = "CUSTOM"
        const val TYPE_NAVIGATE = "NAVIGATE"
        const val TYPE_EXCEPTION = "EXCEPTION"
        const val TYPE_CRASH = "CRASH"

        const val PATH_SESSION = "/v1/ingest/session"
        const val PATH_IDENTIFY = "/v1/ingest/identify"

        const val KEY_ANONYMOUS_ID = "anonymous_id"
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_PENDING_IDENTIFIES = "pending_identifies"

        /** A batch that fails (retryably) this many times in a row is parked so newer data can go. */
        const val MAX_BATCH_ATTEMPTS = 10
        /** Parked events are retried on this many launches, then dropped. */
        const val MAX_PARK_CYCLES = 3
        /** Largest uncompressed body sent (the API accepts 10 MB decompressed, 2 MB compressed). */
        const val MAX_BODY_CHARS = 4 * 1024 * 1024
        const val MAX_STACK = 16_000
        const val MAX_MESSAGE = 8_000
        const val RECENT_SESSIONS = 8
        const val ATTRIBUTION_SLACK_MS = 1_000L
    }
}
