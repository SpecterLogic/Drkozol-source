package com.v2ray.ang.ui.compose

import android.content.Context
import android.media.AudioAttributes
import android.os.VibratorManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil

/** Called only after the app's feedback preference and tap throttle have been checked. */
internal fun performAppClickFeedback(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else context.getSystemService(Vibrator::class.java)
    vibrator ?: return
    if (!vibrator.hasVibrator()) return
    // This app-owned, user-toggleable effect uses the media vibration channel. Android
    // otherwise reclassifies a short click as system touch feedback and silences it when
    // that separate setting is off. System-wide vibration restrictions still apply.
    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
                VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_MEDIA).build())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            legacyEffectClick(vibrator, attributes)
        } else {
            legacyClick(vibrator, attributes)
        }
    } catch (error: RuntimeException) {
        LogUtil.w(AppConfig.TAG, "App feedback: click unavailable", error)
    }
}

// API 24-25 do not expose VibrationEffect. Remove when minSdk reaches 26.
@Suppress("DEPRECATION")
private fun legacyClick(vibrator: Vibrator, attributes: AudioAttributes) = vibrator.vibrate(12L, attributes)

// API 26-32 require AudioAttributes; VibrationAttributes arrives in 33. Remove at minSdk 33.
@androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
@Suppress("DEPRECATION")
private fun legacyEffectClick(vibrator: Vibrator, attributes: AudioAttributes) {
    val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    else VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE)
    vibrator.vibrate(effect, attributes)
}
