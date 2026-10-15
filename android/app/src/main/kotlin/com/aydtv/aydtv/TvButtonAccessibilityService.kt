package com.aydtv.aydtv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class TvButtonAccessibilityService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        // Tüm Android TV kumandalarındaki TV & Uydu Tuşları:
        // KEYCODE_TV (170)
        // KEYCODE_TV_INPUT (178)
        // KEYCODE_TV_TERRESTRIAL_ANALOG (174)
        // KEYCODE_TV_TERRESTRIAL_DIGITAL (175)
        // KEYCODE_TV_SATELLITE (176)
        // KEYCODE_TV_SATELLITE_BS (177)
        // KEYCODE_TV_SATELLITE_CS (178)
        val isTvKey = when (keyCode) {
            KeyEvent.KEYCODE_TV,
            KeyEvent.KEYCODE_TV_INPUT,
            KeyEvent.KEYCODE_TV_TERRESTRIAL_ANALOG,
            KeyEvent.KEYCODE_TV_TERRESTRIAL_DIGITAL,
            KeyEvent.KEYCODE_TV_SATELLITE,
            KeyEvent.KEYCODE_TV_SATELLITE_BS,
            KeyEvent.KEYCODE_TV_SATELLITE_CS,
            170, 174, 175, 176, 177, 178 -> true
            else -> false
        }

        if (isTvKey) {

            if (action == KeyEvent.ACTION_UP) {
                // Yerel TV uygulamasının açılmasını tamamen engelle ve doğrudan AydTV'yi öne fırlat!
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                launchIntent?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                startActivity(launchIntent)
            }
            // return true diyerek bu donanım sinyalini tüketiyoruz; Mediatek veya TV'nin dahili tuner'ına asla gitmez!
            return true
        }

        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
