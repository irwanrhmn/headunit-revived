package com.andrerinas.headunitrevived.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.utils.AppLog

/**
 * A comprehensive receiver for steering wheel and hardware buttons, 
 * covering both standard Android events and proprietary Chinese headunit broadcasts.
 */
class CarKeyReceiver : BroadcastReceiver() {

    companion object {
        val ACTIONS = arrayOf(
            "android.intent.action.MEDIA_BUTTON",
            "hy.intent.action.MEDIA_BUTTON", // Huayu / Hyundai Protocol
            "com.nwd.action.ACTION_KEY_VALUE", // NWD (NewWell)
            "com.microntek.irkeyUp", // Microntek (MTCE/MTCB)
            "com.microntek.irkeyDown",
            "com.winca.service.Setting.KEY_ACTION", // Winca
            "android.intent.action.C3_HARDKEY", // FlyAudio / C3
            "IKeyClick.KEY_CLICK",
            "com.eryanet.music.prev", // Eryanet (Eonon etc.)
            "com.eryanet.music.next",
            "com.eryanet.media.playorpause",
            "com.eryanet.media.play",
            "com.eryanet.media.pause",
            "com.bz.action.phone.pickup", // BZ (Joying etc.)
            "com.bz.action.phone.hangup",
            "com.tencent.qqmusiccar.action.MEDIA_BUTTON_INNER_ONKEY",
            "cn.kuwo.kwmusicauto.action.MEDIA_BUTTON"
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        AppLog.d("CarKeyReceiver: Received action: $action")

        // Try to abort broadcast to prevent other apps (like built-in radio) from reacting
        if (isOrderedBroadcast) {
            abortBroadcast()
        }

        val commManager = App.provide(context).commManager
        if (!commManager.isConnected) return

        var keyCode = -1
        var isLongPress = false

        // 1. Standard Media Button extraction
        if (action == "android.intent.action.MEDIA_BUTTON" || action == "hy.intent.action.MEDIA_BUTTON") {
            val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            if (event != null) {
                keyCode = event.keyCode
                isLongPress = event.repeatCount > 0
                
                // If it's just a down event, wait for up or handle immediately
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        commManager.send(keyCode, true)
                    }
                    return
                } else if (event.action == KeyEvent.ACTION_UP) {
                    commManager.send(keyCode, false)
                    return
                }
            }
        }

        // 2. Proprietary extraction based on Autokit findings
        when (action) {
            "com.microntek.irkeyDown", "com.microntek.irkeyUp" -> {
                keyCode = intent.getIntExtra("keyCode", -1)
                val isDown = action.endsWith("keyDown")
                if (keyCode != -1) {
                    commManager.send(keyCode, isDown)
                }
            }
            "com.nwd.action.ACTION_KEY_VALUE" -> {
                keyCode = intent.getByteExtra("extra_key_value", 0).toInt()
                handleNwdKey(keyCode, commManager)
            "com.winca.service.Setting.KEY_ACTION" -> {
                keyCode = intent.getIntExtra("com.winca.service.Setting.KEY_ACTION_EXTRA", -1)
                if (keyCode != -1) handleGenericKey(keyCode, commManager)
            }
            "IKeyClick.KEY_CLICK" -> {
                keyCode = intent.getIntExtra("CLICK_KEY", -1)
                if (keyCode != -1) handleGenericKey(keyCode, commManager)
            }
            "com.eryanet.music.prev" -> sendFullClick(KeyEvent.KEYCODE_MEDIA_PREVIOUS, commManager)
            "com.eryanet.music.next" -> sendFullClick(KeyEvent.KEYCODE_MEDIA_NEXT, commManager)
            "com.eryanet.media.playorpause" -> sendFullClick(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, commManager)
            "com.bz.action.phone.pickup" -> sendFullClick(KeyEvent.KEYCODE_CALL, commManager)
            "com.bz.action.phone.hangup" -> sendFullClick(KeyEvent.KEYCODE_ENDCALL, commManager)
        }
    }

    private fun sendFullClick(keyCode: Int, commManager: CommManager) {
        commManager.send(keyCode, true)
        commManager.send(keyCode, false)
    }

    private fun handleNwdKey(code: Int, commManager: CommManager) {
        // Mapping based on common NWD values
        val mappedKey = when (code) {
            1 -> KeyEvent.KEYCODE_MEDIA_NEXT
            2 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            3 -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            else -> -1
        }
        if (mappedKey != -1) {
            sendFullClick(mappedKey, commManager)
        }
    }

    private fun handleGenericKey(code: Int, commManager: CommManager) {
        // Generic handler for unknown Learning-Codes
        // In the future, this should hook into the Key Learning settings
        AppLog.i("CarKeyReceiver: Handling generic/learned keycode: $code")
        commManager.send(code, true)
        commManager.send(code, false)
    }
}
