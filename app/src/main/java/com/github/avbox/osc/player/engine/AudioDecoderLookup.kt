package com.github.avbox.osc.player.engine

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import com.github.avbox.osc.player.core.PlayerDecodeKind
import com.github.avbox.osc.player.core.DecodeKindRule

internal object AudioDecoderLookup {

    private const val FFMPEG_PREFIX = "ffmpeg."
    private const val FFMPEG_SUFFIX = ".decoder"

    private val codecList: MediaCodecList? by lazy {
        runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS) }.getOrNull()
    }

    fun choiceFor(format: Format?): AudioCodecChoice? {
        val mime = format?.sampleMimeType?.takeIf { it.isNotEmpty() } ?: return null
        if (!MimeTypes.isAudio(mime)) return null
        val mediaFormat = MediaFormat.createAudioFormat(mime, format.sampleRate, format.channelCount)
        val candidates = codecList?.codecInfos?.filter { it.isAudioDecoderFor(mediaFormat) }.orEmpty()
        if (candidates.isEmpty()) return null
        val info = candidates.firstOrNull { it.isHardwareAccelerated && !it.isSoftwareOnly } ?: candidates.first()
        return AudioCodecChoice(
            name = info.name,
            hardwareAccelerated = info.isHardwareAccelerated,
            softwareOnly = info.isSoftwareOnly,
        )
    }

    fun decodeKind(format: Format?): Pair<String, PlayerDecodeKind>? {
        val choice = choiceFor(format) ?: return null
        return choice.name to DecodeKindRule.kindOf(
            choice.name,
            choice.hardwareAccelerated,
            choice.softwareOnly,
        )
    }

    fun guessChoice(format: Format?): Pair<AudioCodecChoice, Boolean>? {
        extensionChoice(format)?.let { return it to true }
        val predicted = choiceFor(format) ?: return null
        return predicted to false
    }

    private fun extensionChoice(format: Format?): AudioCodecChoice? {
        val mime = format?.sampleMimeType?.takeIf { it.isNotEmpty() } ?: return null
        if (!MimeTypes.isAudio(mime)) return null
        val available = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)
        if (!available) return null
        val supported = runCatching { FfmpegLibrary.supportsFormat(mime) }.getOrDefault(false)
        if (!supported) return null
        return AudioCodecChoice(
            name = FFMPEG_PREFIX + mime.substringAfter('/') + FFMPEG_SUFFIX,
            hardwareAccelerated = false,
            softwareOnly = true,
        )
    }

    private fun MediaCodecInfo.isAudioDecoderFor(format: MediaFormat): Boolean {
        if (isEncoder) return false
        val caps = runCatching { getCapabilitiesForType(format.getString(MediaFormat.KEY_MIME)) }.getOrNull()
            ?: return false
        if (caps.colorFormats.isNotEmpty()) return false
        return runCatching { caps.isFormatSupported(format) }.getOrDefault(false)
    }
}
