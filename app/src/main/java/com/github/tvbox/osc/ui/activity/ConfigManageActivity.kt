package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
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
            if (uri != null && handleLocalConfigResult(this, uri)) settleUnreachableSource(uri)
        }

    private val sourceTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            handleLocalSourceTreeResult(this, uri)
        }

    /** 不预检存储权限:能不能直引由"应用此刻是否真读得到"决定,预检会让读得到的设备白跳一次设置页 */
    fun launchLocalConfig(onResult: (api: String) -> Unit) {
        startLocalConfig(localConfigLauncher) { api -> onResult(api) }
    }

    /**
     * 读不到原文件:先争一次「所有文件访问」(只有它能救存储根 / Download 根这类落点),
     * 拿到就重试导入;拿不到则用上一次已挂起的结果接着要目录授权。
     */
    private fun settleUnreachableSource(uri: Uri) {
        if (PermissionHelper.isStorageGranted(this)) {
            sourceTreeLauncher.launch(null)
            return
        }
        PermissionHelper.requestStorage(this) { granted, _ ->
            if (granted.isNullOrEmpty()) sourceTreeLauncher.launch(null)
            else if (handleLocalConfigResult(this, uri)) sourceTreeLauncher.launch(null)
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
