package io.unilitix.sdk.platform

import io.unilitix.sdk.UnilitixConfig
import io.unilitix.sdk.core.Screenshot
import io.unilitix.sdk.core.Snapshot
import io.unilitix.sdk.engine.Engine
import io.unilitix.sdk.network.ApiClient
import io.unilitix.sdk.network.UploadOutcome
import io.unilitix.sdk.storage.EventDatabase
import io.unilitix.sdk.storage.PendingScreenshot
import io.unilitix.sdk.storage.PendingSnapshot
import io.unilitix.sdk.storage.ScreenshotQueueCap
import io.unilitix.sdk.util.Logger
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Session replay media on the device: screenshots and wireframe snapshots,
 * each stored with the session it was captured in (1.11.0: snapshots are
 * persisted too, so they can no longer be uploaded under a later session),
 * and uploaded once the server has acknowledged a batch of that session.
 *
 * Uploads share the engine's gate (consent granted, no 401/403 latch, no
 * backoff window, online); screenshots also wait for Wi-Fi when
 * `uploadScreenshotsOnWifiOnly`. Everything runs on one background thread.
 */
internal class ReplayPipeline(
    private val config: UnilitixConfig,
    private val db: EventDatabase,
    private val api: ApiClient,
    private val engine: () -> Engine?,
    private val isWifi: () -> Boolean,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "unilitix-replay").also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY }
    }
    private val uploadQueued = AtomicBoolean(false)

    private fun submit(what: String, block: () -> Unit) {
        try {
            executor.execute {
                try { block() } catch (t: Throwable) { Logger.w("Unilitix: replay $what failed: ${t.javaClass.simpleName}: ${t.message}") }
            }
        } catch (t: Throwable) {
            Logger.d("Unilitix: replay $what not scheduled: ${t.javaClass.simpleName}")
        }
    }

    fun addScreenshot(sessionId: String, shot: Screenshot, format: String = PendingScreenshot.FORMAT_WEBP) = submit("screenshot") {
        val dao = db.screenshotDao()
        dao.insert(
            PendingScreenshot(
                sessionId = sessionId,
                ordinal = shot.ordinal,
                screenName = shot.screenName,
                viewportWidth = shot.viewportWidth,
                viewportHeight = shot.viewportHeight,
                capturedAt = shot.capturedAt,
                imageBytes = shot.imageBytes,
                format = format,
            )
        )
        // Bound the queue by count and bytes, oldest first: frames can wait
        // for Wi-Fi across many sessions.
        val evict = ScreenshotQueueCap.idsToEvict(dao.sizesNewestFirst(), maxCount = config.maxScreenshotsPerSession)
        if (evict.isNotEmpty()) {
            evict.chunked(500).forEach { dao.deleteByIds(it) }
            Logger.d("Unilitix: screenshot queue full, dropped ${evict.size} oldest")
        }
    }

    fun addSnapshot(sessionId: String, snapshot: Snapshot) = submit("snapshot") {
        val element = JSONObject().apply {
            put("capturedAt", iso(snapshot.capturedAt))
            put("ordinal", snapshot.ordinal)
            put("screenName", snapshot.screenName)
            put("viewportWidth", snapshot.viewportWidth)
            put("viewportHeight", snapshot.viewportHeight)
            put("hierarchy", snapshot.hierarchy)
        }
        insertSnapshot(sessionId, snapshot.ordinal, element.toString())
    }

    /** A snapshot in the contract's JSON shape from a wrapper SDK; [ordinal] assigned by the SDK. */
    fun addSnapshotJson(sessionId: String, ordinal: Int, element: JSONObject) = submit("snapshot") {
        element.put("ordinal", ordinal)
        insertSnapshot(sessionId, ordinal, element.toString())
    }

    private fun insertSnapshot(sessionId: String, ordinal: Int, json: String) {
        val dao = db.snapshotDao()
        dao.insert(PendingSnapshot(sessionId = sessionId, ordinal = ordinal, json = json))
        val over = dao.count() - MAX_SNAPSHOTS_HELD
        if (over > 0) dao.deleteOldest(over)
    }

    /** Consent denied: every queued frame and snapshot is deleted. */
    fun purge() = submit("purge") {
        db.screenshotDao().deleteAll()
        db.snapshotDao().deleteAll()
    }

    /** Uploads what may go now; coalesced (one pending request at a time). */
    fun scheduleUpload() {
        if (!uploadQueued.compareAndSet(false, true)) return
        submit("upload") {
            uploadQueued.set(false)
            uploadNow()
        }
    }

    /** Replay upload thread (or a caller that waits for it, e.g. FlushWorker). */
    internal fun uploadNow() {
        val e = engine() ?: return
        if (!e.mediaUploadAllowed()) return
        if (!uploadSnapshots(e)) return
        uploadScreenshots(e)
    }

    /** False when the gate closed (stop for now). */
    private fun uploadSnapshots(e: Engine): Boolean {
        val dao = db.snapshotDao()
        val pending = dao.oldest(SNAPSHOT_SCAN)
        if (pending.isEmpty()) return true
        for ((session, items) in pending.groupBy { it.sessionId }) {
            if (!e.isSessionAcknowledged(session)) continue
            for (chunk in items.chunked(SNAPSHOT_CHUNK)) {
                if (!e.mediaUploadAllowed()) return false
                val result = api.uploadSnapshots(session, chunk.map { it.json })
                when (e.gate.note(result, "Snapshots (${chunk.size})")) {
                    UploadOutcome.SENT, UploadOutcome.DROPPED -> dao.delete(chunk.map { it.id })
                    else -> return false
                }
            }
        }
        return true
    }

    private fun uploadScreenshots(e: Engine) {
        if (config.uploadScreenshotsOnWifiOnly && !isWifi()) return
        val dao = db.screenshotDao()
        val pending = dao.getOldest(SCREENSHOT_SCAN).filter { e.isSessionAcknowledged(it.sessionId) }
        if (pending.isEmpty()) return
        val result = api.uploadScreenshots(pending)
        val done = result.confirmedIds + result.rejectedIds
        if (done.isNotEmpty()) dao.deleteByIds(done.toList())
        result.failure?.let { e.gate.note(it, "Screenshots") }
        Logger.d("Unilitix: ${result.confirmedIds.size}/${pending.size} screenshots uploaded")
    }

    fun shutdown() {
        try { executor.shutdownNow() } catch (_: Throwable) {}
    }

    private fun iso(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    companion object {
        /** Snapshots kept on the device (all sessions). */
        const val MAX_SNAPSHOTS_HELD = 1_500
        private const val SNAPSHOT_SCAN = 200
        private const val SNAPSHOT_CHUNK = 20
        private const val SCREENSHOT_SCAN = 60
    }
}
