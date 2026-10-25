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
import com.github.avbox.osc.subtitle.runtime.AppTaskExecutor

class UIRenderTask(private val subtitleChangeListener: SubtitleEngine.OnSubtitleChangeListener?) : Runnable {

    private var renderSubtitle: Subtitle? = null

    override fun run() {
        if (subtitleChangeListener != null) {
            subtitleChangeListener.onSubtitleChanged(renderSubtitle)
        }
    }

    fun execute(subtitle: Subtitle?) {
        renderSubtitle = subtitle
        AppTaskExecutor.mainThread().execute(this)
    }
}
