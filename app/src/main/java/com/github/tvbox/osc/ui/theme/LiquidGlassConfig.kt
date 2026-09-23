package com.github.tvbox.osc.ui.theme

// 折射衰减深度/位移量:1.0 = 铺满整个控件(只剩"整体糊"),越小越集中在边缘窄带 ⇒ 透镜环越清晰
const val REFRACTION_DEPTH_RATIO = 0.4f

// 内阴影厚度带宽度/模糊半径(dp):落在下缘,给玻璃"有体积"的观感
const val GLASS_THICKNESS_DP = 4f

// 内阴影不透明度。⚠️ 实际黑度 = 它 × InnerShadow 默认色的 0.15;取大了会在下缘显出一条偏色暗带
const val GLASS_THICKNESS_ALPHA = 0.3f

// 方向性高光强度(库的 Ambient 样式):亮上缘 + 暗下缘,是"立体感"的主来源;过大会把下缘暗线变脏
const val GLASS_AMBIENT_INTENSITY = 0.55f

// 光源角度(度)。-90 = 正上方受光(亮上缘);库默认 45 = 右下受光
const val GLASS_LIGHT_ANGLE = -90f

data class LiquidGlassConfig(
    val navbarEnabled: Boolean,
    val controlsEnabled: Boolean,
    val blurDp: Float,
    val distortionDp: Float,
    val translucency: Float,
    val dispersion: Boolean,
) {
    /** 通透度 → 底色不透明度系数(0.5 为基准 1,越透越小) */
    val containerAlphaScale: Float get() = 1.25f - translucency * 0.5f

    /** 通透度 → 采样内容亮度补偿(底色越淡越压暗,保住压在玻璃上的文字) */
    val contentBrightness: Float get() = (0.5f - translucency) * 0.06f

    /** 通透度 → 采样内容对比度补偿 */
    val contentContrast: Float get() = 1f + (translucency - 0.5f) * 0.24f
}
