package com.example.dale

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.example.dale.utils.SharedPreferencesManager
import com.example.dale.utils.AppActivityLogger
import java.util.Timer
import kotlin.concurrent.timerTask

/**
 * Tier 2: Experimental/UsageStats Backend Service
 * Reliable app detection using Usage Stats Manager with 250ms polling
 *
 * Features:
 * - 250ms polling interval (FASTEST) ⭐
 * - ACTIVITY_RESUMED event filtering
 * - Recents and system app filtering
 * - Grace period support
 * - Automatic fallback to Tier 3 if permission denied
 */
class DALEExperimentalAppLockService : Service() {
    private val TAG = "ExperimentalAppLockService"
    private val NOTIFICATION_ID = 1112
    private val CHANNEL_ID = "ExperimentalAppLockServiceChannel"
    
    private val usageStatsManager: UsageStatsManager by lazy {
        getSystemService(UsageStatsManager::class.java) ?: throw Exception("UsageStatsManager not available")
    }
    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java) ?: throw Exception("NotificationManager not available")
    }
    
    private var timer: Timer? = null
    private var previousForegroundPackage = ""
    private var keyboardPackages: List<String> = emptyList()
    
    companion object {
        @Volatile
        var isServiceRunning = false
            private set
    }
    
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.d(TAG, "Screen off detected in Usage Stats. Resetting app lock state.")
                DALEAppLockManager.isLockScreenShown.set(false)
                DALEAppLockManager.clearTemporarilyUnlockedApp()
                previousForegroundPackage = ""
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Experimental service created")
        DALEAppLockManager.isLockScreenShown.set(false)
        
        // Get keyboard packages
        try {
            keyboardPackages = getSystemService<InputMethodManager>()
                ?.enabledInputMethodList
                ?.map { it.packageName }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting keyboard packages", e)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        
        if (isServiceRunning) return START_STICKY
        isServiceRunning = true
        
        val sharedPrefs = SharedPreferencesManager.getInstance(this)
        
        // Check permissions and protection
        if (!sharedPrefs.isProtectionEnabled()) {
            Log.e(TAG, "Protection not enabled")
            stopSelf()
            return START_NOT_STICKY
        }
        
        if (!hasUsagePermission()) {
            Log.e(TAG, "PACKAGE_USAGE_STATS permission not available, falling back to Tier 3")
            isServiceRunning = false
            startFallbackService()
            stopSelf()
            return START_NOT_STICKY
        }
        
        try {
            DALEAppLockManager.resetRestartAttempts(TAG)
            
            // Register screen state receiver
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(this, screenStateReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

            startMonitoringTimer()
            startAsForegroundService()
            Log.d(TAG, "Experimental service started successfully (250ms polling)")
            return START_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service", e)
            isServiceRunning = false
            startFallbackService()
            stopSelf()
            return START_NOT_STICKY
        }
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        timer?.cancel()
        
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "Receiver not registered")
        }
        
        if (isServiceRunning) {
            Log.d(TAG, "Starting fallback service")
            startFallbackService()
        }
        
        isServiceRunning = false
        notificationManager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun startMonitoringTimer() {
        timer?.cancel()
        timer = Timer()
        timer?.schedule(timerTask {
            try {
                if (!isServiceRunning) return@timerTask
                
                val sharedPrefs = SharedPreferencesManager.getInstance(this@DALEExperimentalAppLockService)
                if (!sharedPrefs.isProtectionEnabled() || this@DALEExperimentalAppLockService.isDeviceLocked()) {
                    if (this@DALEExperimentalAppLockService.isDeviceLocked()) {
                        DALEAppLockManager.appUnlockTimes.clear()
                        previousForegroundPackage = ""
                    }
                    return@timerTask
                }
                
                val currentPackage = getCurrentForegroundAppPackage() ?: return@timerTask
                val triggeringPackage = previousForegroundPackage
                previousForegroundPackage = currentPackage
                
                // Skip exclusion apps
                if (isExclusionApp(currentPackage)) return@timerTask
                
                // Skip if triggering package is excluded
                if (triggeringPackage in sharedPrefs.getTriggerExcludedApps()) {
                    return@timerTask
                }
                
                // Only trigger on package change
                if (currentPackage == triggeringPackage) return@timerTask
                
                checkAndLockApp(currentPackage, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e(TAG, "Error in monitoring timer", e)
            }
        }, 0, 250)  // 250ms interval ⭐ FASTEST
        
        Log.d(TAG, "Monitoring timer started (250ms interval)")
    }
    
    /**
     * Get current foreground app using Usage Stats
     * Returns Pair<packageName, className>
     */
    @SuppressLint("MissingPermission")
    private fun getCurrentForegroundAppPackage(): String? {
        return try {
            val time = System.currentTimeMillis()
            val events = usageStatsManager.queryEvents(time - 1000 * 100, time)
            val event = UsageEvents.Event()
            var recentApp: String? = null

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                
                // Only look at ACTIVITY_RESUMED events
                if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                
                // Skip lock screen class
                if (event.className == "com.example.dale.DrawOverOtherAppsLockScreen") continue
                
                // Skip known recents classes
                if (event.className in DALELockConstants.KNOWN_RECENTS_CLASSES) continue
                
                // This is the latest foreground activity
                recentApp = event.packageName
            }
            
            recentApp
        } catch (e: Exception) {
            Log.e(TAG, "Error getting foreground app", e)
            null
        }
    }
    
    private fun isExclusionApp(packageName: String): Boolean {
        return packageName == this.packageName ||
                packageName in keyboardPackages ||
                packageName in DALELockConstants.EXCLUDED_APPS
    }
    
    private fun checkAndLockApp(packageName: String, currentTime: Long) {
        try {
            val sharedPrefs = SharedPreferencesManager.getInstance(this)
            
            // Check if already showing lock screen or biometric auth in progress
            if (DALEAppLockManager.isLockScreenShown.get()) {
                Log.d(TAG, "Lock screen already shown, skipping")
                return
            }
            
            // Check if app is in any protected group
            for (group in sharedPrefs.getAllAppGroups()) {
                if (packageName == group.app1PackageName || packageName == group.app2PackageName) {
                    // Check unlock grace period
                    val unlockTime = DALEAppLockManager.appUnlockTimes[packageName]
                    if (unlockTime != null) {
                        val elapsed = currentTime - unlockTime
                        if (elapsed < 5000) {  // 5 second grace period
                            Log.d(TAG, "App still in grace period, skipping lock")
                            return
                        }
                        DALEAppLockManager.appUnlockTimes.remove(packageName)
                    }
                    
                    // Check if app is temporarily unlocked
                    if (DALEAppLockManager.isAppTemporarilyUnlocked(packageName)) {
                        Log.d(TAG, "App is temporarily unlocked, skipping")
                        return
                    }
                    
                    // Show lock screen
                    showLockScreen(packageName, group.id)
                    return
                }
            }

            Log.d(TAG, "Package $packageName not in any protected group")
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkAndLockApp", e)
        }
    }
    
     private fun showLockScreen(packageName: String, groupId: String) {
         try {
             DALEAppLockManager.isLockScreenShown.set(true)
             Log.d(TAG, "Showing lock screen for: $packageName")

             // Log the lock screen trigger
             val sharedPrefs = SharedPreferencesManager.getInstance(this)
             val group = sharedPrefs.getAppGroup(groupId)
             if (group != null) {
                 val appName = if (packageName == group.app1PackageName) group.app1Name else group.app2Name
                 AppActivityLogger.logLockScreenTriggered(
                     packageName,
                     appName,
                     group.groupName,
                     "Usage Stats (Experimental)"
                 )
             }

             val intent = Intent(this, DrawOverOtherAppsLockScreen::class.java).apply {
                 addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                 addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                 addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                 putExtra("TARGET_PACKAGE", packageName)
                 putExtra("GROUP_ID", groupId)
             }
             startActivity(intent)
         } catch (e: Exception) {
             Log.e(TAG, "Error showing lock screen", e)
             DALEAppLockManager.isLockScreenShown.set(false)
         }
     }

    @SuppressLint("MissingPermission")
    private fun hasUsagePermission(): Boolean {
        return try {
            val time = System.currentTimeMillis()
            usageStatsManager.queryEvents(time - 1000, time)
            true
        } catch (_: Exception) {
            Log.d(TAG, "PACKAGE_USAGE_STATS permission not available")
            false
        }
    }
    
    private fun startFallbackService() {
        try {
            Log.d(TAG, "Starting fallback Tier 3 (Accessibility) service")
            startService(Intent(this, DALEAppLockAccessibilityService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start fallback service", e)
        }
    }
    
    private fun startAsForegroundService() {
        try {
            createNotificationChannel()
            val notification = createNotification()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }
    }
    
    private fun createNotificationChannel() {
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Experimental App Lock Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Monitors apps with 250ms polling"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating notification channel", e)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DALE App Lock")
            .setContentText("Monitoring (Fast)")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}

