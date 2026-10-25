package com.github.avbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.Image
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

private const val BackdropScale = 1.3f

private val BackdropBlurRadius = 40.dp

private val BackdropScrimAlphas = listOf(0.22f, 0.48f, 0.80f)

@Composable
internal fun PosterBackdrop(
    pic: String?,
    modifier: Modifier = Modifier,
    onImage: ((Image) -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (!pic.isNullOrEmpty()) {
            AsyncImage(
                model = pic,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Success) onImage?.invoke(state.result.image)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = BackdropScale
                        scaleY = BackdropScale
                    }
                    .blur(BackdropBlurRadius),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = BackdropScrimAlphas.map {
                            MaterialTheme.colorScheme.surface.copy(alpha = it)
                        },
                    ),
                ),
        )
    }
}
