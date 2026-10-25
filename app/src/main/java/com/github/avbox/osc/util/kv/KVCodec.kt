package com.github.avbox.osc.util.kv

import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.kvcodec.KVDecoder
import com.github.avbox.osc.util.kvcodec.KVLog
import com.tencent.mmkv.MMKV

object KVCodec {

    private val DECODER = KVDecoder()

    init {
        KVLog.setSink(AppLog::e)
        DECODER.setRegistry(KVKeySpec())
        AppLog.i("echo-kv type-registry keys=" + KVKeySpec.registeredCount())
    }

    @JvmStatic
    fun encode(value: Any): String {
        return DECODER.encode(value)
    }

    @JvmStatic
    fun <T> decode(key: String, store: MMKV, defaultValue: T?): T? {
        if (!store.containsKey(key)) return defaultValue
        val raw = store.decodeString(key)
        if (raw == null) return defaultValue
        val decoder = if (defaultValue == null) DECODER.quiet() else DECODER
        val parsed = decoder.decode(key, raw, defaultValue)
        return parsed ?: defaultValue
    }
}
