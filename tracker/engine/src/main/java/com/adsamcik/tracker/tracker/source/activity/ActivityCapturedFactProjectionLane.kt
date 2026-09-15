package com.adsamcik.tracker.tracker.source.activity

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceEventProjectionEligibilityRow
import com.adsamcik.tracker.shared.base.database.dao.SourceProjectionEventIdentityRow
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
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
 * Dormant source-local owner for captured Activity fact projection.
 *
 * A hint is process-local and conflated. The durable WAL, exact Activity product-lane cursor, and
 * terminal failure row own recovery. Catalog executability grants no authority by itself:
 * production remains inactive until the explicit Activity owner/lane/rollout transition commits.
 */
@Singleton
@Suppress("LargeClass", "TooManyFunctions") // One source-local transactional projection owner.
internal class ActivityCapturedFactProjectionLane private constructor(
	private val database: AppDatabase,
	private val adapter: ActivityCapturedWalAdmissionAdapter,
	private val writer: ActivityCapturedFactWriter,
	private val executableLaneCatalog: ExecutableSourceLaneCatalog,
	applicationScope: CoroutineScope?,
	@Suppress("UNUSED_PARAMETER") constructionMarker: Unit,
) {
	private val mutex = Mutex()
	private val drainSignals = Channel<Unit>(Channel.CONFLATED)

	@Inject
	constructor(
		database: AppDatabase,
		adapter: ActivityCapturedWalAdmissionAdapter,
		executableLaneCatalog: ExecutableSourceLaneCatalog,
		@ApplicationScope applicationScope: CoroutineScope,
	) : this(
		database,
		adapter,
		ActivityCapturedFactWriter(database),
		executableLaneCatalog,
		applicationScope,
		Unit,
	)

	internal constructor(
		database: AppDatabase,
		adapter: ActivityCapturedWalAdmissionAdapter,
		writer: ActivityCapturedFactWriter = ActivityCapturedFactWriter(database),
		executableLaneCatalog: ExecutableSourceLaneCatalog = ExecutableSourceLaneCatalog(),
	) : this(database, adapter, writer, executableLaneCatalog, null, Unit)

	init {
		applicationScope?.launch {
			for (ignored in drainSignals) {
				try {
					drainAvailable()
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (_: Exception) {
					// WAL, lane cursor, and failure rows own recovery; never poll after a hint.
				}
			}
		}
	}

	fun requestDrain() {
		drainSignals.trySend(Unit)
	}

	/** Captures one finite WAL high-water. Admissions after this snapshot require another hint. */
	suspend fun drainAvailable(): ActivityCapturedFactDrainResult = mutex.withLock {
		val initial = database.withTransaction { resolveLane() }
		if (initial !is ActivityLaneResolution.Ready) {
			return@withLock ActivityCapturedFactDrainResult.Inactive(
				(initial as ActivityLaneResolution.Inactive).reason,
			)
		}
		val lane = initial.lane
		val target = try {
			database.withTransaction {
				requireExactLane(lane)
				val evidenceState = evidenceState()
				val highWater = maxOf(
					database.sourceEventWalDao().maximumAdmissionOrdinal() ?: 0L,
					evidenceState.deletedSourceEventHighWaterOrdinal,
				)
				minOf(highWater, lane.captureAdmissionCutoffOrdinal ?: Long.MAX_VALUE)
			}
		} catch (changed: ActivityLaneAuthorityChangedException) {
			return@withLock ActivityCapturedFactDrainResult.AuthorityChanged(changed.reason)
		}
		if (target <= lane.contiguousAdmissionOrdinal) {
			return@withLock ActivityCapturedFactDrainResult.Complete(
				lane.contiguousAdmissionOrdinal,
				windowsApplied = 0,
				eventsValidated = 0,
			)
		}
		drainThroughLocked(lane, target)
	}

	internal suspend fun drainThrough(
		throughAdmissionOrdinal: Long,
	): ActivityCapturedFactDrainResult = mutex.withLock {
		require(throughAdmissionOrdinal >= 0L)
		val initial = database.withTransaction { resolveLane() }
		if (initial !is ActivityLaneResolution.Ready) {
			return@withLock ActivityCapturedFactDrainResult.Inactive(
				(initial as ActivityLaneResolution.Inactive).reason,
			)
		}
		val target = minOf(
			throughAdmissionOrdinal,
			initial.lane.captureAdmissionCutoffOrdinal ?: Long.MAX_VALUE,
		)
		drainThroughLocked(initial.lane, target)
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount", "TooGenericExceptionCaught")
	private suspend fun drainThroughLocked(
		initialLane: SourceProductProjectionLaneEntity,
		targetAdmissionOrdinal: Long,
	): ActivityCapturedFactDrainResult {
		var cursor = initialLane.contiguousAdmissionOrdinal
		var windowsApplied = 0
		var eventsValidated = 0
		while (cursor < targetAdmissionOrdinal) {
			var attemptedOrdinal: Long? = null
			val pass = try {
				database.withTransaction {
					val lane = requireExactLane(initialLane, cursor)
					val evidenceState = evidenceState()
					val storedTerminal = database.sourceProjectionStateDao()
						.firstTerminalFailureAfterThrough(
							WRITER_ID,
							WRITER_VERSION,
							cursor,
							targetAdmissionOrdinal,
						)
					val terminal = storedTerminal?.takeUnless { failure ->
						if (failureIsLifecycleRejected(failure.admissionOrdinal, evidenceState)) {
							database.sourceProjectionStateDao().deleteFailure(
								WRITER_ID,
								WRITER_VERSION,
								failure.admissionOrdinal,
							)
							true
						} else {
							false
						}
					}
					if (terminal != null) {
						return@withTransaction ActivityProjectionPass.TerminalBlocked(
							terminal,
							cursor,
						)
					}

					val preflight = projectionPage(cursor, targetAdmissionOrdinal, PROBE_PAGE_SIZE)
					if (preflight.isEmpty()) {
						advanceCursor(lane, targetAdmissionOrdinal)
						return@withTransaction ActivityProjectionPass.Applied(
							targetAdmissionOrdinal,
							windowsApplied = 0,
							eventsValidated = 0,
						)
					}
					var skippedThrough = cursor
					for (event in preflight) {
						attemptedOrdinal = event.admissionOrdinal
						when (val admission = adapter.admit(SourceEventId(event.eventId))) {
							is ActivityCapturedWalAdmissionResult.Admitted -> {
								return@withTransaction projectTerminalRun(
									lane,
									cursor,
									skippedThrough,
									targetAdmissionOrdinal,
									event,
									admission,
								)
							}
							is ActivityCapturedWalAdmissionResult.Rejected -> when (admission.reason) {
								ActivityCapturedWalAdmissionRejection.CONTROL_ONLY,
								ActivityCapturedWalAdmissionRejection.DELETED_EVIDENCE,
								ActivityCapturedWalAdmissionRejection.RETAINED_EVIDENCE,
								-> {
									database.sourceProjectionStateDao().deleteFailure(
										WRITER_ID,
										WRITER_VERSION,
										event.admissionOrdinal,
									)
									skippedThrough = event.admissionOrdinal
								}
								ActivityCapturedWalAdmissionRejection.DESTINATION_OWNER_MISMATCH ->
									throw ActivityLaneAuthorityChangedException(
										"ACTIVITY_DESTINATION_OWNER_CHANGED",
									)
								else -> return@withTransaction terminalFailure(
									lane,
									cursor,
									event.admissionOrdinal,
									admission.reason.failureCode(),
								)
							}
							is ActivityCapturedWalAdmissionResult.Unavailable -> {
								if (admission.reason ==
									ActivityCapturedWalAdmissionUnavailable.UNSETTLED_FINITE_WINDOW
								) {
									advanceCursor(lane, skippedThrough)
									return@withTransaction ActivityProjectionPass.Deferred(
										skippedThrough,
										event.admissionOrdinal,
										admission.reason.name,
									)
								}
								return@withTransaction terminalFailure(
									lane,
									cursor,
									event.admissionOrdinal,
									admission.reason.name,
								)
							}
							is ActivityCapturedWalAdmissionResult.ObservationRejected ->
								return@withTransaction terminalFailure(
									lane,
									cursor,
									event.admissionOrdinal,
									"ACTIVITY_OBSERVATION_${admission.reason.name}",
								)
						}
					}
					val through = if (preflight.size < PROBE_PAGE_SIZE) {
						targetAdmissionOrdinal
					} else {
						skippedThrough
					}
					advanceCursor(lane, through)
					ActivityProjectionPass.Applied(through, 0, preflight.size)
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (changed: ActivityLaneAuthorityChangedException) {
				return ActivityCapturedFactDrainResult.AuthorityChanged(changed.reason)
			} catch (poison: ActivityCapturedProjectionPoisonException) {
				return persistFailure(
					cursor,
					poison.admissionOrdinal,
					poison.failureCode,
					terminal = true,
				)
			} catch (failure: Exception) {
				val ordinal = attemptedOrdinal
					?: return ActivityCapturedFactDrainResult.Failed(
						cursor,
						failedOrdinal = null,
						failureCode = failure::class.java.simpleName,
						terminal = false,
					)
				return persistFailure(
					cursor,
					ordinal,
					failure::class.java.simpleName,
					terminal = false,
				)
			}

			when (pass) {
				is ActivityProjectionPass.Applied -> {
					cursor = pass.throughOrdinal
					windowsApplied += pass.windowsApplied
					eventsValidated += pass.eventsValidated
				}
				is ActivityProjectionPass.Deferred -> return ActivityCapturedFactDrainResult.Deferred(
					pass.throughOrdinal,
					pass.blockedOrdinal,
					pass.reason,
				)
				is ActivityProjectionPass.TerminalBlocked ->
					return ActivityCapturedFactDrainResult.Failed(
						pass.throughOrdinal,
						pass.failure.admissionOrdinal,
						pass.failure.failureCode,
						terminal = true,
					)
			}
		}
		return ActivityCapturedFactDrainResult.Complete(cursor, windowsApplied, eventsValidated)
	}

	private suspend fun projectTerminalRun(
		lane: SourceProductProjectionLaneEntity,
		originalCursor: Long,
		skippedThrough: Long,
		targetAdmissionOrdinal: Long,
		seedEvent: SourceProjectionEventIdentityRow,
		seed: ActivityCapturedWalAdmissionResult.Admitted,
	): ActivityProjectionPass {
		val authority = seed.acquisitionAuthority.captureAuthority
		val session = database.sourceSessionDao().session(authority.logicalTrackingId.value)
		val finalOrdinal = session?.finalAdmissionOrdinal
		if (finalOrdinal == null || finalOrdinal < seedEvent.admissionOrdinal ||
			finalOrdinal > targetAdmissionOrdinal ||
			lane.captureAdmissionCutoffOrdinal?.let { finalOrdinal > it } == true
		) {
			advanceCursor(lane, skippedThrough)
			return ActivityProjectionPass.Deferred(
				skippedThrough,
				seedEvent.admissionOrdinal,
				"ACTIVITY_TERMINAL_HIGH_WATER_UNSETTLED",
			)
		}

		val preflight = projectionPage(skippedThrough, finalOrdinal, MAX_RUN_EVENTS + 1)
		val overflowOrdinal = preflight.firstOverflowOrdinal(MAX_RUN_EVENTS)
		if (overflowOrdinal != null) {
			return terminalFailure(
				lane,
				originalCursor,
				overflowOrdinal,
				"ACTIVITY_TERMINAL_RUN_EVENT_LIMIT_EXCEEDED",
			)
		}

		val groups = linkedMapOf<ActivityProjectionWindowKey, ActivityProjectionWindowAccumulator>()
		var validated = 0
		for (event in preflight) {
			when (val admission = adapter.admit(SourceEventId(event.eventId))) {
				is ActivityCapturedWalAdmissionResult.Admitted -> {
					val candidateAuthority = admission.acquisitionAuthority.captureAuthority
					if (candidateAuthority.logicalTrackingId != authority.logicalTrackingId) {
						return terminalFailure(
							lane,
							originalCursor,
							event.admissionOrdinal,
							"ACTIVITY_TERMINAL_SESSION_AUTHORITY_INTERLEAVED",
						)
					}
					val key = ActivityProjectionWindowKey(
						admission.acquisitionAuthority,
						admission.settledWindow,
					)
					groups.getOrPut(key) {
						ActivityProjectionWindowAccumulator(event.admissionOrdinal)
					}.observations += admission.observation
					validated++
				}
				is ActivityCapturedWalAdmissionResult.Rejected -> when (admission.reason) {
					ActivityCapturedWalAdmissionRejection.CONTROL_ONLY -> validated++
					ActivityCapturedWalAdmissionRejection.DESTINATION_OWNER_MISMATCH ->
						throw ActivityLaneAuthorityChangedException(
							"ACTIVITY_DESTINATION_OWNER_CHANGED",
						)
					ActivityCapturedWalAdmissionRejection.DELETED_EVIDENCE,
					ActivityCapturedWalAdmissionRejection.RETAINED_EVIDENCE,
					-> throw ActivityLaneAuthorityChangedException(
						"ACTIVITY_TERMINAL_RUN_EVIDENCE_REDACTED",
					)
					else -> return terminalFailure(
						lane,
						originalCursor,
						event.admissionOrdinal,
						admission.reason.failureCode(),
					)
				}
				is ActivityCapturedWalAdmissionResult.Unavailable ->
					return if (admission.reason ==
						ActivityCapturedWalAdmissionUnavailable.UNSETTLED_FINITE_WINDOW
					) {
						advanceCursor(lane, skippedThrough)
						ActivityProjectionPass.Deferred(
							skippedThrough,
							event.admissionOrdinal,
							admission.reason.name,
						)
					} else {
						terminalFailure(
							lane,
							originalCursor,
							event.admissionOrdinal,
							admission.reason.name,
						)
					}
				is ActivityCapturedWalAdmissionResult.ObservationRejected ->
					return terminalFailure(
						lane,
						originalCursor,
						event.admissionOrdinal,
						"ACTIVITY_OBSERVATION_${admission.reason.name}",
					)
			}
		}

		val windows = groups.map { (key, group) ->
			val interval = key.settledWindow.interval
			val coalesced = ActivityCapturedFactCoalescer.coalesce(
				ActivityCapturedCoalescingRequest(
					mutation = ActivityCapturedWindowMutation(
						identity = ActivityCapturedWindowIdentity(
							key.acquisitionAuthority.captureAuthority,
							interval.startInclusiveNanos,
							interval.endExclusiveNanos,
						),
						semanticRevision = 1L,
						supersedesSemanticRevision = null,
					),
					observations = group.observations,
				),
			)
			val window = when (coalesced) {
				is ActivityCoalescingResult.Coalesced -> coalesced.window
				is ActivityCoalescingResult.Rejected -> return terminalFailure(
					lane,
					originalCursor,
					group.originAdmissionOrdinal,
					"ACTIVITY_COALESCING_${coalesced.reason.name}",
				)
			}
			ActivityProjectedWindow(group.originAdmissionOrdinal, window)
		}
		var applied = 0
		for (projected in windows) {
			when (val written = writer.write(ActivityCapturedWriteCommand.Captured(projected.window))) {
				is ActivityCapturedWriteResult.Applied -> applied++
				is ActivityCapturedWriteResult.Unchanged -> Unit
				is ActivityCapturedWriteResult.Rejected -> when (written.reason) {
					ActivityCapturedWriteRejection.DESTINATION_OWNER_CHANGED,
					ActivityCapturedWriteRejection.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
					ActivityCapturedWriteRejection.DELETED_SCOPE,
					ActivityCapturedWriteRejection.RETAINED_DATA,
					-> throw ActivityLaneAuthorityChangedException(
						"ACTIVITY_WRITER_${written.reason.name}",
					)
					else -> throw ActivityCapturedProjectionPoisonException(
						projected.originAdmissionOrdinal,
						"ACTIVITY_WRITER_${written.reason.name}",
					)
				}
			}
		}
		val projectionDao = database.sourceProjectionStateDao()
		preflight.forEach { event ->
			projectionDao.deleteFailure(WRITER_ID, WRITER_VERSION, event.admissionOrdinal)
		}
		advanceCursor(lane, finalOrdinal)
		return ActivityProjectionPass.Applied(finalOrdinal, applied, validated)
	}

	private suspend fun projectionPage(
		afterOrdinal: Long,
		throughOrdinal: Long,
		limit: Int,
	): List<SourceProjectionEventIdentityRow> = database.sourceEventWalDao()
		.sourceProjectionEventsAfterThrough(
			SourceKind.ACTIVITY.stableCode,
			afterOrdinal,
			throughOrdinal,
			limit,
		)

	private suspend fun terminalFailure(
		lane: SourceProductProjectionLaneEntity,
		cursor: Long,
		admissionOrdinal: Long,
		failureCode: String,
	): ActivityProjectionPass.TerminalBlocked {
		advanceCursor(lane, cursor)
		val failure = saveFailure(admissionOrdinal, failureCode, terminal = true)
		return ActivityProjectionPass.TerminalBlocked(failure, cursor)
	}

	private suspend fun persistFailure(
		cursor: Long,
		admissionOrdinal: Long,
		failureCode: String,
		terminal: Boolean,
	): ActivityCapturedFactDrainResult.Failed {
		val code = failureCode.ifBlank { "UNKNOWN_ACTIVITY_CAPTURED_PROJECTION_FAILURE" }
		val persisted = try {
			database.withTransaction {
				saveFailure(admissionOrdinal, code, terminal)
				true
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			false
		}
		return ActivityCapturedFactDrainResult.Failed(
			cursor,
			admissionOrdinal,
			if (persisted) code else "FAILURE_AUDIT_UNAVAILABLE",
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
			attemptCount = (dao.failure(WRITER_ID, WRITER_VERSION, admissionOrdinal)
				?.attemptCount ?: 0) + 1,
			failureCode = failureCode,
			terminal = terminal,
			lastAttemptAtMs = nowMs(),
		)
		dao.saveFailure(failure)
		return failure
	}

	private suspend fun resolveLane(): ActivityLaneResolution {
		val active = database.sourceProjectionStateDao().allActiveProductLanes()
			.filter { it.sourceKind == SourceKind.ACTIVITY.stableCode }
		if (active.isEmpty()) {
			return ActivityLaneResolution.Inactive(ActivityCapturedLaneInactiveReason.NO_ACTIVE_LANE)
		}
		if (active.size != 1) {
			return ActivityLaneResolution.Inactive(ActivityCapturedLaneInactiveReason.AMBIGUOUS_ACTIVE_LANE)
		}
		val lane = active.single()
		val binding = executableLaneCatalog.bindingFor(lane)
		if (binding == null || binding.source != SourceKind.ACTIVITY ||
			binding.bindingGeneration != BINDING_GENERATION ||
			binding.projectionId != WRITER_ID || binding.projectionVersion != WRITER_VERSION ||
			binding.captureModes != setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE)
		) return ActivityLaneResolution.Inactive(
			ActivityCapturedLaneInactiveReason.BINARY_BINDING_NOT_ENABLED,
		)
		if (!lane.hasExecutableActivityShape()) {
			return ActivityLaneResolution.Inactive(ActivityCapturedLaneInactiveReason.LANE_NOT_CANONICAL)
		}
		if (database.sourceProjectionStateDao().registration(WRITER_ID, WRITER_VERSION) != null) {
			return ActivityLaneResolution.Inactive(ActivityCapturedLaneInactiveReason.GLOBAL_WRITER_CONFLICT)
		}
		val owner = database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
		)
		if (owner?.owner != SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS ||
			owner.ownerGeneration != SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
		) return ActivityLaneResolution.Inactive(
			ActivityCapturedLaneInactiveReason.DESTINATION_NOT_OWNED,
		)
		return ActivityLaneResolution.Ready(lane)
	}

	private suspend fun requireExactLane(
		expected: SourceProductProjectionLaneEntity,
		expectedCursor: Long = expected.contiguousAdmissionOrdinal,
	): SourceProductProjectionLaneEntity {
		val current = resolveLane()
		if (current !is ActivityLaneResolution.Ready ||
			!current.lane.hasSameActivityExecutionBinding(expected)
		) throw ActivityLaneAuthorityChangedException("ACTIVITY_LANE_BINDING_CHANGED")
		if (current.lane.contiguousAdmissionOrdinal != expectedCursor) {
			throw ActivityLaneAuthorityChangedException("ACTIVITY_LANE_CURSOR_CHANGED")
		}
		return current.lane
	}

	private suspend fun evidenceState(): SourceEvidenceState =
		database.sourceEvidenceStateDao().get()
			?: throw ActivityLaneAuthorityChangedException("SOURCE_EVIDENCE_STATE_MISSING")

	private suspend fun failureIsLifecycleRejected(
		admissionOrdinal: Long,
		evidenceState: SourceEvidenceState,
	): Boolean {
		if (admissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal) return true
		val raw = database.sourceEventWalDao()
			.projectionEligibilityByAdmissionOrdinal(admissionOrdinal) ?: return true
		if (raw.sourceKind != SourceKind.ACTIVITY.stableCode) return true
		return raw.isLifecycleRejected(evidenceState) || raw.isDeletedScope()
	}

	private fun SourceEventProjectionEligibilityRow.isLifecycleRejected(
		evidenceState: SourceEvidenceState,
	): Boolean = authorizationPurposeEligibilityMask and
		SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
		capturedCollectedDataEpoch != evidenceState.collectedDataEpoch ||
		evidenceState.retainedFromMs?.let { retainedFrom ->
			acquiredAtMs < retainedFrom || earliestPossibleWallTimeMs()?.let { it < retainedFrom } == true
		} == true

	private fun SourceEventProjectionEligibilityRow.earliestPossibleWallTimeMs(): Long? {
		val wallTime = wallTimeMs?.takeIf { it >= 0L } ?: return null
		val uncertainty = wallTimeUncertaintyMs?.takeIf { it >= 0L } ?: return null
		return if (uncertainty >= wallTime) 0L else wallTime - uncertainty
	}

	private suspend fun SourceEventProjectionEligibilityRow.isDeletedScope(): Boolean {
		val logicalId = logicalTrackingId ?: return false
		val runId = serviceRunId ?: return false
		return database.sourceDeletionFenceDao().contains(
			sourceKind = SourceKind.ACTIVITY.stableCode,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind = SourceKind.ACTIVITY.stableCode,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = logicalId,
				serviceRunId = runId,
			),
		)
	}

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
		) == 1) { "Exact Activity product lane changed before its cursor commit" }
	}

	private fun nowMs(): Long = System.currentTimeMillis().coerceAtLeast(0L)

	companion object {
		const val WRITER_ID = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION
		const val BINDING_GENERATION = SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION
		const val MANUAL_CAPTURE_MODE_MASK = 1L

		private const val PROBE_PAGE_SIZE = 64
		private const val MAX_RUN_EVENTS = 4_096
	}
}

internal enum class ActivityCapturedLaneInactiveReason {
	NO_ACTIVE_LANE,
	AMBIGUOUS_ACTIVE_LANE,
	BINARY_BINDING_NOT_ENABLED,
	LANE_NOT_CANONICAL,
	GLOBAL_WRITER_CONFLICT,
	DESTINATION_NOT_OWNED,
}

internal sealed interface ActivityCapturedFactDrainResult {
	data class Inactive(val reason: ActivityCapturedLaneInactiveReason) : ActivityCapturedFactDrainResult

	data class Complete(
		val lastCompletedOrdinal: Long,
		val windowsApplied: Int,
		val eventsValidated: Int,
	) : ActivityCapturedFactDrainResult

	data class Deferred(
		val lastCompletedOrdinal: Long,
		val blockedOrdinal: Long,
		val reason: String,
	) : ActivityCapturedFactDrainResult

	data class AuthorityChanged(val reason: String) : ActivityCapturedFactDrainResult

	data class Failed(
		val lastCompletedOrdinal: Long,
		val failedOrdinal: Long?,
		val failureCode: String,
		val terminal: Boolean,
	) : ActivityCapturedFactDrainResult
}

private sealed interface ActivityLaneResolution {
	data class Ready(val lane: SourceProductProjectionLaneEntity) : ActivityLaneResolution
	data class Inactive(val reason: ActivityCapturedLaneInactiveReason) : ActivityLaneResolution
}

private sealed interface ActivityProjectionPass {
	data class Applied(
		val throughOrdinal: Long,
		val windowsApplied: Int,
		val eventsValidated: Int,
	) : ActivityProjectionPass

	data class Deferred(
		val throughOrdinal: Long,
		val blockedOrdinal: Long,
		val reason: String,
	) : ActivityProjectionPass

	data class TerminalBlocked(
		val failure: SourceProjectionFailureEntity,
		val throughOrdinal: Long,
	) : ActivityProjectionPass
}

private data class ActivityProjectionWindowKey(
	val acquisitionAuthority: ActivityCaptureAcquisitionAuthority,
	val settledWindow: ActivityCapturedSettledWindow,
)

private data class ActivityProjectionWindowAccumulator(
	val originAdmissionOrdinal: Long,
	val observations: MutableList<ActivityCapturedObservation> = mutableListOf(),
)

private data class ActivityProjectedWindow(
	val originAdmissionOrdinal: Long,
	val window: ActivityCapturedWindow,
)

private class ActivityLaneAuthorityChangedException(val reason: String) :
	IllegalStateException(reason)

private class ActivityCapturedProjectionPoisonException(
	val admissionOrdinal: Long,
	val failureCode: String,
) : IllegalStateException(failureCode)

/** Returns the exact first row outside a caller's finite processing budget. */
internal fun List<SourceProjectionEventIdentityRow>.firstOverflowOrdinal(maximumEvents: Int): Long? {
	require(maximumEvents > 0)
	return getOrNull(maximumEvents)?.admissionOrdinal
}

private fun ActivityCapturedWalAdmissionRejection.failureCode(): String =
	"ACTIVITY_WAL_${name}"

private fun SourceProductProjectionLaneEntity.hasExecutableActivityShape(): Boolean =
	sourceKind == SourceKind.ACTIVITY.stableCode &&
		bindingGeneration == ActivityCapturedFactProjectionLane.BINDING_GENERATION &&
		projectionId == ActivityCapturedFactProjectionLane.WRITER_ID &&
		projectionVersion == ActivityCapturedFactProjectionLane.WRITER_VERSION &&
		captureModeMask == ActivityCapturedFactProjectionLane.MANUAL_CAPTURE_MODE_MASK &&
		productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL &&
		activatedRolloutRevision > 0L && activationOrdinal > 0L &&
		contiguousAdmissionOrdinal >= activationOrdinal - 1L &&
		captureAdmissionCutoffOrdinal?.let { contiguousAdmissionOrdinal <= it } != false &&
		retentionRequired && status == SourceProductProjectionLaneEntity.STATUS_ACTIVE &&
		terminalDisposition == null && terminalAtMs == null

@Suppress("CyclomaticComplexMethod")
private fun SourceProductProjectionLaneEntity.hasSameActivityExecutionBinding(
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
