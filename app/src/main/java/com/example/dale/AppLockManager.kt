package com.example.dale

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.example.dale.utils.SharedPreferencesManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Singleton manager to coordinate app locking logic between Accessibility Service and Polling Service.
 * Ensures consistent state and prevents redundant lock screens.
 */
class AppLockManager private constructor(private val context: Context) {

    private val sharedPrefs = SharedPreferencesManager.getInstance(context)
    private val TAG = "AppLockManager"

    // Session state (Thread-safe via synchronization where necessary)
    private val unlockedSessions = mutableSetOf<String>()
    private val unlockingApps = mutableSetOf<String>()
    private val backgroundSince = mutableMapOf<String, Long>()
    private val lockInProgress = mutableSetOf<String>()
    private val unlockTimestamps = mutableMapOf<String, Long>() // Uses elapsedRealtime
    private var lastForegroundPackage: String? = null

    // Configuration
    private val unlockGracePeriodMs = 5000L
    private val exitGracePeriodMs = 2000L

    companion object {
        @Volatile
        private var instance: AppLockManager? = null

        fun getInstance(context: Context): AppLockManager {
            return instance ?: synchronized(this) {
                instance ?: AppLockManager(context.applicationContext).also { instance = it }
            }
        }
    }

    @Synchronized
    fun onAppUnlocking(destinationPackage: String, sourcePackage: String?, groupId: String?) {
        val now = SystemClock.elapsedRealtime()
        lockInProgress.remove(destinationPackage)
        backgroundSince.remove(destinationPackage)
        unlockingApps.add(destinationPackage)
        unlockTimestamps[destinationPackage] = now

        if (!sourcePackage.isNullOrBlank() && sourcePackage != destinationPackage) {
            unlockingApps.add(sourcePackage)
            lockInProgress.remove(sourcePackage)
            backgroundSince.remove(sourcePackage)
            unlockTimestamps[sourcePackage] = now
            Log.d(TAG, "Cross-unlocking detected: $sourcePackage -> $destinationPackage")
        }
    }

    @Synchronized
    fun onAppUnlocked(destinationPackage: String, sourcePackage: String?) {
        val now = SystemClock.elapsedRealtime()
        unlockingApps.remove(destinationPackage)
        unlockedSessions.add(destinationPackage)
        backgroundSince.remove(destinationPackage)
        lockInProgress.remove(destinationPackage)
        unlockTimestamps[destinationPackage] = now

        if (!sourcePackage.isNullOrBlank() && sourcePackage != destinationPackage) {
            unlockingApps.remove(sourcePackage)
            unlockedSessions.add(sourcePackage)
            backgroundSince.remove(sourcePackage)
            lockInProgress.remove(sourcePackage)
            unlockTimestamps[sourcePackage] = now
        }
    }

    @Synchronized
    fun processForegroundApp(currentPackage: String) {
        if (!sharedPrefs.isProtectionEnabled()) return
        if (currentPackage == context.packageName) {
            lastForegroundPackage = currentPackage
            return
        }

        val now = SystemClock.elapsedRealtime()
        val previous = lastForegroundPackage
        val groups = sharedPrefs.getAllAppGroups()
        val groupId = groups.find { it.app1PackageName == currentPackage || it.app2PackageName == currentPackage }?.id

        // Handle app exit/switch
        if (previous != null && previous != currentPackage && previous != context.packageName) {
            markAppClosed(previous, now)
        }

        lastForegroundPackage = currentPackage
        cleanupExpiredStates(now)

        if (groupId == null) return

        // Check if we should lock
        if (shouldLock(currentPackage, groupId, now)) {
            triggerLock(currentPackage, groupId)
        }
    }

    @Synchronized
    fun processNoProtectedForeground() {
        val previous = lastForegroundPackage ?: return
        val now = SystemClock.elapsedRealtime()
        
        if (previous != context.packageName) {
            markAppClosed(previous, now)
        }
        lastForegroundPackage = null
    }

    private fun shouldLock(pkg: String, groupId: String, now: Long): Boolean {
        // 1. Check if it's already being locked
        if (pkg in lockInProgress) return false

        // 2. Check unlock grace period (recent manual unlock)
        val unlockTime = unlockTimestamps[pkg]
        if (unlockTime != null && (now - unlockTime) < unlockGracePeriodMs) {
            backgroundSince.remove(pkg)
            return false
        }

        // 3. Check session grace period (temporary backgrounding)
        if (pkg in unlockedSessions) {
            val leftAt = backgroundSince[pkg]
            if (leftAt == null || (now - leftAt) < exitGracePeriodMs) {
                backgroundSince.remove(pkg)
                return false
            }
            // Grace period expired
            unlockedSessions.remove(pkg)
        }

        // 4. Check persistent state in SharedPreferences
        val latestEvent = sharedPrefs.getLatestActivityEventForPackage(groupId, pkg)
        if (latestEvent == "OPENED") {
            // If the DB says it's open, trust it but re-verify session
            unlockedSessions.add(pkg)
            return false
        }

        return true
    }

    private fun triggerLock(pkg: String, groupId: String) {
        lockInProgress.add(pkg)
        Log.d(TAG, "Triggering lock for $pkg")
        val intent = Intent(context, DrawOverOtherAppsLockScreen::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra("TARGET_PACKAGE", pkg)
            putExtra("GROUP_ID", groupId)
        }
        context.startActivity(intent)
    }

    private fun markAppClosed(pkg: String, now: Long) {
        if (pkg in unlockingApps) return

        backgroundSince[pkg] = now
        lockInProgress.remove(pkg)

        val groups = sharedPrefs.getAllAppGroups()
        val group = groups.find { it.app1PackageName == pkg || it.app2PackageName == pkg }
        if (group != null) {
            val latestEvent = sharedPrefs.getLatestActivityEventForPackage(group.id, pkg)
            if (latestEvent == null) {
                Log.d(TAG, "Skipping CLOSED for $pkg because no previous activity log exists")
            } else if (latestEvent != "CLOSED") {
                saveActivityLog(group.id, pkg, "CLOSED")
                Log.d(TAG, "Logged CLOSED for $pkg")
            }
        }
    }

    private fun cleanupExpiredStates(now: Long) {
        val unlockIterator = unlockTimestamps.entries.iterator()
        while (unlockIterator.hasNext()) {
            val entry = unlockIterator.next()
            if (now - entry.value > unlockGracePeriodMs) {
                unlockingApps.remove(entry.key)
                unlockIterator.remove()
            }
        }
    }

    private fun saveActivityLog(groupId: String, packageName: String, event: String) {
        val group = sharedPrefs.getAppGroup(groupId) ?: return
        val appName = if (packageName == group.app1PackageName) group.app1Name else group.app2Name
        val timestamp = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date())

        sharedPrefs.saveActivityLog(
            groupId = groupId,
            entry = ActivityLogEntry(
                appName = appName,
                packageName = packageName,
                event = event,
                timestamp = timestamp
            )
        )
    }
}
