package com.github.avbox.osc.ui.page

import com.github.avbox.osc.bean.Movie
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodCardActionTest {

    private fun video(tag: String?): Movie.Video {
        val item = Movie.Video()
        item.tag = tag
        return item
    }

    @Test
    fun folderTagIsFolderCard() {
        assertTrue(video("folder").isFolderCard())
    }

    @Test
    fun missingOrOtherTagIsNotFolderCard() {
        assertFalse(video(null).isFolderCard())
        assertFalse(video("").isFolderCard())
        assertFalse(video("电影").isFolderCard())
        assertFalse(video("Folder").isFolderCard())
    }
}
