package com.github.avbox.osc.player.effect

import com.github.avbox.osc.player.effect.anime4k.Anime4kEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureEffectsEffectListTest {

    @Test
    fun disabledAnime4k_isNotHandedToTheKernel() {
        val effects = PictureEffects.effectsFor(anime4kEnabled = false)
        assertEquals(2, effects.size)
        assertFalse(effects.any { it is Anime4kEffect })
    }

    @Test
    fun enabledAnime4k_isHandedToTheKernel() {
        val effects = PictureEffects.effectsFor(anime4kEnabled = true)
        assertEquals(3, effects.size)
        assertTrue(effects.any { it is Anime4kEffect })
    }

    @Test
    fun bothLists_shareTheSameEffectInstances() {
        val without = PictureEffects.effectsFor(anime4kEnabled = false)
        val with = PictureEffects.effectsFor(anime4kEnabled = true)
        assertTrue(with.containsAll(without))
    }
}
