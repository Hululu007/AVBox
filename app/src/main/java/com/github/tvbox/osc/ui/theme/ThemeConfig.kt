package com.github.tvbox.osc.ui.theme

import com.github.tvbox.osc.R
import com.materialkolor.PaletteStyle

object ThemeSource {
    const val SYSTEM = 0

    const val CUSTOM = 1
}

object ThemeMode {
    const val FOLLOW_SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

data class ThemeConfig(
    val source: Int,
    val mode: Int,
    val seedArgb: Int,
    val style: PaletteStyle,
)

val DefaultSeedArgb: Int = 0xFF1B6EF3.toInt()

val DefaultPaletteStyle: PaletteStyle = PaletteStyle.TonalSpot

/** 名字存资源 id(不在数据表里存字符串):展示侧用 `stringResource` 取,切语言后自动跟随 */
val PresetSeeds: List<Pair<Int, Int>> = listOf(
    R.string.theme_seed_red to 0xFFD02020.toInt(),
    R.string.theme_seed_orange to 0xFFE07A00.toInt(),
    R.string.theme_seed_yellow to 0xFFB08000.toInt(),
    R.string.theme_seed_green to 0xFF208040.toInt(),
    R.string.theme_seed_cyan to 0xFF008080.toInt(),
    R.string.theme_seed_blue to 0xFF1B6EF3.toInt(),
    R.string.theme_seed_purple to 0xFF6750A4.toInt(),
    R.string.theme_seed_pink to 0xFFB04080.toInt(),
)

val PaletteStyles: List<Pair<PaletteStyle, Int>> = listOf(
    PaletteStyle.TonalSpot to R.string.theme_style_tonal_spot,
    PaletteStyle.Vibrant to R.string.theme_style_vibrant,
    PaletteStyle.Expressive to R.string.theme_style_expressive,
    PaletteStyle.Neutral to R.string.theme_style_neutral,
    PaletteStyle.Monochrome to R.string.theme_style_monochrome,
    PaletteStyle.Fidelity to R.string.theme_style_fidelity,
    PaletteStyle.Content to R.string.theme_style_content,
    PaletteStyle.Rainbow to R.string.theme_style_rainbow,
    PaletteStyle.FruitSalad to R.string.theme_style_fruit_salad,
)
