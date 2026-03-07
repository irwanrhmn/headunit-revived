package com.andrerinas.headunitrevived.aap

import com.andrerinas.headunitrevived.aap.protocol.messages.Messages
import com.andrerinas.headunitrevived.connection.AccessoryConnection
import com.andrerinas.headunitrevived.ssl.ConscryptInitializer
import com.andrerinas.headunitrevived.ssl.NoCheckTrustManager
import com.andrerinas.headunitrevived.ssl.SingleKeyKeyManager
import com.andrerinas.headunitrevived.utils.AppLog
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

class AapSslContext(keyManager: SingleKeyKeyManager): AapSsl {
    private val sslContext: SSLContext = createSslContext(keyManager)
    private lateinit var sslEngine: SSLEngine
    private lateinit var txBuffer: ByteBuffer
    private lateinit var rxBuffer: ByteBuffer

    override fun performHandshake(connection: AccessoryConnection): Boolean {
        if (prepare() < 0) return false

        val buffer = ByteArray(Messages.DEF_BUFFER_LENGTH)
        // Accumulates bytes across NEED_UNWRAP iterations when a TLS record is fragmented
        // across multiple USB packets. JSSE returns BUFFER_UNDERFLOW when the input is too
        // short to hold a complete record; without this accumulation the previous
        // handshakeWrite() inner loop would spin forever on the same partial data.
        var pendingTlsData = ByteArray(0)
        // Hard cap on the entire SSL phase.
        val deadline = android.os.SystemClock.elapsedRealtime() + SSL_HANDSHAKE_TIMEOUT_MS

        while (getHandshakeStatus() != SSLEngineResult.HandshakeStatus.FINISHED &&
                getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {

            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                AppLog.e("SSL Handshake: Timed out after ${SSL_HANDSHAKE_TIMEOUT_MS}ms")
                return false
            }

            when (getHandshakeStatus()) {
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    // Use buffered data first; only block on USB if the buffer is empty
                    // (normal case) or after BUFFER_UNDERFLOW appended more data below.
                    if (pendingTlsData.isEmpty()) {
                        val size = connection.recvBlocking(buffer, buffer.size, 2000, false)
                        if (size <= 6) {
                            AppLog.e("SSL Handshake: Receive failed or too small ($size)")
                            return false
                        }
                        pendingTlsData = buffer.copyOfRange(6, size)
                    }

                    rxBuffer.clear()
                    val data = ByteBuffer.wrap(pendingTlsData)
                    val result = sslEngine.unwrap(data, rxBuffer)
                    runDelegatedTasks(result, sslEngine)

                    when (result.status) {
                        SSLEngineResult.Status.OK -> {
                            // Keep any unconsumed bytes (e.g. next record already in the packet).
                            pendingTlsData = if (data.hasRemaining())
                                ByteArray(data.remaining()).also { data.get(it) }
                            else ByteArray(0)
                        }
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            // Partial TLS record — read the next USB packet and append it.
                            // The outer loop re-enters NEED_UNWRAP and retries unwrap with
                            // the now-larger buffer (pendingTlsData non-empty → no extra USB read).
                            val size = connection.recvBlocking(buffer, buffer.size, 2000, false)
                            if (size <= 6) {
                                AppLog.e("SSL Handshake: Receive failed during accumulation ($size)")
                                return false
                            }
                            pendingTlsData += buffer.copyOfRange(6, size)
                            AppLog.d("SSL Handshake: partial TLS record, ${pendingTlsData.size} B buffered")
                        }
                        else -> {
                            AppLog.e("SSL Handshake: unwrap failed with status ${result.status}")
                            return false
                        }
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val handshakeData = handshakeRead()
                    val bio = Messages.createRawMessage(0, 3, 3, handshakeData)
                    if (connection.sendBlocking(bio, bio.size, 2000) < 0) {
                        AppLog.e("SSL Handshake: Send failed")
                        return false
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    runDelegatedTasks()
                }

                else -> {
                    AppLog.e("SSL Handshake: Unexpected status ${getHandshakeStatus()}")
                    return false
                }
            }
        }
        // Log the session ID so we can verify resumption in logcat: two consecutive connects
        // with the same non-empty base-64 value means the abbreviated handshake was used.
        val sessionId = sslEngine.session?.id
        if (sessionId != null && sessionId.isNotEmpty()) {
            AppLog.i("SSL handshake complete. Session id: ${android.util.Base64.encodeToString(sessionId, android.util.Base64.NO_WRAP)}")
        } else {
            AppLog.i("SSL handshake complete. No session id (full handshake).")
        }
        return true
    }

    private fun prepare(): Int {
        // Use a consistent (host, port) key so JSSE's ClientSessionContext can find and reuse
        // the session from the previous connection.  The values are arbitrary — they are never
        // used for DNS resolution; they just serve as the cache lookup key.
        sslEngine = sslContext.createSSLEngine("android-auto", 5277).apply {
            useClientMode = true
            session.also {
                val appBufferMax = it.applicationBufferSize
                val netBufferMax = it.packetBufferSize

                txBuffer = ByteBuffer.allocateDirect(netBufferMax)
                rxBuffer = ByteBuffer.allocateDirect(Messages.DEF_BUFFER_LENGTH.coerceAtLeast(appBufferMax + 50))
            }
        }
        sslEngine.beginHandshake()
        return 0
    }

    override fun postHandshakeReset() {
        // Clear buffers. In this implementation, the buffers are re-created for each wrap/unwrap
        // operation (implicitly by ByteBuffer.wrap), but clearing them ensures no stale data.
        txBuffer.clear()
        rxBuffer.clear()
    }

    private fun getHandshakeStatus(): SSLEngineResult.HandshakeStatus {
        return sslEngine.handshakeStatus
    }

    private fun runDelegatedTasks() {
        if (sslEngine.handshakeStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
            var runnable: Runnable? = sslEngine.delegatedTask
            while (runnable != null) {
                runnable.run()
                runnable = sslEngine.delegatedTask
            }
            val hsStatus = sslEngine.handshakeStatus
            if (hsStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
                throw Exception("handshake shouldn't need additional tasks")
            }
        }
    }

    private fun handshakeRead(): ByteArray {
        txBuffer.clear()
        val result = sslEngine.wrap(emptyArray(), txBuffer)
        runDelegatedTasks(result, sslEngine)
        val resultBuffer = ByteArray(result.bytesProduced())
        txBuffer.flip()
        txBuffer.get(resultBuffer)
        return resultBuffer
    }

    private fun handshakeWrite(start: Int, length: Int, buffer: ByteArray): Int {
        rxBuffer.clear()
        val receivedHandshakeData = ByteArray(length)
        System.arraycopy(buffer, start, receivedHandshakeData, 0, length)

        val data = ByteBuffer.wrap(receivedHandshakeData)
        while (data.hasRemaining()) {
            val result = sslEngine.unwrap(data, rxBuffer)
            runDelegatedTasks(result, sslEngine)
            // Break on any non-OK status (especially BUFFER_UNDERFLOW on a partial TLS record)
            // to prevent an infinite loop. performHandshake() no longer calls this method for
            // NEED_UNWRAP — it handles fragmented records directly via pendingTlsData.
            if (result.status != SSLEngineResult.Status.OK) break
        }
        return receivedHandshakeData.size
    }

    override fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? {
        synchronized(this) {
            if (!::sslEngine.isInitialized || !::rxBuffer.isInitialized) {
                AppLog.w("SSL Decrypt: Not initialized yet")
                return null
            }
            try {
                rxBuffer.clear()
                val encrypted = ByteBuffer.wrap(buffer, start, length)
                val result = sslEngine.unwrap(encrypted, rxBuffer)
                runDelegatedTasks(result, sslEngine)
                
                if (AppLog.LOG_VERBOSE || result.bytesProduced() == 0) {
                    AppLog.d("SSL Decrypt Status: ${result.status}, Produced: ${result.bytesProduced()}, Consumed: ${result.bytesConsumed()}")
                }

                val resultBuffer = ByteArray(result.bytesProduced())
                rxBuffer.flip()
                rxBuffer.get(resultBuffer)
                return ByteArrayWithLimit(resultBuffer, resultBuffer.size)
            } catch (e: Exception) {
                AppLog.e("SSL Decrypt failed", e)
                return null
            }
        }
    }

    override fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? {
        synchronized(this) {
            if (!::sslEngine.isInitialized || !::txBuffer.isInitialized) {
                AppLog.w("SSL Encrypt: Not initialized yet")
                return null
            }
            try {
                txBuffer.clear()
                val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
                val result = sslEngine.wrap(byteBuffer, txBuffer)
                runDelegatedTasks(result, sslEngine)
                val resultBuffer = ByteArray(result.bytesProduced() + offset)
                txBuffer.flip()
                txBuffer.get(resultBuffer, offset, result.bytesProduced())
                return ByteArrayWithLimit(resultBuffer, resultBuffer.size)
            } catch (e: Exception) {
                AppLog.e("SSL Encrypt failed", e)
                return null
            }
        }
    }

    private fun runDelegatedTasks(result: SSLEngineResult, engine: SSLEngine) {
        if (result.handshakeStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
            var runnable: Runnable? = engine.delegatedTask
            while (runnable != null) {
                runnable.run()
                runnable = engine.delegatedTask
            }
            val hsStatus = engine.handshakeStatus
            if (hsStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
                throw Exception("handshake shouldn't need additional tasks")
            }
        }
    }

    companion object {
        // Maximum wall-clock time for the entire SSL handshake loop. Caps worst-case stall at
        // 15 s regardless of how many round-trips remain when the phone stops responding.
        private const val SSL_HANDSHAKE_TIMEOUT_MS = 15_000L

        private fun createSslContext(keyManager: SingleKeyKeyManager): SSLContext {
            val providerName = ConscryptInitializer.getProviderName()

            val sslContext = if (providerName != null) {
                try {
                    AppLog.d("Creating SSLContext with Conscrypt provider")
                    SSLContext.getInstance("TLS", providerName)
                } catch (e: Exception) {
                    AppLog.w("Failed to create SSLContext with Conscrypt, using default", e)
                    SSLContext.getInstance("TLS")
                }
            } else {
                AppLog.d("Creating SSLContext with default provider")
                SSLContext.getInstance("TLS")
            }

            return sslContext.apply {
                init(arrayOf(keyManager), arrayOf(NoCheckTrustManager()), null)
                // Keep the default session cache (size 10, timeout 86400 s) so that a
                // reconnect within the same app session can use an abbreviated handshake.
            }
        }
    }
}
