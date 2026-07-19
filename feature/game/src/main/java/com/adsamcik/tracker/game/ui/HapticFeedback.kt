package com.adsamcik.tracker.game.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.getSystemService

/**
 * Consistent haptic feedback vocabulary for game events.
 * All patterns are short, subtle, and battery-friendly.
 */
object HapticFeedback {

	/** Short tap confirmation (20ms). */
	fun tapConfirm(context: Context) {
		vibrate(context, longArrayOf(0, 20))
	}

	/** Progress milestone double-tick (20ms, gap, 20ms). */
	fun progressMilestone(context: Context) {
		vibrate(context, longArrayOf(0, 20, 80, 20))
	}

	/** Achievement unlock triple ascending (20ms, 30ms, 40ms). */
	fun achievementComplete(context: Context) {
		vibrate(context, longArrayOf(0, 20, 60, 30, 60, 40))
	}

	/** Level up long roll (100ms). */
	fun levelUp(context: Context) {
		vibrate(context, longArrayOf(0, 100))
	}

	/** Record broken burst (3 × 15ms). */
	fun recordBroken(context: Context) {
		vibrate(context, longArrayOf(0, 15, 40, 15, 40, 15))
	}

	/** Streak broken heavy thud (80ms). */
	fun streakBroken(context: Context) {
		vibrate(context, longArrayOf(0, 80))
	}

	private fun vibrate(context: Context, pattern: LongArray) {
		runCatching {
			val appContext = context.applicationContext
			val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				appContext.getSystemService<VibratorManager>()?.defaultVibrator
			} else {
				@Suppress("DEPRECATION")
				appContext.getSystemService<Vibrator>()
			} ?: return
			if (!vibrator.hasVibrator()) return

			// Uses the application service, so cues remain safe when no screen is attached.
			vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
		}
	}
}
