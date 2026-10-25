/*
 *                       Copyright (C) of Avery
 *
 *                              _ooOoo_
 *                             o8888888o
 *                             88" . "88
 *                             (| -_- |)
 *                             O\  =  /O
 *                          ____/`- -'\____
 *                        .'  \\|     |//  `.
 *                       /  \\|||  :  |||//  \
 *                      /  _||||| -:- |||||-  \
 *                      |   | \\\  -  /// |   |
 *                      | \_|  ''\- -/''  |   |
 *                      \  .-\__  `-`  ___/-. /
 *                    ___`. .' /- -.- -\  `. . __
 *                 ."" '<  `.___\_<|>_/___.'  >'"".
 *                | | :  `- \`.;`\ _ /`;.`/ - ` : | |
 *                \  \ `-.   \_ __\ /__ _/   .-` /  /
 *           ======`-.____`-.___\_____/___.-`____.-'======
 *                              `=- -='
 *           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *              Buddha bless, there will never be bug!!!
 */

package com.github.avbox.osc.subtitle

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.util.Log

import com.github.avbox.osc.base.App
import com.github.avbox.osc.data.AppGraph
import com.github.avbox.osc.io.FileUtils
import com.github.avbox.osc.subtitle.model.Subtitle
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.MD5
import com.github.avbox.osc.util.SubtitleHelper

import java.io.File
import java.nio.charset.Charset
import java.util.ArrayList
import java.util.TreeMap

import com.github.avbox.osc.player.core.KernelPlayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DefaultSubtitleEngine : SubtitleEngine {
    private var workHandler: Handler? = null
    private var loadedSubtitles: MutableList<Subtitle>? = null
    private var uiRenderTask: UIRenderTask? = null
    private var attachedPlayer: KernelPlayer? = null
    private var onSubtitlePreparedListener: SubtitleEngine.OnSubtitlePreparedListener? = null
    private var subtitleChangeListener: SubtitleEngine.OnSubtitleChangeListener? = null
    var mergeSameTime: Boolean = false

    override fun bindToMediaPlayer(mediaPlayer: KernelPlayer?) {
        attachedPlayer = mediaPlayer
    }

    override fun setSubtitlePath(path: String?) {
        initWorkThread()
        reset()
        if (path.isNullOrEmpty()) {
            Log.w(TAG, "loadSubtitleFromRemote: path is null.")
            return
        }
        val p = path

        val loadSeq = loadGeneration
        SubtitleLoader.loadSubtitle(p, object : SubtitleLoader.Callback {
            override fun onSuccess(result: SubtitleLoadSuccessResult?) {
                if (loadSeq != loadGeneration) {
                    AppLog.i("echo-sub drop stale: " + (if (p.startsWith("data:")) "inline" else p))
                    return
                }
                if (result == null) {
                    AppLog.d(TAG, "onSuccess: subtitleLoadSuccessResult is null.")
                    return
                }
                val timedTextObject = result.timedTextObject
                if (timedTextObject == null) {
                    AppLog.d(TAG, "onSuccess: timedTextObject is null.")
                    return
                }
                val captions = timedTextObject.captions
                if (captions == null) {
                    AppLog.d(TAG, "onSuccess: captions is null.")
                    return
                }
                val subtitles = buildSubtitles(captions)
                loadedSubtitles = subtitles
                AppLog.i("echo-sub ready: lines=" + subtitles.size +
                        " src=" + (if (p.startsWith("data:")) "inline" else p))
                setSubtitleDelay(SubtitleHelper.timeDelay)
                notifyPrepared()

                val subtitlePath = result.subtitlePath ?: ""
                if (subtitlePath.startsWith("http://") || subtitlePath.startsWith("https://")) {
                    val subtitleFileCacheDir = requireNotNull(App.getInstance()) { "app instance not ready" }.cacheDir.absolutePath + "/zimu/"
                    val cacheDir = File(subtitleFileCacheDir)
                    if (!cacheDir.exists()) {
                        cacheDir.mkdirs()
                    }
                    val subtitleFile = subtitleFileCacheDir + result.fileName
                    val cacheSubtitleFile = File(subtitleFile)
                    val writeResult = FileUtils.writeSimple(result.content.orEmpty().toByteArray(Charset.defaultCharset()), cacheSubtitleFile)
                    if (writeResult && playSubtitleCacheKey != null) {
                        val cacheKey = requireNotNull(MD5.string2MD5(getPlaySubtitleCacheKey()))
                        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                            AppGraph.cacheRepository.save(cacheKey, subtitleFile)
                        }
                    }
                } else {
                    val cacheKey = requireNotNull(MD5.string2MD5(getPlaySubtitleCacheKey()))
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        AppGraph.cacheRepository.save(cacheKey, p)
                    }
                }
            }

            override fun onError(exception: Exception?) {
                if (loadSeq != loadGeneration) return
                val e = exception
                Log.e(TAG, "onError: " + e?.message)
                AppLog.e("echo-sub fail: " + (if (p.startsWith("data:")) "inline" else p) + " -> " + e?.message)
            }
        })
    }

    private fun buildSubtitles(captions: TreeMap<Int, Subtitle>): MutableList<Subtitle> {
        val subtitles = ArrayList<Subtitle>()
        for (subtitle in captions.values) {
            if (mergeSameTime && !subtitles.isEmpty()) {
                val previous = subtitles[subtitles.size - 1]
                val previousStart = previous.start
                val previousEnd = previous.end
                val subtitleStart = subtitle.start
                val subtitleEnd = subtitle.end
                if (previousStart != null && previousEnd != null && subtitleStart != null && subtitleEnd != null &&
                    previousStart.mseconds == subtitleStart.mseconds &&
                    previousEnd.mseconds == subtitleEnd.mseconds) {
                    var previousLines = previous.lines
                    if (previousLines == null) {
                        previousLines = ArrayList()
                        previous.lines = previousLines
                        previousLines.add(previous)
                    }
                    previousLines.add(subtitle)
                    if (!TextUtils.isEmpty(subtitle.content)) {
                        if (!TextUtils.isEmpty(previous.content)) previous.content += "\n"
                        previous.content += subtitle.content
                    }
                    continue
                }
            }
            if (mergeSameTime) {
                val lines = ArrayList<Subtitle>()
                lines.add(subtitle)
                subtitle.lines = lines
            }
            subtitles.add(subtitle)
        }
        return subtitles
    }

    override fun setSubtitleDelay(milliseconds: Int?) {
        if (milliseconds == 0) {
            return
        }
        val currentSubtitles = loadedSubtitles
        if (currentSubtitles == null || currentSubtitles.isEmpty()) {
            return
        }
        loadedSubtitles = null
        val ms = requireNotNull(milliseconds) { "subtitle delay is not set" }
        for (i in currentSubtitles.indices) {
            val subtitle = currentSubtitles[i]
            val start = subtitle.start ?: continue
            val end = subtitle.end ?: continue
            start.mseconds += ms
            end.mseconds += ms
            if (start.mseconds <= 0) {
                start.mseconds = 0
            }
            if (end.mseconds <= 0) {
                end.mseconds = 0
            }
            subtitle.start = start
            subtitle.end = end
        }
        loadedSubtitles = currentSubtitles
    }

    private var playSubtitleCacheKey: String? = null

    private var loadGeneration: Int = 0

    override fun setPlaySubtitleCacheKey(cacheKey: String?) {
        playSubtitleCacheKey = cacheKey
    }

    override fun getPlaySubtitleCacheKey(): String? {
        return playSubtitleCacheKey
    }

    override fun reset() {
        stop()
        loadedSubtitles = null
        uiRenderTask = null
        loadGeneration++
    }

    override fun start() {
        AppLog.d(TAG, "start: ")
        if (attachedPlayer == null) {
            Log.w(TAG, "MediaPlayer is not bind, You must bind MediaPlayer to " +
                    SubtitleEngine::class.java.simpleName +
                    " before start() method be called," +
                    " you can do this by call " +
                    "bindToMediaPlayer(MediaPlayer mediaPlayer) method.")
            return
        }
        stop()
        workHandler?.sendEmptyMessageDelayed(MSG_REFRESH, REFRESH_INTERVAL.toLong())
    }

    override fun pause() {
        stop()
    }

    override fun resume() {
        start()
    }

    override fun stop() {
        workHandler?.removeMessages(MSG_REFRESH)
    }

    override fun destroy() {
        AppLog.d(TAG, "destroy: ")
        stopWorkThread()
        reset()
    }

    private fun initWorkThread() {
        stopWorkThread()
        workHandler = Handler(Looper.getMainLooper(), Handler.Callback { msg: Message ->
            try {
                var delay: Long = REFRESH_INTERVAL.toLong()
                val mediaPlayer = attachedPlayer
                if (mediaPlayer != null && mediaPlayer.isPlaying) {
                    val position = mediaPlayer.currentPosition
                    val subtitle = SubtitleFinder.find(position, loadedSubtitles)
                    notifyRefreshUI(subtitle)
                    val subtitleEnd = subtitle?.end
                    if (subtitleEnd != null) {
                        delay = subtitleEnd.mseconds - position
                    }
                }
                workHandler?.sendEmptyMessageDelayed(MSG_REFRESH, delay)
            } catch (e: Exception) {
                AppLog.d("DefaultSubtitleEngine", "subtitle refresh tick failed")
            }
            true
        })
    }

    private fun stopWorkThread() {
        workHandler?.removeCallbacksAndMessages(null)
        workHandler = null
    }

    private fun notifyRefreshUI(subtitle: Subtitle?) {
        val renderTask = uiRenderTask ?: UIRenderTask(subtitleChangeListener).also { uiRenderTask = it }
        renderTask.execute(subtitle)
    }

    private fun notifyPrepared() {
        onSubtitlePreparedListener?.onSubtitlePrepared(loadedSubtitles)
    }

    override fun setOnSubtitlePreparedListener(listener: SubtitleEngine.OnSubtitlePreparedListener?) {
        onSubtitlePreparedListener = listener
    }

    override fun setOnSubtitleChangeListener(listener: SubtitleEngine.OnSubtitleChangeListener?) {
        subtitleChangeListener = listener
    }

    companion object {
        private const val TAG = "DefaultSubtitleEngine"
        private const val MSG_REFRESH = 0x888
        private const val REFRESH_INTERVAL = 100
    }
}
