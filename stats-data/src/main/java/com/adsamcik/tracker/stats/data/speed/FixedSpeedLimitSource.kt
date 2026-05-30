package com.adsamcik.tracker.stats.data.speed

import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.stats.api.speed.SpeedLimitSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 1 implementation of [SpeedLimitSource] that always returns the
 * user-configured single baseline speed limit stored in
 * [TrackingParamsRepository].
 *
 * **Snapshot pattern (R2 round-6 perf review):** the previous implementation
 * collected `trackingParamsRepository.data.first()` on every call, which
 * suspends the caller and hits DataStore on the hot path. Hot callers like
 * the vehicle-compliance map layer iterate over hundreds of samples per
 * heatmap rebuild — the per-call cost adds up to seconds of avoidable IO.
 *
 * We now subscribe to the params flow ONCE in init and cache the latest
 * baseline value in a [kotlinx.coroutines.flow.StateFlow] backed by
 * `stateIn(..., SharingStarted.Eagerly, …)`. The hot path reads the cached
 * value with no suspension and no IO. On the very first call (before the
 * DataStore flow has emitted) we fall back to a one-shot `data.first()` so
 * behaviour is identical to the pre-snapshot version — subsequent calls
 * always hit the cache.
 *
 * No network access. The baseline value is the same for every location and
 * timestamp - sophisticated per-road limits are deferred to future phases.
 */
@Singleton
class FixedSpeedLimitSource @Inject constructor(
	private val trackingParamsRepository: TrackingParamsRepository,
	@ApplicationScope appScope: CoroutineScope,
) : SpeedLimitSource {

	private val cachedBaselineMps = trackingParamsRepository.data
		.map { it.vehicleSpeedLimitBaselineMps }
		.stateIn(appScope, SharingStarted.Eagerly, BASELINE_UNINITIALIZED)

	override suspend fun limitMpsAt(epochMs: Long, latE7: Int?, lonE7: Int?): Double {
		val cached = cachedBaselineMps.value
		if (!cached.isNaN()) return cached
		// First call before the upstream flow has emitted: fall back to a
		// one-shot collect so we never return the sentinel.
		return trackingParamsRepository.data.first().vehicleSpeedLimitBaselineMps
	}

	private companion object {
		// NaN is unambiguous: a legitimate baseline is always a finite, positive m/s.
		private val BASELINE_UNINITIALIZED = Double.NaN
	}
}
