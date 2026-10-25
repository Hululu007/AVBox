package com.github.avbox.osc.sourcedata

import android.content.Context
import android.net.Uri
import android.text.TextUtils

import com.github.avbox.osc.R
import com.github.avbox.osc.bean.Movie
import com.github.avbox.osc.io.FileUtils
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.LanguageManager
import com.github.avbox.osc.util.RegexUtils
import com.xunlei.downloadlib.XLDownloadManager
import com.xunlei.downloadlib.XLTaskHelper
import com.xunlei.downloadlib.android.XLUtil
import com.xunlei.downloadlib.parameter.TorrentFileInfo
import com.xunlei.downloadlib.parameter.XLTaskInfo

import com.github.avbox.osc.util.AppContextHolder
import java.io.File
import java.util.ArrayList
import java.util.HashMap
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object Thunder {

    private const val PARSE_BUDGET_MS = 10_000L
    private const val PLAY_META_BUDGET_MS = 20_000L
    private const val PLAY_STAGE_BUDGET_MS = 10_000L
    private const val PLAY_PARSE_POLL_MS = 200L

    private val parseGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val playGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    private fun str(resId: Int, vararg args: Any?): String {
        val app = AppContextHolder.context()
        return if (app == null) "" else LanguageManager.localized(app).getString(resId, *args)
    }

    private var cacheRoot = ""
    private var localPath = ""
    private var name = ""
    private var task_url = ""
    @Volatile
    private var currentTask = 0L

    @Volatile
    private var streamTaskId = 0L

    @Volatile
    private var parseTaskId = 0L

    private val cancelMark = java.util.concurrent.atomic.AtomicInteger(0)
    private var torrentFileInfoArrayList: ArrayList<TorrentFileInfo>? = null
    private var threadPool: ExecutorService? = null
    private var playPool: ExecutorService? = null

    private fun init(context: Context) {
        var imei = KV.get(KVKeys.THUNDER_IMEI, "")
        var mac = KV.get(KVKeys.THUNDER_MAC, "")
        if (TextUtils.isEmpty(imei)) {
            imei = randomImei()
            KV.put(KVKeys.THUNDER_IMEI, imei)
        }
        if (TextUtils.isEmpty(mac)) {
            mac = randomMac()
            KV.put(KVKeys.THUNDER_MAC, mac)
        }

        XLUtil.mIMEI = imei
        XLUtil.isGetIMEI = true
        XLUtil.mMAC = mac
        XLUtil.isGetMAC = true
        val cd3 = "cee25055f125a2fde0"
        val base64Decode = "axzNjAwMQ^^yb==0^852^083dbcff^"
        val substring = base64Decode.substring(1)
        val substring2 = cd3.substring(0, cd3.length - 1)
        val cd = substring + substring2
        XLTaskHelper.init(context, cd, "21.01.07.800002")
        cacheRoot = context.cacheDir.absolutePath + File.separator + "thunder"
    }

    @JvmStatic
    fun stop(bool: Boolean) {
        playGeneration.incrementAndGet()
        cancelMark.incrementAndGet()
        stopCurrentTask()
        stopStreamTask()
        if (bool) {
            parseGeneration.incrementAndGet()
            torrentFileInfoArrayList = null
            val cache = File(if (task_url.isEmpty()) cacheRoot else localPath)
            recursiveDelete(cache)
            if (!cache.exists())
                cache.mkdirs()
            threadPool?.shutdownNow()
            threadPool = null
            playPool?.shutdownNow()
            playPool = null
        }
    }

    private fun stopCurrentTask() {
        if (currentTask > 0) {
            XLTaskHelper.instance().stopTask(currentTask)
            currentTask = 0L
        }
    }

    private fun stopStreamTask() {
        if (streamTaskId > 0) {
            XLTaskHelper.instance().stopTask(streamTaskId)
            streamTaskId = 0L
        }
    }

    @JvmStatic
    fun cancelPendingParse() {
        parseGeneration.incrementAndGet()
        playGeneration.incrementAndGet()
        cancelMark.incrementAndGet()
        stopCurrentTask()
        val pending = parseTaskId
        if (pending > 0) {
            XLTaskHelper.instance().stopTask(pending)
            parseTaskId = 0L
        }
        threadPool?.shutdownNow()
        threadPool = Executors.newSingleThreadExecutor()
        playPool?.shutdownNow()
        playPool = null
    }

    interface ThunderCallback {

        fun status(code: Int, info: String)

        fun list(urlMap: MutableMap<Int, String>)

        fun play(url: String)
    }

    private var playList: ArrayList<String>? = null
    private var ed2kList: ArrayList<String>? = null
    @JvmStatic
    fun parse(context: Context, urlBean: Movie.Video.UrlBean?, callback: ThunderCallback) {
        init(context)
        stopCurrentTask()
        val gen = parseGeneration.incrementAndGet()
        threadPool?.shutdownNow()
        val pool = Executors.newSingleThreadExecutor()
        threadPool = pool
        if (streamTaskId <= 0) {
            val cache = File(cacheRoot)
            recursiveDelete(cache)
            if (!cache.exists()) cache.mkdirs()
        }
        torrentFileInfoArrayList = ArrayList()
        playList = ArrayList()
        ed2kList = ArrayList()
        val urlMap = HashMap<Int, String>()
        pool.execute(ParseTask(urlBean, urlMap, callback, gen))
    }

    private class ParseTask(
        private val urlBean: Movie.Video.UrlBean?,
        private val urlMap: MutableMap<Int, String>,
        private val callback: ThunderCallback,
        private val gen: Int
    ) : Runnable {

        override fun run() {
            val infoList = urlBean?.infoList ?: return
            if (Thunder.parseGeneration.get() != gen) return
            val playList = requireNotNull(Thunder.playList) { "thunder play list missing" }
            val start = System.currentTimeMillis()
            val deadline = start + Thunder.PARSE_BUDGET_MS
            var parseStatus = -1
            var lastTaskId = 0L
            for (idx in 0 until infoList.size) {
                if (Thunder.parseGeneration.get() != gen) break
                val urlInfo: Movie.Video.UrlBean.UrlInfo? = infoList[idx]
                if (urlInfo != null) {
                    var url = ""
                    for (infoBean in urlInfo.beanList.orEmpty()) {
                        var isParse = false
                        url = infoBean.url.orEmpty()
                        if (Thunder.isMagnet(url) || Thunder.isThunder(url) || Thunder.isTorrent(url)) {
                            if (System.currentTimeMillis() < deadline) {
                                if (Thunder.isThunder(url)) url = XLDownloadManager.getInstance().parserThunderUrl(url)
                                val link = if (Thunder.isThunder(url)) XLDownloadManager.getInstance().parserThunderUrl(url) else url
                                val p = Uri.parse(link)
                                if (p == null) {
                                    continue
                                }
                                val fileName = XLTaskHelper.instance().getFileName(link)
                                val cache = File(Thunder.cacheRoot + File.separator + fileName)
                                var taskId = 0L
                                try {
                                    if (lastTaskId > 0) {
                                        XLTaskHelper.instance().stopTask(lastTaskId)
                                        if (Thunder.parseTaskId == lastTaskId) Thunder.parseTaskId = 0L
                                        lastTaskId = 0L
                                    }
                                    taskId = if (Thunder.isMagnet(url))
                                        XLTaskHelper.instance().addMagentTask(url, Thunder.cacheRoot, fileName)
                                    else
                                        XLTaskHelper.instance().addThunderTask(url, Thunder.cacheRoot, fileName)
                                } catch (exception: Exception) {
                                    AppLog.e("Thunder", exception)
                                    taskId = 0L
                                }
                                if (taskId <= 0) {
                                    continue
                                }
                                lastTaskId = taskId
                                Thunder.parseTaskId = taskId
                                var count = (Thunder.PARSE_BUDGET_MS / 100).toInt()
                                outerLoop@ while (true) {
                                    count--
                                    if (count <= 0 || System.currentTimeMillis() >= deadline
                                        || Thunder.parseGeneration.get() != gen) {
                                        break
                                    }
                                    val taskInfo = XLTaskHelper.instance().getTaskInfo(taskId)
                                    if (taskInfo != null) {
                                        parseStatus = taskInfo.mTaskStatus
                                        when (taskInfo.mTaskStatus) {
                                            2 -> {
                                                try {
                                                    val torrentInfo = XLTaskHelper.instance().getTorrentInfo(cache.absolutePath)
                                                    if (torrentInfo == null || TextUtils.isEmpty(torrentInfo.mInfoHash)) {

                                                    } else {
                                                        val subFiles = torrentInfo.mSubFileInfo
                                                        if (subFiles != null) {
                                                            for (sub in subFiles) {
                                                                if (Thunder.isMedia(sub.mFileName) && sub.mFileSize > 1048576L * 30) {
                                                                    sub.torrentPath = cache.absolutePath
                                                                    val torrents = Thunder.torrentFileInfoArrayList
                                                                    if (torrents != null) {
                                                                        playList.add(sub.mFileName + "\$tvbox-torrent:" + torrents.size)
                                                                        torrents.add(sub)
                                                                    }
                                                                }
                                                            }
                                                            isParse = true
                                                            break@outerLoop
                                                        }
                                                    }
                                                } catch (throwable: Throwable) {
                                                    AppLog.e("Thunder", throwable)
                                                }
                                                break@outerLoop
                                            }
                                            3 -> {
                                                break@outerLoop
                                            }
                                            else -> {
                                            }
                                        }
                                    }
                                    try {
                                        Thread.sleep(100)
                                    } catch (e: InterruptedException) {
                                        Thread.currentThread().interrupt()
                                        break@outerLoop
                                    }
                                }
                            }
                        } else {
                            url = infoBean.url.orEmpty()
                            if (Thunder.isThunder(url)) url = XLDownloadManager.getInstance().parserThunderUrl(url)
                            if (Thunder.isNetworkDownloadTask(url)) {
                                Thunder.task_url = url
                                if (TextUtils.isEmpty(Thunder.task_url)) {
                                    continue
                                }
                                Thunder.name = XLTaskHelper.instance().getFileName(Thunder.task_url)
                                playList.add(Thunder.name + "\$tvbox-oth:" + (Thunder.ed2kList?.size ?: 0))
                                Thunder.ed2kList?.add(Thunder.task_url)
                                isParse = true
                            }
                        }
                        if (!isParse) playList.add(infoBean.name + "\$" + infoBean.url)
                    }
                    if (playList.isNotEmpty()) {
                        urlMap.put(idx, TextUtils.join("#", playList))
                        playList.clear()
                    }
                }
            }

            AppLog.i("echo-thunder-parse cost=" + (System.currentTimeMillis() - start) + "ms items=" + infoList.size + " torrents=" + (torrentFileInfoArrayList?.size ?: 0) + " status=" + parseStatus)

            if (lastTaskId > 0) {
                XLTaskHelper.instance().stopTask(lastTaskId)
                if (Thunder.parseTaskId == lastTaskId) Thunder.parseTaskId = 0L
            }
            if (Thunder.parseGeneration.get() != gen) return
            if (urlMap.size > 0) {
                callback.list(urlMap)
            } else {
                callback.status(-1, Thunder.str(R.string.thunder_error_parse))
            }
        }
    }

    @JvmStatic
    fun play(url: String, callback: ThunderCallback): Boolean {
        if (isMagnet(url)) {
            if (cacheRoot.isEmpty()) {
                init(requireNotNull(AppContextHolder.context()) { "app context not initialized" })
            }
            val gen = playGeneration.incrementAndGet()
            cancelMark.incrementAndGet()
            stopCurrentTask()
            val pendingParse = parseTaskId
            if (pendingParse > 0) {
                XLTaskHelper.instance().stopTask(pendingParse)
                parseTaskId = 0L
            }
            AppLog.i("echo-thunder-play-start gen=" + gen)
            playPool?.shutdownNow()
            val pool = Executors.newSingleThreadExecutor()
            playPool = pool
            pool.execute(MagnetPlayTask(url, callback, gen, cancelMark.get()))
            return true
        }
        if (url.startsWith("tvbox-torrent:")) {
            val idx = Integer.parseInt(url.substring(14))
            val info = torrentFileInfoArrayList?.getOrNull(idx)
            if (info == null) {
                callback.status(-1, str(R.string.thunder_error_parse))
                return true
            }
            stopCurrentTask()
            threadPool?.execute(object : Runnable {
                override fun run() {
                    val torrentName = File(info.torrentPath).name
                    val cache = cacheRoot + File.separator + torrentName.substring(0, torrentName.lastIndexOf("."))
                    val taskId = XLTaskHelper.instance().addTorrentTask(info.torrentPath, cache, info.mFileIndex)
                    if (taskId < 0) {
                        callback.status(-1, str(R.string.thunder_error_download))
                        return
                    }
                    currentTask = taskId
                    var count = 30
                    while (true) {
                        count--
                        if (count <= 0) {
                            XLTaskHelper.instance().stopTask(taskId)
                            currentTask = 0L
                            callback.status(-1, str(R.string.thunder_error_timeout))
                            break
                        }
                        val taskInfo = XLTaskHelper.instance().getBtSubTaskInfo(taskId, info.mFileIndex).mTaskInfo
                        when (taskInfo.mTaskStatus) {
                            3 -> {
                                XLTaskHelper.instance().stopTask(taskId)
                                currentTask = 0L
                                callback.status(-1, errorInfo(taskInfo.mErrorCode))
                                return
                            }
                            1, 4, 2 -> {
                                val pUrl = XLTaskHelper.instance().getLoclUrl(cache + File.separator + info.mFileName)
                                if (pUrl.isNullOrEmpty()) {
                                    XLTaskHelper.instance().stopTask(taskId)
                                    currentTask = 0L
                                    callback.status(-1, str(R.string.thunder_error_download))
                                    return
                                }
                                streamTaskId = taskId
                                currentTask = 0L
                                callback.play(pUrl)
                                return
                            }
                        }
                        try {
                            Thread.sleep(1000)
                        } catch (e: InterruptedException) {
                            AppLog.e("Thunder", e)
                        }
                    }
                }
            })
            return true
        }
        if (url.startsWith("tvbox-oth:")) {
            stop(false)
            val idx = Integer.parseInt(url.substring(10))
            val targetUrl = ed2kList?.getOrNull(idx)
            if (targetUrl == null) {
                callback.status(-1, str(R.string.thunder_error_parse))
                return true
            }
            task_url = targetUrl
            name = XLTaskHelper.instance().getFileName(task_url)
            localPath = (File(cacheRoot + File.separator + "temp", FileUtils.fileNameWithoutExt(name))).toString() + "/"
            currentTask = XLTaskHelper.instance().addThunderTask(task_url, localPath, null)
            if (threadPool == null) threadPool = Executors.newSingleThreadExecutor()

            threadPool?.execute(object : Runnable {
                override fun run() {

                    var count = 20
                    while (true) {
                        count--
                        if (count <= 0) {
                            callback.status(-1, str(R.string.thunder_error_timeout))
                            break
                        }
                        val playUrl = getPlayUrl()
                        if (playUrl != null && !playUrl.isEmpty()) {
                            streamTaskId = currentTask
                            currentTask = 0L
                            callback.play(playUrl)
                            return
                        }
                        try {
                            Thread.sleep(1000)
                        } catch (e: InterruptedException) {
                            AppLog.e("Thunder", e)
                        }
                    }
                }
            })
            return true
        }
        if (isEd2k(url) || isFtp(url)) {
            if (threadPool == null) {
                init(requireNotNull(AppContextHolder.context()) { "app context not initialized" })
                threadPool = Executors.newSingleThreadExecutor()
            }
            if (currentTask > 0) {
                XLTaskHelper.instance().stopTask(currentTask)
                currentTask = 0L
            }
            task_url = url
            name = XLTaskHelper.instance().getFileName(task_url)
            localPath = (File(cacheRoot + File.separator + "temp", FileUtils.fileNameWithoutExt(name))).toString() + "/"
            currentTask = XLTaskHelper.instance().addThunderTask(task_url, localPath, null)

            threadPool?.execute(object : Runnable {
                override fun run() {

                    var count = 20
                    while (true) {
                        count--
                        if (count <= 0) {
                            callback.status(-1, str(R.string.thunder_error_timeout))
                            break
                        }
                        val playUrl = getPlayUrl()
                        if (!playUrl.isNullOrEmpty()) {
                            streamTaskId = currentTask
                            currentTask = 0L
                            callback.play(playUrl)
                            return
                        }
                        try {
                            Thread.sleep(1000)
                        } catch (e: InterruptedException) {
                            AppLog.e("Thunder", e)
                        }
                    }
                }
            })
            return true
        }
        return false
    }

    @JvmStatic
    fun isMagnetUrl(url: String?): Boolean = url != null && isMagnet(url)

    private class MagnetPlayTask(
        private val url: String,
        private val callback: ThunderCallback,
        private val gen: Int,
        private val mark: Int
    ) : Runnable {

        private fun staleExit(task: Long): Boolean {
            if (Thunder.playGeneration.get() == gen) return false
            if (task > 0) XLTaskHelper.instance().stopTask(task)
            clearCurrent(task)
            if (Thunder.cancelMark.get() == mark) {
                callback.status(-1, Thunder.str(R.string.thunder_parse_timeout))
            }
            return true
        }

        private fun clearCurrent(task: Long) {
            if (task > 0 && Thunder.currentTask == task) Thunder.currentTask = 0L
        }

        override fun run() {
            if (staleExit(0L)) return
            val fileName = try {
                XLTaskHelper.instance().getFileName(url)
            } catch (throwable: Throwable) {
                AppLog.e("Thunder", throwable)
                ""
            }
            if (fileName.isEmpty()) {
                AppLog.i("echo-thunder-play-fail reason=file-name")
                callback.status(-1, Thunder.str(R.string.thunder_error_parse))
                return
            }
            val torrentFile = File(Thunder.cacheRoot + File.separator + fileName)
            var taskId = try {
                XLTaskHelper.instance().addMagentTask(url, Thunder.cacheRoot, fileName)
            } catch (exception: Exception) {
                AppLog.e("Thunder", exception)
                -1L
            }
            if (taskId <= 0) {
                try {
                    Thread.sleep(300)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                taskId = try {
                    XLTaskHelper.instance().addMagentTask(url, Thunder.cacheRoot, fileName)
                } catch (exception: Exception) {
                    AppLog.e("Thunder", exception)
                    -1L
                }
            }
            if (staleExit(taskId)) return
            if (taskId <= 0) {
                AppLog.i("echo-thunder-play-fail reason=add-task")
                callback.status(-1, Thunder.str(R.string.thunder_error_parse))
                return
            }
            AppLog.i("echo-thunder-play-task id=" + taskId)
            Thunder.currentTask = taskId
            val metaDeadline = System.currentTimeMillis() + Thunder.PLAY_META_BUDGET_MS
            var media: TorrentFileInfo? = null
            var metadataReady = false
            var metaPollErrorLogged = false
            while (System.currentTimeMillis() < metaDeadline) {
                if (staleExit(taskId)) return
                var finished = false
                try {
                    val taskInfo = XLTaskHelper.instance().getTaskInfo(taskId)
                    if (taskInfo != null) {
                        if (taskInfo.mTaskStatus == 3) {
                            XLTaskHelper.instance().stopTask(taskId)
                            clearCurrent(taskId)
                            callback.status(-1, Thunder.str(R.string.thunder_error_parse))
                            return
                        }
                        if (taskInfo.mTaskStatus == 2) {
                            media = XLTaskHelper.instance().getTorrentInfo(torrentFile.absolutePath)
                                ?.mSubFileInfo
                                ?.filter { Thunder.isMedia(it.mFileName) && it.mFileSize > 1048576L * 30 }
                                ?.maxByOrNull { it.mFileSize }
                            metadataReady = true
                            finished = true
                        }
                    }
                } catch (throwable: Throwable) {
                    if (!metaPollErrorLogged) {
                        metaPollErrorLogged = true
                        AppLog.e("Thunder", throwable)
                    }
                }
                if (finished) break
                try {
                    Thread.sleep(Thunder.PLAY_PARSE_POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    if (Thunder.currentTask == taskId) XLTaskHelper.instance().stopTask(taskId)
                    return
                }
            }
            if (staleExit(taskId)) return
            if (!metadataReady) {
                XLTaskHelper.instance().stopTask(taskId)
                clearCurrent(taskId)
                AppLog.i("echo-thunder-play-fail reason=meta-timeout")
                callback.status(-1, Thunder.str(R.string.thunder_parse_timeout))
                return
            }
            val target = media
            if (target == null) {
                XLTaskHelper.instance().stopTask(taskId)
                clearCurrent(taskId)
                AppLog.i("echo-thunder-play-fail reason=no-media")
                callback.status(-1, Thunder.str(R.string.thunder_error_parse))
                return
            }
            AppLog.i("echo-thunder-play-meta file=" + target.mFileName + " size=" + target.mFileSize)
            val saveDir = Thunder.cacheRoot + File.separator + "play_" + target.mFileIndex
            val subTaskId = try {
                XLTaskHelper.instance().addTorrentTask(torrentFile.absolutePath, saveDir, target.mFileIndex)
            } catch (throwable: Throwable) {
                AppLog.e("Thunder", throwable)
                -1L
            }
            if (subTaskId <= 0) {
                XLTaskHelper.instance().stopTask(taskId)
                clearCurrent(taskId)
                callback.status(-1, Thunder.str(R.string.thunder_error_download))
                return
            }
            XLTaskHelper.instance().stopTask(taskId)
            Thunder.currentTask = subTaskId
            val playDeadline = System.currentTimeMillis() + Thunder.PLAY_STAGE_BUDGET_MS
            var playPollErrorLogged = false
            while (System.currentTimeMillis() < playDeadline) {
                if (staleExit(subTaskId)) return
                try {
                    val subInfo = XLTaskHelper.instance().getBtSubTaskInfo(subTaskId, target.mFileIndex).mTaskInfo
                    when (subInfo.mTaskStatus) {
                        3 -> {
                            XLTaskHelper.instance().stopTask(subTaskId)
                            clearCurrent(subTaskId)
                            callback.status(-1, Thunder.str(R.string.thunder_error_download))
                            return
                        }
                        1, 4, 2 -> {
                            val localUrl = XLTaskHelper.instance().getLoclUrl(saveDir + File.separator + target.mFileName)
                            if (localUrl.isNullOrEmpty()) {
                                XLTaskHelper.instance().stopTask(subTaskId)
                                clearCurrent(subTaskId)
                                callback.status(-1, Thunder.str(R.string.thunder_error_download))
                                return
                            }
                            Thunder.streamTaskId = subTaskId
                            clearCurrent(subTaskId)
                            AppLog.i("echo-thunder-play-url " + localUrl)
                            callback.play(localUrl)
                            return
                        }
                    }
                } catch (throwable: Throwable) {
                    if (!playPollErrorLogged) {
                        playPollErrorLogged = true
                        AppLog.e("Thunder", throwable)
                    }
                }
                try {
                    Thread.sleep(Thunder.PLAY_PARSE_POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    if (Thunder.currentTask == subTaskId) XLTaskHelper.instance().stopTask(subTaskId)
                    return
                }
            }
            if (!staleExit(subTaskId)) {
                XLTaskHelper.instance().stopTask(subTaskId)
                clearCurrent(subTaskId)
                AppLog.i("echo-thunder-play-fail reason=play-timeout")
                callback.status(-1, Thunder.str(R.string.thunder_parse_timeout))
            }
        }
    }

    private fun errorInfo(code: Int): String {
        return when (code) {
            9125 ->
                str(R.string.thunder_error_name_too_long)
            111120 ->
                str(R.string.thunder_error_path_too_long)
            111142 ->
                str(R.string.thunder_error_file_too_small)
            111085 ->
                str(R.string.thunder_error_no_space)
            111171 ->
                str(R.string.thunder_error_network_refused)
            9301 ->
                str(R.string.thunder_error_buffer)
            114001, 114004, 114005, 114006, 114007, 114011, 9304, 111154 ->
                str(R.string.thunder_error_copyright)
            114101 ->
                str(R.string.thunder_error_invalid_url)
            else ->
                "ErrorCode=" + code
        }
    }

    @JvmStatic
    fun isSupportUrl(url: String?): Boolean {
        val u = url ?: return false
        return isMagnet(u) || isThunder(u) || isTorrent(u)
    }

    private fun isMagnet(url: String): Boolean {
        return url.lowercase().startsWith("magnet:")
    }

    private fun isThunder(url: String): Boolean {
        return url.lowercase().startsWith("thunder")
    }

    private fun isTorrent(url: String): Boolean {
        return RegexUtils.getPattern(";").split(url.lowercase())[0].endsWith(".torrent")
    }

    private fun isEd2k(url: String): Boolean {
        return url.lowercase().startsWith("ed2k:")
    }

    @JvmStatic
    fun isFtp(url: String): Boolean {
        return url.lowercase().startsWith("ftp://")
    }

    private fun recursiveDelete(file: File) {
        if (!file.exists())
            return
        if (file.isDirectory) {
            for (f in file.listFiles()) {
                recursiveDelete(f)
            }
        }
        file.delete()
    }

    private val formats: ArrayList<String> = ArrayList()

    private fun isMedia(name: String): Boolean {
        if (formats.size == 0) {
            formats.add(".rmvb")
            formats.add(".avi")
            formats.add(".mkv")
            formats.add(".flv")
            formats.add(".mp4")
            formats.add(".rm")
            formats.add(".vob")
            formats.add(".wmv")
            formats.add(".mov")
            formats.add(".3gp")
            formats.add(".asf")
            formats.add("mpg")
            formats.add("mpeg")
            formats.add("mpe")
        }
        for (f in formats) {
            if (name.lowercase().endsWith(f))
                return true

        }
        return false
    }

    private fun randomImei(): String {
        return randomString("0123456", 15)
    }

    private fun randomMac(): String {
        return randomString("ABCDEF0123456", 12).uppercase()
    }

    private fun randomString(base: String, length: Int): String {
        val random = Random()
        val sb = StringBuffer()
        for (i in 0 until length) {
            val number = random.nextInt(base.length)
            sb.append(base[number])
        }
        return sb.toString()
    }

    @JvmStatic
    fun isNetworkDownloadTask(url: String): Boolean {
        if (TextUtils.isEmpty(url)) return false
        if (isFtp(url) || isEd2k(url)) {
            return true
        } else {
            return false
        }
    }

    @JvmStatic
    fun stopTask() {
        if (currentTask != 0L) {
            XLTaskHelper.instance().deleteTask(currentTask, if (task_url.isEmpty()) cacheRoot else localPath)
            currentTask = 0L
        }
    }
    @JvmStatic
    fun getTaskInfo(): XLTaskInfo {
        return XLTaskHelper.instance().getTaskInfo(currentTask)
    }

    @JvmStatic
    fun getPlayUrl(): String? {
        if (currentTask != 0L) {
            if (isNetworkDownloadTask(task_url)) {
                return XLTaskHelper.instance().getLoclUrl(localPath + name)
            }
        }
        return null
    }

}
