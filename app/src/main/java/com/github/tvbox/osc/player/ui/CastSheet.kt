@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.player.ui

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.dlna.CastDevice
import com.github.tvbox.osc.dlna.DLNACastManager
import com.github.tvbox.osc.player.state.CastSheetState
import com.github.tvbox.osc.player.thirdparty.RemoteTVBox
import com.github.tvbox.osc.util.PermissionHelper
import com.github.tvbox.osc.util.PlayerHelper

/** 投屏面板:DLNA/TVBox 设备扫描与投送 */

// ---------------------------------------------------------------------------
// 投屏(扫描/投送逻辑按原实现 1:1 迁移)
// ---------------------------------------------------------------------------

@Composable
fun CastSheet(sheet: CastSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivityOrNull() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val devices = remember { LinkedHashMap<String, CastDevice>() }
    var deviceList by remember { mutableStateOf(emptyList<CastDevice>()) }
    var searchFinished by remember { mutableStateOf(false) }
    var scanToken by remember { mutableIntStateOf(1) }
    var canScan by remember { mutableStateOf(PermissionHelper.isLocalNetworkGranted(context)) }

    val addDevice: (CastDevice) -> Unit = { device ->
        devices[device.type.toString() + ":" + device.id] = device
        deviceList = devices.values.toList()
    }

    val applyGranted: (Boolean) -> Unit = { granted ->
        canScan = granted
        if (granted) scanToken++
    }

    LaunchedEffect(Unit) {
        val act = activity
        if (!canScan && act != null) {
            PermissionHelper.requestLocalNetworkAuto(act) { granted, _ ->
                applyGranted(!granted.isNullOrEmpty())
            }
        }
    }

    DisposableEffect(scanToken, canScan) {
        searchFinished = false
        val scanning = canScan
        if (!scanning) return@DisposableEffect onDispose { }
        // TVBox 局域网扫描(旧 searchTvBoxDevices;回调线程切主线程)
        Thread {
            RemoteTVBox.searchAvalible(object : RemoteTVBox.Callback() {
                override fun found(viewHost: String?, end: Boolean) {
                    mainHandler.post {
                        // 记住扫描到的 TVBox 地址:PlayerHelper 13 号
                        // 「RemoteTVBox 播放器」与 RemoteTVBox.run() 都依赖 HawkConfig.REMOTE_TVBOX,
                        // 而这里曾是它唯一的写入时机 —— Compose 迁移后漏掉了,导致该播放器永远不可用。
                        // 仅当尚未记住时写入,避免多台设备时覆盖用户显式选择。
                        if (!viewHost.isNullOrEmpty() && RemoteTVBox.getAvalible() == null) {
                            RemoteTVBox.setAvalible(viewHost)
                            PlayerHelper.invalidatePlayersExistInfo()
                        }
                        addDevice(CastDevice.tvbox(viewHost))
                    }
                }

                override fun fail(all: Boolean, end: Boolean) {
                    // 旧实现 end 时仅刷新状态,无需处理
                }
            })
        }.start()
        // DLNA 扫描(旧 searchDlnaDevices)
        DLNACastManager.get().setDeviceListener(object : DLNACastManager.DeviceListener {
            override fun onDeviceChanged() {
                mainHandler.post {
                    for (device in DLNACastManager.get().devices) addDevice(device)
                }
            }
        })
        DLNACastManager.get().init(context)
        mainHandler.postDelayed({ DLNACastManager.get().search() }, 1000)
        mainHandler.postDelayed({ searchFinished = true }, 15000)
        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            DLNACastManager.get().setDeviceListener(null)
            DLNACastManager.get().release(context)
        }
    }

    val castToDevice: (CastDevice) -> Unit = { device ->
        if (device.type == CastDevice.TYPE_TVBOX) {
            try {
                val headers = sheet.video.headers
                val url = if (headers == null || headers.isEmpty()) sheet.video.url
                else sheet.video.url + "@Headers=" +
                        java.net.URLEncoder.encode(org.json.JSONObject(headers).toString(), "UTF-8") + "@"
                val params = HashMap<String, String>()
                params["do"] = "push"
                params["url"] = url
                RemoteTVBox.post("http://" + device.id + "/action", params, object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        mainHandler.post {
                            Toast.makeText(context, context.getString(R.string.toast_cast_tvbox_failed), Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        val ok = try {
                            response.body.string() == "ok"
                        } finally {
                            response.close()
                        }
                        mainHandler.post {
                            if (ok) {
                                // 投屏成功 = 用户显式选中该设备 → 以它为准记住地址(覆盖扫描时的兜底值)
                                RemoteTVBox.setAvalible(device.id)
                                PlayerHelper.invalidatePlayersExistInfo()
                                Toast.makeText(context, context.getString(R.string.toast_cast_success), Toast.LENGTH_SHORT).show()
                                sheet.onCastSuccess()
                                onDismiss()
                            } else {
                                Toast.makeText(context, context.getString(R.string.toast_cast_tvbox_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.toast_cast_tvbox_failed), Toast.LENGTH_SHORT).show()
            }
        } else {
            DLNACastManager.get().cast(device, sheet.video, object : DLNACastManager.CastCallback {
                override fun onResult(success: Boolean, msg: String?) {
                    mainHandler.post {
                        if (success) {
                            Toast.makeText(context, context.getString(R.string.toast_cast_success), Toast.LENGTH_SHORT).show()
                            sheet.onCastSuccess()
                            onDismiss()
                        } else {
                            Toast.makeText(context, if (msg.isNullOrEmpty()) context.getString(R.string.toast_cast_failed) else msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SheetPanel(width = playerDim(R.dimen.vs_520)) {
                Spacer(Modifier.height(playerDim(R.dimen.vs_24)))
                SheetTitle(stringResource(R.string.cast_title))
                Spacer(Modifier.height(playerDim(R.dimen.vs_15)))
                Text(
                    text = stringResource(R.string.cast_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = playerTextSize(R.dimen.ts_18),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = playerDim(R.dimen.vs_30)),
                )
                Spacer(Modifier.height(playerDim(R.dimen.vs_20)))
                Box(
                    Modifier
                        .padding(horizontal = playerDim(R.dimen.vs_30))
                        .fillMaxWidth()
                        .height(playerDim(R.dimen.vs_200)),
                ) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10))) {
                        itemsIndexed(deviceList) { _, device ->
                            SheetButton(
                                text = "${device.name}  ${if (device.type == CastDevice.TYPE_DLNA) "DLNA  " else "TVBox  "}${device.id}",
                                onClick = { castToDevice(device) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (!canScan) {
                        Text(
                            text = stringResource(R.string.cast_permission_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = playerTextSize(R.dimen.ts_20),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else if (deviceList.isEmpty() && !searchFinished) {
                        // 与首页(HomePage 整页加载态)同款的 M3 expressive 几何加载指示器 + 同尺寸 64dp:
                        // 取代原先的 CircularProgressIndicator(SheetLoading),口径见 UI spec「加载指示器」
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ContainedLoadingIndicator(Modifier.size(64.dp))
                            Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                            Text(
                                text = stringResource(R.string.cast_searching),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = playerTextSize(R.dimen.ts_20),
                            )
                        }
                    } else if (deviceList.isEmpty() && searchFinished) {
                        Text(
                            text = stringResource(R.string.cast_no_device),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = playerTextSize(R.dimen.ts_20),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_20)))
                Row(
                    Modifier
                        .padding(horizontal = playerDim(R.dimen.vs_30))
                        .fillMaxWidth()
                        .height(playerDim(R.dimen.vs_50)),
                    horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_20)),
                ) {
                    SheetButton(text = stringResource(R.string.common_refresh), onClick = {
                        devices.clear()
                        deviceList = emptyList()
                        if (PermissionHelper.isLocalNetworkGranted(context)) {
                            applyGranted(true)
                        } else {
                            val act = activity
                            if (act != null) {
                                PermissionHelper.requestLocalNetwork(act) { granted, _ ->
                                    applyGranted(!granted.isNullOrEmpty())
                                }
                            }
                        }
                    }, modifier = Modifier.weight(1f))
                    SheetButton(text = stringResource(R.string.common_cancel), onClick = { onDismiss() }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_24)))
            }
        }
    }
}
