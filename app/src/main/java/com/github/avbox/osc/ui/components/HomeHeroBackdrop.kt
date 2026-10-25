package com.github.avbox.osc.ui.components

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

private const val HOME_HERO_BACKDROP_SCALE = 1.3f

private const val HOME_HERO_BACKDROP_TOP_SCRIM = 0.54f

private const val HOME_HERO_BACKDROP_MIN_SCRIM = 0.6f

private val HomeHeroBackdropBlurRadius = 40.dp

private val HomeHeroBackdropRevealStops = arrayOf(
    0.33f to 0.86f,
    1f to 0.84f,
)

internal val homeHeroBackdropSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
internal fun HomeHeroBackdrop(
    pic: String,
    scrimColor: Color,
    modifier: Modifier = Modifier,
) {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val heroFraction = heroSpotlightHeight(screenHeightDp).value / screenHeightDp
    val scrimStops = remember(heroFraction) {
        arrayOf(0f to HOME_HERO_BACKDROP_TOP_SCRIM) +
            HeroSpotlightFadeStops.map { (position, alpha) ->
                position * heroFraction to alpha.coerceAtLeast(HOME_HERO_BACKDROP_MIN_SCRIM)
            } +
            HomeHeroBackdropRevealStops.map { (position, alpha) ->
                heroFraction + (1f - heroFraction) * position to alpha
            }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        Crossfade(
            targetState = pic,
            animationSpec = tween(200),
            label = "homeHeroBackdrop",
        ) { target ->
            if (target.isNotEmpty()) {
                VodPoster(
                    name = null,
                    pic = target,
                    size = PosterSize.LARGE,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = HOME_HERO_BACKDROP_SCALE
                            scaleY = HOME_HERO_BACKDROP_SCALE
                        }
                        .blur(HomeHeroBackdropBlurRadius),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        *scrimStops
                            .map { (position, alpha) -> position to scrimColor.copy(alpha = alpha) }
                            .toTypedArray(),
                    ),
                ),
        )
    }
}
