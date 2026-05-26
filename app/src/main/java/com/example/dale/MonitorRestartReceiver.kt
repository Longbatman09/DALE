package com.example.dale

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.example.dale.utils.AccessibilityStatusNotifier
import com.example.dale.utils.SharedPreferencesManager

class MonitorRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val sharedPrefs = SharedPreferencesManager.getInstance(context)
                if (!sharedPrefs.isSetupCompleted()) return
                if (!sharedPrefs.isProtectionEnabled()) return

                // Ensure accessibility service is enabled on boot/package replacement
                val expectedService = "${context.packageName}/${DALEAppLockAccessibilityService::class.java.name}"
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""

                if (!enabledServices.split(':').any { it.equals(expectedService, ignoreCase = true) }) {
                    Log.d("MonitorRestartReceiver", "Accessibility service not enabled after boot/update")
                    AccessibilityStatusNotifier.show(context)
                } else {
                    AccessibilityStatusNotifier.cancel(context)
                }
            }
        }
    }
}


