package com.github.avbox.osc.api

import android.net.Uri
import android.text.TextUtils
import android.util.Base64

import com.github.catvod.crawler.Spider
import com.github.avbox.osc.R
import com.github.avbox.osc.base.App
import com.github.avbox.osc.bean.LiveChannelGroup
import com.github.avbox.osc.bean.LiveChannelItem
import com.github.avbox.osc.bean.LiveSettingGroup
import com.github.avbox.osc.bean.LiveSettingItem
import com.github.avbox.osc.bean.ParseBean
import com.github.avbox.osc.bean.ProxyRule
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.io.FileUtils
import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.net.SearchHelper
import com.github.avbox.osc.util.AES
import com.github.avbox.osc.util.AdBlocker
import com.github.avbox.osc.util.DefaultConfig
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.HeaderGuard
import com.github.avbox.osc.util.HistoryHelper
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.LanguageManager
import com.github.avbox.osc.util.LocalAddress
import com.github.avbox.osc.util.RegexUtils
import com.github.avbox.osc.util.VideoParseRuler
import com.github.avbox.osc.util.live.TxtSubscribe
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

import org.json.JSONObject

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.Locale

class ApiConfig private constructor() {
    @Volatile
    private var sourceBeanList: LinkedHashMap<String?, SourceBean>
    @Volatile
    private var homeSource: SourceBean? = null
    @Volatile
    internal var defaultParseBean: ParseBean? = null
    @Volatile
    private var liveChannelGroupList: MutableList<LiveChannelGroup>
    @Volatile
    var parseBeanList: MutableList<ParseBean>
    @Volatile
    internal var vipParseFlags: MutableList<String>? = null
    @Volatile
    private var vodHosts: MutableMap<String, String>? = null
    @Volatile
    private var liveHosts: MutableMap<String, String>? = null
    @JvmField
    var loadedLiveConfigUrl: String = ""
    private var danmaku: String? = ""
    @Volatile
    private var configLogo: String? = ""

    fun getConfigLogo(): String {
        return configLogo ?: ""
    }

    private val emptyHome = SourceBean()

    private val spiderLoader = SpiderLoader()

    private val proxyEntry = ProxyEntry(this, spiderLoader)

    private val warmQueue = WarmQueue(this, spiderLoader)

    private val configLoader = ConfigLoader(this)
    private val gson: Gson
    @Volatile
    private var searchSourceBeanList: MutableList<SourceBean> = ArrayList()

    @Volatile
    private var liveSettingGroups: MutableList<LiveSettingGroup> = ArrayList()

    val liveSettingGroupList: List<LiveSettingGroup>
        get() = java.util.Collections.unmodifiableList(liveSettingGroups)

    init {
        clearLoader()
        sourceBeanList = LinkedHashMap()
        liveChannelGroupList = ArrayList()
        parseBeanList = ArrayList()
        searchSourceBeanList = ArrayList()
        gson = Gson()
        KV.put(KVKeys.LIVE_GROUP_LIST, JsonArray())
        loadDefaultConfig()
        HttpClients.hostsProvider = { myHost() }
        SearchHelper.liveSourcesProvider = { getSourceBeanList() }
    }

    suspend fun loadConfig(useCache: Boolean): String? {
        return configLoader.loadConfig(useCache)
    }

    suspend fun loadLiveConfig(useCache: Boolean): String? {
        return configLoader.loadLiveConfig(useCache)
    }

    fun refreshConfigIfChanged(onChanged: (String, String) -> Unit) {
        configLoader.refreshIfChanged(onChanged)
    }

    fun refreshLiveConfigIfChanged() {
        configLoader.refreshLiveIfChanged()
    }

    fun applyRefreshedConfig(apiUrl: String, body: String) {
        configLoader.applyRefreshed(apiUrl, body)
    }

    fun restoreConfigCache(apiUrl: String, body: String) {
        configLoader.restoreCache(apiUrl, body)
    }

    fun hasLiveConfigResult(): Boolean {
        return !liveChannelGroupList.isEmpty()
    }

    fun invalidateLiveConfig() {
        liveChannelGroupList = ArrayList()
        loadedLiveConfigUrl = ""
    }

    suspend fun loadJar(useCache: Boolean, spider: String?): String? {
        return spiderLoader.loadJar(useCache, requireNotNull(spider) { "spider url missing" })
    }

    fun clearLiveConfigResult() {
        liveChannelGroupList = ArrayList()
        spiderLoader.liveSpider = ""
        spiderLoader.resetCurrentLiveSpider()
        initLiveSettings()
        KV.put(KVKeys.LIVE_GROUP_LIST, JsonArray())
    }

    private fun resetConfigData() {
        warmQueue.bumpGeneration()
        clearSpiderCache()
        proxyEntry.setCurrentPlaySourceKey("")
        configLogo = ""
        sourceBeanList = LinkedHashMap()
        liveChannelGroupList = ArrayList()
        parseBeanList = ArrayList()
        searchSourceBeanList = ArrayList()
        KV.put(KVKeys.LIVE_GROUP_LIST, JsonArray())
        vodHosts = null
        if (isLiveFollowVod()) liveHosts = null
        HttpClients.refreshHosts()
    }

    fun clearConfig() {
        clearVodConfig()
        clearLiveConfig()
    }

    fun cancelPendingLoads() {
        configLoader.cancelVodFetches()
        spiderLoader.invalidateJarLoads()
    }

    fun clearVodConfig() {
        val followLive = isLiveFollowVod()
        cancelPendingLoads()
        resetConfigData()
        homeSource = null
        KV.put(KVKeys.API_URL, "")
        KV.put(KVKeys.HOME_API, "")
        HistoryHelper.clearApiLineList()
        if (followLive) {
            KV.put(KVKeys.LIVE_API_URL, "")
            HistoryHelper.clearLiveApiLineList()
        }
        invalidateLiveConfig()
    }

    fun clearLiveConfig() {
        KV.put(KVKeys.LIVE_API_URL, "")
        HistoryHelper.clearLiveApiLineList()
        clearLiveHosts()
        invalidateLiveConfig()
    }

    fun clearLiveHosts() {
        liveHosts = null
        HttpClients.refreshHosts()
    }

    fun invalidateVodConfig() {
        resetConfigData()
        homeSource = null
        invalidateLiveConfig()
    }

    private fun clearApiLinesIfUnmatched(apiUrl: String) {
        val apiLines: ArrayList<String> = KV.get(KVKeys.API_LINE_LIST, ArrayList<String>())
        if (apiLines.isEmpty()) {
            return
        }
        for (apiLine in apiLines) {
            if (apiUrl == HistoryHelper.apiLineUrl(apiLine)) {
                return
            }
        }
        HistoryHelper.clearApiLineList()
    }

    fun parseJson(apiUrl: String, jsonStr: String) {
        if (apiUrl != KV.get(KVKeys.API_URL, "")) {
            AppLog.debugI("echo-switch: drop stale config")
            return
        }
        resetConfigData()
        VideoParseRuler.clearRule()
        AppLog.i("echo-apiurl:" + apiUrl)
        val infoJson = gson.fromJson(jsonStr, JsonObject::class.java)
        configLogo = DefaultConfig.safeJsonString(infoJson, "logo", "")
        spiderLoader.spider = DefaultConfig.safeJsonString(infoJson, "spider", "")
        spiderLoader.jarCache = DefaultConfig.safeJsonString(infoJson, "jarCache", "true")
        danmaku = DefaultConfig.safeJsonString(infoJson, "danmaku", "")
        val sites = ConfigParser.parseSites(infoJson)
        val siteMap = LinkedHashMap<String?, SourceBean>()
        for (sb in sites) {
            siteMap[sb.key] = sb
        }
        sourceBeanList = siteMap
        val firstSite = firstVisibleSite(sites)
        if (sourceBeanList.size > 0) {
            val home = KV.get(KVKeys.HOME_API, "")
            val sh = getSource(home)
            if (sh == null) {
                assert(firstSite != null)
                setSourceBean(requireNotNull(firstSite) { "first site missing" })
            } else {
                setSourceBean(sh)
            }
        }
        vipParseFlags = DefaultConfig.safeJsonStringList(infoJson, "flags")
        val parses = ArrayList<ParseBean>()
        val parsedParses = ConfigApplier.parseParseBeans(infoJson)
        if (!parsedParses.isEmpty()) {
            parses.addAll(parsedParses)
            addSuperParse(parses)
        }
        parseBeanList = parses
        if (parses.size > 0) {
            val defaultParse = KV.get(KVKeys.DEFAULT_PARSE, "")
            if (!TextUtils.isEmpty(defaultParse)) {
                for (pb in parses) {
                    if (pb.name == defaultParse) {
                        setDefaultParse(pb)
                    }
                }
            }
            if (defaultParseBean == null) {
                setDefaultParse(parses[0])
            }
        }

        val live_api_url = KV.get(KVKeys.LIVE_API_URL, "")
        if (live_api_url.isEmpty() || apiUrl == live_api_url) {
            AppLog.i("echo-load-config_live")
            initLiveSettings()
            if (infoJson.has("lives")) {
                val lives_groups = infoJson.get("lives").asJsonArray
                var live_group_index = liveGroupIndex
                if (live_group_index > lives_groups.size() - 1) live_group_index = 0
                KV.put(KVKeys.LIVE_GROUP_LIST, lives_groups)
                try {
                    liveSettingGroups[5].liveSettingItems = ConfigParser.parseLiveSettingItems(lives_groups)
                } catch (e: Exception) {
                    AppLog.e("ApiConfig", e)
                }

                val livesOBJ = lives_groups.get(live_group_index).asJsonObject
                loadLiveApi(livesOBJ)
            }
        }

        vodHosts = if (infoJson.has("hosts")) ConfigParser.parseHosts(infoJson.getAsJsonArray("hosts")) else null
        HttpClients.refreshHosts()

        loadProxyRules(infoJson)

        ConfigApplier.applyHostRules(infoJson)

        ConfigApplier.applyDoh(infoJson)
        AppLog.i("echo-api-config-----------load")
        ConfigApplier.applyAds(infoJson)
    }

    private fun loadDefaultConfig() {
        val defaultJson = gson.fromJson(FileUtils.asOpen("default_config.json"), JsonObject::class.java)
        if (defaultJson == null) {
            AppLog.e("ApiConfig: default_config.json unavailable")
            return
        }
        if (AdBlocker.isEmpty()) {
            for (host in defaultJson.getAsJsonArray("ads")) {
                AdBlocker.addAdHost(host.asString)
            }
        }
        AppLog.i("echo-default-config-----------load")
    }

    private fun parseLiveConfigContent(apiUrl: String, f: File) {
        val content = BufferedReader(InputStreamReader(FileInputStream(f), "UTF-8")).use { bReader ->
            val sb = StringBuilder()
            var s: String? = bReader.readLine()
            while (s != null) {
                sb.append(s + "\n")
                s = bReader.readLine()
            }
            sb.toString()
        }
        parseLiveConfigContent(apiUrl, content)
    }

    fun parseLiveConfigContent(apiUrl: String, content: String) {
        val jsonContent = ConfigParser.trimJsonObject(content)
        if (!TextUtils.isEmpty(jsonContent)) {
            try {
                val infoJson = gson.fromJson(jsonContent, JsonObject::class.java)
                if (infoJson != null && infoJson.has("lives")) {
                    parseLiveJson(apiUrl, jsonContent)
                    return
                }
            } catch (ignored: Throwable) {
                AppLog.d("ApiConfig", "live config json parse failed, fallback to text")
            }
        }
        if (ConfigParser.isLiveJsonContent(content)) {
            parseLiveJson(apiUrl, jsonContent)
        } else {
            parseLiveText(apiUrl, content)
        }
    }

    private fun parseLiveText(apiUrl: String, content: String) {
        liveChannelGroupList = ArrayList()
        spiderLoader.liveSpider = ""
        spiderLoader.resetCurrentLiveSpider()
        initLiveSettings()
        KV.put(KVKeys.LIVE_GROUP_LIST, JsonArray())
        KV.put(KVKeys.EPG_URL, ConfigParser.extractLiveTextEpg(content))
        KV.put(KVKeys.LIVE_WEB_HEADER, null)
        liveHosts = null
        HttpClients.refreshHosts()
        val livesArray = TxtSubscribe.parseToJsonArray(content)
        loadLives(livesArray)
        AppLog.i("echo-live-text-config-----------load:" + apiUrl)
    }

    private fun parseLiveJson(apiUrl: String, jsonStr: String) {
        liveChannelGroupList = ArrayList()
        val infoJson = gson.fromJson(jsonStr, JsonObject::class.java)
        spiderLoader.liveSpider = DefaultConfig.safeJsonString(infoJson, "spider", "")
        initLiveSettings()
        if (infoJson.has("lives")) {
            val lives_groups = infoJson.get("lives").asJsonArray

            var live_group_index = liveGroupIndex
            if (live_group_index > lives_groups.size() - 1) live_group_index = 0
            KV.put(KVKeys.LIVE_GROUP_LIST, lives_groups)
            try {
                liveSettingGroups[5].liveSettingItems = ConfigParser.parseLiveSettingItems(lives_groups)
            } catch (e: Exception) {
                AppLog.e("ApiConfig", e)
            }

            val livesOBJ = lives_groups.get(live_group_index).asJsonObject
            loadLiveApi(livesOBJ)
        }

        liveHosts = if (infoJson.has("hosts")) ConfigParser.parseHosts(infoJson.getAsJsonArray("hosts")) else null
        HttpClients.refreshHosts()
        AppLog.i("echo-api-live-config-----------load")
    }

    private fun initLiveSettings() {
        val groupNames = ArrayList(
            listOf(
                str(R.string.live_group_line), str(R.string.live_group_scale), str(R.string.live_group_decoder),
                str(R.string.live_group_timeout), str(R.string.settings_preference_title),
                str(R.string.live_group_multi_source), str(R.string.live_group_config_switch)
            )
        )
        val itemsArrayList = ArrayList<ArrayList<String>>()
        val sourceItems = ArrayList<String>()
        val scaleItems = ArrayList(
            listOf(
                str(R.string.common_default), "16:9", "4:3",
                str(R.string.player_scale_fill), str(R.string.player_scale_origin), str(R.string.player_scale_crop)
            )
        )
        val playerDecoderItems = ArrayList(
            listOf(str(R.string.player_decode_hard), str(R.string.player_decode_soft))
        )
        val timeoutItems = ArrayList(listOf("5s", "10s", "15s", "20s", "25s", "30s"))
        val personalSettingItems = ArrayList(
            listOf(
                str(R.string.live_setting_show_time), str(R.string.live_setting_show_speed),
                str(R.string.live_setting_reverse), str(R.string.live_setting_cross_group)
            )
        )
        val yumItems = ArrayList<String>()
        val liveApiHistoryItems = ArrayList<String>()

        itemsArrayList.add(sourceItems)
        itemsArrayList.add(scaleItems)
        itemsArrayList.add(playerDecoderItems)
        itemsArrayList.add(timeoutItems)
        itemsArrayList.add(personalSettingItems)
        itemsArrayList.add(yumItems)
        itemsArrayList.add(liveApiHistoryItems)

        val groups = ArrayList<LiveSettingGroup>()
        for (i in groupNames.indices) {
            val liveSettingGroup = LiveSettingGroup()
            val liveSettingItemList = ArrayList<LiveSettingItem>()
            liveSettingGroup.groupIndex = i
            liveSettingGroup.groupName = groupNames[i]
            for (j in itemsArrayList[i].indices) {
                val liveSettingItem = LiveSettingItem()
                liveSettingItem.itemIndex = j
                liveSettingItem.itemName = itemsArrayList[i][j]
                liveSettingItemList.add(liveSettingItem)
            }
            liveSettingGroup.liveSettingItems = liveSettingItemList
            groups.add(liveSettingGroup)
        }
        liveSettingGroups = groups
        refreshLiveApiHistoryItems()
    }

    fun refreshLiveApiHistoryItems() {
        if (liveSettingGroups.size < 7) return
        val liveSettingItemList = ArrayList<LiveSettingItem>()
        val followItem = LiveSettingItem()
        followItem.itemIndex = 0
        followItem.itemName = str(R.string.live_follow_vod_source)
        liveSettingItemList.add(followItem)
        val entries = liveConfigEntries()
        for (i in entries.indices) {
            val liveSettingItem = LiveSettingItem()
            liveSettingItem.itemIndex = i + 1
            liveSettingItem.itemName = HistoryHelper.apiLineName(entries[i])
            liveSettingItemList.add(liveSettingItem)
        }
        liveSettingGroups[6].liveSettingItems = liveSettingItemList
    }

    fun isLiveApiLineMode(): Boolean {
        return HistoryHelper.isLiveApiLineUrl(KV.get(KVKeys.LIVE_API_URL, ""))
    }

    fun liveConfigEntries(): ArrayList<String> {
        return if (HistoryHelper.isLiveApiLineUrl(KV.get(KVKeys.LIVE_API_URL, ""))) {
            HistoryHelper.liveApiLines()
        } else {
            KV.get(KVKeys.LIVE_API_HISTORY, ArrayList())
        }
    }

    fun liveConfigUrls(): ArrayList<String> {
        val urls = ArrayList<String>()
        for (entry in liveConfigEntries()) {
            val url = HistoryHelper.apiLineUrl(entry)
            if (!TextUtils.isEmpty(url)) urls.add(url)
        }
        return urls
    }

    fun liveApiHistoryUrl(position: Int): String {
        val urls = liveConfigUrls()
        val index = position - 1
        if (index < 0 || index >= urls.size) return ""
        return urls[index]
    }

    fun loadLives(livesArray: JsonArray) {
        val groups = ArrayList<LiveChannelGroup>()
        var groupIndex = 0
        var channelIndex: Int
        var channelNum = 0
        for (groupElement in livesArray) {
            val liveChannelGroup = LiveChannelGroup()
            liveChannelGroup.liveChannels = ArrayList<LiveChannelItem>()
            liveChannelGroup.groupIndex = groupIndex++
            val groupJson = groupElement as JsonObject
            val groupName = groupJson.get("group").asString.trim { it <= ' ' }
            val splitGroupName = RegexUtils.getPattern("_").split(groupName, 2)
            liveChannelGroup.groupName = splitGroupName[0]
            if (splitGroupName.size > 1) {
                liveChannelGroup.groupPassword = splitGroupName[1]
            } else {
                liveChannelGroup.groupPassword = ""
            }
            channelIndex = 0
            for (channelElement in groupJson.get("channels").asJsonArray) {
                val obj = channelElement as JsonObject
                val urls = DefaultConfig.safeJsonStringList(obj, "urls")
                if (urls.isEmpty()) {
                    AppLog.i("echo-skip live channel without url: " + obj)
                    continue
                }
                val liveChannelItem = LiveChannelItem()
                liveChannelItem.channelLogo = DefaultConfig.safeJsonString(obj, "logo", "")
                liveChannelItem.channelEpg = DefaultConfig.safeJsonString(obj, "epg", "")
                liveChannelItem.channelUa = DefaultConfig.safeJsonString(obj, "ua", "")
                liveChannelItem.channelClick = DefaultConfig.safeJsonString(obj, "click", "")
                liveChannelItem.channelFormat = DefaultConfig.safeJsonString(obj, "format", "")
                liveChannelItem.channelOrigin = DefaultConfig.safeJsonString(obj, "origin", "")
                liveChannelItem.channelReferer = DefaultConfig.safeJsonString(obj, "referer", "")
                liveChannelItem.channelTvgId = DefaultConfig.safeJsonString(obj, "tvg-id", "")
                liveChannelItem.channelTvgName = DefaultConfig.safeJsonString(obj, "tvg-name", "")
                if (obj.has("parse")) {
                    try {
                        liveChannelItem.channelParse = obj.get("parse").asInt
                    } catch (ignored: Throwable) {
                        AppLog.d("ApiConfig", "channel parse flag not an int, use default")
                    }
                }
                val catchupObj = ConfigParser.parseLiveCatchup(obj)
                if (catchupObj != null) liveChannelItem.channelCatchup = catchupObj
                if (obj.has("header") && obj.get("header").isJsonObject) {
                    val headerObj = obj.getAsJsonObject("header")
                    val channelHeader = HashMap<String, String>()
                    for (entry in headerObj.entrySet()) {
                        if (entry.value == null || !entry.value.isJsonPrimitive) continue
                        val value = entry.value.asString
                        if (!HeaderGuard.isSendable(entry.key, value)) {
                            AppLog.i("echo-channel-header-skip:" + entry.key)
                            continue
                        }
                        channelHeader[entry.key] = value
                    }
                    liveChannelItem.channelHeader = channelHeader
                }
                val sourceNames = ArrayList<String>()
                val sourceUrls = ArrayList<String>()
                var sourceIndex = 1
                for (url in urls) {
                    val splitText = RegexUtils.getPattern("\\$").split(url, 2)
                    sourceUrls.add(splitText[0])
                    if (splitText.size > 1) {
                        sourceNames.add(splitText[1])
                    } else {
                        sourceNames.add(str(R.string.live_source_index_name, sourceIndex))
                    }
                    sourceIndex++
                }
                val channelName = ConfigParser.parseLiveChannelName(obj, sourceUrls)
                if (channelName.isEmpty()) {
                    AppLog.i("echo-skip live channel without name/url: " + obj)
                    continue
                }
                liveChannelItem.channelName = channelName
                liveChannelItem.channelSourceNames = sourceNames
                liveChannelItem.channelUrls = sourceUrls
                val channels = requireNotNull(liveChannelGroup.liveChannels) { "live channel group channels missing" }
                if (mergeLiveChannel(channels, liveChannelItem)) {
                    liveChannelItem.channelIndex = channelIndex++
                    liveChannelItem.channelNum = ++channelNum
                }
            }
            groups.add(liveChannelGroup)
        }
        liveChannelGroupList = groups
    }

    private fun mergeLiveChannel(channelItems: ArrayList<LiveChannelItem>, newItem: LiveChannelItem): Boolean {
        val oldItem = findLiveChannel(channelItems, newItem.channelName)
        if (oldItem == null) {
            channelItems.add(newItem)
            return true
        }
        mergeLiveChannelUrls(oldItem, newItem)
        return false
    }

    private fun findLiveChannel(channelItems: ArrayList<LiveChannelItem>, channelName: String?): LiveChannelItem? {
        for (item in channelItems) {
            if (channelName != null && channelName == item.channelName) return item
        }
        return null
    }

    private fun mergeLiveChannelUrls(oldItem: LiveChannelItem, newItem: LiveChannelItem) {
        var oldUrls = oldItem.channelUrls
        var oldSourceNames = oldItem.channelSourceNames
        if (oldUrls == null) {
            oldUrls = ArrayList()
            oldItem.channelUrls = oldUrls
        }
        if (oldSourceNames == null) {
            oldSourceNames = ArrayList()
            oldItem.channelSourceNames = oldSourceNames
        }
        while (oldSourceNames.size < oldUrls.size) {
            oldSourceNames.add(str(R.string.live_source_index_name, oldSourceNames.size + 1))
        }
        val newUrls = newItem.channelUrls
        val newSourceNames = newItem.channelSourceNames
        if (newUrls == null) return
        for (i in newUrls.indices) {
            val url = newUrls[i]
            if (oldUrls.contains(url)) continue
            oldUrls.add(url)
            if (newSourceNames != null && i < newSourceNames.size) {
                oldSourceNames.add(newSourceNames[i])
            } else {
                oldSourceNames.add(str(R.string.live_source_index_name, oldSourceNames.size + 1))
            }
        }
        oldItem.channelUrls = oldUrls
        oldItem.channelSourceNames = oldSourceNames
    }

    fun loadLiveApi(livesOBJ: JsonObject) {
        try {
            AppLog.i("echo-loadLiveApi")
            liveChannelGroupList = ArrayList()
            spiderLoader.resetCurrentLiveSpider()
            val lives = livesOBJ.toString()
            val index = lives.indexOf("proxy://")
            val url: String
            if (index != -1) {
                val endIndex = lives.lastIndexOf("\"")
                var fixUrl = lives.substring(index, endIndex)
                fixUrl = DefaultConfig.checkReplaceProxy(fixUrl)
                val extUrl = Uri.parse(fixUrl).getQueryParameter("ext")
                if (extUrl != null && !extUrl.isEmpty()) {
                    var extUrlFix: String
                    if (extUrl.startsWith("http") || extUrl.startsWith("clan://")) {
                        extUrlFix = extUrl
                    } else {
                        extUrlFix = String(Base64.decode(extUrl, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
                    }
                    extUrlFix = Base64.encodeToString(extUrlFix.toByteArray(Charsets.UTF_8), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP)
                    fixUrl = fixUrl.replace(extUrl, extUrlFix)
                }
                url = fixUrl
            } else {
                val api = if (livesOBJ.has("api")) livesOBJ.get("api").asString.trim { it <= ' ' } else ""
                val type = if (livesOBJ.has("type")) livesOBJ.get("type").asString else (if (SpiderLoader.isLiveSpiderApi(api)) "3" else "0")
                if (type == "0" || type == "3") {
                    var fixUrl = if (livesOBJ.has("url")) livesOBJ.get("url").asString else ""
                    if (fixUrl.isEmpty()) fixUrl = api
                    AppLog.i("echo-liveurl" + fixUrl)
                    if (!fixUrl.startsWith("http://127.0.0.1")) {
                        if (fixUrl.startsWith("http")) {
                            fixUrl = Base64.encodeToString(fixUrl.toByteArray(Charsets.UTF_8), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP)
                        }
                        fixUrl = "http://127.0.0.1:9978/proxy?do=live&type=txt&ext=" + fixUrl
                    }
                    if (type == "3") {
                        val jarUrl = if (livesOBJ.has("jar")) livesOBJ.get("jar").asString.trim { it <= ' ' } else ""
                        spiderLoader.loadLiveSpider(api, jarUrl, livesOBJ)
                    }
                    url = fixUrl
                } else {
                    AppLog.i("echo-live-unsupported-type:" + type + " api:" + api)
                    resetLiveKvOnUnsupportedLine()
                    return
                }
            }
            if (livesOBJ.has("epg")) {
                val epg = livesOBJ.get("epg").asString
                KV.put(KVKeys.EPG_URL, epg)
            } else {
                KV.put(KVKeys.EPG_URL, "")
            }
            if (livesOBJ.has("timeout")) {
                val timeout = Math.max(5, Math.min(30, livesOBJ.get("timeout").asInt))
                KV.put(KVKeys.LIVE_CONNECT_TIMEOUT, (timeout + 4) / 5 - 1)
            }
            if (livesOBJ.has("header") && livesOBJ.get("header").isJsonObject) {
                val headerObj = livesOBJ.getAsJsonObject("header")
                val liveHeader = HashMap<String, String>()
                for (entry in headerObj.entrySet()) {
                    if (entry.value == null || !entry.value.isJsonPrimitive) continue
                    val value = entry.value.asString
                    if (!HeaderGuard.isSendable(entry.key, value)) {
                        AppLog.i("echo-live-header-skip:" + entry.key)
                        continue
                    }
                    liveHeader[entry.key] = value
                }
                KV.put(KVKeys.LIVE_WEB_HEADER, liveHeader)
            } else if (livesOBJ.has("ua")) {
                val ua = DefaultConfig.safeJsonString(livesOBJ, "ua", "")
                val liveHeader = HashMap<String, String>()
                liveHeader["User-Agent"] = ua
                KV.put(KVKeys.LIVE_WEB_HEADER, liveHeader)
            } else {
                KV.put(KVKeys.LIVE_WEB_HEADER, null)
            }
            val liveChannelGroup = LiveChannelGroup()
            liveChannelGroup.groupName = url
            liveChannelGroupList = arrayListOf(liveChannelGroup)
        } catch (th: Throwable) {
            AppLog.e("ApiConfig", th)
        }
    }

    private fun resetLiveKvOnUnsupportedLine() {
        KV.put(KVKeys.EPG_URL, "")
        KV.put(KVKeys.LIVE_WEB_HEADER, null)
    }

    fun setLiveJar(liveJar: String) {
        spiderLoader.setLiveJar(liveJar)
    }

    fun getSpider(): String? {
        return spiderLoader.spider
    }

    fun getDanmaku(): String {
        return danmaku ?: ""
    }

    fun getCSP(sourceBean: SourceBean): Spider {
        return spiderLoader.getCSP(sourceBean)
    }

    fun warmSearchSpiders() {
        warmQueue.warmSearchSpiders(ArrayList(sourceBeanList.values), homeSourceBean())
    }

    fun getPyCSP(url: String): Spider {
        return spiderLoader.getPyCSP(url)
    }

    fun getJsCSP(url: String): Spider {
        return spiderLoader.getJsCSP(url)
    }

    fun getLiveCSP(url: String): Spider {
        return spiderLoader.getLiveCSP(url)
    }

    fun searchDanmuUi(name: String, episode: String, longClick: Boolean) {
        spiderLoader.searchDanmuUi(name, episode, longClick)
    }

    fun hasDanmuSearchUi(): Boolean {
        return spiderLoader.hasDanmuSearchUi()
    }

    val liveConnectTimeoutSeconds: Int
        get() = (KV.get(KVKeys.LIVE_CONNECT_TIMEOUT, 1) + 1) * 5

    fun proxyLocal(param: MutableMap<String, String>): Array<Any?>? {
        return proxyEntry.proxyLocal(param)
    }

    fun setCurrentPlaySourceKey(sourceKey: String?) {
        proxyEntry.setCurrentPlaySourceKey(sourceKey)
    }

    fun jsonExt(key: String, jxs: LinkedHashMap<String, String>, url: String): JSONObject? {
        return spiderLoader.jsonExt(key, jxs, url)
    }

    fun jsonExtMix(flag: String, key: String, name: String, jxs: LinkedHashMap<String, HashMap<String, String>>, url: String): JSONObject? {
        return spiderLoader.jsonExtMix(flag, key, name, jxs, url)
    }

    interface LoadConfigCallback {
        fun success()

        fun error(msg: String?)
    }

    interface FastParseCallback {
        fun success(parse: Boolean, url: String, header: MutableMap<String, String>?)

        fun fail(code: Int, msg: String?)
    }

    fun getSource(key: String?): SourceBean? {
        if (!sourceBeanList.containsKey(key)) {
            if ("push_agent" == key) {
                val sourceBean = SourceBean()
                sourceBean.key = "push_agent"
                sourceBean.name = str(R.string.source_push_agent)
                sourceBean.type = -1
                return sourceBean
            }
            return null
        }
        return sourceBeanList[key]
    }

    fun setSourceBean(sourceBean: SourceBean) {
        this.homeSource = sourceBean
        KV.put(KVKeys.HOME_API, sourceBean.key)
    }

    fun setDefaultParse(parseBean: ParseBean) {
        val previous = this.defaultParseBean
        if (previous != null) {
            previous.isDefault = false
        }
        this.defaultParseBean = parseBean
        KV.put(KVKeys.DEFAULT_PARSE, parseBean.name)
        parseBean.isDefault = true
    }

    fun getSourceBeanList(): List<SourceBean> {
        return ArrayList(sourceBeanList.values)
    }

    fun switchSourceBeanList(): List<SourceBean> {
        val filteredList: MutableList<SourceBean> = ArrayList()
        val homeKey = homeSourceBean().key
        for (bean in sourceBeanList.values) {
            if (bean.isHidden() && bean.key != homeKey) continue
            filteredList.add(bean)
        }
        return filteredList
    }

    private fun firstVisibleSite(sites: List<SourceBean>): SourceBean? {
        for (bean in sites) {
            if (!bean.isHidden()) return bean
        }
        return if (sites.isEmpty()) null else sites[0]
    }

    fun getSearchSourceBeanList(): List<SourceBean> {
        if (searchSourceBeanList.isEmpty()) {
            AppLog.i("echo-第一次getSearchSourceBeanList")
            val list = ArrayList<SourceBean>()
            for (bean in sourceBeanList.values) {
                if (bean.isSearchable()) {
                    list.add(bean)
                }
            }
            searchSourceBeanList = list
        }
        return searchSourceBeanList
    }

    fun homeSourceBean(): SourceBean {
        return homeSource ?: emptyHome
    }

    val channelGroupList: List<LiveChannelGroup>
        get() = java.util.Collections.unmodifiableList(liveChannelGroupList)

    fun myHost(): MutableMap<String, String> {
        val merged = HashMap<String, String>()
        merged.putAll(liveHosts.orEmpty())
        merged.putAll(vodHosts.orEmpty())
        return merged
    }

    private fun loadProxyRules(infoJson: JsonObject) {
        if (!infoJson.has("proxy")) {
            HttpClients.setProxyList(null)
            return
        }
        try {
            HttpClients.setProxyList(ProxyRule.arrayFrom(infoJson.get("proxy")))
        } catch (th: Throwable) {
            AppLog.e("ApiConfig", th)
            HttpClients.setProxyList(null)
        }
    }

    fun clearJarLoader() {
        spiderLoader.clearJarLoader()
    }

    private fun addSuperParse(parseBeanList: MutableList<ParseBean>) {
        val superPb = ParseBean()
        // i18n: keep —— 解析名参与 DEFAULT_PARSE 持久化与比较(见 setDefaultParse),不能翻
        superPb.name = "超级解析"
        superPb.url = "SuperParse"
        superPb.ext = ""
        superPb.type = 4
        parseBeanList.add(0, superPb)
    }

    fun clearLiveChannelGroups() {
        liveChannelGroupList = ArrayList()
    }

    fun clearLoader() {
        spiderLoader.clearLoader()
    }

    fun clearSpiderCache() {
        spiderLoader.clearSpiderCache()
    }

    companion object {
        @Volatile
        private var instance: ApiConfig? = null

        @JvmStatic
        fun get(): ApiConfig {
            if (instance == null) {
                synchronized(ApiConfig::class.java) {
                    if (instance == null) {
                        instance = ApiConfig()
                    }
                }
            }
            return requireNotNull(instance) { "api config instance missing" }
        }

        @JvmStatic
        fun FindResult(json: String, configKey: String?): String? {
            var out: String? = json
            var content = json
            try {
                if (AES.isJson(content)) return content
                val pattern = RegexUtils.getPattern("[A-Za-z0-9]{8}\\*\\*")
                val matcher = pattern.matcher(content)
                if (matcher.find()) {
                    content = content.substring(content.indexOf(matcher.group()) + 10)
                    content = String(Base64.decode(content, Base64.DEFAULT), Charset.defaultCharset())
                }
                content = content.trim { it <= ' ' }
                if (content.startsWith("2423")) {
                    content = content.replace(Regex("\\s+"), "")
                    val data = content.substring(content.indexOf("2324") + 4, content.length - 26)
                    content = String(AES.toBytes(content), Charset.defaultCharset()).lowercase(Locale.getDefault())
                    val key = AES.rightPadding(content.substring(content.indexOf("\$#") + 2, content.indexOf("#\$")), "0", 16)
                    val iv = AES.rightPadding(content.substring(content.length - 13), "0", 16)
                    out = AES.CBC(data, key, iv)
                } else if (configKey != null && !AES.isJson(content)) {
                    out = AES.ECB(content, configKey)
                } else {
                    out = content
                }
            } catch (e: Exception) {
                AppLog.e("ApiConfig", e)
            }
            return out
        }

        @JvmStatic
        fun localFileBase(): String {
            return LocalAddress.get()
        }

        @JvmStatic
        fun effectiveLiveUrl(): String {
            val liveApiUrl = KV.get(KVKeys.LIVE_API_URL, "")
            return if (TextUtils.isEmpty(liveApiUrl)) KV.get(KVKeys.API_URL, "") else liveApiUrl
        }

        @JvmStatic
        fun isLiveFollowVod(): Boolean {
            val liveApiUrl = KV.get(KVKeys.LIVE_API_URL, "")
            if (TextUtils.isEmpty(liveApiUrl)) {
                return true
            }
            return liveApiUrl == KV.get(KVKeys.API_URL, "")
        }

        @JvmStatic
        fun str(resId: Int, vararg args: Any?): String {
            val app = App.getInstance()
            return if (app == null) "" else LanguageManager.localized(app).getString(resId, *args)
        }

        @JvmStatic
        fun liveGroupIndexKey(): String {
            val liveApiUrl = KV.get(KVKeys.LIVE_API_URL, "")
            if (liveApiUrl == null || liveApiUrl.length == 0) {
                return KVKeys.LIVE_GROUP_INDEX
            }
            return KVKeys.LIVE_GROUP_INDEX + "_" + liveApiUrl
        }

        @JvmStatic
        var liveGroupIndex: Int
            get() = KV.get(liveGroupIndexKey(), 0)
            set(value) {
                KV.put(liveGroupIndexKey(), value)
            }
    }
}
