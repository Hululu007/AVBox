package com.github.avbox.osc.ui.components

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShimmerBandTest {

    private fun spanU(width: Float, height: Float, progress: Float): ClosedFloatingPointRange<Float> {
        val diagonal = hypot(width, height)
        val startU = shimmerStartX(width, height, progress) * width / diagonal
        return startU..(startU + diagonal)
    }

    private fun assertClearsBoxAtBothEnds(width: Float, height: Float) {
        val diagonal = hypot(width, height)
        val tolerance = 0.5f
        val entering = spanU(width, height, 0f)
        assertTrue(
            "progress=0 高光应整体在框外(左上), 实际末端 ${entering.endInclusive} > 0",
            entering.endInclusive <= tolerance,
        )
        val leaving = spanU(width, height, 1f)
        assertTrue(
            "progress=1 高光应整体在框外(右下), 实际首端 ${leaving.start} < 对角线 $diagonal",
            leaving.start >= diagonal - tolerance,
        )
    }

    @Test
    fun posterAspectClearsBoxAtBothEnds() = assertClearsBoxAtBothEnds(200f, 300f)

    @Test
    fun everyAspectClearsBoxAtBothEnds() {
        listOf(100f to 100f, 200f to 300f, 300f to 400f, 300f to 200f, 100f to 560f)
            .forEach { (width, height) -> assertClearsBoxAtBothEnds(width, height) }
    }

    @Test
    fun midCyclePatternCoversBoxExactly() {
        val width = 200f
        val height = 300f
        val span = spanU(width, height, 0.5f)
        assertEquals(0f, span.start, 0.5f)
        assertEquals(hypot(width, height), span.endInclusive, 0.5f)
    }

    @Test
    fun sweepAdvancesMonotonically() {
        val width = 200f
        val height = 300f
        val diagonal = hypot(width, height)
        val positions = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
            .map { shimmerStartX(width, height, it) * width / diagonal }
        assertTrue(positions.zipWithNext().all { (previous, next) -> next > previous })
    }

    @Test
    fun degenerateSizeStaysFinite() {
        assertTrue(shimmerStartX(0f, 0f, 0.5f).isFinite())
        assertTrue(shimmerStartX(0f, 300f, 0.5f).isFinite())
    }
}
