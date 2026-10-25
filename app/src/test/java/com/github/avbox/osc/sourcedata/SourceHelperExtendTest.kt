package com.github.avbox.osc.sourcedata

import com.github.avbox.osc.bean.AbsXml
import com.github.avbox.osc.bean.Movie
import com.github.avbox.osc.util.MD5
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class SourceHelperExtendTest {

    private val gson = Gson()
    private val cache = ConcurrentHashMap<String, String>()

    @Test
    fun emptyAndNonHttpExtendPassThrough() {
        assertEquals("", SourceHelper.getFixUrl(cache, gson, "", 15))
        assertEquals("""{"a":1}""", SourceHelper.getFixUrl(cache, gson, """{"a":1}""", 15))
    }

    @Test
    fun cacheHitIsKeyedByMd5OfExtend() {
        val extend = "http://ext.example/api.json"
        cache[MD5.string2MD5(extend)!!] = """{"minified":true}"""
        assertEquals("""{"minified":true}""", SourceHelper.getFixUrl(cache, gson, extend, 15))
    }

    @Test
    fun unresolvedExtendFallsBackToOriginal() {
        val extend = "http://127.0.0.1/file/missing-extend-${System.nanoTime()}.json"
        assertEquals(extend, SourceHelper.getFixUrl(cache, gson, extend, -1))
    }

    @Test
    fun absXmlSplitsPlayUrlWithJavaRegexSemantics() {
        val urlInfo = Movie.Video.UrlBean.UrlInfo()
        urlInfo.urls = "第1集\$http://a.example/1.m3u8#"
        val urlBean = Movie.Video.UrlBean()
        urlBean.infoList = arrayListOf(urlInfo)
        val video = Movie.Video()
        video.urlBean = urlBean
        val movie = Movie()
        movie.videoList = arrayListOf(video)
        val data = AbsXml()
        data.movie = movie

        SourceHelper.absXml(data, "src")

        val beans = urlInfo.beanList!!
        assertEquals(1, beans.size)
        assertEquals("第1集", beans[0].name)
        assertEquals("http://a.example/1.m3u8", beans[0].url)
    }
}
