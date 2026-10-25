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

package com.github.avbox.osc.subtitle.runtime

import android.os.Handler
import android.os.Looper

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DefaultTaskExecutor : TaskExecutor() {

    private var mainHandler: Handler? = null
    private val lock = Any()
    private val deskIO: ExecutorService = Executors.newFixedThreadPool(3)

    override fun executeOnDeskIO(task: Runnable) {
        deskIO.execute(task)
    }

    override fun postToMainThread(task: Runnable) {
        val handler = mainHandler ?: synchronized(lock) {
            Handler(Looper.getMainLooper()).also { mainHandler = it }
        }
        handler.post(task)
    }

    override fun isMainThread(): Boolean {
        return Thread.currentThread() === Looper.getMainLooper().thread
    }
}
