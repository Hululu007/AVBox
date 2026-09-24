package com.github.tvbox.osc.util.kv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.reflect.TypeToken;

import org.junit.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 生产类型登记表的**结构**测试(纯 JVM:不实例化 MMKV、不碰任何 Android 存储)。
 *
 * <p>存在理由:`KVKeySpec` 的集合类型靠匿名 `TypeToken` 子类的泛型签名恢复
 * (`TypeToken.getSuperclassTypeParameter` 读的是 class 的 Signature 属性)。R8 只保留了
 * `-keepattributes Signature`,而 `TypeToken` 子类那条规则带 `allowoptimization` ——
 * 所以"release 下泛型签名是否还在"必须**在 R8 后的字节码上**验证,不能只看 debug。
 *
 * <p>跑法:debug 基线 = `gradlew :app:testDebugUnitTest`。
 * ⚠️ 原写的 `:app:testReleaseUnitTest` 在当前 AGP 配置下**已不存在**(工程里只有 testDebugUnitTest),
 * release/R8 验证改为对产物 dex 做静态检查(2026-09-13 实测有效):
 * <pre>
 *   dexdump -a &lt;classes*.dex&gt; | findstr /C:"annotation/Signature"
 * </pre>
 * 判据:每个 `* extends TypeToken` 的匿名子类都应带
 * `VISIBILITY_SYSTEM Ldalvik/annotation/Signature; value={...}`。
 * ⚠️ 不要用"在 dex 里搜完整签名串"的方式判断 —— D8 会把签名**拆成片段**存储
 * (如 "Lcom/google/gson/reflect/TypeToken&lt;" "Ljava/util/HashMap&lt;" "Ljava/lang/String;" "&gt;;&gt;;"),
 * 完整字符串在池里根本不存在,直接搜索必然落空(2026-09-13 亲测踩坑)。
 */
public class KVKeySpecTest {

    private final KVKeySpec spec = new KVKeySpec();

    @Test
    public void registeredListKey_keepsElementTypeSignature() {
        Type listType = spec.typeOf("subscribe_list");
        assertNotNull("订阅列表必须登记类型", listType);
        assertEquals(ArrayList.class, TypeToken.get(listType).getRawType());
        // 关键:元素类型是 String 而不是被擦成 Object
        assertTrue("泛型签名丢失:实际 " + listType,
                listType.toString().contains("java.lang.String"));
    }

    @Test
    public void registeredNestedMapKey_keepsBothTypeArguments() {
        Type nested = spec.typeOf("checked_sources_for_search");
        assertNotNull("嵌套泛型键必须登记类型", nested);
        assertEquals(HashMap.class, TypeToken.get(nested).getRawType());
        String text = nested.toString();
        // 嵌套泛型必须两层都在,否则内层会解成 LinkedTreeMap
        assertTrue("嵌套泛型签名丢失: " + text, text.contains("HashMap<java.lang.String, java.util.HashMap<java.lang.String, java.lang.String>>"));
    }

    @Test
    public void jsonArrayKey_isRegisteredAsJsonArrayNotList() {
        Type jsonArrayType = spec.typeOf("live_group_list");
        assertNotNull("直播分组必须登记类型", jsonArrayType);
        assertEquals(JsonArray.class, TypeToken.get(jsonArrayType).getRawType());
    }

    @Test
    public void primitiveKeys_resolveToTheirBoxedTypes() {
        assertEquals(TypeToken.get(Integer.class).getType(), spec.typeOf("play_type"));
        assertEquals(TypeToken.get(Boolean.class).getType(), spec.typeOf("incognito"));
        assertEquals(TypeToken.get(Boolean.class).getType(), spec.typeOf("nav_live_hidden"));
        assertEquals(TypeToken.get(String.class).getType(), spec.typeOf("api_url"));
    }

    @Test
    public void dynamicKeyFamilies_resolveByPrefix() {
        // live_group_index_<直播源地址> → int
        assertEquals(TypeToken.get(Integer.class).getType(), spec.typeOf("live_group_index_http://example.com/tv"));
        // jsRuntime_* → String
        assertEquals(TypeToken.get(String.class).getType(), spec.typeOf("jsRuntime_spiderA_cookie"));
        // cache_* → String(HTTP 缓存接口,当前无调用方但登记保留)
        assertEquals(TypeToken.get(String.class).getType(), spec.typeOf("cache_rule_key"));
    }

    @Test
    public void newlyAddedSettingsKey_isRegistered() {
        // 禁用手势控制(2026-09-13 新增)必须登记,否则读取要退回"调用侧默认值兜底"
        Map<String, Type> unusedGuard = new HashMap<>();
        assertTrue(unusedGuard.isEmpty());
        assertEquals(TypeToken.get(Boolean.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.GESTURE_CONTROL_DISABLED));
    }

    /**
     * 2026-09-13 修复的回归锁:直播源配置的 header/ua 由 ApiConfig 写入的是 HashMap&lt;String,String&gt;,
     * 一旦登记成 String,读取侧 Gson 会用 String 解析对象原文抛错、被 KV.get(key)(quiet 副本)静默吞成
     * null —— 症状是直播源的 UA/Referer 全部失效。此测试跑真实 KVKeySpec 注册表 + KVDecoder 往返,
     * 保证"写入类型 == 登记类型"。
     */
    @Test
    public void liveWebHeader_roundTripDecodesAsStringMap() {
        com.github.tvbox.osc.util.kvcodec.KVDecoder decoder =
                new com.github.tvbox.osc.util.kvcodec.KVDecoder();
        decoder.setRegistry(spec);
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", "Mozilla/5.0");
        header.put("Referer", "http://example.com/");
        String raw = decoder.encode(header);
        Object decoded = decoder.decode("live_web_header", raw, null);
        assertTrue("应解出 Map,实际 " + (decoded == null ? "null" : decoded.getClass()), decoded instanceof Map);
        assertEquals("Mozilla/5.0", ((Map<?, ?>) decoded).get("User-Agent"));
        assertEquals("http://example.com/", ((Map<?, ?>) decoded).get("Referer"));
    }

    @Test
    public void unknownKey_returnsNull() {
        org.junit.Assert.assertNull(spec.typeOf("definitely_not_a_registered_key"));
    }
}
