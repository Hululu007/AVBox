package com.github.avbox.osc.player.engine.iso

import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV

object IsoSettings {

    var lpcmEnabled: Boolean
        get() = KV.get(KVKeys.ISO_LPCM, KVKeys.ISO_LPCM_DEFAULT)
        set(value) {
            KV.put(KVKeys.ISO_LPCM, value)
        }

    var trueHdCoreEnabled: Boolean
        get() = KV.get(KVKeys.ISO_TRUEHD_CORE, KVKeys.ISO_TRUEHD_CORE_DEFAULT)
        set(value) {
            KV.put(KVKeys.ISO_TRUEHD_CORE, value)
        }

    var pgsEnabled: Boolean
        get() = KV.get(KVKeys.ISO_PGS, KVKeys.ISO_PGS_DEFAULT)
        set(value) {
            KV.put(KVKeys.ISO_PGS, value)
        }
}
