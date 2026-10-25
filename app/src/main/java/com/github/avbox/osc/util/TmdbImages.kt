package com.github.avbox.osc.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TmdbImages {

    private const val POSTER_SIZE_SMALL = "w342"
    private const val POSTER_SIZE_LARGE = "w780"
    private const val POSTER_SIZE_FULL = "original"
    private const val BACKDROP_SIZE_LARGE = "w1280"
    private const val PROFILE_SIZE = "w185"
    private const val STILL_SIZE = "w300"

    private val configEpochFlow = MutableStateFlow(0)

    val configEpoch: StateFlow<Int> = configEpochFlow.asStateFlow()

    fun notifyConfigChanged() {
        configEpochFlow.value = configEpochFlow.value + 1
    }

    fun isActive(): Boolean = try {
        KV.get(KVKeys.TMDB_ENABLE, false) && KV.get(KVKeys.TMDB_API_KEY, "").isNotBlank()
    } catch (e: Exception) {
        AppLog.e("TmdbImages", e)
        false
    }

    fun imageBaseUrl(configured: String): String =
        configured.trim().trimEnd('/').ifEmpty { KVKeys.TMDB_IMAGE_BASE_DEFAULT }

    fun imageUrl(configuredImageBase: String, posterPath: String, large: Boolean): String =
        imageBaseUrl(configuredImageBase) + "/" + (if (large) POSTER_SIZE_LARGE else POSTER_SIZE_SMALL) + posterPath

    fun fullPosterUrl(configuredImageBase: String, posterPath: String): String =
        imageBaseUrl(configuredImageBase) + "/" + POSTER_SIZE_FULL + posterPath

    fun backdropUrl(configuredImageBase: String, backdropPath: String): String =
        imageBaseUrl(configuredImageBase) + "/" + BACKDROP_SIZE_LARGE + backdropPath

    fun profileUrl(configuredImageBase: String, profilePath: String): String =
        imageBaseUrl(configuredImageBase) + "/" + PROFILE_SIZE + profilePath

    fun stillUrl(configuredImageBase: String, stillPath: String): String =
        imageBaseUrl(configuredImageBase) + "/" + STILL_SIZE + stillPath

    fun logoUrl(configuredImageBase: String, logoPath: String): String =
        imageBaseUrl(configuredImageBase) + "/" + POSTER_SIZE_FULL + logoPath

    fun imageUrl(posterPath: String, large: Boolean): String =
        imageUrl(readImageBase(), posterPath, large)

    fun fullPosterUrl(posterPath: String): String =
        fullPosterUrl(readImageBase(), posterPath)

    fun backdropUrl(backdropPath: String): String =
        backdropUrl(readImageBase(), backdropPath)

    fun profileUrl(profilePath: String): String =
        profileUrl(readImageBase(), profilePath)

    fun stillUrl(stillPath: String): String =
        stillUrl(readImageBase(), stillPath)

    fun logoUrl(logoPath: String): String =
        logoUrl(readImageBase(), logoPath)

    private fun readImageBase(): String = try {
        KV.get(KVKeys.TMDB_IMAGE_BASE, "")
    } catch (e: Exception) {
        AppLog.e("TmdbImages", e)
        ""
    }
}
