package com.adsamcik.tracker.stats.data.speed

import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.stats.api.speed.SpeedLimitSource
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 1 implementation of [SpeedLimitSource] that always returns the
 * user-configured single baseline speed limit stored in
 * [TrackingParamsRepository].
 *
 * No network access. The baseline value is the same for every location and
 * timestamp - sophisticated per-road limits are deferred to future phases.
 */
@Singleton
class FixedSpeedLimitSource @Inject constructor(
	private val trackingParamsRepository: TrackingParamsRepository,
) : SpeedLimitSource {

	override suspend fun limitMpsAt(epochMs: Long, latE7: Int?, lonE7: Int?): Double {
		return trackingParamsRepository.data.first().vehicleSpeedLimitBaselineMps
	}
}
