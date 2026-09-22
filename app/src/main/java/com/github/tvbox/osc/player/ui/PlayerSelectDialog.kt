package com.github.tvbox.osc.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.SelectDialogState

/**
 * 播放器选择弹窗(倍速/画面尺寸/内核等列表选择):
 * 480mm 宽 M3 面板(surfaceContainer + 18dp 圆角)+ 标题 + 竖向列表(最高 vs_410),
 * 条目沿用 [SheetButton](surfaceBright 底 / 选中 primaryContainer)。
 * 行为:点击已选中项不响应、点击其他项回调后收起。
 */
@Composable
fun PlayerSelectDialog(
    dialogState: SelectDialogState,
    onDismiss: () -> Unit,
) {
    PlayerDialog(onDismiss = onDismiss) {
        val dismiss = LocalPlayerSheetDismiss.current
        SheetPanel(width = playerDim(R.dimen.vs_480)) {
            Spacer(Modifier.height(playerDim(R.dimen.vs_20)))
            SheetTitle(dialogState.tip)
            Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
            LazyColumn(
                Modifier
                    .padding(horizontal = playerDim(R.dimen.vs_30))
                    .heightIn(max = playerDim(R.dimen.vs_410)),
                verticalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10)),
            ) {
                items(dialogState.items.size) { index ->
                    SelectDialogItem(
                        text = dialogState.items[index],
                        selected = index == dialogState.defaultIndex,
                        onClick = {
                            if (index != dialogState.defaultIndex) {
                                dialogState.onSelected(index)
                                dismiss()
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
        }
    }
}

@Composable
private fun SelectDialogItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SheetButton(
        text = text,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}