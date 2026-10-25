package com.github.avbox.osc.util.kvcodec

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken

import java.lang.reflect.Type

class KVDecoder private constructor(private val quiet: Boolean) {

    interface TypeRegistry {
        fun typeOf(key: String): Type?
    }

    @Volatile
    private var registry: TypeRegistry? = null
    @Volatile
    private var quietInstance: KVDecoder? = null

    constructor() : this(false)

    fun quiet(): KVDecoder {
        var copy = quietInstance
        if (copy == null) {
            copy = KVDecoder(true)
            copy.registry = registry
            quietInstance = copy
        }
        return copy
    }

    fun setRegistry(typeRegistry: TypeRegistry?) {
        registry = typeRegistry
        val copy = quietInstance
        if (copy != null) copy.registry = typeRegistry
    }

    private fun registeredType(key: String): Type? {
        val current = registry
        return current?.typeOf(key)
    }

    fun encode(value: Any): String {
        return if (value is String) value else JSON_PREFIX + GSON.toJson(value)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> decode(key: String, raw: Any?, defaultValue: T?): T? {
        val wanted: Class<*>? = if (defaultValue == null || defaultValue.javaClass == Any::class.java)
            null else defaultValue.javaClass
        if (wanted != null && wanted.isInstance(raw)) return raw as T

        val type = resolveType(key, wanted)
        var parsed: Any? = null
        if (type == null) {
            val json = jsonTextOf(raw)
            parsed = if (json == null) null else GSON.fromJson(json, JsonElement::class.java)
            if (parsed == null) {
                report("echo-kv type-unresolved key=" + key + " raw=" + typeName(raw)
                        + " default=" + (wanted?.name ?: "null"))
                return null
            }
            return parsed as T
        }
        try {
            parsed = parse(raw, type)
        } catch (e: Throwable) {
            report("echo-kv decode-failed key=" + key + " type=" + type + " raw=" + describe(raw) + " " + e)
            return null
        }
        if (type is Class<*>) {
            parsed = coerceNumber(type, parsed)
        }
        if (assignable(type, parsed)) return parsed as T

        report("echo-kv type-mismatch key=" + key + " expected=" + type + " actual=" + typeName(parsed)
                + " raw=" + describe(raw))
        return null
    }

    fun coerceNumber(wanted: Class<*>, value: Any?): Any? {
        if (value !is Number) return value
        val number = value
        val longValue = number.toLong()
        val doubleValue = number.toDouble()
        if (wanted == Int::class.javaPrimitiveType || wanted == Int::class.javaObjectType) {
            return if (longValue < Int.MIN_VALUE || longValue > Int.MAX_VALUE) null else longValue.toInt()
        }
        if (wanted == Long::class.javaPrimitiveType || wanted == Long::class.javaObjectType) {
            return if (doubleValue < Long.MIN_VALUE.toDouble() || doubleValue > Long.MAX_VALUE.toDouble()) null else longValue
        }
        if (wanted == Short::class.javaPrimitiveType || wanted == Short::class.javaObjectType) {
            return if (longValue < Short.MIN_VALUE || longValue > Short.MAX_VALUE) null else longValue.toShort()
        }
        if (wanted == Byte::class.javaPrimitiveType || wanted == Byte::class.javaObjectType) {
            return if (longValue < Byte.MIN_VALUE || longValue > Byte.MAX_VALUE) null else longValue.toByte()
        }
        if (wanted == Float::class.javaPrimitiveType || wanted == Float::class.javaObjectType) return number.toFloat()
        if (wanted == Double::class.javaPrimitiveType || wanted == Double::class.javaObjectType) return doubleValue
        return value
    }

    fun resolveType(key: String, wanted: Class<*>?): Type? {
        if (wanted != null && wanted != Any::class.java) return TypeToken.get(wanted).type
        return registeredType(key)
    }

    private fun parse(raw: Any?, type: Type): Any? {
        val json = jsonTextOf(raw)
        if (json != null) return GSON.fromJson(json, type)
        if (raw == null) return null
        if (raw is Number || raw is Boolean || raw is String) return raw
        return GSON.fromJson(GSON.toJson(raw), type)
    }

    private fun report(message: String) {
        if (!quiet) KVLog.e(message)
    }

    companion object {
        private val GSON = Gson()
        private const val JSON_PREFIX = "\u0001json:"

        private fun jsonTextOf(raw: Any?): String? {
            if (raw !is String) return null
            val text = raw
            return if (text.startsWith(JSON_PREFIX)) text.substring(JSON_PREFIX.length) else null
        }

        private fun assignable(type: Type, value: Any?): Boolean {
            if (value == null) return false
            val raw = TypeToken.get(type).rawType
            if (raw.isInstance(value)) return true
            if (Collection::class.java.isAssignableFrom(raw)) return value is Collection<*>
            if (Map::class.java.isAssignableFrom(raw)) return value is Map<*, *>
            if (JsonElement::class.java.isAssignableFrom(raw)) return value is JsonElement
            return false
        }

        private fun typeName(value: Any?): String {
            return if (value == null) "null" else value.javaClass.name
        }

        private fun describe(value: Any?): String {
            if (value == null) return "null"
            var text = value.toString()
            if (text.length > 60) text = text.substring(0, 60) + "…"
            return value.javaClass.simpleName + "(" + text + ")"
        }
    }
}
