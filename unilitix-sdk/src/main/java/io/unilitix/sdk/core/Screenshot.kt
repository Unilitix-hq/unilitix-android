package io.unilitix.sdk.core

internal data class Screenshot(
    val ordinal: Int,
    val capturedAt: Long,
    val screenName: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val imageBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Screenshot) return false
        return ordinal == other.ordinal && capturedAt == other.capturedAt && screenName == other.screenName
    }

    override fun hashCode(): Int = 31 * ordinal + capturedAt.hashCode()
}
