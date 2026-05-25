package io.unilitix.sdk.network

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.GzipSink
import okio.buffer

internal class GzipInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val originalBody = original.body ?: return chain.proceed(original)

        // Only compress JSON payloads to the SDK API — binary uploads (R2 presigned PUT)
        // must not be gzip-compressed: the body would be invalid and Content-Length becomes
        // unknown (-1), causing R2 to reject the upload with 411 Length Required.
        val contentType = originalBody.contentType()?.toString() ?: ""
        if (!contentType.contains("application/json")) {
            return chain.proceed(original)
        }

        val compressedRequest = original.newBuilder()
            .header("Content-Encoding", "gzip")
            .method(original.method, gzip(originalBody))
            .build()

        return chain.proceed(compressedRequest)
    }

    private fun gzip(body: RequestBody): RequestBody {
        return object : RequestBody() {
            override fun contentType(): MediaType? = body.contentType()
            override fun contentLength(): Long = -1

            override fun writeTo(sink: BufferedSink) {
                val gzipSink = GzipSink(sink).buffer()
                body.writeTo(gzipSink)
                gzipSink.close()
            }
        }
    }
}
