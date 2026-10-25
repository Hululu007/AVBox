package com.github.avbox.osc.ui.components

internal class PosterSeedCache(private val capacity: Int = 32) {

    private val seeds = object : LinkedHashMap<String, Int?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int?>?): Boolean = size > capacity
    }

    fun put(key: String, seed: Int?) {
        seeds[key] = seed
    }

    fun get(key: String): Int? = seeds[key]

    fun has(key: String): Boolean = seeds.containsKey(key)
}
