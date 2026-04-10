package com.andrerinas.headunitrevived.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.contract.KeyIntent
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

        // Broadcast for KeymapFragment debugger (raw intent data)
        context.sendBroadcast(Intent("com.andrerinas.headunitrevived.DEBUG_KEY").apply {
            setPackage(context.packageName)
            putExtra("action", action)
            intent.extras?.let { putExtras(it) }
        })

        // Try to abort broadcast to prevent other apps (like built-in radio) from reacting
        if (isOrderedBroadcast) {
            abortBroadcast()
        }

        val commManager = App.provide(context).commManager

        // 1. Standard Media Button extraction (already has KeyEvent with proper DOWN/UP)
        if (action == "android.intent.action.MEDIA_BUTTON" || action == "hy.intent.action.MEDIA_BUTTON"
            || action == "com.tencent.qqmusiccar.action.MEDIA_BUTTON_INNER_ONKEY"
            || action == "cn.kuwo.kwmusicauto.action.MEDIA_BUTTON") {
            val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            if (event != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    handleKey(context, commManager, event.keyCode, true)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    handleKey(context, commManager, event.keyCode, false)
                }
            }
            return
        }

        // 2. Proprietary extraction — extract keycode, then use unified handlers
        when (action) {
            // --- Protocols with proper DOWN/UP separation ---
            "com.microntek.irkeyDown", "com.microntek.irkeyUp" -> {
                val keyCode = intent.getIntExtra("keyCode", -1)
                if (keyCode != -1) handleKey(context, commManager, keyCode, action.endsWith("keyDown"))
            }
            "android.intent.action.C3_HARDKEY" -> {
                val keyCode = intent.getIntExtra("c3_hardkey_keycode", -1)
                val c3Action = intent.getIntExtra("c3_hardkey_action", -1)
                if (keyCode != -1 && c3Action != -1) handleKey(context, commManager, keyCode, c3Action == 1)
            }

            // --- Protocols that fire once (no DOWN/UP) → simulate full click ---
            "com.nwd.action.ACTION_KEY_VALUE" -> {
                val mapped = when (intent.getByteExtra("extra_key_value", 0).toInt()) {
                    1 -> KeyEvent.KEYCODE_MEDIA_NEXT
                    2 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    3 -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    else -> -1
                }
                if (mapped > 0) handleClick(context, commManager, mapped)
            }
            "com.winca.service.Setting.KEY_ACTION" ->
                intent.getIntExtra("com.winca.service.Setting.KEY_ACTION_EXTRA", -1)
                    .takeIf { it != -1 }?.let { handleClick(context, commManager, it) }
            "IKeyClick.KEY_CLICK" ->
                intent.getIntExtra("CLICK_KEY", -1)
                    .takeIf { it != -1 }?.let { handleClick(context, commManager, it) }
            "com.eryanet.music.prev" -> handleClick(context, commManager, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "com.eryanet.music.next" -> handleClick(context, commManager, KeyEvent.KEYCODE_MEDIA_NEXT)
            "com.eryanet.media.playorpause", "com.eryanet.media.play", "com.eryanet.media.pause" ->
                handleClick(context, commManager, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "com.bz.action.phone.pickup" -> handleClick(context, commManager, KeyEvent.KEYCODE_CALL)
            "com.bz.action.phone.hangup" -> handleClick(context, commManager, KeyEvent.KEYCODE_ENDCALL)
        }
    }

    /** Single key press or release — broadcasts for learning AND sends to AA. */
    private fun handleKey(context: Context, commManager: CommManager, keyCode: Int, isDown: Boolean) {
        context.sendBroadcast(Intent(KeyIntent.action).apply {
            setPackage(context.packageName)
            putExtra(KeyIntent.extraEvent, KeyEvent(
                if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode
            ))
        })
        if (commManager.isConnected) commManager.send(keyCode, isDown)
    }

    /** Full click (DOWN + UP) — broadcasts both events for learning AND sends to AA. */
    private fun handleClick(context: Context, commManager: CommManager, keyCode: Int) {
        handleKey(context, commManager, keyCode, true)
        handleKey(context, commManager, keyCode, false)
    }
}


