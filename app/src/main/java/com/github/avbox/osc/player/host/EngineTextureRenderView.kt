package com.github.avbox.osc.player.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import com.github.avbox.osc.player.core.KernelPlayer
import com.github.avbox.osc.util.AppLog

@SuppressLint("ViewConstructor")
class EngineTextureRenderView(
    context: Context,
) : TextureView(context), PlayerRenderView, TextureView.SurfaceTextureListener, TextureRenderHost {

    private var mediaPlayer: KernelPlayer? = null

    private var texture: SurfaceTexture? = null

    private var surface: Surface? = null

    private var retiredSurface: Surface? = null

    private var surfaceReadyListener: Runnable? = null

    private var outputWidth = 0

    private var outputHeight = 0

    private var scaleType = RenderMeasure.SCALE_DEFAULT

    private var videoWidth = 0

    private var videoHeight = 0

    private var videoRotationDegree = 0

    init {
        surfaceTextureListener = this
        AppLog.i("echo-render-view: create TextureView")
    }

    override fun setOnSurfaceReadyListener(listener: Runnable?) {
        surfaceReadyListener = listener
    }

    override fun setOutputSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == outputWidth && height == outputHeight) return
        outputWidth = width
        outputHeight = height
        applyOutputBufferSize()
        refreshSurface()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyOutputBufferSize()
    }

    private fun applyOutputBufferSize() {
        val surfaceTexture = texture ?: return
        if (outputWidth <= 0 || outputHeight <= 0) return
        surfaceTexture.setDefaultBufferSize(outputWidth, outputHeight)
    }

    fun refreshSurface(): Boolean {
        val surfaceTexture = texture ?: return false
        val player = mediaPlayer ?: return false
        releaseRetiredSurface()
        retiredSurface = surface
        val newSurface = Surface(surfaceTexture)
        surface = newSurface
        player.setSurface(newSurface)
        notifySurfaceReady()
        return true
    }

    private fun releaseRetiredSurface() {
        retiredSurface?.release()
        retiredSurface = null
    }

    override fun attachToPlayer(player: KernelPlayer) {
        mediaPlayer = player
        val current = surface
        if (current != null) {
            player.setSurface(current)
            notifySurfaceReady()
        }
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

    override fun doScreenShot(): Bitmap? = bitmap

    override fun release() {
        mediaPlayer?.detachVideoSurface()
        mediaPlayer = null
        releaseRetiredSurface()
        surface?.release()
        surface = null
        texture?.release()
        texture = null
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

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        val existing = texture
        if (existing != null) {
            setSurfaceTexture(existing)
            refreshSurface()
            return
        }
        texture = surfaceTexture
        surface = Surface(surfaceTexture)
        applyOutputBufferSize()
        val player = mediaPlayer
        if (player != null) {
            player.setSurface(surface)
            notifySurfaceReady()
        }
    }

    private fun notifySurfaceReady() {
        surfaceReadyListener?.run()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
