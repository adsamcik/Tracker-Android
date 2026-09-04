package com.adsamcik.tracker.tracker.source.projection

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.markStepsRetentionTruncation
import com.adsamcik.tracker.shared.base.database.dao.SourceEventProjectionEligibilityRow
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.ingress.CorruptSourceEventException
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The bounded source-local writer for self-contained session Steps facts.
 *
 * This is deliberately not a generic materializer. An installed shadow lane may validate retained
 * WAL and advance only its own retention cursor. Destination facts and evidence-state mutations are
 * possible only for the exact canonical writer generation. The legacy StepInterval writer and
 * all product readers remain outside this lane until an explicit cutover fences them.
 */
@Singleton
class StepsSessionFactProjectionLane private constructor(
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
				// The WAL and lane cursor own retry. A later admission or startup kick is the only
				// retry trigger; a failed source must never create an in-process polling loop.
				try {
					drainAvailable()
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (_: Exception) {
					// A failure with an event ordinal is persisted by drainAvailable. A database-wide
					// failure has no safe durable row and is retried only on the next explicit signal.
				}
			}
		}
	}

	/** Conflated process-local hint; durable WAL and the exact lane cursor remain authoritative. */
	fun requestDrain() {
		drainSignals.trySend(Unit)
	}

	/** Drains a finite high-water snapshot; evidence admitted later requires another signal. */
	suspend fun drainAvailable(): StepsSessionFactDrainResult = mutex.withLock {
		val initialLane = database.withTransaction { executableLaneOrNull() }
			?: return@withLock StepsSessionFactDrainResult.Inactive
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
		} catch (changed: StepsLaneAuthorityChangedException) {
			return@withLock StepsSessionFactDrainResult.AuthorityChanged(changed.reason)
		}
		if (target <= initialLane.contiguousAdmissionOrdinal) {
			return@withLock StepsSessionFactDrainResult.Complete(
				lastCompletedOrdinal = initialLane.contiguousAdmissionOrdinal,
				factsInserted = 0,
				eventsValidated = 0,
			)
		}
		drainThroughLocked(initialLane, target)
	}

	/** Test/cutover seam for a bounded already-durable interval; it never installs or promotes a lane. */
	internal suspend fun drainThrough(
		throughAdmissionOrdinal: Long,
	): StepsSessionFactDrainResult = mutex.withLock {
		require(throughAdmissionOrdinal >= 0L)
		val initialLane = database.withTransaction { executableLaneOrNull() }
			?: return@withLock StepsSessionFactDrainResult.Inactive
		val target = minOf(
			throughAdmissionOrdinal,
			initialLane.captureAdmissionCutoffOrdinal ?: Long.MAX_VALUE,
		)
		if (target <= initialLane.contiguousAdmissionOrdinal) {
			return@withLock StepsSessionFactDrainResult.Complete(
				lastCompletedOrdinal = initialLane.contiguousAdmissionOrdinal,
				factsInserted = 0,
				eventsValidated = 0,
			)
		}
		drainThroughLocked(initialLane, target)
	}

	private suspend fun drainThroughLocked(
		initialLane: SourceProductProjectionLaneEntity,
		targetAdmissionOrdinal: Long,
	): StepsSessionFactDrainResult {
		var cursor = initialLane.contiguousAdmissionOrdinal
		var factsInserted = 0
		var eventsValidated = 0
		while (cursor < targetAdmissionOrdinal) {
			var attemptedOrdinal: Long? = null
			val pass = try {
				database.withTransaction {
					val lane = requireExactLane(initialLane, expectedCursor = cursor)
					val evidenceState = evidenceState()
					val manifestBindings = mutableMapOf<StepsManifestKey, SessionManifestSourceEntity>()
					val storedTerminal = database.sourceProjectionStateDao()
						.firstTerminalFailureAfterThrough(
							projectionId = WRITER_ID,
							projectionVersion = WRITER_VERSION,
							afterOrdinal = cursor,
							throughOrdinal = targetAdmissionOrdinal,
						)
					val terminal = storedTerminal?.takeUnless { failure ->
						if (failureIsLifecycleRejected(
								admissionOrdinal = failure.admissionOrdinal,
								failureCode = failure.failureCode,
								evidenceState = evidenceState,
							)
						) {
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
						return@withTransaction StepsProjectionPass.TerminalBlocked(
							failure = requireNotNull(terminal),
							throughOrdinal = cursor,
						)
					}

					val events = ingress.committedSourceBatch(
						source = SourceKind.STEPS,
						afterOrdinal = cursor,
						throughOrdinal = readThroughOrdinal,
						limit = BATCH_SIZE,
					)
					if (events.isEmpty()) {
						advanceCursor(lane, readThroughOrdinal)
						return@withTransaction StepsProjectionPass.Applied(
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
							"Steps reader returned an event outside its requested ordinal interval"
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
									FactAdmission.INSERTED -> inserted++
									FactAdmission.EXACT_REPLAY -> Unit
								}
							}
							validated++
							database.sourceProjectionStateDao().deleteFailure(
								WRITER_ID,
								WRITER_VERSION,
								event.admissionOrdinal,
							)
							null
						} catch (poison: StepsSessionFactPoisonException) {
							saveFailure(
								admissionOrdinal = poison.admissionOrdinal,
								failureCode = poison.failureCode,
								terminal = true,
							)
						} catch (collision: StepsSessionFactIdentityCollisionException) {
							saveFailure(
								admissionOrdinal = collision.admissionOrdinal,
								failureCode = collision.failureCode,
								terminal = true,
							)
						}
						if (terminalFailure != null) {
							publishEvidenceRevisionIfNeeded(inserted)
							val through = event.admissionOrdinal - 1L
							advanceCursor(lane, through)
							return@withTransaction StepsProjectionPass.TerminalBlocked(
								failure = terminalFailure,
								throughOrdinal = through,
							)
						}
					}
					publishEvidenceRevisionIfNeeded(inserted)
					val through = events.last().admissionOrdinal
					// Cursor is deliberately the final write in this transaction. A crash before it
					// rolls back facts/receipts/evidence; a committed cursor therefore proves them.
					advanceCursor(lane, through)
					StepsProjectionPass.Applied(
						throughOrdinal = through,
						factsInserted = inserted,
						eventsValidated = validated,
					)
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (changed: StepsLaneAuthorityChangedException) {
				return StepsSessionFactDrainResult.AuthorityChanged(changed.reason)
			} catch (corrupt: CorruptSourceEventException) {
				resolveCorruptFailure(initialLane, cursor, corrupt)
			} catch (poison: StepsSessionFactPoisonException) {
				return persistFailure(
					cursor = cursor,
					admissionOrdinal = poison.admissionOrdinal,
					failureCode = poison.failureCode,
					terminal = true,
				)
			} catch (collision: StepsSessionFactIdentityCollisionException) {
				return persistFailure(
					cursor = cursor,
					admissionOrdinal = collision.admissionOrdinal,
					failureCode = collision.failureCode,
					terminal = true,
				)
			} catch (failure: Exception) {
				val ordinal = attemptedOrdinal
				if (ordinal == null) {
					return StepsSessionFactDrainResult.Failed(
						lastCompletedOrdinal = cursor,
						failedOrdinal = null,
						failureCode = failure::class.java.simpleName,
						terminal = false,
					)
				}
				return persistFailure(
					cursor = cursor,
					admissionOrdinal = ordinal,
					failureCode = failure::class.java.simpleName,
					terminal = false,
				)
			}

			when (pass) {
				is StepsProjectionPass.TerminalBlocked -> {
					return StepsSessionFactDrainResult.Failed(
						lastCompletedOrdinal = pass.throughOrdinal,
						failedOrdinal = pass.failure.admissionOrdinal,
						failureCode = pass.failure.failureCode,
						terminal = true,
					)
				}
				is StepsProjectionPass.Applied -> {
					cursor = pass.throughOrdinal
					factsInserted += pass.factsInserted
					eventsValidated += pass.eventsValidated
				}
			}
		}
		return StepsSessionFactDrainResult.Complete(cursor, factsInserted, eventsValidated)
	}

	private suspend fun persistFailure(
		cursor: Long,
		admissionOrdinal: Long,
		failureCode: String,
		terminal: Boolean,
	): StepsSessionFactDrainResult.Failed {
		val normalizedCode = failureCode.ifBlank { "UNKNOWN_STEPS_PROJECTION_FAILURE" }
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
		return StepsSessionFactDrainResult.Failed(
			lastCompletedOrdinal = cursor,
			failedOrdinal = admissionOrdinal,
			failureCode = if (persisted) normalizedCode else "FAILURE_AUDIT_UNAVAILABLE",
			terminal = persisted && terminal,
		)
	}

	private suspend fun resolveCorruptFailure(
		expectedLane: SourceProductProjectionLaneEntity,
		cursor: Long,
		corrupt: CorruptSourceEventException,
	): StepsProjectionPass = database.withTransaction {
		val lane = requireExactLane(expectedLane, expectedCursor = cursor)
		val evidenceState = evidenceState()
		check(corrupt.sourceKind == SourceKind.STEPS.stableCode) {
			"Steps reader reported corruption for another source"
		}
		check(corrupt.admissionOrdinal > cursor) {
			"Steps reader reported corruption at or before its cursor"
		}
		if (failureIsLifecycleRejected(
				admissionOrdinal = corrupt.admissionOrdinal,
				failureCode = corrupt.failureCode,
				evidenceState = evidenceState,
			)
		) {
			database.sourceProjectionStateDao().deleteFailure(
				WRITER_ID,
				WRITER_VERSION,
				corrupt.admissionOrdinal,
			)
			advanceCursor(lane, corrupt.admissionOrdinal)
			StepsProjectionPass.Applied(
				throughOrdinal = corrupt.admissionOrdinal,
				factsInserted = 0,
				eventsValidated = 0,
			)
		} else {
			val failure = saveFailure(
				admissionOrdinal = corrupt.admissionOrdinal,
				failureCode = corrupt.failureCode,
				terminal = true,
			)
			val through = corrupt.admissionOrdinal - 1L
			advanceCursor(lane, through)
			StepsProjectionPass.TerminalBlocked(failure, through)
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
			attemptCount = (dao.failure(
				WRITER_ID,
				WRITER_VERSION,
				admissionOrdinal,
			)?.attemptCount ?: 0) + 1,
			failureCode = failureCode,
			terminal = terminal,
			lastAttemptAtMs = nowMs(),
		)
		dao.saveFailure(failure)
		return failure
	}

	private suspend fun publishEvidenceRevisionIfNeeded(inserted: Int) {
		if (inserted == 0) return
		check(database.sourceEvidenceStateDao().incrementRevision(nowMs()) == 1) {
			"Unable to publish the Steps fact evidence revision"
		}
	}

	@Suppress("CyclomaticComplexMethod", "ReturnCount")
	private suspend fun failureIsLifecycleRejected(
		admissionOrdinal: Long,
		failureCode: String,
		evidenceState: SourceEvidenceState,
	): Boolean {
		if (admissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal) return true
		// A failed WAL integrity identity authenticates none of the row's scope, purpose, epoch,
		// or time fields. Only the independent global deletion high-water may release it.
		if (failureCode == RAW_PAYLOAD_INTEGRITY_FAILURE) return false
		val raw = database.sourceEventWalDao()
			.projectionEligibilityByAdmissionOrdinal(admissionOrdinal) ?: return true
		if (raw.sourceKind != SourceKind.STEPS.stableCode) return true
		if (raw.isDeletedScope()) return true
		if (raw.authorizationPurposeEligibilityMask and
			SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
			raw.capturedCollectedDataEpoch != evidenceState.collectedDataEpoch
		) {
			return true
		}
		// A poison caused by the event's own wall/acquisition shape cannot be released by
		// consulting those same disputed timestamps. Exact scope deletion above remains valid;
		// otherwise only the independent global deletion high-water may make it disappear.
		if (failureCode in RETENTION_UNTRUSTED_TIME_FAILURES) return false
		val retainedFromMs = evidenceState.retainedFromMs
		val retentionRejected = retainedFromMs != null && (
			raw.acquiredAtMs < retainedFromMs ||
				raw.wallTimeMs?.takeIf { it >= 0L }?.let { it < retainedFromMs } == true
			)
		if (retentionRejected) {
			val logicalTrackingId = raw.logicalTrackingId ?: return true
			val serviceRunId = raw.serviceRunId ?: return true
			markRetentionTruncation(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				collectedDataEpoch = evidenceState.collectedDataEpoch,
				admissionOrdinal = admissionOrdinal,
			)
			return true
		}
		return false
	}

	private suspend fun SourceEventProjectionEligibilityRow.isDeletedScope(): Boolean {
		val logicalTrackingId = logicalTrackingId ?: return false
		val serviceRunId = serviceRunId ?: return false
		return sessionScopeIsDeleted(logicalTrackingId, serviceRunId)
	}

	private suspend fun executableLaneOrNull(): SourceProductProjectionLaneEntity? {
		val active = database.sourceProjectionStateDao().allActiveProductLanes()
			.filter { lane -> lane.sourceKind == SourceKind.STEPS.stableCode }
		if (active.size != 1) return null
		return active.single().takeIf(::isExecutableLane)
	}

	private suspend fun requireExactLane(
		expected: SourceProductProjectionLaneEntity,
		expectedCursor: Long = expected.contiguousAdmissionOrdinal,
	): SourceProductProjectionLaneEntity {
		val current = executableLaneOrNull()
		if (current == null || !current.hasSameExecutionBinding(expected)) {
			throw StepsLaneAuthorityChangedException("STEPS_LANE_BINDING_CHANGED")
		}
		if (current.contiguousAdmissionOrdinal != expectedCursor) {
			throw StepsLaneAuthorityChangedException("STEPS_LANE_CURSOR_CHANGED")
		}
		if (database.sourceProjectionStateDao().registration(WRITER_ID, WRITER_VERSION) != null) {
			throw StepsLaneAuthorityChangedException("STEPS_WRITER_HAS_GLOBAL_REGISTRATION")
		}
		if (current.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL) {
			val owner = database.sourceDestinationOwnerDao().get(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			)
			if (owner?.owner != SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS) {
				throw StepsLaneAuthorityChangedException("STEPS_DESTINATION_OWNER_CHANGED")
			}
		}
		return current
	}

	private fun isExecutableLane(lane: SourceProductProjectionLaneEntity): Boolean {
		val binding = executableLaneCatalog.bindingFor(lane) ?: return false
		return binding.source == SourceKind.STEPS &&
			binding.projectionId == WRITER_ID &&
			binding.projectionVersion == WRITER_VERSION &&
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
			?: throw StepsLaneAuthorityChangedException("SOURCE_EVIDENCE_STATE_MISSING")

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
		) == 1) { "Exact Steps product lane changed before its cursor commit" }
	}

	private suspend fun insertOrVerifyExactReplay(
		candidate: StepFactRevisionEntity,
	): FactAdmission {
		val dao = database.stepFactRevisionDao()
		if (dao.insert(candidate) != INSERT_IGNORED) return FactAdmission.INSERTED

		val byRevision = dao.revision(
			candidate.writerProjectionId,
			candidate.writerProjectionVersion,
			candidate.logicalFactId,
			candidate.semanticRevision,
		)
		val byMutation = dao.mutation(
			candidate.writerProjectionId,
			candidate.writerProjectionVersion,
			candidate.mutationId,
		)
		val byAdmission = dao.writerAdmission(
			candidate.writerProjectionId,
			candidate.writerProjectionVersion,
			requireNotNull(candidate.sourceAdmissionOrdinal),
		)
		if (byRevision == candidate && byMutation == candidate && byAdmission == candidate) {
			return FactAdmission.EXACT_REPLAY
		}
		val collisions = buildList {
			if (byRevision != null && byRevision != candidate) add("FACT_REVISION")
			if (byMutation != null && byMutation != candidate) add("MUTATION")
			if (byAdmission != null && byAdmission != candidate) add("WRITER_ADMISSION")
		}.ifEmpty { listOf("UNKNOWN_UNIQUE_CONSTRAINT") }.joinToString("+")
		throw StepsSessionFactIdentityCollisionException(
			admissionOrdinal = requireNotNull(candidate.sourceAdmissionOrdinal),
			failureCode = "STEPS_IDENTITY_COLLISION_$collisions",
		)
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun AdmittedSourceEvent<out SourcePayload>.toFactOrNull(
		evidenceState: SourceEvidenceState,
		lane: SourceProductProjectionLaneEntity,
		manifestBindings: MutableMap<StepsManifestKey, SessionManifestSourceEntity>,
	): StepFactRevisionEntity? {
		val evidence = evidence
		if (evidence.capturedCollectedDataEpoch != evidenceState.collectedDataEpoch ||
			admissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal ||
			evidence.registrationPurposeEligibilityMask and
			SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
			evidence.logicalTrackingId == null ||
			evidence.serviceRunId == null
		) return null
		val logicalTrackingId = evidence.logicalTrackingId.value
		val serviceRunId = evidence.serviceRunId.value
		fun poison(code: String): Nothing = throw StepsSessionFactPoisonException(
			admissionOrdinal,
			code,
		)
		val endTimeMs = evidence.wallTimeMs ?: poison("STEPS_WALL_TIME_MISSING")
		if (endTimeMs < 0L) poison("STEPS_WALL_TIME_NEGATIVE")
		if (endTimeMs != evidence.acquiredAtMs) {
			poison("STEPS_ACQUIRED_WALL_TIME_MISMATCH")
		}
		if (sessionScopeIsDeleted(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)) return null
		if (evidenceState.retainedFromMs?.let { evidence.acquiredAtMs < it } == true) {
			markRetentionTruncation(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				collectedDataEpoch = evidenceState.collectedDataEpoch,
				admissionOrdinal = admissionOrdinal,
			)
			return null
		}

		val payload = evidence.payload as? StepCounterWindowPayload
			?: poison("STEPS_PAYLOAD_TYPE_MISMATCH")
		if (payload.bootClockDomainId != evidence.clockDomainId) {
			poison("STEPS_BOOT_CLOCK_DOMAIN_MISMATCH")
		}
		if (payload.windowStartElapsedRealtimeNanos < 0L ||
			payload.windowEndElapsedRealtimeNanos < payload.windowStartElapsedRealtimeNanos ||
			payload.windowEndElapsedRealtimeNanos != evidence.observedElapsedRealtimeNanos
		) poison("STEPS_WINDOW_INVALID")
		if (payload.firstCumulativeCount < 0L || payload.lastCumulativeCount < 0L ||
			payload.deltaCount < 0L || payload.firstProviderSequence < 0L ||
			payload.lastProviderSequence < payload.firstProviderSequence
		) poison("STEPS_COUNTER_INVALID")

		val coverage = when (payload.boundaryKind) {
			StepBoundaryKind.BASELINE -> {
				if (payload.deltaCount != 0L ||
					payload.firstCumulativeCount != payload.lastCumulativeCount
				) poison("STEPS_BASELINE_SHAPE_INVALID")
				StepFactRevisionEntity.COVERAGE_BASELINE to 0L
			}
			StepBoundaryKind.COVERED -> {
				if (payload.lastCumulativeCount < payload.firstCumulativeCount ||
					payload.deltaCount != payload.lastCumulativeCount - payload.firstCumulativeCount
				) poison("STEPS_COVERED_DELTA_INVALID")
				StepFactRevisionEntity.COVERAGE_COVERED to payload.deltaCount
			}
			StepBoundaryKind.COUNTER_RESET -> {
				if (payload.deltaCount != 0L ||
					payload.lastCumulativeCount >= payload.firstCumulativeCount
				) poison("STEPS_RESET_SHAPE_INVALID")
				StepFactRevisionEntity.COVERAGE_RESET_GAP to 0L
			}
			StepBoundaryKind.LEGACY_AMBIGUOUS -> {
				if (payload.deltaCount != 0L) poison("STEPS_LEGACY_BOUNDARY_INVALID")
				StepFactRevisionEntity.COVERAGE_PARTIAL to 0L
			}
		}

		val manifestRevision = evidence.sessionManifestRevision
			?: poison("STEPS_MANIFEST_REVISION_MISSING")
		val policyRevision = evidence.sourcePolicyRevision
			?: poison("STEPS_POLICY_REVISION_MISSING")
		val consentEpoch = evidence.captureConsentEpoch
			?: poison("STEPS_CAPTURE_CONSENT_EPOCH_MISSING")
		if (evidence.lifecycleLeaseGeneration == null) {
			poison("STEPS_LIFECYCLE_LEASE_MISSING")
		}
		val manifestKey = StepsManifestKey(logicalTrackingId, serviceRunId, manifestRevision)
		val manifestBinding = manifestBindings[manifestKey] ?: resolveManifestBinding(
			key = manifestKey,
			admissionOrdinal = admissionOrdinal,
			policyRevision = policyRevision,
			consentEpoch = consentEpoch,
		).also { resolved -> manifestBindings[manifestKey] = resolved }
		if (lane.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL) {
			val owner = database.sourceDestinationOwnerDao().get(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			) ?: poison("STEPS_DESTINATION_OWNER_MISSING")
			if (manifestBinding.outputDestination != owner.destination ||
				manifestBinding.writerOwner != owner.owner ||
				manifestBinding.writerOwnerGeneration != owner.ownerGeneration
			) poison("STEPS_MANIFEST_WRITER_NOT_ACTIVE")
			if (manifestBinding.writerProjectionId != lane.projectionId ||
				manifestBinding.writerProjectionVersion != lane.projectionVersion ||
				manifestBinding.writerBindingGeneration != lane.bindingGeneration
			) poison("STEPS_MANIFEST_PROJECTION_MISMATCH")
		}
		if (evidenceState.retainedFromMs?.let { endTimeMs < it } == true) {
			markRetentionTruncation(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				collectedDataEpoch = evidenceState.collectedDataEpoch,
				admissionOrdinal = admissionOrdinal,
			)
			return null
		}
		val uncertaintyMs = evidence.wallTimeUncertaintyMs
			?: poison("STEPS_WALL_TIME_UNCERTAINTY_MISSING")
		val durationMs = (payload.windowEndElapsedRealtimeNanos -
			payload.windowStartElapsedRealtimeNanos) / NANOS_PER_MILLISECOND
		val startTimeMs = if (durationMs > endTimeMs) 0L else endTimeMs - durationMs
		val logicalFactId = "$WRITER_ID:${eventId.value}"
		val mutationId = "$logicalFactId:$SEMANTIC_REVISION:${StepFactRevisionEntity.OPERATION_UPSERT}"
		val unsignedFact = StepFactRevisionEntity(
			logicalFactId = logicalFactId,
			semanticRevision = SEMANTIC_REVISION,
			mutationId = mutationId,
			stepIntervalId = null,
			sourceEventId = eventId.value,
			sourceAdmissionOrdinal = admissionOrdinal,
			originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
			originIdentity = eventId.value,
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = lane.bindingGeneration,
			operation = StepFactRevisionEntity.OPERATION_UPSERT,
			intervalStartTimeMs = startTimeMs,
			intervalEndTimeMs = endTimeMs,
			intervalStartElapsedRealtimeNanos = payload.windowStartElapsedRealtimeNanos,
			intervalEndElapsedRealtimeNanos = payload.windowEndElapsedRealtimeNanos,
			clockDomainId = evidence.clockDomainId,
			bootClockDomainId = payload.bootClockDomainId,
			cumulativeStepCountStart = payload.firstCumulativeCount,
			cumulativeStepCountEnd = payload.lastCumulativeCount,
			wallTimeUncertaintyMs = uncertaintyMs,
			coverageKind = coverage.first,
			effectiveStepCount = coverage.second,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = manifestRevision,
			sourcePolicyRevision = policyRevision,
			captureConsentEpoch = consentEpoch,
			collectedDataEpoch = evidence.capturedCollectedDataEpoch,
			scopeDeletionGeneration = 0L,
			effectChecksum = UNSIGNED_EFFECT_CHECKSUM,
			// Deterministic durable-event time keeps a replay byte-for-byte comparable.
			appliedAtMs = endTimeMs,
		)
		return unsignedFact.copy(
			effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsignedFact),
		)
	}

	/** The marker commits with the skipped cursor so a later valid suffix cannot look complete. */
	private suspend fun markRetentionTruncation(
		logicalTrackingId: String,
		serviceRunId: String,
		collectedDataEpoch: Long,
		admissionOrdinal: Long,
	) {
		val inserted = try {
			database.markStepsRetentionTruncation(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				collectedDataEpoch = collectedDataEpoch,
				markedAtMs = nowMs(),
			)
		} catch (_: IllegalArgumentException) {
			throw StepsSessionFactPoisonException(
				admissionOrdinal,
				"STEPS_RETENTION_SCOPE_INVALID",
			)
		} catch (_: IllegalStateException) {
			throw StepsSessionFactPoisonException(
				admissionOrdinal,
				"STEPS_RETENTION_MARKER_CONFLICT",
			)
		}
		if (inserted) {
			check(database.sourceEvidenceStateDao().incrementRevision(nowMs()) == 1) {
				"Unable to publish the Steps retention-truncation marker"
			}
		}
	}

	private suspend fun resolveManifestBinding(
		key: StepsManifestKey,
		admissionOrdinal: Long,
		policyRevision: Long,
		consentEpoch: Long,
	): SessionManifestSourceEntity {
		fun poison(code: String): Nothing = throw StepsSessionFactPoisonException(
			admissionOrdinal,
			code,
		)
		val sessionDao = database.sourceSessionDao()
		val manifest = sessionDao.manifestByServiceRunRevision(
			key.serviceRunId,
			key.manifestRevision,
		) ?: poison("STEPS_MANIFEST_MISSING")
		if (manifest.logicalTrackingId != key.logicalTrackingId ||
			manifest.sourcePolicyRevision != policyRevision
		) poison("STEPS_MANIFEST_MEMBERSHIP_MISMATCH")
		val sources = sessionDao.manifestSources(key.logicalTrackingId, key.manifestRevision)
		if (!SessionManifestIntegrity.verify(manifest, sources)) {
			poison("STEPS_MANIFEST_INTEGRITY_MISMATCH")
		}
		val binding = sources.singleOrNull { source ->
			source.sourceKind == SourceKind.STEPS.stableCode &&
				source.purpose == StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE
		} ?: poison("STEPS_MANIFEST_BINDING_MISSING")
		if (!binding.persistenceEligible || binding.consentEpoch != consentEpoch) {
			poison("STEPS_MANIFEST_ELIGIBILITY_MISMATCH")
		}
		validateExecutableCandidateManifestBinding(binding, manifest.sessionMode, admissionOrdinal)
		return binding
	}

	private fun validateExecutableCandidateManifestBinding(
		binding: SessionManifestSourceEntity,
		sessionMode: String,
		admissionOrdinal: Long,
	) {
		if (binding.writerOwner != SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS) return
		fun poison(code: String): Nothing = throw StepsSessionFactPoisonException(
			admissionOrdinal,
			code,
		)
		val executableBinding = binding.writerBindingGeneration?.let { generation ->
			executableLaneCatalog.bindingFor(
				source = SourceKind.STEPS,
				bindingGeneration = generation,
				projectionId = binding.writerProjectionId,
				projectionVersion = binding.writerProjectionVersion,
			)
		} ?: poison("STEPS_MANIFEST_PROJECTION_MISMATCH")
		val captureMode = when (sessionMode) {
			"MANUAL" -> CaptureReachabilityMode.MANUAL_SESSION_CAPTURE
			"AUTOMATIC" -> CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE
			else -> poison("STEPS_MANIFEST_CAPTURE_MODE_MISMATCH")
		}
		if (captureMode !in executableBinding.captureModes) {
			poison("STEPS_MANIFEST_CAPTURE_MODE_MISMATCH")
		}
	}

	private suspend fun sessionScopeIsDeleted(
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean = database.sourceDeletionFenceDao().contains(
		sourceKind = SourceKind.STEPS.stableCode,
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
		scopeIdentityDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceKind.STEPS.stableCode,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		),
	)

	private fun nowMs(): Long = System.currentTimeMillis().coerceAtLeast(0L)

	companion object {
		const val WRITER_ID = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION
		/** Immutable generation-1 manual-session contract. */
		const val BINDING_GENERATION = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION
		const val MANUAL_CAPTURE_MODE_MASK = 1L

		private const val SEMANTIC_REVISION = 1L
		private const val UNSIGNED_EFFECT_CHECKSUM = "pending-live-wal-effect"
		private const val BATCH_SIZE = 64
		private const val NANOS_PER_MILLISECOND = 1_000_000L
		private const val INSERT_IGNORED = -1L
		private const val RAW_PAYLOAD_INTEGRITY_FAILURE = "RAW_PAYLOAD_INTEGRITY"
		private val RETENTION_UNTRUSTED_TIME_FAILURES = setOf(
			"STEPS_WALL_TIME_MISSING",
			"STEPS_WALL_TIME_NEGATIVE",
			"STEPS_ACQUIRED_WALL_TIME_MISMATCH",
		)
		private val EXECUTABLE_STAGES = setOf(
			SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
			SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		)
	}
}

private data class StepsManifestKey(
	val logicalTrackingId: String,
	val serviceRunId: String,
	val manifestRevision: Long,
)

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

sealed interface StepsSessionFactDrainResult {
	data object Inactive : StepsSessionFactDrainResult

	data class Complete(
		val lastCompletedOrdinal: Long,
		val factsInserted: Int,
		val eventsValidated: Int,
	) : StepsSessionFactDrainResult

	data class AuthorityChanged(val reason: String) : StepsSessionFactDrainResult

	data class Failed(
		val lastCompletedOrdinal: Long,
		val failedOrdinal: Long?,
		val failureCode: String,
		val terminal: Boolean,
	) : StepsSessionFactDrainResult
}

private sealed interface StepsProjectionPass {
	data class Applied(
		val throughOrdinal: Long,
		val factsInserted: Int,
		val eventsValidated: Int,
	) : StepsProjectionPass

	data class TerminalBlocked(
		val failure: SourceProjectionFailureEntity,
		val throughOrdinal: Long,
	) : StepsProjectionPass
}

private enum class FactAdmission { INSERTED, EXACT_REPLAY }

private class StepsLaneAuthorityChangedException(val reason: String) :
	IllegalStateException(reason)

private class StepsSessionFactPoisonException(
	val admissionOrdinal: Long,
	val failureCode: String,
) : IllegalArgumentException(failureCode)

private class StepsSessionFactIdentityCollisionException(
	val admissionOrdinal: Long,
	val failureCode: String,
) : IllegalStateException(failureCode)
