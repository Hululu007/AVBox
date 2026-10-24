package com.github.tvbox.osc.ui.activity

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.io.cancelLocalConfig
import com.github.tvbox.osc.io.handleLocalConfigResult
import com.github.tvbox.osc.io.handleLocalSourceTreeResult
import com.github.tvbox.osc.io.importLocalSource
import com.github.tvbox.osc.io.startLocalConfig
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.api.AppBootstrap
import com.github.tvbox.osc.ui.page.ConfigManageScreen
import com.github.tvbox.osc.ui.page.ConfigManageViewModel
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.PermissionHelper
import com.github.tvbox.osc.util.SpiderStorageNotice

class ConfigManageActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ConfigManageActivity::class.java))
        }
    }

    private val configVm by lazy {
        ViewModelProvider(this, ConfigManageViewModel.defaultFactory())[ConfigManageViewModel::class.java]
    }

    private val localConfigLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                handleLocalConfigResult(this, uri) { needTree -> if (needTree) settleUnreachableSource(uri) }
            } else {
                cancelLocalConfig()
            }
        }

    private val sourceTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            handleLocalSourceTreeResult(this, uri)
        }

    fun launchLocalConfig(onResult: (name: String, api: String) -> Unit) {
        startLocalConfig(localConfigLauncher) { name, api -> onResult(name, api) }
    }

    private fun launchSourceTree() {
        SpiderStorageNotice.suppress()
        sourceTreeLauncher.launch(null)
    }

    private fun settleUnreachableSource(uri: Uri) {
        if (PermissionHelper.isStorageGranted(this)) {
            launchSourceTree()
            return
        }
        PermissionHelper.requestStorage(this) { granted, _ ->
            if (granted.isNullOrEmpty()) {
                launchSourceTree()
            } else {
                handleLocalConfigResult(this, uri) { needTree -> if (needTree) launchSourceTree() }
            }
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppBootstrap.start()
        if (savedInstanceState == null) handleViewIntent(intent)
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                leavePage()
            }
        })
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    ConfigManageScreen(onNavigateBack = { leavePage() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        importLocalSource(
            this,
            uri,
            onImported = { name, api ->
                if (configVm.addLocalSource(name, api)) {
                    Toast.makeText(this, R.string.toast_local_source_already_imported, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.toast_local_source_imported, name), Toast.LENGTH_SHORT).show()
                }
            },
            onFailed = { unsupported ->
                val tip = if (unsupported) {
                    R.string.toast_local_source_unsupported
                } else {
                    R.string.toast_local_config_read_failed
                }
                Toast.makeText(this, getString(tip), Toast.LENGTH_SHORT).show()
            },
        )
    }

    private fun inForeignTask(): Boolean {
        val tasks = try {
            (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.appTasks
        } catch (th: Throwable) {
            LOG.e("ConfigManageActivity", th)
            null
        } ?: return false
        return tasks.none { it.taskInfo?.taskId == taskId }
    }

    private fun leavePage() {
        if (inForeignTask()) {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }
        finish()
    }
}
