package io.unilitix.sdk.network

import io.unilitix.sdk.BuildConfig
import io.unilitix.sdk.core.Snapshot
import io.unilitix.sdk.storage.PendingScreenshot
import io.unilitix.sdk.util.Logger
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

internal class ApiClient(
    private val apiUrl: String,
    private val apiKey: String,
) {
    private val json = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .addInterceptor(GzipInterceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .applyCertPinning()
        .apply {
            // Compile-time guard — BODY logging cannot be enabled in release builds regardless
            // of what the host app sets in UnilitixConfig.debugLogging.
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor { Logger.d("OkHttp: $it") }.apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                })
                addInterceptor(RedactingLoggingInterceptor())
            }
        }
        .build()

    // Certificate pinning is active in release builds only — debug allows proxy tools (Charles, mitmproxy).
    // TODO: Replace placeholder SHA-256 pins with real ones from your production API host.
    //   Get pins: openssl s_client -connect <host>:443 | openssl x509 -pubkey -noout |
    //             openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
    private fun buildCertificatePinner(): CertificatePinner = CertificatePinner.Builder()
        .add(
            BuildConfig.API_HOST,
            "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", // TODO: primary pin
            "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=", // TODO: backup pin
        )
        .build()

    private fun OkHttpClient.Builder.applyCertPinning(): OkHttpClient.Builder {
        if (!BuildConfig.DEBUG) certificatePinner(buildCertificatePinner())
        return this
    }

    suspend fun ingest(payload: String): ApiResult {
        Logger.d("ApiClient payload (${payload.length} chars): ${payload.take(500)}")
        val request = Request.Builder()
            .url("$apiUrl/v1/ingest/session")
            .post(payload.toRequestBody(json))
            .header("x-unilitix-key", apiKey)
            .header("User-Agent", "Unilitix-Android/${BuildConfig.SDK_VERSION}")
            .build()

        return try {
            val response = client.newCall(request).execute()
            response.use {
                when {
                    it.isSuccessful -> {
                        val body = it.body?.string() ?: "{}"
                        val sessionId = try { JSONObject(body).optString("sessionId", "") } catch (e: Exception) { "" }
                        ApiResult.Success(sessionId)
                    }
                    it.code == 429 -> {
                        val retryAfter = it.header("Retry-After")?.toLongOrNull()?.times(1000) ?: 5_000L
                        ApiResult.RateLimit(retryAfter)
                    }
                    it.code in 400..499 -> ApiResult.ClientError(it.code, it.body?.string() ?: "")
                    else -> ApiResult.ServerError(it.code)
                }
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e)
        }
    }

    suspend fun uploadSnapshots(sessionId: String, snapshots: List<Snapshot>) {
        snapshots.chunked(20).forEach { chunk ->
            val payload = buildSnapshotPayload(sessionId, chunk)
            val request = Request.Builder()
                .url("$apiUrl/v1/ingest/snapshots")
                .post(payload.toRequestBody(json))
                .header("x-unilitix-key", apiKey)
                .header("User-Agent", "Unilitix-Android/${BuildConfig.SDK_VERSION}")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Logger.d("ApiClient: uploaded ${chunk.size} snapshots for session $sessionId")
                    } else {
                        Logger.w("ApiClient: snapshot upload ${response.code} for session $sessionId")
                    }
                }
            } catch (e: IOException) {
                Logger.w("ApiClient: snapshot upload failed: ${e.message}")
            }
        }
    }

    private fun buildSnapshotPayload(sessionId: String, chunk: List<Snapshot>): String {
        val snapshotsArray = JSONArray()
        for (s in chunk) {
            snapshotsArray.put(JSONObject().apply {
                put("capturedAt", isoTimestamp(s.capturedAt))
                put("ordinal", s.ordinal)
                put("screenName", s.screenName)
                put("viewportWidth", s.viewportWidth)
                put("viewportHeight", s.viewportHeight)
                put("hierarchy", s.hierarchy)
            })
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("snapshots", snapshotsArray)
        }.toString()
    }

    // Upload screenshots to R2 via presigned URLs (2-step: init → PUT → confirm).
    // Returns the Room DB IDs of shots that were successfully confirmed so callers
    // can delete only those, not the entire pending batch.
    suspend fun uploadScreenshots(screenshots: List<PendingScreenshot>): Set<Long> {
        val confirmedIds = mutableSetOf<Long>()
        val grouped = screenshots.groupBy { it.sessionId }
        for ((sessionId, shots) in grouped) {
            try {
                confirmedIds.addAll(uploadScreenshotsForSession(sessionId, shots))
            } catch (e: Exception) {
                Logger.w("ApiClient: screenshot upload failed for session $sessionId: ${e.message}")
            }
        }
        return confirmedIds
    }

    private suspend fun uploadScreenshotsForSession(sessionId: String, shots: List<PendingScreenshot>): Set<Long> {
        // Pass actual capture ordinals so the server assigns the correct R2 key for each
        // shot. Without this, a second batch (ordinals 100+) would get slots 0..N-1 from
        // the server and no shot would match, silently skipping the entire batch.
        val ordinalsArray = JSONArray()
        shots.forEach { ordinalsArray.put(it.ordinal) }

        val initBody = JSONObject().apply {
            put("sessionId", sessionId)
            put("count", shots.size)
            put("ordinals", ordinalsArray)
        }
        val initResponse = postJson("/v1/ingest/screenshots/init", initBody)
        val uploads = initResponse.getJSONArray("uploads")

        val confirmedMeta = mutableListOf<JSONObject>()
        val confirmedIds = mutableSetOf<Long>()

        for (i in 0 until uploads.length()) {
            val slot = uploads.getJSONObject(i)
            val slotOrdinal = slot.getInt("ordinal")
            val presignedUrl = slot.getString("presignedUrl")
            // Use index-based mapping — shots[i] corresponds to the i-th slot.
            val shot = shots.getOrNull(i) ?: continue

            val request = Request.Builder()
                .url(presignedUrl)
                .put(shot.imageBytes.toRequestBody("image/webp".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Logger.d("ApiClient: screenshot ordinal $slotOrdinal uploaded (${shot.imageBytes.size}B)")
                        confirmedMeta.add(JSONObject().apply {
                            put("ordinal", slotOrdinal)
                            put("screenName", shot.screenName)
                            put("viewportWidth", shot.viewportWidth)
                            put("viewportHeight", shot.viewportHeight)
                            put("bytes", shot.imageBytes.size)
                            put("capturedAt", isoTimestamp(shot.capturedAt))
                        })
                        confirmedIds.add(shot.id)
                    } else {
                        Logger.w("ApiClient: screenshot $slotOrdinal upload failed: ${response.code}")
                    }
                }
            } catch (e: IOException) {
                Logger.w("ApiClient: screenshot $slotOrdinal PUT failed: ${e.message}")
            }
        }

        if (confirmedMeta.isNotEmpty()) {
            val confirmBody = JSONObject().apply {
                put("sessionId", sessionId)
                put("screenshots", JSONArray(confirmedMeta))
            }
            postJson("/v1/ingest/screenshots/confirm", confirmBody)
            Logger.d("ApiClient: confirmed ${confirmedMeta.size} screenshots for session $sessionId")
        }

        return confirmedIds
    }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("$apiUrl$path")
            .post(body.toString().toRequestBody(json))
            .header("x-unilitix-key", apiKey)
            .header("User-Agent", "Unilitix-Android/${BuildConfig.SDK_VERSION}")
            .build()
        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: "{}"
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $bodyStr")
            return JSONObject(bodyStr)
        }
    }

    private fun isoTimestamp(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMs))
    }
}
