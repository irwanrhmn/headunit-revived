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
    private var isInitialized: Boolean = false
    
    // Flag to determine if the projection should stretch and ignore aspect ratio
    private var stretchToFill: Boolean = false 
    
    // Forced scale for older devices (Legacy fix)
    var forcedScale: Boolean = false
        private set

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
        stretchToFill = settings.stretchToFill
        forcedScale = settings.forcedScale && settings.viewMode == Settings.ViewMode.SURFACE

        val realW: Int
        val realH: Int
        val usableW: Int
        val usableH: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // API 30+
            val windowManager = context.getSystemService(android.view.WindowManager::class.java)
            val bounds = windowManager.currentWindowMetrics.bounds
            // On API 30+, bounds on an Activity context often return the usable area.
            // We use the displayMetrics as a fallback for the physical area.
            realW = displayMetrics.widthPixels
            realH = displayMetrics.heightPixels
            usableW = bounds.width()
            usableH = bounds.height()
        } else { // Older APIs
            @Suppress("DEPRECATION")
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val display = windowManager.defaultDisplay
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            realW = size.x
            realH = size.y
            
            @Suppress("DEPRECATION")
            display.getSize(size)
            usableW = size.x
            usableH = size.y
        }

        // Only update if dimensions or settings changed
        if (isInitialized && realScreenWidthPx == realW && realScreenHeightPx == realH && this::currentSettings.isInitialized && currentSettings == settings) {
            return
        }

        isInitialized = true
        currentSettings = settings

        // Determine if we are planning to hide the bars (Immersive)
        val immersive = settings.fullscreenMode == Settings.FullscreenMode.IMMERSIVE || 
                        settings.fullscreenMode == Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH

        // THE ANCHOR: 
        // If we are immersive, our "World" is the physical screen. 
        // If we are NOT, our "World" is limited to the usable window area (no lying to AA).
        realScreenWidthPx = if (immersive) realW else usableW
        realScreenHeightPx = if (immersive) realH else usableH
        
        density = displayMetrics.density
        densityDpi = displayMetrics.densityDpi

        // Initial Insets: For non-immersive, the bars are already baked into the anchor (realSize = 736),
        // so we start with 0 system insets and just add manual settings.
        systemInsetLeft = settings.insetLeft
        systemInsetTop = settings.insetTop
        systemInsetRight = settings.insetRight
        systemInsetBottom = settings.insetBottom
        
        AppLog.i("[UI_DEBUG] HeadUnitScreenConfig: Honest Init | Mode: ${settings.fullscreenMode} | Anchor: ${realScreenWidthPx}x${realScreenHeightPx} | Seeded Insets: L$systemInsetLeft T$systemInsetTop R$systemInsetRight B$systemInsetBottom")
        
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
            screenWidthPx = realScreenWidthPx
            screenHeightPx = realScreenHeightPx
        }

        val selectedResolution = Settings.Resolution.fromId(currentSettings.resolutionId)
        val isPortraitDisplay = screenHeightPx > screenWidthPx

        // 1. Determine base negotiated resolution
        if (selectedResolution == Settings.Resolution.AUTO) {
            if (isPortraitDisplay) {
                negotiatedResolutionType = if (screenWidthPx > 720 || screenHeightPx > 1280) {
                    Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1080x1920
                } else {
                    Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280
                }
            } else {
                negotiatedResolutionType = when {
                    screenWidthPx <= 800 && screenHeightPx <= 480 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
                    (screenWidthPx >= 3840 || screenHeightPx >= 2160) && com.andrerinas.headunitrevived.decoder.VideoDecoder.isHevcSupported() && Build.VERSION.SDK_INT >= 24 -> 
                        Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._3840x2160
                    (screenWidthPx >= 2560 || screenHeightPx >= 1440) && com.andrerinas.headunitrevived.decoder.VideoDecoder.isHevcSupported() && Build.VERSION.SDK_INT >= 24 -> 
                        Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440
                    screenWidthPx > 1280 || screenHeightPx > 720 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080
                    else -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720
                }
            }
        } else {
            // Manual selection: Map to correct orientation
            val codec = selectedResolution?.codec ?: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
            negotiatedResolutionType = if (isPortraitDisplay) {
                when (selectedResolution) {
                    Settings.Resolution._800x480 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280
                    Settings.Resolution._1280x720 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280
                    Settings.Resolution._1920x1080 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1080x1920
                    Settings.Resolution._2560x1440 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1440x2560
                    Settings.Resolution._3840x2160 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2160x3840
                    else -> codec
                }
            } else {
                codec
            }
        }

        // 2. Perform scaling calculations (now safe because negotiatedResolutionType is set)
        AppLog.i("[UI_DEBUG] CarScreen: usable area ${screenWidthPx}x${screenHeightPx}, using $negotiatedResolutionType")

        if (screenHeightPx > screenWidthPx) {
            isSmallScreen = screenWidthPx <= 1080 && screenHeightPx <= 1920
        } else {
            isSmallScreen = screenWidthPx <= 1920 && screenHeightPx <= 1080
        }

        scaleFactor = 1.0f
        if (!isSmallScreen) {
            val sWidth = screenWidthPx.toFloat()
            val sHeight = screenHeightPx.toFloat()
            if (getNegotiatedWidth() > 0 && getNegotiatedHeight() > 0) {
                 if (sWidth / sHeight < getAspectRatio()) {
                    isPortraitScaled = true
                    scaleFactor = sHeight / getNegotiatedHeight().toFloat()
                } else {
                    isPortraitScaled = false
                    scaleFactor = sWidth / getNegotiatedWidth().toFloat()
                }
            }
        }
        
        AppLog.i("[UI_DEBUG] CarScreen isSmallScreen: $isSmallScreen, scaleFactor: $scaleFactor, margins: w=${getWidthMargin()}, h=${getHeightMargin()}")
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
        if (forcedScale) {
            return 1.0f
        }

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
        if (forcedScale) {
            return 1.0f
        }

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

    fun getUsableWidth(): Int = screenWidthPx
    fun getUsableHeight(): Int = screenHeightPx
}
