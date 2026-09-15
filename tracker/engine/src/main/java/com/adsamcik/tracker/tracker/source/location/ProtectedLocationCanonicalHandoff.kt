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
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.shared.base.data.LocationFixMetadata
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.signal.LocationDecision
import com.adsamcik.tracker.stats.api.signal.LocationDecisionSignal
import com.adsamcik.tracker.stats.api.signal.PolicySignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.source.model.SourceEventId
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
 * Active-session consumer for the established canonical Location pipeline.
 *
 * Implementations must serialize this call with every ordinary cycle, flush, tier transition, and
 * shutdown path that can touch [com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor].
 */
internal fun interface ProtectedLocationCanonicalWriter {
	suspend fun write(
		command: LocationCapturedFactCommand,
		acquisitionMetadata: LocationWalAcquisitionMetadata,
	): ProtectedLocationCanonicalWriteResult
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
		val target = try {
			database.withTransaction {
				val exact = requireExactLane(lane, lane.contiguousAdmissionOrdinal)
				val evidence = database.sourceEvidenceStateDao().get()
					?: throw ProtectedLocationAuthorityChangedException(
						"SOURCE_EVIDENCE_STATE_MISSING",
					)
				val terminal = database.sourceProjectionStateDao()
					.firstTerminalFailureAfterThrough(
						projectionId = WRITER_ID,
						projectionVersion = WRITER_VERSION,
						afterOrdinal = exact.contiguousAdmissionOrdinal,
						throughOrdinal = Long.MAX_VALUE,
					)?.admissionOrdinal ?: 0L
				minOf(
					maxOf(
						database.sourceEventWalDao().maximumAdmissionOrdinal() ?: 0L,
						evidence.deletedSourceEventHighWaterOrdinal,
						terminal,
					),
					exact.captureAdmissionCutoffOrdinal ?: Long.MAX_VALUE,
				)
			}
		} catch (changed: ProtectedLocationAuthorityChangedException) {
			return@withLock ProtectedLocationCanonicalDrainResult.AuthorityChanged(
				lane.contiguousAdmissionOrdinal,
				changed.reason,
			)
		}
		drainLocked(lane, target, expectedLogicalTrackingId = null, expectedServiceRunId = null)
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
		val target = minOf(
			throughAdmissionOrdinal,
			lane.captureAdmissionCutoffOrdinal ?: Long.MAX_VALUE,
		)
		drainLocked(lane, target, logicalTrackingId, serviceRunId)
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
							ProtectedLocationNext.Advanced(terminal.admissionOrdinal)
						} else {
							ProtectedLocationNext.Terminal(terminal)
						}
					} else if (candidate == null) {
						advanceCursor(initialLane, cursor, targetAdmissionOrdinal)
						ProtectedLocationNext.Advanced(targetAdmissionOrdinal)
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
				is ProtectedLocationNext.Advanced -> cursor = next.throughOrdinal
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
		if (acquisitionMetadata == LocationWalAcquisitionMetadata.UNKNOWN) {
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
			advanceCursor(initialLane, expectedCursor, throughOrdinal)
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
	}
}

internal data class ProtectedLocationCanonicalPendingDecision(
	val sourceEventId: String,
	val decision: String,
	val reason: String?,
	val acceptedSampleSourceSignalId: String?,
	val sourceSignalId: String,
	val clockDomainId: String?,
	val decidedAtMs: Long,
)

internal data class ProtectedLocationCanonicalPendingWrite(
	val sourceEventId: String,
	val observation: LocationObservation?,
	val decision: ProtectedLocationCanonicalPendingDecision?,
	val sample: LocationSample?,
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

	suspend fun verifyInCurrentWriterTransaction(write: ProtectedLocationCanonicalPendingWrite) {
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
		check(acquisitionMetadata != LocationWalAcquisitionMetadata.UNKNOWN) {
			"Protected Location historical acquisition metadata is unavailable"
		}
		require(command.mutation.identity.sourceEventId.value == write.sourceEventId)
		db.requireProtectedLocationWriterAuthority()

		val observation = write.observation
			?: db.locationObservationDao().getBySourceEventId(write.sourceEventId)
		requireNotNull(observation) {
			"Protected Location canonical decision has no raw observation"
		}
		require(observation.matches(command, acquisitionMetadata)) {
			"Protected Location raw observation does not match authenticated WAL evidence"
		}

		val decision = write.decision
		val sample = write.sample
		require(decision != null || sample == null) {
			"Protected Location sample cannot commit without its terminal decision"
		}
		if (decision != null) {
			require(decision.matches(command, acquisitionMetadata, sample)) {
				"Protected Location terminal decision/sample receipt is inconsistent"
			}
		}
		db.requireProtectedLocationWriterAuthority()
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
	if (!observation.matches(command, acquisitionMetadata)) {
		return ProtectedLocationCanonicalReceipt.Invalid(
			"LOCATION_CANONICAL_OBSERVATION_MISMATCH",
		)
	}
	val decision = locationObservationDecisionDao().getBySourceEventId(eventId)
		?: return ProtectedLocationCanonicalReceipt.Incomplete(
			"LOCATION_CANONICAL_DECISION_PENDING",
		)
	val canonicalSignalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(eventId)
	if (decision.sourceSignalId != canonicalSignalId ||
		decision.observationSourceEventId != eventId
	) {
		return ProtectedLocationCanonicalReceipt.Invalid(
			"LOCATION_CANONICAL_DECISION_IDENTITY_MISMATCH",
		)
	}
	return when (decision.decision) {
		LocationObservationDecision.ACCEPTED -> {
			if (decision.acceptedSampleSourceSignalId != canonicalSignalId) {
				ProtectedLocationCanonicalReceipt.Invalid(
					"LOCATION_CANONICAL_ACCEPTED_SAMPLE_IDENTITY_MISMATCH",
				)
			} else {
				val sample = locationSampleDao().getBySourceSignalId(canonicalSignalId)
					?: return ProtectedLocationCanonicalReceipt.Incomplete(
						"LOCATION_CANONICAL_SAMPLE_PENDING",
					)
				if (sample.matches(command, acquisitionMetadata)) {
					ProtectedLocationCanonicalReceipt.Complete(observation, decision, sample)
				} else {
					ProtectedLocationCanonicalReceipt.Invalid(
						"LOCATION_CANONICAL_SAMPLE_MISMATCH",
					)
				}
			}
		}
		LocationObservationDecision.REJECTED -> {
			if (decision.acceptedSampleSourceSignalId != null ||
				locationSampleDao().getBySourceSignalId(canonicalSignalId) != null
			) {
				ProtectedLocationCanonicalReceipt.Invalid(
					"LOCATION_CANONICAL_REJECTED_SAMPLE_PRESENT",
				)
			} else {
				ProtectedLocationCanonicalReceipt.Complete(observation, decision, null)
			}
		}
		else -> ProtectedLocationCanonicalReceipt.Invalid(
			"LOCATION_CANONICAL_DECISION_UNSUPPORTED",
		)
	}
}

internal fun LocationCapturedFactCommand.toProtectedLocationTrackingCycle(
	acquisitionMetadata: LocationWalAcquisitionMetadata,
): TrackingCycle {
	require(!productEffect.isMock) {
		"Mock Location evidence must take the explicit canonical rejection path"
	}
	val evidence = productEffect.durableEvidence
	val clock = evidence.clockAuthority
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
		receivedAtMs = clock.observedWallTimeMs,
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
	require(acquisitionMetadata != LocationWalAcquisitionMetadata.UNKNOWN)
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

private fun LocationObservation.matches(
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
		receivedAtMs == clock.observedWallTimeMs &&
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
		callbackId == evidence.sourceDeliveryIdentity?.value &&
		clockDomainId == clock.clockDomainId &&
		bootClockDomainId == clock.clockDomainId
}

private fun ProtectedLocationCanonicalPendingDecision.matches(
	command: LocationCapturedFactCommand,
	acquisitionMetadata: LocationWalAcquisitionMetadata,
	sample: LocationSample?,
): Boolean {
	val eventId = command.mutation.identity.sourceEventId.value
	val canonicalSignalId = ProtectedLocationCanonicalSignalIdentity.canonicalProduct(eventId)
	if (sourceEventId != eventId || sourceSignalId != canonicalSignalId ||
		clockDomainId != command.authority.clockDomainId ||
		decidedAtMs != command.productEffect.durableEvidence.clockAuthority.observedWallTimeMs
	) {
		return false
	}
	return when (decision) {
		LocationDecision.ACCEPTED.name ->
			acceptedSampleSourceSignalId == canonicalSignalId &&
				sample?.matches(command, acquisitionMetadata) == true
		LocationDecision.REJECTED.name ->
			acceptedSampleSourceSignalId == null && sample == null
		else -> false
	}
}

private fun LocationSample.matches(
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
		hAccM == payload.horizontalAccuracyMeters &&
		rawPlatformSpeedMps == payload.speedMetersPerSecond &&
		provider == payload.provider &&
		receivedElapsedRealtimeNanos == evidence.clockAuthority.receivedElapsedRealtimeNanos &&
		acquisitionMode == acquisitionMetadata.acquisitionMode.name &&
		requestPriority == acquisitionMetadata.requestPriority.name &&
		permissionPrecision == command.authority.permissionPrecision.name &&
		batchIndex == evidence.deliveryUnitIndex &&
		batchSize == evidence.deliveryUnitCount &&
		!isMock &&
		clockDomainId == evidence.clockAuthority.clockDomainId &&
		bootClockDomainId == evidence.clockAuthority.clockDomainId
}

private fun StoredLocationSample.matches(
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
		hAccM == payload.horizontalAccuracyMeters &&
		rawPlatformSpeedMps == payload.speedMetersPerSecond &&
		provider == payload.provider &&
		receivedElapsedRealtimeNanos == evidence.clockAuthority.receivedElapsedRealtimeNanos &&
		acquisitionMode == acquisitionMetadata.acquisitionMode.name &&
		requestPriority == acquisitionMetadata.requestPriority.name &&
		permissionPrecision == command.authority.permissionPrecision.name &&
		batchIndex == evidence.deliveryUnitIndex &&
		batchSize == evidence.deliveryUnitCount &&
		!isMock &&
		clockDomainId == evidence.clockAuthority.clockDomainId &&
		bootClockDomainId == evidence.clockAuthority.clockDomainId
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
	data class Advanced(val throughOrdinal: Long) : ProtectedLocationNext
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

private class ProtectedLocationAuthorityChangedException(
	val reason: String,
) : IllegalStateException(reason)
