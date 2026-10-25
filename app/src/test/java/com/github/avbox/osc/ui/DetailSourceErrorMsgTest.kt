package com.github.avbox.osc.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailSourceErrorMsgTest {

    @Test
    fun noErrorWhenMsgMissing() {
        assertFalse(DetailViewModel.isSourceErrorMsg(null))
        assertFalse(DetailViewModel.isSourceErrorMsg(""))
    }

    @Test
    fun noErrorOnEmptyDataSentinel() {
        assertFalse(DetailViewModel.isSourceErrorMsg("数据列表"))
    }

    @Test
    fun errorOnRealSourceMessage() {
        assertTrue(DetailViewModel.isSourceErrorMsg("本接口免费分享！切勿上当！"))
        assertTrue(DetailViewModel.isSourceErrorMsg("数据不存在"))
    }
}