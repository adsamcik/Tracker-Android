package com.adsamcik.tracker.stats.data.speed

import com.adsamcik.tracker.osm.speed.OsmSpeedLimitSource
import com.adsamcik.tracker.shared.base.database.dao.OsmImportDao
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.stats.api.speed.SpeedLimitSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * called `osmImportDao.readyCount()` on every `limitMpsAt` invocation. Hot callers
 * — most notably the vehicle-compliance map layer — iterate over hundreds of
 * samples per heatmap rebuild, so the aggregate cost is significant even
 * though a `COUNT(*)` is cheap individually.
 *
 * We now subscribe to [OsmImportDao.observeReadyCount] once in init and cache the
 * latest count in a [kotlinx.coroutines.flow.StateFlow]. The hot path reads
 * the cached value with no IO. Disabling all OSM regions still takes effect
 * for the next sample after the Flow propagates, exactly as before.
 *
 * **Cold-start single-flight (R2 round-6 second-pass):** the eager StateFlow
 * collector is launched on [appScope] but does not necessarily emit before
 * the first `limitMpsAt` caller arrives — a heatmap rebuild triggered from
 * `Application.onCreate` can issue hundreds of parallel calls before the
 * Room observer has had a chance to fire. The previous implementation issued
 * an `osmImportDao.readyCount()` on every cold-start caller, which defeated the
 * snapshot benefit and reintroduced the IO storm we were trying to avoid.
 *
 * The cold-start fallback is now serialised by [coldStartMutex] and the
 * result is cached in [coldStartCount] so exactly one DAO query is issued
 * regardless of how many parallel callers race the warmup. Once the
 * StateFlow has emitted at least once we prefer its value (it tracks live
 * updates); the cold-start cache is only consulted while the StateFlow is
 * still on its sentinel.
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

	private val cachedOsmImportCount: StateFlow<Int> = osmImportDao.observeReadyCount()
		.stateIn(appScope, SharingStarted.Eagerly, COUNT_UNINITIALIZED)

	/**
	 * Cached result of the cold-start direct `readyCount()` fallback. Guarded by
	 * [coldStartMutex] for writes; reads are lock-free via the volatile
	 * field — readers either see [COUNT_UNINITIALIZED] (and pay the mutex
	 * cost to re-check) or a final value (and skip the mutex entirely).
	 */
	@Volatile
	private var coldStartCount: Int = COUNT_UNINITIALIZED

	/**
	 * Serialises the cold-start `readyCount()` fallback so N parallel callers
	 * before the StateFlow's first emission issue at most one DAO query.
	 */
	private val coldStartMutex = Mutex()

	override suspend fun limitMpsAt(epochMs: Long, latE7: Int?, lonE7: Int?): Double {
		if (latE7 == null || lonE7 == null) {
			return fixed.limitMpsAt(epochMs, latE7, lonE7)
		}
		val effectiveCount = osmImportCountOrWarmUp()
		if (effectiveCount == 0) {
			return fixed.limitMpsAt(epochMs, latE7, lonE7)
		}
		val osmLimit = osm.findRoadLimitMps(latE7, lonE7)
		return osmLimit ?: fixed.limitMpsAt(epochMs, latE7, lonE7)
	}

	/**
	 * Returns the current OSM import count, preferring the live StateFlow
	 * snapshot and falling back to a single-flight direct DAO query only
	 * when the snapshot has not emitted yet.
	 */
	private suspend fun osmImportCountOrWarmUp(): Int {
		val live = cachedOsmImportCount.value
		if (live != COUNT_UNINITIALIZED) return live
		val coldCached = coldStartCount
		if (coldCached != COUNT_UNINITIALIZED) return coldCached
		return coldStartMutex.withLock {
			// Recheck both sources under the lock so concurrent callers
			// converge on the same warmup result without redundant queries.
			val liveRecheck = cachedOsmImportCount.value
			if (liveRecheck != COUNT_UNINITIALIZED) return@withLock liveRecheck
			val coldRecheck = coldStartCount
			if (coldRecheck != COUNT_UNINITIALIZED) return@withLock coldRecheck
			val direct = osmImportDao.readyCount()
			coldStartCount = direct
			direct
		}
	}

	private companion object {
		// Sentinel for "the upstream observeReadyCount() flow has not emitted yet".
		// Valid counts are always >= 0.
		private const val COUNT_UNINITIALIZED = -1
	}
}
