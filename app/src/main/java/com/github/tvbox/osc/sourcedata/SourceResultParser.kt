package com.github.tvbox.osc.sourcedata

import android.text.TextUtils

import com.github.tvbox.osc.bean.AbsJson
import com.github.tvbox.osc.bean.AbsSortJson
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.util.LOG
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.thoughtworks.xstream.XStream
import com.thoughtworks.xstream.io.xml.DomDriver
import kotlinx.coroutines.CancellationException


import java.util.ArrayList
import java.util.LinkedHashMap

class SourceResultParser(
    private val gson: Gson,
    private val searchResult: SourceChannel<AbsXml?>,
    private val detailResult: SourceChannel<AbsXml?>,
    private val pushDetailResolver: PushDetailResolver,
) {

    private fun getSortFilter(obj: JsonObject): MovieSort.SortFilter {
        val key = obj.get("key").asString
        val name = obj.get("name").asString
        val kv = obj.getAsJsonArray("value")
        val values = LinkedHashMap<String, String>()
        for (ele in kv) {
            val ele_obj = ele.asJsonObject
            val values_value = if (ele_obj.has("v")) ele_obj.get("v").asString else ""
            val values_name = if (ele_obj.has("n")) ele_obj.get("n").asString else ""
            values[values_value] = values_name
        }
        val filter = MovieSort.SortFilter()
        filter.key = key
        filter.name = name
        filter.values = values
        return filter
    }

    fun sortJson(result: SourceChannel<AbsSortXml?>?, json: String?): AbsSortXml? {
        try {
            if (TextUtils.isEmpty(json)) {
                return AbsSortJson().toAbsSortXml()
            }
            val obj = JsonParser.parseString(requireNotNull(json) { "source json missing" }).asJsonObject
            val sortJson = gson.fromJson<AbsSortJson>(obj, object : TypeToken<AbsSortJson>() {}.type)
            val data = sortJson.toAbsSortXml()
            try {
                if (obj.has("filters")) {
                    val sortFilters = LinkedHashMap<String?, ArrayList<MovieSort.SortFilter>>()
                    val filters = obj.getAsJsonObject("filters")
                    for (key in filters.keySet()) {
                        val sortFilter = ArrayList<MovieSort.SortFilter>()
                        val one = filters.get(key)
                        if (one.isJsonObject) {
                            sortFilter.add(getSortFilter(one.asJsonObject))
                        } else {
                            for (ele in one.asJsonArray) {
                                sortFilter.add(getSortFilter(ele.asJsonObject))
                            }
                        }
                        sortFilters[key] = sortFilter
                    }
                    val sortList = data.classes?.sortList
                    if (sortList != null) {
                        for (sort in sortList) {
                            val filters = sortFilters[sort.id]
                            if (filters != null) {
                                sort.filters = filters
                            }
                        }
                    }
                }
            } catch (th: Throwable) {
                LOG.d("SourceViewModel", "sort filters parse failed, continue without filters")
            }
            return data
        } catch (e: Exception) {
            val head = if (json == null) "null" else json.substring(0, Math.min(200, json.length))
            LOG.i("echo--parse-fail-sortJson: ex=$e head=$head")
            return null
        }
    }

    fun sortXml(result: SourceChannel<AbsSortXml?>?, xml: String?): AbsSortXml? {
        try {
            val xstream = requireNotNull(sortXStream.get()) { "sort xstream missing" }
            val data = xstream.fromXML(xml) as AbsSortXml
            for (sort in data.classes?.sortList.orEmpty()) {
                if (sort.filters == null) {
                    sort.filters = ArrayList()
                }
            }
            return data
        } catch (e: Exception) {
            val head = if (xml == null) "null" else xml.substring(0, Math.min(200, xml.length))
            LOG.i("echo--parse-fail-sortXml: ex=$e head=$head")
            return null
        }
    }

    suspend fun xml(result: SourceChannel<AbsXml?>?, xml: String?, sourceKey: String?): AbsXml? {
        return xml(result, xml, sourceKey, "")
    }

    suspend fun xml(result: SourceChannel<AbsXml?>?, xml: String?, sourceKey: String?, searchToken: String?): AbsXml? {
        return xml(result, xml, sourceKey, searchToken, null)
    }

    suspend fun xml(result: SourceChannel<AbsXml?>?, xml: String?, sourceKey: String?, searchToken: String?, detailToken: Int?): AbsXml? {
        var text: String? = xml
        try {
            val xstream = requireNotNull(listXStream.get()) { "list xstream missing" }
            val original = requireNotNull(text) { "list xml missing" }
            if (original.contains("<year></year>")) {
                text = original.replace("<year></year>", "<year>0</year>")
            }
            val withYear = text
            if (withYear.contains("<state></state>")) {
                text = withYear.replace("<state></state>", "<state>0</state>")
            }
            var data = xstream.fromXML(text) as AbsXml
            SourceHelper.absXml(data, sourceKey, searchToken)
            data.detailToken = detailToken
            if (searchResult === result) {
                searchResult.postValue(data)
            } else if (result != null) {
                if (result === detailResult) {
                    data = pushDetailResolver.checkPush(data)
                    pushDetailResolver.checkThunder(data, 0)
                } else {
                    postSearchResult(result, data)
                }
            }
            return data
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (result != null) {
                val head = if (text == null) "null" else text.substring(0, Math.min(200, text.length))
                LOG.i("echo--parse-fail-xml:$sourceKey ex=$e head=$head")
            }
            if (searchResult === result) {
                postEmptySearchResult(result, sourceKey, searchToken)
            } else if (result != null) {
                if (result === detailResult) {
                    result.postValue(createEmptyDetail(sourceKey, detailToken))
                } else {
                    result.postValue(null)
                }
            }
            return null
        }
    }

    suspend fun json(result: SourceChannel<AbsXml?>?, json: String?, sourceKey: String?): AbsXml? {
        return json(result, json, sourceKey, "")
    }

    suspend fun json(result: SourceChannel<AbsXml?>?, json: String?, sourceKey: String?, searchToken: String?): AbsXml? {
        return json(result, json, sourceKey, searchToken, null)
    }

    suspend fun json(result: SourceChannel<AbsXml?>?, json: String?, sourceKey: String?, searchToken: String?, detailToken: Int?): AbsXml? {
        try {
            if (json == null || json.trim { it <= ' ' }.isEmpty()) {
                if (result != null) {
                    LOG.i("echo--parse-empty-body:$sourceKey (站点返回空响应;JSON 型源(ac=detail)拿不到内容时常见,或该源实为 XML 类型)")
                }
                if (searchResult === result) {
                    postEmptySearchResult(result, sourceKey, searchToken)
                } else if (result === detailResult) {
                    result.postValue(createEmptyDetail(sourceKey, detailToken))
                } else if (result != null) {
                    result.postValue(null)
                }
                return null
            }
            val absJson = gson.fromJson<AbsJson>(json, object : TypeToken<AbsJson>() {}.type)
            if (absJson == null) {
                throw IllegalStateException("json 非空但解析不出对象: $json")
            }
            var data = absJson.toAbsXml()
            SourceHelper.absXml(data, sourceKey, searchToken)
            data.detailToken = detailToken
            if (searchResult === result) {
                searchResult.postValue(data)
            } else if (result != null) {
                if (result === detailResult) {
                    data = pushDetailResolver.checkPush(data)
                    pushDetailResolver.checkThunder(data, 0)
                } else {
                    postSearchResult(result, data)
                }
            }
            return data
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (result != null) {
                val head = if (json == null) "null" else json.substring(0, Math.min(200, json.length))
                LOG.i("echo--parse-fail-json:$sourceKey ex=$e head=$head")
            }
            if (searchResult === result) {
                postEmptySearchResult(result, sourceKey, searchToken)
            } else if (result != null) {
                if (result === detailResult) {
                    result.postValue(createEmptyDetail(sourceKey, detailToken))
                } else {
                    result.postValue(null)
                }
            }
            return null
        }
    }

    fun postEmptySearchResult(result: SourceChannel<AbsXml?>?, sourceKey: String?, searchToken: String?) {
        val data = AbsXml()
        data.sourceKey = sourceKey
        data.searchToken = searchToken
        if (searchResult === result) {
            searchResult.postValue(data)
        } else if (result != null) {
            postSearchResult(result, data)
        }
    }

    private fun createEmptyDetail(sourceKey: String?, detailToken: Int?): AbsXml {
        val data = AbsXml()
        data.sourceKey = sourceKey
        data.detailToken = detailToken
        return data
    }

    private fun postSearchResult(result: SourceChannel<AbsXml?>, data: AbsXml) {
        result.postValue(data)
    }

    companion object {

        private val sortXStream: ThreadLocal<XStream> = object : ThreadLocal<XStream>() {
            override fun initialValue(): XStream {
                val xstream = XStream(DomDriver())
                xstream.autodetectAnnotations(true)
                xstream.processAnnotations(AbsSortXml::class.java)
                xstream.ignoreUnknownElements()
                return xstream
            }
        }

        private val listXStream: ThreadLocal<XStream> = object : ThreadLocal<XStream>() {
            override fun initialValue(): XStream {
                val xstream = XStream(DomDriver())
                xstream.autodetectAnnotations(true)
                xstream.processAnnotations(AbsXml::class.java)
                xstream.ignoreUnknownElements()
                return xstream
            }
        }
    }
}
