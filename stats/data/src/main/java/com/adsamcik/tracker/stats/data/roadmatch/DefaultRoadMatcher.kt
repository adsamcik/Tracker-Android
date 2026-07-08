package com.adsamcik.tracker.stats.data.roadmatch

import com.adsamcik.tracker.osm.match.OsmHmmMapMatcher
import com.adsamcik.tracker.shared.base.database.dao.OsmImportDao
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.stats.api.roadmatch.MatchedEdge
import com.adsamcik.tracker.stats.api.roadmatch.RoadMatcher
import com.adsamcik.tracker.stats.api.roadmatch.RoadObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [RoadMatcher] used in production.
 *
 * Gating: the matcher only makes sense when the user has imported at least one
 * OSM region. When no region is present we return an empty result so the map's
 * Vehicle Compliance layer renders its explicit "no road data" empty state
 * instead of guessing from raw GPS.
 *
 * Mirrors the snapshot/cold-start pattern of
 * [com.adsamcik.tracker.stats.data.speed.DefaultSpeedLimitSource]: the live OSM
 * import count is cached in a [StateFlow] (no per-call IO), with a single-flight
 * direct `count()` fallback while the flow is still on its sentinel.
 *
 * Stays strictly offline — [OsmHmmMapMatcher] reads only local Room data.
 */
@Singleton
class DefaultRoadMatcher @Inject constructor(
	private val matcher: OsmHmmMapMatcher,
	private val osmImportDao: OsmImportDao,
	@ApplicationScope appScope: CoroutineScope,
) : RoadMatcher {

	private val cachedOsmImportCount: StateFlow<Int> = osmImportDao.observeCount()
		.stateIn(appScope, SharingStarted.Eagerly, COUNT_UNINITIALIZED)

	@Volatile
	private var coldStartCount: Int = COUNT_UNINITIALIZED

	private val coldStartMutex = Mutex()

	override suspend fun match(observations: List<RoadObservation>): List<MatchedEdge> {
		if (observations.size < 2) return emptyList()
		if (osmImportCountOrWarmUp() == 0) return emptyList()
		return matcher.match(observations)
	}

	private suspend fun osmImportCountOrWarmUp(): Int {
		val live = cachedOsmImportCount.value
		if (live != COUNT_UNINITIALIZED) return live
		val coldCached = coldStartCount
		if (coldCached != COUNT_UNINITIALIZED) return coldCached
		return coldStartMutex.withLock {
			val liveRecheck = cachedOsmImportCount.value
			if (liveRecheck != COUNT_UNINITIALIZED) return@withLock liveRecheck
			val coldRecheck = coldStartCount
			if (coldRecheck != COUNT_UNINITIALIZED) return@withLock coldRecheck
			val direct = osmImportDao.count()
			coldStartCount = direct
			direct
		}
	}

	private companion object {
		private const val COUNT_UNINITIALIZED = -1
	}
}
