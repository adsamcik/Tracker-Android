package com.adsamcik.tracker.tracker.source.wifi

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceDemandContract
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiBroadcastAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.runtime.toSourceDemandContract
import com.adsamcik.tracker.tracker.source.runtime.wifiProviderDeliveryIdentity
import java.time.DateTimeException
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class WifiCapturedMaintenanceCheckpoint {
	TRANSACTION_STARTED,
	REVISION_PAGE_LOADED,
	WAL_AUTHENTICATED,
	LINEAGE_AUTHENTICATED,
	DELETION_FENCES_INSTALLED,
	PAYLOAD_REMOVED,
}

internal data class WifiCapturedMaintenanceLimits(
	val revisionPageSize: Int = 256,
	val maximumRevisions: Int = 65_536,
	val maximumLogicalFacts: Int = 16_384,
	val maximumCursors: Int = 16_384,
	val maximumDeletionGenerations: Int = 16_384,
	val maximumWalEvents: Int = 65_536,
	val maximumWalPayloadBytes: Int = 1_024 * 1_024,
	val maximumManifestsPerRun: Int = 256,
	val maximumSourcesPerRun: Int = 4_096,
	val maximumSourcesPerManifest: Int = 32,
	val maximumAuthorizationMembers: Int = 64,
	val maximumPlanApplicationRows: Int = 1,
	val maximumDirectDemands: Int = 256,
	val maximumNonterminalRegistrations: Int = 64,
) {
	init {
		require(revisionPageSize in 1..1_024)
		listOf(
			maximumRevisions,
			maximumLogicalFacts,
			maximumCursors,
			maximumDeletionGenerations,
			maximumWalEvents,
			maximumWalPayloadBytes,
			maximumManifestsPerRun,
			maximumSourcesPerRun,
			maximumSourcesPerManifest,
			maximumAuthorizationMembers,
			maximumPlanApplicationRows,
			maximumDirectDemands,
			maximumNonterminalRegistrations,
		).forEach { limit -> require(limit in 1 until Int.MAX_VALUE) }
		require(maximumLogicalFacts <= maximumRevisions)
	}
}

internal enum class WifiCapturedRetentionBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	DESTINATION_OWNER_CHANGED,
	UNRECOGNIZED_PAYLOAD_PRESENT,
	MAINTENANCE_BOUND_EXCEEDED,
	FACT_AUTHORITY_UNVERIFIABLE,
	STALE_REQUEST,
}

internal sealed interface WifiCapturedRetentionResult {
	data class Pruned(
		val logicalFactCount: Int,
		val revisionCount: Int,
	) : WifiCapturedRetentionResult

	data object NoChange : WifiCapturedRetentionResult

	data class Blocked(
		val reason: WifiCapturedRetentionBlockedReason,
	) : WifiCapturedRetentionResult
}

internal enum class WifiCapturedSourceDeletionBlockedReason {
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

internal sealed interface WifiCapturedSourceDeletionResult {
	data class Deleted(
		val logicalFactCount: Int,
		val revisionCount: Int,
		val fencedServiceRunCount: Int,
	) : WifiCapturedSourceDeletionResult

	data object AlreadyDeleted : WifiCapturedSourceDeletionResult

	data class Blocked(
		val reason: WifiCapturedSourceDeletionBlockedReason,
	) : WifiCapturedSourceDeletionResult
}

/**
 * Dormant source-local retention and consent-deletion boundary for identity-free Wi-Fi facts.
 *
 * Provider WAL remains immutable. Retention removes a complete correction/dependency closure only
 * when the observation's wall-time uncertainty crosses the retained floor. Consent deletion first
 * installs both the payload-free global run fence and the Wi-Fi-local generation, so delayed WAL
 * replay cannot recreate a removed fact.
 */
internal class WifiCapturedFactMaintenance @Inject constructor(
	private val database: AppDatabase,
	private val payloadCodec: SourcePayloadCodec,
	private val planCodec: SourcePlanCodec,
) {
	suspend fun pruneAffectedByRetentionFloor(
		beforeMs: Long,
		expectedCollectedDataEpoch: Long,
		expectedDeletedSourceEventHighWaterOrdinal: Long,
		markedAtMs: Long,
	): WifiCapturedRetentionResult = pruneAffectedByRetentionFloor(
		beforeMs,
		expectedCollectedDataEpoch,
		expectedDeletedSourceEventHighWaterOrdinal,
		markedAtMs,
		DEFAULT_LIMITS,
	) { currentCoroutineContext().ensureActive() }

	internal suspend fun pruneAffectedByRetentionFloor(
		beforeMs: Long,
		expectedCollectedDataEpoch: Long,
		expectedDeletedSourceEventHighWaterOrdinal: Long,
		markedAtMs: Long,
		limits: WifiCapturedMaintenanceLimits,
		checkpoint: suspend (WifiCapturedMaintenanceCheckpoint) -> Unit,
	): WifiCapturedRetentionResult {
		require(beforeMs >= 0L)
		require(expectedCollectedDataEpoch >= 0L)
		require(expectedDeletedSourceEventHighWaterOrdinal >= 0L)
		require(markedAtMs >= 0L)
		return try {
			database.withTransaction {
				checkpoint(WifiCapturedMaintenanceCheckpoint.TRANSACTION_STARTED)
				val evidence = database.sourceEvidenceStateDao().get()
				if (evidence == null || evidence.collectedDataEpoch != expectedCollectedDataEpoch ||
					evidence.deletedSourceEventHighWaterOrdinal !=
						expectedDeletedSourceEventHighWaterOrdinal || evidence.retainedFromMs != beforeMs
				) block(WifiCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
				val audit = audit(evidence, limits, checkpoint)
				if (markedAtMs < audit.latestDurableTimeMs) {
					block(WifiCapturedRetentionBlockedReason.STALE_REQUEST)
				}
				val selectedIds = affectedDependencyClosure(audit.lineages, beforeMs)
				if (selectedIds.isEmpty()) return@withTransaction WifiCapturedRetentionResult.NoChange
				val selected = selectedIds.map(audit.lineagesById::getValue)
					.dependencySafeDeletionOrder()
				if (audit.lineages.any { lineage ->
					lineage.logicalFactId !in selectedIds &&
						lineage.aggregateOwnerLogicalFactId in selectedIds
				}) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

				val dao = database.wifiCapturedFactDao()
				var removedRevisions = 0
				selected.chunked(DELETE_BATCH_SIZE).forEach { batch ->
					currentCoroutineContext().ensureActive()
					val ids = batch.map(WifiCapturedLineage::logicalFactId)
					if (dao.deleteExactCursors(WRITER_ID, WRITER_VERSION, ids) != batch.size) {
						block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
					}
					val expected = batch.sumOf { lineage -> lineage.revisions.size }
					val removed = dao.deleteExactRevisionLineages(WRITER_ID, WRITER_VERSION, ids)
					if (removed != expected) {
						block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
					}
					removedRevisions = Math.addExact(removedRevisions, removed)
					checkpoint(WifiCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED)
				}
				check(database.sourceEvidenceStateDao().incrementRevision(markedAtMs) == 1) {
					"Unable to publish captured Wi-Fi retention"
				}
				WifiCapturedRetentionResult.Pruned(selected.size, removedRevisions)
			}
		} catch (blocked: WifiCapturedRetentionBlockedException) {
			WifiCapturedRetentionResult.Blocked(blocked.reason)
		} catch (@Suppress("SwallowedException") _: WifiCapturedMaintenanceLimitExceeded) {
			WifiCapturedRetentionResult.Blocked(
				WifiCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
			)
		} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
			WifiCapturedRetentionResult.Blocked(
				WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
			)
		}
	}

	suspend fun deleteAfterCaptureConsentReset(
		expectedCollectedDataEpoch: Long,
		expectedDeletedSourceEventHighWaterOrdinal: Long,
		expectedRevokedConsentEpoch: Long,
		deletedAtMs: Long,
	): WifiCapturedSourceDeletionResult = deleteAfterCaptureConsentReset(
		expectedCollectedDataEpoch,
		expectedDeletedSourceEventHighWaterOrdinal,
		expectedRevokedConsentEpoch,
		deletedAtMs,
		DEFAULT_LIMITS,
	) { currentCoroutineContext().ensureActive() }

	internal suspend fun deleteAfterCaptureConsentReset(
		expectedCollectedDataEpoch: Long,
		expectedDeletedSourceEventHighWaterOrdinal: Long,
		expectedRevokedConsentEpoch: Long,
		deletedAtMs: Long,
		limits: WifiCapturedMaintenanceLimits,
		checkpoint: suspend (WifiCapturedMaintenanceCheckpoint) -> Unit,
	): WifiCapturedSourceDeletionResult {
		require(expectedCollectedDataEpoch >= 0L)
		require(expectedDeletedSourceEventHighWaterOrdinal >= 0L)
		require(expectedRevokedConsentEpoch >= 0L)
		require(deletedAtMs >= 0L)
		return try {
			database.withTransaction {
				checkpoint(WifiCapturedMaintenanceCheckpoint.TRANSACTION_STARTED)
				val evidence = database.sourceEvidenceStateDao().get()
				if (evidence == null || evidence.collectedDataEpoch != expectedCollectedDataEpoch ||
					evidence.deletedSourceEventHighWaterOrdinal !=
						expectedDeletedSourceEventHighWaterOrdinal
				) block(WifiCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
				val policyAuthority = database.sourcePolicyDao().authority()?.takeIf { authority ->
					authority.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
				} ?: block(WifiCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE)
				val policy = database.sourcePolicyDao().policyAtRevision(
					policyAuthority.currentPolicyRevision,
					WIFI_SOURCE,
				)
				val revokedConsent = database.sourcePolicyDao().latestConsentEpoch(
					WIFI_SOURCE,
					CAPTURE_PURPOSE,
				)
				if (policy == null || revokedConsent == null ||
					revokedConsent.epoch != expectedRevokedConsentEpoch
				) block(WifiCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE)
				if (policy.capturePersistenceEligible || policy.captureConsentEpoch != null ||
					revokedConsent.eligible || revokedConsent.persistenceEligible ||
					revokedConsent.policyRevision != policy.policyRevision ||
					revokedConsent.effectiveBootId != policy.effectiveBootId ||
					revokedConsent.effectiveElapsedRealtimeNanos != policy.effectiveElapsedRealtimeNanos ||
					revokedConsent.effectiveWallTimeMs != policy.effectiveWallTimeMs
				) block(WifiCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE)
				if (deletedAtMs < maxOf(
					policyAuthority.updatedAtMs,
					policy.effectiveWallTimeMs,
					revokedConsent.effectiveWallTimeMs,
					evidence.updatedAtMs,
				)) block(WifiCapturedSourceDeletionBlockedReason.STALE_REQUEST)

				val dao = database.wifiCapturedFactDao()
				val demands = dao.captureDemandsForDeletion(
					WIFI_SOURCE,
					CAPTURE_PURPOSE,
					limits.maximumDirectDemands + 1,
				)
				if (demands.size > limits.maximumDirectDemands) {
					throw WifiCapturedMaintenanceLimitExceeded()
				}
				if (demands.isNotEmpty()) {
					block(WifiCapturedSourceDeletionBlockedReason.CAPTURE_DEMAND_NOT_QUIESCED)
				}
				val registrations = dao.registrationsForDeletion(
					WIFI_SOURCE,
					limits.maximumNonterminalRegistrations + 1,
				)
				if (registrations.size > limits.maximumNonterminalRegistrations) {
					throw WifiCapturedMaintenanceLimitExceeded()
				}
				if (registrations.isNotEmpty()) {
					block(WifiCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED)
				}

				val audit = audit(evidence, limits, checkpoint)
				if (audit.wal.scopes.isNotEmpty() || audit.lineages.isNotEmpty()) {
					val owner = database.sourceDestinationOwnerDao().get(WIFI_SOURCE, DESTINATION)
					if (owner?.owner != OWNER || owner.ownerGeneration != OWNER_GENERATION) {
						block(WifiCapturedSourceDeletionBlockedReason.DESTINATION_OWNER_CHANGED)
					}
				}
				if (deletedAtMs < audit.latestDurableTimeMs) {
					block(WifiCapturedSourceDeletionBlockedReason.STALE_REQUEST)
				}
				val scopes = (audit.lineages.map(WifiCapturedLineage::scope) + audit.wal.scopes).distinct()
				val scopesNeedingFence = scopes.filter { scope ->
					audit.generationByScope[scope] == null
				}
				if (audit.lineages.isEmpty() && scopesNeedingFence.isEmpty()) {
					return@withTransaction WifiCapturedSourceDeletionResult.AlreadyDeleted
				}
				for (scope in scopesNeedingFence) {
					currentCoroutineContext().ensureActive()
					val fence = SourceDeletionFenceEntity.createLogicalServiceRun(
						WIFI_SOURCE,
						CAPTURE_PURPOSE,
						scope.logicalTrackingId,
						scope.serviceRunId,
						FIRST_DELETION_GENERATION,
						expectedCollectedDataEpoch,
						deletedAtMs,
					)
					if (database.sourceDeletionFenceDao().insertIfAbsent(fence) == INSERT_IGNORED) {
						val retained = database.sourceDeletionFenceDao().get(
							fence.sourceKind,
							fence.purpose,
							fence.scopeKind,
							fence.scopeIdentityDigest,
						)
						if (retained != fence) {
							block(WifiCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
						}
					}
					dao.insertDeletionGeneration(
						WifiCaptureDeletionGenerationEntity(
							scope.logicalTrackingId,
							scope.serviceRunId,
							expectedCollectedDataEpoch,
							FIRST_DELETION_GENERATION,
							deletedAtMs,
						),
					)
				}
				checkpoint(WifiCapturedMaintenanceCheckpoint.DELETION_FENCES_INSTALLED)

				val revisionCount = audit.lineages.sumOf { lineage -> lineage.revisions.size }
				audit.lineages.dependencySafeDeletionOrder().chunked(DELETE_BATCH_SIZE).forEach { batch ->
					currentCoroutineContext().ensureActive()
					val ids = batch.map(WifiCapturedLineage::logicalFactId)
					if (dao.deleteExactCursors(WRITER_ID, WRITER_VERSION, ids) != batch.size ||
						dao.deleteExactRevisionLineages(WRITER_ID, WRITER_VERSION, ids) !=
							batch.sumOf { lineage -> lineage.revisions.size }
					) block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				}
				if (dao.revisionCount() != 0L || dao.cursorCount() != 0L) {
					block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				}
				check(database.sourceEvidenceStateDao().incrementRevision(deletedAtMs) == 1) {
					"Unable to publish captured Wi-Fi source deletion"
				}
				checkpoint(WifiCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED)
				WifiCapturedSourceDeletionResult.Deleted(
					audit.lineages.size,
					revisionCount,
					scopesNeedingFence.size,
				)
			}
		} catch (blocked: WifiCapturedSourceDeletionBlockedException) {
			WifiCapturedSourceDeletionResult.Blocked(blocked.reason)
		} catch (blocked: WifiCapturedRetentionBlockedException) {
			WifiCapturedSourceDeletionResult.Blocked(
				when (blocked.reason) {
					WifiCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED ->
						WifiCapturedSourceDeletionBlockedReason.DESTINATION_OWNER_CHANGED
					WifiCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT ->
						WifiCapturedSourceDeletionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT
					WifiCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED ->
						WifiCapturedSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED
					else -> WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE
				},
			)
		} catch (@Suppress("SwallowedException") _: WifiCapturedMaintenanceLimitExceeded) {
			WifiCapturedSourceDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED,
			)
		} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
			WifiCapturedSourceDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE,
			)
		}
	}

	private suspend fun audit(
		evidence: SourceEvidenceState,
		limits: WifiCapturedMaintenanceLimits,
		checkpoint: suspend (WifiCapturedMaintenanceCheckpoint) -> Unit,
	): WifiCapturedAudit {
		val dao = database.wifiCapturedFactDao()
		if (dao.unsupportedRevisionCount(WRITER_ID, WRITER_VERSION) != 0L ||
			dao.unsupportedCursorCount(WRITER_ID, WRITER_VERSION) != 0L
		) block(WifiCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT)

		val lineages = mutableListOf<WifiCapturedLineage>()
		var afterLogicalFactId: String? = null
		var afterSemanticRevision: Long? = null
		var revisionCount = 0
		var current = mutableListOf<WifiCapturedFactRevisionEntity>()
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = dao.maintenanceRevisionPage(
				WRITER_ID,
				WRITER_VERSION,
				afterLogicalFactId,
				afterSemanticRevision,
				limits.revisionPageSize,
			)
			if (page.isEmpty()) break
			revisionCount = Math.addExact(revisionCount, page.size)
			if (revisionCount > limits.maximumRevisions) throw WifiCapturedMaintenanceLimitExceeded()
			checkpoint(WifiCapturedMaintenanceCheckpoint.REVISION_PAGE_LOADED)
			for (revision in page) {
				if (current.isNotEmpty() && current.last().logicalFactId != revision.logicalFactId) {
					lineages += authenticateLineage(current)
					current = mutableListOf()
					if (lineages.size > limits.maximumLogicalFacts) {
						throw WifiCapturedMaintenanceLimitExceeded()
					}
				}
				current += revision
			}
			afterLogicalFactId = page.last().logicalFactId
			afterSemanticRevision = page.last().semanticRevision
			if (page.size < limits.revisionPageSize) break
		}
		if (current.isNotEmpty()) lineages += authenticateLineage(current)
		if (lineages.size > limits.maximumLogicalFacts) throw WifiCapturedMaintenanceLimitExceeded()

		val owner = database.sourceDestinationOwnerDao().get(WIFI_SOURCE, DESTINATION)
		if (lineages.isNotEmpty() &&
			(owner?.owner != OWNER || owner.ownerGeneration != OWNER_GENERATION)
		) block(WifiCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED)

		val cursors = loadCursors(limits)
		val generations = loadDeletionGenerations(limits)
		if (dao.revisionCount() != revisionCount.toLong() ||
			dao.cursorCount() != cursors.size.toLong() ||
			dao.deletionGenerationCount() != generations.size.toLong()
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		if (cursors.size != lineages.size ||
			cursors.keys != lineages.mapTo(mutableSetOf(), WifiCapturedLineage::logicalFactId)
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

		val wal = auditWal(evidence, limits, checkpoint)
		val lineagesById = lineages.associateBy(WifiCapturedLineage::logicalFactId)
		if (lineagesById.size != lineages.size) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val reuseAuthorityById = mutableMapOf<String, WifiAggregateReuseAuthority>()
		for (lineage in lineages) {
			currentCoroutineContext().ensureActive()
			val latest = lineage.revisions.last()
			val cursor = cursors[lineage.logicalFactId]
			if (cursor == null || !cursor.matchesCurrent(latest)) {
				block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			if (latest.collectedDataEpoch != evidence.collectedDataEpoch ||
				latest.sourceAdmissionOrdinal <= evidence.deletedSourceEventHighWaterOrdinal
			) block(WifiCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
			val walAuthority = wal.captureByEventId[latest.sourceEventId]
				?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			if (!latest.matchesAuthenticatedWal(walAuthority)) {
				block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			reuseAuthorityById[lineage.logicalFactId] = walAuthority.reuseAuthority
			checkpoint(WifiCapturedMaintenanceCheckpoint.LINEAGE_AUTHENTICATED)
		}
		authenticateDependencies(lineages, lineagesById, reuseAuthorityById)
		authenticateDeletionGenerations(database, lineages, generations, evidence.collectedDataEpoch)

		return WifiCapturedAudit(
			lineages = lineages,
			lineagesById = lineagesById,
			generationByScope = generations.associateBy { generation ->
				WifiCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
			},
			wal = wal,
			latestDurableTimeMs = maxOf(
				evidence.updatedAtMs,
				owner?.updatedAtMs ?: 0L,
				wal.latestDurableTimeMs,
				lineages.maxOfOrNull { lineage ->
					lineage.revisions.maxOf(WifiCapturedFactRevisionEntity::appliedAtMs)
				} ?: 0L,
				cursors.values.maxOfOrNull(WifiCapturedFactCursorEntity::updatedAtMs) ?: 0L,
				generations.maxOfOrNull(WifiCaptureDeletionGenerationEntity::updatedAtMs) ?: 0L,
			),
		)
	}

	private fun authenticateLineage(
		revisions: List<WifiCapturedFactRevisionEntity>,
	): WifiCapturedLineage {
		if (revisions.isEmpty()) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val first = revisions.first()
		for ((index, revision) in revisions.withIndex()) {
			val expectedRevision = index + 1L
			val previous = revisions.getOrNull(index - 1)
			if (revision.semanticRevision != expectedRevision ||
				revision.supersedesSemanticRevision !=
					expectedRevision.takeIf { it > 1L }?.minus(1L) ||
				revision.logicalFactId != WifiCapturedFactRevisionIntegrity.logicalFactId(
					revision.sourceDeliveryIdentity,
					revision.logicalTrackingId,
					revision.serviceRunId,
					revision.sessionSegmentId,
					revision.manifestRevision,
					revision.collectedDataEpoch,
					revision.scopeDeletionGeneration,
				) || revision.mutationId != WifiCapturedFactRevisionIntegrity.mutationId(
					revision.logicalFactId,
					revision.semanticRevision,
				) || !WifiCapturedFactRevisionIntegrity.hasValidEffectChecksum(revision) ||
				!revision.hasSameCorrectionEffect(first) ||
				(previous != null && !revision.isExactSettlementSuccessorOf(previous))
			) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		return WifiCapturedLineage(
			logicalFactId = first.logicalFactId,
			revisions = revisions,
			earliestPossibleWallTimeMs = first.earliestCoveredWallTimeMs()
				?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE),
			aggregateOwnerLogicalFactId = first.aggregateOwnerLogicalFactId,
			aggregateOwnerSemanticRevision = first.aggregateOwnerSemanticRevision,
			aggregateOwnerCursorRevision = first.aggregateOwnerCursorRevision,
		)
	}

	private suspend fun loadCursors(
		limits: WifiCapturedMaintenanceLimits,
	): Map<String, WifiCapturedFactCursorEntity> {
		val rows = mutableListOf<WifiCapturedFactCursorEntity>()
		var after: String? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = limits.maximumCursors - rows.size
			if (remaining <= 0) {
				if (database.wifiCapturedFactDao().maintenanceCursorPage(
						WRITER_ID,
						WRITER_VERSION,
						after,
						1,
					).isNotEmpty()
				) throw WifiCapturedMaintenanceLimitExceeded()
				break
			}
			val pageSize = minOf(remaining, CURSOR_PAGE_SIZE)
			val page = database.wifiCapturedFactDao().maintenanceCursorPage(
				WRITER_ID,
				WRITER_VERSION,
				after,
				pageSize,
			)
			if (page.isEmpty()) break
			if (page != page.sortedBy(WifiCapturedFactCursorEntity::logicalFactId) ||
				page.distinctBy(WifiCapturedFactCursorEntity::logicalFactId).size != page.size
			) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			rows += page
			after = page.last().logicalFactId
			if (page.size < pageSize) break
		}
		return rows.associateBy(WifiCapturedFactCursorEntity::logicalFactId).also { result ->
			if (result.size != rows.size) {
				block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
		}
	}

	private suspend fun loadDeletionGenerations(
		limits: WifiCapturedMaintenanceLimits,
	): List<WifiCaptureDeletionGenerationEntity> {
		val rows = mutableListOf<WifiCaptureDeletionGenerationEntity>()
		var afterLogicalTrackingId: String? = null
		var afterServiceRunId: String? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = limits.maximumDeletionGenerations - rows.size
			if (remaining <= 0) {
				if (database.wifiCapturedFactDao().maintenanceDeletionGenerationPage(
						afterLogicalTrackingId,
						afterServiceRunId,
						1,
					).isNotEmpty()
				) throw WifiCapturedMaintenanceLimitExceeded()
				break
			}
			val pageSize = minOf(remaining, DELETION_GENERATION_PAGE_SIZE)
			val page = database.wifiCapturedFactDao().maintenanceDeletionGenerationPage(
				afterLogicalTrackingId,
				afterServiceRunId,
				pageSize,
			)
			if (page.isEmpty()) break
			rows += page
			afterLogicalTrackingId = page.last().logicalTrackingId
			afterServiceRunId = page.last().serviceRunId
			if (page.size < pageSize) break
		}
		return rows
	}

	private suspend fun auditWal(
		evidence: SourceEvidenceState,
		limits: WifiCapturedMaintenanceLimits,
		checkpoint: suspend (WifiCapturedMaintenanceCheckpoint) -> Unit,
	): WifiCapturedWalAudit {
		val dao = database.wifiCapturedFactDao()
		val total = dao.maintenanceWalCount(WIFI_SOURCE)
		if (total > limits.maximumWalEvents) throw WifiCapturedMaintenanceLimitExceeded()
		val captureByEventId = linkedMapOf<String, AuthenticatedWifiWal>()
		val scopes = linkedSetOf<WifiCapturedRunScope>()
		var afterAdmissionOrdinal = 0L
		var loaded = 0
		var latestDurableTimeMs = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = limits.maximumWalEvents - loaded
			if (remaining <= 0) break
			val pageSize = minOf(remaining, WAL_PAGE_SIZE)
			val keys = dao.maintenanceWalKeys(WIFI_SOURCE, afterAdmissionOrdinal, pageSize)
			if (keys.isEmpty()) break
			if (keys != keys.sortedBy { key -> key.admissionOrdinal } ||
				keys.any { key -> key.admissionOrdinal <= afterAdmissionOrdinal } ||
				keys.distinctBy { key -> key.admissionOrdinal }.size != keys.size
			) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			for (key in keys) {
				currentCoroutineContext().ensureActive()
				val payloadBytes = dao.maintenanceWalPayloadByteCount(key.eventId)
				if (payloadBytes == null || payloadBytes > limits.maximumWalPayloadBytes ||
					payloadBytes > MAX_CANONICAL_WIFI_PAYLOAD_BYTES
				) block(WifiCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT)
				val wal = database.sourceEventWalDao().getByEventId(key.eventId)
				if (wal == null || wal.sourceKind != WIFI_SOURCE ||
					wal.admissionOrdinal != key.admissionOrdinal || !wal.hasQualifiedIntegrity()
				) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				val payload = decodeCanonicalPayload(wal)
					?: block(WifiCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT)
				if (!wal.hasExactProducerEnvelope() || !payload.hasExactProducerShape(wal) ||
					!wal.hasExactSequenceAndDelivery()
				) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				if (payload.accessPoints.isNotEmpty() &&
					wifiProviderDeliveryIdentity(wal.clockDomainId, payload.accessPoints).value !=
						wal.deliveryIdentity
				) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				latestDurableTimeMs = maxOf(latestDurableTimeMs, wal.createdAtMs)

				val captureEligible = wal.authorizationPurposeEligibilityMask and
					SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L
				if (!captureEligible) {
					if (wal.captureConsentEpoch != null) {
						block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
					}
					checkpoint(WifiCapturedMaintenanceCheckpoint.WAL_AUTHENTICATED)
					continue
				}
				if (wal.capturedCollectedDataEpoch != evidence.collectedDataEpoch ||
					wal.admissionOrdinal <= evidence.deletedSourceEventHighWaterOrdinal
				) {
					// Globally deleted evidence cannot authorize a source-local fence or product row.
					checkpoint(WifiCapturedMaintenanceCheckpoint.WAL_AUTHENTICATED)
					continue
				}
				val authenticated = authenticateCaptureWal(wal, payload, evidence, limits)
				if (captureByEventId.put(wal.eventId, authenticated) != null) {
					block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				}
				scopes += authenticated.scope
				checkpoint(WifiCapturedMaintenanceCheckpoint.WAL_AUTHENTICATED)
			}
			loaded = Math.addExact(loaded, keys.size)
			afterAdmissionOrdinal = keys.last().admissionOrdinal
			if (keys.size < pageSize) break
		}
		if (loaded.toLong() != total) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		return WifiCapturedWalAudit(captureByEventId, scopes, latestDurableTimeMs)
	}

	private fun decodeCanonicalPayload(wal: SourceEventWalEntity): WifiResultSnapshotPayload? {
		if (wal.payloadVersion != WIFI_PAYLOAD_VERSION ||
			wal.payload.size > MAX_CANONICAL_WIFI_PAYLOAD_BYTES
		) return null
		val payload = runCatching {
			payloadCodec.decode(SourceKind.WIFI, wal.payloadVersion, wal.payload)
				as? WifiResultSnapshotPayload
		}.getOrNull() ?: return null
		val encoded = runCatching { payloadCodec.encode(payload, wal.payloadVersion) }.getOrNull()
			?: return null
		return payload.takeIf {
			encoded.bytes.contentEquals(wal.payload) && encoded.checksum == wal.payloadChecksum
		}
	}

	private suspend fun SourceEventWalEntity.hasExactSequenceAndDelivery(): Boolean {
		val sequence = database.sourceEventWalDao().getBySourceSequence(
			WIFI_SOURCE,
			sourceInstanceId,
			sourceSequence,
		) ?: return false
		if (!exactlyMatches(sequence)) return false
		val delivery = database.sourceEventWalDao().deliveryUnitsBounded(
			WIFI_SOURCE,
			capturedCollectedDataEpoch,
			clockDomainId,
			deliveryIdentity ?: return false,
			MAX_EXPECTED_DELIVERY_UNITS + 1,
		)
		return delivery.size <= MAX_EXPECTED_DELIVERY_UNITS &&
			delivery.singleOrNull()?.let(::exactlyMatches) == true
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
	private suspend fun authenticateCaptureWal(
		wal: SourceEventWalEntity,
		payload: WifiResultSnapshotPayload,
		evidence: SourceEvidenceState,
		limits: WifiCapturedMaintenanceLimits,
	): AuthenticatedWifiWal {
		val logicalTrackingId = wal.logicalTrackingId
		val serviceRunId = wal.serviceRunId
		val policyRevision = wal.sourcePolicyRevision
		val consentEpoch = wal.captureConsentEpoch
		val manifestRevision = wal.sessionManifestRevision
		val leaseGeneration = wal.lifecycleLeaseGeneration
		val physicalFingerprint = wal.physicalConfigurationFingerprint
		val authorizationRevision = wal.authorizationRevision
		val authorizationFingerprint = wal.authorizationFingerprint
		val observedStart = wal.observedIntervalStartNanos
		val wallTime = wal.wallTimeMs
		val wallUncertainty = wal.wallTimeUncertaintyMs
		if (logicalTrackingId.isNullOrBlank() || serviceRunId.isNullOrBlank() ||
			policyRevision == null || policyRevision <= 0L || consentEpoch == null || consentEpoch < 0L ||
			manifestRevision == null || manifestRevision <= 0L ||
			leaseGeneration == null || leaseGeneration <= 0L || physicalFingerprint.isNullOrBlank() ||
			authorizationRevision == null || authorizationRevision <= 0L ||
			authorizationFingerprint?.matches(LOWERCASE_SHA_256) != true || observedStart == null ||
			wallTime == null || wallUncertainty == null ||
			wal.activityAutomationEpoch != null || wal.planAttribution != CAPTURED_PLAN_ATTRIBUTION
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

		val sessionDao = database.sourceSessionDao()
		val run = sessionDao.serviceRun(serviceRunId)
		val session = sessionDao.session(logicalTrackingId)
		if (run == null || session == null) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val currentRun = session.currentServiceRunId?.let(sessionDao::serviceRun)
		if (!session.hasValidLifecycleShape(wal.admissionOrdinal) || !run.hasValidLifecycleShape() ||
			!run.hasValidRelationshipTo(session, currentRun)
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		if (run.state in TERMINAL_SESSION_STATES && session.state !in TERMINAL_SESSION_STATES &&
			(currentRun == null || !hasExactCurrentReplacementBundle(
				session,
				currentRun,
				wal.capturedCollectedDataEpoch,
				evidence.collectedDataEpoch,
				limits,
			))
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val sessionEnd = session.cutoffElapsedNanos ?: Long.MAX_VALUE
		if (run.desiredPlanRevision <= 0L || run.logicalTrackingId != logicalTrackingId ||
			run.bootId != wal.clockDomainId || run.leaseGeneration != leaseGeneration ||
			session.clockDomainId != wal.clockDomainId ||
			session.lifecycleLeaseGeneration < leaseGeneration || run.startedElapsedNanos > observedStart ||
			wal.observedElapsedNanos >= sessionEnd
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

		val manifests = sessionDao.manifestsForServiceRun(
			serviceRunId,
			limits.maximumManifestsPerRun + 1,
		)
		if (manifests.size > limits.maximumManifestsPerRun) {
			throw WifiCapturedMaintenanceLimitExceeded()
		}
		if (!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests)) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val manifestIndex = manifests.indexOfFirst { manifest ->
			manifest.manifestRevision == manifestRevision
		}
		if (manifestIndex < 0) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val manifest = manifests[manifestIndex]
		val configurationRevision = manifest.acquisitionPlanRevision
		if (configurationRevision <= 0L ||
			wal.configRevision?.let { it != configurationRevision } == true
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

		val planHeader = database.sourcePlanStateDao().revision(configurationRevision)
		val desiredPlan = database.sourcePlanStateDao().desiredPlan(configurationRevision, WIFI_SOURCE)
		if (planHeader == null || desiredPlan == null || desiredPlan.payload.size > MAX_PLAN_PAYLOAD_BYTES) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val plan = runCatching { planCodec.decode(desiredPlan.payload) as? WifiPlan }.getOrNull()
		val reencodedPlan = plan?.let { runCatching { planCodec.encode(it) }.getOrNull() }
		if (desiredPlan.payloadVersion != PLAN_PAYLOAD_VERSION ||
			desiredPlan.revision != configurationRevision || desiredPlan.sourceKind != WIFI_SOURCE ||
			plan == null || !plan.hasSupportedHistoricalShape() ||
			plan.revision != configurationRevision || reencodedPlan == null ||
			!reencodedPlan.bytes.contentEquals(desiredPlan.payload) ||
			reencodedPlan.checksum != desiredPlan.payloadChecksum ||
			plan.physicalConfigurationFingerprint() != physicalFingerprint ||
			planHeader.sourcePolicyRevision != policyRevision
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

		val brokerDao = database.sourceBrokerDao()
		val registration = brokerDao.registration(WIFI_SOURCE, wal.registrationGeneration)
		val registrationStart = registration?.acceptedElapsedRealtimeNanos
		val registrationEnd = registration?.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
		if (registration == null || registrationStart == null ||
			!registration.hasValidHistoricalShape() ||
			registration.sourceInstanceId != wal.sourceInstanceId ||
			registration.ownerScope != "source-broker:$WIFI_SOURCE" ||
			registration.providerResidency != ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ||
			registration.physicalConfigurationFingerprint != physicalFingerprint ||
			registration.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			registration.clockDomainId != wal.clockDomainId ||
			registration.status !in ACCEPTED_REGISTRATION_STATES ||
			(registration.retiredAtMs == null) != (registration.retiredElapsedRealtimeNanos == null) ||
			observedStart < registrationStart || wal.observedElapsedNanos >= registrationEnd
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

		val authorizationRows = boundedAuthorizationMembers(
			wal.registrationGeneration,
			authorizationRevision,
			limits,
		)
		val authorization = authorizationRows.toSnapshotOrNull()
			?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val startAuthorization = boundedAuthorizationAt(
			wal.registrationGeneration,
			wal.clockDomainId,
			observedStart,
			limits,
		)
		val endAuthorization = boundedAuthorizationAt(
			wal.registrationGeneration,
			wal.clockDomainId,
			wal.observedElapsedNanos,
			limits,
		)
		val nextRows = database.wifiCapturedFactDao().maintenanceNextAuthorizationMembers(
			WIFI_SOURCE,
			wal.registrationGeneration,
			authorizationRevision,
			limits.maximumAuthorizationMembers + 1,
		)
		if (nextRows.size > limits.maximumAuthorizationMembers) {
			throw WifiCapturedMaintenanceLimitExceeded()
		}
		val nextAuthorization = if (nextRows.isEmpty()) null else nextRows.toSnapshotOrNull()
			?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		if (nextAuthorization != null &&
			(nextAuthorization.effectiveBootId != wal.clockDomainId ||
				nextAuthorization.effectiveElapsedRealtimeNanos <
					authorization.effectiveElapsedRealtimeNanos)
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val authorizationEnd = minOf(
			registrationEnd,
			nextAuthorization?.effectiveElapsedRealtimeNanos ?: Long.MAX_VALUE,
		)
		val captureMember = authorization.authorizedMembers.singleOrNull { member ->
			member.purpose == CAPTURE_PURPOSE && member.persistenceEligible &&
				member.logicalTrackingId == logicalTrackingId && member.serviceRunId == serviceRunId &&
				member.manifestRevision == manifestRevision &&
				member.lifecycleLeaseGeneration == leaseGeneration &&
				member.sourcePolicyRevision == policyRevision && member.consentEpoch == consentEpoch
		}
		val demandIds = authorization.authorizedMembers.mapNotNull { member -> member.demandId }
		val demands = brokerDao.demandsByIds(demandIds)
		val recomputedAuthorization = runCatching {
			SourceBrokerAuthorization.rows(
				WIFI_SOURCE,
				wal.registrationGeneration,
				authorizationRevision,
				demands,
				authorization.effectiveBootId,
				authorization.effectiveElapsedRealtimeNanos,
				authorization.members.first().effectiveWallTimeMs,
			)
		}.getOrNull()
		if (authorization.isDenied || captureMember == null || startAuthorization != authorization ||
			endAuthorization != authorization || demandIds.distinct().size != authorization.authorizedMembers.size ||
			demands.size != authorization.authorizedMembers.size ||
			recomputedAuthorization?.sortedBy { row -> row.memberId } !=
				authorization.members.sortedBy { row -> row.memberId } ||
			authorization.authorizationFingerprint != authorizationFingerprint ||
			authorization.purposeEligibilityMask != wal.authorizationPurposeEligibilityMask ||
			authorization.effectiveBootId != wal.clockDomainId ||
			authorization.effectiveElapsedRealtimeNanos > observedStart ||
			wal.observedElapsedNanos >= authorizationEnd
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val contracts = demands.map { demand ->
			runCatching { demand.toSourceDemandContract() }.getOrNull()
				?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		if (contracts.any { contract -> !plan.satisfiesExactWifiContract(contract) }) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}

		val policy = database.sourcePolicyDao().policyAtRevision(policyRevision, WIFI_SOURCE)
		val consent = database.sourcePolicyDao().consentEpoch(WIFI_SOURCE, CAPTURE_PURPOSE, consentEpoch)
		if (policy == null || consent == null || !policy.enabled || !policy.capturePersistenceEligible ||
			policy.captureConsentEpoch != consentEpoch || policy.qosCode != captureMember.qosCode ||
			policy.effectiveBootId != wal.clockDomainId ||
			policy.effectiveElapsedRealtimeNanos > observedStart || !consent.eligible ||
			!consent.persistenceEligible || consent.policyRevision > policyRevision ||
			consent.effectiveBootId != wal.clockDomainId ||
			consent.effectiveElapsedRealtimeNanos > observedStart
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

		val allSources = database.trackingHistoryReadDao().manifestSources(
			listOf(serviceRunId),
			limits.maximumSourcesPerRun + 1,
		)
		if (allSources.size > limits.maximumSourcesPerRun || manifests.any { candidate ->
				!SessionManifestIntegrity.verify(
					candidate,
					allSources.filter { source ->
						source.logicalTrackingId == candidate.logicalTrackingId &&
							source.manifestRevision == candidate.manifestRevision
					},
				)
			}
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val manifestSources = allSources.filter { source ->
			source.logicalTrackingId == logicalTrackingId && source.manifestRevision == manifestRevision
		}
		if (manifestSources.size > limits.maximumSourcesPerManifest || manifestSources.any { source ->
				SourceKind.entries.none { kind -> kind.stableCode == source.sourceKind } ||
					source.purpose !in SessionManifestPurposeCode.ALL
			}
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val wifiBinding = manifestSources.singleOrNull { source ->
			source.sourceKind == WIFI_SOURCE && source.purpose == CAPTURE_PURPOSE &&
				source.persistenceEligible
		}
		val capturedSources = manifestSources.filter { source ->
			source.purpose == CAPTURE_PURPOSE && source.persistenceEligible
		}.mapNotNullTo(linkedSetOf()) { source ->
			SourceKind.entries.singleOrNull { kind -> kind.stableCode == source.sourceKind }
		}
		val controlSources = manifestSources.filter { source ->
			source.purpose == SessionManifestPurposeCode.CONTROL
		}.mapNotNullTo(linkedSetOf()) { source ->
			SourceKind.entries.singleOrNull { kind -> kind.stableCode == source.sourceKind }
		}
		val manifestEnd = manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos
			?: minOf(sessionEnd, registrationEnd, authorizationEnd)
		val segmentId = run.sessionSegmentId
		val segment = segmentId?.let { database.sessionSegmentDao().getById(it) }
		if (wifiBinding == null || !wifiBinding.isExactWifiWriter(consentEpoch) ||
			manifest.logicalTrackingId != logicalTrackingId || manifest.serviceRunId != serviceRunId ||
			manifest.sourcePolicyRevision != policyRevision ||
			manifest.acquisitionPlanRevision != configurationRevision ||
			manifest.effectiveBootId != wal.clockDomainId ||
			manifest.effectiveElapsedRealtimeNanos > observedStart ||
			wal.observedElapsedNanos >= manifestEnd || policy.qosCode != wifiBinding.qosCode ||
			SourceKind.WIFI !in capturedSources || SourceKind.WIFI in controlSources ||
			segment == null || segment.logicalTrackingId != logicalTrackingId ||
			segment.serviceRunId != serviceRunId || !hasValidZone(manifest.zoneId)
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

		val appliedAtElapsedNanos = maxOf(registrationStart, authorization.effectiveElapsedRealtimeNanos)
		val actions = sessionDao.sourceStartActionsForManifestBounded(
			logicalTrackingId,
			serviceRunId,
			manifestRevision,
			WIFI_SOURCE,
			limits.maximumPlanApplicationRows + 1,
		)
		val action = actions.singleOrNull()
		if (actions.size > limits.maximumPlanApplicationRows ||
			action?.authenticates(
				wal,
				run,
				manifest,
				configurationRevision,
				appliedAtElapsedNanos,
			) != true || requireNotNull(registration.acceptedAtMs) > requireNotNull(action.acknowledgedAtMs)
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

		val temporalAuthority = runCatching {
			WifiCaptureTemporalAuthority(
				WifiProviderTimeInterval(registrationStart, registrationEnd),
				WifiProviderTimeInterval(authorization.effectiveElapsedRealtimeNanos, authorizationEnd),
				WifiProviderTimeInterval(manifest.effectiveElapsedRealtimeNanos, manifestEnd),
			)
		}.getOrNull() ?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		if (!temporalAuthority.contains(observedStart) ||
			!temporalAuthority.contains(wal.observedElapsedNanos)
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

		val authority = WifiCaptureAuthority(
			logicalTrackingId = LogicalTrackingId(logicalTrackingId),
			serviceRunId = ServiceRunId(serviceRunId),
			sessionSegmentId = segment.id,
			capturedSources = capturedSources,
			controlSources = controlSources,
			sourceInstanceId = SourceInstanceId(wal.sourceInstanceId),
			registrationGeneration = wal.registrationGeneration,
			configurationRevision = configurationRevision,
			physicalConfigurationFingerprint = physicalFingerprint,
			authorizationRevision = authorizationRevision,
			authorizationFingerprint = authorizationFingerprint,
			purposeEligibilityMask = wal.authorizationPurposeEligibilityMask,
			sourcePolicyRevision = policyRevision,
			captureConsentEpoch = consentEpoch,
			sessionManifestRevision = manifestRevision,
			lifecycleLeaseGeneration = leaseGeneration,
			collectedDataEpoch = wal.capturedCollectedDataEpoch,
			clockDomainId = wal.clockDomainId,
			zoneId = manifest.zoneId,
			temporalAuthority = temporalAuthority,
			acquisitionConfiguration = WifiHistoricalAcquisitionConfiguration(
				plan.maximumObservationAgeNanos(),
				WifiIdentityFreeResultContract.ANDROID_SCAN_RESULTS_V1,
			),
			serializedAcquisitionPlan = WifiSerializedAcquisitionPlanEvidence(
				desiredPlan.payloadVersion,
				desiredPlan.payload.toList(),
				desiredPlan.payloadChecksum,
			),
			appliedRegistration = WifiAppliedRegistrationEvidence(
				configurationRevision,
				configurationRevision,
				SourceInstanceId(wal.sourceInstanceId),
				wal.registrationGeneration,
				appliedAtElapsedNanos,
				physicalFingerprint,
				wal.clockDomainId,
				wal.capturedCollectedDataEpoch,
			),
			scopeDeletionGeneration = 0L,
		)
		val walEvidence = WifiWalObservationEvidence(
			sourceEventId = SourceEventId(wal.eventId),
			sourceAdmissionOrdinal = wal.admissionOrdinal,
			walIntegrityIdentity = wal.integrityIdentity,
			providerDedupKey = wal.providerDedupKey,
			sourceDeliveryIdentity = SourceDeliveryIdentity(requireNotNull(wal.deliveryIdentity)),
			deliveryUnitIndex = requireNotNull(wal.deliveryUnitIndex),
			deliveryUnitCount = requireNotNull(wal.deliveryUnitCount),
			capturedAuthority = authority,
			logicalTrackingId = authority.logicalTrackingId,
			serviceRunId = authority.serviceRunId,
			sourceInstanceId = authority.sourceInstanceId,
			registrationGeneration = wal.registrationGeneration,
			configurationRevision = wal.configRevision,
			physicalConfigurationFingerprint = physicalFingerprint,
			authorizationRevision = authorizationRevision,
			authorizationFingerprint = authorizationFingerprint,
			purposeEligibilityMask = wal.authorizationPurposeEligibilityMask,
			sourceSequence = wal.sourceSequence,
			sourcePolicyRevision = policyRevision,
			captureConsentEpoch = consentEpoch,
			sessionManifestRevision = manifestRevision,
			lifecycleLeaseGeneration = leaseGeneration,
			capturedCollectedDataEpoch = wal.capturedCollectedDataEpoch,
			activityAutomationEpoch = wal.activityAutomationEpoch,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clock = WifiDurableClockEvidence(
				wal.clockDomainId,
				observedStart,
				wal.observedElapsedNanos,
				wal.receivedElapsedNanos,
				wallTime,
				wallUncertainty,
			),
			acquiredAtMs = wal.acquiredAtMs,
			qualityFlags = wal.qualityFlags,
			qualityConfidence = wal.qualityConfidence,
			payloadVersion = wal.payloadVersion,
			payloadBytes = wal.payload.toList(),
			payloadChecksum = wal.payloadChecksum,
		)
		val classified = WifiCapturedFactClassifier.classify(
			WifiObservationInput(
				origin = WifiObservationOrigin.PROVIDER_RESULTS_CALLBACK,
				outcome = WifiProviderOutcome.RESULTS_UPDATED,
				walEvidence = walEvidence,
			),
			authority,
			WifiDeletionAuthority(evidence.collectedDataEpoch, null, 0L),
		)
		val derivedAggregate = when (classified) {
			is WifiCapturedFactClassification.FreshChanged -> classified.fact
			is WifiCapturedFactClassification.Absent,
			is WifiCapturedFactClassification.Stale,
			is WifiCapturedFactClassification.Failed,
			is WifiCapturedFactClassification.PermissionLimited,
			is WifiCapturedFactClassification.OsThrottled,
			is WifiCapturedFactClassification.ClockUnverifiable,
			-> null
			else -> block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		return AuthenticatedWifiWal(
			scope = WifiCapturedRunScope(logicalTrackingId, serviceRunId),
			authority = authority,
			evidence = walEvidence,
			derivedAggregate = derivedAggregate,
		)
	}

	private suspend fun boundedAuthorizationMembers(
		registrationGeneration: Long,
		authorizationRevision: Long,
		limits: WifiCapturedMaintenanceLimits,
	): List<com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity> =
		database.wifiCapturedFactDao().maintenanceAuthorizationMembers(
			WIFI_SOURCE,
			registrationGeneration,
			authorizationRevision,
			limits.maximumAuthorizationMembers + 1,
		).also { rows ->
			if (rows.size > limits.maximumAuthorizationMembers) {
				throw WifiCapturedMaintenanceLimitExceeded()
			}
		}

	private suspend fun boundedAuthorizationAt(
		registrationGeneration: Long,
		bootId: String,
		observedElapsedRealtimeNanos: Long,
		limits: WifiCapturedMaintenanceLimits,
	): SourceAuthorizationSnapshot = database.wifiCapturedFactDao().maintenanceAuthorizationAt(
		WIFI_SOURCE,
		registrationGeneration,
		bootId,
		observedElapsedRealtimeNanos,
		limits.maximumAuthorizationMembers + 1,
	).also { rows ->
		if (rows.size > limits.maximumAuthorizationMembers) {
			throw WifiCapturedMaintenanceLimitExceeded()
		}
	}.toSnapshotOrNull() ?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

	/** Authenticates the complete live replacement that keeps an older retired run attributable. */
	@Suppress("LongMethod", "CyclomaticComplexMethod")
	private suspend fun hasExactCurrentReplacementBundle(
		session: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
		expectedCollectedDataEpoch: Long,
		currentCollectedDataEpoch: Long,
		limits: WifiCapturedMaintenanceLimits,
	): Boolean {
		if (expectedCollectedDataEpoch < 0L || currentCollectedDataEpoch != expectedCollectedDataEpoch ||
			!run.hasValidCurrentRelationshipTo(session) || session.currentIntentRevision?.let { it > 0L } != true ||
			run.preparedIntentRevision != session.currentIntentRevision ||
			run.rolloutRevision != session.rolloutRevision || run.runtimeAcknowledgement != "START_ACCEPTED" ||
			run.runtimeFailureCode != null ||
			run.presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_PENDING ||
			run.presentationAcknowledgedAtMs != null
		) return false
		val sessionDao = database.sourceSessionDao()
		val manifests = sessionDao.manifestsForServiceRun(
			run.serviceRunId,
			limits.maximumManifestsPerRun + 1,
		)
		if (manifests.size > limits.maximumManifestsPerRun ||
			!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests)
		) return false
		val currentRevision = session.currentManifestRevision ?: return false
		val manifest = manifests.lastOrNull()?.takeIf { candidate ->
			candidate.manifestRevision == currentRevision
		} ?: return false
		if (manifest.sessionMode != session.sessionMode ||
			manifest.acquisitionPlanRevision != session.desiredPlanRevision ||
			manifest.effectiveBootId != session.lifecycleBootId
		) return false
		val allSources = database.trackingHistoryReadDao().manifestSources(
			listOf(run.serviceRunId),
			limits.maximumSourcesPerRun + 1,
		)
		if (allSources.size > limits.maximumSourcesPerRun || manifests.any { candidate ->
				!SessionManifestIntegrity.verify(candidate, allSources.filter { source ->
					source.logicalTrackingId == candidate.logicalTrackingId &&
						source.manifestRevision == candidate.manifestRevision
				})
			}
		) return false
		val manifestSources = allSources.filter { source ->
			source.logicalTrackingId == session.logicalTrackingId &&
				source.manifestRevision == manifest.manifestRevision
		}
		if (manifestSources.size > limits.maximumSourcesPerManifest || manifestSources.any { source ->
				SourceKind.entries.none { kind -> kind.stableCode == source.sourceKind } ||
					source.purpose !in SessionManifestPurposeCode.ALL
			}
		) return false
		val wifiSource = manifestSources.singleOrNull { source ->
			source.sourceKind == WIFI_SOURCE && source.purpose == CAPTURE_PURPOSE &&
				source.persistenceEligible
		} ?: return false

		val planHeader = database.sourcePlanStateDao().revision(manifest.acquisitionPlanRevision)
			?: return false
		val desiredPlan = database.sourcePlanStateDao().desiredPlan(
			manifest.acquisitionPlanRevision,
			WIFI_SOURCE,
		) ?: return false
		if (desiredPlan.payload.size > MAX_PLAN_PAYLOAD_BYTES) return false
		val plan = runCatching { planCodec.decode(desiredPlan.payload) as? WifiPlan }.getOrNull()
			?: return false
		val reencoded = runCatching { planCodec.encode(plan) }.getOrNull() ?: return false
		if (planHeader.sourcePolicyRevision != manifest.sourcePolicyRevision ||
			desiredPlan.payloadVersion != PLAN_PAYLOAD_VERSION ||
			desiredPlan.revision != manifest.acquisitionPlanRevision ||
			desiredPlan.sourceKind != WIFI_SOURCE || plan.revision != manifest.acquisitionPlanRevision ||
			!plan.hasSupportedHistoricalShape() || !reencoded.bytes.contentEquals(desiredPlan.payload) ||
			reencoded.checksum != desiredPlan.payloadChecksum
		) return false

		val policy = database.sourcePolicyDao().policyAtRevision(manifest.sourcePolicyRevision, WIFI_SOURCE)
			?: return false
		val consent = database.sourcePolicyDao().consentEpoch(
			WIFI_SOURCE,
			CAPTURE_PURPOSE,
			wifiSource.consentEpoch,
		) ?: return false
		if (!policy.enabled || !policy.capturePersistenceEligible ||
			policy.captureConsentEpoch != wifiSource.consentEpoch || policy.qosCode != wifiSource.qosCode ||
			policy.effectiveBootId != manifest.effectiveBootId ||
			policy.effectiveElapsedRealtimeNanos > manifest.effectiveElapsedRealtimeNanos ||
			!consent.eligible || !consent.persistenceEligible ||
			consent.policyRevision > policy.policyRevision || consent.effectiveBootId != manifest.effectiveBootId ||
			consent.effectiveElapsedRealtimeNanos > manifest.effectiveElapsedRealtimeNanos
		) return false

		val segmentId = run.sessionSegmentId ?: return false
		val segment = database.sessionSegmentDao().getById(segmentId) ?: return false
		if (segment.logicalTrackingId != session.logicalTrackingId ||
			segment.serviceRunId != run.serviceRunId
		) return false
		val actions = sessionDao.sourceStartActionsForManifestBounded(
			session.logicalTrackingId,
			run.serviceRunId,
			manifest.manifestRevision,
			WIFI_SOURCE,
			limits.maximumPlanApplicationRows + 1,
		)
		if (actions.size > limits.maximumPlanApplicationRows) return false
		val action = actions.singleOrNull()?.takeIf { candidate ->
			candidate.authenticatesCurrentReplacement(session, run, manifest, wifiSource)
		} ?: return false
		val generation = action.registrationGeneration ?: return false
		val registration = database.sourceBrokerDao().registration(WIFI_SOURCE, generation) ?: return false
		val acceptedWall = registration.acceptedAtMs ?: return false
		val acceptedElapsed = registration.acceptedElapsedRealtimeNanos ?: return false
		val acknowledgedWall = action.acknowledgedAtMs ?: return false
		val acknowledgedElapsed = action.acknowledgedElapsedRealtimeNanos ?: return false
		val validStatus = when (registration.status) {
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE ->
				registration.retiredAtMs == null && registration.retiredElapsedRealtimeNanos == null &&
					registration.failureCode == null && run.state in ADMISSION_RUN_STATES
			ProviderRegistrationGenerationEntity.STATUS_RETIRING ->
				registration.retiredAtMs != null && registration.retiredElapsedRealtimeNanos != null &&
					!registration.failureCode.isNullOrBlank() && run.state == "STOPPING" &&
					registration.retiredAtMs >= acknowledgedWall &&
					registration.retiredElapsedRealtimeNanos >= acknowledgedElapsed
			else -> false
		}
		return validStatus && registration.sourceInstanceId == action.sourceInstanceId &&
			registration.ownerScope == "source-broker:$WIFI_SOURCE" &&
			registration.clockDomainId == run.bootId &&
			registration.providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
			registration.collectedDataEpoch == expectedCollectedDataEpoch &&
			registration.physicalConfigurationFingerprint == plan.physicalConfigurationFingerprint() &&
			registration.reservedAtMs >= manifest.effectiveWallTimeMs &&
			registration.reservedElapsedRealtimeNanos >= manifest.effectiveElapsedRealtimeNanos &&
			registration.reservedAtMs <= acceptedWall &&
			registration.reservedElapsedRealtimeNanos <= acceptedElapsed &&
			acceptedWall <= acknowledgedWall && acceptedElapsed <= acknowledgedElapsed &&
			registration.retiredAtMs?.let { it >= acceptedWall } != false &&
			registration.retiredElapsedRealtimeNanos?.let { it >= acceptedElapsed } != false
	}

	private fun List<com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity>
		.toSnapshotOrNull(): SourceAuthorizationSnapshot? =
		runCatching { toAuthorizationSnapshotOrNull() }.getOrNull()
}

private fun authenticateDependencies(
	lineages: List<WifiCapturedLineage>,
	lineagesById: Map<String, WifiCapturedLineage>,
	reuseAuthorityById: Map<String, WifiAggregateReuseAuthority>,
) {
	for (lineage in lineages) {
		val ownerId = lineage.aggregateOwnerLogicalFactId ?: continue
		val ownerRevision = lineage.aggregateOwnerSemanticRevision
			?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val ownerCursorRevision = lineage.aggregateOwnerCursorRevision
			?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val owner = lineagesById[ownerId]
			?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val referenced = owner.revisions.singleOrNull { revision ->
			revision.semanticRevision == ownerRevision
		}
		val dependent = lineage.revisions.last()
		val ownerAuthority = reuseAuthorityById[owner.logicalFactId]
		val dependentAuthority = reuseAuthorityById[lineage.logicalFactId]
		if (referenced == null || owner.revisions.last() != referenced ||
			ownerCursorRevision != referenced.semanticRevision ||
			owner.revisions.any { revision ->
				revision.factKind != WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE
			} || dependent.factKind != WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY ||
			ownerAuthority == null || dependentAuthority == null || ownerAuthority != dependentAuthority ||
			!referenced.hasFiniteAggregateReuseAuthority() ||
			dependent.acceptedResultCount != referenced.observationCount
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun authenticateDeletionGenerations(
	database: AppDatabase,
	lineages: List<WifiCapturedLineage>,
	generations: List<WifiCaptureDeletionGenerationEntity>,
	collectedDataEpoch: Long,
) {
	val scopesWithFacts = lineages.groupBy(WifiCapturedLineage::scope)
	val generationByScope = generations.associateBy { generation ->
		WifiCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
	}
	if (generationByScope.size != generations.size) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	for ((scope, scopedLineages) in scopesWithFacts) {
		val declared = scopedLineages.map { lineage ->
			lineage.revisions.first().scopeDeletionGeneration
		}.distinct()
		if (declared != listOf(0L) || generationByScope[scope] != null) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			WIFI_SOURCE,
			CAPTURE_PURPOSE,
			scope.logicalTrackingId,
			scope.serviceRunId,
		)
		if (database.sourceDeletionFenceDao().contains(
			WIFI_SOURCE,
			CAPTURE_PURPOSE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			digest,
		)) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	for (generation in generations) {
		val scope = WifiCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
		if (generation.collectedDataEpoch != collectedDataEpoch || scope in scopesWithFacts ||
			generation.generation != FIRST_DELETION_GENERATION
		) block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val expectedFence = SourceDeletionFenceEntity.createLogicalServiceRun(
			WIFI_SOURCE,
			CAPTURE_PURPOSE,
			generation.logicalTrackingId,
			generation.serviceRunId,
			generation.generation,
			generation.collectedDataEpoch,
			generation.updatedAtMs,
		)
		val retained = database.sourceDeletionFenceDao().get(
			expectedFence.sourceKind,
			expectedFence.purpose,
			expectedFence.scopeKind,
			expectedFence.scopeIdentityDigest,
		)
		if (retained != expectedFence) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
}

private fun affectedDependencyClosure(
	lineages: List<WifiCapturedLineage>,
	beforeMs: Long,
): Set<String> {
	val selected = lineages.filter { lineage ->
		lineage.earliestPossibleWallTimeMs < beforeMs
	}.mapTo(linkedSetOf(), WifiCapturedLineage::logicalFactId)
	if (selected.isEmpty()) return emptySet()
	val dependentsByOwner = lineages.filter { lineage ->
		lineage.aggregateOwnerLogicalFactId != null
	}.groupBy(WifiCapturedLineage::aggregateOwnerLogicalFactId)
	var changed: Boolean
	do {
		changed = false
		for (ownerId in selected.toList()) {
			for (dependent in dependentsByOwner[ownerId].orEmpty()) {
				if (selected.add(dependent.logicalFactId)) changed = true
			}
		}
	} while (changed)
	return selected
}

/** Foreign-key-safe bounded order: remove one-hop coverage dependents before their owner. */
private fun List<WifiCapturedLineage>.dependencySafeDeletionOrder(): List<WifiCapturedLineage> =
	sortedWith(compareBy<WifiCapturedLineage> { it.aggregateOwnerLogicalFactId == null }
		.thenBy(WifiCapturedLineage::logicalFactId))

private fun WifiCapturedFactRevisionEntity.matchesAuthenticatedWal(
	authenticated: AuthenticatedWifiWal,
): Boolean {
	val authority = authenticated.authority
	val evidence = authenticated.evidence
	val derived = authenticated.derivedAggregate ?: return false
	val coverage = derived.coverage
	val aggregate = derived.aggregate
	val immutableMatches = logicalTrackingId == authority.logicalTrackingId.value &&
		serviceRunId == authority.serviceRunId.value && sessionSegmentId == authority.sessionSegmentId &&
		capturedSourceCodes == authority.capturedSources.toStableCodes() &&
		controlSourceCodes == authority.controlSources.toStableCodes() &&
		sourceEventId == evidence.sourceEventId.value &&
		sourceAdmissionOrdinal == evidence.sourceAdmissionOrdinal &&
		walIntegrityIdentity == evidence.walIntegrityIdentity && payloadChecksum == evidence.payloadChecksum &&
		sourceDeliveryIdentity == evidence.sourceDeliveryIdentity.value &&
		deliveryUnitIndex == evidence.deliveryUnitIndex && deliveryUnitCount == evidence.deliveryUnitCount &&
		sourceSequence == evidence.sourceSequence && planAttribution == evidence.planAttribution.name &&
		sourceInstanceId == authority.sourceInstanceId.value &&
		registrationGeneration == authority.registrationGeneration &&
		configurationRevision == authority.configurationRevision &&
		physicalConfigurationFingerprint == authority.physicalConfigurationFingerprint &&
		authorizationRevision == authority.authorizationRevision &&
		authorizationFingerprint == authority.authorizationFingerprint &&
		purposeEligibilityMask == authority.purposeEligibilityMask &&
		sourcePolicyRevision == authority.sourcePolicyRevision &&
		captureConsentEpoch == authority.captureConsentEpoch && manifestRevision == authority.sessionManifestRevision &&
		lifecycleLeaseGeneration == authority.lifecycleLeaseGeneration &&
		collectedDataEpoch == authority.collectedDataEpoch && scopeDeletionGeneration == 0L &&
		clockDomainId == authority.clockDomainId && storedZoneId == authority.zoneId &&
		planPayloadVersion == authority.serializedAcquisitionPlan.payloadVersion &&
		planPayloadChecksum == authority.serializedAcquisitionPlan.payloadChecksum &&
		maximumObservationAgeNanos == authority.acquisitionConfiguration.maximumObservationAgeNanos &&
		resultContract == authority.acquisitionConfiguration.resultContract.name &&
		registrationAppliedAtNanos == authority.appliedRegistration.appliedAtElapsedRealtimeNanos &&
		providerAcceptanceStartNanos == authority.temporalAuthority.providerAcceptance.startInclusiveNanos &&
		providerAcceptanceEndNanos == authority.temporalAuthority.providerAcceptance.endExclusiveNanos &&
		authorizationEffectStartNanos == authority.temporalAuthority.authorizationEffect.startInclusiveNanos &&
		authorizationEffectEndNanos == authority.temporalAuthority.authorizationEffect.endExclusiveNanos &&
		sessionRunEffectStartNanos == authority.temporalAuthority.sessionRunEffect.startInclusiveNanos &&
		sessionRunEffectEndNanos == authority.temporalAuthority.sessionRunEffect.endExclusiveNanos &&
		observedIntervalStartNanos == evidence.observedIntervalStartElapsedRealtimeNanos &&
		observedElapsedNanos == evidence.observedElapsedRealtimeNanos &&
		receivedElapsedNanos == evidence.receivedElapsedRealtimeNanos &&
		coverageIntervalStartNanos == coverage.providerIntervalStartElapsedRealtimeNanos &&
		coverageIntervalEndNanos == coverage.providerIntervalEndElapsedRealtimeNanos &&
		observedWallTimeMs == derived.observedWallTimeMs &&
		wallTimeUncertaintyMs == derived.wallTimeUncertaintyMs && acquiredAtMs == evidence.acquiredAtMs &&
		qualityFlags == evidence.qualityFlags && qualityConfidence == evidence.qualityConfidence &&
		availability == derived.availability.name && submittedResultCount == coverage.submittedResultCount &&
		acceptedResultCount == coverage.acceptedResultCount && staleResultCount == coverage.staleResultCount &&
		clockUnverifiableResultCount == coverage.clockUnverifiableResultCount &&
		malformedResultCount == coverage.malformedResultCount &&
		coverageCompleteness == coverage.completeness.name && appliedAtMs == derived.observedWallTimeMs
	if (!immutableMatches) return false
	return when (factKind) {
		WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE ->
			aggregateOwnerLogicalFactId == null && aggregateOwnerSemanticRevision == null &&
				aggregateOwnerCursorRevision == null && observationCount == aggregate.observationCount &&
				twoPointFourGhzCount == aggregate.bandMix.twoPointFourGhzCount &&
				fiveGhzCount == aggregate.bandMix.fiveGhzCount && sixGhzCount == aggregate.bandMix.sixGhzCount &&
				otherBandCount == aggregate.bandMix.otherCount &&
				strongestSignalDbm == aggregate.signalQuality?.strongestSignalLevelDbm &&
				weakestSignalDbm == aggregate.signalQuality?.weakestSignalLevelDbm &&
				signalSumDbm == aggregate.signalQuality?.signalLevelSumDbm
		WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY ->
			aggregateOwnerLogicalFactId != null && aggregateOwnerSemanticRevision != null &&
				aggregateOwnerCursorRevision != null && observationCount == null &&
				twoPointFourGhzCount == null && fiveGhzCount == null && sixGhzCount == null &&
				otherBandCount == null && strongestSignalDbm == null && weakestSignalDbm == null &&
				signalSumDbm == null
		else -> false
	}
}

private fun WifiCapturedFactCursorEntity.matchesCurrent(
	revision: WifiCapturedFactRevisionEntity,
): Boolean = writerProjectionId == revision.writerProjectionId &&
	writerProjectionVersion == revision.writerProjectionVersion && logicalFactId == revision.logicalFactId &&
	logicalTrackingId == revision.logicalTrackingId && serviceRunId == revision.serviceRunId &&
	sessionSegmentId == revision.sessionSegmentId && writerOwnerGeneration == revision.writerOwnerGeneration &&
	collectedDataEpoch == revision.collectedDataEpoch &&
	scopeDeletionGeneration == revision.scopeDeletionGeneration &&
	latestSemanticRevision == revision.semanticRevision && latestMutationId == revision.mutationId &&
	latestEffectChecksum == revision.effectChecksum &&
	latestSourceAdmissionOrdinal == revision.sourceAdmissionOrdinal &&
	cursorRevision == revision.semanticRevision && updatedAtMs == revision.appliedAtMs

private fun WifiCapturedFactRevisionEntity.isExactSettlementSuccessorOf(
	previous: WifiCapturedFactRevisionEntity,
): Boolean = providerAcceptanceEndNanos.isExactSettlementOf(previous.providerAcceptanceEndNanos) &&
	authorizationEffectEndNanos.isExactSettlementOf(previous.authorizationEffectEndNanos) &&
	sessionRunEffectEndNanos.isExactSettlementOf(previous.sessionRunEffectEndNanos)

private fun WifiCapturedFactRevisionEntity.hasFiniteAggregateReuseAuthority(): Boolean =
	providerAcceptanceEndNanos != Long.MAX_VALUE &&
		authorizationEffectEndNanos != Long.MAX_VALUE && sessionRunEffectEndNanos != Long.MAX_VALUE

private fun WifiCapturedFactRevisionEntity.hasSameCorrectionEffect(
	other: WifiCapturedFactRevisionEntity,
): Boolean = stableCorrectionParts() == other.stableCorrectionParts()

@Suppress("LongMethod")
private fun WifiCapturedFactRevisionEntity.stableCorrectionParts(): List<Any?> = listOf(
	writerProjectionId,
	writerProjectionVersion,
	writerBindingGeneration,
	writerOwnerGeneration,
	logicalFactId,
	factKind,
	aggregateOwnerLogicalFactId,
	aggregateOwnerSemanticRevision,
	aggregateOwnerCursorRevision,
	logicalTrackingId,
	serviceRunId,
	sessionSegmentId,
	purpose,
	capturedSourceCodes,
	controlSourceCodes,
	sourceEventId,
	sourceAdmissionOrdinal,
	walIntegrityIdentity,
	payloadChecksum,
	sourceDeliveryIdentity,
	deliveryUnitIndex,
	deliveryUnitCount,
	sourceSequence,
	planAttribution,
	sourceInstanceId,
	registrationGeneration,
	configurationRevision,
	physicalConfigurationFingerprint,
	authorizationRevision,
	authorizationFingerprint,
	purposeEligibilityMask,
	sourcePolicyRevision,
	captureConsentEpoch,
	manifestRevision,
	lifecycleLeaseGeneration,
	collectedDataEpoch,
	scopeDeletionGeneration,
	clockDomainId,
	storedZoneId,
	planPayloadVersion,
	planPayloadChecksum,
	maximumObservationAgeNanos,
	resultContract,
	registrationAppliedAtNanos,
	providerAcceptanceStartNanos,
	authorizationEffectStartNanos,
	sessionRunEffectStartNanos,
	observedIntervalStartNanos,
	observedElapsedNanos,
	receivedElapsedNanos,
	coverageIntervalStartNanos,
	coverageIntervalEndNanos,
	observedWallTimeMs,
	wallTimeUncertaintyMs,
	acquiredAtMs,
	qualityFlags,
	qualityConfidence,
	availability,
	submittedResultCount,
	acceptedResultCount,
	staleResultCount,
	clockUnverifiableResultCount,
	malformedResultCount,
	coverageCompleteness,
	observationCount,
	twoPointFourGhzCount,
	fiveGhzCount,
	sixGhzCount,
	otherBandCount,
	strongestSignalDbm,
	weakestSignalDbm,
	signalSumDbm,
)

private fun WifiCapturedFactRevisionEntity.earliestCoveredWallTimeMs(): Long? = runCatching {
	val elapsedSpanNanos = Math.subtractExact(observedElapsedNanos, coverageIntervalStartNanos)
	val spanMs = elapsedSpanNanos / NANOS_PER_MILLISECOND
	val roundingMs = if (elapsedSpanNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L
	Math.subtractExact(
		Math.subtractExact(observedWallTimeMs, spanMs),
		Math.addExact(wallTimeUncertaintyMs, roundingMs),
	).takeIf { it >= 0L }
}.getOrNull()

private fun SourceEventWalEntity.hasExactProducerEnvelope(): Boolean =
	admissionOrdinal > 0L && providerDedupKey == null && deliveryIdentity != null &&
		deliveryUnitIndex == 0 && deliveryUnitCount == 1 && sourceSequence > 0L &&
		planAttribution == CAPTURED_PLAN_ATTRIBUTION && activityAutomationEpoch == null &&
		observedIntervalStartNanos != null && requireNotNull(observedIntervalStartNanos) > 0L &&
		observedElapsedNanos >= requireNotNull(observedIntervalStartNanos) &&
		receivedElapsedNanos >= observedElapsedNanos && wallTimeMs != null &&
		requireNotNull(wallTimeMs) >= 0L && wallTimeUncertaintyMs == PRODUCER_WALL_UNCERTAINTY_MS &&
		acquiredAtMs == wallTimeMs && createdAtMs >= 0L && qualityFlags == 0L &&
		qualityConfidence == null

private fun WifiResultSnapshotPayload.hasExactProducerShape(wal: SourceEventWalEntity): Boolean {
	if (accessPoints.size > WifiIdentityFreeResultContract.ANDROID_SCAN_RESULTS_V1.maximumAccessPointCount ||
		resultAgeMs != null || accessPoints.any { accessPoint ->
			accessPoint.identifierToken.isNotEmpty() ||
				accessPoint.providerTimestampNanos?.let { it <= 0L } != false
		}
	) return false
	if (accessPoints.isEmpty()) {
		return platformTimestampMs == null &&
			wal.observedIntervalStartNanos == wal.observedElapsedNanos
	}
	if (accessPoints != accessPoints.sortedWith(WIFI_PROVIDER_ITEM_ORDER)) return false
	val providerTimes = accessPoints.map { point -> requireNotNull(point.providerTimestampNanos) }
	val latest = requireNotNull(providerTimes.maxOrNull())
	return providerTimes.minOrNull() == wal.observedIntervalStartNanos &&
		latest == wal.observedElapsedNanos &&
		platformTimestampMs == latest / NANOS_PER_MILLISECOND
}

private fun SourceEventWalEntity.exactlyMatches(other: SourceEventWalEntity): Boolean =
	copy(payload = EMPTY_BYTES) == other.copy(payload = EMPTY_BYTES) && payload.contentEquals(other.payload)

private fun ProviderRegistrationGenerationEntity.hasValidHistoricalShape(): Boolean {
	val acceptedWall = acceptedAtMs ?: return false
	val acceptedElapsed = acceptedElapsedRealtimeNanos ?: return false
	val statusShape = when (status) {
		ProviderRegistrationGenerationEntity.STATUS_ACTIVE ->
			retiredAtMs == null && retiredElapsedRealtimeNanos == null && failureCode == null
		ProviderRegistrationGenerationEntity.STATUS_RETIRING ->
			retiredAtMs != null && retiredElapsedRealtimeNanos != null && !failureCode.isNullOrBlank()
		ProviderRegistrationGenerationEntity.STATUS_RETIRED ->
			retiredAtMs != null && retiredElapsedRealtimeNanos != null &&
				failureCode?.isNotBlank() != false
		else -> false
	}
	return statusShape && reservedAtMs <= acceptedWall && reservedElapsedRealtimeNanos <= acceptedElapsed &&
		retiredAtMs?.let { it >= acceptedWall } != false &&
		retiredElapsedRealtimeNanos?.let { it >= acceptedElapsed } != false
}

private fun WifiPlan.hasSupportedHistoricalShape(): Boolean =
	mode != WifiMode.OFF && minimumAttemptIntervalMs >= 0L &&
		maximumAcceptableResultAgeMs >= 0L && unchangedResultDedupeWindowMs >= 0L &&
		backoff.initialDelayMs >= 0L && backoff.maximumDelayMs >= backoff.initialDelayMs &&
		backoff.multiplier.isFinite() && backoff.multiplier >= 1.0

private fun WifiPlan.satisfiesExactWifiContract(contract: SourceDemandContract): Boolean =
	contract.floor === WifiBroadcastAcquisitionFloor &&
		mode in setOf(WifiMode.BROADCAST_DRIVEN, WifiMode.ACTIVE_ATTEMPTS) &&
		maximumAcceptableResultAgeMs <= contract.maximumProviderItemAgeMs &&
		contract.requestedDeliveryLatencyMs == null

private fun WifiPlan.maximumObservationAgeNanos(): Long =
	if (maximumAcceptableResultAgeMs > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
		Long.MAX_VALUE
	} else {
		maximumAcceptableResultAgeMs * NANOS_PER_MILLISECOND
	}

private fun LifecycleDesiredActionEntity.authenticates(
	wal: SourceEventWalEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	configurationRevision: Long,
	appliedAtElapsedNanos: Long,
): Boolean {
	val acknowledgedWall = acknowledgedAtMs ?: return false
	val acknowledgedElapsed = acknowledgedElapsedRealtimeNanos ?: return false
	return actionRevision > 0L && actionFamily == "SOURCE_RUNTIME" && sourceKind == WIFI_SOURCE &&
		desiredState == "STARTED" && status == "START_ACCEPTED" && attemptCount > 0 &&
		failureCode == null && retryTrigger == null && logicalTrackingId == wal.logicalTrackingId &&
		serviceRunId == wal.serviceRunId && manifestRevision == wal.sessionManifestRevision &&
		desiredPlanRevision == configurationRevision && sourcePolicyRevision == wal.sourcePolicyRevision &&
		consentEpoch == wal.captureConsentEpoch && startOrigin == manifest.startOrigin &&
		startOrigin == run.startOrigin && bootId == wal.clockDomainId &&
		leaseGeneration == wal.lifecycleLeaseGeneration && sourceInstanceId == wal.sourceInstanceId &&
		registrationGeneration == wal.registrationGeneration && requestedAtMs == manifest.effectiveWallTimeMs &&
		acknowledgedWall >= requestedAtMs &&
		requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
		requestedElapsedRealtimeNanos <= appliedAtElapsedNanos &&
		appliedAtElapsedNanos <= acknowledgedElapsed &&
		appliedAtElapsedNanos <= (wal.observedIntervalStartNanos ?: Long.MIN_VALUE)
}

private fun LifecycleDesiredActionEntity.authenticatesCurrentReplacement(
	session: LogicalTrackingSessionEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	wifiSource: SessionManifestSourceEntity,
): Boolean {
	val acknowledgedWall = acknowledgedAtMs ?: return false
	val acknowledgedElapsed = acknowledgedElapsedRealtimeNanos ?: return false
	val sourceInstance = sourceInstanceId ?: return false
	val registration = registrationGeneration ?: return false
	return actionRevision > 0L && actionFamily == "SOURCE_RUNTIME" && sourceKind == WIFI_SOURCE &&
		desiredState == "STARTED" && status == "START_ACCEPTED" && attemptCount > 0 &&
		failureCode == null && retryTrigger == null && sourceInstance.isNotBlank() && registration > 0L &&
		logicalTrackingId == session.logicalTrackingId && serviceRunId == run.serviceRunId &&
		manifestRevision == manifest.manifestRevision &&
		desiredPlanRevision == manifest.acquisitionPlanRevision &&
		sourcePolicyRevision == manifest.sourcePolicyRevision && consentEpoch == wifiSource.consentEpoch &&
		startOrigin == manifest.startOrigin && startOrigin == run.startOrigin && bootId == run.bootId &&
		bootId == session.lifecycleBootId && leaseGeneration == run.leaseGeneration &&
		leaseGeneration == session.lifecycleLeaseGeneration && requestedAtMs == manifest.effectiveWallTimeMs &&
		requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
		requestedAtMs >= run.startedAtMs && requestedElapsedRealtimeNanos >= run.startedElapsedNanos &&
		acknowledgedWall >= requestedAtMs && acknowledgedElapsed >= requestedElapsedRealtimeNanos
}

private fun SessionManifestSourceEntity.isExactWifiWriter(consentEpoch: Long): Boolean =
	sourceKind == WIFI_SOURCE && purpose == CAPTURE_PURPOSE && persistenceEligible &&
	this.consentEpoch == consentEpoch && outputDestination == DESTINATION && writerOwner == OWNER &&
	writerOwnerGeneration == OWNER_GENERATION && writerProjectionId == WRITER_ID &&
	writerProjectionVersion == WRITER_VERSION &&
	writerBindingGeneration == SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION

private fun Set<SourceKind>.toStableCodes(): String = map(SourceKind::stableCode).sorted().joinToString(",")

private fun LogicalTrackingSessionEntity.hasValidLifecycleShape(admissionOrdinal: Long): Boolean {
	if (state !in ALL_SESSION_STATES || lifecycleRevision <= 0L || desiredPlanRevision <= 0L ||
		startedAtMs < 0L || startedElapsedNanos < 0L || lifecycleLeaseGeneration <= 0L ||
		lifecycleBootId.isNullOrBlank() || currentManifestRevision?.let { it <= 0L } != false
	) return false
	val terminal = state in TERMINAL_SESSION_STATES
	if ((completedAtMs != null) != terminal || completedAtMs?.let { it < startedAtMs } == true ||
		(cutoffAtMs == null) != (cutoffElapsedNanos == null)
	) return false
	if (terminal || state == "STOPPING") {
		if (cutoffAtMs == null || cutoffElapsedNanos == null || cutoffAtMs < startedAtMs ||
			cutoffElapsedNanos < startedElapsedNanos
		) return false
	} else if (cutoffAtMs != null || cutoffElapsedNanos != null) {
		return false
	}
	return if (terminal) {
		currentServiceRunId == null && finalAdmissionOrdinal?.let { it >= admissionOrdinal } == true
	} else {
		!currentServiceRunId.isNullOrBlank() && finalAdmissionOrdinal == null
	}
}

private fun SourceServiceRunEntity.hasValidLifecycleShape(): Boolean {
	if (state !in ALL_SESSION_STATES || desiredPlanRevision <= 0L || startedAtMs < 0L ||
		startedElapsedNanos < 0L || leaseGeneration <= 0L || bootId.isBlank()
	) return false
	val terminal = state in TERMINAL_SESSION_STATES
	return (completedAtMs != null) == terminal && completedAtMs?.let { it >= startedAtMs } != false
}

private fun SourceServiceRunEntity.hasValidRelationshipTo(
	session: LogicalTrackingSessionEntity,
	currentRun: SourceServiceRunEntity?,
): Boolean = when {
	logicalTrackingId != session.logicalTrackingId -> false
	state !in TERMINAL_SESSION_STATES -> hasValidCurrentRelationshipTo(session)
	session.state in TERMINAL_SESSION_STATES -> session.currentServiceRunId == null
	session.currentServiceRunId == serviceRunId -> false
	else -> currentRun?.hasValidLifecycleShape() == true &&
		currentRun.hasValidCurrentRelationshipTo(session)
}

private fun SourceServiceRunEntity.hasValidCurrentRelationshipTo(
	session: LogicalTrackingSessionEntity,
): Boolean = logicalTrackingId == session.logicalTrackingId && state !in TERMINAL_SESSION_STATES &&
	session.state !in TERMINAL_SESSION_STATES && session.currentServiceRunId == serviceRunId &&
	bootId == session.lifecycleBootId && leaseGeneration == session.lifecycleLeaseGeneration &&
	desiredPlanRevision == session.desiredPlanRevision &&
	preparedManifestRevision == session.currentManifestRevision && startedAtMs >= session.startedAtMs &&
	startedElapsedNanos >= session.startedElapsedNanos &&
	((session.state in ADMISSION_SESSION_STATES && state in ADMISSION_RUN_STATES) ||
		(session.state == "ACTIVE" && state == "STOPPING") ||
		(session.state == "STOPPING" && state == "STOPPING"))

private fun Long.isExactSettlementOf(previous: Long): Boolean =
	this == previous || (previous == Long.MAX_VALUE && this < Long.MAX_VALUE)

private fun hasValidZone(zoneId: String): Boolean = try {
	ZoneId.of(zoneId)
	true
} catch (_: DateTimeException) {
	false
}

private data class WifiCapturedLineage(
	val logicalFactId: String,
	val revisions: List<WifiCapturedFactRevisionEntity>,
	val earliestPossibleWallTimeMs: Long,
	val aggregateOwnerLogicalFactId: String?,
	val aggregateOwnerSemanticRevision: Long?,
	val aggregateOwnerCursorRevision: Long?,
) {
	val scope: WifiCapturedRunScope
		get() = revisions.first().let { revision ->
			WifiCapturedRunScope(revision.logicalTrackingId, revision.serviceRunId)
		}
}

private data class WifiCapturedRunScope(
	val logicalTrackingId: String,
	val serviceRunId: String,
)

private data class WifiAggregateReuseAuthority(
	val logicalTrackingId: String,
	val serviceRunId: String,
	val sessionSegmentId: Long,
	val capturedSources: Set<SourceKind>,
	val controlSources: Set<SourceKind>,
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val configurationRevision: Long,
	val physicalConfigurationFingerprint: String,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val purposeEligibilityMask: Long,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val manifestRevision: Long,
	val lifecycleLeaseGeneration: Long,
	val collectedDataEpoch: Long,
	val scopeDeletionGeneration: Long,
	val clockDomainId: String,
	val storedZoneId: String,
	val planPayloadVersion: Int,
	val planPayloadChecksum: String,
	val maximumObservationAgeNanos: Long,
	val resultContract: String,
	val registrationAppliedAtNanos: Long,
	val providerAcceptanceStartNanos: Long,
	val providerAcceptanceEndNanos: Long,
	val authorizationEffectStartNanos: Long,
	val authorizationEffectEndNanos: Long,
	val sessionRunEffectStartNanos: Long,
	val sessionRunEffectEndNanos: Long,
)

private data class AuthenticatedWifiWal(
	val scope: WifiCapturedRunScope,
	val authority: WifiCaptureAuthority,
	val evidence: WifiWalObservationEvidence,
	val derivedAggregate: WifiCapturedFact.Aggregate?,
) {
	val reuseAuthority = WifiAggregateReuseAuthority(
		authority.logicalTrackingId.value,
		authority.serviceRunId.value,
		authority.sessionSegmentId,
		authority.capturedSources,
		authority.controlSources,
		authority.sourceInstanceId.value,
		authority.registrationGeneration,
		authority.configurationRevision,
		authority.physicalConfigurationFingerprint,
		authority.authorizationRevision,
		authority.authorizationFingerprint,
		authority.purposeEligibilityMask,
		authority.sourcePolicyRevision,
		authority.captureConsentEpoch,
		authority.sessionManifestRevision,
		authority.lifecycleLeaseGeneration,
		authority.collectedDataEpoch,
		authority.scopeDeletionGeneration,
		authority.clockDomainId,
		authority.zoneId,
		authority.serializedAcquisitionPlan.payloadVersion,
		authority.serializedAcquisitionPlan.payloadChecksum,
		authority.acquisitionConfiguration.maximumObservationAgeNanos,
		authority.acquisitionConfiguration.resultContract.name,
		requireNotNull(authority.appliedRegistration.appliedAtElapsedRealtimeNanos),
		authority.temporalAuthority.providerAcceptance.startInclusiveNanos,
		authority.temporalAuthority.providerAcceptance.endExclusiveNanos,
		authority.temporalAuthority.authorizationEffect.startInclusiveNanos,
		authority.temporalAuthority.authorizationEffect.endExclusiveNanos,
		authority.temporalAuthority.sessionRunEffect.startInclusiveNanos,
		authority.temporalAuthority.sessionRunEffect.endExclusiveNanos,
	)
}

private data class WifiCapturedWalAudit(
	val captureByEventId: Map<String, AuthenticatedWifiWal>,
	val scopes: Set<WifiCapturedRunScope>,
	val latestDurableTimeMs: Long,
)

private data class WifiCapturedAudit(
	val lineages: List<WifiCapturedLineage>,
	val lineagesById: Map<String, WifiCapturedLineage>,
	val generationByScope: Map<WifiCapturedRunScope, WifiCaptureDeletionGenerationEntity>,
	val wal: WifiCapturedWalAudit,
	val latestDurableTimeMs: Long,
)

private class WifiCapturedRetentionBlockedException(
	val reason: WifiCapturedRetentionBlockedReason,
) : IllegalStateException(reason.name)

private class WifiCapturedSourceDeletionBlockedException(
	val reason: WifiCapturedSourceDeletionBlockedReason,
) : IllegalStateException(reason.name)

private class WifiCapturedMaintenanceLimitExceeded : IllegalStateException()

private fun block(reason: WifiCapturedRetentionBlockedReason): Nothing =
	throw WifiCapturedRetentionBlockedException(reason)

private fun block(reason: WifiCapturedSourceDeletionBlockedReason): Nothing =
	throw WifiCapturedSourceDeletionBlockedException(reason)

private const val WIFI_SOURCE = SourceDestinationOwnerEntity.SOURCE_WIFI
private const val CAPTURE_PURPOSE = SourceBrokerPurpose.SESSION_CAPTURE
private const val DESTINATION = SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI
private const val OWNER = SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS
private const val OWNER_GENERATION = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
private const val WRITER_ID = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID
private const val WRITER_VERSION = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION
private const val CAPTURED_PLAN_ATTRIBUTION = 0
private const val PLAN_PAYLOAD_VERSION = 1
private const val WIFI_PAYLOAD_VERSION = 2
private const val FIRST_DELETION_GENERATION = 1L
private const val INSERT_IGNORED = -1L
private const val DELETE_BATCH_SIZE = 128
private const val CURSOR_PAGE_SIZE = 256
private const val DELETION_GENERATION_PAGE_SIZE = 256
private const val WAL_PAGE_SIZE = 256
private const val MAX_EXPECTED_DELIVERY_UNITS = 1
private const val MAX_PLAN_PAYLOAD_BYTES = 1_024
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val PRODUCER_WALL_UNCERTAINTY_MS = 1L
private val LOWERCASE_SHA_256 = Regex("^[0-9a-f]{64}$")
private val EMPTY_BYTES = byteArrayOf()
private val ACCEPTED_REGISTRATION_STATES = setOf(
	ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
	ProviderRegistrationGenerationEntity.STATUS_RETIRING,
	ProviderRegistrationGenerationEntity.STATUS_RETIRED,
)
private val TERMINAL_SESSION_STATES = setOf("FINALIZED", "CLOSED", "FAILED")
private val ADMISSION_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING")
private val ADMISSION_RUN_STATES = setOf("STARTING", "ACTIVE")
private val ALL_SESSION_STATES = TERMINAL_SESSION_STATES +
	setOf("STARTING", "ACTIVE", "RECONFIGURING", "STOPPING")
private val WIFI_PROVIDER_ITEM_ORDER = compareBy<WifiAccessPointEvidence>(
	WifiAccessPointEvidence::frequencyMhz,
	WifiAccessPointEvidence::signalLevelDbm,
	WifiAccessPointEvidence::providerTimestampNanos,
)
private val MAX_CANONICAL_WIFI_PAYLOAD_BYTES = 4 + 4 +
	(WifiIdentityFreeResultContract.ANDROID_SCAN_RESULTS_V1.maximumAccessPointCount * 19) + 9 + 1
private val DEFAULT_LIMITS = WifiCapturedMaintenanceLimits()
