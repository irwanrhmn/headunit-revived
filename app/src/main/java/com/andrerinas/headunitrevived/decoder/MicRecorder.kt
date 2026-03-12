package com.andrerinas.headunitrevived.decoder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.PermissionChecker
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

class MicRecorder(private val micSampleRate: Int, private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private val settings = Settings(context)

    private val micBufferSize: Int
    private var micAudioBuf: ByteArray

    // Indicates whether mic recording is available on this device
    val isAvailable: Boolean

    init {
        val minSize = AudioRecord.getMinBufferSize(micSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minSize <= 0) {
            // Device doesn't support the requested audio config (common on API 16)
            AppLog.w("MicRecorder: getMinBufferSize returned $minSize, mic recording unavailable")
            micBufferSize = 0
            micAudioBuf = ByteArray(0)
            isAvailable = false
        } else {
            micBufferSize = minSize
            micAudioBuf = ByteArray(minSize)
            isAvailable = true
        }
    }

    private var threadMicAudioActive = false
    private var threadMicAudio: Thread? = null
    var listener: Listener? = null

    // Tracks whether this instance started Bluetooth SCO so we can clean it up
    private var bluetoothScoStarted = false

    companion object {
        // Sentinel value stored in settings to indicate Bluetooth SCO mode
        const val SOURCE_BLUETOOTH_SCO = 100
    }

    interface Listener {
        fun onMicDataAvailable(mic_buf: ByteArray, mic_audio_len: Int)
    }

    fun stop() {
        AppLog.i("threadMicAudio: $threadMicAudio  threadMicAudioActive: $threadMicAudioActive")
        if (threadMicAudioActive) {
            threadMicAudioActive = false
            if (threadMicAudio != null) {
                threadMicAudio!!.interrupt()
            }
        }

        if (audioRecord != null) {
            audioRecord!!.stop()
            audioRecord!!.release()                                     // Release AudioTrack resources
            audioRecord = null
        }

        if (bluetoothScoStarted) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            bluetoothScoStarted = false
            AppLog.i("MicRecorder: Bluetooth SCO stopped")
        }
    }

    private fun micAudioRead(aud_buf: ByteArray, max_len: Int): Int {
        var len = 0
        val currentListener = listener
        if (audioRecord == null || currentListener == null) {
            return len
        }
        len = audioRecord!!.read(aud_buf, 0, max_len)
        if (len <= 0) {
            // If no audio data...
            if (len == AudioRecord.ERROR_INVALID_OPERATION)
            // -3
                AppLog.e("get expected interruption error due to shutdown: $len")
            return len
        }

        currentListener.onMicDataAvailable(aud_buf, len)
        return len
    }

    fun start(): Int {
        if (!isAvailable) {
            AppLog.w("MicRecorder: Cannot start, mic not available on this device")
            return -4
        }
        try {
            if (PermissionChecker.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PermissionChecker.PERMISSION_GRANTED) {
                AppLog.e("No permission")
                audioRecord = null
                return -3
            }
            val configuredSource = settings.micInputSource
            val actualSource: Int
            if (configuredSource == SOURCE_BLUETOOTH_SCO) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
                bluetoothScoStarted = true
                actualSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
                AppLog.i("MicRecorder: Bluetooth SCO started, using VOICE_COMMUNICATION source")
            } else {
                actualSource = configuredSource
            }
            audioRecord = AudioRecord(actualSource, micSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, micBufferSize)
            audioRecord!!.startRecording()
            // Start input

            threadMicAudio = Thread(Runnable {
                while (threadMicAudioActive) {
                    micAudioRead(micAudioBuf, micBufferSize)
                }
            }, "mic_audio")

            threadMicAudioActive = true
            threadMicAudio!!.start()
            return 0
        } catch (e: Exception) {
            AppLog.e(e)
            audioRecord = null
            return -2
        }

    }

}
