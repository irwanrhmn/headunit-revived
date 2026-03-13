package com.andrerinas.headunitrevived.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.contract.LocationUpdateIntent
import java.util.Calendar

class AppThemeManager(
    private val context: Context,
    private val settings: Settings
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var nightModeCalculator = NightMode(settings, false)

    private var lastEmittedNight: Boolean? = null
    private var currentLux: Float = -1f
    private var currentBrightness: Int = -1
    private var isFirstSensorReading = true
    private var isSensorRegistered = false
    private var isObserverRegistered = false

    private val handler = Handler(Looper.getMainLooper())

    private var pendingValue: Boolean? = null
    private val debounceRunnable = Runnable {
        pendingValue?.let { isNight ->
            if (lastEmittedNight != isNight) {
                lastEmittedNight = isNight
                applyNightMode(isNight)
            }
        }
    }

    private val brightnessObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            update()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocationUpdateIntent.action) {
                val oldLux = currentLux
                val oldBright = currentBrightness
                nightModeCalculator = NightMode(settings, true)
                nightModeCalculator.currentLux = oldLux
                nightModeCalculator.currentBrightness = oldBright
            }
            update()
        }
    }

    fun start() {
        AppLog.d("AppThemeManager: Starting with mode=${settings.appTheme}, luxThreshold=${settings.nightModeThresholdLux}, brightnessThreshold=${settings.nightModeThresholdBrightness}")
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(LocationUpdateIntent.action)
        }

        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        refreshListeners()

        lastEmittedNight = null
        update(debounce = false)
    }

    fun stop() {
        try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        if (isSensorRegistered) {
            sensorManager.unregisterListener(this)
            isSensorRegistered = false
        }
        if (isObserverRegistered) {
            context.contentResolver.unregisterContentObserver(brightnessObserver)
            isObserverRegistered = false
        }
        handler.removeCallbacks(debounceRunnable)
    }

    private fun refreshListeners() {
        val theme = settings.appTheme

        if (theme == Settings.AppTheme.LIGHT_SENSOR) {
            if (!isSensorRegistered && lightSensor != null) {
                sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
                isSensorRegistered = true
            }
        } else {
            if (isSensorRegistered) {
                sensorManager.unregisterListener(this)
                isSensorRegistered = false
            }
        }

        if (theme == Settings.AppTheme.SCREEN_BRIGHTNESS) {
            if (!isObserverRegistered) {
                context.contentResolver.registerContentObserver(
                    SystemSettings.System.getUriFor(SystemSettings.System.SCREEN_BRIGHTNESS),
                    false, brightnessObserver
                )
                if (Build.VERSION.SDK_INT >= 28) {
                    context.contentResolver.registerContentObserver(
                        SystemSettings.System.getUriFor("screen_brightness_float"),
                        false, brightnessObserver
                    )
                }
                isObserverRegistered = true
            }
        } else {
            if (isObserverRegistered) {
                context.contentResolver.unregisterContentObserver(brightnessObserver)
                isObserverRegistered = false
            }
        }
    }

    private fun update(debounce: Boolean = true) {
        var isNight = false
        val threshold = settings.nightModeThresholdLux
        val thresholdBrightness = settings.nightModeThresholdBrightness

        when (settings.appTheme) {
            Settings.AppTheme.LIGHT_SENSOR -> {
                if (currentLux >= 0) {
                    val hyst = 5.0f
                    val currentIsNight = lastEmittedNight ?: false
                    isNight = if (currentIsNight) {
                        currentLux < (threshold + hyst)
                    } else {
                        currentLux < threshold
                    }
                    AppLog.d("AppThemeManager: LIGHT_SENSOR lux=$currentLux threshold=$threshold isNight=$isNight")
                }
            }
            Settings.AppTheme.SCREEN_BRIGHTNESS -> {
                currentBrightness = readBrightnessPercent()
                if (currentBrightness >= 0) {
                    val hyst = 4
                    val currentIsNight = lastEmittedNight ?: false
                    isNight = if (currentIsNight) {
                        currentBrightness < (thresholdBrightness + hyst)
                    } else {
                        currentBrightness < thresholdBrightness
                    }
                    AppLog.d("AppThemeManager: SCREEN_BRIGHTNESS brightness=$currentBrightness% threshold=$thresholdBrightness% isNight=$isNight")
                }
            }
            Settings.AppTheme.AUTO_SUNRISE -> {
                nightModeCalculator = NightMode(settings, true)
                isNight = nightModeCalculator.current
                AppLog.d("AppThemeManager: AUTO_SUNRISE isNight=$isNight")
            }
            Settings.AppTheme.MANUAL_TIME -> {
                val now = Calendar.getInstance()
                val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                val start = settings.nightModeManualStart
                val end = settings.nightModeManualEnd
                isNight = if (start <= end) {
                    currentMinutes in start..end
                } else {
                    currentMinutes >= start || currentMinutes <= end
                }
                AppLog.d("AppThemeManager: MANUAL_TIME currentMinutes=$currentMinutes start=$start end=$end isNight=$isNight")
            }
            else -> return
        }

        if (debounce) {
            if (pendingValue != isNight) {
                pendingValue = isNight
                handler.removeCallbacks(debounceRunnable)
                handler.postDelayed(debounceRunnable, 2000)
            }
        } else {
            handler.removeCallbacks(debounceRunnable)
            if (lastEmittedNight != isNight) {
                lastEmittedNight = isNight
                applyNightMode(isNight)
            }
        }
    }

    private fun applyNightMode(isNight: Boolean) {
        val mode = if (isNight) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        AppLog.d("AppThemeManager: Setting night mode to ${if (isNight) "NIGHT" else "DAY"}")
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            val newLux = event.values[0]
            if (kotlin.math.abs(newLux - currentLux) >= 1.0f || isFirstSensorReading) {
                currentLux = newLux
                nightModeCalculator.currentLux = currentLux
                if (isFirstSensorReading) {
                    isFirstSensorReading = false
                    update(debounce = false)
                } else {
                    update()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun readBrightnessPercent(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val floatVal = SystemSettings.System.getFloat(
                    context.contentResolver, "screen_brightness_float"
                )
                (floatVal * 100).toInt().coerceIn(0, 100)
            } else {
                val intVal = SystemSettings.System.getInt(
                    context.contentResolver, SystemSettings.System.SCREEN_BRIGHTNESS
                )
                (intVal * 100 / 255).coerceIn(0, 100)
            }
        } catch (e: Exception) { -1 }
    }

    fun getCurrentLux(): Float = currentLux
    fun getCurrentBrightness(): Int = currentBrightness

    companion object {
        fun applyStaticTheme(settings: Settings) {
            val mode = when (settings.appTheme) {
                Settings.AppTheme.AUTOMATIC -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                Settings.AppTheme.CLEAR -> AppCompatDelegate.MODE_NIGHT_NO
                Settings.AppTheme.DARK, Settings.AppTheme.EXTREME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> return
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        fun isStaticMode(theme: Settings.AppTheme): Boolean {
            return theme == Settings.AppTheme.AUTOMATIC ||
                    theme == Settings.AppTheme.CLEAR ||
                    theme == Settings.AppTheme.DARK ||
                    theme == Settings.AppTheme.EXTREME_DARK
        }
    }
}
