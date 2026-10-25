package com.github.avbox.osc.api

import com.github.avbox.osc.bean.TmdbEpisodeRef
import com.github.avbox.osc.bean.TmdbSearchHit
import com.github.avbox.osc.bean.TmdbSeason
import com.github.avbox.osc.util.TmdbImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbApiTest {

    private fun hit(
        id: Int = 1,
        mediaType: String = "movie",
        title: String = "",
        originalTitle: String = "",
        year: Int = 0,
        posterPath: String = "/p.jpg",
        originalLanguage: String? = null,
    ) = TmdbSearchHit(id, mediaType, title, originalTitle, year, posterPath, originalLanguage)

    @Test
    fun cleanTitle_removesSeasonSuffix() {
        assertEquals("老友记", TmdbApi.cleanTitle("老友记 第十季"))
        assertEquals("权力的游戏", TmdbApi.cleanTitle("权力的游戏 第3季"))
    }

    @Test
    fun cleanTitle_removesBracketLabels() {
        assertEquals("流浪地球", TmdbApi.cleanTitle("流浪地球[国语]"))
        assertEquals("哪吒", TmdbApi.cleanTitle("哪吒【中字】"))
    }

    @Test
    fun cleanTitle_keepsFirstAlias() {
        assertEquals("隐秘的角落", TmdbApi.cleanTitle("隐秘的角落 / 坏小孩"))
    }

    @Test
    fun cleanTitle_removesYearAndLabelParens() {
        assertEquals("复仇者联盟", TmdbApi.cleanTitle("复仇者联盟（2012）"))
        assertEquals("肖申克的救赎", TmdbApi.cleanTitle("肖申克的救赎(蓝光)"))
    }

    @Test
    fun cleanTitle_keepsPlainParensContent() {
        assertEquals("人生大事(下)", TmdbApi.cleanTitle("人生大事(下)"))
    }

    @Test
    fun cleanTitle_stripsInvisibleCharsAndTrailingLabels() {
        assertEquals("神探之痕迹", TmdbApi.cleanTitle("神探之痕迹\u200e"))
        assertEquals("一个部门的诞生", TmdbApi.cleanTitle("一个部门的诞生粤语"))
        assertEquals("xxx", TmdbApi.cleanTitle("xxx 粤语 中字"))
        assertEquals("粤语", TmdbApi.cleanTitle("粤语"))
        assertEquals("隐秘而伟大", TmdbApi.cleanTitle("隐秘而伟大"))
    }

    @Test
    fun cleanTitle_stripsRecapSuffixSections() {
        assertEquals("伟大的长征", TmdbApi.cleanTitle("伟大的长征 深度解读：细节还原真实长征之路"))
        assertEquals("凡人修仙传", TmdbApi.cleanTitle("凡人修仙传剧情揭秘"))
        assertEquals("仙逆", TmdbApi.cleanTitle("仙逆杂谈"))
        assertEquals("《斗破苍穹年番》", TmdbApi.cleanTitle("《斗破苍穹年番》之精彩解说"))
        assertEquals("斗破苍穹", TmdbApi.cleanTitle("斗破苍穹 解说"))
        assertEquals("斗破苍穹 年番", TmdbApi.cleanTitle("斗破苍穹 年番解说"))
    }

    @Test
    fun normalizeForMatch_foldsTheaterEditionWord() {
        assertEquals(
            TmdbApi.normalizeForMatch("仙逆剧场版：弑仙之战"),
            TmdbApi.normalizeForMatch("仙逆剧场弑仙之战"),
        )
        assertEquals("仙逆剧场弑仙之战", TmdbApi.normalizeForMatch("仙逆剧场弑仙之战"))
    }

    @Test
    fun parseAlternativeTitles_readsTvAndMovieShapes() {
        val tv = """{"id":1,"results":[{"iso_3166_1":"CN","title":"灵异女仆"},{"iso_3166_1":"TW","title":"靈異女僕"}]}"""
        assertEquals(listOf("灵异女仆", "靈異女僕"), TmdbApi.parseAlternativeTitles(tv))
        val movie = """{"titles":[{"iso_3166_1":"HK","title":"Too Many Ways To Be No.2"}]}"""
        assertEquals(listOf("Too Many Ways To Be No.2"), TmdbApi.parseAlternativeTitles(movie))
        assertTrue(TmdbApi.parseAlternativeTitles("not-json").isEmpty())
        assertTrue(TmdbApi.parseAlternativeTitles("{}").isEmpty())
    }

    @Test
    fun normalizeForMatch_stripsDecorationPunctuationButKeepsDigitsAndDash() {
        assertEquals(TmdbApi.normalizeForMatch("年会不能停！"), TmdbApi.normalizeForMatch("年会不能停"))
        assertEquals(TmdbApi.normalizeForMatch("年会不能停2！"), TmdbApi.normalizeForMatch("年会不能停2"))
        assertEquals(TmdbApi.normalizeForMatch("蜘蛛侠：英雄归来"), TmdbApi.normalizeForMatch("蜘蛛侠英雄归来"))
        assertNotEquals(TmdbApi.normalizeForMatch("7.1"), TmdbApi.normalizeForMatch("71"))
        assertNotEquals(TmdbApi.normalizeForMatch("Spider-Man"), TmdbApi.normalizeForMatch("SpiderMan"))
    }

    @Test
    fun normalizeForMatch_foldsFullWidthAndLowercase() {
        assertEquals("abc", TmdbApi.normalizeForMatch("ＡＢＣ"))
        assertEquals("abc", TmdbApi.normalizeForMatch("A b C"))
        assertEquals("复仇者联盟", TmdbApi.normalizeForMatch("复仇者联盟（２０１２）"))
    }

    @Test
    fun searchQuery_fallsBackToRawName() {
        assertEquals("老友记", TmdbApi.searchQuery("老友记 第一季"))
        assertEquals("第十季", TmdbApi.searchQuery("第十季"))
    }

    @Test
    fun pickBest_exactTitleMatchOnly() {
        val hits = listOf(hit(id = 1, title = "复仇者联盟2"))
        assertNull(TmdbApi.pickBest(hits, "复仇者联盟", 0))
        assertEquals(2, TmdbApi.pickBest(listOf(hit(id = 2, title = "复仇者联盟")), "复仇者联盟", 0)?.id)
    }

    @Test
    fun pickBest_matchesOriginalTitle() {
        val hits = listOf(hit(id = 7, title = "肖申克的救赎", originalTitle = "The Shawshank Redemption"))
        assertEquals(7, TmdbApi.pickBest(hits, "The Shawshank Redemption", 0)?.id)
    }

    @Test
    fun pickBest_prefersClosestYear() {
        val hits = listOf(
            hit(id = 1, title = "西游记", year = 1986),
            hit(id = 2, title = "西游记", year = 2011),
        )
        assertEquals(2, TmdbApi.pickBest(hits, "西游记", 2011)?.id)
        assertEquals(1, TmdbApi.pickBest(hits, "西游记", 1990)?.id)
    }

    @Test
    fun pickBest_skipsMissingPoster() {
        val hits = listOf(hit(id = 1, title = "无间道", posterPath = ""))
        assertNull(TmdbApi.pickBest(hits, "无间道", 0))
    }

    @Test
    fun pickBest_blankNameGivesNull() {
        assertNull(TmdbApi.pickBest(listOf(hit(title = "x")), " ", 0))
        assertNull(TmdbApi.pickBest(listOf(hit(title = "x")), null, 0))
    }

    @Test
    fun pickBest_prefersEarliestWhenSourceYearMissing() {
        val hits = listOf(
            hit(id = 1, title = "西游记", year = 2011),
            hit(id = 2, title = "西游记", year = 1986),
        )
        assertEquals(2, TmdbApi.pickBest(hits, "西游记", 0)?.id)
    }

    @Test
    fun pickBest_prefersChineseOriginalOnYearTie() {
        val hits = listOf(
            hit(id = 1, title = "Dune", year = 2021, originalLanguage = "en"),
            hit(id = 2, title = "Dune", year = 2021, originalLanguage = "zh"),
        )
        assertEquals(2, TmdbApi.pickBest(hits, "Dune", 2021)?.id)
    }

    @Test
    fun pickBest_seasonHintRejectsMovieCandidates() {
        val hits = listOf(hit(id = 3, title = "流人", year = 2004))
        assertNull(TmdbApi.pickBest(hits, "流人第六季", 0))
        assertEquals(3, TmdbApi.pickBest(hits, "流人", 0)?.id)
    }

    @Test
    fun pickBest_seasonHintKeepsTvCandidate() {
        val hits = listOf(
            hit(id = 3, title = "流人", year = 2004),
            hit(id = 4, mediaType = "tv", title = "流人", year = 2008),
        )
        assertEquals(4, TmdbApi.pickBest(hits, "流人第六季", 0)?.id)
    }

    @Test
    fun cleanTitle_stripsExtendedLabels() {
        assertEquals("西游记", TmdbApi.cleanTitle("西游记国语版"))
        assertEquals("西游记", TmdbApi.cleanTitle("西游记（未删减）"))
        assertEquals("西游记", TmdbApi.cleanTitle("西游记更新至36集"))
        assertEquals("西游记", TmdbApi.cleanTitle("西游记 4K HDR 杜比视界"))
        assertEquals("西游记", TmdbApi.cleanTitle("西游记H.265"))
    }

    @Test
    fun cleanTitle_stripsQualityBrandLabels() {
        assertEquals("兰香如故", TmdbApi.cleanTitle("兰香如故（臻彩）"))
        assertEquals("兰香如故", TmdbApi.cleanTitle("兰香如故 臻彩"))
        assertEquals("玉簟秋", TmdbApi.cleanTitle("玉簟秋（帧享）"))
        assertEquals("玉簟秋", TmdbApi.cleanTitle("玉簟秋（巨幕）"))
        assertEquals("玉簟秋", TmdbApi.cleanTitle("玉簟秋（高帧）"))
        assertEquals("兰香如故", TmdbApi.searchQuery("兰香如故（臻彩）"))
    }

    @Test
    fun qualityTagVariant_stripsUnknownQualityTags() {
        assertEquals("兰香如故", TmdbApi.qualityTagVariant("兰香如故（臻彩视听）"))
        assertEquals("兰香如故", TmdbApi.qualityTagVariant("兰香如故（HDR Vivid）"))
        assertEquals("兰香如故", TmdbApi.qualityTagVariant("兰香如故 原画"))
        assertEquals("兰香如故", TmdbApi.qualityTagVariant("兰香如故（臻彩Max）"))
    }

    @Test
    fun qualityTagVariant_ignoresCleanedAndSemanticNames() {
        assertEquals("", TmdbApi.qualityTagVariant("兰香如故（臻彩）"))
        assertEquals("", TmdbApi.qualityTagVariant("兰香如故"))
        assertEquals("", TmdbApi.qualityTagVariant("西游记（动画版）"))
        assertEquals("", TmdbApi.qualityTagVariant("名侦探柯南（剧场版）"))
        assertEquals("", TmdbApi.qualityTagVariant("人生大事(下)"))
        assertEquals("", TmdbApi.qualityTagVariant("第十季"))
        assertEquals("", TmdbApi.qualityTagVariant(null))
    }

    @Test
    fun parseSearchHits_keepsMovieAndTvOnly() {
        val json = """
            {"page":1,"results":[
              {"id":1,"media_type":"movie","title":"沙丘","original_title":"Dune","release_date":"2021-10-22","poster_path":"/a.jpg"},
              {"id":2,"media_type":"tv","name":"沙丘","original_name":"Dune","first_air_date":"2022-01-01","poster_path":"/b.jpg"},
              {"id":3,"media_type":"person","name":"某人"}
            ]}
        """.trimIndent()
        val hits = TmdbApi.parseSearchHits(json)
        assertEquals(2, hits.size)
        assertEquals(1, hits[0].id)
        assertEquals(2021, hits[0].year)
        assertEquals(2, hits[1].id)
        assertEquals("tv", hits[1].mediaType)
    }

    @Test
    fun parseSearchHits_toleratesNullPosterAndBadJson() {
        val json = """{"results":[{"id":9,"media_type":"movie","title":"x","release_date":"","poster_path":null}]}"""
        val hits = TmdbApi.parseSearchHits(json)
        assertEquals(1, hits.size)
        assertEquals("", hits[0].posterPath)
        assertEquals(0, hits[0].year)
        assertTrue(TmdbApi.parseSearchHits("not-json").isEmpty())
        assertTrue(TmdbApi.parseSearchHits("{}").isEmpty())
    }

    @Test
    fun parseImagePaths_sortsByVoteAndKeepsFilePaths() {
        val json = """
            {"posters":[
              {"file_path":"/low.jpg","vote_average":1.0},
              {"file_path":"/high.jpg","vote_average":8.5},
              {"file_path":null,"vote_average":9.9}
            ]}
        """.trimIndent()
        assertEquals(listOf("/high.jpg", "/low.jpg"), TmdbApi.parseImagePaths(json))
    }

    @Test
    fun parseBackdropPaths_prefersTextlessAndSortsByVote() {
        val json = """
            {"backdrops":[
              {"file_path":"/text.jpg","vote_average":9.9,"iso_639_1":"en"},
              {"file_path":"/low.jpg","vote_average":1.0,"iso_639_1":null},
              {"file_path":"/high.jpg","vote_average":8.0},
              {"file_path":null,"vote_average":9.9,"iso_639_1":null}
            ]}
        """.trimIndent()
        assertEquals(listOf("/high.jpg", "/low.jpg", "/text.jpg"), TmdbApi.parseBackdropPaths(json))
    }

    @Test
    fun parseBackdropPaths_capsAtTenAndToleratesBadInput() {
        val items = (1..12).joinToString(",") { """{"file_path":"/p$it.jpg","vote_average":$it}""" }
        val paths = TmdbApi.parseBackdropPaths("""{"backdrops":[$items]}""")
        assertEquals(10, paths.size)
        assertEquals("/p12.jpg", paths.first())
        assertTrue(TmdbApi.parseBackdropPaths("not json").isEmpty())
        assertTrue(TmdbApi.parseBackdropPaths("{}").isEmpty())
    }

    @Test
    fun parseLogoPaths_prefersChineseThenEnglishThenOthers() {
        val json = """
            {"logos":[
              {"file_path":"/en.png","iso_639_1":"en","vote_average":9.0},
              {"file_path":"/zh.png","iso_639_1":"zh","vote_average":1.0},
              {"file_path":"/ja.png","iso_639_1":"ja","vote_average":9.9},
              {"file_path":"/none.png","iso_639_1":null,"vote_average":5.0}
            ]}
        """.trimIndent()
        assertEquals(listOf("/zh.png", "/en.png", "/none.png"), TmdbApi.parseLogoPaths(json))
    }

    @Test
    fun parseLogoPaths_treatsRegionVariantsByLanguagePrefix() {
        val json = """
            {"logos":[
              {"file_path":"/tw.png","iso_639_1":"zh-TW","vote_average":8.0},
              {"file_path":"/us.png","iso_639_1":"en-US","vote_average":9.0}
            ]}
        """.trimIndent()
        assertEquals(listOf("/tw.png", "/us.png"), TmdbApi.parseLogoPaths(json))
    }

    @Test
    fun parseLogoPaths_sortsByVoteWithinLanguageAndCapsAtThree() {
        val json = """
            {"logos":[
              {"file_path":"/a.png","iso_639_1":"zh","vote_average":1.0},
              {"file_path":"/b.png","iso_639_1":"zh","vote_average":3.0},
              {"file_path":"/c.png","iso_639_1":"zh","vote_average":2.0},
              {"file_path":"/d.png","iso_639_1":"zh","vote_average":0.5}
            ]}
        """.trimIndent()
        assertEquals(listOf("/b.png", "/c.png", "/a.png"), TmdbApi.parseLogoPaths(json))
        assertTrue(TmdbApi.parseLogoPaths("not json").isEmpty())
        assertTrue(TmdbApi.parseLogoPaths("{}").isEmpty())
    }

    @Test
    fun parseDetail_readsMetaAndCastSortedWithProfileOnly() {
        val json = """
            {
              "overview":"剧情简介",
              "vote_average":6.1,
              "genres":[{"id":35,"name":"喜剧"},{"id":28,"name":"动作"}],
              "credits":{"cast":[
                {"id":1041,"name":"刘嘉玲","character":"C","profile_path":"/c.jpg","order":3},
                {"id":1042,"name":"无头像","character":"D","profile_path":null,"order":1},
                {"id":1043,"name":"张小斐","character":"A","profile_path":"/a.jpg","order":0},
                {"id":1044,"name":"迪丽热巴","character":"B","profile_path":"/b.jpg","order":2}
              ]}
            }
        """.trimIndent()
        val detail = TmdbApi.parseDetail(json)!!
        assertEquals("剧情简介", detail.overview)
        assertEquals(6.1, detail.rating, 0.001)
        assertEquals(listOf("喜剧", "动作"), detail.genres)
        assertEquals(listOf("张小斐", "迪丽热巴", "刘嘉玲"), detail.cast.map { it.name })
        assertEquals(listOf(1043, 1044, 1041), detail.cast.map { it.id })
        assertEquals(listOf("/a.jpg", "/b.jpg", "/c.jpg"), detail.cast.map { it.profilePath })
    }

    @Test
    fun parsePerson_readsProfileFields() {
        val json = """
            {
              "name":"刘嘉玲",
              "biography":"香港演员。",
              "birthday":"1965-12-08",
              "place_of_birth":"中国江苏苏州",
              "profile_path":"/p.jpg"
            }
        """.trimIndent()
        val person = TmdbApi.parsePerson(json)!!
        assertEquals("刘嘉玲", person.name)
        assertEquals("香港演员。", person.biography)
        assertEquals("1965-12-08", person.birthday)
        assertEquals("中国江苏苏州", person.placeOfBirth)
        assertEquals("/p.jpg", person.profilePath)
        assertNull(TmdbApi.parsePerson("not-json"))
    }

    @Test
    fun parseDetail_toleratesMissingParts() {
        val detail = TmdbApi.parseDetail("""{"overview":"x"}""")!!
        assertEquals("x", detail.overview)
        assertEquals(0.0, detail.rating, 0.001)
        assertTrue(detail.genres.isEmpty())
        assertTrue(detail.cast.isEmpty())
        assertNull(TmdbApi.parseDetail("not-json"))
    }

    @Test
    fun mapEpisodes_singleSeasonByCount() {
        val seasons = listOf(TmdbSeason(1, 24), TmdbSeason(2, 12))
        val refs = TmdbApi.mapEpisodes(seasons, 24, null)!!
        assertEquals(24, refs.size)
        assertEquals(TmdbEpisodeRef(1, 1), refs.first())
        assertEquals(TmdbEpisodeRef(1, 24), refs.last())
    }

    @Test
    fun mapEpisodes_hintWinsOverAmbiguity() {
        val seasons = listOf(TmdbSeason(1, 10), TmdbSeason(2, 10), TmdbSeason(3, 10))
        assertNull(TmdbApi.mapEpisodes(seasons, 10, null))
        val refs = TmdbApi.mapEpisodes(seasons, 10, 3)!!
        assertEquals(10, refs.size)
        assertEquals(TmdbEpisodeRef(3, 1), refs.first())
        assertEquals(TmdbEpisodeRef(3, 10), refs.last())
    }

    @Test
    fun mapEpisodes_totalCountSpansSeasons() {
        val seasons = listOf(TmdbSeason(1, 10), TmdbSeason(2, 8))
        val refs = TmdbApi.mapEpisodes(seasons, 18, null)!!
        assertEquals(18, refs.size)
        assertEquals(TmdbEpisodeRef(1, 10), refs[9])
        assertEquals(TmdbEpisodeRef(2, 1), refs[10])
        assertEquals(TmdbEpisodeRef(2, 8), refs[17])
    }

    @Test
    fun mapEpisodes_mismatchGivesNull() {
        val seasons = listOf(TmdbSeason(1, 10), TmdbSeason(2, 10))
        assertNull(TmdbApi.mapEpisodes(seasons, 7, null))
        assertNull(TmdbApi.mapEpisodes(emptyList(), 5, null))
        assertNull(TmdbApi.mapEpisodes(seasons, 0, null))
    }

    @Test
    fun mapEpisodes_singleSeasonPartialTakesFirstN() {
        val seasons = listOf(TmdbSeason(1, 206))
        val refs = TmdbApi.mapEpisodes(seasons, 194, null)!!
        assertEquals(194, refs.size)
        assertEquals(TmdbEpisodeRef(1, 1), refs.first())
        assertEquals(TmdbEpisodeRef(1, 194), refs.last())
        assertNull(TmdbApi.mapEpisodes(seasons, 300, null))
        assertNull(
            TmdbApi.mapEpisodes(
                listOf(TmdbSeason(1, 10), TmdbSeason(2, 196)),
                194,
                null,
            ),
        )
    }

    @Test
    fun mapEpisodes_includesSpecialsWhenTotalMatches() {
        val seasons = listOf(TmdbSeason(0, 2), TmdbSeason(1, 264))
        val refs = TmdbApi.mapEpisodes(seasons, 266, null)!!
        assertEquals(266, refs.size)
        assertEquals(TmdbEpisodeRef(0, 1), refs.first())
        assertEquals(TmdbEpisodeRef(0, 2), refs[1])
        assertEquals(TmdbEpisodeRef(1, 1), refs[2])
        assertEquals(TmdbEpisodeRef(1, 264), refs.last())
        val partial = TmdbApi.mapEpisodes(seasons, 265, null)!!
        assertEquals(265, partial.size)
        assertEquals(TmdbEpisodeRef(0, 1), partial.first())
        assertEquals(TmdbEpisodeRef(1, 263), partial.last())
        assertNull(TmdbApi.mapEpisodes(seasons, 300, null))
        assertNull(TmdbApi.mapEpisodes(seasons, 266, 1))
    }

    @Test
    fun mapEpisodes_foldsIntegralSegmentCount() {
        val seasons = listOf(TmdbSeason(1, 47))
        val refs = TmdbApi.mapEpisodes(seasons, 141, null)!!
        assertEquals(141, refs.size)
        assertEquals(TmdbEpisodeRef(1, 1), refs[0])
        assertEquals(TmdbEpisodeRef(1, 1), refs[2])
        assertEquals(TmdbEpisodeRef(1, 2), refs[3])
        assertEquals(TmdbEpisodeRef(1, 47), refs[140])
        assertEquals(3, TmdbApi.foldSegmentsPerEpisode(seasons, 141, null))
        assertEquals(2, TmdbApi.foldSegmentsPerEpisode(listOf(TmdbSeason(1, 20)), 40, null))
    }

    @Test
    fun mapEpisodes_foldRejectsOutOfRangeAndHinted() {
        val seasons = listOf(TmdbSeason(1, 47))
        assertNull(TmdbApi.mapEpisodes(seasons, 58, null))
        assertNull(TmdbApi.mapEpisodes(seasons, 141, 1))
        assertNull(TmdbApi.mapEpisodes(seasons, 235, null))
        assertNull(
            TmdbApi.mapEpisodes(
                listOf(TmdbSeason(1, 10), TmdbSeason(2, 10)),
                60,
                null,
            ),
        )
        assertEquals(0, TmdbApi.foldSegmentsPerEpisode(seasons, 58, null))
        assertEquals(0, TmdbApi.foldSegmentsPerEpisode(seasons, 47, null))
        assertEquals(0, TmdbApi.foldSegmentsPerEpisode(emptyList(), 141, null))
    }

    @Test
    fun mapEpisodesByNumber_mapsSiteNumbersToSeason() {
        val seasons = listOf(TmdbSeason(1, 104))
        val refs = TmdbApi.mapEpisodesByNumber(
            seasons,
            9,
            null,
            listOf(90, 91, 92, 93, 94, 85, 87, 88, 89),
        )!!
        assertEquals(9, refs.size)
        assertEquals(TmdbEpisodeRef(1, 90), refs[0])
        assertEquals(TmdbEpisodeRef(1, 94), refs[4])
        assertEquals(TmdbEpisodeRef(1, 89), refs[8])
    }

    @Test
    fun mapEpisodesByNumber_requiresSingleSeasonWithoutHint() {
        assertNull(
            TmdbApi.mapEpisodesByNumber(
                listOf(TmdbSeason(1, 24), TmdbSeason(2, 12)),
                2,
                null,
                listOf(1, 2),
            ),
        )
        assertNull(TmdbApi.mapEpisodesByNumber(listOf(TmdbSeason(1, 104)), 2, 1, listOf(1, 2)))
    }

    @Test
    fun mapEpisodesByNumber_rejectsUnparsedDuplicateAndOutOfRange() {
        val seasons = listOf(TmdbSeason(1, 47))
        assertNull(TmdbApi.mapEpisodesByNumber(seasons, 2, null, listOf(-1, 2)))
        assertNull(TmdbApi.mapEpisodesByNumber(seasons, 2, null, listOf(5, 5)))
        assertNull(TmdbApi.mapEpisodesByNumber(seasons, 2, null, listOf(46, 48)))
        assertNull(TmdbApi.mapEpisodesByNumber(seasons, 2, null, listOf(1)))
        assertNull(TmdbApi.mapEpisodesByNumber(seasons, 141, null, (1..141).toList()))
    }

    @Test
    fun parseSeasonHint_readsCommonForms() {
        assertEquals(3, TmdbApi.parseSeasonHint("灵异女仆第三季"))
        assertEquals(3, TmdbApi.parseSeasonHint("庆余年 第3季"))
        assertEquals(2, TmdbApi.parseSeasonHint("Show Season 2"))
        assertEquals(1, TmdbApi.parseSeasonHint("某剧 S1"))
        assertEquals(12, TmdbApi.parseSeasonHint("第 十二 季"))
        assertNull(TmdbApi.parseSeasonHint("老友记"))
        assertNull(TmdbApi.parseSeasonHint(null))
    }

    @Test
    fun parseSeasonEpisodes_sortsByEpisodeNumber() {
        val json = """{"episodes":[
            {"episode_number":2,"name":"第二集","still_path":"/b.jpg"},
            {"episode_number":1,"name":"第一集","still_path":"/a.jpg"},
            {"episode_number":3,"name":"第三集","still_path":null}
        ]}"""
        val episodes = TmdbApi.parseSeasonEpisodes(json)
        assertEquals(listOf("第一集", "第二集", "第三集"), episodes.map { it.name })
        assertEquals(listOf("/a.jpg", "/b.jpg", ""), episodes.map { it.stillPath })
    }

    @Test
    fun stillUrl_joinsStillSize() {
        assertEquals("https://images.tmdb.org/t/p/w300/a.jpg", TmdbImages.stillUrl("", "/a.jpg"))
    }

    @Test
    fun profileUrl_joinsProfileSizeAndBase() {
        assertEquals(
            "https://images.tmdb.org/t/p/w185/a.jpg",
            TmdbImages.profileUrl("", "/a.jpg"),
        )
        assertEquals(
            "https://mirror.example.com/t/p/w185/a.jpg",
            TmdbImages.profileUrl("https://mirror.example.com/t/p", "/a.jpg"),
        )
    }

    @Test
    fun fullPosterUrl_joinsOriginalSize() {
        assertEquals("https://images.tmdb.org/t/p/original/a.jpg", TmdbImages.fullPosterUrl("", "/a.jpg"))
        assertEquals(
            "https://mirror.example.com/t/p/original/a.jpg",
            TmdbImages.fullPosterUrl("https://mirror.example.com/t/p/", "/a.jpg"),
        )
    }

    @Test
    fun backdropUrl_joinsW1280Size() {
        assertEquals("https://images.tmdb.org/t/p/w1280/b.jpg", TmdbImages.backdropUrl("", "/b.jpg"))
        assertEquals(
            "https://mirror.example.com/t/p/w1280/b.jpg",
            TmdbImages.backdropUrl("https://mirror.example.com/t/p/", "/b.jpg"),
        )
    }

    @Test
    fun imageUrl_joinsSizeAndBase() {
        assertEquals(
            "https://images.tmdb.org/t/p/w342/a.jpg",
            TmdbImages.imageUrl("", "/a.jpg", large = false),
        )
        assertEquals(
            "https://images.tmdb.org/t/p/w780/a.jpg",
            TmdbImages.imageUrl("", "/a.jpg", large = true),
        )
        assertEquals(
            "https://mirror.example.com/t/p/w342/a.jpg",
            TmdbImages.imageUrl("https://mirror.example.com/t/p/", "/a.jpg", large = false),
        )
    }
}
