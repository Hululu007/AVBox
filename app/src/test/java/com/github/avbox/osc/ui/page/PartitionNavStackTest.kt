package com.github.avbox.osc.ui.page

import com.github.avbox.osc.bean.MovieSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionNavStackTest {

    private fun target(id: String, sourceKey: String? = "src", title: String = id) =
        PartitionNavStack.Target(MovieSort.SortData(id, title), sourceKey, title)

    @Test
    fun pushThenPopReturnsPreviousLevel() {
        val stack = PartitionNavStack()
        assertTrue(stack.push(target("a")))
        assertTrue(stack.isRoot())
        assertTrue(stack.push(target("b")))
        assertFalse(stack.isRoot())
        assertEquals("b", stack.current()?.sort?.id)
        assertEquals("a", stack.pop()?.sort?.id)
        assertTrue(stack.isRoot())
        assertEquals("a", stack.current()?.sort?.id)
    }

    @Test
    fun firstLevelIsNotPoppable() {
        val stack = PartitionNavStack()
        stack.push(target("a"))
        assertNull(stack.pop())
        assertEquals("a", stack.current()?.sort?.id)
    }

    @Test
    fun sameIdAndSourceIsNotPushedTwice() {
        val stack = PartitionNavStack()
        assertTrue(stack.push(target("a")))
        assertFalse(stack.push(target("a")))
        assertNull(stack.pop())
    }

    @Test
    fun sameIdWithAnotherSourceIsANewLevel() {
        val stack = PartitionNavStack()
        assertTrue(stack.push(target("a", sourceKey = "src1")))
        assertTrue(stack.push(target("a", sourceKey = "src2")))
        assertEquals("src1", stack.pop()?.sourceKey)
    }

    @Test
    fun emptyStackHasNoCurrent() {
        val stack = PartitionNavStack()
        assertTrue(stack.isEmpty())
        assertNull(stack.current())
        assertNull(stack.pop())
    }
}
