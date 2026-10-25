package com.github.avbox.osc.player

object ZeroStartGate {

    const val MAX_KEYS = 64

    fun mark(pending: MutableSet<String>, keys: List<String>): Boolean {
        var changed = false
        for (key in keys) {
            if (key.isEmpty()) continue
            if (pending.add(key)) changed = true
        }
        while (pending.size > MAX_KEYS) {
            val oldest = pending.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
            changed = true
        }
        return changed
    }

    fun consume(pending: MutableSet<String>, key: String?): Boolean {
        if (key.isNullOrEmpty()) return false
        return pending.remove(key)
    }
}
