package com.adsamcik.tracker.stats.data.research

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.SourceEvidenceSnapshot
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.readSourceEvidenceSnapshot
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.research.CanonicalActivityEvidence
import com.adsamcik.tracker.stats.api.research.CanonicalLocationDecision
import com.adsamcik.tracker.stats.api.research.CanonicalLocationDecisionKind
import com.adsamcik.tracker.stats.api.research.CanonicalLocationEvidence
import com.adsamcik.tracker.stats.api.research.CanonicalSegmentationObservation
import com.adsamcik.tracker.stats.api.research.CanonicalStepEvidence
import com.adsamcik.tracker.stats.api.research.SourceTimeCapability
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production Room-to-canonical replay seam.
 *
 * It reads every input under the shared source-revision transaction, joins by immutable source
 * identity, and represents sensor-only cycles instead of dropping them.
 */
@Singleton
class RoomCanonicalEvidenceReader @Inject constructor(
	private val database: AppDatabase,
) {
	suspend fun read(
		fromMs: Long,
		toMsInclusive: Long,
	): SourceEvidenceSnapshot<List<CanonicalSegmentationObservation>> =
		database.readSourceEvidenceSnapshot {
			val observations = locationObservationDao().getBetween(
				fromMs,
				if (toMsInclusive == Long.MAX_VALUE) Long.MAX_VALUE else toMsInclusive + 1L,
			)
			val decisions = locationObservationDecisionDao()
				.getBetween(fromMs, toMsInclusive)
				.associateBy { it.observationSourceEventId }
			val accepted = buildList {
				var afterTime: Long? = null
				var afterId: Long? = null
				while (true) {
					val page = locationSampleDao().getChunkBetweenOrdered(
						fromMs,
						toMsInclusive,
						afterTime,
						afterId,
						PAGE_SIZE,
					)
					if (page.isEmpty()) break
					addAll(page)
					afterTime = page.last().timeMs
					afterId = page.last().id
				}
			}.associateBy { it.sourceEventId }
			val steps = stepIntervalDao().getAllBetween(fromMs, toMsInclusive)
			val activities = activitySnapshotDao().getAllBetween(fromMs, toMsInclusive)
			val stepsBySignal = steps.associateBy { it.sourceSignalId }
			val activitiesBySignal = activities.associateBy { it.sourceSignalId }
			val output = observations.map { raw ->
				val sourceEventId = raw.sourceEventId
				val decision = sourceEventId?.let(decisions::get)
				val curated = sourceEventId?.let(accepted::get)
				val signalId = decision?.acceptedSampleSourceSignalId ?: raw.sourceSignalId
				CanonicalSegmentationObservation(
					eventEpochMs = raw.fixTimeMs,
					acquisitionElapsedNanos = raw.fixElapsedRealtimeNanos.takeIf { it > 0L },
					receiptEpochMs = raw.receivedAtMs.takeIf { it > 0L },
					receiptElapsedNanos =
						raw.receivedElapsedRealtimeNanos.takeIf { it > 0L },
					sourceSequence = raw.id,
					clockDomainId = raw.clockDomainId ?: "unknown-location-clock",
					identityScopes = setOfNotNull(raw.sourceEventId, raw.sourceSignalId),
					capabilityFlags = buildSet {
						add("location_observation")
						if (raw.bearingDeg != null) add("bearing")
						if (raw.bearingAccuracyDeg != null) add("bearing_accuracy")
						if (raw.bootClockDomainId != null) add("boot_clock_domain")
						if (raw.isMock) add("mock_provider")
						add("ingress:${raw.ingressDisposition}")
					},
					rawLocation = raw.toCanonical(),
					curatedLocation = curated?.let {
						CanonicalLocationEvidence(
							latitudeE7 = it.latE7,
							longitudeE7 = it.lonE7,
							horizontalAccuracyM = it.hAccM,
							speedMps = it.speedMps,
							speedAccuracyMps = it.speedAccuracyMps,
							verticalAccuracyM = it.vAccM,
							sourceIdentity = it.sourceEventId,
							batchIndex = it.batchIndex,
							batchSize = it.batchSize,
							acquisitionMode = it.acquisitionMode,
							requestPriority = it.requestPriority,
							permissionPrecision = it.permissionPrecision,
							bearingDeg = it.bearingDeg,
							bearingAccuracyDeg = it.bearingAccuracyDeg,
						)
					},
					locationDecision = CanonicalLocationDecision(
						kind = when (decision?.decision) {
							"ACCEPTED" -> CanonicalLocationDecisionKind.ACCEPTED
							"REJECTED" -> CanonicalLocationDecisionKind.REJECTED
							else -> CanonicalLocationDecisionKind.MISSING
						},
						reason = decision?.reason,
						sourceEventId = sourceEventId,
						decisionEpochMs = decision?.decidedAtMs,
						algorithmVersion = decision?.decisionVersion?.let { "curated_v$it" },
					),
					stepEvidence = stepsBySignal[signalId].toCanonical(),
					activityEvidence = activitiesBySignal[signalId].toCanonical(),
				)
			}.toMutableList()
			val representedSignals = observations.mapNotNullTo(mutableSetOf()) { it.sourceSignalId }
			(steps.mapNotNull { it.sourceSignalId } + activities.mapNotNull { it.sourceSignalId })
				.distinct()
				.filterNot(representedSignals::contains)
				.forEachIndexed { index, signalId ->
					val step = stepsBySignal[signalId]
					val activity = activitiesBySignal[signalId]
					val eventMs = step?.endTimeMs ?: activity?.timeMs ?: return@forEachIndexed
					val stamp = step?.observationStamp ?: activity?.observationStamp
					output += CanonicalSegmentationObservation(
						eventEpochMs = eventMs,
						acquisitionElapsedNanos = stamp?.sourceElapsedRealtimeNanos,
						receiptEpochMs = stamp?.receivedTimeMs,
						receiptElapsedNanos = stamp?.receivedElapsedRealtimeNanos,
						sourceSequence = SENSOR_SEQUENCE_BASE + index,
						clockDomainId = stamp?.clockDomainId ?: "unknown-sensor-clock",
						identityScopes = setOf(signalId),
						capabilityFlags = setOf("sensor_only_cycle"),
						stepEvidence = step.toCanonical(),
						activityEvidence = activity.toCanonical(),
					)
				}
			output.sortedWith(compareBy(CanonicalSegmentationObservation::eventEpochMs)
				.thenBy(CanonicalSegmentationObservation::sourceSequence))
		}

	private fun LocationObservation.toCanonical() = CanonicalLocationEvidence(
		latitudeE7 = latE7,
		longitudeE7 = lonE7,
		horizontalAccuracyM = hAccM,
		speedMps = speedMps,
		speedAccuracyMps = speedAccuracyMps,
		verticalAccuracyM = vAccM,
		sourceIdentity = sourceEventId,
		batchIndex = batchIndex,
		batchSize = batchSize,
		acquisitionMode = acquisitionMode,
		requestPriority = requestPriority,
		permissionPrecision = permissionPrecision,
		bearingDeg = bearingDeg,
		bearingAccuracyDeg = bearingAccuracyDeg,
	)

	private fun StepInterval?.toCanonical(): CanonicalStepEvidence? = this?.let {
		CanonicalStepEvidence(
			delta = stepCount,
			sourceFirstSequence = observationStamp.sourceFirstSequence,
			sourceLastSequence = observationStamp.sourceSequence,
			totalSinceReset = sensorValueEnd.toLong(),
			reset = sensorReset,
		)
	}

	private fun ActivitySnapshot?.toCanonical(): CanonicalActivityEvidence? = this?.let {
		CanonicalActivityEvidence(
			type = DetectedActivityType.entries.getOrNull(activityType)
				?: DetectedActivityType.UNKNOWN,
			confidence = confidence,
			sourceEpochMs = observationStamp.sourceTimeMs,
			fresh = true,
			sourceTimeCapability = if (
				observationStamp.sourceTimeMs != null ||
				observationStamp.sourceElapsedRealtimeNanos != null
			) {
				SourceTimeCapability.PRESENT
			} else {
				SourceTimeCapability.UNAVAILABLE
			},
		)
	}

	private companion object {
		const val PAGE_SIZE = 1_000
		const val SENSOR_SEQUENCE_BASE = 4_000_000_000_000_000_000L
	}
}
