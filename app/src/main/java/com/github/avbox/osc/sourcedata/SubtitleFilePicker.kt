package com.github.avbox.osc.sourcedata

import com.github.avbox.osc.data.EpisodeMatcher
import java.util.Locale

object SubtitleFilePicker {

    @JvmStatic
    fun pick(fileNames: List<String>?, episodeName: String?, fileNameHint: String?): Int {
        if (fileNames == null || fileNames.isEmpty()) return -1
        val episode = EpisodeMatcher.extractEpisodeNumber(episodeName)

        if (episode >= 0) {
            var only = -1
            var sameEpisode = 0
            for (i in fileNames.indices) {
                if (EpisodeMatcher.extractEpisodeNumber(fileNames[i]) != episode) continue
                only = i
                sameEpisode++
            }
            if (sameEpisode == 1) return only
            if (sameEpisode > 1) return pickSameVariant(fileNames, episode, fileNameHint)
        }

        val hint = normalize(fileNameHint)
        if (hint.isNotEmpty()) {
            var only = -1
            var sameName = 0
            for (i in fileNames.indices) {
                if (normalize(fileNames[i]) != hint) continue
                only = i
                sameName++
            }
            if (sameName == 1) return only
        }

        if (fileNames.size == 1) {
            val fileEpisode = EpisodeMatcher.extractEpisodeNumber(fileNames[0])
            if (fileEpisode < 0 || episode < 0 || fileEpisode == episode) return 0
        }
        return -1
    }

    private fun pickSameVariant(fileNames: List<String>, episode: Int, fileNameHint: String?): Int {
        val hintVariant = variant(fileNameHint)
        if (hintVariant.isEmpty()) return -1
        var only = -1
        var matched = 0
        for (i in fileNames.indices) {
            if (EpisodeMatcher.extractEpisodeNumber(fileNames[i]) != episode) continue
            if (variant(fileNames[i]) != hintVariant) continue
            only = i
            matched++
        }
        return if (matched == 1) only else -1
    }

    private fun normalize(text: String?): String {
        val value = if (text == null) "" else text.trim { it <= ' ' }.lowercase(Locale.ROOT)
        val dot = value.lastIndexOf('.')
        return if (dot > 0) value.substring(0, dot) else value
    }

    private fun variant(text: String?): String {
        val value = normalize(text)
        return if (value.isEmpty()) "" else value.replace(Regex("\\d+"), "#")
    }
}
