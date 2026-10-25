package com.github.avbox.osc.data

import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.util.AppLog
import java.util.ArrayList
import java.util.Locale
import java.util.regex.Pattern

object EpisodeMatcher {

    private fun isEmpty(text: String?): Boolean {
        return text == null || text.length == 0
    }

    @JvmStatic
    fun lineFlagsInDisplayOrder(vod: VodInfo?): List<String> {
        val lineFlags = ArrayList<String>()
        if (vod == null) {
            return lineFlags
        }
        val seriesMap = vod.seriesMap ?: return lineFlags
        val seriesFlags = vod.seriesFlags
        if (seriesFlags != null) {
            for (flag in seriesFlags) {
                val name = flag.name
                if (!name.isNullOrEmpty() && seriesMap.containsKey(name) && !lineFlags.contains(name)) {
                    lineFlags.add(name)
                }
            }
        }
        for (flag in seriesMap.keys) {
            if (!flag.isNullOrEmpty() && !lineFlags.contains(flag)) {
                lineFlags.add(flag)
            }
        }
        return lineFlags
    }

    @JvmStatic
    fun lineFlagIndex(lineFlags: List<String>?, currentFlag: String?): Int {
        if (lineFlags == null || currentFlag.isNullOrEmpty()) {
            return -1
        }
        for (i in lineFlags.indices) {
            if (currentFlag == lineFlags[i]) {
                return i
            }
        }
        return -1
    }

    @JvmStatic
    fun sameEpisodeIndex(currentSeries: VodInfo.VodSeries?, targetList: List<VodInfo.VodSeries>?, fallbackIndex: Int): Int {
        if (targetList == null || targetList.isEmpty()) {
            return 0
        }
        if (targetList.size == 1) {
            return 0
        }
        if (currentSeries == null || currentSeries.name.isNullOrEmpty()) {
            return Math.max(0, Math.min(fallbackIndex, targetList.size - 1))
        }
        val currentEpisode = extractEpisodeNumber(currentSeries.name)
        var matchedIndex = -1
        var bestScore = 0
        for (i in targetList.indices) {
            val score = episodeMatchScore(currentSeries.name, currentEpisode, targetList[i].name)
            if (score > bestScore) {
                bestScore = score
                matchedIndex = i
            }
        }
        if (matchedIndex >= 0) {
            return matchedIndex
        }
        return Math.max(0, Math.min(fallbackIndex, targetList.size - 1))
    }

    @JvmStatic
    fun episodeMatchScore(currentName: String?, currentEpisode: Int, targetName: String?): Int {
        if (currentName.isNullOrEmpty() || targetName.isNullOrEmpty()) {
            return 0
        }
        if (targetName.equals(currentName, ignoreCase = true)) {
            return 100
        }
        if (currentEpisode >= 0 && extractEpisodeNumber(targetName) == currentEpisode) {
            return 80
        }
        val currentLower = currentName.lowercase(Locale.ROOT)
        val targetLower = targetName.lowercase(Locale.ROOT)
        if (currentEpisode < 0 && currentName.length >= 2 && targetLower.contains(currentLower)) {
            return 70
        }
        if (currentEpisode < 0 && targetName.length >= 2 && currentLower.contains(targetLower)) {
            return 60
        }
        return 0
    }

    @JvmStatic
    fun extractEpisodeNumber(name: String?): Int {
        if (isEmpty(name)) {
            return -1
        }
        val raw = name ?: return -1
        try {
            var text = raw.replace(Regex("\\[.*?\\]|\\(.*?\\)"), "")
            text = text.replace(Regex("\\b(19|20)\\d{2}\\b"), "")
            text = text.lowercase(Locale.ROOT).replace(Regex("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4"), "")
            val matcher = Pattern.compile("(?i)(?:ep|\\u7b2c|e|[\\-\\.\\s])\\s?(\\d{1,4})").matcher(text)
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1))
            }
            val number = text.replace(Regex("\\D+"), "")
            if (!isEmpty(number)) {
                return Integer.parseInt(number)
            }
        } catch (ignored: Exception) {
            AppLog.d("EpisodeMatcher", "episode number extract failed, name=$name")
        }
        return -1
    }
}
