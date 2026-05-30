package com.adsamcik.tracker.stats.data.speed

import com.adsamcik.tracker.osm.speed.OsmSpeedLimitSource
import com.adsamcik.tracker.shared.base.database.dao.OsmImportDao
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.stats.api.speed.SpeedLimitSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [SpeedLimitSource] dispatcher used in production.
 *
 * Decision tree on every call:
 *  - If the call is missing coordinates → fall back to [FixedSpeedLimitSource].
 *  - If the user has imported at least one OSM region → ask
 *    [OsmSpeedLimitSource]; on a hit (way within 50 m) return the road's
 *    limit, otherwise fall back to [FixedSpeedLimitSource].
 *  - Otherwise → [FixedSpeedLimitSource].
 *
 * **Snapshot pattern (R2 round-6 perf review):** the previous implementation
 * called `osmImportDao.count()` on every `limitMpsAt` invocation. Hot callers
 * — most notably the vehicle-compliance map layer — iterate over hundreds of
 * samples per heatmap rebuild, so the aggregate cost is significant even
 * though a `COUNT(*)` is cheap individually.
 *
 * We now subscribe to [OsmImportDao.observeCount] once in init and cache the
 * latest count in a [kotlinx.coroutines.flow.StateFlow]. The hot path reads
 * the cached value with no IO. If the cache hasn't been populated yet (the
 * Flow has not emitted between class construction and the first call) we
 * fall back to a one-shot `count()` query so behaviour is identical to the
 * pre-snapshot version on cold start. Disabling all OSM regions still takes
 * effect for the next sample after the Flow propagates, exactly as before.
 *
 * Stays strictly offline — both backing sources are local.
 */
@Singleton
class DefaultSpeedLimitSource @Inject constructor(
	private val fixed: FixedSpeedLimitSource,
	private val osm: OsmSpeedLimitSource,
	private val osmImportDao: OsmImportDao,
	@ApplicationScope appScope: CoroutineScope,
) : SpeedLimitSource {

	private val cachedOsmImportCount = osmImportDao.observeCount()
		.stateIn(appScope, SharingStarted.Eagerly, COUNT_UNINITIALIZED)

	override suspend fun limitMpsAt(epochMs: Long, latE7: Int?, lonE7: Int?): Double {
		if (latE7 == null || lonE7 == null) {
			return fixed.limitMpsAt(epochMs, latE7, lonE7)
		}
		val cached = cachedOsmImportCount.value
		val effectiveCount = if (cached == COUNT_UNINITIALIZED) osmImportDao.count() else cached
		if (effectiveCount == 0) {
			return fixed.limitMpsAt(epochMs, latE7, lonE7)
		}
		val osmLimit = osm.findRoadLimitMps(latE7, lonE7)
		return osmLimit ?: fixed.limitMpsAt(epochMs, latE7, lonE7)
	}

	private companion object {
		// Sentinel for "the upstream observeCount() flow has not emitted yet".
		// Valid counts are always >= 0.
		private const val COUNT_UNINITIALIZED = -1
	}
}
