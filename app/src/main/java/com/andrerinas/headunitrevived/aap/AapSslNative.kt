package com.andrerinas.headunitrevived.aap

import androidx.annotation.Keep
import com.andrerinas.headunitrevived.aap.protocol.messages.Messages
import com.andrerinas.headunitrevived.connection.AccessoryConnection
import com.andrerinas.headunitrevived.utils.AppLog

/**
 * Native SSL implementation using OpenSSL via JNI.
 * This is generally faster on older devices than Java SSLEngine.
 */
@Keep
internal class AapSslNative : AapSsl {

    companion object {
        init {
            try {
                System.loadLibrary("crypto_1_1")
                System.loadLibrary("ssl_1_1")
                System.loadLibrary("hu_jni")
            } catch (e: UnsatisfiedLinkError) {
                AppLog.e("Failed to load native SSL libraries", e)
            }
        }
    }

    @Keep
    private external fun native_ssl_prepare(): Int
    @Keep
    private external fun native_ssl_do_handshake(): Int
    @Keep
    private external fun native_ssl_bio_read(offset: Int, res_len: Int, res_buf: ByteArray): Int
    @Keep
    private external fun native_ssl_bio_write(offset: Int, msg_len: Int, msg_buf: ByteArray): Int
    @Keep
    private external fun native_ssl_read(offset: Int, res_len: Int, res_buf: ByteArray): Int
    @Keep
    private external fun native_ssl_write(offset: Int, msg_len: Int, msg_buf: ByteArray): Int

    private val bio_read = ByteArray(Messages.DEF_BUFFER_LENGTH)
    private val enc_buf = ByteArray(Messages.DEF_BUFFER_LENGTH)
    private val dec_buf = ByteArray(Messages.DEF_BUFFER_LENGTH)

    override fun performHandshake(connection: AccessoryConnection): Boolean {
        if (prepare() < 0) {
            AppLog.e("Native SSL prepare failed")
            return false
        }

        val buffer = ByteArray(Messages.DEF_BUFFER_LENGTH)
        var hs_ctr = 0
        while (hs_ctr < 2) {
            hs_ctr++

            val handshakeData = handshakeRead()
            if (handshakeData == null) {
                AppLog.e("Native SSL handshakeRead failed")
                return false
            }

            // Wrap in AAP Message: Channel 0, Flags 3, Type 3
            val bio = Messages.createRawMessage(0, 3, 3, handshakeData)

            if (connection.sendBlocking(bio, bio.size, 5000) < 0) {
                AppLog.e("Native SSL handshake send failed")
                return false
            }

            val size = connection.recvBlocking(buffer, buffer.size, 5000, false)
            if (size <= 6) {
                AppLog.e("Native SSL handshake recv failed or too small")
                return false
            }

            handshakeWrite(6, size - 6, buffer)
        }
        return true
    }

    private fun prepare(): Int {
        val ret = native_ssl_prepare()
        if (ret < 0) {
            AppLog.e("SSL prepare failed: $ret")
        }
        return ret
    }

    private fun handshakeRead(): ByteArray? {
        native_ssl_do_handshake()
        val size = native_ssl_bio_read(0, Messages.DEF_BUFFER_LENGTH, bio_read)
        if (size <= 0) {
            AppLog.i("SSL BIO read error")
            return null
        }
        val result = ByteArray(size)
        System.arraycopy(bio_read, 0, result, 0, size)
        return result
    }

    private fun handshakeWrite(start: Int, length: Int, buffer: ByteArray): Int {
        return native_ssl_bio_write(start, length, buffer)
    }

    // Stub for delegated tasks (Native SSL handles this internally or synchronously)
    private fun runDelegatedTasks() {
        // No-op for Native SSL
    }

    // Stub for handshake status (Native SSL manages state internally)
    private fun getHandshakeStatus(): javax.net.ssl.SSLEngineResult.HandshakeStatus {
        return javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
    }
    
    // Stub for reset
    override fun postHandshakeReset() {
        // No-op
    }

    override fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? {
        val bytes_written = native_ssl_bio_write(start, length, buffer)
        // Write encrypted to SSL input BIO
        if (bytes_written <= 0) {
            AppLog.e("BIO_write() bytes_written: %d", bytes_written)
            return null
        }

        val bytes_read = native_ssl_read(0, Messages.DEF_BUFFER_LENGTH, dec_buf)
        // Read decrypted to decrypted rx buf
        if (bytes_read <= 0) {
            AppLog.e("SSL_read bytes_read: %d", bytes_read)
            return null
        }

        return ByteArrayWithLimit(dec_buf, bytes_read)
    }

    override fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? {

        val bytes_written = native_ssl_write(offset, length, buffer)
        // Write plaintext to SSL
        if (bytes_written <= 0) {
            AppLog.e("SSL_write() bytes_written: %d", bytes_written)
            return null
        }

        if (bytes_written != length) {
            AppLog.e("SSL Write len: %d  bytes_written: %d", length, bytes_written)
        }

        // AppLog.v("SSL Write len: %d  bytes_written: %d", length, bytes_written)

        val bytes_read = native_ssl_bio_read(offset, Messages.DEF_BUFFER_LENGTH - offset, enc_buf)
        if (bytes_read <= 0) {
            AppLog.e("BIO read  bytes_read: %d", bytes_read)
            return null
        }

        // AppLog.v("BIO read bytes_read: %d", bytes_read)

        return ByteArrayWithLimit(enc_buf, bytes_read + offset)
    }

}
