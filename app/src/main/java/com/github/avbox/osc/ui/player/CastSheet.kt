@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.avbox.osc.ui.player

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.R
import com.github.avbox.osc.dlna.CastDevice
import com.github.avbox.osc.dlna.DLNACastManager
import com.github.avbox.osc.player.state.CastSheetState
import com.github.avbox.osc.player.thirdparty.PlayerCatalog
import com.github.avbox.osc.player.thirdparty.RemoteTVBox
import com.github.avbox.osc.ui.theme.AVBoxTheme
import com.github.avbox.osc.util.PermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CastSheet(sheet: CastSheetState, onDismiss: () -> Unit) {
    AVBoxTheme(manageStatusBarIcons = false) {
        CastSheetContent(sheet, onDismiss)
    }
}

@Composable
private fun CastSheetContent(sheet: CastSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivityOrNull() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var searchFinished by remember { mutableStateOf(false) }
    var scanToken by remember { mutableIntStateOf(1) }
    var canScan by remember { mutableStateOf(PermissionHelper.isLocalNetworkGranted(context)) }
    val dlnaDevices by DLNACastManager.get().devices.collectAsState()
    var tvboxDevices by remember { mutableStateOf(emptyList<CastDevice>()) }
    val deviceList = remember(dlnaDevices, tvboxDevices) { (dlnaDevices + tvboxDevices).distinct() }

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
        DLNACastManager.get().init(context)
        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            DLNACastManager.get().release(context)
        }
    }

    LaunchedEffect(scanToken, canScan) {
        if (!canScan) return@LaunchedEffect
        launch {
            delay(1000)
            DLNACastManager.get().search()
        }
        launch {
            RemoteTVBox.searchAvailable().collect { host ->
                if (RemoteTVBox.avalible == null) {
                    RemoteTVBox.avalible = host
                    PlayerCatalog.invalidateCache()
                }
                tvboxDevices = tvboxDevices + CastDevice.tvbox(host)
            }
        }
        delay(15000)
        searchFinished = true
    }

    val castToDevice: (CastDevice, () -> Unit) -> Unit = { device, dismiss ->
        if (device.type == CastDevice.TYPE_TVBOX) {
            try {
                val headers = sheet.video.headers
                val url = if (headers.isEmpty()) sheet.video.url
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
                                RemoteTVBox.avalible = requireNotNull(device.id)
                                PlayerCatalog.invalidateCache()
                                Toast.makeText(context, context.getString(R.string.toast_cast_success), Toast.LENGTH_SHORT).show()
                                sheet.onCastSuccess()
                                dismiss()
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
                            dismiss()
                        } else {
                            Toast.makeText(context, if (msg.isNullOrEmpty()) context.getString(R.string.toast_cast_failed) else msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
        }
    }

    PlayerDialog(onDismiss = onDismiss) {
        val dismiss = LocalPlayerSheetDismiss.current
        SheetPanel(width = playerDim(R.dimen.vs_520)) {
                Spacer(Modifier.height(playerDim(R.dimen.vs_24)))
                SheetTitle(stringResource(R.string.cast_title))
                Spacer(Modifier.height(playerDim(R.dimen.vs_15)))
                Text(
                    text = stringResource(R.string.cast_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = playerTextSize(R.dimen.ts_18),
                    fontWeight = FontWeight.Medium,
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
                        items(deviceList, key = { it.type.toString() + ":" + it.id }) { device ->
                            SheetButton(
                                text = "${device.name}  ${if (device.type == CastDevice.TYPE_DLNA) "DLNA  " else "TVBox  "}${device.id}",
                                onClick = { castToDevice(device, dismiss) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (!canScan) {
                        Text(
                            text = stringResource(R.string.cast_permission_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = playerTextSize(R.dimen.ts_20),
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else if (deviceList.isEmpty() && !searchFinished) {
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
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    } else if (deviceList.isEmpty() && searchFinished) {
                        Text(
                            text = stringResource(R.string.cast_no_device),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = playerTextSize(R.dimen.ts_20),
                            fontWeight = FontWeight.Medium,
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
                        tvboxDevices = emptyList()
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
                    SheetButton(text = stringResource(R.string.common_cancel), onClick = dismiss, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_24)))
            }
    }
}
