package com.github.avbox.osc.player.engine

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import com.github.avbox.osc.R
import com.github.avbox.osc.player.core.TrackInfo
import com.github.avbox.osc.player.core.TrackInfoBean
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.LanguageManager
import com.github.avbox.osc.util.TrackMemory
import java.util.ArrayList
import java.util.Locale

internal class EngineTrackSelection(private val host: Host) {

    interface Host {
        fun trackSelector(): DefaultTrackSelector

        fun internalPlayer(): ExoPlayer?

        fun appContext(): Context
    }

    private var contentKey = ""

    private var defaultSubtitleTrackSelected = false
    private var defaultSubtitleTrackSelectionClosed = false

    private var rendererListLogged = false

    @Volatile
    private var subtitleDelayUsValue = 0L

    val subtitleDelayUs: Long
        get() = subtitleDelayUsValue

    var onCuesListener: ((List<Cue>) -> Unit)? = null

    fun dispatchCues(cues: List<Cue>) {
        onCuesListener?.invoke(cues)
    }

    fun setInternalSubtitleDelay(milliseconds: Int) {
        subtitleDelayUsValue = milliseconds * 1000L
    }

    fun getSelectedVideoFormat(): Format? = selectedFormat(C.TRACK_TYPE_VIDEO)

    fun getSelectedAudioFormat(): Format? = selectedFormat(C.TRACK_TYPE_AUDIO)

    private fun selectedFormat(trackType: Int): Format? {
        val exo = host.internalPlayer() ?: return null
        val tracks = exo.currentTracks
        for (group in tracks.groups) {
            if (group.type != trackType || !group.isSelected) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) return group.getTrackFormat(i)
            }
        }
        return null
    }

    fun getTrackInfo(): TrackInfo {
        val data = TrackInfo()
        val mappedInfo = host.trackSelector().currentMappedTrackInfo ?: return data
        logRendererListOnce(mappedInfo)
        for (rendererIndex in 0 until mappedInfo.rendererCount) {
            val type = mappedInfo.getRendererType(rendererIndex)
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_TEXT) continue
            val groups = mappedInfo.getTrackGroups(rendererIndex)
            for (groupIndex in 0 until groups.length) {
                val group = groups[groupIndex]
                for (trackIndex in 0 until group.length) {
                    val fmt = group.getFormat(trackIndex)
                    if (type == C.TRACK_TYPE_TEXT && isUndeclaredClosedCaptionTrack(fmt)) continue
                    val language = getLanguage(fmt)
                    val detail = when (type) {
                        C.TRACK_TYPE_VIDEO -> videoName(fmt)
                        C.TRACK_TYPE_TEXT -> subtitleName(fmt)
                        else -> getName(fmt)
                    }
                    val bean = TrackInfoBean()
                    bean.language = language
                    bean.name = buildDisplayName(
                        if (type == C.TRACK_TYPE_AUDIO) str(R.string.player_menu_audio_track)
                        else if (type == C.TRACK_TYPE_VIDEO) str(R.string.player_menu_video_track)
                        else str(R.string.player_menu_subtitle),
                        if (type == C.TRACK_TYPE_AUDIO) data.audio.size + 1
                        else if (type == C.TRACK_TYPE_VIDEO) data.video.size + 1
                        else data.subtitle.size + 1,
                        language,
                        detail,
                    )
                    bean.renderId = rendererIndex
                    bean.trackGroupId = groupIndex
                    bean.trackId = trackIndex
                    bean.groupIndex = groupIndex
                    bean.index = trackIndex
                    bean.selected = isCurrentTrackSelected(fmt, type)
                    bean.bitmapSubtitle = type == C.TRACK_TYPE_TEXT && isBitmapSubtitle(fmt)
                    bean.type = type
                    bean.formatKey = formatKey(fmt, type)
                    if (type == C.TRACK_TYPE_AUDIO) {
                        data.addAudio(bean)
                    } else if (type == C.TRACK_TYPE_VIDEO) {
                        data.addVideo(bean)
                    } else {
                        data.addSubtitle(bean)
                    }
                }
            }
        }
        return data
    }

    private fun logRendererListOnce(mappedInfo: MappingTrackSelector.MappedTrackInfo) {
        if (rendererListLogged) return
        rendererListLogged = true
        val sb = StringBuilder("echo-setTrack renderers:")
        for (i in 0 until mappedInfo.rendererCount) {
            sb.append(" [").append(i).append("]type=").append(mappedInfo.getRendererType(i))
                .append('/').append(mappedInfo.getRendererName(i))
        }
        AppLog.i(sb.toString())
    }

    fun setTrack(track: TrackInfoBean?) {
        if (track == null) return
        if (!applyTrack(track.renderId, track.trackGroupId, track.trackId)) return
        if (track.type == C.TRACK_TYPE_TEXT) {
            TrackMemory.saveSubtitle(contentKey, track.formatKey)
        } else {
            TrackMemory.saveTrack(contentKey, track.type, track.formatKey)
        }
    }

    fun selectTrack(track: TrackInfoBean?) {
        if (track == null) return
        applyTrack(track.renderId, track.trackGroupId, track.trackId)
    }

    private fun applyTrack(rendererIndex: Int, groupIndex: Int, trackIndex: Int): Boolean {
        try {
            val mappedInfo = host.trackSelector().currentMappedTrackInfo
            if (mappedInfo == null) {
                AppLog.i("echo-setTrack: MappedTrackInfo is null")
                return false
            }
            if (rendererIndex == C.INDEX_UNSET || rendererIndex < 0 || rendererIndex >= mappedInfo.rendererCount) {
                AppLog.i("echo-setTrack: No renderer found")
                return false
            }
            val groups = mappedInfo.getTrackGroups(rendererIndex)
            if (!isTrackIndexValid(groups, groupIndex, trackIndex)) {
                AppLog.i("echo-setTrack: Invalid track index - group:$groupIndex, track:$trackIndex")
                return false
            }
            val override = DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex)
            val builder = host.trackSelector().buildUponParameters()
            builder.setRendererDisabled(rendererIndex, false)
            builder.clearSelectionOverrides(rendererIndex)
            val targetType = mappedInfo.getRendererType(rendererIndex)
            for (i in 0 until mappedInfo.rendererCount) {
                if (i != rendererIndex && mappedInfo.getRendererType(i) == targetType) {
                    builder.clearSelectionOverrides(i)
                }
            }
            builder.setSelectionOverride(rendererIndex, groups, override)
            host.trackSelector().setParameters(builder.build())
            val applied = groups.get(groupIndex).getFormat(trackIndex)
            AppLog.i(
                "echo-setTrack applied: renderer=$rendererIndex group=$groupIndex track=$trackIndex type=$targetType " +
                    "mime=${applied?.sampleMimeType} channels=${applied?.channelCount ?: -1} codecs=${applied?.codecs} trackKey=$contentKey",
            )
            return true
        } catch (e: Exception) {
            AppLog.i("echo-setTrack error: ${e.message}")
            return false
        }
    }

    fun restoreTracks() {
        restoreByMemory(C.TRACK_TYPE_AUDIO)
        restoreByMemory(C.TRACK_TYPE_VIDEO)
        restoreSubtitleByMemory()
    }

    private fun restoreByMemory(trackType: Int) {
        val remembered = TrackMemory.loadTrack(contentKey, trackType) ?: return
        val position = locate(trackType, remembered)
        if (position == null) {
            AppLog.i("echo-track-memory miss type=$trackType key=$contentKey fp=$remembered")
            return
        }
        if (applyTrack(position[0], position[1], position[2])) {
            AppLog.i("echo-track-memory restore type=$trackType fp=$remembered")
        }
    }

    private fun restoreSubtitleByMemory() {
        val record = TrackMemory.loadSubtitle(contentKey) ?: return
        if (!TrackMemory.isSubtitleTrack(record)) return
        val position = locate(C.TRACK_TYPE_TEXT, record)
        if (position == null) {
            AppLog.i("echo-track-memory text miss, use default: $record")
            selectDefaultSubtitlePick()
            return
        }
        if (applyTrack(position[0], position[1], position[2])) {
            AppLog.i("echo-track-memory restore text fp=$record")
        }
    }

    private fun locate(trackType: Int, fingerprint: String): IntArray? {
        val mappedInfo = host.trackSelector().currentMappedTrackInfo ?: return null
        val keys = ArrayList<String>()
        val positions = ArrayList<IntArray>()
        for (rendererIndex in 0 until mappedInfo.rendererCount) {
            if (mappedInfo.getRendererType(rendererIndex) != trackType) continue
            val groups = mappedInfo.getTrackGroups(rendererIndex)
            for (groupIndex in 0 until groups.length) {
                val group = groups[groupIndex]
                for (trackIndex in 0 until group.length) {
                    val format = group.getFormat(trackIndex)
                    if (trackType == C.TRACK_TYPE_TEXT && isUndeclaredClosedCaptionTrack(format)) continue
                    keys.add(formatKey(format, trackType))
                    positions.add(intArrayOf(rendererIndex, groupIndex, trackIndex))
                }
            }
        }
        val index = TrackMemory.pick(keys, fingerprint)
        return if (index < 0) null else positions[index]
    }

    private fun formatKey(fmt: Format?, trackType: Int): String {
        if (fmt == null) return ""
        val codec = firstNonEmpty(fmt.codecs, mimeSubtype(fmt))
        if (trackType == C.TRACK_TYPE_AUDIO) {
            return TrackMemory.audioFingerprint(getLanguage(fmt), codec, fmt.channelCount)
        }
        if (trackType == C.TRACK_TYPE_VIDEO) {
            return TrackMemory.videoFingerprint(codec, fmt.width, fmt.height)
        }
        return TrackMemory.textFingerprint(getLanguage(fmt), fmt.id, fmt.label, codec)
    }

    private fun mimeSubtype(fmt: Format?): String {
        val mime = fmt?.sampleMimeType ?: return ""
        if (!mime.contains("/")) return ""
        return mime.substring(mime.indexOf('/') + 1)
    }

    private fun firstNonEmpty(first: String?, second: String?): String =
        if (first != null && first.isNotEmpty()) first else (second ?: "")

    fun loadDefaultSubtitleTrack() {
        if (defaultSubtitleTrackSelected) return
        if (TrackMemory.loadSubtitle(contentKey) != null) {
            AppLog.i("echo-track-memory subtitle decision exists, skip default")
            defaultSubtitleTrackSelected = true
            return
        }
        selectDefaultSubtitlePick()
    }

    fun ensureSubtitleTrackSelected() {
        val subtitles = getTrackInfo().subtitle
        if (subtitles.isEmpty()) return
        for (subtitle in subtitles) {
            if (subtitle.selected) return
        }
        selectDefaultSubtitlePick()
    }

    private fun selectDefaultSubtitlePick() {
        val subtitles = getTrackInfo().subtitle
        if (subtitles.isEmpty()) return
        defaultSubtitleTrackSelected = true
        var target = subtitles[0]
        for (subtitle in subtitles) {
            if ("国语" == subtitle.language) { // i18n: keep(字幕语言匹配值)
                target = subtitle
                break
            }
        }
        selectTrack(target)
    }

    fun loadDefaultSubtitleTrackBeforeReady() {
        if (defaultSubtitleTrackSelectionClosed) return
        loadDefaultSubtitleTrack()
    }

    fun markSubtitleSelectionClosed() {
        defaultSubtitleTrackSelectionClosed = true
    }

    fun resetForNewContent() {
        defaultSubtitleTrackSelected = false
        defaultSubtitleTrackSelectionClosed = false
    }

    fun resetTrackSelection() {
        host.trackSelector().setParameters(host.trackSelector().buildUponParameters().clearSelectionOverrides().build())
        AppLog.i("echo-setTrack: clear stale overrides on content switch")
    }

    fun setContentKey(key: String?) {
        contentKey = key ?: ""
    }

    private fun isTrackIndexValid(groups: TrackGroupArray, groupIndex: Int, trackIndex: Int): Boolean {
        if (groupIndex < 0 || groupIndex >= groups.length) return false
        val group = groups.get(groupIndex)
        return trackIndex >= 0 && trackIndex < group.length
    }

    private fun isBitmapSubtitle(format: Format?): Boolean {
        val mimeType = format?.sampleMimeType ?: return false
        val lower = mimeType.lowercase(Locale.getDefault())
        return lower.contains("pgs") || lower.contains("dvb") || lower.contains("vobsub")
    }

    private fun isUndeclaredClosedCaptionTrack(format: Format?): Boolean {
        if (format == null || format.accessibilityChannel != Format.NO_VALUE) return false
        return MimeTypes.APPLICATION_CEA608 == format.sampleMimeType ||
            MimeTypes.APPLICATION_CEA708 == format.sampleMimeType
    }

    private fun isCurrentTrackSelected(format: Format?, trackType: Int): Boolean {
        val exo = host.internalPlayer() ?: return false
        val tracks = exo.currentTracks
        for (group in tracks.groups) {
            if (group.type != trackType || !group.isSelected) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i) && isSameFormat(format, group.getTrackFormat(i))) {
                    return true
                }
            }
        }
        return false
    }

    private fun isSameFormat(a: Format?, b: Format?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a.id != null && b.id != null && a.id == b.id) return true
        return a == b
    }

    private fun getLanguage(fmt: Format): String {
        val language = matchLanguage(fmt.language)
        if (language.isNotEmpty()) {
            return language
        }
        return matchLanguage(
            (fmt.label ?: "") + " " + (fmt.id ?: "") + " " + (fmt.codecs ?: ""),
        )
    }

    private fun matchLanguage(text: String?): String {
        if (text == null) return ""
        val value = text.lowercase(Locale.getDefault())
        LANG_MAP[value]?.let { return it }
        if (value.contains("yue") || value.contains("cantonese") || value.contains("粤") || value.contains("广东")) {
            return "粤语"
        }
        if (value.contains("zh") || value.contains("chi") || value.contains("zho") || value.contains("chs") ||
            value.contains("cht") || value.contains("cmn") || value.contains("中") ||
            value.contains("国语") || value.contains("普通话")
        ) {
            return "国语"
        }
        if (value.contains("en") || value.contains("eng") || value.contains("english") || value.contains("英")) {
            return "英语"
        }
        if (value.contains("ja") || value.contains("jpn") || value.contains("japanese") || value.contains("日")) {
            return "日语"
        }
        if (value.contains("ko") || value.contains("kor") || value.contains("korean") || value.contains("韩")) {
            return "韩语"
        }
        if (value.contains("tha") || value.contains("thai") || value.contains("th")) {
            return "泰语"
        }
        return ""
    }

    private fun getName(fmt: Format): String {
        val channelLabel: String = if (fmt.channelCount <= 0) {
            ""
        } else if (fmt.channelCount == 1) {
            str(R.string.player_channel_mono)
        } else if (fmt.channelCount == 2) {
            str(R.string.player_channel_stereo)
        } else {
            str(R.string.player_channel_count, fmt.channelCount)
        }

        var codec = ""
        val codecs = fmt.codecs
        if (!codecs.isNullOrEmpty()) {
            codec = codecs.uppercase(Locale.getDefault())
        }
        val mime = fmt.sampleMimeType
        if (mime != null && mime.contains("/")) {
            if (codec.isEmpty()) {
                codec = mime.substring(mime.indexOf('/') + 1).uppercase(Locale.getDefault())
            }
        }
        val builder = StringBuilder()
        appendPart(builder, fmt.label)
        appendPart(builder, codec)
        appendPart(builder, channelLabel)
        return builder.toString()
    }

    private fun subtitleName(fmt: Format): String {
        val builder = StringBuilder()
        val fromMime = subtitleFormatName(fmt.sampleMimeType)
        appendPart(builder, fromMime)
        val fromCodecs = subtitleFormatName(fmt.codecs)
        if (fromCodecs.isNotEmpty() && fromCodecs != fromMime) appendPart(builder, fromCodecs)
        appendPart(builder, fmt.label)
        return builder.toString()
    }

    private fun videoName(fmt: Format): String {
        val builder = StringBuilder()
        appendPart(builder, fmt.label)
        if (fmt.width > 0 && fmt.height > 0) {
            appendPart(builder, "${fmt.width}x${fmt.height}")
        }
        val codecs = fmt.codecs
        val mime = fmt.sampleMimeType
        if (!codecs.isNullOrEmpty()) {
            appendPart(builder, codecs.uppercase(Locale.getDefault()))
        } else if (mime != null && mime.contains("/")) {
            appendPart(builder, mime.substring(mime.indexOf('/') + 1).uppercase(Locale.getDefault()))
        }
        return builder.toString()
    }

    private fun buildDisplayName(prefix: String, number: Int, language: String?, detail: String?): String {
        val builder = StringBuilder(prefix).append(number)
        if (!language.isNullOrEmpty()) {
            builder.append(" - ").append(language)
        }
        if (!detail.isNullOrEmpty()) {
            builder.append(" ").append(detail)
        }
        return builder.toString()
    }

    private fun appendPart(builder: StringBuilder, value: String?) {
        if (value == null) return
        val part = value.trim { it <= ' ' }
        if (part.isEmpty() || "und".equals(part, ignoreCase = true) || "未知" == part) return
        if (builder.isNotEmpty()) {
            builder.append(" / ")
        }
        builder.append(part)
    }

    private fun str(resId: Int, vararg args: Any?): String =
        LanguageManager.localized(host.appContext()).getString(resId, *args)

    companion object {

        private val LANG_MAP = hashMapOf(
            "zh" to "国语",
            "zh-cn" to "国语",
            "cmn" to "国语",
            "chi" to "国语",
            "zho" to "国语",
            "chs" to "国语",
            "yue" to "粤语",
            "zh-hk" to "粤语",
            "zh-yue" to "粤语",
            "en" to "英语",
            "en-us" to "英语",
            "eng" to "英语",
            "ja" to "日语",
            "jpn" to "日语",
            "ko" to "韩语",
            "kor" to "韩语",
            "th" to "泰语",
            "tha" to "泰语",
        )
    }
}
