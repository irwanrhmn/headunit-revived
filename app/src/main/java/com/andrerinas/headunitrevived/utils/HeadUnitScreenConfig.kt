package com.andrerinas.headunitrevived.utils

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import com.andrerinas.headunitrevived.aap.protocol.proto.Control
import kotlin.math.roundToInt

object HeadUnitScreenConfig {

    private var screenWidthPx: Int = 0
    private var screenHeightPx: Int = 0
    private var density: Float = 1.0f
    private var densityDpi: Int = 240
    private var scaleFactor: Float = 1.0f
    private var isSmallScreen: Boolean = true
    private var isPortraitScaled: Boolean = false
    private var isInitialized: Boolean = false // New flag
    
    // Flag to determine if the projection should stretch and ignore aspect ratio
    private var stretchToFill: Boolean = false 
    
    var negotiatedResolutionType: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType? = null
    private lateinit var currentSettings: Settings // Store settings instance

    // System Insets (Bars/Cutouts)
    var systemInsetLeft: Int = 0
        private set
    var systemInsetTop: Int = 0
        private set
    var systemInsetRight: Int = 0
        private set
    var systemInsetBottom: Int = 0
        private set

    // Raw Screen Dimensions (Full Display)
    private var realScreenWidthPx: Int = 0
    private var realScreenHeightPx: Int = 0


    fun init(context: Context, displayMetrics: DisplayMetrics, settings: Settings) {
        // Read user preference for stretching the screen from the app's Settings
        stretchToFill = settings.stretchToFill

        val screenWidth: Int
        val screenHeight: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // API 30+
            val windowManager = context.getSystemService(android.view.WindowManager::class.java)
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else { // Older APIs
            @Suppress("DEPRECATION")
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val display = windowManager.defaultDisplay
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            screenWidth = size.x
            screenHeight = size.y
        }

        // Only update if dimensions or settings changed (and we are already initialized)
        if (isInitialized && realScreenWidthPx == screenWidth && realScreenHeightPx == screenHeight && this::currentSettings.isInitialized && currentSettings == settings) {
            return
        }

        isInitialized = true
        currentSettings = settings

        realScreenWidthPx = screenWidth
        realScreenHeightPx = screenHeight
        density = displayMetrics.density
        densityDpi = displayMetrics.densityDpi
        
        recalculate()
    }

    fun updateInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (systemInsetLeft == left && systemInsetTop == top && systemInsetRight == right && systemInsetBottom == bottom) {
            return
        }
        
        systemInsetLeft = left
        systemInsetTop = top
        systemInsetRight = right
        systemInsetBottom = bottom
        
        if (isInitialized) {
            recalculate()
        }
    }

    private fun recalculate() {
        // Calculate USABLE area
        screenWidthPx = realScreenWidthPx - systemInsetLeft - systemInsetRight
        screenHeightPx = realScreenHeightPx - systemInsetTop - systemInsetBottom

        if (screenWidthPx <= 0 || screenHeightPx <= 0) {
            // Fallback to raw if calculation fails or leads to 0
            screenWidthPx = realScreenWidthPx
            screenHeightPx = realScreenHeightPx
        }
        
        AppLog.i("CarScreen: usable width: $screenWidthPx height: $screenHeightPx (Raw: ${realScreenWidthPx}x${realScreenHeightPx}, Insets: L$systemInsetLeft T$systemInsetTop R$systemInsetRight B$systemInsetBottom)")

        // check if small screen
        if (screenHeightPx > screenWidthPx) { // Portrait mode
            if (screenWidthPx > 1080 || screenHeightPx > 1920) {
                isSmallScreen = false
            }
        } else {
            if (screenWidthPx > 1920 || screenHeightPx > 1080) {
                isSmallScreen = false
            }
        }

        val selectedResolution = Settings.Resolution.fromId(currentSettings.resolutionId)

        // Determine negotiatedResolutionType based on physical pixels if AUTO was selected
        if (selectedResolution == Settings.Resolution.AUTO) {
            if (screenHeightPx > screenWidthPx) { // Portrait mode
                if (screenWidthPx > 720 || screenHeightPx > 1280) {
                    negotiatedResolutionType = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1080x1920
                } else {
                    negotiatedResolutionType = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280
                }
            } else { // Landscape mode
                if (screenWidthPx <= 800 && screenHeightPx <= 480) {
                    negotiatedResolutionType = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
                } else if ((screenWidthPx >= 2560 || screenHeightPx >= 1440) && com.andrerinas.headunitrevived.decoder.VideoDecoder.isHevcSupported() && Build.VERSION.SDK_INT >= 24) {
                    negotiatedResolutionType = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440
                } else if (screenWidthPx > 1280 || screenHeightPx > 720) {
                    negotiatedResolutionType = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080
                } else {
                    negotiatedResolutionType = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720
                }
            }
        } else {
            // Manual selection: Adapt to orientation
            if (screenHeightPx > screenWidthPx) { // Portrait
                negotiatedResolutionType = when (selectedResolution) {
                    Settings.Resolution._800x480 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280 // Upgrade to 720p Port
                    Settings.Resolution._1280x720 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280
                    Settings.Resolution._1920x1080 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1080x1920
                    Settings.Resolution._2560x1440 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1440x2560
                    else -> selectedResolution?.codec
                }
            } else {
                negotiatedResolutionType = selectedResolution?.codec
            }
        }

        if (!isSmallScreen) {
            val sWidth = screenWidthPx.toFloat()
            val sHeight = screenHeightPx.toFloat()
            if (getNegotiatedWidth() > 0 && getNegotiatedHeight() > 0) { // Ensure division by zero is avoided
                 if (sWidth / sHeight < getAspectRatio()) {
                    isPortraitScaled = true
                    scaleFactor = (sHeight * 1.0f) / getNegotiatedHeight().toFloat()
                } else {
                    isPortraitScaled = false
                    scaleFactor = (sWidth * 1.0f) / getNegotiatedWidth().toFloat()
                }
            } else {
                scaleFactor = 1.0f // Default if negotiated resolution is not valid
            }
        }
        AppLog.i("CarScreen isSmallScreen: $isSmallScreen, scaleFactor: ${scaleFactor}")
        AppLog.i("CarScreen using: $negotiatedResolutionType, number: ${negotiatedResolutionType?.number}, scales: scaleX: ${getScaleX()}, scaleY: ${getScaleY()}")
    }

    fun getAdjustedHeight(): Int {
        return (getNegotiatedHeight() * scaleFactor).roundToInt()
    }

    fun getAdjustedWidth(): Int {
        return (getNegotiatedWidth() * scaleFactor).roundToInt()
    }

    private fun getAspectRatio(): Float {
        return getNegotiatedWidth().toFloat() / getNegotiatedHeight().toFloat()
    }

    fun getNegotiatedHeight(): Int {
        val resString = negotiatedResolutionType.toString().replace("_", "")
        return resString.split("x")[1].toInt()
    }

    fun getNegotiatedWidth(): Int {
        val resString = negotiatedResolutionType.toString().replace("_", "")
        return resString.split("x")[0].toInt()
    }

    fun getHeightMargin(): Int {
        val margin = ((getAdjustedHeight() - screenHeightPx) / scaleFactor).roundToInt()
        return margin.coerceAtLeast(0)
    }

    fun getWidthMargin(): Int {
        val margin = ((getAdjustedWidth() - screenWidthPx) / scaleFactor).roundToInt()
        return margin.coerceAtLeast(0)
    }

    private fun divideOrOne(numerator: Float, denominator: Float): Float {
        return if (denominator == 0.0f) 1.0f else numerator / denominator
    }

    fun getScaleX(): Float {
        if (getNegotiatedWidth() > screenWidthPx) {
            return divideOrOne(getNegotiatedWidth().toFloat(), screenWidthPx.toFloat())
        }
        if (isPortraitScaled) {
            return divideOrOne(getAspectRatio(), (screenWidthPx.toFloat() / screenHeightPx.toFloat()))
        }
        return 1.0f
    }
        // Stretch option PR #259
        fun getScaleY(): Float {
        if (getNegotiatedHeight() > screenHeightPx) {
            return if (stretchToFill) {
                // Before PR #233 Fix scaler Y
                divideOrOne(getNegotiatedHeight().toFloat(), screenHeightPx.toFloat())
            } else {
                // After PR #233 Fix scaler Y
                divideOrOne((screenWidthPx.toFloat() / screenHeightPx.toFloat()), getAspectRatio())
            }
        }

        if (isPortraitScaled) {
            return 1.0f
        }

        return divideOrOne((screenWidthPx.toFloat() / screenHeightPx.toFloat()), getAspectRatio())
    }

    fun getDensityWidth(): Int {
        return (screenWidthPx / density).roundToInt()
    }

    fun getDensityHeight(): Int {
        return (screenHeightPx / density).roundToInt()
    }

    fun getDensityDpi(): Int {
        return if (this::currentSettings.isInitialized && currentSettings.dpiPixelDensity != 0) {
            currentSettings.dpiPixelDensity
        } else {
            densityDpi
        }
    }

    fun getHorizontalCorrection(): Float {
        return (getNegotiatedWidth() - getWidthMargin()).toFloat() / screenWidthPx.toFloat()
    }

    fun getVerticalCorrection(): Float {
        val fIntValue = (getNegotiatedHeight() - getHeightMargin()).toFloat() / screenHeightPx.toFloat()
        return fIntValue
    }
}
