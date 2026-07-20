package com.adsamcik.tracker.shared.base.database.analysis

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AnalysisCell
import com.adsamcik.tracker.shared.base.database.data.PresenceCellContribution
import com.adsamcik.tracker.shared.base.database.data.PresenceCompactionBlock
import com.adsamcik.tracker.shared.base.database.data.PresenceCompactionCheckpoint
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.PresenceInterval
import com.adsamcik.tracker.shared.base.database.data.TrackerRun
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Materializes canonical presence intervals and immutable UTC-day observed-presence blocks.
 *
 * Version 1 intentionally performs no behavioural inference: usable fixes are OBSERVED and every
 * remaining tracked millisecond is UNRESOLVED. This conservative baseline establishes mass
 * conservation before a calibrated inference model is introduced.
 */
class PresenceCompactor(
	private val database: AppDatabase,
	private val nowMillis: () -> Long = { Time.nowMillis },
) {
	data class CompactionProgress(
		val safeThroughMs: Long,
		val safeObservationId: Long,
		val targetThroughMs: Long,
		val complete: Boolean,
		val partitionsProcessed: Int,
	)

	private data class TimedInterval(
		val startMs: Long,
		val endMs: Long,
		val observation: LocationObservation?,
		val unresolvedReason: String?,
		val run: TrackerRun,
	)

	private data class CellMass(
		var expectedMs: Long = 0L,
		var observedMs: Long = 0L,
		var inferredMs: Long = 0L,
	)

	/** Compacts oldest-first and advances the deletion-safe checkpoint only across contiguous days. */
	suspend fun compactThroughExclusive(
		requestedThroughMs: Long,
		maxPartitions: Int = DEFAULT_MAX_PARTITIONS,
	): CompactionProgress = COMPACTION_MUTEX.withLock {
		compactThroughExclusiveLocked(requestedThroughMs, maxPartitions)
	}

	private suspend fun compactThroughExclusiveLocked(
		requestedThroughMs: Long,
		maxPartitions: Int,
	): CompactionProgress {
		require(maxPartitions > 0)
		val target = startOfUtcDay(requestedThroughMs)
		val dao = database.presenceAnalysisDao()
		val reconciledPartitions = reconcileLateArrivals()
		val existing = dao.getCheckpoint(PIPELINE_KEY)
		var cursor = existing?.contiguousCompactedThroughMs ?: earliestSourceDay() ?: target
		cursor = startOfUtcDay(cursor)
		var processed = 0
		while (cursor + Time.DAY_IN_MILLISECONDS <= target && processed < maxPartitions) {
			compactPartitionLocked(cursor, advanceCheckpoint = true)
			cursor += Time.DAY_IN_MILLISECONDS
			processed++
		}
		val finalCheckpoint = dao.getCheckpoint(PIPELINE_KEY)
		val safeThrough = finalCheckpoint?.contiguousCompactedThroughMs ?: cursor
		return CompactionProgress(
			safeThroughMs = safeThrough,
			safeObservationId = finalCheckpoint?.sourceMaxObservationId ?: 0L,
			targetThroughMs = target,
			complete = safeThrough >= target,
			partitionsProcessed = reconciledPartitions + processed,
		)
	}

	/**
	 * Rebuild every already-compacted UTC day touched by observations committed after the checkpoint
	 * snapshot. The id-bounded scan and compare-and-set watermark make concurrent new arrivals safe:
	 * anything inserted after [scanThroughId] remains above the deletion watermark for the next run.
	 */
	private suspend fun reconcileLateArrivals(): Int {
		var rebuiltPartitions = 0
		repeat(MAX_RECONCILIATION_RETRIES) {
			val presenceAnalysisDao = database.presenceAnalysisDao()
			val checkpoint = presenceAnalysisDao.getCheckpoint(PIPELINE_KEY) ?: return rebuiltPartitions
			val observationDao = database.locationObservationDao()
			val scanThroughId = observationDao.maxId()
			if (scanThroughId <= checkpoint.sourceMaxObservationId) return rebuiltPartitions

			val affectedDays = observationDao.getLateArrivals(
				afterId = checkpoint.sourceMaxObservationId,
				throughId = scanThroughId,
				// Look-ahead observations can change support on the preceding UTC partition.
				beforeFixTimeMs = saturatedAdd(
					checkpoint.contiguousCompactedThroughMs,
					MAX_CONTINUOUS_GAP_MS,
				),
			).asSequence()
				.flatMap { reference ->
					sequenceOf(
						startOfUtcDay(saturatedSubtract(reference.fixTimeMs, MAX_CONTINUOUS_GAP_MS)),
						startOfUtcDay(reference.fixTimeMs),
						startOfUtcDay(saturatedAdd(reference.fixTimeMs, MAX_CONTINUOUS_GAP_MS)),
					)
				}
				.filter { it < checkpoint.contiguousCompactedThroughMs }
				.distinct()
				.sorted()
				.toList()
			affectedDays.forEach { day -> compactPartitionLocked(day, advanceCheckpoint = false) }
			rebuiltPartitions += affectedDays.size

			val advanced = presenceAnalysisDao.advanceObservationWatermark(
				pipelineKey = PIPELINE_KEY,
				expectedCompactedThroughMs = checkpoint.contiguousCompactedThroughMs,
				expectedSourceMaxObservationId = checkpoint.sourceMaxObservationId,
				newSourceMaxObservationId = scanThroughId,
				updatedAt = nowMillis(),
			)
			if (advanced == 1) return rebuiltPartitions
		}
		error("Presence checkpoint changed repeatedly during late-arrival reconciliation")
	}

	/** Rebuilds one UTC partition as a new generation. Map-triggered rebuilds must not advance retention. */
	suspend fun compactPartition(
		partitionStartMs: Long,
		advanceCheckpoint: Boolean = false,
	) = COMPACTION_MUTEX.withLock {
		compactPartitionLocked(partitionStartMs, advanceCheckpoint)
	}

	private suspend fun compactPartitionLocked(
		partitionStartMs: Long,
		advanceCheckpoint: Boolean,
	) {
		require(partitionStartMs == startOfUtcDay(partitionStartMs)) {
			"Presence partitions must start at UTC midnight"
		}
		val partitionEndMs = partitionStartMs + Time.DAY_IN_MILLISECONDS
		// Snapshot before source materialization. Later inserts receive larger ids and cannot be
		// deleted under the checkpoint produced by this partition build.
		val sourceObservationHighWatermark = database.locationObservationDao().maxId()
		val presenceRows = materializePresenceLocked(partitionStartMs, partitionEndMs)

		val cellsById = LinkedHashMap<String, AnalysisCell>()
		val massByCell = LinkedHashMap<String, CellMass>()
		for (presence in presenceRows) {
			if (presence.resolutionState == STATE_UNRESOLVED) continue
			val lat = requireNotNull(presence.centerLatE7) / 1e7
			val lon = requireNotNull(presence.centerLonE7) / 1e7
			val r90 = requireNotNull(presence.effectiveR90M)
			val finest = MetricAnalysisGrid.finestSupportedResolution(r90) ?: continue
			val durationMs = presence.endTimeMs - presence.startTimeMs
			for (resolution in MetricAnalysisGrid.RESOLUTIONS_M.filter { it >= finest }) {
				val allocated = MetricAnalysisGrid.allocateMillis(
					durationMs,
					MetricAnalysisGrid.weightedCells(lat, lon, r90, resolution),
				)
				for (weightedCell in allocated) {
					val cell = weightedCell.cell
					cellsById[cell.cellId] = cell
					val mass = massByCell.getOrPut(cell.cellId) { CellMass() }
					mass.expectedMs += weightedCell.millis
					if (presence.resolutionState == STATE_OBSERVED) {
						mass.observedMs += weightedCell.millis
					} else {
						mass.inferredMs += weightedCell.millis
					}
				}
			}
		}

		val observedMs = presenceRows
			.filter { it.resolutionState == STATE_OBSERVED }
			.sumOf { it.endTimeMs - it.startTimeMs }
		val inferredMs = presenceRows
			.filter { it.resolutionState == STATE_INFERRED }
			.sumOf { it.endTimeMs - it.startTimeMs }
		val unresolvedMs = presenceRows
			.filter { it.resolutionState == STATE_UNRESOLVED }
			.sumOf { it.endTimeMs - it.startTimeMs }
		val resolvedMs = observedMs + inferredMs
		for (resolution in MetricAnalysisGrid.RESOLUTIONS_M) {
			val supportedAtResolutionMs = presenceRows
				.asSequence()
				.filter { it.resolutionState != STATE_UNRESOLVED }
				.filter { MetricAnalysisGrid.finestSupportedResolution(it.effectiveR90M) != null }
				.filter { MetricAnalysisGrid.finestSupportedResolution(it.effectiveR90M)!! <= resolution }
				.sumOf { it.endTimeMs - it.startTimeMs }
			val cellMassMs = massByCell.entries
				.filter { cellsById.getValue(it.key).resolutionM == resolution }
				.sumOf { it.value.expectedMs }
			check(cellMassMs == supportedAtResolutionMs) {
				"Presence mass mismatch at ${resolution}m: cells=$cellMassMs supported=$supportedAtResolutionMs"
			}
		}
		check(observedMs + inferredMs + unresolvedMs == presenceRows.sumOf { it.endTimeMs - it.startTimeMs })
		check(resolvedMs >= 0L)

		val createdAt = nowMillis()
		database.withTransaction {
			val presenceAnalysisDao = database.presenceAnalysisDao()
			if (cellsById.isNotEmpty()) presenceAnalysisDao.insertCells(cellsById.values)
			val generation = presenceAnalysisDao.maxGeneration(
				METRIC_KIND,
				MODEL_KEY,
				MetricAnalysisGrid.GRID_VERSION,
				partitionStartMs,
			) + 1
			val blockId = presenceAnalysisDao.insertBlock(
				PresenceCompactionBlock(
					metricKind = METRIC_KIND,
					partitionStartMs = partitionStartMs,
					partitionEndMs = partitionEndMs,
					modelKey = MODEL_KEY,
					gridVersion = MetricAnalysisGrid.GRID_VERSION,
					generation = generation,
					status = STATUS_STAGING,
					sourceMaxPresenceId = database.presenceIntervalDao().maxId(MODEL_KEY),
					trackedMs = observedMs + inferredMs + unresolvedMs,
					spatiallyObservedMs = observedMs,
					spatiallyInferredMs = inferredMs,
					spatiallyUnresolvedMs = unresolvedMs,
					createdAt = createdAt,
					committedAt = null,
				),
			)
			val contributions = massByCell.map { (cellId, mass) ->
				PresenceCellContribution(
					blockId = blockId,
					cellId = cellId,
					expectedMs = mass.expectedMs,
					observedMs = mass.observedMs,
					inferredMs = mass.inferredMs,
				)
			}
			if (contributions.isNotEmpty()) presenceAnalysisDao.insertContributions(contributions)
			presenceAnalysisDao.supersedeCommitted(
				METRIC_KIND,
				MODEL_KEY,
				MetricAnalysisGrid.GRID_VERSION,
				partitionStartMs,
			)
			check(presenceAnalysisDao.commitBlock(blockId, createdAt) == 1)

			if (advanceCheckpoint) {
				val oldCheckpoint = presenceAnalysisDao.getCheckpoint(PIPELINE_KEY)
				if (oldCheckpoint == null || oldCheckpoint.contiguousCompactedThroughMs == partitionStartMs) {
					presenceAnalysisDao.putCheckpoint(
						PresenceCompactionCheckpoint(
							pipelineKey = PIPELINE_KEY,
							modelKey = MODEL_KEY,
							gridVersion = MetricAnalysisGrid.GRID_VERSION,
							contiguousCompactedThroughMs = partitionEndMs,
							sourceMaxObservationId = max(
								oldCheckpoint?.sourceMaxObservationId ?: 0L,
								sourceObservationHighWatermark,
							),
							sourceMaxPresenceId = database.presenceIntervalDao().maxId(MODEL_KEY),
							updatedAt = createdAt,
						),
					)
				}
			}
		}
	}

	/** Rebuilds canonical intervals for an arbitrary half-open boundary fragment. */
	suspend fun materializePresence(fromMs: Long, toMs: Long): List<PresenceInterval> =
		COMPACTION_MUTEX.withLock { materializePresenceLocked(fromMs, toMs) }

	private suspend fun materializePresenceLocked(fromMs: Long, toMs: Long): List<PresenceInterval> {
		require(toMs > fromMs)
		val intervals = buildIntervals(fromMs, toMs)
		validateIntervalMass(intervals)
		val presenceRows = intervals.map(::toPresenceInterval)
		database.withTransaction {
			val presenceDao = database.presenceIntervalDao()
			val oldOverlaps = presenceDao.getOverlapping(MODEL_KEY, fromMs, toMs)
			val preservedFragments = oldOverlaps.flatMap { old ->
				buildList {
					if (old.startTimeMs < fromMs) add(old.clippedTo(old.startTimeMs, fromMs))
					if (old.endTimeMs > toMs) add(old.clippedTo(toMs, old.endTimeMs))
				}
			}
			presenceDao.deleteOverlapping(MODEL_KEY, fromMs, toMs)
			val replacementRows = preservedFragments + presenceRows
			if (replacementRows.isNotEmpty()) {
				val inserted = presenceDao.insert(replacementRows)
				check(inserted.all { it != -1L }) { "Presence replacement conflicted with canonical rows" }
			}
			val invariantFrom = minOf(fromMs, preservedFragments.minOfOrNull { it.startTimeMs } ?: fromMs)
			val invariantTo = maxOf(toMs, preservedFragments.maxOfOrNull { it.endTimeMs } ?: toMs)
			check(presenceDao.countOverlappingPairs(MODEL_KEY, invariantFrom, invariantTo) == 0) {
				"Presence intervals must remain globally non-overlapping"
			}
		}
		return presenceRows
	}

	private suspend fun earliestSourceDay(): Long? {
		val earliest = listOfNotNull(
			database.trackerRunDao().minStartTimeMs(),
			database.locationObservationDao().minFixTimeMs(),
		).minOrNull()
		return earliest?.let(::startOfUtcDay)
	}

	private fun PresenceInterval.clippedTo(newStartMs: Long, newEndMs: Long): PresenceInterval {
		require(newStartMs >= startTimeMs && newEndMs <= endTimeMs && newEndMs > newStartMs)
		return copy(
			id = 0,
			startTimeMs = newStartMs,
			endTimeMs = newEndMs,
			startElapsedRealtimeNanos = elapsedAt(newStartMs),
			endElapsedRealtimeNanos = elapsedAt(newEndMs),
		)
	}

	private fun PresenceInterval.elapsedAt(timeMs: Long): Long? = when {
		startElapsedRealtimeNanos != null -> startElapsedRealtimeNanos +
			(timeMs - startTimeMs) * Time.MILLISECONDS_IN_NANOSECONDS
		endElapsedRealtimeNanos != null -> endElapsedRealtimeNanos -
			(endTimeMs - timeMs) * Time.MILLISECONDS_IN_NANOSECONDS
		else -> null
	}

	private suspend fun buildIntervals(fromMs: Long, toMs: Long): List<TimedInterval> {
		val contextFromMs = saturatedSubtract(fromMs, MAX_CONTINUOUS_GAP_MS)
		val contextToMs = saturatedAdd(toMs, MAX_CONTINUOUS_GAP_MS + 1L)
		val observations = database.locationObservationDao()
			.getObservedPresenceSupportBetween(contextFromMs, contextToMs)
		val contextRuns = database.trackerRunDao().getOverlapping(contextFromMs, contextToMs)
		val runs = contextRuns.filter { run ->
			run.startTimeMs < toMs && (run.endTimeMs ?: toMs) > fromMs
		}
		val result = ArrayList<TimedInterval>()
		var coveredThrough = fromMs
		for (run in runs) {
			val runStart = max(max(fromMs, run.startTimeMs), coveredThrough)
			val runEnd = min(toMs, run.endTimeMs ?: toMs)
			if (runEnd <= runStart) continue
			val runContextStart = max(run.startTimeMs, saturatedSubtract(runStart, MAX_CONTINUOUS_GAP_MS))
			val runContextEnd = min(
				run.endTimeMs ?: Long.MAX_VALUE,
				saturatedAdd(runEnd, MAX_CONTINUOUS_GAP_MS + 1L),
			)
			val usable = observations
				.asSequence()
				.filter { it.fixTimeMs >= runContextStart && it.fixTimeMs < runContextEnd }
				.filter(::isSpatiallyUsable)
				.groupBy { it.fixTimeMs }
				.values
				.map { sameTime -> sameTime.minBy { it.hAccM ?: Float.MAX_VALUE } }
				.sortedBy { it.fixTimeMs }

			val observed = observedSupport(usable, runStart, runEnd, run)

			var cursor = runStart
			for (interval in observed) {
				if (interval.startMs > cursor) {
					result += TimedInterval(
						cursor,
						interval.startMs,
						null,
						unresolvedReason(run, usable.isEmpty()),
						run,
					)
				}
				result += interval
				cursor = interval.endMs
			}
			if (cursor < runEnd) {
				result += TimedInterval(
					cursor,
					runEnd,
					null,
					unresolvedReason(run, usable.isEmpty()),
					run,
				)
			}
			coveredThrough = runEnd
		}

		// Legacy migrations can produce useful observations without tracker_run rows. Preserve a
		// bounded observed support interval for each such fix using a stable negative synthetic
		// session id. No gaps are inferred around these rows.
		val standalone = observations
			.asSequence()
			.filter(::isSpatiallyUsable)
			.filter { observation -> contextRuns.none { it.contains(observation.fixTimeMs) } }
			.groupBy { it.fixTimeMs }
			.values
			.map { sameTime -> sameTime.minBy { it.hAccM ?: Float.MAX_VALUE } }
			.sortedBy { it.fixTimeMs }
		val occupied = result.sortedBy { it.startMs }
		val standaloneIntervals = standaloneSupport(standalone, fromMs, toMs)
			.flatMap { candidate -> candidate.subtract(occupied) }
		return (result + standaloneIntervals).sortedWith(compareBy(TimedInterval::startMs, TimedInterval::endMs))
	}

	private fun observedSupport(
		observations: List<LocationObservation>,
		fromMs: Long,
		toMs: Long,
		run: TrackerRun,
	): List<TimedInterval> {
		val observed = ArrayList<TimedInterval>(observations.size)
		var lastObservedEnd = fromMs
		for (index in observations.indices) {
			val observation = observations[index]
			val (start, end) = supportBounds(observations, index)
			val clippedStart = max(max(start, fromMs), lastObservedEnd)
			val clippedEnd = min(end, toMs)
			if (clippedEnd > clippedStart) {
				observed += TimedInterval(clippedStart, clippedEnd, observation, null, run)
				lastObservedEnd = clippedEnd
			}
		}
		return observed
	}

	private fun standaloneSupport(
		observations: List<LocationObservation>,
		fromMs: Long,
		toMs: Long,
	): List<TimedInterval> = buildList {
		for (index in observations.indices) {
			val observation = observations[index]
			val (start, end) = supportBounds(observations, index)
			val clippedStart = max(start, fromMs)
			val clippedEnd = min(end, toMs)
			if (clippedEnd > clippedStart) {
				add(
					TimedInterval(
						startMs = clippedStart,
						endMs = clippedEnd,
						observation = observation,
						unresolvedReason = null,
						run = syntheticRun(observation),
					),
				)
			}
		}
	}

	private fun supportBounds(
		observations: List<LocationObservation>,
		index: Int,
	): Pair<Long, Long> {
		val observation = observations[index]
		val previous = observations.getOrNull(index - 1)
		val next = observations.getOrNull(index + 1)
		val start = if (
			previous != null && observation.fixTimeMs - previous.fixTimeMs <= MAX_CONTINUOUS_GAP_MS
		) {
			midpoint(previous.fixTimeMs, observation.fixTimeMs)
		} else {
			saturatedSubtract(observation.fixTimeMs, ISOLATED_HALF_SUPPORT_MS)
		}
		val end = if (next != null && next.fixTimeMs - observation.fixTimeMs <= MAX_CONTINUOUS_GAP_MS) {
			midpoint(observation.fixTimeMs, next.fixTimeMs)
		} else {
			saturatedAdd(observation.fixTimeMs, ISOLATED_HALF_SUPPORT_MS)
		}
		return start to end
	}

	private fun TimedInterval.subtract(occupied: List<TimedInterval>): List<TimedInterval> {
		var fragments = listOf(startMs to endMs)
		for (blocker in occupied) {
			if (blocker.endMs <= startMs) continue
			if (blocker.startMs >= endMs) break
			fragments = fragments.flatMap { (start, end) ->
				when {
					blocker.endMs <= start || blocker.startMs >= end -> listOf(start to end)
					else -> buildList {
						if (start < blocker.startMs) add(start to min(end, blocker.startMs))
						if (blocker.endMs < end) add(max(start, blocker.endMs) to end)
					}
				}
			}
			if (fragments.isEmpty()) break
		}
		return fragments.map { (start, end) -> copy(startMs = start, endMs = end) }
	}

	private fun TrackerRun.contains(timeMs: Long): Boolean =
		timeMs >= startTimeMs && timeMs < (endTimeMs ?: Long.MAX_VALUE)

	private fun syntheticRun(observation: LocationObservation): TrackerRun = TrackerRun(
		id = -observation.id,
		startTimeMs = observation.fixTimeMs,
		endTimeMs = observation.fixTimeMs,
		policy = STANDALONE_OBSERVATION_POLICY,
		policyParams = null,
		userInitiated = false,
		createdAt = observation.createdAt,
	)

	private fun isSpatiallyUsable(observation: LocationObservation): Boolean {
		val accuracy = observation.hAccM?.toDouble() ?: return false
		return observation.ingressDisposition in SUPPORTED_INGRESS_DISPOSITIONS &&
			observation.latE7 != null && observation.lonE7 != null &&
			!observation.isMock && accuracy.isFinite() && accuracy > 0.0 &&
			effectiveR90(observation) <= MAX_SUPPORTED_R90_M
	}

	/** Conservative global fallback until CAL-01 supplies a qualified calibration model. */
	private fun effectiveR90(observation: LocationObservation): Double {
		val nominalR90 = requireNotNull(observation.hAccM).toDouble() * R68_TO_R90
		val fallbackFloor = if (observation.permissionPrecision == "APPROXIMATE") {
			APPROXIMATE_PERMISSION_R90_FLOOR_M
		} else {
			UNCALIBRATED_R90_FLOOR_M
		}
		return max(nominalR90, fallbackFloor)
	}

	private fun unresolvedReason(run: TrackerRun, noUsableFix: Boolean): String = when {
		run.policy == "PASSIVE_LOW" || run.policy == "MOVEMENT_SUSPECTED" -> "NO_LOCATION_REQUESTED"
		noUsableFix -> "NO_USABLE_FIX"
		else -> "LOCATION_GAP"
	}

	private fun toPresenceInterval(interval: TimedInterval): PresenceInterval {
		val observation = interval.observation
		val r90 = observation?.let(::effectiveR90)
		val sigmaSquared = r90?.let { r ->
			val sigma = r / R90_SIGMA_FACTOR
			sigma * sigma
		}
		val startElapsed = observation?.fixElapsedRealtimeNanos?.takeIf { it > 0L }?.let {
			it + (interval.startMs - observation.fixTimeMs) * Time.MILLISECONDS_IN_NANOSECONDS
		}
		val endElapsed = observation?.fixElapsedRealtimeNanos?.takeIf { it > 0L }?.let {
			it + (interval.endMs - observation.fixTimeMs) * Time.MILLISECONDS_IN_NANOSECONDS
		}
		return PresenceInterval(
			sessionId = interval.run.id,
			modelKey = MODEL_KEY,
			estimatorVersion = ESTIMATOR_VERSION,
			calibrationVersion = CALIBRATION_VERSION,
			configHash = CONFIG_HASH,
			startTimeMs = interval.startMs,
			endTimeMs = interval.endMs,
			startElapsedRealtimeNanos = startElapsed,
			endElapsedRealtimeNanos = endElapsed,
			resolutionState = if (observation == null) STATE_UNRESOLVED else STATE_OBSERVED,
			motionState = BEHAVIOUR_UNKNOWN,
			provenance = observation?.let { "PROVIDER:${it.provider}" } ?: "TRACKER_RUN",
			unresolvedReason = interval.unresolvedReason ?: REASON_BEHAVIOUR_NOT_CLASSIFIED,
			centerLatE7 = observation?.latE7,
			centerLonE7 = observation?.lonE7,
			covarianceXxM2 = sigmaSquared,
			covarianceXyM2 = sigmaSquared?.let { 0.0 },
			covarianceYyM2 = sigmaSquared,
			effectiveR90M = r90,
			posteriorFormat = observation?.let { "ISOTROPIC_GAUSSIAN_V1" },
			posteriorPayload = null,
			sourceFirstObservationId = observation?.id,
			sourceLastObservationId = observation?.id,
			createdAt = nowMillis(),
		)
	}

	private fun validateIntervalMass(intervals: List<TimedInterval>) {
		for ((sessionId, sessionIntervals) in intervals.groupBy { it.run.id }) {
			val ordered = sessionIntervals.sortedBy { it.startMs }
			for (index in 1 until ordered.size) {
				val previousEnd = ordered[index - 1].endMs
				val nextStart = ordered[index].startMs
				check(if (sessionId > 0L) previousEnd == nextStart else previousEnd <= nextStart) {
					"Presence intervals must be contiguous for tracker runs and non-overlapping for standalone fixes"
				}
			}
			check(ordered.all { it.endMs > it.startMs }) { "Presence intervals must be non-empty" }
		}
		val globallyOrdered = intervals.sortedWith(compareBy(TimedInterval::startMs, TimedInterval::endMs))
		for (index in 1 until globallyOrdered.size) {
			check(globallyOrdered[index - 1].endMs <= globallyOrdered[index].startMs) {
				"Presence intervals must not overlap globally"
			}
		}
	}

	private fun midpoint(first: Long, second: Long): Long = first + (second - first) / 2L

	private fun saturatedAdd(value: Long, delta: Long): Long =
		if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

	private fun saturatedSubtract(value: Long, delta: Long): Long =
		if (value < Long.MIN_VALUE + delta) Long.MIN_VALUE else value - delta

	companion object {
		const val ESTIMATOR_VERSION: Int = 1
		const val CALIBRATION_VERSION: Int = 0
		const val METRIC_KIND: String = "OBSERVED_PRESENCE_V1"
		const val MODEL_KEY: String = "observed-presence-v1-calibration-v0"
		const val PIPELINE_KEY: String = "$MODEL_KEY-grid-${MetricAnalysisGrid.GRID_VERSION}"
		const val CONFIG_HASH: String =
			"r90=1.42;uncalibratedFloor=50;approximateFloor=500;gap=300000;support=120000;rho=0.5"

		private const val STATE_OBSERVED = "OBSERVED"
		private const val STATE_INFERRED = "INFERRED"
		private const val STATE_UNRESOLVED = "UNRESOLVED"
		private const val BEHAVIOUR_UNKNOWN = "UNKNOWN"
		private const val REASON_BEHAVIOUR_NOT_CLASSIFIED = "BEHAVIOUR_NOT_CLASSIFIED"
		private const val STATUS_STAGING = "STAGING"
		private const val MAX_CONTINUOUS_GAP_MS = 5 * Time.MINUTE_IN_MILLISECONDS
		private const val ISOLATED_HALF_SUPPORT_MS = Time.MINUTE_IN_MILLISECONDS
		private const val R68_TO_R90 = 1.42
		private const val R90_SIGMA_FACTOR = 2.145966
		private const val UNCALIBRATED_R90_FLOOR_M = 50.0
		private const val APPROXIMATE_PERMISSION_R90_FLOOR_M = 500.0
		private const val MAX_SUPPORTED_R90_M = 500.0
		private const val DEFAULT_MAX_PARTITIONS = 366
		private const val MAX_RECONCILIATION_RETRIES = 3
		private const val STANDALONE_OBSERVATION_POLICY = "LEGACY_STANDALONE_OBSERVATION"
		private val COMPACTION_MUTEX = Mutex()
		private val SUPPORTED_INGRESS_DISPOSITIONS = setOf(
			"DELIVERED_VALID",
			"MIGRATED_ACCEPTED",
		)

		fun startOfUtcDay(timeMs: Long): Long = Math.floorDiv(timeMs, Time.DAY_IN_MILLISECONDS) *
			Time.DAY_IN_MILLISECONDS
	}
}
