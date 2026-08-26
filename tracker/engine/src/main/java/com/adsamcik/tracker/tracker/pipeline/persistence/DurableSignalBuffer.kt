package com.adsamcik.tracker.tracker.pipeline.persistence

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalClaimDao
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.dao.SourceEvidenceStateDao
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.tracker.pipeline.DurableAdmissionStatus
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import androidx.room.withTransaction
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Durable write-ahead buffer for [TrackingSignal]s.
 *
 * Signals are first staged in a fast in-memory list (via [stage]).
 * [checkpoint] serializes them to the `pending_signal` Room table so they
 * survive process death, returning the generated row IDs. [peekBatch] reads
 * (but does NOT delete) the oldest entries so callers can persist them to the
 * destination tables and only then acknowledge the exact IDs — in a single
 * transaction — via [PendingSignalDao.deleteByIds].
 *
 * Thread safety:
 * - [stage], [stagingSize] and [checkpoint]'s staging mutation synchronize on
 *   [stagingLock].
 * - [peekBatch], [hasPendingEntries] and [clear] are DAO reads/writes and do
 *   not touch the staging list.
 */
@Singleton
class DurableSignalBuffer @Inject constructor(
	private val pendingSignalDao: PendingSignalDao,
	private val dispatchers: DispatchersProvider,
	private val pendingSignalClaimDao: PendingSignalClaimDao? = null,
	private val appDatabase: AppDatabase? = null,
	private val collectedDataLifecycleStore: CollectedDataLifecycleStore? = null,
) {
	private val stagingLock = Any()
	private val staging = mutableListOf<StagedSignal>()
	private var sessionId: Long = 0L

	fun setSessionId(id: Long) {
		sessionId = id
	}

	/**
	 * Stage a signal in the in-memory buffer with a stable identity. Fast, no
	 * I/O. When the producer supplied [TrackingSignal.persistenceSignalId], use
	 * it so retrying the same logical signal reaches the same WAL row; otherwise
	 * mint an identity for this staging attempt. The identity then survives
	 * staging, checkpointing, recovery, and destination insertion.
	 *
	 * [CheckpointedSignal] is only emitted after the corresponding
	 * `pending_signal` insert succeeds. Callers should therefore defer consuming
	 * producer state or creating typed destination rows until checkpoint commit.
	 */
	fun stage(signal: TrackingSignal): StagedSignal {
		val stagedSignal = StagedSignal(
			signalId = signal.persistenceSignalId?.takeIf(String::isNotBlank)
				?: UUID.randomUUID().toString(),
			signal = signal,
		)
		stage(stagedSignal)
		return stagedSignal
	}

	/**
	 * Stage a signal with an existing stable identity (primarily useful in tests and producer
	 * retries). Re-delivery of the same payload before checkpoint is coalesced here, so the DAO never
	 * receives two rows for one logical signal in a single admission batch.
	 */
	fun stage(stagedSignal: StagedSignal) {
		require(stagedSignal.signalId.isNotBlank()) { "signalId must not be blank" }
		synchronized(stagingLock) {
			val existing = staging.firstOrNull { it.signalId == stagedSignal.signalId }
			if (existing == null) {
				staging.add(stagedSignal)
			} else {
				require(existing.signal == stagedSignal.signal) {
					"Signal identity ${stagedSignal.signalId} was reused for a different staged payload"
				}
			}
		}
	}

	/** Number of signals currently in the in-memory staging area. */
	val stagingSize: Int get() = synchronized(stagingLock) { staging.size }

	/**
	 * Durably write all currently-staged signals to the Room WAL table.
	 *
	 * The staged signals are **only** removed from the in-memory buffer after
	 * the insert succeeds. If the insert throws (including cancellation), the
	 * staging list is left untouched so the signals can be retried on the next
	 * checkpoint. Returns the generated WAL row IDs (empty when nothing was
	 * staged).
	 */
	suspend fun checkpoint(
		onCommitted: (List<CheckpointedSignal>) -> Unit = {},
	): List<Long> = checkpointWithAdmission(onCommitted).admittedIds

	/**
	 * Checkpoint staged signals and report whether the lifecycle guard admitted every candidate.
	 *
	 * A lifecycle rejection is a successful, terminal discard rather than a storage failure: the
	 * rejected staged rows are removed with the admitted rows, but callers that would otherwise fan
	 * a signal out to derived consumers must not treat that signal as accepted.
	 */
	suspend fun checkpointWithAdmission(
		onCommitted: (List<CheckpointedSignal>) -> Unit = {},
	): CheckpointAdmission {
		val signals: List<StagedSignal> = synchronized(stagingLock) {
			if (staging.isEmpty()) return CheckpointAdmission.EMPTY
			staging.toList()
		}

		// This is the epoch observed while the producer owns the signal. It is
		// intentionally captured before admission: a later lifecycle transition
		// must reject this candidate instead of relabelling old data as new.
		val capturedLifecycle = collectedDataLifecycleStore?.snapshot()
		return withContext(NonCancellable + dispatchers.io) {
			val now = Time.nowMillis
			val candidates = signals.map { stagedSignal ->
				val encoded = SignalSerializer.encode(stagedSignal.signal)
				CandidateAdmission(
					stagedSignal = stagedSignal,
					acquiredAtMs = stagedSignal.signal.acquiredAtMs(now),
					capturedEpoch = capturedLifecycle?.epoch ?: 0L,
					entity = PendingSignalEntity(
						sessionId = sessionId,
						signalId = stagedSignal.signalId,
						envelopeVersion = encoded.envelopeVersion,
						payloadChecksum = encoded.payloadChecksum,
						signalJson = encoded.payloadJson,
						createdAt = now,
						capturedEpoch = capturedLifecycle?.epoch ?: 0L,
						acquiredAtMs = stagedSignal.signal.acquiredAtMs(now),
					),
				)
			}
			val admitted = admitCandidates(candidates, now)
			val ids = admitted.ids
			check(ids.size == admitted.candidates.size) {
				"Pending signal insert returned ${ids.size} IDs for ${admitted.candidates.size} rows"
			}

			// Keep the WAL commit, staging removal, and caller ID registration in
			// one non-cancellable section. This closes the post-commit cancellation
			// window that could otherwise checkpoint the same signals twice.
			synchronized(stagingLock) {
				val count = signals.size
				if (count >= staging.size) {
					staging.clear()
				} else {
					repeat(count) { staging.removeAt(0) }
				}
			}
			onCommitted(
				admitted.rows.zip(admitted.candidates) { row, candidate ->
					CheckpointedSignal(
						id = row.id,
						signalId = row.signalId,
						signal = candidate.stagedSignal.signal,
						// A retry can resolve a row committed by an earlier attempt. Keep
						// that row's immutable lifecycle metadata rather than relabelling
						// it with the retry's newer observation.
						capturedEpoch = row.capturedEpoch,
						acquiredAtMs = row.acquiredAtMs,
						stepsWriterOwner = row.stepsWriterOwner,
						stepsWriterOwnerGeneration = row.stepsWriterOwnerGeneration,
					)
				},
			)

			val admittedSignalIds = admitted.candidates.map { it.stagedSignal.signalId }.toSet()
			val rejectedSignalIds = candidates.asSequence()
				.map { it.stagedSignal.signalId }
				.filterNot(admittedSignalIds::contains)
				.toSet()
			CheckpointAdmission(
				admittedIds = ids,
				lifecycleRejectedCount = candidates.size - admitted.candidates.size,
				admittedSignalIds = admittedSignalIds,
				rejectedSignalIds = rejectedSignalIds,
			)
		}
	}

	private data class CandidateAdmission(
		val stagedSignal: StagedSignal,
		val capturedEpoch: Long,
		val acquiredAtMs: Long,
		val entity: PendingSignalEntity,
	)

	private data class AdmittedCandidates(
		val rows: List<PendingSignalEntity>,
		val candidates: List<CandidateAdmission>,
	) {
		init {
			require(rows.size == candidates.size) {
				"Pending signal resolution returned ${rows.size} rows for ${candidates.size} candidates"
			}
		}

		val ids: List<Long> get() = rows.map(PendingSignalEntity::id)
	}

	/**
	 * Checks the mirrored lifecycle guard in the same transaction as the WAL insert. The
	 * authoritative DataStore snapshot is read *inside* that transaction: a candidate carries its
	 * earlier captured epoch, while a retention/deletion transition observed here rejects it. If a
	 * deletion starts after the read, its source-delete transaction waits for this admission and
	 * removes the newly inserted row before it completes.
	 */
	private suspend fun admitCandidates(
		candidates: List<CandidateAdmission>,
		now: Long,
	): AdmittedCandidates {
		val database = appDatabase
		if (database == null) {
			return AdmittedCandidates(
				rows = pendingSignalDao.insertOrResolveEntities(
					candidates.map(CandidateAdmission::entity),
				),
				candidates = candidates,
			)
		}
		return database.withTransaction {
			// Do not seed the Room mirror from the producer's captured snapshot. It
			// may have become stale while this coroutine waited for the database
			// transaction, particularly across a full collected-data deletion.
			val authoritativeLifecycle = collectedDataLifecycleStore?.snapshot()
			val guard = reconcileLifecycleGuard(
				stateDao = database.sourceEvidenceStateDao(),
				external = authoritativeLifecycle,
				now = now,
			)
			val admitted = candidates.filter { candidate ->
				guard.accepts(candidate.capturedEpoch, candidate.acquiredAtMs)
			}
			val existing = admitted.map { it.stagedSignal.signalId }
				.chunked(PENDING_IDENTITY_LOOKUP_CHUNK)
				.flatMap { ids -> pendingSignalDao.getBySignalIds(ids) }
				.associateBy(PendingSignalEntity::signalId)
			val entities = admitted.map { candidate ->
				existing[candidate.stagedSignal.signalId]?.let { durable ->
					candidate.entity.copy(
						stepsWriterOwner = durable.stepsWriterOwner,
						stepsWriterOwnerGeneration = durable.stepsWriterOwnerGeneration,
					)
				} ?: candidate.entity.withResolvedStepsWriter(candidate.stagedSignal)
			}
			AdmittedCandidates(
				rows = if (entities.isEmpty()) emptyList() else
					pendingSignalDao.insertOrResolveEntities(entities),
				candidates = admitted,
			)
		}
	}

	private suspend fun PendingSignalEntity.withResolvedStepsWriter(
		staged: StagedSignal,
	): PendingSignalEntity {
		fun withoutStepsWriter() = copy(
			stepsWriterOwner = null,
			stepsWriterOwnerGeneration = null,
		)
		if (staged.signal.steps == null) return withoutStepsWriter()
		val database = requireNotNull(appDatabase)
		val sourceEventId = staged.signalId.takeIf { id ->
			id.startsWith(SOURCE_EVENT_SIGNAL_PREFIX) && id.length > SOURCE_EVENT_SIGNAL_PREFIX.length
		}?.removePrefix(SOURCE_EVENT_SIGNAL_PREFIX) ?: return withoutStepsWriter()
		val event = database.sourceEventWalDao().getByEventId(sourceEventId)
			?: return withoutStepsWriter()
		if (event.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS ||
			!event.hasQualifiedIntegrity() ||
			event.planAttribution != PlanAttribution.CAPTURED_REGISTRATION.ordinal ||
			event.authorizationPurposeEligibilityMask and
				SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
			event.capturedCollectedDataEpoch != capturedEpoch
		) return withoutStepsWriter()
		val logicalTrackingId = event.logicalTrackingId ?: return withoutStepsWriter()
		val serviceRunId = event.serviceRunId ?: return withoutStepsWriter()
		val manifestRevision = event.sessionManifestRevision ?: return withoutStepsWriter()
		val policyRevision = event.sourcePolicyRevision ?: return withoutStepsWriter()
		val consentEpoch = event.captureConsentEpoch ?: return withoutStepsWriter()
		if (event.lifecycleLeaseGeneration == null) return withoutStepsWriter()
		val segment = database.sessionSegmentDao().getById(sessionId) ?: return withoutStepsWriter()
		if (segment.logicalTrackingId != logicalTrackingId || segment.serviceRunId != serviceRunId) {
			return withoutStepsWriter()
		}
		val sessionDao = database.sourceSessionDao()
		val run = sessionDao.serviceRun(serviceRunId) ?: return withoutStepsWriter()
		if (run.logicalTrackingId != logicalTrackingId) return withoutStepsWriter()
		val manifest = sessionDao.manifestByServiceRunRevision(serviceRunId, manifestRevision)
			?: return withoutStepsWriter()
		if (manifest.logicalTrackingId != logicalTrackingId ||
			manifest.sourcePolicyRevision != policyRevision
		) return withoutStepsWriter()
		val sources = sessionDao.manifestSources(logicalTrackingId, manifestRevision)
		if (!SessionManifestIntegrity.verify(manifest, sources)) return withoutStepsWriter()
		val binding = sources.singleOrNull { source ->
			source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
				source.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		} ?: return withoutStepsWriter()
		if (!binding.persistenceEligible || binding.consentEpoch != consentEpoch) {
			return withoutStepsWriter()
		}
		if (binding.outputDestination != SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS) {
			return withoutStepsWriter()
		}
		val writerOwner = binding.writerOwner ?: return withoutStepsWriter()
		val writerOwnerGeneration = binding.writerOwnerGeneration ?: return withoutStepsWriter()
		return copy(
			stepsWriterOwner = writerOwner,
			stepsWriterOwnerGeneration = writerOwnerGeneration,
		)
	}

	private suspend fun reconcileLifecycleGuard(
		stateDao: SourceEvidenceStateDao,
		external: CollectedDataLifecycleSnapshot?,
		now: Long,
	): SourceEvidenceState {
		stateDao.ensure()
		val current = requireNotNull(stateDao.get())
		val desiredEpoch = maxOf(current.collectedDataEpoch, external?.epoch ?: current.collectedDataEpoch)
		val externalRetainedFrom = external?.retainedFromMs
		val desiredRetainedFrom = listOfNotNull(current.retainedFromMs, externalRetainedFrom)
			.maxOrNull()
		if (
			desiredEpoch != current.collectedDataEpoch ||
			desiredRetainedFrom != current.retainedFromMs
		) {
			check(stateDao.updateLifecycle(desiredEpoch, desiredRetainedFrom, now) == 1)
		}
		return requireNotNull(stateDao.get())
	}

	private fun SourceEvidenceState.accepts(capturedEpoch: Long, acquiredAtMs: Long): Boolean {
		val retainedFrom = retainedFromMs
		return capturedEpoch == collectedDataEpoch &&
			(retainedFrom == null || acquiredAtMs >= retainedFrom)
	}

	private fun TrackingSignal.acquiredAtMs(fallback: Long): Long {
		val rawFixTime = locationObservation?.rawFixTimeMs ?: timestampMs.raw
		return rawFixTime.takeIf { it >= 0L }
			?: locationObservation?.receivedAtMs?.takeIf { it >= 0L }
			?: fallback
	}

	/**
	 * Read the oldest batch of WAL entries **across all sessions** without
	 * deleting them.
	 *
	 * Recovery is intentionally session-agnostic: a restarted process mints a
	 * new session id, so filtering by the current session would strand rows
	 * written under the previous one. Each returned [PeekedSignal] includes the
	 * row ID, stable source signal ID, envelope metadata, and a typed payload
	 * result. The caller acknowledges the exact IDs (via
	 * [PendingSignalDao.deleteByIds]) once the destination writes commit, so a
	 * failure before that leaves the rows available for retry. Malformed and
	 * unsupported rows are surfaced for transactional quarantine, never silently
	 * converted to an acknowledgement.
	 */
	suspend fun peekBatch(limit: Int = RECOVERY_BATCH_SIZE): List<PeekedSignal> =
		withContext(dispatchers.io) {
			val entities = pendingSignalDao.getOldestAcrossSessions(limit)
			entities.map { it.toPeekedSignal() }
		}

	/**
	 * Claim an exclusive recovery batch. A second coordinator can only observe
	 * this batch after its lease expires; its destination writes must still use
	 * [PendingSignalClaimDao.deleteClaimedByIds] with [ClaimedBatch.claimToken]
	 * in the same transaction as its destination inserts.
	 */
	suspend fun claimBatch(limit: Int = RECOVERY_BATCH_SIZE): ClaimedBatch? =
		withContext(dispatchers.io) {
			val claimDao = requireNotNull(pendingSignalClaimDao) {
				"PendingSignalClaimDao is required for claim-based recovery"
			}
			val now = Time.nowMillis
			val leaseExpiresAt = now + CLAIM_LEASE_DURATION_MS
			val claimToken = UUID.randomUUID().toString()
			val entities = claimDao.claimOldestAvailable(
				claimToken = claimToken,
				nowMs = now,
				leaseExpiresAtMs = leaseExpiresAt,
				limit = limit,
			)
			if (entities.isEmpty()) {
				null
			} else {
				ClaimedBatch(
					claimToken = claimToken,
					leaseExpiresAtMs = leaseExpiresAt,
					signals = entities.map { it.toPeekedSignal() },
				)
			}
		}

	/** Release a batch after a transient failure so another recovery pass can retry it. */
	suspend fun releaseClaim(claimToken: String): Int = withContext(dispatchers.io) {
		requireNotNull(pendingSignalClaimDao) {
			"PendingSignalClaimDao is required for claim-based recovery"
		}.releaseClaim(claimToken)
	}

	/** True when there are still WAL entries for **any** session (recovery scope). */
	suspend fun hasPendingEntries(): Boolean = withContext(dispatchers.io) {
		pendingSignalDao.countAll() > 0
	}

	/** Delete all WAL entries for the current session. */
	suspend fun clear() = withContext(dispatchers.io) {
		pendingSignalDao.deleteBySession(sessionId)
	}

	/**
	 * An in-memory signal accepted into staging. The [signalId] is generated
	 * once, before the object reaches any typed persistence buffer.
	 */
	data class StagedSignal(
		val signalId: String,
		val signal: TrackingSignal,
	)

	/** A staged signal whose `pending_signal` row has committed. */
	data class CheckpointedSignal(
		val id: Long,
		val signalId: String,
		val signal: TrackingSignal,
		val capturedEpoch: Long = 0L,
		val acquiredAtMs: Long = 0L,
		val stepsWriterOwner: String? = null,
		val stepsWriterOwnerGeneration: Long? = null,
	)

	/** Result of one staging admission attempt. */
	data class CheckpointAdmission(
		val admittedIds: List<Long>,
		val lifecycleRejectedCount: Int,
		/** Stable producer identities admitted by this checkpoint. */
		val admittedSignalIds: Set<String> = emptySet(),
		/** Stable producer identities terminally rejected by the lifecycle guard. */
		val rejectedSignalIds: Set<String> = emptySet(),
	) {
		init {
			require(lifecycleRejectedCount >= 0) { "lifecycleRejectedCount must not be negative" }
		}

		val fullyAdmitted: Boolean get() = lifecycleRejectedCount == 0

		/**
		 * Resolve admission for one signal when a checkpoint contains a mixture of old rejected and
		 * new admitted rows. Older test/fallback implementations expose only the aggregate result,
		 * so retain that behavior when no identity metadata is available.
		 */
		fun statusFor(signalId: String?): DurableAdmissionStatus = when {
			signalId.isNullOrBlank() -> if (fullyAdmitted) {
				DurableAdmissionStatus.ADMITTED
			} else {
				DurableAdmissionStatus.LIFECYCLE_REJECTED
			}
			signalId in admittedSignalIds -> DurableAdmissionStatus.ADMITTED
			signalId in rejectedSignalIds -> DurableAdmissionStatus.LIFECYCLE_REJECTED
			fullyAdmitted -> DurableAdmissionStatus.ADMITTED
			else -> DurableAdmissionStatus.LIFECYCLE_REJECTED
		}

		companion object {
			val EMPTY = CheckpointAdmission(admittedIds = emptyList(), lifecycleRejectedCount = 0)
		}
	}

	/** Rows currently owned by one recovery coordinator. */
	data class ClaimedBatch(
		val claimToken: String,
		val leaseExpiresAtMs: Long,
		val signals: List<PeekedSignal>,
	)

	/** A pending row plus its verified payload result and durable metadata. */
	data class PeekedSignal(
		val id: Long,
		val signalId: String,
		val sessionId: Long,
		val envelopeVersion: Int,
		val payloadChecksum: String?,
		val signalJson: String,
		val createdAt: Long,
		val capturedEpoch: Long,
		val acquiredAtMs: Long,
		val deliveryAttemptCount: Int,
		val stepsWriterOwner: String?,
		val stepsWriterOwnerGeneration: Long?,
		val payload: PendingSignalDecodeResult,
	) {
		/** Compatibility view for callers that only need a successfully decoded signal. */
		val signal: TrackingSignal?
			get() = (payload as? PendingSignalDecodeResult.Valid)?.signal

		/** Convenience constructor for focused tests and hand-built current-format fixtures. */
		constructor(id: Long, signal: TrackingSignal?) : this(
			id,
			signal,
			signal?.let(SignalSerializer::encode),
		)

		private constructor(
			id: Long,
			signal: TrackingSignal?,
			encoded: SignalSerializer.EncodedSignal?,
		) : this(
			id = id,
			signalId = "test-pending-$id",
			sessionId = 0L,
			envelopeVersion = SignalSerializer.CURRENT_ENVELOPE_VERSION,
			payloadChecksum = encoded?.payloadChecksum
				?: SignalSerializer.payloadChecksum(""),
			signalJson = encoded?.payloadJson.orEmpty(),
			createdAt = 0L,
			capturedEpoch = 0L,
			acquiredAtMs = signal?.timestampMs?.raw ?: 0L,
			deliveryAttemptCount = 0,
			stepsWriterOwner = null,
			stepsWriterOwnerGeneration = null,
			payload = signal?.let(PendingSignalDecodeResult::Valid)
				?: PendingSignalDecodeResult.Malformed(
					PendingSignalDecodeFailure.MALFORMED_PAYLOAD,
				),
		)
	}

	private fun PendingSignalEntity.toPeekedSignal(): PeekedSignal {
		val decodedPayload = if (signalId.isBlank()) {
			PendingSignalDecodeResult.Malformed(PendingSignalDecodeFailure.MISSING_SIGNAL_ID)
		} else {
			SignalSerializer.decode(
				envelopeVersion = envelopeVersion,
				payloadJson = signalJson,
				payloadChecksum = payloadChecksum,
			)
		}
		return PeekedSignal(
			id = id,
			signalId = signalId,
			sessionId = sessionId,
			envelopeVersion = envelopeVersion,
			payloadChecksum = payloadChecksum,
			signalJson = signalJson,
			createdAt = createdAt,
			capturedEpoch = capturedEpoch,
			acquiredAtMs = acquiredAtMs,
			deliveryAttemptCount = deliveryAttemptCount,
			stepsWriterOwner = stepsWriterOwner,
			stepsWriterOwnerGeneration = stepsWriterOwnerGeneration,
			payload = decodedPayload,
		)
	}

	companion object {
		internal const val RECOVERY_BATCH_SIZE = 100
		private const val CLAIM_LEASE_DURATION_MS = 60_000L
		private const val PENDING_IDENTITY_LOOKUP_CHUNK = 900
		private const val SOURCE_EVENT_SIGNAL_PREFIX = "source-event:"
	}
}
