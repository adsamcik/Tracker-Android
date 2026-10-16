package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.LengthUnit
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.stats.api.error.StatsError
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Measures current saved Location sections for whole completed trips, selected by original start.
 * No provider is started and no cached aggregate can outlive a point correction or deletion.
 * Native membership is explicit; unattributed saved/imported trips keep their compatibility interval.
 */
class SavedTripDistanceReader @Inject constructor(
	private val database: AppDatabase,
	private val dispatchers: DispatchersProvider,
) {
	/**
	 * Returns the largest complete saved-trip distance in whole meters for trips starting within the selected
	 * UTC-millisecond bounds; gaps are not bridged.
	 */
	suspend fun longestDistanceMeters(fromMs: Long, toMs: Long): Either<StatsError, Long> =
		withContext(dispatchers.io) {
			if (fromMs < 0L || toMs < fromMs) {
				return@withContext StatsError.ValidationError("Invalid longest trip interval").left()
			}
			try {
				database.withTransaction {
					if (database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null) {
						throw UnavailableDistance("Location retention is still being updated")
					}
					val state = database.sourceEvidenceStateDao().get()
						?: throw IllegalStateException("Source evidence state is unavailable")
					val logicalMaximum = readLogicalTripMaximum(fromMs, toMs, state.retainedFromMs)
					val maximum = readLegacyTripMaximum(fromMs, toMs, state.retainedFromMs, logicalMaximum)
					currentCoroutineContext().ensureActive()
					maximumResult(maximum, fromMs, toMs)
				}
			} catch (error: CancellationException) {
				throw error
			} catch (error: UnavailableDistance) {
				StatsError.ValidationError(requireNotNull(error.message)).left()
			} catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
				// Room reads and saved-row materialization failures stay in this public metric result.
				StatsError.DatabaseError("Failed to load measured trip distance", error).left()
			}
		}

	private fun maximumResult(maximum: Double?, fromMs: Long, toMs: Long): Either<StatsError, Long> =
		maximum?.let { value ->
			if (!value.isFinite() || value >= Long.MAX_VALUE.toDouble()) {
				throw UnavailableDistance("Measured trip distance is outside the supported range")
			}
			value.toLong().right()
		} ?: StatsError.NotFound("No measured trip distance is available", "Location trip",
			"$fromMs:$toMs").left()

	private suspend fun readLogicalTripMaximum(fromMs: Long, toMs: Long, retainedFromMs: Long?): Double? {
		var maximum: Double? = null
		var afterStart: Long? = null
		var afterLogicalId: String? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = database.sourceSessionDao().completedLocationSessionCandidatePage(
				fromMs, toMs, afterStart, afterLogicalId, CANDIDATE_PAGE_SIZE,
			)
			for (session in page) {
				val value = readLogicalTrip(session, retainedFromMs)
				if (value != null) {
					maximum = maxOf(maximum ?: value, value)
				}
			}
			if (page.size < CANDIDATE_PAGE_SIZE) {
				break
			}
			afterStart = page.last().startedAtMs
			afterLogicalId = page.last().logicalTrackingId
		}
		return maximum
	}

	private suspend fun readLegacyTripMaximum(
		fromMs: Long,
		toMs: Long,
		retainedFromMs: Long?,
		initialMaximum: Double?,
	): Double? {
		var maximum = initialMaximum
		var afterStart: Long? = null
		var afterSegmentId: Long? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = database.sessionSegmentDao().legacyLocationTripCandidatePage(
				fromMs, toMs, afterStart, afterSegmentId, CANDIDATE_PAGE_SIZE,
			)
			for (segment in page) {
				checkBounds(segment.startTimeMs, segment.endTimeMs, retainedFromMs)
				val value = readPoints { time, id -> database.locationPurposeDao().savedLegacyTripPointPage(
					segment.startTimeMs, segment.endTimeMs, time, id, POINT_PAGE_SIZE,
				) }
				if (value != null) {
					maximum = maxOf(maximum ?: value, value)
				}
			}
			if (page.size < CANDIDATE_PAGE_SIZE) {
				break
			}
			afterStart = page.last().startTimeMs
			afterSegmentId = page.last().id
		}
		return maximum
	}

	private suspend fun readLogicalTrip(session: LogicalTrackingSessionEntity, retainedFromMs: Long?): Double? {
		val runs = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
			listOf(session.logicalTrackingId), MAX_MEMBERS + 1, null, null, null,
		)
		if (runs.size > MAX_MEMBERS) {
			throw UnavailableDistance("Trip membership read budget exceeded")
		}
		val runIds = runs.map { it.serviceRunId }
		val segments = database.trackingHistoryReadDao().rawLogicalEntrySegments(
			session.logicalTrackingId, runIds, MAX_MEMBERS + 1,
		)
		if (segments.size > MAX_MEMBERS) {
			throw UnavailableDistance("Trip membership read budget exceeded")
		}
		verifyTripMembership(session, runs, segments, retainedFromMs)

		val fenced = database.sourceDeletionFenceDao().contextFences(LOCATION, CAPTURE,
			runs.map { SourceDeletionFenceEntity.logicalServiceRunIdentity(LOCATION, CAPTURE, session.logicalTrackingId,
                it.serviceRunId) },
			MAX_MEMBERS + 1).map { it.scopeIdentityDigest }.toSet()
		val retainedRuns = runs.filter { SourceDeletionFenceEntity.logicalServiceRunIdentity(
			LOCATION, CAPTURE, session.logicalTrackingId, it.serviceRunId) !in fenced }
		val modernIds = retainedRuns.filter {
			it.presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE
		}.map { it.serviceRunId }
		val total = if (modernIds.isEmpty()) {
			null
		} else {
			readPoints { time, id ->
				database.locationPurposeDao().savedLogicalTripPointPage(session.logicalTrackingId, modernIds,
					time, id, POINT_PAGE_SIZE)
			}
		}
		return readLegacyTripMembers(segments, retainedRuns, total)
	}

	private fun verifyTripMembership(
		session: LogicalTrackingSessionEntity,
		runs: List<SourceServiceRunEntity>,
		segments: List<SessionSegment>,
		retainedFromMs: Long?,
	) {
		if (runs.isEmpty() || runs.size != segments.size ||
			runs.any { !it.hasCompleteTripSegment(session.logicalTrackingId, segments) }) {
			throw UnavailableDistance("Trip membership is incomplete")
		}
		checkBounds(session.startedAtMs, session.cutoffAtMs, retainedFromMs)
		if (session.completedAtMs == null) {
			throw UnavailableDistance("Trip completion is unavailable")
		}
		for (segment in segments) checkBounds(segment.startTimeMs, segment.endTimeMs, retainedFromMs)
	}

	private fun SourceServiceRunEntity.hasCompleteTripSegment(
		logicalId: String,
		segments: List<SessionSegment>,
	): Boolean {
		val member = segments.singleOrNull { it.serviceRunId == serviceRunId } ?: return false
		return member.logicalTrackingId == logicalId &&
			(presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE ||
				sessionSegmentId == member.id) && completedAtMs != null
	}

	private suspend fun readLegacyTripMembers(
		segments: List<SessionSegment>,
		retainedRuns: List<SourceServiceRunEntity>,
		initialTotal: Double?,
	): Double? {
		var total = initialTotal
		val legacyIds = retainedRuns.filter {
			it.presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE
		}.map { it.serviceRunId }.toSet()
		val legacyMembers = segments.filter { it.serviceRunId in legacyIds }.sortedBy { it.startTimeMs }
		if (legacyMembers.zipWithNext().any { (left, right) -> left.endTimeMs >= right.startTimeMs }) {
			throw UnavailableDistance("Legacy trip membership has overlapping intervals")
		}
		for (member in legacyMembers) {
			val value = readPoints { time, id -> database.locationPurposeDao().savedLegacyTripPointPage(
				member.startTimeMs, member.endTimeMs, time, id, POINT_PAGE_SIZE,
			) }
			if (value != null) {
				total = (total ?: 0.0) + value
			}
		}
		return total
	}

	private suspend fun readPoints(
		page: suspend (afterTimeMs: Long?, afterId: Long?) -> List<LocationPathSampleRow>,
	): Double? {
		val distance = SavedTripDistanceAccumulator()
		var afterTime: Long? = null
		var afterId: Long? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val points = page(afterTime, afterId)
			for (point in points) {
				currentCoroutineContext().ensureActive()
				distance.add(point)
			}
			if (points.size < POINT_PAGE_SIZE) {
				break
			}
			afterTime = points.last().sample.timeMs
			afterId = points.last().sample.id
		}
		return distance.measuredMeters
	}

	private fun checkBounds(start: Long, end: Long?, retainedFromMs: Long?) {
		if (start < 0L || end == null || end < start) {
			throw UnavailableDistance("Trip time bounds are unavailable")
		}
		if (retainedFromMs != null && start < retainedFromMs) {
			throw UnavailableDistance("The beginning of a trip is outside retained Location history")
		}
	}

	private class UnavailableDistance(message: String) : IllegalStateException(message)

	private companion object {
		const val CANDIDATE_PAGE_SIZE = 64
		const val POINT_PAGE_SIZE = 256
		const val MAX_MEMBERS = 256
		const val LOCATION = 1
		const val CAPTURE = "SESSION_CAPTURE"
	}
}

/** Constant-memory route measurement; the same continuity boundaries used by geographic exports. */
internal data class SavedTripDistanceState(
	val previous: LocationPathSampleRow? = null,
	val totalMeters: Double = 0.0,
	val hasMeasuredPair: Boolean = false,
	val disconnected: Boolean = false,
	val movingDistanceMeters: Double = 0.0,
	val movingDurationSeconds: Double = 0.0,
) {
	init {
		require(totalMeters.isFinite() && totalMeters >= 0.0)
		require(movingDistanceMeters.isFinite() && movingDistanceMeters >= 0.0)
		require(movingDurationSeconds.isFinite() && movingDurationSeconds >= 0.0)
	}
}

internal class SavedTripDistanceAccumulator(state: SavedTripDistanceState = SavedTripDistanceState()) {
	private var previous: LocationPathSampleRow? = state.previous
	private var totalMeters = state.totalMeters
	private var hasMeasuredPair = state.hasMeasuredPair
	private var movingDistanceMeters = state.movingDistanceMeters
	private var movingDurationSeconds = state.movingDurationSeconds
	fun snapshotState() = SavedTripDistanceState(previous, totalMeters, hasMeasuredPair,
		hasDisconnectedSections, movingDistanceMeters, movingDurationSeconds)
	var hasDisconnectedSections: Boolean = state.disconnected
		private set
	val measuredMeters: Double? get() = totalMeters.takeIf { hasMeasuredPair }
	val movingAverageSpeedMetersPerSecond: Double? get() = when {
		!hasMeasuredPair -> null
		movingDurationSeconds > 0.0 -> movingDistanceMeters / movingDurationSeconds
		else -> 0.0
	}

	/** A rejected or erased candidate cannot become a bridge between its valid neighbours. */
	fun breakContinuity() {
		if (previous != null) {
			hasDisconnectedSections = true
		}
		previous = null
	}

	fun add(row: LocationPathSampleRow) {
		val point = row.sample
		val lat = point.latE7 ?: return breakContinuity()
		val lon = point.lonE7 ?: return breakContinuity()
		if (lat !in -900_000_000..900_000_000 || lon !in -1_800_000_000..1_800_000_000 || point.timeMs < 0L) {
			breakContinuity()
			return
		}
		val old = previous
		if (old != null) {
			if (continuous(old, row)) {
				addMeasuredPair(old, row, lat, lon)
			} else {
				hasDisconnectedSections = true
			}
		}
		previous = row
	}

	private fun addMeasuredPair(old: LocationPathSampleRow, current: LocationPathSampleRow, lat: Int, lon: Int) {
		val point = current.sample
		val meters = Location.distance(requireNotNull(old.sample.latE7) / 1e7,
			requireNotNull(old.sample.lonE7) / 1e7, lat / 1e7, lon / 1e7, LengthUnit.Meter)
		totalMeters += meters
		if (meters > 0.0) {
			movingDistanceMeters += meters
			movingDurationSeconds += if (old.continuityId != null) {
				(point.elapsedRealtimeNanos - old.sample.elapsedRealtimeNanos).toDouble() / NANOSECONDS_PER_SECOND
			} else {
				(point.timeMs - old.sample.timeMs).toDouble() / MILLISECONDS_PER_SECOND
			}
		}
		hasMeasuredPair = true
	}

	private fun continuous(old: LocationPathSampleRow, current: LocationPathSampleRow): Boolean {
		val before = old.sample
		val after = current.sample
		return if (old.continuityId != null || current.continuityId != null) {
			consecutiveRegistrationSequence(old, current) &&
				before.clockDomainId == after.clockDomainId && before.elapsedRealtimeNanos > 0L &&
				after.elapsedRealtimeNanos > before.elapsedRealtimeNanos &&
				after.elapsedRealtimeNanos - before.elapsedRealtimeNanos <= MAX_GAP_NANOS
		} else {
			after.timeMs > before.timeMs && after.timeMs - before.timeMs <= MAX_GAP_MS
		}
	}

	private fun consecutiveRegistrationSequence(old: LocationPathSampleRow, current: LocationPathSampleRow): Boolean {
		val beforeSequence = old.continuitySequence
		return old.continuityId == current.continuityId && beforeSequence != null &&
			beforeSequence != Long.MAX_VALUE && current.continuitySequence == beforeSequence + 1
	}

	private companion object {
		const val NANOSECONDS_PER_SECOND = 1_000_000_000.0
		const val MILLISECONDS_PER_SECOND = 1_000.0
		const val MAX_GAP_MS = 120_000L
		const val MAX_GAP_NANOS = 120_000_000_000L
	}
}
