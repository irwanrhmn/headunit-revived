package com.andrerinas.headunitrevived.aap

import android.view.KeyEvent
import com.andrerinas.headunitrevived.aap.protocol.messages.ScrollWheelEvent // Not directly in supported list, but used in AapTransport

object KeyCode {

    val supported = listOf(
        // Standard Android KeyEvents used in the convert method or common
        KeyEvent.KEYCODE_SOFT_LEFT,
        KeyEvent.KEYCODE_SOFT_RIGHT,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_SEARCH,
        KeyEvent.KEYCODE_CALL,
        KeyEvent.KEYCODE_MUSIC,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_STOP,

        // Additional keys explicitly listed by number in BuildCarConfig.java
        // Mapped to named constants where they exist in KeyEvent
        KeyEvent.KEYCODE_ENDCALL, // 6
        KeyEvent.KEYCODE_0, // 7
        KeyEvent.KEYCODE_1, // 8
        KeyEvent.KEYCODE_2, // 9
        KeyEvent.KEYCODE_3, // 10
        KeyEvent.KEYCODE_4, // 11
        KeyEvent.KEYCODE_5, // 12
        KeyEvent.KEYCODE_6, // 13
        KeyEvent.KEYCODE_7, // 14
        KeyEvent.KEYCODE_8, // 15
        KeyEvent.KEYCODE_9, // 16
        KeyEvent.KEYCODE_STAR, // 17
        KeyEvent.KEYCODE_POUND, // 18

        // Custom/Rotary codes from BuildCarConfig.java (no direct KeyEvent.KEYCODE_X mapping)
        1, // Appears to be a custom keycode
        2, // Appears to be a custom keycode
        81, // Appears to be a custom keycode or old BOOKMARK (actual BOOKMARK is 137)
        224, // KEYCODE_WAKEUP → Voice Command
        264, 265, 267, // STEM_PRIMARY, STEM_1, STEM_3 (steering wheel)
        268, 269, 270, 271, // Rotary controller
        65536, 65537, 65538 // Rotary controller
    ).distinct().sorted()

    val KeyEvent.isMediaSessionKey: Boolean
        get() = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                keyCode == KeyEvent.KEYCODE_MEDIA_STOP ||
                keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                keyCode == KeyEvent.KEYCODE_MEDIA_REWIND

    internal fun convert(keyCode: Int): Int {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_SOFT_LEFT,
            KeyEvent.KEYCODE_SOFT_RIGHT,
            KeyEvent.KEYCODE_MUSIC,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_ENDCALL,
            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_6,
            KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_9,
            KeyEvent.KEYCODE_STAR,
            KeyEvent.KEYCODE_POUND
                -> return keyCode
            KeyEvent.KEYCODE_ENTER -> return KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_HEADSETHOOK -> return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_STOP -> return KeyEvent.KEYCODE_MEDIA_PAUSE
            // Add any custom or rotary codes that should be passed through directly
            1, 2, 81, 268, 269, 270, 271, 65536, 65537, 65538 -> return keyCode
            // C3 / FlyAudio steering wheel (STEM & WAKEUP keycodes)
            264 -> return KeyEvent.KEYCODE_MEDIA_PREVIOUS   // STEM_PRIMARY
            265 -> return KeyEvent.KEYCODE_MEDIA_NEXT        // STEM_1
            267 -> return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE  // STEM_3
            224 -> return KeyEvent.KEYCODE_SEARCH            // WAKEUP → Voice
        }
        return KeyEvent.KEYCODE_UNKNOWN
    }
}

