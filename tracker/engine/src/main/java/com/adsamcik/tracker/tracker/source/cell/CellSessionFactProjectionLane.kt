package com.adsamcik.tracker.tracker.source.cell

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceEventProjectionEligibilityRow
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Dormant bounded source-local lane for retained captured Cell WAL.
 *
 * The covering preflight reads only event identity and purpose eligibility. Every capture candidate
 * is then reloaded and authenticated by [CellWalQualificationAdapter]. Shadow execution validates
 * only; canonical execution delegates the sole fact mutation to [CellCapturedFactWriter]. No lane
 * installation, rollout transition, provider demand, or active refresh is performed here.
 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions") // One source-local transactional state machine.
class CellSessionFactProjectionLane private constructor(
	private val database: AppDatabase,
	private val adapter: CellWalQualificationAdapter,
	private val writer: CellCapturedFactWriter,
	private val executableLaneCatalog: ExecutableSourceLaneCatalog,
	applicationScope: CoroutineScope?,
	private val writeCheckpoint: suspend (Long, CellCapturedWriteCheckpoint) -> Unit,
	@Suppress("UNUSED_PARAMETER") constructionMarker: Unit,
) {
	private val mutex = Mutex()
	private val drainSignals = Channel<Unit>(Channel.CONFLATED)

	@Inject
	internal constructor(
		database: AppDatabase,
		adapter: CellWalQualificationAdapter,
		writer: CellCapturedFactWriter,
		executableLaneCatalog: ExecutableSourceLaneCatalog,
		@ApplicationScope applicationScope: CoroutineScope,
	) : this(
		database,
		adapter,
		writer,
		executableLaneCatalog,
		applicationScope,
		{ _, _ -> },
		Unit,
	)

	internal constructor(
		database: AppDatabase,
		adapter: CellWalQualificationAdapter,
		writer: CellCapturedFactWriter,
		executableLaneCatalog: ExecutableSourceLaneCatalog = ExecutableSourceLaneCatalog(),
		writeCheckpoint: suspend (Long, CellCapturedWriteCheckpoint) -> Unit = { _, _ -> },
	) : this(database, adapter, writer, executableLaneCatalog, null, writeCheckpoint, Unit)

	init {
		applicationScope?.launch {
			for (ignored in drainSignals) {
				try {
					drainAvailable()
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (_: Exception) {
					// The durable cursor/failure row owns retry; never poll after a process hint.
				}
			}
		}
	}

	/** Conflated process-local hint; the WAL and exact Cell lane cursor remain authoritative. */
	fun requestDrain() {
		drainSignals.trySend(Unit)
	}

	/** Drains one finite durable high-water snapshot. */
	internal suspend fun drainAvailable(): CellSessionFactDrainResult = mutex.withLock {
		val initialLane = database.withTransaction { executableLaneOrNull() }
			?: return@withLock CellSessionFactDrainResult.Inactive
		val target = try {
			database.withTransaction {
				val exactLane = requireExactLane(initialLane)
				val state = evidenceState()
				val durableHighWater = maxOf(
					database.sourceEventWalDao().maximumAdmissionOrdinal() ?: 0L,
					state.deletedSourceEventHighWaterOrdinal,
				)
				minOf(durableHighWater, exactLane.captureAdmissionCutoffOrdinal ?: Long.MAX_VALUE)
			}
		} catch (changed: CellLaneAuthorityChangedException) {
			return@withLock CellSessionFactDrainResult.AuthorityChanged(changed.reason)
		}
		if (target <= initialLane.contiguousAdmissionOrdinal) {
			return@withLock CellSessionFactDrainResult.Complete(
				lastCompletedOrdinal = initialLane.contiguousAdmissionOrdinal,
				factsInserted = 0,
				eventsValidated = 0,
			)
		}
		drainThroughLocked(initialLane, target)
	}

	/** Test/cutover seam for one already-durable interval; never installs or promotes a lane. */
	internal suspend fun drainThrough(
		throughAdmissionOrdinal: Long,
	): CellSessionFactDrainResult = mutex.withLock {
		require(throughAdmissionOrdinal >= 0L)
		val initialLane = database.withTransaction { executableLaneOrNull() }
			?: return@withLock CellSessionFactDrainResult.Inactive
		val target = minOf(
			throughAdmissionOrdinal,
			initialLane.captureAdmissionCutoffOrdinal ?: Long.MAX_VALUE,
		)
		if (target <= initialLane.contiguousAdmissionOrdinal) {
			return@withLock CellSessionFactDrainResult.Complete(
				lastCompletedOrdinal = initialLane.contiguousAdmissionOrdinal,
				factsInserted = 0,
				eventsValidated = 0,
			)
		}
		drainThroughLocked(initialLane, target)
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount", "TooGenericExceptionCaught")
	private suspend fun drainThroughLocked(
		initialLane: SourceProductProjectionLaneEntity,
		targetAdmissionOrdinal: Long,
	): CellSessionFactDrainResult {
		var cursor = initialLane.contiguousAdmissionOrdinal
		var factsInserted = 0
		var eventsValidated = 0
		while (cursor < targetAdmissionOrdinal) {
			var attemptedOrdinal: Long? = null
			val pass = try {
				database.withTransaction {
					val lane = requireExactLane(initialLane, expectedCursor = cursor)
					val state = evidenceState()
					var releasedTerminalOrdinal: Long? = null
					val terminal = database.sourceProjectionStateDao()
						.firstTerminalFailureAfterThrough(
							projectionId = WRITER_ID,
							projectionVersion = WRITER_VERSION,
							afterOrdinal = cursor,
							throughOrdinal = targetAdmissionOrdinal,
						)?.takeUnless { failure ->
							if (failureIsLifecycleRejected(failure, state)) {
								database.sourceProjectionStateDao().deleteFailure(
									WRITER_ID,
									WRITER_VERSION,
									failure.admissionOrdinal,
								)
								releasedTerminalOrdinal = failure.admissionOrdinal
								true
							} else {
								false
							}
						}
					val readThroughOrdinal = terminal?.admissionOrdinal?.minus(1L)
						?: targetAdmissionOrdinal
					if (readThroughOrdinal <= cursor) {
						return@withTransaction CellProjectionPass.TerminalBlocked(
							failure = requireNotNull(terminal),
							throughOrdinal = cursor,
						)
					}

					val candidates = database.sourceEventWalDao().sourceProjectionCandidatesAfterThrough(
						sourceKind = SourceKind.CELL.stableCode,
						afterOrdinal = cursor,
						throughOrdinal = readThroughOrdinal,
						limit = BATCH_SIZE,
					)
					if (candidates.isEmpty()) {
						advanceCursor(lane, readThroughOrdinal)
						return@withTransaction CellProjectionPass.Applied(readThroughOrdinal, 0, 0)
					}

					var inserted = 0
					var validated = 0
					var previousOrdinal = cursor
					for (candidate in candidates) {
						attemptedOrdinal = candidate.admissionOrdinal
						check(candidate.admissionOrdinal in (previousOrdinal + 1L)..readThroughOrdinal) {
							"Cell preflight returned an event outside its requested ordinal interval"
						}
						previousOrdinal = candidate.admissionOrdinal
						if (candidate.admissionOrdinal == releasedTerminalOrdinal) {
							// The row was terminally classified before a later deletion/retention change.
							// Its lifecycle authority is now settled, so it cannot become captured history.
							continue
						}
						if (candidate.authorizationPurposeEligibilityMask and
							SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L
						) {
							// CONTROL and ambient/opportunistic observations are not captured history.
							database.sourceProjectionStateDao().deleteFailure(
								WRITER_ID,
								WRITER_VERSION,
								candidate.admissionOrdinal,
							)
							continue
						}

						val result = if (
							lane.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW
						) {
							adapter.qualify(SourceEventId(candidate.eventId)).toProjectionResult()
						} else {
							writer.writeInCurrentTransaction(SourceEventId(candidate.eventId)) { checkpoint ->
								writeCheckpoint(candidate.admissionOrdinal, checkpoint)
							}.toProjectionResult()
						}
						val failureCode = result.failureCode
						if (failureCode != null) {
							val failure = saveFailure(candidate.admissionOrdinal, failureCode, terminal = true)
							val through = candidate.admissionOrdinal - 1L
							advanceCursor(lane, through)
							return@withTransaction CellProjectionPass.TerminalBlocked(failure, through)
						}
						if (result.factInserted) inserted++
						validated++
						database.sourceProjectionStateDao().deleteFailure(
							WRITER_ID,
							WRITER_VERSION,
							candidate.admissionOrdinal,
						)
					}
					val through = candidates.last().admissionOrdinal
					// This commit proves fact/fact-cursor/evidence/failure and lane-cursor agreement.
					advanceCursor(lane, through)
					CellProjectionPass.Applied(through, inserted, validated)
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (changed: CellLaneAuthorityChangedException) {
				return CellSessionFactDrainResult.AuthorityChanged(changed.reason)
			} catch (rejected: CellCapturedWriteRejectedException) {
				if (rejected.reason == CellCapturedWriteRejection.DESTINATION_OWNER_CHANGED) {
					return CellSessionFactDrainResult.AuthorityChanged("CELL_DESTINATION_OWNER_CHANGED")
				}
				return persistFailure(
					cursor = cursor,
					admissionOrdinal = requireNotNull(attemptedOrdinal),
					failureCode = "CELL_WRITE_${rejected.reason.name}",
					terminal = rejected.reason != CellCapturedWriteRejection.CURSOR_CHANGED,
				)
			} catch (failure: Exception) {
				val ordinal = attemptedOrdinal
				if (ordinal == null) {
					return CellSessionFactDrainResult.Failed(
						lastCompletedOrdinal = cursor,
						failedOrdinal = null,
						failureCode = failure::class.java.simpleName,
						terminal = false,
					)
				}
				return persistFailure(
					cursor,
					ordinal,
					failure::class.java.simpleName,
					terminal = false,
				)
			}

			when (pass) {
				is CellProjectionPass.TerminalBlocked -> return CellSessionFactDrainResult.Failed(
					lastCompletedOrdinal = pass.throughOrdinal,
					failedOrdinal = pass.failure.admissionOrdinal,
					failureCode = pass.failure.failureCode,
					terminal = true,
				)
				is CellProjectionPass.Applied -> {
					cursor = pass.throughOrdinal
					factsInserted += pass.factsInserted
					eventsValidated += pass.eventsValidated
				}
			}
		}
		return CellSessionFactDrainResult.Complete(cursor, factsInserted, eventsValidated)
	}

	private suspend fun persistFailure(
		cursor: Long,
		admissionOrdinal: Long,
		failureCode: String,
		terminal: Boolean,
	): CellSessionFactDrainResult.Failed {
		val normalized = failureCode.ifBlank { "UNKNOWN_CELL_PROJECTION_FAILURE" }
		val persisted = try {
			database.withTransaction {
				saveFailure(admissionOrdinal, normalized, terminal)
				true
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			false
		}
		return CellSessionFactDrainResult.Failed(
			lastCompletedOrdinal = cursor,
			failedOrdinal = admissionOrdinal,
			failureCode = if (persisted) normalized else "FAILURE_AUDIT_UNAVAILABLE",
			terminal = persisted && terminal,
		)
	}

	private suspend fun saveFailure(
		admissionOrdinal: Long,
		failureCode: String,
		terminal: Boolean,
	): SourceProjectionFailureEntity {
		val dao = database.sourceProjectionStateDao()
		val failure = SourceProjectionFailureEntity(
			projectionId = WRITER_ID,
			projectionVersion = WRITER_VERSION,
			admissionOrdinal = admissionOrdinal,
			attemptCount = (dao.failure(WRITER_ID, WRITER_VERSION, admissionOrdinal)?.attemptCount ?: 0) + 1,
			failureCode = failureCode,
			terminal = terminal,
			lastAttemptAtMs = nowMs(),
		)
		dao.saveFailure(failure)
		return failure
	}

	private suspend fun failureIsLifecycleRejected(
		failure: SourceProjectionFailureEntity,
		state: SourceEvidenceState,
	): Boolean {
		if (failure.failureCode in LIFECYCLE_SETTLED_FAILURES ||
			failure.admissionOrdinal <= state.deletedSourceEventHighWaterOrdinal
		) return true
		val raw = database.sourceEventWalDao()
			.projectionEligibilityByAdmissionOrdinal(failure.admissionOrdinal) ?: return true
		if (raw.sourceKind != SourceKind.CELL.stableCode) return true
		return raw.isLifecycleRejected(state) || raw.isDeletedScope()
	}

	private fun SourceEventProjectionEligibilityRow.isLifecycleRejected(
		state: SourceEvidenceState,
	): Boolean = authorizationPurposeEligibilityMask and
		SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
		capturedCollectedDataEpoch != state.collectedDataEpoch ||
		state.retainedFromMs?.let { retainedFrom ->
			acquiredAtMs < retainedFrom ||
				wallTimeMs?.takeIf { it >= 0L }?.let { it < retainedFrom } == true
		} == true

	private suspend fun SourceEventProjectionEligibilityRow.isDeletedScope(): Boolean {
		val logical = logicalTrackingId ?: return false
		val run = serviceRunId ?: return false
		return database.sourceDeletionFenceDao().contains(
			sourceKind = SourceKind.CELL.stableCode,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind = SourceKind.CELL.stableCode,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = logical,
				serviceRunId = run,
			),
		)
	}

	private suspend fun executableLaneOrNull(): SourceProductProjectionLaneEntity? {
		val active = database.sourceProjectionStateDao().allActiveProductLanes()
			.filter { it.sourceKind == SourceKind.CELL.stableCode }
		if (active.size != 1) return null
		return active.single().takeIf(::isExecutableLane)
	}

	private suspend fun requireExactLane(
		expected: SourceProductProjectionLaneEntity,
		expectedCursor: Long = expected.contiguousAdmissionOrdinal,
	): SourceProductProjectionLaneEntity {
		val current = executableLaneOrNull()
		if (current == null || !current.hasSameCellExecutionBinding(expected)) {
			throw CellLaneAuthorityChangedException("CELL_LANE_BINDING_CHANGED")
		}
		if (current.contiguousAdmissionOrdinal != expectedCursor) {
			throw CellLaneAuthorityChangedException("CELL_LANE_CURSOR_CHANGED")
		}
		if (database.sourceProjectionStateDao().registration(WRITER_ID, WRITER_VERSION) != null) {
			throw CellLaneAuthorityChangedException("CELL_WRITER_HAS_GLOBAL_REGISTRATION")
		}
		if (current.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL) {
			val owner = database.sourceDestinationOwnerDao().get(
				SourceDestinationOwnerEntity.SOURCE_CELL,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
			)
			if (owner?.owner != SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS ||
				owner.ownerGeneration != SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
			) throw CellLaneAuthorityChangedException("CELL_DESTINATION_OWNER_CHANGED")
		}
		return current
	}

	private fun isExecutableLane(lane: SourceProductProjectionLaneEntity): Boolean =
		executableLaneCatalog.bindingFor(lane) == ExecutableSourceLaneCatalog.CELL_SESSION_FACTS &&
			lane.productStage in EXECUTABLE_STAGES && lane.activatedRolloutRevision > 0L &&
			lane.activationOrdinal > 0L &&
			lane.contiguousAdmissionOrdinal >= lane.activationOrdinal - 1L &&
			lane.captureAdmissionCutoffOrdinal?.let { lane.contiguousAdmissionOrdinal <= it } != false &&
			lane.retentionRequired && lane.status == SourceProductProjectionLaneEntity.STATUS_ACTIVE &&
			lane.terminalDisposition == null && lane.terminalAtMs == null

	private suspend fun evidenceState(): SourceEvidenceState =
		database.sourceEvidenceStateDao().get()
			?: throw CellLaneAuthorityChangedException("SOURCE_EVIDENCE_STATE_MISSING")

	private suspend fun advanceCursor(
		lane: SourceProductProjectionLaneEntity,
		throughOrdinal: Long,
	) {
		if (throughOrdinal == lane.contiguousAdmissionOrdinal) return
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
			expectedCurrentOrdinal = lane.contiguousAdmissionOrdinal,
			throughOrdinal = throughOrdinal,
			updatedAtMs = nowMs(),
		) == 1) { "Exact Cell product lane changed before its cursor commit" }
	}

	private fun nowMs(): Long = System.currentTimeMillis().coerceAtLeast(0L)

	companion object {
		const val WRITER_ID = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION
		const val BINDING_GENERATION = SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION
		const val MANUAL_CAPTURE_MODE_MASK = 1L

		private const val BATCH_SIZE = 64
		private val EXECUTABLE_STAGES = setOf(
			SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
			SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		)
		private val LIFECYCLE_SETTLED_FAILURES = setOf(
			"CELL_ADAPTER_DELETED_EVIDENCE",
			"CELL_ADAPTER_DELETED_SCOPE",
			"CELL_ADAPTER_SCOPE_DELETION_AUTHORITY_MISMATCH",
			"CELL_ADAPTER_BEFORE_RETENTION_FLOOR",
			"CELL_WRITE_SOURCE_EVIDENCE_AUTHORITY_CHANGED",
			"CELL_WRITE_DELETED_SCOPE",
			"CELL_WRITE_SCOPE_DELETION_AUTHORITY_CHANGED",
			"CELL_WRITE_RETAINED_DATA",
		)
	}
}

private fun CellWalAdapterResult.toProjectionResult(): CellCandidateProjectionResult = when (this) {
	is CellWalAdapterResult.Evaluated -> CellCandidateProjectionResult(
		factInserted = false,
		failureCode = classification.failureCodeOrNull(),
	)
	is CellWalAdapterResult.Rejected -> CellCandidateProjectionResult(
		factInserted = false,
		failureCode = "CELL_ADAPTER_${reason.name}",
	)
}

private fun CellCapturedWriteResult.toProjectionResult(): CellCandidateProjectionResult = when (this) {
	is CellCapturedWriteResult.Applied -> CellCandidateProjectionResult(true, null)
	is CellCapturedWriteResult.Unchanged -> CellCandidateProjectionResult(false, null)
	is CellCapturedWriteResult.AdapterRejected -> CellCandidateProjectionResult(
		false,
		"CELL_ADAPTER_${reason.name}",
	)
	is CellCapturedWriteResult.Rejected -> CellCandidateProjectionResult(
		false,
		"CELL_WRITE_${reason.name}",
	)
	is CellCapturedWriteResult.NoEvidence -> CellCandidateProjectionResult(
		false,
		classification.failureCodeOrNull(),
	)
}

private data class CellCandidateProjectionResult(
	val factInserted: Boolean,
	val failureCode: String?,
)

private fun CellCapturedFactClassification.failureCodeOrNull(): String? = when (this) {
	is CellCapturedFactClassification.FreshChanged,
	is CellCapturedFactClassification.FreshUnchanged,
	is CellCapturedFactClassification.Replay -> null
	is CellCapturedFactClassification.Absent -> "CELL_CLASSIFICATION_ABSENT_${reason.name}"
	is CellCapturedFactClassification.Stale -> "CELL_CLASSIFICATION_STALE"
	is CellCapturedFactClassification.Failed -> "CELL_CLASSIFICATION_FAILED_${reason.name}"
	CellCapturedFactClassification.PermissionLimited -> "CELL_CLASSIFICATION_PERMISSION_LIMITED"
	CellCapturedFactClassification.OsLimited -> "CELL_CLASSIFICATION_OS_LIMITED"
	is CellCapturedFactClassification.ClockUnverifiable ->
		"CELL_CLASSIFICATION_CLOCK_UNVERIFIABLE_${reason.name}"
	is CellCapturedFactClassification.SourceUnverifiable ->
		"CELL_CLASSIFICATION_SOURCE_UNVERIFIABLE_${reason.name}"
	is CellCapturedFactClassification.Rejected -> "CELL_CLASSIFICATION_REJECTED_${reason.name}"
}

private fun SourceProductProjectionLaneEntity.hasSameCellExecutionBinding(
	other: SourceProductProjectionLaneEntity,
): Boolean = sourceKind == other.sourceKind && bindingGeneration == other.bindingGeneration &&
	projectionId == other.projectionId && projectionVersion == other.projectionVersion &&
	captureModeMask == other.captureModeMask && productStage == other.productStage &&
	activatedRolloutRevision == other.activatedRolloutRevision &&
	activationOrdinal == other.activationOrdinal &&
	captureAdmissionCutoffOrdinal == other.captureAdmissionCutoffOrdinal &&
	retentionRequired == other.retentionRequired && status == other.status &&
	terminalDisposition == other.terminalDisposition && terminalAtMs == other.terminalAtMs &&
	installedAtMs == other.installedAtMs

internal sealed interface CellSessionFactDrainResult {
	data object Inactive : CellSessionFactDrainResult
	data class Complete(
		val lastCompletedOrdinal: Long,
		val factsInserted: Int,
		val eventsValidated: Int,
	) : CellSessionFactDrainResult
	data class AuthorityChanged(val reason: String) : CellSessionFactDrainResult
	data class Failed(
		val lastCompletedOrdinal: Long,
		val failedOrdinal: Long?,
		val failureCode: String,
		val terminal: Boolean,
	) : CellSessionFactDrainResult
}

private sealed interface CellProjectionPass {
	data class Applied(
		val throughOrdinal: Long,
		val factsInserted: Int,
		val eventsValidated: Int,
	) : CellProjectionPass
	data class TerminalBlocked(
		val failure: SourceProjectionFailureEntity,
		val throughOrdinal: Long,
	) : CellProjectionPass
}

private class CellLaneAuthorityChangedException(val reason: String) : IllegalStateException(reason)
