package com.github.tvbox.osc.bean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;

import org.junit.Test;

import java.util.List;

/**
 * [Depot] 多仓条目解析的纯 JVM 单测。
 *
 * <p>选点理由:多仓地址是用户手填的第三方链接,写法极不统一(url / api / 裸字符串三种),
 * 解析错了不报错 —— 只是仓里的子源少几条或整个仓被当成普通配置加载失败,真机上没有堆栈可查。
 *
 * <p>⚠️ 这里同时是 {@code android.text.TextUtils} 陷阱的回归锁:单测开了
 * {@code returnDefaultValues},用 TextUtils.isEmpty 判空会**静默返回 false**,
 * 空地址过滤失效 —— 表现为"单测全绿、真机才过滤"。所以 {@link Depot} 必须用本地判空,
 * 本类的 {@link #arrayFrom_skipsEntriesWithoutUrl()} 就是钉死这一点的。
 */
public class DepotTest {

    private static JsonArray arr(String text) {
        return new Gson().fromJson(text, JsonArray.class);
    }

    @Test
    public void arrayFrom_readsUrlNameOptional() {
        List<Depot> items = Depot.arrayFrom(arr(
                "[{\"name\":\"仓A\",\"url\":\"http://a/1\"},{\"url\":\"http://b/2\"}]"));

        assertEquals(2, items.size());
        assertEquals("仓A", items.get(0).getName());
        assertEquals("http://a/1", items.get(0).getUrl());
        // 没写 name 时用地址兜底当显示名,否则切换界面会出现空名字的线路
        assertEquals("http://b/2", items.get(1).getName());
    }

    @Test
    public void arrayFrom_supportsApiFieldAndBareString() {
        List<Depot> items = Depot.arrayFrom(arr(
                "[{\"name\":\"x\",\"api\":\"http://d/4\"},\"http://c/3\"]"));

        assertEquals(2, items.size());
        assertEquals("x", items.get(0).getName());
        assertEquals("http://d/4", items.get(0).getUrl());
        assertEquals("http://c/3", items.get(1).getUrl());
    }

    /** url 为空/缺失且没有 api 兜底时整条丢弃:空地址点开必然失败 */
    @Test
    public void arrayFrom_skipsEntriesWithoutUrl() {
        List<Depot> items = Depot.arrayFrom(arr(
                "[{\"name\":\"empty\",\"url\":\"\"},{\"name\":\"noUrl\"},{\"url\":\"http://ok/1\"}]"));

        assertEquals(1, items.size());
        assertEquals("http://ok/1", items.get(0).getUrl());
    }

    /** 数组里出现 null / 数字 / 嵌套数组等乱数据时,坏条目丢掉、好条目照常保留 */
    @Test
    public void arrayFrom_toleratesGarbageEntries() {
        List<Depot> items = Depot.arrayFrom(arr(
                "[null,123,[\"http://nested\"],{\"url\":123},{\"url\":\"http://ok/1\"}]"));

        assertEquals(1, items.size());
        assertEquals("http://ok/1", items.get(0).getUrl());
    }

    /** name 不是字符串(数字/布尔)按"没写"处理并回落地址,不能抛异常也不能留个 "123" 当名字 */
    @Test
    public void arrayFrom_nonStringNameFallsBackToUrl() {
        List<Depot> items = Depot.arrayFrom(arr("[{\"url\":\"http://a/1\",\"name\":123}]"));

        assertEquals(1, items.size());
        assertEquals("http://a/1", items.get(0).getName());
    }

    @Test
    public void arrayFrom_nullArrayIsEmptyNotCrash() {
        assertTrue(Depot.arrayFrom(null).isEmpty());
        assertTrue(Depot.arrayFrom(arr("[]")).isEmpty());
    }

    /** 地址两端空白要修掉:手填配置里带空格很常见,带空格会被当成"另一个源" */
    @Test
    public void getUrl_trimsWhitespace() {
        List<Depot> items = Depot.arrayFrom(arr("[{\"name\":\" x \",\"url\":\"  http://a/1  \"}]"));

        assertEquals(1, items.size());
        assertEquals("http://a/1", items.get(0).getUrl());
        assertEquals("x", items.get(0).getName());
    }
}
