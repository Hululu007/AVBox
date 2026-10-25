package com.github.avbox.osc.sourcedata

import android.text.TextUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import com.github.avbox.osc.bean.SourceSubtitle
import com.github.avbox.osc.bean.SubtitleData
import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.net.Http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.jsoup.Jsoup

import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class SubtitleViewModel : ViewModel() {

    fun interface SubtitleLoader {
        fun loadSubtitle(subtitle: SourceSubtitle)
    }

    val searchResult = SourceChannel<SubtitleData?>()

    fun searchResult(title: String?, page: Int) {
        viewModelScope.launch { searchResultFromAssrt(title, page) }
    }

    fun searchResultSubtitleUrls(subtitle: SourceSubtitle) {
        viewModelScope.launch {
            val (files, error) = fetchSubtitleFiles(subtitle)
            setSearchListData(files, true, error)
        }
    }

    fun getSubtitleUrl(subtitle: SourceSubtitle, subtitleLoader: SubtitleLoader) {
        subtitleUrlFromAssrt(subtitle, subtitleLoader, null)
    }

    fun pickEpisodeSubtitle(releaseUrl: String?, episodeName: String?, fileNameHint: String?,
                            onPicked: SubtitleLoader?, onFailed: Runnable?) {
        if (TextUtils.isEmpty(releaseUrl) || onPicked == null) {
            if (onFailed != null) onFailed.run()
            return
        }
        viewModelScope.launch {
            val release = SourceSubtitle()
            release.url = releaseUrl
            val (files, error) = fetchSubtitleFiles(release)
            if (error || files == null || files.isEmpty()) {
                if (onFailed != null) onFailed.run()
                return@launch
            }
            val names = ArrayList<String>()
            for (item in files) names.add(item.name ?: "")
            val index = SubtitleFilePicker.pick(names, episodeName, fileNameHint)
            if (index < 0) {
                if (onFailed != null) onFailed.run()
                return@launch
            }
            subtitleUrlFromAssrt(files[index], onPicked, onFailed)
        }
    }

    private fun setSearchListData(data: List<SourceSubtitle>?, isNew: Boolean, isZip: Boolean) {
        try {
            val subtitleData = SubtitleData()
            subtitleData.subtitleList = data
            subtitleData.isNew = isNew
            subtitleData.isZip = isZip
            searchResult.postValue(subtitleData)
        } catch (e: Throwable) {
            AppLog.e("SubtitleViewModel", e)
            searchResult.postValue(null)
        }
    }

    private var pagesTotal = -1

    private suspend fun searchResultFromAssrt(title: String?, page: Int) {
        try {
            if (pagesTotal > 0 && page > pagesTotal) {
                setSearchListData(ArrayList(), page <= 1, true)
                return
            }
            if (page == 1) pagesTotal = -1
            val searchApiUrl = "https://secure.assrt.net/sub/"
            val content = Http.get(searchApiUrl) {
                params("searchword", title)
                params("sort", "rank")
                params("page", page.toString())
                params("no_redir", "1")
            }
            val parsedPagesTotal = withContext(Dispatchers.IO) {
                try {
                    val doc = Jsoup.parse(content)
                    val items = doc.select(".resultcard .sublist_box_title a.introtitle")
                    val data = ArrayList<SourceSubtitle>()
                    for (item in items) {
                        val subtitleTitle = item.attr("title")
                        val href = item.attr("href")
                        if (TextUtils.isEmpty(href) || !containsSearchWord(subtitleTitle, title)) continue
                        val one = SourceSubtitle()
                        one.name = subtitleTitle
                        one.url = "https://assrt.net" + href
                        one.isZip = true
                        data.add(one)
                    }
                    setSearchListData(data, page <= 1, true)
                    val pages = doc.select(".pagelinkcard a")
                    if (pages.size > 0) {
                        val ps = pages.last()?.text()?.split("/", limit = 2).orEmpty()
                        if (ps.size == 2 && !TextUtils.isEmpty(ps[1])) {
                            ps[1].trim { it <= ' ' }.toInt()
                        } else -1
                    } else -1
                } catch (th: Throwable) {
                    AppLog.e("SubtitleViewModel", th)
                    -1
                }
            }
            if (parsedPagesTotal > 0) pagesTotal = parsedPagesTotal
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("SubtitleViewModel", e)
            setSearchListData(null, page <= 1, true)
        }
    }

    private val regexShooterFileOnclick = Pattern.compile("onthefly\\(\"(\\d+)\",\"(\\d+)\",\"([\\s\\S]*)\"\\)")

    private suspend fun fetchSubtitleFiles(subtitle: SourceSubtitle): Pair<List<SourceSubtitle>?, Boolean> {
        val url = subtitle.url ?: return null to true
        return try {
            val content = Http.get(url)
            withContext(Dispatchers.IO) {
                try {
                    val data = ArrayList<SourceSubtitle>()
                    val doc = Jsoup.parse(content)
                    val items = doc.select("#detail-filelist .waves-effect")
                    if (items.size > 0) {
                        for (item in items) {
                            val onclick = item.attr("onclick")
                            if (TextUtils.isEmpty(onclick)) continue
                            val matcher = regexShooterFileOnclick.matcher(onclick)
                            if (matcher.find()) {
                                val fileName = matcher.group(3)
                                if (!isSupportedSubtitleFile(fileName)) continue
                                val downloadUrl = String.format("https://secure.assrt.net/download/%s/-/%s/%s", matcher.group(1), matcher.group(2), matcher.group(3))
                                val one = SourceSubtitle()
                                val name = item.selectFirst("#filelist-name")
                                one.name = if (name == null) fileName else name.text()
                                one.url = downloadUrl
                                one.isZip = false
                                data.add(one)
                            }
                        }
                        data to false
                    } else {
                        val item = doc.selectFirst(".download a#btn_download")
                        if (item == null) {
                            null to false
                        } else {
                            val href = item.attr("href")
                            if (TextUtils.isEmpty(href) || !isSupportedSubtitleFile(href)) {
                                null to false
                            } else {
                                val downloadUrl = "https://assrt.net" + href
                                val one = SourceSubtitle()
                                val title = href.substring(href.lastIndexOf("/") + 1)
                                one.name = URLDecoder.decode(title)
                                one.url = downloadUrl
                                one.isZip = false
                                data.add(one)
                                data to false
                            }
                        }
                    }
                } catch (th: Throwable) {
                    AppLog.e("SubtitleViewModel", th)
                    null to true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("SubtitleViewModel", e)
            null to true
        }
    }

    private fun containsSearchWord(subtitleTitle: String?, searchWord: String?): Boolean {
        if (subtitleTitle.isNullOrEmpty() || searchWord.isNullOrEmpty()) return false
        return subtitleTitle.lowercase(Locale.ROOT).contains(searchWord.lowercase(Locale.ROOT))
    }

    private fun isSupportedSubtitleFile(fileName: String?): Boolean {
        if (fileName.isNullOrEmpty()) return false
        val lower = fileName.lowercase(Locale.ROOT)
        return lower.endsWith(".srt") ||
            lower.endsWith(".ass") ||
            lower.endsWith(".stl") ||
            lower.endsWith(".ttml")
    }

    private fun subtitleUrlFromAssrt(subtitle: SourceSubtitle, subtitleLoader: SubtitleLoader, onFailed: Runnable?) {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.54 Safari/537.36"
        val request = Request.Builder()
            .url(requireNotNull(subtitle.url) { "subtitle url missing" })
            .get()
            .addHeader("Referer", "https://secure.assrt.net")
            .addHeader("User-Agent", ua)
            .build()
        val base = HttpClients.defaultClient
        val builder = if (base != null) base.newBuilder() else OkHttpClient.Builder().proxySelector(HttpClients.proxySelector()).proxyAuthenticator(HttpClients.proxyAuthenticator())
        builder.readTimeout(15, TimeUnit.SECONDS)
        builder.writeTimeout(15, TimeUnit.SECONDS)
        builder.connectTimeout(15, TimeUnit.SECONDS)
        builder.followRedirects(false)
        builder.followSslRedirects(false)
        builder.retryOnConnectionFailure(true)
        val client = builder.build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppLog.e("SubtitleViewModel", e)
                if (onFailed != null) onFailed.run()
            }

            override fun onResponse(call: Call, response: Response) {
                val location = response.header("location")
                if (TextUtils.isEmpty(location)) {
                    if (onFailed != null) onFailed.run()
                    return
                }
                subtitle.url = location
                subtitleLoader.loadSubtitle(subtitle)
            }
        })
    }
}
