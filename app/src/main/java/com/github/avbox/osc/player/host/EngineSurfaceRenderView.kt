package com.github.avbox.osc.player.host

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.github.avbox.osc.player.core.KernelPlayer
import com.github.avbox.osc.util.AppLog

class EngineSurfaceRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr), PlayerRenderView, SurfaceHolder.Callback {

    private var mediaPlayer: KernelPlayer? = null

    private var released = false

    private var scaleType = RenderMeasure.SCALE_DEFAULT

    private var videoWidth = 0

    private var videoHeight = 0

    private var videoRotationDegree = 0

    init {
        val surfaceHolder = holder
        surfaceHolder.addCallback(this)
        surfaceHolder.setFormat(PixelFormat.RGBA_8888)
        AppLog.i("echo-render-view: create SurfaceView")
    }

    override fun attachToPlayer(player: KernelPlayer) {
        if (released) return
        mediaPlayer = player
        val surfaceHolder = holder
        val surface = surfaceHolder.surface
        val valid = surface != null && surface.isValid
        if (valid) {
            player.setDisplay(surfaceHolder)
        }
        AppLog.i("echo-surface: attach player valid=$valid")
    }

    override fun setVideoSize(videoWidth: Int, videoHeight: Int) {
        if (videoWidth > 0 && videoHeight > 0) {
            this.videoWidth = videoWidth
            this.videoHeight = videoHeight
            requestLayout()
        }
    }

    override fun setVideoRotation(degree: Int) {
        videoRotationDegree = degree
        rotation = degree.toFloat()
    }

    override fun setScaleType(scaleType: Int) {
        this.scaleType = scaleType
        requestLayout()
    }

    override fun getView(): View = this

    override fun doScreenShot(): Bitmap? = null

    override fun release() {
        released = true
        mediaPlayer?.detachVideoSurface()
        mediaPlayer = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measured = RenderMeasure.measure(
            widthMeasureSpec,
            heightMeasureSpec,
            View.MeasureSpec.getSize(widthMeasureSpec),
            View.MeasureSpec.getSize(heightMeasureSpec),
            scaleType,
            videoWidth,
            videoHeight,
            videoRotationDegree,
        )
        setMeasuredDimension(measured[0], measured[1])
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (released) return
        AppLog.i("echo-surface: created valid=" + holder.surface?.isValid + " player=" + (mediaPlayer != null))
        mediaPlayer?.setDisplay(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (released) return
        AppLog.i("echo-surface: changed ${width}x$height player=" + (mediaPlayer != null))
        mediaPlayer?.setDisplay(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (released) return
        AppLog.i("echo-surface: destroyed -> detach player=" + (mediaPlayer != null))
        mediaPlayer?.detachVideoSurface()
    }
}
