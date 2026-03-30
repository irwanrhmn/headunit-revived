package com.andrerinas.headunitrevived.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.andrerinas.headunitrevived.aap.protocol.proto.Wireless
import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.*

/**
 * Manages the official Android Auto Wireless Bluetooth handshake.
 * This class implements the RFCOMM server protocol to exchange WiFi credentials with the phone.
 */
class NativeAaHandshakeManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private val AA_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        private val HFP_UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
        private val A2DP_SOURCE_UUID = UUID.fromString("00001112-0000-1000-8000-00805f9b34fb")

        fun checkCompatibility(): Boolean {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            if (!adapter.isEnabled) return false
            return try {
                val socket = adapter.listenUsingRfcommWithServiceRecord("Compatibility Check", AA_UUID)
                socket.close()
                AppLog.i("NativeAA: Compatibility Check SUCCESS")
                true
            } catch (e: Exception) {
                AppLog.w("NativeAA: Compatibility Check FAILED: ${e.message}")
                false
            }
        }
    }

    private var aaServerSocket: BluetoothServerSocket? = null
    private var hfpServerSocket: BluetoothServerSocket? = null
    private var isRunning = false

    private var currentSsid: String? = null
    private var currentPsk: String? = null
    private var currentIp: String? = null
    private var currentBssid: String? = null

    /**
     * Updates the WiFi credentials that will be sent to the phone during the next handshake.
     */
    fun updateWifiCredentials(ssid: String, psk: String, ip: String, bssid: String) {
        AppLog.i("NativeAA: Credentials updated. SSID=$ssid, IP=$ip, BSSID=$bssid")
        currentSsid = ssid
        currentPsk = psk
        currentIp = ip
        currentBssid = bssid
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        isRunning = true

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            AppLog.e("NativeAA: Bluetooth adapter not available or disabled")
            return
        }

        AppLog.i("NativeAA: Starting Bluetooth Handshake Servers...")

        // Start AA RFCOMM Server
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-RfcommServer")) {
            try {
                aaServerSocket = adapter.listenUsingRfcommWithServiceRecord("Android Auto", AA_UUID)
                while (isRunning && isActive) {
                    val socket = aaServerSocket?.accept()
                    if (socket != null) {
                        AppLog.i("NativeAA: Connection accepted from ${socket.remoteDevice.name}")
                        handleHandshake(socket)
                    }
                }
            } catch (e: IOException) {
                if (isRunning) AppLog.d("NativeAA: AA Server socket closed: ${e.message}")
            }
        }

        // Start HFP RFCOMM Server (Required by some phones to detect HU)
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpServer")) {
            try {
                hfpServerSocket = adapter.listenUsingRfcommWithServiceRecord("Hands-Free Unit", HFP_UUID)
                while (isRunning && isActive) {
                    val socket = hfpServerSocket?.accept()
                    if (socket != null) {
                        // Just consume and close, HFP is only a "presence" signal for us
                        scope.launch(Dispatchers.IO) {
                            try {
                                val buf = ByteArray(1024)
                                socket.inputStream.read(buf)
                            } catch (e: Exception) {}
                            finally { try { socket.close() } catch (e: Exception) {} }
                        }
                    }
                }
            } catch (e: IOException) {
                if (isRunning) AppLog.d("NativeAA: HFP Server socket closed: ${e.message}")
            }
        }

        // Active Poke logic: Wake up the phone if it's already paired but not looking for HU
        val settings = com.andrerinas.headunitrevived.App.provide(context).settings
        val lastMac = settings.autoStartBluetoothDeviceMac
        if (lastMac.isNotEmpty()) {
            scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Wakeup")) {
                delay(2000) // Give the servers time to start
                AppLog.i("NativeAA: Attempting active poke to phone ($lastMac)...")
                try {
                    val device = adapter.getRemoteDevice(lastMac)
                    val socket = device.createRfcommSocketToServiceRecord(A2DP_SOURCE_UUID)
                    socket.connect()
                    AppLog.i("NativeAA: Successfully poked phone ($lastMac).")
                    socket.close()
                } catch (e: Exception) {
                    AppLog.d("NativeAA: Active poke failed (this is often normal): ${e.message}")
                }
            }
        }
    }

    private suspend fun handleHandshake(socket: BluetoothSocket) = withContext(Dispatchers.IO) {
        try {
            val input = DataInputStream(socket.inputStream)
            val output = socket.outputStream

            AppLog.i("NativeAA: Waiting for WiFi credentials to be ready...")
            // Wait up to 30 seconds for credentials
            var attempts = 0
            while ((currentSsid == null || currentIp == null) && attempts < 60) {
                delay(500)
                attempts++
            }

            if (currentSsid == null || currentIp == null) {
                AppLog.e("NativeAA: Handshake failed - No WiFi credentials available")
                return@withContext
            }

            val ip = currentIp!!
            val ssid = currentSsid!!
            val psk = currentPsk ?: ""
            val bssid = currentBssid ?: ""

            AppLog.i("NativeAA: Sending WifiStartRequest (Type 1) to $ip:5288")
            sendWifiStartRequest(output, ip, 5288)

            val response = readProtobuf(input)
            if (response.type == 2) {
                AppLog.i("NativeAA: Phone requested security info. Sending SSID=$ssid, BSSID=$bssid")
                sendWifiSecurityResponse(output, ssid, psk, bssid)
                AppLog.i("NativeAA: Handshake success. Keeping BT socket alive for transition...")
                
                // Keep the socket open for 20 seconds so the phone feels "stable" 
                // during the WiFi switch
                delay(20000)
            } else {
                AppLog.w("NativeAA: Unexpected response type: ${response.type}")
            }

        } catch (e: Exception) {
            AppLog.e("NativeAA: Handshake error: ${e.message}")
        } finally {
            try { socket.close() } catch (e: Exception) {}
            AppLog.i("NativeAA: BT Handshake socket closed.")
        }
    }

    private fun sendWifiStartRequest(output: OutputStream, ip: String, port: Int) {
        val request = Wireless.WifiStartRequest.newBuilder()
            .setIpAddress(ip)
            .setPort(port)
            .setStatus(0)
            .build()
        sendProtobuf(output, request.toByteArray(), 1)
    }

    private fun sendWifiSecurityResponse(output: OutputStream, ssid: String, key: String, bssid: String) {
        val response = Wireless.WifiInfoResponse.newBuilder()
            .setSsid(ssid)
            .setKey(key)
            .setBssid(bssid)
            .setSecurityMode(Wireless.SecurityMode.WPA2_PERSONAL)
            .setAccessPointType(Wireless.AccessPointType.STATIC)
            .build()
        sendProtobuf(output, response.toByteArray(), 3)
    }

    private fun sendProtobuf(output: OutputStream, data: ByteArray, type: Short) {
        val buffer = ByteBuffer.allocate(data.size + 4)
        buffer.put((data.size shr 8).toByte())
        buffer.put((data.size and 0xFF).toByte())
        buffer.putShort(type)
        buffer.put(data)
        output.write(buffer.array())
        output.flush()
    }

    private fun readProtobuf(input: DataInputStream): ProtobufMessage {
        val header = ByteArray(4)
        input.readFully(header)
        val size = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        val type = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val payload = if (size > 0) {
            val p = ByteArray(size)
            input.readFully(p)
            p
        } else ByteArray(0)
        return ProtobufMessage(type, payload)
    }

    data class ProtobufMessage(val type: Int, val payload: ByteArray)

    fun stop() {
        isRunning = false
        try { aaServerSocket?.close() } catch (e: Exception) {}
        try { hfpServerSocket?.close() } catch (e: Exception) {}
        aaServerSocket = null
        hfpServerSocket = null
        currentSsid = null
        currentIp = null
        currentPsk = null
    }
}
