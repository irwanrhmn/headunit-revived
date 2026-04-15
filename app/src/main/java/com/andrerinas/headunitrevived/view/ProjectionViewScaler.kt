package com.andrerinas.headunitrevived.view

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.Gravity
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig

object ProjectionViewScaler {

    fun updateScale(view: View, videoWidth: Int, videoHeight: Int) {
        if (videoWidth == 0 || videoHeight == 0 || view.width == 0 || view.height == 0) {
            return
        }

        val settings = App.provide(view.context).settings
        HeadUnitScreenConfig.init(view.context, view.resources.displayMetrics, settings)

        val usableW = HeadUnitScreenConfig.getUsableWidth()
        val usableH = HeadUnitScreenConfig.getUsableHeight()

        if (HeadUnitScreenConfig.forcedScale && view is ProjectionView) {
            val lp = view.layoutParams
            var paramsChanged = false
            
            if (settings.stretchToFill) {
                // Mode A: Preserve aspect ratio using Adjusted dimensions
                val targetW = HeadUnitScreenConfig.getAdjustedWidth()
                val targetH = HeadUnitScreenConfig.getAdjustedHeight()

                if (lp.width != targetW || lp.height != targetH) {
                    lp.width = targetW
                    lp.height = targetH
                    paramsChanged = true
                }

                // Center the view in the usable area
                if (lp is FrameLayout.LayoutParams) {
                    if (lp.gravity != Gravity.CENTER) {
                        lp.gravity = Gravity.CENTER
                        paramsChanged = true
                    }
                }
                
                if (paramsChanged) {
                    view.layoutParams = lp
                }

                view.scaleX = 1.0f
                view.scaleY = 1.0f
                view.translationX = 0f
                view.translationY = 0f

                AppLog.i("FORCED & STRETCH On: Resized view to ${targetW}x${targetH} (centered)")
            } else {
                // Mode B: Stretch to fill the usable area exactly (ignores aspect ratio)
                if (lp.width != usableW || lp.height != usableH) {
                    lp.width = usableW
                    lp.height = usableH
                    paramsChanged = true
                }
                
                if (lp is FrameLayout.LayoutParams) {
                    val targetGravity = Gravity.TOP or Gravity.START
                    if (lp.gravity != targetGravity) {
                        lp.gravity = targetGravity
                        paramsChanged = true
                    }
                }
                
                if (paramsChanged) {
                    view.layoutParams = lp
                }

                view.scaleX = 1.0f
                view.scaleY = 1.0f
                view.translationX = 0f
                view.translationY = 0f

                AppLog.i("FORCED & STRETCH Off: Resized view to match screen exactly: ${usableW}x${usableH}")
            }
        } else {
            val videoW = HeadUnitScreenConfig.getNegotiatedWidth().toFloat()
            val videoH = HeadUnitScreenConfig.getNegotiatedHeight().toFloat()
            val marginW = HeadUnitScreenConfig.getWidthMargin().toFloat()
            val marginH = HeadUnitScreenConfig.getHeightMargin().toFloat()
            
            val uiW = videoW - marginW
            val uiH = videoH - marginH

            val viewW = view.width.toFloat()
            val viewH = view.height.toFloat()

            var finalScaleX = 1.0f
            var finalScaleY = 1.0f

            if (settings.stretchToFill) {
                // Stretch to Fill: Make the active UI box (uiW x uiH) completely fill the View (viewW x viewH).
                // The TextureView default scales videoW -> viewW. 
                // To scale uiW -> viewW, we apply targetScale / defaultScale.
                finalScaleX = videoW / uiW
                finalScaleY = videoH / uiH
                AppLog.i("[UI_DEBUG] ProjectionViewScaler: STRETCH - Scaling UI (${uiW}x${uiH}) to fill View (${viewW}x${viewH}). ScaleX=$finalScaleX, ScaleY=$finalScaleY")
            } else {
                // Letterbox / Fit Center: Keep the UI aspect ratio intact, max out at View dimensions.
                val uiRatio = uiW / uiH
                val viewRatio = viewW / viewH

                if (viewRatio > uiRatio) {
                    // View is wider than UI. Pillarboxing (black bars left/right). Limit by Height.
                    val displayedUiH = viewH
                    val displayedUiW = viewH * uiRatio
                    finalScaleX = (displayedUiW * videoW) / (viewW * uiW)
                    finalScaleY = videoH / uiH 
                    AppLog.i("[UI_DEBUG] ProjectionViewScaler: FIT - Pillarboxed UI (${displayedUiW}x${displayedUiH}) into View (${viewW}x${viewH}). ScaleX=$finalScaleX, ScaleY=$finalScaleY")
                } else {
                    // View is taller than UI. Letterboxing (black bars top/bottom). Limit by Width.
                    val displayedUiW = viewW
                    val displayedUiH = viewW / uiRatio
                    finalScaleX = videoW / uiW
                    finalScaleY = (displayedUiH * videoH) / (viewH * uiH)
                    AppLog.i("[UI_DEBUG] ProjectionViewScaler: FIT - Letterboxed UI (${displayedUiW}x${displayedUiH}) into View (${viewW}x${viewH}). ScaleX=$finalScaleX, ScaleY=$finalScaleY")
                }
            }

            val lp = view.layoutParams
            var paramsChanged = false
            
            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT || 
                lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                paramsChanged = true
            }
            
            if (lp is FrameLayout.LayoutParams) {
                if (lp.gravity != Gravity.CENTER) {
                    lp.gravity = Gravity.CENTER
                    paramsChanged = true
                }
            }
            
            if (paramsChanged) {
                view.layoutParams = lp
            }

            view.translationX = 0f
            view.translationY = 0f

            if (view is IProjectionView) {
                view.setVideoScale(finalScaleX, finalScaleY)
            } else {
                view.scaleX = finalScaleX
                view.scaleY = finalScaleY
            }
        }
    }
}
