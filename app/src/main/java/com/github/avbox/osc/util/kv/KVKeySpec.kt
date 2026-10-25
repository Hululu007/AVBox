package com.github.avbox.osc.util.kv

import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.kvcodec.KVDecoder
import com.google.gson.JsonArray
import com.google.gson.reflect.TypeToken

import java.lang.reflect.Type

class KVKeySpec : KVDecoder.TypeRegistry {

    override fun typeOf(key: String): Type? {
        val type = TYPES[key]
        return type ?: typeOfDynamic(key)
    }

    companion object {
        @JvmField
        val JS_RUNTIME_PREFIX = "jsRuntime_"
        @JvmField
        val CACHE_PREFIX = "cache_"

        private val TYPES: MutableMap<String, Type> = HashMap()

        @JvmStatic
        fun typeOfDynamic(key: String): Type? {
            if (key.startsWith(KVKeys.LIVE_GROUP_INDEX)) return TYPES[KVKeys.LIVE_GROUP_INDEX]
            if (key.startsWith(JS_RUNTIME_PREFIX) || key.startsWith(CACHE_PREFIX)) return TYPES[KVKeys.API_URL]
            return null
        }

        @JvmStatic
        fun registeredCount(): Int {
            return TYPES.size
        }

        private fun register(key: String, sample: Any) {
            TYPES[key] = TypeToken.get(sample.javaClass).type
        }

        private fun register(key: String, type: TypeToken<*>) {
            TYPES[key] = type.type
        }

        init {
            register(KVKeys.API_URL, "")
            register(KVKeys.EPG_URL, "")
            register(KVKeys.API_LINE_SOURCE, "")
            register(KVKeys.HOME_API, "")
            register(KVKeys.DEFAULT_PARSE, "")
            register(KVKeys.EXO_DECODE, "")
            register(KVKeys.LIVE_CHANNEL, "")
            register(KVKeys.DOH_JSON, "")
            register(KVKeys.LIVE_API_URL, "")
            register(KVKeys.REMOTE_TVBOX, "")
            register(KVKeys.DANMU_API, "")
            register(KVKeys.THEME_PALETTE_STYLE, "")
            register(KVKeys.PICTURE_PRESET, "")
            register(KVKeys.ANIME4K_TIER, "")
            register(KVKeys.ANIME4K_SHARPEN, 1.0f)
            register(KVKeys.ANIME4K_DEBLUR, false)
            register(KVKeys.HOME_HOT, "")
            register(KVKeys.HOME_HOT_DAY, "")
            register(KVKeys.THUNDER_IMEI, "")
            register(KVKeys.THUNDER_MAC, "")
            register(KVKeys.TMDB_API_KEY, "")
            register(KVKeys.TMDB_API_BASE, "")
            register(KVKeys.TMDB_IMAGE_BASE, "")

            register(KVKeys.PLAY_TYPE, 0)
            register(KVKeys.PLAY_RENDER, 0)
            register(KVKeys.PLAY_SCALE, 0)
            register(KVKeys.LIVE_PLAY_SCALE, 0)
            register(KVKeys.DOH_URL, 0)
            register(KVKeys.HISTORY_NUM, 0)
            register(KVKeys.LIVE_CONNECT_TIMEOUT, 0)
            register(KVKeys.SUBTITLE_TEXT_SIZE, 0)
            register(KVKeys.SUBTITLE_TIME_DELAY, 0)
            register(KVKeys.SUBTITLE_EXO_SCALE, 0)
            register(KVKeys.SUBTITLE_TEXT_STYLE, 0)
            register(KVKeys.LIVE_GROUP_INDEX, 0)
            register(KVKeys.SEARCH_THREADS, 0)
            register(KVKeys.LONG_PRESS_SPEED, 0)
            register(KVKeys.TMDB_POSTER_STYLE, 0)
            register(KVKeys.BUFFER_TIMES, 0)
            register(KVKeys.PRELOAD_DURATION, 0)
            register(KVKeys.EXO_CACHE_SIZE_MB, 0)
            register(KVKeys.DANMU_MAX_LINE, 0)
            register(KVKeys.THEME_SOURCE, 0)
            register(KVKeys.THEME_MODE, 0)
            register(KVKeys.THEME_SEED, 0)
            register(KVKeys.LIQUID_GLASS_BLUR, 0)
            register(KVKeys.LIQUID_GLASS_DISTORTION, 0)
            register(KVKeys.COLLECT_COLUMNS, 0)

            register(KVKeys.PLAYER_IS_LIVE, false)
            register(KVKeys.LIVE_CHANNEL_REVERSE, false)
            register(KVKeys.LIVE_CROSS_GROUP, false)
            register(KVKeys.LIVE_SHOW_NET_SPEED, false)
            register(KVKeys.LIVE_SHOW_TIME, false)
            register(KVKeys.M3U8_PURIFY, false)
            register(KVKeys.AUTO_SWITCH_LINE, false)
            register(KVKeys.DEFAULT_LOAD_LIVE, false)
            register(KVKeys.INCOGNITO, false)
            register(KVKeys.HIDE_STATUS_BAR, false)
            register(KVKeys.GESTURE_CONTROL_DISABLED, false)
            register(KVKeys.NAV_ANIMATION_DISABLED, false)
            register(KVKeys.NAV_LIVE_HIDDEN, false)
            register(KVKeys.MUSIC_AUTO_OPEN_PAGE, true)
            register(KVKeys.PLAY_TUNNEL, false)
            register(KVKeys.PLAY_PREFER_AAC, false)
            register(KVKeys.ANIME4K_ENABLED, false)
            register(KVKeys.KERNEL_PREWARM, false)
            register(KVKeys.EXO_VIDEO_DYNAMIC_SCHEDULING, false)
            register(KVKeys.PRELOAD_NEXT_EPISODE, false)
            register(KVKeys.ISO_LPCM, true)
            register(KVKeys.ISO_TRUEHD_CORE, true)
            register(KVKeys.ISO_PGS, true)
            register(KVKeys.PLAY_CACHE, false)
            register(KVKeys.DANMU_OPEN, false)
            register(KVKeys.DANMU_RANDOM_COLOR, false)
            register(KVKeys.DANMU_API_USE_DEFAULT, false)
            register(KVKeys.LIQUID_GLASS_NAVBAR, false)
            register(KVKeys.LIQUID_GLASS_CONTROLS, false)
            register(KVKeys.LIQUID_GLASS_DISPERSION, false)
            register(KVKeys.THEME_PURE_BLACK, false)
            register(KVKeys.TMDB_ENABLE, false)

            register(KVKeys.LIQUID_GLASS_BLUR, 0f)
            register(KVKeys.LIQUID_GLASS_DISTORTION, 0f)
            register(KVKeys.LIQUID_GLASS_TRANSLUCENCY, 0f)
            register(KVKeys.DANMU_SPEED, 0f)
            register(KVKeys.DANMU_ALPHA, 0f)
            register(KVKeys.DANMU_SIZE_SCALE, 0f)
            register(KVKeys.SUBTITLE_EXO_POSITION, 0f)
            register(KVKeys.PICTURE_SATURATION, 0f)
            register(KVKeys.PICTURE_CONTRAST, 0f)
            register(KVKeys.PICTURE_BRIGHTNESS, 0f)
            register(KVKeys.PICTURE_GAMMA, 0f)
            register(KVKeys.PICTURE_HUE, 0f)
            register(KVKeys.PICTURE_TEMPERATURE, 0f)
            register(KVKeys.PICTURE_SHARPNESS, 0f)
            register(KVKeys.PICTURE_SHADOW_LIFT, 0f)

            register(KVKeys.SEARCH_HISTORY, object : TypeToken<ArrayList<String>>() {
            })
            register(KVKeys.API_HISTORY, object : TypeToken<ArrayList<String>>() {
            })
            register(KVKeys.LIVE_API_HISTORY, object : TypeToken<ArrayList<String>>() {
            })
            register(KVKeys.API_LINE_LIST, object : TypeToken<ArrayList<String>>() {
            })
            register(KVKeys.LIVE_API_LINE_LIST, object : TypeToken<ArrayList<String>>() {
            })
            register(KVKeys.BOOT_LOADING_JAR, "")
            register(KVKeys.BOOT_LOADING_SCOPE, "")
            register(KVKeys.BOOT_SAFE_DISABLED, "")
            register(KVKeys.BOOT_DISABLED_SOURCES, object : TypeToken<ArrayList<String>>() {
            })
            register(KVKeys.BOOT_DISABLED_JARS, object : TypeToken<ArrayList<String>>() {
            })
            register(KVKeys.BOOT_VOD_SOURCE, "")
            register(KVKeys.BOOT_LIVE_SOURCE, "")
            register(KVKeys.BOOT_LOADING_COUNT, 0L)
            register(KVKeys.BOOT_LAST_ATTEMPT_AT, 0L)
            register(KVKeys.BOOT_LOAD_START_ELAPSED, 0L)
            register(KVKeys.SUBSCRIBE_LIST, object : TypeToken<ArrayList<String>>() {
            })
            register(KVKeys.LIVE_SUBSCRIBE_LIST, object : TypeToken<ArrayList<String>>() {
            })
            register(KVKeys.LOCAL_SOURCE_TREES, object : TypeToken<ArrayList<String>>() {
            })
            register(KVKeys.LIVE_GROUP_LIST, TypeToken.get(JsonArray::class.java))

            register(KVKeys.LIVE_WEB_HEADER, object : TypeToken<HashMap<String, String>>() {
            })
            register(KVKeys.SOURCES_FOR_SEARCH, object : TypeToken<HashMap<String, HashMap<String, String>>>() {
            })
            register(KVKeys.SOURCE_NAME_CACHE, object : TypeToken<HashMap<String, String>>() {
            })
        }
    }
}
