package com.github.avbox.osc.player

import android.content.res.Configuration
import com.github.avbox.osc.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlayerErrorTipTextTest {

    @Test
    fun simplified_embedsStatusCode() {
        assertEquals(
            "片源播放失败（HTTP 403），请切换线路或更换片源",
            format(R.string.player_error_play_http, "zh-CN", 403),
        )
    }

    @Test
    fun english_embedsStatusCode() {
        assertEquals(
            "Source playback failed (HTTP 403). Try another line or source.",
            format(R.string.player_error_play_http, "en", 403),
        )
    }

    @Test
    fun simplified_prefixesSiteName() {
        assertEquals(
            "咕噜：Parse Failed",
            format(R.string.player_site_failed, "zh-CN", "咕噜", "Parse Failed"),
        )
    }

    @Test
    fun english_prefixesSiteName() {
        assertEquals(
            "Gulu: Parse Failed",
            format(R.string.player_site_failed, "en", "Gulu", "Parse Failed"),
        )
    }

    @Test
    fun traditional_embedsStatusCode() {
        assertEquals(
            "片源播放失敗（HTTP 403），請切換線路或更換片源",
            format(R.string.player_error_play_http, "zh-TW", 403),
        )
    }

    @Test
    fun hongKong_embedsStatusCode() {
        assertEquals(
            "片源播放失敗（HTTP 403），請切換線路或更換片源",
            format(R.string.player_error_play_http, "zh-HK", 403),
        )
    }

    @Test
    fun traditional_prefixesSiteName() {
        assertEquals(
            "咕嚕：Parse Failed",
            format(R.string.player_site_failed, "zh-TW", "咕嚕", "Parse Failed"),
        )
    }

    private fun format(resId: Int, languageTag: String, vararg args: Any): String {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration)
        config.setLocale(Locale.forLanguageTag(languageTag))
        return app.createConfigurationContext(config).getString(resId, *args)
    }
}
