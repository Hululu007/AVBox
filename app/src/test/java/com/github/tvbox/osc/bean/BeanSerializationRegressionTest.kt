package com.github.tvbox.osc.bean

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.thoughtworks.xstream.XStream
import com.thoughtworks.xstream.io.xml.DomDriver
import com.thoughtworks.xstream.security.AnyTypePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.LinkedHashMap

class BeanSerializationRegressionTest {

    private val gson = Gson()

    private fun listXStream(): XStream {
        val xstream = XStream(DomDriver())
        xstream.addPermission(AnyTypePermission.ANY)
        xstream.autodetectAnnotations(true)
        xstream.processAnnotations(AbsXml::class.java)
        xstream.ignoreUnknownElements()
        return xstream
    }

    private fun sortXStream(): XStream {
        val xstream = XStream(DomDriver())
        xstream.addPermission(AnyTypePermission.ANY)
        xstream.autodetectAnnotations(true)
        xstream.processAnnotations(AbsSortXml::class.java)
        xstream.ignoreUnknownElements()
        return xstream
    }

    @Test
    fun absXml_xstream_deserializesEveryMappedField() {
        val xml = """
            <rss>
              <list page="1" pagecount="2" pagesize="20" recordcount="30">
                <video>
                  <last>2021-01-01</last>
                  <id>v1</id>
                  <tid>5</tid>
                  <name>测试片</name>
                  <type>剧情</type>
                  <pic>http://img/1.jpg</pic>
                  <lang>国语</lang>
                  <area>大陆</area>
                  <year>2021</year>
                  <state>0</state>
                  <note>全12集</note>
                  <actor>A</actor>
                  <director>B</director>
                  <dl>
                    <dd flag="线路甲"><![CDATA[第1集${'$'}http://a/1.m3u8#第2集${'$'}http://a/2.m3u8]]></dd>
                  </dl>
                  <des><![CDATA[权来]]></des>
                  <tag>folder</tag>
                  <action>act</action>
                </video>
              </list>
              <msg>ok</msg>
            </rss>
        """.trimIndent()

        val absXml = listXStream().fromXML(xml) as AbsXml
        val movie = absXml.movie!!
        assertEquals(1, movie.page)
        assertEquals(2, movie.pagecount)
        assertEquals(20, movie.pagesize)
        assertEquals(30, movie.recordcount)

        val video = movie.videoList!![0]
        assertEquals("2021-01-01", video.last)
        assertEquals("v1", video.id)
        assertEquals(5, video.tid)
        assertEquals("测试片", video.name)
        assertEquals("剧情", video.type)
        assertEquals("http://img/1.jpg", video.pic)
        assertEquals("国语", video.lang)
        assertEquals("大陆", video.area)
        assertEquals(2021, video.year)
        assertEquals("0", video.state)
        assertEquals("全12集", video.note)
        assertEquals("A", video.actor)
        assertEquals("B", video.director)
        assertEquals("folder", video.tag)
        assertEquals("act", video.action)
        assertEquals("权来", video.des)

        val urlInfo = video.urlBean!!.infoList!![0]
        assertEquals("线路甲", urlInfo.flag)
        assertEquals("第1集\$http://a/1.m3u8#第2集\$http://a/2.m3u8", urlInfo.urls)

        assertEquals("ok", absXml.msg)
        assertNull(absXml.sourceKey)
        assertNull(absXml.searchToken)
        assertNull(absXml.detailToken)
    }

    @Test
    fun absSortXml_xstream_deserializesClassesAndList() {
        val xml = """
            <rss>
              <class>
                <ty id="1"><![CDATA[电影]]></ty>
                <ty id="2"><![CDATA[剧集]]></ty>
              </class>
              <list page="1" pagesize="20">
                <video>
                  <id>v1</id>
                  <name>片1</name>
                </video>
              </list>
            </rss>
        """.trimIndent()

        val absSortXml = sortXStream().fromXML(xml) as AbsSortXml
        val sortList = absSortXml.classes!!.sortList!!
        assertEquals(2, sortList.size)
        assertEquals("1", sortList[0].id)
        assertEquals("电影", sortList[0].name)
        assertEquals("2", sortList[1].id)
        assertEquals("剧集", sortList[1].name)

        val videos = absSortXml.list!!.videoList!!
        assertEquals(1, videos.size)
        assertEquals("v1", videos[0].id)
        assertEquals("片1", videos[0].name)
        assertNull(absSortXml.sourceKey)
    }

    @Test
    fun absJson_toAbsXml_splitsPlayFlagsAndUrls() {
        val json =
            """{"code":1,"page":1,"pagecount":2,"limit":"20","total":30,"msg":"ok","list":[""" +
                """{"vod_id":"1","vod_name":"测试片","type_id":"7","type_name":"剧情","vod_year":"2021",""" +
                """"vod_remarks":"全2集","vod_en":"Slow Horses","vod_tag":"","cate":{"x":1},""" +
                """"vod_play_from":"线路甲${'$'}${'$'}${'$'}线路乙",""" +
                """"vod_play_url":"第1集${'$'}http://a/1.m3u8#第2集${'$'}http://a/2.m3u8${'$'}${'$'}${'$'}第1集${'$'}http://b/1.m3u8"}]}"""

        val absJson = gson.fromJson(json, AbsJson::class.java)
        val absXml = absJson.toAbsXml()

        val movie = absXml.movie!!
        assertEquals(1, movie.page)
        assertEquals(2, movie.pagecount)
        assertEquals(20, movie.pagesize)
        assertEquals(30, movie.recordcount)

        val video = movie.videoList!![0]
        assertEquals("1", video.id)
        assertEquals("测试片", video.name)
        assertEquals(7, video.tid)
        assertEquals(2021, video.year)
        assertEquals("剧情", video.type)
        assertEquals("Slow Horses", video.en)
        assertEquals("全2集", video.note)
        assertEquals("folder", video.tag)

        val infoList = video.urlBean!!.infoList!!
        assertEquals(2, infoList.size)
        assertEquals("线路甲", infoList[0].flag)
        assertEquals("第1集\$http://a/1.m3u8#第2集\$http://a/2.m3u8", infoList[0].urls)
        assertEquals("线路乙", infoList[1].flag)

        assertEquals("ok", absXml.msg)
    }

    @Test
    fun absJson_toAbsXml_keepsNonNumericTypeIdAtZero() {
        val json = """{"list":[{"vod_id":"1","vod_name":"片","type_id":"abc","vod_year":"x"}]}"""

        val video = gson.fromJson(json, AbsJson::class.java).toAbsXml().movie!!.videoList!![0]

        assertEquals(0, video.tid)
        assertEquals(0, video.year)
    }

    @Test
    fun absSortJson_honoursSerializedNameAndAlternates() {
        val json =
            """{"class":[{"type_id":"1","type_name":"电影","type_flag":"dy"},""" +
                """{"id":"2","name":"剧集"}],"list":[{"vod_id":"1","vod_name":"片1"}]}"""

        val absSortXml = gson.fromJson(json, AbsSortJson::class.java).toAbsSortXml()

        val sortList = absSortXml.classes!!.sortList!!
        assertEquals(2, sortList.size)
        assertEquals("1", sortList[0].id)
        assertEquals("电影", sortList[0].name)
        assertEquals("dy", sortList[0].flag)
        assertEquals("2", sortList[1].id)
        assertEquals("剧集", sortList[1].name)

        assertEquals("片1", absSortXml.list!!.videoList!![0].name)
    }

    @Test
    fun absSortJson_skipsNullClassEntries() {
        val json = """{"class":[null,{"type_id":"1","type_name":"电影"},{"type_id":null,"type_name":"x"}]}"""

        val absSortXml = gson.fromJson(json, AbsSortJson::class.java).toAbsSortXml()

        val sortList = absSortXml.classes!!.sortList!!
        assertEquals(1, sortList.size)
        assertEquals("1", sortList[0].id)
    }

    @Test
    fun movieVideo_gsonRoundTripKeepsFieldNamesAndNestedBeans() {
        val video = Movie.Video()
        video.id = "v1"
        video.name = "片"
        video.tid = 3
        video.year = 2020
        video.tag = "folder"
        video.urlBean = Movie.Video.UrlBean().apply {
            infoList = arrayListOf(Movie.Video.UrlBean.UrlInfo().apply {
                flag = "线路甲"
                urls = "第1集\$http://a/1"
                beanList = arrayListOf(Movie.Video.UrlBean.UrlInfo.InfoBean("第1集", "http://a/1"))
            })
        }

        val json = gson.toJson(video)
        assertTrue(json.contains("\"urlBean\""))
        assertTrue(json.contains("\"infoList\""))
        assertTrue(json.contains("\"beanList\""))
        assertTrue(json.contains("\"tid\""))
        assertTrue(json.contains("\"year\""))

        val back = gson.fromJson(json, Movie.Video::class.java)
        assertEquals("v1", back.id)
        assertEquals(3, back.tid)
        assertEquals(2020, back.year)
        assertEquals("folder", back.tag)
        assertEquals("线路甲", back.urlBean!!.infoList!![0].flag)
        assertEquals("第1集", back.urlBean!!.infoList!![0].beanList!![0].name)
        assertEquals("http://a/1", back.urlBean!!.infoList!![0].beanList!![0].url)
    }

    @Test
    fun sortData_gsonRoundTripKeepsFiltersAndComparesBySort() {
        val sort = MovieSort.SortData("1", "电影")
        sort.sort = 3
        sort.filters.add(MovieSort.SortFilter().apply {
            key = "area"
            name = "地区"
            values = LinkedHashMap<String, String>().apply {
                put("cn", "大陆")
            }
        })
        sort.filterSelect["area"] = "cn"

        val back = gson.fromJson(gson.toJson(sort), MovieSort.SortData::class.java)

        assertEquals("1", back.id)
        assertEquals("电影", back.name)
        assertEquals(3, back.sort)
        assertEquals(1, back.filterSelectCount())
        assertEquals("cn", back.filterSelect["area"])
        assertEquals(1, back.filters.size)
        assertEquals("地区", back.filters[0].name)
        assertEquals("大陆", back.filters[0].values!!["cn"])
        assertEquals(4, back.compareTo(MovieSort.SortData("2", "x")))
        assertTrue(back.toString().startsWith("SortData{id='1', name='电影', sort=3"))
    }

    @Test
    fun sortData_filterSelectCountToleratesEmptyAndNullValues() {
        val sort = MovieSort.SortData()
        assertEquals(0, sort.filterSelectCount())

        sort.filterSelect["a"] = ""
        sort.filterSelect["b"] = "cn"
        assertEquals(1, sort.filterSelectCount())
    }

    @Test
    fun vodInfo_gsonKeepsRoomExcludedFieldNames() {
        val info = VodInfo(
            id = "1",
            name = "片",
            sourceKey = "src",
            playFlag = "线路甲",
            playIndex = 2,
            seriesFlags = arrayListOf(VodInfo.VodSeriesFlag("线路甲")),
            seriesMap = LinkedHashMap<String?, MutableList<VodInfo.VodSeries>>().apply {
                put("线路甲", arrayListOf(
                    VodInfo.VodSeries("第1集", "http://a/1"),
                    VodInfo.VodSeries("第2集", "http://a/2"),
                ))
            },
        )

        val json = gson.toJson(info)
        assertTrue(json.contains("\"seriesFlags\""))
        assertTrue(json.contains("\"seriesMap\""))
        assertTrue(json.contains("\"playFlag\""))
        assertTrue(json.contains("\"playIndex\""))
        assertTrue(json.contains("\"playerCfg\""))
        assertTrue(json.contains("\"reverseSort\""))

        val back = gson.fromJson(json, VodInfo::class.java)
        assertEquals("线路甲", back.playFlag)
        assertEquals(2, back.playIndex)
        assertEquals(1, back.seriesFlags!!.size)
        assertEquals(2, back.seriesMap!!["线路甲"]!!.size)
        assertEquals("http://a/2", back.seriesMap!!["线路甲"]!![1].url)

        back.reverse()
        assertEquals("第2集", back.seriesMap!!["线路甲"]!![0].name)
    }

    @Test
    fun vodInfo_setVideo_buildsSeriesAndAutoReversesDescendingEpisodes() {
        val video = Movie.Video()
        video.id = "1"
        video.name = "片"
        video.year = 2021
        video.urlBean = Movie.Video.UrlBean().apply {
            infoList = arrayListOf(Movie.Video.UrlBean.UrlInfo().apply {
                flag = "线路甲"
                beanList = arrayListOf(
                    Movie.Video.UrlBean.UrlInfo.InfoBean("第3集", "http://a/3"),
                    Movie.Video.UrlBean.UrlInfo.InfoBean("第2集", "http://a/2"),
                    Movie.Video.UrlBean.UrlInfo.InfoBean("第1集", "http://a/1"),
                )
            })
        }

        val info = VodInfo.fromVideo(video)

        assertEquals("1", info.id)
        assertEquals("片", info.name)
        assertEquals(2021, info.year)
        assertEquals(1, info.seriesFlags!!.size)
        assertEquals("线路甲", info.seriesFlags!![0].name)
        val series = info.seriesMap!!["线路甲"]!!
        assertEquals(3, series.size)
        assertEquals("第1集", series[0].name)
        assertEquals("http://a/1", series[0].url)
        assertEquals("第3集", series[2].name)
    }

    @Test
    fun vodInfo_setVideo_withoutLinesLeavesSeriesMapNull() {
        val video = Movie.Video()
        video.id = "1"
        video.name = "片"

        val info = VodInfo.fromVideo(video)

        assertEquals("1", info.id)
        assertNull(info.seriesFlags)
        assertNull(info.seriesMap)
    }

    @Test
    fun proxyRule_keepsFieldNamesAndSerializedNamesOnFields() {
        val fields = ProxyRule::class.java.declaredFields.associateBy { it.name }
        listOf("name", "hosts", "urls", "proxies", "uris", "wildcard").forEach {
            assertTrue("字段 $it 必须存在(gson 按字段名/注解映射)", fields.containsKey(it))
        }
        assertEquals("name", fields.getValue("name").getAnnotation(SerializedName::class.java).value)
        assertEquals("hosts", fields.getValue("hosts").getAnnotation(SerializedName::class.java).value)
        assertEquals("urls", fields.getValue("urls").getAnnotation(SerializedName::class.java).value)

        val rule = ProxyRule()
        assertTrue(rule.getHosts().isEmpty())
        assertTrue(rule.getUrls().isEmpty())
        assertTrue(rule.getProxies().isEmpty())

        rule.init()
        assertTrue(rule.getProxies().isEmpty())
    }

    @Test
    fun proxyRule_arrayFrom_toleratesNullInput() {
        assertTrue(ProxyRule.arrayFrom(null).isEmpty())
    }

    @Test
    fun proxyRule_init_skipsNullHostEntries() {
        val wildcardRule = ProxyRule()
        val hosts = ProxyRule::class.java.getDeclaredField("hosts")
        hosts.isAccessible = true
        hosts.set(wildcardRule, arrayListOf<String?>(null, "*.b.com"))
        val plainRule = ProxyRule()

        wildcardRule.init()
        plainRule.init()

        assertTrue(wildcardRule.compareTo(plainRule) > 0)
        assertTrue(plainRule.compareTo(wildcardRule) < 0)
    }
}
