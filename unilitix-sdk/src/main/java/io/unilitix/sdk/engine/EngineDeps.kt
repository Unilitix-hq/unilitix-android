package io.unilitix.sdk.engine

import io.unilitix.sdk.core.KeyValueStore
import java.util.UUID
import kotlin.random.Random

/**
 * Everything the [Engine] needs from the platform. Android supplies real
 * implementations (`AndroidEngineDeps`); the behaviour-fixture runner
 * supplies fakes (clock, timers, transport, network, device, storage) so the
 * same engine code runs the shared contract cases on the JVM.
 */
internal class EngineDeps(
    val clock: EngineClock,
    val scheduler: EngineScheduler,
    val executor: EngineExecutor,
    val transport: EngineTransport,
    val network: NetworkSource,
    val device: DeviceSource,
    val random: EngineRandom,
    val store: EngineStore,
    val hooks: EngineHooks = EngineHooks.NONE,
)

internal interface EngineClock {
    /** Wall clock, ms since 1970 (event timestamps). */
    fun wallMs(): Long
    /** Monotonic ms (backoff windows, background duration). */
    fun monotonicMs(): Long
}

internal fun interface Cancellable {
    fun cancel()
}

/** Timers. Tasks run on a scheduler thread; the engine hops onto its executor. */
internal interface EngineScheduler {
    /** Runs [task] after [delayMs], then every [periodMs] if > 0. */
    fun schedule(delayMs: Long, periodMs: Long, task: () -> Unit): Cancellable
    fun shutdown()
}

/** The engine's serial executor: every state change happens on it, in order. */
internal interface EngineExecutor {
    fun execute(task: () -> Unit)
    /** True on the executor's own thread (blocking waits there would deadlock). */
    fun isEngineThread(): Boolean
    fun shutdown()
}

/** An HTTP answer; status 0 = no response (connection error, timeout). */
internal class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * POSTs JSON to the Unilitix API (`path` like `/v1/ingest/session`). The
 * production transport gzips, adds the key and User-Agent headers and answers
 * on a network thread; [callback] must be called exactly once.
 */
internal interface EngineTransport {
    fun post(path: String, json: String, callback: (HttpResponse) -> Unit)
}

/**
 * The device's network now. [type] is a contract `networkTypes` value;
 * [online] is the contract's notion (validated connectivity), [reachable]
 * whether an upload is worth attempting (a default network with internet).
 */
internal data class NetworkSnapshot(val type: String, val online: Boolean, val reachable: Boolean = online)

internal fun interface NetworkSource {
    fun current(): NetworkSnapshot
}

/** Device and app fields of the session record, and per-event performance fields. */
internal interface DeviceSource {
    /** deviceType, manufacturer, deviceModel, os, osVersion, screen*, appVersion, buildNumber, packageName, locale, timezone, carrierName, orientation, totalStorageGb, batteryLevel. */
    fun sessionFields(): Map<String, Any?>
    /** memoryUsageMb, cpuUsagePct, frameDrops, or null. */
    fun eventFields(): Map<String, Any?>? = null
}

internal interface EngineRandom {
    fun uuid(): String = UUID.randomUUID().toString()
    /** The per-session replay sampling roll in [0, 1). */
    fun replayRoll(): Double = Random.nextDouble()
    /** Source of backoff jitter. */
    val jitter: Random get() = Random.Default

    object Default : EngineRandom
}

/** Platform side effects of engine decisions. All run on the engine executor. */
internal interface EngineHooks {
    /** Consent denied: delete platform-held data (screenshots, snapshots, crash files). */
    fun onPurge() {}
    /** Capture allowed again (true) or stopped (false) by a consent change. */
    fun onCaptureAllowed(allowed: Boolean) {}
    fun onSessionStart(sessionId: String, replaySampled: Boolean) {}
    /** An upload cycle ended (replay media can follow on its own thread). */
    fun onUploadCycleFinished() {}

    companion object {
        val NONE = object : EngineHooks {}
    }
}

/** One queued event: its session and its JSON (with eventId and seq). */
internal class StoredEvent(val id: Long, val sessionId: String, val json: String, val attempts: Int)

/**
 * The engine's persistent state. Must survive process death (the runner's
 * "disk"); every call happens on the engine executor.
 */
internal interface EngineStore {
    /** SDK preferences: consent, exit watermark. */
    val prefs: KeyValueStore
    /** Device identifiers: anonymous id, install id. */
    val device: KeyValueStore
    /** Personal data (encrypted at rest on Android): user id, pending identify requests. */
    val identity: KeyValueStore

    fun appendEvent(sessionId: String, json: String)
    fun eventCount(): Int
    fun dropOldestEvents(count: Int)
    /** Oldest non-parked events, at most [limit], in queue order. */
    fun oldestEvents(limit: Int): List<StoredEvent>
    fun deleteEvents(ids: List<Long>)
    /** One more failed attempt for [ids]; those reaching [parkAfter] are parked. */
    fun recordFailedAttempt(ids: List<Long>, parkAfter: Int)
    /**
     * New process: parked events are queued again with fresh attempts, except
     * those already parked [maxCycles] times, which are deleted. Returns how
     * many were deleted.
     */
    fun unparkEvents(maxCycles: Int): Int
    fun deleteAllEvents()
    fun sessionIdsWithEvents(): Set<String>

    fun putSession(id: String, startedAt: Long, json: String)
    fun session(id: String): String?
    /** Session records, newest start first. */
    fun recentSessions(limit: Int): List<String>
    fun deleteSessionsExcept(keep: Set<String>)
}
