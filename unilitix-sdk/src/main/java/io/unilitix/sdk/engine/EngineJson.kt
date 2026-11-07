package io.unilitix.sdk.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Already-encoded JSON, written verbatim by [EngineJson.write]. */
internal class RawJson(val json: String)

/**
 * JSON for the engine: pure Kotlin, so the engine and the behaviour-fixture
 * runner work in JVM unit tests (org.json is a stub there). Maps keep their
 * iteration order. Non-finite numbers are written as `null` (the contract
 * normalises them to null; JSON has no NaN).
 */
internal object EngineJson {

    fun write(value: Any?): String = StringBuilder(256).also { append(it, value) }.toString()

    fun append(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is RawJson -> sb.append(value.json)
            is String -> quote(sb, value)
            is CharSequence -> quote(sb, value.toString())
            is Boolean -> sb.append(if (value) "true" else "false")
            is Double -> if (value.isNaN() || value.isInfinite()) sb.append("null") else sb.append(value.toString())
            is Float -> if (value.isNaN() || value.isInfinite()) sb.append("null") else sb.append(value.toDouble().toString())
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Number -> {
                val d = value.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(value.toString())
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    append(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    append(sb, v)
                }
                sb.append(']')
            }
            is Array<*> -> append(sb, value.asList())
            is IntArray -> append(sb, value.asList())
            is LongArray -> append(sb, value.asList())
            is DoubleArray -> append(sb, value.asList())
            is BooleanArray -> append(sb, value.asList())
            else -> quote(sb, value.toString())
        }
    }

    private const val HEX = "0123456789abcdef"

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 || c == ' ' || c == ' ' -> {
                    sb.append("\\u")
                    val code = c.code
                    sb.append(HEX[(code shr 12) and 0xf]).append(HEX[(code shr 8) and 0xf])
                        .append(HEX[(code shr 4) and 0xf]).append(HEX[code and 0xf])
                }
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    /**
     * Parses one JSON value: objects as LinkedHashMap (file order), arrays as
     * ArrayList, integers as Long, other numbers as Double.
     * Throws [IllegalArgumentException] on malformed input.
     */
    fun parse(text: String): Any? {
        val r = Reader(text)
        val v = r.value()
        r.ws()
        require(r.i == text.length) { "trailing data at ${r.i}" }
        return v
    }

    /** [parse] for an object, or null when the text is not a JSON object. */
    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?>? = try {
        parse(text) as? Map<String, Any?>
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private class Reader(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\r' || s[i] == '\n')) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw IllegalArgumentException("bad value at $i")
            }
        }
        fun lit(w: String, v: Any?): Any? {
            require(s.startsWith(w, i)) { "bad literal at $i" }
            i += w.length
            return v
        }
        fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                require(i < s.length && s[i] == '"') { "expected key at $i" }
                val k = str()
                ws()
                require(i < s.length && s[i] == ':') { "expected : at $i" }
                i++
                out[k] = value()
                ws()
                require(i < s.length) { "unexpected end" }
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected , or } at $i")
                }
            }
        }
        fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                ws()
                require(i < s.length) { "unexpected end" }
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected , or ] at $i")
                }
            }
        }
        fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        require(i < s.length) { "bad escape" }
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "bad unicode escape" }
                                b.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }
        fun num(): Any {
            val start = i
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            val t = s.substring(start, i)
            val isInt = t.none { it == '.' || it == 'e' || it == 'E' }
            if (isInt) t.toLongOrNull()?.let { return it }
            return t.toDoubleOrNull() ?: throw IllegalArgumentException("bad number $t")
        }
    }
}

/** RFC 3339 UTC timestamps with milliseconds, as every event and session field uses. */
internal object IsoTime {
    private val format = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    fun format(epochMs: Long): String = format.get()!!.format(Date(epochMs))
}
