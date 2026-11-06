package io.unilitix.sdk.engine

/**
 * Property and trait normalisation (contract 1.4.0 "Event properties",
 * reference `NormalizeProperties` in the api repo):
 * - non-finite numbers become null;
 * - strings, event names, screen names and user ids are cut to
 *   [MAX_STRING_LENGTH] UTF-16 units, never inside a surrogate pair;
 * - a map or list that would sit deeper than [MAX_DEPTH] (the top-level map
 *   is level 1) becomes the string [TRUNCATED];
 * - at most [MAX_KEYS] entries per map (first ones, in iteration order) or
 *   list.
 * Values of other types (dates, enums, app objects) are sent as their
 * `toString()`, cut like any string.
 */
internal object Normalize {
    const val MAX_DEPTH = 5
    const val MAX_KEYS = 100
    const val MAX_STRING_LENGTH = 1024
    const val TRUNCATED = "[Truncated]"

    /** [s] cut to at most [max] UTF-16 code units without splitting a surrogate pair. */
    fun string(s: String, max: Int = MAX_STRING_LENGTH): String {
        if (s.length <= max) return s
        var end = max
        if (end > 0 && Character.isHighSurrogate(s[end - 1]) && Character.isLowSurrogate(s[end])) end--
        return s.substring(0, end)
    }

    /** Normalised copy of [m]; null stays null. */
    fun properties(m: Map<*, *>?): LinkedHashMap<String, Any?>? = if (m == null) null else map(m, 1)

    private fun map(m: Map<*, *>, level: Int): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in m) {
            if (out.size >= MAX_KEYS) break
            out[string(k.toString())] = value(v, level)
        }
        return out
    }

    private fun list(items: Iterable<*>, level: Int): List<Any?> {
        val out = ArrayList<Any?>()
        for (e in items) {
            if (out.size >= MAX_KEYS) break
            out.add(value(e, level + 1))
        }
        return out
    }

    /** [v], which sits inside a container at [level]. */
    private fun value(v: Any?, level: Int): Any? = when (v) {
        null -> null
        is Boolean -> v
        is Double -> if (v.isNaN() || v.isInfinite()) null else v
        is Float -> if (v.isNaN() || v.isInfinite()) null else v.toDouble()
        is Int, is Long, is Short, is Byte -> v
        is Number -> v.toDouble().let { d -> if (d.isNaN() || d.isInfinite()) null else v }
        is CharSequence -> string(v.toString())
        is Map<*, *> -> if (level + 1 > MAX_DEPTH) TRUNCATED else map(v, level + 1)
        is Iterable<*> -> if (level + 1 > MAX_DEPTH) TRUNCATED else list(v, level)
        is Array<*> -> if (level + 1 > MAX_DEPTH) TRUNCATED else list(v.asList(), level)
        is IntArray -> if (level + 1 > MAX_DEPTH) TRUNCATED else list(v.asList(), level)
        is LongArray -> if (level + 1 > MAX_DEPTH) TRUNCATED else list(v.asList(), level)
        is DoubleArray -> if (level + 1 > MAX_DEPTH) TRUNCATED else list(v.asList(), level)
        is BooleanArray -> if (level + 1 > MAX_DEPTH) TRUNCATED else list(v.asList(), level)
        else -> string(v.toString())
    }
}

/**
 * The contract's `networkTypeRules` (1.4.0): `WIFI ETHERNET 5G 4G 3G 2G
 * CELLULAR OFFLINE UNKNOWN`, first match wins. [transport] is `wifi`,
 * `ethernet`, `cellular`, `none` or `other`; [generation] `2g`..`5g` or
 * `unknown` (cellular only).
 */
internal object NetworkTypes {
    const val WIFI = "WIFI"
    const val ETHERNET = "ETHERNET"
    const val CELLULAR = "CELLULAR"
    const val OFFLINE = "OFFLINE"
    const val UNKNOWN = "UNKNOWN"

    val ALL = listOf("WIFI", "ETHERNET", "5G", "4G", "3G", "2G", "CELLULAR", "OFFLINE", "UNKNOWN")

    fun canonical(transport: String, generation: String?, online: Boolean): String {
        if (!online) return OFFLINE
        return when (transport) {
            "none" -> OFFLINE
            "wifi" -> WIFI
            "ethernet" -> ETHERNET
            "cellular" -> when (generation) {
                "5g" -> "5G"
                "4g" -> "4G"
                "3g" -> "3G"
                "2g" -> "2G"
                else -> CELLULAR
            }
            else -> UNKNOWN
        }
    }
}
