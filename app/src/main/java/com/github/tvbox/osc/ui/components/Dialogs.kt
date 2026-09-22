package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 居中对话框 —— 走应用窗口内覆盖层,不是平台 dialog window(与 [AVBoxBottomSheet] 同一套宿主路由/遮罩/动画)。
 *
 * 相比 M3 `AlertDialog`(平台窗口,瞬现瞬消、遮罩不可控):
 * ① 入场 = 0.90 → 1.0 缩放 + 淡入(220ms),退场同款,遮罩与面板同一进度;
 * ② 键盘弹起时面板自己上移(覆盖层没有 dialog window 帮忙避让 IME);
 * ③ 页面在被裁剪容器里(主页 pager 的 tab)时,自动改在窗口根渲染,不会被裁掉。
 *
 * 关闭入口:[LocalSheetDismiss] = 播完退场动画再 `onDismissRequest`;
 * [LocalSheetDismissThen] = 播完退场动画再执行确认动作(动作自己负责清宿主状态)。
 */
@Composable
fun AVBoxDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    isScrollable: Boolean = true,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) = OverlayRequest(
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    containerColor = containerColor,
    isScrollable = isScrollable,
    variant = SheetVariant.CENTER,
    dismissible = dismissible,
    content = content,
)

/**
 * 观感对齐 M3 `AlertDialog`(形状/配色/内边距/按钮排布照抄 `AlertDialogDefaults` 与 `AlertDialogContent`),
 * 但渲染在应用窗口内的 [AVBoxDialog] 里 —— 于是有了出入场动画,遮罩也与面板同步。
 *
 * 参数与 M3 同名同义,调用点基本只需改函数名;按钮里的关闭动作请用
 * [LocalSheetDismiss](只关闭)或 [LocalSheetDismissThen](先执行动作再关闭)。
 *
 * [dismissible] = false 用于"必须二选一"的阻断式弹窗(如启动失败):点遮罩/返回键**不关闭**
 * —— 它们仍会照常回调 `onDismissRequest`(这类弹窗传空实现),是否真的关闭由它决定,
 * 与旧平台 Dialog 传空回调完全一致;遮罩依旧吃触摸保持模态。
 * 面板内的动作按钮(重试/离线)不受影响 —— 它们走 [LocalSheetDismissThen],照常播退场并执行动作。
 */
@Composable
fun AVBoxAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    dismissible: Boolean = true,
) {
    AVBoxDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = AlertDialogDefaults.containerColor,
        isScrollable = true,
        dismissible = dismissible,
    ) {
        // ⚠️ 必须 fillMaxWidth:M3 的对话框靠 BasicAlertDialog 的 propagateMinConstraints 把 280dp 最小宽
        // 传进内容,按钮的 `align(End)` 才有"面板右侧"可对齐;我们的壳层 Surface 不传最小约束,内容会退化成
        // "只有文字那么宽的窄列",按钮就会贴到左边(2026-09-22 装机反馈)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DialogContentPadding),
        ) {
            icon?.let {
                CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.iconContentColor) {
                    Box(
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                            .align(Alignment.CenterHorizontally),
                        contentAlignment = Alignment.Center,
                    ) {
                        it()
                    }
                }
            }
            title?.let {
                CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                        Box(
                            // 有图标时标题居中,与 M3 一致
                            modifier = Modifier
                                .padding(bottom = 16.dp)
                                .align(if (icon == null) Alignment.Start else Alignment.CenterHorizontally),
                        ) {
                            it()
                        }
                    }
                }
            }
            text?.let {
                CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                        Box(
                            modifier = Modifier
                                .padding(bottom = 24.dp)
                                .align(Alignment.Start),
                        ) {
                            it()
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

private val DialogContentPadding = PaddingValues(24.dp)
