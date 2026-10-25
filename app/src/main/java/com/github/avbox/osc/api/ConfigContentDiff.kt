package com.github.avbox.osc.api

object ConfigContentDiff {

    fun isSame(a: String, b: String): Boolean {
        if (a == b) return true
        return normalize(a) == normalize(b)
    }

    private fun normalize(text: String): String {
        return text.filterNot { it.isWhitespace() }
    }
}
