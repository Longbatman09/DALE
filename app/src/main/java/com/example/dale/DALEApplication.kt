package com.example.dale

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.dale.utils.AccessibilityStatusNotifier
import com.example.dale.utils.AppActivityLogger

class DALEApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize activity logger
        AppActivityLogger.initialize(this)
        AccessibilityStatusNotifier.sync(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                applySystemBars(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                applySystemBars(activity)
                AccessibilityStatusNotifier.sync(activity)
            }

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun applySystemBars(activity: Activity) {
        val systemBarColor = ContextCompat.getColor(activity, R.color.dale_system_bar)
        activity.window.statusBarColor = systemBarColor
        activity.window.navigationBarColor = systemBarColor

        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
    }
}



