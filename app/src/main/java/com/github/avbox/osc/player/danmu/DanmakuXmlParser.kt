package com.github.avbox.osc.player.danmu

import android.graphics.Color
import android.text.TextUtils
import com.github.avbox.osc.bean.Danmu
import com.github.avbox.osc.io.FileUtils
import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.util.DanmuSettingsState
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.RegexUtils
import com.github.avbox.osc.util.SSL.SSLSocketFactoryCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import master.flame.danmaku.danmaku.model.AlphaValue
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.Duration
import master.flame.danmaku.danmaku.model.IDanmakus
import master.flame.danmaku.danmaku.model.IDisplayer
import master.flame.danmaku.danmaku.model.SpecialDanmaku
import master.flame.danmaku.danmaku.model.android.DanmakuFactory
import master.flame.danmaku.danmaku.model.android.Danmakus
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.danmaku.util.DanmakuUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException

class DanmakuXmlParser : BaseDanmakuParser {

    fun interface CancelChecker {
        fun isCancelled(): Boolean
    }

    private val danmu: Danmu
    private val cancelChecker: CancelChecker?
    private var scaleX: Float = 0f
    private var scaleY: Float = 0f
    private var index: Int = 0

    @JvmOverloads
    constructor(input: String?, cancelChecker: CancelChecker? = null) {
        this.cancelChecker = cancelChecker
        this.danmu = Danmu.fromXml(resolveContent(input))
    }

    fun getDanmuCount(): Int = danmu.getData().size

    private fun resolveContent(input: String?): String {
        if (isCancelled()) return ""
        if (input.isNullOrEmpty()) return ""
        val source = input.trim { it <= ' ' }
        if (source.startsWith("file")) return FileUtils.read(source)
        if (source.startsWith("http")) {
            try {
                if (isCancelled()) return ""
                val response = executeHttp(source)
                if (isCancelled()) {
                    response.close()
                    return ""
                }
                val content = readBody(response)
                AppLog.i(
                    "echo-danmu http code: " + response.code
                        + ", encoding: " + response.header("Content-Encoding", "")
                        + ", length: " + content.length
                )
                return content
            } catch (e: SocketTimeoutException) {
                if (isLocalProxy(source)) {
                    try {
                        AppLog.e("echo-danmu load timeout, retry local proxy")
                        if (isCancelled()) return ""
                        val response = executeHttp(source)
                        if (isCancelled()) {
                            response.close()
                            return ""
                        }
                        val content = readBody(response)
                        AppLog.i(
                            "echo-danmu retry http code: " + response.code
                                + ", encoding: " + response.header("Content-Encoding", "")
                                + ", length: " + content.length
                        )
                        return content
                    } catch (retryError: Throwable) {
                        AppLog.e("echo-danmu retry error: " + retryError.message)
                    }
                }
                AppLog.e("echo-danmu load error: timeout")
                return ""
            } catch (th: Throwable) {
                if (source.startsWith("https") && isSslError(th)) {
                    try {
                        AppLog.i("echo-danmu ssl verify failed, retry unsafe ssl")
                        if (isCancelled()) return ""
                        val response = executeUnsafeHttp(source)
                        if (isCancelled()) {
                            response.close()
                            return ""
                        }
                        val content = readBody(response)
                        AppLog.i(
                            "echo-danmu unsafe ssl http code: " + response.code
                                + ", encoding: " + response.header("Content-Encoding", "")
                                + ", length: " + content.length
                        )
                        return content
                    } catch (retryError: Throwable) {
                        AppLog.e("echo-danmu unsafe ssl retry error: " + retryError.message)
                    }
                }
                AppLog.e("echo-danmu load error: " + th.message)
                return ""
            }
        }
        return source
    }

    private fun isCancelled(): Boolean = cancelChecker != null && cancelChecker.isCancelled()

    private fun executeHttp(source: String): okhttp3.Response {
        val request = Request.Builder().url(source).get().build()
        return HTTP_CLIENT.newCall(request).execute()
    }

    private fun executeUnsafeHttp(source: String): okhttp3.Response {
        val request = Request.Builder().url(source).get().build()
        return UNSAFE_HTTP_CLIENT.newCall(request).execute()
    }

    private fun isSslError(th0: Throwable?): Boolean {
        var th = th0
        while (th != null) {
            if (th is SSLException) return true
            th = th.cause
        }
        return false
    }

    private fun isLocalProxy(source: String): Boolean {
        return source.startsWith("http://127.0.0.1:") || source.startsWith("http://localhost:")
    }

    @Throws(IOException::class)
    private fun readBody(response: okhttp3.Response): String {
        var bytes = response.body.bytes()
        if (looksXml(bytes)) return String(bytes, Charsets.UTF_8)
        val encoding = response.header("Content-Encoding", "")
        if ("gzip".equals(encoding, ignoreCase = true)) {
            bytes = readAll(GZIPInputStream(ByteArrayInputStream(bytes)))
        } else if ("deflate".equals(encoding, ignoreCase = true)) {
            bytes = inflate(bytes)
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun looksXml(bytes: ByteArray?): Boolean {
        if (bytes == null) return false
        for (value in bytes) {
            val ch = (value.toInt() and 0xff).toChar()
            if (Character.isWhitespace(ch)) continue
            return ch == '<'
        }
        return false
    }

    @Throws(IOException::class)
    private fun inflate(bytes: ByteArray): ByteArray {
        try {
            return readAll(InflaterInputStream(ByteArrayInputStream(bytes)))
        } catch (e: IOException) {
            val inflater = Inflater(true)
            try {
                return readAll(InflaterInputStream(ByteArrayInputStream(bytes), inflater))
            } finally {
                inflater.end()
            }
        }
    }

    @Throws(IOException::class)
    private fun readAll(input: InputStream): ByteArray {
        try {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        } finally {
            input.close()
        }
    }

    override fun parse(): Danmakus {
        val result = Danmakus(IDanmakus.ST_BY_TIME)
        var renderedCount = 0
        for (data in danmu.getData()) {
            val danmaku = createDanmaku(data)
            if (danmaku == null) continue
            synchronized(result.obtainSynchronizer()) {
                result.addItem(danmaku)
            }
            renderedCount++
        }
        AppLog.i("echo-danmu rendered count: $renderedCount")
        return result
    }

    override fun setDisplayer(display: IDisplayer): BaseDanmakuParser {
        super.setDisplayer(display)
        scaleX = mDispWidth / DanmakuFactory.BILI_PLAYER_WIDTH
        scaleY = mDispHeight / DanmakuFactory.BILI_PLAYER_HEIGHT
        return this
    }

    private fun createDanmaku(data: Danmu.Data): BaseDanmaku? {
        try {
            val values = RegexUtils.getPattern(",").split(data.getParam())
            if (values.size < 4) return null
            val type = values[1].toInt()
            val item = mContext.mDanmakuFactory.createDanmaku(type, mContext)
            if (item == null) return null
            val time = (values[0].toFloat() * 1000).toLong()
            val size = values[2].toFloat() * Math.max(1.0f, mDispDensity - 0.6f)
            val color = if (DanmuSettingsState.config.randomColor) {
                DanmuSettingsState.randomColor()
            } else {
                (0x00000000ff000000L or java.lang.Long.parseLong(values[3])).toInt()
            }
            item.setTime(time)
            item.setTimer(mTimer)
            item.textSize = size
            item.textColor = color
            item.textShadowColor = if (color <= Color.BLACK) Color.WHITE else Color.BLACK
            item.flags = mContext.mGlobalFlagValues
            item.index = index++
            DanmakuUtils.fillText(item, decodeXmlString(data.getText()))
            if (item.getType() == BaseDanmaku.TYPE_SPECIAL
                && data.getText().startsWith("[")
                && data.getText().endsWith("]")
                && !setSpecial(item)
            ) {
                return null
            }
            return item
        } catch (ignored: Throwable) {
            return null
        }
    }

    private fun setSpecial(item: BaseDanmaku): Boolean {
        val textArr: Array<String>
        try {
            val jsonArray = JSONArray(item.text)
            textArr = Array(jsonArray.length()) { jsonArray.getString(it) }
        } catch (e: JSONException) {
            return false
        }
        if (textArr.size < 5 || TextUtils.isEmpty(textArr[4])) return false
        try {
            DanmakuUtils.fillText(item, textArr[4])
            var beginX = textArr[0].toFloat()
            var beginY = textArr[1].toFloat()
            var endX = beginX
            var endY = beginY
            val alphaArr = RegexUtils.getPattern("-").split(textArr[2])
            val beginAlpha = (AlphaValue.MAX * alphaArr[0].toFloat()).toInt()
            val endAlpha = if (alphaArr.size > 1) (AlphaValue.MAX * alphaArr[1].toFloat()).toInt() else beginAlpha
            val alphaDuration = (textArr[3].toFloat() * 1000).toLong()
            var translationDuration = alphaDuration
            var translationStartDelay = 0L
            var rotateY = 0f
            var rotateZ = 0f
            if (textArr.size >= 7) {
                rotateZ = textArr[5].toFloat()
                rotateY = textArr[6].toFloat()
            }
            if (textArr.size >= 11) {
                endX = textArr[7].toFloat()
                endY = textArr[8].toFloat()
                if (!TextUtils.isEmpty(textArr[9])) translationDuration = java.lang.Long.parseLong(textArr[9])
                if (!TextUtils.isEmpty(textArr[10])) translationStartDelay = textArr[10].toFloat().toLong()
            }
            if (isPercentageNumber(textArr[0])) beginX *= DanmakuFactory.BILI_PLAYER_WIDTH
            if (isPercentageNumber(textArr[1])) beginY *= DanmakuFactory.BILI_PLAYER_HEIGHT
            if (textArr.size >= 8 && isPercentageNumber(textArr[7])) endX *= DanmakuFactory.BILI_PLAYER_WIDTH
            if (textArr.size >= 9 && isPercentageNumber(textArr[8])) endY *= DanmakuFactory.BILI_PLAYER_HEIGHT
            item.duration = Duration(alphaDuration)
            item.rotationZ = rotateZ
            item.rotationY = rotateY
            mContext.mDanmakuFactory.fillTranslationData(
                item, beginX, beginY, endX, endY, translationDuration, translationStartDelay, scaleX, scaleY
            )
            mContext.mDanmakuFactory.fillAlphaData(item, beginAlpha, endAlpha, alphaDuration)
            if (textArr.size >= 12 && "true".equals(textArr[11], ignoreCase = true)) {
                item.textShadowColor = Color.TRANSPARENT
            }
            if (textArr.size >= 14) {
                (item as SpecialDanmaku).isQuadraticEaseOut = "0" == textArr[13]
            }
            if (textArr.size >= 15 && !TextUtils.isEmpty(textArr[14])) {
                fillLinePath(item, textArr[14])
            }
            return true
        } catch (ignored: Throwable) {
            return false
        }
    }

    private fun fillLinePath(item: BaseDanmaku, motionPathString: String) {
        val motionPath = motionPathString.substring(1)
        if (TextUtils.isEmpty(motionPath)) return
        val pointStrArray = RegexUtils.getPattern("L").split(motionPath)
        val points = Array(pointStrArray.size) { FloatArray(2) }
        for (i in pointStrArray.indices) {
            val pointArray = RegexUtils.getPattern(",").split(pointStrArray[i])
            if (pointArray.size < 2) return
            points[i][0] = pointArray[0].toFloat()
            points[i][1] = pointArray[1].toFloat()
        }
        DanmakuFactory.fillLinePathData(item, points, scaleX, scaleY)
    }

    private fun isPercentageNumber(number: String?): Boolean = number != null && number.contains(".")

    private fun decodeXmlString(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return text.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&gt;", ">")
            .replace("&lt;", "<")
    }

    companion object {
        private const val HTTP_TIMEOUT_MS = 20 * 1000L

        private val TRUST_ALL_CERT: X509TrustManager = object : X509TrustManager {
            @Throws(CertificateException::class)
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
            }

            @Throws(CertificateException::class)
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
            }

            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                arrayOf<java.security.cert.X509Certificate>()
        }

        private val TRUST_ALL_HOSTNAME: HostnameVerifier = HostnameVerifier { _, _: SSLSession -> true }

        @Volatile
        private var HTTP_CLIENT: OkHttpClient = buildHttpClient(false)

        @Volatile
        private var UNSAFE_HTTP_CLIENT: OkHttpClient = buildHttpClient(true)

        private fun buildHttpClient(unsafeSsl: Boolean): OkHttpClient {
            val base = HttpClients.defaultClient
            val builder = if (base != null) {
                base.newBuilder()
            } else {
                OkHttpClient.Builder()
                    .proxySelector(HttpClients.proxySelector())
                    .proxyAuthenticator(HttpClients.proxyAuthenticator())
            }
            builder.readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            builder.writeTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            builder.connectTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            builder.retryOnConnectionFailure(true)
            if (unsafeSsl) {
                val sslSocketFactory: SSLSocketFactory = SSLSocketFactoryCompat(TRUST_ALL_CERT)
                builder.sslSocketFactory(sslSocketFactory, TRUST_ALL_CERT)
                builder.hostnameVerifier(TRUST_ALL_HOSTNAME)
            }
            return builder.build()
        }

        @JvmStatic
        @Synchronized
        fun resetHttpClient() {
            HTTP_CLIENT = buildHttpClient(false)
            UNSAFE_HTTP_CLIENT = buildHttpClient(true)
        }
    }
}
