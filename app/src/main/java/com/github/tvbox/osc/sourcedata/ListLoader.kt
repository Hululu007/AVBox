package com.github.tvbox.osc.sourcedata

import android.text.TextUtils
import android.util.Base64
import com.github.catvod.crawler.SpiderReaper
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.BoundedCall
import com.github.tvbox.osc.util.LOG
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import java.util.ArrayList
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

class ListLoader(
    private val gson: Gson,
    private val extendCache: ConcurrentHashMap<String, String>,
    private val listResult: SourceChannel<AbsXml?>,
    private val resultParser: SourceResultParser,
) {

    suspend fun getList(sourceKey: String?, sortData: MovieSort.SortData?, page: Int) {
        if (sortData == null) {
            LOG.i("echo-getList-sortData-null")
            listResult.postValue(null)
            return
        }
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (sourceBean == null) {
            LOG.i("echo--getList-source-missing:$sourceKey sort=${sortData.id} pg=$page")
            listResult.postValue(null)
            return
        }
        val type = sourceBean.type
        if (type == 3) {
            getListFromSpider(sourceBean, sortData, page)
        } else if (type == 0 || type == 1) {
            getListFromApi(sourceBean, sortData, page)
        } else if (type == 4) {
            getListFromExtendedApi(sourceBean, sortData, page)
        } else {
            listResult.postValue(null)
        }
    }

    private suspend fun getListFromSpider(sourceBean: SourceBean, sortData: MovieSort.SortData, page: Int) {
        val json = withContext(Dispatchers.IO) {
            BoundedCall.call(Callable<String> {
                val sp = ApiConfig.get().getCSP(sourceBean)
                SpiderReaper.track(sp) { sp.categoryContent(sortData.id, page.toString(), true, sortData.filterSelect) }
            }, sourceBean.getPlayTimeoutSeconds() * 1000L, "echo--getList--" + sourceBean.key)
        }
        if (json != null) {
            withContext(Dispatchers.IO) { resultParser.json(listResult, json, sourceBean.key) }
        } else {
            LOG.i("echo--list-spider-null:" + sourceBean.key + " sort=" + sortData.id + " pg=" + page)
            listResult.postValue(null)
        }
    }

    private suspend fun getListFromApi(sourceBean: SourceBean, sortData: MovieSort.SortData, page: Int) {
        val type = sourceBean.type

        try {
            val body = SourceHelper.siteGet(sourceBean) {
                params("ac", if (type == 0) "videolist" else "detail")
                params("t", sortData.id)
                params("pg", page.toString())
                params(sortData.filterSelect)
                params(
                    "f",
                    if (sortData.filterSelect.isEmpty()) ""
                    else JSONObject(sortData.filterSelect).toString()
                )
            }
            withContext(Dispatchers.IO) {
                if (type == 0) {
                    resultParser.xml(listResult, body, sourceBean.key)
                } else {
                    resultParser.json(listResult, body, sourceBean.key)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.i(
                "echo--list-api-error:" + sourceBean.key + " t=" + sortData.id + " pg=" + page
                    + " ex=" + e
            )
            listResult.postValue(null)
        }
    }

    private suspend fun getListFromExtendedApi(sourceBean: SourceBean, sortData: MovieSort.SortData, page: Int) {

        var ext = ""
        val extend = withContext(Dispatchers.IO) {
            SourceHelper.getFixUrl(extendCache, gson, sourceBean.ext, sourceBean.getPlayTimeoutSeconds().toLong())
        }
        if (sortData.filterSelect.size > 0) {
            try {
                val selectExt = JSONObject(sortData.filterSelect).toString()
                ext = Base64.encodeToString(selectExt.toByteArray(Charsets.UTF_8), Base64.DEFAULT or Base64.NO_WRAP)
            } catch (e: UnsupportedEncodingException) {
                LOG.e("SourceViewModel", e)
            }
        } else {
            ext = Base64.encodeToString("{}".toByteArray(Charset.defaultCharset()), Base64.DEFAULT or Base64.NO_WRAP)
        }

        try {
            val body = SourceHelper.siteGet(sourceBean) {
                params("ac", "detail")
                params("filter", "true")
                params("t", sortData.id)
                params("pg", page.toString())
                params("ext", ext)
                if (extend != null && extend.isNotEmpty()) {
                    params("extend", extend)
                }
            }
            withContext(Dispatchers.IO) { resultParser.json(listResult, body, sourceBean.key) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.i(
                "echo--list-ext-error:" + sourceBean.key + " t=" + sortData.id + " pg=" + page
                    + " ex=" + e
            )
            listResult.postValue(null)
        }
    }

    suspend fun getHomeRecList(sourceBean: SourceBean, ids: ArrayList<String?>?): MutableList<Movie.Video>? {
        val type = sourceBean.type
        if (type == 3) {
            val sortJson = withContext(Dispatchers.IO) {
                BoundedCall.call(Callable<String> {
                    val sp = ApiConfig.get().getCSP(sourceBean)
                    SpiderReaper.track(sp) { sp.homeVideoContent() }
                }, sourceBean.getPlayTimeoutSeconds() * 1000L, "echo--getHomeRecList--" + sourceBean.key)
            }
            if (sortJson == null) return null
            return withContext(Dispatchers.IO) { resultParser.json(null, sortJson, sourceBean.key)?.movie?.videoList }
        }
        if (type == 0 || type == 1) {
            try {
                val body = SourceHelper.siteGet(sourceBean) {
                    params("ac", if (sourceBean.type == 0) "videolist" else "detail")
                    params("ids", TextUtils.join(",", ids.orEmpty()))
                }
                return withContext(Dispatchers.IO) {
                    val absXml = if (sourceBean.type == 0) {
                        resultParser.xml(null, body, sourceBean.key)
                    } else {
                        resultParser.json(null, body, sourceBean.key)
                    }
                    absXml?.movie?.videoList
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }
}
