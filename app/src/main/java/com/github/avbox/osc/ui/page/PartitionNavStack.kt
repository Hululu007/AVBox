package com.github.avbox.osc.ui.page

import com.github.avbox.osc.bean.MovieSort

internal class PartitionNavStack {

    internal data class Target(
        val sort: MovieSort.SortData,
        val sourceKey: String?,
        val title: String,
    )

    private val stack = ArrayList<Target>()

    fun current(): Target? = stack.lastOrNull()

    fun isEmpty(): Boolean = stack.isEmpty()

    fun isRoot(): Boolean = stack.size <= 1

    fun push(target: Target): Boolean {
        val top = stack.lastOrNull()
        if (top != null && top.sort.id == target.sort.id && top.sourceKey == target.sourceKey) {
            return false
        }
        stack.add(target)
        return true
    }

    fun pop(): Target? {
        if (stack.size <= 1) return null
        stack.removeAt(stack.lastIndex)
        return stack.last()
    }
}
