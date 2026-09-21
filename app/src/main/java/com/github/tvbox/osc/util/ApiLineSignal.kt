package com.github.tvbox.osc.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 仓列表变更信号(生效地址由仓地址改写成仓内子源、或反向清空),每次自增。
 *
 * 改写发生在**异步**配置加载里,不产生任何 Compose 状态变化 —— 界面若只把生效地址读进本地快照
 * 就会一直滞后,直到页面重建(表现为「换仓」入口要退出重进才出现)。靠"加载完成"的状态反推不可靠:
 * 点播的完成态要等 jar 装载也跑完(改写早已结束),直播侧的改写又发生在另一个页面。故由改写点直接发信号。
 */
object ApiLineSignal {

    private val _version = MutableStateFlow(0)

    /** 单调自增;订阅它重读即可。写回等值不会触发重组,可安全地在任何改写点调用 */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun notifyChanged() {
        _version.value += 1
    }
}
