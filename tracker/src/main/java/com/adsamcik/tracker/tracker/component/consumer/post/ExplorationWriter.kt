package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ExplorationCellEntity
import com.adsamcik.tracker.shared.base.database.data.ExplorationStreakEntity
import com.adsamcik.tracker.stats.engine.exploration.CellDiscovery
import com.adsamcik.tracker.stats.engine.exploration.CellDiscoveryEngine
import com.adsamcik.tracker.stats.engine.exploration.CellDiscoveryConfig
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Post-tracker component that discovers S2 cells as the user moves.
 *
 * Feeds GPS locations into [CellDiscoveryEngine] and persists discovered
 * cells to the database. Also maintains daily discovery streaks.
 *
 * Contract:
 * - No required data: operates on whatever location is available
 * - Thread safety: called from TrackerService's componentMutex (single-threaded)
 */
internal class ExplorationWriter : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private lateinit var database: AppDatabase
	private var scope: CoroutineScope? = null
	private var engine: CellDiscoveryEngine? = null
	private var config: CellDiscoveryConfig = CellDiscoveryConfig()

	// Track whether we discovered any new cells this session for streak logic
	private var newCellsThisSession: Int = 0

	override suspend fun onEnable(context: Context) {
		database = AppDatabase.database(context)
		scope = CoroutineScope(Job() + Dispatchers.Default)

		// Load known tokens from DB to avoid re-marking as new
		val knownTokens = database.explorationCellDao()
			.getAllTokensAtLevel(config.cellLevel)
			.toSet()

		engine = CellDiscoveryEngine(
			config = config,
			knownTokens = knownTokens,
		)
		newCellsThisSession = 0
	}

	override suspend fun onDisable(context: Context) {
		val now = Time.nowMillis
		// Use withContext for final writes so they complete before scope cancellation
		withContext(Dispatchers.IO) {
			// Finalize current cell (may emit quality upgrade)
			engine?.finalize(now)?.let { discovery ->
				persistDiscoverySync(discovery, now)
			}

			// Update daily discovery streak if we found new cells
			if (newCellsThisSession > 0) {
				updateDailyStreakSync(now)
			}
		}

		engine = null
		scope?.cancel()
		scope = null
	}

	override fun onNewData(
		context: Context,
		session: com.adsamcik.tracker.shared.base.data.TrackerSession,
		collectionData: CollectionData,
		tempData: CollectionTempData,
	) {
		val eng = engine ?: return
		val location = collectionData.location ?: return

		val discovery = eng.onLocation(
			latDeg = location.latitude,
			lngDeg = location.longitude,
			accuracyM = location.horizontalAccuracy,
			timestampMs = tempData.timeMillis,
		)

		if (discovery != null) {
			val now = Time.nowMillis
			if (discovery.isNew) {
				newCellsThisSession++
			}
			persistDiscovery(discovery, now)
		}
	}

	private fun persistDiscovery(discovery: CellDiscovery, now: Long) {
		scope?.launch(Dispatchers.IO) {
			persistDiscoverySync(discovery, now)
		}
	}

	private suspend fun persistDiscoverySync(discovery: CellDiscovery, now: Long) {
		val existing = database.explorationCellDao().getByToken(discovery.token)
		if (existing != null) {
			// Update visit: only upgrade quality, never downgrade
			val newQuality = maxOf(existing.quality, discovery.quality.ordinal)
			database.explorationCellDao().updateVisit(
				token = discovery.token,
				quality = newQuality,
				lastVisitedAt = now,
				seasonBit = discovery.seasonBit,
			)
		} else {
			// Insert new cell
			database.explorationCellDao().insert(
				ExplorationCellEntity(
					cellToken = discovery.token,
					level = discovery.level,
					quality = discovery.quality.ordinal,
					firstDiscoveredAt = now,
					lastVisitedAt = now,
					visitCount = 1,
					seasonBitmask = discovery.seasonBit,
					centerLatE7 = discovery.centerLatE7,
					centerLonE7 = discovery.centerLonE7,
					createdAt = now,
				)
			)
		}
	}

	private suspend fun updateDailyStreakSync(now: Long) {
		val epochDay = now / (24 * 60 * 60 * 1000L)
		val streakType = "DAILY_DISCOVERY"

		val streak = database.explorationStreakDao().getByType(streakType)
		if (streak == null) {
			// First ever streak entry
			database.explorationStreakDao().upsert(
				ExplorationStreakEntity(
					type = streakType,
					currentCount = 1,
					bestCount = 1,
					lastIncrementDay = epochDay,
					updatedAt = now,
				)
			)
		} else if (streak.lastIncrementDay == epochDay) {
			// Already incremented today, nothing to do
		} else if (streak.lastIncrementDay == epochDay - 1) {
			// Consecutive day - increment streak
			database.explorationStreakDao().incrementStreak(streakType, epochDay, now)
		} else {
			// Streak broken - reset to 1 (single upsert replaces resetStreak + upsert)
			database.explorationStreakDao().upsert(
				ExplorationStreakEntity(
					type = streakType,
					currentCount = 1,
					bestCount = streak.bestCount,
					lastIncrementDay = epochDay,
					updatedAt = now,
				)
			)
		}
	}
}
