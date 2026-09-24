package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DetailFullScreenGateTest {

    private val loading = "内容还没加载好"
    private val empty = "暂无片源,可尝试换源或搜索"

    private fun reason(state: DetailViewModel.PageState) =
        DetailFullScreenGate.refusalReason(state, loadingText = { loading }, emptyText = { empty })

    @Test
    fun refusedWhileDetailLoading() {
        assertEquals(loading, reason(DetailViewModel.PageState.Loading))
    }

    @Test
    fun refusedWhenEmptyAndTellsSourceReason() {
        assertEquals("本接口免费分享！切勿上当！", reason(DetailViewModel.PageState.Empty("本接口免费分享！切勿上当！")))
    }

    @Test
    fun refusedWhenEmptyWithoutUsableMsg() {
        assertEquals(empty, reason(DetailViewModel.PageState.Empty(null)))
        assertEquals(empty, reason(DetailViewModel.PageState.Empty("")))
        assertEquals(empty, reason(DetailViewModel.PageState.Empty("   ")))
    }

    @Test
    fun allowedOnceReadyWithoutTouchingTexts() {
        var textsAsked = false
        val result = DetailFullScreenGate.refusalReason(
            DetailViewModel.PageState.Ready,
            loadingText = { textsAsked = true; loading },
            emptyText = { textsAsked = true; empty },
        )
        assertNull(result)
        assertFalse(textsAsked)
    }
}
