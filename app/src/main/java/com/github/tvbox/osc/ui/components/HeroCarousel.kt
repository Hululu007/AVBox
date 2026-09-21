package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import kotlin.math.abs

private const val HERO_PAGES_PER_SET = 100_000

/** 18% 是手机档的视觉比例;宽屏下不设上限会让左右留白大到看不见内容 */
private val HeroMaxSidePad = 96.dp

/** Hero 宽度上限:不封顶时它会按 1.5 宽高比撑满整屏,并把相邻页挤成一条"黑边"(真机实测仅 6.8dp 宽) */
private val HeroMaxWidth = 640.dp

/** Hero 高度上限:与宽度上限共同约束,宽屏下高度约 340dp(未封顶时实测 544dp,占屏高 72%) */
private val HeroMaxHeight = 340.dp

@Composable
fun HeroCarousel(
    videos: List<Movie.Video>,
    onCardClick: (Movie.Video) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (videos.isEmpty()) return
    val n = videos.size
    val sidePad = (LocalConfiguration.current.screenWidthDp.dp * 0.18f).coerceAtMost(HeroMaxSidePad)
    val pagerState = rememberPagerState(
        initialPage = n * (HERO_PAGES_PER_SET / 2),
        pageCount = { n * HERO_PAGES_PER_SET },
    )

    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = sidePad),
        pageSpacing = 12.dp,
    ) { page ->
        val video = videos[page % n]
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 宽屏下封顶并居中。不封顶有两宗罪:①按 1.5 宽高比撑满整屏;
                // ②相邻页缩放后边缘内移量随宽度变大,只从左侧缝里露出几 dp,看着像一条随机黑条
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = HeroMaxWidth)
                .aspectRatio(1.5f)
                .heightIn(max = HeroMaxHeight)
                .graphicsLayer {
                    val pageOffset =
                        (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    val d = abs(pageOffset).coerceIn(0f, 1f)
                    scaleX = 1f - 0.18f * d
                    scaleY = 1f - 0.18f * d
                    alpha = 1f - 0.25f * d
                }
                .clip(RoundedCornerShape(24.dp))
                .clickable { onCardClick(video) },
        ) {
            AsyncImage(
                model = video.pic,
                contentDescription = video.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.4f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.6f),
                        )
                    ),
            )
            Text(
                text = stringResource(R.string.home_hot_recommend),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            ) {
                Text(
                    text = video.name ?: "",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = ratingBadgeText(video.note)
                if (!sub.isNullOrBlank()) {
                    Text(
                        text = if (sub != video.note?.trim()) stringResource(R.string.detail_rating, sub) else sub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
