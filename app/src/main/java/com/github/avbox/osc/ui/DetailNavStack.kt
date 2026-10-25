package com.github.avbox.osc.ui

internal class DetailNavStack {

    internal data class Target(
        val vodId: String,
        val sourceKey: String,
        val title: String,
        val picture: String,
        val fromCollect: Boolean,
    )

    private val stack = ArrayList<Target>()

    fun push(target: Target): Boolean {
        val top = stack.lastOrNull()
        if (top != null &&
            top.vodId == target.vodId &&
            top.sourceKey == target.sourceKey &&
            top.title == target.title
        ) {
            return false
        }
        stack.add(target)
        while (stack.size > MAX_DEPTH) {
            stack.removeAt(0)
        }
        return true
    }

    fun pop(): Target? {
        if (stack.size <= 1) return null
        stack.removeAt(stack.lastIndex)
        return stack.last()
    }

    companion object {
        private const val MAX_DEPTH = 1
    }
}
