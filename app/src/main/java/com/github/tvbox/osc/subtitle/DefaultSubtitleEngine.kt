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

package com.github.tvbox.osc.subtitle

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.util.Log

import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.io.FileUtils
import com.github.tvbox.osc.subtitle.model.Subtitle
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import com.github.tvbox.osc.util.SubtitleHelper

import java.io.File
import java.nio.charset.Charset
import java.util.ArrayList
import java.util.TreeMap

import com.github.tvbox.osc.player.KernelPlayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DefaultSubtitleEngine : SubtitleEngine {
    private var mWorkHandler: Handler? = null
    private var mSubtitles: MutableList<Subtitle>? = null
    private var mUIRenderTask: UIRenderTask? = null
    private var mMediaPlayer: KernelPlayer? = null
    private var mOnSubtitlePreparedListener: SubtitleEngine.OnSubtitlePreparedListener? = null
    private var mOnSubtitleChangeListener: SubtitleEngine.OnSubtitleChangeListener? = null
    private var mergeSameTime: Boolean = false

    override fun bindToMediaPlayer(mediaPlayer: KernelPlayer?) {
        mMediaPlayer = mediaPlayer
    }

    override fun setSubtitlePath(path: String?) {
        initWorkThread()
        reset()
        if (path.isNullOrEmpty()) {
            Log.w(TAG, "loadSubtitleFromRemote: path is null.")
            return
        }
        val p = path

        val loadSeq = mLoadSeq
        SubtitleLoader.loadSubtitle(p, object : SubtitleLoader.Callback {
            override fun onSuccess(result: SubtitleLoadSuccessResult?) {
                if (loadSeq != mLoadSeq) {
                    LOG.i("echo-sub drop stale: " + (if (p.startsWith("data:")) "inline" else p))
                    return
                }
                if (result == null) {
                    LOG.d(TAG, "onSuccess: subtitleLoadSuccessResult is null.")
                    return
                }
                val timedTextObject = result.timedTextObject
                if (timedTextObject == null) {
                    LOG.d(TAG, "onSuccess: timedTextObject is null.")
                    return
                }
                val captions = timedTextObject.captions
                if (captions == null) {
                    LOG.d(TAG, "onSuccess: captions is null.")
                    return
                }
                val subtitles = buildSubtitles(captions)
                mSubtitles = subtitles
                LOG.i("echo-sub ready: lines=" + subtitles.size +
                        " src=" + (if (p.startsWith("data:")) "inline" else p))
                setSubtitleDelay(SubtitleHelper.getTimeDelay())
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
                if (loadSeq != mLoadSeq) return
                val e = exception
                Log.e(TAG, "onError: " + e?.message)
                LOG.e("echo-sub fail: " + (if (p.startsWith("data:")) "inline" else p) + " -> " + e?.message)
            }
        })
    }

    fun setMergeSameTime(mergeSameTime: Boolean) {
        this.mergeSameTime = mergeSameTime
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
        val currentSubtitles = mSubtitles
        if (currentSubtitles == null || currentSubtitles.isEmpty()) {
            return
        }
        mSubtitles = null
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
        mSubtitles = currentSubtitles
    }

    private var playSubtitleCacheKey: String? = null

    private var mLoadSeq: Int = 0

    override fun setPlaySubtitleCacheKey(cacheKey: String?) {
        playSubtitleCacheKey = cacheKey
    }

    override fun getPlaySubtitleCacheKey(): String? {
        return playSubtitleCacheKey
    }

    override fun reset() {
        stop()
        mSubtitles = null
        mUIRenderTask = null
        mLoadSeq++
    }

    override fun start() {
        LOG.d(TAG, "start: ")
        if (mMediaPlayer == null) {
            Log.w(TAG, "MediaPlayer is not bind, You must bind MediaPlayer to " +
                    SubtitleEngine::class.java.simpleName +
                    " before start() method be called," +
                    " you can do this by call " +
                    "bindToMediaPlayer(MediaPlayer mediaPlayer) method.")
            return
        }
        stop()
        mWorkHandler?.sendEmptyMessageDelayed(MSG_REFRESH, REFRESH_INTERVAL.toLong())
    }

    override fun pause() {
        stop()
    }

    override fun resume() {
        start()
    }

    override fun stop() {
        mWorkHandler?.removeMessages(MSG_REFRESH)
    }

    override fun destroy() {
        LOG.d(TAG, "destroy: ")
        stopWorkThread()
        reset()
    }

    private fun initWorkThread() {
        stopWorkThread()
        mWorkHandler = Handler(Looper.getMainLooper(), Handler.Callback { msg: Message ->
            try {
                var delay: Long = REFRESH_INTERVAL.toLong()
                val mediaPlayer = mMediaPlayer
                if (mediaPlayer != null && mediaPlayer.isPlaying) {
                    val position = mediaPlayer.currentPosition
                    val subtitle = SubtitleFinder.find(position, mSubtitles)
                    notifyRefreshUI(subtitle)
                    val subtitleEnd = subtitle?.end
                    if (subtitleEnd != null) {
                        delay = subtitleEnd.mseconds - position
                    }
                }
                mWorkHandler?.sendEmptyMessageDelayed(MSG_REFRESH, delay)
            } catch (e: Exception) {
                LOG.d("DefaultSubtitleEngine", "subtitle refresh tick failed")
            }
            true
        })
    }

    private fun stopWorkThread() {
        mWorkHandler?.removeCallbacksAndMessages(null)
        mWorkHandler = null
    }

    private fun notifyRefreshUI(subtitle: Subtitle?) {
        val renderTask = mUIRenderTask ?: UIRenderTask(mOnSubtitleChangeListener).also { mUIRenderTask = it }
        renderTask.execute(subtitle)
    }

    private fun notifyPrepared() {
        mOnSubtitlePreparedListener?.onSubtitlePrepared(mSubtitles)
    }

    override fun setOnSubtitlePreparedListener(listener: SubtitleEngine.OnSubtitlePreparedListener?) {
        mOnSubtitlePreparedListener = listener
    }

    override fun setOnSubtitleChangeListener(listener: SubtitleEngine.OnSubtitleChangeListener?) {
        mOnSubtitleChangeListener = listener
    }

    companion object {
        private const val TAG = "DefaultSubtitleEngine"
        private const val MSG_REFRESH = 0x888
        private const val REFRESH_INTERVAL = 100
    }
}
