package com.github.avbox.osc.sourcedata

import com.github.avbox.osc.bean.Movie
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SortLoaderActionVideoTest {

    @Suppress("UNCHECKED_CAST")
    private fun field(target: Any, name: String): Any {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target) as Any
    }

    private fun hasActionVideo(sortLoader: Any, videos: List<Movie.Video?>): Boolean {
        val m = sortLoader.javaClass.getDeclaredMethod("hasActionVideo", List::class.java)
        m.isAccessible = true
        return m.invoke(sortLoader, videos) as Boolean
    }

    private fun sortLoader(): Any = field(SourceViewModel(), "sortLoader")

    private fun video(action: String?): Movie.Video {
        val video = Movie.Video()
        video.action = action
        return video
    }

    @Test
    fun nullElementIsSkippedInsteadOfCrashing() {
        assertFalse("null 元素应被跳过", hasActionVideo(sortLoader(), listOf(null, video(null))))
        assertTrue("null 元素之后的 action 仍要认出来", hasActionVideo(sortLoader(), listOf(null, video("action"))))
    }

    @Test
    fun actionIsDetectedOnlyWhenPresent() {
        assertFalse(hasActionVideo(sortLoader(), listOf(video(null))))
        assertTrue(hasActionVideo(sortLoader(), listOf(video("action"))))
    }
}
