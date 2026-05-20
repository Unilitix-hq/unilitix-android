package io.unilitix.sdk.core

import java.util.ArrayDeque

internal data class Breadcrumb(
    val type: String,
    val screen: String?,
    val timestamp: Long
)

internal class Breadcrumbs {
    private val buffer = ArrayDeque<Breadcrumb>(MAX_SIZE)

    @Synchronized
    fun add(type: String, screen: String?) {
        if (buffer.size >= MAX_SIZE) {
            buffer.pollFirst()
        }
        buffer.addLast(Breadcrumb(type = type, screen = screen, timestamp = System.currentTimeMillis()))
    }

    @Synchronized
    fun snapshot(): List<Breadcrumb> = buffer.toList()

    companion object {
        private const val MAX_SIZE = 10
    }
}
