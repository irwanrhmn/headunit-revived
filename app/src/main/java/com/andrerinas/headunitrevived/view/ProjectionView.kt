package com.andrerinas.headunitrevived.view

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.utils.AppLog

class ProjectionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), IProjectionView, SurfaceHolder.Callback {

    private val callbacks = mutableListOf<IProjectionView.Callbacks>()
    private var videoDecoder: VideoDecoder? = null
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        videoDecoder = App.provide(context).videoDecoder
        holder.addCallback(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        videoDecoder?.stop("onDetachedFromWindow")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        AppLog.i("holder $holder")
        callbacks.forEach { it.onSurfaceCreated(holder.surface) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        AppLog.i("holder %s, format: %d, width: %d, height: %d", holder, format, width, height)
        callbacks.forEach { it.onSurfaceChanged(holder.surface, width, height) }
        ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AppLog.i("holder $holder")
        videoDecoder?.stop("surfaceDestroyed")
        callbacks.forEach { it.onSurfaceDestroyed(holder.surface) }
    }

    override fun addCallback(callback: IProjectionView.Callbacks) {
        callbacks.add(callback)
        if (holder.surface.isValid) {
            callback.onSurfaceCreated(holder.surface)
            callback.onSurfaceChanged(holder.surface, width, height)
        }
    }

    override fun removeCallback(callback: IProjectionView.Callbacks) {
        callbacks.remove(callback)
    }

    override fun setVideoSize(width: Int, height: Int) {
        if (videoWidth == width && videoHeight == height) return
        AppLog.i("ProjectionView", "Video size set to ${width}x$height")
        videoWidth = width
        videoHeight = height
        ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
    }

    override fun setVideoScale(scaleX: Float, scaleY: Float) {
        this.scaleX = scaleX
        this.scaleY = scaleY

        if (com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig.forcedScale) {
            this.translationX = -(this.width - (this.width * scaleX)) / 2.0f
            this.translationY = -(this.height - (this.height * scaleY)) / 2.0f
        } else {
            this.translationX = 0f
            this.translationY = 0f
        }
    }
}