package com.github.catvod.crawler

import android.util.Log

import com.github.catvod.crawler.js.JsSpider
import com.github.avbox.osc.io.FileUtils
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.MD5
import com.github.avbox.osc.util.RegexUtils
import com.github.avbox.osc.util.net.Http

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

import dalvik.system.DexClassLoader
import com.whl.quickjs.wrapper.QuickJSContext

class JsLoader {

    @Volatile
    private var recentKey: String = ""

    private val clearGeneration: AtomicLong = AtomicLong(0)
    private val creationLock: Any = Any()
    private val destroyExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "js-loader-destroy") }

    fun clear() {
        clearGeneration.incrementAndGet()
        val stale = ArrayList(spiders.values)
        spiders.clear()
        classes.clear()
        recentKey = ""
        for (spider in stale) {
            try {
                spider.cancelByTag()
            } catch (ignored: Throwable) {
                AppLog.d("JsLoader", "cancel spider failed")
            }
            SpiderReaper.retire(spider)
        }
    }

    private fun loadClassLoader(jar: String, key: String): Boolean {
        var success = false
        var classInit: Class<*>? = null
        try {
            val cacheDir = File(requireNotNull(AppContextHolder.context()) { "app context not ready" }.cacheDir.absolutePath + "/catvod_jsapi")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val classLoader = DexClassLoader(jar, cacheDir.absolutePath, null, requireNotNull(AppContextHolder.context()) { "app context not ready" }.classLoader)
            var count = 0
            do {
                try {
                    try {
                        val clz = classLoader.loadClass("com.github.catvod.js.Function")
                        classInit = clz
                        clz.getDeclaredConstructor(QuickJSContext::class.java)
                        Log.i("JSLoader", "echo-load_com.github.catvod.js.Function")
                    } catch (ignored: Throwable) {
                        val clz = classLoader.loadClass("com.github.catvod.js.Method")
                        classInit = clz
                        clz.getDeclaredConstructor(QuickJSContext::class.java)
                        Log.i("JSLoader", "echo-load_com.github.catvod.js.Method")
                    }
                    if (classInit != null) {
                        Log.i("JSLoader", "echo-自定义jsapi代码加载成功!")
                        success = true
                        break
                    }
                    Thread.sleep(200)
                } catch (th: Throwable) {
                    AppLog.e("JsLoader", th)
                }
                count++
            } while (count < 5)

            if (success) {
                classes[key] = requireNotNull(classInit) { "class init is null" }
            }
        } catch (th: Throwable) {
            AppLog.e("JsLoader", th)
        }
        return success
    }

    private fun loadJarInternal(jar: String, md5: String, key: String): Class<*>? {
        if (classes.containsKey(key)) {
            Log.i("JSLoader", "echo-loadJarInternal cached")
            return classes[key]
        }
        val cache = File(requireNotNull(AppContextHolder.context()) { "app context not ready" }.filesDir.absolutePath + "/csp/" + key + ".jar")
        try {
            val parent = cache.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
        } catch (ignored: Throwable) {
            AppLog.d("JsLoader", "create csp dir failed")
        }
        if (!md5.isEmpty()) {
            if (cache.exists() && MD5.fileMd5(cache).equals(md5, ignoreCase = true)) {
                loadClassLoader(cache.absolutePath, key)
                return classes[key]
            }
        } else {
            if (cache.exists() && !FileUtils.isWeekAgo(cache)) {
                if (loadClassLoader(cache.absolutePath, key)) {
                    return classes[key]
                }
            }
        }
        try {
            val response = Http.getSync(jar)
            val inputStream = response.body.byteStream()
            val outputStream = FileOutputStream(cache)
            try {
                val buffer = ByteArray(2048)
                var length = 0
                while (inputStream.read(buffer).also { length = it } > 0) {
                    outputStream.write(buffer, 0, length)
                }
            } finally {
                try {
                    inputStream.close()
                    outputStream.close()
                } catch (e: Exception) {
                    AppLog.e("JsLoader", e)
                }
            }
            loadClassLoader(cache.absolutePath, key)
            return classes[key]
        } catch (e: Throwable) {
            AppLog.e("JsLoader", e)
        }
        return null
    }

    fun getSpider(key: String?, api: String?, ext: String?, jar: String?): Spider {
        recentKey = requireNotNull(key) { "site key is null" }
        spiders[key]?.let {
            Log.i("JSLoader", "echo-getSpider cached " + key)
            return it
        }
        val generation = clearGeneration.get()
        synchronized(creationLock) {
            val cached = spiders[key]
            if (cached != null) {
                Log.i("JSLoader", "echo-getSpider cached " + key)
                return cached
            }
            var classLoader: Class<*>? = null
            if (!jar.isNullOrEmpty()) {
                val urls = RegexUtils.getPattern(";md5;").split(jar)
                val jarUrl = urls[0]
                val jarKey = requireNotNull(MD5.string2MD5(jarUrl)) { "jar md5 is null" }
                val jarMd5 = if (urls.size > 1) urls[1].trim { it <= ' ' } else ""
                classLoader = loadJarInternal(jarUrl, jarMd5, jarKey)
            }
            var sp: Spider? = null
            try {
                Log.i("JSLoader", "echo-getSpider load")
                val created = JsSpider(key, requireNotNull(api) { "js api is null" }, classLoader)
                sp = created
                created.siteKey = key
                created.init(AppContextHolder.context(), ext)
                if (generation == clearGeneration.get()) {
                    spiders[key] = created
                    return created
                }
                destroyLater(created)
                return SpiderNull()
            } catch (th: Throwable) {
                AppLog.i("echo-getSpider-error " + th.message)
                sp?.let { destroyLater(it) }
            }
            return SpiderNull()
        }
    }

    private fun destroyLater(spider: Spider) {
        destroyExecutor.execute {
            try {
                spider.cancelByTag()
                spider.destroy()
            } catch (ignored: Throwable) {
                AppLog.d("JsLoader", "destroy spider failed")
            }
        }
    }

    fun proxyInvoke(params: Map<String, String>?): Array<Any?>? {
        try {
            val proxyFun = spiders[recentKey]
            if (proxyFun != null) {
                return SpiderReaper.track(proxyFun) { proxyFun.proxyLocal(params) }
            }
        } catch (th: Throwable) {
            AppLog.e("JsLoader", "proxy invoke failed", th)
        }
        return null
    }

    companion object {

        private val spiders = ConcurrentHashMap<String, Spider>()
        private val classes = ConcurrentHashMap<String, Class<*>>()

        @JvmStatic
        fun destroy() {
            for (spider in spiders.values) {
                spider.cancelByTag()
                spider.destroy()
            }
        }

        @JvmStatic
        fun stopAll() {
            for (spider in spiders.values) {
                spider.cancelByTag()
            }
        }
    }
}
