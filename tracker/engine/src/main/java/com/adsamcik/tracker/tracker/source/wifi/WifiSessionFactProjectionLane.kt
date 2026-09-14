package com.adsamcik.tracker.tracker.source.wifi

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceEventProjectionCandidateRow
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
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
 * Dormant bounded source-local lane for retained captured Wifi WAL.
 *
 * The covering preflight reads only event identity and purpose eligibility. Every capture candidate
 * is then reloaded and authenticated by [WifiWalQualificationAdapter]. Shadow execution validates
 * only; canonical execution delegates the sole fact mutation to [WifiCapturedFactWriter]. No lane
 * installation, rollout transition, provider demand, or active refresh is performed here.
 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions") // One source-local transactional state machine.
class WifiSessionFactProjectionLane private constructor(
	private val database: AppDatabase,
	private val adapter: WifiWalQualificationAdapter,
	private val writer: WifiCapturedFactWriter,
	private val executableLaneCatalog: ExecutableSourceLaneCatalog,
	applicationScope: CoroutineScope?,
	private val writeCheckpoint: suspend (Long, WifiCapturedWriteCheckpoint) -> Unit,
	@Suppress("UNUSED_PARAMETER") constructionMarker: Unit,
) {
	private val mutex = Mutex()
	private val drainSignals = Channel<Unit>(Channel.CONFLATED)

	@Inject
	internal constructor(
		database: AppDatabase,
		adapter: WifiWalQualificationAdapter,
		writer: WifiCapturedFactWriter,
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
		adapter: WifiWalQualificationAdapter,
		writer: WifiCapturedFactWriter,
		executableLaneCatalog: ExecutableSourceLaneCatalog = ExecutableSourceLaneCatalog(),
		writeCheckpoint: suspend (Long, WifiCapturedWriteCheckpoint) -> Unit = { _, _ -> },
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

	/** Conflated process-local hint; the WAL and exact Wifi lane cursor remain authoritative. */
	fun requestDrain() {
		drainSignals.trySend(Unit)
	}

	/** Drains one finite durable high-water snapshot. */
	internal suspend fun drainAvailable(): WifiSessionFactDrainResult = mutex.withLock {
		val initialLane = database.withTransaction { executableLaneOrNull() }
			?: return@withLock WifiSessionFactDrainResult.Inactive
		val target = try {
			database.withTransaction {
				val exactLane = requireExactLane(initialLane)
				val state = evidenceState()
				val cutoff = exactLane.captureAdmissionCutoffOrdinal ?: Long.MAX_VALUE
				val terminalOrdinal = database.sourceProjectionStateDao()
					.firstTerminalFailureAfterThrough(
						projectionId = WRITER_ID,
						projectionVersion = WRITER_VERSION,
						afterOrdinal = exactLane.contiguousAdmissionOrdinal,
						throughOrdinal = cutoff,
					)?.admissionOrdinal ?: 0L
				val durableHighWater = maxOf(
					database.sourceEventWalDao().maximumAdmissionOrdinal() ?: 0L,
					state.deletedSourceEventHighWaterOrdinal,
					terminalOrdinal,
				)
				minOf(durableHighWater, cutoff)
			}
		} catch (changed: WifiLaneAuthorityChangedException) {
			return@withLock WifiSessionFactDrainResult.AuthorityChanged(changed.reason)
		}
		if (target <= initialLane.contiguousAdmissionOrdinal) {
			return@withLock WifiSessionFactDrainResult.Complete(
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
	): WifiSessionFactDrainResult = mutex.withLock {
		require(throughAdmissionOrdinal >= 0L)
		val initialLane = database.withTransaction { executableLaneOrNull() }
			?: return@withLock WifiSessionFactDrainResult.Inactive
		val target = minOf(
			throughAdmissionOrdinal,
			initialLane.captureAdmissionCutoffOrdinal ?: Long.MAX_VALUE,
		)
		if (target <= initialLane.contiguousAdmissionOrdinal) {
			return@withLock WifiSessionFactDrainResult.Complete(
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
	): WifiSessionFactDrainResult {
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
						return@withTransaction WifiProjectionPass.TerminalBlocked(
							failure = requireNotNull(terminal),
							throughOrdinal = cursor,
						)
					}

					val candidates = database.sourceEventWalDao().sourceProjectionCandidatesAfterThrough(
						sourceKind = SourceKind.WIFI.stableCode,
						afterOrdinal = cursor,
						throughOrdinal = readThroughOrdinal,
						limit = BATCH_SIZE,
					)
					if (candidates.isEmpty()) {
						advanceCursor(lane, readThroughOrdinal)
						return@withTransaction WifiProjectionPass.Applied(readThroughOrdinal, 0, 0)
					}

					var inserted = 0
					var validated = 0
					var previousOrdinal = cursor
					for (candidate in candidates) {
						attemptedOrdinal = candidate.admissionOrdinal
						check(candidate.admissionOrdinal in (previousOrdinal + 1L)..readThroughOrdinal) {
							"Wifi preflight returned an event outside its requested ordinal interval"
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
							val authenticated = authenticatePreflightCandidate(candidate)
							if (authenticated is WifiPreflightAuthentication.Failed) {
								val failure = saveFailure(
									candidate.admissionOrdinal,
									authenticated.failureCode,
									terminal = true,
								)
								val through = candidate.admissionOrdinal - 1L
								advanceCursor(lane, through)
								return@withTransaction WifiProjectionPass.TerminalBlocked(failure, through)
							}
							val wal = (authenticated as WifiPreflightAuthentication.Authenticated).wal
							if (wal.authorizationPurposeEligibilityMask and
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
							return@withTransaction WifiProjectionPass.TerminalBlocked(failure, through)
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
					WifiProjectionPass.Applied(through, inserted, validated)
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (changed: WifiLaneAuthorityChangedException) {
				return WifiSessionFactDrainResult.AuthorityChanged(changed.reason)
			} catch (rejected: WifiCapturedWriteRejectedException) {
				if (rejected.reason == WifiCapturedWriteRejection.DESTINATION_OWNER_CHANGED) {
					return WifiSessionFactDrainResult.AuthorityChanged("WIFI_DESTINATION_OWNER_CHANGED")
				}
				return persistFailure(
					cursor = cursor,
					admissionOrdinal = requireNotNull(attemptedOrdinal),
					failureCode = "WIFI_WRITE_${rejected.reason.name}",
					terminal = rejected.reason != WifiCapturedWriteRejection.CURSOR_CHANGED,
				)
			} catch (failure: Exception) {
				val ordinal = attemptedOrdinal
				if (ordinal == null) {
					return WifiSessionFactDrainResult.Failed(
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
				is WifiProjectionPass.TerminalBlocked -> return WifiSessionFactDrainResult.Failed(
					lastCompletedOrdinal = pass.throughOrdinal,
					failedOrdinal = pass.failure.admissionOrdinal,
					failureCode = pass.failure.failureCode,
					terminal = true,
				)
				is WifiProjectionPass.Applied -> {
					cursor = pass.throughOrdinal
					factsInserted += pass.factsInserted
					eventsValidated += pass.eventsValidated
				}
			}
		}
		return WifiSessionFactDrainResult.Complete(cursor, factsInserted, eventsValidated)
	}

	private suspend fun persistFailure(
		cursor: Long,
		admissionOrdinal: Long,
		failureCode: String,
		terminal: Boolean,
	): WifiSessionFactDrainResult.Failed {
		val normalized = failureCode.ifBlank { "UNKNOWN_WIFI_PROJECTION_FAILURE" }
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
		return WifiSessionFactDrainResult.Failed(
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
		if (failure.admissionOrdinal <= state.deletedSourceEventHighWaterOrdinal) return true
		val raw = database.sourceEventWalDao().getByAdmissionOrdinal(failure.admissionOrdinal)
			?: return false
		if (raw.admissionOrdinal != failure.admissionOrdinal ||
			raw.sourceKind != SourceKind.WIFI.stableCode ||
			!raw.hasQualifiedIntegrity()
		) return false
		if (failure.failureCode in LIFECYCLE_SETTLED_FAILURES) return true
		return raw.isLifecycleRejected(state) || raw.isDeletedScope()
	}

	private suspend fun authenticatePreflightCandidate(
		candidate: SourceEventProjectionCandidateRow,
	): WifiPreflightAuthentication {
		val wal = database.sourceEventWalDao().getByAdmissionOrdinal(candidate.admissionOrdinal)
			?: return WifiPreflightAuthentication.Failed("WIFI_PREFLIGHT_WAL_MISSING")
		if (wal.eventId != candidate.eventId || wal.admissionOrdinal != candidate.admissionOrdinal ||
			wal.sourceKind != SourceKind.WIFI.stableCode
		) return WifiPreflightAuthentication.Failed("WIFI_PREFLIGHT_WAL_IDENTITY_MISMATCH")
		if (!wal.hasQualifiedIntegrity()) {
			return WifiPreflightAuthentication.Failed("WIFI_PREFLIGHT_WAL_INTEGRITY_MISMATCH")
		}
		return WifiPreflightAuthentication.Authenticated(wal)
	}

	// Wi-Fi retention is based on the decoded provider wall interval. The envelope's acquired/time
	// scalars are not equivalent, so only an authenticated adapter/writer retention failure settles it.
	private fun SourceEventWalEntity.isLifecycleRejected(
		state: SourceEvidenceState,
	): Boolean = authorizationPurposeEligibilityMask and
		SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
		capturedCollectedDataEpoch != state.collectedDataEpoch

	private suspend fun SourceEventWalEntity.isDeletedScope(): Boolean {
		val logical = logicalTrackingId ?: return false
		val run = serviceRunId ?: return false
		return database.sourceDeletionFenceDao().contains(
			sourceKind = SourceKind.WIFI.stableCode,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind = SourceKind.WIFI.stableCode,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = logical,
				serviceRunId = run,
			),
		)
	}

	private suspend fun executableLaneOrNull(): SourceProductProjectionLaneEntity? {
		val active = database.sourceProjectionStateDao().allActiveProductLanes()
			.filter { it.sourceKind == SourceKind.WIFI.stableCode }
		if (active.size != 1) return null
		return active.single().takeIf(::isExecutableLane)
	}

	private suspend fun requireExactLane(
		expected: SourceProductProjectionLaneEntity,
		expectedCursor: Long = expected.contiguousAdmissionOrdinal,
	): SourceProductProjectionLaneEntity {
		val current = executableLaneOrNull()
		if (current == null || !current.hasSameWifiExecutionBinding(expected)) {
			throw WifiLaneAuthorityChangedException("WIFI_LANE_BINDING_CHANGED")
		}
		if (current.contiguousAdmissionOrdinal != expectedCursor) {
			throw WifiLaneAuthorityChangedException("WIFI_LANE_CURSOR_CHANGED")
		}
		if (database.sourceProjectionStateDao().registration(WRITER_ID, WRITER_VERSION) != null) {
			throw WifiLaneAuthorityChangedException("WIFI_WRITER_HAS_GLOBAL_REGISTRATION")
		}
		if (current.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL) {
			val owner = database.sourceDestinationOwnerDao().get(
				SourceDestinationOwnerEntity.SOURCE_WIFI,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI,
			)
			if (owner?.owner != SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS ||
				owner.ownerGeneration != SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
			) throw WifiLaneAuthorityChangedException("WIFI_DESTINATION_OWNER_CHANGED")
		}
		return current
	}

	private fun isExecutableLane(lane: SourceProductProjectionLaneEntity): Boolean =
		executableLaneCatalog.bindingFor(lane) == ExecutableSourceLaneCatalog.WIFI_SESSION_FACTS &&
			lane.productStage in EXECUTABLE_STAGES && lane.activatedRolloutRevision > 0L &&
			lane.activationOrdinal > 0L &&
			lane.contiguousAdmissionOrdinal >= lane.activationOrdinal - 1L &&
			lane.captureAdmissionCutoffOrdinal?.let { lane.contiguousAdmissionOrdinal <= it } != false &&
			lane.retentionRequired && lane.status == SourceProductProjectionLaneEntity.STATUS_ACTIVE &&
			lane.terminalDisposition == null && lane.terminalAtMs == null

	private suspend fun evidenceState(): SourceEvidenceState =
		database.sourceEvidenceStateDao().get()
			?: throw WifiLaneAuthorityChangedException("SOURCE_EVIDENCE_STATE_MISSING")

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
		) == 1) { "Exact Wifi product lane changed before its cursor commit" }
	}

	private fun nowMs(): Long = System.currentTimeMillis().coerceAtLeast(0L)

	companion object {
		const val WRITER_ID = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION
		const val BINDING_GENERATION = SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION
		const val MANUAL_CAPTURE_MODE_MASK = 1L

		private const val BATCH_SIZE = 64
		private val EXECUTABLE_STAGES = setOf(
			SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
			SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		)
		private val LIFECYCLE_SETTLED_FAILURES = setOf(
			"WIFI_ADAPTER_DELETED_EVIDENCE",
			"WIFI_ADAPTER_DELETED_SCOPE",
			"WIFI_ADAPTER_SCOPE_DELETION_AUTHORITY_MISMATCH",
			"WIFI_ADAPTER_BEFORE_RETENTION_FLOOR",
			"WIFI_WRITE_SOURCE_EVIDENCE_AUTHORITY_CHANGED",
			"WIFI_WRITE_DELETED_SCOPE",
			"WIFI_WRITE_SCOPE_DELETION_AUTHORITY_CHANGED",
			"WIFI_WRITE_RETAINED_DATA",
		)
	}
}

private fun WifiWalAdapterResult.toProjectionResult(): WifiCandidateProjectionResult = when (this) {
	is WifiWalAdapterResult.Evaluated -> WifiCandidateProjectionResult(
		factInserted = false,
		failureCode = classification.failureCodeOrNull(),
	)
	is WifiWalAdapterResult.Rejected -> WifiCandidateProjectionResult(
		factInserted = false,
		failureCode = "WIFI_ADAPTER_${reason.name}",
	)
}

private fun WifiCapturedWriteResult.toProjectionResult(): WifiCandidateProjectionResult = when (this) {
	is WifiCapturedWriteResult.Applied -> WifiCandidateProjectionResult(true, null)
	is WifiCapturedWriteResult.Unchanged -> WifiCandidateProjectionResult(false, null)
	is WifiCapturedWriteResult.AdapterRejected -> WifiCandidateProjectionResult(
		false,
		"WIFI_ADAPTER_${reason.name}",
	)
	is WifiCapturedWriteResult.Rejected -> WifiCandidateProjectionResult(
		false,
		"WIFI_WRITE_${reason.name}",
	)
	is WifiCapturedWriteResult.NoEvidence -> WifiCandidateProjectionResult(
		false,
		classification.failureCodeOrNull(),
	)
}

private data class WifiCandidateProjectionResult(
	val factInserted: Boolean,
	val failureCode: String?,
)

private sealed interface WifiPreflightAuthentication {
	data class Authenticated(val wal: SourceEventWalEntity) : WifiPreflightAuthentication
	data class Failed(val failureCode: String) : WifiPreflightAuthentication
}

private fun WifiCapturedFactClassification.failureCodeOrNull(): String? = when (this) {
	is WifiCapturedFactClassification.FreshChanged,
	is WifiCapturedFactClassification.FreshUnchanged,
	is WifiCapturedFactClassification.Replay -> null
	WifiCapturedFactClassification.Absent -> "WIFI_CLASSIFICATION_ABSENT"
	WifiCapturedFactClassification.Stale -> "WIFI_CLASSIFICATION_STALE"
	WifiCapturedFactClassification.Failed -> "WIFI_CLASSIFICATION_FAILED"
	WifiCapturedFactClassification.PermissionLimited -> "WIFI_CLASSIFICATION_PERMISSION_LIMITED"
	WifiCapturedFactClassification.OsThrottled -> "WIFI_CLASSIFICATION_OS_THROTTLED"
	WifiCapturedFactClassification.ClockUnverifiable -> "WIFI_CLASSIFICATION_CLOCK_UNVERIFIABLE"
	is WifiCapturedFactClassification.Rejected -> "WIFI_CLASSIFICATION_REJECTED_${reason.name}"
}

private fun SourceProductProjectionLaneEntity.hasSameWifiExecutionBinding(
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

internal sealed interface WifiSessionFactDrainResult {
	data object Inactive : WifiSessionFactDrainResult
	data class Complete(
		val lastCompletedOrdinal: Long,
		val factsInserted: Int,
		val eventsValidated: Int,
	) : WifiSessionFactDrainResult
	data class AuthorityChanged(val reason: String) : WifiSessionFactDrainResult
	data class Failed(
		val lastCompletedOrdinal: Long,
		val failedOrdinal: Long?,
		val failureCode: String,
		val terminal: Boolean,
	) : WifiSessionFactDrainResult
}

private sealed interface WifiProjectionPass {
	data class Applied(
		val throughOrdinal: Long,
		val factsInserted: Int,
		val eventsValidated: Int,
	) : WifiProjectionPass
	data class TerminalBlocked(
		val failure: SourceProjectionFailureEntity,
		val throughOrdinal: Long,
	) : WifiProjectionPass
}

private class WifiLaneAuthorityChangedException(val reason: String) : IllegalStateException(reason)
