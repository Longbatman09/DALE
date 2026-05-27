package com.example.dale.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.dale.ActivityLogEntry
import com.example.dale.AppGroup
import com.example.dale.UsageLogEntry
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SharedPreferencesManager private constructor(context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val gson = Gson()

    fun isProtectionEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_PROTECTION_ENABLED, true)
    }

    fun setProtectionEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_PROTECTION_ENABLED, enabled).apply()
    }

    fun setSetupCompleted(completed: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_SETUP_COMPLETED, completed).apply()
    }

    fun isSetupCompleted(): Boolean {
        return sharedPreferences.getBoolean(KEY_SETUP_COMPLETED, false)
    }

    fun saveAppGroup(appGroup: AppGroup) {
        val json = gson.toJson(appGroup)
        sharedPreferences.edit().putString("app_group_${appGroup.id}", json).apply()
    }

    fun savePendingAppGroup(appGroup: AppGroup) {
        val json = gson.toJson(appGroup)
        sharedPreferences.edit().putString("pending_app_group_${appGroup.id}", json).apply()
    }

    fun getAppGroup(groupId: String): AppGroup? {
        val json = sharedPreferences.getString("app_group_$groupId", null)
        return if (json != null) gson.fromJson(json, AppGroup::class.java) else null
    }

    fun getPendingAppGroup(groupId: String): AppGroup? {
        val json = sharedPreferences.getString("pending_app_group_$groupId", null)
        return if (json != null) gson.fromJson(json, AppGroup::class.java) else null
    }

    fun getAppGroupForSetup(groupId: String): AppGroup? {
        return getPendingAppGroup(groupId) ?: getAppGroup(groupId)
    }

    fun saveAppGroupForSetup(appGroup: AppGroup) {
        if (getPendingAppGroup(appGroup.id) != null) {
            savePendingAppGroup(appGroup)
        } else {
            saveAppGroup(appGroup)
        }
    }

    fun getAllAppGroups(): List<AppGroup> {
        val groups = mutableListOf<AppGroup>()
        val allPrefs = sharedPreferences.all
        for ((key, value) in allPrefs) {
            if (key.startsWith("app_group_") && value is String) {
                val group = gson.fromJson(value, AppGroup::class.java)
                groups.add(group)
            }
        }
        return groups.sortedByDescending { it.createdAt }
    }

    fun deleteAppGroup(groupId: String) {
        sharedPreferences.edit().remove("app_group_$groupId").apply()
    }

    fun deletePendingAppGroup(groupId: String) {
        sharedPreferences.edit().remove("pending_app_group_$groupId").apply()
    }

    fun commitPendingAppGroup(groupId: String): AppGroup? {
        val pendingGroup = getPendingAppGroup(groupId) ?: return null
        saveAppGroup(pendingGroup)
        deletePendingAppGroup(groupId)
        return pendingGroup
    }

    fun clearAllData() {
        sharedPreferences.edit().clear().apply()
    }

    // ── Activity Logs ────────────────────────────────────────────────────────

    // ✅ FIX #5: Add thread safety with synchronized keyword
    @Synchronized
    fun saveActivityLog(groupId: String, entry: ActivityLogEntry) {
        val key = "activity_logs_$groupId"
        val existing = getActivityLogs(groupId).toMutableList()
        existing.add(0, entry)          // newest first
        if (existing.size > 200) existing.removeAt(existing.lastIndex)   // cap at 200
        val json = gson.toJson(existing)
        sharedPreferences.edit().putString(key, json).apply()
    }

    // ✅ FIX #1: Improve GSON error handling to catch casting exceptions
    fun getActivityLogs(groupId: String): List<ActivityLogEntry> {
        val json = sharedPreferences.getString("activity_logs_$groupId", null) ?: return emptyList()

        return try {
            // Validate JSON format before deserializing
            if (!json.startsWith("[")) {
                android.util.Log.e("SharedPrefsManager", "Invalid JSON format for activity logs: $groupId (not array)")
                sharedPreferences.edit().remove("activity_logs_$groupId").apply()  // Clear corrupted data
                return emptyList()
            }

            val type = object : TypeToken<List<ActivityLogEntry>>() {}.type
            val logs = gson.fromJson<List<ActivityLogEntry>>(json, type)

            // Validate result
            if (logs == null) {
                android.util.Log.w("SharedPrefsManager", "Deserialized null for activity logs: $groupId")
                return emptyList()
            }

            logs
        } catch (e: com.google.gson.JsonSyntaxException) {
            android.util.Log.e("SharedPrefsManager", "JSON Syntax error reading activity logs for $groupId", e)
            sharedPreferences.edit().remove("activity_logs_$groupId").apply()  // Clear corrupted data
            emptyList()
        } catch (e: com.google.gson.JsonParseException) {
            android.util.Log.e("SharedPrefsManager", "JSON Parse error reading activity logs for $groupId", e)
            sharedPreferences.edit().remove("activity_logs_$groupId").apply()  // Clear corrupted data
            emptyList()
        } catch (e: java.lang.ClassCastException) {
            android.util.Log.e("SharedPrefsManager", "Type casting error reading activity logs for $groupId (JsonPrimitive instead of JsonArray)", e)
            sharedPreferences.edit().remove("activity_logs_$groupId").apply()  // Clear corrupted data
            emptyList()
        } catch (e: Exception) {
            android.util.Log.e("SharedPrefsManager", "Unexpected error reading activity logs for $groupId", e)
            emptyList()
        }
    }

    fun getLatestActivityEventForPackage(groupId: String, packageName: String): String? {
        return try {
            getActivityLogs(groupId)
                .firstOrNull { it.packageName == packageName }
                ?.event
                ?.takeIf { it.isNotBlank() }  // ✅ Ensure non-empty
        } catch (e: Exception) {
            android.util.Log.e("SharedPrefsManager", "Error getting latest activity event for $packageName", e)
            null
        }
    }

    fun clearActivityLogs(groupId: String) {
        sharedPreferences.edit().remove("activity_logs_$groupId").apply()
    }

    // ── Usage Logs ───────────────────────────────────────────────────────────

    fun saveUsageSession(groupId: String, packageName: String, appName: String, durationMs: Long) {
        val key = "usage_logs_$groupId"
        val existing = getRawUsageLogs(groupId).toMutableList()
        val dateStr = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        val idx = existing.indexOfFirst { it.packageName == packageName }
        if (idx >= 0) {
            val old = existing[idx]
            existing[idx] = old.copy(
                totalDurationMs = old.totalDurationMs + durationMs,
                lastUsedDate = dateStr
            )
        } else {
            existing.add(
                UsageLogEntry(
                    appName = appName,
                    packageName = packageName,
                    totalDurationMs = durationMs,
                    formattedDuration = "",
                    lastUsedDate = dateStr,
                    usageFraction = 0f
                )
            )
        }
        val json = gson.toJson(existing)
        sharedPreferences.edit().putString(key, json).apply()
    }

    private fun getRawUsageLogs(groupId: String): List<UsageLogEntry> {
        val json = sharedPreferences.getString("usage_logs_$groupId", null) ?: return emptyList()
        val type = object : TypeToken<List<UsageLogEntry>>() {}.type
        return try { gson.fromJson(json, type) } catch (e: Exception) { emptyList() }
    }

    fun getUsageLogs(groupId: String): List<UsageLogEntry> {
        val raw = getRawUsageLogs(groupId)
        val totalMs = raw.sumOf { it.totalDurationMs }.takeIf { it > 0 } ?: 1L
        return raw.sortedByDescending { it.totalDurationMs }.map { entry ->
            entry.copy(
                formattedDuration = formatDuration(entry.totalDurationMs),
                usageFraction = entry.totalDurationMs.toFloat() / totalMs.toFloat()
            )
        }
    }

    fun clearUsageLogs(groupId: String) {
        sharedPreferences.edit().remove("usage_logs_$groupId").apply()
    }

    fun disableGroupDueToUninstall(groupId: String, uninstalledAppName: String) {
        getAppGroup(groupId)?.let { existingGroup ->
            val updatedGroup = existingGroup.copy(
                isDisabledDueToUninstall = true,
                uninstalledAppName = uninstalledAppName,
                isLocked = false // Disable lockscreen for the entire group
            )
            saveAppGroup(updatedGroup)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun isIntroShown(): Boolean {
        return sharedPreferences.getBoolean(KEY_INTRO_SHOWN, false)
    }

    fun setIntroShown(shown: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_INTRO_SHOWN, shown).apply()
    }

    fun getLastSplashVideoTime(): Long {
        return sharedPreferences.getLong(KEY_LAST_SPLASH_VIDEO_TIME, 0L)
    }

    fun setLastSplashVideoTime(timeInMillis: Long) {
        sharedPreferences.edit().putLong(KEY_LAST_SPLASH_VIDEO_TIME, timeInMillis).apply()
    }

    fun getGlobalVibrationLevel(): String {
        return sharedPreferences.getString(KEY_GLOBAL_VIBRATION_LEVEL, VIBRATION_LEVEL_MAX)
            ?: VIBRATION_LEVEL_MAX
    }

    fun setGlobalVibrationLevel(level: String) {
        sharedPreferences.edit().putString(KEY_GLOBAL_VIBRATION_LEVEL, level).apply()
    }

    fun getGlobalVibrationIntensity(): Int {
        return when (getGlobalVibrationLevel()) {
            VIBRATION_LEVEL_OFF -> 0
            VIBRATION_LEVEL_MIN -> 30
            VIBRATION_LEVEL_MID -> 60
            VIBRATION_LEVEL_MAX -> 100
            else -> 100
        }
    }
    
     /**
      * Get trigger excluded apps (apps that won't trigger lock on other apps)
      */
     fun getTriggerExcludedApps(): Set<String> {
         // For now, return empty set - can be expanded for advanced features
         return emptySet()
     }

     // ── Last Opened App Tracking (Step 1 & 2) ────────────────────────────────────

     /**
      * Store the last opened protected app and its group
      * Used to detect when app closes (home launcher detection)
      */
     fun saveLastOpenedApp(packageName: String, groupId: String, groupName: String, appName: String) {
         sharedPreferences.edit()
             .putString("last_opened_app_package", packageName)
             .putString("last_opened_app_group_id", groupId)
             .putString("last_opened_app_group_name", groupName)
             .putString("last_opened_app_name", appName)
             .apply()
     }

     /**
      * Get the last opened protected app package name
      */
     fun getLastOpenedAppPackage(): String? {
         return sharedPreferences.getString("last_opened_app_package", null)
     }

     /**
      * Get the last opened app's group ID
      */
     fun getLastOpenedAppGroupId(): String? {
         return sharedPreferences.getString("last_opened_app_group_id", null)
     }

     /**
      * Get the last opened app's group name
      */
     fun getLastOpenedAppGroupName(): String? {
         return sharedPreferences.getString("last_opened_app_group_name", null)
     }

     /**
      * Get the last opened app's name
      */
     fun getLastOpenedAppName(): String? {
         return sharedPreferences.getString("last_opened_app_name", null)
     }

     /**
      * Clear the last opened app tracking (when screen locked, after restart, etc)
      */
     fun clearLastOpenedApp() {
         sharedPreferences.edit()
             .remove("last_opened_app_package")
             .remove("last_opened_app_group_id")
             .remove("last_opened_app_group_name")
             .remove("last_opened_app_name")
             .apply()
     }

    /**
     * Get the partner app package for a given package if it belongs to a group
     */
    fun getPartnerPackage(packageName: String): String? {
        val groups = getAllAppGroups()
        for (group in groups) {
            if (group.app1PackageName == packageName) return group.app2PackageName
            if (group.app2PackageName == packageName) return group.app1PackageName
        }
        return null
    }

    /**
     * Get the app group for a specific package
     */
    fun getAppGroupForPackage(packageName: String): AppGroup? {
        val groups = getAllAppGroups()
        return groups.find { it.app1PackageName == packageName || it.app2PackageName == packageName }
    }

    /**
     * Get all locked apps from all groups
     */
    fun getAllLockedApps(): Set<String> {
        val lockedApps = mutableSetOf<String>()
        val groups = getAllAppGroups()
        for (group in groups) {
            lockedApps.add(group.app1PackageName)
            lockedApps.add(group.app2PackageName)
        }
        return lockedApps
    }

    /**
     * Transition Token Mechanism (LS Crossover)
     */
    fun setTransitionToken(targetPackage: String, enabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean("transition_token_$targetPackage", enabled)
            .apply()
    }

    fun hasTransitionToken(targetPackage: String): Boolean {
        return sharedPreferences.getBoolean("transition_token_$targetPackage", false)
    }

    fun consumeTransitionToken(targetPackage: String): Boolean {
        val hasToken = hasTransitionToken(targetPackage)
        if (hasToken) {
            setTransitionToken(targetPackage, false)
        }
        return hasToken
    }

    companion object {
        private const val PREFS_NAME = "DALE_PREFS"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_PROTECTION_ENABLED = "protection_enabled"
        private const val KEY_INTRO_SHOWN = "intro_shown"
        private const val KEY_LAST_SPLASH_VIDEO_TIME = "last_splash_video_time"
        private const val KEY_GLOBAL_VIBRATION_LEVEL = "global_vibration_level"
        private const val VIBRATION_LEVEL_OFF = "OFF"
        private const val VIBRATION_LEVEL_MIN = "MIN"
        private const val VIBRATION_LEVEL_MID = "MID"
        private const val VIBRATION_LEVEL_MAX = "MAX"

        @Volatile
        private var instance: SharedPreferencesManager? = null

        fun getInstance(context: Context): SharedPreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: SharedPreferencesManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
