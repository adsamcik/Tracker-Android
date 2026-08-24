package com.adsamcik.tracker.tracker.reconstruction

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.CompletedTrackerSession
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.data.TrajectoryReconstructionRunEntity
import com.adsamcik.tracker.shared.base.database.data.TrajectorySourceLinkEntity
import com.adsamcik.tracker.shared.base.database.data.TrajectoryStateEntity
import com.adsamcik.tracker.shared.base.database.data.VisitIntervalEntity
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.reconstruction.LocationReconstructionObservation
import com.adsamcik.tracker.stats.api.reconstruction.TrajectoryStateEstimate
import com.adsamcik.tracker.stats.engine.reconstruction.LocationQualityAssessor
import com.adsamcik.tracker.stats.engine.reconstruction.DurationVisitDetector
import com.adsamcik.tracker.stats.engine.reconstruction.RobustTrajectoryReconstructor
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class HistoricalReconstructionOutcome(
	val runId: String,
	val stateCount: Int,
	val sourceRevision: Long,
)

/**
 * Reads one coherent raw-evidence snapshot, reconstructs it off the database thread, and publishes
 * an immutable derived run only if the source revision is still current.
 */
@Singleton
class HistoricalTrajectoryReconstructionRunner @Inject constructor(
	private val database: AppDatabase,
) {
	private val assessor = LocationQualityAssessor()
	private val reconstructor = RobustTrajectoryReconstructor(qualityAssessor = assessor)
	private val visitDetector = DurationVisitDetector()

	val algorithmVersion: String
		get() = reconstructor.algorithmVersion

	val configurationVersion: String
		get() = reconstructor.configurationVersion

	suspend fun reconstruct(
		session: CompletedTrackerSession,
		verifyCollectedDataAccess: () -> Unit = {},
	): HistoricalReconstructionOutcome {
		require(session.endElapsedRealtimeNanos >= session.startElapsedRealtimeNanos)
		verifyCollectedDataAccess()
		val sourceSnapshot = database.withTransaction {
			verifyCollectedDataAccess()
			try {
				val stateDao = database.sourceEvidenceStateDao()
				stateDao.ensure()
				val revision = requireNotNull(stateDao.get()).revision
				val observations = database.locationObservationDao().getInClockDomain(
					clockDomainId = session.clockDomainId,
					fromElapsedRealtimeNanos = session.startElapsedRealtimeNanos,
					toElapsedRealtimeNanos = session.endElapsedRealtimeNanos,
				)
				val steps = database.stepIntervalDao().getAllInClockDomain(
					clockDomainId = session.clockDomainId,
					fromElapsedRealtimeNanos = session.startElapsedRealtimeNanos,
					toElapsedRealtimeNanos = session.endElapsedRealtimeNanos,
				)
				val activities = database.activitySnapshotDao().getAllInClockDomain(
					clockDomainId = session.clockDomainId,
					fromElapsedRealtimeNanos = session.startElapsedRealtimeNanos,
					toElapsedRealtimeNanos = session.endElapsedRealtimeNanos,
				)
				HistoricalSourceSnapshot(
					revision = revision,
					observations = observations,
					steps = steps,
					activities = activities,
				)
			} finally {
				verifyCollectedDataAccess()
			}
		}
		val sourceRevision = sourceSnapshot.revision
		val rawObservations = sourceSnapshot.observations
		val stationaryEvidenceBySource = rawObservations.associate { observation ->
			val sourceId = observation.sourceId()
			sourceId to sourceSnapshot.stationaryEvidenceFor(observation)
		}
		val inputs = rawObservations.mapNotNull { value ->
			val latitude = value.latE7 ?: return@mapNotNull null
			val longitude = value.lonE7 ?: return@mapNotNull null
			val sourceId = value.sourceId()
			val stationaryEvidence = stationaryEvidenceBySource[sourceId]
			LocationReconstructionObservation(
				sourceId = sourceId,
				epochMs = value.fixTimeMs,
				elapsedRealtimeNanos = value.fixElapsedRealtimeNanos.takeIf { it > 0L },
				clockDomainId = value.clockDomainId,
				bootClockDomainId = value.bootClockDomainId,
				latitudeE7 = latitude,
				longitudeE7 = longitude,
				horizontalAccuracyM = value.hAccM?.toDouble(),
				platformSpeedMps = value.speedMps?.toDouble(),
				platformSpeedAccuracyMps = value.speedAccuracyMps?.toDouble(),
				bearingDeg = value.bearingDeg?.toDouble(),
				bearingAccuracyDeg = value.bearingAccuracyDeg?.toDouble(),
				provider = value.provider,
				acquisitionMode = value.acquisitionMode,
				requestPriority = value.requestPriority,
				permissionPrecision = value.permissionPrecision,
				batchIndex = value.batchIndex,
				batchSize = value.batchSize.coerceAtLeast(1),
				callbackId = value.callbackId,
				sourceAgeMs = value.deliveryAgeMs,
				timeUncertaintyMs = null,
				isMock = value.isMock,
				ingressDisposition = value.ingressDisposition,
				stepDelta = stationaryEvidence?.step?.stepCount,
				activity = stationaryEvidence?.activity?.activityName,
			)
		}
		val result = reconstructor.reconstruct(inputs)
		val weightedBySource = assessor.assessAll(inputs).associateBy { it.observation.sourceId }
		val approximate = inputs.any { it.permissionPrecision == "APPROXIMATE" }
		val permissionBranch = if (approximate) APPROXIMATE_BRANCH else PRECISE_BRANCH
		val runId = UUID.randomUUID().toString()
		val now = System.currentTimeMillis()
		val sourceWallTimes = buildList {
			add(session.startWallTimeMs)
			add(session.endWallTimeMs)
			rawObservations.forEach { add(it.fixTimeMs) }
			sourceSnapshot.steps.forEach {
				add(it.startTimeMs)
				add(it.endTimeMs)
			}
			sourceSnapshot.activities.forEach { add(it.timeMs) }
		}
		val fromMs = sourceWallTimes.min()
		val toMs = sourceWallTimes.max()
		val sourceBootClockDomainId = buildList<String> {
			rawObservations.mapNotNullTo(this, LocationObservation::bootClockDomainId)
			sourceSnapshot.steps.mapNotNullTo(this) {
				it.observationStamp.bootClockDomainId
			}
			sourceSnapshot.activities.mapNotNullTo(this) {
				it.observationStamp.bootClockDomainId
			}
		}
			.distinct()
			.singleOrNull()

		verifyCollectedDataAccess()
		database.withTransaction {
			verifyCollectedDataAccess()
			try {
				val currentRevision = database.sourceEvidenceStateDao().get()?.revision
				check(currentRevision == sourceRevision) {
					"Source evidence changed during historical reconstruction"
				}
				val reconstructionDao = database.trajectoryReconstructionDao()
				val superseded = reconstructionDao.latestCompletedForClockDomain(session.clockDomainId)
				reconstructionDao.insertRun(
				TrajectoryReconstructionRunEntity(
					runId = runId,
					sourceStartMs = fromMs,
					sourceEndMs = toMs,
					sourceClockDomainId = session.clockDomainId,
					sourceBootClockDomainId = sourceBootClockDomainId,
					sourceStartElapsedRealtimeNanos = session.startElapsedRealtimeNanos,
					sourceEndElapsedRealtimeNanos = session.endElapsedRealtimeNanos,
					sourceRevision = sourceRevision,
					algorithmVersion = result.algorithmVersion,
					configurationVersion = result.configurationVersion,
					permissionBranch = permissionBranch,
					status = STATUS_RUNNING,
					createdAtMs = now,
					supersedesRunId = superseded?.runId,
				),
				)
				val filtered = result.filtered.mapIndexed { index, state ->
				state.toEntity(runId, index, approximate)
				}
				val smoothed = result.smoothed.mapIndexed { index, state ->
				state.toEntity(runId, index, approximate)
				}
				reconstructionDao.insertStates(filtered + smoothed)
				val stateIndexBySource = result.smoothed
				.mapIndexed { index, state -> state.sourceId to index }
				.toMap()
				val links = rawObservations.map { observation ->
				val sourceId = observation.sourceId()
				val assessed = weightedBySource[sourceId]
				val stationaryEvidence = stationaryEvidenceBySource[sourceId]
				TrajectorySourceLinkEntity(
					runId = runId,
					stateIndex = stateIndexBySource[sourceId] ?: NO_DERIVED_STATE_INDEX,
					observationId = observation.id,
					sourceEventId = observation.sourceEventId,
					sourceSignalId = observation.sourceSignalId,
					stepIntervalId = stationaryEvidence?.step?.id,
					activitySnapshotId = stationaryEvidence?.activity?.id,
					weight = assessed?.informationWeight ?: 0.0,
					health = assessed?.health?.name ?: "REJECTED",
					reasonCodes = if (assessed == null) {
						"INVALID_INGRESS"
					} else {
						assessed.reasons
							.map { it.name }
							.sorted()
							.joinToString(",")
							.takeIf(String::isNotEmpty)
					},
				)
				}
				if (links.isNotEmpty()) reconstructionDao.insertSourceLinks(links)
				val visits = visitDetector.detect(result.smoothed).map { visit ->
				VisitIntervalEntity(
					runId = runId,
					startTimeMs = visit.startTimeMs,
					endTimeMs = visit.endTimeMs,
					startElapsedRealtimeNanos = visit.startElapsedRealtimeNanos,
					endElapsedRealtimeNanos = visit.endElapsedRealtimeNanos,
					clockDomainId = visit.clockDomainId,
					bootClockDomainId = visit.bootClockDomainId,
					arrivalUncertaintyMs = visit.arrivalUncertaintyMs,
					departureUncertaintyMs = visit.departureUncertaintyMs,
					centroidLatE7 = if (approximate) {
						quantizeE7(visit.centroidLatE7, APPROXIMATE_E7_GRID)
					} else {
						visit.centroidLatE7
					},
					centroidLonE7 = if (approximate) {
						quantizeE7(visit.centroidLonE7, APPROXIMATE_E7_GRID)
					} else {
						visit.centroidLonE7
					},
					covarianceEastEastM2 = maxOf(
						visit.covarianceEastEastM2,
						if (approximate) APPROXIMATE_MINIMUM_VARIANCE_M2 else 0.0,
					),
					covarianceEastNorthM2 =
						if (approximate) 0.0 else visit.covarianceEastNorthM2,
					covarianceNorthNorthM2 = maxOf(
						visit.covarianceNorthNorthM2,
						if (approximate) APPROXIMATE_MINIMUM_VARIANCE_M2 else 0.0,
					),
					probability = visit.probability,
				)
				}
				if (visits.isNotEmpty()) reconstructionDao.insertVisitIntervals(visits)
				check(reconstructionDao.completeRun(runId, STATUS_COMPLETED, now) == 1)
			} finally {
				verifyCollectedDataAccess()
			}
		}
		return HistoricalReconstructionOutcome(
			runId = runId,
			stateCount = result.smoothed.size,
			sourceRevision = sourceRevision,
		)
	}

	private fun TrajectoryStateEstimate.toEntity(
		runId: String,
		index: Int,
		approximate: Boolean,
	): TrajectoryStateEntity {
		val coordinateGrid = if (approximate) APPROXIMATE_E7_GRID else 1
		val latitude = quantizeE7(latitudeE7, coordinateGrid)
		val longitude = quantizeE7(longitudeE7, coordinateGrid)
		val minimumVariance = if (approximate) APPROXIMATE_MINIMUM_VARIANCE_M2 else 0.0
		return TrajectoryStateEntity(
			runId = runId,
			stateIndex = index,
			estimateKind = kind.name,
			sourceEventId = sourceId,
			timeMs = epochMs,
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			clockDomainId = clockDomainId,
			bootClockDomainId = bootClockDomainId,
			latE7 = latitude,
			lonE7 = longitude,
			velocityEastMps = if (approximate) 0.0 else velocityEastMps,
			velocityNorthMps = if (approximate) 0.0 else velocityNorthMps,
			covarianceEastEastM2 = maxOf(positionCovarianceEastEastM2, minimumVariance),
			covarianceEastNorthM2 = if (approximate) 0.0 else positionCovarianceEastNorthM2,
			covarianceNorthNorthM2 = maxOf(positionCovarianceNorthNorthM2, minimumVariance),
			stationaryProbability = stationaryProbability,
			observationWeight = observationWeight,
			observationHealth = observationHealth.name,
		)
	}

	private fun quantizeE7(value: Int, grid: Int): Int =
		((value.toLong() + if (value >= 0) grid / 2 else -grid / 2) / grid * grid)
			.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
			.toInt()

	private fun LocationObservation.sourceId(): String =
		sourceEventId ?: "location-observation:$id"

	private fun HistoricalSourceSnapshot.stationaryEvidenceFor(
		observation: LocationObservation,
	): StationaryEvidence {
		val fixElapsedRealtimeNanos = observation.fixElapsedRealtimeNanos
		val bootClockDomainId = observation.bootClockDomainId
		val overlappingStep = steps
			.asSequence()
			.filter { it.matchesBootDomain(bootClockDomainId) }
			.filter { step ->
				val start = step.startElapsedRealtimeNanos() ?: return@filter false
				val end = step.endElapsedRealtimeNanos() ?: return@filter false
				fixElapsedRealtimeNanos in start..end
			}
			.maxByOrNull { it.endElapsedRealtimeNanos() ?: Long.MIN_VALUE }
		val recentStep = overlappingStep ?: steps
			.asSequence()
			.filter { it.matchesBootDomain(bootClockDomainId) }
			.filter { step ->
				val end = step.endElapsedRealtimeNanos() ?: return@filter false
				end <= fixElapsedRealtimeNanos &&
					fixElapsedRealtimeNanos - end <= STEP_CONTEXT_MAX_AGE_NANOS
			}
			.maxByOrNull { it.endElapsedRealtimeNanos() ?: Long.MIN_VALUE }
		val recentActivity = activities
			.asSequence()
			.filter { it.matchesBootDomain(bootClockDomainId) }
			.mapNotNull { activity ->
				val activityName = DetectedActivityType.entries
					.getOrNull(activity.activityType)
					?.name
					?.lowercase()
					?: return@mapNotNull null
				val elapsedRealtimeNanos =
					activity.observationStamp.sourceElapsedRealtimeNanos
						?: activity.observationStamp.receivedElapsedRealtimeNanos
						?: return@mapNotNull null
				ActivityEvidence(
					id = activity.id,
					elapsedRealtimeNanos = elapsedRealtimeNanos,
					activityName = activityName,
				)
			}
			.filter { activity ->
				activity.elapsedRealtimeNanos <= fixElapsedRealtimeNanos &&
					fixElapsedRealtimeNanos - activity.elapsedRealtimeNanos <=
					ACTIVITY_CONTEXT_MAX_AGE_NANOS
			}
			.maxByOrNull(ActivityEvidence::elapsedRealtimeNanos)
		return StationaryEvidence(
			step = recentStep?.let { StepEvidence(it.id, it.stepCount) },
			activity = recentActivity,
		)
	}

	private fun StepInterval.startElapsedRealtimeNanos(): Long? =
		observationStamp.sourceFirstElapsedRealtimeNanos
			?: observationStamp.sourceElapsedRealtimeNanos
			?: observationStamp.receivedElapsedRealtimeNanos

	private fun StepInterval.endElapsedRealtimeNanos(): Long? =
		observationStamp.sourceElapsedRealtimeNanos
			?: observationStamp.receivedElapsedRealtimeNanos

	private fun StepInterval.matchesBootDomain(bootClockDomainId: String?): Boolean =
		bootClockDomainId == null ||
			observationStamp.bootClockDomainId == null ||
			observationStamp.bootClockDomainId == bootClockDomainId

	private fun ActivitySnapshot.matchesBootDomain(bootClockDomainId: String?): Boolean =
		bootClockDomainId == null ||
			observationStamp.bootClockDomainId == null ||
			observationStamp.bootClockDomainId == bootClockDomainId

	private data class HistoricalSourceSnapshot(
		val revision: Long,
		val observations: List<LocationObservation>,
		val steps: List<StepInterval>,
		val activities: List<ActivitySnapshot>,
	)

	private data class StationaryEvidence(
		val step: StepEvidence?,
		val activity: ActivityEvidence?,
	)

	private data class StepEvidence(
		val id: Long,
		val stepCount: Int,
	)

	private data class ActivityEvidence(
		val id: Long,
		val elapsedRealtimeNanos: Long,
		val activityName: String,
	)

	private companion object {
		const val STATUS_RUNNING = "RUNNING"
		const val STATUS_COMPLETED = "COMPLETED"
		const val PRECISE_BRANCH = "PRECISE"
		const val APPROXIMATE_BRANCH = "APPROXIMATE_REGION"
		const val APPROXIMATE_E7_GRID = 100_000
		const val APPROXIMATE_MINIMUM_VARIANCE_M2 = 500.0 * 500.0
		const val STEP_CONTEXT_MAX_AGE_NANOS = 2L * 60L * 1_000_000_000L
		const val ACTIVITY_CONTEXT_MAX_AGE_NANOS = 10L * 60L * 1_000_000_000L
		const val NO_DERIVED_STATE_INDEX = -1
	}
}
