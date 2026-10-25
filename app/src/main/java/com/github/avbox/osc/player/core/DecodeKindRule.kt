package com.github.avbox.osc.player.core

object DecodeKindRule {

    @JvmStatic
    fun kindOf(codecName: String?, hardwareAccelerated: Boolean, softwareOnly: Boolean): PlayerDecodeKind {
        val name = codecName.orEmpty()
        if (name.isEmpty()) return PlayerDecodeKind.UNKNOWN
        if (softwareOnly) return PlayerDecodeKind.SOFTWARE
        if (isSoftwareCodecName(name)) return PlayerDecodeKind.SOFTWARE
        if (hardwareAccelerated) return PlayerDecodeKind.HARDWARE
        return PlayerDecodeKind.UNKNOWN
    }

    @JvmStatic
    fun videoKindOf(codecName: String?, softwareOnly: Boolean): PlayerDecodeKind {
        val name = codecName.orEmpty()
        if (name.isEmpty()) return PlayerDecodeKind.UNKNOWN
        if (softwareOnly || isSoftwareCodecName(name)) return PlayerDecodeKind.SOFTWARE
        return PlayerDecodeKind.HARDWARE
    }

    private fun isSoftwareCodecName(name: String): Boolean =
        name.startsWith("c2.android.") ||
            name.startsWith("OMX.google.") ||
            name.startsWith("OMX.ffmpeg.") ||
            name.contains(".sw.") ||
            name.endsWith(".sw")
}
