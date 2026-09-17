package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore
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

sealed interface AmbientStepsSourceDeletionResult {
	data class Deleted(
		val retractedLogicalFactCount: Int,
		val removedPayloadRevisionCount: Int,
	) : AmbientStepsSourceDeletionResult

	data object AlreadyDeleted : AmbientStepsSourceDeletionResult

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
			check(
				StepsCountDomainStore(this).removeOwners(ownerBatch) !is
					StepsCountDomainMaintenanceResult.Overflow,
			) { "Ambient Steps count-domain retention owner batch exceeded its bound" }
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
	deleted
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
	val evidence = sourceEvidenceStateDao().get()
	if (evidence?.collectedDataEpoch != expectedCollectedDataEpoch) {
		return@withTransaction blocked(
			AmbientStepsSourceDeletionBlockedReason.COLLECTED_DATA_EPOCH_CHANGED,
		)
	}
	val owner = sourceDestinationOwnerDao().get(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
	)
	if (owner?.owner != SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS) {
		return@withTransaction blocked(
			AmbientStepsSourceDeletionBlockedReason.DESTINATION_OWNER_CHANGED,
		)
	}
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
		return@withTransaction blocked(
			AmbientStepsSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE,
		)
	}
	if (policy.ambientPersistenceEligible || policy.ambientConsentEpoch != null ||
		consent.eligible || consent.persistenceEligible
	) {
		return@withTransaction blocked(
			AmbientStepsSourceDeletionBlockedReason.AMBIENT_CONSENT_STILL_ELIGIBLE,
		)
	}
	val activeDemands = sourceBrokerDao().activeDemandsBounded(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		limits.maximumActiveDemands + 1,
	)
	if (activeDemands.size > limits.maximumActiveDemands) {
		return@withTransaction blocked(
			AmbientStepsSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)
	}
	if (activeDemands.any { demand ->
			demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT
		}
	) {
		return@withTransaction blocked(
			AmbientStepsSourceDeletionBlockedReason.AMBIENT_DEMAND_NOT_QUIESCED,
		)
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
		return@withTransaction blocked(
			AmbientStepsSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)
	}
	val nonterminalRegistrations = (currentRegistrations + pendingRemovals)
		.distinctBy(ProviderRegistrationGenerationEntity::registrationGeneration)
	if (nonterminalRegistrations.any { registration ->
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
		return@withTransaction blocked(
			AmbientStepsSourceDeletionBlockedReason.AMBIENT_PROVIDER_NOT_QUIESCED,
		)
	}

	val factDao = ambientStepsFactRevisionDao()
	if (factDao.countUnrecognizedPayloadRows(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
		) > 0L
	) {
		return@withTransaction blocked(
			AmbientStepsSourceDeletionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT,
		)
	}
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
	val latestUpserts = audit.lineages.filter { lineage ->
		lineage.latest.operation == AmbientStepsFactRevisionEntity.OPERATION_UPSERT
	}
	val payloadLineages = audit.lineages.filter { lineage -> lineage.upsertRevisionCount > 0 }
	val priorGeneration = audit.lineages.maxOfOrNull { lineage ->
		lineage.latest.scopeDeletionGeneration
	} ?: 0L
	if (latestUpserts.isNotEmpty() && priorGeneration == Long.MAX_VALUE) {
		return@withTransaction blocked(
			AmbientStepsSourceDeletionBlockedReason.DELETION_GENERATION_EXHAUSTED,
		)
	}
	val nextGeneration = if (latestUpserts.isEmpty()) priorGeneration else {
		Math.addExact(priorGeneration, 1L).coerceAtLeast(1L)
	}
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
	checkpoint(AmbientStepsMaintenanceCheckpoint.RETRACTIONS_INSTALLED)
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
			check(
				StepsCountDomainStore(this).removeOwners(ownerBatch) !is
					StepsCountDomainMaintenanceResult.Overflow,
			) { "Ambient Steps deletion count-domain owner batch exceeded its bound" }
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
	if (payloadLineages.isEmpty() && audit.cursors.isEmpty() && audit.gaps.isEmpty() &&
		audit.transitions.isEmpty()
	) {
		return@withTransaction AmbientStepsSourceDeletionResult.AlreadyDeleted
	}
	check(sourceEvidenceStateDao().incrementRevision(deletedAtMs) == 1) {
		"Unable to publish Ambient Steps source deletion"
	}
	checkpoint(AmbientStepsMaintenanceCheckpoint.PAYLOAD_REMOVED)
	AmbientStepsSourceDeletionResult.Deleted(latestUpserts.size, removedPayloads)
}

internal suspend fun AppDatabase.loadAuthenticatedAmbientStepsState(
	limits: AmbientStepsMaintenanceLimits,
	checkpoint: suspend (AmbientStepsMaintenanceCheckpoint) -> Unit,
	authenticateRetractedPayloadAuthority: Boolean = true,
): AmbientStepsMaintenanceAudit {
	val lineages = mutableListOf<AmbientStepsFactLineage>()
	val authority = visitAuthenticatedAmbientStepsState(
		limits,
		checkpoint,
		authenticateRetractedPayloadAuthority,
	) { lineage ->
		lineages += lineage
	}
	return AmbientStepsMaintenanceAudit(
		lineages,
		authority.cursors,
		authority.gaps,
		authority.transitions,
	)
}

internal data class AmbientStepsAuthenticatedAuthorityState(
	val evidence: SourceEvidenceState,
	val cursors: List<AmbientStepsImportCursorEntity>,
	val gaps: List<AmbientStepsImportGapEntity>,
	val transitions: List<AmbientStepsImportAuthorityTransitionEntity>,
)

/**
 * Authenticates native Ambient Steps authority once, then supplies one complete correction lineage
 * at a time. The visitor must not retain provider payload beyond its bounded use.
 */
internal suspend fun AppDatabase.visitAuthenticatedAmbientStepsState(
	limits: AmbientStepsMaintenanceLimits,
	checkpoint: suspend (AmbientStepsMaintenanceCheckpoint) -> Unit,
	authenticateRetractedPayloadAuthority: Boolean = true,
	visitLineage: suspend (AmbientStepsFactLineage) -> Unit,
): AmbientStepsAuthenticatedAuthorityState {
	check(
		ambientStepsFactRevisionDao().countUnrecognizedPayloadRows(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
		) == 0L,
	) { "Ambient Steps authority contains an unrecognized payload-bearing row" }
	val owner = requireNotNull(sourceDestinationOwnerDao().get(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
	)) { "Ambient Steps maintenance requires destination-owner authority" }
	check(owner.owner == SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS) {
		"Ambient Steps maintenance destination owner changed"
	}
	val evidence = requireNotNull(sourceEvidenceStateDao().get()) {
		"Ambient Steps maintenance requires source-evidence state"
	}
	val stateDao = ambientStepsImportStateDao()
	val cursors = stateDao.maintenanceCursors(limits.maximumCursors + 1)
	requireMaintenanceBound(
		cursors.size <= limits.maximumCursors,
		"Ambient Steps cursor maintenance bound exceeded",
	)
	val gaps = stateDao.maintenanceGaps(limits.maximumGaps + 1)
	requireMaintenanceBound(
		gaps.size <= limits.maximumGaps,
		"Ambient Steps gap maintenance bound exceeded",
	)
	val transitions = stateDao.maintenanceAuthorityTransitions(
		limits.maximumAuthorityTransitions + 1,
	)
	requireMaintenanceBound(
		transitions.size <= limits.maximumAuthorityTransitions,
		"Ambient Steps authority-transition maintenance bound exceeded",
	)
	check(cursors.all { it.collectedDataEpoch == evidence.collectedDataEpoch } &&
		gaps.all { it.collectedDataEpoch == evidence.collectedDataEpoch } &&
		transitions.all { it.collectedDataEpoch == evidence.collectedDataEpoch }
	) { "Ambient Steps import state belongs to another collected-data epoch" }
	val cursorsByGeneration = cursors.associateBy(AmbientStepsImportCursorEntity::registrationGeneration)
	check(cursorsByGeneration.size == cursors.size) { "Ambient Steps cursor generations collide" }
	val gapsByRegistration = gaps.groupBy(AmbientStepsImportGapEntity::registrationGeneration)
	val transitionsByRegistration = transitions.groupBy(
		AmbientStepsImportAuthorityTransitionEntity::registrationGeneration,
	)
	cursors.forEach { cursor ->
		val registration = requireNotNull(sourceBrokerDao().registration(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			cursor.registrationGeneration,
		)) { "Ambient Steps cursor is missing its provider registration" }
		check(registration.matches(cursor)) { "Ambient Steps cursor registration authority is invalid" }
		val cursorGaps = gapsByRegistration[cursor.registrationGeneration].orEmpty()
		check(cursorGaps.size.toLong() == cursor.lastGapSequence &&
			cursorGaps.withIndex().all { (index, gap) ->
				gap.gapSequence == index + 1L && gap.provider == cursor.provider &&
					gap.sourceInstanceId == cursor.sourceInstanceId &&
					gap.recordedAtMs <= cursor.updatedAtMs &&
					gap.gapEndTimeMs <= cursor.importedThroughTimeMs
			}
		) { "Ambient Steps gap sequence is incomplete or crosses cursor authority" }
		val cursorTransitions = transitionsByRegistration[cursor.registrationGeneration].orEmpty()
		check(cursorTransitions.size.toLong() == cursor.authorityTransitionSequence &&
			cursorTransitions.withIndex().all { (index, transition) ->
				transition.transitionSequence == index + 1L &&
					transition.provider == cursor.provider &&
					transition.sourceInstanceId == cursor.sourceInstanceId &&
					transition.registrationAcceptedAtMs == cursor.registrationAcceptedAtMs &&
					transition.recordedAtMs <= cursor.updatedAtMs
			}
		) { "Ambient Steps authorization transition sequence is incomplete" }
		check(cursorTransitions.hasExactAuthorityChain(cursor)) {
			"Ambient Steps authorization transition chain does not reach the exact cursor authority"
		}
	}
	check(gapsByRegistration.keys.all(cursorsByGeneration::containsKey) &&
		transitionsByRegistration.keys.all(cursorsByGeneration::containsKey)
	) { "Ambient Steps import state has a dangling cursor reference" }
	val authenticatedAuthorities = mutableMapOf<AmbientStepsFactAuthorityKey, Boolean>()
	visitAmbientStepsFactLineages(limits, checkpoint) { lineage ->
		check(lineage.latest.collectedDataEpoch == evidence.collectedDataEpoch &&
			lineage.latest.writerOwnerGeneration == owner.ownerGeneration
		) { "Ambient Steps facts do not match current epoch/owner authority" }
		if (authenticateRetractedPayloadAuthority ||
			lineage.latest.operation != AmbientStepsFactRevisionEntity.OPERATION_RETRACT
		) {
			lineage.upserts.forEach { fact ->
				val cursor = fact.registrationGeneration?.let(cursorsByGeneration::get)
				check(cursor != null && cursor.provider == fact.provider &&
				cursor.sourceInstanceId == fact.sourceInstanceId &&
				cursor.importedThroughTimeMs >= requireNotNull(fact.windowEndTimeMs) &&
				requireNotNull(fact.continuitySegmentGeneration) <=
					cursor.continuitySegmentGeneration
				) { "Ambient Steps fact is outside its exact cursor authority" }
				check(fact.hasHistoricalAuthority(cursor, transitionsByRegistration[
				cursor.registrationGeneration
				].orEmpty())) { "Ambient Steps fact has no exact authority phase" }
				val authorityKey = fact.authorityKey()
				val authenticated = authenticatedAuthorities[authorityKey] ?: run {
				requireMaintenanceBound(
					authenticatedAuthorities.size < limits.maximumAuthorizationRevisions,
					"Ambient Steps authorization-revision maintenance bound exceeded",
				)
				authenticateAmbientFactAuthority(fact, cursor, limits).also { result ->
					authenticatedAuthorities[authorityKey] = result
				}
				}
				check(authenticated) {
				"Ambient Steps fact policy/consent/authorization authority is invalid"
				}
			}
		}
		visitLineage(lineage)
	}
	return AmbientStepsAuthenticatedAuthorityState(evidence, cursors, gaps, transitions)
}

private suspend fun AppDatabase.visitAmbientStepsFactLineages(
	limits: AmbientStepsMaintenanceLimits,
	checkpoint: suspend (AmbientStepsMaintenanceCheckpoint) -> Unit,
	visitLineage: suspend (AmbientStepsFactLineage) -> Unit,
) {
	var afterLogicalFactId: String? = null
	var afterSemanticRevision: Long? = null
	var revisionCount = 0
	var lineageCount = 0
	var current = mutableListOf<AmbientStepsFactRevisionEntity>()
	suspend fun emitCurrent() {
		if (current.isEmpty()) return
		lineageCount = Math.addExact(lineageCount, 1)
		requireMaintenanceBound(
			lineageCount <= limits.maximumLogicalFacts,
			"Ambient Steps logical-fact maintenance bound exceeded",
		)
		visitLineage(current.toAuthenticatedLineage())
		current = mutableListOf()
	}
	while (true) {
		val page = ambientStepsFactRevisionDao().maintenanceRevisionPage(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			afterLogicalFactId,
			afterSemanticRevision,
			limits.factPageSize,
		)
		if (page.isEmpty()) break
		revisionCount = Math.addExact(revisionCount, page.size)
		requireMaintenanceBound(
			revisionCount <= limits.maximumRevisions,
			"Ambient Steps fact revision maintenance bound exceeded",
		)
		checkpoint(AmbientStepsMaintenanceCheckpoint.FACT_PAGE_LOADED)
		page.forEach { fact ->
			check(AmbientStepsFactIntegrity.hasValidEffectChecksum(fact)) {
				"Ambient Steps maintenance encountered unauthenticated fact state"
			}
			if (current.isNotEmpty() && current.last().logicalFactId != fact.logicalFactId) {
				emitCurrent()
			}
			current += fact
		}
		val last = page.last()
		check(last.logicalFactId != afterLogicalFactId ||
			last.semanticRevision != afterSemanticRevision
		) { "Ambient Steps fact maintenance page did not advance" }
		afterLogicalFactId = last.logicalFactId
		afterSemanticRevision = last.semanticRevision
		if (page.size < limits.factPageSize) break
	}
	emitCurrent()
}

private fun List<AmbientStepsFactRevisionEntity>.toAuthenticatedLineage(): AmbientStepsFactLineage {
	check(isNotEmpty())
	val first = first()
	val expectedFirstRevision = if (
		first.operation == AmbientStepsFactRevisionEntity.OPERATION_RETRACT && size == 1
	) first.semanticRevision else 1L
	check(withIndex().all { (index, fact) ->
		fact.logicalFactId == first.logicalFactId &&
			fact.semanticRevision == expectedFirstRevision + index &&
			fact.writerId == first.writerId && fact.writerVersion == first.writerVersion &&
			fact.writerOwnerGeneration == first.writerOwnerGeneration &&
			fact.collectedDataEpoch == first.collectedDataEpoch && fact.purpose == first.purpose
	}) { "Ambient Steps correction lineage is not contiguous" }
	val upserts = filter { it.operation == AmbientStepsFactRevisionEntity.OPERATION_UPSERT }
	val retractions = filter { it.operation == AmbientStepsFactRevisionEntity.OPERATION_RETRACT }
	check(retractions.size <= 1 && (retractions.isEmpty() || last() == retractions.single())) {
		"Ambient Steps deletion retraction is not terminal"
	}
	upserts.firstOrNull()?.let { origin ->
		check(upserts.all { fact -> fact.sameStableOrigin(origin) }) {
			"Ambient Steps corrections cross immutable source/day authority"
		}
	}
	return AmbientStepsFactLineage(first.logicalFactId, size, upserts, last())
}

private suspend fun AppDatabase.authenticateAmbientFactAuthority(
	fact: AmbientStepsFactRevisionEntity,
	cursor: AmbientStepsImportCursorEntity,
	limits: AmbientStepsMaintenanceLimits,
): Boolean {
	val authorizationRevision = fact.authorizationRevision ?: return false
	val rows = sourceBrokerDao().authorizationRevisionBounded(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		cursor.registrationGeneration,
		authorizationRevision,
		limits.maximumAuthorizationMembersPerRevision + 1,
	)
	requireMaintenanceBound(
		rows.size <= limits.maximumAuthorizationMembersPerRevision,
		"Ambient Steps authorization-member maintenance bound exceeded",
	)
	val first = rows.firstOrNull() ?: return false
	if (first.authorizationRevision != authorizationRevision ||
		first.purposeEligibilityMask != SourceBrokerPurpose.MASK_AMBIENT_PRODUCT ||
		rows.any { row ->
		row.sourceKind != first.sourceKind ||
			row.registrationGeneration != first.registrationGeneration ||
			row.authorizationRevision != first.authorizationRevision ||
		row.authorizationFingerprint != fact.authorizationFingerprint || row.isDenyAll ||
			row.purposeEligibilityMask != first.purposeEligibilityMask ||
			row.effectiveBootId != first.effectiveBootId ||
			row.effectiveElapsedRealtimeNanos != first.effectiveElapsedRealtimeNanos ||
			row.effectiveWallTimeMs != first.effectiveWallTimeMs ||
			row.purpose != SourceBrokerPurpose.AMBIENT_PRODUCT || !row.persistenceEligible ||
			row.sourcePolicyRevision != fact.sourcePolicyRevision ||
			row.consentEpoch != fact.ambientConsentEpoch || row.logicalTrackingId != null ||
			row.serviceRunId != null || row.manifestRevision != null ||
			row.lifecycleLeaseGeneration != null || row.effectiveBootId != cursor.registrationClockDomainId ||
			row.effectiveWallTimeMs > requireNotNull(fact.windowStartTimeMs) ||
			row.effectiveWallTimeMs != first.effectiveWallTimeMs
	}) return false
	val demandIds = rows.mapNotNull(SourceAuthorizationEntity::demandId)
	if (demandIds.size != rows.size || demandIds.distinct().size != demandIds.size) return false
	val demands = demandIds.chunked(AUTHORITY_QUERY_BATCH_SIZE).flatMap { ids ->
		sourceBrokerDao().demandsByIds(ids)
	}
	if (demands.size != demandIds.size || demands.map(SourceDemandEntity::demandId).toSet() !=
		demandIds.toSet() || SourceBrokerAuthorization.fingerprint(demands) != fact.authorizationFingerprint
	) return false
	if (demands.any { demand ->
		demand.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS ||
			demand.purpose != SourceBrokerPurpose.AMBIENT_PRODUCT || !demand.persistenceEligible ||
			demand.sourcePolicyRevision != fact.sourcePolicyRevision ||
			demand.consentEpoch != fact.ambientConsentEpoch || demand.logicalTrackingId != null ||
			demand.serviceRunId != null || demand.manifestRevision != null ||
			demand.lifecycleLeaseGeneration != null || demand.requestedBootId != cursor.registrationClockDomainId ||
			demand.requestedElapsedRealtimeNanos > first.effectiveElapsedRealtimeNanos ||
			demand.requestedAtMs > first.effectiveWallTimeMs
	}) return false
	val policyRevision = fact.sourcePolicyRevision ?: return false
	val consentEpoch = fact.ambientConsentEpoch ?: return false
	val policy = sourcePolicyDao().policyAtRevision(
		policyRevision,
		SourceDestinationOwnerEntity.SOURCE_STEPS,
	) ?: return false
	val consent = sourcePolicyDao().consentEpoch(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		SourceBrokerPurpose.AMBIENT_PRODUCT,
		consentEpoch,
	) ?: return false
	val retentionScope = fact.retentionScope ?: return false
	val retentionPolicyId = fact.retentionPolicyId ?: return false
	val retentionApprovalRevision = fact.retentionApprovalRevision ?: return false
	val retention = ambientStepsFactRevisionDao().retentionAuthorityAt(
		retentionScope,
		first.effectiveBootId,
		first.effectiveElapsedRealtimeNanos,
	) ?: return false
	if (!AmbientStepsRetentionAuthorityIntegrity.isAuthentic(retention) ||
		!retention.isActive ||
		retention.opaquePolicyId != retentionPolicyId ||
		retention.approvalRevision != retentionApprovalRevision ||
		retention.sourcePolicyRevision != policyRevision ||
		retention.ambientConsentEpoch != consentEpoch ||
		retention.collectedDataEpoch != fact.collectedDataEpoch ||
		retention.effectiveBootId != first.effectiveBootId ||
		retention.effectiveElapsedRealtimeNanos > first.effectiveElapsedRealtimeNanos ||
		retention.effectiveWallTimeMs > first.effectiveWallTimeMs
	) return false
	if (demands.any { demand ->
		!policy.isEffectiveAtOrBefore(
			demand.requestedBootId,
			demand.requestedElapsedRealtimeNanos,
			demand.requestedAtMs,
		) ||
			!consent.isEffectiveAtOrBefore(
				demand.requestedBootId,
				demand.requestedElapsedRealtimeNanos,
				demand.requestedAtMs,
			) ||
			!retention.isEffectiveAtOrBefore(
				demand.requestedBootId,
				demand.requestedElapsedRealtimeNanos,
				demand.requestedAtMs,
			)
	}) return false
	return policy.hasAmbientAuthority(
		consent,
		requireNotNull(fact.windowStartTimeMs),
		first,
	)
}

private fun AmbientStepsFactRevisionEntity.hasHistoricalAuthority(
	cursor: AmbientStepsImportCursorEntity,
	transitions: List<AmbientStepsImportAuthorityTransitionEntity>,
): Boolean {
	val generation = continuitySegmentGeneration ?: return false
	val start = windowStartTimeMs ?: return false
	val end = windowEndTimeMs ?: return false
	if (transitions.isEmpty()) {
		return authorizationRevision == cursor.authorizationRevision &&
			authorizationFingerprint == cursor.authorizationFingerprint &&
			sourcePolicyRevision == cursor.sourcePolicyRevision &&
			ambientConsentEpoch == cursor.ambientConsentEpoch &&
			retentionScope == cursor.retentionScope &&
			retentionPolicyId == cursor.retentionPolicyId &&
			retentionApprovalRevision == cursor.retentionApprovalRevision &&
			start >= cursor.eligibleFromTimeMs &&
			end <= cursor.importedThroughTimeMs
	}
	var phaseStart = cursor.registrationAcceptedAtMs
	var firstSegmentGeneration = 1L
	// The exact initial authorization wall is authenticated separately. Here the phase sequence and
	// immutable tuples prevent a correction from crossing a stored transition boundary.
	transitions.forEach { transition ->
		if (generation in firstSegmentGeneration..transition.fromContinuitySegmentGeneration &&
			start >= phaseStart && end <= transition.effectiveBoundaryTimeMs &&
			authorizationRevision == transition.fromAuthorizationRevision &&
			authorizationFingerprint == transition.fromAuthorizationFingerprint &&
			sourcePolicyRevision == transition.fromSourcePolicyRevision &&
			ambientConsentEpoch == transition.fromAmbientConsentEpoch
		) return true
		firstSegmentGeneration = transition.toContinuitySegmentGeneration
		phaseStart = transition.effectiveBoundaryTimeMs
	}
	return generation in firstSegmentGeneration..cursor.continuitySegmentGeneration &&
		start >= phaseStart &&
		end <= cursor.importedThroughTimeMs && authorizationRevision == cursor.authorizationRevision &&
		authorizationFingerprint == cursor.authorizationFingerprint &&
		sourcePolicyRevision == cursor.sourcePolicyRevision &&
		ambientConsentEpoch == cursor.ambientConsentEpoch &&
		retentionScope == cursor.retentionScope &&
		retentionPolicyId == cursor.retentionPolicyId &&
		retentionApprovalRevision == cursor.retentionApprovalRevision
}

private fun List<AmbientStepsImportAuthorityTransitionEntity>.hasExactAuthorityChain(
	cursor: AmbientStepsImportCursorEntity,
): Boolean {
	if (isEmpty()) return cursor.authorityTransitionSequence == 0L
	zipWithNext().forEach { (previous, next) ->
		if (next.fromContinuitySegmentGeneration < previous.toContinuitySegmentGeneration ||
			next.fromAuthorizationRevision != previous.toAuthorizationRevision ||
			next.fromAuthorizationFingerprint != previous.toAuthorizationFingerprint ||
			next.fromSourcePolicyRevision != previous.toSourcePolicyRevision ||
			next.fromAmbientConsentEpoch != previous.toAmbientConsentEpoch ||
			next.effectiveBoundaryTimeMs < previous.effectiveBoundaryTimeMs ||
			next.recordedAtMs < previous.recordedAtMs
		) return false
	}
	val latest = last()
	return latest.toContinuitySegmentGeneration <= cursor.continuitySegmentGeneration &&
		latest.toAuthorizationRevision == cursor.authorizationRevision &&
		latest.toAuthorizationFingerprint == cursor.authorizationFingerprint &&
		latest.toAuthorizationEffectiveBootId == cursor.authorizationEffectiveBootId &&
		latest.toAuthorizationEffectiveElapsedRealtimeNanos ==
			cursor.authorizationEffectiveElapsedRealtimeNanos &&
		latest.toAuthorizationEffectiveWallTimeMs == cursor.authorizationEffectiveWallTimeMs &&
		latest.toSourcePolicyRevision == cursor.sourcePolicyRevision &&
		latest.toAmbientConsentEpoch == cursor.ambientConsentEpoch &&
		latest.effectiveBoundaryTimeMs <= cursor.segmentStartTimeMs
}

private fun SourcePolicyEntity.hasAmbientAuthority(
	consent: SourceConsentEpochEntity,
	windowStartTimeMs: Long,
	authorization: SourceAuthorizationEntity,
): Boolean = sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
	hasExactEligibleAmbientConsentReference(consent) &&
	isEffectiveAtOrBefore(
		authorization.effectiveBootId,
		authorization.effectiveElapsedRealtimeNanos,
		authorization.effectiveWallTimeMs,
	) &&
	consent.isEffectiveAtOrBefore(
		authorization.effectiveBootId,
		authorization.effectiveElapsedRealtimeNanos,
		authorization.effectiveWallTimeMs,
	) &&
	authorization.effectiveWallTimeMs <= windowStartTimeMs &&
	consent.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT

private fun ProviderRegistrationGenerationEntity.matches(
	cursor: AmbientStepsImportCursorEntity,
): Boolean = sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
	registrationGeneration == cursor.registrationGeneration &&
	sourceInstanceId == cursor.sourceInstanceId && clockDomainId == cursor.registrationClockDomainId &&
	ownerScope == SourceProviderPurposeScope.exactOwnerScope(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
	) && physicalConfigurationFingerprint ==
	"ambient-steps-provider:v1:mechanism=${cursor.provider}" &&
	collectedDataEpoch == cursor.collectedDataEpoch && acceptedAtMs == cursor.registrationAcceptedAtMs &&
	acceptedElapsedRealtimeNanos == cursor.registrationAcceptedElapsedRealtimeNanos

private fun AmbientStepsFactRevisionEntity.sameStableOrigin(
	other: AmbientStepsFactRevisionEntity,
): Boolean = provider == other.provider && registrationGeneration == other.registrationGeneration &&
	continuitySegmentGeneration == other.continuitySegmentGeneration &&
	sourceInstanceId == other.sourceInstanceId && windowStartTimeMs == other.windowStartTimeMs &&
	structuralEpochDay == other.structuralEpochDay && storedZoneId == other.storedZoneId &&
	structuralDayStartTimeMs == other.structuralDayStartTimeMs &&
	structuralDayEndTimeMs == other.structuralDayEndTimeMs

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

private data class AmbientStepsFactAuthorityKey(
	val registrationGeneration: Long,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val sourcePolicyRevision: Long,
	val ambientConsentEpoch: Long,
	val retentionScope: String,
	val retentionPolicyId: String,
	val retentionApprovalRevision: Long,
)

private fun AmbientStepsFactRevisionEntity.authorityKey() = AmbientStepsFactAuthorityKey(
	requireNotNull(registrationGeneration),
	requireNotNull(authorizationRevision),
	requireNotNull(authorizationFingerprint),
	requireNotNull(sourcePolicyRevision),
	requireNotNull(ambientConsentEpoch),
	requireNotNull(retentionScope),
	requireNotNull(retentionPolicyId),
	requireNotNull(retentionApprovalRevision),
)

private fun blocked(reason: AmbientStepsSourceDeletionBlockedReason) =
	AmbientStepsSourceDeletionResult.Blocked(reason)

internal class AmbientStepsMaintenanceLimitExceeded(message: String) : IllegalStateException(message)

private fun requireMaintenanceBound(condition: Boolean, message: String) {
	if (!condition) throw AmbientStepsMaintenanceLimitExceeded(message)
}

private val DEFAULT_AMBIENT_STEPS_MAINTENANCE_LIMITS = AmbientStepsMaintenanceLimits()
private const val DELETE_BATCH_SIZE = 128
private const val COUNT_DOMAIN_OWNER_BATCH_SIZE = 400
private const val AUTHORITY_QUERY_BATCH_SIZE = 256
private const val INSERT_IGNORED = -1L
private const val EMPTY_EFFECT_CHECKSUM = "0000000000000000000000000000000000000000000000000000000000000000"
