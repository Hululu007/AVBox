package com.github.avbox.osc.bean

interface TmdbPosterCache {

    fun tmdbCachedPoster(name: String?, year: Int, altName: String?): String?
}
