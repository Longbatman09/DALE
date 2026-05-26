package com.example.dale.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.dale.R

object AccessibilityStatusNotifier {
    private const val CHANNEL_ID = "dale_accessibility_required"
    private const val NOTIFICATION_ID = 4001

    fun sync(context: Context) {
        val appContext = context.applicationContext
        val sharedPrefs = SharedPreferencesManager.getInstance(appContext)
        val shouldShow = sharedPrefs.isSetupCompleted() &&
            sharedPrefs.isProtectionEnabled() &&
            !MonitorStartupHelper.isAccessibilityServiceEnabled(appContext)

        if (shouldShow) {
            show(appContext)
        } else {
            cancel(appContext)
        }
    }

    fun show(context: Context) {
        val appContext = context.applicationContext
        if (!canPostNotifications(appContext)) return

        createChannel(appContext)

        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle("DALE accessibility is off")
            .setContentText("Tap to enable accessibility and restore app protection.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("DALE cannot protect your apps while accessibility is off. Tap to open settings and enable DALE.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()

        appContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.applicationContext
            .getSystemService(NotificationManager::class.java)
            .cancel(NOTIFICATION_ID)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "DALE accessibility required",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Persistent warning when DALE accessibility is disabled"
            setShowBadge(true)
            enableVibration(true)
        }

        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }
}
