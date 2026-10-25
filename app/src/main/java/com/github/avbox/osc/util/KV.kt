package com.github.avbox.osc.util

import android.content.Context

import com.github.avbox.osc.util.kv.KVCodec
import com.tencent.mmkv.MMKV

object KV {

    private const val MMAP_ID = "avbox_kv"

    private var store: MMKV? = null

    @JvmStatic
    fun init(context: Context) {
        val appContext = context.applicationContext
        MMKV.initialize(appContext ?: context)
        store = MMKV.mmkvWithID(MMAP_ID, MMKV.SINGLE_PROCESS_MODE)
    }

    @JvmStatic
    fun <T> put(key: String, value: T?): Boolean {
        val instance = requireStore()
        if (value == null) {
            instance.removeValueForKey(key)
            return true
        }
        val encoded: String
        try {
            encoded = KVCodec.encode(value)
        } catch (e: Throwable) {
            AppLog.e("echo-kv encode-failed key=" + key + " valueType=" + value.javaClass.name + " " + e.message)
            return false
        }
        val ok = instance.encode(key, encoded)
        if (!ok) AppLog.e("echo-kv put-failed key=" + key + " valueType=" + value.javaClass.name)
        return ok
    }

    @JvmStatic
    fun <T> get(key: String): T? {
        return getInternal(key, null)
    }

    @JvmStatic
    fun <T> get(key: String, defaultValue: T?): T {
        @Suppress("UNCHECKED_CAST")
        return getInternal(key, defaultValue) as T
    }

    private fun <T> getInternal(key: String, defaultValue: T?): T? {
        Probe.countKvCall()
        return KVCodec.decode(key, requireStore(), defaultValue)
    }

    @JvmStatic
    fun contains(key: String): Boolean {
        return requireStore().containsKey(key)
    }

    @JvmStatic
    fun delete(key: String) {
        requireStore().removeValueForKey(key)
    }

    @JvmStatic
    fun keys(prefix: String): List<String> {
        val all = requireStore().allKeys()
        val matched = ArrayList<String>()
        if (all == null) return matched
        for (key in all) {
            if (key != null && key.startsWith(prefix)) matched.add(key)
        }
        return matched
    }

    private fun requireStore(): MMKV {
        val instance = store
        if (instance == null) throw IllegalStateException("KV.init(Context) 未调用") // i18n: keep(异常消息)
        return instance
    }
}
