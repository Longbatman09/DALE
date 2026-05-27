package com.example.dale

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.dale.utils.AccessibilityStatusNotifier
import com.example.dale.utils.SharedPreferencesManager
import com.example.dale.utils.MonitorStartupHelper

@SuppressLint("ForegroundServiceType", "MissingPermission")
class AppMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val checkIntervalMs = 400L
    private val accessibilityCheckIntervalMs = 2000L
    private var isPolling = false

    private val lockManager by lazy { AppLockManager.getInstance(this) }
    private val sharedPrefs by lazy { SharedPreferencesManager.getInstance(this) }

    private var lastUsageEventTimestamp = 0L
    private val usageWindowMs = 5000L
    private var isAccessibilityWarningVisible = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val destinationPackage = intent?.getStringExtra("UNLOCKED_PACKAGE") ?: return
            val sourcePackage = intent.getStringExtra("SOURCE_PACKAGE")
            val groupId = intent.getStringExtra("GROUP_ID")

            when (intent.action) {
                ACTION_APP_UNLOCKING -> {
                    lockManager.onAppUnlocking(destinationPackage, sourcePackage, groupId)
                }
                ACTION_APP_UNLOCKED -> {
                    val isCrossover = intent.getBooleanExtra("IS_CROSSOVER", false)
                    lockManager.onAppUnlocked(destinationPackage, sourcePackage, isCrossover)
                }
            }
        }
    }

    private val appCheckRunnable = object : Runnable {
        override fun run() {
            try {
                pollForegroundApp()
            } finally {
                if (isPolling) {
                    handler.postDelayed(this, checkIntervalMs)
                }
            }
        }
    }

    private val accessibilityCheckRunnable = object : Runnable {
        override fun run() {
            try {
                checkAccessibilityStatus()
            } finally {
                if (isPolling) {
                    handler.postDelayed(this, accessibilityCheckIntervalMs)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (!sharedPrefs.isProtectionEnabled()) {
            stopSelf()
            return
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_APP_UNLOCKING)
            addAction(ACTION_APP_UNLOCKED)
        }
        ContextCompat.registerReceiver(this, unlockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        lastUsageEventTimestamp = System.currentTimeMillis()
        createNotificationChannel()
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!sharedPrefs.isProtectionEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        stopPolling()
        try {
            unregisterReceiver(unlockReceiver)
        } catch (_: Exception) {}
        cancelAccessibilityWarning()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPolling() {
        if (isPolling) return
        isPolling = true
        handler.post(appCheckRunnable)
        handler.post(accessibilityCheckRunnable)
    }

    private fun stopPolling() {
        isPolling = false
        handler.removeCallbacks(appCheckRunnable)
        handler.removeCallbacks(accessibilityCheckRunnable)
    }

    private fun checkAccessibilityStatus() {
        if (!sharedPrefs.isProtectionEnabled()) {
            cancelAccessibilityWarning()
            return
        }

        val isEnabled = MonitorStartupHelper.isAccessibilityServiceEnabled(this)
        if (!isEnabled && !isAccessibilityWarningVisible) {
            showAccessibilityWarning()
        } else if (isEnabled && isAccessibilityWarningVisible) {
            cancelAccessibilityWarning()
        }
    }

    private fun showAccessibilityWarning() {
        AccessibilityStatusNotifier.show(this)
        isAccessibilityWarningVisible = true
    }

    private fun cancelAccessibilityWarning() {
        AccessibilityStatusNotifier.cancel(this)
        isAccessibilityWarningVisible = false
    }

    private fun pollForegroundApp() {
        if (!sharedPrefs.isProtectionEnabled()) {
            stopSelf()
            return
        }

        val protectedApps = sharedPrefs.getAllLockedApps()
        if (protectedApps.isEmpty()) {
            lockManager.processNoProtectedForeground()
            return
        }

        val activePackage = detectActiveProtectedPackage(protectedApps)
        if (activePackage == null) {
            lockManager.processNoProtectedForeground()
        } else {
            lockManager.processForegroundApp(activePackage)
        }
    }

    private fun detectActiveProtectedPackage(protectedPackages: Set<String>): String? {
        detectFromUsageEvents(protectedPackages)?.let { return it }

        val am = getSystemService(ActivityManager::class.java) ?: return null
        return detectFromRunningProcesses(am, protectedPackages)
    }

    @SuppressLint("MissingPermission")
    private fun detectFromUsageEvents(protectedPackages: Set<String>): String? {
        return null
    }

    private fun detectFromRunningProcesses(
        am: ActivityManager,
        protectedPackages: Set<String>
    ): String? {
        val running = am.runningAppProcesses ?: return null
        var bestPkg: String? = null
        var bestImportance = Int.MAX_VALUE

        for (proc in running) {
            val pkg = proc.processName?.substringBefore(":") ?: continue
            if (pkg == packageName) continue
            
            if (protectedPackages.contains(pkg)) {
                if (proc.importance < bestImportance) {
                    bestImportance = proc.importance
                    bestPkg = pkg
                }
            }
            
            proc.pkgList?.forEach { p ->
                if (p != packageName && protectedPackages.contains(p)) {
                    if (proc.importance < bestImportance) {
                        bestImportance = proc.importance
                        bestPkg = p
                    }
                }
            }
        }

        return if (bestImportance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
            bestPkg
        } else {
            null
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App Monitor Service",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Monitors apps for lock protection"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DALE Protection Active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val ACTION_APP_UNLOCKING = "com.example.dale.APP_UNLOCKING"
        const val ACTION_APP_UNLOCKED = "com.example.dale.APP_UNLOCKED"
        const val ACTION_UNINSTALL_AUTH_GRANTED = "com.example.dale.UNINSTALL_AUTH_GRANTED"
        private const val CHANNEL_ID = "AppMonitorServiceChannel"
        private const val WARNING_NOTIFICATION_ID = 999
    }
}
