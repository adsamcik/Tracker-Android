package com.adsamcik.tracker.shared.base.database.dao

import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Untrusted candidate state retained until every checksum-covered field has been authenticated. */
data class StepsFactCandidateState(
	val scopeCarrier: StepFactRevisionEntity?,
	val state: StepFactRevisionEntity?,
	val hasInvalidSemanticRevision: Boolean = false,
)

/** Stable database-order identity used to deduplicate provisional run anchors. */
data class StepsFactCandidateIdentity(
	val writerProjectionId: String,
	val writerProjectionVersion: Long,
	val logicalFactId: String,
)

/**
 * Loads every Steps fact plausibly related to the requested physical runs through bounded pages.
 *
 * Invalid membership remains provisionally attached by logical identity or admission interval so a
 * corrupt row cannot disappear before a product reader fails closed. A fully authenticated fact is
 * confined to its exact durable run and therefore cannot taint an unrequested replacement sibling.
 * The caller must hold a stable Room read transaction around this complete traversal.
 */
suspend fun TrackingHistoryReadDao.loadStepsFactCandidateStatesByRun(
	serviceRuns: List<SourceServiceRunEntity>,
	onPageLoaded: (Int) -> Unit = {},
): Map<String, List<StepsFactCandidateState>> {
	val statesByRun = linkedMapOf<
		String,
		LinkedHashMap<StepsFactCandidateIdentity, StepsFactCandidateState>,
	>()
	visitStepsFactCandidateStatesByRun(serviceRuns, onPageLoaded) { runId, identity, state ->
		statesByRun.getOrPut(runId, ::linkedMapOf)[identity] = state
		true
	}
	return statesByRun.mapValues { (_, states) -> states.values.toList() }
}

/**
 * Streams the same candidate relation for consumers that must preflight large bounded day windows
 * without retaining all fact state. Returning false from [visit] stops the traversal immediately.
 */
suspend fun TrackingHistoryReadDao.visitStepsFactCandidateStatesByRun(
	serviceRuns: List<SourceServiceRunEntity>,
	onPageLoaded: (Int) -> Unit = {},
	visit: (String, StepsFactCandidateIdentity, StepsFactCandidateState) -> Boolean,
): Boolean {
	if (serviceRuns.isEmpty()) {
		return true
	}
	for (batch in serviceRuns.chunked(STEPS_FACT_QUERY_RUN_BATCH_SIZE)) {
		if (!visitStepsFactCandidateStateBatch(batch, onPageLoaded, visit)) {
			return false
		}
	}
	return true
}

private suspend fun TrackingHistoryReadDao.visitStepsFactCandidateStateBatch(
	serviceRuns: List<SourceServiceRunEntity>,
	onPageLoaded: (Int) -> Unit,
	visit: (String, StepsFactCandidateIdentity, StepsFactCandidateState) -> Boolean,
): Boolean {
	val serviceRunIds = serviceRuns.map(SourceServiceRunEntity::serviceRunId)
	val requestedRunIds = serviceRunIds.toHashSet()
	val runsByLogicalId = serviceRuns.groupBy(SourceServiceRunEntity::logicalTrackingId)
	var cursor: StepsFactCandidateIdentity? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val page = stepFactStates(
			serviceRunIds = serviceRunIds,
			limit = STEPS_FACT_CANDIDATE_PAGE_SIZE,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			afterWriterProjectionId = cursor?.writerProjectionId,
			afterWriterProjectionVersion = cursor?.writerProjectionVersion,
			afterLogicalFactId = cursor?.logicalFactId,
		)
		onPageLoaded(page.size)
		if (page.isEmpty()) {
			break
		}
		for (row in page) {
			val factState = row.candidateState()
			val factIdentity = row.factIdentity()
			for (runId in row.candidateRunIds(factState, requestedRunIds, runsByLogicalId)) {
				if (!visit(runId, factIdentity, factState)) {
					return false
				}
			}
		}
		val nextCursor = page.last().factIdentity()
		check(nextCursor != cursor) { "Steps history fact page did not advance" }
		cursor = nextCursor
	}
	return true
}

/** Verifies intrinsic writer or local-delete lineage without granting deletion authority. */
@Suppress("CyclomaticComplexMethod", "ReturnCount")
fun hasValidStepsFactCandidateState(scoped: StepsFactCandidateState): Boolean {
	if (scoped.hasInvalidSemanticRevision) {
		return false
	}
	val scope = scoped.scopeCarrier ?: return false
	val state = scoped.state ?: return false
	if (!StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(scope)) {
		return false
	}
	return when (state.operation) {
		StepFactRevisionEntity.OPERATION_UPSERT -> state == scope
		StepFactRevisionEntity.OPERATION_RETRACT -> {
			if (scope.semanticRevision == Long.MAX_VALUE) {
				return false
			}
			val scopeIdentityDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = scope.purpose,
				logicalTrackingId = requireNotNull(scope.logicalTrackingId),
				serviceRunId = requireNotNull(scope.serviceRunId),
			)
			state.semanticRevision == scope.semanticRevision + 1L &&
				state.originKind == StepFactRevisionEntity.ORIGIN_LOCAL_DELETE &&
				state.writerProjectionId == scope.writerProjectionId &&
				state.writerProjectionVersion == scope.writerProjectionVersion &&
				state.writerBindingGeneration == scope.writerBindingGeneration &&
				state.logicalFactId == scope.logicalFactId && state.purpose == scope.purpose &&
				StepFactRevisionIntegrity.hasValidLocalDeleteEffectChecksum(
					retraction = state,
					expectedScopeIdentityDigest = scopeIdentityDigest,
				)
		}
		else -> false
	}
}

private fun UnvalidatedStepFactState.candidateRunIds(
	factState: StepsFactCandidateState,
	requestedRunIds: Set<String>,
	runsByLogicalId: Map<String, List<SourceServiceRunEntity>>,
): Collection<String> {
	val exactRunId = scopeCarrier.serviceRunId?.takeIf(requestedRunIds::contains)
	val hasValidIntrinsicState = hasValidStepsFactCandidateState(factState)
	return when {
		hasValidIntrinsicState && exactRunId != null -> listOf(exactRunId)
		hasValidIntrinsicState && hasDurableAttributedRun -> emptyList()
		else -> buildSet {
			provisionalServiceRunId.takeIf(requestedRunIds::contains)?.let(::add)
			exactRunId?.let(::add)
			scopeCarrier.logicalTrackingId?.let { logicalTrackingId ->
				runsByLogicalId[logicalTrackingId].orEmpty().forEach { run ->
					add(run.serviceRunId)
				}
			}
		}
	}
}

private fun UnvalidatedStepFactState.candidateState() = StepsFactCandidateState(
	scopeCarrier = scopeCarrier.validatedOrNull(),
	state = state.validatedOrNull(),
	hasInvalidSemanticRevision = invalidSemanticRevisionCount > 0,
)

private fun UnvalidatedStepFactState.factIdentity() = StepsFactCandidateIdentity(
	writerProjectionId = scopeCarrier.writerProjectionId,
	writerProjectionVersion = scopeCarrier.writerProjectionVersion,
	logicalFactId = scopeCarrier.logicalFactId,
)

private const val STEPS_FACT_CANDIDATE_PAGE_SIZE = 256
// The DAO expands run ids in three predicates. Keep 3N plus scalar binds below SQLite's
// historical 999-variable ceiling on every supported Android release.
private const val STEPS_FACT_QUERY_RUN_BATCH_SIZE = 64
