package com.example.dale

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.example.dale.utils.SharedPreferencesManager

class AppLockAccessibilityService : AccessibilityService() {

    private val sharedPrefs by lazy { SharedPreferencesManager.getInstance(this) }
    private val lockManager by lazy { AppLockManager.getInstance(this) }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val destinationPackage = intent?.getStringExtra("UNLOCKED_PACKAGE") ?: return
            val sourcePackage = intent.getStringExtra("SOURCE_PACKAGE")
            val groupId = intent.getStringExtra("GROUP_ID")

            when (intent.action) {
                AppMonitorService.ACTION_APP_UNLOCKING -> {
                    lockManager.onAppUnlocking(destinationPackage, sourcePackage, groupId)
                }
                AppMonitorService.ACTION_APP_UNLOCKED -> {
                    val isCrossover = intent.getBooleanExtra("IS_CROSSOVER", false)
                    lockManager.onAppUnlocked(destinationPackage, sourcePackage, isCrossover)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(AppMonitorService.ACTION_APP_UNLOCKING)
            addAction(AppMonitorService.ACTION_APP_UNLOCKED)
        }
        ContextCompat.registerReceiver(this, unlockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            packageNames = null
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        if (!sharedPrefs.isProtectionEnabled()) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName.isBlank() || packageName == this.packageName) return

        lockManager.processForegroundApp(packageName)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            unregisterReceiver(unlockReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
