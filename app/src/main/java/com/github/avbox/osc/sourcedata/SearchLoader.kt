package com.github.avbox.osc.sourcedata

import android.text.TextUtils
import com.github.catvod.crawler.SpiderReaper
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.bean.AbsXml
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.util.AppLog
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class SearchLoader(
    private val gson: Gson,
    private val extendCache: ConcurrentHashMap<String, String>,
    private val searchResult: SourceChannel<AbsXml?>,
    private val resultParser: SourceResultParser,
) {

    suspend fun getSearch(sourceKey: String?, wd: String?) {
        getSearch(sourceKey, wd, "")
    }

    suspend fun getSearch(sourceKey: String?, wd: String?, searchToken: String?) {
        getSearch(sourceKey, wd, searchToken, searchResult)
    }

    private suspend fun getSearch(sourceKey: String?, wd: String?, searchToken: String?, result: SourceChannel<AbsXml?>) {
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (sourceBean == null) {
            resultParser.postEmptySearchResult(result, sourceKey, searchToken)
            return
        }
        val type = sourceBean.type
        if (type == 3) {
            searchFromSpider(sourceBean, wd, result, searchToken)
        } else if (type == 0 || type == 1) {
            searchFromApi(sourceBean, wd, result, searchToken)
        } else if (type == 4) {
            searchFromExtendedApi(sourceBean, wd, result, searchToken)
        } else {
            resultParser.postEmptySearchResult(result, sourceBean.key, searchToken)
        }
    }

    private suspend fun searchFromSpider(sourceBean: SourceBean, wd: String?, result: SourceChannel<AbsXml?>, searchToken: String?) {
        try {
            val search = withContext(Dispatchers.IO) {
                val spider = ApiConfig.get().getCSP(sourceBean)
                SpiderReaper.track(spider) { spider.searchContent(wd, false) }
            }
            withContext(Dispatchers.IO) {
                if (!TextUtils.isEmpty(search)) {
                    resultParser.json(result, search, sourceBean.key, searchToken)
                } else {
                    resultParser.json(result, "", sourceBean.key, searchToken)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (th: Throwable) {
            AppLog.e("SourceViewModel", th)
            withContext(Dispatchers.IO) { resultParser.json(result, "", sourceBean.key, searchToken) }
        }
    }

    private suspend fun searchFromApi(sourceBean: SourceBean, wd: String?, result: SourceChannel<AbsXml?>, searchToken: String?) {
        val type = sourceBean.type

        try {
            val body = SourceHelper.siteGet(sourceBean) {
                params("wd", wd)
                if (type == 1) {
                    params("ac", "detail")
                }
            }
            withContext(Dispatchers.IO) {
                if (type == 0) {
                    resultParser.xml(result, body, sourceBean.key, searchToken)
                } else {
                    resultParser.json(result, body, sourceBean.key, searchToken)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            resultParser.postEmptySearchResult(result, sourceBean.key, searchToken)
        }
    }

    private suspend fun searchFromExtendedApi(sourceBean: SourceBean, wd: String?, result: SourceChannel<AbsXml?>, searchToken: String?) {

        val (extend, queryWd) = withContext(Dispatchers.IO) {
            var queryWd = wd
            try {
                queryWd = URLEncoder.encode(queryWd, "UTF-8")
            } catch (e: UnsupportedEncodingException) {
                AppLog.e("SourceViewModel", e)
            }
            SourceHelper.fixUrlDirect(extendCache, gson, sourceBean.ext) to queryWd
        }

        try {
            val body = SourceHelper.siteGet(sourceBean) {
                params("wd", queryWd)
                params("ac", "detail")
                params("quick", "false")
                if (extend != null && extend.isNotEmpty()) {
                    params("extend", extend)
                }
            }
            withContext(Dispatchers.IO) { resultParser.json(result, body, sourceBean.key, searchToken) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.i("echo-t4 search-onError")
            resultParser.postEmptySearchResult(result, sourceBean.key, searchToken)
        }
    }
}
