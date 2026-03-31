package com.andrerinas.headunitrevived.connection

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class SocketAccessoryConnection(private val ip: String, private val port: Int, private val context: Context) : AccessoryConnection {
    private var output: OutputStream? = null
    private var input: DataInputStream? = null
    private var transport: Socket

    init {
        transport = Socket()
    }

    constructor(socket: Socket, context: Context) : this(socket.inetAddress.hostAddress ?: "", socket.port, context) {
        this.transport = socket
    }


    override val isSingleMessage: Boolean
        get() = true

    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        val out = output ?: return -1
        return try {
            out.write(buf, 0, length)
            out.flush()
            length
        } catch (e: IOException) {
            AppLog.e(e)
            -1
        }
    }

    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
        val inp = input ?: return -1
        return try {
            // Dynamically apply the caller's timeout so handshake (short timeouts)
            // and streaming (long timeouts) both work correctly on the same socket.
            try { transport.soTimeout = timeout } catch (_: Exception) {}
            if (readFully) {
                inp.readFully(buf, 0, length)
                length
            } else {
                inp.read(buf, 0, length)
            }
        } catch (e: SocketTimeoutException) {
            // With raw DataInputStream (no BufferedInputStream), timeout during
            // small reads (4-byte header) virtually never causes partial consumption.
            // Let the caller decide if this is fatal based on context.
            0
        } catch (e: IOException) {
            -1
        }
    }

    override val isConnected: Boolean
        get() = transport.isConnected

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!transport.isConnected) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                   val net = cm.activeNetwork

                    if (net != null) {
                        try {
                            net.bindSocket(transport)
                            AppLog.i("Bound socket to active network: $net")
                        } catch (e: Exception) {
                            AppLog.w("Failed to bind socket to network", e)
                        }
                    }
                } else {
                    // Legacy API < 23 (Lollipop & KitKat & JB)
                    @Suppress("DEPRECATION")
                    if (cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true) {
                        try {
                            val addr = InetAddress.getByName(ip)
                            val b = addr.address
                            val ipInt = ((b[3].toInt() and 0xFF) shl 24) or
                                        ((b[2].toInt() and 0xFF) shl 16) or
                                        ((b[1].toInt() and 0xFF) shl 8) or
                                        (b[0].toInt() and 0xFF)
                            // cm.requestRouteToHost(ConnectivityManager.TYPE_WIFI, ipInt)
                            // Use reflection because requestRouteToHost is removed in newer SDKs
                            val m = cm.javaClass.getMethod("requestRouteToHost", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                            m.invoke(cm, ConnectivityManager.TYPE_WIFI, ipInt)
                            AppLog.i("Legacy: Requested route to host $ip")
                        } catch (e: Exception) {
                            AppLog.w("Legacy: Failed requestRouteToHost", e)
                        }
                    }
                }
                
                // Chinese Headunit Mediatek Correction
                try {
                    transport.connect(InetSocketAddress(ip, port), 5000)
                } catch (e: Throwable) {
                    val errorMessage = e.message ?: e.toString()
                    if (errorMessage.contains("com.mediatek.cta.CtaHttp") || errorMessage.contains("CtaHttp")) {
                        AppLog.e("HUR_DEBUG: MediaTek crash intercepted.")
                    } else {
                        throw IOException(e)
                    }
                }
                // Chinese Headunit Mediatek Correction
            }
            // WiFi needs tolerance for retransmissions,
            // power-save wakes, and bufferbloat. 1s was causing readFully to timeout
            // mid-header, desynchronizing the stream ("Failed to read full header").
            transport.soTimeout = 10000
            transport.tcpNoDelay = true
            transport.keepAlive = true
            transport.reuseAddress = true
            transport.trafficClass = 16 // IPTOS_LOWDELAY
            // Raw DataInputStream — no BufferedInputStream wrapper.
            // BufferedInputStream + readFully + timeout = internal buffer state corruption.
            input = DataInputStream(transport.getInputStream())
            output = transport.getOutputStream()
            return@withContext true
        } catch (e: IOException) {
            AppLog.e(e)
            return@withContext false
        }
    }

    override fun disconnect() {
        if (transport.isConnected) {
            try {
                transport.close()
            } catch (e: IOException) {
                AppLog.e(e)
            }

        }
        input = null
        output = null
    }

    companion object {
        private const val DEF_BUFFER_LENGTH = 131080
    }
}
