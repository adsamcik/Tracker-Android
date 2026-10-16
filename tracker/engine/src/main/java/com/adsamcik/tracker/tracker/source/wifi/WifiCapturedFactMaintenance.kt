package com.adsamcik.tracker.tracker.source.wifi

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.WifiCapturedFactDao
import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.MAX_WIFI_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.RetainedCaptureEventIdentity
import com.adsamcik.tracker.shared.base.database.data.RetainedCaptureScopeIdentity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionScopeProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class WifiCapturedMaintenanceCheckpoint {
	TRANSACTION_STARTED,
	REVISION_PAGE_LOADED,
	WAL_AUTHENTICATED,
	LINEAGE_AUTHENTICATED,
	RETENTION_PROOF_PERSISTED,
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
	val maximumWalPayloadBytes: Int = DEFAULT_MAXIMUM_WAL_PAYLOAD_BYTES,
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

/** Authority, unknown payload or bounded-work reason preventing safe Wi-Fi retention cleanup. */
enum class WifiCapturedRetentionBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	DESTINATION_OWNER_CHANGED,
	UNRECOGNIZED_PAYLOAD_PRESENT,
	MAINTENANCE_BOUND_EXCEEDED,
	FACT_AUTHORITY_UNVERIFIABLE,
	STALE_REQUEST,
}

/** Outcome of removing retained Wi-Fi facts while preserving current ownership and deletion fences. */
sealed interface WifiCapturedRetentionResult {
	/** Counts logical facts and physical revisions removed by the completed retention operation. */
	data class Pruned(
		val logicalFactCount: Int,
		val revisionCount: Int,
	) : WifiCapturedRetentionResult

	/** No eligible saved Wi-Fi facts required retention removal. */
	data object NoChange : WifiCapturedRetentionResult

	/**
	 * Retention made no acknowledged change because the supplied safety condition was not satisfied.
	 */
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
	internal val database: AppDatabase,
	internal val payloadCodec: SourcePayloadCodec,
	internal val planCodec: SourcePlanCodec,
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
				val evidence = requireWifiRetentionEvidence(
					database.sourceEvidenceStateDao().get(), expectedCollectedDataEpoch,
					expectedDeletedSourceEventHighWaterOrdinal, beforeMs,
				)
				val audit = audit(evidence, limits, checkpoint)
				if (markedAtMs < audit.latestDurableTimeMs) {
					block(WifiCapturedRetentionBlockedReason.STALE_REQUEST)
				}
				val selectedIds = affectedDependencyClosure(audit.lineages, beforeMs)
				if (selectedIds.isEmpty()) {
					return@withTransaction WifiCapturedRetentionResult.NoChange
				}
				val directlyExpiredIds = audit.lineages.filter { lineage ->
					lineage.earliestPossibleWallTimeMs < beforeMs
				}.mapTo(hashSetOf(), WifiCapturedLineage::logicalFactId)
				val selected = selectedIds.map(audit.lineagesById::getValue)
				if (audit.lineages.any { lineage ->
					lineage.logicalFactId !in selectedIds &&
						lineage.aggregateOwnerLogicalFactId in selectedIds
				}) {
					block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				}

				val dao = database.wifiCapturedFactDao()
				persistRetentionProofs(selected, audit, beforeMs, markedAtMs, directlyExpiredIds)
				checkpoint(WifiCapturedMaintenanceCheckpoint.RETENTION_PROOF_PERSISTED)
				var removedRevisions = 0
				selected.dependencySafeDeletionBatches(DELETE_BATCH_SIZE).forEach { batch ->
					val removed = deleteExactLineageBatch(dao, batch) {
						block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
					}
					removedRevisions = Math.addExact(removedRevisions, removed)
					checkpoint(WifiCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED)
				}
				check(database.sourceEvidenceStateDao().incrementRevision(
					maxOf(evidence.updatedAtMs, markedAtMs),
				) == 1) {
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
		expectedCurrentPolicyRevision: Long,
		expectedRevokedConsentEpoch: Long,
		deletedAtMs: Long,
	): WifiCapturedSourceDeletionResult = deleteAfterCaptureConsentReset(
		expectedCollectedDataEpoch,
		expectedDeletedSourceEventHighWaterOrdinal,
		expectedCurrentPolicyRevision,
		expectedRevokedConsentEpoch,
		deletedAtMs,
		DEFAULT_LIMITS,
	) { currentCoroutineContext().ensureActive() }

	internal suspend fun deleteAfterCaptureConsentReset(
		expectedCollectedDataEpoch: Long,
		expectedDeletedSourceEventHighWaterOrdinal: Long,
		expectedCurrentPolicyRevision: Long,
		expectedRevokedConsentEpoch: Long,
		deletedAtMs: Long,
		limits: WifiCapturedMaintenanceLimits,
		checkpoint: suspend (WifiCapturedMaintenanceCheckpoint) -> Unit,
	): WifiCapturedSourceDeletionResult {
		require(expectedCollectedDataEpoch >= 0L)
		require(expectedDeletedSourceEventHighWaterOrdinal >= 0L)
		require(expectedCurrentPolicyRevision > 0L)
		require(expectedRevokedConsentEpoch >= 0L)
		require(deletedAtMs >= 0L)
		return try {
			database.withTransaction {
				checkpoint(WifiCapturedMaintenanceCheckpoint.TRANSACTION_STARTED)
				val evidence = database.sourceEvidenceStateDao().get()
				if (evidence == null || evidence.collectedDataEpoch != expectedCollectedDataEpoch ||
					evidence.deletedSourceEventHighWaterOrdinal !=
						expectedDeletedSourceEventHighWaterOrdinal
				) {
					block(WifiCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
				}
				val dao = prepareCaptureConsentDeletion(
					expectedCollectedDataEpoch, expectedCurrentPolicyRevision,
					expectedRevokedConsentEpoch, deletedAtMs, limits,
				)

				val audit = auditForCaptureDeletion(evidence, limits, checkpoint, deletedAtMs)
				CaptureDeletionScopeRegistry(
					expectedCollectedDataEpoch, expectedDeletedSourceEventHighWaterOrdinal, limits,
				).deleteAuditedCapture(this@WifiCapturedFactMaintenance, audit, evidence, dao, deletedAtMs, checkpoint)
			}
		} catch (blocked: WifiCapturedSourceDeletionBlockedException) {
			WifiCapturedSourceDeletionResult.Blocked(blocked.reason)
		} catch (blocked: WifiCapturedRetentionBlockedException) {
			WifiCapturedSourceDeletionResult.Blocked(
				blocked.reason.sourceDeletionReason(),
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

	/**
	 * Audits only the finite fact/WAL closure that can affect one portable logical entry.
	 *
	 * This is intentionally transaction-neutral: the portable reader owns the one Room snapshot
	 * that also authenticates run, manifest, completeness, lane, deletion, and retention state.
	 */
	internal suspend fun auditPortableInTransaction(
		evidence: SourceEvidenceState,
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		completeness: List<SourceSessionCompletenessEntity>,
		limits: WifiCapturedMaintenanceLimits,
	): WifiCapturedAudit {
		require(logicalTrackingId.isNotBlank())
		require(serviceRunIds.isNotEmpty() && serviceRunIds.distinct().size == serviceRunIds.size)
		val dao = database.wifiCapturedFactDao()
		val revisions = dao.portableRevisionClosure(
			logicalTrackingId,
			serviceRunIds,
			limits.maximumRevisions + 1,
		)
		val lineages = authenticateWifiPortableRevisionClosure(revisions, limits)
		val lineagesById = lineages.associateBy(WifiCapturedLineage::logicalFactId)
		if (lineagesById.size != lineages.size) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}

		val owner = database.sourceDestinationOwnerDao().get(WIFI_SOURCE, DESTINATION)
		if (owner == null || owner.owner != OWNER || !owner.ownerGeneration.isKnownCapturedWriterOwnerGeneration()) {
			block(WifiCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED)
		}

		val cursors = loadWifiPortableCursors(logicalTrackingId, serviceRunIds, limits, lineagesById)
		val (generations, generationByScope) = loadWifiPortableDeletionGenerations(logicalTrackingId, serviceRunIds, limits)
		val wal = auditPortableWal(
			evidence,
			logicalTrackingId,
			serviceRunIds,
			revisions.map(WifiCapturedFactRevisionEntity::sourceEventId).distinct(),
			completeness,
			limits,
		)
		val (reuseAuthorityById, aggregateById) = authenticatePortableWifiLineages(lineages, cursors, evidence, wal)
		authenticateDependencies(lineages, lineagesById, reuseAuthorityById, aggregateById)
		authenticatePortableDeletionGenerations(lineages, generations, evidence)

		return WifiCapturedAudit(
			lineages = lineages,
			lineagesById = lineagesById,
			cursorsById = cursors,
			aggregateById = aggregateById,
			generationByScope = generationByScope,
			wal = wal,
			latestDurableTimeMs = latestWifiDurableTime(owner?.updatedAtMs, wal, lineages, cursors, generations),
		)
	}

	private suspend fun auditForCaptureDeletion(
		evidence: SourceEvidenceState,
		limits: WifiCapturedMaintenanceLimits,
		checkpoint: suspend (WifiCapturedMaintenanceCheckpoint) -> Unit,
		deletedAtMs: Long,
	): WifiCapturedAudit {
		val audit = audit(evidence, limits, checkpoint)
		if (audit.wal.scopes.isNotEmpty() || audit.lineages.isNotEmpty()) {
			val owner = database.sourceDestinationOwnerDao().get(WIFI_SOURCE, DESTINATION)
			if (owner?.owner != OWNER || !owner.ownerGeneration.isKnownCapturedWriterOwnerGeneration()) {
				block(WifiCapturedSourceDeletionBlockedReason.DESTINATION_OWNER_CHANGED)
			}
		}
		if (deletedAtMs < audit.latestDurableTimeMs) {
			block(WifiCapturedSourceDeletionBlockedReason.STALE_REQUEST)
		}
		return audit
	}

	/** One consent-deletion call owns the original scope/event collision maps. */
	private class CaptureDeletionScopeRegistry(
		private val expectedCollectedDataEpoch: Long,
		private val expectedDeletedSourceEventHighWaterOrdinal: Long,
		private val limits: WifiCapturedMaintenanceLimits,
	) {
		private val scopeAuthorities = linkedMapOf<WifiCapturedRunScope, RetainedCaptureScopeIdentity>()
		private val eventAuthorities = mutableMapOf<List<Any>, RetainedCaptureEventIdentity>()
		private val rawEventIdentities = mutableMapOf<String, Pair<Long, String>>()
		private val scopes: Collection<RetainedCaptureScopeIdentity> get() = scopeAuthorities.values

		suspend fun deleteAuditedCapture(
			owner: WifiCapturedFactMaintenance,
			audit: WifiCapturedAudit,
			evidence: SourceEvidenceState,
			dao: WifiCapturedFactDao,
			deletedAtMs: Long,
			checkpoint: suspend (WifiCapturedMaintenanceCheckpoint) -> Unit,
		): WifiCapturedSourceDeletionResult = with(owner) {
			val retainedProofs = loadCapturedRetentionProofsForSourceErase(limits)
			val retainedEventProofs = loadSourceEventRetentionProofsForSourceErase(limits)
			val retainedScopeProofs = loadSourceEventRetentionScopeProofsForSourceErase(limits)
			addAudit(audit)
			addRetainedFacts(retainedProofs, evidence, deletedAtMs)
			val scopeProofsByDigest = addRetainedScopes(retainedScopeProofs)
			addRetainedEvents(retainedEventProofs, scopeProofsByDigest)
			val scopesNeedingAuthority = reconcileCaptureDeletionScopes(
				scopes, audit.generationByScope, expectedCollectedDataEpoch,
			)
			val noCapturePayload = audit.lineages.isEmpty() && scopesNeedingAuthority.isEmpty()
			if (noCapturePayload && retainedProofs.isEmpty() && retainedEventProofs.isEmpty() &&
				retainedScopeProofs.isEmpty()
			) {
				return@with WifiCapturedSourceDeletionResult.AlreadyDeleted
			}
			installCaptureDeletionFences(
				dao, scopesNeedingAuthority, expectedCollectedDataEpoch, deletedAtMs,
			)
			checkpoint(WifiCapturedMaintenanceCheckpoint.DELETION_FENCES_INSTALLED)

			val revisionCount = audit.lineages.sumOf { lineage -> lineage.revisions.size }
			removeCapturePayloadAndRetentionProofs(
				dao, audit.lineages, retainedEventProofs, retainedScopeProofs,
			)
			check(database.sourceEvidenceStateDao().incrementRevision(
				maxOf(evidence.updatedAtMs, deletedAtMs),
			) == 1) {
				"Unable to publish captured Wi-Fi source deletion"
			}
			checkpoint(WifiCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED)
			WifiCapturedSourceDeletionResult.Deleted(
				audit.lineages.size,
				revisionCount,
				scopesNeedingAuthority.size,
			)
		}

		private fun registerScope(
			identity: RetainedCaptureScopeIdentity,
			eventIdentity: RetainedCaptureEventIdentity? = null,
		) {
			if (!identity.isCanonicalFor(WIFI_SOURCE, expectedCollectedDataEpoch)) {
				block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			val scope = WifiCapturedRunScope(
				identity.logicalTrackingId,
				identity.serviceRunId,
			)
			val retainedScope = scopeAuthorities.putIfAbsent(scope, identity)
			if (retainedScope != null && retainedScope != identity) {
				block(WifiCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
			}
			if (retainedScope == null &&
				scopeAuthorities.size > limits.maximumDeletionGenerations
			) {
				throw WifiCapturedMaintenanceLimitExceeded()
			}
			if (eventIdentity != null &&
				eventIdentity.sourceAdmissionOrdinal <=
				expectedDeletedSourceEventHighWaterOrdinal
			) {
				block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
			}
			if (eventIdentity != null) {
				registerEvent(eventIdentity)
			}
		}

		private fun registerEvent(eventIdentity: RetainedCaptureEventIdentity) {
			val rawIdentity = eventIdentity.sourceAdmissionOrdinal to eventIdentity.walIntegrityIdentity
			val original = rawEventIdentities.putIfAbsent(eventIdentity.eventId, rawIdentity)
			if (original != null && original != rawIdentity) {
				block(WifiCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
			}
			val retained = eventAuthorities.putIfAbsent(
				listOf(eventIdentity.eventId, eventIdentity.scope.logicalRunScopeDigest,
					eventIdentity.sourcePolicyRevision, eventIdentity.captureConsentEpoch),
				eventIdentity,
			)
			if (retained != null && retained != eventIdentity) {
				block(WifiCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
			}
		}

		private fun addAudit(audit: WifiCapturedAudit) {
			for (scope in audit.lineages.map(WifiCapturedLineage::scope) + audit.wal.scopes) {
				registerScope(scope.retainedIdentity(expectedCollectedDataEpoch))
			}
			for (wal in audit.wal.captureByEventId.values.flatten()) {
				val eventId = wal.evidence.sourceEventId.value
				val scope = wal.scope.retainedIdentity(expectedCollectedDataEpoch)
				registerScope(
					scope,
					RetainedCaptureEventIdentity(
						eventId = eventId,
						sourceAdmissionOrdinal = wal.evidence.sourceAdmissionOrdinal,
						sourcePolicyRevision = wal.evidence.sourcePolicyRevision,
						captureConsentEpoch = wal.evidence.captureConsentEpoch,
						walIntegrityIdentity = wal.evidence.walIntegrityIdentity,
						scope = scope,
					),
				)
			}
		}

		private fun addRetainedFacts(
			retainedProofs: List<CapturedRadioFactRetentionProofEntity>,
			evidence: SourceEvidenceState,
			deletedAtMs: Long,
		) {
			for (proof in retainedProofs) {
				val eventIdentity = proof.authenticatedCaptureEvent(
					expectedSourceKind = WIFI_SOURCE,
					expectedCollectedDataEpoch = expectedCollectedDataEpoch,
					currentRetainedFromMs = evidence.retainedFromMs,
					deletedAtMs = deletedAtMs,
				) ?: block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				registerScope(eventIdentity.scope, eventIdentity)
			}
		}

		private fun addRetainedScopes(
			retainedScopeProofs: List<SourceEventRetentionScopeProofEntity>,
		): Map<String, SourceEventRetentionScopeProofEntity> {
			val scopeProofsByDigest =
				retainedScopeProofs.associateBy(
					SourceEventRetentionScopeProofEntity::logicalRunScopeDigest,
				)
			if (scopeProofsByDigest.size != retainedScopeProofs.size) {
				block(WifiCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT)
			}
			for (proof in retainedScopeProofs) {
				val identity = proof.authenticatedCaptureScope(
					expectedSourceKind = WIFI_SOURCE,
					expectedCollectedDataEpoch = expectedCollectedDataEpoch,
					expectedDeletedSourceEventHighWaterOrdinal =
						expectedDeletedSourceEventHighWaterOrdinal,
				) ?: block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				registerScope(identity)
			}
			return scopeProofsByDigest
		}

		private fun addRetainedEvents(
			retainedEventProofs: List<SourceEventRetentionProofEntity>,
			scopeProofsByDigest: Map<String, SourceEventRetentionScopeProofEntity>,
		) {
			for (proof in retainedEventProofs) {
				val eventIdentity = proof.authenticatedCaptureEvent(
					expectedSourceKind = WIFI_SOURCE,
					expectedCollectedDataEpoch = expectedCollectedDataEpoch,
				) ?: block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				val scopeProof = proof.logicalRunScopeDigest?.let(scopeProofsByDigest::get)
				if (scopeProof?.authenticatesRetainedEvent(proof) != true) {
					block(WifiCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
				}
				registerScope(eventIdentity.scope, eventIdentity)
			}
		}
	}

	internal companion object {
		internal const val WIFI_SOURCE = SourceDestinationOwnerEntity.SOURCE_WIFI
		internal const val CAPTURE_PURPOSE = SourceBrokerPurpose.SESSION_CAPTURE
		internal const val CONTROL_PURPOSE = SessionManifestPurposeCode.CONTROL
		internal const val DESTINATION = SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI
		internal const val OWNER = SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS
		internal const val WRITER_ID = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID
		internal const val WRITER_VERSION = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION
		internal const val CAPTURED_PLAN_ATTRIBUTION = 0
		internal val RECEIVE_TIME_ONLY_PLAN_ATTRIBUTION = PlanAttribution.RECEIVE_TIME_ONLY.ordinal
		internal const val PLAN_PAYLOAD_VERSION = 1
		internal const val FIRST_DELETION_GENERATION = 1L
		internal const val INSERT_IGNORED = -1L
		internal const val DELETE_BATCH_SIZE = 128
		internal const val CURSOR_PAGE_SIZE = 256
		internal const val DELETION_GENERATION_PAGE_SIZE = 256
		internal const val WAL_PAGE_SIZE = 256
		internal const val PORTABLE_WAL_BATCH_SIZE = 256
		internal const val MAX_EXPECTED_DELIVERY_UNITS = 1
		internal const val MAX_NATIVE_DETAIL_CHILDREN = 64
		internal const val MAX_PLAN_PAYLOAD_BYTES = 1_024
		internal const val NANOS_PER_MILLISECOND = 1_000_000L
		internal const val PRODUCER_WALL_UNCERTAINTY_MS = 1L
		internal val LOWERCASE_SHA_256 = Regex("^[0-9a-f]{64}$")
		internal val EMPTY_BYTES = byteArrayOf()
		internal val ACCEPTED_REGISTRATION_STATES = setOf(
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
			ProviderRegistrationGenerationEntity.STATUS_RETIRING,
			ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		)
		internal val NONCAPTURE_BROKER_PURPOSES = setOf(
			SourceBrokerPurpose.CONTROL_AUTOSTART,
			SourceBrokerPurpose.CONTROL_CONTINUATION,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
		internal val TERMINAL_SESSION_STATES = setOf("FINALIZED", "CLOSED", "FAILED")
		internal val ADMISSION_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING")
		internal val ADMISSION_RUN_STATES = setOf("STARTING", "ACTIVE")
		internal val ALL_SESSION_STATES = TERMINAL_SESSION_STATES +
			setOf("STARTING", "ACTIVE", "RECONFIGURING", "STOPPING")
		internal val WIFI_PROVIDER_ITEM_ORDER = compareBy<WifiAccessPointEvidence>(
			WifiAccessPointEvidence::frequencyMhz,
			WifiAccessPointEvidence::signalLevelDbm,
			WifiAccessPointEvidence::providerTimestampNanos,
			WifiAccessPointEvidence::identifierToken,
			WifiAccessPointEvidence::networkName,
		)
		internal const val MAX_CANONICAL_WIFI_PAYLOAD_BYTES = MAX_WIFI_WAL_PAYLOAD_BYTES
		internal val DEFAULT_LIMITS = WifiCapturedMaintenanceLimits()
	}
}

private const val DEFAULT_MAXIMUM_WAL_PAYLOAD_BYTES = 1_024 * 1_024
