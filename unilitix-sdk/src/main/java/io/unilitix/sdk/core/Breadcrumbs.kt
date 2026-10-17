package io.unilitix.sdk.core

import java.util.ArrayDeque

internal data class Breadcrumb(
    val type: String,
    val screen: String?,
    val timestamp: Long,
    val message: String? = null
)

/**
 * The trail attached to every crash and exception report: the last
 * [MAX_SIZE] events (taps, navigation, custom events) plus anything the host
 * app adds through `Unilitix.addBreadcrumb`. Same cap as the Flutter SDK.
 */
internal class Breadcrumbs {
    private val buffer = ArrayDeque<Breadcrumb>(MAX_SIZE)

    @Synchronized
    fun add(type: String, screen: String?, message: String? = null) {
        if (buffer.size >= MAX_SIZE) {
            buffer.pollFirst()
        }
        buffer.addLast(
            Breadcrumb(
                type = type,
                screen = screen,
                timestamp = System.currentTimeMillis(),
                message = message
            )
        )
    }

    @Synchronized
    fun snapshot(): List<Breadcrumb> = buffer.toList()

    /** The screen of the most recent breadcrumb that had one. */
    @Synchronized
    fun lastScreen(): String? = buffer.lastOrNull { it.screen != null }?.screen

    /** Wire form of the trail, as the ingest contract's `breadcrumbs` array. */
    @Synchronized
    fun toWire(): List<Map<String, Any>> = buffer.map { crumb ->
        val m = LinkedHashMap<String, Any>()
        m["type"] = crumb.type
        m["screen"] = crumb.screen ?: ""
        crumb.message?.let { m["message"] = it }
        m["timestamp"] = crumb.timestamp
        m
    }

    companion object {
        const val MAX_SIZE = 50
    }
}
