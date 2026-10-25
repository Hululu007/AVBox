package com.github.avbox.osc.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle

internal val ContinuousSheetTop = ContinuousRoundedRectangle(
    topStart = CornerSize(36.dp),
    topEnd = CornerSize(36.dp),
    bottomEnd = CornerSize(0.dp),
    bottomStart = CornerSize(0.dp),
)

internal val ContinuousSheetStart = ContinuousRoundedRectangle(
    topStart = CornerSize(36.dp),
    topEnd = CornerSize(0.dp),
    bottomEnd = CornerSize(0.dp),
    bottomStart = CornerSize(36.dp),
)

internal val ContinuousDialog = ContinuousRoundedRectangle(32.dp)

internal val ContinuousMenuContainer = ContinuousRoundedRectangle(22.dp)

internal val ContinuousMenuItem = ContinuousRoundedRectangle(18.dp)

internal val ContinuousPlayerPanel = ContinuousRoundedRectangle(32.dp)
