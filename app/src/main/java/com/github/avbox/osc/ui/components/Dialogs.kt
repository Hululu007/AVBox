package com.github.avbox.osc.ui.components

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
