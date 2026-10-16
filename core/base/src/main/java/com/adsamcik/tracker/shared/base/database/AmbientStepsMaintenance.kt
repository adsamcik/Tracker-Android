package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.AmbientStepsFactRevisionDao
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Checkpoints used only to prove cancellation and whole-transaction rollback. */
internal enum class AmbientStepsMaintenanceCheckpoint {
	TRANSACTION_STARTED,
	FACT_PAGE_LOADED,
	AUTHORITY_AUTHENTICATED,
	RETRACTIONS_INSTALLED,
	PAYLOAD_REMOVED,
}

/** Explicit finite limits for a personal-device Ambient Steps maintenance pass. */
internal data class AmbientStepsMaintenanceLimits(
	val factPageSize: Int = 256,
	val maximumRevisions: Int = 65_536,
	val maximumLogicalFacts: Int = 16_384,
	val maximumCursors: Int = 256,
	val maximumGaps: Int = 4_096,
	val maximumAuthorityTransitions: Int = 4_096,
	val maximumAuthorizationRevisions: Int = 4_096,
	val maximumAuthorizationMembersPerRevision: Int = 64,
	val maximumActiveDemands: Int = 256,
	val maximumCurrentRegistrations: Int = 32,
	val maximumPendingProviderRemovals: Int = 32,
) {
	init {
		require(factPageSize in 1..1_024)
		listOf(
			maximumRevisions,
			maximumLogicalFacts,
			maximumCursors,
			maximumGaps,
			maximumAuthorityTransitions,
			maximumAuthorizationRevisions,
			maximumAuthorizationMembersPerRevision,
			maximumActiveDemands,
			maximumCurrentRegistrations,
			maximumPendingProviderRemovals,
		).forEach { limit -> require(limit in 1 until Int.MAX_VALUE) }
	}
}

/**
 * Active consent/provider/demand, changed epoch/destination or unrecognized/excessive payload
 * preventing all-day Steps erasure.
 */
enum class AmbientStepsSourceDeletionBlockedReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	DESTINATION_OWNER_CHANGED,
	POLICY_AUTHORITY_UNAVAILABLE,
	AMBIENT_CONSENT_STILL_ELIGIBLE,
	AMBIENT_DEMAND_NOT_QUIESCED,
	AMBIENT_PROVIDER_NOT_QUIESCED,
	MAINTENANCE_BOUND_EXCEEDED,
	UNRECOGNIZED_PAYLOAD_PRESENT,
	DELETION_GENERATION_EXHAUSTED,
}

/**
 * All-day Steps erase result with removed payload/retraction counts or an explicit authority
 * boundary.
 */
sealed interface AmbientStepsSourceDeletionResult {
	/**
	 * The exact selected payload was fenced and removed; reports original removed scope counts.
	 */
	data class Deleted(
		val retractedLogicalFactCount: Int,
		val removedPayloadRevisionCount: Int,
	) : AmbientStepsSourceDeletionResult

	/**
	 * The exact selected source scope was already deleted and remains represented by its original
	 * deletion authority.
	 */
	data object AlreadyDeleted : AmbientStepsSourceDeletionResult

	/**
	 * Changed or invalid scope/authority prevents the requested operation; the exact reason remains
	 * explicit.
	 */
	data class Blocked(
		val reason: AmbientStepsSourceDeletionBlockedReason,
	) : AmbientStepsSourceDeletionResult
}

/**
 * Removes complete authenticated Ambient Steps fact lineages whose immutable window starts before
 * the exact global retention floor.
 *
 * Provider aggregates cannot be split truthfully. A crossing aggregate is therefore removed as a
 * whole, while later same-day facts remain discoverable and compose as partial coverage. The
 * already-durable retained-from floor prevents any importer replay from restoring the removed
 * prefix. Gaps, transitions, structural-day authority, and the current cursor remain untouched.
 */
suspend fun AppDatabase.pruneAuthenticatedAmbientStepsFactsAffectedByRetentionFloor(
	beforeMs: Long,
	collectedDataEpoch: Long,
	markedAtMs: Long,
): Int = pruneAuthenticatedAmbientStepsFactsAffectedByRetentionFloor(
	beforeMs,
	collectedDataEpoch,
	markedAtMs,
	DEFAULT_AMBIENT_STEPS_MAINTENANCE_LIMITS,
	{ currentCoroutineContext().ensureActive() },
)

internal suspend fun AppDatabase.pruneAuthenticatedAmbientStepsFactsAffectedByRetentionFloor(
	beforeMs: Long,
	collectedDataEpoch: Long,
	markedAtMs: Long,
	limits: AmbientStepsMaintenanceLimits,
	checkpoint: suspend (AmbientStepsMaintenanceCheckpoint) -> Unit,
): Int = withTransaction {
	require(beforeMs >= 0L)
	require(collectedDataEpoch >= 0L)
	require(markedAtMs >= 0L)
	checkpoint(AmbientStepsMaintenanceCheckpoint.TRANSACTION_STARTED)
	val evidence = requireNotNull(sourceEvidenceStateDao().get()) {
		"Ambient Steps retention requires initialized source-evidence state"
	}
	check(evidence.collectedDataEpoch == collectedDataEpoch) {
		"Ambient Steps retention epoch does not match current source-evidence state"
	}
	check(evidence.retainedFromMs == beforeMs) {
		"Ambient Steps retention floor does not match current source-evidence authority"
	}
	check(
		ambientStepsFactRevisionDao().countUnrecognizedPayloadRows(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
		) == 0L,
	) { "Ambient Steps retention encountered an unrecognized payload-bearing row" }
	val audit = loadAuthenticatedAmbientStepsState(
		limits,
		checkpoint,
		authenticateRetractedPayloadAuthority = false,
	)
	checkpoint(AmbientStepsMaintenanceCheckpoint.AUTHORITY_AUTHENTICATED)
	check(markedAtMs >= audit.latestDurableTimeMs) {
		"Ambient Steps retention time precedes retained source authority"
	}
	val selected = audit.lineages.filter { lineage ->
		when (lineage.latest.operation) {
			AmbientStepsFactRevisionEntity.OPERATION_UPSERT ->
				requireNotNull(lineage.latest.windowStartTimeMs) < beforeMs
			AmbientStepsFactRevisionEntity.OPERATION_RETRACT -> lineage.upserts.any { fact ->
				requireNotNull(fact.windowStartTimeMs) < beforeMs
			}
			else -> error("Unsupported Ambient Steps fact operation")
		}
	}
	removeAmbientStepsRetentionPayload(selected, checkpoint)
}

private suspend fun AppDatabase.removeAmbientStepsRetentionPayload(
	selected: List<AmbientStepsFactLineage>,
	checkpoint: suspend (AmbientStepsMaintenanceCheckpoint) -> Unit,
): Int {
	val countDomainStore = StepsCountDomainStore(this)
	var deleted = 0
	selected.chunked(DELETE_BATCH_SIZE).forEach { batch ->
		val expected = batch.sumOf(AmbientStepsFactLineage::revisionCount)
		val countDomainKeys = batch.flatMap { lineage ->
			(lineage.upserts + lineage.latest)
				.distinctBy(AmbientStepsFactRevisionEntity::semanticRevision)
				.map { revision ->
					StepsCountDomainOwnerLookupKey(
						ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
						ownerIdentity = StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
							revision.writerId,
							revision.writerVersion,
							revision.logicalFactId,
						),
						ownerRevision = revision.semanticRevision,
					)
				}
		}
		countDomainKeys.chunked(COUNT_DOMAIN_OWNER_BATCH_SIZE).forEach { ownerBatch ->
			countDomainStore.withOwnerMaintenance { maintenance ->
				check(
					maintenance.removeOwners(ownerBatch).let { result ->
						result is StepsCountDomainMaintenanceResult.Applied ||
							result == StepsCountDomainMaintenanceResult.SchemaUnavailable
					},
				) { "Ambient Steps count-domain retention evidence could not be removed" }
			}
		}
		val actual = ambientStepsFactRevisionDao().deleteExactLineages(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			batch.map { it.logicalFactId },
		)
		check(actual == expected) { "Ambient Steps retention lineage changed during pruning" }
		deleted = Math.addExact(deleted, actual)
		checkpoint(AmbientStepsMaintenanceCheckpoint.PAYLOAD_REMOVED)
	}
	return deleted
}

/**
 * Deletes only Ambient Steps product data after policy, consent, demand, and provider cleanup won.
 *
 * One redacted latest-state revision is installed before any provider payload or import authority
 * is removed. The retained tombstones carry no provider, time, zone, count, policy, or consent
 * payload, but prevent an old exact logical-fact replay from becoming effective again. A future
 * opt-in uses a new consent/registration privacy floor and therefore a different logical identity.
 */
suspend fun AppDatabase.deleteAmbientStepsAfterConsentReset(
	expectedCollectedDataEpoch: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
): AmbientStepsSourceDeletionResult = deleteAmbientStepsAfterConsentReset(
	expectedCollectedDataEpoch,
	expectedRevokedConsentEpoch,
	deletedAtMs,
	DEFAULT_AMBIENT_STEPS_MAINTENANCE_LIMITS,
	{ currentCoroutineContext().ensureActive() },
)

internal suspend fun AppDatabase.deleteAmbientStepsAfterConsentReset(
	expectedCollectedDataEpoch: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
	limits: AmbientStepsMaintenanceLimits,
	checkpoint: suspend (AmbientStepsMaintenanceCheckpoint) -> Unit,
): AmbientStepsSourceDeletionResult = withTransaction {
	require(expectedCollectedDataEpoch >= 0L)
	require(expectedRevokedConsentEpoch >= 0L)
	require(deletedAtMs >= 0L)
	checkpoint(AmbientStepsMaintenanceCheckpoint.TRANSACTION_STARTED)
	ambientStepsDeletionRejection(
		expectedCollectedDataEpoch, expectedRevokedConsentEpoch, limits,
	)?.let { return@withTransaction blocked(it) }
	val factDao = ambientStepsFactRevisionDao()
	val (audit, nativeReplayOwners) = try {
		val authenticated = loadAuthenticatedAmbientStepsState(
			limits,
			checkpoint,
			authenticateRetractedPayloadAuthority = false,
		)
		val protectedIdentities = authenticatedNativeReplayFootprints(
			expectedCollectedDataEpoch,
		).mapTo(mutableSetOf()) {
			it.protectedIdentity
		}
		authenticated to authenticated.toPortableLocalOwners(
			incomingIdentities = null,
			protectedIdentities = protectedIdentities,
			retainedFromMs = requireNotNull(sourceEvidenceStateDao().get()).retainedFromMs,
		)
	} catch (@Suppress("SwallowedException") _: AmbientStepsMaintenanceLimitExceeded) {
		return@withTransaction blocked(
			AmbientStepsSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)
	}
	checkpoint(AmbientStepsMaintenanceCheckpoint.AUTHORITY_AUTHENTICATED)
	check(deletedAtMs >= audit.latestDurableTimeMs) {
		"Ambient Steps deletion time precedes retained source authority"
	}
	if (nativeReplayOwners.isNotEmpty()) {
		try {
			installAmbientStepsNativeReplayFootprintsInCurrentTransaction(
				owners = nativeReplayOwners,
				expectedCollectedDataEpoch = expectedCollectedDataEpoch,
				protectedAtMs = deletedAtMs,
			)
		} catch (@Suppress("SwallowedException") _: AmbientStepsMaintenanceLimitExceeded) {
			return@withTransaction blocked(
				AmbientStepsSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
			)
		}
	}
	settleAmbientStepsConsentDeletion(audit, factDao, deletedAtMs, checkpoint)
}

private suspend fun AppDatabase.settleAmbientStepsConsentDeletion(
	audit: AmbientStepsMaintenanceAudit,
	factDao: AmbientStepsFactRevisionDao,
	deletedAtMs: Long,
	checkpoint: suspend (AmbientStepsMaintenanceCheckpoint) -> Unit,
): AmbientStepsSourceDeletionResult {
	val latestUpserts = audit.lineages.filter { lineage ->
		lineage.latest.operation == AmbientStepsFactRevisionEntity.OPERATION_UPSERT
	}
	val payloadLineages = audit.lineages.filter { lineage -> lineage.upsertRevisionCount > 0 }
	val priorGeneration = audit.lineages.maxOfOrNull { lineage ->
		lineage.latest.scopeDeletionGeneration
	} ?: 0L
	if (latestUpserts.isNotEmpty() && priorGeneration == Long.MAX_VALUE) {
		return blocked(
			AmbientStepsSourceDeletionBlockedReason.DELETION_GENERATION_EXHAUSTED,
		)
	}
	val nextGeneration = if (latestUpserts.isEmpty()) {
		priorGeneration
	} else {
		Math.addExact(priorGeneration, 1L).coerceAtLeast(1L)
	}
	installAmbientStepsConsentRetractions(factDao, latestUpserts, nextGeneration, deletedAtMs)
	checkpoint(AmbientStepsMaintenanceCheckpoint.RETRACTIONS_INSTALLED)
	val removedPayloads = removeAmbientStepsConsentPayload(factDao, payloadLineages)
	if (payloadLineages.isEmpty() && audit.cursors.isEmpty() && audit.gaps.isEmpty() &&
		audit.transitions.isEmpty()
	) {
		return AmbientStepsSourceDeletionResult.AlreadyDeleted
	}
	check(sourceEvidenceStateDao().incrementRevision(deletedAtMs) == 1) {
		"Unable to publish Ambient Steps source deletion"
	}
	// Re-evaluate existing goal periods under their stored calendar authority. The queue and
	// deletion commit together; independently retained session/imported evidence may still qualify.
	enqueueAllStepsGoalRepairs()
	checkpoint(AmbientStepsMaintenanceCheckpoint.PAYLOAD_REMOVED)
	return AmbientStepsSourceDeletionResult.Deleted(latestUpserts.size, removedPayloads)
}

private suspend fun installAmbientStepsConsentRetractions(
	factDao: AmbientStepsFactRevisionDao,
	latestUpserts: List<AmbientStepsFactLineage>,
	nextGeneration: Long,
	deletedAtMs: Long,
) {
	latestUpserts.forEach { lineage ->
		val retraction = lineage.latest.toAmbientStepsRetraction(nextGeneration, deletedAtMs)
		if (factDao.insert(retraction) == INSERT_IGNORED &&
			factDao.latest(
				AmbientStepsFactRevisionEntity.WRITER_ID,
				AmbientStepsFactRevisionEntity.WRITER_VERSION,
				retraction.logicalFactId,
			) != retraction
		) {
			error("Ambient Steps deletion retraction lost its exact mutation identity")
		}
	}
}

private suspend fun AppDatabase.ambientStepsDeletionRejection(
	expectedCollectedDataEpoch: Long,
	expectedRevokedConsentEpoch: Long,
	limits: AmbientStepsMaintenanceLimits,
): AmbientStepsSourceDeletionBlockedReason? {
	val evidence = sourceEvidenceStateDao().get()
	if (evidence?.collectedDataEpoch != expectedCollectedDataEpoch) {
		return AmbientStepsSourceDeletionBlockedReason.COLLECTED_DATA_EPOCH_CHANGED
	}
	val owner = sourceDestinationOwnerDao().get(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
	)
	if (owner?.owner != SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS) {
		return AmbientStepsSourceDeletionBlockedReason.DESTINATION_OWNER_CHANGED
	}
	val rejection = ambientStepsRevokedConsentRejection(expectedRevokedConsentEpoch)
		?: ambientStepsQuiescenceRejection(limits)
	if (rejection != null) {
		return rejection
	}
	return if (ambientStepsFactRevisionDao().countUnrecognizedPayloadRows(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
		) > 0L
	) {
		AmbientStepsSourceDeletionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT
	} else {
		null
	}
}

private suspend fun AppDatabase.removeAmbientStepsConsentPayload(
	factDao: AmbientStepsFactRevisionDao,
	payloadLineages: List<AmbientStepsFactLineage>,
): Int {
	val countDomainStore = StepsCountDomainStore(this)
	var removedPayloads = 0
	payloadLineages.chunked(DELETE_BATCH_SIZE).forEach { batch ->
		val expected = batch.sumOf { lineage -> lineage.upsertRevisionCount }
		val countDomainKeys = batch.flatMap { lineage ->
			lineage.upserts.map { revision ->
				StepsCountDomainOwnerLookupKey(
					ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
					ownerIdentity = StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
						revision.writerId,
						revision.writerVersion,
						revision.logicalFactId,
					),
					ownerRevision = revision.semanticRevision,
				)
			}
		}
		countDomainKeys.chunked(COUNT_DOMAIN_OWNER_BATCH_SIZE).forEach { ownerBatch ->
			countDomainStore.withOwnerMaintenance { maintenance ->
				check(
					maintenance.removeOwners(ownerBatch).let { result ->
						result is StepsCountDomainMaintenanceResult.Applied ||
							result == StepsCountDomainMaintenanceResult.SchemaUnavailable
					},
				) { "Ambient Steps deletion count-domain evidence could not be removed" }
			}
		}
		val actual = factDao.deleteUpsertsForLogicalFacts(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			batch.map(AmbientStepsFactLineage::logicalFactId),
		)
		check(actual == expected) { "Ambient Steps payload changed after deletion fencing" }
		removedPayloads = Math.addExact(removedPayloads, actual)
	}
	val stateDao = ambientStepsImportStateDao()
	stateDao.deleteAllAuthorityTransitions()
	stateDao.deleteAllGaps()
	stateDao.deleteAllCursors()
	check(stateDao.countAuthorityTransitions() == 0L && stateDao.countGaps() == 0L &&
		stateDao.countCursors() == 0L && factDao.countPayloadBearingRows() == 0L
	) { "Ambient Steps source deletion did not remove all fenced source state" }
	return removedPayloads
}

private suspend fun AppDatabase.ambientStepsRevokedConsentRejection(
	expectedRevokedConsentEpoch: Long,
): AmbientStepsSourceDeletionBlockedReason? {
	val policyAuthority = sourcePolicyDao().authority()
	val policy = policyAuthority?.takeIf {
		it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
	}?.let {
		sourcePolicyDao().policyAtRevision(
			it.currentPolicyRevision,
			SourceDestinationOwnerEntity.SOURCE_STEPS,
		)
	}
	val consent = sourcePolicyDao().latestConsentEpoch(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		SourceBrokerPurpose.AMBIENT_PRODUCT,
	)
	if (policy == null || consent == null || consent.epoch != expectedRevokedConsentEpoch) {
		return AmbientStepsSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE
	}
	if (policy.ambientPersistenceEligible || policy.ambientConsentEpoch != null ||
		consent.eligible || consent.persistenceEligible
	) {
		return AmbientStepsSourceDeletionBlockedReason.AMBIENT_CONSENT_STILL_ELIGIBLE
	}
	return null
}

private suspend fun AppDatabase.ambientStepsQuiescenceRejection(
	limits: AmbientStepsMaintenanceLimits,
): AmbientStepsSourceDeletionBlockedReason? {
	val activeDemands = sourceBrokerDao().activeDemandsBounded(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		limits.maximumActiveDemands + 1,
	)
	if (activeDemands.size > limits.maximumActiveDemands) {
		return AmbientStepsSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED
	}
	if (activeDemands.any { demand ->
			demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT
		}
	) {
		return AmbientStepsSourceDeletionBlockedReason.AMBIENT_DEMAND_NOT_QUIESCED
	}
	val currentRegistrations = sourceBrokerDao().currentPhysicalRegistrationsBounded(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		limits.maximumCurrentRegistrations + 1,
	)
	val pendingRemovals = sourceBrokerDao().pendingProviderRemovalsBounded(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		limits.maximumPendingProviderRemovals + 1,
	)
	if (currentRegistrations.size > limits.maximumCurrentRegistrations ||
		pendingRemovals.size > limits.maximumPendingProviderRemovals
	) {
		return AmbientStepsSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED
	}
	val nonterminalRegistrations = (currentRegistrations + pendingRemovals)
		.distinctBy(ProviderRegistrationGenerationEntity::registrationGeneration)
	return if (nonterminalRegistrations.any { registration ->
			!SourceProviderPurposeScope.isCanonicalOwnerScope(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				registration.ownerScope,
			) || SourceProviderPurposeScope.supportsPurpose(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				registration.ownerScope,
				SourceBrokerPurpose.AMBIENT_PRODUCT,
			)
		}
	) {
		AmbientStepsSourceDeletionBlockedReason.AMBIENT_PROVIDER_NOT_QUIESCED
	} else {
		null
	}
}

private fun AmbientStepsFactRevisionEntity.toAmbientStepsRetraction(
	deletionGeneration: Long,
	deletedAtMs: Long,
): AmbientStepsFactRevisionEntity {
	val nextRevision = Math.addExact(semanticRevision, 1L)
	val unsigned = copy(
		semanticRevision = nextRevision,
		mutationId = AmbientStepsFactIntegrity.mutationId(
			logicalFactId,
			nextRevision,
			AmbientStepsFactRevisionEntity.OPERATION_RETRACT,
		),
		operation = AmbientStepsFactRevisionEntity.OPERATION_RETRACT,
		originKind = AmbientStepsFactRevisionEntity.ORIGIN_LOCAL_DELETE,
		provider = null,
		registrationGeneration = null,
		continuitySegmentGeneration = null,
		sourceInstanceId = null,
		authorizationRevision = null,
		authorizationFingerprint = null,
		windowStartTimeMs = null,
		windowEndTimeMs = null,
		observedAtMs = null,
		structuralEpochDay = null,
		storedZoneId = null,
		structuralDayStartTimeMs = null,
		structuralDayEndTimeMs = null,
		stepCount = null,
		sourcePolicyRevision = null,
		ambientConsentEpoch = null,
		scopeDeletionGeneration = deletionGeneration,
		effectChecksum = EMPTY_EFFECT_CHECKSUM,
		appliedAtMs = deletedAtMs,
		retentionScope = null,
		retentionPolicyId = null,
		retentionApprovalRevision = null,
	)
	return unsigned.copy(effectChecksum = AmbientStepsFactIntegrity.effectChecksum(unsigned))
}

internal data class AmbientStepsFactLineage(
	val logicalFactId: String,
	val revisionCount: Int,
	val upserts: List<AmbientStepsFactRevisionEntity>,
	val latest: AmbientStepsFactRevisionEntity,
) {
	val upsertRevisionCount: Int get() = upserts.size
}

internal data class AmbientStepsMaintenanceAudit(
	val lineages: List<AmbientStepsFactLineage>,
	val cursors: List<AmbientStepsImportCursorEntity>,
	val gaps: List<AmbientStepsImportGapEntity>,
	val transitions: List<AmbientStepsImportAuthorityTransitionEntity>,
) {
	val latestDurableTimeMs: Long = maxOf(
		lineages.maxOfOrNull { it.latest.appliedAtMs } ?: 0L,
		cursors.maxOfOrNull(AmbientStepsImportCursorEntity::updatedAtMs) ?: 0L,
		gaps.maxOfOrNull(AmbientStepsImportGapEntity::recordedAtMs) ?: 0L,
		transitions.maxOfOrNull(AmbientStepsImportAuthorityTransitionEntity::recordedAtMs) ?: 0L,
	)
}

private fun blocked(reason: AmbientStepsSourceDeletionBlockedReason) =
	AmbientStepsSourceDeletionResult.Blocked(reason)

internal class AmbientStepsMaintenanceLimitExceeded(message: String) : IllegalStateException(message)

internal fun requireAmbientStepsMaintenanceBound(condition: Boolean, message: String) {
	if (!condition) {
		throw AmbientStepsMaintenanceLimitExceeded(message)
	}
}

private val DEFAULT_AMBIENT_STEPS_MAINTENANCE_LIMITS = AmbientStepsMaintenanceLimits()
private const val DELETE_BATCH_SIZE = 128
private const val COUNT_DOMAIN_OWNER_BATCH_SIZE = 400
private const val INSERT_IGNORED = -1L
private const val EMPTY_EFFECT_CHECKSUM = "0000000000000000000000000000000000000000000000000000000000000000"
