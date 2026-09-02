package com.adsamcik.tracker.tracker.source.projection

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceEventProjectionEligibilityRow
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.ingress.CorruptSourceEventException
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.ingress.PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.PressureWindowQualification
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.toStableFlags
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Dormant bounded source-local writer for qualified session Pressure facts.
 *
 * An installed shadow lane validates retained source evidence and advances only its own cursor. It
 * never writes facts or publishes evidence. Canonical writes require the exact immutable manual
 * Pressure binding, candidate destination ownership, and agreeing manifest provenance. The legacy
 * PressureSample writer and all readers remain untouched until a separate explicit cutover.
 */
@Suppress("LargeClass", "TooManyFunctions") // One source-local transactional state machine.
@Singleton
class PressureSessionFactProjectionLane private constructor(
	private val database: AppDatabase,
	private val ingress: DurableSourceIngress,
	private val executableLaneCatalog: ExecutableSourceLaneCatalog,
	applicationScope: CoroutineScope?,
	@Suppress("UNUSED_PARAMETER") constructionMarker: Unit,
) {
	private val mutex = Mutex()
	private val drainSignals = Channel<Unit>(Channel.CONFLATED)

	@Inject
	constructor(
		database: AppDatabase,
		ingress: DurableSourceIngress,
		executableLaneCatalog: ExecutableSourceLaneCatalog,
		@ApplicationScope applicationScope: CoroutineScope,
	) : this(database, ingress, executableLaneCatalog, applicationScope, Unit)

	constructor(
		database: AppDatabase,
		ingress: DurableSourceIngress,
		@ApplicationScope applicationScope: CoroutineScope,
	) : this(database, ingress, ExecutableSourceLaneCatalog(), applicationScope, Unit)

	internal constructor(
		database: AppDatabase,
		ingress: DurableSourceIngress,
		executableLaneCatalog: ExecutableSourceLaneCatalog = ExecutableSourceLaneCatalog(),
	) : this(database, ingress, executableLaneCatalog, null, Unit)

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

	/** Conflated process-local hint; the WAL and exact lane cursor remain authoritative. */
	fun requestDrain() {
		drainSignals.trySend(Unit)
	}

	/** Drains one finite durable high-water snapshot. */
	suspend fun drainAvailable(): PressureSessionFactDrainResult = mutex.withLock {
		val initialLane = database.withTransaction { executableLaneOrNull() }
			?: return@withLock PressureSessionFactDrainResult.Inactive
		val target = try {
			database.withTransaction {
				val exactLane = requireExactLane(initialLane)
				val evidenceState = evidenceState()
				val durableHighWater = maxOf(
					database.sourceEventWalDao().maximumAdmissionOrdinal() ?: 0L,
					evidenceState.deletedSourceEventHighWaterOrdinal,
				)
				minOf(durableHighWater, exactLane.captureAdmissionCutoffOrdinal ?: Long.MAX_VALUE)
			}
		} catch (changed: PressureLaneAuthorityChangedException) {
			return@withLock PressureSessionFactDrainResult.AuthorityChanged(changed.reason)
		}
		if (target <= initialLane.contiguousAdmissionOrdinal) {
			return@withLock PressureSessionFactDrainResult.Complete(
				lastCompletedOrdinal = initialLane.contiguousAdmissionOrdinal,
				factsInserted = 0,
				eventsValidated = 0,
			)
		}
		drainThroughLocked(initialLane, target)
	}

	/** Test/cutover seam for a bounded already-durable interval; never installs or promotes a lane. */
	internal suspend fun drainThrough(
		throughAdmissionOrdinal: Long,
	): PressureSessionFactDrainResult = mutex.withLock {
		require(throughAdmissionOrdinal >= 0L)
		val initialLane = database.withTransaction { executableLaneOrNull() }
			?: return@withLock PressureSessionFactDrainResult.Inactive
		val target = minOf(
			throughAdmissionOrdinal,
			initialLane.captureAdmissionCutoffOrdinal ?: Long.MAX_VALUE,
		)
		if (target <= initialLane.contiguousAdmissionOrdinal) {
			return@withLock PressureSessionFactDrainResult.Complete(
				lastCompletedOrdinal = initialLane.contiguousAdmissionOrdinal,
				factsInserted = 0,
				eventsValidated = 0,
			)
		}
		drainThroughLocked(initialLane, target)
	}

	@Suppress(
		"CyclomaticComplexMethod",
		"LongMethod",
		"ReturnCount",
		"TooGenericExceptionCaught",
	) // Keeping read, validate, write, evidence, and cursor mutation in one Room transaction is deliberate.
	private suspend fun drainThroughLocked(
		initialLane: SourceProductProjectionLaneEntity,
		targetAdmissionOrdinal: Long,
	): PressureSessionFactDrainResult {
		var cursor = initialLane.contiguousAdmissionOrdinal
		var factsInserted = 0
		var eventsValidated = 0
		while (cursor < targetAdmissionOrdinal) {
			var attemptedOrdinal: Long? = null
			val pass = try {
				database.withTransaction {
					val lane = requireExactLane(initialLane, expectedCursor = cursor)
					val evidenceState = evidenceState()
					val manifestBindings = mutableMapOf<PressureManifestKey, SessionManifestSourceEntity>()
					val storedTerminal = database.sourceProjectionStateDao()
						.firstTerminalFailureAfterThrough(
							projectionId = WRITER_ID,
							projectionVersion = WRITER_VERSION,
							afterOrdinal = cursor,
							throughOrdinal = targetAdmissionOrdinal,
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
					val readThroughOrdinal = terminal?.admissionOrdinal?.minus(1L)
						?: targetAdmissionOrdinal
					if (readThroughOrdinal <= cursor) {
						return@withTransaction PressureProjectionPass.TerminalBlocked(
							failure = requireNotNull(terminal),
							throughOrdinal = cursor,
						)
					}

					val events = ingress.committedSourceBatch(
						source = SourceKind.PRESSURE,
						afterOrdinal = cursor,
						throughOrdinal = readThroughOrdinal,
						limit = BATCH_SIZE,
					)
					if (events.isEmpty()) {
						advanceCursor(lane, readThroughOrdinal)
						return@withTransaction PressureProjectionPass.Applied(
							throughOrdinal = readThroughOrdinal,
							factsInserted = 0,
							eventsValidated = 0,
						)
					}

					var inserted = 0
					var validated = 0
					var previousOrdinal = cursor
					for (event in events) {
						attemptedOrdinal = event.admissionOrdinal
						check(event.admissionOrdinal in (previousOrdinal + 1L)..readThroughOrdinal) {
							"Pressure reader returned an event outside its requested ordinal interval"
						}
						previousOrdinal = event.admissionOrdinal
						val terminalFailure = try {
							val candidate = event.toFactOrNull(
								evidenceState = evidenceState,
								lane = lane,
								manifestBindings = manifestBindings,
							)
							if (lane.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL &&
								candidate != null
							) {
								when (insertOrVerifyExactReplay(candidate)) {
									PressureFactAdmission.INSERTED -> inserted++
									PressureFactAdmission.EXACT_REPLAY -> Unit
								}
							}
							validated++
							database.sourceProjectionStateDao().deleteFailure(
								WRITER_ID,
								WRITER_VERSION,
								event.admissionOrdinal,
							)
							null
						} catch (poison: PressureSessionFactPoisonException) {
							saveFailure(poison.admissionOrdinal, poison.failureCode, terminal = true)
						} catch (collision: PressureSessionFactIdentityCollisionException) {
							saveFailure(collision.admissionOrdinal, collision.failureCode, terminal = true)
						}
						if (terminalFailure != null) {
							publishEvidenceRevisionIfNeeded(inserted)
							val through = event.admissionOrdinal - 1L
							advanceCursor(lane, through)
							return@withTransaction PressureProjectionPass.TerminalBlocked(
								failure = terminalFailure,
								throughOrdinal = through,
							)
						}
					}
					publishEvidenceRevisionIfNeeded(inserted)
					val through = events.last().admissionOrdinal
					// A committed cursor proves all fact, failure, and evidence writes in this transaction.
					advanceCursor(lane, through)
					PressureProjectionPass.Applied(through, inserted, validated)
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (changed: PressureLaneAuthorityChangedException) {
				return PressureSessionFactDrainResult.AuthorityChanged(changed.reason)
			} catch (corrupt: CorruptSourceEventException) {
				resolveCorruptFailure(initialLane, cursor, corrupt)
			} catch (poison: PressureSessionFactPoisonException) {
				return persistFailure(cursor, poison.admissionOrdinal, poison.failureCode, true)
			} catch (collision: PressureSessionFactIdentityCollisionException) {
				return persistFailure(cursor, collision.admissionOrdinal, collision.failureCode, true)
			} catch (failure: Exception) {
				val ordinal = attemptedOrdinal
				if (ordinal == null) {
					return PressureSessionFactDrainResult.Failed(
						lastCompletedOrdinal = cursor,
						failedOrdinal = null,
						failureCode = failure::class.java.simpleName,
						terminal = false,
					)
				}
				return persistFailure(cursor, ordinal, failure::class.java.simpleName, false)
			}

			when (pass) {
				is PressureProjectionPass.TerminalBlocked -> {
					return PressureSessionFactDrainResult.Failed(
						lastCompletedOrdinal = pass.throughOrdinal,
						failedOrdinal = pass.failure.admissionOrdinal,
						failureCode = pass.failure.failureCode,
						terminal = true,
					)
				}
				is PressureProjectionPass.Applied -> {
					cursor = pass.throughOrdinal
					factsInserted += pass.factsInserted
					eventsValidated += pass.eventsValidated
				}
			}
		}
		return PressureSessionFactDrainResult.Complete(cursor, factsInserted, eventsValidated)
	}

	private suspend fun persistFailure(
		cursor: Long,
		admissionOrdinal: Long,
		failureCode: String,
		terminal: Boolean,
	): PressureSessionFactDrainResult.Failed {
		val normalizedCode = failureCode.ifBlank { "UNKNOWN_PRESSURE_PROJECTION_FAILURE" }
		val persisted = try {
			database.withTransaction {
				saveFailure(admissionOrdinal, normalizedCode, terminal)
				true
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			false
		}
		return PressureSessionFactDrainResult.Failed(
			lastCompletedOrdinal = cursor,
			failedOrdinal = admissionOrdinal,
			failureCode = if (persisted) {
				normalizedCode
			} else {
				"FAILURE_AUDIT_UNAVAILABLE"
			},
			terminal = persisted && terminal,
		)
	}

	private suspend fun resolveCorruptFailure(
		expectedLane: SourceProductProjectionLaneEntity,
		cursor: Long,
		corrupt: CorruptSourceEventException,
	): PressureProjectionPass = database.withTransaction {
		val lane = requireExactLane(expectedLane, expectedCursor = cursor)
		val evidenceState = evidenceState()
		check(corrupt.sourceKind == SourceKind.PRESSURE.stableCode) {
			"Pressure reader reported corruption for another source"
		}
		check(corrupt.admissionOrdinal > cursor) {
			"Pressure reader reported corruption at or before its cursor"
		}
		if (failureIsLifecycleRejected(corrupt.admissionOrdinal, evidenceState)) {
			database.sourceProjectionStateDao().deleteFailure(
				WRITER_ID,
				WRITER_VERSION,
				corrupt.admissionOrdinal,
			)
			advanceCursor(lane, corrupt.admissionOrdinal)
			PressureProjectionPass.Applied(corrupt.admissionOrdinal, 0, 0)
		} else {
			val failure = saveFailure(
				admissionOrdinal = corrupt.admissionOrdinal,
				failureCode = corrupt.failureCode,
				terminal = true,
			)
			val through = corrupt.admissionOrdinal - 1L
			advanceCursor(lane, through)
			PressureProjectionPass.TerminalBlocked(failure, through)
		}
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

	private suspend fun publishEvidenceRevisionIfNeeded(inserted: Int) {
		if (inserted == 0) {
			return
		}
		check(database.sourceEvidenceStateDao().incrementRevision(nowMs()) == 1) {
			"Unable to publish the Pressure fact evidence revision"
		}
	}

	private suspend fun failureIsLifecycleRejected(
		admissionOrdinal: Long,
		evidenceState: SourceEvidenceState,
	): Boolean {
		if (admissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal) {
			return true
		}
		val raw = database.sourceEventWalDao()
			.projectionEligibilityByAdmissionOrdinal(admissionOrdinal) ?: return true
		if (raw.sourceKind != SourceKind.PRESSURE.stableCode) {
			return true
		}
		return raw.isLifecycleRejected(evidenceState) || raw.isDeletedScope()
	}

	private fun SourceEventProjectionEligibilityRow.isLifecycleRejected(
		evidenceState: SourceEvidenceState,
	): Boolean = authorizationPurposeEligibilityMask and
		SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
		capturedCollectedDataEpoch != evidenceState.collectedDataEpoch ||
		evidenceState.retainedFromMs?.let { retainedFrom ->
			acquiredAtMs < retainedFrom ||
				wallTimeMs?.takeIf { it >= 0L }?.let { it < retainedFrom } == true
		} == true

	private suspend fun SourceEventProjectionEligibilityRow.isDeletedScope(): Boolean {
		val logicalTrackingId = logicalTrackingId ?: return false
		val serviceRunId = serviceRunId ?: return false
		return sessionScopeIsDeleted(logicalTrackingId, serviceRunId)
	}

	private suspend fun executableLaneOrNull(): SourceProductProjectionLaneEntity? {
		val active = database.sourceProjectionStateDao().allActiveProductLanes()
			.filter { lane -> lane.sourceKind == SourceKind.PRESSURE.stableCode }
		if (active.size != 1) {
			return null
		}
		return active.single().takeIf(::isExecutableLane)
	}

	@Suppress("ThrowsCount") // Each mismatch preserves its exact durable authority reason.
	private suspend fun requireExactLane(
		expected: SourceProductProjectionLaneEntity,
		expectedCursor: Long = expected.contiguousAdmissionOrdinal,
	): SourceProductProjectionLaneEntity {
		val current = executableLaneOrNull()
		if (current == null || !current.hasSamePressureExecutionBinding(expected)) {
			throw PressureLaneAuthorityChangedException("PRESSURE_LANE_BINDING_CHANGED")
		}
		if (current.contiguousAdmissionOrdinal != expectedCursor) {
			throw PressureLaneAuthorityChangedException("PRESSURE_LANE_CURSOR_CHANGED")
		}
		if (database.sourceProjectionStateDao().registration(WRITER_ID, WRITER_VERSION) != null) {
			throw PressureLaneAuthorityChangedException("PRESSURE_WRITER_HAS_GLOBAL_REGISTRATION")
		}
		if (current.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL) {
			val owner = database.sourceDestinationOwnerDao().get(
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			)
			if (owner?.owner != SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS ||
				owner.ownerGeneration != SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
			) {
				throw PressureLaneAuthorityChangedException("PRESSURE_DESTINATION_OWNER_CHANGED")
			}
		}
		return current
	}

	@Suppress("CyclomaticComplexMethod") // The complete immutable execution binding is a single guard.
	private fun isExecutableLane(lane: SourceProductProjectionLaneEntity): Boolean {
		val binding = executableLaneCatalog.bindingFor(lane) ?: return false
		return binding == ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS &&
			lane.productStage in EXECUTABLE_STAGES &&
			lane.activatedRolloutRevision > 0L &&
			lane.activationOrdinal > 0L &&
			lane.contiguousAdmissionOrdinal >= lane.activationOrdinal - 1L &&
			lane.captureAdmissionCutoffOrdinal?.let { cutoff ->
				lane.contiguousAdmissionOrdinal <= cutoff
			} != false &&
			lane.retentionRequired &&
			lane.status == SourceProductProjectionLaneEntity.STATUS_ACTIVE &&
			lane.terminalDisposition == null && lane.terminalAtMs == null
	}

	private suspend fun evidenceState(): SourceEvidenceState =
		database.sourceEvidenceStateDao().get()
			?: throw PressureLaneAuthorityChangedException("SOURCE_EVIDENCE_STATE_MISSING")

	private suspend fun advanceCursor(
		lane: SourceProductProjectionLaneEntity,
		throughOrdinal: Long,
	) {
		if (throughOrdinal == lane.contiguousAdmissionOrdinal) {
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
			expectedCurrentOrdinal = lane.contiguousAdmissionOrdinal,
			throughOrdinal = throughOrdinal,
			updatedAtMs = nowMs(),
		) == 1) { "Exact Pressure product lane changed before its cursor commit" }
	}

	private suspend fun insertOrVerifyExactReplay(
		candidate: PressureFactRevisionEntity,
	): PressureFactAdmission {
		val dao = database.pressureFactRevisionDao()
		if (dao.insert(candidate) != INSERT_IGNORED) {
			return PressureFactAdmission.INSERTED
		}
		val replay = dao.replay(
			candidate.writerProjectionId,
			candidate.writerProjectionVersion,
			candidate.logicalFactId,
			candidate.semanticRevision,
			candidate.mutationId,
			candidate.sourceAdmissionOrdinal,
		)
		if (replay == candidate) {
			return PressureFactAdmission.EXACT_REPLAY
		}
		throw PressureSessionFactIdentityCollisionException(
			admissionOrdinal = candidate.sourceAdmissionOrdinal,
			failureCode = "PRESSURE_IDENTITY_COLLISION",
		)
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod")
	private suspend fun AdmittedSourceEvent<out SourcePayload>.toFactOrNull(
		evidenceState: SourceEvidenceState,
		lane: SourceProductProjectionLaneEntity,
		manifestBindings: MutableMap<PressureManifestKey, SessionManifestSourceEntity>,
	): PressureFactRevisionEntity? {
		val evidence = evidence
		if (evidence.capturedCollectedDataEpoch != evidenceState.collectedDataEpoch ||
			admissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal ||
			evidenceState.retainedFromMs?.let { evidence.acquiredAtMs < it } == true ||
			evidence.registrationPurposeEligibilityMask and
			SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
			evidence.logicalTrackingId == null ||
			evidence.serviceRunId == null
		) {
			return null
		}
		val logicalTrackingId = evidence.logicalTrackingId.value
		val serviceRunId = evidence.serviceRunId.value
		if (sessionScopeIsDeleted(logicalTrackingId, serviceRunId)) {
			return null
		}

		fun poison(code: String): Nothing = throw PressureSessionFactPoisonException(
			admissionOrdinal,
			code,
		)

		if (evidence.payloadVersion != PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION) {
			poison("PRESSURE_PAYLOAD_VERSION_UNSUPPORTED")
		}
		val payload = evidence.payload as? PressureWindowPayload
			?: poison("PRESSURE_PAYLOAD_TYPE_MISMATCH")
		if (payload.windowStartElapsedRealtimeNanos < 0L ||
			payload.windowEndElapsedRealtimeNanos < payload.windowStartElapsedRealtimeNanos ||
			payload.windowEndElapsedRealtimeNanos != evidence.observedElapsedRealtimeNanos
		) {
			poison("PRESSURE_WINDOW_INVALID")
		}

		val firstHectopascals = payload.firstHectopascals
			?: poison("PRESSURE_PAYLOAD_V4_INCOMPLETE")
		val lastHectopascals = payload.lastHectopascals
			?: poison("PRESSURE_PAYLOAD_V4_INCOMPLETE")
		val effectiveSamplePeriodMicros = payload.effectiveSamplePeriodMicros
			?: poison("PRESSURE_PAYLOAD_V4_INCOMPLETE")
		val effectiveMaximumReportLatencyMicros = payload.effectiveMaximumReportLatencyMicros
			?: poison("PRESSURE_PAYLOAD_V4_INCOMPLETE")
		val targetWindowDurationNanos = payload.targetWindowDurationNanos
			?: poison("PRESSURE_PAYLOAD_V4_INCOMPLETE")
		val expectedSampleCount = payload.expectedSampleCount
			?: poison("PRESSURE_PAYLOAD_V4_INCOMPLETE")
		val maximumInterSampleGapNanos = payload.maximumInterSampleGapNanos
			?: poison("PRESSURE_PAYLOAD_V4_INCOMPLETE")
		val qualification = try {
			PressureWindowQualification.classify(payload)
		} catch (_: IllegalArgumentException) {
			poison("PRESSURE_PAYLOAD_V4_INVALID")
		}
		val incompleteFlag = SourceQualityFlag.INCOMPLETE_WINDOW in evidence.quality.flags
		if (incompleteFlag != (qualification == PressureWindowQualification.PARTIAL)) {
			poison("PRESSURE_QUALIFICATION_MISMATCH")
		}

		val manifestRevision = evidence.sessionManifestRevision
			?: poison("PRESSURE_MANIFEST_REVISION_MISSING")
		val policyRevision = evidence.sourcePolicyRevision
			?: poison("PRESSURE_POLICY_REVISION_MISSING")
		val consentEpoch = evidence.captureConsentEpoch
			?: poison("PRESSURE_CAPTURE_CONSENT_EPOCH_MISSING")
		if (evidence.lifecycleLeaseGeneration == null) {
			poison("PRESSURE_LIFECYCLE_LEASE_MISSING")
		}
		val manifestKey = PressureManifestKey(
			logicalTrackingId,
			serviceRunId,
			manifestRevision,
			policyRevision,
			consentEpoch,
		)
		val manifestBinding = manifestBindings[manifestKey] ?: resolveManifestBinding(
			key = manifestKey,
			admissionOrdinal = admissionOrdinal,
		).also { resolved -> manifestBindings[manifestKey] = resolved }
		if (lane.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL) {
			val owner = database.sourceDestinationOwnerDao().get(
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			) ?: poison("PRESSURE_DESTINATION_OWNER_MISSING")
			if (owner.owner != SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS ||
				owner.ownerGeneration != SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION ||
				manifestBinding.outputDestination != owner.destination ||
				manifestBinding.writerOwner != owner.owner ||
				manifestBinding.writerOwnerGeneration != owner.ownerGeneration
			) {
				poison("PRESSURE_MANIFEST_WRITER_NOT_ACTIVE")
			}
			if (manifestBinding.writerProjectionId != lane.projectionId ||
				manifestBinding.writerProjectionVersion != lane.projectionVersion ||
				manifestBinding.writerBindingGeneration != lane.bindingGeneration
			) {
				poison("PRESSURE_MANIFEST_PROJECTION_MISMATCH")
			}
		}

		val endTimeMs = evidence.wallTimeMs ?: poison("PRESSURE_WALL_TIME_MISSING")
		if (endTimeMs < 0L) {
			poison("PRESSURE_WALL_TIME_NEGATIVE")
		}
		if (evidenceState.retainedFromMs?.let { endTimeMs < it } == true) {
			return null
		}
		val uncertaintyMs = evidence.wallTimeUncertaintyMs
			?: poison("PRESSURE_WALL_TIME_UNCERTAINTY_MISSING")
		val durationMs = (payload.windowEndElapsedRealtimeNanos -
			payload.windowStartElapsedRealtimeNanos) / NANOS_PER_MILLISECOND
		if (durationMs > endTimeMs) {
			poison("PRESSURE_WALL_TIME_UNDERFLOW")
		}
		val startTimeMs = endTimeMs - durationMs
		val logicalFactId = "$WRITER_ID:${eventId.value}"
		val mutationId = "$logicalFactId:$SEMANTIC_REVISION"
		val sourceQualityFlags = evidence.quality.toStableFlags()
		val effectChecksum = effectChecksum(
			logicalFactId,
			SEMANTIC_REVISION,
			mutationId,
			eventId.value,
			admissionOrdinal,
			WRITER_ID,
			WRITER_VERSION,
			lane.bindingGeneration,
			evidence.payloadVersion,
			startTimeMs,
			endTimeMs,
			payload.windowStartElapsedRealtimeNanos,
			payload.windowEndElapsedRealtimeNanos,
			evidence.clockDomainId,
			uncertaintyMs,
			payload.sampleCount,
			payload.meanHectopascals,
			payload.sumSquaredDeviations,
			payload.minimumHectopascals,
			payload.maximumHectopascals,
			payload.firstProviderSequence,
			payload.lastProviderSequence,
			firstHectopascals,
			lastHectopascals,
			payload.slopeHectopascalsPerSecond,
			payload.rSquared,
			payload.sensorAccuracy.name,
			effectiveSamplePeriodMicros,
			effectiveMaximumReportLatencyMicros,
			targetWindowDurationNanos,
			expectedSampleCount,
			maximumInterSampleGapNanos,
			payload.closureKind.name,
			qualification.name,
			sourceQualityFlags,
			evidence.quality.confidence,
			logicalTrackingId,
			serviceRunId,
			PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision,
			policyRevision,
			consentEpoch,
			manifestBinding.outputDestination,
			manifestBinding.writerOwner,
			manifestBinding.writerOwnerGeneration,
			manifestBinding.writerProjectionId,
			manifestBinding.writerProjectionVersion,
			manifestBinding.writerBindingGeneration,
			evidence.capturedCollectedDataEpoch,
		)
		return try {
			PressureFactRevisionEntity(
				logicalFactId = logicalFactId,
				semanticRevision = SEMANTIC_REVISION,
				mutationId = mutationId,
				sourceEventId = eventId.value,
				sourceAdmissionOrdinal = admissionOrdinal,
				writerProjectionId = WRITER_ID,
				writerProjectionVersion = WRITER_VERSION,
				writerBindingGeneration = lane.bindingGeneration,
				payloadVersion = evidence.payloadVersion,
				intervalStartTimeMs = startTimeMs,
				intervalEndTimeMs = endTimeMs,
				windowStartElapsedRealtimeNanos = payload.windowStartElapsedRealtimeNanos,
				windowEndElapsedRealtimeNanos = payload.windowEndElapsedRealtimeNanos,
				clockDomainId = evidence.clockDomainId,
				wallTimeUncertaintyMs = uncertaintyMs,
				sampleCount = payload.sampleCount,
				meanHectopascals = payload.meanHectopascals,
				sumSquaredDeviations = payload.sumSquaredDeviations,
				minimumHectopascals = payload.minimumHectopascals,
				maximumHectopascals = payload.maximumHectopascals,
				firstProviderSequence = payload.firstProviderSequence,
				lastProviderSequence = payload.lastProviderSequence,
				firstHectopascals = firstHectopascals,
				lastHectopascals = lastHectopascals,
				slopeHectopascalsPerSecond = payload.slopeHectopascalsPerSecond,
				rSquared = payload.rSquared,
				sensorAccuracy = payload.sensorAccuracy.name,
				effectiveSamplePeriodMicros = effectiveSamplePeriodMicros,
				effectiveMaximumReportLatencyMicros = effectiveMaximumReportLatencyMicros,
				targetWindowDurationNanos = targetWindowDurationNanos,
				expectedSampleCount = expectedSampleCount,
				maximumInterSampleGapNanos = maximumInterSampleGapNanos,
				closureKind = payload.closureKind.name,
				qualification = qualification.name,
				sourceQualityFlags = sourceQualityFlags,
				sourceQualityConfidence = evidence.quality.confidence,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				manifestRevision = manifestRevision,
				sourcePolicyRevision = policyRevision,
				captureConsentEpoch = consentEpoch,
				collectedDataEpoch = evidence.capturedCollectedDataEpoch,
				effectChecksum = effectChecksum,
				appliedAtMs = endTimeMs,
			)
		} catch (_: IllegalArgumentException) {
			poison("PRESSURE_FACT_INVARIANT_MISMATCH")
		}
	}

	private suspend fun resolveManifestBinding(
		key: PressureManifestKey,
		admissionOrdinal: Long,
	): SessionManifestSourceEntity {
		fun poison(code: String): Nothing = throw PressureSessionFactPoisonException(
			admissionOrdinal,
			code,
		)
		val sessionDao = database.sourceSessionDao()
		val manifest = sessionDao.manifestByServiceRunRevision(
			key.serviceRunId,
			key.manifestRevision,
		) ?: poison("PRESSURE_MANIFEST_MISSING")
		if (manifest.logicalTrackingId != key.logicalTrackingId ||
			manifest.sourcePolicyRevision != key.policyRevision
		) {
			poison("PRESSURE_MANIFEST_MEMBERSHIP_MISMATCH")
		}
		val sources = sessionDao.manifestSources(key.logicalTrackingId, key.manifestRevision)
		if (!SessionManifestIntegrity.verify(manifest, sources)) {
			poison("PRESSURE_MANIFEST_INTEGRITY_MISMATCH")
		}
		val binding = sources.singleOrNull { source ->
			source.sourceKind == SourceKind.PRESSURE.stableCode &&
				source.purpose == PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE
		} ?: poison("PRESSURE_MANIFEST_BINDING_MISSING")
		if (!binding.persistenceEligible || binding.consentEpoch != key.consentEpoch) {
			poison("PRESSURE_MANIFEST_ELIGIBILITY_MISMATCH")
		}
		validateExecutableCandidateManifestBinding(binding, manifest.sessionMode, admissionOrdinal)
		return binding
	}

	private fun validateExecutableCandidateManifestBinding(
		binding: SessionManifestSourceEntity,
		sessionMode: String,
		admissionOrdinal: Long,
	) {
		if (binding.writerOwner != SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS) {
			return
		}
		fun poison(code: String): Nothing = throw PressureSessionFactPoisonException(
			admissionOrdinal,
			code,
		)
		val executableBinding = binding.writerBindingGeneration?.let { generation ->
			executableLaneCatalog.bindingFor(
				source = SourceKind.PRESSURE,
				bindingGeneration = generation,
				projectionId = binding.writerProjectionId,
				projectionVersion = binding.writerProjectionVersion,
			)
		} ?: poison("PRESSURE_MANIFEST_PROJECTION_MISMATCH")
		if (executableBinding != ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS) {
			poison("PRESSURE_MANIFEST_PROJECTION_MISMATCH")
		}
		if (sessionMode != "MANUAL" ||
			CaptureReachabilityMode.MANUAL_SESSION_CAPTURE !in executableBinding.captureModes
		) {
			poison("PRESSURE_MANIFEST_CAPTURE_MODE_MISMATCH")
		}
	}

	private suspend fun sessionScopeIsDeleted(
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean = database.sourceDeletionFenceDao().contains(
		sourceKind = SourceKind.PRESSURE.stableCode,
		purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
		scopeIdentityDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceKind.PRESSURE.stableCode,
			purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		),
	)

	private fun effectChecksum(vararg values: Any?): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value?.toString()
			if (text == null) {
				"-1:"
			} else {
				"${text.length}:$text"
			}
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray())
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	private fun nowMs(): Long = System.currentTimeMillis().coerceAtLeast(0L)

	/** Frozen identity for the dormant generation-1 manual Pressure fact lane. */
	companion object {
		const val WRITER_ID = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION
		/** Immutable generation-1 manual-session contract. */
		const val BINDING_GENERATION = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION
		const val MANUAL_CAPTURE_MODE_MASK = 1L

		private const val SEMANTIC_REVISION = 1L
		private const val BATCH_SIZE = 64
		private const val NANOS_PER_MILLISECOND = 1_000_000L
		private const val INSERT_IGNORED = -1L
		private val EXECUTABLE_STAGES = setOf(
			SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
			SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		)
	}
}

private data class PressureManifestKey(
	val logicalTrackingId: String,
	val serviceRunId: String,
	val manifestRevision: Long,
	val policyRevision: Long,
	val consentEpoch: Long,
)

@Suppress("CyclomaticComplexMethod") // Every immutable lane field participates in the CAS authority.
private fun SourceProductProjectionLaneEntity.hasSamePressureExecutionBinding(
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

/** Typed bounded-drain outcome for the dormant Pressure fact lane. */
sealed interface PressureSessionFactDrainResult {
	/** No exact executable Pressure product lane is installed. */
	data object Inactive : PressureSessionFactDrainResult

	/** The finite high-water completed with the reported validated and inserted counts. */
	data class Complete(
		val lastCompletedOrdinal: Long,
		val factsInserted: Int,
		val eventsValidated: Int,
	) : PressureSessionFactDrainResult

	/** Structural source-local authority changed before a cursor commit. */
	data class AuthorityChanged(val reason: String) : PressureSessionFactDrainResult

	/** A retryable or terminal failure stopped the bounded drain at an exact boundary. */
	data class Failed(
		val lastCompletedOrdinal: Long,
		val failedOrdinal: Long?,
		val failureCode: String,
		val terminal: Boolean,
	) : PressureSessionFactDrainResult
}

private sealed interface PressureProjectionPass {
	data class Applied(
		val throughOrdinal: Long,
		val factsInserted: Int,
		val eventsValidated: Int,
	) : PressureProjectionPass

	data class TerminalBlocked(
		val failure: SourceProjectionFailureEntity,
		val throughOrdinal: Long,
	) : PressureProjectionPass
}

private enum class PressureFactAdmission { INSERTED, EXACT_REPLAY }

private class PressureLaneAuthorityChangedException(val reason: String) :
	IllegalStateException(reason)

private class PressureSessionFactPoisonException(
	val admissionOrdinal: Long,
	val failureCode: String,
) : IllegalArgumentException(failureCode)

private class PressureSessionFactIdentityCollisionException(
	val admissionOrdinal: Long,
	val failureCode: String,
) : IllegalStateException(failureCode)
