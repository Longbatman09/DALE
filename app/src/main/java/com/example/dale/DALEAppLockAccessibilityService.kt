package com.example.dale

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import com.example.dale.ActivityLogEntry
import com.example.dale.utils.SharedPreferencesManager
import com.example.dale.utils.AppActivityLogger
import com.example.dale.utils.DetectionMethod
import com.example.dale.utils.DetectionMethodManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tier 3: Accessibility Service Backend
 * Universal fallback using AccessibilityService for 95%+ device coverage
 *
 * Features:
 * - Event-driven (no polling needed!)
 * - Detects recents and home screen
 * - System UI aware
 * - Works on all devices when enabled
 * - Lowest battery impact
 */
@SuppressLint("AccessibilityPolicy")
class DALEAppLockAccessibilityService : AccessibilityService() {
    private val TAG = "AccessibilityService"
    
    private var recentsOpen = false
    private var lastForegroundPackage = ""
    private var keyboardPackages: List<String> = emptyList()
    private var pendingUninstallChallenge: UninstallChallenge? = null
    private var uninstallApprovalUntilMs: Long = 0L
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        @Volatile
        var isServiceRunning = false
            private set
    }

    private data class UninstallChallenge(
        val packageName: String,
        val groupId: String,
        val appName: String
    )

     private val screenStateReceiver = object : android.content.BroadcastReceiver() {
         override fun onReceive(context: android.content.Context?, intent: Intent?) {
             try {
                 if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                     Log.d(TAG, "Screen off detected. Resetting app lock state.")
                     DALEAppLockManager.isLockScreenShown.set(false)
                     DALEAppLockManager.clearTemporarilyUnlockedApp()
                     DALEAppLockManager.appUnlockTimes.clear()

                     // ✅ Clear last opened app when screen turns off (session ends)
                     context?.let {
                         val sharedPrefs = SharedPreferencesManager.getInstance(it)
                         sharedPrefs.clearLastOpenedApp()
                         Log.d("AppDetection", " Cleared last opened app on screen off")
                     }
                 }
             } catch (e: Exception) {
                 Log.e(TAG, "Error in screenStateReceiver", e)
             }
         }
     }

     private val appUnlockReceiver = object : android.content.BroadcastReceiver() {
         override fun onReceive(context: android.content.Context?, intent: Intent?) {
             try {
                 when (intent?.action) {
                     AppMonitorService.ACTION_UNINSTALL_AUTH_GRANTED -> {
                         val packageName = intent.getStringExtra("TARGET_PACKAGE") ?: return
                         val groupId = intent.getStringExtra("GROUP_ID")
                         handleUninstallAuthGranted(packageName, groupId)
                     }
                 }
             } catch (e: Exception) {
                 Log.e(TAG, "Error handling uninstall auth broadcast", e)
             }
         }
     }

    override fun onCreate() {
         super.onCreate()
         try {
             isServiceRunning = true
             DALEAppLockManager.currentBiometricState = DALEAppLockManager.BiometricState.IDLE
             DALEAppLockManager.isLockScreenShown.set(false)

             val filter = android.content.IntentFilter().apply {
                 addAction(Intent.ACTION_SCREEN_OFF)
                 addAction(Intent.ACTION_USER_PRESENT)
             }
             registerReceiver(screenStateReceiver, filter, android.content.Context.RECEIVER_EXPORTED)

              val uninstallFilter = android.content.IntentFilter().apply {
                  addAction(AppMonitorService.ACTION_UNINSTALL_AUTH_GRANTED)
              }
              registerReceiver(appUnlockReceiver, uninstallFilter, android.content.Context.RECEIVER_NOT_EXPORTED)

             // Get keyboard packages
             try {
                 keyboardPackages = getSystemService<InputMethodManager>()
                     ?.enabledInputMethodList
                     ?.map { it.packageName }
                     ?: emptyList()
             } catch (e: Exception) {
                 Log.e(TAG, "Error getting keyboard packages", e)
             }

             Log.d(TAG, "Accessibility service created")
             Log.d("AppDetection", "✅ ACCESSIBILITY_SERVICE_CREATED - Ready to monitor app events")
             Log.d("AppDetection", " Watching for app opens/closes...")
             autoOpenTrackedAppAfterServiceCreated()
         } catch (e: Exception) {
             Log.e(TAG, "Error in onCreate", e)
             Log.e("AppDetection", "❌ ERROR_IN_ACCESSIBILITY_SERVICE_CREATION: ${e.message}")
         }
     }

    private fun autoOpenTrackedAppAfterServiceCreated() {
        try {
            val sharedPrefs = SharedPreferencesManager.getInstance(applicationContext)
            val packageToOpen = sharedPrefs.getLastOpenedAppPackage()

            // If there is no tracked app, open DALE home screen.
            if (packageToOpen.isNullOrBlank()) {
                val daleIntent = Intent(applicationContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(daleIntent)
                Log.d("AppDetection", " AUTO_OPEN_TRIGGERED_AFTER_SERVICE_CREATED: DALE_HOME")
                return
            }

            // If the tracked package is DALE, explicitly open DALE home screen.
            if (packageToOpen == packageName) {
                val daleIntent = Intent(applicationContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(daleIntent)
                Log.d("AppDetection", " AUTO_OPEN_TRIGGERED_AFTER_SERVICE_CREATED: DALE_HOME")
                return
            }

            val launchIntent = packageManager.getLaunchIntentForPackage(packageToOpen)
            if (launchIntent == null) {
                Log.d("AppDetection", "ℹ️ AUTO_OPEN_SKIPPED - No launch intent for $packageToOpen")
                return
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(launchIntent)
            Log.d("AppDetection", " AUTO_OPEN_TRIGGERED_AFTER_SERVICE_CREATED: $packageToOpen")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-open app after service creation", e)
            Log.e("AppDetection", "❌ AUTO_OPEN_ERROR_AFTER_SERVICE_CREATED: ${e.message}")
        }
    }

    override fun onServiceConnected() {
         super.onServiceConnected()
         try {
             serviceInfo = serviceInfo.apply {
                 eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_WINDOWS_CHANGED
                 feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                 packageNames = null
             }

             Log.d(TAG, "Accessibility service connected")
             Log.d("AppDetection", "✅ ACCESSIBILITY_SERVICE_CONNECTED - Now listening to app events")
             DALEAppLockManager.resetRestartAttempts(TAG)
         } catch (e: Exception) {
             Log.e(TAG, "Error in onServiceConnected", e)
             Log.e("AppDetection", "❌ ERROR_CONNECTING_ACCESSIBILITY_SERVICE: ${e.message}")
         }
     }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            handleAccessibilityEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent", e)
        }
    }
    
    private fun handleAccessibilityEvent(event: AccessibilityEvent) {
         // Early return if protection disabled
         if (!applicationContext.let { SharedPreferencesManager.getInstance(it).isProtectionEnabled() } || !isServiceRunning) {
             return
         }

         // Extract and log package name for debugging
         val packageName = event.packageName?.toString() ?: return
         val eventType = when(event.eventType) {
             AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
             AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
             AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
             else -> "OTHER (${event.eventType})"
         }
         Log.v("AppDetection", " Event received - Package: $packageName, Type: $eventType")

          if (handleUninstallDialogEvent(event, packageName)) {
              return
          }

         // Handle window state changes (recents, home screen detection)
         if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
             try {
                 handleWindowStateChanged(event)
             } catch (e: Exception) {
                 Log.e(TAG, "Error handling window state change", e)
                 return
             }
         }

         if (isRecentsEvent(event)) {
             if (!recentsOpen) {
                 Log.d(TAG, "Entering recents")
                 Log.d("AppDetection", " RECENTS_OPENED - User viewing recent apps")
             }
             recentsOpen = true
             return
         }

         // Skip processing if recents are open
         if (recentsOpen) {
             Log.d(TAG, "Recents open, ignoring event")
             return
         }

         // Skip if app is excluded or device locked
         if (!isValidPackageForLocking(packageName)) {
             Log.v("AppDetection", "⏭️  Skipping package: $packageName (excluded or invalid)")
             return
         }

         try {
             processPackageLocking(packageName)
         } catch (e: Exception) {
             Log.e(TAG, "Error processing package locking", e)
         }
     }

     private fun handleWindowStateChanged(event: AccessibilityEvent) {
         val isRecentlyOpened = isRecentlyOpened(event)
         val isHomeScreen = isHomeScreen(event)
         val isSamsungHomeScreen = isSamsungHomeScreenOpened(event)

          when {
              isSamsungHomeScreen -> {
                  Log.d(TAG, "Home launcher detected: ${event.packageName}")
                  Log.d("AppDetection", " HOME_LAUNCHER_OPENED (${event.packageName})")

                  // ✅ STEP 2 & 3: Use last opened app from storage instead of local variable
                  val sharedPrefs = SharedPreferencesManager.getInstance(applicationContext)
                  val lastOpenedPackage = sharedPrefs.getLastOpenedAppPackage()
                  val lastOpenedGroupId = sharedPrefs.getLastOpenedAppGroupId()
                  val lastOpenedGroupName = sharedPrefs.getLastOpenedAppGroupName()
                  val lastOpenedAppName = sharedPrefs.getLastOpenedAppName()

                  if (!lastOpenedPackage.isNullOrEmpty() && !lastOpenedGroupId.isNullOrEmpty()) {
                      Log.d("AppDetection", " Logging app closed: $lastOpenedPackage (group: $lastOpenedGroupName)")

                      // ✅ STEP 3: Check if last log is OPENED before logging CLOSED
                      logAppClosedIfProtected(
                          packageName = lastOpenedPackage,
                          groupId = lastOpenedGroupId,
                          groupName = lastOpenedGroupName ?: lastOpenedPackage,
                          appName = lastOpenedAppName ?: lastOpenedPackage,
                          sharedPrefs = sharedPrefs
                      )

                      // Clear after logging
                      sharedPrefs.clearLastOpenedApp()
                  }

                  // Now clear the unlocked state
                  recentsOpen = false
                  clearTemporarilyUnlockedAppIfNeeded()
                  lastForegroundPackage = ""
              }

             isRecentlyOpened -> {
                 Log.d(TAG, "Entering recents")
                 Log.d("AppDetection", " RECENTS_OPENED - User viewing recent apps")
                 recentsOpen = true
             }

              isHomeScreen -> {
                  Log.d(TAG, "On home screen")
                  Log.d("AppDetection", " HOME_SCREEN_OPENED - User on home screen")
                  recentsOpen = false
                  clearTemporarilyUnlockedAppIfNeeded()

                  // ✅ Also use last opened app tracking here
                  val sharedPrefs = SharedPreferencesManager.getInstance(applicationContext)
                  val lastOpenedPackage = sharedPrefs.getLastOpenedAppPackage()
                  val lastOpenedGroupId = sharedPrefs.getLastOpenedAppGroupId()
                  val lastOpenedGroupName = sharedPrefs.getLastOpenedAppGroupName()
                  val lastOpenedAppName = sharedPrefs.getLastOpenedAppName()

                  if (!lastOpenedPackage.isNullOrEmpty() && !lastOpenedGroupId.isNullOrEmpty()) {
                      logAppClosedIfProtected(
                          packageName = lastOpenedPackage,
                          groupId = lastOpenedGroupId,
                          groupName = lastOpenedGroupName ?: lastOpenedPackage,
                          appName = lastOpenedAppName ?: lastOpenedPackage,
                          sharedPrefs = sharedPrefs
                      )
                      sharedPrefs.clearLastOpenedApp()
                  }

                  lastForegroundPackage = ""
              }

             isAppSwitchedFromRecents(event) -> {
                 Log.d(TAG, "App switched from recents")
                 Log.d("AppDetection", " APP_SWITCHED_FROM_RECENTS - App: ${event.packageName}")
                 recentsOpen = false
                 clearTemporarilyUnlockedAppIfNeeded(event.packageName?.toString())
             }
         }
     }

     @SuppressLint("InlinedApi")
     private fun isRecentlyOpened(event: AccessibilityEvent): Boolean {
         if (isRecentsEvent(event)) {
             return true
         }
         return (event.packageName == getSystemDefaultLauncherPackageName() &&
                 event.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED) ||
                 (event.text.toString().lowercase().contains("recent"))
     }

     private fun isRecentsEvent(event: AccessibilityEvent): Boolean {
         val className = event.className?.toString().orEmpty()
         if (className in DALELockConstants.KNOWN_RECENTS_CLASSES) {
             return true
         }
         val packageName = event.packageName?.toString().orEmpty()
         val launcherPackage = getSystemDefaultLauncherPackageName()
         val isSystemUiOrLauncher = packageName.contains("systemui", ignoreCase = true) ||
             packageName.contains("launcher", ignoreCase = true) ||
             packageName == launcherPackage

         if (isSystemUiOrLauncher && (className.contains("Recents", ignoreCase = true) ||
                 className.contains("Overview", ignoreCase = true))
         ) {
             return true
         }

         if (isSystemUiOrLauncher) {
             val text = event.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
             if (text.contains("recent")) {
                 return true
             }
         }

         return false
     }

     private fun isSamsungHomeScreenOpened(event: AccessibilityEvent): Boolean {
         val packageName = event.packageName?.toString() ?: return false
         // Detect any launcher that contains com.android.launcher (Samsung, OneUI, Stock Android, etc.)
         return packageName.contains("com.android.launcher") || packageName == "com.sec.android.app.launcher"
     }

    private fun isHomeScreen(event: AccessibilityEvent): Boolean {
        return event.packageName == getSystemDefaultLauncherPackageName() &&
                (event.className?.contains("Launcher") == true ||
                        event.text.toString().lowercase().contains("home screen"))
    }
    
    private fun isAppSwitchedFromRecents(event: AccessibilityEvent): Boolean {
        return event.packageName != getSystemDefaultLauncherPackageName() && recentsOpen
    }
    
    private fun clearTemporarilyUnlockedAppIfNeeded(newPackage: String? = null) {
        val sharedPrefs = SharedPreferencesManager.getInstance(applicationContext)
        val shouldClear = newPackage == null ||
                (newPackage != DALEAppLockManager.temporarilyUnlockedApp &&
                        newPackage !in sharedPrefs.getTriggerExcludedApps())
        
        if (shouldClear) {
            Log.d(TAG, "Clearing temporarily unlocked app")
            DALEAppLockManager.clearTemporarilyUnlockedApp()
        }
    }

    private fun handleUninstallAuthGranted(packageName: String, groupId: String?) {
        val sharedPrefs = SharedPreferencesManager.getInstance(applicationContext)
        val group = groupId?.let { sharedPrefs.getAppGroup(it) }
        val appName = when {
            group == null -> packageName
            packageName == group.app1PackageName -> resolveAppLabel(group.app1PackageName, group.app1Name)
            packageName == group.app2PackageName -> resolveAppLabel(group.app2PackageName, group.app2Name)
            else -> packageName
        }

        pendingUninstallChallenge = UninstallChallenge(
            packageName = packageName,
            groupId = groupId ?: "",
            appName = appName
        )
        uninstallApprovalUntilMs = System.currentTimeMillis() + 15_000L
        DALEAppLockManager.isLockScreenShown.set(false)

        Log.d(TAG, "Uninstall credentials accepted for $appName ($packageName); approval window active")
        // Defer attempting to resume the uninstall dialog to avoid interfering with lock screen display
        mainHandler.postDelayed({
            try {
                attemptResumeUninstallDialog(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to auto-resume uninstall dialog: ${e.message}")
            }
        }, 500L) // 500ms delay to let lock screen fully appear
    }

    private fun clearUninstallChallenge() {
        pendingUninstallChallenge = null
        uninstallApprovalUntilMs = 0L
    }

    private fun handleUninstallDialogEvent(event: AccessibilityEvent, packageName: String): Boolean {
        val lowerPackage = packageName.lowercase(Locale.ROOT)
        if (!lowerPackage.contains("packageinstaller")) return false

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return false
        }

        val windowText = collectVisibleWindowText(event)
        if (windowText.isBlank()) return false

        val targetGroup = SharedPreferencesManager.getInstance(applicationContext)
            .getAllAppGroups()
            .firstOrNull { group -> matchesUninstallDialogForGroup(windowText, group) }
            ?: return false

        val targetChallenge = resolveUninstallTarget(windowText, targetGroup) ?: return false
        val now = System.currentTimeMillis()
        val approvalActive = pendingUninstallChallenge?.packageName == targetChallenge.packageName &&
                now <= uninstallApprovalUntilMs

        if (approvalActive) {
            Log.d(TAG, "Uninstall approval active for ${targetChallenge.packageName}")
            return true
        }

        if (DALEAppLockManager.isLockScreenShown.get()) {
            return true
        }

        pendingUninstallChallenge = targetChallenge
        Log.d(TAG, "Uninstall dialog detected for ${targetChallenge.appName} (${targetChallenge.packageName})")
        showLockScreen(targetChallenge.packageName, targetChallenge.groupId, isUninstallFlow = true)
        return true
    }

    private fun matchesUninstallDialogForGroup(windowText: String, group: AppGroup): Boolean {
        val lower = windowText.lowercase(Locale.ROOT)
        if (!lower.contains("uninstall")) return false

        val app1Name = resolveAppLabel(group.app1PackageName, group.app1Name).lowercase(Locale.ROOT)
        val app2Name = resolveAppLabel(group.app2PackageName, group.app2Name).lowercase(Locale.ROOT)
        return (app1Name.isNotBlank() && lower.contains(app1Name)) ||
            (app2Name.isNotBlank() && lower.contains(app2Name))
    }

    private fun resolveUninstallTarget(windowText: String, group: AppGroup): UninstallChallenge? {
        val lower = windowText.lowercase(Locale.ROOT)
        val app1Name = resolveAppLabel(group.app1PackageName, group.app1Name)
        val app2Name = resolveAppLabel(group.app2PackageName, group.app2Name)

        return when {
            app1Name.isNotBlank() && lower.contains(app1Name.lowercase(Locale.ROOT)) -> {
                UninstallChallenge(group.app1PackageName, group.id, app1Name)
            }
            app2Name.isNotBlank() && lower.contains(app2Name.lowercase(Locale.ROOT)) -> {
                UninstallChallenge(group.app2PackageName, group.id, app2Name)
            }
            else -> null
        }
    }

    private fun resolveAppLabel(packageName: String, fallbackName: String): String {
        if (fallbackName.isNotBlank()) return fallbackName
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun collectVisibleWindowText(event: AccessibilityEvent): String {
        val parts = mutableListOf<String>()
        event.text?.forEach { text ->
            val value = text?.toString()?.trim().orEmpty()
            if (value.isNotBlank()) {
                parts.add(value)
            }
        }

        event.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(parts::add)

        rootInActiveWindow?.let { root ->
            collectNodeText(root, parts)
        }

        return parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    private fun collectNodeText(node: AccessibilityNodeInfo?, parts: MutableList<String>) {
        if (node == null) return

        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(parts::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(parts::add)

        for (index in 0 until node.childCount) {
            collectNodeText(node.getChild(index), parts)
        }
    }

    // Try to locate the uninstall dialog and click the uninstall/ok button. Retries for a short period
    private fun attemptResumeUninstallDialog(targetPackage: String) {
        val start = System.currentTimeMillis()
        val timeout = 3000L
        val interval = 200L

        val runnable = object : Runnable {
            override fun run() {
                try {
                    val root = rootInActiveWindow
                    if (root != null) {
                        val windowText = collectVisibleWindowTextFromRoot(root)
                        if (windowText.isNotBlank() && windowText.lowercase(Locale.ROOT).contains("uninstall")) {
                            val clicked = findAndClickUninstallButton(root)
                            if (clicked) {
                                Log.d(TAG, "Auto-clicked uninstall button for $targetPackage")
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error while attempting to resume uninstall dialog: ${e.message}")
                }

                if (System.currentTimeMillis() - start < timeout) {
                    mainHandler.postDelayed(this, interval)
                } else {
                    Log.d(TAG, "Giving up trying to resume uninstall dialog for $targetPackage")
                }
            }
        }

        mainHandler.post(runnable)
    }

    private fun collectVisibleWindowTextFromRoot(root: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        collectNodeText(root, parts)
        return parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    private fun findAndClickUninstallButton(root: AccessibilityNodeInfo): Boolean {
        val candidates = listOf("uninstall", "ok", "confirm", "yes")

        for (candidate in candidates) {
            val nodes = root.findAccessibilityNodeInfosByText(candidate)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    try {
                        if (node.isClickable) {
                            val performed = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (performed) return true
                        } else {
                            var parent = node.parent
                            while (parent != null) {
                                try {
                                    if (parent.isClickable) {
                                        val performed = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                        if (performed) return true
                                    }
                                } catch (_: Exception) {}
                                parent = parent.parent
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val className = node.className?.toString()?.lowercase(Locale.ROOT) ?: ""
                if (className.contains("button")) {
                    try {
                        if (node.isClickable) {
                            val performed = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (performed) return true
                        }
                    } catch (_: Exception) {}
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        } catch (_: Exception) {}

        return false
    }

    private fun isValidPackageForLocking(packageName: String): Boolean {
        // Check if device is locked
        if (applicationContext.isDeviceLocked()) {
            DALEAppLockManager.appUnlockTimes.clear()
            DALEAppLockManager.clearTemporarilyUnlockedApp()
            return false
        }
        
        // Skip excluded packages
        if (packageName == this.packageName ||
            packageName in keyboardPackages ||
            packageName in DALELockConstants.EXCLUDED_APPS
        ) {
            return false
        }
        
        return true
    }
    
    private fun processPackageLocking(packageName: String) {
         val currentForegroundPackage = packageName
         val triggeringPackage = lastForegroundPackage
         lastForegroundPackage = currentForegroundPackage

         val sharedPrefs = SharedPreferencesManager.getInstance(applicationContext)

         // Skip if triggering package is excluded
         if (triggeringPackage in sharedPrefs.getTriggerExcludedApps()) {
             return
         }

         // Check if package changed
         if (currentForegroundPackage == triggeringPackage) {
             return
         }

         // Log app closed if previous app was in a protected group
         if (triggeringPackage.isNotEmpty()) {
             // ✅ Fixed: Find group info before logging closed
             try {
                 val appGroups = sharedPrefs.getAllAppGroups()
                 for (group in appGroups) {
                     if (triggeringPackage == group.app1PackageName) {
                         logAppClosedIfProtected(
                             packageName = triggeringPackage,
                             groupId = group.id,
                             groupName = group.groupName,
                             appName = group.app1Name,
                             sharedPrefs = sharedPrefs
                         )
                         break
                     } else if (triggeringPackage == group.app2PackageName) {
                         logAppClosedIfProtected(
                             packageName = triggeringPackage,
                             groupId = group.id,
                             groupName = group.groupName,
                             appName = group.app2Name,
                             sharedPrefs = sharedPrefs
                         )
                         break
                     }
                 }
             } catch (e: Exception) {
                 Log.e(TAG, "Error logging app closed for $triggeringPackage", e)
             }
         }

         // Log app opened if current app is in a protected group
         logAppOpenedIfProtected(currentForegroundPackage, sharedPrefs)

         checkAndLockApp(currentForegroundPackage, triggeringPackage, System.currentTimeMillis())
     }

     private fun logAppOpenedIfProtected(packageName: String, sharedPrefs: SharedPreferencesManager) {
         try {
             val appGroups = sharedPrefs.getAllAppGroups()
             for (group in appGroups) {
                 if (packageName == group.app1PackageName) {
                     val message = " APP_OPENED: ${group.app1Name} ($packageName) from group '${group.groupName}' [Accessibility Service]"
                     Log.d("AppDetection", message)
                     AppActivityLogger.logAppOpened(
                         packageName,
                         group.app1Name,
                         group.groupName,
                         "Accessibility Service"
                     )
                     return
                 }
                 if (packageName == group.app2PackageName) {
                     val message = " APP_OPENED: ${group.app2Name} ($packageName) from group '${group.groupName}' [Accessibility Service]"
                     Log.d("AppDetection", message)
                     AppActivityLogger.logAppOpened(
                         packageName,
                         group.app2Name,
                         group.groupName,
                         "Accessibility Service"
                     )
                     return
                 }
             }
         } catch (e: Exception) {
             Log.e(TAG, "Error logging app opened: $packageName", e)
         }
     }

      private fun logAppClosedIfProtected(
          packageName: String,
          groupId: String,
          groupName: String,
          appName: String,
          sharedPrefs: SharedPreferencesManager
      ) {
          try {
              Log.d("AppDetection", " Checking if $packageName should be logged as closed...")

              // ✅ STEP 3: Check if last log entry for this package is "OPENED"
              // If it's already "CLOSED", skip logging to prevent duplicates
              val lastEvent = sharedPrefs.getLatestActivityEventForPackage(groupId, packageName)

              if (lastEvent?.uppercase(Locale.ROOT) == "CLOSED") {
                  Log.d("AppDetection", "⏭️ Skipped logging CLOSED - last event was already CLOSED for $packageName")
                  return
              }

              if (lastEvent == null) {
                  Log.d("AppDetection", "⚠️ No previous log found for $packageName - will log CLOSED anyway")
              } else {
                  Log.d("AppDetection", "✅ Last event was $lastEvent - safe to log CLOSED")
              }

              // ✅ Now log as CLOSED to activity logs (database)
              val timestamp = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
                  .format(Date())

              sharedPrefs.saveActivityLog(
                  groupId = groupId,
                  entry = ActivityLogEntry(
                      appName = appName,
                      packageName = packageName,
                      event = "CLOSED",
                      timestamp = timestamp
                  )
              )

              // Also log to file (security log)
              AppActivityLogger.logAppClosed(
                  packageName,
                  appName,
                  groupName,
                  "Home Launcher Detection (Accessibility Service)"
              )

              Log.d("AppDetection", "✅ App closed successfully logged: $appName ($packageName) in group '$groupName'")

          } catch (e: Exception) {
              Log.e(TAG, "Error logging app closed: $packageName", e)
              Log.e("AppDetection", "❌ Exception in logAppClosedIfProtected: ${e.message}")
              e.printStackTrace()
          }
      }

     private fun checkAndLockApp(packageName: String, triggeringPackage: String, currentTime: Long) {
          try {
              val sharedPrefs = SharedPreferencesManager.getInstance(applicationContext)

              // Check if app is in any protected group
              val appGroups = sharedPrefs.getAllAppGroups()
              for (group in appGroups) {
                  if (packageName == group.app1PackageName || packageName == group.app2PackageName) {
                      val appName = if (packageName == group.app1PackageName) group.app1Name else group.app2Name

                      // ✅ NEW ALGORITHM: Check last log entry for this package FIRST
                      // Do this BEFORE checking isLockScreenShown, because state should be based on actual logs
                      Log.d("AppDetection", " Checking last log entry for $packageName...")
                      val lastEvent = sharedPrefs.getLatestActivityEventForPackage(group.id, packageName)

                      when {
                          lastEvent?.uppercase(Locale.ROOT) == "OPENED" -> {
                              // ✅ Last log is OPENED - User is currently using the app
                              Log.d("AppDetection", "✅ LAST LOG IS OPENED: $appName ($packageName) - User already unlocked, skipping lock screen")
                              return
                          }
                          lastEvent?.uppercase(Locale.ROOT) == "CLOSED" -> {
                              // ✅ Last log is CLOSED - App was closed, need lock screen
                              Log.d("AppDetection", " LAST LOG IS CLOSED: $appName ($packageName) - Triggering lock screen")

                                // ✅ Reset lock screen state if needed (app was closed, so it's a new session)
                                if (DALEAppLockManager.isLockScreenShown.get()) {
                                    Log.d("AppDetection", "⚠️ Resetting lock screen state for new session")
                                    DALEAppLockManager.isLockScreenShown.set(false)
                                }

                                // Check unlock grace period and temporary unlock state before showing lock
                                val unlockTime = DALEAppLockManager.appUnlockTimes[packageName]
                                if (unlockTime != null) {
                                    val elapsed = System.currentTimeMillis() - unlockTime
                                    if (elapsed < 5000) {
                                        Log.d(TAG, "App still in grace period, skipping lock for $packageName (elapsed: ${elapsed}ms)")
                                        DALEAppLockManager.appUnlockTimes.remove(packageName)
                                        return
                                    }
                                    DALEAppLockManager.appUnlockTimes.remove(packageName)
                                }

                                if (DALEAppLockManager.isAppTemporarilyUnlocked(packageName)) {
                                    Log.d(TAG, "App is temporarily unlocked, skipping lock for $packageName")
                                    return
                                }

                                showLockScreen(packageName, group.id)
                                return
                          }
                          lastEvent == null -> {
                              // ✅ No previous logs - First time opening, trigger lock screen
                              Log.d("AppDetection", "⚠️ NO PREVIOUS LOGS: $appName ($packageName) - First time opening, triggering lock screen")

                                // ✅ Reset lock screen state if needed
                                if (DALEAppLockManager.isLockScreenShown.get()) {
                                    Log.d("AppDetection", "⚠️ Resetting lock screen state for first-time opening")
                                    DALEAppLockManager.isLockScreenShown.set(false)
                                }

                                // Check unlock grace period and temporary unlock state before showing lock
                                val unlockTime2 = DALEAppLockManager.appUnlockTimes[packageName]
                                if (unlockTime2 != null) {
                                    val elapsed2 = System.currentTimeMillis() - unlockTime2
                                    if (elapsed2 < 5000) {
                                        Log.d(TAG, "App still in grace period, skipping lock for $packageName (elapsed: ${elapsed2}ms)")
                                        DALEAppLockManager.appUnlockTimes.remove(packageName)
                                        return
                                    }
                                    DALEAppLockManager.appUnlockTimes.remove(packageName)
                                }

                                if (DALEAppLockManager.isAppTemporarilyUnlocked(packageName)) {
                                    Log.d(TAG, "App is temporarily unlocked, skipping lock for $packageName")
                                    return
                                }

                                showLockScreen(packageName, group.id)
                                return
                          }
                          else -> {
                              // Unknown event type, trigger lock screen for safety
                              Log.d("AppDetection", "❓ UNKNOWN EVENT TYPE: $appName ($packageName) - Last event: $lastEvent - Triggering lock screen")

                                // ✅ Reset lock screen state if needed
                                if (DALEAppLockManager.isLockScreenShown.get()) {
                                    Log.d("AppDetection", "⚠️ Resetting lock screen state for unknown event")
                                    DALEAppLockManager.isLockScreenShown.set(false)
                                }

                                // Check unlock grace period and temporary unlock state before showing lock
                                val unlockTime3 = DALEAppLockManager.appUnlockTimes[packageName]
                                if (unlockTime3 != null) {
                                    val elapsed3 = System.currentTimeMillis() - unlockTime3
                                    if (elapsed3 < 5000) {
                                        Log.d(TAG, "App still in grace period, skipping lock for $packageName (elapsed: ${elapsed3}ms)")
                                        DALEAppLockManager.appUnlockTimes.remove(packageName)
                                        return
                                    }
                                    DALEAppLockManager.appUnlockTimes.remove(packageName)
                                }

                                if (DALEAppLockManager.isAppTemporarilyUnlocked(packageName)) {
                                    Log.d(TAG, "App is temporarily unlocked, skipping lock for $packageName")
                                    return
                                }

                                showLockScreen(packageName, group.id)
                                return
                          }
                      }
                  }
              }

              // App is not protected, just log it
              Log.d("AppDetection", "✅ UNPROTECTED_APP_OPENED: $packageName (not in any group)")

          } catch (e: Exception) {
              Log.e(TAG, "Error checking app lock", e)
              Log.e("AppDetection", "❌ ERROR_IN_CHECK_AND_LOCK: $packageName - ${e.message}")
          }
      }

      private fun showLockScreen(packageName: String, groupId: String, isUninstallFlow: Boolean = false) {
          try {
              DALEAppLockManager.isLockScreenShown.set(true)
              Log.d(TAG, "Showing lock screen for: $packageName")

              // Log the lock screen trigger
              val sharedPrefs = SharedPreferencesManager.getInstance(applicationContext)
              val group = sharedPrefs.getAppGroup(groupId)
              if (group != null) {
                  val appName = if (packageName == group.app1PackageName) group.app1Name else group.app2Name

                  Log.d("AppDetection", " LOCK_SCREEN_TRIGGERED ========================================")
                  Log.d("AppDetection", "   App: $appName")
                  Log.d("AppDetection", "   Package: $packageName")
                  Log.d("AppDetection", "   Group: ${group.groupName}")
                  Log.d("AppDetection", "   Method: Accessibility Service")
                  Log.d("AppDetection", "   Status: User must enter credentials to unlock")
                  Log.d("AppDetection", " ========================================")

                  AppActivityLogger.logLockScreenTriggered(
                      packageName,
                      appName,
                      group.groupName,
                      "Accessibility Service"
                  )
              }

              val intent = Intent(this, DrawOverOtherAppsLockScreen::class.java).apply {
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                  addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                  addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                  putExtra("TARGET_PACKAGE", packageName)
                  putExtra("GROUP_ID", groupId)
                  putExtra("IS_UNINSTALL_FLOW", isUninstallFlow)
              }
              startActivity(intent)
          } catch (e: Exception) {
              Log.e(TAG, "Error showing lock screen", e)
              Log.e("AppDetection", "❌ ERROR_SHOWING_LOCK_SCREEN: $packageName - ${e.message}")
              DALEAppLockManager.isLockScreenShown.set(false)
          }
      }

    private fun getSystemDefaultLauncherPackageName(): String {
        return try {
            val pm = packageManager
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            
            val resolveInfoList = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfoList.firstOrNull()?.activityInfo?.packageName ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error getting launcher package", e)
            ""
        }
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Service unbound")
        isServiceRunning = false
        return super.onUnbind(intent)
    }
    
    override fun onDestroy() {
        try {
            super.onDestroy()
            isServiceRunning = false
            Log.d(TAG, "Accessibility service destroyed")
            
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: IllegalArgumentException) {
                Log.w(TAG, "Receiver not registered")
            }

            try {
                unregisterReceiver(appUnlockReceiver)
            } catch (_: IllegalArgumentException) {
                Log.w(TAG, "Uninstall receiver not registered")
            }

            DALEAppLockManager.isLockScreenShown.set(false)
            clearUninstallChallenge()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
    }
}
