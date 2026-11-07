package io.unilitix.sdk.contract

import io.unilitix.sdk.UnilitixConsent
import io.unilitix.sdk.capture.NativeCrashFile
import io.unilitix.sdk.capture.NativeCrashRecord
import io.unilitix.sdk.engine.Cancellable
import io.unilitix.sdk.engine.DeviceSource
import io.unilitix.sdk.engine.Engine
import io.unilitix.sdk.engine.EngineClock
import io.unilitix.sdk.engine.EngineConfig
import io.unilitix.sdk.engine.EngineDeps
import io.unilitix.sdk.engine.EngineExecutor
import io.unilitix.sdk.engine.EngineJson
import io.unilitix.sdk.engine.EngineRandom
import io.unilitix.sdk.engine.EngineScheduler
import io.unilitix.sdk.engine.EngineTransport
import io.unilitix.sdk.engine.ErrorReport
import io.unilitix.sdk.engine.HttpResponse
import io.unilitix.sdk.engine.MemoryEngineStore
import io.unilitix.sdk.engine.NetworkSnapshot
import io.unilitix.sdk.engine.NetworkSource
import io.unilitix.sdk.engine.NetworkTypes
import io.unilitix.sdk.engine.StartInputs
import io.unilitix.sdk.engine.UnilitixCore
import java.io.File
import java.time.OffsetDateTime

/**
 * Runs the shared behaviour fixtures (api repo `api/sdk/behaviour/`, runner
 * contract in its README.md) against the Android SDK's [Engine] and
 * [UnilitixCore] with a fake clock, timers, transport, network, device and
 * storage. The engine and the pre-init buffer are the code the published SDK
 * runs; only the platform edges are faked:
 *
 * - executor: inline and serial, so "settling" happens before every call returns;
 * - transport: answers synchronously (its callback is queued on the executor,
 *   like the real network thread's), records what the engine sent;
 * - storage: one [MemoryEngineStore] per case, kept across `restart`;
 * - native crash: `crashNextLaunch` writes the record `libunilitix-native.so`
 *   writes (`files/unilitix/native_crash.json`); the next init reads it with
 *   the production [NativeCrashFile] / [NativeCrashRecord] code.
 */
internal class BehaviourRunner(private val fixtures: BehaviourFixtures.Fixtures, private val tmp: File) {

    class Failure(val caseId: String, val message: String)

    @Suppress("UNCHECKED_CAST")
    private val harness = fixtures.harness
    private val apiUrl = harness["apiUrl"] as String
    @Suppress("UNCHECKED_CAST")
    private val ignoredPaths = harness["ignoredPaths"] as List<String>
    @Suppress("UNCHECKED_CAST")
    private val defaultStatus = ((harness["defaultResponse"] as Map<String, Any?>)["status"] as Number).toInt()

    fun runAll(skip: Set<String> = emptySet()): List<Failure> {
        val failures = ArrayList<Failure>()
        for (c in fixtures.cases) {
            if (c.id in skip) continue
            val err = try {
                runCase(c)
            } catch (t: Throwable) {
                "${t.javaClass.simpleName}: ${t.message}\n" + t.stackTrace.take(8).joinToString("\n") { "  at $it" }
            }
            if (err != null) failures += Failure(c.id, err)
        }
        return failures
    }

    // ── Fakes ───────────────────────────────────────────────────────────

    class FakeClock(var wall: Long) : EngineClock {
        var mono: Long = 5_000_000L
        override fun wallMs(): Long = wall
        override fun monotonicMs(): Long = mono
        fun advance(ms: Long) { wall += ms; mono += ms }
    }

    /** Inline, serial: a task submitted while another runs is queued and runs right after it. */
    class InlineExecutor : EngineExecutor {
        private val queue = ArrayDeque<() -> Unit>()
        private var draining = false
        var dead = false
        override fun execute(task: () -> Unit) {
            if (dead) return
            queue.addLast(task)
            if (draining) return
            draining = true
            try {
                while (queue.isNotEmpty()) queue.removeFirst()()
            } finally {
                draining = false
            }
        }
        override fun isEngineThread(): Boolean = draining
        override fun shutdown() { dead = true; queue.clear() }
    }

    class FakeScheduler(private val clock: FakeClock) : EngineScheduler {
        private class Timer(var due: Long, val period: Long, val task: () -> Unit, var cancelled: Boolean = false)
        private val timers = ArrayList<Timer>()
        var dead = false
        override fun schedule(delayMs: Long, periodMs: Long, task: () -> Unit): Cancellable {
            val t = Timer(clock.mono + delayMs, periodMs, task)
            timers += t
            return Cancellable { t.cancelled = true }
        }
        override fun shutdown() { dead = true; timers.clear() }

        /** Advances the clock by [ms], firing due timers in order at their due time. */
        fun advance(ms: Long) {
            val end = clock.mono + ms
            while (!dead) {
                val next = timers.filter { !it.cancelled && it.due <= end }.minByOrNull { it.due } ?: break
                clock.advance(next.due - clock.mono)
                if (next.period > 0) next.due += next.period else timers.remove(next)
                next.task()
            }
            clock.advance(end - clock.mono)
        }
    }

    class FakeNetwork(var type: String, var generation: String?, var online: Boolean) : NetworkSource {
        override fun current(): NetworkSnapshot {
            val t = NetworkTypes.canonical(type, generation ?: "unknown", online)
            return NetworkSnapshot(t, online, online)
        }
    }

    class Recorded(val path: String, val status: Int, val body: Map<String, Any?>)

    class FakeTransport(private val network: FakeNetwork, private val defaultStatus: Int) : EngineTransport {
        val recorded = ArrayList<Recorded>()
        private var override: Triple<Int, Map<String, String>, Int?>? = null // status, headers, times left (null = all)

        fun respond(status: Int, headers: Map<String, String>, times: Int?) { override = Triple(status, headers, times) }

        private fun next(): Pair<Int, Map<String, String>> {
            val o = override ?: return defaultStatus to emptyMap()
            val (status, headers, times) = o
            if (times != null) {
                override = if (times - 1 <= 0) null else Triple(status, headers, times - 1)
            }
            return status to headers
        }

        @Suppress("UNCHECKED_CAST")
        override fun post(path: String, json: String, callback: (HttpResponse) -> Unit) {
            if (!network.online) {
                callback(HttpResponse(0)) // never left the device: not recorded
                return
            }
            val body = BehaviourFixtures.Json.parse(json) as Map<String, Any?>
            val (status, headers) = next()
            recorded += Recorded(path, status, body)
            if (status == 0) {
                callback(HttpResponse(0))
                return
            }
            val reply = when (path) {
                "/v1/ingest/identify" -> EngineJson.write(mapOf("anonymousId" to body["anonymousId"], "customUserId" to body["customUserId"], "updated" to true))
                "/v1/ingest/session", "/v1/ingest/events" -> EngineJson.write(mapOf("received" to ((body["events"] as? List<*>)?.size ?: 0), "sessionId" to body["sessionId"]))
                else -> "{}"
            }
            callback(HttpResponse(status, headers, reply))
        }
    }

    class FakeRandom(rolls: List<Double>) : EngineRandom {
        private val rolls = ArrayDeque(rolls)
        override fun replayRoll(): Double = if (rolls.isEmpty()) 0.999 else rolls.removeFirst()
    }

    object FakeDevice : DeviceSource {
        override fun sessionFields(): Map<String, Any?> = linkedMapOf(
            "deviceType" to "phone",
            "manufacturer" to "Fake",
            "deviceModel" to "Behaviour",
            "os" to "Android",
            "osVersion" to "14",
            "appVersion" to "1.4.2",
            "buildNumber" to "42",
            "packageName" to "io.unilitix.behaviour",
            "sdkVersion" to "test",
        )
    }

    // ── One case ────────────────────────────────────────────────────────

    private inner class Device(c: BehaviourFixtures.Case) {
        val clock = FakeClock(OffsetDateTime.parse(c.clock).toInstant().toEpochMilli())
        val network = FakeNetwork(
            c.network["type"] as String,
            c.network["generation"] as String?,
            c.network["online"] as Boolean,
        )
        val transport = FakeTransport(network, defaultStatus)
        val random = FakeRandom(c.randomRolls)
        val store = MemoryEngineStore()
        val filesDir = File(tmp, "case-${System.nanoTime()}").also { it.mkdirs() }
        val config = engineConfig(c.config)
    }

    /** One process: dies on `restart`, keeping the [Device]. */
    private inner class Process(val d: Device) {
        val executor = InlineExecutor()
        val scheduler = FakeScheduler(d.clock)
        val core = UnilitixCore()
        var engine: Engine? = null

        fun init() {
            if (core.isStarted) return // a second init in one process is ignored
            val deps = EngineDeps(d.clock, scheduler, executor, d.transport, d.network, FakeDevice, d.random, d.store)
            val e = Engine(d.config, deps)
            val crashes = NativeCrashFile.consume(NativeCrashFile.locate(d.filesDir))
                ?.let { NativeCrashRecord.parse(it) }
                ?.let { listOf(it) } ?: emptyList()
            engine = e
            core.start(e, StartInputs(nativeCrashes = crashes))
        }

        fun die() {
            engine?.shutdown()
            scheduler.shutdown()
            executor.shutdown()
        }
    }

    private fun engineConfig(cfg: Map<String, Any?>): EngineConfig {
        var c = EngineConfig()
        fun num(k: String) = (cfg[k] as Number)
        for ((k, v) in cfg) {
            c = when (k) {
                "flushIntervalSeconds" -> c.copy(flushIntervalMs = num(k).toLong() * 1000)
                "flushBatchSize" -> c.copy(flushBatchSize = num(k).toInt())
                "maxOfflineEvents" -> c.copy(maxOfflineEvents = num(k).toInt())
                "sessionTimeoutSeconds" -> c.copy(sessionTimeoutMs = num(k).toLong() * 1000)
                "replaySampleRate" -> c.copy(replaySampleRate = num(k).toDouble())
                "consent" -> c.copy(initialConsent = UnilitixConsent.fromValue(v as String)!!)
                // Platform capture options (Android capture components, not the engine).
                "debug", "autoTrackScreens", "autoTrackTaps", "autoTrackCrashes", "autoTrackRageTaps",
                "captureSnapshots", "captureScreenshots" -> c
                else -> throw IllegalArgumentException("option $k is not offered by unilitix-android (fixture bug?)")
            }
        }
        val replay = (cfg["captureSnapshots"] as? Boolean ?: true) || (cfg["captureScreenshots"] as? Boolean ?: true)
        return c.copy(replayCapture = replay)
    }

    /** Null when the case passes, else what failed. */
    private fun runCase(c: BehaviourFixtures.Case): String? {
        val d = Device(c)
        var p = Process(d)
        val caps = HashMap<String, Any?>()
        var sinceLast = 0

        fun takeRecorded(): List<Recorded> {
            val all = d.transport.recorded
            val out = all.subList(sinceLast, all.size).toList()
            sinceLast = all.size
            return out.filter { r -> ignoredPaths.none { r.path.startsWith(it) } }
        }

        fun state(): Map<String, Any?> {
            val e = p.engine
            return mapOf(
                "anonymousId" to e?.currentAnonymousId(),
                "sessionId" to e?.currentSessionId(),
                "userId" to e?.currentUserId(),
                "consent" to (e?.consentState() ?: UnilitixConsent.GRANTED).value,
                "queueLength" to d.store.eventCount().toLong(),
                "uploadsPaused" to (e?.uploadsPaused() ?: false),
                "replaySampled" to (e?.isReplaySampled() ?: false),
                "screen" to e?.currentScreen(),
            )
        }

        @Suppress("UNCHECKED_CAST")
        fun step(s: BehaviourFixtures.Step, index: String): String? {
            val raw = s.args
            val args = (BehaviourFixtures.decodeInput(raw) as? Map<String, Any?>) ?: emptyMap()
            val core = p.core
            when (s.action) {
                "init" -> p.init()
                "track" -> core.track(args["name"] as String, args["properties"] as Map<*, *>?)
                "screen" -> core.screen(args["name"] as String)
                "identify" -> core.identify(args["userId"] as String, args["traits"] as Map<*, *>?)
                "reset" -> core.reset()
                "setConsent" -> core.setConsent(UnilitixConsent.fromValue(args["state"] as String)!!)
                "advanceTime" -> p.scheduler.advance((args["ms"] as Number).toLong())
                "setNetwork" -> {
                    d.network.type = args["type"] as String
                    d.network.generation = args["generation"] as String?
                    d.network.online = args["online"] as Boolean
                    core.onNetworkChanged()
                }
                "background" -> core.onBackground()
                "foreground" -> core.onForeground()
                "flush" -> core.flush()
                "recordError" -> {
                    // Unilitix.recordException(type, message, stackTrace, fatal, platform = "android").
                    val report = ErrorReport(
                        type = args["type"] as String,
                        message = args["message"] as String,
                        stackTrace = args["stack"] as String? ?: "",
                        fatal = args["fatal"] as Boolean,
                        platform = args["platform"] as String? ?: "android",
                    )
                    if (report.fatal) core.recordFatal(report, 2_000L) else core.recordError(report)
                }
                "crashNextLaunch" -> {
                    val file = NativeCrashFile.locate(d.filesDir)
                    file.parentFile!!.mkdirs()
                    val rec = LinkedHashMap<String, Any?>()
                    rec["platform"] = args["platform"]
                    rec["exceptionType"] = args["exceptionType"]
                    (args["signal"] as Number?)?.let { rec["signal"] = it.toInt() }
                    (args["message"] as String?)?.let { rec["exceptionMessage"] = it }
                    (args["stack"] as String?)?.let { rec["stackTrace"] = it }
                    rec["timestamp"] = d.clock.wall
                    file.writeText(EngineJson.write(rec))
                }
                "restart" -> {
                    p.die()
                    p = Process(d)
                }
                "serverResponds" -> d.transport.respond(
                    (args["status"] as Number).toInt(),
                    (args["headers"] as Map<String, Any?>?)?.mapValues { it.value.toString() } ?: emptyMap(),
                    (args["times"] as Number?)?.toInt(),
                )
                "repeat" -> {
                    val times = (args["times"] as Number).toInt()
                    val steps = (raw as Map<String, Any?>)["steps"] as List<Map<String, Any?>>
                    for (i in 0 until times) for ((j, st) in steps.withIndex()) {
                        val sub = BehaviourFixtures.substitute(st, i) as Map<String, Any?>
                        step(BehaviourFixtures.stepOf(sub), "$index.$i.$j")?.let { return it }
                    }
                }
                "expectRequests" -> return Matcher.checkRequests(raw as List<Any?>, takeRecorded(), caps)
                "expectEvents" -> return Matcher.checkEvents(raw as Map<String, Any?>, takeRecorded(), caps)
                "expectState" -> {
                    val st = state()
                    for ((k, m) in (raw as Map<String, Any?>).toSortedMap()) {
                        require(k in BehaviourFixtures.stateKeys) { "unknown state key $k" }
                        Matcher.match(m, st[k], true, caps, "state.$k")?.let { return it }
                    }
                }
                else -> return "unknown step ${s.action}"
            }
            return null
        }

        try {
            for ((i, s) in c.steps.withIndex()) {
                step(s, "$i")?.let { return "step $i ${s.action}: $it" }
            }
        } finally {
            p.die()
            d.filesDir.deleteRecursively()
        }
        return null
    }

    // ── Matcher (README "Matchers"; reference_test.go) ──────────────────

    object Matcher {
        private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val fingerprint = Regex("^[0-9a-f]{16}$")
        private val operators = setOf("\$capture", "\$match", "\$eq", "\$ne", "\$size", "\$length", "\$repeat", "\$time", "\$oneOf")

        fun show(v: Any?, present: Boolean = true): String {
            if (!present) return "(absent)"
            val s = EngineJson.write(v)
            return if (s.length > 400) s.take(400) + "…" else s
        }

        fun jsonEqual(a: Any?, b: Any?): Boolean = when {
            a is Number && b is Number -> a.toDouble() == b.toDouble()
            a is Map<*, *> && b is Map<*, *> -> a.size == b.size && a.all { (k, v) -> b.containsKey(k) && jsonEqual(v, b[k]) }
            a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { jsonEqual(a[it], b[it]) }
            else -> a == b
        }

        private fun instant(s: Any?): Long? = try {
            OffsetDateTime.parse(s as String).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }

        @Suppress("UNCHECKED_CAST")
        fun match(exp: Any?, act: Any?, present: Boolean, caps: MutableMap<String, Any?>, at: String): String? {
            when (exp) {
                is String -> {
                    if (exp.startsWith("<") && exp.endsWith(">")) return sentinel(exp, act, present, at)
                    return if (present && act == exp) null else "$at: want \"$exp\", got ${show(act, present)}"
                }
                null -> return if (present && act == null) null else "$at: want null, got ${show(act, present)}"
                is Boolean -> return if (present && act == exp) null else "$at: want $exp, got ${show(act, present)}"
                is Number -> return if (present && act is Number && act.toDouble() == exp.toDouble()) null else "$at: want $exp, got ${show(act, present)}"
                is List<*> -> {
                    if (!present || act !is List<*>) return "$at: want an array, got ${show(act, present)}"
                    val want = expandEach(exp)
                    if (want.size != act.size) return "$at: want ${want.size} items, got ${act.size}: ${show(act)}"
                    for (i in want.indices) match(want[i], act[i], true, caps, "$at[$i]")?.let { return it }
                    return null
                }
                is Map<*, *> -> {
                    val e = exp as Map<String, Any?>
                    if (e.keys.any { it.startsWith("$") }) return operator(e, act, present, caps, at)
                    if (!present || act !is Map<*, *>) return "$at: want an object, got ${show(act, present)}"
                    for (k in e.keys.sorted()) {
                        match(e[k], act[k], act.containsKey(k), caps, "$at.$k")?.let { return it }
                    }
                    return null
                }
            }
            return "$at: bad matcher $exp"
        }

        private fun sentinel(s: String, act: Any?, present: Boolean, at: String): String? {
            val ok = when (s) {
                "<any>" -> present
                "<absent>" -> !present
                "<empty>" -> !present || act == null || act == ""
                "<uuid>" -> present && act is String && uuid.matches(act)
                "<timestamp>" -> present && act is String && instant(act) != null
                "<fingerprint>" -> present && act is String && fingerprint.matches(act)
                "<string>" -> present && act is String
                "<number>" -> present && act is Number
                "<bool>" -> present && act is Boolean
                "<object>" -> present && act is Map<*, *>
                "<array>" -> present && act is List<*>
                else -> return "$at: unknown sentinel $s"
            }
            return if (ok) null else "$at: want $s, got ${show(act, present)}"
        }

        @Suppress("UNCHECKED_CAST")
        private fun operator(e: Map<String, Any?>, act: Any?, present: Boolean, caps: MutableMap<String, Any?>, at: String): String? {
            for (k in e.keys) if (k !in operators) return "$at: unknown or misplaced operator $k"
            if (e.containsKey("\$match")) match(e["\$match"], act, present, caps, at)?.let { return it }
            (e["\$eq"] as String?)?.let { name ->
                if (!caps.containsKey(name)) return "$at: \$eq $name before \$capture"
                if (!present || !jsonEqual(caps[name], act)) return "$at: want $name (=${show(caps[name])}), got ${show(act, present)}"
            }
            (e["\$ne"] as String?)?.let { name ->
                if (!caps.containsKey(name)) return "$at: \$ne $name before \$capture"
                if (!present || jsonEqual(caps[name], act)) return "$at: want a value other than $name (=${show(caps[name])}), got ${show(act, present)}"
            }
            (e["\$size"] as Number?)?.let { n ->
                val size = when (act) { is List<*> -> act.size; is Map<*, *> -> act.size; else -> -1 }
                if (!present || size != n.toInt()) return "$at: want size $n, got ${show(act, present)}"
            }
            (e["\$length"] as Number?)?.let { n ->
                if (!present || act !is String || act.length != n.toInt()) return "$at: want a string of $n UTF-16 units, got ${show(act, present)}"
            }
            (e["\$repeat"] as Map<String, Any?>?)?.let { r ->
                val want = (r["string"] as String).repeat((r["count"] as Number).toInt())
                if (!present || act != want) return "$at: want ${r["count"]}×${r["string"]}, got ${show(act, present)}"
            }
            (e["\$time"] as String?)?.let { t ->
                val want = instant(t) ?: return "$at: bad \$time $t"
                if (!present || instant(act) != want) return "$at: want instant $t, got ${show(act, present)}"
            }
            (e["\$oneOf"] as List<Any?>?)?.let { alts ->
                var last: String? = null
                var okAny = false
                for (a in alts) {
                    val trial = HashMap(caps)
                    val err = match(a, act, present, trial, at)
                    if (err == null) { caps.putAll(trial); okAny = true; break } else last = err
                }
                if (!okAny) return "$at: no \$oneOf alternative matched (last: $last)"
            }
            (e["\$capture"] as String?)?.let { name ->
                if (!present) return "$at: \$capture $name of an absent value"
                caps[name] = act
            }
            return null
        }

        @Suppress("UNCHECKED_CAST")
        fun expandEach(items: List<*>): List<Any?> {
            val out = ArrayList<Any?>()
            for (it in items) {
                val m = it as? Map<String, Any?>
                val each = m?.get("\$each") as? Map<String, Any?>
                if (m != null && each != null && m.size == 1) {
                    for (i in (each["from"] as Number).toInt()..(each["to"] as Number).toInt()) {
                        out += BehaviourFixtures.substitute(each["match"], i)
                    }
                    continue
                }
                out += it
            }
            return out
        }

        private fun ok2xx(s: Int) = s in 200..299

        /** README "Normalised requests" rules 4-5. */
        fun normalise(recs: List<Recorded>): Map<String, List<Recorded>> {
            val out = LinkedHashMap<String, MutableList<Recorded>>()
            for (r0 in recs) {
                val r = if (r0.path == "/v1/ingest/events") Recorded("/v1/ingest/session", r0.status, r0.body) else r0
                val list = out.getOrPut(r.path) { ArrayList() }
                if (r.path == "/v1/ingest/session" && list.isNotEmpty()) {
                    val last = list.last()
                    if (ok2xx(last.status) && ok2xx(r.status) && jsonEqual(last.body["sessionId"], r.body["sessionId"])) {
                        val merged = LinkedHashMap(last.body)
                        for ((k, v) in r.body) {
                            if (k == "events") {
                                merged[k] = ((last.body["events"] as? List<*>) ?: emptyList<Any?>()) + ((v as? List<*>) ?: emptyList<Any?>())
                            } else {
                                merged[k] = v
                            }
                        }
                        list[list.size - 1] = Recorded(last.path, last.status, merged)
                        continue
                    }
                }
                list += r
            }
            return out
        }

        @Suppress("UNCHECKED_CAST")
        fun checkRequests(exp: List<Any?>, recs: List<Recorded>, caps: MutableMap<String, Any?>): String? {
            val actual = normalise(recs)
            val want = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
            for (e in exp) {
                val m = e as Map<String, Any?>
                want.getOrPut(m["path"] as String) { ArrayList() } += m
            }
            for (p in (actual.keys + want.keys).toSortedSet()) {
                val a = actual[p] ?: emptyList()
                val w = want[p] ?: emptyList()
                if (a.size != w.size) {
                    return "$p: want ${w.size} requests, got ${a.size}: " + a.joinToString(" | ") { "${it.status} ${show(it.body)}" }
                }
                for (i in w.indices) {
                    (w[i]["status"] as Number?)?.let { s -> if (s.toInt() != a[i].status) return "$p[$i]: want status $s, got ${a[i].status}" }
                    if (w[i].containsKey("body")) match(w[i]["body"], a[i].body, true, caps, "$p[$i].body")?.let { return it }
                }
            }
            return null
        }

        private fun delivered(recs: List<Recorded>): List<Any?> =
            (normalise(recs)["/v1/ingest/session"] ?: emptyList())
                .filter { ok2xx(it.status) }
                .flatMap { (it.body["events"] as? List<*>) ?: emptyList<Any?>() }

        @Suppress("UNCHECKED_CAST")
        fun checkEvents(spec: Map<String, Any?>, recs: List<Recorded>, caps: MutableMap<String, Any?>): String? {
            val evs = delivered(recs)
            val want = (spec["events"] as List<Any?>?) ?: emptyList()
            when (spec["mode"]) {
                "exact" -> match(want, evs, true, caps, "events")?.let { return it }
                "contains" -> {
                    var j = 0
                    for ((i, w) in expandEach(want).withIndex()) {
                        var found = false
                        while (j < evs.size) {
                            val trial = HashMap(caps)
                            val ok = match(w, evs[j], true, trial, "event") == null
                            j++
                            if (ok) { caps.putAll(trial); found = true; break }
                        }
                        if (!found) return "events: no event (in order) matches #$i ${show(w)} among ${show(evs)}"
                    }
                }
                else -> return "bad mode ${spec["mode"]}"
            }
            for (a in (spec["absent"] as List<Any?>?) ?: emptyList()) {
                for (e in evs) if (match(a, e, true, HashMap(caps), "event") == null) return "events: ${show(e)} should be absent"
            }
            return null
        }
    }
}
