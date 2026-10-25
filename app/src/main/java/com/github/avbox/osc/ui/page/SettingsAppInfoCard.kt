package com.github.avbox.osc.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.avbox.osc.R

private val HeaderCardShape = RoundedCornerShape(32.dp)

private val VersionPillShape = RoundedCornerShape(24.dp)

private val AppBadgeSize = 76.dp

private const val AppIconGlyphScale = 1.26f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppInfoHeaderCard(versionName: String) {
    val scheme = MaterialTheme.colorScheme
    val contentColor = scheme.onPrimaryContainer
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HeaderCardShape)
            .background(scheme.primaryContainer)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(AppBadgeSize)
                    .clip(MaterialShapes.Cookie12Sided.toShape())
                    .background(scheme.surfaceBright),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier
                        .size(AppBadgeSize)
                        .scale(AppIconGlyphScale),
                )
            }
            Spacer(Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AudioVideoBox",
                    style = MaterialTheme.typography.headlineMedium,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                )
                Text(
                    text = stringResource(R.string.settings_app_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.75f),
                )
            }
        }
        VersionPill(versionText = versionName.ifEmpty { "-" })
    }
}

@Composable
private fun VersionPill(versionText: String) {
    val scheme = MaterialTheme.colorScheme
    val contentColor = scheme.onPrimaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VersionPillShape)
            .background(scheme.surfaceContainerLow)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_play_decode),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = stringResource(R.string.settings_app_version),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.75f),
            )
            Text(
                text = versionText,
                style = MaterialTheme.typography.titleLarge,
                fontSize = 16.sp,
                color = contentColor,
            )
        }
    }
}
