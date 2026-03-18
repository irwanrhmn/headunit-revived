package com.andrerinas.headunitrevived.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.connection.CommManager
import com.andrerinas.headunitrevived.connection.UsbAccessoryMode
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.DeviceIntent
import com.andrerinas.headunitrevived.utils.LocaleHelper
import com.andrerinas.headunitrevived.main.MainActivity
import com.andrerinas.headunitrevived.utils.Settings


class UsbAttachedActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private fun resolveUsbDevice(intent: Intent?): UsbDevice? {
        DeviceIntent(intent).device?.let { return it }

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList.values.toList()
        return if (devices.size == 1) {
            val device = devices[0]
            AppLog.i("No USB device in intent extras, falling back to single device from deviceList: ${UsbDeviceCompat(device).uniqueName}")
            device
        } else {
            AppLog.e("No USB device in intent extras and ${devices.size} devices in deviceList, cannot determine target")
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLog.i("USB Intent: $intent")

        val device = resolveUsbDevice(intent)
        if (device == null) {
            finish()
            return
        }

        val settings = Settings(this)
        if (settings.autoStartOnUsb && !App.provide(this).commManager.isConnected) {
            AppLog.i("USB auto-start: launching app")
            try {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                AppLog.w("Could not start UI from USB auto-start: ${e.message}")
            }
        }

        if (App.provide(this).commManager.connectionState.value is CommManager.ConnectionState.TransportStarted) {
            AppLog.e("Thread already running")
            finish()
            return
        }

        if (UsbDeviceCompat.isInAccessoryMode(device)) {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            if (!usbManager.hasPermission(device)) {
                AppLog.i("Usb in accessory mode but no permission. Requesting...")
                val permissionIntent = android.app.PendingIntent.getBroadcast(
                    this, 0,
                    Intent(com.andrerinas.headunitrevived.connection.UsbReceiver.ACTION_USB_DEVICE_PERMISSION).apply {
                        setPackage(packageName)
                    },
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                    else android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                usbManager.requestPermission(device, permissionIntent)
                finish()
                return
            }
            AppLog.i("Usb in accessory mode and has permission. Starting AapService.")
            ContextCompat.startForegroundService(this, Intent(this, AapService::class.java).apply {
                action = AapService.ACTION_CHECK_USB
            })
            finish()
            return
        }

        val deviceCompat = UsbDeviceCompat(device)
        if (!settings.isConnectingDevice(deviceCompat)) {
            AppLog.i("Skipping device " + deviceCompat.uniqueName)
            finish()
            return
        }

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val usbMode = UsbAccessoryMode(usbManager)
        AppLog.i("Switching USB device to accessory mode " + deviceCompat.uniqueName)
        Toast.makeText(this, getString(R.string.switching_usb_accessory_mode, deviceCompat.uniqueName), Toast.LENGTH_SHORT).show()
        // Run the USB control transfers on a background thread — they block for several
        // hundred ms and must not execute on the main thread (ANR risk).
        Thread {
            val result = usbMode.connectAndSwitch(device)
            runOnUiThread {
                if (result) {
                    Toast.makeText(this, getString(R.string.success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val device = resolveUsbDevice(getIntent())
        if (device == null) {
            finish()
            return
        }

        AppLog.i(UsbDeviceCompat.getUniqueName(device))

        if (App.provide(this).commManager.connectionState.value !is CommManager.ConnectionState.TransportStarted) {
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                AppLog.e("Usb in accessory mode")
                ContextCompat.startForegroundService(this, Intent(this, AapService::class.java).apply {
                    action = AapService.ACTION_CHECK_USB
                })
            }
        } else {
            AppLog.e("Thread already running")
        }

        finish()
    }
}
