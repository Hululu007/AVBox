package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailSourceErrorMsgTest {

    @Test
    fun noErrorWhenMsgMissing() {
        assertFalse(DetailViewModel.isSourceErrorMsg(null))
        assertFalse(DetailViewModel.isSourceErrorMsg(""))
    }

    /** 「数据列表」是源侧"没有数据"的正常提示(数据规则),判成错误会让空详情页自动关闭 */
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