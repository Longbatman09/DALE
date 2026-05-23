package com.example.dale.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToLong

fun performKeypadHaptic(
    context: Context,
    intensityPercent: Int = 100,
    durationMs: Long = 35L
) {
    val normalizedIntensity = intensityPercent.coerceIn(0, 100)
    if (normalizedIntensity == 0) {
        return
    }

    val minDurationMs = 8L
    val maxDurationMs = 60L
    val scaledDuration = (
        minDurationMs + (maxDurationMs - minDurationMs) * (normalizedIntensity / 100f)
    ).roundToLong().coerceAtLeast(1L)

    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    if (vibrator?.hasVibrator() != true) {
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val hasAmplitudeControl = vibrator.hasAmplitudeControl()
        val amplitude = if (hasAmplitudeControl) {
            (normalizedIntensity * 255 / 100).coerceIn(1, 255)
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }
        vibrator.vibrate(VibrationEffect.createOneShot(scaledDuration, amplitude))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(scaledDuration)
    }
}
