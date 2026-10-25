package com.github.catvod.crawler

import android.os.Build
import android.util.Log

import com.github.catvod.crawler.python.IPyLoader
import com.github.avbox.osc.base.App
import com.github.avbox.osc.util.AppLog
import com.undcover.freedom.pyramid.PythonLoader
import com.undcover.freedom.pyramid.PythonSpider

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class pyLoader : IPyLoader {
    private var pythonLoader: PythonLoader? = null
    private val spiders: ConcurrentHashMap<String, Spider> = ConcurrentHashMap()
    private var lastConfig: String? = null // 记录上次的配置
    private val clearGeneration: AtomicLong = AtomicLong(0)
    private val creationLock: Any = Any()

    override fun clear() {
        clearGeneration.incrementAndGet()
        spiders.clear()
        lastConfig = null
        recentPyKey = null
        pythonLoader?.invalidate()
    }

    override fun setConfig(jsonStr: String?) {
        if (!isPythonSupported()) {
            Log.w("PyLoader", "python32 is disabled on Android 16+ 32-bit process.")
            return
        }
        if (jsonStr != null && jsonStr != lastConfig) {
            Log.i("PyLoader", "echo-setConfig 初始化json ")
            getPythonLoader().setConfig(jsonStr)
            lastConfig = jsonStr
        }
    }

    private var recentPyKey: String? = null

    override fun setRecentPyKey(key: String?) {
        recentPyKey = key
    }

    override fun getSpider(key: String, cls: String?, ext: String?): Spider {
        if (!isPythonSupported()) {
            Log.w("PyLoader", "python32 is disabled on Android 16+ 32-bit process.")
            return SpiderNull()
        }
        val cached = spiders[key]
        if (cached != null) {
            Log.i("PyLoader", "echo-getSpider spider缓存: $key")
            return cached
        }
        val generation = clearGeneration.get()
        val sp: Spider
        try {
            Log.i("PyLoader", "echo-getSpider url: $cls")
            sp = synchronized(creationLock) {
                spiders[key] ?: getPythonLoader().getSpider(key, cls, ext)
            }
        } catch (th: Throwable) {
            AppLog.e("pyLoader", th)
            return SpiderNull()
        }
        if (sp is SpiderNull) return sp
        if (generation != clearGeneration.get()) return SpiderNull()
        Log.i("PyLoader", "echo-getSpider 加载spider: $key")
        return spiders.putIfAbsent(key, sp) ?: sp
    }

    override fun proxyInvoke(params: Map<String, String>?): Array<Any?>? {
        return proxyInvoke(params, recentPyKey)
    }

    override fun proxyInvoke(params: Map<String, String>?, key: String?): Array<Any?>? {
        if (!isPythonSupported()) return null
        if (key == null || key.isEmpty()) return null
        AppLog.i("echo-recentPyKey$key")
        try {
            val spider = spiders[key]
            if (spider !is PythonSpider) return null
            return SpiderReaper.track(spider) { spider.proxyLocal(params) }
        } catch (th: Throwable) {
            AppLog.i("echo-proxyInvoke_Throwable:---" + th.message)
            AppLog.e("pyLoader", th)
        }
        return null
    }

    private fun getPythonLoader(): PythonLoader {
        val cached = pythonLoader
        if (cached != null) return cached
        val app = requireNotNull(App.getInstance()) { "app instance not ready" }
        val created = PythonLoader.getInstance().setApplication(app)
        pythonLoader = created
        return created
    }

    private fun isPythonSupported(): Boolean {
        if (Build.VERSION.SDK_INT < 36) return true
        if (Build.VERSION.SDK_INT < 23) return true
        return android.os.Process.is64Bit()
    }
}
