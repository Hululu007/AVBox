package com.github.avbox.osc.sourcedata

import android.text.TextUtils
import androidx.lifecycle.ViewModel
import com.github.catvod.crawler.SpiderReaper
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.bean.AbsSortXml
import com.github.avbox.osc.bean.AbsXml
import com.github.avbox.osc.bean.MovieSort
import com.github.avbox.osc.util.AppLog
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class SourceViewModel : ViewModel() {

    @JvmField
    val sortResult = SourceChannel<AbsSortXml?>()

    @JvmField
    val listResult = SourceChannel<AbsXml?>()

    @JvmField
    val searchResult = SourceChannel<AbsXml?>()

    @JvmField
    val detailResult = SourceChannel<AbsXml?>()

    @JvmField
    val actionResult = SourceChannel<JSONObject?>()

    @JvmField
    val playResult = SourceChannel<JSONObject?>()

    @JvmField
    val preloadResult = SourceChannel<JSONObject?>()

    private val gson = Gson()
    private val pushDetailResolver = PushDetailResolver(gson, detailResult)
    private val resultParser = SourceResultParser(gson, searchResult, detailResult, pushDetailResolver)
    private val listLoader = ListLoader(gson, SourceRuntimeState.extendCache, listResult, resultParser)
    private val sortLoader = SortLoader(gson, SourceRuntimeState.extendCache, SourceRuntimeState.sortCache, sortResult, listLoader, resultParser)
    private val detailLoader = DetailLoader(gson, SourceRuntimeState.extendCache, detailResult, resultParser)
    private val searchLoader = SearchLoader(gson, SourceRuntimeState.extendCache, searchResult, resultParser)
    private val playLoader = PlayLoader(gson, SourceRuntimeState.extendCache, playResult, preloadResult)

    private val rootJob = SupervisorJob()

    private val requestScope: CoroutineScope by lazy { CoroutineScope(rootJob + Dispatchers.Main.immediate) }

    @Volatile
    private var detailChain = SupervisorJob(rootJob)

    @Volatile
    private var searchChain = SupervisorJob(rootJob)

    fun getSort(sourceKey: String?) {
        getSort(sourceKey, true)
    }

    fun getSort(sourceKey: String?, withRec: Boolean) {
        requestScope.launch { sortLoader.getSort(sourceKey, withRec) }
    }

    fun getList(sourceKey: String?, sortData: MovieSort.SortData?, page: Int) {
        requestScope.launch { listLoader.getList(sourceKey, sortData, page) }
    }

    fun getDetail(sourceKey: String?, urlid: String) {
        getDetail(sourceKey, urlid, false)
    }

    fun getDetail(sourceKey: String?, urlid: String, fallback: Boolean) {
        getDetail(sourceKey, urlid, fallback, null)
    }

    fun getDetail(sourceKey: String?, urlid: String, fallback: Boolean, requestToken: Int?) {
        requestScope.launch(detailChain) { detailLoader.getDetail(sourceKey, urlid, fallback, requestToken) }
    }

    fun cancelDetail() {
        detailChain.cancel()
        detailChain = SupervisorJob(rootJob)
    }

    fun action(sourceKey: String?, action: String?) {
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (sourceBean == null || action == null) {
            actionResult.postValue(null)
            return
        }
        if (sourceBean.type == 3) {
            SourceHelper.SPIDER_POOL.execute {
                try {
                    val sp = ApiConfig.get().getCSP(sourceBean)
                    val json = SpiderReaper.track(sp) { sp.action(action) }
                    actionResult.postValue(if (TextUtils.isEmpty(json)) null else JSONObject(json))
                } catch (th: Throwable) {
                    AppLog.e("SourceViewModel", th)
                    actionResult.postValue(null)
                }
            }
        } else {
            actionResult.postValue(null)
        }
    }

    fun getSearch(sourceKey: String?, wd: String?) {
        getSearch(sourceKey, wd, "")
    }

    fun getSearch(sourceKey: String?, wd: String?, searchToken: String?) {
        requestScope.launch(searchChain) { searchLoader.getSearch(sourceKey, wd, searchToken) }
    }

    fun cancelSearch() {
        searchChain.cancel()
        searchChain = SupervisorJob(rootJob)
    }

    fun getPlay(sourceKey: String?, playFlag: String?, progressKey: String?, url: String?, subtitleKey: String?) {
        playLoader.getPlay(sourceKey, playFlag, progressKey, url, subtitleKey)
    }

    fun getPlayForPreload(sourceKey: String?, playFlag: String?, progressKey: String?, url: String?, subtitleKey: String?) {
        playLoader.getPlayForPreload(sourceKey, playFlag, progressKey, url, subtitleKey)
    }

    fun cancelPlayRequest() {
        playLoader.cancelPlayRequest()
    }

    fun checkThunder(data: AbsXml, index: Int) {
        pushDetailResolver.checkThunder(data, index)
    }

    override fun onCleared() {
        super.onCleared()
    }
}
