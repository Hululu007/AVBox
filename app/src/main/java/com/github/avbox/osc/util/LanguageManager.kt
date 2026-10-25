package com.github.avbox.osc.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

enum class AppLanguage(val tag: String?) {
    System(null),
    SimplifiedChinese("zh-Hans"),
    English("en"),
    TraditionalTW("zh-Hant-TW"),
    TraditionalHK("zh-Hant-HK"),
}

object LanguageManager {

    private const val KEY_LANGUAGE = "app_language"

    @Volatile
    private var cached: AppLanguage? = null

    @Volatile
    private var localizedCache: Context? = null

    private val traditionalRegions = setOf("TW", "HK", "MO")

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

    fun isTraditional(): Boolean {
        val tag = current().tag
        if (tag != null) return tag.startsWith("zh-Hant")
        val locale = Locale.getDefault()
        return locale.script.equals("Hant", ignoreCase = true) || locale.country in traditionalRegions
    }

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

    fun localized(base: Context): Context {
        localizedCache?.let { return it }
        return wrap(base).also { localizedCache = it }
    }

    fun wrap(base: Context): Context {
        val tag = current().tag ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }

    private fun byName(name: String): AppLanguage =
        AppLanguage.entries.firstOrNull { it.name == name } ?: AppLanguage.System
}
