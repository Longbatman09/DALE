package com.example.dale.utils

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import java.io.File
import java.time.Instant
import kotlin.concurrent.thread

/**
 * Comprehensive app activity logging system for DALE
 * Logs all app open/close events with timestamps and detection method used
 */
object AppActivityLogger {
    private const val TAG = "AppActivityLogger"
    private const val SECURITY_LOGS = "activity_log.txt"
    private lateinit var context: Context
    private lateinit var firebaseAnalytics: FirebaseAnalytics
    private var loggingEnabled = true

    private fun hasAnalyticsConsent(): Boolean {
        return context.getSharedPreferences("dale_prefs", Context.MODE_PRIVATE)
            .getBoolean("analytics_enabled", false)
    }

    fun initialize(application: Context) {
        context = application
        firebaseAnalytics = FirebaseAnalytics.getInstance(application)
    }

    fun setLoggingEnabled(enabled: Boolean) {
        loggingEnabled = enabled
    }

    /**
     * Log an app being opened
     * @param packageName Package of the app
     * @param appName Display name of the app
     * @param groupName Group this app belongs to
     * @param detectionMethod Which detection method triggered this
     */
    fun logAppOpened(
        packageName: String,
        appName: String,
        groupName: String,
        detectionMethod: String
    ) {
        if (!loggingEnabled || !hasAnalyticsConsent()) return

        val timestamp = Instant.now().toString()
        val message = "[$timestamp] APP_OPENED | Group: $groupName | App: $appName ($packageName) | Method: $detectionMethod"

        thread(name = "LogThread-$packageName", isDaemon = true) {
            try {
                // Log to Firebase without sensitive app/package names
                val bundle = Bundle().apply {
                    putString("group_name", groupName)
                    putString("detection_method", detectionMethod)
                }
                firebaseAnalytics.logEvent("app_opened", bundle)

                val file = File(context.filesDir, SECURITY_LOGS)
                if (!file.exists()) {
                    file.createNewFile()
                }
                file.appendText(message + "\n")
                Log.d(TAG, message)
                // Also log to AppDetection tag for easy filtering
                Log.d("AppDetection", "📱 APP_OPENED: $appName ($packageName) from group '$groupName' [via $detectionMethod]")
            } catch (e: Exception) {
                Log.e(TAG, "Error logging app opened: $packageName", e)
                Log.e("AppDetection", "❌ ERROR_LOGGING_APP_OPENED: $packageName - ${e.message}")
            }
        }
    }

    /**
     * Log an app being closed
     * @param packageName Package of the app
     * @param appName Display name of the app
     * @param groupName Group this app belongs to
     * @param detectionMethod Which detection method detected this
     */
    fun logAppClosed(
        packageName: String,
        appName: String,
        groupName: String,
        detectionMethod: String
    ) {
        if (!loggingEnabled || !hasAnalyticsConsent()) return

        val timestamp = Instant.now().toString()
        val message = "[$timestamp] APP_CLOSED | Group: $groupName | App: $appName ($packageName) | Method: $detectionMethod"

        thread(name = "LogThread-$packageName", isDaemon = true) {
            try {
                // Log to Firebase without sensitive app/package names
                val bundle = Bundle().apply {
                    putString("group_name", groupName)
                    putString("detection_method", detectionMethod)
                }
                firebaseAnalytics.logEvent("app_closed", bundle)

                val file = File(context.filesDir, SECURITY_LOGS)
                if (!file.exists()) {
                    file.createNewFile()
                }
                file.appendText(message + "\n")
                Log.d(TAG, message)
                // Also log to AppDetection tag for easy filtering
                Log.d("AppDetection", "🔒 APP_CLOSED: $appName ($packageName) from group '$groupName' [via $detectionMethod]")
            } catch (e: Exception) {
                Log.e(TAG, "Error logging app closed: $packageName", e)
                Log.e("AppDetection", "❌ ERROR_LOGGING_APP_CLOSED: $packageName - ${e.message}")
            }
        }
    }

    /**
     * Log a lock screen event
     */
    fun logLockScreenTriggered(
        packageName: String,
        appName: String,
        groupName: String,
        detectionMethod: String
    ) {
        if (!loggingEnabled || !hasAnalyticsConsent()) return

        val timestamp = Instant.now().toString()
        val message = "[$timestamp] LOCK_SCREEN_TRIGGERED | Group: $groupName | App: $appName ($packageName) | Method: $detectionMethod"

        thread(name = "LogThread-$packageName", isDaemon = true) {
            try {
                // Log to Firebase without sensitive app/package names
                val bundle = Bundle().apply {
                    putString("group_name", groupName)
                    putString("detection_method", detectionMethod)
                }
                firebaseAnalytics.logEvent("lock_screen_triggered", bundle)

                val file = File(context.filesDir, SECURITY_LOGS)
                if (!file.exists()) {
                    file.createNewFile()
                }
                file.appendText(message + "\n")
                Log.d(TAG, message)
            } catch (e: Exception) {
                Log.e(TAG, "Error logging lock screen triggered: $packageName", e)
            }
        }
    }

    /**
     * Log PIN entry attempt
     */
    fun logPINAttempt(
        packageName: String,
        appName: String,
        groupName: String,
        success: Boolean
    ) {
        if (!loggingEnabled || !hasAnalyticsConsent()) return

        val timestamp = Instant.now().toString()
        val status = if (success) "SUCCESS" else "FAILED"
        val message = "[$timestamp] PIN_ATTEMPT | Group: $groupName | App: $appName ($packageName) | Status: $status"

        thread(name = "LogThread-$packageName", isDaemon = true) {
            try {
                // Log to Firebase without sensitive app/package names
                val bundle = Bundle().apply {
                    putString("group_name", groupName)
                    putBoolean("success", success)
                }
                firebaseAnalytics.logEvent("pin_attempt", bundle)

                val file = File(context.filesDir, SECURITY_LOGS)
                if (!file.exists()) {
                    file.createNewFile()
                }
                file.appendText(message + "\n")
                Log.d(TAG, message)
            } catch (e: Exception) {
                Log.e(TAG, "Error logging PIN attempt: $packageName", e)
            }
        }
    }

    /**
     * Get all activity logs
     */
    fun getActivityLogs(): List<String> {
        return try {
            val file = File(context.filesDir, SECURITY_LOGS)
            if (!file.exists()) {
                emptyList()
            } else {
                file.readLines().reversed()  // Most recent first
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading activity logs", e)
            emptyList()
        }
    }

    /**
     * Clear all activity logs
     */
    fun clearActivityLogs() {
        thread(name = "ClearLogsThread", isDaemon = true) {
            try {
                val file = File(context.filesDir, SECURITY_LOGS)
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "Cleared activity logs")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing logs", e)
            }
        }
    }

    /**
     * Get last N logs
     */
    fun getLastLogs(limit: Int = 50): List<String> {
        return getActivityLogs().take(limit)
    }

    /**
     * Export logs to a formatted string
     */
    fun exportLogs(): String {
        return getActivityLogs().joinToString("\n")
    }
}
