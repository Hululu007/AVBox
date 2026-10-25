package com.github.avbox.osc.player

enum class KernelDecision { REUSE, REBUILD }

object KernelReusePolicy {

    @JvmStatic
    fun decide(
        kernelPresent: Boolean,
        rebuildRequired: Boolean,
        dedicatedPath: Boolean,
        reuseAllowed: Boolean,
    ): KernelDecision {
        if (!kernelPresent) return KernelDecision.REBUILD
        if (rebuildRequired || dedicatedPath) return KernelDecision.REBUILD
        return if (reuseAllowed) KernelDecision.REUSE else KernelDecision.REBUILD
    }

    @JvmStatic
    fun isCrossContentSwitch(startedKey: String?, targetKey: String?): Boolean {
        if (startedKey.isNullOrEmpty()) return false
        val prefix = sameVodEpisodePrefix(targetKey)
        if (prefix.isEmpty()) return true
        return !startedKey.startsWith(prefix)
    }

    private fun sameVodEpisodePrefix(targetKey: String?): String {
        if (targetKey.isNullOrEmpty()) return ""
        val cut = targetKey.lastIndexOf('|')
        return if (cut > 0) targetKey.substring(0, cut + 1) else ""
    }
}
