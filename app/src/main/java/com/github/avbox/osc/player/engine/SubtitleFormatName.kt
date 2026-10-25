package com.github.avbox.osc.player.engine

import java.util.Locale

internal fun subtitleFormatName(mimeType: String?): String {
    val value = mimeType?.trim()?.lowercase(Locale.US) ?: return ""
    if (value.isEmpty()) return ""
    return when {
        value.contains("pgs") -> "PGS"
        value.contains("vobsub") -> "VobSub"
        value.contains("dvb") -> "DVB"
        value.contains("ssa") -> "ASS"
        value.contains("ass") -> "ASS"
        value.contains("subrip") -> "SRT"
        value.contains("vtt") -> "WebVTT"
        value.contains("ttml") -> "TTML"
        value.contains("cea-608") || value.contains("cea608") -> "CEA-608"
        value.contains("cea-708") || value.contains("cea708") -> "CEA-708"
        value.contains("sami") -> "SAMI"
        value.contains("media3-cues") -> ""
        value.contains('/') -> value.substringAfter('/').uppercase(Locale.US)
        else -> value.uppercase(Locale.US)
    }
}
