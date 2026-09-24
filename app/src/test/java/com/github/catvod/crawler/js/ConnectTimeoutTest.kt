package com.github.catvod.crawler.js

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * JS 爬虫 req() 的 timeout 回归。
 *
 * 存在理由:Req.getTimeout() 原先没有任何消费方,爬虫传 timeout 无效、慢接口 10s 必超时;
 * 超时是 client 级设置,只能从基座 client 派生。
 */
class ConnectTimeoutTest {

    @Test
    fun withTimeout_appliesCrawlerTimeout() {
        val derived = Connect.withTimeout(Req.objectFrom("{\"timeout\":30000}"), OkHttpClient.Builder().build())
        assertEquals(30_000, derived.readTimeoutMillis)
        assertEquals(30_000, derived.connectTimeoutMillis)
        assertEquals(30_000, derived.writeTimeoutMillis)
    }

    @Test
    fun withTimeout_reusesBaseWhenNothingToApply() {
        val base = OkHttpClient.Builder().build()
        assertSame(base, Connect.withTimeout(Req.objectFrom("{}"), base))
        assertSame(base, Connect.withTimeout(Req.objectFrom("{\"timeout\":10000}"), base))
        // 非正数一律回落默认值:负数会被 checkDuration 拒,0 在 OkHttp 里表示"不超时",这里不采用该语义(见 features.md 取舍)
        assertSame(base, Connect.withTimeout(Req.objectFrom("{\"timeout\":0}"), base))
        assertSame(base, Connect.withTimeout(Req.objectFrom("{\"timeout\":-1}"), base))
    }

    @Test
    fun derivedClientKeepsSharedDispatcherSoTagCancelStillWorks() {
        // Connect.cancelByTag 在基座 client 上遍历 queued/runningCalls 来取消 JS 请求:
        // 派生 client 一旦不再共享 dispatcher/连接池,搜索页 stopAll 式取消就会静默失效
        val base = OkHttpClient.Builder().build()
        val derived = Connect.withTimeout(Req.objectFrom("{\"timeout\":3000}"), base)
        assertSame(base.dispatcher, derived.dispatcher)
        assertSame(base.connectionPool, derived.connectionPool)
    }

    @Test
    fun twoBuildsFromOneBuilderShareDispatcher() {
        // OkGoHelper 的 defaultClient/noRedirectClient 是同一个 Builder build 两次;
        // cancelByTag 的两条遍历路径(默认 client、noRedirectClient)靠这一点才能互相看到在途请求
        val builder = OkHttpClient.Builder()
        val defaultClient = builder.build()
        builder.followRedirects(false).followSslRedirects(false)
        val noRedirectClient = builder.build()
        assertSame(defaultClient.dispatcher, noRedirectClient.dispatcher)
    }

    @Test
    fun withTimeout_keepsBaseRedirectSetting() {
        val noRedirect = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        val derived = Connect.withTimeout(Req.objectFrom("{\"timeout\":3000}"), noRedirect)
        assertNotSame(noRedirect, derived)
        assertFalse(derived.followRedirects)
    }
}
