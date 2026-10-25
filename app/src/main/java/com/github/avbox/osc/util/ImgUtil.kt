package com.github.avbox.osc.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.widget.ImageView

import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.Disposable
import coil3.request.ImageRequest

import org.json.JSONException
import org.json.JSONObject

import java.util.Random

import android.util.LruCache
import me.jessyan.autosize.utils.AutoSizeUtils

object ImgUtil {
    private const val DRAWABLE_CACHE_MAX = 64
    private val drawableCache = LruCache<String, Drawable>(DRAWABLE_CACHE_MAX)
    @JvmField
    var defaultWidth = 244
    @JvmField
    var defaultHeight = 320

    class Style(@JvmField var ratio: Float, @JvmField var type: String)

    @JvmStatic
    fun isBase64Image(picUrl: String?): Boolean {
        return picUrl != null && picUrl.startsWith("data:image")
    }

    @JvmStatic
    fun initStyle(styleJson: String?): Style? {
        if (styleJson.isNullOrEmpty()) {
            return null
        }
        try {
            val jsonObject = JSONObject(styleJson)
            return Style(jsonObject.getDouble("ratio").toFloat(), jsonObject.getString("type"))
        } catch (ignored: JSONException) {
            AppLog.d("ImgUtil", "home style json invalid, use default grid")
        }
        return null
    }

    @JvmStatic
    fun spanCountByStyle(style: Style, defaultCount: Int): Int {
        var spanCount = defaultCount
        if ("rect" == style.type) {
            if (style.ratio >= 1.7) {
                spanCount = 3
            } else if (style.ratio >= 1.3) {
                spanCount = 4
            }
        } else if ("list" == style.type) {
            spanCount = 1
        }
        return spanCount
    }

    @JvmStatic
    fun getStyleDefaultWidth(style: Style): Int {
        var styleDefaultWidth = 280
        if (style.ratio < 1) styleDefaultWidth = 214
        if (style.ratio > 1.7) styleDefaultWidth = 380
        return styleDefaultWidth
    }

    @JvmStatic
    fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
        val base64Data = base64Str.substring(base64Str.indexOf(",") + 1)
        val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    }

    @JvmStatic
    fun loadPlayerArtwork(url: String, view: ImageView): Disposable? {
        view.setScaleType(ImageView.ScaleType.FIT_CENTER)
        if (isInvalidImageUrl(url)) {
            view.setImageDrawable(createTextDrawable("TVBox", 0, 0, 1f))
            return null
        }
        val context = requireNotNull(AppContextHolder.context()) { "app context not initialized" }
        val request = ImageRequest.Builder(context)
            .data(url)
            .target(ArtworkTarget(view))
            .build()
        return SingletonImageLoader.get(context).enqueue(request)
    }

    private class ArtworkTarget(private val view: ImageView) : coil3.target.Target {
        override fun onSuccess(image: coil3.Image) {
            view.setImageDrawable(image.asDrawable(view.resources))
        }
    }

    @JvmStatic
    fun getRandomColor(): Int {
        val random = Random()
        return Color.argb(255, random.nextInt(256), random.nextInt(256), random.nextInt(256))
    }

    @JvmStatic
    fun createTextDrawable(text: String): Drawable {
        return createTextDrawable(text, 0, 0, AutoSizeUtils.mm2px(requireNotNull(AppContextHolder.context()) { "app context not initialized" }, 10f).toFloat())
    }

    private fun createTextDrawable(textIn: String, widthIn: Int, heightIn: Int, cornerRadiusIn: Float): Drawable {
        var text = textIn
        var width = widthIn
        var height = heightIn
        var cornerRadius = cornerRadiusIn
        if (TextUtils.isEmpty(text)) text = "TVBox"
        if (width <= 0) width = 180
        if (height <= 0) height = 240
        if (cornerRadius <= 0) cornerRadius = 1f
        val key = text + "_" + width + "x" + height + "_" + cornerRadius.toInt()
        text = text.substring(0, 1)
        val cached = drawableCache.get(key)
        if (cached != null) return cached
        val randomColor = getRandomColor()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.setColor(randomColor)
        paint.setStyle(Paint.Style.FILL)
        val rectF = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
        paint.setColor(Color.WHITE)
        paint.setTextSize(60f)
        paint.setTextAlign(Paint.Align.CENTER)
        val fontMetrics = paint.getFontMetrics()
        val x = width / 2f
        val y = (height - fontMetrics.bottom - fontMetrics.top) / 2f
        canvas.drawText(text, x, y, paint)
        val drawable: Drawable = BitmapDrawable(requireNotNull(AppContextHolder.context()) { "app context not initialized" }.resources, bitmap)
        drawableCache.put(key, drawable)
        return drawable
    }

    @JvmStatic
    fun clearCache() {
        drawableCache.evictAll()
    }

    @JvmStatic
    fun clearMemoryCache() {
        clearCache()
        try {
            SingletonImageLoader.get(requireNotNull(AppContextHolder.context()) { "app context not initialized" }).memoryCache?.clear()
            AppLog.i("echo-img-clear-memory-cache")
        } catch (th: Throwable) {
            AppLog.i("echo-img-clear-memory-cache-error:" + th.message)
        }
    }

    private fun isInvalidImageUrl(urlIn: String): Boolean {
        var url = urlIn
        if (TextUtils.isEmpty(url)) return true
        url = url.trim { it <= ' ' }
        if (TextUtils.isEmpty(url)) return true
        return hasEmptyProxyParam(url, "img")
    }

    private fun hasEmptyProxyParam(url: String, key: String): Boolean {
        if (!url.startsWith("proxy://") && !url.contains("/proxy?")) return false
        val queryIndex = url.indexOf('?')
        val query = if (queryIndex >= 0) url.substring(queryIndex + 1) else url.substring("proxy://".length)
        val pairs = RegexUtils.getPattern("&").split(query)
        for (pair in pairs) {
            val eqIndex = pair.indexOf('=')
            if (eqIndex < 0) continue
            if (key == pair.substring(0, eqIndex) && TextUtils.isEmpty(pair.substring(eqIndex + 1))) {
                return true
            }
        }
        return false
    }
}
