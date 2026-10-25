package com.github.catvod.crawler.js

import android.content.Context
import android.text.TextUtils
import android.util.Base64

import com.github.catvod.crawler.Spider
import com.github.avbox.osc.io.FileUtils
import com.github.avbox.osc.net.ProxyHeaderPolicy
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.MD5
import com.github.avbox.osc.util.RegexUtils

import com.whl.quickjs.wrapper.ContextSetter
import com.whl.quickjs.wrapper.Function
import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSMethod
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.JSUtils
import com.whl.quickjs.wrapper.ModuleLoader
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.UriUtil

import org.json.JSONException
import org.json.JSONObject

import java.io.ByteArrayInputStream
import java.lang.reflect.Method
import java.nio.charset.Charset
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class JsSpider : Spider {

    private val executor: ExecutorService
    private val dex: Class<*>?
    private var ctx: QuickJSContext? = null
    private var jsObject: JSObject? = null
    private var global: Global? = null
    private val key: String
    private val api: String
    private var cat: Boolean = false
    private var emptyModuleBytecode: ByteArray? = null
    private val destroyed = AtomicBoolean(false)

    @Throws(Exception::class)
    constructor(key: String, api: String, cls: Class<*>?) {
        this.key = "J" + MD5.encode(key)
        this.executor = Executors.newSingleThreadExecutor()
        this.api = api
        this.dex = cls
        try {
            initializeJS()
        } catch (th: Throwable) {
            destroyed.set(true)
            try {
                ctx?.destroy()
            } catch (ignored: Throwable) {
                AppLog.d("JsSpider", "cleanup ctx after init failure failed")
            }
            executor.shutdownNow()
            throw th
        }
    }

    override fun cancelByTag() {
        global?.cancelPending()
        Connect.cancelByTag(global?.getHttpTag() ?: "js_okhttp_tag")
    }

    private fun createObject(): JSObject {
        return requireNotNull(ctx) { "js context not ready" }.createNewJSObject()
    }

    private fun createArray(): JSArray {
        return requireNotNull(ctx) { "js context not ready" }.createNewJSArray()
    }

    private fun set(obj: JSObject, name: String, value: Any?) {
        requireNotNull(ctx) { "js context not ready" }.setProperty(obj, name, value)
    }

    private fun get(obj: JSObject, name: String): Any? {
        return requireNotNull(ctx) { "js context not ready" }.getProperty(obj, name)
    }

    private fun bind(target: JSObject, receiver: Any) {
        for (method in receiver.javaClass.methods) {
            if (method.isAnnotationPresent(ContextSetter::class.java)) {
                try {
                    method.invoke(receiver, ctx)
                } catch (ignored: Throwable) {
                    AppLog.d("JsSpider", "context setter invoke failed")
                }
            }
        }
        for (method in receiver.javaClass.methods) {
            if (!isQuickJsMethod(method)) continue
            val name = methodName(method)
            set(target, name, object : JSCallFunction {
                override fun call(vararg args: Any?): Any? {
                    try {
                        return method.invoke(receiver, *args)
                    } catch (ignored: Throwable) {
                        return null
                    }
                }
            })
        }
    }

    private fun isQuickJsMethod(method: Method): Boolean {
        return method.isAnnotationPresent(Function::class.java) || method.isAnnotationPresent(JSMethod::class.java)
    }

    private fun methodName(method: Method): String {
        val function = method.getAnnotation(Function::class.java)
        if (function != null && !TextUtils.isEmpty(function.name)) return function.name
        return method.name
    }

    private fun submit(runnable: Runnable) {
        if (!destroyed.get()) executor.submit(runnable)
    }

    private fun <T> submit(callable: Callable<T>): Future<T> {
        return executor.submit(callable)
    }

    private fun <T> submitAndWait(task: Callable<T>): T? {
        try {
            return executor.submit(task).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            AppLog.i("echo-js-submitAndWait-interrupted")
            return null
        } catch (e: Exception) {
            AppLog.i("echo-js-submitAndWait-failed " + e)
            return null
        }
    }

    private fun call(func: String, vararg args: Any?): Any? {
        val target = jsObject ?: return null
        if (destroyed.get()) return null
        try {
            val pending = executor.submit(Callable { Async.run(target, func, args) })
            val result = pending.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return result.get(CALL_TIMEOUT_MS)
        } catch (e: Exception) {
            AppLog.i("Executor 提交或等待失败 " + e)
            return null
        }
    }

    private fun cfg(ext: String?): JSObject {
        val cfg = createObject()
        set(cfg, "stype", 3)
        set(cfg, "skey", if (TextUtils.isEmpty(siteKey)) key else siteKey)
        if (Json.invalid(ext)) set(cfg, "ext", ext)
        else set(cfg, "ext", requireNotNull(ctx) { "js context not ready" }.parse(ext) as JSObject)
        return cfg
    }

    override fun init(context: Context?, extend: String?) {
        try {
            global?.setSiteKey(siteKey)
            if (cat) call("init", submitAndWait(Callable { cfg(extend) }))
            else call("init", if (Json.valid(extend)) requireNotNull(ctx) { "js context not ready" }.parse(extend) else extend)
        } catch (e: Exception) {
            AppLog.e("JsSpider", "init js spider failed", e)
        }
    }

    override fun homeContent(filter: Boolean): String? {
        try {
            return call("home", filter) as String?
        } catch (e: Exception) {
            return null
        }
    }

    override fun homeVideoContent(): String? {
        try {
            return call("homeVod") as String?
        } catch (e: Exception) {
            return null
        }
    }

    override fun categoryContent(tid: String?, pg: String, filter: Boolean, extend: HashMap<String, String>?): String? {
        try {
            val obj = submitAndWait(Callable { JSUtils<String>().toObj(requireNotNull(ctx) { "js context not ready" }, extend) })
            return call("category", tid, pg, filter, obj) as String?
        } catch (e: Exception) {
            return null
        }
    }

    override fun detailContent(ids: List<String>?): String? {
        try {
            val id = ids?.firstOrNull() ?: return null
            return call("detail", id) as String?
        } catch (e: Exception) {
            return null
        }
    }

    override fun searchContent(key: String?, quick: Boolean): String? {
        try {
            return call("search", key, quick) as String?
        } catch (e: Exception) {
            return null
        }
    }

    override fun playerContent(flag: String?, id: String, vipFlags: List<String>?): String? {
        try {
            val array = submitAndWait(Callable { JSUtils<String>().toArray(requireNotNull(ctx) { "js context not ready" }, vipFlags) })
            return call("play", flag, id, array) as String?
        } catch (e: Exception) {
            return null
        }
    }

    override fun liveContent(url: String?): String? {
        try {
            return call("live", url) as String?
        } catch (e: Exception) {
            return null
        }
    }

    override fun manualVideoCheck(): Boolean {
        try {
            return call("sniffer") as Boolean
        } catch (e: Exception) {
            return false
        }
    }

    override fun isVideoFormat(url: String?): Boolean {
        try {
            return call("isVideo", url) as Boolean
        } catch (e: Exception) {
            return false
        }
    }

    override fun proxyLocal(params: Map<String, String>?): Array<Any?>? {
        try {
            val p = params ?: return arrayOfNulls(0)
            if ("catvod" == p.get("from")) return proxy2(p)
            return proxy1(p) ?: arrayOfNulls(0)
        } catch (e: Exception) {
            AppLog.e("JsSpider", e)
            return arrayOfNulls(0)
        }
    }

    override fun action(action: String): String? {
        try {
            return call("action", action) as String?
        } catch (e: Exception) {
            return null
        }
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        val globalRef = global
        if (globalRef != null) {
            try {
                globalRef.destroy()
            } catch (ignored: Throwable) {
                AppLog.d("JsSpider", "global destroy failed")
            }
        }
        try {
            executor.submit(Runnable {
                try {
                    jsObject = null
                    ctx?.destroy()
                } catch (th: Throwable) {
                    AppLog.i("echo-js-destroy-error " + th.message)
                } finally {
                    executor.shutdown()
                }
            })
        } catch (th: Throwable) {
            executor.shutdownNow()
        }
    }

    @Throws(Exception::class)
    private fun initializeJS() {
        val init = submit(Callable<Any?> {
            if (ctx == null) createCtx()
            if (dex != null) createDex()

            val loaded = FileUtils.loadModule(api)
            if (isInvalidModuleContent(loaded)) {
                return@Callable null
            }
            var content: String = loaded ?: return@Callable null

            if (content.startsWith("//bb")) {
                cat = true
                val b = Base64.decode(content.replace("//bb", ""), 0)
                try {
                    requireNotNull(ctx) { "js context not ready" }.execute(byteFF(b))
                    requireNotNull(ctx) { "js context not ready" }.evaluateModule(String.format(SPIDER_STRING_CODE, key + ".js") + "globalThis." + key + " = globalThis.__JS_SPIDER__;", "tv_box_root.js")
                } catch (th: Throwable) {
                    AppLog.i("echo-bytecode-execute-error " + api + ", msg=" + th.message)
                    return@Callable null
                }
            } else {
                if (content.contains("__JS_SPIDER__")) {
                    content = content.replace(Regex("__JS_SPIDER__\\s*="), "export default ")
                }
                var moduleExtName = "default"
                if (content.contains("__jsEvalReturn") && !content.contains("export default")) {
                    moduleExtName = "__jsEvalReturn"
                    cat = true
                }
                try {
                    requireNotNull(ctx) { "js context not ready" }.evaluateModule(content, api)
                    requireNotNull(ctx) { "js context not ready" }.evaluateModule(String.format(SPIDER_STRING_CODE, api) + "globalThis." + key + " = globalThis.__JS_SPIDER__;", "tv_box_root.js")
                } catch (th: Throwable) {
                    AppLog.i("echo-evaluateModule-error " + api + ", msg=" + th.message)
                    return@Callable null
                }
            }
            jsObject = get(requireNotNull(ctx) { "js context not ready" }.globalObject, key) as JSObject?
            jsObject?.hold()
            return@Callable null
        })
        try {
            init.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is Exception) throw cause
            throw Exception(cause)
        } catch (e: TimeoutException) {
            AppLog.i("echo-js-init-timeout " + api)
            init.cancel(true)
        }
    }

    private fun createCtx() {
        ctx = QuickJSContext.create()
        set(requireNotNull(ctx) { "js context not ready" }.globalObject, "__jsSiteKey", key)
        emptyModuleBytecode = requireNotNull(ctx) { "js context not ready" }.compileModule(EMPTY_MODULE_CODE, "empty.js")
        requireNotNull(ctx) { "js context not ready" }.setModuleLoader(object : ModuleLoader() {
            override fun isBytecodeMode(): Boolean {
                return true
            }

            override fun getModuleBytecode(moduleName: String): ByteArray? {
                val ss = FileUtils.loadModule(moduleName)
                if (isInvalidModuleContent(ss)) {
                    return compileEmptyModule(moduleName)
                }
                val text = ss ?: return compileEmptyModule(moduleName)
                if (text.startsWith("//DRPY")) {
                    try {
                        val bytes = bytecode(Base64.decode(text.replace("//DRPY", ""), Base64.URL_SAFE))
                        return bytes ?: compileEmptyModule(moduleName)
                    } catch (th: Throwable) {
                        AppLog.i("echo-bytecode-module-error " + moduleName + ", msg=" + th.message)
                        return compileEmptyModule(moduleName)
                    }
                } else if (text.startsWith("//bb")) {
                    try {
                        val b = Base64.decode(text.replace("//bb", ""), 0)
                        return byteFF(b)
                    } catch (th: Throwable) {
                        AppLog.i("echo-bytecode-module-error " + moduleName + ", msg=" + th.message)
                        return compileEmptyModule(moduleName)
                    }
                } else {
                    return compileModule(moduleName, text)
                }
            }

            override fun getModuleStringCode(moduleName: String): String? {
                return null
            }

            override fun moduleNormalizeName(moduleBaseName: String?, moduleName: String?): String {
                return UriUtil.resolve(moduleBaseName, moduleName)
            }
        })
        requireNotNull(ctx) { "js context not ready" }.setConsole(object : QuickJSContext.Console {
            override fun log(s: String?) {
                AppLog.i("echo-QuJs " + s)
            }

            override fun info(s: String?) {
                AppLog.i("echo-QuJs " + s)
            }

            override fun warn(s: String?) {
                AppLog.i("echo-QuJs " + s)
            }

            override fun error(s: String?) {
                AppLog.i("echo-QuJs " + s)
            }
        })

        global = Global(executor, key)
        bind(requireNotNull(ctx) { "js context not ready" }.globalObject, requireNotNull(global) { "global not ready" })

        val localObj = createObject()
        set(requireNotNull(ctx) { "js context not ready" }.globalObject, "local", localObj)
        bind(localObj, local())

        val net = FileUtils.loadModule("net.js")
        if (!isInvalidModuleContent(net)) {
            try {
                requireNotNull(ctx) { "js context not ready" }.globalObject.context.evaluate(net)
            } catch (th: Throwable) {
                AppLog.e("JsSpider", th)
            }
        }
        preloadTemplate()
    }

    private fun compileEmptyModule(moduleName: String?): ByteArray? {
        AppLog.i("echo-getModuleBytecode empty :" + moduleName)
        return emptyModuleBytecode
    }

    private fun compileModule(moduleName: String?, content: String): ByteArray? {
        try {
            if (moduleName != null && moduleName.contains("cheerio.min.js")) {
                val bytecode = requireNotNull(ctx) { "js context not ready" }.compileModule(content, "cheerio.min.js")
                FileUtils.setCacheByte("cheerio.min", bytecode)
                return bytecode
            } else if (moduleName != null && moduleName.contains("crypto-js.js")) {
                val bytecode = requireNotNull(ctx) { "js context not ready" }.compileModule(content, "crypto-js.js")
                FileUtils.setCacheByte("crypto-js", bytecode)
                return bytecode
            }
            return requireNotNull(ctx) { "js context not ready" }.compileModule(content, moduleName)
        } catch (th: Throwable) {
            AppLog.i("echo-compileModule-error " + moduleName + ", msg=" + th.message)
            return compileEmptyModule(moduleName)
        }
    }

    private fun isInvalidModuleContent(content: String?): Boolean {
        if (content.isNullOrEmpty()) return true
        var trim = content.trim { it <= ' ' }
        if (trim.startsWith("\uFEFF")) trim = trim.substring(1).trim { it <= ' ' }
        val lower = trim.lowercase(Locale.getDefault())
        return lower.startsWith("<")
                || lower.startsWith("{\"code\":404")
                || lower.startsWith("404")
                || lower.startsWith("not found")
    }

    private fun preloadTemplate() {
        try {
            // i18n: keep(R4:模板.js import 语句)
            val template = "import tpl from '模板.js';\n" +
                    "globalThis.muban = tpl.muban;\n" +
                    "globalThis.getMubans = tpl.getMubans;"
            requireNotNull(ctx) { "js context not ready" }.evaluateModule(template, "tv_box_template.js")
        } catch (th: Throwable) {
            AppLog.i("echo-preloadTemplate-error " + th.message)
        }
    }

    private fun createDex() {
        try {
            val obj = createObject()
            val clz = requireNotNull(dex) { "dex is null" }
            val classes = clz.declaredClasses
            set(requireNotNull(ctx) { "js context not ready" }.globalObject, "jsapi", obj)
            if (classes.isEmpty()) invokeSingle(clz, obj)
            if (classes.size >= 1) invokeMultiple(clz, obj)
        } catch (e: Throwable) {
            AppLog.e("JsSpider", e)
        }
    }

    @Throws(Throwable::class)
    private fun invokeSingle(clz: Class<*>, jsObj: JSObject) {
        invoke(clz, jsObj, clz.getDeclaredConstructor(QuickJSContext::class.java).newInstance(ctx))
    }

    @Throws(Throwable::class)
    private fun invokeMultiple(clz: Class<*>, jsObj: JSObject) {
        for (subClz in clz.declaredClasses) {
            val javaObj = subClz.getDeclaredConstructor(clz).newInstance(clz.getDeclaredConstructor(QuickJSContext::class.java).newInstance(ctx))
            val subObj = createObject()
            invoke(subClz, subObj, javaObj)
            set(jsObj, subClz.simpleName, subObj)
        }
    }

    private fun invoke(clz: Class<*>, jsObj: JSObject, javaObj: Any) {
        for (method in clz.methods) {
            if (!isQuickJsMethod(method)) continue
            invoke(jsObj, method, javaObj)
        }
    }

    private fun invoke(jsObj: JSObject, method: Method, javaObj: Any) {
        set(jsObj, methodName(method), object : JSCallFunction {
            override fun call(vararg objects: Any?): Any? {
                try {
                    return method.invoke(javaObj, *objects)
                } catch (e: Throwable) {
                    return null
                }
            }
        })
    }

    private fun getContent(): String? {
        val global = "globalThis." + key
        val content = FileUtils.loadModule(api)
        if (isInvalidModuleContent(content)) {
            return null
        }
        val text = content ?: return null
        return if (text.contains("__jsEvalReturn")) {
            requireNotNull(ctx) { "js context not ready" }.evaluate("req = http")
            text + global + " = __jsEvalReturn()"
        } else if (text.contains("__JS_SPIDER__")) {
            text.replace("__JS_SPIDER__", global)
        } else {
            text.replace(Regex("export default.*?[{]"), global + " = {")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun proxy1(params: Map<String, String>): Array<Any?>? {
        if (destroyed.get() || jsObject == null) return null
        val args = submitAndWait(Callable {
            JSUtils<String>().toObj(requireNotNull(ctx) { "js context not ready" }, params)
        }) ?: return null
        return callConverted("proxy", { value -> toProxyResult(value) }, args) as? Array<Any?>
    }

    private fun callConverted(func: String, converter: (Any?) -> Any?, vararg args: Any?): Any? {
        val target = jsObject ?: return null
        if (destroyed.get()) return null
        return try {
            val pending = executor.submit(Callable { Async.run(target, func, args, converter) })
            val result = pending.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            result.get(CALL_TIMEOUT_MS)
        } catch (e: Exception) {
            AppLog.i("echo-js-call-failed " + e)
            null
        }
    }

    private fun toProxyResult(value: Any?): Array<Any?>? {
        val array = value as? JSArray ?: run {
            AppLog.i("echo-js-proxy-result " + (value?.javaClass?.simpleName ?: "null"))
            return null
        }
        val size = array.length()
        if (size < 3) return null
        val encoded = size > 4 && (array.get(4) as? Number)?.toInt() == 1
        val body = (if (encoded) base64Body(array.get(2)) else proxyBody(array.get(2))) ?: return null
        val result = arrayOfNulls<Any?>(4)
        result[0] = (array.get(0) as? Number)?.toInt() ?: 0
        result[1] = array.get(1) as? String ?: DEFAULT_MIME
        result[2] = body
        result[3] = proxyHeaders(if (size > 3) array.get(3) else null)
        return result
    }

    private fun proxyBody(raw: Any?): Any? {
        if (raw == null) return null
        if (raw is JSObject) {
            val token = proxyToken(raw)
            if (token != null) return global?.consumeStream(token)
            return ByteArrayInputStream(raw.stringify().toByteArray(Charset.defaultCharset()))
        }
        if (raw is JSArray) {
            val bytes = ByteArray(raw.length())
            for (index in bytes.indices) bytes[index] = (raw.get(index) as? Number)?.toInt()?.toByte() ?: 0
            return ByteArrayInputStream(bytes)
        }
        return ByteArrayInputStream(raw.toString().toByteArray(Charset.defaultCharset()))
    }

    private fun proxyToken(handle: JSObject): Int? {
        return try {
            (handle.getProperty("__token") as? Number)?.toInt()
        } catch (th: Throwable) {
            null
        }
    }

    private fun base64Body(raw: Any?): Any? {
        val text = raw as? String ?: return proxyBody(raw)
        return try {
            var content = text
            if (content.contains("base64,")) content = content.substring(content.indexOf("base64,") + 7)
            ByteArrayInputStream(Base64.decode(content, Base64.DEFAULT))
        } catch (e: Exception) {
            AppLog.e("JsSpider", e)
            proxyBody(raw)
        }
    }

    private fun proxyHeaders(raw: Any?): Map<String, String> {
        if (raw == null) return emptyMap()
        val headers = if (raw is JSObject) getHeader(raw.stringify()) else getHeader(raw)
        return ProxyHeaderPolicy.filter(headers)
    }

    private fun getHeader(headerRaw: Any?): Map<String, String> {
        val headers = HashMap<String, String>()
        if (headerRaw is JSONObject) {
            val json = headerRaw
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                headers[key] = json.optString(key)
            }
        } else if (headerRaw is String) {
            try {
                val json = JSONObject(headerRaw)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    headers[key] = json.optString(key)
                }
            } catch (e: JSONException) {
                AppLog.i("getHeader: 无法解析 String 为 JSON" + e)
            }
        } else if (headerRaw is Map<*, *>) {
            for ((k, v) in headerRaw) {
                headers[k.toString()] = v.toString()
            }
        }
        return headers
    }

    @Throws(Exception::class)
    private fun proxy2(params: Map<String, String>?): Array<Any?> {
        val p = requireNotNull(params) { "params is null" }
        val url = p.get("url") ?: return arrayOfNulls(0)
        val header = p.get("header")
        val array = submitAndWait(Callable { JSUtils<String>().toArray(requireNotNull(ctx) { "js context not ready" }, RegexUtils.getPattern("/").split(url).toList()) })
        val obj = submitAndWait(Callable { requireNotNull(ctx) { "js context not ready" }.parse(header) })
        if (array == null || obj == null) return arrayOfNulls(0)
        val json = call("proxy", array, obj) as String? ?: return arrayOfNulls(0)
        val res = Res.objectFrom(json)
        var contentType = res.getContentType()
        if (TextUtils.isEmpty(contentType)) contentType = "application/octet-stream"
        val result = arrayOfNulls<Any?>(3)
        result[0] = 200
        result[1] = contentType
        if (res.getBuffer() == 2) {
            result[2] = ByteArrayInputStream(Base64.decode(res.getContent(), Base64.DEFAULT))
        } else {
            result[2] = ByteArrayInputStream(res.getContent().toByteArray(Charset.defaultCharset()))
        }
        return result
    }

    companion object {

        private const val DEFAULT_MIME = "application/octet-stream"

        private const val BYTECODE_VERSION: Byte = 67
        private const val EMPTY_MODULE_CODE: String =
            "const empty = null;\n" +
                    "export default empty;\n" +
                    "export const JSEncrypt = empty;\n" +
                    "export const NodeRSA = empty;\n" +
                    "export const pako = empty;\n" +
                    "export const JSON5 = empty;\n" +
                    "export const mb = empty;\n" +
                    "export const parse = empty;\n" +
                    "export const stringify = empty;\n" +
                    "export const inflate = empty;\n" +
                    "export const deflate = empty;\n" +
                    "export const gzip = empty;\n" +
                    "export const ungzip = empty;\n" +
                    "export const encrypt = empty;\n" +
                    "export const decrypt = empty;"

        private const val CALL_TIMEOUT_MS: Long = 120_000

        private const val SPIDER_STRING_CODE: String = "import * as spider from '%s'\n\n" +
                "if (!globalThis.__JS_SPIDER__) {\n" +
                "    if (spider.__jsEvalReturn) {\n" +
                "        globalThis.req = http\n" +
                "        globalThis.__JS_SPIDER__ = spider.__jsEvalReturn()\n" +
                "        globalThis.__JS_SPIDER__.is_cat = true\n" +
                "    } else if (spider.default) {\n" +
                "        globalThis.__JS_SPIDER__ = typeof spider.default === 'function' ? spider.default() : spider.default\n" +
                "    }\n" +
                "}\n"

        @JvmStatic
        fun byteFF(bytes: ByteArray): ByteArray {
            val newBt = ByteArray(bytes.size - 4)
            newBt[0] = BYTECODE_VERSION
            System.arraycopy(bytes, 5, newBt, 1, bytes.size - 5)
            return newBt
        }

        private fun bytecode(bytes: ByteArray?): ByteArray? {
            if (bytes == null || bytes.isEmpty()) return null
            if (bytes[0] == BYTECODE_VERSION) return bytes
            AppLog.i("echo-bytecode-version-mismatch actual=" + bytes[0] + ", expected=" + BYTECODE_VERSION)
            return null
        }
    }
}
