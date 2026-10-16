package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState

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
	val authority = loadAmbientStepsImportAuthority(evidence, limits)
	val authenticator = AmbientStepsStateAuthenticator(this, owner, authority, limits)
	authenticator.authenticateCursors()
	authenticator.visitLineages(checkpoint, authenticateRetractedPayloadAuthority, visitLineage)
	return authority
}

private suspend fun AppDatabase.loadAmbientStepsImportAuthority(
	evidence: SourceEvidenceState,
	limits: AmbientStepsMaintenanceLimits,
): AmbientStepsAuthenticatedAuthorityState {
	val stateDao = ambientStepsImportStateDao()
	val cursors = stateDao.maintenanceCursors(limits.maximumCursors + 1)
	requireAmbientStepsMaintenanceBound(
		cursors.size <= limits.maximumCursors,
		"Ambient Steps cursor maintenance bound exceeded",
	)
	val gaps = stateDao.maintenanceGaps(limits.maximumGaps + 1)
	requireAmbientStepsMaintenanceBound(
		gaps.size <= limits.maximumGaps,
		"Ambient Steps gap maintenance bound exceeded",
	)
	val transitions = stateDao.maintenanceAuthorityTransitions(
		limits.maximumAuthorityTransitions + 1,
	)
	requireAmbientStepsMaintenanceBound(
		transitions.size <= limits.maximumAuthorityTransitions,
		"Ambient Steps authority-transition maintenance bound exceeded",
	)
	check(cursors.all { evidence.acceptsRetainedEpoch(it.collectedDataEpoch) } &&
		gaps.all { evidence.acceptsRetainedEpoch(it.collectedDataEpoch) } &&
		transitions.all { evidence.acceptsRetainedEpoch(it.collectedDataEpoch) }
	) { "Ambient Steps import state belongs to another collected-data epoch" }
	return AmbientStepsAuthenticatedAuthorityState(evidence, cursors, gaps, transitions)
}

/** One bounded audit owns its saved cursor indexes and exact authorization cache. */
private class AmbientStepsStateAuthenticator(
	private val database: AppDatabase,
	private val owner: SourceDestinationOwnerEntity,
	private val authority: AmbientStepsAuthenticatedAuthorityState,
	private val limits: AmbientStepsMaintenanceLimits,
) {
	private val cursorsByGeneration = authority.cursors
		.associateBy(AmbientStepsImportCursorEntity::registrationGeneration)

	init {
		check(cursorsByGeneration.size == authority.cursors.size) { "Ambient Steps cursor generations collide" }
	}

	private val gapsByRegistration = authority.gaps.groupBy(AmbientStepsImportGapEntity::registrationGeneration)
	private val transitionsByRegistration = authority.transitions.groupBy(
		AmbientStepsImportAuthorityTransitionEntity::registrationGeneration,
	)

	suspend fun authenticateCursors() {
		authority.cursors.forEach { cursor ->
			val registration = requireNotNull(database.sourceBrokerDao().registration(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				cursor.registrationGeneration,
			)) { "Ambient Steps cursor is missing its provider registration" }
			check(registration.matchesAmbientStepsMaintenanceCursor(cursor)) {
				"Ambient Steps cursor registration authority is invalid"
			}
			authenticateCursorGaps(cursor)
			authenticateCursorTransitions(cursor)
		}
		check(gapsByRegistration.keys.all(cursorsByGeneration::containsKey) &&
			transitionsByRegistration.keys.all(cursorsByGeneration::containsKey)
		) { "Ambient Steps import state has a dangling cursor reference" }
	}

	private fun authenticateCursorGaps(cursor: AmbientStepsImportCursorEntity) {
		val cursorGaps = gapsByRegistration[cursor.registrationGeneration].orEmpty()
		check(cursorGaps.size.toLong() == cursor.lastGapSequence &&
			cursorGaps.withIndex().all { (index, gap) ->
				gap.gapSequence == index + 1L && gap.provider == cursor.provider &&
					gap.sourceInstanceId == cursor.sourceInstanceId &&
					gap.recordedAtMs <= cursor.updatedAtMs &&
					gap.gapEndTimeMs <= cursor.importedThroughTimeMs
			}
		) { "Ambient Steps gap sequence is incomplete or crosses cursor authority" }
	}

	private fun authenticateCursorTransitions(cursor: AmbientStepsImportCursorEntity) {
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
		check(cursorTransitions.hasExactAmbientStepsMaintenanceAuthorityChain(cursor)) {
			"Ambient Steps authorization transition chain does not reach the exact cursor authority"
		}
	}

	suspend fun visitLineages(
		checkpoint: suspend (AmbientStepsMaintenanceCheckpoint) -> Unit,
		authenticateRetractedPayloadAuthority: Boolean,
		visitLineage: suspend (AmbientStepsFactLineage) -> Unit,
	) {
		val authenticatedAuthorities = mutableMapOf<AmbientStepsFactAuthorityKey, Boolean>()
		database.visitAmbientStepsFactLineages(limits, checkpoint) { lineage ->
			authenticateLineage(lineage, authenticateRetractedPayloadAuthority, authenticatedAuthorities)
			visitLineage(lineage)
		}
	}

	private suspend fun authenticateLineage(
		lineage: AmbientStepsFactLineage,
		authenticateRetractedPayloadAuthority: Boolean,
		authenticatedAuthorities: MutableMap<AmbientStepsFactAuthorityKey, Boolean>,
	) {
		check(authority.evidence.acceptsRetainedEpoch(lineage.latest.collectedDataEpoch) &&
			lineage.latest.writerOwnerGeneration == owner.ownerGeneration
		) { "Ambient Steps facts do not match current epoch/owner authority" }
		if (authenticateRetractedPayloadAuthority ||
			lineage.latest.operation != AmbientStepsFactRevisionEntity.OPERATION_RETRACT
		) {
			lineage.upserts.forEach { fact -> authenticateFact(fact, authenticatedAuthorities) }
		}
	}

	private suspend fun authenticateFact(
		fact: AmbientStepsFactRevisionEntity,
		authenticatedAuthorities: MutableMap<AmbientStepsFactAuthorityKey, Boolean>,
	) {
		val cursor = fact.registrationGeneration?.let(cursorsByGeneration::get)
		check(cursor != null && cursor.provider == fact.provider &&
			cursor.sourceInstanceId == fact.sourceInstanceId &&
			cursor.importedThroughTimeMs >= requireNotNull(fact.windowEndTimeMs) &&
			requireNotNull(fact.continuitySegmentGeneration) <=
				cursor.continuitySegmentGeneration
		) { "Ambient Steps fact is outside its exact cursor authority" }
		check(fact.hasAmbientStepsMaintenanceHistoricalAuthority(cursor, transitionsByRegistration[
			cursor.registrationGeneration
		].orEmpty())) { "Ambient Steps fact has no exact authority phase" }
		val authorityKey = fact.authorityKey()
		val authenticated = authenticatedAuthorities[authorityKey] ?: run {
			requireAmbientStepsMaintenanceBound(
				authenticatedAuthorities.size < limits.maximumAuthorizationRevisions,
				"Ambient Steps authorization-revision maintenance bound exceeded",
			)
			database.authenticateAmbientFactAuthority(fact, cursor, limits).also { result ->
				authenticatedAuthorities[authorityKey] = result
			}
		}
		check(authenticated) {
			"Ambient Steps fact policy/consent/authorization authority is invalid"
		}
	}
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
		if (current.isEmpty()) {
			return
		}
		lineageCount = Math.addExact(lineageCount, 1)
		requireAmbientStepsMaintenanceBound(
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
		if (page.isEmpty()) {
			break
		}
		revisionCount = Math.addExact(revisionCount, page.size)
		requireAmbientStepsMaintenanceBound(
			revisionCount <= limits.maximumRevisions,
			"Ambient Steps fact revision maintenance bound exceeded",
		)
		checkpoint(AmbientStepsMaintenanceCheckpoint.FACT_PAGE_LOADED)
		page.forEach { fact ->
			check(AmbientStepsFactIntegrity.hasValidEffectChecksum(fact)) {
				"Ambient Steps maintenance encountered unauthenticated fact state"
			}
			if (fact.startsAmbientStepsMaintenanceLineageAfter(current.lastOrNull())) {
				emitCurrent()
			}
			current += fact
		}
		val last = page.last()
		check(last.advancesAmbientStepsMaintenanceCursor(afterLogicalFactId, afterSemanticRevision)
		) { "Ambient Steps fact maintenance page did not advance" }
		afterLogicalFactId = last.logicalFactId
		afterSemanticRevision = last.semanticRevision
		if (page.size < limits.factPageSize) {
			break
		}
	}
	emitCurrent()
}

private fun AmbientStepsFactRevisionEntity.startsAmbientStepsMaintenanceLineageAfter(
	previous: AmbientStepsFactRevisionEntity?,
): Boolean = previous != null && previous.logicalFactId != logicalFactId

private fun AmbientStepsFactRevisionEntity.advancesAmbientStepsMaintenanceCursor(
	afterLogicalFactId: String?,
	afterSemanticRevision: Long?,
): Boolean = logicalFactId != afterLogicalFactId || semanticRevision != afterSemanticRevision

private fun List<AmbientStepsFactRevisionEntity>.toAuthenticatedLineage(): AmbientStepsFactLineage {
	check(isNotEmpty())
	val first = first()
	val expectedFirstRevision = if (
		first.operation == AmbientStepsFactRevisionEntity.OPERATION_RETRACT && size == 1
	) {
		first.semanticRevision
	} else {
		1L
	}
	check(withIndex().all { (index, fact) ->
		fact.hasAmbientStepsMaintenanceLineageIdentity(first) &&
			fact.semanticRevision == expectedFirstRevision + index
	}) { "Ambient Steps correction lineage is not contiguous" }
	val upserts = filter { it.operation == AmbientStepsFactRevisionEntity.OPERATION_UPSERT }
	val retractions = filter { it.operation == AmbientStepsFactRevisionEntity.OPERATION_RETRACT }
	check(retractions.size <= 1 && (retractions.isEmpty() || last() == retractions.single())) {
		"Ambient Steps deletion retraction is not terminal"
	}
	upserts.firstOrNull()?.let { origin ->
		check(upserts.all { fact -> fact.hasSameAmbientStepsMaintenanceOrigin(origin) }) {
			"Ambient Steps corrections cross immutable source/day authority"
		}
	}
	return AmbientStepsFactLineage(first.logicalFactId, size, upserts, last())
}

private fun AmbientStepsFactRevisionEntity.hasAmbientStepsMaintenanceLineageIdentity(
	first: AmbientStepsFactRevisionEntity,
): Boolean = logicalFactId == first.logicalFactId && writerId == first.writerId &&
	writerVersion == first.writerVersion && writerOwnerGeneration == first.writerOwnerGeneration &&
	collectedDataEpoch == first.collectedDataEpoch && purpose == first.purpose

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
