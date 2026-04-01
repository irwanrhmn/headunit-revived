package com.andrerinas.headunitrevived.view

import android.view.View
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig

object ProjectionViewScaler {

    fun updateScale(view: View, videoWidth: Int, videoHeight: Int) {
        if (videoWidth == 0 || videoHeight == 0 || view.width == 0 || view.height == 0) {
            return
        }

        // The dimensions of the content area we want to display
        val displayMetrics = view.resources.displayMetrics
        val contentWidth = displayMetrics.widthPixels
        val contentHeight = displayMetrics.heightPixels

        HeadUnitScreenConfig.init(view.context, displayMetrics, App.provide(view.context).settings)

        // Calculate final scale factors
        val finalScaleX = HeadUnitScreenConfig.getScaleX()
        val finalScaleY = HeadUnitScreenConfig.getScaleY()

        // If the view implements IProjectionView, use its specific scaling logic (which handles translation)
        if (view is IProjectionView) {
            view.setVideoScale(finalScaleX, finalScaleY)
        } else {
            view.scaleX = finalScaleX
            view.scaleY = finalScaleY
        }

        AppLog.i("ProjectionViewScaler: Dimensions: Video: ${videoWidth}x$videoHeight, Content: ${contentWidth}x$contentHeight, View: ${view.width}x${view.height}")
        AppLog.i("ProjectionViewScaler: Scale updated for view ${view.javaClass.simpleName}. scaleX: $finalScaleX, scaleY: $finalScaleY")
    }
}
