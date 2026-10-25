package com.github.catvod.crawler

import android.content.Context
import android.os.Environment
import android.text.TextUtils
import android.util.Log

import com.github.catvod.Proxy
import com.github.catvod.net.OkHttp
import com.github.avbox.osc.io.FileUtils
import com.github.avbox.osc.server.ControlManager
import com.github.avbox.osc.server.RemoteServer
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.boot.BootGuard
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.MD5
import com.github.avbox.osc.util.RegexUtils
import com.github.avbox.osc.util.SpiderInitWatch
import com.github.avbox.osc.util.net.Http

import org.json.JSONObject

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.reflect.Method
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import dalvik.system.DexClassLoader

class JarLoader {

    private val loaders = ConcurrentHashMap<String, DexClassLoader>()
    private val proxyMethods = ConcurrentHashMap<String, Method>()
    private val danmuClickMethods = ConcurrentHashMap<String, Method>()
    private val danmuLongClickMethods = ConcurrentHashMap<String, Method>()
    private val spiders = ConcurrentHashMap<String, Spider>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val siteJarKeys = ConcurrentHashMap<String, String>()
    private val aliases = ConcurrentHashMap<String, String>()
    private val liveJars = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var recent: String = MAIN_KEY

    private val clearGeneration: AtomicLong = AtomicLong(0)

    fun load(cache: String): Boolean {
        val success = load(MAIN_KEY, File(cache))
        if (success) recent = MAIN_KEY
        return success
    }

    fun setRecentJarKey(key: String?) {
        if (key.isNullOrEmpty()) return
        recent = realKey(key)
        injectProxyPort(loaders[recent])
    }

    fun loadLiveJar(jar: String?) {
        val key = jarKey(jar)
        liveJars.add(key)
        parseJar(key, jar)
        setRecentJarKey(key)
    }

    fun clear() {
        clearGeneration.incrementAndGet()
        val stale = ArrayList(spiders.values)
        loaders.clear()
        proxyMethods.clear()
        danmuClickMethods.clear()
        danmuLongClickMethods.clear()
        spiders.clear()
        siteJarKeys.clear()
        aliases.clear()
        liveJars.clear()
        recent = MAIN_KEY
        for (spider in stale) {
            SpiderReaper.retire(spider)
        }
    }

    private fun load(key: String, file: File): Boolean {
        if (Thread.interrupted()) return false
        if (!exists(file)) return false
        if (loaders.containsKey(key)) return true
        if (BootGuard.isJarDisabled(file.absolutePath)) {
            AppLog.i("echo-boot-guard: blocked jar key=" + key + " file=" + file.absolutePath)
            return false
        }
        if (key != MAIN_KEY) {
            val scope = if (liveJars.contains(key)) BootGuard.SCOPE_LIVE else BootGuard.SCOPE_VOD
            BootGuard.onJarLoadStart(file.absolutePath, scope)
        }
        try {
            file.setReadOnly()
            val cachePath = jarDir().absolutePath
            val loader = DexClassLoader(file.absolutePath, cachePath, cachePath, requireNotNull(AppContextHolder.context()) { "app context not ready" }.classLoader)
            invokeInit(loader)
            invokeProxy(key, loader)
            invokeDanmaku(key, loader)
            injectProxyPort(loader)
            loaders[key] = loader
            BootGuard.scheduleStableRunReset()
            AppLog.i("echo--jar-load success key=" + key + ", file=" + file.absolutePath)
            return true
        } catch (e: Throwable) {
            AppLog.i("echo--jar-load error key=" + key + ", msg=" + e.javaClass.simpleName + ":" + e.message)
            AppLog.e("JarLoader", e)
            return false
        }
    }

    private fun invokeInit(loader: DexClassLoader) {
        try {
            val clz = loader.loadClass("com.github.catvod.spider.Init")
            val method = clz.getMethod("init", Context::class.java)
            method.invoke(null, AppContextHolder.context())
        } catch (e: Throwable) {
            AppLog.e("JarLoader", e)
        }
    }

    private fun invokeProxy(key: String, loader: DexClassLoader) {
        try {
            val clz = loader.loadClass("com.github.catvod.spider.Proxy")
            val method = clz.getMethod("proxy", Map::class.java)
            proxyMethods[key] = method
        } catch (e: Throwable) {
            AppLog.e("echo-proxy-jar: register fail key=" + key + " | " + e)
        }
    }

    private fun invokeDanmaku(key: String, loader: DexClassLoader) {
        try {
            val clz = loader.loadClass("com.github.catvod.spider.Danmaku")
            try {
                danmuClickMethods[key] = clz.getMethod("onClick", String::class.java, String::class.java)
            } catch (ignored: Throwable) {
                AppLog.d("JarLoader", "danmaku onClick method not found")
            }
            try {
                danmuLongClickMethods[key] = clz.getMethod("onLongClick", String::class.java, String::class.java)
            } catch (ignored: Throwable) {
                AppLog.d("JarLoader", "danmaku onLongClick method not found")
            }
        } catch (ignored: Throwable) {
            AppLog.d("JarLoader", "danmaku class not found in jar")
        }
    }

    fun parseJar(key: String?, jar: String?) {
        if (key.isNullOrEmpty() || jar.isNullOrEmpty()) return
        if (loaders.containsKey(key)) return
        val lock = lock(key)
        synchronized(lock) {
            if (loaders.containsKey(key)) return
            var source = jar
            var md5 = ""
            val texts = RegexUtils.getPattern(";md5;").split(source)
            if (texts.size > 1) {
                source = texts[0]
                md5 = texts[1].trim { it <= ' ' }
            }
            aliases[jarKey(source)] = key
            if (md5.startsWith("http")) {
                val value = OkHttp.string(md5, null)
                md5 = value?.trim { it <= ' ' } ?: ""
            }
            val file = fileForJar(source)
            if (!TextUtils.isEmpty(md5) && exists(file) && MD5.fileMd5(file).equals(md5, ignoreCase = true)) {
                load(key, file)
            } else if (TextUtils.isEmpty(md5) && exists(file) && !FileUtils.isWeekAgo(file)) {
                load(key, file)
            } else if (source.startsWith("http")) {
                load(key, download(source, file))
            } else if (source.startsWith("assets")) {
                load(key, copyAsset(source, file))
            } else if (source.startsWith("file")) {
                load(key, local(source))
            } else if (source.startsWith("clan://")) {
                load(key, download(clanToAddress(source), file))
            }
        }
    }

    fun dex(jar: String?): DexClassLoader? {
        try {
            val key = jarKey(jar)
            parseJar(key, jar)
            return loaders[key]
        } catch (e: Throwable) {
            AppLog.e("JarLoader", e)
            return null
        }
    }

    fun getSpider(key: String?, api: String?, ext: String?, jar: String?): Spider {
        val spiderKey = key ?: ""
        val spiderApi = api ?: ""
        val spiderExt = ext ?: ""
        val spiderJar = jar ?: ""
        if (TextUtils.isEmpty(spiderApi)) return SpiderNull()

        val jaKey = if (TextUtils.isEmpty(spiderJar)) MAIN_KEY else jarKey(spiderJar)
        val spKey = jaKey + spiderKey
        recent = jaKey
        siteJarKeys[spiderKey] = jaKey
        injectProxyPort(loaders[jaKey])

        val cached = spiders[spKey]
        if (cached != null) {
            Log.i(TAG, "getSpider cached key=" + spKey)
            return cached
        }

        val lock = locks.computeIfAbsent(spKey) { Any() }
        val generation = clearGeneration.get()
        synchronized(lock) {
            val cachedAgain = spiders[spKey]
            if (cachedAgain != null) return cachedAgain
            try {
                if (MAIN_KEY != jaKey) parseJar(jaKey, spiderJar)
                val loader = loaders[jaKey]
                if (loader == null) return SpiderNull()
                val spider = loader.loadClass("com.github.catvod.spider." + className(spiderApi)).newInstance() as Spider
                spider.siteKey = spiderKey
                spider.initApi(SpiderApi())
                SpiderInitWatch.begin()
                spider.init(AppContextHolder.context(), spiderExt)
                SpiderInitWatch.end()
                if (generation != clearGeneration.get()) return SpiderNull()
                spiders[spKey] = spider
                Log.i(TAG, "getSpider success key=" + spKey)
                return spider
            } catch (e: Throwable) {
                Log.i(TAG, "getSpider error key=" + spKey + ", msg=" + e.message)
                AppLog.e("JarLoader", e)
                return SpiderNull()
            }
        }
    }

    fun searchDanmuUi(name: String, episode: String, longClick: Boolean) {
        try {
            val methods = if (longClick) danmuLongClickMethods else danmuClickMethods
            val method = methods[recent] ?: methods["main"]
            if (method == null) return
            method.invoke(null, name, episode)
        } catch (e: Throwable) {
            AppLog.e("JarLoader", e)
        }
    }

    fun hasDanmuSearchUi(): Boolean {
        return danmuClickMethods.containsKey(recent) || danmuLongClickMethods.containsKey(recent)
    }

    fun jsonExt(key: String, jxs: LinkedHashMap<String, String>, url: String): JSONObject? {
        try {
            val clz = loadParserClass("com.github.catvod.parser.Json" + key)
            val method = clz.getMethod("parse", LinkedHashMap::class.java, String::class.java)
            return method.invoke(null, jxs, url) as JSONObject?
        } catch (e: Throwable) {
            AppLog.e("JarLoader", e)
            return null
        }
    }

    fun jsonExtMix(flag: String, key: String, name: String, jxs: LinkedHashMap<String, HashMap<String, String>>, url: String): JSONObject? {
        try {
            val clz = loadParserClass("com.github.catvod.parser.Mix" + key)
            val method = clz.getMethod("parse", LinkedHashMap::class.java, String::class.java, String::class.java, String::class.java)
            return method.invoke(null, jxs, name, flag, url) as JSONObject?
        } catch (e: Throwable) {
            AppLog.e("JarLoader", e)
            return null
        }
    }

    fun proxyInvoke(params: Map<String, String>?): Array<Any?>? {
        val siteKey = params?.get("siteKey")
        if (!siteKey.isNullOrEmpty()) {
            val key = siteKey
            val result = proxyInvoke(proxyMethods[requireNotNull(siteJarKeys[key]) { "jar key not found for siteKey" }], params)
            if (result != null) return result
        }
        var result = proxyInvoke(proxyMethods[recent], params)
        if (result != null) return result
        for (entry in proxyMethods.entries) {
            if (entry.key == recent) continue
            result = proxyInvoke(entry.value, params)
            if (result != null) return result
        }
        AppLog.e("echo-proxy-jar: no result, siteKey=" + siteKey + " recent=" + recent + " jars=" + proxyMethods.keys)
        return null
    }

    private fun proxyInvoke(method: Method?, params: Map<String, String>?): Array<Any?>? {
        if (method == null) return null
        try {
            val args = if (params == null) null else HashMap(params)
            args?.remove("siteKey")
            return method.invoke(null, args) as Array<Any?>?
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            val bad = StringBuilder()
            if (params != null) {
                for (entry in params.entries) {
                    val v = entry.value
                    if (v == null) continue
                    for (i in v.indices) {
                        if (v[i].code > 0x7f) {
                            if (bad.isNotEmpty()) bad.append(',')
                            bad.append(entry.key)
                            break
                        }
                    }
                }
            }
            AppLog.e("echo-proxy-jar: invoke error | " + cause + " | nonAsciiKeys=" + bad)
            return null
        }
    }

    private fun requireRecentLoader(): DexClassLoader {
        var loader = loaders[recent]
        if (loader == null) loader = loaders[MAIN_KEY]
        if (loader == null) throw IllegalStateException("No jar loaded for recent key: " + recent)
        return loader
    }

    @Throws(ClassNotFoundException::class)
    private fun loadParserClass(name: String): Class<*> {
        var loader = loaders[recent]
        if (loader != null) {
            try {
                return loader.loadClass(name)
            } catch (ignored: ClassNotFoundException) {
                AppLog.d("JarLoader", "class not in cached loader: " + name)
            }
        }
        loader = loaders[MAIN_KEY]
        if (loader != null) return loader.loadClass(name)
        throw ClassNotFoundException(name)
    }

    private fun download(url: String, file: File): File {
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        try {
            val response = Http.getSync(url)
            val input = response.body.byteStream()
            inputStream = input
            val output = FileOutputStream(create(file))
            outputStream = output
            val buffer = ByteArray(16384)
            var length = 0
            while (input.read(buffer).also { length = it } != -1) {
                if (Thread.interrupted()) return file
                output.write(buffer, 0, length)
            }
            output.flush()
        } catch (e: Throwable) {
            AppLog.e("JarLoader", e)
        } finally {
            close(inputStream)
            close(outputStream)
        }
        return file
    }

    private fun copyAsset(url: String, file: File): File {
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        try {
            val path = url.replace("assets://", "").replace("assets/", "")
            val input = requireNotNull(AppContextHolder.context()) { "app context not ready" }.assets.open(path)
            inputStream = input
            val output = FileOutputStream(create(file))
            outputStream = output
            val buffer = ByteArray(16384)
            var length = 0
            while (input.read(buffer).also { length = it } != -1) {
                output.write(buffer, 0, length)
            }
            output.flush()
        } catch (e: Throwable) {
            AppLog.e("JarLoader", e)
        } finally {
            close(inputStream)
            close(outputStream)
        }
        return file
    }

    private fun local(path: String): File {
        val normalized = path.replace("file:/", "")
        val file = File(Environment.getExternalStorageDirectory(), normalized)
        return if (file.exists()) file else File(normalized)
    }

    private fun fileForJar(jar: String): File {
        return File(jarDir(), jarKey(jar) + ".jar")
    }

    private fun jarDir(): File {
        val dir = File(requireNotNull(AppContextHolder.context()) { "app context not ready" }.cacheDir, "jar")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Throws(Exception::class)
    private fun create(file: File): File {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        if (file.exists()) file.delete()
        file.createNewFile()
        file.setReadable(true)
        file.setWritable(true)
        file.setExecutable(true)
        return file
    }

    private fun exists(file: File?): Boolean {
        return file != null && file.exists() && file.length() > 0
    }

    private fun lock(key: String): Any {
        val lock = locks[key]
        if (lock != null) return lock
        val created = Any()
        val old = locks.putIfAbsent(key, created)
        return old ?: created
    }

    private fun jarKey(jar: String?): String {
        val key = MD5.string2MD5(jar ?: "")
        return if (key.isNullOrEmpty()) MAIN_KEY else key
    }

    private fun realKey(key: String): String {
        val alias = aliases[key]
        return if (alias.isNullOrEmpty()) key else alias
    }

    private fun className(api: String): String {
        return if (api.contains("csp_")) RegexUtils.getPattern("csp_").split(api)[1] else api
    }

    private fun clanToAddress(url: String): String {
        if (url.startsWith("clan://localhost/")) {
            return url.replace("clan://localhost/", ControlManager.get().getAddress(true) + "file/")
        }
        if (url.startsWith("clan://")) {
            val text = url.substring(7)
            val index = text.indexOf('/')
            if (index > 0) return "http://" + text.substring(0, index) + "/file/" + text.substring(index + 1)
        }
        return url
    }

    private fun injectProxyPort(loader: DexClassLoader?) {
        Proxy.set(getServerPort())
        if (loader == null) return
        try {
            val proxy = loader.loadClass("com.github.catvod.Proxy")
            val set = proxy.getMethod("set", Int::class.javaPrimitiveType)
            set.invoke(null, getServerPort())
        } catch (ignored: Throwable) {
            AppLog.d("JarLoader", "inject proxy port into jar failed")
        }
    }

    private fun getServerPort(): Int {
        try {
            val address = ControlManager.get().getAddress(true)
            if (address.startsWith("http://127.0.0.1:")) {
                val baseUrl = if (address.endsWith("/")) address.substring(0, address.length - 1) else address
                return baseUrl.substring(baseUrl.lastIndexOf(":") + 1).toInt()
            }
        } catch (ignored: Throwable) {
            AppLog.d("JarLoader", "parse server port failed, use RemoteServer.serverPort")
        }
        return RemoteServer.serverPort
    }

    private fun close(closeable: Closeable?) {
        try {
            if (closeable != null) closeable.close()
        } catch (ignored: Throwable) {
            AppLog.d("JarLoader", "close failed")
        }
    }

    companion object {

        private const val TAG: String = "JarLoader"
        private const val MAIN_KEY: String = "main"
    }
}
