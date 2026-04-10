package com.andrerinas.headunitrevived.connection

import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket

class NearbySocket : Socket() {
    @Volatile var inputStreamWrapper: InputStream? = null
    @Volatile var outputStreamWrapper: ParcelFileDescriptor.AutoCloseOutputStream? = null

    override fun isConnected(): Boolean {
        return true
    }
    
    override fun getInetAddress(): InetAddress {
        return InetAddress.getLoopbackAddress()
    }

    override fun getInputStream(): InputStream {
        return object : InputStream() {
            override fun read(): Int {
                while (inputStreamWrapper == null) {
                    Thread.sleep(10)
                }
                return inputStreamWrapper!!.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                while (inputStreamWrapper == null) {
                    Thread.sleep(10)
                }
                return inputStreamWrapper!!.read(b, off, len)
            }
        }
    }

    override fun getOutputStream(): OutputStream {
        while (outputStreamWrapper == null) {
            Thread.sleep(10)
        }
        return outputStreamWrapper!!
    }
}