package com.andrerinas.headunitrevived.connection

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UsbAccessoryConnection(private val usbMgr: UsbManager, private val device: UsbDevice) : AccessoryConnection {
    // @Volatile so isConnected / isDeviceRunning see the latest value without a lock.
    @Volatile private var usbDeviceConnected: UsbDeviceCompat? = null
    @Volatile private var usbDeviceConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    // @Volatile so sendBlocking / recvBlocking see updates from connect() / resetInterface()
    // without holding sStateLock during the transfer.
    @Volatile private var endpointIn: UsbEndpoint? = null
    @Volatile private var endpointOut: UsbEndpoint? = null

    // Internal buffer — only ever accessed by the poll thread (recvBlocking); no lock needed.
    private val internalBuffer = ByteArray(16384)
    private var internalBufferPos = 0
    private var internalBufferAvailable = 0

    fun isDeviceRunning(device: UsbDevice): Boolean {
        synchronized(sStateLock) {
            val connected = usbDeviceConnected ?: return false
            return UsbDeviceCompat.getUniqueName(device) == connected.uniqueName
        }
    }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        return@withContext try {
            connect(device)
        } catch (e: UsbOpenException) {
            AppLog.e(e)
            false
        }
    }

    @Throws(UsbOpenException::class)
    private fun connect(device: UsbDevice): Boolean {
        if (usbDeviceConnection != null) {
            disconnect()
        }
        synchronized(sStateLock) {
            try {
                usbOpen(device)
            } catch (e: UsbOpenException) {
                disconnect()
                throw e
            }

            val ret = initEndpoint()
            if (ret < 0) {
                disconnect()
                return false
            }

            usbDeviceConnected = UsbDeviceCompat(device)
            return true
        }
    }

    @Throws(UsbOpenException::class)
    private fun usbOpen(device: UsbDevice) {
        var connection: UsbDeviceConnection? = null
        var lastError: Throwable? = null

        for (i in 0 until 3) {
            try {
                connection = usbMgr.openDevice(device)
                if (connection != null) break
            } catch (t: Throwable) {
                lastError = t
                AppLog.w("Attempt ${i+1} to openDevice failed: ${t.message}")
            }
            if (i < 2) try { Thread.sleep(500) } catch (_: Exception) {}
        }

        usbDeviceConnection = connection ?: throw UsbOpenException(lastError ?: Throwable("openDevice: connection is null"))

        AppLog.i("Established connection: " + usbDeviceConnection!!)

        try {
            val interfaceCount = device.interfaceCount
            if (interfaceCount <= 0) {
                AppLog.e("interfaceCount: $interfaceCount")
                throw UsbOpenException("No usb interfaces")
            }
            AppLog.i("interfaceCount: $interfaceCount")
            usbInterface = device.getInterface(0)

            if (!usbDeviceConnection!!.claimInterface(usbInterface, true)) {
                throw UsbOpenException("Error claiming interface")
            }
        } catch (e: Throwable) {
            AppLog.e(e)
            throw UsbOpenException(e)
        }
    }

    private fun initEndpoint(): Int {
        AppLog.i("Check accessory endpoints")
        endpointIn = null
        endpointOut = null

        for (i in 0 until usbInterface!!.endpointCount) {
            val ep = usbInterface!!.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN) {
                if (endpointIn == null) endpointIn = ep
            } else {
                if (endpointOut == null) endpointOut = ep
            }
        }
        if (endpointIn == null || endpointOut == null) {
            AppLog.e("Unable to find bulk endpoints")
            return -1
        }

        AppLog.i("Connected have EPs")
        return 0
    }

    private fun resetInterface() {
        if (usbDeviceConnection == null) return
        synchronized(sStateLock) {
            val connection = usbDeviceConnection ?: return
            val iface = usbInterface ?: return
            AppLog.w("Attempting USB interface soft-reset...")
            try {
                connection.releaseInterface(iface)
                Thread.sleep(100)
                if (connection.claimInterface(iface, true)) {
                    AppLog.i("USB interface re-claimed successfully")
                    internalBufferPos = 0
                    internalBufferAvailable = 0
                    initEndpoint()
                } else {
                    AppLog.e("Failed to re-claim USB interface — disconnecting")
                    disconnect()
                }
            } catch (e: Exception) {
                AppLog.e("Error during USB reset: ${e.message}")
            }
        }
    }

    override fun disconnect() {
        // close() is thread-safe and immediately aborts any in-flight bulkTransfer(),
        // so both sendBlocking and recvBlocking unblock within milliseconds.
        usbDeviceConnection?.close()

        synchronized(sStateLock) {
            if (usbDeviceConnected != null) {
                AppLog.i(usbDeviceConnected!!.toString())
            }
            endpointIn = null
            endpointOut = null

            if (usbDeviceConnection != null) {
                var bret = false
                if (usbInterface != null) {
                    // releaseInterface() may fail since close() was already called; log and continue.
                    bret = try { usbDeviceConnection!!.releaseInterface(usbInterface) } catch (_: Exception) { false }
                }
                if (bret) {
                    AppLog.i("OK releaseInterface()")
                } else {
                    AppLog.e("Error releaseInterface()")
                }
            }
            usbDeviceConnection = null
            usbInterface = null
            usbDeviceConnected = null
            internalBufferPos = 0
            internalBufferAvailable = 0
        }
    }

    override val isConnected: Boolean
        get() = usbDeviceConnected != null

    override val isSingleMessage: Boolean
        get() = false

    // consecutiveReadErrors is only accessed by the poll thread; no lock needed.
    private var consecutiveReadErrors = 0
    private val maxConsecutiveErrorsBeforeReset = 10

    // Volatile reads capture the latest connection/endpoint references; bulkTransfer runs
    // entirely outside any lock. If disconnect() calls close() concurrently, bulkTransfer
    // returns -1 immediately — a safe, recoverable outcome.
    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        val connection = usbDeviceConnection ?: return -1
        val ep = endpointOut ?: return -1
        return try {
            connection.bulkTransfer(ep, buf, length, timeout)
        } catch (e: Exception) {
            AppLog.e("USB Write Error: ${e.message}")
            -1
        }
    }

    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
        val connection = usbDeviceConnection ?: return -1
        val ep = endpointIn ?: return -1

        return try {
            var totalReturned = 0

            while (totalReturned < length) {
                // 1. Serve from internal buffer if data is available
                if (internalBufferAvailable > 0) {
                    val toCopy = minOf(length - totalReturned, internalBufferAvailable)
                    System.arraycopy(internalBuffer, internalBufferPos, buf, totalReturned, toCopy)
                    internalBufferPos += toCopy
                    internalBufferAvailable -= toCopy
                    totalReturned += toCopy

                    if (totalReturned >= length || !readFully) break
                    continue
                }

                // 2. Internal buffer empty, read new 16KB chunk from USB
                val read = try {
                    connection.bulkTransfer(ep, internalBuffer, internalBuffer.size, timeout)
                } catch (e: Exception) {
                    AppLog.e("USB Read Error: ${e.message}")
                    -1
                }

                if (read < 0) {
                    consecutiveReadErrors++
                    if (consecutiveReadErrors >= maxConsecutiveErrorsBeforeReset) {
                        AppLog.w("Too many read errors, attempting interface reset...")
                        resetInterface()
                        consecutiveReadErrors = 0
                    }
                    return if (totalReturned > 0) totalReturned else -1
                }
                if (read == 0) {
                    consecutiveReadErrors = 0
                    return totalReturned
                }

                consecutiveReadErrors = 0
                internalBufferPos = 0
                internalBufferAvailable = read
                // Loop will continue and serve from the new internalBuffer data
            }

            totalReturned

        } catch (e: Exception) {
            AppLog.e("USB Read Error: ${e.message}")
            -1
        }
    }

    private class UsbOpenException : Exception {
        constructor(message: String) : super(message)
        constructor(tr: Throwable) : super(tr)
    }

    companion object {
        // Held only during state mutations (connect / disconnect / reset).
        // Neither sendBlocking nor recvBlocking holds this lock during bulkTransfer.
        private val sStateLock = Any()
    }
}
