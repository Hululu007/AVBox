package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.ui.page.PreferenceSettingsScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme

class PreferenceSettingsActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, PreferenceSettingsActivity::class.java))
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
                // 独立 Activity 页面:必须给弹层一个窗口根槽位,否则"切换语言"之类的对话框
                // 会就地渲染进设置列表(见 SheetHostScaffold 注释)
                SheetHostScaffold {
                    PreferenceSettingsScreen(onNavigateBack = { finish() })
                }
            }
        }
    }
}
