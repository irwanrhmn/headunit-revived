package com.andrerinas.headunitrevived.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.connection.CommManager
import com.andrerinas.headunitrevived.utils.AppLog

class RemoteControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        AppLog.i("RemoteControlReceiver received: $action")

        // Broadcast for UI debugging (KeymapFragment)
        val debugIntent = Intent("com.andrerinas.headunitrevived.DEBUG_KEY").apply {
            putExtra("action", action)
            intent.extras?.let { putExtras(it) }
            setPackage(context.packageName)
        }
        context.sendBroadcast(debugIntent)

        if (Intent.ACTION_MEDIA_BUTTON == action) {
            val event = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
            
            event?.let {
                AppLog.i("ACTION_MEDIA_BUTTON: " + it.keyCode)
                App.provide(context).commManager.send(it.keyCode, it.action == KeyEvent.ACTION_DOWN)

                // Also broadcast for the UI
                val keyIntent = Intent(com.andrerinas.headunitrevived.contract.KeyIntent.action).apply {
                    putExtra(com.andrerinas.headunitrevived.contract.KeyIntent.extraEvent, it)
                    setPackage(context.packageName)
                }
                context.sendBroadcast(keyIntent)
            }
        } else {
            // Handle command-based intents (common on many Android Headunits)
            val command = intent.getStringExtra("command") ?: intent.getStringExtra("action") ?: intent.getStringExtra("action_command")

            // Broadcast command for UI debug (if not already handled by ACTION_MEDIA_BUTTON block)
            if (action != Intent.ACTION_MEDIA_BUTTON) {
                val debugIntent = Intent("com.andrerinas.headunitrevived.DEBUG_KEY").apply {
                    putExtra("action", action)
                    putExtra("command", command)
                    intent.extras?.let { putExtras(it) }
                    setPackage(context.packageName)
                }
                context.sendBroadcast(debugIntent)
            }

            val commManager = App.provide(context).commManager
            if (commManager.connectionState.value !is CommManager.ConnectionState.TransportStarted) {
                AppLog.i("RemoteControlReceiver: Transport not started, skipping command execution")
                return
            }

            when (command) {
                "next", "skip_next" -> {
                    commManager.send(KeyEvent.KEYCODE_MEDIA_NEXT, true)
                    commManager.send(KeyEvent.KEYCODE_MEDIA_NEXT, false)
                }
                "previous", "skip_previous", "prev" -> {
                    commManager.send(KeyEvent.KEYCODE_MEDIA_PREVIOUS, true)
                    commManager.send(KeyEvent.KEYCODE_MEDIA_PREVIOUS, false)
                }
                "play", "start" -> {
                    commManager.send(KeyEvent.KEYCODE_MEDIA_PLAY, true)
                    commManager.send(KeyEvent.KEYCODE_MEDIA_PLAY, false)
                }
                "pause", "stop" -> {
                    commManager.send(KeyEvent.KEYCODE_MEDIA_PAUSE, true)
                    commManager.send(KeyEvent.KEYCODE_MEDIA_PAUSE, false)
                }
                "togglepause", "playpause" -> {
                    commManager.send(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true)
                    commManager.send(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, false)
                }
            }
        }
    }
}
