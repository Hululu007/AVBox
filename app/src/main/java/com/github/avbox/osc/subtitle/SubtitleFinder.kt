/*
 *                       Copyright (C) of Avery
 *
 *                              _ooOoo_
 *                             o8888888o
 *                             88" . "88
 *                             (| -_- |)
 *                             O\  =  /O
 *                          ____/`- -'\____
 *                        .'  \\|     |//  `.
 *                       /  \\|||  :  |||//  \
 *                      /  _||||| -:- |||||-  \
 *                      |   | \\\  -  /// |   |
 *                      | \_|  ''\- -/''  |   |
 *                      \  .-\__  `-`  ___/-. /
 *                    ___`. .' /- -.- -\  `. . __
 *                 ."" '<  `.___\_<|>_/___.'  >'"".
 *                | | :  `- \`.;`\ _ /`;.`/ - ` : | |
 *                \  \ `-.   \_ __\ /__ _/   .-` /  /
 *           ======`-.____`-.___\_____/___.-`____.-'======
 *                              `=- -='
 *           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *              Buddha bless, there will never be bug!!!
 */

package com.github.avbox.osc.subtitle

import com.github.avbox.osc.subtitle.model.Subtitle

object SubtitleFinder {

    @JvmStatic
    fun find(position: Long, subtitles: List<Subtitle>?): Subtitle? {
        if (subtitles == null || subtitles.isEmpty()) {
            return null
        }
        var start = 0
        var end = subtitles.size - 1
        while (start <= end) {
            val middle = (start + end) / 2
            val middleSubtitle = subtitles[middle]
            val startTime = middleSubtitle.start?.mseconds ?: return null
            val endTime = middleSubtitle.end?.mseconds ?: return null
            if (position < startTime) {
                if (position > endTime) {
                    return middleSubtitle
                }
                end = middle - 1
            } else if (position > endTime) {
                if (position < startTime) {
                    return middleSubtitle
                }
                start = middle + 1
            } else if (position >= startTime &&
                position <= endTime) {
                return middleSubtitle
            }
        }
        return null
    }
}
