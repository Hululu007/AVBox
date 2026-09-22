package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.ui.page.ThemeSettingsScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme

class ThemeSettingsActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ThemeSettingsActivity::class.java))
        }
    }

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                // 独立 Activity 页面:套窗口根槽位,弹层无论写在哪都能全屏弹出(见 SheetHostScaffold)
                SheetHostScaffold {
                    ThemeSettingsScreen(onNavigateBack = { finish() })
                }
            }
        }
    }
}
