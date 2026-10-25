package com.github.avbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.Image
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transitionFactory
import coil3.transition.Transition
import com.github.avbox.osc.bean.TMDB_SOURCE_KEY
import com.github.avbox.osc.bean.TmdbPosterCache
import com.github.avbox.osc.util.VodImages
import com.github.avbox.osc.util.TmdbImages

private const val UnnamedPosterKey = "！"

internal val LocalTmdbPosterLookup = staticCompositionLocalOf<(String?, Int, String?) -> String?> {
    { _, _, _ -> null }
}

@Composable
internal fun ProvideTmdbPosterLookup(cache: TmdbPosterCache, content: @Composable () -> Unit) {
    val lookup = remember(cache) {
        { name: String?, year: Int, altName: String? -> cache.tmdbCachedPoster(name, year, altName) }
    }
    CompositionLocalProvider(LocalTmdbPosterLookup provides lookup, content = content)
}

internal enum class PosterSize { THUMB, LARGE, WIDE, FULL }

internal const val HERO_IMAGE_LARGE_MAX_WIDTH_PX = 800

internal const val HERO_IMAGE_WIDE_MAX_WIDTH_PX = 1400

internal data class HeroImageSizes(val poster: PosterSize, val backdrop: PosterSize)

internal fun heroPosterSize(widthPx: Int): PosterSize =
    if (widthPx <= HERO_IMAGE_LARGE_MAX_WIDTH_PX) PosterSize.LARGE else PosterSize.FULL

internal fun heroBackdropSize(widthPx: Int): PosterSize = when {
    widthPx <= HERO_IMAGE_LARGE_MAX_WIDTH_PX -> PosterSize.LARGE
    widthPx <= HERO_IMAGE_WIDE_MAX_WIDTH_PX -> PosterSize.WIDE
    else -> PosterSize.FULL
}

@Composable
internal fun rememberHeroImageSizes(): HeroImageSizes {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val widthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    return remember(widthPx) {
        HeroImageSizes(heroPosterSize(widthPx), heroBackdropSize(widthPx))
    }
}

private const val POSTER_CHAR_WIDTH_RATIO = 0.46f

private val PosterPalette = intArrayOf(
    0xFFEF5350.toInt(),
    0xFFEC407A.toInt(),
    0xFFAB47BC.toInt(),
    0xFF7E57C2.toInt(),
    0xFF5C6BC0.toInt(),
    0xFF42A5F5.toInt(),
    0xFF29B6F6.toInt(),
    0xFF26C6DA.toInt(),
    0xFF26A69A.toInt(),
    0xFF66BB6A.toInt(),
    0xFF9CCC65.toInt(),
    0xFFD4E157.toInt(),
    0xFFFFEE58.toInt(),
    0xFFFFCA28.toInt(),
    0xFFFFA726.toInt(),
    0xFFFF7043.toInt(),
    0xFF8D6E63.toInt(),
    0xFFBDBDBD.toInt(),
    0xFF78909C.toInt(),
)

internal fun posterSeedColor(name: String?): Int {
    val key = posterFirstChar(name)
    return PosterPalette[(key.hashCode() and Int.MAX_VALUE) % PosterPalette.size]
}

internal fun posterFirstChar(name: String?): String {
    val text = name?.trim().orEmpty()
    if (text.isEmpty()) return UnnamedPosterKey
    return text.take(if (text[0].isHighSurrogate()) 2 else 1)
}

@Composable
internal fun VodPoster(
    name: String?,
    pic: String?,
    modifier: Modifier = Modifier,
    size: PosterSize = PosterSize.THUMB,
    year: Int = 0,
    sourceKey: String? = null,
    resolveTmdb: Boolean = true,
    tmdbCacheOnly: Boolean = false,
    altName: String? = null,
    tmdbPath: String? = null,
    onImage: ((Image) -> Unit)? = null,
) {
    val preferLarge = size != PosterSize.THUMB
    val tmdbUrl = if (resolveTmdb && !name.isNullOrBlank() && sourceKey != TMDB_SOURCE_KEY) {
        val path = tmdbPath ?: if (tmdbCacheOnly) LocalTmdbPosterLookup.current(name, year, altName) else null
        path?.takeIf { it.isNotEmpty() }?.let { tmdbUrlOf(it, size) }
    } else {
        null
    }
    val siteUrl = if (preferLarge) VodImages.largePosterUrl(pic) else pic
    var showFallback by remember(pic) { mutableStateOf(false) }
    var posterUrl by remember(pic, siteUrl, tmdbUrl) {
        mutableStateOf(tmdbUrl ?: siteUrl)
    }
    val context = LocalPlatformContext.current
    val request = remember(context, posterUrl, preferLarge) {
        ImageRequest.Builder(context)
            .data(posterUrl)
            .apply {
                if (preferLarge) crossfade(true) else transitionFactory(Transition.Factory.NONE)
            }
            .build()
    }
    val baseRequest = remember(context, pic) {
        ImageRequest.Builder(context)
            .data(pic)
            .transitionFactory(Transition.Factory.NONE)
            .build()
    }
    val baseUnderlay = preferLarge && !pic.isNullOrEmpty() && posterUrl != pic && !showFallback

    Box(modifier = modifier) {
        if (showFallback) PosterFallback(name)
        if (baseUnderlay) {
            AsyncImage(
                model = baseRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AsyncImage(
            model = request,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            onState = { state ->
                if (state is AsyncImagePainter.State.Error) {
                    val fallback = when {
                        tmdbUrl != null && posterUrl == tmdbUrl -> siteUrl
                        posterUrl != pic -> pic
                        else -> null
                    }
                    if (fallback != null && fallback != posterUrl) posterUrl = fallback else showFallback = true
                } else {
                    showFallback = false
                    if (state is AsyncImagePainter.State.Success) onImage?.invoke(state.result.image)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

internal fun tmdbUrlOf(posterPath: String, size: PosterSize): String = when (size) {
    PosterSize.THUMB -> TmdbImages.imageUrl(posterPath, false)
    PosterSize.LARGE -> TmdbImages.imageUrl(posterPath, true)
    PosterSize.WIDE -> TmdbImages.backdropUrl(posterPath)
    PosterSize.FULL -> TmdbImages.fullPosterUrl(posterPath)
}

@Composable
private fun PosterFallback(name: String?) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(posterSeedColor(name))),
        contentAlignment = Alignment.Center,
    ) {
        val charHeight = minOf(maxWidth, maxHeight) * POSTER_CHAR_WIDTH_RATIO
        val charSize = with(LocalDensity.current) { charHeight.toSp() }
        Text(
            text = posterFirstChar(name),
            style = TextStyle(
                color = Color.White,
                fontSize = charSize,
                lineHeight = charSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            maxLines = 1,
            modifier = Modifier.padding(2.dp),
        )
    }
}
