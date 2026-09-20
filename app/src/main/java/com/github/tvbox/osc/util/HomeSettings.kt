package com.github.tvbox.osc.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object HomeSettings {

    enum class HomeLayout { Horizontal, Vertical }

    // 未登记 KVKeySpec:读取必须带默认值(靠默认值携带 String 类型),KV.get(key) 不带默认值会解不出
    private const val KEY_LAYOUT = "home_layout"

    private const val VALUE_LAYOUT_HORIZONTAL = "horizontal"

    private const val VALUE_LAYOUT_VERTICAL = "vertical"

    private val mutableLayout = MutableStateFlow(current())

    val layoutFlow: StateFlow<HomeLayout> = mutableLayout

    fun current(): HomeLayout =
        if (KV.get(KEY_LAYOUT, VALUE_LAYOUT_VERTICAL) == VALUE_LAYOUT_HORIZONTAL) {
            HomeLayout.Horizontal
        } else {
            HomeLayout.Vertical
        }

    fun setLayout(layout: HomeLayout) {
        KV.put(
            KEY_LAYOUT,
            if (layout == HomeLayout.Vertical) VALUE_LAYOUT_VERTICAL else VALUE_LAYOUT_HORIZONTAL,
        )
        mutableLayout.value = layout
    }
}
