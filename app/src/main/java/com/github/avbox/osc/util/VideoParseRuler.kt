package com.github.avbox.osc.util

import android.net.Uri
import java.util.ArrayList
import java.util.HashMap

object VideoParseRuler {

    private val HOSTS_RULE = HashMap<String, ArrayList<ArrayList<String>>>()
    private val HOSTS_FILTER = HashMap<String, ArrayList<ArrayList<String>>>()
    internal val hostsRegex = HashMap<String, ArrayList<String>>()
    private val HOSTS_SCRIPT = HashMap<String, ArrayList<String>>()
    private val HOSTS_EXCLUDE = HashMap<String, ArrayList<String>>()

    @JvmStatic
    fun clearRule() {
        HOSTS_RULE.clear()
        HOSTS_FILTER.clear()
        hostsRegex.clear()
        HOSTS_SCRIPT.clear()
        HOSTS_EXCLUDE.clear()
    }

    @JvmStatic
    fun addHostRule(host: String, rule: ArrayList<String>) {
        var rules = ArrayList<ArrayList<String>>()
        val existing = HOSTS_RULE[host]
        if (existing != null && existing.size > 0) {
            rules = existing
        }
        rules.add(rule)
        HOSTS_RULE[host] = rules
    }

    @JvmStatic
    fun getHostRules(host: String?): ArrayList<ArrayList<String>>? {
        if (HOSTS_RULE.containsKey(host)) {
            return HOSTS_RULE[host]
        }
        return null
    }

    @JvmStatic
    fun addHostFilter(host: String, rule: ArrayList<String>) {
        var filters = ArrayList<ArrayList<String>>()
        val existing = HOSTS_FILTER[host]
        if (existing != null && existing.size > 0) {
            filters = existing
        }
        filters.add(rule)
        HOSTS_FILTER[host] = filters
    }

    @JvmStatic
    fun getHostFilters(host: String?): ArrayList<ArrayList<String>>? {
        if (HOSTS_FILTER.containsKey(host)) {
            return HOSTS_FILTER[host]
        }
        return null
    }

    @JvmStatic
    fun addHostRegex(host: String, regex: ArrayList<String>?) {
        if (regex == null || regex.size == 0) return
        var temp = ArrayList<String>()
        val existing = hostsRegex[host]
        if (existing != null && existing.size > 0) temp = existing
        temp.addAll(regex)
        hostsRegex[host] = temp
    }

    @JvmStatic
    fun checkIsVideoForParse(webUrl: String?, url: String): Boolean {
        try {
            if (isExcluded(webUrl, url)) {
                return false
            }
            var isVideo = DefaultConfig.isVideoFormat(url)
            if (!HOSTS_RULE.isEmpty() && !isVideo && webUrl != null) {
                val uri = Uri.parse(webUrl)
                if (getHostRules(uri.host) != null) {
                    isVideo = checkVideoForOneHostRules(uri.host, url)
                } else {
                    isVideo = checkVideoForOneHostRules("*", url)
                }
            }
            return isVideo
        } catch (e: Exception) {
            AppLog.e("VideoParseRuler", e)
        }
        return false
    }

    private fun checkVideoForOneHostRules(host: String?, url: String): Boolean {
        var isVideo = false
        val hostRules = getHostRules(host)
        if (hostRules != null && hostRules.size > 0) {
            var isVideoRuleCheck = false
            for (i in 0 until hostRules.size) {
                var checkIsVideo = true
                if (hostRules[i] != null && hostRules[i].size > 0) {
                    for (j in 0 until hostRules[i].size) {
                        val onePattern = RegexUtils.getPattern("" + hostRules[i][j])
                        if (!onePattern.matcher(url).find()) {
                            checkIsVideo = false
                            break
                        }
                        AppLog.i("echo-VIDEO RULE:" + hostRules[i][j])
                    }
                } else {
                    checkIsVideo = false
                }
                if (checkIsVideo) {
                    isVideoRuleCheck = true
                    break
                }
            }
            if (isVideoRuleCheck) {
                isVideo = true
            }
        }
        return isVideo
    }

    @JvmStatic
    fun isFilter(webUrl: String?, url: String): Boolean {
        try {
            var isFilter = false
            if (!HOSTS_FILTER.isEmpty() && webUrl != null) {
                val uri = Uri.parse(webUrl)
                if (getHostFilters(uri.host) != null) {
                    isFilter = checkIsFilterForOneHostRules(uri.host, url)
                }
            }
            return isFilter
        } catch (e: Exception) {
            AppLog.e("VideoParseRuler", e)
        }
        return false
    }

    private fun checkIsFilterForOneHostRules(host: String?, url: String): Boolean {
        var isFilter = false
        val hostFilters = getHostFilters(host)
        if (hostFilters != null && hostFilters.size > 0) {
            var isFilterRuleCheck = false
            for (i in 0 until hostFilters.size) {
                var checkIsFilter = true
                if (hostFilters[i] != null && hostFilters[i].size > 0) {
                    for (j in 0 until hostFilters[i].size) {
                        val onePattern = RegexUtils.getPattern("" + hostFilters[i][j])
                        if (!onePattern.matcher(url).find()) {
                            checkIsFilter = false
                            break
                        }
                        AppLog.i("echo-FILTER RULE:" + hostFilters[i][j])
                    }
                } else {
                    checkIsFilter = false
                }
                if (checkIsFilter) {
                    isFilterRuleCheck = true
                    break
                }
            }
            if (isFilterRuleCheck) {
                isFilter = true
            }
        }
        return isFilter
    }

    @JvmStatic
    fun addHostScript(host: String, script: ArrayList<String>?) {
        if (script == null || script.size == 0) return
        var temp = ArrayList<String>()
        val existing = HOSTS_SCRIPT[host]
        if (existing != null && existing.size > 0) temp = existing
        temp.addAll(script)
        HOSTS_SCRIPT[host] = temp
    }

    @JvmStatic
    fun hostScript(url: String): String {
        for ((host, list) in HOSTS_SCRIPT) {
            if (url.contains(host)) {
                if (list != null && !list.isEmpty()) {
                    return list[0]
                }
            }
        }
        return ""
    }

    @JvmStatic
    fun addHostExclude(host: String, exclude: ArrayList<String>?) {
        if (exclude == null || exclude.isEmpty()) return
        var temp = HOSTS_EXCLUDE[host]
        if (temp == null) temp = ArrayList()
        temp.addAll(exclude)
        HOSTS_EXCLUDE[host] = temp
    }

    @JvmStatic
    fun hostExcludes(host: String?): ArrayList<String>? {
        return HOSTS_EXCLUDE[host]
    }

    private fun isExcluded(webUrl: String?, url: String): Boolean {
        if (HOSTS_EXCLUDE.isEmpty() || webUrl == null) return false
        val uri = Uri.parse(webUrl)
        var excludes = hostExcludes(uri.host)
        if (excludes == null) excludes = hostExcludes("*")
        if (excludes == null) return false
        for (exclude in excludes) {
            if (exclude == null || exclude.isEmpty()) continue
            if (url.contains(exclude)) return true
            try {
                if (RegexUtils.getPattern(exclude).matcher(url).find()) return true
            } catch (th: Throwable) {
                AppLog.i("echo-EXCLUDE bad pattern:" + exclude)
            }
        }
        return false
    }
}
