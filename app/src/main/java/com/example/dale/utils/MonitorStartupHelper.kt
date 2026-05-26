package com.example.dale.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.content.pm.PackageManager
import androidx.core.net.toUri

object MonitorStartupHelper {
    private const val TAG = "MonitorStartupHelper"

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedService = "${context.packageName}/${com.example.dale.DALEAppLockAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices
            .split(':')
            .any { it.equals(expectedService, ignoreCase = true) }
    }

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun openBatteryOptimizationSettings(context: Context) {
        val batterySettingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        startFirstResolvableActivity(context, listOf(batterySettingsIntent, appDetailsIntent))
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to open accessibility settings", t)
        }
    }


    private fun startFirstResolvableActivity(context: Context, intents: List<Intent>) {
        for (intent in intents) {
            val canResolve = intent.resolveActivity(context.packageManager) != null
            if (!canResolve) continue

            try {
                context.startActivity(intent)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to launch settings intent: ${intent.action}", t)
            }
        }
    }
}
