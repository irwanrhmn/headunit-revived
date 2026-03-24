package com.andrerinas.headunitrevived.decoder

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.pow

interface VideoDimensionsListener {
    fun onVideoDimensionsChanged(width: Int, height: Int)
}

class VideoDecoder(private val settings: Settings) {
    companion object {
        private const val TIMEOUT_US = 10000L

        fun isHevcSupported(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            return codecList.codecInfos.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals("video/hevc", true) } }
        }
    }

    private var codec: MediaCodec? = null
    private var codecBufferInfo: MediaCodec.BufferInfo? = null
    private var mSurface: Surface? = null
    private var outputThread: Thread? = null
    @Volatile private var running = false
    private var startTime = 0L
    
    private var mWidth = 0
    private var mHeight = 0
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var codecConfigured = false
    private var currentCodecType = CodecType.H264

    // Buffers cache for API < 21
    private var inputBuffers: Array<ByteBuffer>? = null
    private var legacyFrameBuffer: ByteArray? = null

    var dimensionsListener: VideoDimensionsListener? = null
    var onFpsChanged: ((Int) -> Unit)? = null
    private var frameCount = 0
    private var lastFpsLogTime = 0L
    @Volatile var onFirstFrameListener: (() -> Unit)? = null
    @Volatile var lastFrameRenderedMs: Long = 0L

    val videoWidth: Int get() = mWidth
    val videoHeight: Int get() = mHeight

    enum class CodecType(val mimeType: String, val displayName: String) {
        H264("video/avc", "H.264/AVC"),
        H265("video/hevc", "H.265/HEVC")
    }

    private fun handleOutputFormatChange(format: MediaFormat) {
        AppLog.i("Output Format Changed: $format")
        val newWidth = try { format.getInteger(MediaFormat.KEY_WIDTH) } catch (e: Exception) { mWidth }
        val newHeight = try { format.getInteger(MediaFormat.KEY_HEIGHT) } catch (e: Exception) { mHeight }
        if (mWidth != newWidth || mHeight != newHeight) {
            AppLog.i("Video dimensions changed via format: ${newWidth}x$newHeight")
            mWidth = newWidth
            mHeight = newHeight
            dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
        }
        try {
            codec?.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        } catch (e: Exception) {}
    }

    fun setSurface(surface: Surface?) {
        synchronized(this) {
            if (mSurface === surface) return
            
            AppLog.i("New surface set: $surface")
            if (codec != null) {
                stop("New surface")
            }
            mSurface = surface
        }
    }

    fun stop(reason: String = "unknown") {
        synchronized(this) {
            running = false
            try {
                outputThread?.interrupt()
                outputThread?.join(500)
            } catch (e: Exception) {}
            outputThread = null
            
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {
                AppLog.e("Error releasing decoder", e)
            }
            codec = null
            inputBuffers = null
            legacyFrameBuffer = null
            codecBufferInfo = null
            codecConfigured = false
            onFirstFrameListener = null
            lastFrameRenderedMs = 0L
            AppLog.i("Decoder stopped: $reason")
        }
    }

    fun decode(buffer: ByteArray, offset: Int, size: Int, forceSoftware: Boolean, codecName: String) {
        synchronized(this) {
            val frameData = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                if (legacyFrameBuffer == null || legacyFrameBuffer!!.size < size) {
                    legacyFrameBuffer = ByteArray(size + 1024)
                }
                System.arraycopy(buffer, offset, legacyFrameBuffer!!, 0, size)
                legacyFrameBuffer!!
            } else {
                if (offset == 0 && size == buffer.size) buffer else buffer.copyOfRange(offset, offset + size)
            }
            
            if (codec == null) {
                val detectedType = detectCodecType(frameData, 0, size)
                val typeToUse = detectedType ?: if (codecName.contains("265")) CodecType.H265 else CodecType.H264
                currentCodecType = typeToUse

                if (!codecConfigured) {
                    scanAndApplyConfig(frameData, 0, size, typeToUse)
                    
                    if (mWidth == 0) {
                         val negotiatedW = HeadUnitScreenConfig.getNegotiatedWidth()
                         val negotiatedH = HeadUnitScreenConfig.getNegotiatedHeight()
                         if (negotiatedW > 0 && negotiatedH > 0) {
                             AppLog.i("Fallback to negotiated dimensions: ${negotiatedW}x${negotiatedH}")
                             mWidth = negotiatedW
                             mHeight = negotiatedH
                             dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
                         }
                    }
                }

                if (mSurface == null || !mSurface!!.isValid) return
                if (mWidth == 0 || mHeight == 0) return 
                
                start(typeToUse.mimeType, settings.forceSoftwareDecoding || forceSoftware, mWidth, mHeight)
            }

            if (codec == null) return

            val buf = ByteBuffer.wrap(frameData, 0, size)
            while (buf.hasRemaining()) {
                if (!feedInputBuffer(buf)) {
                    return
                }
            }
        }
    }

    private fun detectCodecType(buffer: ByteArray, offset: Int, size: Int): CodecType? {
        if (size < 5) return null
        val length = offset + (size.coerceAtMost(100)) - 5
        for (i in offset until length) {
            if (buffer[i].toInt() == 0 && buffer[i+1].toInt() == 0) {
                if ((buffer[i+2].toInt() == 0 && buffer[i+3].toInt() == 1) || (buffer[i+2].toInt() == 1)) {
                    val headerPos = if (buffer[i+2].toInt() == 1) i + 3 else i + 4
                    val b = buffer[headerPos].toInt()
                    // H.265: NAL type is in bits 1-6
                    val hevcType = (b and 0x7E) shr 1
                    if (hevcType in 32..34) return CodecType.H265
                    // H.264: NAL type is in bits 0-4
                    val avcType = b and 0x1F
                    if (avcType == 7 || avcType == 8) return CodecType.H264
                }
            }
        }
        return null
    }

    /**
     * Iterates through all NAL units in the buffer and provides them to the callback.
     * Automatically fixes 3-byte start codes to 4-byte start codes for MediaCodec compatibility.
     */
    private fun forEachNalUnit(buffer: ByteArray, offset: Int, size: Int, callback: (ByteArray, Int) -> Unit) {
        var currentPos = offset
        val limit = offset + size
        
        while (currentPos < limit - 3) {
            var nalStart = -1
            var startCodeLen = 0
            
            // Find next start code
            for (i in currentPos until limit - 3) {
                if (buffer[i].toInt() == 0 && buffer[i+1].toInt() == 0) {
                    if (buffer[i+2].toInt() == 0 && buffer[i+3].toInt() == 1) {
                        nalStart = i; startCodeLen = 4; break
                    } else if (buffer[i+2].toInt() == 1) {
                        nalStart = i; startCodeLen = 3; break
                    }
                }
            }
            
            if (nalStart != -1) {
                var nalEnd = limit
                // Find end of this NAL (start of next one)
                for (j in (nalStart + startCodeLen) until limit - 3) {
                    if (buffer[j].toInt() == 0 && buffer[j+1].toInt() == 0 && 
                        (buffer[j+2].toInt() == 1 || (buffer[j+2].toInt() == 0 && buffer[j+3].toInt() == 1))) {
                        nalEnd = j; break
                    }
                }
                
                val rawNal = buffer.copyOfRange(nalStart, nalEnd)
                val fixedNal = if (startCodeLen == 3) {
                    ByteArray(rawNal.size + 1).apply {
                        this[0] = 0; System.arraycopy(rawNal, 0, this, 1, rawNal.size)
                    }
                } else rawNal
                
                callback(fixedNal, if (startCodeLen == 3) 4 else 4)
                currentPos = nalEnd
            } else break
        }
    }

    private fun scanAndApplyConfig(buffer: ByteArray, offset: Int, size: Int, type: CodecType) {
        forEachNalUnit(buffer, offset, size) { nalData, headerLen ->
            val nalFirstByte = nalData[headerLen].toInt()
            if (type == CodecType.H264) {
                val nalType = nalFirstByte and 0x1F
                if (nalType == 7) { // SPS
                    sps = nalData
                    codecConfigured = true
                    try {
                        SpsParser.parse(sps!!)?.let {
                            if (mWidth != it.width || mHeight != it.height) {
                                AppLog.i("H.264 SPS parsed: ${it.width}x${it.height}")
                                mWidth = it.width; mHeight = it.height
                                dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
                            }
                        }
                    } catch (e: Exception) { AppLog.e("Failed to parse SPS data", e) }
                } else if (nalType == 8) pps = nalData // PPS
            } else {
                val nalType = (nalFirstByte and 0x7E) shr 1
                if (nalType == 32) { vps = nalData; codecConfigured = true }
                else if (nalType == 33) sps = nalData
                else if (nalType == 34) pps = nalData
            }
        }
    }

    private fun start(mimeType: String, forceSoftware: Boolean, width: Int, height: Int) {
        try {
            startTime = System.nanoTime()
            val bestCodec = findBestCodec(mimeType, !forceSoftware)
                ?: throw IllegalStateException("No decoder available for $mimeType")

            codec = MediaCodec.createByCodecName(bestCodec)
            codecBufferInfo = MediaCodec.BufferInfo()

            val format = MediaFormat.createVideoFormat(mimeType, width, height)
            
            if (mimeType == CodecType.H265.mimeType) {
                val combined = (vps ?: byteArrayOf()) + (sps ?: byteArrayOf()) + (pps ?: byteArrayOf())
                if (combined.isNotEmpty()) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(combined))
                }
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
            } else {
                if (sps != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(sps!!))
                if (pps != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(pps!!))
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            }

            if (!mSurface!!.isValid) throw IllegalStateException("Surface not valid")

            AppLog.i("Configuring decoder: $bestCodec for ${width}x${height}")
            codec?.configure(format, mSurface, null, 0)
            try { codec?.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT) } catch (e: Exception) {}
            codec?.start()
            
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                @Suppress("DEPRECATION") inputBuffers = codec?.inputBuffers
            }

            running = true
            outputThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                com.andrerinas.headunitrevived.utils.LegacyOptimizer.setHighPriority()
                outputThreadLoop()
            }.apply { name = "VideoDecoder-Output"; start() }
            
            AppLog.i("Codec initialized: $bestCodec")
        } catch (e: Exception) {
            AppLog.e("Failed to start decoder", e)
            codec = null; running = false
        }
    }

    private fun feedInputBuffer(buffer: ByteBuffer): Boolean {
        val currentCodec = codec ?: return false
        try {
            var inputIndex = -1
            var attempts = 0
            while (attempts < 30) {
                inputIndex = currentCodec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) break
                attempts++
            }

            if (inputIndex < 0) {
                AppLog.e("Input buffer feed failed (full)")
                return false
            }

            val inputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                currentCodec.getInputBuffer(inputIndex)
            } else {
                @Suppress("DEPRECATION") inputBuffers?.get(inputIndex)
            }

            if (inputBuffer == null) return false
            inputBuffer.clear()
            
            val capacity = inputBuffer.capacity()
            if (buffer.remaining() <= capacity) {
                inputBuffer.put(buffer)
            } else {
                AppLog.w("Frame too large: ${buffer.remaining()} > $capacity. Truncating!")
                val limit = buffer.limit()
                buffer.limit(buffer.position() + capacity)
                inputBuffer.put(buffer)
                buffer.limit(limit)
            }
            
            inputBuffer.flip()
            val pts = (System.nanoTime() - startTime) / 1000
            currentCodec.queueInputBuffer(inputIndex, 0, inputBuffer.limit(), pts, 0)
            return true
        } catch (e: Exception) {
            AppLog.e("Error feeding input buffer", e)
            return false
        }
    }

    private fun outputThreadLoop() {
        AppLog.i("Output thread started")
        while (running) {
            val currentCodec = codec
            val bufferInfo = codecBufferInfo
            if (currentCodec == null || bufferInfo == null) {
                try { Thread.sleep(10) } catch (e: InterruptedException) { break }
                continue
            }

            try {
                val outputIndex = currentCodec.dequeueOutputBuffer(bufferInfo, 10000L)
                if (outputIndex >= 0) {
                    currentCodec.releaseOutputBuffer(outputIndex, true)
                    lastFrameRenderedMs = SystemClock.elapsedRealtime()
                    onFirstFrameListener?.let { it(); onFirstFrameListener = null }

                    frameCount++
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastFpsLogTime
                    if (elapsed >= 1000) {
                        if (lastFpsLogTime != 0L) {
                            val fps = (frameCount * 1000 / elapsed).toInt()
                            onFpsChanged?.invoke(fps)
                        }
                        frameCount = 0
                        lastFpsLogTime = now
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    handleOutputFormatChange(currentCodec.outputFormat)
                }
            } catch (e: Exception) {
                if (running) AppLog.w("Codec exception in output thread")
            }
        }
        AppLog.i("Output thread stopped")
    }

    private fun findBestCodec(mimeType: String, preferHardware: Boolean): String? {
        val codecInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
        } else {
            @Suppress("DEPRECATION")
            val count = MediaCodecList.getCodecCount()
            (0 until count).map { MediaCodecList.getCodecInfoAt(it) }
        }

        val infos = codecInfos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mimeType, true) } }
        val hw = infos.find { isHardwareAccelerated(it.name) }
        val sw = infos.find { !isHardwareAccelerated(it.name) }
        return if (preferHardware && hw != null) hw.name else sw?.name ?: hw?.name
    }

    private fun isHardwareAccelerated(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return !(lower.startsWith("omx.google.") || lower.startsWith("c2.android.") || lower.contains(".sw.") || lower.contains("software"))
    }
}

private class BitReader(private val buffer: ByteArray) {
    private var bitPosition = 0
    fun readBit(): Int = (buffer[bitPosition / 8].toInt() shr (7 - (bitPosition++ % 8))) and 1
    fun readBits(count: Int): Int {
        var res = 0; repeat(count) { res = (res shl 1) or readBit() }; return res
    }
    fun readUE(): Int {
        var zeros = 0; while (readBit() == 0) zeros++
        return if (zeros == 0) 0 else (2.0.pow(zeros.toDouble()) - 1 + readBits(zeros)).toInt()
    }
}

data class SpsData(val width: Int, val height: Int)

private object SpsParser {
    fun parse(sps: ByteArray): SpsData? {
        try {
            val offset = if (sps[2].toInt() == 1) 3 else 4
            val reader = BitReader(sps.copyOfRange(offset, sps.size))
            reader.readBits(8)
            val profileIdc = reader.readBits(8)
            reader.readBits(16)
            reader.readUE()
            if (profileIdc in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128)) {
                val chroma = reader.readUE()
                if (chroma == 3) reader.readBit()
                reader.readUE(); reader.readUE(); reader.readBit()
                if (reader.readBit() == 1) {
                    repeat(if (chroma != 3) 8 else 12) {
                        if (reader.readBit() == 1) {
                            var last = 8; var next = 8
                            repeat(if (it < 6) 16 else 64) {
                                if (next != 0) next = (last + reader.readUE() + 256) % 256
                                if (next != 0) last = next
                            }
                        }
                    }
                }
            }
            reader.readUE()
            if (reader.readUE() == 0) reader.readUE()
            reader.readUE(); reader.readBit()
            val w = (reader.readUE() + 1) * 16
            val hMap = reader.readUE()
            val mbs = reader.readBit()
            var h = (2 - mbs) * (hMap + 1) * 16
            if (mbs == 0) reader.readBit()
            reader.readBit()
            if (reader.readBit() == 1) {
                val l = reader.readUE(); val r = reader.readUE()
                val t = reader.readUE(); val b = reader.readUE()
                return SpsData(w - (l + r) * 2, h - (t + b) * 2)
            }
            return SpsData(w, h)
        } catch (e: Exception) { return null }
    }
}
