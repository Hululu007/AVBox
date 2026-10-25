package com.github.avbox.osc.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.github.avbox.osc.util.KVKeys
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.github.avbox.osc.util.KV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object AppThemeState {

    private var current by mutableStateOf(load())

    val config: ThemeConfig get() = current

    private fun load(): ThemeConfig = ThemeConfig(
        source = KV.get(KVKeys.THEME_SOURCE, ThemeSource.SYSTEM),
        mode = KV.get(KVKeys.THEME_MODE, ThemeMode.FOLLOW_SYSTEM),
        seedArgb = KV.get(KVKeys.THEME_SEED, DefaultSeedArgb),
        style = runCatching { PaletteStyle.valueOf(KV.get(KVKeys.THEME_PALETTE_STYLE, "")) }
            .getOrDefault(DefaultPaletteStyle),
        pureBlack = KV.get(KVKeys.THEME_PURE_BLACK, false),
    )

    var source: Int
        get() = current.source
        set(value) {
            KV.put(KVKeys.THEME_SOURCE, value)
            current = current.copy(source = value)
        }

    var mode: Int
        get() = current.mode
        set(value) {
            KV.put(KVKeys.THEME_MODE, value)
            current = current.copy(mode = value)
        }

    var seed: Int
        get() = current.seedArgb
        set(value) {
            KV.put(KVKeys.THEME_SEED, value)
            current = current.copy(seedArgb = value)
        }

    var style: PaletteStyle
        get() = current.style
        set(value) {
            KV.put(KVKeys.THEME_PALETTE_STYLE, value.name)
            current = current.copy(style = value)
        }

    var pureBlack: Boolean
        get() = current.pureBlack
        set(value) {
            KV.put(KVKeys.THEME_PURE_BLACK, value)
            current = current.copy(pureBlack = value)
        }

    fun isDark(systemDark: Boolean): Boolean = when (current.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }

    fun resolveScheme(context: Context, config: ThemeConfig, dark: Boolean): ColorScheme = when {
        config.source == ThemeSource.CUSTOM -> customScheme(config.seedArgb, dark, config.style)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    fun windowSurfaceArgb(context: Context): Int {
        val dark = isDark(isSystemDark(context))
        val scheme = resolveScheme(context, current, dark)
        val resolved = if (dark && current.pureBlack) scheme.toPureBlack() else scheme
        return resolved.surface.toArgb()
    }

    private fun isSystemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val schemeCache = ConcurrentHashMap<Triple<Int, Boolean, PaletteStyle>, ColorScheme>()

    fun customScheme(seedArgb: Int, isDark: Boolean, style: PaletteStyle): ColorScheme =
        schemeCache.getOrPut(Triple(seedArgb, isDark, style)) {
            dynamicColorScheme(seedColor = Color(seedArgb), isDark = isDark, style = style)
        }

    private val previewCache = ConcurrentHashMap<Pair<Int, PaletteStyle>, ColorScheme>()

    suspend fun previewScheme(seedArgb: Int, style: PaletteStyle): ColorScheme {
        previewCache[seedArgb to style]?.let { return it }
        return withContext(Dispatchers.Default) {
            previewCache.getOrPut(seedArgb to style) {
                dynamicColorScheme(seedColor = Color(seedArgb), isDark = false, style = style)
            }
        }
    }
}
