@file:Suppress("TooManyFunctions")

package com.adsamcik.tracker.tracker.source.location

import android.location.Location
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceEventWalPayloadPreflightRow
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.LocationObservationDecision
import com.adsamcik.tracker.shared.base.database.data.LocationSample as StoredLocationSample
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionJoinStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.shared.base.data.LocationFixMetadata
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.signal.LocationDecision
import com.adsamcik.tracker.stats.api.signal.LocationDecisionSignal
import com.adsamcik.tracker.stats.api.signal.PolicySignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.collection.LocationCanonicalCurationContext
import com.adsamcik.tracker.tracker.data.collection.LocationCanonicalCurationPoint
import com.adsamcik.tracker.tracker.data.collection.LocationCanonicalCurationState
import com.adsamcik.tracker.tracker.altitude.AltitudeFusionState
import com.adsamcik.tracker.tracker.altitude.AltitudeKalmanState
import com.adsamcik.tracker.tracker.altitude.AltitudeProcessorState
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object ProtectedLocationCanonicalSignalIdentity {
	private const val RAW_PREFIX = "protected-location-observation:"
	private const val CANONICAL_PREFIX = "protected-location-canonical:"
	private const val LEGACY_RAW_PREFIX = "location-observation:"

	fun rawObservation(sourceEventId: String): String = "$RAW_PREFIX$sourceEventId"
	fun canonicalProduct(sourceEventId: String): String = "$CANONICAL_PREFIX$sourceEventId"

	fun rawEventId(sourceSignalId: String?): String? =
		sourceSignalId?.removePrefixOrNull(RAW_PREFIX)

	fun canonicalEventId(sourceSignalId: String?): String? =
		sourceSignalId?.removePrefixOrNull(CANONICAL_PREFIX)

	fun isCanonicalRawIdentity(sourceSignalId: String?, sourceEventId: String): Boolean =
		sourceSignalId == rawObservation(sourceEventId) ||
			sourceSignalId == "$LEGACY_RAW_PREFIX$sourceEventId"

	private fun String.removePrefixOrNull(prefix: String): String? =
		takeIf { startsWith(prefix) }
			?.removePrefix(prefix)
			?.takeIf(String::isNotBlank)
}

internal enum class ProtectedLocationCanonicalInactiveReason {
	NO_ACTIVE_LANE,
	AMBIGUOUS_ACTIVE_LANE,
	BINDING_NOT_EXECUTABLE,
	LANE_NOT_CANONICAL,
	GLOBAL_WRITER_CONFLICT,
	DESTINATION_NOT_OWNED,
}

internal sealed interface ProtectedLocationCanonicalDrainResult {
	val lastCommittedOrdinal: Long

	data class Complete(
		override val lastCommittedOrdinal: Long,
		val observationsCommitted: Int,
		val acceptedSamplesCommitted: Int,
		val rejectedObservationsCommitted: Int,
		val lifecycleSettled: Int,
	) : ProtectedLocationCanonicalDrainResult

	data class Deferred(
		override val lastCommittedOrdinal: Long,
		val deferredOrdinal: Long?,
		val reason: String,
	) : ProtectedLocationCanonicalDrainResult

	data class Inactive(
		override val lastCommittedOrdinal: Long,
		val reason: ProtectedLocationCanonicalInactiveReason,
	) : ProtectedLocationCanonicalDrainResult

	data class Failed(
		override val lastCommittedOrdinal: Long,
		val failedOrdinal: Long?,
		val failureCode: String,
		val terminal: Boolean,
	) : ProtectedLocationCanonicalDrainResult

	data class AuthorityChanged(
		override val lastCommittedOrdinal: Long,
		val reason: String,
	) : ProtectedLocationCanonicalDrainResult
}

internal sealed interface ProtectedLocationCanonicalWriteResult {
	data object Committed : ProtectedLocationCanonicalWriteResult
	data class Deferred(val reason: String) : ProtectedLocationCanonicalWriteResult
	data class Inactive(val reason: String) : ProtectedLocationCanonicalWriteResult
	data class Failed(
		val failureCode: String,
		val terminal: Boolean,
	) : ProtectedLocationCanonicalWriteResult

	data class AuthorityChanged(val reason: String) : ProtectedLocationCanonicalWriteResult
}

/**
 * Consumer for the established canonical Location pipeline.
 *
 * Implementations must either run under the live orchestrator's serializer or prove that pipeline
 * inactive before opening an isolated recovery pipeline.
 */
internal fun interface ProtectedLocationCanonicalWriter {
	suspend fun write(
		command: LocationCapturedFactCommand,
		acquisitionMetadata: LocationWalAcquisitionMetadata,
	): ProtectedLocationCanonicalWriteResult
}

/**
 * Selects the live serialized writer when available and otherwise uses the quiesced recovery
 * writer. The parent runtime owns the live writer reference and the lifecycle ordering.
 */
internal class ProtectedLocationCanonicalWriterChain(
	private val liveWriter: () -> ProtectedLocationCanonicalWriter?,
	private val offlineWriter: ProtectedLocationCanonicalWriter,
) : ProtectedLocationCanonicalWriter {
	override suspend fun write(
		command: LocationCapturedFactCommand,
		acquisitionMetadata: LocationWalAcquisitionMetadata,
	): ProtectedLocationCanonicalWriteResult {
		val liveResult = liveWriter()?.write(command, acquisitionMetadata)
		if (liveResult != null && liveResult !is ProtectedLocationCanonicalWriteResult.Inactive) {
			return liveResult
		}
		return offlineWriter.write(command, acquisitionMetadata)
	}
}

/**
 * Dormant Location WAL handoff to the existing serialized canonical writer.
 *
 * The lane owns no Location destination mutation. It advances only after the canonical observation
 * and terminal curation receipt are source-qualified, or after monotonic deletion/retention
 * authority proves that no product may be resurrected.
 */
@Suppress("LargeClass", "TooManyFunctions")
internal class ProtectedLocationCanonicalHandoff(
	private val database: AppDatabase,
	private val qualifier: ProtectedLocationWalQualifier,
	private val canonicalWriter: ProtectedLocationCanonicalWriter,
	applicationScope: CoroutineScope? = null,
) {
	private val mutex = Mutex()
	private val drainSignals = Channel<Unit>(Channel.CONFLATED)

	init {
		applicationScope?.launch {
			for (ignored in drainSignals) {
				try {
					drainHighWater()
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (_: Exception) {
					// Durable WAL, failure state, and the exact lane cursor own the next retry.
				}
			}
		}
	}

	/** Conflated process hint only; no polling or provider activation is performed here. */
	fun requestDrain() {
		drainSignals.trySend(Unit)
	}

	/** Drains one finite global WAL/deletion high-water snapshot. */
	suspend fun drainHighWater(): ProtectedLocationCanonicalDrainResult = mutex.withLock {
		val resolution = database.withTransaction { resolveLane() }
		val lane = when (resolution) {
			is ProtectedLocationLaneResolution.Active -> resolution.lane
			is ProtectedLocationLaneResolution.Inactive -> {
				return@withLock ProtectedLocationCanonicalDrainResult.Inactive(
					lastCommittedOrdinal = resolution.lastCommittedOrdinal,
					reason = resolution.reason,
				)
			}
		}
		val snapshot = try {
			database.withTransaction {
				val exact = requireExactLane(lane, lane.contiguousAdmissionOrdinal)
				val evidence = database.sourceEvidenceStateDao().get()
					?: throw ProtectedLocationAuthorityChangedException(
						"SOURCE_EVIDENCE_STATE_MISSING",
					)
				val target = minOf(
					maxOf(
						database.sourceEventWalDao().admissionAllocatorHighWater(),
						evidence.deletedSourceEventHighWaterOrdinal,
					),
					exact.captureAdmissionCutoffOrdinal ?: Long.MAX_VALUE,
				)
				ProtectedLocationGlobalDrainSnapshot(
					targetAdmissionOrdinal = target,
					deletedSourceEventHighWaterOrdinal =
						evidence.deletedSourceEventHighWaterOrdinal,
					firstTerminalFailureOrdinal = database.sourceProjectionStateDao()
						.firstTerminalFailureAfterThrough(
							WRITER_ID,
							WRITER_VERSION,
							lane.contiguousAdmissionOrdinal,
							target,
						)?.admissionOrdinal,
				)
			}
		} catch (changed: ProtectedLocationAuthorityChangedException) {
			return@withLock ProtectedLocationCanonicalDrainResult.AuthorityChanged(
				lane.contiguousAdmissionOrdinal,
				changed.reason,
			)
		}
		val continuity = database.withTransaction {
			verifyGlobalLocationContinuity(
				afterOrdinal = lane.contiguousAdmissionOrdinal,
				throughOrdinal = snapshot.targetAdmissionOrdinal,
				deletedSourceEventHighWaterOrdinal =
					snapshot.deletedSourceEventHighWaterOrdinal,
				firstTerminalFailureOrdinal = snapshot.firstTerminalFailureOrdinal,
			)
		}
		continuity.failure?.let { return@withLock it }
		val provenTarget = continuity.classifiedThroughOrdinal
		val result = drainLocked(
			lane,
			provenTarget,
			expectedLogicalTrackingId = null,
			expectedServiceRunId = null,
		)
		if (result is ProtectedLocationCanonicalDrainResult.Complete &&
			provenTarget < snapshot.targetAdmissionOrdinal
		) {
			ProtectedLocationCanonicalDrainResult.Deferred(
				lastCommittedOrdinal = result.lastCommittedOrdinal,
				deferredOrdinal = runCatching {
					Math.addExact(provenTarget, 1L)
				}.getOrNull(),
				reason = "LOCATION_DRAIN_TRAILING_RANGE_UNPROVEN",
			)
		} else {
			result
		}
	}

	private suspend fun verifyGlobalLocationContinuity(
		afterOrdinal: Long,
		throughOrdinal: Long,
		deletedSourceEventHighWaterOrdinal: Long,
		firstTerminalFailureOrdinal: Long?,
	): ProtectedLocationGlobalContinuityProof {
		var pageAfter = afterOrdinal
		var classifiedThroughOrdinal = maxOf(
			afterOrdinal,
			minOf(throughOrdinal, deletedSourceEventHighWaterOrdinal),
		)
		var unclassifiedGapOrdinal: Long? = null
		val expectedByRegistration =
			mutableMapOf<ProtectedLocationRegistrationIdentity, Long>()
		while (pageAfter < throughOrdinal) {
			val page = database.sourceEventWalDao().continuityEventsAfterThrough(
				afterOrdinal = pageAfter,
				throughOrdinal = throughOrdinal,
				limit = CONTINUITY_PAGE_SIZE,
			)
			if (page.isEmpty()) break
			page.forEach { row ->
				if (row.admissionOrdinal <= classifiedThroughOrdinal) return@forEach
				val gapOrdinal = unclassifiedGapOrdinal
				?: runCatching {
					Math.addExact(classifiedThroughOrdinal, 1L)
				}.getOrNull()
				?: return ProtectedLocationGlobalContinuityProof(
					classifiedThroughOrdinal,
					ProtectedLocationCanonicalDrainResult.Failed(
						afterOrdinal,
						row.admissionOrdinal,
						"LOCATION_DRAIN_ORDINAL_OVERFLOW",
						terminal = true,
					),
				)
				if (unclassifiedGapOrdinal != null) {
					if (row.sourceKind == SOURCE_LOCATION) {
						return ProtectedLocationGlobalContinuityProof(
							classifiedThroughOrdinal,
							ProtectedLocationCanonicalDrainResult.Failed(
								afterOrdinal,
								row.admissionOrdinal,
								"LOCATION_DRAIN_INTERIOR_SEQUENCE_GAP",
								terminal = true,
							),
						)
					}
					return@forEach
				}
				if (row.admissionOrdinal != gapOrdinal) {
					if (firstTerminalFailureOrdinal == gapOrdinal) {
						return ProtectedLocationGlobalContinuityProof(gapOrdinal)
					}
					unclassifiedGapOrdinal = gapOrdinal
					if (row.sourceKind == SOURCE_LOCATION) {
						return ProtectedLocationGlobalContinuityProof(
							classifiedThroughOrdinal,
							ProtectedLocationCanonicalDrainResult.Failed(
								afterOrdinal,
								row.admissionOrdinal,
								"LOCATION_DRAIN_INTERIOR_SEQUENCE_GAP",
								terminal = true,
							),
						)
					}
					return@forEach
				}
				classifiedThroughOrdinal = row.admissionOrdinal
				if (row.sourceKind != SOURCE_LOCATION) return@forEach
				val logicalTrackingId = row.logicalTrackingId
					?: return ProtectedLocationGlobalContinuityProof(
						classifiedThroughOrdinal,
						ProtectedLocationCanonicalDrainResult.Failed(
							afterOrdinal,
							row.admissionOrdinal,
							"LOCATION_DRAIN_CONTINUITY_AUTHORITY_MISSING",
							terminal = true,
						),
					)
				val serviceRunId = row.serviceRunId
					?: return ProtectedLocationGlobalContinuityProof(
						classifiedThroughOrdinal,
						ProtectedLocationCanonicalDrainResult.Failed(
							afterOrdinal,
							row.admissionOrdinal,
							"LOCATION_DRAIN_CONTINUITY_AUTHORITY_MISSING",
							terminal = true,
						),
					)
				val registration = ProtectedLocationRegistrationIdentity(
					row.sourceInstanceId,
					row.registrationGeneration,
					logicalTrackingId,
					serviceRunId,
				)
				val previous = expectedByRegistration[registration]
					?: loadLocationContinuityAnchor(registration)?.sourceSequence
					?: database.sourceEventWalDao().latestContinuityEventAtOrBefore(
						sourceKind = SOURCE_LOCATION,
						sourceInstanceId = row.sourceInstanceId,
						registrationGeneration = row.registrationGeneration,
						throughOrdinal = afterOrdinal,
					)?.sourceSequence
					?: 0L
				val expected = runCatching { Math.addExact(previous, 1L) }.getOrNull()
					?: return ProtectedLocationGlobalContinuityProof(
						classifiedThroughOrdinal,
						ProtectedLocationCanonicalDrainResult.Failed(
							afterOrdinal,
							row.admissionOrdinal,
							"LOCATION_DRAIN_SOURCE_SEQUENCE_OVERFLOW",
							terminal = true,
						),
					)
				if (row.sourceSequence != expected) {
					return ProtectedLocationGlobalContinuityProof(
						classifiedThroughOrdinal,
						ProtectedLocationCanonicalDrainResult.Failed(
							afterOrdinal,
							row.admissionOrdinal,
							"LOCATION_DRAIN_INTERIOR_SEQUENCE_GAP",
							terminal = true,
						),
					)
				}
				expectedByRegistration[registration] = row.sourceSequence
			}
			pageAfter = page.last().admissionOrdinal
		}
		val nextOrdinal = runCatching {
			Math.addExact(classifiedThroughOrdinal, 1L)
		}.getOrNull()
		if (unclassifiedGapOrdinal == null &&
			nextOrdinal != null &&
			firstTerminalFailureOrdinal == nextOrdinal
		) {
			classifiedThroughOrdinal = nextOrdinal
		}
		return ProtectedLocationGlobalContinuityProof(classifiedThroughOrdinal)
	}

	/**
	 * Drains through an acknowledged source high-water for one finite session.
	 *
	 * Earlier Location events cannot be skipped merely because they belong to another run. A
	 * complete existing receipt may advance them; otherwise the unavailable historical canonical
	 * session is reported as deferred.
	 */
	suspend fun drainThrough(
		logicalTrackingId: String,
		serviceRunId: String,
		throughAdmissionOrdinal: Long,
	): ProtectedLocationCanonicalDrainResult = mutex.withLock {
		require(logicalTrackingId.isNotBlank())
		require(serviceRunId.isNotBlank())
		require(throughAdmissionOrdinal >= 0L)
		val resolution = database.withTransaction { resolveLane() }
		val lane = when (resolution) {
			is ProtectedLocationLaneResolution.Active -> resolution.lane
			is ProtectedLocationLaneResolution.Inactive -> {
				return@withLock ProtectedLocationCanonicalDrainResult.Inactive(
					lastCommittedOrdinal = resolution.lastCommittedOrdinal,
					reason = resolution.reason,
				)
			}
		}
		val cutoff = lane.captureAdmissionCutoffOrdinal
		if (cutoff != null && throughAdmissionOrdinal > cutoff) {
			return@withLock ProtectedLocationCanonicalDrainResult.AuthorityChanged(
				lane.contiguousAdmissionOrdinal,
				"LOCATION_DRAIN_ENDPOINT_AFTER_CAPTURE_CUTOFF",
			)
		}
		val target = throughAdmissionOrdinal
		if (target > lane.contiguousAdmissionOrdinal) {
			val endpointFailure = database.withTransaction {
				verifyExplicitDrainEndpoint(
					targetAdmissionOrdinal = target,
					logicalTrackingId = logicalTrackingId,
					serviceRunId = serviceRunId,
					lastCommittedOrdinal = lane.contiguousAdmissionOrdinal,
				)
			}
			if (endpointFailure != null) return@withLock endpointFailure
		}
		drainLocked(lane, target, logicalTrackingId, serviceRunId)
	}

	private suspend fun verifyExplicitDrainEndpoint(
		targetAdmissionOrdinal: Long,
		logicalTrackingId: String,
		serviceRunId: String,
		lastCommittedOrdinal: Long,
	): ProtectedLocationCanonicalDrainResult? {
		val allocatorHighWater = database.sourceEventWalDao().admissionAllocatorHighWater()
		if (targetAdmissionOrdinal > allocatorHighWater) {
			return ProtectedLocationCanonicalDrainResult.Deferred(
				lastCommittedOrdinal,
				targetAdmissionOrdinal,
				"LOCATION_DRAIN_ENDPOINT_ABOVE_ALLOCATOR_HIGH_WATER",
			)
		}
		val run = database.sourceSessionDao().serviceRun(serviceRunId)
		val session = database.sourceSessionDao().session(logicalTrackingId)
		if (run?.logicalTrackingId != logicalTrackingId || session == null) {
			return ProtectedLocationCanonicalDrainResult.AuthorityChanged(
				lastCommittedOrdinal,
				"LOCATION_DRAIN_ENDPOINT_SESSION_CHANGED",
			)
		}
		if (session.finalAdmissionOrdinal?.let { it < targetAdmissionOrdinal } == true) {
			return ProtectedLocationCanonicalDrainResult.Failed(
				lastCommittedOrdinal,
				targetAdmissionOrdinal,
				"LOCATION_DRAIN_ENDPOINT_AFTER_FINAL_ADMISSION",
				terminal = true,
			)
		}
		val evidence = database.sourceEvidenceStateDao().get()
			?: return ProtectedLocationCanonicalDrainResult.AuthorityChanged(
				lastCommittedOrdinal,
				"SOURCE_EVIDENCE_STATE_MISSING",
			)
		val locationCompleteness = database.sourceSessionDao()
			.completenessForServiceRun(logicalTrackingId, serviceRunId)
			.filter { it.sourceKind == SOURCE_LOCATION }
		val endpoint = database.sourceEventWalDao()
				.projectionEligibilityByAdmissionOrdinal(targetAdmissionOrdinal)
		if (endpoint != null) {
			if (endpoint.sourceKind != SOURCE_LOCATION ||
				endpoint.logicalTrackingId != logicalTrackingId ||
				endpoint.serviceRunId != serviceRunId
			) {
				return ProtectedLocationCanonicalDrainResult.Failed(
					lastCommittedOrdinal,
					targetAdmissionOrdinal,
					"LOCATION_DRAIN_ENDPOINT_IDENTITY_MISMATCH",
					terminal = true,
				)
			}
			if (locationCompleteness.none { completeness ->
				completeness.sourceInstanceId == endpoint.sourceInstanceId &&
					completeness.registrationGeneration == endpoint.registrationGeneration &&
					completeness.lastAdmissionOrdinal == targetAdmissionOrdinal &&
					completeness.lastSourceSequence == endpoint.sourceSequence &&
					completeness.hasSettledLocationContinuity()
			}) {
				return ProtectedLocationCanonicalDrainResult.Deferred(
					lastCommittedOrdinal,
					targetAdmissionOrdinal,
					"LOCATION_DRAIN_ENDPOINT_NOT_ACKNOWLEDGED",
				)
			}
			return verifyLocationContinuity(
				afterOrdinal = lastCommittedOrdinal,
				throughOrdinal = targetAdmissionOrdinal,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				lastCommittedOrdinal = lastCommittedOrdinal,
			)
		}
		if (locationCompleteness.none { completeness ->
			completeness.lastAdmissionOrdinal == targetAdmissionOrdinal &&
				completeness.hasSettledLocationContinuity()
		}) {
			return ProtectedLocationCanonicalDrainResult.Deferred(
				lastCommittedOrdinal,
				targetAdmissionOrdinal,
				"LOCATION_DRAIN_ENDPOINT_NOT_ACKNOWLEDGED",
			)
		}
		if (targetAdmissionOrdinal <= evidence.deletedSourceEventHighWaterOrdinal) return null
		val failure = database.sourceProjectionStateDao().failure(
			WRITER_ID,
			WRITER_VERSION,
			targetAdmissionOrdinal,
		)
		if (failure?.terminal == true) return null
		return ProtectedLocationCanonicalDrainResult.Failed(
			lastCommittedOrdinal,
			targetAdmissionOrdinal,
			"LOCATION_DRAIN_ENDPOINT_MISSING",
			terminal = true,
		)
	}

	private suspend fun verifyLocationContinuity(
		afterOrdinal: Long,
		throughOrdinal: Long,
		logicalTrackingId: String,
		serviceRunId: String,
		lastCommittedOrdinal: Long,
	): ProtectedLocationCanonicalDrainResult? {
		var pageAfter = afterOrdinal
		val observedSequences =
			mutableMapOf<ProtectedLocationRegistrationIdentity, MutableSet<Long>>()
		while (pageAfter < throughOrdinal) {
			val page = database.sourceEventWalDao().continuityEventsAfterThrough(
				afterOrdinal = pageAfter,
				throughOrdinal = throughOrdinal,
				limit = CONTINUITY_PAGE_SIZE,
			)
			if (page.isEmpty()) break
			page.forEach { row ->
				if (row.sourceKind != SOURCE_LOCATION) return@forEach
				if (row.logicalTrackingId != logicalTrackingId ||
					row.serviceRunId != serviceRunId
				) {
					return ProtectedLocationCanonicalDrainResult.Deferred(
						lastCommittedOrdinal,
						row.admissionOrdinal,
						"LOCATION_DRAIN_PRIOR_RUN_REQUIRES_OWN_DRAIN",
					)
				}
				val registration = ProtectedLocationRegistrationIdentity(
					row.sourceInstanceId,
					row.registrationGeneration,
					logicalTrackingId,
					serviceRunId,
				)
				observedSequences.getOrPut(registration, ::mutableSetOf)
					.add(row.sourceSequence)
			}
			pageAfter = page.last().admissionOrdinal
		}
		val completeness = database.sourceSessionDao()
			.completenessForServiceRun(logicalTrackingId, serviceRunId)
			.filter { it.sourceKind == SOURCE_LOCATION }
		completeness.forEach { row ->
			if (!row.hasSettledLocationContinuity()) {
				return ProtectedLocationCanonicalDrainResult.Deferred(
					lastCommittedOrdinal,
					row.lastAdmissionOrdinal,
					"LOCATION_DRAIN_COMPLETENESS_UNSETTLED",
				)
			}
			val finalSequence = requireNotNull(row.lastSourceSequence)
			val finalOrdinal = requireNotNull(row.lastAdmissionOrdinal)
			if (finalOrdinal > throughOrdinal) return@forEach
			val registration = ProtectedLocationRegistrationIdentity(
				row.sourceInstanceId,
				row.registrationGeneration,
				logicalTrackingId,
				serviceRunId,
			)
			val observed = observedSequences[registration].orEmpty()
			val prior = loadLocationContinuityAnchor(registration)?.sourceSequence
				?: database.sourceEventWalDao().latestContinuityEventAtOrBefore(
				sourceKind = SOURCE_LOCATION,
				sourceInstanceId = row.sourceInstanceId,
				registrationGeneration = row.registrationGeneration,
				throughOrdinal = afterOrdinal,
			)?.sourceSequence ?: 0L
			if (finalSequence < prior) {
				return ProtectedLocationCanonicalDrainResult.Failed(
					lastCommittedOrdinal,
					finalOrdinal,
					"LOCATION_DRAIN_COMPLETENESS_SEQUENCE_REGRESSION",
					terminal = true,
				)
			}
			val firstObserved = observed.minOrNull()
			val expectedFirst = prior + 1L
			if (firstObserved != null && firstObserved != expectedFirst) {
				return ProtectedLocationCanonicalDrainResult.Failed(
					lastCommittedOrdinal,
					finalOrdinal,
					"LOCATION_DRAIN_INTERIOR_SEQUENCE_GAP",
					terminal = true,
				)
			}
			var expected = firstObserved ?: expectedFirst
			while (expected <= finalSequence) {
				if (expected !in observed) {
					return ProtectedLocationCanonicalDrainResult.Failed(
						lastCommittedOrdinal,
						finalOrdinal,
						"LOCATION_DRAIN_INTERIOR_SEQUENCE_GAP",
						terminal = true,
					)
				}
				expected++
			}
		}
		val completenessRegistrations = completeness.mapTo(mutableSetOf()) {
			ProtectedLocationRegistrationIdentity(
				it.sourceInstanceId,
				it.registrationGeneration,
				logicalTrackingId,
				serviceRunId,
			)
		}
		val missingCompleteness = observedSequences.keys - completenessRegistrations
		if (missingCompleteness.isNotEmpty()) {
			return ProtectedLocationCanonicalDrainResult.Deferred(
				lastCommittedOrdinal,
				throughOrdinal,
				"LOCATION_DRAIN_OBSERVED_REGISTRATION_MISSING_COMPLETENESS",
			)
		}
		return null
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "TooGenericExceptionCaught")
	private suspend fun drainLocked(
		initialLane: SourceProductProjectionLaneEntity,
		targetAdmissionOrdinal: Long,
		expectedLogicalTrackingId: String?,
		expectedServiceRunId: String?,
	): ProtectedLocationCanonicalDrainResult {
		var cursor = initialLane.contiguousAdmissionOrdinal
		var observations = 0
		var accepted = 0
		var rejected = 0
		var lifecycleSettled = 0
		if (targetAdmissionOrdinal <= cursor) {
			return ProtectedLocationCanonicalDrainResult.Complete(cursor, 0, 0, 0, 0)
		}

		while (cursor < targetAdmissionOrdinal) {
			val next = try {
				database.withTransaction {
					requireExactLane(initialLane, cursor)
					val terminal = database.sourceProjectionStateDao()
						.firstTerminalFailureAfterThrough(
							WRITER_ID,
							WRITER_VERSION,
							cursor,
							targetAdmissionOrdinal,
						)
					val candidate = database.sourceEventWalDao()
						.sourceProjectionEventsAfterThrough(
							sourceKind = SOURCE_LOCATION,
							afterOrdinal = cursor,
							throughOrdinal = targetAdmissionOrdinal,
							limit = 1,
						)
						.singleOrNull()
					if (terminal != null &&
						(candidate == null || terminal.admissionOrdinal < candidate.admissionOrdinal)
					) {
						val deletedHighWater = database.sourceEvidenceStateDao().get()
							?.deletedSourceEventHighWaterOrdinal ?: 0L
						if (terminal.admissionOrdinal <= deletedHighWater) {
							database.sourceProjectionStateDao().deleteFailure(
								WRITER_ID,
								WRITER_VERSION,
								terminal.admissionOrdinal,
							)
							advanceCursor(
								initialLane,
								cursor,
								terminal.admissionOrdinal,
							)
							ProtectedLocationNext.Advanced(
								terminal.admissionOrdinal,
								lifecycleSettled = true,
							)
						} else {
							ProtectedLocationNext.Terminal(terminal)
						}
					} else if (candidate == null) {
						val lifecycleSettled = database.sourceEvidenceStateDao().get()
							?.deletedSourceEventHighWaterOrdinal
							?.let { targetAdmissionOrdinal <= it } == true
						advanceCursor(initialLane, cursor, targetAdmissionOrdinal)
						ProtectedLocationNext.Advanced(
							targetAdmissionOrdinal,
							lifecycleSettled,
						)
					} else {
						ProtectedLocationNext.Candidate(
							eventId = candidate.eventId,
							admissionOrdinal = candidate.admissionOrdinal,
						)
					}
				}
			} catch (changed: ProtectedLocationAuthorityChangedException) {
				return ProtectedLocationCanonicalDrainResult.AuthorityChanged(cursor, changed.reason)
			} catch (failure: Exception) {
				return ProtectedLocationCanonicalDrainResult.Failed(
					cursor,
					null,
					failure.safeCode(),
					terminal = false,
				)
			}

			when (next) {
				is ProtectedLocationNext.Advanced -> {
					cursor = next.throughOrdinal
					if (next.lifecycleSettled) lifecycleSettled++
				}
				is ProtectedLocationNext.Terminal -> {
					return ProtectedLocationCanonicalDrainResult.Failed(
						cursor,
						next.failure.admissionOrdinal,
						next.failure.failureCode,
						terminal = true,
					)
				}
				is ProtectedLocationNext.Candidate -> {
					val effect = try {
						processCandidate(
							initialLane = initialLane,
							expectedCursor = cursor,
							candidate = next,
							expectedLogicalTrackingId = expectedLogicalTrackingId,
							expectedServiceRunId = expectedServiceRunId,
						)
					} catch (cancelled: CancellationException) {
						throw cancelled
					} catch (changed: ProtectedLocationAuthorityChangedException) {
						return ProtectedLocationCanonicalDrainResult.AuthorityChanged(
							cursor,
							changed.reason,
						)
					} catch (failure: Exception) {
						return ProtectedLocationCanonicalDrainResult.Failed(
							cursor,
							next.admissionOrdinal,
							failure.safeCode(),
							terminal = false,
						)
					}
					when (effect) {
						is ProtectedLocationCandidateEffect.Applied -> {
							cursor = effect.throughOrdinal
							observations += 1
							if (effect.accepted) accepted++ else rejected++
						}
						is ProtectedLocationCandidateEffect.LifecycleSettled -> {
							cursor = effect.throughOrdinal
							lifecycleSettled++
						}
						is ProtectedLocationCandidateEffect.Deferred -> {
							return ProtectedLocationCanonicalDrainResult.Deferred(
								cursor,
								next.admissionOrdinal,
								effect.reason,
							)
						}
						is ProtectedLocationCandidateEffect.Failed -> {
							return ProtectedLocationCanonicalDrainResult.Failed(
								cursor,
								next.admissionOrdinal,
								effect.failureCode,
								effect.terminal,
							)
						}
						is ProtectedLocationCandidateEffect.AuthorityChanged -> {
							return ProtectedLocationCanonicalDrainResult.AuthorityChanged(
								cursor,
								effect.reason,
							)
						}
					}
				}
			}
		}
		return ProtectedLocationCanonicalDrainResult.Complete(
			cursor,
			observations,
			accepted,
			rejected,
			lifecycleSettled,
		)
	}

	private suspend fun processCandidate(
		initialLane: SourceProductProjectionLaneEntity,
		expectedCursor: Long,
		candidate: ProtectedLocationNext.Candidate,
		expectedLogicalTrackingId: String?,
		expectedServiceRunId: String?,
	): ProtectedLocationCandidateEffect {
		val preflight = database.sourceEventWalDao().payloadPreflightByEventId(candidate.eventId)
		val adapted = qualifier.qualify(SourceEventId(candidate.eventId))
		val qualified = when (adapted) {
			is LocationWalAdapterResult.Rejected -> {
				if (adapted.reason.isLifecycleSettlement() &&
					settleLifecycleRejection(
						initialLane,
						expectedCursor,
						candidate,
						preflight,
						adapted.reason,
					)
				) {
					return ProtectedLocationCandidateEffect.LifecycleSettled(
						candidate.admissionOrdinal,
					)
				}
				val code = "LOCATION_ADAPTER_${adapted.reason.name}"
				saveFailure(initialLane, expectedCursor, candidate.admissionOrdinal, code, true)
				return ProtectedLocationCandidateEffect.Failed(code, terminal = true)
			}
			is LocationWalAdapterResult.Evaluated -> when (val qualification = adapted.qualification) {
				is LocationObservationQualification.Qualified ->
					ProtectedLocationQualifiedCandidate(
						qualification.command,
						adapted.acquisitionMetadata,
					)
				is LocationObservationQualification.Duplicate ->
					ProtectedLocationQualifiedCandidate(
						qualification.existing,
						adapted.acquisitionMetadata,
					)
				is LocationObservationQualification.Stale -> {
					val code = "LOCATION_QUALIFICATION_STALE_${qualification.reason.name}"
					saveFailure(initialLane, expectedCursor, candidate.admissionOrdinal, code, true)
					return ProtectedLocationCandidateEffect.Failed(code, terminal = true)
				}
				is LocationObservationQualification.Unavailable -> {
					val code = "LOCATION_QUALIFICATION_UNAVAILABLE_${qualification.reason.name}"
					saveFailure(initialLane, expectedCursor, candidate.admissionOrdinal, code, true)
					return ProtectedLocationCandidateEffect.Failed(code, terminal = true)
				}
				is LocationObservationQualification.Rejected -> {
					val code = "LOCATION_QUALIFICATION_REJECTED_${qualification.reason.name}"
					saveFailure(initialLane, expectedCursor, candidate.admissionOrdinal, code, true)
					return ProtectedLocationCandidateEffect.Failed(code, terminal = true)
				}
			}
		}
		val command = qualified.command
		val acquisitionMetadata = qualified.acquisitionMetadata
		if (!acquisitionMetadata.isQualified) {
			val code = "LOCATION_ACQUISITION_METADATA_UNVERIFIABLE"
			saveFailure(initialLane, expectedCursor, candidate.admissionOrdinal, code, true)
			return ProtectedLocationCandidateEffect.Failed(code, terminal = true)
		}
		if (command.mutation.identity.sourceAdmissionOrdinal != candidate.admissionOrdinal ||
			command.mutation.identity.sourceEventId.value != candidate.eventId
		) {
			val code = "LOCATION_ADAPTER_IDENTITY_MISMATCH"
			saveFailure(initialLane, expectedCursor, candidate.admissionOrdinal, code, true)
			return ProtectedLocationCandidateEffect.Failed(code, terminal = true)
		}

		val authority = command.authority
		val isExpectedRun = expectedLogicalTrackingId == null ||
			(authority.logicalTrackingId.value == expectedLogicalTrackingId &&
				authority.serviceRunId.value == expectedServiceRunId)
		val beforeWrite = database.withTransaction {
			requireExactLane(initialLane, expectedCursor)
			database.readProtectedLocationCanonicalReceipt(command, acquisitionMetadata)
		}
		if (beforeWrite is ProtectedLocationCanonicalReceipt.Complete) {
			advanceAfterReceipt(
				initialLane,
				expectedCursor,
				candidate.admissionOrdinal,
				command,
				acquisitionMetadata,
			)
			return ProtectedLocationCandidateEffect.Applied(
				candidate.admissionOrdinal,
				accepted = beforeWrite.acceptedSample != null,
			)
		}
		if (beforeWrite is ProtectedLocationCanonicalReceipt.Invalid) {
			saveFailure(
				initialLane,
				expectedCursor,
				candidate.admissionOrdinal,
				beforeWrite.reason,
				terminal = true,
			)
			return ProtectedLocationCandidateEffect.Failed(beforeWrite.reason, terminal = true)
		}
		if (beforeWrite is ProtectedLocationCanonicalReceipt.Incomplete &&
			beforeWrite.reason != "LOCATION_CANONICAL_OBSERVATION_PENDING"
		) {
			val code = beforeWrite.reason
			saveFailure(
				initialLane,
				expectedCursor,
				candidate.admissionOrdinal,
				code,
				terminal = true,
			)
			return ProtectedLocationCandidateEffect.Failed(code, terminal = true)
		}
		if (!isExpectedRun) {
			return ProtectedLocationCandidateEffect.Deferred(
				"HISTORICAL_LOCATION_CANONICAL_SESSION_NOT_ACTIVE",
			)
		}

		when (val write = canonicalWriter.write(command, acquisitionMetadata)) {
			ProtectedLocationCanonicalWriteResult.Committed -> Unit
			is ProtectedLocationCanonicalWriteResult.Deferred ->
				return ProtectedLocationCandidateEffect.Deferred(write.reason)
			is ProtectedLocationCanonicalWriteResult.Inactive ->
				return ProtectedLocationCandidateEffect.Deferred(write.reason)
			is ProtectedLocationCanonicalWriteResult.AuthorityChanged ->
				return ProtectedLocationCandidateEffect.AuthorityChanged(write.reason)
			is ProtectedLocationCanonicalWriteResult.Failed -> {
				if (write.terminal) {
					saveFailure(
						initialLane,
						expectedCursor,
						candidate.admissionOrdinal,
						write.failureCode,
						terminal = true,
					)
				}
				return ProtectedLocationCandidateEffect.Failed(
					write.failureCode,
					write.terminal,
				)
			}
		}

		return when (val receipt = database.withTransaction {
			requireExactLane(initialLane, expectedCursor)
			database.readProtectedLocationCanonicalReceipt(command, acquisitionMetadata)
		}) {
			is ProtectedLocationCanonicalReceipt.Complete -> {
				advanceAfterReceipt(
					initialLane,
					expectedCursor,
					candidate.admissionOrdinal,
					command,
					acquisitionMetadata,
				)
				ProtectedLocationCandidateEffect.Applied(
					candidate.admissionOrdinal,
					accepted = receipt.acceptedSample != null,
				)
			}
			is ProtectedLocationCanonicalReceipt.Incomplete ->
				ProtectedLocationCandidateEffect.Deferred(receipt.reason)
			is ProtectedLocationCanonicalReceipt.Invalid -> {
				saveFailure(
					initialLane,
					expectedCursor,
					candidate.admissionOrdinal,
					receipt.reason,
					terminal = true,
				)
				ProtectedLocationCandidateEffect.Failed(receipt.reason, terminal = true)
			}
		}
	}

	private suspend fun advanceAfterReceipt(
		initialLane: SourceProductProjectionLaneEntity,
		expectedCursor: Long,
		throughOrdinal: Long,
		command: LocationCapturedFactCommand,
		acquisitionMetadata: LocationWalAcquisitionMetadata,
	) {
		database.withTransaction {
			requireExactLane(initialLane, expectedCursor)
			check(database.readProtectedLocationCanonicalReceipt(
				command,
				acquisitionMetadata,
			) is
				ProtectedLocationCanonicalReceipt.Complete
			) {
				"Protected Location canonical receipt disappeared before cursor commit"
			}
			database.sourceProjectionStateDao().deleteFailure(
				WRITER_ID,
				WRITER_VERSION,
				throughOrdinal,
			)
			database.saveLocationContinuityAnchor(
				command = command,
			)
			advanceCursor(initialLane, expectedCursor, throughOrdinal)
			val receiptKey = protectedLocationReceiptKey(
				command.mutation.identity.sourceEventId.value,
			)
			val receipt = requireNotNull(database.sourceProjectionStateDao().joinState(
				WRITER_ID,
				WRITER_VERSION,
				receiptKey,
			))
			database.sourceProjectionStateDao().saveJoinState(
				receipt.copy(
					minimumRequiredOrdinal = Long.MAX_VALUE,
					updatedAtMs = nowMs(),
				),
			)
		}
	}

	private suspend fun settleLifecycleRejection(
		initialLane: SourceProductProjectionLaneEntity,
		expectedCursor: Long,
		candidate: ProtectedLocationNext.Candidate,
		preflight: SourceEventWalPayloadPreflightRow?,
		reason: LocationWalAdapterRejection,
	): Boolean = database.withTransaction {
		requireExactLane(initialLane, expectedCursor)
		val state = database.sourceEvidenceStateDao().get() ?: return@withTransaction false
		val settled = when (reason) {
			LocationWalAdapterRejection.DELETED_EVIDENCE ->
				candidate.admissionOrdinal <= state.deletedSourceEventHighWaterOrdinal ||
					preflight?.let {
						it.capturedCollectedDataEpoch != state.collectedDataEpoch
					} == true
			LocationWalAdapterRejection.RETAINED_EVIDENCE -> state.retainedFromMs != null
			LocationWalAdapterRejection.MISSING_EVENT ->
				candidate.admissionOrdinal <= state.deletedSourceEventHighWaterOrdinal
			else -> false
		}
		if (!settled) return@withTransaction false
		database.sourceProjectionStateDao().deleteFailure(
			WRITER_ID,
			WRITER_VERSION,
			candidate.admissionOrdinal,
		)
		advanceCursor(initialLane, expectedCursor, candidate.admissionOrdinal)
		true
	}

	private suspend fun saveFailure(
		initialLane: SourceProductProjectionLaneEntity,
		expectedCursor: Long,
		admissionOrdinal: Long,
		failureCode: String,
		terminal: Boolean,
	) {
		database.withTransaction {
			requireExactLane(initialLane, expectedCursor)
			val dao = database.sourceProjectionStateDao()
			val prior = dao.failure(WRITER_ID, WRITER_VERSION, admissionOrdinal)
			dao.saveFailure(
				SourceProjectionFailureEntity(
					projectionId = WRITER_ID,
					projectionVersion = WRITER_VERSION,
					admissionOrdinal = admissionOrdinal,
					attemptCount = (prior?.attemptCount ?: 0) + 1,
					failureCode = failureCode.ifBlank { "UNKNOWN_LOCATION_HANDOFF_FAILURE" },
					terminal = terminal,
					lastAttemptAtMs = nowMs(),
				),
			)
		}
	}

	private suspend fun resolveLane(): ProtectedLocationLaneResolution {
		val active = database.sourceProjectionStateDao().allActiveProductLanes()
			.filter { it.sourceKind == SOURCE_LOCATION }
		if (active.isEmpty()) {
			return ProtectedLocationLaneResolution.Inactive(
				lastCommittedOrdinal = database.sourceProjectionStateDao()
					.latestProductLane(SOURCE_LOCATION)
					?.contiguousAdmissionOrdinal ?: 0L,
				reason = ProtectedLocationCanonicalInactiveReason.NO_ACTIVE_LANE,
			)
		}
		if (active.size != 1) {
			return ProtectedLocationLaneResolution.Inactive(
				lastCommittedOrdinal = active.minOf { it.contiguousAdmissionOrdinal },
				reason = ProtectedLocationCanonicalInactiveReason.AMBIGUOUS_ACTIVE_LANE,
			)
		}
		val lane = active.single()
		val inactiveReason = when {
			lane.bindingGeneration != BINDING_GENERATION ||
				lane.projectionId != WRITER_ID ||
				lane.projectionVersion != WRITER_VERSION ||
				lane.captureModeMask != MANUAL_CAPTURE_MODE_MASK ||
				lane.activatedRolloutRevision <= 0L ||
				lane.activationOrdinal <= 0L ||
				lane.contiguousAdmissionOrdinal < lane.activationOrdinal - 1L ||
				lane.captureAdmissionCutoffOrdinal?.let {
					lane.contiguousAdmissionOrdinal > it
				} == true ||
				!lane.retentionRequired ||
				lane.status != SourceProductProjectionLaneEntity.STATUS_ACTIVE ||
				lane.terminalDisposition != null ||
				lane.terminalAtMs != null ->
				ProtectedLocationCanonicalInactiveReason.BINDING_NOT_EXECUTABLE
			lane.productStage != SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL ->
				ProtectedLocationCanonicalInactiveReason.LANE_NOT_CANONICAL
			database.sourceProjectionStateDao().registration(WRITER_ID, WRITER_VERSION) != null ->
				ProtectedLocationCanonicalInactiveReason.GLOBAL_WRITER_CONFLICT
			!database.sourceDestinationOwnerDao().isExactOwner(
				SourceDestinationOwnerEntity.SOURCE_LOCATION,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
				SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE,
				SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
			) -> ProtectedLocationCanonicalInactiveReason.DESTINATION_NOT_OWNED
			else -> null
		}
		return if (inactiveReason == null) {
			ProtectedLocationLaneResolution.Active(lane)
		} else {
			ProtectedLocationLaneResolution.Inactive(
				lane.contiguousAdmissionOrdinal,
				inactiveReason,
			)
		}
	}

	private suspend fun requireExactLane(
		expected: SourceProductProjectionLaneEntity,
		expectedCursor: Long,
	): SourceProductProjectionLaneEntity {
		val current = when (val resolution = resolveLane()) {
			is ProtectedLocationLaneResolution.Active -> resolution.lane
			is ProtectedLocationLaneResolution.Inactive ->
				throw ProtectedLocationAuthorityChangedException(
					"LOCATION_HANDOFF_${resolution.reason.name}",
				)
		}
		if (!current.hasSameExecutionBinding(expected)) {
			throw ProtectedLocationAuthorityChangedException("LOCATION_HANDOFF_BINDING_CHANGED")
		}
		if (current.contiguousAdmissionOrdinal != expectedCursor) {
			throw ProtectedLocationAuthorityChangedException("LOCATION_HANDOFF_CURSOR_CHANGED")
		}
		return current
	}

	private suspend fun advanceCursor(
		lane: SourceProductProjectionLaneEntity,
		expectedCursor: Long,
		throughOrdinal: Long,
	) {
		if (throughOrdinal == expectedCursor) return
		check(database.sourceProjectionStateDao().advanceExactProductLaneCursor(
			sourceKind = lane.sourceKind,
			bindingGeneration = lane.bindingGeneration,
			projectionId = lane.projectionId,
			projectionVersion = lane.projectionVersion,
			captureModeMask = lane.captureModeMask,
			productStage = lane.productStage,
			activatedRolloutRevision = lane.activatedRolloutRevision,
			activationOrdinal = lane.activationOrdinal,
			expectedCutoffOrdinal = lane.captureAdmissionCutoffOrdinal,
			expectedCurrentOrdinal = expectedCursor,
			throughOrdinal = throughOrdinal,
			updatedAtMs = nowMs(),
		) == 1) {
			"Exact protected Location lane changed before cursor commit"
		}
	}

	private suspend fun loadLocationContinuityAnchor(
		registration: ProtectedLocationRegistrationIdentity,
	): ProtectedLocationContinuityAnchor? =
		database.loadLocationContinuityAnchor(registration)

	private fun nowMs(): Long = System.currentTimeMillis().coerceAtLeast(0L)

	companion object {
		const val WRITER_ID =
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID
		const val WRITER_VERSION =
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION
		const val BINDING_GENERATION =
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_BINDING_GENERATION
		const val MANUAL_CAPTURE_MODE_MASK = 1L
		private const val SOURCE_LOCATION = SourceDestinationOwnerEntity.SOURCE_LOCATION
		private const val CONTINUITY_PAGE_SIZE = 256
	}
}

internal data class ProtectedLocationCanonicalPendingDecision(
	val sourceEventId: String,
	val decision: String,
	val reason: String?,
	val decisionVersion: Int,
	val acceptedSampleSourceSignalId: String?,
	val sourceSignalId: String,
	val clockDomainId: String?,
	val decidedAtMs: Long,
)

internal data class ProtectedLocationCanonicalPendingWrite(
	val sourceEventId: String,
	val observation: LocationObservation?,
	val decision: ProtectedLocationCanonicalPendingDecision?,
	val sample: StoredLocationSample?,
)

internal data class ProtectedLocationVerifiedWrite(
	val command: LocationCapturedFactCommand,
	val acquisitionMetadata: LocationWalAcquisitionMetadata,
	val expectedOutput: ProtectedLocationPreparedCanonicalOutput? = null,
)

/**
 * Rechecks WAL authority plus permanent writer/lane generation inside PersistenceProcessor's
 * existing destination transaction. It never opens a second Location writer.
 */
internal class ProtectedLocationCanonicalPersistenceGuard private constructor(
	private val database: AppDatabase?,
	private val qualifier: ProtectedLocationWalQualifier?,
) {
	@Inject
	constructor(
		database: AppDatabase,
		qualifier: LocationWalQualificationAdapter,
	) : this(database, qualifier as ProtectedLocationWalQualifier)

	internal constructor(
		database: AppDatabase,
		qualifier: ProtectedLocationWalQualifier,
		@Suppress("UNUSED_PARAMETER") testMarker: Unit,
	) : this(database, qualifier)

	suspend fun verifyInCurrentWriterTransaction(
		write: ProtectedLocationCanonicalPendingWrite,
	): ProtectedLocationVerifiedWrite {
		val db = database ?: error("Protected Location persistence guard is not configured")
		val adapter = qualifier ?: error("Protected Location qualifier is not configured")
		val qualified = when (val adapted = adapter.qualify(SourceEventId(write.sourceEventId))) {
			is LocationWalAdapterResult.Rejected ->
				error("Protected Location WAL authority rejected: ${adapted.reason.name}")
			is LocationWalAdapterResult.Evaluated -> when (val result = adapted.qualification) {
				is LocationObservationQualification.Qualified ->
					ProtectedLocationQualifiedCandidate(
						result.command,
						adapted.acquisitionMetadata,
					)
				is LocationObservationQualification.Duplicate ->
					ProtectedLocationQualifiedCandidate(
						result.existing,
						adapted.acquisitionMetadata,
					)
				is LocationObservationQualification.Stale ->
					error("Protected Location WAL became stale: ${result.reason.name}")
				is LocationObservationQualification.Unavailable ->
					error("Protected Location WAL became unavailable: ${result.reason.name}")
				is LocationObservationQualification.Rejected ->
					error("Protected Location qualification rejected: ${result.reason.name}")
			}
		}
		val command = qualified.command
		val acquisitionMetadata = qualified.acquisitionMetadata
		check(acquisitionMetadata.isQualified) {
			"Protected Location historical acquisition metadata is unavailable"
		}
		require(command.mutation.identity.sourceEventId.value == write.sourceEventId)
		db.requireProtectedLocationWriterAuthority()

		val observation = write.observation
			?: db.locationObservationDao().getBySourceEventId(write.sourceEventId)
		requireNotNull(observation) {
			"Protected Location canonical decision has no raw observation"
		}
		require(observation.matchesQualifiedEvidence(command, acquisitionMetadata)) {
			"Protected Location raw observation does not match authenticated WAL evidence"
		}

		val decision = write.decision
		val sample = write.sample
		require(decision != null || sample == null) {
			"Protected Location sample cannot commit without its terminal decision"
		}
		var prepared: ProtectedLocationPreparedCurationState? = null
		if (decision != null) {
			require(decision.matches(command, acquisitionMetadata, sample)) {
				"Protected Location terminal decision/sample receipt is inconsistent"
			}
			prepared = requireNotNull(
				db.loadPreparedProtectedLocationCanonicalCurationState(command),
			) {
				"Protected Location terminal write has no prepared canonical output"
			}
			require(prepared.expectedOutput.matches(decision.toStoredDecision(), sample)) {
				"Protected Location buffered destination differs from prepared canonical output"
			}
		}
		db.requireProtectedLocationWriterAuthority()
		return ProtectedLocationVerifiedWrite(
			command,
			acquisitionMetadata,
			prepared?.expectedOutput,
		)
	}

	companion object {
		fun unavailable() = ProtectedLocationCanonicalPersistenceGuard(null, null)
	}
}

internal sealed interface ProtectedLocationCanonicalReceipt {
	data class Complete(
		val observation: LocationObservation,
		val decision: LocationObservationDecision,
		val acceptedSample: StoredLocationSample?,
		val curationState: LocationCanonicalCurationState,
	) : ProtectedLocationCanonicalReceipt

	data class Incomplete(val reason: String) : ProtectedLocationCanonicalReceipt
	data class Invalid(val reason: String) : ProtectedLocationCanonicalReceipt
}

internal suspend fun AppDatabase.readProtectedLocationCanonicalReceipt(
	command: LocationCapturedFactCommand,
	acquisitionMetadata: LocationWalAcquisitionMetadata,
): ProtectedLocationCanonicalReceipt {
	val eventId = command.mutation.identity.sourceEventId.value
	val observation = locationObservationDao().getBySourceEventId(eventId)
		?: return ProtectedLocationCanonicalReceipt.Incomplete(
			"LOCATION_CANONICAL_OBSERVATION_PENDING",
		)
	if (!observation.matchesQualifiedEvidence(command, acquisitionMetadata)) {
		return ProtectedLocationCanonicalReceipt.Invalid(
			"LOCATION_CANONICAL_OBSERVATION_MISMATCH",
		)
	}
	val decision = locationObservationDecisionDao().getBySourceEventId(eventId)
		?: return ProtectedLocationCanonicalReceipt.Incomplete(
			"LOCATION_CANONICAL_DECISION_PENDING",
		)
	val canonicalSignalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(eventId)
	val sample = when (decision.decision) {
		LocationObservationDecision.ACCEPTED -> {
			if (decision.acceptedSampleSourceSignalId != canonicalSignalId) {
				return ProtectedLocationCanonicalReceipt.Invalid(
					"LOCATION_CANONICAL_ACCEPTED_SAMPLE_IDENTITY_MISMATCH",
				)
			}
			locationSampleDao().getBySourceSignalId(canonicalSignalId)
				?: return ProtectedLocationCanonicalReceipt.Incomplete(
					"LOCATION_CANONICAL_SAMPLE_PENDING",
				)
		}
		LocationObservationDecision.REJECTED -> {
			if (decision.acceptedSampleSourceSignalId != null ||
				locationSampleDao().getBySourceSignalId(canonicalSignalId) != null
			) {
				return ProtectedLocationCanonicalReceipt.Invalid(
					"LOCATION_CANONICAL_REJECTED_SAMPLE_PRESENT",
				)
			}
			null
		}
		else -> return ProtectedLocationCanonicalReceipt.Invalid(
			"LOCATION_CANONICAL_DECISION_UNSUPPORTED",
		)
	}
	val stored = sourceProjectionStateDao().joinState(
		projectionId = ProtectedLocationCanonicalHandoff.WRITER_ID,
		projectionVersion = ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		stateKey = protectedLocationReceiptKey(eventId),
	) ?: return ProtectedLocationCanonicalReceipt.Incomplete(
		"LOCATION_CANONICAL_CONTENT_RECEIPT_PENDING",
	)
	val decoded = runCatching {
		ProtectedLocationCanonicalReceiptCodec.decode(stored.payload)
	}.getOrElse {
		return ProtectedLocationCanonicalReceipt.Invalid(
			"LOCATION_CANONICAL_CONTENT_RECEIPT_MALFORMED",
		)
	}
	if (stored.payloadVersion != ProtectedLocationCanonicalReceiptCodec.VERSION ||
		stored.logicalTrackingId != command.authority.logicalTrackingId.value ||
		stored.minimumRequiredOrdinal !in setOf(
			command.mutation.identity.sourceAdmissionOrdinal,
			Long.MAX_VALUE,
		) ||
		!decoded.matches(command, acquisitionMetadata) ||
		!decoded.observation.contentEquals(observation.canonicalReceiptBytes()) ||
		!decoded.decision.contentEquals(decision.canonicalReceiptBytes()) ||
		!decoded.sample.contentEqualsNullable(sample?.canonicalReceiptBytes()) ||
		runCatching {
			ProtectedLocationCanonicalCurationStateCodec.decode(decoded.curationState)
		}.isFailure
	) {
		return ProtectedLocationCanonicalReceipt.Invalid(
			"LOCATION_CANONICAL_CONTENT_RECEIPT_MISMATCH",
		)
	}
	return ProtectedLocationCanonicalReceipt.Complete(
		observation,
		decision,
		sample,
		ProtectedLocationCanonicalCurationStateCodec.decode(decoded.curationState),
	)
}

internal suspend fun AppDatabase.recordProtectedLocationCanonicalReceiptInCurrentTransaction(
	verified: ProtectedLocationVerifiedWrite,
) {
	val command = verified.command
	val eventId = command.mutation.identity.sourceEventId.value
	val observation = requireNotNull(locationObservationDao().getBySourceEventId(eventId)) {
		"Protected Location receipt requires the committed raw observation"
	}
	val decision = requireNotNull(locationObservationDecisionDao().getBySourceEventId(eventId)) {
		"Protected Location receipt requires a terminal curation decision"
	}
	val canonicalSignalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(eventId)
	val sample = when (decision.decision) {
		LocationObservationDecision.ACCEPTED ->
			requireNotNull(locationSampleDao().getBySourceSignalId(canonicalSignalId)) {
				"Protected Location accepted receipt requires its committed sample"
			}
		LocationObservationDecision.REJECTED -> {
			check(locationSampleDao().getBySourceSignalId(canonicalSignalId) == null) {
				"Protected Location rejected receipt cannot include a sample"
			}
			null
		}
		else -> error("Unsupported protected Location terminal decision ${decision.decision}")
	}
	check(observation.matchesQualifiedEvidence(command, verified.acquisitionMetadata)) {
		"Protected Location receipt observation no longer matches qualified evidence"
	}
	check(decision.toPendingDecision().matches(
		command,
		verified.acquisitionMetadata,
		sample,
	)) {
		"Protected Location receipt decision/sample no longer matches qualified evidence"
	}
	check(requireNotNull(verified.expectedOutput) {
		"Protected Location terminal write has no verified prepared output"
	}.matches(decision, sample)) {
		"Protected Location stored destination differs from writer-transaction expectation"
	}
	val preparedKey = protectedLocationPreparedCurationKey(eventId)
	val prepared = requireNotNull(sourceProjectionStateDao().joinState(
		ProtectedLocationCanonicalHandoff.WRITER_ID,
		ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		preparedKey,
	)) {
		"Protected Location terminal write has no prepared curation state"
	}
	check(prepared.logicalTrackingId == command.authority.logicalTrackingId.value &&
		prepared.minimumRequiredOrdinal == command.mutation.identity.sourceAdmissionOrdinal &&
		prepared.payloadVersion == ProtectedLocationPreparedCurationStateCodec.VERSION
	) {
		"Protected Location prepared curation authority changed"
	}
	val preparedState = ProtectedLocationPreparedCurationStateCodec.decode(prepared.payload)
	check(preparedState.expectedOutput.contentEquals(verified.expectedOutput) &&
		preparedState.expectedOutput.matches(decision, sample)
	) {
		"Protected Location stored destination differs from prepared canonical output"
	}
	val committedCurationState = ProtectedLocationCanonicalCurationStateCodec.encode(
		preparedState.stateAfter,
	)
	sourceProjectionStateDao().saveJoinState(
		SourceProjectionJoinStateEntity(
			projectionId = ProtectedLocationCanonicalHandoff.WRITER_ID,
			projectionVersion = ProtectedLocationCanonicalHandoff.WRITER_VERSION,
			stateKey = protectedLocationReceiptKey(eventId),
			logicalTrackingId = command.authority.logicalTrackingId.value,
			minimumRequiredOrdinal = command.mutation.identity.sourceAdmissionOrdinal,
			payloadVersion = ProtectedLocationCanonicalReceiptCodec.VERSION,
			payload = ProtectedLocationCanonicalReceiptCodec.encode(
				command = command,
				acquisitionMetadata = verified.acquisitionMetadata,
				observation = observation,
				decision = decision,
				sample = sample,
				curationState = committedCurationState,
			),
			updatedAtMs = System.currentTimeMillis().coerceAtLeast(0L),
		),
	)
	saveProtectedLocationRunCurationCheckpoint(
		logicalTrackingId = command.authority.logicalTrackingId.value,
		serviceRunId = command.authority.serviceRunId.value,
		committedThroughOrdinal = command.mutation.identity.sourceAdmissionOrdinal,
		payload = committedCurationState,
	)
	sourceProjectionStateDao().deleteJoinState(
		ProtectedLocationCanonicalHandoff.WRITER_ID,
		ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		preparedKey,
	)
}

private fun LocationObservationDecision.toPendingDecision() =
	ProtectedLocationCanonicalPendingDecision(
		sourceEventId = observationSourceEventId,
		decision = decision,
		reason = reason,
		decisionVersion = decisionVersion,
		acceptedSampleSourceSignalId = acceptedSampleSourceSignalId,
		sourceSignalId = sourceSignalId,
		clockDomainId = clockDomainId,
		decidedAtMs = decidedAtMs,
	)

private fun ProtectedLocationCanonicalPendingDecision.toStoredDecision() =
	LocationObservationDecision(
		observationSourceEventId = sourceEventId,
		decision = decision,
		reason = reason,
		decisionVersion = decisionVersion,
		acceptedSampleSourceSignalId = acceptedSampleSourceSignalId,
		sourceSignalId = sourceSignalId,
		clockDomainId = clockDomainId,
		decidedAtMs = decidedAtMs,
	)

private data class ProtectedLocationCanonicalReceiptPayload(
	val sourceEventId: String,
	val sourceAdmissionOrdinal: Long,
	val walIntegrityIdentity: String,
	val acquisitionMode: String,
	val requestPriority: String,
	val requiredAccuracyMeters: Float,
	val policyTier: String,
	val policyName: String,
	val curationVersion: Int,
	val altitudeModelVersion: Int,
	val altitudeEstimatorVersion: Int,
	val altitudeCalibrationVersion: Int,
	val observation: ByteArray,
	val decision: ByteArray,
	val sample: ByteArray?,
	val curationState: ByteArray,
) {
	fun matches(
		command: LocationCapturedFactCommand,
		acquisitionMetadata: LocationWalAcquisitionMetadata,
	): Boolean =
		sourceEventId == command.mutation.identity.sourceEventId.value &&
			sourceAdmissionOrdinal == command.mutation.identity.sourceAdmissionOrdinal &&
			walIntegrityIdentity == command.mutation.identity.walIntegrityIdentity &&
			acquisitionMode == acquisitionMetadata.acquisitionMode.name &&
			requestPriority == acquisitionMetadata.requestPriority.name &&
			requiredAccuracyMeters == acquisitionMetadata.requiredAccuracyMeters &&
			policyTier == acquisitionMetadata.policyTier.name &&
			policyName == acquisitionMetadata.policyName &&
			curationVersion == acquisitionMetadata.curationVersion &&
			altitudeModelVersion == acquisitionMetadata.altitudeModelVersion &&
			altitudeEstimatorVersion == acquisitionMetadata.altitudeEstimatorVersion &&
			altitudeCalibrationVersion == acquisitionMetadata.altitudeCalibrationVersion
}

private object ProtectedLocationCanonicalReceiptCodec {
	const val VERSION = 3
	private const val MAGIC = 0x504c4352

	fun encode(
		command: LocationCapturedFactCommand,
		acquisitionMetadata: LocationWalAcquisitionMetadata,
		observation: LocationObservation,
		decision: LocationObservationDecision,
		sample: StoredLocationSample?,
		curationState: ByteArray,
	): ByteArray = ByteArrayOutputStream().use { bytes ->
		DataOutputStream(bytes).use { output ->
			output.writeInt(MAGIC)
			output.writeInt(VERSION)
			output.writeUTF(command.mutation.identity.sourceEventId.value)
			output.writeLong(command.mutation.identity.sourceAdmissionOrdinal)
			output.writeUTF(command.mutation.identity.walIntegrityIdentity)
			output.writeUTF(acquisitionMetadata.acquisitionMode.name)
			output.writeUTF(acquisitionMetadata.requestPriority.name)
			output.writeInt(
				java.lang.Float.floatToRawIntBits(acquisitionMetadata.requiredAccuracyMeters),
			)
			output.writeUTF(acquisitionMetadata.policyTier.name)
			output.writeUTF(acquisitionMetadata.policyName)
			output.writeInt(acquisitionMetadata.curationVersion)
			output.writeInt(acquisitionMetadata.altitudeModelVersion)
			output.writeInt(acquisitionMetadata.altitudeEstimatorVersion)
			output.writeInt(acquisitionMetadata.altitudeCalibrationVersion)
			output.writeByteArray(observation.canonicalReceiptBytes())
			output.writeByteArray(decision.canonicalReceiptBytes())
			output.writeNullableByteArray(sample?.canonicalReceiptBytes())
			output.writeByteArray(curationState)
		}
		bytes.toByteArray().withSha256Trailer()
	}

	fun decode(payload: ByteArray): ProtectedLocationCanonicalReceiptPayload =
		DataInputStream(ByteArrayInputStream(payload.verifiedSha256Body())).use { input ->
			require(input.readInt() == MAGIC)
			require(input.readInt() == VERSION)
			val decoded = ProtectedLocationCanonicalReceiptPayload(
				sourceEventId = input.readUTF(),
				sourceAdmissionOrdinal = input.readLong(),
				walIntegrityIdentity = input.readUTF(),
				acquisitionMode = input.readUTF(),
				requestPriority = input.readUTF(),
				requiredAccuracyMeters = java.lang.Float.intBitsToFloat(input.readInt()),
				policyTier = input.readUTF(),
				policyName = input.readUTF(),
				curationVersion = input.readInt(),
				altitudeModelVersion = input.readInt(),
				altitudeEstimatorVersion = input.readInt(),
				altitudeCalibrationVersion = input.readInt(),
				observation = input.readByteArray(),
				decision = input.readByteArray(),
				sample = input.readNullableByteArray(),
				curationState = input.readByteArray(),
			)
			require(input.available() == 0)
			decoded
		}

	private fun DataOutputStream.writeByteArray(value: ByteArray) {
		writeInt(value.size)
		write(value)
	}

	private fun DataOutputStream.writeNullableByteArray(value: ByteArray?) {
		writeBoolean(value != null)
		if (value != null) writeByteArray(value)
	}

	private fun DataInputStream.readByteArray(): ByteArray {
		val size = readInt()
		require(size in 0..MAX_RECEIPT_PART_BYTES)
		return ByteArray(size).also(::readFully)
	}

	private fun DataInputStream.readNullableByteArray(): ByteArray? =
		if (readBoolean()) readByteArray() else null

	private const val MAX_RECEIPT_PART_BYTES = 256 * 1024
}

private fun LocationObservation.canonicalReceiptBytes(): ByteArray =
	canonicalLocationReceiptBytes {
		writeLong(id)
		writeLong(fixTimeMs)
		writeLong(fixElapsedRealtimeNanos)
		writeLong(receivedAtMs)
		writeLong(receivedElapsedRealtimeNanos)
		writeNullableLong(deliveryAgeMs)
		writeNullableInt(latE7)
		writeNullableInt(lonE7)
		writeNullableFloat(rawAltitudeM)
		writeNullableFloat(hAccM)
		writeNullableFloat(vAccM)
		writeNullableFloat(speedMps)
		writeNullableFloat(speedAccuracyMps)
		writeUTF(provider)
		writeUTF(acquisitionMode)
		writeUTF(requestPriority)
		writeUTF(permissionPrecision)
		writeInt(batchIndex)
		writeInt(batchSize)
		writeBoolean(isMock)
		writeUTF(ingressDisposition)
		writeInt(estimatorVersion)
		writeInt(calibrationVersion)
		writeLong(createdAt)
		writeNullableString(sourceSignalId)
		writeNullableString(sourceEventId)
		writeNullableString(callbackId)
		writeNullableString(clockDomainId)
		writeLong(sourceRevision)
		writeNullableFloat(bearingDeg)
		writeNullableFloat(bearingAccuracyDeg)
		writeNullableString(bootClockDomainId)
	}

private fun LocationObservationDecision.canonicalReceiptBytes(): ByteArray =
	canonicalLocationReceiptBytes {
		writeLong(id)
		writeUTF(observationSourceEventId)
		writeUTF(decision)
		writeNullableString(reason)
		writeInt(decisionVersion)
		writeNullableString(acceptedSampleSourceSignalId)
		writeUTF(sourceSignalId)
		writeNullableString(clockDomainId)
		writeLong(decidedAtMs)
		writeLong(sourceRevision)
	}

private fun LocationObservationDecision.normalizedCanonicalReceiptBytes(): ByteArray =
	copy(id = 0L, sourceRevision = 0L).canonicalReceiptBytes()

private fun StoredLocationSample.canonicalReceiptBytes(): ByteArray =
	canonicalLocationReceiptBytes {
		writeLong(id)
		writeLong(timeMs)
		writeLong(elapsedRealtimeNanos)
		writeNullableInt(latE7)
		writeNullableInt(lonE7)
		writeNullableFloat(altitudeM)
		writeNullableFloat(rawGpsAltitudeM)
		writeNullableFloat(hAccM)
		writeNullableFloat(vAccM)
		writeNullableFloat(speedMps)
		writeNullableFloat(speedAccuracyMps)
		writeUTF(provider)
		writeUTF(quality.name)
		writeNullableString(motionState?.name)
		writeNullableString(policy)
		writeNullableLong(bucketId)
		writeLong(createdAt)
		writeLong(receivedElapsedRealtimeNanos)
		writeNullableLong(deliveryAgeMs)
		writeUTF(acquisitionMode)
		writeUTF(requestPriority)
		writeUTF(permissionPrecision)
		writeInt(batchIndex)
		writeInt(batchSize)
		writeBoolean(isMock)
		writeInt(estimatorVersion)
		writeInt(calibrationVersion)
		writeNullableString(sourceSignalId)
		writeNullableString(sourceEventId)
		writeNullableString(clockDomainId)
		writeLong(sourceRevision)
		writeUTF(altitudeDatum.name)
		writeUTF(altitudeSource.name)
		writeUTF(altitudeConversionStatus.name)
		writeUTF(rawGpsAltitudeDatum.name)
		writeInt(altitudeModelVersion)
		writeNullableFloat(rawPlatformSpeedMps)
		writeNullableFloat(rawPlatformSpeedAccuracyMps)
		writeNullableFloat(bearingDeg)
		writeNullableFloat(bearingAccuracyDeg)
		writeNullableString(bootClockDomainId)
	}

private fun StoredLocationSample.normalizedCanonicalReceiptBytes(): ByteArray =
	copy(id = 0L, sourceRevision = 0L).canonicalReceiptBytes()

private inline fun canonicalLocationReceiptBytes(
	write: DataOutputStream.() -> Unit,
): ByteArray = ByteArrayOutputStream().use { bytes ->
	DataOutputStream(bytes).use { output -> output.write() }
	bytes.toByteArray()
}

private fun DataOutputStream.writeNullableString(value: String?) {
	writeBoolean(value != null)
	if (value != null) writeUTF(value)
}

private fun DataOutputStream.writeNullableLong(value: Long?) {
	writeBoolean(value != null)
	if (value != null) writeLong(value)
}

private fun DataOutputStream.writeNullableInt(value: Int?) {
	writeBoolean(value != null)
	if (value != null) writeInt(value)
}

private fun DataOutputStream.writeNullableFloat(value: Float?) {
	writeBoolean(value != null)
	if (value != null) writeInt(java.lang.Float.floatToRawIntBits(value))
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
	this == null -> other == null
	other == null -> false
	else -> contentEquals(other)
}

private fun ByteArray.withSha256Trailer(): ByteArray =
	this + MessageDigest.getInstance("SHA-256").digest(this)

private fun ByteArray.verifiedSha256Body(): ByteArray {
	require(size >= SHA_256_BYTES)
	val body = copyOfRange(0, size - SHA_256_BYTES)
	val expected = copyOfRange(size - SHA_256_BYTES, size)
	require(MessageDigest.getInstance("SHA-256").digest(body).contentEquals(expected))
	return body
}

private fun protectedLocationReceiptKey(sourceEventId: String): String =
	"canonical-receipt:$sourceEventId"

private fun protectedLocationPreparedCurationKey(sourceEventId: String): String =
	"prepared-curation:$sourceEventId"

private fun protectedLocationRunCurationKey(
	logicalTrackingId: String,
	serviceRunId: String,
): String = "curation-state:$logicalTrackingId:$serviceRunId"

private const val SHA_256_BYTES = 32

private fun protectedLocationContinuityKey(
	registration: ProtectedLocationRegistrationIdentity,
): String = buildString {
	append("continuity:")
	append(registration.logicalTrackingId.length)
	append(':')
	append(registration.logicalTrackingId)
	append(':')
	append(registration.serviceRunId.length)
	append(':')
	append(registration.serviceRunId)
	append(':')
	append(registration.sourceInstanceId.length)
	append(':')
	append(registration.sourceInstanceId)
	append(':')
	append(registration.registrationGeneration)
}

private suspend fun AppDatabase.loadLocationContinuityAnchor(
	registration: ProtectedLocationRegistrationIdentity,
): ProtectedLocationContinuityAnchor? {
	val stored = sourceProjectionStateDao().joinState(
		ProtectedLocationCanonicalHandoff.WRITER_ID,
		ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		protectedLocationContinuityKey(registration),
	) ?: return null
	check(stored.logicalTrackingId == registration.logicalTrackingId &&
		stored.payloadVersion == ProtectedLocationContinuityAnchorCodec.VERSION
	) {
		"Protected Location continuity anchor authority changed"
	}
	return ProtectedLocationContinuityAnchorCodec.decode(stored.payload)
}

private suspend fun AppDatabase.saveLocationContinuityAnchor(
	command: LocationCapturedFactCommand,
) {
	val evidence = command.productEffect.durableEvidence
	val registration = ProtectedLocationRegistrationIdentity(
		command.authority.sourceInstanceId.value,
		command.authority.registrationGeneration,
		command.authority.logicalTrackingId.value,
		command.authority.serviceRunId.value,
	)
	val anchor = ProtectedLocationContinuityAnchor(
		admissionOrdinal = evidence.sourceAdmissionOrdinal,
		sourceSequence = evidence.sourceSequence,
	)
	val entity = SourceProjectionJoinStateEntity(
		projectionId = ProtectedLocationCanonicalHandoff.WRITER_ID,
		projectionVersion = ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		stateKey = protectedLocationContinuityKey(registration),
		logicalTrackingId = command.authority.logicalTrackingId.value,
		minimumRequiredOrdinal = runCatching {
			Math.addExact(evidence.sourceAdmissionOrdinal, 1L)
		}.getOrDefault(Long.MAX_VALUE),
		payloadVersion = ProtectedLocationContinuityAnchorCodec.VERSION,
		payload = ProtectedLocationContinuityAnchorCodec.encode(anchor),
		updatedAtMs = System.currentTimeMillis().coerceAtLeast(0L),
	)
	if (sourceProjectionStateDao().insertJoinStateIfAbsent(entity) != -1L) return
	val existing = requireNotNull(sourceProjectionStateDao().joinState(
		entity.projectionId,
		entity.projectionVersion,
		entity.stateKey,
	))
	when {
		existing.minimumRequiredOrdinal > entity.minimumRequiredOrdinal -> return
		existing.minimumRequiredOrdinal == entity.minimumRequiredOrdinal -> check(
			existing.logicalTrackingId == entity.logicalTrackingId &&
				existing.payloadVersion == entity.payloadVersion &&
				existing.payload.contentEquals(entity.payload),
		) {
			"Protected Location continuity anchor cannot change at one ordinal"
		}
		else -> sourceProjectionStateDao().saveJoinState(entity)
	}
}

private object ProtectedLocationContinuityAnchorCodec {
	const val VERSION = 1
	private const val MAGIC = 0x504c4341

	fun encode(anchor: ProtectedLocationContinuityAnchor): ByteArray =
		canonicalLocationReceiptBytes {
			writeInt(MAGIC)
			writeInt(VERSION)
			writeLong(anchor.admissionOrdinal)
			writeLong(anchor.sourceSequence)
		}.withSha256Trailer()

	fun decode(payload: ByteArray): ProtectedLocationContinuityAnchor =
		DataInputStream(ByteArrayInputStream(payload.verifiedSha256Body())).use { input ->
			require(input.readInt() == MAGIC)
			require(input.readInt() == VERSION)
			val anchor = ProtectedLocationContinuityAnchor(
				admissionOrdinal = input.readLong(),
				sourceSequence = input.readLong(),
			)
			require(anchor.admissionOrdinal > 0L && anchor.sourceSequence > 0L)
			require(input.available() == 0)
			anchor
		}
}

internal data class ProtectedLocationCommittedCurationState(
	val committedThroughOrdinal: Long,
	val state: LocationCanonicalCurationState,
)

internal suspend fun AppDatabase.loadProtectedLocationCanonicalCurationState(
	command: LocationCapturedFactCommand,
): LocationCanonicalCurationState {
	val checkpoint = loadProtectedLocationCommittedCurationState(
		command.authority.logicalTrackingId.value,
		command.authority.serviceRunId.value,
	) ?: return LocationCanonicalCurationState.EMPTY
	check(
		checkpoint.committedThroughOrdinal <
			command.mutation.identity.sourceAdmissionOrdinal,
	) {
		"Protected Location curation checkpoint is not before the pending event"
	}
	return checkpoint.state
}

internal suspend fun AppDatabase.loadProtectedLocationCommittedCurationState(
	logicalTrackingId: String,
	serviceRunId: String,
): ProtectedLocationCommittedCurationState? {
	val stored = sourceProjectionStateDao().joinState(
		ProtectedLocationCanonicalHandoff.WRITER_ID,
		ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		protectedLocationRunCurationKey(
			logicalTrackingId,
			serviceRunId,
		),
	) ?: return null
	check(stored.logicalTrackingId == logicalTrackingId &&
		stored.payloadVersion == ProtectedLocationCanonicalCurationStateCodec.VERSION &&
		stored.minimumRequiredOrdinal > 0L
	) {
		"Protected Location curation state authority changed"
	}
	return ProtectedLocationCommittedCurationState(
		committedThroughOrdinal = stored.minimumRequiredOrdinal - 1L,
		state = ProtectedLocationCanonicalCurationStateCodec.decode(stored.payload),
	)
}

private suspend fun AppDatabase.saveProtectedLocationRunCurationCheckpoint(
	logicalTrackingId: String,
	serviceRunId: String,
	committedThroughOrdinal: Long,
	payload: ByteArray,
) {
	val nextOrdinal = runCatching {
		Math.addExact(committedThroughOrdinal, 1L)
	}.getOrDefault(Long.MAX_VALUE)
	val entity = SourceProjectionJoinStateEntity(
		projectionId = ProtectedLocationCanonicalHandoff.WRITER_ID,
		projectionVersion = ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		stateKey = protectedLocationRunCurationKey(logicalTrackingId, serviceRunId),
		logicalTrackingId = logicalTrackingId,
		minimumRequiredOrdinal = nextOrdinal,
		payloadVersion = ProtectedLocationCanonicalCurationStateCodec.VERSION,
		payload = payload,
		updatedAtMs = System.currentTimeMillis().coerceAtLeast(0L),
	)
	if (sourceProjectionStateDao().insertJoinStateIfAbsent(entity) != -1L) return
	val existing = requireNotNull(sourceProjectionStateDao().joinState(
		entity.projectionId,
		entity.projectionVersion,
		entity.stateKey,
	))
	when {
		existing.minimumRequiredOrdinal > entity.minimumRequiredOrdinal -> return
		existing.minimumRequiredOrdinal == entity.minimumRequiredOrdinal -> check(
			existing.logicalTrackingId == entity.logicalTrackingId &&
				existing.payloadVersion == entity.payloadVersion &&
				existing.payload.contentEquals(entity.payload),
		) {
			"Protected Location curation checkpoint cannot change at one ordinal"
		}
		else -> sourceProjectionStateDao().saveJoinState(entity)
	}
}

internal suspend fun AppDatabase.prepareProtectedLocationCanonicalCurationState(
	command: LocationCapturedFactCommand,
	stateBefore: LocationCanonicalCurationState,
	stateAfter: LocationCanonicalCurationState,
	expectedOutput: ProtectedLocationPreparedCanonicalOutput,
) {
	requireProtectedLocationWriterAuthority()
	val entity = SourceProjectionJoinStateEntity(
		projectionId = ProtectedLocationCanonicalHandoff.WRITER_ID,
		projectionVersion = ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		stateKey = protectedLocationPreparedCurationKey(
			command.mutation.identity.sourceEventId.value,
		),
		logicalTrackingId = command.authority.logicalTrackingId.value,
		minimumRequiredOrdinal = command.mutation.identity.sourceAdmissionOrdinal,
		payloadVersion = ProtectedLocationPreparedCurationStateCodec.VERSION,
		payload = ProtectedLocationPreparedCurationStateCodec.encode(
			ProtectedLocationPreparedCurationState(
				stateBefore,
				stateAfter,
				expectedOutput,
			),
		),
		updatedAtMs = System.currentTimeMillis().coerceAtLeast(0L),
	)
	val inserted = sourceProjectionStateDao().insertJoinStateIfAbsent(entity)
	if (inserted == -1L) {
		val existing = requireNotNull(sourceProjectionStateDao().joinState(
			entity.projectionId,
			entity.projectionVersion,
			entity.stateKey,
		)) {
			"Protected Location prepared curation disappeared after insert conflict"
		}
		check(existing.projectionId == entity.projectionId &&
			existing.projectionVersion == entity.projectionVersion &&
			existing.stateKey == entity.stateKey &&
			existing.logicalTrackingId == entity.logicalTrackingId &&
			existing.minimumRequiredOrdinal == entity.minimumRequiredOrdinal &&
			existing.payloadVersion == entity.payloadVersion &&
			existing.payload.contentEquals(entity.payload)
		) {
			"Protected Location prepared event cannot be replaced with different output"
		}
	}
	requireProtectedLocationWriterAuthority()
}

internal suspend fun AppDatabase.loadPreparedProtectedLocationCanonicalCurationState(
	command: LocationCapturedFactCommand,
): ProtectedLocationPreparedCurationState? {
	val stored = sourceProjectionStateDao().joinState(
		ProtectedLocationCanonicalHandoff.WRITER_ID,
		ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		protectedLocationPreparedCurationKey(
			command.mutation.identity.sourceEventId.value,
		),
	) ?: return null
	check(stored.logicalTrackingId == command.authority.logicalTrackingId.value &&
		stored.minimumRequiredOrdinal == command.mutation.identity.sourceAdmissionOrdinal &&
		stored.payloadVersion == ProtectedLocationPreparedCurationStateCodec.VERSION
	) {
		"Protected Location prepared curation state authority changed"
	}
	return ProtectedLocationPreparedCurationStateCodec.decode(stored.payload)
}

internal fun LocationWalAcquisitionMetadata.toCanonicalCurationContext(
	stateBefore: LocationCanonicalCurationState,
): LocationCanonicalCurationContext {
	check(isQualified)
	check(curationVersion == PROTECTED_LOCATION_CANONICAL_CURATION_VERSION &&
		altitudeModelVersion ==
		com.adsamcik.tracker.shared.model.AltitudeContractVersions.MODEL_VERSION &&
		altitudeEstimatorVersion ==
		com.adsamcik.tracker.shared.model.AltitudeContractVersions.ESTIMATOR_VERSION &&
		altitudeCalibrationVersion ==
		com.adsamcik.tracker.shared.model.AltitudeContractVersions.CALIBRATION_VERSION
	) {
		"Protected Location curation contract is unsupported by this writer"
	}
	return LocationCanonicalCurationContext(
		requiredAccuracyMeters = requiredAccuracyMeters,
		policyTier = policyTier,
		policyName = policyName,
		curationVersion = curationVersion,
		altitudeModelVersion = altitudeModelVersion,
		altitudeEstimatorVersion = altitudeEstimatorVersion,
		altitudeCalibrationVersion = altitudeCalibrationVersion,
		stateBefore = stateBefore,
	)
}

private object ProtectedLocationCanonicalCurationStateCodec {
	const val VERSION = 2
	private const val MAGIC = 0x504c4353

	fun encode(state: LocationCanonicalCurationState): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(MAGIC)
				output.writeInt(VERSION)
				output.writeNullablePoint(state.lastAccepted)
				output.writeNullablePoint(state.pendingReacquisition)
				output.writeInt(java.lang.Float.floatToRawIntBits(state.lastSmoothedSpeedMps))
				output.writeAltitudeProcessorState(state.altitudeProcessorState)
			}
			bytes.toByteArray().withSha256Trailer()
		}

	fun decode(payload: ByteArray): LocationCanonicalCurationState =
		DataInputStream(ByteArrayInputStream(payload.verifiedSha256Body())).use { input ->
			require(input.readInt() == MAGIC)
			require(input.readInt() == VERSION)
			val state = LocationCanonicalCurationState(
				lastAccepted = input.readNullablePoint(),
				pendingReacquisition = input.readNullablePoint(),
				lastSmoothedSpeedMps = java.lang.Float.intBitsToFloat(input.readInt()),
				altitudeProcessorState = input.readAltitudeProcessorState(),
			)
			require(input.available() == 0)
			state
		}

	private fun DataOutputStream.writeNullablePoint(point: LocationCanonicalCurationPoint?) {
		writeBoolean(point != null)
		if (point == null) return
		writeUTF(point.provider)
		writeLong(point.timeMs)
		writeLong(point.elapsedRealtimeNanos)
		writeLong(java.lang.Double.doubleToRawLongBits(point.latitude))
		writeLong(java.lang.Double.doubleToRawLongBits(point.longitude))
		writeNullableFloat(point.accuracyM)
		writeNullableDouble(point.altitudeM)
		writeNullableFloat(point.verticalAccuracyM)
		writeNullableFloat(point.speedMps)
		writeNullableFloat(point.speedAccuracyMps)
		writeNullableFloat(point.bearingDegrees)
		writeNullableFloat(point.bearingAccuracyDegrees)
	}

	private fun DataInputStream.readNullablePoint(): LocationCanonicalCurationPoint? {
		if (!readBoolean()) return null
		return LocationCanonicalCurationPoint(
			provider = readUTF(),
			timeMs = readLong(),
			elapsedRealtimeNanos = readLong(),
			latitude = java.lang.Double.longBitsToDouble(readLong()),
			longitude = java.lang.Double.longBitsToDouble(readLong()),
			accuracyM = readNullableFloat(),
			altitudeM = readNullableDouble(),
			verticalAccuracyM = readNullableFloat(),
			speedMps = readNullableFloat(),
			speedAccuracyMps = readNullableFloat(),
			bearingDegrees = readNullableFloat(),
			bearingAccuracyDegrees = readNullableFloat(),
		)
	}

	private fun DataOutputStream.writeNullableDouble(value: Double?) {
		writeBoolean(value != null)
		if (value != null) writeLong(java.lang.Double.doubleToRawLongBits(value))
	}

	private fun DataInputStream.readNullableDouble(): Double? =
		if (readBoolean()) java.lang.Double.longBitsToDouble(readLong()) else null

	private fun DataInputStream.readNullableFloat(): Float? =
		if (readBoolean()) java.lang.Float.intBitsToFloat(readInt()) else null

	private fun DataOutputStream.writeAltitudeProcessorState(state: AltitudeProcessorState) {
		writeInt(state.version)
		writeInt(java.lang.Float.floatToRawIntBits(state.verticalAccuracyThresholdM))
		writeInt(state.modelVersion)
		writeInt(state.estimatorVersion)
		writeAltitudeFusionState(state.fusionState)
	}

	private fun DataInputStream.readAltitudeProcessorState(): AltitudeProcessorState =
		AltitudeProcessorState(
			version = readInt(),
			verticalAccuracyThresholdM = java.lang.Float.intBitsToFloat(readInt()),
			modelVersion = readInt(),
			estimatorVersion = readInt(),
			fusionState = readAltitudeFusionState(),
		)

	private fun DataOutputStream.writeAltitudeFusionState(state: AltitudeFusionState) {
		writeInt(state.version)
		writeLong(state.recalibrationIntervalMs)
		writeLong(java.lang.Double.doubleToRawLongBits(state.defaultGpsMeasurementNoiseM2))
		writeLong(java.lang.Double.doubleToRawLongBits(state.barometerMeasurementNoiseM2))
		writeNullableDouble(state.calibratedSeaLevelPressureHpa)
		writeLong(state.lastCalibrationElapsedTimeMs)
		writeNullableDouble(state.previousBarometerAltitudeM)
		writeUTF(state.lastEstimateDatum.name)
		writeAltitudeKalmanState(state.kalmanState)
	}

	private fun DataInputStream.readAltitudeFusionState(): AltitudeFusionState =
		AltitudeFusionState(
			version = readInt(),
			recalibrationIntervalMs = readLong(),
			defaultGpsMeasurementNoiseM2 = java.lang.Double.longBitsToDouble(readLong()),
			barometerMeasurementNoiseM2 = java.lang.Double.longBitsToDouble(readLong()),
			calibratedSeaLevelPressureHpa = readNullableDouble(),
			lastCalibrationElapsedTimeMs = readLong(),
			previousBarometerAltitudeM = readNullableDouble(),
			lastEstimateDatum = com.adsamcik.tracker.shared.model.AltitudeDatum.valueOf(readUTF()),
			kalmanState = readAltitudeKalmanState(),
		)

	private fun DataOutputStream.writeAltitudeKalmanState(state: AltitudeKalmanState) {
		writeInt(state.version)
		writeLong(java.lang.Double.doubleToRawLongBits(state.processNoiseAltitude))
		writeLong(java.lang.Double.doubleToRawLongBits(state.processNoiseVelocity))
		writeLong(java.lang.Double.doubleToRawLongBits(state.altitudeM))
		writeLong(java.lang.Double.doubleToRawLongBits(state.verticalVelocityMps))
		writeLong(java.lang.Double.doubleToRawLongBits(state.covariance00))
		writeLong(java.lang.Double.doubleToRawLongBits(state.covariance01))
		writeLong(java.lang.Double.doubleToRawLongBits(state.covariance10))
		writeLong(java.lang.Double.doubleToRawLongBits(state.covariance11))
		writeLong(state.lastElapsedTimeMs)
		writeBoolean(state.initialized)
	}

	private fun DataInputStream.readAltitudeKalmanState(): AltitudeKalmanState =
		AltitudeKalmanState(
			version = readInt(),
			processNoiseAltitude = java.lang.Double.longBitsToDouble(readLong()),
			processNoiseVelocity = java.lang.Double.longBitsToDouble(readLong()),
			altitudeM = java.lang.Double.longBitsToDouble(readLong()),
			verticalVelocityMps = java.lang.Double.longBitsToDouble(readLong()),
			covariance00 = java.lang.Double.longBitsToDouble(readLong()),
			covariance01 = java.lang.Double.longBitsToDouble(readLong()),
			covariance10 = java.lang.Double.longBitsToDouble(readLong()),
			covariance11 = java.lang.Double.longBitsToDouble(readLong()),
			lastElapsedTimeMs = readLong(),
			initialized = readBoolean(),
		)
}

internal data class ProtectedLocationPreparedCurationState(
	val stateBefore: LocationCanonicalCurationState,
	val stateAfter: LocationCanonicalCurationState,
	val expectedOutput: ProtectedLocationPreparedCanonicalOutput,
)

internal data class ProtectedLocationPreparedCanonicalOutput(
	val decision: ByteArray,
	val sample: ByteArray?,
) {
	fun contentEquals(other: ProtectedLocationPreparedCanonicalOutput?): Boolean =
		other != null &&
			decision.contentEquals(other.decision) &&
			sample.contentEqualsNullable(other.sample)

	fun matches(
		decision: LocationObservationDecision,
		sample: StoredLocationSample?,
	): Boolean =
		this.decision.contentEquals(decision.normalizedCanonicalReceiptBytes()) &&
			this.sample.contentEqualsNullable(sample?.normalizedCanonicalReceiptBytes())

	companion object {
		fun fromDestinations(
			decision: LocationObservationDecision,
			sample: StoredLocationSample?,
		): ProtectedLocationPreparedCanonicalOutput =
			ProtectedLocationPreparedCanonicalOutput(
				decision = decision.normalizedCanonicalReceiptBytes(),
				sample = sample?.normalizedCanonicalReceiptBytes(),
			)

		fun fromSignal(
			command: LocationCapturedFactCommand,
			signal: TrackingSignal,
		): ProtectedLocationPreparedCanonicalOutput {
			val eventId = command.mutation.identity.sourceEventId.value
			val signalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(eventId)
			require(signal.persistenceSignalId == signalId)
			val decisionSignal = requireNotNull(signal.locationDecision)
			require(decisionSignal.sourceEventId == eventId)
			val accepted = decisionSignal.decision == LocationDecision.ACCEPTED
			val decision = LocationObservationDecision(
				observationSourceEventId = eventId,
				decision = decisionSignal.decision.name,
				reason = decisionSignal.reason,
				acceptedSampleSourceSignalId = signalId.takeIf { accepted },
				sourceSignalId = signalId,
				clockDomainId = signal.clockDomainId,
				decidedAtMs = signal.timestampMs.raw,
			)
			val sample = if (accepted) {
				val location = requireNotNull(signal.location)
				StoredLocationSample(
					timeMs = signal.timestampMs.raw,
					elapsedRealtimeNanos = signal.elapsedRealtimeNanos,
					latE7 = location.coordinate.lat.raw,
					lonE7 = location.coordinate.lon.raw,
					altitudeM = location.altitudeM,
					rawGpsAltitudeM = location.rawGpsAltitudeM,
					hAccM = location.horizontalAccuracyM,
					vAccM = location.verticalAccuracyM,
					speedMps = location.speed?.raw,
					speedAccuracyMps = location.speedAccuracyMps,
					provider = location.provider,
					quality = expectedStoredLocationQuality(location.horizontalAccuracyM),
					motionState = null,
					policy = signal.policy?.policyName,
					bucketId = null,
					createdAt = signal.timestampMs.raw,
					receivedElapsedRealtimeNanos = location.receivedElapsedRealtimeNanos,
					deliveryAgeMs = protectedLocationDeliveryAgeMs(
						signal.elapsedRealtimeNanos,
						location.receivedElapsedRealtimeNanos,
					),
					acquisitionMode = location.acquisitionMode,
					requestPriority = location.requestPriority,
					permissionPrecision = location.permissionPrecision,
					batchIndex = location.batchIndex,
					batchSize = location.batchSize,
					isMock = location.isMock,
					estimatorVersion = location.altitudeEstimatorVersion,
					calibrationVersion = location.altitudeCalibrationVersion,
					sourceSignalId = signalId,
					sourceEventId = location.sourceEventId,
					clockDomainId = signal.clockDomainId,
					altitudeDatum = location.altitudeDatum,
					altitudeSource = location.altitudeSource,
					altitudeConversionStatus = location.altitudeConversionStatus,
					rawGpsAltitudeDatum = location.rawGpsAltitudeDatum,
					altitudeModelVersion = location.altitudeModelVersion,
					rawPlatformSpeedMps = location.rawPlatformSpeedMps,
					rawPlatformSpeedAccuracyMps = location.rawPlatformSpeedAccuracyMps,
					bearingDeg = location.bearingDeg,
					bearingAccuracyDeg = location.bearingAccuracyDeg,
					bootClockDomainId = signal.bootClockDomainId,
				)
			} else {
				require(signal.location == null)
				null
			}
			return fromDestinations(decision, sample)
		}
	}
}

private object ProtectedLocationPreparedCurationStateCodec {
	const val VERSION = 2
	private const val MAGIC = 0x504c4350

	fun encode(state: ProtectedLocationPreparedCurationState): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(MAGIC)
				output.writeInt(VERSION)
				output.writeByteArray(
					ProtectedLocationCanonicalCurationStateCodec.encode(state.stateBefore),
				)
				output.writeByteArray(
					ProtectedLocationCanonicalCurationStateCodec.encode(state.stateAfter),
				)
				output.writeByteArray(state.expectedOutput.decision)
				output.writeNullableByteArray(state.expectedOutput.sample)
			}
			bytes.toByteArray().withSha256Trailer()
		}

	fun decode(payload: ByteArray): ProtectedLocationPreparedCurationState =
		DataInputStream(ByteArrayInputStream(payload.verifiedSha256Body())).use { input ->
			require(input.readInt() == MAGIC)
			require(input.readInt() == VERSION)
			val state = ProtectedLocationPreparedCurationState(
				stateBefore = ProtectedLocationCanonicalCurationStateCodec.decode(
					input.readByteArray(),
				),
				stateAfter = ProtectedLocationCanonicalCurationStateCodec.decode(
					input.readByteArray(),
				),
				expectedOutput = ProtectedLocationPreparedCanonicalOutput(
					decision = input.readByteArray(),
					sample = input.readNullableByteArray(),
				),
			)
			require(input.available() == 0)
			state
		}

	private fun DataOutputStream.writeByteArray(value: ByteArray) {
		writeInt(value.size)
		write(value)
	}

	private fun DataInputStream.readByteArray(): ByteArray {
		val size = readInt()
		require(size in 0..MAX_PREPARED_STATE_BYTES)
		return ByteArray(size).also(::readFully)
	}

	private fun DataOutputStream.writeNullableByteArray(value: ByteArray?) {
		writeBoolean(value != null)
		if (value != null) writeByteArray(value)
	}

	private fun DataInputStream.readNullableByteArray(): ByteArray? =
		if (readBoolean()) readByteArray() else null

	private const val MAX_PREPARED_STATE_BYTES = 256 * 1024
}

internal fun LocationCapturedFactCommand.toProtectedLocationTrackingCycle(
	acquisitionMetadata: LocationWalAcquisitionMetadata,
	curationContext: LocationCanonicalCurationContext,
): TrackingCycle {
	require(!productEffect.isMock) {
		"Mock Location evidence must take the explicit canonical rejection path"
	}
	val evidence = productEffect.durableEvidence
	val clock = evidence.clockAuthority
	val receivedWallTimeMs = requireNotNull(clock.receivedWallTimeMs) {
		"Protected Location evidence requires callback receipt wall time"
	}
	val payload = evidence.payload
	val location = Location(payload.provider).apply {
		time = clock.observedWallTimeMs
		elapsedRealtimeNanos = clock.observedElapsedRealtimeNanos
		latitude = payload.latitudeDegrees
		longitude = payload.longitudeDegrees
		accuracy = payload.horizontalAccuracyMeters
		payload.altitudeMeters?.let { altitude = it }
		payload.verticalAccuracyMeters?.let { verticalAccuracyMeters = it }
		payload.speedMetersPerSecond?.let { speed = it }
		payload.bearingDegrees?.let { bearing = it }
	}
	val metadata = LocationFixMetadata(
		callbackId = evidence.sourceDeliveryIdentity?.value,
		sourceEventId = evidence.sourceEventId.value,
		clockDomainId = clock.clockDomainId,
		bootClockDomainId = clock.clockDomainId,
		receivedAtMs = receivedWallTimeMs,
		receivedElapsedRealtimeNanos = clock.receivedElapsedRealtimeNanos,
		acquisitionMode = acquisitionMetadata.acquisitionMode,
		requestPriority = acquisitionMetadata.requestPriority,
		permissionPrecision = authority.permissionPrecision,
		batchIndex = evidence.deliveryUnitIndex,
		batchSize = evidence.deliveryUnitCount,
	)
	return TrackingCycle(
		timestampMs = clock.observedWallTimeMs,
		elapsedRealtimeNanos = clock.observedElapsedRealtimeNanos,
		location = LocationData(
			locations = listOf(location),
			previousLocation = null,
			distance = null,
			fixMetadata = listOf(metadata),
		),
		locationCanonicalCuration = curationContext,
		persistenceSignalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(
			evidence.sourceEventId.value,
		),
	)
}

internal fun LocationCapturedFactCommand.toProtectedLocationMockRejectionSignal(
	acquisitionMetadata: LocationWalAcquisitionMetadata,
	policyTier: PolicyTier,
	policyName: String?,
): TrackingSignal {
	require(productEffect.isMock)
	require(acquisitionMetadata.isQualified)
	val evidence = productEffect.durableEvidence
	val clock = evidence.clockAuthority
	return TrackingSignal(
		timestampMs = EpochMs(clock.observedWallTimeMs),
		elapsedRealtimeNanos = clock.observedElapsedRealtimeNanos,
		clockDomainId = clock.clockDomainId,
		bootClockDomainId = clock.clockDomainId,
		locationDecision = LocationDecisionSignal(
			sourceEventId = evidence.sourceEventId.value,
			decision = LocationDecision.REJECTED,
			reason = "MOCK_LOCATION_REJECTED",
		),
		policy = PolicySignal(policyTier, policyName),
		persistenceSignalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(
			evidence.sourceEventId.value,
		),
	)
}

private suspend fun AppDatabase.requireProtectedLocationWriterAuthority() {
	val lanes = sourceProjectionStateDao().allActiveProductLanes()
		.filter { it.sourceKind == SourceDestinationOwnerEntity.SOURCE_LOCATION }
	check(lanes.size == 1) { "Protected Location requires exactly one active product lane" }
	val lane = lanes.single()
	check(
		lane.bindingGeneration ==
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_BINDING_GENERATION &&
			lane.projectionId ==
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID &&
			lane.projectionVersion ==
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION &&
			lane.captureModeMask == ProtectedLocationCanonicalHandoff.MANUAL_CAPTURE_MODE_MASK &&
			lane.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL &&
			lane.activatedRolloutRevision > 0L &&
			lane.activationOrdinal > 0L &&
			lane.contiguousAdmissionOrdinal >= lane.activationOrdinal - 1L &&
			lane.captureAdmissionCutoffOrdinal?.let {
				lane.contiguousAdmissionOrdinal <= it
			} != false &&
			lane.retentionRequired &&
			lane.status == SourceProductProjectionLaneEntity.STATUS_ACTIVE &&
			lane.terminalDisposition == null &&
			lane.terminalAtMs == null
	) {
		"Protected Location product lane authority changed"
	}
	check(
		sourceProjectionStateDao().registration(
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID,
			SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION,
		) == null
	) {
		"Protected Location writer conflicts with a global projection registration"
	}
	check(
		sourceDestinationOwnerDao().isExactOwner(
			SourceDestinationOwnerEntity.SOURCE_LOCATION,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION,
			SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE,
			SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
		),
	) {
		"Existing canonical Location writer no longer owns the permanent destination"
	}
}

private fun LocationObservation.matchesQualifiedEvidence(
	command: LocationCapturedFactCommand,
	acquisitionMetadata: LocationWalAcquisitionMetadata,
): Boolean {
	val evidence = command.productEffect.durableEvidence
	val clock = evidence.clockAuthority
	val payload = evidence.payload
	val expectedDeliveryAgeMs =
		(clock.receivedElapsedRealtimeNanos - clock.observedElapsedRealtimeNanos) / 1_000_000L
	return sourceEventId == evidence.sourceEventId.value &&
		ProtectedLocationCanonicalSignalIdentity.isCanonicalRawIdentity(
			sourceSignalId,
			evidence.sourceEventId.value,
		) &&
		fixTimeMs == clock.observedWallTimeMs &&
		fixElapsedRealtimeNanos == clock.observedElapsedRealtimeNanos &&
		receivedAtMs == clock.receivedWallTimeMs &&
		receivedElapsedRealtimeNanos == clock.receivedElapsedRealtimeNanos &&
		deliveryAgeMs == expectedDeliveryAgeMs &&
		latE7 == LatE7.fromDegrees(payload.latitudeDegrees).raw &&
		lonE7 == LonE7.fromDegrees(payload.longitudeDegrees).raw &&
		rawAltitudeM == payload.altitudeMeters?.toFloat() &&
		hAccM == payload.horizontalAccuracyMeters &&
		vAccM == payload.verticalAccuracyMeters &&
		speedMps == payload.speedMetersPerSecond &&
		speedAccuracyMps == null &&
		bearingDeg == payload.bearingDegrees &&
		bearingAccuracyDeg == null &&
		provider == payload.provider &&
		acquisitionMode == acquisitionMetadata.acquisitionMode.name &&
		requestPriority == acquisitionMetadata.requestPriority.name &&
		permissionPrecision == command.authority.permissionPrecision.name &&
		batchIndex == evidence.deliveryUnitIndex &&
		batchSize == evidence.deliveryUnitCount &&
		isMock == evidence.isMock &&
		ingressDisposition == "DELIVERED_VALID" &&
		estimatorVersion == LOCATION_OBSERVATION_ESTIMATOR_VERSION &&
		calibrationVersion == LOCATION_OBSERVATION_CALIBRATION_VERSION &&
		createdAt == clock.observedWallTimeMs &&
		callbackId == evidence.sourceDeliveryIdentity?.value &&
		clockDomainId == clock.clockDomainId &&
		bootClockDomainId == clock.clockDomainId
}

private fun ProtectedLocationCanonicalPendingDecision.matches(
	command: LocationCapturedFactCommand,
	acquisitionMetadata: LocationWalAcquisitionMetadata,
	sample: StoredLocationSample?,
): Boolean {
	val eventId = command.mutation.identity.sourceEventId.value
	val canonicalSignalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(eventId)
	if (sourceEventId != eventId || sourceSignalId != canonicalSignalId ||
		clockDomainId != command.authority.clockDomainId ||
		decisionVersion != LocationObservationDecision.CURRENT_DECISION_VERSION ||
		decidedAtMs != command.productEffect.durableEvidence.clockAuthority.observedWallTimeMs
	) {
		return false
	}
	return when (decision) {
		LocationDecision.ACCEPTED.name ->
			reason == null &&
				acceptedSampleSourceSignalId == canonicalSignalId &&
				sample?.matchesQualifiedEvidence(command, acquisitionMetadata) == true
		LocationDecision.REJECTED.name ->
			!reason.isNullOrBlank() && acceptedSampleSourceSignalId == null && sample == null
		else -> false
	}
}

private fun StoredLocationSample.matchesQualifiedEvidence(
	command: LocationCapturedFactCommand,
	acquisitionMetadata: LocationWalAcquisitionMetadata,
): Boolean {
	val evidence = command.productEffect.durableEvidence
	val payload = evidence.payload
	val canonicalSignalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(
		evidence.sourceEventId.value,
	)
	return sourceSignalId == canonicalSignalId &&
		sourceEventId == evidence.sourceEventId.value &&
		timeMs == evidence.clockAuthority.observedWallTimeMs &&
		elapsedRealtimeNanos == evidence.clockAuthority.observedElapsedRealtimeNanos &&
		latE7 == LatE7.fromDegrees(payload.latitudeDegrees).raw &&
		lonE7 == LonE7.fromDegrees(payload.longitudeDegrees).raw &&
		rawGpsAltitudeM == payload.altitudeMeters?.toFloat() &&
		rawGpsAltitudeDatum == (if (payload.altitudeMeters != null) {
			com.adsamcik.tracker.shared.model.AltitudeDatum.WGS84_ELLIPSOID
		} else {
			com.adsamcik.tracker.shared.model.AltitudeDatum.UNKNOWN_LEGACY
		}) &&
		hAccM == payload.horizontalAccuracyMeters &&
		vAccM == payload.verticalAccuracyMeters &&
		deliveryAgeMs ==
			(evidence.clockAuthority.receivedElapsedRealtimeNanos -
				evidence.clockAuthority.observedElapsedRealtimeNanos) / 1_000_000L &&
		rawPlatformSpeedMps == payload.speedMetersPerSecond &&
		rawPlatformSpeedAccuracyMps == null &&
		bearingDeg == payload.bearingDegrees &&
		bearingAccuracyDeg == null &&
		provider == payload.provider &&
		quality.name == expectedLocationQuality(payload.horizontalAccuracyMeters) &&
		motionState == null &&
		policy == acquisitionMetadata.policyName &&
		createdAt == evidence.clockAuthority.observedWallTimeMs &&
		receivedElapsedRealtimeNanos == evidence.clockAuthority.receivedElapsedRealtimeNanos &&
		acquisitionMode == acquisitionMetadata.acquisitionMode.name &&
		requestPriority == acquisitionMetadata.requestPriority.name &&
		permissionPrecision == command.authority.permissionPrecision.name &&
		batchIndex == evidence.deliveryUnitIndex &&
		batchSize == evidence.deliveryUnitCount &&
		!isMock &&
		estimatorVersion == acquisitionMetadata.altitudeEstimatorVersion &&
		calibrationVersion in 0..acquisitionMetadata.altitudeCalibrationVersion &&
		altitudeModelVersion == acquisitionMetadata.altitudeModelVersion &&
		clockDomainId == evidence.clockAuthority.clockDomainId &&
		bootClockDomainId == evidence.clockAuthority.clockDomainId
}

private fun expectedLocationQuality(horizontalAccuracyMeters: Float): String = when {
	horizontalAccuracyMeters < 10f -> "HIGH"
	horizontalAccuracyMeters < 50f -> "MEDIUM"
	else -> "LOW"
}

private fun expectedStoredLocationQuality(
	horizontalAccuracyMeters: Float,
): com.adsamcik.tracker.shared.base.database.data.SampleQuality =
	com.adsamcik.tracker.shared.base.database.data.SampleQuality.valueOf(
		expectedLocationQuality(horizontalAccuracyMeters),
	)

private fun protectedLocationDeliveryAgeMs(
	fixElapsedRealtimeNanos: Long,
	receivedElapsedRealtimeNanos: Long,
): Long? {
	if (fixElapsedRealtimeNanos <= 0L ||
		receivedElapsedRealtimeNanos <= 0L ||
		fixElapsedRealtimeNanos > receivedElapsedRealtimeNanos
	) {
		return null
	}
	return (receivedElapsedRealtimeNanos - fixElapsedRealtimeNanos) / 1_000_000L
}

private fun SourceProductProjectionLaneEntity.hasSameExecutionBinding(
	other: SourceProductProjectionLaneEntity,
): Boolean = sourceKind == other.sourceKind &&
	bindingGeneration == other.bindingGeneration &&
	projectionId == other.projectionId &&
	projectionVersion == other.projectionVersion &&
	captureModeMask == other.captureModeMask &&
	productStage == other.productStage &&
	activatedRolloutRevision == other.activatedRolloutRevision &&
	activationOrdinal == other.activationOrdinal &&
	captureAdmissionCutoffOrdinal == other.captureAdmissionCutoffOrdinal &&
	retentionRequired == other.retentionRequired &&
	status == other.status &&
	terminalDisposition == other.terminalDisposition &&
	terminalAtMs == other.terminalAtMs &&
	installedAtMs == other.installedAtMs

private fun LocationWalAdapterRejection.isLifecycleSettlement(): Boolean = this in setOf(
	LocationWalAdapterRejection.DELETED_EVIDENCE,
	LocationWalAdapterRejection.RETAINED_EVIDENCE,
	LocationWalAdapterRejection.MISSING_EVENT,
)

private fun Throwable.safeCode(): String =
	javaClass.simpleName.takeIf(String::isNotBlank) ?: "UNKNOWN_LOCATION_HANDOFF_FAILURE"

private sealed interface ProtectedLocationLaneResolution {
	data class Active(val lane: SourceProductProjectionLaneEntity) : ProtectedLocationLaneResolution
	data class Inactive(
		val lastCommittedOrdinal: Long,
		val reason: ProtectedLocationCanonicalInactiveReason,
	) : ProtectedLocationLaneResolution
}

private sealed interface ProtectedLocationNext {
	data class Candidate(
		val eventId: String,
		val admissionOrdinal: Long,
	) : ProtectedLocationNext

	data class Terminal(val failure: SourceProjectionFailureEntity) : ProtectedLocationNext
	data class Advanced(
		val throughOrdinal: Long,
		val lifecycleSettled: Boolean,
	) : ProtectedLocationNext
}

private sealed interface ProtectedLocationCandidateEffect {
	data class Applied(
		val throughOrdinal: Long,
		val accepted: Boolean,
	) : ProtectedLocationCandidateEffect

	data class LifecycleSettled(val throughOrdinal: Long) : ProtectedLocationCandidateEffect
	data class Deferred(val reason: String) : ProtectedLocationCandidateEffect
	data class Failed(
		val failureCode: String,
		val terminal: Boolean,
	) : ProtectedLocationCandidateEffect

	data class AuthorityChanged(val reason: String) : ProtectedLocationCandidateEffect
}

private data class ProtectedLocationQualifiedCandidate(
	val command: LocationCapturedFactCommand,
	val acquisitionMetadata: LocationWalAcquisitionMetadata,
)

private data class ProtectedLocationGlobalDrainSnapshot(
	val targetAdmissionOrdinal: Long,
	val deletedSourceEventHighWaterOrdinal: Long,
	val firstTerminalFailureOrdinal: Long?,
)

private data class ProtectedLocationGlobalContinuityProof(
	val classifiedThroughOrdinal: Long,
	val failure: ProtectedLocationCanonicalDrainResult? = null,
)

private data class ProtectedLocationRegistrationIdentity(
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val logicalTrackingId: String,
	val serviceRunId: String,
)

private data class ProtectedLocationContinuityAnchor(
	val admissionOrdinal: Long,
	val sourceSequence: Long,
)

private fun SourceSessionCompletenessEntity.hasSettledLocationContinuity(): Boolean =
	appDrainComplete &&
		stopStatus == "COMPLETE" &&
		lastSourceSequence != null &&
		unresolvedSequenceStart == null &&
		unresolvedSequenceEnd == null

private class ProtectedLocationAuthorityChangedException(
	val reason: String,
) : IllegalStateException(reason)

private const val LOCATION_OBSERVATION_ESTIMATOR_VERSION = 1
private const val LOCATION_OBSERVATION_CALIBRATION_VERSION = 0
