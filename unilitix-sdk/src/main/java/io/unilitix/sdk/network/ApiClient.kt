package io.unilitix.sdk.network

import io.unilitix.sdk.BuildConfig
import io.unilitix.sdk.engine.EngineTransport
import io.unilitix.sdk.engine.HttpResponse
import io.unilitix.sdk.storage.PendingScreenshot
import io.unilitix.sdk.util.Logger
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Screenshots confirmed (safe to delete) and the API failure that stopped the upload, if any. */
internal data class ScreenshotUploadResult(
    val confirmedIds: Set<Long>,
    val failure: ApiResult? = null,
    /** Shots the server refused for good (too large, bad ordinal): delete them. */
    val rejectedIds: Set<Long> = emptySet(),
)

/** A non-2xx response from the Unilitix API, classified. */
internal class HttpStatusException(val result: ApiResult, code: Int) : IOException("HTTP $code")

/**
 * HTTP to the Unilitix API. JSON bodies are gzipped ([GzipInterceptor]);
 * every request carries `x-unilitix-key` and
 * `User-Agent: Unilitix-Android/<version>` (plus ` (<wrapper>/<version>)`
 * when a Flutter or React Native SDK wraps this one) and times out after the
 * contract's 40 s.
 *
 * As the engine's [EngineTransport] it answers on OkHttp's threads; the
 * engine hops back onto its own.
 */
internal class ApiClient(
    private val apiUrl: String,
    private val apiKey: String,
) : EngineTransport {
    private val jsonType = "application/json".toMediaType()

    /** `flutter/3.0.0`-style suffix for the User-Agent, set by [io.unilitix.sdk.Unilitix.setWrapper]. */
    @Volatile var wrapper: String? = null

    private val client = OkHttpClient.Builder()
        .addInterceptor(GzipInterceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Contract requestTimeoutSeconds: the whole call, connect to last byte.
        .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .apply {
            // HTTP header logging exists only in debug builds of the library itself
            // (never in the published AAR), and is further gated on debugLogging via
            // Logger.d. Bodies are never logged; the API key header is redacted.
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor { Logger.d("OkHttp: $it") }.apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                    redactHeader("x-unilitix-key")
                    redactHeader("Authorization")
                    redactHeader("Cookie")
                    redactHeader("Set-Cookie")
                })
                addInterceptor(RedactingLoggingInterceptor())
            }
        }
        .build()

    // No certificate pinning. The API's certificates are issued by Let's
    // Encrypt and rotate every few months (including intermediates), and an
    // SDK pin cannot be updated in apps already in users' hands, so a pin
    // would eventually break every install. Apps that want pinning can add it
    // for api.unilitix.com in their own network_security_config.xml.

    private fun userAgent(): String {
        val w = wrapper
        return "Unilitix-Android/${BuildConfig.SDK_VERSION}" + if (w.isNullOrEmpty()) "" else " ($w)"
    }

    private fun apiRequest(path: String, body: String): Request = Request.Builder()
        .url("$apiUrl$path")
        .post(body.toRequestBody(jsonType))
        .header("x-unilitix-key", apiKey)
        .header("User-Agent", userAgent())
        .build()

    // ── Engine transport ────────────────────────────────────────────────

    override fun post(path: String, json: String, callback: (HttpResponse) -> Unit) {
        // Never log the payload itself: it carries user ids and traits.
        Logger.d("ApiClient: POST $path (${json.length} chars)")
        val call = try {
            client.newCall(apiRequest(path, json))
        } catch (t: Throwable) {
            callback(HttpResponse(0))
            return
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(HttpResponse(0))
            }

            override fun onResponse(call: Call, response: Response) {
                val r = try {
                    response.use {
                        val headers = HashMap<String, String>()
                        it.header("Retry-After")?.let { v -> headers["Retry-After"] = v }
                        HttpResponse(it.code, headers, it.body?.string()?.take(MAX_RESPONSE_CHARS))
                    }
                } catch (e: IOException) {
                    HttpResponse(0)
                }
                callback(r)
            }
        })
    }

    // ── Replay media (called on the replay upload thread) ────────────────

    /** Uploads one session's snapshots ([snapshotsJson] are elements of the contract's `snapshots` array). */
    fun uploadSnapshots(sessionId: String, snapshotsJson: List<String>): ApiResult {
        val payload = "{\"sessionId\":${JSONObject.quote(sessionId)},\"snapshots\":[${snapshotsJson.joinToString(",")}]}"
        return try {
            client.newCall(apiRequest("/v1/ingest/snapshots", payload)).execute().use { response ->
                if (response.isSuccessful) ApiResult.Success(sessionId)
                else ApiResult.failure(response.code, response.header("Retry-After"))
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e)
        }
    }

    /**
     * Uploads screenshots to storage through presigned URLs (init → PUT →
     * confirm), grouped by session and encoding. Returns the ids confirmed (safe
     * to delete). A retryable or auth failure of an API call stops the upload
     * and is returned for the caller's backoff.
     */
    fun uploadScreenshots(screenshots: List<PendingScreenshot>): ScreenshotUploadResult {
        val confirmedIds = mutableSetOf<Long>()
        val rejectedIds = mutableSetOf<Long>()
        val grouped = screenshots.groupBy { it.sessionId to it.format }
        for ((key, shots) in grouped) {
            for (chunk in shots.chunked(INIT_CHUNK)) {
                try {
                    confirmedIds.addAll(uploadChunk(key.first, key.second, chunk))
                } catch (e: HttpStatusException) {
                    if (RetryPolicy.classify(e.result) == UploadOutcome.DROPPED) {
                        // 413 (frame too large) and 400 (bad ordinal) never succeed: drop them.
                        // Other refusals (a session the server does not know yet) wait for the next flush.
                        val code = (e.result as? ApiResult.ClientError)?.code
                        if (code == 413 || code == 400) rejectedIds.addAll(chunk.map { it.id })
                        Logger.w("ApiClient: screenshots for session ${key.first.take(8)} refused (${e.message})")
                    } else {
                        return ScreenshotUploadResult(confirmedIds, e.result, rejectedIds)
                    }
                } catch (e: IOException) {
                    return ScreenshotUploadResult(confirmedIds, ApiResult.NetworkError(e), rejectedIds)
                } catch (e: Exception) {
                    Logger.w("ApiClient: screenshot upload failed for session ${key.first.take(8)}: ${e.message}")
                }
            }
        }
        return ScreenshotUploadResult(confirmedIds, null, rejectedIds)
    }

    private fun uploadChunk(sessionId: String, format: String, shots: List<PendingScreenshot>): Set<Long> {
        // The capture ordinals, so each shot gets its own storage key, and the
        // exact byte sizes, so the presigned PUT is bound to them (the server
        // can then require sizes: SCREENSHOT_REQUIRE_SIZES).
        val initBody = JSONObject().apply {
            put("sessionId", sessionId)
            put("count", shots.size)
            put("ordinals", JSONArray().also { a -> shots.forEach { a.put(it.ordinal) } })
            put("sizes", JSONArray().also { a -> shots.forEach { a.put(it.imageBytes.size) } })
            put("format", format)
        }
        val initResponse = postJson("/v1/ingest/screenshots/init", initBody)
        // "slots" is the API's name; "uploads" was read by SDKs up to 1.10.2.
        val slots = initResponse.optJSONArray("slots") ?: initResponse.optJSONArray("uploads") ?: JSONArray()
        val byOrdinal = shots.associateBy { it.ordinal }
        val contentType = (if (format == PendingScreenshot.FORMAT_JPEG) "image/jpeg" else "image/webp").toMediaType()

        val confirmedMeta = JSONArray()
        val confirmedIds = mutableSetOf<Long>()
        for (i in 0 until slots.length()) {
            val slot = slots.optJSONObject(i) ?: continue
            val ordinal = slot.optInt("ordinal", -1)
            val url = slot.optString("presignedUrl", "")
            val shot = byOrdinal[ordinal] ?: shots.getOrNull(i) ?: continue
            if (url.isEmpty()) continue
            val put = Request.Builder().url(url).put(shot.imageBytes.toRequestBody(contentType)).build()
            try {
                client.newCall(put).execute().use { response ->
                    if (response.isSuccessful) {
                        confirmedMeta.put(JSONObject().apply {
                            put("ordinal", shot.ordinal)
                            put("screenName", shot.screenName)
                            put("viewportWidth", shot.viewportWidth)
                            put("viewportHeight", shot.viewportHeight)
                            put("bytes", shot.imageBytes.size)
                            put("capturedAt", isoTimestamp(shot.capturedAt))
                        })
                        confirmedIds.add(shot.id)
                    } else {
                        Logger.w("ApiClient: screenshot ${shot.ordinal} upload failed: ${response.code}")
                    }
                }
            } catch (e: IOException) {
                Logger.w("ApiClient: screenshot ${shot.ordinal} PUT failed: ${e.message}")
            }
        }
        if (confirmedMeta.length() > 0) {
            postJson("/v1/ingest/screenshots/confirm", JSONObject().apply {
                put("sessionId", sessionId)
                put("screenshots", confirmedMeta)
            })
        }
        return confirmedIds
    }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        client.newCall(apiRequest(path, body.toString())).execute().use { response ->
            val bodyStr = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw HttpStatusException(
                    ApiResult.failure(response.code, response.header("Retry-After"), bodyStr.take(200)),
                    response.code
                )
            }
            return try { JSONObject(bodyStr) } catch (e: Exception) { JSONObject() }
        }
    }

    private fun isoTimestamp(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMs))
    }

    companion object {
        /** Contract `requestTimeoutSeconds`. */
        const val REQUEST_TIMEOUT_SECONDS = 40L
        private const val MAX_RESPONSE_CHARS = 64 * 1024
        /** Screenshots reserved per init request. */
        private const val INIT_CHUNK = 20
    }
}
