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

import com.github.avbox.osc.player.core.KernelPlayer

interface SubtitleEngine {

    fun setSubtitlePath(path: String?)

    fun setSubtitleDelay(milliseconds: Int?)

    fun setPlaySubtitleCacheKey(cacheKey: String?)

    fun getPlaySubtitleCacheKey(): String?

    fun start()

    fun pause()

    fun resume()

    fun stop()

    fun reset()

    fun destroy()

    fun bindToMediaPlayer(mediaPlayer: KernelPlayer?)

    fun setOnSubtitlePreparedListener(listener: OnSubtitlePreparedListener?)

    fun setOnSubtitleChangeListener(listener: OnSubtitleChangeListener?)

    interface OnSubtitlePreparedListener {
        fun onSubtitlePrepared(subtitles: List<Subtitle>?)
    }

    interface OnSubtitleChangeListener {
        fun onSubtitleChanged(subtitle: Subtitle?)
    }
}
