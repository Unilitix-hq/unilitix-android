package io.unilitix.sdk.util

import org.json.JSONArray
import org.json.JSONObject

internal object Json {

    fun toJson(value: Any?): String = anyToJson(value)

    private fun anyToJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> JSONObject.quote(value)
            is Boolean -> value.toString()
            is Number -> value.toString()
            is Map<*, *> -> {
                val sb = StringBuilder("{")
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(",")
                    sb.append(JSONObject.quote(k.toString()))
                    sb.append(":")
                    sb.append(anyToJson(v))
                    first = false
                }
                sb.append("}")
                sb.toString()
            }
            is List<*> -> {
                val sb = StringBuilder("[")
                var first = true
                for (item in value) {
                    if (!first) sb.append(",")
                    sb.append(anyToJson(item))
                    first = false
                }
                sb.append("]")
                sb.toString()
            }
            is Array<*> -> anyToJson(value.toList())
            else -> JSONObject.quote(value.toString())
        }
    }

    fun fromJson(json: String): Map<String, Any?> {
        return try {
            val obj = JSONObject(json)
            jsonObjectToMap(obj)
        } catch (e: Exception) {
            Logger.e("JSON parse error", e)
            emptyMap()
        }
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            map[key] = jsonValueToKotlin(obj.get(key))
        }
        return map
    }

    private fun jsonArrayToList(arr: JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until arr.length()) {
            list.add(jsonValueToKotlin(arr.get(i)))
        }
        return list
    }

    private fun jsonValueToKotlin(value: Any?): Any? {
        return when (value) {
            is JSONObject -> jsonObjectToMap(value)
            is JSONArray -> jsonArrayToList(value)
            JSONObject.NULL -> null
            else -> value
        }
    }
}
