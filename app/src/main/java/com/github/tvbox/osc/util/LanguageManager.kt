package com.github.tvbox.osc.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 应用语言。tag = null 表示跟随系统,其余为 BCP-47 语言标签(用于选资源目录)。
 *
 * 繁体判定:显式语言按 tag,「跟随系统」回落系统 locale(数据侧简繁转换的门控读它)。
 */
enum class AppLanguage(val tag: String?) {
    System(null),
    SimplifiedChinese("zh-Hans"),
    English("en"),
    TraditionalTW("zh-Hant-TW"),
    TraditionalHK("zh-Hant-HK"),
}

/**
 * 应用语言的唯一读入口(存 KV `app_language`,未登记 KVKeySpec ⇒ 读取必须带默认值)。
 *
 * 资源包裹在 Application / Activity / Service 的 attachBaseContext 里做(App / BaseActivity / PlaybackService);
 * ⚠️ 爬虫与 Trans 可能早于 `App.attachBaseContext`,KV 未就绪时按"跟随系统"返回而不是抛异常。
 */
object LanguageManager {

    private const val KEY_LANGUAGE = "app_language"

    /** KV 读不到(未 init)时不缓存,等就绪后重新读 */
    @Volatile
    private var cached: AppLanguage? = null

    /** [`localized`] 的缓存,切语言时置空 */
    @Volatile
    private var localizedCache: Context? = null

    /** 繁体地区(zh-TW / zh-HK / zh-MO;脚本判定覆盖不到老式无 script 的 locale) */
    private val traditionalRegions = setOf("TW", "HK", "MO")

    /** 已交付语言白名单:未交付的语言不出现进设置页入口 —— 切到半成品语言(缺的条目回落简体)会误报成 bug */
    private val delivered = setOf(
        AppLanguage.System,
        AppLanguage.SimplifiedChinese,
        AppLanguage.English,
        AppLanguage.TraditionalTW,
        AppLanguage.TraditionalHK,
    )

    fun current(): AppLanguage {
        cached?.let { return it }
        return try {
            val stored = KV.get(KEY_LANGUAGE, AppLanguage.System.name)
            byName(stored).takeIf { it in delivered }?.also { cached = it } ?: AppLanguage.System
        } catch (ignored: IllegalStateException) {
            AppLanguage.System
        }
    }

    /**
     * 繁体档判定(数据侧简繁转换的门控):显式语言按 tag;「跟随系统」回落系统 locale —— 否则
     * 系统语言为 zh-TW / zh-HK 的老用户(未在应用内选过语言)会"UI 繁体、源数据不转"。
     */
    fun isTraditional(): Boolean {
        val tag = current().tag
        if (tag != null) return tag.startsWith("zh-Hant")
        val locale = Locale.getDefault()
        return locale.script.equals("Hant", ignoreCase = true) || locale.country in traditionalRegions
    }

    /** 只写 KV;重启生效由调用方负责(见 `restartApp`,`util/AppRestart.kt`) */
    fun set(lang: AppLanguage) {
        KV.put(KEY_LANGUAGE, lang.name)
        cached = lang.takeIf { it in delivered }
        localizedCache = null
    }

    fun available(): List<AppLanguage> = AppLanguage.entries.filter { it in delivered }

    fun resolve(): Locale {
        val tag = current().tag ?: return Locale.getDefault()
        return Locale.forLanguageTag(tag)
    }

    /**
     * 按当前语言包裹好的 Context(**只传 Application**,缓存里会长期持有)。
     *
     * ⚠️ Application / Service 的 base 只在创建时挂一次,切语言不会重挂 —— 长生命周期组件(通知、
     * 数据层 `App.getInstance().getString(...)`)取文案必须走这里,否则会停在旧语言。
     */
    fun localized(base: Context): Context {
        localizedCache?.let { return it }
        return wrap(base).also { localizedCache = it }
    }

    /** 拷贝 Configuration 再 setLocale(API 24+ 会同步 LocaleList);"跟随系统"原样返回 base,零改动 */
    fun wrap(base: Context): Context {
        val tag = current().tag ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }

    private fun byName(name: String): AppLanguage =
        AppLanguage.entries.firstOrNull { it.name == name } ?: AppLanguage.System
}
