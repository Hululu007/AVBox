package com.github.avbox.osc.ui.player

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import com.github.avbox.osc.R
import com.github.avbox.osc.data.AppGraph
import com.github.avbox.osc.player.ExoPlayer
import com.github.avbox.osc.player.core.KernelPlayer
import com.github.avbox.osc.player.core.TrackInfo
import com.github.avbox.osc.player.state.PlaybackSettingsState
import com.github.avbox.osc.player.state.SelectDialogState
import com.github.avbox.osc.player.state.SubtitleSearchSheetState
import com.github.avbox.osc.player.state.SubtitleSheetState
import com.github.avbox.osc.sourcedata.SubtitleViewModel
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.MD5
import com.github.avbox.osc.util.SubtitleHelper
import com.github.avbox.osc.util.TrackMemory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

internal class PlayContainerSubtitles(private val host: PlayContainer) {

    private val exoCues: MutableList<Cue> = ArrayList()

    private var exoInternalSubtitle: Boolean = false

    private var subtitleDecisionSeq: Int = 0

    private var subtitleInitSeq: Int = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun setSubtitle(path: String?) {
        if (path != null && path.length > 0) {
            subtitleDecisionSeq++
            hideExoInternalSubtitle()
            host.playerController.getSubtitleView().setVisibility(View.GONE)
            host.playerController.getSubtitleView().setSubtitlePath(path)
            setSubtitleViewTextStyle(PlaybackSettingsState.config.subtitleTextStyle)
            host.playerController.getSubtitleView().setVisibility(View.VISIBLE)
        }
    }

    fun selectMySubtitle() {
        try {
            if (!host.isAttached()) return
            val view = host.kernelView ?: return
            val uiState = host.playerController.getUiState()
            val mediaPlayer = view.mediaPlayer
            val hasInternal = host.playerController.getSubtitleView().hasInternal || hasExoInternalSubtitle(mediaPlayer)
            val exoInternal = mediaPlayer is ExoPlayer && exoInternalSubtitle
            uiState.openSubtitleSheet(SubtitleSheetState(
                exoInternal,
                hasInternal,
                {
                    selectMyInternalSubtitle()
                },
                {
                    openLocalSubtitleChooser()
                },
                {
                    openSubtitleSearchSheet()
                },
                { style ->
                    PlaybackSettingsState.subtitleTextStyle = style
                    setSubtitleViewTextStyle(style)
                },
                {
                    applySubtitleTextSize()
                },
                {
                    SubtitleHelper.reset()
                    PlaybackSettingsState.reloadSubtitleTextStyle()
                    setSubtitleViewTextStyle(0)
                    applySubtitleTextSize()
                },
            ))
        } catch (e: Exception) {
            AppLog.e("PlayContainer", e)
        }
    }

    private fun openLocalSubtitleChooser() {
        host.pageHostRef?.launchLocalSubtitlePicker()
    }

    fun onLocalSubtitlePicked(uri: Uri) {
        val activity = host.attachedActivity
        if (activity == null || activity.isFinishing) return
        Thread {
            try {
                var name = queryDisplayName(activity, uri)
                if (name == null || !name.contains(".")) name = "local_subtitle.srt"
                name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val dst = File(activity.cacheDir, "subtitle_" + System.currentTimeMillis() + "_" + name)
                activity.contentResolver.openInputStream(uri).use { input ->
                    java.io.FileOutputStream(dst).use { out ->
                        val buf = ByteArray(8192)
                        var len = 0
                        requireNotNull(input) { "subtitle input stream is null" }
                        while (input.read(buf).also { len = it } > 0) out.write(buf, 0, len)
                    }
                }
                val path = dst.absolutePath
                activity.runOnUiThread {
                    if (!host.isAttached()) return@runOnUiThread
                    AppLog.i("echo-Local Subtitle Path: " + path)
                    TrackMemory.saveSubtitle(trackMemoryKey(), TrackMemory.subtitleLocal(path))
                    setSubtitle(path)
                }
            } catch (e: Exception) {
                AppLog.e("echo-Local Subtitle copy err: " + e)
                activity.runOnUiThread {
                    if (host.isAttached()) {
                        Toast.makeText(activity, activity.getString(R.string.toast_subtitle_read_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun queryDisplayName(activity: Activity, uri: Uri): String? {
        try {
            activity.contentResolver.query(uri, null, null, null, null).use { c ->
                if (c != null && c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        } catch (ignored: Exception) {
            AppLog.d("PlayContainer", "query display name failed, keep null")
        }
        return null
    }

    private fun openSubtitleSearchSheet() {
        if (!host.isAttached()) return
        val vod = host.scheduler.vod() ?: return
        val playFlag = vod.playFlag ?: ""
        val word = if (playFlag.contains("Ali") || playFlag.contains("parse")) {
            vod.playNote
        } else {
            vod.name
        }
        val uiState = host.playerController.getUiState()
        uiState.openSubtitleSearchSheet(SubtitleSearchSheetState(word ?: "") { subtitle, releaseUrl ->
            if (host.isAttached()) {
                runOnUi {
                    val zimuUrl = subtitle.url
                    AppLog.i("echo-Remote Subtitle Url: " + zimuUrl)
                    TrackMemory.saveSubtitle(
                        trackMemoryKey(),
                        TrackMemory.subtitleOnline(releaseUrl, subtitle.name),
                    )
                    setSubtitle(zimuUrl)
                }
            }
        })
    }

    @SuppressLint("UseCompatLoadingForColorStateLists")
    fun setSubtitleViewTextStyle(style: Int) {
        if (style == 0) {
            host.playerController.getSubtitleView().setTextColor(host.context.resources.getColorStateList(R.color.color_FFFFFF))
        } else if (style == 1) {
            host.playerController.getSubtitleView().setTextColor(host.context.resources.getColorStateList(R.color.color_FFB6C1))
        }
        applyExoSubtitleStyle()
    }

    fun selectMyInternalSubtitle() {
        val view = host.kernelView ?: return
        val mediaPlayer = view.mediaPlayer
        var trackInfo: TrackInfo? = null
        if (mediaPlayer is ExoPlayer) {
            trackInfo = mediaPlayer.getTrackInfo()
        }
        if (trackInfo == null) {
            Toast.makeText(host.context, host.context.getString(R.string.player_no_internal_subtitle), Toast.LENGTH_SHORT).show()
            return
        }
        val bean = trackInfo.subtitle
        if (bean.size < 1) return
        val names = ArrayList<String>()
        for (item in bean) names.add(item.name ?: "")
        AppLog.i(
            "echo-setTrack subtitle-list: count=" + bean.size +
                " selected=" + trackInfo.subtitleSelected(false) + " names=" + names,
        )
        host.playerController.getUiState().openSelectDialog(SelectDialogState(
            host.context.getString(R.string.player_switch_internal_subtitle),
            names,
            trackInfo.subtitleSelected(false),
        ) { pos ->
            if (pos >= 0 && pos < bean.size) {
                val value = bean[pos]
                try {
                    subtitleDecisionSeq++
                    for (subtitle in bean) {
                        subtitle.selected = TrackSelectorDelegate.isSameTrack(subtitle, value)
                    }
                    if (mediaPlayer is ExoPlayer) {
                        host.playerController.getSubtitleView().setVisibility(View.GONE)
                        host.playerController.getSubtitleView().destroy()
                        host.playerController.getSubtitleView().clearSubtitleCache()
                        host.playerController.getSubtitleView().isInternal = false
                        exoInternalSubtitle = true
                        mediaPlayer.setTrack(value)
                        mediaPlayer.setInternalSubtitleDelay(SubtitleHelper.timeDelay)
                        host.playerController.getExoSubtitleView().setVisibility(View.VISIBLE)
                        applyExoSubtitleSettings()
                    }
                } catch (e: Exception) {
                    AppLog.e("echo-switch-internal-subtitle-error:" + e.message)
                }
            }
        })
    }

    private fun hasExoInternalSubtitle(mediaPlayer: KernelPlayer?): Boolean {
        if (mediaPlayer !is ExoPlayer) return false
        val trackInfo = mediaPlayer.getTrackInfo()
        return !trackInfo.subtitle.isEmpty()
    }

    private fun hideExoInternalSubtitle() {
        exoInternalSubtitle = false
        exoCues.clear()
        if (host.playerController != null && host.playerController.getExoSubtitleView() != null) {
            host.playerController.getExoSubtitleView().setCues(exoCues)
            host.playerController.getExoSubtitleView().setVisibility(View.GONE)
        }
    }

    private fun onExoCues(cues: List<Cue>?) {
        if (!host.isAttached() || !exoInternalSubtitle) return
        exoCues.clear()
        if (cues != null) exoCues.addAll(cues)
        runOnUi {
            applyExoSubtitleSettings()
        }
    }

    private fun applyExoSubtitleSettings() {
        if (!exoInternalSubtitle || host.playerController == null || host.playerController.getExoSubtitleView() == null) return
        applyExoSubtitleStyle()
        val scale = SubtitleHelper.exoSubtitleScale / 100f
        val position = SubtitleHelper.exoSubtitlePosition
        host.playerController.getExoSubtitleView().setFractionalTextSize(0.0533f * scale)
        host.playerController.getExoSubtitleView().setBottomPaddingFraction(limit(0.08f + position / 100f, 0f, 0.9f))

        val displayCues = ArrayList<Cue>()
        for (cue in exoCues) {
            if (cue.bitmap == null) {
                displayCues.add(cue)
                continue
            }
            val builder = cue.buildUpon()
            if (cue.size != Cue.DIMEN_UNSET) {
                builder.setSize(limit(cue.size * scale, 0f, 1f))
            }
            if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
                builder.setBitmapHeight(limit(cue.bitmapHeight * scale, 0f, 1f))
            }
            if (cue.line != Cue.DIMEN_UNSET) {
                builder.setLine(limit(cue.line - position / 100f, 0f, 1f), cue.lineType)
            }
            displayCues.add(builder.build())
        }
        host.playerController.getExoSubtitleView().setCues(displayCues)
    }

    private fun applyExoSubtitleStyle() {
        if (host.playerController == null || host.playerController.getExoSubtitleView() == null) return
        val style = PlaybackSettingsState.config.subtitleTextStyle
        val textColor = host.context.resources.getColorStateList(
            if (style == 1) R.color.color_FFB6C1 else R.color.color_FFFFFF,
        ).defaultColor
        host.playerController.getExoSubtitleView().setStyle(
            CaptionStyleCompat(
                textColor,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                Typeface.DEFAULT_BOLD,
            ),
        )
    }

    private fun limit(value: Float, min: Float, max: Float): Float {
        return Math.max(min, Math.min(max, value))
    }

    fun initSubtitleView() {
        val seq = ++subtitleInitSeq
        scope.launch {
            val view = host.kernelView ?: return@launch
            val lyricKey = host.scheduler.lyricCacheKey()
            val subtitleKey = host.scheduler.subtitleCacheKey()
            val cachedPaths = async(Dispatchers.IO) {
                cachedPlayPath(lyricKey) to cachedPlayPath(subtitleKey)
            }
            var trackInfo: TrackInfo? = null
            val mediaPlayer = view.mediaPlayer
            host.playerController.getLyricView().setTextSize(if (host.playerController.getUiState().previewMode) 16f else 24f)
            applySubtitleTextSize()
            host.playerController.getLyricView().setVisibility(View.GONE)
            host.playerController.getLyricView().reset()
            host.playerController.getLyricView().bindToMediaPlayer(mediaPlayer)
            host.playerController.getLyricView().setMergeSameTime(true)
            host.playerController.getLyricView().lyricMode = true
            host.playerController.getLyricView().setPlaySubtitleCacheKey(lyricKey)
            host.playerController.getSubtitleView().hasInternal = false
            host.playerController.getSubtitleView().isInternal = false
            hideExoInternalSubtitle()
            val memoryKey = trackMemoryKey()
            if (mediaPlayer is ExoPlayer) {
                mediaPlayer.setContentKey(memoryKey)
                trackInfo = mediaPlayer.getTrackInfo()
                if (trackInfo != null && !trackInfo.subtitle.isEmpty()) {
                    host.playerController.getSubtitleView().hasInternal = true
                    exoInternalSubtitle = true
                    host.playerController.getExoSubtitleView().setVisibility(View.VISIBLE)
                    mediaPlayer.setInternalSubtitleDelay(SubtitleHelper.timeDelay)
                    mediaPlayer.setOnCuesListener { cues -> onExoCues(cues) }
                    applyExoSubtitleSettings()
                }
                mediaPlayer.restoreTracks()
            }
            val cached = cachedPaths.await()
            if (seq != subtitleInitSeq) return@launch
            var lyricPath = host.scheduler.playLyric()
            if (lyricPath.isNullOrEmpty() || !lyricPath.startsWith("data:")) {
                if (!TextUtils.isEmpty(cached.first)) lyricPath = cached.first
            }
            if (!TextUtils.isEmpty(lyricPath)) {
                host.playerController.getLyricView().setSubtitlePath(lyricPath)
                host.playerController.getLyricView().setVisibility(View.VISIBLE)
            }
            host.playerController.getSubtitleView().bindToMediaPlayer(view.mediaPlayer)
            host.playerController.getSubtitleView().setPlaySubtitleCacheKey(subtitleKey)
            applySubtitleDecision(mediaPlayer, trackInfo, cached.second)
        }
    }

    private fun applySubtitleDecision(mediaPlayer: KernelPlayer?, trackInfo: TrackInfo?, cachedSubtitlePath: String) {
        val memoryKey = trackMemoryKey()
        subtitleDecisionSeq++
        val record = TrackMemory.loadSubtitle(memoryKey)
        if (TrackMemory.isSubtitleOff(record)) {
            closeSubtitleViews()
            return
        }
        if (TrackMemory.isSubtitleLocal(record)) {
            val path = TrackMemory.localPath(record)
            if (!TextUtils.isEmpty(path) && File(path).exists()) {
                setSubtitle(path)
                return
            }
            AppLog.i("echo-track-memory local subtitle gone, fallback: " + path)
        } else if (TrackMemory.isSubtitleOnline(record)) {
            val player = mediaPlayer
            val info = trackInfo
            resolveRememberedOnlineSubtitle(memoryKey, record) {
                applyDefaultSubtitle(player, info, cachedSubtitlePath)
            }
            return
        } else if (TrackMemory.isSubtitleTrack(record) && host.playerController.getSubtitleView().hasInternal) {
            showInternalSubtitle(mediaPlayer)
            return
        }
        applyDefaultSubtitle(mediaPlayer, trackInfo, cachedSubtitlePath)
    }

    private fun applyDefaultSubtitle(mediaPlayer: KernelPlayer?, trackInfo: TrackInfo?, subtitlePathCache: String) {
        if (!subtitlePathCache.isEmpty()) {
            hideExoInternalSubtitle()
            host.playerController.getSubtitleView().setSubtitlePath(subtitlePathCache)
            return
        }
        val playSubtitle = host.scheduler.playSubtitle()
        if (!playSubtitle.isNullOrEmpty()) {
            hideExoInternalSubtitle()
            host.playerController.getSubtitleView().setSubtitlePath(playSubtitle)
            return
        }
        if (!host.playerController.getSubtitleView().hasInternal) return
        ensureInternalSubtitleTrackSelected(mediaPlayer, trackInfo)
        showInternalSubtitle(mediaPlayer)
    }

    private fun showInternalSubtitle(mediaPlayer: KernelPlayer?) {
        if (mediaPlayer is ExoPlayer) {
            mediaPlayer.setInternalSubtitleDelay(SubtitleHelper.timeDelay)
            exoInternalSubtitle = true
            host.playerController.getExoSubtitleView().setVisibility(View.VISIBLE)
            applyExoSubtitleSettings()
        }
    }

    private fun ensureInternalSubtitleTrackSelected(mediaPlayer: KernelPlayer?, trackInfo: TrackInfo?) {
        if (mediaPlayer is ExoPlayer) {
            mediaPlayer.ensureSubtitleTrackSelected()
        }
    }

    private fun resolveRememberedOnlineSubtitle(memoryKey: String, record: String?, fallback: Runnable) {
        val releaseUrl = TrackMemory.onlineRelease(record)
        if (TextUtils.isEmpty(releaseUrl) || host.attachedActivity !is ViewModelStoreOwner) {
            runOnUi(fallback)
            return
        }
        val vod = host.scheduler.vod()
        val series = if (vod == null) null else host.scheduler.currentSeries(vod.playFlag, vod.playIndex)
        val episodeName = series?.name ?: ""
        val fileNameHint = TrackMemory.onlineFileName(record)
        val episodeKey = host.scheduler.progressKey()
        val decisionSeq = subtitleDecisionSeq
        AppLog.i("echo-track-memory online subtitle: release=" + releaseUrl + " episode=" + episodeName)
        ViewModelProvider(host.attachedActivity as ViewModelStoreOwner).get(SubtitleViewModel::class.java).pickEpisodeSubtitle(
            releaseUrl, episodeName, fileNameHint,
            { subtitle ->
                runOnUi {
                    if (!isSubtitleResultCurrent(memoryKey, episodeKey, decisionSeq)) return@runOnUi
                    val url = subtitle?.url
                    if (TextUtils.isEmpty(url)) {
                        AppLog.i("echo-track-memory online subtitle empty url, fallback")
                        fallback.run()
                        return@runOnUi
                    }
                    AppLog.i("echo-track-memory online subtitle picked: " + subtitle.name)
                    setSubtitle(url)
                }
            },
            {
                runOnUi {
                    if (!isSubtitleResultCurrent(memoryKey, episodeKey, decisionSeq)) return@runOnUi
                    AppLog.i("echo-track-memory online subtitle miss, fallback")
                    fallback.run()
                }
            },
        )
    }

    private fun isSubtitleResultCurrent(memoryKey: String, episodeKey: String?, decisionSeq: Int): Boolean {
        if (!host.isAttached() || subtitleDecisionSeq != decisionSeq) return false
        if (!TextUtils.equals(memoryKey, trackMemoryKey())) return false
        return TextUtils.equals(episodeKey, host.scheduler.progressKey())
    }

    private fun runOnUi(action: Runnable) {
        val activity = host.attachedActivity
        if (activity == null) return
        activity.runOnUiThread(action)
    }

    private fun closeSubtitleViews() {
        try {
            hideExoInternalSubtitle()
            host.playerController.getSubtitleView().setVisibility(View.GONE)
            host.playerController.getSubtitleView().destroy()
            host.playerController.getSubtitleView().clearSubtitleCache()
            host.playerController.getSubtitleView().isInternal = false
        } catch (e: Exception) {
            AppLog.e("echo-close-subtitle-error:" + e.message)
        }
    }

    fun closeSubtitles() {
        if (host.kernelView == null) return
        closeSubtitleViews()
        subtitleDecisionSeq++
        TrackMemory.saveSubtitle(trackMemoryKey(), TrackMemory.SUBTITLE_OFF)
    }

    fun trackMemoryKey(): String {
        val vod = host.scheduler?.vod()
        if (vod == null) return ""
        return TrackMemory.contentKey(vod.sourceKey, vod.id)
    }

    private suspend fun cachedPlayPath(cacheKey: String?): String {
        if (TextUtils.isEmpty(cacheKey)) return ""
        val cached = AppGraph.cacheRepository.get(MD5.string2MD5(cacheKey))
        if (cached !is String) return ""
        val path = cached
        if (TextUtils.isEmpty(path)) return ""
        if (path.startsWith("data:")) return path
        return if (File(path).exists()) path else ""
    }

    fun clearLyricView() {
        if (host.playerController == null || host.playerController.getLyricView() == null) return
        host.playerController.getLyricView().setVisibility(View.GONE)
        host.playerController.getLyricView().destroy()
        host.playerController.getLyricView().setText("")
    }

    fun applySubtitleTextSize() {
        if (host.playerController == null || host.playerController.getSubtitleView() == null) return
        val size = SubtitleHelper.getTextSize(host.attachedActivity)
        host.playerController.getSubtitleView().setTextSize(if (host.playerController.getUiState().previewMode) size * 0.6f else size.toFloat())
    }
}
