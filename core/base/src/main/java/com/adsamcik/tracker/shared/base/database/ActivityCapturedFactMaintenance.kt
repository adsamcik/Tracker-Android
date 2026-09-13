package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class ActivityCapturedMaintenanceCheckpoint {
	TRANSACTION_STARTED,
	REVISION_PAGE_LOADED,
	LINEAGE_AUTHENTICATED,
	DELETION_FENCES_INSTALLED,
	PAYLOAD_REMOVED,
}

internal data class ActivityCapturedMaintenanceLimits(
	val revisionPageSize: Int = 256,
	val maximumRevisions: Int = 65_536,
	val maximumLogicalWindows: Int = 16_384,
	val maximumFragmentsPerRevision: Int = 1_024,
	val maximumEvidencePerRevision: Int = 4_096,
	val maximumTotalFragments: Int = 131_072,
	val maximumTotalEvidence: Int = 262_144,
	val maximumCursors: Int = 16_384,
	val maximumRegistrationPlans: Int = 4_096,
	val maximumManifestsPerRun: Int = 256,
	val maximumSourcesPerRun: Int = 3_072,
	val maximumAuthorizationMembers: Int = 64,
	val maximumCaptureDemands: Int = 256,
	val maximumNonterminalRegistrations: Int = 64,
) {
	init {
		require(revisionPageSize in 1..1_024)
		listOf(
			maximumRevisions,
			maximumLogicalWindows,
			maximumFragmentsPerRevision,
			maximumEvidencePerRevision,
			maximumTotalFragments,
			maximumTotalEvidence,
			maximumCursors,
			maximumRegistrationPlans,
			maximumManifestsPerRun,
			maximumSourcesPerRun,
			maximumAuthorizationMembers,
			maximumCaptureDemands,
			maximumNonterminalRegistrations,
		).forEach { limit -> require(limit in 1 until Int.MAX_VALUE) }
		require(maximumLogicalWindows <= maximumRevisions)
	}
}

enum class ActivityCapturedRetentionBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	DESTINATION_OWNER_CHANGED,
	UNRECOGNIZED_PAYLOAD_PRESENT,
	MAINTENANCE_BOUND_EXCEEDED,
	FACT_AUTHORITY_UNVERIFIABLE,
	STALE_REQUEST,
}

sealed interface ActivityCapturedRetentionResult {
	data class Pruned(
		val logicalWindowCount: Int,
		val revisionCount: Int,
	) : ActivityCapturedRetentionResult

	data object NoChange : ActivityCapturedRetentionResult

	data class Blocked(
		val reason: ActivityCapturedRetentionBlockedReason,
	) : ActivityCapturedRetentionResult
}

enum class ActivityCapturedSourceDeletionBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	DESTINATION_OWNER_CHANGED,
	POLICY_AUTHORITY_UNAVAILABLE,
	CAPTURE_CONSENT_STILL_ELIGIBLE,
	CAPTURE_DEMAND_NOT_QUIESCED,
	CAPTURE_PROVIDER_NOT_QUIESCED,
	UNRECOGNIZED_PAYLOAD_PRESENT,
	MAINTENANCE_BOUND_EXCEEDED,
	FACT_AUTHORITY_UNVERIFIABLE,
	DELETION_FENCE_CONFLICT,
	STALE_REQUEST,
}

sealed interface ActivityCapturedSourceDeletionResult {
	data class Deleted(
		val logicalWindowCount: Int,
		val revisionCount: Int,
		val registrationPlanCount: Int,
		val fencedServiceRunCount: Int,
	) : ActivityCapturedSourceDeletionResult

	data object AlreadyDeleted : ActivityCapturedSourceDeletionResult

	data class Blocked(
		val reason: ActivityCapturedSourceDeletionBlockedReason,
	) : ActivityCapturedSourceDeletionResult
}

/**
 * Removes whole authenticated captured-Activity window lineages affected by the global floor.
 *
 * A derived wall boundary is an uncertainty interval. If either endpoint of any retained band can
 * precede [beforeMs], the complete stable window lineage is removed. The already-durable global
 * retention floor then prevents the WAL adapter or writer from recreating that window.
 */
suspend fun AppDatabase.pruneCapturedActivityFactsAffectedByRetentionFloor(
	beforeMs: Long,
	expectedCollectedDataEpoch: Long,
	markedAtMs: Long,
): ActivityCapturedRetentionResult = pruneCapturedActivityFactsAffectedByRetentionFloor(
	beforeMs,
	expectedCollectedDataEpoch,
	markedAtMs,
	DEFAULT_ACTIVITY_CAPTURED_MAINTENANCE_LIMITS,
	{ currentCoroutineContext().ensureActive() },
)

internal suspend fun AppDatabase.pruneCapturedActivityFactsAffectedByRetentionFloor(
	beforeMs: Long,
	expectedCollectedDataEpoch: Long,
	markedAtMs: Long,
	limits: ActivityCapturedMaintenanceLimits,
	checkpoint: suspend (ActivityCapturedMaintenanceCheckpoint) -> Unit,
): ActivityCapturedRetentionResult {
	require(beforeMs >= 0L)
	require(expectedCollectedDataEpoch >= 0L)
	require(markedAtMs >= 0L)
	return try {
		withTransaction {
			checkpoint(ActivityCapturedMaintenanceCheckpoint.TRANSACTION_STARTED)
			val state = sourceEvidenceStateDao().get()
			if (state == null || state.collectedDataEpoch != expectedCollectedDataEpoch ||
				state.retainedFromMs != beforeMs
			) block(ActivityCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
			val audit = auditCapturedActivityFacts(limits, checkpoint)
			if (markedAtMs < audit.latestDurableTimeMs) {
				block(ActivityCapturedRetentionBlockedReason.STALE_REQUEST)
			}
			val selected = audit.lineages.filter { lineage ->
				lineage.earliestPossibleWallTimeMs < beforeMs
			}
			if (selected.isEmpty()) return@withTransaction ActivityCapturedRetentionResult.NoChange
			val dao = activityCapturedFactDao()
			var removedRevisions = 0
			selected.chunked(DELETE_BATCH_SIZE).forEach { batch ->
				val ids = batch.map(ActivityCapturedLineage::logicalWindowId)
				val deletedCursors = dao.deleteExactCursors(WRITER_ID, WRITER_VERSION, ids)
				if (deletedCursors != batch.size) {
					block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				}
				val expectedRevisions = batch.sumOf { lineage -> lineage.revisions.size }
				val deletedRevisions = dao.deleteExactRevisionLineages(
					WRITER_ID,
					WRITER_VERSION,
					ids,
				)
				if (deletedRevisions != expectedRevisions) {
					block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				}
				removedRevisions = Math.addExact(removedRevisions, deletedRevisions)
				checkpoint(ActivityCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED)
			}
			check(sourceEvidenceStateDao().incrementRevision(markedAtMs) == 1) {
				"Unable to publish captured Activity retention"
			}
			ActivityCapturedRetentionResult.Pruned(selected.size, removedRevisions)
		}
	} catch (blocked: ActivityCapturedRetentionBlockedException) {
		ActivityCapturedRetentionResult.Blocked(blocked.reason)
	} catch (@Suppress("SwallowedException") _: ActivityCapturedMaintenanceLimitExceeded) {
		ActivityCapturedRetentionResult.Blocked(
			ActivityCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		ActivityCapturedRetentionResult.Blocked(
			ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}
}

/**
 * Deletes captured Activity product state after capture consent has been explicitly revoked.
 *
 * CONTROL demands, authorization history, and WAL rows are never changed here. Every retained fact
 * scope receives a permanent run fence before fact payload is removed, so old captured WAL cannot
 * recreate it. Unknown payload shapes block the operation rather than being silently skipped.
 */
suspend fun AppDatabase.deleteCapturedActivityFactsAfterConsentReset(
	expectedCollectedDataEpoch: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
): ActivityCapturedSourceDeletionResult = deleteCapturedActivityFactsAfterConsentReset(
	expectedCollectedDataEpoch,
	expectedRevokedConsentEpoch,
	deletedAtMs,
	DEFAULT_ACTIVITY_CAPTURED_MAINTENANCE_LIMITS,
	{ currentCoroutineContext().ensureActive() },
)

internal suspend fun AppDatabase.deleteCapturedActivityFactsAfterConsentReset(
	expectedCollectedDataEpoch: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
	limits: ActivityCapturedMaintenanceLimits,
	checkpoint: suspend (ActivityCapturedMaintenanceCheckpoint) -> Unit,
): ActivityCapturedSourceDeletionResult {
	require(expectedCollectedDataEpoch >= 0L)
	require(expectedRevokedConsentEpoch >= 0L)
	require(deletedAtMs >= 0L)
	return try {
		withTransaction {
			checkpoint(ActivityCapturedMaintenanceCheckpoint.TRANSACTION_STARTED)
			val evidenceState = sourceEvidenceStateDao().get()
			if (evidenceState?.collectedDataEpoch != expectedCollectedDataEpoch) {
				block(ActivityCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
			}
			val policyAuthority = sourcePolicyDao().authority()
			val policy = policyAuthority?.takeIf { authority ->
				authority.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
			}?.let { authority ->
				sourcePolicyDao().policyAtRevision(authority.currentPolicyRevision, ACTIVITY_SOURCE)
			}
			val revokedConsent = sourcePolicyDao().latestConsentEpoch(
				ACTIVITY_SOURCE,
				CAPTURE_PURPOSE,
			)
			if (policy == null || revokedConsent == null ||
				revokedConsent.epoch != expectedRevokedConsentEpoch
			) block(ActivityCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE)
			if (policy.capturePersistenceEligible || policy.captureConsentEpoch != null ||
				revokedConsent.eligible || revokedConsent.persistenceEligible ||
				revokedConsent.policyRevision != policy.policyRevision ||
				revokedConsent.effectiveBootId != policy.effectiveBootId ||
				revokedConsent.effectiveElapsedRealtimeNanos !=
					policy.effectiveElapsedRealtimeNanos ||
				revokedConsent.effectiveWallTimeMs != policy.effectiveWallTimeMs
			) block(ActivityCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE)
			if (deletedAtMs < maxOf(
				policyAuthority.updatedAtMs,
				policy.effectiveWallTimeMs,
				revokedConsent.effectiveWallTimeMs,
			)) block(ActivityCapturedSourceDeletionBlockedReason.STALE_REQUEST)

			val dao = activityCapturedFactDao()
			val demands = dao.capturedActivityDemandsForDeletion(
				ACTIVITY_SOURCE,
				CAPTURE_PURPOSE,
				limits.maximumCaptureDemands + 1,
			)
			if (demands.size > limits.maximumCaptureDemands) {
				throw ActivityCapturedMaintenanceLimitExceeded()
			}
			if (demands.isNotEmpty()) {
				block(ActivityCapturedSourceDeletionBlockedReason.CAPTURE_DEMAND_NOT_QUIESCED)
			}
			val registrations = dao.activityRegistrationsForDeletion(
				ACTIVITY_SOURCE,
				limits.maximumNonterminalRegistrations + 1,
			)
			if (registrations.size > limits.maximumNonterminalRegistrations) {
				throw ActivityCapturedMaintenanceLimitExceeded()
			}
			// Activity still has one shared physical callback registration. Until a released owner
			// scope proves otherwise, every nonterminal generation is capture-compatible.
			if (registrations.isNotEmpty()) {
				block(ActivityCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED)
			}

			val audit = auditCapturedActivityFacts(limits, checkpoint, requireOwnerWhenEmpty = false)
			val plans = auditCapturedActivityRegistrationPlans(limits)
			if (deletedAtMs < audit.latestDurableTimeMs) {
				block(ActivityCapturedSourceDeletionBlockedReason.STALE_REQUEST)
			}
			if (audit.lineages.isEmpty() && plans.isEmpty()) {
				return@withTransaction ActivityCapturedSourceDeletionResult.AlreadyDeleted
			}
			val scopes = audit.lineages.map { lineage ->
				ActivityCapturedRunScope(
					lineage.revisions.first().logicalTrackingId,
					lineage.revisions.first().serviceRunId,
				)
			}.distinct()
			for (scope in scopes) {
				val expectedFence = SourceDeletionFenceEntity.createLogicalServiceRun(
					sourceKind = ACTIVITY_SOURCE,
					purpose = CAPTURE_PURPOSE,
					logicalTrackingId = scope.logicalTrackingId,
					serviceRunId = scope.serviceRunId,
					fenceGeneration = FIRST_DELETION_GENERATION,
					collectedDataEpoch = expectedCollectedDataEpoch,
					deletedAtMs = deletedAtMs,
				)
				if (sourceDeletionFenceDao().insertIfAbsent(expectedFence) == INSERT_IGNORED) {
					val retained = sourceDeletionFenceDao().get(
						expectedFence.sourceKind,
						expectedFence.purpose,
						expectedFence.scopeKind,
						expectedFence.scopeIdentityDigest,
					)
					if (retained != expectedFence) {
						block(ActivityCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
					}
				}
			}
			checkpoint(ActivityCapturedMaintenanceCheckpoint.DELETION_FENCES_INSTALLED)

			val revisionCount = audit.lineages.sumOf { lineage -> lineage.revisions.size }
			dao.deleteAllEvidence()
			dao.deleteAllFragments()
			dao.deleteAllCursors()
			dao.deleteAllRevisions()
			dao.deleteAllRegistrationPlanBindings()
			if (dao.revisionCount() != 0L || dao.fragmentCount() != 0L ||
				dao.evidenceCount() != 0L || dao.cursorCount() != 0L ||
				dao.registrationPlanBindingCount() != 0L
			) block(ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			check(sourceEvidenceStateDao().incrementRevision(deletedAtMs) == 1) {
				"Unable to publish captured Activity source deletion"
			}
			checkpoint(ActivityCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED)
			ActivityCapturedSourceDeletionResult.Deleted(
				logicalWindowCount = audit.lineages.size,
				revisionCount = revisionCount,
				registrationPlanCount = plans.size,
				fencedServiceRunCount = scopes.size,
			)
		}
	} catch (blocked: ActivityCapturedSourceDeletionBlockedException) {
		ActivityCapturedSourceDeletionResult.Blocked(blocked.reason)
	} catch (blocked: ActivityCapturedRetentionBlockedException) {
		ActivityCapturedSourceDeletionResult.Blocked(
			when (blocked.reason) {
				ActivityCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED ->
					ActivityCapturedSourceDeletionBlockedReason.DESTINATION_OWNER_CHANGED
				ActivityCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT ->
					ActivityCapturedSourceDeletionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT
				ActivityCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED ->
					ActivityCapturedSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED
				else -> ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE
			},
		)
	} catch (@Suppress("SwallowedException") _: ActivityCapturedMaintenanceLimitExceeded) {
		ActivityCapturedSourceDeletionResult.Blocked(
			ActivityCapturedSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
		)
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		ActivityCapturedSourceDeletionResult.Blocked(
			ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
		)
	}
}

private suspend fun AppDatabase.auditCapturedActivityFacts(
	limits: ActivityCapturedMaintenanceLimits,
	checkpoint: suspend (ActivityCapturedMaintenanceCheckpoint) -> Unit,
	requireOwnerWhenEmpty: Boolean = true,
): ActivityCapturedFactAudit {
	val dao = activityCapturedFactDao()
	if (dao.unsupportedRevisionCount(WRITER_ID, WRITER_VERSION) != 0L ||
		dao.unsupportedFragmentCount(WRITER_ID, WRITER_VERSION) != 0L ||
		dao.unsupportedEvidenceCount(WRITER_ID, WRITER_VERSION) != 0L ||
		dao.unsupportedCursorCount(WRITER_ID, WRITER_VERSION) != 0L
	) block(ActivityCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT)

	val owner = sourceDestinationOwnerDao().get(
		ACTIVITY_SOURCE,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
	)
	val ownerValid = owner != null &&
		owner.owner == SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS &&
		owner.ownerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
	val lineages = mutableListOf<ActivityCapturedLineage>()
	var afterWindowId: String? = null
	var afterRevision: Long? = null
	var revisionCount = 0
	var fragmentCount = 0
	var evidenceCount = 0
	var current = mutableListOf<ActivityCapturedPersistedRevision>()
	while (true) {
		val page = dao.maintenanceRevisionPage(
			WRITER_ID,
			WRITER_VERSION,
			afterWindowId,
			afterRevision,
			limits.revisionPageSize,
		)
		if (page.isEmpty()) break
		revisionCount = Math.addExact(revisionCount, page.size)
		if (revisionCount > limits.maximumRevisions) throw ActivityCapturedMaintenanceLimitExceeded()
		checkpoint(ActivityCapturedMaintenanceCheckpoint.REVISION_PAGE_LOADED)
		for (revision in page) {
			if (current.isNotEmpty() && current.last().revision.logicalWindowId != revision.logicalWindowId) {
				lineages += authenticateActivityCapturedLineage(current, limits)
				current = mutableListOf()
				if (lineages.size > limits.maximumLogicalWindows) {
					throw ActivityCapturedMaintenanceLimitExceeded()
				}
			}
			val fragments = dao.maintenanceFragments(
				WRITER_ID,
				WRITER_VERSION,
				revision.logicalWindowId,
				revision.semanticRevision,
				limits.maximumFragmentsPerRevision + 1,
			)
			val evidence = dao.maintenanceEvidence(
				WRITER_ID,
				WRITER_VERSION,
				revision.logicalWindowId,
				revision.semanticRevision,
				limits.maximumEvidencePerRevision + 1,
			)
			if (fragments.size > limits.maximumFragmentsPerRevision ||
				evidence.size > limits.maximumEvidencePerRevision
			) throw ActivityCapturedMaintenanceLimitExceeded()
			fragmentCount = Math.addExact(fragmentCount, fragments.size)
			evidenceCount = Math.addExact(evidenceCount, evidence.size)
			if (fragmentCount > limits.maximumTotalFragments ||
				evidenceCount > limits.maximumTotalEvidence
			) throw ActivityCapturedMaintenanceLimitExceeded()
			current += ActivityCapturedPersistedRevision(revision, fragments, evidence)
		}
		afterWindowId = page.last().logicalWindowId
		afterRevision = page.last().semanticRevision
		if (page.size < limits.revisionPageSize) break
	}
	if (current.isNotEmpty()) lineages += authenticateActivityCapturedLineage(current, limits)
	if (lineages.size > limits.maximumLogicalWindows) throw ActivityCapturedMaintenanceLimitExceeded()
	if ((requireOwnerWhenEmpty || lineages.isNotEmpty()) && !ownerValid) {
		block(ActivityCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED)
	}

	val cursors = loadActivityCapturedCursors(limits)
	if (dao.revisionCount() != revisionCount.toLong() ||
		dao.fragmentCount() != fragmentCount.toLong() ||
		dao.evidenceCount() != evidenceCount.toLong() ||
		dao.cursorCount() != cursors.size.toLong()
	) block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (cursors.size != lineages.size || cursors.keys != lineages.mapTo(mutableSetOf()) {
			lineage -> lineage.logicalWindowId
		}
	) block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	for (lineage in lineages) {
		val latest = lineage.revisions.last().revision
		val cursor = cursors[lineage.logicalWindowId]
		if (cursor == null || cursor.logicalTrackingId != latest.logicalTrackingId ||
			cursor.serviceRunId != latest.serviceRunId ||
			cursor.sessionSegmentId != latest.sessionSegmentId ||
			cursor.writerOwnerGeneration != latest.writerOwnerGeneration ||
			cursor.latestSemanticRevision != latest.semanticRevision ||
			cursor.latestMutationId != latest.mutationId ||
			cursor.latestEffectChecksum != latest.effectChecksum ||
			cursor.cursorRevision != latest.semanticRevision ||
			cursor.collectedDataEpoch != latest.collectedDataEpoch ||
			cursor.updatedAtMs != latest.appliedAtMs
		) block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		authenticateActivityCapturedAuthority(latest, limits)
		checkpoint(ActivityCapturedMaintenanceCheckpoint.LINEAGE_AUTHENTICATED)
	}
	return ActivityCapturedFactAudit(
		lineages,
		maxOf(
			lineages.maxOfOrNull { lineage -> lineage.revisions.maxOf { it.revision.appliedAtMs } }
				?: 0L,
			cursors.values.maxOfOrNull(ActivityCapturedWindowCursorEntity::updatedAtMs) ?: 0L,
		),
	)
}

private fun authenticateActivityCapturedLineage(
	revisions: List<ActivityCapturedPersistedRevision>,
	limits: ActivityCapturedMaintenanceLimits,
): ActivityCapturedLineage {
	if (revisions.isEmpty() || revisions.size > limits.maximumRevisions) {
		block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val first = revisions.first().revision
	var earliestWallTimeMs: Long? = null
	for ((index, persisted) in revisions.withIndex()) {
		val revision = persisted.revision
		val expectedSemanticRevision = index + 1L
		if (revision.semanticRevision != expectedSemanticRevision ||
			revision.supersedesSemanticRevision != expectedSemanticRevision.takeIf { it > 1L }?.minus(1L) ||
			revision.logicalWindowId != first.logicalWindowId ||
			!revision.hasSameStableAuthority(first) ||
			revision.logicalWindowId != revision.calculatedLogicalWindowId() ||
			revision.mutationId != calculatedMutationId(
				revision.logicalWindowId,
				revision.semanticRevision,
			) || !persisted.hasCanonicalFragmentsAndEvidence() ||
			revision.effectChecksum != calculatedEffectChecksum(persisted)
		) block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val revisionEarliest = persisted.fragments.asSequence()
			.filter { fragment -> fragment.fragmentKind == ActivityCapturedFragmentEntity.KIND_BAND }
			.flatMap { fragment ->
				sequenceOf(
					earliestPossibleWallTime(
						requireNotNull(fragment.startWallTimeMs),
						requireNotNull(fragment.startWallTimeUncertaintyMs),
					),
					earliestPossibleWallTime(
						requireNotNull(fragment.endWallTimeMs),
						requireNotNull(fragment.endWallTimeUncertaintyMs),
					),
				)
			}.minOrNull()
		earliestWallTimeMs = listOfNotNull(earliestWallTimeMs, revisionEarliest).minOrNull()
	}
	return ActivityCapturedLineage(
		first.logicalWindowId,
		revisions,
		earliestWallTimeMs
			?: block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE),
	)
}

private suspend fun AppDatabase.authenticateActivityCapturedAuthority(
	revision: ActivityCapturedWindowRevisionEntity,
	limits: ActivityCapturedMaintenanceLimits,
) {
	val sessionDao = sourceSessionDao()
	val run = sessionDao.serviceRun(revision.serviceRunId)
	val session = sessionDao.session(revision.logicalTrackingId)
	val segment = sessionSegmentDao().getById(revision.sessionSegmentId)
	val sameClockSessionEnd = session?.let { current ->
		current.cutoffElapsedNanos.takeIf { current.lifecycleBootId == revision.clockDomainId }
	} ?: Long.MAX_VALUE
	if (run == null || session == null || segment == null ||
		run.logicalTrackingId != revision.logicalTrackingId ||
		run.sessionSegmentId != revision.sessionSegmentId ||
		run.bootId != revision.clockDomainId || run.leaseGeneration != revision.lifecycleLeaseGeneration ||
		run.startedElapsedNanos != revision.sessionRunEffectStartNanos ||
		segment.logicalTrackingId != revision.logicalTrackingId ||
		segment.serviceRunId != revision.serviceRunId ||
		session.lifecycleBootId != revision.clockDomainId ||
		session.lifecycleLeaseGeneration < revision.lifecycleLeaseGeneration ||
		sameClockSessionEnd != revision.sessionRunEffectEndNanos
	) block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val manifests = sessionDao.manifestsForServiceRun(
		revision.serviceRunId,
		limits.maximumManifestsPerRun + 1,
	)
	if (manifests.size > limits.maximumManifestsPerRun ||
		!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests)
	) block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val sources = trackingHistoryReadDao().manifestSources(
		listOf(revision.serviceRunId),
		limits.maximumSourcesPerRun + 1,
	)
	if (sources.size > limits.maximumSourcesPerRun || manifests.any { manifest ->
		!SessionManifestIntegrity.verify(
			manifest,
			sources.filter { source ->
				source.logicalTrackingId == manifest.logicalTrackingId &&
					source.manifestRevision == manifest.manifestRevision
			},
		)
	}) block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val manifestIndex = manifests.indexOfFirst { manifest ->
		manifest.manifestRevision == revision.manifestRevision
	}
	if (manifestIndex < 0) block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val manifest = manifests[manifestIndex]
	val membership = sources.filter { source ->
		source.logicalTrackingId == manifest.logicalTrackingId &&
			source.manifestRevision == manifest.manifestRevision
	}
	val binding = membership.singleOrNull { source ->
		source.sourceKind == ACTIVITY_SOURCE && source.purpose == CAPTURE_PURPOSE
	}
	val nextManifestStart = manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos
	if (binding == null || !binding.isCapturedActivityBinding(revision) ||
		manifest.logicalTrackingId != revision.logicalTrackingId ||
		manifest.serviceRunId != revision.serviceRunId ||
		manifest.sourcePolicyRevision != revision.sourcePolicyRevision ||
		manifest.acquisitionPlanRevision != revision.configurationRevision ||
		manifest.effectiveBootId != revision.clockDomainId ||
		manifest.effectiveElapsedRealtimeNanos > revision.windowStartElapsedRealtimeNanos ||
		nextManifestStart?.let { start -> revision.windowEndElapsedRealtimeNanos > start } == true ||
		manifest.zoneId != revision.storedZoneId || !hasValidZone(revision.storedZoneId)
	) block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val policy = sourcePolicyDao().policyAtRevision(revision.sourcePolicyRevision, ACTIVITY_SOURCE)
	val consent = sourcePolicyDao().consentEpoch(
		ACTIVITY_SOURCE,
		CAPTURE_PURPOSE,
		revision.captureConsentEpoch,
	)
	if (policy == null || consent == null || !policy.enabled ||
		!policy.capturePersistenceEligible || policy.captureConsentEpoch != revision.captureConsentEpoch ||
		policy.qosCode != binding.qosCode || policy.effectiveBootId != revision.clockDomainId ||
		policy.effectiveElapsedRealtimeNanos > revision.windowStartElapsedRealtimeNanos ||
		!consent.eligible || !consent.persistenceEligible ||
		consent.policyRevision > revision.sourcePolicyRevision ||
		consent.effectiveBootId != revision.clockDomainId ||
		consent.effectiveElapsedRealtimeNanos > revision.windowStartElapsedRealtimeNanos
	) block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val acquisition = sourcePlanStateDao().revision(revision.configurationRevision)
	val desiredPlans = activityCapturedFactDao().maintenanceDesiredPlans(
		revision.configurationRevision,
		MAX_SOURCES_PER_PLAN + 1,
	)
	val desired = desiredPlans.singleOrNull { plan -> plan.sourceKind == ACTIVITY_SOURCE }
	val appliedPlan = activityCapturedFactDao().registrationPlanBinding(
		revision.sourceInstanceId,
		revision.registrationGeneration,
	)
	if (desiredPlans.size > MAX_SOURCES_PER_PLAN || acquisition == null || desired == null ||
		appliedPlan == null || acquisition.sourcePolicyRevision != revision.sourcePolicyRevision ||
		appliedPlan.configurationRevision != revision.configurationRevision ||
		appliedPlan.desiredPlanPayloadVersion != desired.payloadVersion ||
		!appliedPlan.desiredPlanPayload.contentEquals(desired.payload) ||
		appliedPlan.desiredPlanPayloadChecksum != desired.payloadChecksum ||
		appliedPlan.desiredPlanPayloadChecksum != sha256(appliedPlan.desiredPlanPayload) ||
		appliedPlan.physicalConfigurationFingerprint != revision.physicalConfigurationFingerprint ||
		appliedPlan.appliedAtElapsedRealtimeNanos > revision.windowStartElapsedRealtimeNanos
	) block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val registration = sourceBrokerDao().registration(ACTIVITY_SOURCE, revision.registrationGeneration)
	val providerEnd = registration?.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
	val providerEndCompatible = revision.providerAcceptanceEndNanos == providerEnd ||
		(revision.providerAcceptanceEndNanos == Long.MAX_VALUE &&
			providerEnd >= revision.windowEndElapsedRealtimeNanos)
	if (registration == null || registration.sourceInstanceId != revision.sourceInstanceId ||
		registration.clockDomainId != revision.clockDomainId ||
		registration.physicalConfigurationFingerprint != revision.physicalConfigurationFingerprint ||
		registration.collectedDataEpoch != revision.collectedDataEpoch ||
		registration.acceptedElapsedRealtimeNanos != revision.providerAcceptanceStartNanos ||
		!providerEndCompatible
	) block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	val authorizationRows = activityCapturedFactDao().maintenanceAuthorizationMembers(
		ACTIVITY_SOURCE,
		revision.registrationGeneration,
		revision.authorizationRevision,
		limits.maximumAuthorizationMembers + 1,
	)
	if (authorizationRows.size > limits.maximumAuthorizationMembers) {
		throw ActivityCapturedMaintenanceLimitExceeded()
	}
	val authorization = authorizationRows.toAuthorizationSnapshotOrNull()
	val authorizationEnd = activityCapturedFactDao().nextAuthorizationBoundary(
		ACTIVITY_SOURCE,
		revision.registrationGeneration,
		revision.clockDomainId,
		authorization?.effectiveElapsedRealtimeNanos ?: 0L,
		authorization?.authorizationRevision ?: 0L,
	) ?: Long.MAX_VALUE
	val authorizationEndCompatible = revision.authorizationEffectEndNanos == authorizationEnd ||
		(revision.authorizationEffectEndNanos == Long.MAX_VALUE &&
			authorizationEnd >= revision.windowEndElapsedRealtimeNanos)
	val member = authorization?.authorizedMembers?.singleOrNull { candidate ->
		candidate.purpose == CAPTURE_PURPOSE && candidate.persistenceEligible &&
			candidate.logicalTrackingId == revision.logicalTrackingId &&
			candidate.serviceRunId == revision.serviceRunId &&
			candidate.manifestRevision == revision.manifestRevision &&
			candidate.lifecycleLeaseGeneration == revision.lifecycleLeaseGeneration &&
			candidate.sourcePolicyRevision == revision.sourcePolicyRevision &&
			candidate.consentEpoch == revision.captureConsentEpoch
	}
	if (authorization == null || member == null ||
		authorization.authorizationFingerprint != revision.authorizationFingerprint ||
		authorization.purposeEligibilityMask != revision.purposeEligibilityMask ||
		authorization.effectiveBootId != revision.clockDomainId ||
		authorization.effectiveElapsedRealtimeNanos != revision.authorizationEffectStartNanos ||
		!authorizationEndCompatible
	) block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
}

private suspend fun AppDatabase.loadActivityCapturedCursors(
	limits: ActivityCapturedMaintenanceLimits,
): Map<String, ActivityCapturedWindowCursorEntity> {
	val rows = mutableListOf<ActivityCapturedWindowCursorEntity>()
	var after: String? = null
	while (true) {
		val remaining = limits.maximumCursors - rows.size
		if (remaining <= 0) {
			if (activityCapturedFactDao().maintenanceCursorPage(
					WRITER_ID,
					WRITER_VERSION,
					after,
					1,
				).isNotEmpty()
			) throw ActivityCapturedMaintenanceLimitExceeded()
			break
		}
		val page = activityCapturedFactDao().maintenanceCursorPage(
			WRITER_ID,
			WRITER_VERSION,
			after,
			minOf(remaining, CURSOR_PAGE_SIZE),
		)
		if (page.isEmpty()) break
		if (page != page.sortedBy(ActivityCapturedWindowCursorEntity::logicalWindowId) ||
			page.distinctBy(ActivityCapturedWindowCursorEntity::logicalWindowId).size != page.size
		) block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		rows += page
		after = page.last().logicalWindowId
		if (page.size < minOf(remaining, CURSOR_PAGE_SIZE)) break
	}
	val byId = rows.associateBy(ActivityCapturedWindowCursorEntity::logicalWindowId)
	if (byId.size != rows.size) block(ActivityCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	return byId
}

private suspend fun AppDatabase.auditCapturedActivityRegistrationPlans(
	limits: ActivityCapturedMaintenanceLimits,
): List<ActivityCapturedRegistrationPlanEntity> {
	val result = mutableListOf<ActivityCapturedRegistrationPlanEntity>()
	var sourceInstanceId: String? = null
	var registrationGeneration: Long? = null
	while (true) {
		val remaining = limits.maximumRegistrationPlans - result.size
		if (remaining <= 0) {
			if (activityCapturedFactDao().maintenanceRegistrationPlanPage(
					sourceInstanceId,
					registrationGeneration,
					1,
				).isNotEmpty()
			) throw ActivityCapturedMaintenanceLimitExceeded()
			break
		}
		val page = activityCapturedFactDao().maintenanceRegistrationPlanPage(
			sourceInstanceId,
			registrationGeneration,
			minOf(remaining, REGISTRATION_PLAN_PAGE_SIZE),
		)
		if (page.isEmpty()) break
		for (plan in page) {
			val registration = sourceBrokerDao().registration(
				ACTIVITY_SOURCE,
				plan.registrationGeneration,
			)
			val desiredPlans = activityCapturedFactDao().maintenanceDesiredPlans(
				plan.configurationRevision,
				MAX_SOURCES_PER_PLAN + 1,
			)
			val desired = desiredPlans
				.singleOrNull { candidate -> candidate.sourceKind == ACTIVITY_SOURCE }
			if (plan.bindingIdentity != plan.calculatedBindingIdentity() ||
				desiredPlans.size > MAX_SOURCES_PER_PLAN ||
				plan.desiredPlanPayloadChecksum != sha256(plan.desiredPlanPayload) ||
				desired == null || desired.payloadVersion != plan.desiredPlanPayloadVersion ||
				!desired.payload.contentEquals(plan.desiredPlanPayload) ||
				desired.payloadChecksum != plan.desiredPlanPayloadChecksum ||
				registration == null || registration.sourceInstanceId != plan.sourceInstanceId ||
				registration.registrationGeneration != plan.registrationGeneration ||
				registration.physicalConfigurationFingerprint != plan.physicalConfigurationFingerprint
			) block(ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		result += page
		sourceInstanceId = page.last().sourceInstanceId
		registrationGeneration = page.last().registrationGeneration
		if (page.size < minOf(remaining, REGISTRATION_PLAN_PAGE_SIZE)) break
	}
	if (activityCapturedFactDao().registrationPlanBindingCount() != result.size.toLong()) {
		block(ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return result
}

private fun ActivityCapturedPersistedRevision.hasCanonicalFragmentsAndEvidence(): Boolean {
	val revision = revision
	if (fragments.isEmpty() || fragments.withIndex().any { (index, fragment) ->
		fragment.logicalWindowId != revision.logicalWindowId ||
			fragment.semanticRevision != revision.semanticRevision ||
			fragment.fragmentOrdinal != index
	}) return false
	if (fragments.first().intervalStartElapsedRealtimeNanos !=
		revision.windowStartElapsedRealtimeNanos ||
		fragments.last().intervalEndElapsedRealtimeNanos !=
		revision.windowEndElapsedRealtimeNanos ||
		fragments.zipWithNext().any { (left, right) ->
			left.intervalEndElapsedRealtimeNanos != right.intervalStartElapsedRealtimeNanos
		}
	) return false
	val bands = fragments.filter { fragment ->
		fragment.fragmentKind == ActivityCapturedFragmentEntity.KIND_BAND
	}
	if (bands.withIndex().any { (index, band) -> band.bandOrdinal != index }) return false
	val bandsByFragment = bands.associateBy(ActivityCapturedFragmentEntity::fragmentOrdinal)
	if (evidence.any { item ->
		item.logicalWindowId != revision.logicalWindowId ||
			item.semanticRevision != revision.semanticRevision ||
			item.fragmentOrdinal !in bandsByFragment
	}) return false
	val evidenceByFragment = evidence.groupBy(ActivityCapturedEvidenceEntity::fragmentOrdinal)
	if (bands.any { band -> evidenceByFragment[band.fragmentOrdinal].isNullOrEmpty() }) return false
	return evidenceByFragment.all { (fragmentOrdinal, items) ->
		val band = bandsByFragment.getValue(fragmentOrdinal)
		items.withIndex().all { (index, item) ->
			item.evidenceOrdinal == index &&
				item.providerElapsedRealtimeNanos >= band.intervalStartElapsedRealtimeNanos &&
				item.providerElapsedRealtimeNanos <= band.intervalEndElapsedRealtimeNanos &&
				item.coverageEndExclusiveElapsedRealtimeNanos?.let { end ->
					end <= revision.windowEndElapsedRealtimeNanos
				} != false
		}
	}
}

@Suppress("ComplexCondition")
private fun ActivityCapturedWindowRevisionEntity.hasSameStableAuthority(
	other: ActivityCapturedWindowRevisionEntity,
): Boolean = writerProjectionId == other.writerProjectionId &&
	writerProjectionVersion == other.writerProjectionVersion &&
	writerBindingGeneration == other.writerBindingGeneration &&
	writerOwnerGeneration == other.writerOwnerGeneration && logicalWindowId == other.logicalWindowId &&
	logicalTrackingId == other.logicalTrackingId && serviceRunId == other.serviceRunId &&
	sessionSegmentId == other.sessionSegmentId && purpose == other.purpose &&
	sourceInstanceId == other.sourceInstanceId &&
	registrationGeneration == other.registrationGeneration &&
	configurationRevision == other.configurationRevision &&
	physicalConfigurationFingerprint == other.physicalConfigurationFingerprint &&
	authorizationRevision == other.authorizationRevision &&
	authorizationFingerprint == other.authorizationFingerprint &&
	purposeEligibilityMask == other.purposeEligibilityMask &&
	sourcePolicyRevision == other.sourcePolicyRevision &&
	captureConsentEpoch == other.captureConsentEpoch && manifestRevision == other.manifestRevision &&
	lifecycleLeaseGeneration == other.lifecycleLeaseGeneration &&
	collectedDataEpoch == other.collectedDataEpoch && clockDomainId == other.clockDomainId &&
	storedZoneId == other.storedZoneId &&
	providerAcceptanceStartNanos == other.providerAcceptanceStartNanos &&
	providerAcceptanceEndNanos == other.providerAcceptanceEndNanos &&
	authorizationEffectStartNanos == other.authorizationEffectStartNanos &&
	authorizationEffectEndNanos == other.authorizationEffectEndNanos &&
	sessionRunEffectStartNanos == other.sessionRunEffectStartNanos &&
	sessionRunEffectEndNanos == other.sessionRunEffectEndNanos &&
	windowStartElapsedRealtimeNanos == other.windowStartElapsedRealtimeNanos &&
	windowEndElapsedRealtimeNanos == other.windowEndElapsedRealtimeNanos &&
	scopeDeletionGeneration == other.scopeDeletionGeneration

private fun ActivityCapturedWindowRevisionEntity.calculatedLogicalWindowId(): String = digest(
	"activity-captured-window-v1",
	listOf(
		logicalTrackingId,
		serviceRunId,
		sourceInstanceId,
		registrationGeneration.toString(),
		configurationRevision.toString(),
		physicalConfigurationFingerprint,
		authorizationRevision.toString(),
		authorizationFingerprint,
		purposeEligibilityMask.toString(),
		sourcePolicyRevision.toString(),
		captureConsentEpoch.toString(),
		manifestRevision.toString(),
		lifecycleLeaseGeneration.toString(),
		collectedDataEpoch.toString(),
		clockDomainId,
		providerAcceptanceStartNanos.toString(),
		providerAcceptanceEndNanos.toString(),
		authorizationEffectStartNanos.toString(),
		authorizationEffectEndNanos.toString(),
		sessionRunEffectStartNanos.toString(),
		sessionRunEffectEndNanos.toString(),
		windowStartElapsedRealtimeNanos.toString(),
		windowEndElapsedRealtimeNanos.toString(),
	),
)

private fun calculatedMutationId(logicalWindowId: String, semanticRevision: Long): String = digest(
	"activity-captured-mutation-v1",
	listOf(logicalWindowId, semanticRevision.toString()),
)

private fun calculatedEffectChecksum(value: ActivityCapturedPersistedRevision): String = digest(
	"activity-captured-effect-v1",
	listOf(
		value.revision.logicalWindowId,
		value.revision.storedZoneId,
		value.revision.coverage,
		value.revision.knownActiveDurationNanos.toString(),
		value.revision.knownInactiveDurationNanos.toString(),
		value.revision.unknownActivityDurationNanos.toString(),
		value.revision.unobservedDurationNanos.toString(),
	) + value.fragments.flatMap(::fragmentIntegrityParts) +
		value.evidence.flatMap(::evidenceIntegrityParts),
)

private fun fragmentIntegrityParts(fragment: ActivityCapturedFragmentEntity): List<String> = listOf(
	fragment.fragmentOrdinal,
	fragment.fragmentKind,
	fragment.bandOrdinal,
	fragment.intervalStartElapsedRealtimeNanos,
	fragment.intervalEndElapsedRealtimeNanos,
	fragment.gapReason,
	fragment.activity,
	fragment.mechanism,
	fragment.refinedTransitionActivity,
	fragment.confidenceKind,
	fragment.confidenceMinimumPercent,
	fragment.confidenceMaximumPercent,
	fragment.confidenceObservationCount,
	fragment.startWallTimeMs,
	fragment.startWallTimeUncertaintyMs,
	fragment.startBoundaryKind,
	fragment.startAnchorSourceEventId,
	fragment.startAnchorProviderElapsedNanos,
	fragment.endWallTimeMs,
	fragment.endWallTimeUncertaintyMs,
	fragment.endBoundaryKind,
	fragment.endAnchorSourceEventId,
	fragment.endAnchorProviderElapsedNanos,
	fragment.wallTimeContinuity,
).map { field -> field?.toString() ?: "null" }

private fun evidenceIntegrityParts(evidence: ActivityCapturedEvidenceEntity): List<String> = listOf(
	evidence.fragmentOrdinal.toString(),
	evidence.evidenceOrdinal.toString(),
	evidence.sourceEventId,
	evidence.sourceAdmissionOrdinal.toString(),
	evidence.sourceSequence.toString(),
	evidence.providerElapsedRealtimeNanos.toString(),
	evidence.receivedElapsedRealtimeNanos.toString(),
	evidence.observationKind,
	evidence.observedActivity,
	evidence.transitionChange ?: "null",
	evidence.confidencePercent?.toString() ?: "null",
	evidence.coverageEndExclusiveElapsedRealtimeNanos?.toString() ?: "null",
)

private fun SessionManifestSourceEntity.isCapturedActivityBinding(
	revision: ActivityCapturedWindowRevisionEntity,
): Boolean = sourceKind == ACTIVITY_SOURCE && purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
	persistenceEligible && consentEpoch == revision.captureConsentEpoch &&
	outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY &&
	writerOwner == SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS &&
	writerOwnerGeneration == revision.writerOwnerGeneration &&
	writerProjectionId == WRITER_ID && writerProjectionVersion == WRITER_VERSION &&
	writerBindingGeneration == SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION

private fun earliestPossibleWallTime(wallTimeMs: Long, uncertaintyMs: Long): Long =
	if (uncertaintyMs >= wallTimeMs) 0L else wallTimeMs - uncertaintyMs

private fun hasValidZone(zoneId: String): Boolean = try {
	ZoneId.of(zoneId)
	true
} catch (_: DateTimeException) {
	false
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
	.digest(bytes)
	.joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun digest(domain: String, values: List<String>): String {
	val canonical = (listOf(domain) + values).joinToString(separator = "") { value ->
		"${value.length}:$value"
	}
	return sha256(canonical.toByteArray(Charsets.UTF_8))
}

private data class ActivityCapturedPersistedRevision(
	val revision: ActivityCapturedWindowRevisionEntity,
	val fragments: List<ActivityCapturedFragmentEntity>,
	val evidence: List<ActivityCapturedEvidenceEntity>,
)

private data class ActivityCapturedLineage(
	val logicalWindowId: String,
	val revisions: List<ActivityCapturedPersistedRevision>,
	val earliestPossibleWallTimeMs: Long,
)

private data class ActivityCapturedFactAudit(
	val lineages: List<ActivityCapturedLineage>,
	val latestDurableTimeMs: Long,
)

private data class ActivityCapturedRunScope(
	val logicalTrackingId: String,
	val serviceRunId: String,
)

private class ActivityCapturedRetentionBlockedException(
	val reason: ActivityCapturedRetentionBlockedReason,
) : IllegalStateException(reason.name)

private class ActivityCapturedSourceDeletionBlockedException(
	val reason: ActivityCapturedSourceDeletionBlockedReason,
) : IllegalStateException(reason.name)

private class ActivityCapturedMaintenanceLimitExceeded : IllegalStateException()

private fun block(reason: ActivityCapturedRetentionBlockedReason): Nothing =
	throw ActivityCapturedRetentionBlockedException(reason)

private fun block(reason: ActivityCapturedSourceDeletionBlockedReason): Nothing =
	throw ActivityCapturedSourceDeletionBlockedException(reason)

private const val ACTIVITY_SOURCE = 2
private const val CAPTURE_PURPOSE = SourceBrokerPurpose.SESSION_CAPTURE
private const val WRITER_ID = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID
private const val WRITER_VERSION = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION
private const val FIRST_DELETION_GENERATION = 1L
private const val INSERT_IGNORED = -1L
private const val DELETE_BATCH_SIZE = 128
private const val CURSOR_PAGE_SIZE = 256
private const val REGISTRATION_PLAN_PAGE_SIZE = 128
private const val MAX_SOURCES_PER_PLAN = 12

private val DEFAULT_ACTIVITY_CAPTURED_MAINTENANCE_LIMITS = ActivityCapturedMaintenanceLimits()
