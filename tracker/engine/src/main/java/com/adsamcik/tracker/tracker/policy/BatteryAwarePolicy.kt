package com.adsamcik.tracker.tracker.policy

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.adsamcik.tracker.stats.api.PolicyTier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Adjusts the tracking [PolicyTier] based on current battery level.
 *
 * When battery is critically low, tracking is capped to preserve device
 * usability. This integrates with the existing escalation pipeline —
 * it never raises the tier, only lowers or caps it.
 *
 * Battery thresholds:
 * - ≤10%: [PolicyTier.AMBIENT] (critical — bare minimum, no GPS)
 * - ≤20%: cap at [PolicyTier.AMBIENT] (low — no GPS)
 * - ≤35%: cap at [PolicyTier.ACTIVE] (medium — no PRECISION)
 * - >35%: no adjustment (normal — full quality)
 */
@Singleton
class BatteryAwarePolicy @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	val batteryLevelUpdates: Flow<Int> = callbackFlow {
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				trySend(intent.batteryPercentageOrNull() ?: currentBatteryLevel())
			}
		}
		val stickyIntent = context.registerReceiver(
			receiver,
			IntentFilter(Intent.ACTION_BATTERY_CHANGED),
		)
		trySend(stickyIntent?.batteryPercentageOrNull() ?: currentBatteryLevel())
		awaitClose { context.unregisterReceiver(receiver) }
	}

	/**
	 * Adjusts [baseTier] downward based on current battery level.
	 *
	 * @param baseTier The tier determined by the escalation engine or user.
	 * @return The adjusted tier, which is always ≤ [baseTier].
	 */
	fun adjustForBattery(baseTier: PolicyTier): PolicyTier {
		val level = currentBatteryLevel()
		val isCharging = context.getSystemService(BatteryManager::class.java)?.isCharging == true
		return adjustForBatteryLevel(baseTier, level, isCharging)
	}

	/**
	 * Pure function for testability: adjusts tier given a battery level.
	 */
	internal fun adjustForBatteryLevel(
		baseTier: PolicyTier,
		batteryLevel: Int,
		isCharging: Boolean = false,
	): PolicyTier {
		if (baseTier == PolicyTier.OFF) return PolicyTier.OFF
		if (isCharging) return baseTier

		return when {
			batteryLevel <= CRITICAL_LEVEL -> PolicyTier.AMBIENT
			batteryLevel <= LOW_LEVEL -> minOf(baseTier, PolicyTier.AMBIENT)
			batteryLevel <= MEDIUM_LEVEL -> minOf(baseTier, PolicyTier.ACTIVE)
			else -> baseTier
		}
	}

	private fun currentBatteryLevel(): Int {
		val batteryManager = context.getSystemService(BatteryManager::class.java)
		return batteryManager
			?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
			?.takeIf { it in 0..100 }
			?: DEFAULT_BATTERY_LEVEL
	}


	private fun Intent.batteryPercentageOrNull(): Int? {
		val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
		val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
		return if (level >= 0 && scale > 0) {
			(level * 100 / scale).coerceIn(0, 100)
		} else {
			null
		}
	}

	internal companion object {
		const val CRITICAL_LEVEL = 10
		const val LOW_LEVEL = 20
		const val MEDIUM_LEVEL = 35
		const val DEFAULT_BATTERY_LEVEL = 100
	}
}
