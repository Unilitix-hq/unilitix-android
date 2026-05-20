package io.unilitix.sdk.core

import org.json.JSONObject

internal data class Snapshot(
    val capturedAt: Long,
    val ordinal: Int,
    val screenName: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val hierarchy: JSONObject
)
