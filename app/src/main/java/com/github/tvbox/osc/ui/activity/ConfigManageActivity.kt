package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.ui.page.ConfigManageScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.util.PermissionHelper
import com.github.tvbox.osc.util.handleLocalConfigResult
import com.github.tvbox.osc.util.handleLocalSourceTreeResult
import com.github.tvbox.osc.util.startLocalConfig

class ConfigManageActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ConfigManageActivity::class.java))
        }
    }

    private val localConfigLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && handleLocalConfigResult(this, uri)) sourceTreeLauncher.launch(null)
        }

    private val sourceTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            handleLocalSourceTreeResult(this, uri)
        }

    fun launchLocalConfig(onResult: (api: String) -> Unit) {
        if (PermissionHelper.isStorageGranted(this)) {
            startLocalConfig(localConfigLauncher) { api -> onResult(api) }
            return
        }
        PermissionHelper.requestStorage(this) { granted, _ ->
            if (!granted.isNullOrEmpty()) {
                startLocalConfig(localConfigLauncher) { api -> onResult(api) }
            } else {
                Toast.makeText(this, getString(R.string.toast_permission_required), Toast.LENGTH_SHORT).show()
            }
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
                    ConfigManageScreen(onNavigateBack = { finish() })
                }
            }
        }
    }
}
