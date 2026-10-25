package com.github.avbox.osc.util.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HttpTest {

    @Test
    fun get_returnsBodyForNonFailCodes() = runBlocking {
        val body = Http.executeWithRetry(request(), clientReturning(403, "denied"))
        assertEquals("denied", body)
    }

    @Test
    fun get_throwsHttpExceptionFor404() = runBlocking {
        try {
            Http.executeWithRetry(request(), clientReturning(404, "missing"))
            fail("expected HttpException")
        } catch (e: HttpException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun get_throwsHttpExceptionForServerError() = runBlocking {
        try {
            Http.executeWithRetry(request(), clientReturning(503, "unavailable"))
            fail("expected HttpException")
        } catch (e: HttpException) {
            assertEquals(503, e.code)
        }
    }

    @Test
    fun get_retriesSocketTimeoutUpToFourAttempts() = runBlocking {
        val attempts = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            attempts.incrementAndGet()
            throw SocketTimeoutException("read timed out")
        }.build()
        try {
            Http.executeWithRetry(request(), client)
            fail("expected SocketTimeoutException")
        } catch (e: SocketTimeoutException) {
        }
        assertEquals(4, attempts.get())
    }

    @Test
    fun get_doesNotRetryNonTimeoutFailures() = runBlocking {
        val attempts = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            attempts.incrementAndGet()
            throw ConnectException("connection refused")
        }.build()
        try {
            Http.executeWithRetry(request(), client)
            fail("expected ConnectException")
        } catch (e: ConnectException) {
        }
        assertEquals(1, attempts.get())
    }

    @Test
    fun get_doesNotRetryBodyReadTimeout() = runBlocking {
        val attempts = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            attempts.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("test")
                .body(bodyFailingOnRead())
                .build()
        }.build()
        try {
            Http.executeWithRetry(request(), client)
            fail("expected SocketTimeoutException")
        } catch (e: SocketTimeoutException) {
        }
        assertEquals("读体超时属转换失败(E4),不进重试", 1, attempts.get())
    }

    @Test
    fun get_readsResponseBodyOffCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        val readOn = AtomicReference<Thread>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("test")
                .body(threadRecordingBody("ok", readOn))
                .build()
        }.build()
        assertEquals("ok", Http.executeWithRetry(request(), client))
        val readThread = readOn.get()
        assertTrue("响应体读取不应落在调用线程上", readThread != null && readThread !== caller)
    }

    @Test
    fun getRaw_returnsBytesAndHeaders() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("test")
                .header("Content-Disposition", "attachment; filename=\"a.ass\"")
                .body("你好".toByteArray().toResponseBody(null))
                .build()
        }.build()
        val raw = Http.executeRawWithRetry(request(), client)
        assertEquals("你好", String(raw.body, Charsets.UTF_8))
        assertEquals("attachment; filename=\"a.ass\"", raw.headers["Content-Disposition"])
    }

    @Test
    fun getRaw_throwsHttpExceptionFor404() = runBlocking {
        try {
            Http.executeRawWithRetry(request(), clientReturning(404, "missing"))
            fail("expected HttpException")
        } catch (e: HttpException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun getRaw_readsResponseBodyOffCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        val readOn = AtomicReference<Thread>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("test")
                .body(threadRecordingBody("ok", readOn))
                .build()
        }.build()
        assertEquals("ok", String(Http.executeRawWithRetry(request(), client).body, Charsets.UTF_8))
        val readThread = readOn.get()
        assertTrue("响应体读取不应落在调用线程上", readThread != null && readThread !== caller)
    }

    @Test
    fun getRaw_throwsHttpExceptionForServerError() = runBlocking {
        try {
            Http.executeRawWithRetry(request(), clientReturning(503, "unavailable"))
            fail("expected HttpException")
        } catch (e: HttpException) {
            assertEquals(503, e.code)
        }
    }

    @Test
    fun getRaw_retriesSocketTimeoutUpToFourAttempts() = runBlocking {
        val attempts = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            attempts.incrementAndGet()
            throw SocketTimeoutException("read timed out")
        }.build()
        try {
            Http.executeRawWithRetry(request(), client)
            fail("expected SocketTimeoutException")
        } catch (e: SocketTimeoutException) {
        }
        assertEquals(4, attempts.get())
    }

    @Test
    fun getRaw_returnsEmptyBody() = runBlocking {
        assertEquals(0, Http.executeRawWithRetry(request(), clientReturning(200, "")).body.size)
    }

    @Test
    fun getSync_returnsRawResponseForFailCodes() {
        Http.getSync(request(), clientReturning(404, "missing")).use { response ->
            assertEquals(404, response.code)
            assertEquals("missing", response.body.string())
        }
    }

    @Test
    fun getSync_doesNotRetryOnTimeout() {
        val attempts = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            attempts.incrementAndGet()
            throw SocketTimeoutException("read timed out")
        }.build()
        try {
            Http.getSync(request(), client)
            fail("expected SocketTimeoutException")
        } catch (e: SocketTimeoutException) {
        }
        assertEquals(1, attempts.get())
    }

    @Test
    fun get_cancelPropagatesCancellationWithoutOtherFailure() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw IOException("released")
        }.build()
        var cancelled = false
        var other: Throwable? = null
        val job = launch {
            try {
                Http.executeWithRetry(request(), client)
            } catch (e: CancellationException) {
                cancelled = true
            } catch (e: Throwable) {
                other = e
            }
        }
        yield()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        release.countDown()
        assertTrue(cancelled)
        assertNull(other)
    }

    private fun request(): Request {
        return HttpRequest("https://example.com/api").build()
    }

    private fun clientReturning(code: Int, body: String): OkHttpClient {
        return OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body.toResponseBody(null))
                .build()
        }.build()
    }

    private fun bodyFailingOnRead(): ResponseBody {
        return object : ResponseBody() {
            override fun contentType(): MediaType? = null

            override fun contentLength(): Long = -1

            override fun source(): BufferedSource = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long = throw SocketTimeoutException("read timed out")

                override fun timeout(): Timeout = Timeout.NONE

                override fun close() = Unit
            }.buffer()
        }
    }

    private fun threadRecordingBody(text: String, readOn: AtomicReference<Thread>): ResponseBody {
        return object : ResponseBody() {
            override fun contentType(): MediaType? = null

            override fun contentLength(): Long = text.toByteArray().size.toLong()

            override fun source(): BufferedSource {
                readOn.set(Thread.currentThread())
                return Buffer().apply { writeUtf8(text) }
            }
        }
    }
}
