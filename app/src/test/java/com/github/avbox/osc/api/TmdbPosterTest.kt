package com.github.avbox.osc.api

import com.github.avbox.osc.bean.TmdbSearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbPosterTest {

    private fun hit(
        id: Int = 1,
        mediaType: String = "tv",
        title: String = "庆余年",
        originalTitle: String = "庆余年",
        year: Int = 2019,
        posterPath: String = "/p.jpg",
    ) = TmdbSearchHit(id, mediaType, title, originalTitle, year, posterPath)

    @Test
    fun bestHit_matchesQualityTagVariantTarget() {
        assertEquals(1, TmdbPoster.bestHit(listOf(hit(title = "兰香如故")), "兰香如故（臻彩视听）", 0)?.id)
        assertEquals(1, TmdbPoster.bestHit(listOf(hit(title = "兰香如故")), "兰香如故（臻彩）", 0)?.id)
    }

    @Test
    fun bestHit_keepsStrictEqualityForSemanticNames() {
        assertEquals(5, TmdbPoster.bestHit(listOf(hit(id = 5, title = "庆余年")), "庆余年", 0)?.id)
        assertNull(TmdbPoster.bestHit(listOf(hit(title = "西游记")), "西游记（动画版）", 0))
        assertNull(TmdbPoster.bestHit(listOf(hit(title = "人生大事")), "人生大事(下)", 0))
    }

    @Test
    fun bestHit_seasonHintRejectsMovieCandidate() {
        val hits = listOf(hit(id = 9, mediaType = "movie", title = "流人", originalTitle = "るにん"))
        assertNull(TmdbPoster.bestHit(hits, "流人第六季", 0))
        assertEquals(9, TmdbPoster.bestHit(hits, "流人", 0)?.id)
    }

    @Test
    fun nameCandidates_primaryThenAlt() {
        assertEquals(listOf("流人第六季", "Slow Horses"), TmdbPoster.nameCandidates("流人第六季", "Slow Horses"))
        assertEquals(listOf("流人第六季"), TmdbPoster.nameCandidates("流人第六季", null))
        assertEquals(listOf("流人第六季"), TmdbPoster.nameCandidates("流人第六季", " "))
        assertEquals(listOf("流人第六季"), TmdbPoster.nameCandidates("流人第六季", "流人第六季"))
        assertEquals(listOf("Slow Horses"), TmdbPoster.nameCandidates(null, "Slow Horses"))
        assertEquals(emptyList<String>(), TmdbPoster.nameCandidates(null, null))
    }

    @Test
    fun hitsValue_roundTripKeepsAllFields() {
        val hits = listOf(
            hit(),
            hit(id = 2, title = "庆余年之帝王业", originalTitle = "Qing Yu Nian", year = 0, posterPath = "/q.jpg"),
        )
        val encoded = TmdbPoster.encodeHitsValue(hits, now = 1000L)
        assertEquals(hits, TmdbPoster.decodeHitsValue(encoded, now = 2000L))
    }

    @Test
    fun hitsValue_emptyIsNegativeWithTtl() {
        val encoded = TmdbPoster.encodeHitsValue(emptyList(), now = 1000L)
        assertTrue(encoded.startsWith("-"))
        assertEquals(emptyList<TmdbSearchHit>(), TmdbPoster.decodeHitsValue(encoded, now = 2000L))
        assertNull(TmdbPoster.decodeHitsValue(encoded, now = 1000L + 7L * 24 * 60 * 60 * 1000 + 1))
    }

    @Test
    fun hitsValue_badJsonIsUnknown() {
        assertNull(TmdbPoster.decodeHitsValue("not-json", now = 1000L))
        assertNull(TmdbPoster.decodeHitsValue("-garbage", now = 1000L))
    }

    @Test
    fun episodesValue_roundTrip() {
        val encoded = """[{"name":"重生","stillPath":"/a.jpg"},{"name":"","stillPath":""}]"""
        val episodes = TmdbPoster.decodeEpisodesValue(encoded)!!
        assertEquals(2, episodes.size)
        assertEquals("重生", episodes[0].name)
        assertEquals("/a.jpg", episodes[0].stillPath)
        assertEquals("", episodes[1].stillPath)
        assertNull(TmdbPoster.decodeEpisodesValue("not-json"))
    }

    @Test
    fun emptyEpisodesValue_marksMovieMatch() {
        val meta = TmdbPoster.decodeEpisodesValue("[]")
        assertTrue(meta != null && meta.isEmpty())
    }

    @Test
    fun aliasHitValue_decodesHitOrNull() {
        val encoded = """{"id":88055,"mediaType":"tv","title":"Servant","originalTitle":"Servant","year":2019,"posterPath":"/k3hOXC2Uo2duEfuYbdd0Rkta3Ad.jpg"}"""
        val hit = TmdbPoster.decodeAliasHitValue(encoded)!!
        assertEquals(88055, hit.id)
        assertEquals("tv", hit.mediaType)
        assertEquals("/k3hOXC2Uo2duEfuYbdd0Rkta3Ad.jpg", hit.posterPath)
        assertNull(TmdbPoster.decodeAliasHitValue("not-json"))
    }

    @Test
    fun imagesValue_splitsPaths() {
        assertEquals(listOf("/a.jpg", "/b.jpg"), TmdbPoster.decodeImagesValue("/a.jpg,/b.jpg", now = 1000L))
        assertEquals(emptyList<String>(), TmdbPoster.decodeImagesValue("", now = 1000L))
    }

    @Test
    fun imagesValue_activeNegativeIsEmpty() {
        assertEquals(emptyList<String>(), TmdbPoster.decodeImagesValue("-2000", now = 1000L))
    }

    @Test
    fun imagesValue_expiredNegativeIsUnknown() {
        assertNull(TmdbPoster.decodeImagesValue("-500", now = 1000L))
    }
}
