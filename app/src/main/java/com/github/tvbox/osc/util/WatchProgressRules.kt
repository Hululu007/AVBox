package com.github.tvbox.osc.util

/** 一条进度值的处置结论:落盘 / 清除已有记录 / 不写也不清 */
enum class WatchDecision { SAVE, CLEAR, SKIP }

/**
 * "这条进度值不值得记住"的唯一判据:续播点、历史页百分比、"看过"门槛三处共用 ——
 * 各自定阈值必然漂移出"卡片显示看过、点进去却从头"。纯 JVM,边界见 [WatchProgressRulesTest]。
 */
object WatchProgressRules {

    /** 续播点最小位置(毫秒);时长未知时用绝对值 */
    const val MIN_RESUME_MS = 30_000L

    /** 续播点最小比例(%);与 [MIN_RESUME_MS] 取更小者,让短视频也能留下续播点 */
    const val MIN_RESUME_PERCENT = 30L

    /** 看到这个比例即视为看完:清掉续播点与百分比,否则下次点开直接跳片尾 */
    const val FINISHED_PERCENT = 95L

    fun decide(positionMs: Long, durationMs: Long): WatchDecision {
        if (positionMs <= 0) return WatchDecision.SKIP
        if (durationMs > 0 && positionMs * 100 >= durationMs * FINISHED_PERCENT) return WatchDecision.CLEAR
        val minResume = if (durationMs > 0) {
            minOf(MIN_RESUME_MS, durationMs * MIN_RESUME_PERCENT / 100)
        } else {
            MIN_RESUME_MS
        }
        return if (positionMs >= minResume) WatchDecision.SAVE else WatchDecision.SKIP
    }

    /** "真看过":进观看历史与记集数的门槛。与 SAVE 同源 —— 看完的那一集当然也算看过 */
    fun shouldRemember(positionMs: Long, durationMs: Long): Boolean =
        decide(positionMs, durationMs) != WatchDecision.SKIP
}
