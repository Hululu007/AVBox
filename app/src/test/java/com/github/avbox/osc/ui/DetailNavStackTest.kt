package com.github.avbox.osc.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailNavStackTest {

    private fun target(id: String, key: String = "src", title: String = "片$id") =
        DetailNavStack.Target(vodId = id, sourceKey = key, title = title, picture = "", fromCollect = false)

    @Test
    fun pushKeepsOnlyCurrentTarget() {
        val stack = DetailNavStack()
        assertTrue(stack.push(target("a")))
        assertTrue(stack.push(target("b")))
        assertNull(stack.pop())
    }

    @Test
    fun pushIgnoresSameTargetOnTop() {
        val stack = DetailNavStack()
        assertTrue(stack.push(target("a")))
        assertFalse(stack.push(target("a")))
        assertTrue(stack.push(target("a", key = "src2")))
        assertTrue(stack.push(target("a", key = "src2", title = "同片换名")))
    }

    @Test
    fun popNeverReturnsPrevious() {
        val stack = DetailNavStack()
        assertNull(stack.pop())
        stack.push(target("a"))
        assertNull(stack.pop())
    }

    @Test
    fun emptyIdCardsUseTitle() {
        val stack = DetailNavStack()
        assertTrue(stack.push(target("", title = "片甲")))
        assertFalse(stack.push(target("", title = "片甲")))
        assertTrue(stack.push(target("", title = "片乙")))
    }
}
