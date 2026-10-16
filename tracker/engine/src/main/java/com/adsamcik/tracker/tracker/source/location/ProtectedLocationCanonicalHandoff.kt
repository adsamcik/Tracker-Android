@file:Suppress("TooManyFunctions")

package com.adsamcik.tracker.tracker.source.location

import android.location.Location
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceEventWalPayloadPreflightRow
import com.adsamcik.tracker.shared.base.database.dao.SourceEventContinuityRow
import com.adsamcik.tracker.shared.base.database.dao.SourceEventProjectionEligibilityRow
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.LocationObservationDecision
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.LocationSamplePurposeAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.ProtectedLocationCanonicalReceiptCodec as SharedProtectedLocationCanonicalReceiptCodec
import com.adsamcik.tracker.shared.base.database.data.ProtectedLocationCanonicalReceiptPayload
import com.adsamcik.tracker.shared.base.database.data.LocationSample as StoredLocationSample
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionJoinStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.authenticatedProtectedLocationCanonicalCurationState
import com.adsamcik.tracker.shared.base.database.data.protectedLocationCanonicalProductIdentity
import com.adsamcik.tracker.shared.base.database.data.protectedLocationCanonicalReceiptBytes
import com.adsamcik.tracker.shared.base.database.data.protectedLocationCanonicalReceiptStateKey
import com.adsamcik.tracker.shared.base.database.data.protectedLocationPreparedCurationStateKey
import com.adsamcik.tracker.shared.base.database.data.protectedLocationRunCurationStateKey
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
	fun canonicalProduct(sourceEventId: String): String =
		protectedLocationCanonicalProductIdentity(sourceEventId)

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

	/** Finishes retained offline teardown before a drain inspects complete, pending or fenced data. */
	suspend fun retryPendingCleanup(): Boolean = true

	suspend fun writeAmbient(command: LocationAmbientFactCommand): ProtectedLocationCanonicalWriteResult =
		ProtectedLocationCanonicalWriteResult.Inactive("LOCATION_AMBIENT_WRITER_UNAVAILABLE")
}

/**
 * Selects the live serialized writer when available and otherwise uses the quiesced recovery
 * writer. The parent runtime owns the live writer reference and the lifecycle ordering.
 */
internal class ProtectedLocationCanonicalWriterChain(
	private val liveWriter: () -> ProtectedLocationCanonicalWriter?,
	private val offlineWriter: ProtectedLocationCanonicalWriter,
	private val ambientWriter: ProtectedLocationAmbientCanonicalWriter? = null,
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
	override suspend fun retryPendingCleanup(): Boolean = offlineWriter.retryPendingCleanup()

	override suspend fun writeAmbient(command: LocationAmbientFactCommand): ProtectedLocationCanonicalWriteResult =
		ambientWriter?.write(command) ?:
            ProtectedLocationCanonicalWriteResult.Inactive("LOCATION_AMBIENT_WRITER_UNAVAILABLE")

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
    private val onSavedPointCommitted: (Long) -> Unit = {},
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
		retryPendingCleanup(resolution)?.let { return@withLock it }
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
			globalDrainSnapshot(lane)
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

	private suspend fun globalDrainSnapshot(
		lane: SourceProductProjectionLaneEntity,
	): ProtectedLocationGlobalDrainSnapshot =
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

	private suspend fun verifyGlobalLocationContinuity(
		afterOrdinal: Long,
		throughOrdinal: Long,
		deletedSourceEventHighWaterOrdinal: Long,
		firstTerminalFailureOrdinal: Long?,
	): ProtectedLocationGlobalContinuityProof = GlobalContinuityScan(
		afterOrdinal, throughOrdinal, deletedSourceEventHighWaterOrdinal, firstTerminalFailureOrdinal,
	).scan()

	/** One finite scan; only the original traversal cursors and sequence map live for this call. */
	private inner class GlobalContinuityScan(
		private val afterOrdinal: Long,
		private val throughOrdinal: Long,
		deletedSourceEventHighWaterOrdinal: Long,
		private val firstTerminalFailureOrdinal: Long?,
	) {
		private var pageAfter = afterOrdinal
		private var classifiedThroughOrdinal = maxOf(
			afterOrdinal, minOf(throughOrdinal, deletedSourceEventHighWaterOrdinal),
		)
		private var unclassifiedGapOrdinal: Long? = null
		private val expectedByRegistration = mutableMapOf<ProtectedLocationRegistrationIdentity, Long>()

		suspend fun scan(): ProtectedLocationGlobalContinuityProof {
			while (pageAfter < throughOrdinal) {
				val page = database.sourceEventWalDao().continuityEventsAfterThrough(
					afterOrdinal = pageAfter, throughOrdinal = throughOrdinal, limit = CONTINUITY_PAGE_SIZE,
				)
				if (page.isEmpty()) {
					break
				}
				for (row in page) {
					classify(row)?.let { return it }
				}
				pageAfter = page.last().admissionOrdinal
			}
			val nextOrdinal = runCatching { Math.addExact(classifiedThroughOrdinal, 1L) }.getOrNull()
			if (unclassifiedGapOrdinal == null && nextOrdinal != null && firstTerminalFailureOrdinal == nextOrdinal) {
				classifiedThroughOrdinal = nextOrdinal
			}
			return ProtectedLocationGlobalContinuityProof(classifiedThroughOrdinal)
		}

		private suspend fun classify(row: SourceEventContinuityRow): ProtectedLocationGlobalContinuityProof? =
			if (row.admissionOrdinal <= classifiedThroughOrdinal) {
				null
			} else {
				classifyUnvisited(row)
			}

		private suspend fun classifyUnvisited(row: SourceEventContinuityRow): ProtectedLocationGlobalContinuityProof? {
			val gapOrdinal = unclassifiedGapOrdinal
				?: runCatching { Math.addExact(classifiedThroughOrdinal, 1L) }.getOrNull()
				?: return failure(row, "LOCATION_DRAIN_ORDINAL_OVERFLOW")
			if (unclassifiedGapOrdinal != null) {
				return outstandingGapResult(row)
			}
			if (row.admissionOrdinal != gapOrdinal) {
				return classifyNewGap(row, gapOrdinal)
			}
			classifiedThroughOrdinal = row.admissionOrdinal
			return if (row.sourceKind != SOURCE_LOCATION) {
				null
			} else {
				verifySourceSequence(row)
			}
		}

		private fun classifyNewGap(
			row: SourceEventContinuityRow,
			gapOrdinal: Long,
		): ProtectedLocationGlobalContinuityProof? {
			if (firstTerminalFailureOrdinal == gapOrdinal) {
				return ProtectedLocationGlobalContinuityProof(gapOrdinal)
			}
			unclassifiedGapOrdinal = gapOrdinal
			return outstandingGapResult(row)
		}

		private fun outstandingGapResult(row: SourceEventContinuityRow): ProtectedLocationGlobalContinuityProof? =
			if (row.sourceKind == SOURCE_LOCATION) {
				failure(row, "LOCATION_DRAIN_INTERIOR_SEQUENCE_GAP")
			} else {
				null
			}

		private suspend fun verifySourceSequence(row: SourceEventContinuityRow): ProtectedLocationGlobalContinuityProof? {
			val registration = ProtectedLocationRegistrationIdentity(
				row.sourceInstanceId, row.registrationGeneration, row.logicalTrackingId, row.serviceRunId,
			)
			val previous = expectedByRegistration[registration]
				?: loadLocationContinuityAnchor(registration)?.sourceSequence
				?: database.locationPurposeDao().latestInstanceContinuityEvent(
					sourceInstanceId = row.sourceInstanceId, throughOrdinal = afterOrdinal,
				)?.sourceSequence
				?: -1L
			val expected = runCatching { Math.addExact(previous, 1L) }.getOrNull()
				?: return failure(row, "LOCATION_DRAIN_SOURCE_SEQUENCE_OVERFLOW")
			if (row.sourceSequence != expected) {
				return failure(row, "LOCATION_DRAIN_INTERIOR_SEQUENCE_GAP")
			}
			expectedByRegistration[registration] = row.sourceSequence
			return null
		}

		private fun failure(row: SourceEventContinuityRow, code: String) =
			ProtectedLocationGlobalContinuityProof(
				classifiedThroughOrdinal,
				ProtectedLocationCanonicalDrainResult.Failed(afterOrdinal, row.admissionOrdinal, code, terminal = true),
			)
	}

	/**
	 * Drains through an acknowledged source high-water for one finite session.
	 *
	 * Earlier Location events cannot be skipped merely because they belong to another run or
	 * are sessionless. The acknowledged physical prefix is drained with each row's own authority.
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
		retryPendingCleanup(resolution)?.let { return@withLock it }
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
			if (endpointFailure != null) {
				return@withLock endpointFailure
			}
		}
		// The session acknowledges a physical FIFO prefix. Each row retains its own qualified purpose.
		drainLocked(lane, target, null, null)
	}

	private suspend fun retryPendingCleanup(
		resolution: ProtectedLocationLaneResolution,
	): ProtectedLocationCanonicalDrainResult.Failed? {
		if (canonicalWriter.retryPendingCleanup()) {
			return null
		}
		val lastCommittedOrdinal = when (resolution) {
			is ProtectedLocationLaneResolution.Active -> resolution.lane.contiguousAdmissionOrdinal
			is ProtectedLocationLaneResolution.Inactive -> resolution.lastCommittedOrdinal
		}
		return ProtectedLocationCanonicalDrainResult.Failed(
			lastCommittedOrdinal = lastCommittedOrdinal,
			failedOrdinal = null,
			failureCode = "LOCATION_CANONICAL_OFFLINE_CLEANUP_PENDING",
			terminal = false,
		)
	}

	private suspend fun verifyExplicitDrainEndpoint(
		targetAdmissionOrdinal: Long,
		logicalTrackingId: String,
		serviceRunId: String,
		lastCommittedOrdinal: Long,
	): ProtectedLocationCanonicalDrainResult? {
		verifyExplicitSessionEndpoint(targetAdmissionOrdinal, logicalTrackingId, serviceRunId, lastCommittedOrdinal)
			?.let { return it }
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
		return if (endpoint != null) {
			verifyPresentLocationEndpoint(
				targetAdmissionOrdinal, logicalTrackingId, serviceRunId, lastCommittedOrdinal, endpoint, locationCompleteness,
			)
		} else {
			verifyMissingLocationEndpoint(
				targetAdmissionOrdinal, lastCommittedOrdinal, locationCompleteness, evidence.deletedSourceEventHighWaterOrdinal,
			)
		}
	}

	private suspend fun verifyExplicitSessionEndpoint(
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
		return null
	}

	private suspend fun verifyPresentLocationEndpoint(
		targetAdmissionOrdinal: Long,
		logicalTrackingId: String,
		serviceRunId: String,
		lastCommittedOrdinal: Long,
		endpoint: SourceEventProjectionEligibilityRow,
		locationCompleteness: List<SourceSessionCompletenessEntity>,
	): ProtectedLocationCanonicalDrainResult? {
		if (!endpoint.hasLocationEndpointIdentity(logicalTrackingId, serviceRunId)) {
			return ProtectedLocationCanonicalDrainResult.Failed(
				lastCommittedOrdinal,
				targetAdmissionOrdinal,
				"LOCATION_DRAIN_ENDPOINT_IDENTITY_MISMATCH",
				terminal = true,
			)
		}
		if (locationCompleteness.none { it.acknowledgesLocationEndpoint(endpoint, targetAdmissionOrdinal) }) {
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

	private suspend fun verifyMissingLocationEndpoint(
		targetAdmissionOrdinal: Long,
		lastCommittedOrdinal: Long,
		locationCompleteness: List<SourceSessionCompletenessEntity>,
		deletedSourceEventHighWaterOrdinal: Long,
	): ProtectedLocationCanonicalDrainResult? {
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
		if (targetAdmissionOrdinal <= deletedSourceEventHighWaterOrdinal) {
			return null
		}
		val failure = database.sourceProjectionStateDao().failure(
			WRITER_ID,
			WRITER_VERSION,
			targetAdmissionOrdinal,
		)
		if (failure?.terminal == true) {
			return null
		}
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
		val completeness = database.sourceSessionDao().completenessForServiceRun(logicalTrackingId, serviceRunId)
			.filter { it.sourceKind == SOURCE_LOCATION }
		if (completeness.any { !it.hasSettledLocationContinuity() }) {
			return ProtectedLocationCanonicalDrainResult.Deferred(lastCommittedOrdinal, throughOrdinal,
				"LOCATION_DRAIN_COMPLETENESS_UNSETTLED")
		}
		val evidence = database.sourceEvidenceStateDao().get()
			?: return ProtectedLocationCanonicalDrainResult.AuthorityChanged(lastCommittedOrdinal,
                "SOURCE_EVIDENCE_STATE_MISSING")
		val proof = verifyGlobalLocationContinuity(afterOrdinal, throughOrdinal,
            evidence.deletedSourceEventHighWaterOrdinal,
			database.sourceProjectionStateDao().firstTerminalFailureAfterThrough(WRITER_ID, WRITER_VERSION,
				afterOrdinal, throughOrdinal)?.admissionOrdinal)
		return when {
			proof.failure != null -> proof.failure
			proof.classifiedThroughOrdinal < throughOrdinal -> ProtectedLocationCanonicalDrainResult.Deferred(
				lastCommittedOrdinal, throughOrdinal, "LOCATION_DRAIN_TRAILING_RANGE_UNPROVEN",
			)
			else -> verifyObservedLocationCompleteness(
				afterOrdinal, throughOrdinal, logicalTrackingId, serviceRunId, lastCommittedOrdinal, completeness,
			)
		}
	}

	private suspend fun verifyObservedLocationCompleteness(
		afterOrdinal: Long,
		throughOrdinal: Long,
		logicalTrackingId: String,
		serviceRunId: String,
		lastCommittedOrdinal: Long,
		completeness: List<SourceSessionCompletenessEntity>,
	): ProtectedLocationCanonicalDrainResult? {
		// Completeness is session-local; physical continuity includes interleaved ambient and
		// earlier session rows. Never relabel those rows as members of the closing session.
		var pageAfter = afterOrdinal
		while (pageAfter < throughOrdinal) {
			val page = database.sourceEventWalDao().continuityEventsAfterThrough(pageAfter, throughOrdinal,
                CONTINUITY_PAGE_SIZE)
			if (page.isEmpty()) {
				break
			}
			for (row in page) {
				if (row.sourceKind != SOURCE_LOCATION || row.logicalTrackingId != logicalTrackingId ||
                    row.serviceRunId != serviceRunId) {
					continue
				}
				if (completeness.none { it.coversObservedLocation(row) }) {
					return ProtectedLocationCanonicalDrainResult.Deferred(lastCommittedOrdinal, row.admissionOrdinal,
						"LOCATION_DRAIN_OBSERVED_REGISTRATION_MISSING_COMPLETENESS")
				}
			}
			pageAfter = page.last().admissionOrdinal
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

			val terminalResult = when (next) {
				is ProtectedLocationNext.Advanced -> {
					cursor = next.throughOrdinal
					if (next.lifecycleSettled) {
						lifecycleSettled++
					}
					null
				}
				is ProtectedLocationNext.Terminal -> {
					ProtectedLocationCanonicalDrainResult.Failed(
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
						ProtectedLocationCandidateEffect.AuthorityChanged(changed.reason)
					} catch (failure: Exception) {
						ProtectedLocationCandidateEffect.Failed(failure.safeCode(), terminal = false)
					}
					when (effect) {
						is ProtectedLocationCandidateEffect.Applied -> {
							cursor = effect.throughOrdinal
							observations += 1
							if (effect.accepted) {
								accepted++
							} else {
								rejected++
							}
							null
						}
						is ProtectedLocationCandidateEffect.LifecycleSettled -> {
							cursor = effect.throughOrdinal
							lifecycleSettled++
							null
						}
						is ProtectedLocationCandidateEffect.Deferred -> {
							ProtectedLocationCanonicalDrainResult.Deferred(
								cursor,
								next.admissionOrdinal,
								effect.reason,
							)
						}
						is ProtectedLocationCandidateEffect.Failed -> {
							ProtectedLocationCanonicalDrainResult.Failed(
								cursor,
								next.admissionOrdinal,
								effect.failureCode,
								effect.terminal,
							)
						}
						is ProtectedLocationCandidateEffect.AuthorityChanged -> {
							ProtectedLocationCanonicalDrainResult.AuthorityChanged(
								cursor,
								effect.reason,
							)
						}
					}
				}
			}
			if (terminalResult != null) {
				return terminalResult
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
		val ambient = qualifier.qualifyAmbient(SourceEventId(candidate.eventId))
		if (ambient is LocationAmbientQualification.Qualified &&
			ambient.command.wal.authorizationPurposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L
		) {
			return processAmbientCandidate(initialLane, expectedCursor, candidate, ambient.command)
		}
		return when (val adapted = qualifier.qualify(SourceEventId(candidate.eventId))) {
			is LocationWalAdapterResult.Rejected ->
				processRejectedAdapter(initialLane, expectedCursor, candidate, preflight, adapted, ambient)
			is LocationWalAdapterResult.Evaluated -> processEvaluatedCandidate(
				initialLane, expectedCursor, candidate, adapted, ambient, expectedLogicalTrackingId, expectedServiceRunId,
			)
		}
	}

	private suspend fun processRejectedAdapter(
		initialLane: SourceProductProjectionLaneEntity,
		expectedCursor: Long,
		candidate: ProtectedLocationNext.Candidate,
		preflight: SourceEventWalPayloadPreflightRow?,
		adapted: LocationWalAdapterResult.Rejected,
		ambient: LocationAmbientQualification,
	): ProtectedLocationCandidateEffect {
		if (ambient is LocationAmbientQualification.Qualified) {
			return processAmbientCandidate(initialLane, expectedCursor, candidate, ambient.command)
		}
		if (adapted.reason.isLifecycleSettlement() &&
			settleLifecycleRejection(initialLane, expectedCursor, candidate, preflight, adapted.reason)
		) {
			return ProtectedLocationCandidateEffect.LifecycleSettled(candidate.admissionOrdinal)
		}
		return failCandidate(initialLane, expectedCursor, candidate, "LOCATION_ADAPTER_${adapted.reason.name}")
	}

	// The complete existing candidate context plus the two optional expected-run identifiers.
	@Suppress("LongParameterList")
	private suspend fun processEvaluatedCandidate(
		initialLane: SourceProductProjectionLaneEntity,
		expectedCursor: Long,
		candidate: ProtectedLocationNext.Candidate,
		adapted: LocationWalAdapterResult.Evaluated,
		ambient: LocationAmbientQualification,
		expectedLogicalTrackingId: String?,
		expectedServiceRunId: String?,
	): ProtectedLocationCandidateEffect {
		val qualified = when (val qualification = adapted.qualification) {
			is LocationObservationQualification.Qualified ->
				ProtectedLocationQualifiedCandidate(qualification.command, adapted.acquisitionMetadata)
			is LocationObservationQualification.Duplicate ->
				ProtectedLocationQualifiedCandidate(qualification.existing, adapted.acquisitionMetadata)
			is LocationObservationQualification.Stale -> return if (ambient is LocationAmbientQualification.Qualified) {
				processAmbientCandidate(initialLane, expectedCursor, candidate, ambient.command)
			} else {
				failCandidate(
					initialLane, expectedCursor, candidate, "LOCATION_QUALIFICATION_STALE_${qualification.reason.name}",
				)
			}
			is LocationObservationQualification.Unavailable -> return if (ambient is LocationAmbientQualification.Qualified) {
				processAmbientCandidate(initialLane, expectedCursor, candidate, ambient.command)
			} else {
				failCandidate(
					initialLane, expectedCursor, candidate, "LOCATION_QUALIFICATION_UNAVAILABLE_${qualification.reason.name}",
				)
			}
			is LocationObservationQualification.Rejected -> return if (ambient is LocationAmbientQualification.Qualified) {
				processAmbientCandidate(initialLane, expectedCursor, candidate, ambient.command)
			} else {
				failCandidate(
					initialLane, expectedCursor, candidate, "LOCATION_QUALIFICATION_REJECTED_${qualification.reason.name}",
				)
			}
		}
		return processQualifiedCandidate(
			initialLane, expectedCursor, candidate, qualified, ambient, expectedLogicalTrackingId, expectedServiceRunId,
		)
	}

	// Keep the existing candidate/run context intact across admission and receipt phases.
	@Suppress("LongParameterList")
	private suspend fun processQualifiedCandidate(
		initialLane: SourceProductProjectionLaneEntity,
		expectedCursor: Long,
		candidate: ProtectedLocationNext.Candidate,
		qualified: ProtectedLocationQualifiedCandidate,
		ambient: LocationAmbientQualification,
		expectedLogicalTrackingId: String?,
		expectedServiceRunId: String?,
	): ProtectedLocationCandidateEffect {
		val command = qualified.command
		val acquisitionMetadata = qualified.acquisitionMetadata
		if (!acquisitionMetadata.isQualified) {
			return failCandidate(initialLane, expectedCursor, candidate, "LOCATION_ACQUISITION_METADATA_UNVERIFIABLE")
		}
		if (command.mutation.identity.sourceAdmissionOrdinal != candidate.admissionOrdinal ||
			command.mutation.identity.sourceEventId.value != candidate.eventId
		) {
			return failCandidate(initialLane, expectedCursor, candidate, "LOCATION_ADAPTER_IDENTITY_MISMATCH")
		}
		val authority = command.authority
		val isExpectedRun = expectedLogicalTrackingId == null ||
			(authority.logicalTrackingId.value == expectedLogicalTrackingId &&
				authority.serviceRunId.value == expectedServiceRunId)
		val beforeWrite = database.withTransaction {
			requireExactLane(initialLane, expectedCursor)
			database.readProtectedLocationCanonicalReceipt(command, acquisitionMetadata)
		}
		return processCaptureReceipt(initialLane, expectedCursor, candidate, qualified, ambient, isExpectedRun, beforeWrite)
	}

	// One candidate's existing receipt, purpose and expected-run context; no wrapper state.
	@Suppress("LongParameterList")
	private suspend fun processCaptureReceipt(
		initialLane: SourceProductProjectionLaneEntity,
		expectedCursor: Long,
		candidate: ProtectedLocationNext.Candidate,
		qualified: ProtectedLocationQualifiedCandidate,
		ambient: LocationAmbientQualification,
		isExpectedRun: Boolean,
		beforeWrite: ProtectedLocationCanonicalReceipt,
	): ProtectedLocationCandidateEffect {
		settleExistingCaptureReceipt(initialLane, expectedCursor, candidate, qualified, ambient, beforeWrite)
			?.let { return it }
		if (!isExpectedRun) {
			return ProtectedLocationCandidateEffect.Deferred("HISTORICAL_LOCATION_CANONICAL_SESSION_NOT_ACTIVE")
		}
		writeQualifiedCandidate(initialLane, expectedCursor, candidate, qualified)?.let { return it }
		return readCaptureAfterWrite(initialLane, expectedCursor, candidate, qualified, ambient)
	}

	private suspend fun settleExistingCaptureReceipt(
		initialLane: SourceProductProjectionLaneEntity,
		expectedCursor: Long,
		candidate: ProtectedLocationNext.Candidate,
		qualified: ProtectedLocationQualifiedCandidate,
		ambient: LocationAmbientQualification,
		beforeWrite: ProtectedLocationCanonicalReceipt,
	): ProtectedLocationCandidateEffect? {
		if (beforeWrite is ProtectedLocationCanonicalReceipt.Complete) {
			return completeCaptureCandidate(initialLane, expectedCursor, candidate, qualified, beforeWrite, ambient)
		}
		if (ambient is LocationAmbientQualification.Qualified && database.withTransaction {
			requireExactLane(initialLane, expectedCursor)
			canRecoverRetiredAmbientSibling(initialLane, qualified.command, beforeWrite, ambient.command)
		}) {
			return processAmbientCandidate(initialLane, expectedCursor, candidate, ambient.command)
		}
		val invalidReason = when {
			beforeWrite is ProtectedLocationCanonicalReceipt.Invalid -> beforeWrite.reason
			beforeWrite is ProtectedLocationCanonicalReceipt.Incomplete &&
				beforeWrite.reason != "LOCATION_CANONICAL_OBSERVATION_PENDING" -> beforeWrite.reason
			else -> null
		}
		return invalidReason?.let { failCandidate(initialLane, expectedCursor, candidate, it) }
	}

	private suspend fun writeQualifiedCandidate(
		initialLane: SourceProductProjectionLaneEntity,
		expectedCursor: Long,
		candidate: ProtectedLocationNext.Candidate,
		qualified: ProtectedLocationQualifiedCandidate,
	): ProtectedLocationCandidateEffect? =
		when (val write = canonicalWriter.write(qualified.command, qualified.acquisitionMetadata)) {
			ProtectedLocationCanonicalWriteResult.Committed -> null
			is ProtectedLocationCanonicalWriteResult.Deferred -> ProtectedLocationCandidateEffect.Deferred(write.reason)
			is ProtectedLocationCanonicalWriteResult.Inactive -> ProtectedLocationCandidateEffect.Deferred(write.reason)
			is ProtectedLocationCanonicalWriteResult.AuthorityChanged ->
				ProtectedLocationCandidateEffect.AuthorityChanged(write.reason)
			is ProtectedLocationCanonicalWriteResult.Failed -> {
				if (write.terminal) {
					saveFailure(initialLane, expectedCursor, candidate.admissionOrdinal, write.failureCode, terminal = true)
				}
				ProtectedLocationCandidateEffect.Failed(write.failureCode, write.terminal)
			}
		}

	private suspend fun readCaptureAfterWrite(
		initialLane: SourceProductProjectionLaneEntity,
		expectedCursor: Long,
		candidate: ProtectedLocationNext.Candidate,
		qualified: ProtectedLocationQualifiedCandidate,
		ambient: LocationAmbientQualification,
	): ProtectedLocationCandidateEffect = when (val receipt = database.withTransaction {
		requireExactLane(initialLane, expectedCursor)
		database.readProtectedLocationCanonicalReceipt(qualified.command, qualified.acquisitionMetadata)
	}) {
		is ProtectedLocationCanonicalReceipt.Complete ->
			completeCaptureCandidate(initialLane, expectedCursor, candidate, qualified, receipt, ambient)
		is ProtectedLocationCanonicalReceipt.Incomplete -> ProtectedLocationCandidateEffect.Deferred(receipt.reason)
		is ProtectedLocationCanonicalReceipt.Invalid -> failCandidate(initialLane, expectedCursor, candidate, receipt.reason)
	}

	private suspend fun failCandidate(
		initialLane: SourceProductProjectionLaneEntity,
		expectedCursor: Long,
		candidate: ProtectedLocationNext.Candidate,
		code: String,
	): ProtectedLocationCandidateEffect.Failed {
		saveFailure(initialLane, expectedCursor, candidate.admissionOrdinal, code, terminal = true)
		return ProtectedLocationCandidateEffect.Failed(code, terminal = true)
	}

	/** Completes either an already committed capture or the same receipt after writer replay. */
	private suspend fun completeCaptureCandidate(
		initialLane: SourceProductProjectionLaneEntity,
		expectedCursor: Long,
		candidate: ProtectedLocationNext.Candidate,
		qualified: ProtectedLocationQualifiedCandidate,
		receipt: ProtectedLocationCanonicalReceipt.Complete,
		ambient: LocationAmbientQualification,
	): ProtectedLocationCandidateEffect {
		if (receipt.acceptedSample == null && ambient is LocationAmbientQualification.Qualified) {
			val sibling = writeAmbientSibling(ambient.command)
			if (sibling != null) {
				return sibling
			}
		}
		advanceAfterReceipt(
			initialLane,
			expectedCursor,
			candidate.admissionOrdinal,
			qualified.command,
			qualified.acquisitionMetadata,
		)
		return ProtectedLocationCandidateEffect.Applied(
			candidate.admissionOrdinal,
			accepted = receipt.acceptedSample != null || database.hasAcceptedAmbientSibling(ambient),
		)
	}

	/** Session curation cannot decide whether an independently authorized all-day point is useful. */
	private suspend fun writeAmbientSibling(command: LocationAmbientFactCommand): ProtectedLocationCandidateEffect? =
		when (val result = canonicalWriter.writeAmbient(command)) {
			ProtectedLocationCanonicalWriteResult.Committed -> null
			is ProtectedLocationCanonicalWriteResult.Deferred ->
                ProtectedLocationCandidateEffect.Deferred(result.reason)
			is ProtectedLocationCanonicalWriteResult.Inactive ->
                ProtectedLocationCandidateEffect.Deferred(result.reason)
			is ProtectedLocationCanonicalWriteResult.AuthorityChanged ->
                ProtectedLocationCandidateEffect.AuthorityChanged(result.reason)
			is ProtectedLocationCanonicalWriteResult.Failed ->
                ProtectedLocationCandidateEffect.Failed(result.failureCode,
                result.terminal)
		}

	/** A late original session member cannot borrow the final binding's capture support. */
	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	private suspend fun canRecoverRetiredAmbientSibling(
		current: SourceProductProjectionLaneEntity,
		command: LocationCapturedFactCommand,
		receipt: ProtectedLocationCanonicalReceipt,
		ambient: LocationAmbientFactCommand,
	): Boolean {
		val final =
            com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog.LOCATION_CANONICAL_HANDOFF_V2
		val original =
            com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog.LOCATION_CANONICAL_HANDOFF_V1
		if (current.bindingGeneration != final.bindingGeneration || current.captureModeMask != final.captureModeMask ||
			command.authority.writerBindingGeneration != original.bindingGeneration ||
			command.mutation.identity.sourceEventId.value != ambient.wal.eventId) {
			return false
		}
		val retired = database.sourceProjectionStateDao().productLane(SOURCE_LOCATION,
			original.bindingGeneration, original.projectionId, original.projectionVersion) ?: return false
		val cutoff = retired.captureAdmissionCutoffOrdinal ?: return false
		if (!retired.hasSettledRetiredLocationPrefix(
			original.captureModeMask, cutoff, command.mutation.identity.sourceAdmissionOrdinal,
		)) {
			return false
		}
		return receipt is ProtectedLocationCanonicalReceipt.Incomplete &&
			receipt.reason == "LOCATION_CANONICAL_OBSERVATION_PENDING" ||
			database.readLocationAmbientReceipt(ambient) != null
	}

	private suspend fun processAmbientCandidate(
		lane: SourceProductProjectionLaneEntity,
		cursor: Long,
		candidate: ProtectedLocationNext.Candidate,
		command: LocationAmbientFactCommand,
	): ProtectedLocationCandidateEffect {
		check(command.wal.eventId == candidate.eventId && command.wal.admissionOrdinal == candidate.admissionOrdinal)
		val pendingWrite = writeAmbientSibling(command)
		if (pendingWrite != null) {
			return pendingWrite
		}
		val applied = database.withTransaction {
			requireExactLane(lane, cursor)
			val current = qualifier.qualifyAmbient(SourceEventId(candidate.eventId))
			if (current !is LocationAmbientQualification.Qualified ||
                !command.hasSameAuthorityAndEvidence(current.command)) {
				return@withTransaction ProtectedLocationCandidateEffect.AuthorityChanged(
					"LOCATION_AMBIENT_AUTHORITY_CHANGED"
				) to null
			}
			val accepted = requireNotNull(database.readLocationAmbientReceipt(command))
			database.saveLocationContinuityAnchor(command.wal)
			database.sourceProjectionStateDao().deleteFailure(WRITER_ID, WRITER_VERSION, candidate.admissionOrdinal)
			advanceCursor(lane, cursor, candidate.admissionOrdinal)
            ProtectedLocationCandidateEffect.Applied(candidate.admissionOrdinal, accepted) to
                (if (accepted) {
                    database.locationPurposeDao().receiptByEvent(candidate.eventId)?.sampleId
                } else {
                    null
                })
        }
        applied.second?.let(onSavedPointCommitted)
        return applied.first
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
			val canonicalReceipt = database.readProtectedLocationCanonicalReceipt(
				command,
				acquisitionMetadata,
			)
			check(canonicalReceipt is ProtectedLocationCanonicalReceipt.Complete) {
				"Protected Location canonical receipt disappeared before cursor commit"
			}
			database.recordLocationCapturePurposeInCurrentTransaction(command, canonicalReceipt)
			val ambient = qualifier.qualifyAmbient(command.mutation.identity.sourceEventId)
			if (ambient is LocationAmbientQualification.Qualified && canonicalReceipt.acceptedSample != null) {
				database.recordLocationAmbientPurpose(ambient.command, canonicalReceipt.acceptedSample)
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
			val receiptKey = protectedLocationCanonicalReceiptStateKey(
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
            canonicalReceipt.acceptedSample?.id ?: if (database.hasAcceptedAmbientSibling(ambient)) {
                database.locationPurposeDao().receiptByEvent(command.mutation.identity.sourceEventId.value)?.sampleId
            } else {
                null
            }
        }.let { sampleId -> sampleId?.let(onSavedPointCommitted) }
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
		if (!settled) {
			return@withTransaction false
		}
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
			!lane.hasExecutableProtectedLocationBinding() ->
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
		val mismatch = when {
			!current.hasSameExecutionBinding(expected) -> "LOCATION_HANDOFF_BINDING_CHANGED"
			current.contiguousAdmissionOrdinal != expectedCursor -> "LOCATION_HANDOFF_CURSOR_CHANGED"
			else -> null
		}
		if (mismatch != null) {
			throw ProtectedLocationAuthorityChangedException(mismatch)
		}
		return current
	}

	private suspend fun advanceCursor(
		lane: SourceProductProjectionLaneEntity,
		expectedCursor: Long,
		throughOrdinal: Long,
	) {
		if (throughOrdinal == expectedCursor) {
			return
		}
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
	val writerProjectionId: String? = null,
	val writerProjectionVersion: Int? = null,
	val writerBindingGeneration: Long? = null,
	val writerOwnerGeneration: Long? = null,
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

	/** Reused only within the caller's current Room destination transaction. */
	suspend fun qualifyInCurrentWriterTransaction(sourceEventId: String): ProtectedLocationQualifiedCandidate {
		val db = database ?: error("Protected Location persistence guard is not configured")
		val adapter = qualifier ?: error("Protected Location qualifier is not configured")
		val qualified = when (val adapted = adapter.qualify(SourceEventId(sourceEventId))) {
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
		require(command.mutation.identity.sourceEventId.value == sourceEventId)
		db.requireProtectedLocationWriterAuthority(command.authority.writerBindingGeneration)
		return qualified
	}

	suspend fun verifyInCurrentWriterTransaction(
		write: ProtectedLocationCanonicalPendingWrite,
	): ProtectedLocationVerifiedWrite = verifyInCurrentWriterTransaction(write,
		qualifyInCurrentWriterTransaction(write.sourceEventId))

	suspend fun verifyInCurrentWriterTransaction(
		write: ProtectedLocationCanonicalPendingWrite,
		qualified: ProtectedLocationQualifiedCandidate,
	): ProtectedLocationVerifiedWrite {
		val db = database ?: error("Protected Location persistence guard is not configured")
		val command = qualified.command
		val acquisitionMetadata = qualified.acquisitionMetadata
		check(acquisitionMetadata.isQualified)
		require(command.mutation.identity.sourceEventId.value == write.sourceEventId)
		db.requireProtectedLocationWriterAuthority(command.authority.writerBindingGeneration)

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
		db.requireProtectedLocationWriterAuthority(command.authority.writerBindingGeneration)
		return ProtectedLocationVerifiedWrite(
			command,
			acquisitionMetadata,
			prepared?.expectedOutput,
		)
	}

	/** Rechecked inside the same Room transaction as sessionless canonical destinations. */
	suspend fun verifyAmbientInCurrentWriterTransaction(command: LocationAmbientFactCommand) {
		val current = requireNotNull(qualifier).qualifyAmbient(SourceEventId(command.wal.eventId))
		check(current is LocationAmbientQualification.Qualified &&
			command.hasSameAuthorityAndEvidence(current.command)) {
			"Location ambient authority changed before canonical commit"
		}
		requireNotNull(database).requireProtectedLocationWriterAuthority()
	}

	/** Reuse only a complete rejected capture receipt; an unfinished or accepted write is a conflict. */
	suspend fun rejectedCaptureInCurrentWriterTransaction(eventId: String): ProtectedLocationCanonicalReceipt.Complete {
		val qualified = qualifyInCurrentWriterTransaction(eventId)
		val receipt = requireNotNull(database).readProtectedLocationCanonicalReceipt(
			qualified.command, qualified.acquisitionMetadata,
		)
		check(receipt is ProtectedLocationCanonicalReceipt.Complete && receipt.acceptedSample == null) {
			"Location ambient fallback requires a complete rejected session decision"
		}
		return receipt
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

/** Called only after the protected writer's complete content receipt was authenticated. */
private suspend fun AppDatabase.recordLocationCapturePurposeInCurrentTransaction(
	command: LocationCapturedFactCommand,
	canonical: ProtectedLocationCanonicalReceipt.Complete,
) {
	val sample = canonical.acceptedSample ?: return
	val authority = command.authority
	val evidence = command.productEffect.durableEvidence
	val clock = evidence.clockAuthority
	val current = requireNotNull(sourceEvidenceStateDao().get())
	check(current.collectedDataEpoch == authority.capturedCollectedDataEpoch)
	check(current.retainedFromMs?.let {
		command.productEffect.derivedQualification.earliestPossibleWallTimeMs >= it
	} != false)
	val common = LocationAcceptedReceiptIntegrity.seal(LocationAcceptedReceiptEntity(
		sampleId = sample.id,
		sourceEventId = evidence.sourceEventId.value,
		sourceSignalId = requireNotNull(sample.sourceSignalId),
		sourceAdmissionOrdinal = evidence.sourceAdmissionOrdinal,
		walIntegrityIdentity = evidence.walIntegrityIdentity,
		canonicalContentChecksum = LocationAcceptedReceiptIntegrity.canonicalChecksum(sample),
		sourceInstanceId = authority.sourceInstanceId.value,
		registrationGeneration = authority.registrationGeneration,
		sourceSequence = evidence.sourceSequence,
		deliveryIdentity = requireNotNull(evidence.sourceDeliveryIdentity).value,
		deliveryUnitIndex = evidence.deliveryUnitIndex,
		deliveryUnitCount = evidence.deliveryUnitCount,
		writerId = ProtectedLocationCanonicalHandoff.WRITER_ID,
		writerVersion = ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		configurationRevision = authority.configurationRevision,
		physicalConfigurationFingerprint = authority.physicalConfigurationFingerprint,
		authorizationRevision = authority.authorizationRevision,
		authorizationFingerprint = authority.authorizationFingerprint,
		clockDomainId = clock.clockDomainId,
		bootId = authority.clockDomainId,
		observedElapsedRealtimeNanos = clock.observedElapsedRealtimeNanos,
		receivedElapsedRealtimeNanos = clock.receivedElapsedRealtimeNanos,
		observedWallTimeMs = clock.observedWallTimeMs,
		receivedWallTimeMs = clock.receivedWallTimeMs,
		wallTimeUncertaintyMs = clock.wallTimeUncertaintyMs,
		collectedDataEpoch = authority.capturedCollectedDataEpoch,
	))
	val association = LocationAcceptedReceiptIntegrity.seal(LocationSamplePurposeAuthorityEntity(
		sampleId = sample.id,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		authorityId = authority.serviceRunId.value,
		sourcePolicyRevision = authority.sourcePolicyRevision,
		consentEpoch = authority.captureConsentEpoch,
		// The exact observed instant was inside every authenticated authority interval.
		// Do not snapshot an open interval that can close before replay.
		effectiveFromElapsedNanos = clock.observedElapsedRealtimeNanos,
		effectiveUntilElapsedNanos = Math.addExact(clock.observedElapsedRealtimeNanos, 1L),
		logicalTrackingId = authority.logicalTrackingId.value,
		serviceRunId = authority.serviceRunId.value,
		sessionSegmentId = authority.sessionSegmentId,
		sessionManifestRevision = authority.sessionManifestRevision,
		lifecycleLeaseGeneration = authority.lifecycleLeaseGeneration,
		ambientIntentId = null,
		retentionPolicyId = null,
		retentionApprovalRevision = null,
		scopeDeletionGeneration = 0,
		zoneId = authority.zoneId,
	), common)
	saveLocationCapturePurposeReceipt(common, association)
}

private suspend fun AppDatabase.saveLocationCapturePurposeReceipt(
	common: LocationAcceptedReceiptEntity,
	association: LocationSamplePurposeAuthorityEntity,
) {
	val dao = locationPurposeDao()
	if (dao.insertReceiptIfAbsent(common) == -1L) {
		check(LocationAcceptedReceiptIntegrity.compatibleStoredReceipt(dao.receipt(common.sampleId), common) &&
			LocationAcceptedReceiptIntegrity.compatibleStoredReceipt(dao.receiptByEvent(common.sourceEventId),
                common)) {
			"Location accepted receipt collision"
		}
	}
	if (dao.insertPurposeIfAbsent(association) == -1L) {
		check(LocationAcceptedReceiptIntegrity.compatibleStoredPurpose(
			dao.purpose(association.sampleId, association.purpose, association.authorityId), association)) {
			"Location purpose authority collision"
		}
	}
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
	return when (decision.decision) {
		LocationObservationDecision.ACCEPTED ->
			readAcceptedLocationCanonicalReceipt(command, acquisitionMetadata, observation, decision, canonicalSignalId)
		LocationObservationDecision.REJECTED ->
			readRejectedLocationCanonicalReceipt(command, acquisitionMetadata, observation, decision, canonicalSignalId)
		else -> ProtectedLocationCanonicalReceipt.Invalid("LOCATION_CANONICAL_DECISION_UNSUPPORTED")
	}
}

private suspend fun AppDatabase.readAcceptedLocationCanonicalReceipt(
	command: LocationCapturedFactCommand,
	acquisitionMetadata: LocationWalAcquisitionMetadata,
	observation: LocationObservation,
	decision: LocationObservationDecision,
	canonicalSignalId: String,
): ProtectedLocationCanonicalReceipt {
	if (decision.acceptedSampleSourceSignalId != canonicalSignalId) {
		return ProtectedLocationCanonicalReceipt.Invalid("LOCATION_CANONICAL_ACCEPTED_SAMPLE_IDENTITY_MISMATCH")
	}
	val sample = locationSampleDao().getBySourceSignalId(canonicalSignalId)
		?: return ProtectedLocationCanonicalReceipt.Incomplete("LOCATION_CANONICAL_SAMPLE_PENDING")
	return readLocationCanonicalContentReceipt(command, acquisitionMetadata, observation, decision, sample)
}

private suspend fun AppDatabase.readRejectedLocationCanonicalReceipt(
	command: LocationCapturedFactCommand,
	acquisitionMetadata: LocationWalAcquisitionMetadata,
	observation: LocationObservation,
	decision: LocationObservationDecision,
	canonicalSignalId: String,
): ProtectedLocationCanonicalReceipt {
	if (decision.acceptedSampleSourceSignalId != null ||
		(locationSampleDao().getBySourceSignalId(canonicalSignalId) != null &&
			!hasAcceptedAmbientSiblingReceipt(command.mutation.identity.sourceEventId.value))
	) {
		return ProtectedLocationCanonicalReceipt.Invalid("LOCATION_CANONICAL_REJECTED_SAMPLE_PRESENT")
	}
	return readLocationCanonicalContentReceipt(command, acquisitionMetadata, observation, decision, null)
}

private suspend fun AppDatabase.readLocationCanonicalContentReceipt(
	command: LocationCapturedFactCommand,
	acquisitionMetadata: LocationWalAcquisitionMetadata,
	observation: LocationObservation,
	decision: LocationObservationDecision,
	sample: StoredLocationSample?,
): ProtectedLocationCanonicalReceipt {
	val eventId = command.mutation.identity.sourceEventId.value
	val stored = sourceProjectionStateDao().joinState(
		projectionId = ProtectedLocationCanonicalHandoff.WRITER_ID,
		projectionVersion = ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		stateKey = protectedLocationCanonicalReceiptStateKey(eventId),
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
	if (!stored.hasCanonicalLocationReceiptHeader(command) ||
		!decoded.hasCanonicalLocationDestinations(command, acquisitionMetadata, observation, decision, sample)
	) {
		return ProtectedLocationCanonicalReceipt.Invalid(
			"LOCATION_CANONICAL_CONTENT_RECEIPT_MISMATCH",
		)
	}
	val curationState = runCatching {
		ProtectedLocationCanonicalCurationStateCodec.decode(decoded.curationState)
	}
	return if (curationState.isFailure) {
		ProtectedLocationCanonicalReceipt.Invalid("LOCATION_CANONICAL_CONTENT_RECEIPT_MISMATCH")
	} else {
		ProtectedLocationCanonicalReceipt.Complete(observation, decision, sample, curationState.getOrThrow())
	}
}

private fun SourceProjectionJoinStateEntity.hasCanonicalLocationReceiptHeader(
	command: LocationCapturedFactCommand,
): Boolean = payloadVersion == ProtectedLocationCanonicalReceiptCodec.VERSION &&
	logicalTrackingId == command.authority.logicalTrackingId.value &&
	minimumRequiredOrdinal in setOf(command.mutation.identity.sourceAdmissionOrdinal, Long.MAX_VALUE)

private fun ProtectedLocationCanonicalReceiptPayload.hasCanonicalLocationDestinations(
	command: LocationCapturedFactCommand,
	acquisitionMetadata: LocationWalAcquisitionMetadata,
	observation: LocationObservation,
	decision: LocationObservationDecision,
	sample: StoredLocationSample?,
): Boolean = matches(command, acquisitionMetadata) &&
	this.observation.contentEquals(observation.canonicalReceiptBytes()) &&
	this.decision.contentEquals(decision.canonicalReceiptBytes()) &&
	this.sample.contentEqualsNullable(sample?.canonicalReceiptBytes())

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
	val preparedKey = protectedLocationPreparedCurationStateKey(eventId)
	val preparedState = readPreparedLocationCurationState(verified, decision, sample, preparedKey)
	val committedCurationState = ProtectedLocationCanonicalCurationStateCodec.encode(
		preparedState.stateAfter,
	)
	saveCommittedLocationReceipt(verified, observation, decision, sample, committedCurationState, preparedKey)
}

private suspend fun AppDatabase.readPreparedLocationCurationState(
	verified: ProtectedLocationVerifiedWrite,
	decision: LocationObservationDecision,
	sample: StoredLocationSample?,
	preparedKey: String,
): ProtectedLocationPreparedCurationState {
	val command = verified.command
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
	return preparedState
}

private suspend fun AppDatabase.saveCommittedLocationReceipt(
	verified: ProtectedLocationVerifiedWrite,
	observation: LocationObservation,
	decision: LocationObservationDecision,
	sample: StoredLocationSample?,
	committedCurationState: ByteArray,
	preparedKey: String,
) {
	val command = verified.command
	val eventId = command.mutation.identity.sourceEventId.value
	sourceProjectionStateDao().saveJoinState(
		SourceProjectionJoinStateEntity(
			projectionId = ProtectedLocationCanonicalHandoff.WRITER_ID,
			projectionVersion = ProtectedLocationCanonicalHandoff.WRITER_VERSION,
			stateKey = protectedLocationCanonicalReceiptStateKey(eventId),
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
		writerProjectionId = writerProjectionId,
		writerProjectionVersion = writerProjectionVersion,
		writerBindingGeneration = writerBindingGeneration,
		writerOwnerGeneration = writerOwnerGeneration,
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
		writerProjectionId = writerProjectionId,
		writerProjectionVersion = writerProjectionVersion,
		writerBindingGeneration = writerBindingGeneration,
		writerOwnerGeneration = writerOwnerGeneration,
		acceptedSampleSourceSignalId = acceptedSampleSourceSignalId,
		sourceSignalId = sourceSignalId,
		clockDomainId = clockDomainId,
		decidedAtMs = decidedAtMs,
	)

// Compares the canonical receipt metadata with the supplied immutable command and acquisition tuple.
@Suppress("CyclomaticComplexMethod")
private fun ProtectedLocationCanonicalReceiptPayload.matches(
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

private object ProtectedLocationCanonicalReceiptCodec {
	const val VERSION = SharedProtectedLocationCanonicalReceiptCodec.VERSION

	fun encode(
		command: LocationCapturedFactCommand,
		acquisitionMetadata: LocationWalAcquisitionMetadata,
		observation: LocationObservation,
		decision: LocationObservationDecision,
		sample: StoredLocationSample?,
		curationState: ByteArray,
	): ByteArray = SharedProtectedLocationCanonicalReceiptCodec.encode(
		ProtectedLocationCanonicalReceiptPayload(
			sourceEventId = command.mutation.identity.sourceEventId.value,
			sourceAdmissionOrdinal = command.mutation.identity.sourceAdmissionOrdinal,
			walIntegrityIdentity = command.mutation.identity.walIntegrityIdentity,
			acquisitionMode = acquisitionMetadata.acquisitionMode.name,
			requestPriority = acquisitionMetadata.requestPriority.name,
			requiredAccuracyMeters = acquisitionMetadata.requiredAccuracyMeters,
			policyTier = acquisitionMetadata.policyTier.name,
			policyName = acquisitionMetadata.policyName,
			curationVersion = acquisitionMetadata.curationVersion,
			altitudeModelVersion = acquisitionMetadata.altitudeModelVersion,
			altitudeEstimatorVersion = acquisitionMetadata.altitudeEstimatorVersion,
			altitudeCalibrationVersion = acquisitionMetadata.altitudeCalibrationVersion,
			observation = observation.canonicalReceiptBytes(),
			decision = decision.canonicalReceiptBytes(),
			sample = sample?.canonicalReceiptBytes(),
			curationState = curationState,
		),
	)

	fun decode(payload: ByteArray): ProtectedLocationCanonicalReceiptPayload =
		SharedProtectedLocationCanonicalReceiptCodec.decode(payload)
}

internal fun SourceProjectionJoinStateEntity.authenticatesProtectedLocationCanonicalReceipt(
	sourceEventId: String,
	sourceAdmissionOrdinal: Long,
	walIntegrityIdentity: String,
	observation: LocationObservation,
	decision: LocationObservationDecision,
	sample: StoredLocationSample?,
): Boolean = authenticatedProtectedLocationCanonicalCurationState(
	sourceEventId = sourceEventId,
	sourceAdmissionOrdinal = sourceAdmissionOrdinal,
	walIntegrityIdentity = walIntegrityIdentity,
	observation = observation,
	decision = decision,
	sample = sample,
)?.let { curationState ->
	runCatching {
		ProtectedLocationCanonicalCurationStateCodec.decode(curationState)
	}.isSuccess
} == true

internal fun LocationObservation.canonicalReceiptBytes(): ByteArray =
	protectedLocationCanonicalReceiptBytes()

internal fun LocationObservationDecision.canonicalReceiptBytes(): ByteArray =
	protectedLocationCanonicalReceiptBytes()

private fun LocationObservationDecision.normalizedCanonicalReceiptBytes(): ByteArray =
	copy(id = 0L, sourceRevision = 0L).canonicalReceiptBytes()

internal fun StoredLocationSample.canonicalReceiptBytes(): ByteArray =
	protectedLocationCanonicalReceiptBytes()

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
	if (value != null) {
		writeUTF(value)
	}
}

private fun DataOutputStream.writeNullableLong(value: Long?) {
	writeBoolean(value != null)
	if (value != null) {
		writeLong(value)
	}
}

private fun DataOutputStream.writeNullableInt(value: Int?) {
	writeBoolean(value != null)
	if (value != null) {
		writeInt(value)
	}
}

private fun DataOutputStream.writeNullableFloat(value: Float?) {
	writeBoolean(value != null)
	if (value != null) {
		writeInt(java.lang.Float.floatToRawIntBits(value))
	}
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

private const val SHA_256_BYTES = 32

private fun protectedLocationContinuityKey(registration: ProtectedLocationRegistrationIdentity): String =
	"instance-continuity:${registration.sourceInstanceId.length}:${registration.sourceInstanceId}"

private fun legacyProtectedLocationContinuityKey(
	registration: ProtectedLocationRegistrationIdentity,
): String = buildString {
	append("continuity:")
	append(registration.logicalTrackingId?.length ?: -1)
	append(':')
	append(registration.logicalTrackingId)
	append(':')
	append(registration.serviceRunId?.length ?: -1)
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
	) ?: sourceProjectionStateDao().joinState(
		ProtectedLocationCanonicalHandoff.WRITER_ID, ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		"physical-continuity:${registration.sourceInstanceId.length}:${registration.sourceInstanceId}:" +
			"${registration.registrationGeneration}",
	) ?: sourceProjectionStateDao().joinState(
		ProtectedLocationCanonicalHandoff.WRITER_ID, ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		legacyProtectedLocationContinuityKey(registration),
	) ?: locationPurposeDao().latestLegacyInstanceContinuityAnchor(registration.sourceInstanceId) ?: return null
	check(stored.payloadVersion == ProtectedLocationContinuityAnchorCodec.VERSION
	) {
		"Protected Location continuity anchor authority changed"
	}
	return ProtectedLocationContinuityAnchorCodec.decode(stored.payload).also {
		check(stored.minimumRequiredOrdinal == Math.addExact(it.admissionOrdinal, 1L)) {
			"Protected Location continuity anchor ordinal changed"
		}
	}
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
		logicalTrackingId = null,
		minimumRequiredOrdinal = runCatching {
			Math.addExact(evidence.sourceAdmissionOrdinal, 1L)
		}.getOrDefault(Long.MAX_VALUE),
		payloadVersion = ProtectedLocationContinuityAnchorCodec.VERSION,
		payload = ProtectedLocationContinuityAnchorCodec.encode(anchor),
		updatedAtMs = System.currentTimeMillis().coerceAtLeast(0L),
	)
	if (sourceProjectionStateDao().insertJoinStateIfAbsent(entity) != -1L) {
		return
	}
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

private suspend fun AppDatabase.saveLocationContinuityAnchor(wal: SourceEventWalEntity) {
	val registration = ProtectedLocationRegistrationIdentity(wal.sourceInstanceId, wal.registrationGeneration,
        wal.logicalTrackingId, wal.serviceRunId)
	val old = loadLocationContinuityAnchor(registration)
	check(old == null || old.admissionOrdinal <= wal.admissionOrdinal)
	if (old?.admissionOrdinal == wal.admissionOrdinal) {
		check(old.sourceSequence == wal.sourceSequence)
	}
	sourceProjectionStateDao().saveJoinState(SourceProjectionJoinStateEntity(
		ProtectedLocationCanonicalHandoff.WRITER_ID, ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		protectedLocationContinuityKey(registration), null, Math.addExact(wal.admissionOrdinal, 1L),
		ProtectedLocationContinuityAnchorCodec.VERSION,
		ProtectedLocationContinuityAnchorCodec.encode(ProtectedLocationContinuityAnchor(wal.admissionOrdinal,
            wal.sourceSequence)),
		System.currentTimeMillis().coerceAtLeast(0L),
	))
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
			require(anchor.admissionOrdinal > 0L && anchor.sourceSequence >= 0L)
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
		protectedLocationRunCurationStateKey(
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
		stateKey = protectedLocationRunCurationStateKey(logicalTrackingId, serviceRunId),
		logicalTrackingId = logicalTrackingId,
		minimumRequiredOrdinal = nextOrdinal,
		payloadVersion = ProtectedLocationCanonicalCurationStateCodec.VERSION,
		payload = payload,
		updatedAtMs = System.currentTimeMillis().coerceAtLeast(0L),
	)
	if (sourceProjectionStateDao().insertJoinStateIfAbsent(entity) != -1L) {
		return
	}
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
	requireProtectedLocationWriterAuthority(command.authority.writerBindingGeneration)
	val entity = SourceProjectionJoinStateEntity(
		projectionId = ProtectedLocationCanonicalHandoff.WRITER_ID,
		projectionVersion = ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		stateKey = protectedLocationPreparedCurationStateKey(
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
	requireProtectedLocationWriterAuthority(command.authority.writerBindingGeneration)
}

internal suspend fun AppDatabase.loadPreparedProtectedLocationCanonicalCurationState(
	command: LocationCapturedFactCommand,
): ProtectedLocationPreparedCurationState? {
	val stored = sourceProjectionStateDao().joinState(
		ProtectedLocationCanonicalHandoff.WRITER_ID,
		ProtectedLocationCanonicalHandoff.WRITER_VERSION,
		protectedLocationPreparedCurationStateKey(
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

internal object ProtectedLocationCanonicalCurationStateCodec {
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
		if (point == null) {
			return
		}
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
		if (!readBoolean()) {
			return null
		}
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
		if (value != null) {
			writeLong(java.lang.Double.doubleToRawLongBits(value))
		}
	}

	private fun DataInputStream.readNullableDouble(): Double? =
		if (readBoolean()) {
			java.lang.Double.longBitsToDouble(readLong())
		} else {
			null
		}

	private fun DataInputStream.readNullableFloat(): Float? =
		if (readBoolean()) {
			java.lang.Float.intBitsToFloat(readInt())
		} else {
			null
		}

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
				writerProjectionId = ProtectedLocationCanonicalHandoff.WRITER_ID,
				writerProjectionVersion = ProtectedLocationCanonicalHandoff.WRITER_VERSION,
				writerBindingGeneration = command.authority.writerBindingGeneration,
				writerOwnerGeneration =
					SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION,
				acceptedSampleSourceSignalId = signalId.takeIf { accepted },
				sourceSignalId = signalId,
				clockDomainId = signal.clockDomainId,
				decidedAtMs = signal.timestampMs.raw,
			)
			val sample = expectedLocationSample(signal, signalId, accepted)
			return fromDestinations(decision, sample)
		}

		private fun expectedLocationSample(
			signal: TrackingSignal,
			signalId: String,
			accepted: Boolean,
		): StoredLocationSample? = if (accepted) {
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
		if (value != null) {
			writeByteArray(value)
		}
	}

	private fun DataInputStream.readNullableByteArray(): ByteArray? =
		if (readBoolean()) {
			readByteArray()
		} else {
			null
		}

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
		payload.horizontalAccuracyMeters?.let { accuracy = it }
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

internal suspend fun AppDatabase.requireProtectedLocationWriterAuthority(expectedBindingGeneration: Long? = null) {
	val lanes = sourceProjectionStateDao().allActiveProductLanes()
		.filter { it.sourceKind == SourceDestinationOwnerEntity.SOURCE_LOCATION }
	check(lanes.size == 1) { "Protected Location requires exactly one active product lane" }
	val lane = lanes.single()
	check(
		lane.hasExecutableProtectedLocationBinding() &&
			(expectedBindingGeneration == null || lane.bindingGeneration == expectedBindingGeneration) &&
			lane.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL
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

// Checks the explicit evidence-to-observation field mapping using the existing numeric conversions.
@Suppress("CyclomaticComplexMethod")
private fun LocationObservation.matchesQualifiedEvidence(
	command: LocationCapturedFactCommand,
	acquisitionMetadata: LocationWalAcquisitionMetadata,
): Boolean {
	val evidence = command.productEffect.durableEvidence
	val clock = evidence.clockAuthority
	val payload = evidence.payload
	val expectedDeliveryAgeMs =
		(clock.receivedElapsedRealtimeNanos - clock.observedElapsedRealtimeNanos) / Time.MILLISECONDS_IN_NANOSECONDS
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

// One saved decision correspondence: fixed identity fields and the two persisted outcome shapes.
@Suppress("CyclomaticComplexMethod", "ComplexCondition")
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
		writerProjectionId != ProtectedLocationCanonicalHandoff.WRITER_ID ||
		writerProjectionVersion != ProtectedLocationCanonicalHandoff.WRITER_VERSION ||
		writerBindingGeneration != command.authority.writerBindingGeneration ||
		writerOwnerGeneration != SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION ||
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

// Checks the saved-sample field mapping, preserving unknown optional values and existing conversions.
@Suppress("CyclomaticComplexMethod")
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
				evidence.clockAuthority.observedElapsedRealtimeNanos) / Time.MILLISECONDS_IN_NANOSECONDS &&
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

private fun expectedLocationQuality(horizontalAccuracyMeters: Float?): String = when {
    horizontalAccuracyMeters == null -> "COARSE"
	horizontalAccuracyMeters < HIGH_ACCURACY_BOUND_METERS -> "HIGH"
	horizontalAccuracyMeters < MEDIUM_ACCURACY_BOUND_METERS -> "MEDIUM"
	else -> "LOW"
}

private fun expectedStoredLocationQuality(
	horizontalAccuracyMeters: Float?,
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
	return (receivedElapsedRealtimeNanos - fixElapsedRealtimeNanos) / Time.MILLISECONDS_IN_NANOSECONDS
}

/** One immutable execution contract shared by drain selection and the destination writer guard. */
private fun SourceProductProjectionLaneEntity.hasExecutableProtectedLocationBinding(): Boolean {
	val expectedCaptureMask = SourceWriterGenerationContract.captureModeMaskForKnownBinding(bindingGeneration)
		?: return false
	return projectionId == ProtectedLocationCanonicalHandoff.WRITER_ID &&
		projectionVersion == ProtectedLocationCanonicalHandoff.WRITER_VERSION &&
		captureModeMask == expectedCaptureMask && hasActiveProtectedLocationRange()
}

private fun SourceProductProjectionLaneEntity.hasActiveProtectedLocationRange(): Boolean =
	activatedRolloutRevision > 0L && activationOrdinal > 0L &&
		contiguousAdmissionOrdinal >= activationOrdinal - 1L &&
		captureAdmissionCutoffOrdinal?.let { contiguousAdmissionOrdinal <= it } != false &&
		retentionRequired && status == SourceProductProjectionLaneEntity.STATUS_ACTIVE &&
		terminalDisposition == null && terminalAtMs == null

private fun SourceProductProjectionLaneEntity.hasSettledRetiredLocationPrefix(
	expectedCaptureMask: Long,
	cutoff: Long,
	candidateOrdinal: Long,
): Boolean = captureModeMask == expectedCaptureMask &&
	productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL &&
	status == SourceProductProjectionLaneEntity.STATUS_RETIRED && !retentionRequired &&
	terminalDisposition == SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN &&
	terminalAtMs != null && contiguousAdmissionOrdinal == cutoff && candidateOrdinal > cutoff

private fun SourceProductProjectionLaneEntity.hasSameExecutionBinding(
	other: SourceProductProjectionLaneEntity,
): Boolean = this == other.copy(
	contiguousAdmissionOrdinal = contiguousAdmissionOrdinal,
	updatedAtMs = updatedAtMs,
)

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

internal data class ProtectedLocationQualifiedCandidate(
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

private class ProtectedLocationRegistrationIdentity(
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val logicalTrackingId: String?,
	val serviceRunId: String?,
) {
	override fun equals(other: Any?): Boolean = other is ProtectedLocationRegistrationIdentity &&
		sourceInstanceId == other.sourceInstanceId
	override fun hashCode(): Int = sourceInstanceId.hashCode()
}

private data class ProtectedLocationContinuityAnchor(
	val admissionOrdinal: Long,
	val sourceSequence: Long,
)

private fun SourceEventProjectionEligibilityRow.hasLocationEndpointIdentity(
	logicalTrackingId: String,
	serviceRunId: String,
): Boolean = sourceKind == SourceDestinationOwnerEntity.SOURCE_LOCATION &&
	!((this.logicalTrackingId != logicalTrackingId || this.serviceRunId != serviceRunId) &&
		(this.logicalTrackingId != null || this.serviceRunId != null))

private fun SourceSessionCompletenessEntity.acknowledgesLocationEndpoint(
	endpoint: SourceEventProjectionEligibilityRow,
	targetAdmissionOrdinal: Long,
): Boolean = sourceInstanceId == endpoint.sourceInstanceId &&
	registrationGeneration == endpoint.registrationGeneration &&
	lastAdmissionOrdinal == targetAdmissionOrdinal && lastSourceSequence == endpoint.sourceSequence &&
	hasSettledLocationContinuity()

private fun SourceSessionCompletenessEntity.coversObservedLocation(row: SourceEventContinuityRow): Boolean =
	sourceInstanceId == row.sourceInstanceId && registrationGeneration == row.registrationGeneration &&
		requireNotNull(lastAdmissionOrdinal) >= row.admissionOrdinal &&
		requireNotNull(lastSourceSequence) >= row.sourceSequence

private fun SourceSessionCompletenessEntity.hasSettledLocationContinuity(): Boolean =
	appDrainComplete &&
		stopStatus == "COMPLETE" &&
		lastSourceSequence != null &&
		unresolvedSequenceStart == null &&
		unresolvedSequenceEnd == null

private class ProtectedLocationAuthorityChangedException(
	val reason: String,
) : IllegalStateException(reason)

private const val HIGH_ACCURACY_BOUND_METERS = 10f
private const val MEDIUM_ACCURACY_BOUND_METERS = 50f
private const val LOCATION_OBSERVATION_ESTIMATOR_VERSION = 1
private const val LOCATION_OBSERVATION_CALIBRATION_VERSION = 0
