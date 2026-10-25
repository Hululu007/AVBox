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

import java.util.concurrent.Executor

class AppTaskExecutor private constructor() : TaskExecutor() {

    private var delegate: TaskExecutor
    private var defaultTaskExecutor: TaskExecutor

    init {
        defaultTaskExecutor = DefaultTaskExecutor()
        delegate = defaultTaskExecutor
    }

    fun setDelegate(taskExecutor: TaskExecutor?) {
        delegate = taskExecutor ?: defaultTaskExecutor
    }

    override fun executeOnDeskIO(task: Runnable) {
        delegate.executeOnDeskIO(task)
    }

    override fun executeOnMainThread(task: Runnable) {
        delegate.executeOnMainThread(task)
    }

    override fun postToMainThread(task: Runnable) {
        delegate.postToMainThread(task)
    }

    override fun isMainThread(): Boolean {
        return delegate.isMainThread()
    }

    companion object {

        private var sInstance: AppTaskExecutor? = null

        @JvmStatic
        fun getInstance(): TaskExecutor {
            return sInstance ?: synchronized(AppTaskExecutor::class.java) {
                sInstance ?: AppTaskExecutor().also { sInstance = it }
            }
        }

        private val sDeskIO: Executor = Executor { command ->
            getInstance().executeOnDeskIO(command)
        }

        private val sMainThread: Executor = Executor { command ->
            getInstance().executeOnMainThread(command)
        }

        @JvmStatic
        fun deskIO(): Executor {
            return sDeskIO
        }

        @JvmStatic
        fun mainThread(): Executor {
            return sMainThread
        }
    }
}
