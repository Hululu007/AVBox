/*
 *                       Copyright (C) of Avery
 *
 *                              _ooOoo_
 *                             o8888888o
 *                             88" . "88
 *                             (| -_- |)
 *                             O\  =  /O
 *                          ____/`- -'\____
 *                        .'  \\|     |//  `.
 *                       /  \\|||  :  |||//  \
 *                      /  _||||| -:- |||||-  \
 *                      |   | \\\  -  /// |   |
 *                      | \_|  ''\- -/''  |   |
 *                      \  .-\__  `-`  ___/-. /
 *                    ___`. .' /- -.- -\  `. . __
 *                 ."" '<  `.___\_<|>_/___.'  >'"".
 *                | | :  `- \`.;`\ _ /`;.`/ - ` : | |
 *                \  \ `-.   \_ __\ /__ _/   .-` /  /
 *           ======`-.____`-.___\_____/___.-`____.-'======
 *                              `=- -='
 *           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *              Buddha bless, there will never be bug!!!
 */

package com.github.tvbox.osc.subtitle.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.TextView

import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.subtitle.DefaultSubtitleEngine
import com.github.tvbox.osc.subtitle.SubtitleEngine
import com.github.tvbox.osc.subtitle.model.Subtitle
import com.github.tvbox.osc.util.MD5

import com.github.tvbox.osc.player.core.KernelPlayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@SuppressLint("AppCompatCustomView")
class SimpleSubtitleView : TextView,
    SubtitleEngine, SubtitleEngine.OnSubtitleChangeListener,
    SubtitleEngine.OnSubtitlePreparedListener {

    private lateinit var mSubtitleEngine: SubtitleEngine

    @JvmField
    var isInternal: Boolean = false

    @JvmField
    var hasInternal: Boolean = false

    private lateinit var backGroundText: TextView
    private var lyricMode: Boolean = false

    constructor(context: Context) : super(context) {
        backGroundText = TextView(context)
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        backGroundText = TextView(context, attrs)
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
            super(context, attrs, defStyleAttr) {
        backGroundText = TextView(context, attrs, defStyleAttr)
        init()
    }

    private fun init() {
        mSubtitleEngine = DefaultSubtitleEngine()
        mSubtitleEngine.setOnSubtitlePreparedListener(this)
        mSubtitleEngine.setOnSubtitleChangeListener(this)
    }

    override fun onSubtitlePrepared(subtitles: List<Subtitle>?) {
        start()
    }

    override fun onSubtitleChanged(subtitle: Subtitle?) {
        if (subtitle == null) {
            setText(EMPTY_TEXT)
            return
        }
        val lines = subtitle.lines
        if (lyricMode && lines != null) {
            val builder = SpannableStringBuilder()
            for (i in lines.size - 1 downTo 0) {
                val line = lines[i]
                if (i > 0) builder.append('\n')
                val start = builder.length
                builder.append(Html.fromHtml(formatText(line.content)))
                val end = builder.length
                builder.setSpan(ForegroundColorSpan(if (line.lyricCurrent) Color.GREEN else Color.YELLOW), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setText(builder)
            invalidate()
            return
        }
        setText(Html.fromHtml(formatText(subtitle.content)))
    }

    private fun formatText(text: String?): String {
        if (text == null) return EMPTY_TEXT
        var formatted = text
        formatted = formatted.replace(Regex("(?:\\r\\n)"), "<br />")
        formatted = formatted.replace(Regex("(?:\\r)"), "<br />")
        formatted = formatted.replace(Regex("(?:\\n)"), "<br />")
        formatted = formatted.replace(Regex("\\\\N"), "<br />")
        formatted = formatted.replace(Regex("\\{[\\s\\S]*?\\}"), "")
        formatted = formatted.replace(Regex("^.*?,.*?,.*?,.*?,.*?,.*?,.*?,.*?,.*?,"), "")
        return formatted
    }

    override fun setSubtitlePath(path: String?) {
        isInternal = false
        mSubtitleEngine.setSubtitlePath(path)
    }

    override fun setSubtitleDelay(milliseconds: Int?) {
        mSubtitleEngine.setSubtitleDelay(milliseconds)
    }

    override fun setPlaySubtitleCacheKey(cacheKey: String?) {
        mSubtitleEngine.setPlaySubtitleCacheKey(cacheKey)
    }

    fun setMergeSameTime(mergeSameTime: Boolean) {
        if (mSubtitleEngine is DefaultSubtitleEngine) {
            (mSubtitleEngine as DefaultSubtitleEngine).setMergeSameTime(mergeSameTime)
        }
    }

    fun setLyricMode(lyricMode: Boolean) {
        this.lyricMode = lyricMode
    }

    override fun getPlaySubtitleCacheKey(): String? {
        return mSubtitleEngine.getPlaySubtitleCacheKey()
    }

    fun clearSubtitleCache() {
        val subtitleCacheKey = getPlaySubtitleCacheKey()
        if (subtitleCacheKey != null && subtitleCacheKey.length > 0) {
            val cacheKey = MD5.string2MD5(subtitleCacheKey)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                AppGraph.cacheRepository.delete(cacheKey, "")
            }
        }
    }

    override fun reset() {
        mSubtitleEngine.reset()
    }

    override fun start() {
        mSubtitleEngine.start()
    }

    override fun pause() {
        mSubtitleEngine.pause()
    }

    override fun resume() {
        mSubtitleEngine.resume()
    }

    override fun stop() {
        mSubtitleEngine.stop()
    }

    override fun destroy() {
        mSubtitleEngine.destroy()
    }

    override fun bindToMediaPlayer(mediaPlayer: KernelPlayer?) {
        mSubtitleEngine.bindToMediaPlayer(mediaPlayer)
    }

    override fun setOnSubtitlePreparedListener(listener: SubtitleEngine.OnSubtitlePreparedListener?) {
        mSubtitleEngine.setOnSubtitlePreparedListener(listener)
    }

    override fun setOnSubtitleChangeListener(listener: SubtitleEngine.OnSubtitleChangeListener?) {
        mSubtitleEngine.setOnSubtitleChangeListener(listener)
    }

    override fun onDetachedFromWindow() {
        destroy()
        super.onDetachedFromWindow()
    }

    override fun setLayoutParams(params: ViewGroup.LayoutParams) {
        backGroundText.layoutParams = params
        super.setLayoutParams(params)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tt = backGroundText.text
        val text = if (lyricMode) getText().toString() else getText()
        if (TextUtils.isEmpty(tt) || tt != text) {
            backGroundText.text = text
            this.postInvalidate()
        }
        backGroundText.measure(widthMeasureSpec, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun setTextSize(size: Float) {
        super.setTextSize(size)
        backGroundText.setTextSize(size)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, after: Int) {
        if (this::backGroundText.isInitialized) {
            backGroundText.text = if (lyricMode && text != null) text.toString() else text
        }
        super.onTextChanged(text, start, before, after)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        backGroundText.layout(left, top, right, bottom)
        super.onLayout(changed, left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        drawBackGroundText()
        backGroundText.draw(canvas)
        super.onDraw(canvas)
    }

    private fun drawBackGroundText() {
        val tp: TextPaint = backGroundText.paint
        tp.strokeWidth = 10f
        tp.style = Paint.Style.FILL_AND_STROKE
        backGroundText.setTextColor(Color.BLACK)
        backGroundText.gravity = gravity
    }

    companion object {
        private const val EMPTY_TEXT = ""
    }
}
