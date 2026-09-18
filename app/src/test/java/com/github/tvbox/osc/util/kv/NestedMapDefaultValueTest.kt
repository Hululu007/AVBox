package com.github.tvbox.osc.util.kv

import com.github.tvbox.osc.util.kvcodec.KVDecoder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 嵌套 Map 的两条读取路径:带裸 `HashMap<>()` 默认值时内层会退化成 Gson 的 LinkedTreeMap
 * (Java 侧 `mCheckSourcesForSearch` 强转 HashMap 抛异常并被 catch 成"读不到"),走登记表泛型才是 HashMap。
 * 搜索站点选择的读取因此必须走登记表那条(见 `util/SearchSettings.currentSelection`)。
 */
class NestedMapDefaultValueTest {

    @Test
    fun rawHashMapDefault_degradesInnerMapType() {
        val decoder = KVDecoder()
        decoder.setRegistry(KVKeySpec())
        val stored = HashMap<String, HashMap<String, String>>()
        stored["api"] = hashMapOf("site" to "1")
        val raw = decoder.encode(stored)

        val byDefault = decoder.decode("checked_sources_for_search", raw, HashMap<String, HashMap<String, String>>())
        val inner = (byDefault as Map<*, *>)["api"]
        assertFalse("裸 HashMap 默认值下内层不应是 HashMap", inner is HashMap<*, *>)

        val byRegistry = decoder.decode<HashMap<String, HashMap<String, String>>?>(
            "checked_sources_for_search",
            raw,
            null,
        )
        assertTrue("走登记表读取时内层必须是 HashMap", (byRegistry as Map<*, *>)["api"] is HashMap<*, *>)
    }
}
