package com.github.tvbox.osc.ui.page

import com.github.tvbox.osc.api.AppBootstrap
import com.github.tvbox.osc.api.BootGeneration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootGenerationTest {

    @Test
    fun onlyLatestGenerationIsAccepted() {
        val generation = BootGeneration()
        val first = generation.next()
        assertTrue(generation.isLatest(first))
        val second = generation.next()
        assertFalse(generation.isLatest(first))
        assertTrue(generation.isLatest(second))
    }

    @Test
    fun everyInitProducesDistinctReadyValue() {
        val generation = BootGeneration()
        val first = AppBootstrap.Boot.Ready(generation.next())
        val second = AppBootstrap.Boot.Ready(generation.next())
        assertFalse(first == second)
    }

    @Test
    fun identicalReadyValuesAreConflated() = runBlocking {
        val state = MutableStateFlow<AppBootstrap.Boot>(AppBootstrap.Boot.Ready(1))
        val seen = mutableListOf<AppBootstrap.Boot>()
        val collector = launch { state.collect { seen.add(it) } }
        yield()
        state.value = AppBootstrap.Boot.Ready(1)
        yield()
        state.value = AppBootstrap.Boot.Ready(2)
        yield()
        collector.cancel()
        assertEquals(2, seen.size)
    }

    @Test
    fun staleInitDoesNotPublishReady() = runBlocking {
        val generation = BootGeneration()
        val state = MutableStateFlow<AppBootstrap.Boot>(AppBootstrap.Boot.Loading)
        val seen = mutableListOf<AppBootstrap.Boot>()
        val collector = launch { state.collect { seen.add(it) } }
        yield()
        val stale = generation.next()
        val latest = generation.next()
        if (generation.isLatest(stale)) state.value = AppBootstrap.Boot.Ready(stale)
        yield()
        if (generation.isLatest(latest)) state.value = AppBootstrap.Boot.Ready(latest)
        yield()
        collector.cancel()
        assertEquals(2, seen.size)
        assertEquals(AppBootstrap.Boot.Ready(latest), seen.last())
    }
}
