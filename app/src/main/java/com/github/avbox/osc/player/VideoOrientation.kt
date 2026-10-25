package com.github.avbox.osc.player

object VideoOrientation {
    @JvmStatic
    fun isPortrait(width: Int, height: Int): Boolean = width > 0 && height > width

    @JvmStatic
    fun isUsableSize(width: Int, height: Int): Boolean = width > 0 && height > 0
}
