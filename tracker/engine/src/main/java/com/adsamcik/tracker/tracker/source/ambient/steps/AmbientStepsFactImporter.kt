package com.adsamcik.tracker.tracker.source.ambient.steps

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapIntegrity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.database.dao.synchronizeLifecycle
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.toSourceDemandContract
import java.time.ZoneId
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** Explicit current-clock and calendar authority for one opportunistic import attempt. */
internal data class AmbientStepsImportBoundary(
	val observedBootId: String,
	val observedElapsedRealtimeNanos: Long,
	val observedAtMs: Long,
	val throughTimeMs: Long,
	val zoneId: ZoneId,
) {
	init {
		require(observedBootId.isNotBlank())
		require(observedElapsedRealtimeNanos >= 0L)
		require(observedAtMs >= 0L)
		require(throughTimeMs >= 0L && throughTimeMs <= observedAtMs)
		require(throughTimeMs % MILLIS_PER_SECOND == 0L)
	}
}

internal enum class AmbientStepsImportIneligibleReason {
	NO_ACTIVE_REGISTRATION,
	REGISTRATION_NOT_ACCEPTED,
	POLICY_INACTIVE,
	AMBIENT_POLICY_INELIGIBLE,
	AMBIENT_CONSENT_INELIGIBLE,
	RETENTION_AUTHORITY_UNAVAILABLE,
	AUTHORIZATION_INELIGIBLE,
	DESTINATION_OWNER_INELIGIBLE,
	PROVIDER_READER_UNAVAILABLE,
}

internal enum class AmbientStepsImportStaleReason {
	CURRENT_STATE_CHANGED,
	LIFECYCLE_CHANGED,
	REGISTRATION_CHANGED,
	AUTHORIZATION_CHANGED,
	DESTINATION_OWNER_CHANGED,
	CURSOR_CHANGED,
	FACT_RETRACTED,
}

internal enum class AmbientStepsImportRetryableReason {
	PROVIDER_READ_FAILED,
	PROVIDER_RESULT_MISMATCH,
	STORAGE_UNAVAILABLE,
}

internal enum class AmbientStepsImportNoEvidenceReason {
	NO_WINDOW_AVAILABLE,
	PROVIDER_RETURNED_NO_EVIDENCE,
}

internal enum class AmbientStepsImportGapReason {
	INITIAL_ZONE_AUTHORITY_UNOBSERVED,
	AUTHORITY_BOUNDARY_NOT_DRAINED,
	PROVIDER_NO_EVIDENCE,
	RETENTION_ADVANCED,
	ZONE_CHANGED,
}

internal sealed interface AmbientStepsImportResult {
	data class Ineligible(val reason: AmbientStepsImportIneligibleReason) : AmbientStepsImportResult
	data class Stale(val reason: AmbientStepsImportStaleReason) : AmbientStepsImportResult
	data class Retryable(val reason: AmbientStepsImportRetryableReason) : AmbientStepsImportResult
	data class NoEvidence(val reason: AmbientStepsImportNoEvidenceReason) : AmbientStepsImportResult
	data class Gap(
		val reason: AmbientStepsImportGapReason,
		val fromTimeMs: Long,
		val toTimeMs: Long,
	) : AmbientStepsImportResult
	data class Unchanged(
		val window: AmbientStepsProviderReadWindow,
		val cursorRevision: Long,
	) : AmbientStepsImportResult
	data class Applied(
		val window: AmbientStepsProviderReadWindow,
		val logicalFactId: String,
		val semanticRevision: Long,
		val cursorRevision: Long,
	) : AmbientStepsImportResult
}

/**
 * Imports at most one structural-day provider window without creating a cadence or activating a
 * lane. The provider read is deliberately outside Room. The exact authority snapshot is then
 * revalidated before the cursor, fact, and evidence revision are committed together.
 */
@Singleton
internal class AmbientStepsFactImporter internal constructor(
	private val database: AppDatabase,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val clock: Clock,
	private val readers: Map<AmbientStepsProvider, AmbientStepsProviderReader>,
	private val planner: AmbientStepsStructuralWindowPlanner =
		AmbientStepsStructuralWindowPlanner(maximumWindowsPerPass = 1),
) {
	@Inject
	constructor(
		database: AppDatabase,
		lifecycleStore: CollectedDataLifecycleStore,
		clock: Clock,
		healthConnectReader: HealthConnectAmbientStepsProviderReader,
		localRecordingReader: LocalRecordingAmbientStepsProviderReader,
	) : this(
		database = database,
		lifecycleStore = lifecycleStore,
		clock = clock,
		readers = mapOf(
			healthConnectReader.provider to healthConnectReader,
			localRecordingReader.provider to localRecordingReader,
		),
	)

	suspend fun importNext(boundary: AmbientStepsImportBoundary): AmbientStepsImportResult {
		val lifecycle = lifecycleSnapshotOrNull()
			?: return retryable(AmbientStepsImportRetryableReason.STORAGE_UNAVAILABLE)
		val preflight = try {
			database.withTransaction { resolvePreflight(boundary, lifecycle) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return retryable(AmbientStepsImportRetryableReason.STORAGE_UNAVAILABLE)
		}
		if (preflight is Preflight.Outcome) return preflight.result
		preflight as Preflight.Ready

		val reader = readers[preflight.authority.provider]
			?: return ineligible(AmbientStepsImportIneligibleReason.PROVIDER_READER_UNAVAILABLE)
		if (reader.provider != preflight.authority.provider) {
			return retryable(AmbientStepsImportRetryableReason.PROVIDER_RESULT_MISMATCH)
		}
		val aggregate = try {
			reader.read(preflight.window.providerWindow, boundary.observedAtMs)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return retryable(AmbientStepsImportRetryableReason.PROVIDER_READ_FAILED)
		}
		if (aggregate != null && (
			aggregate.provider != preflight.authority.provider ||
				aggregate.window != preflight.window.providerWindow ||
				aggregate.observedAtMs != boundary.observedAtMs
			)
		) {
			return retryable(AmbientStepsImportRetryableReason.PROVIDER_RESULT_MISMATCH)
		}
		val commitLifecycle = lifecycleSnapshotOrNull()
			?: return retryable(AmbientStepsImportRetryableReason.STORAGE_UNAVAILABLE)
		if (commitLifecycle != lifecycle) {
			return stale(AmbientStepsImportStaleReason.LIFECYCLE_CHANGED)
		}

		return try {
			database.withTransaction {
				commitRead(preflight, boundary, commitLifecycle, aggregate)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RetractedAmbientStepsFactException) {
			stale(AmbientStepsImportStaleReason.FACT_RETRACTED)
		} catch (_: ConcurrentAmbientStepsImportException) {
			stale(AmbientStepsImportStaleReason.CURRENT_STATE_CHANGED)
		} catch (_: Exception) {
			retryable(AmbientStepsImportRetryableReason.STORAGE_UNAVAILABLE)
		}
	}

	/**
	 * Drains one already-retiring predecessor and partitions its coverage from the accepted
	 * successor. This command is dormant until provider lifecycle explicitly wires the handoff.
	 */
	internal suspend fun handoffAcceptedProvider(
		command: AmbientStepsProviderHandoffCommand,
	): AmbientStepsProviderHandoffResult {
		val lifecycle = lifecycleSnapshotOrNull()
			?: return AmbientStepsProviderHandoffResult.Retryable(
				AmbientStepsProviderHandoffRetryableReason.STORAGE_UNAVAILABLE,
			)
		val preflight = try {
			database.withTransaction { resolveHandoffPreflight(command, lifecycle) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: IllegalArgumentException) {
			return AmbientStepsProviderHandoffResult.Stale(
				AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED,
			)
		} catch (_: Exception) {
			return AmbientStepsProviderHandoffResult.Retryable(
				AmbientStepsProviderHandoffRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
		if (preflight is HandoffPreflight.Outcome) return preflight.result
		preflight as HandoffPreflight.Ready

		val aggregate = if (preflight.drainWindow == null) {
			null
		} else {
			val reader = readers[preflight.predecessor.provider]
				?: return AmbientStepsProviderHandoffResult.Ineligible(
					AmbientStepsProviderHandoffIneligibleReason.PROVIDER_READER_UNAVAILABLE,
				)
			if (reader.provider != preflight.predecessor.provider) {
				return AmbientStepsProviderHandoffResult.Retryable(
					AmbientStepsProviderHandoffRetryableReason.PROVIDER_RESULT_MISMATCH,
				)
			}
			try {
				reader.read(preflight.drainWindow.providerWindow, command.observedAtMs)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				return AmbientStepsProviderHandoffResult.Retryable(
					AmbientStepsProviderHandoffRetryableReason.PROVIDER_READ_FAILED,
				)
			}
		}
		if (aggregate != null && (
			aggregate.provider != preflight.predecessor.provider ||
				aggregate.window != preflight.drainWindow?.providerWindow ||
				aggregate.observedAtMs != command.observedAtMs
			)
		) {
			return AmbientStepsProviderHandoffResult.Retryable(
				AmbientStepsProviderHandoffRetryableReason.PROVIDER_RESULT_MISMATCH,
			)
		}

		val commitLifecycle = lifecycleSnapshotOrNull()
			?: return AmbientStepsProviderHandoffResult.Retryable(
				AmbientStepsProviderHandoffRetryableReason.STORAGE_UNAVAILABLE,
			)
		if (commitLifecycle != lifecycle) {
			return AmbientStepsProviderHandoffResult.Stale(
				AmbientStepsProviderHandoffStaleReason.LIFECYCLE_CHANGED,
			)
		}
		return try {
			database.withTransaction {
				commitHandoff(preflight, command, commitLifecycle, aggregate)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RetractedAmbientStepsFactException) {
			AmbientStepsProviderHandoffResult.Stale(
				AmbientStepsProviderHandoffStaleReason.FACT_RETRACTED,
			)
		} catch (_: ConcurrentAmbientStepsImportException) {
			AmbientStepsProviderHandoffResult.Stale(
				AmbientStepsProviderHandoffStaleReason.CURRENT_STATE_CHANGED,
			)
		} catch (_: Exception) {
			AmbientStepsProviderHandoffResult.Retryable(
				AmbientStepsProviderHandoffRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	private suspend fun resolveHandoffPreflight(
		command: AmbientStepsProviderHandoffCommand,
		lifecycle: CollectedDataLifecycleSnapshot,
	): HandoffPreflight {
		val boundary = command.importBoundary()
		val successor = when (val resolved = resolveAuthority(boundary, lifecycle)) {
			is AuthorityResolution.Outcome -> {
				val importReason = (resolved.result as? AmbientStepsImportResult.Ineligible)?.reason
				return HandoffPreflight.Outcome(
					AmbientStepsProviderHandoffResult.Ineligible(
						if (importReason ==
							AmbientStepsImportIneligibleReason.DESTINATION_OWNER_INELIGIBLE
					) {
							AmbientStepsProviderHandoffIneligibleReason.DESTINATION_OWNER_INELIGIBLE
						} else {
							AmbientStepsProviderHandoffIneligibleReason.REPLACEMENT_NOT_ACCEPTED
						},
					),
				)
			}
			is AuthorityResolution.Ready -> resolved.authority
		}
		if (successor.registration.registrationGeneration !=
			command.successorRegistrationGeneration
		) {
			return handoffStale(AmbientStepsProviderHandoffStaleReason.REGISTRATION_CHANGED)
		}

		val brokerDao = database.sourceBrokerDao()
		val predecessorRegistration = brokerDao.registration(
			SOURCE_KIND,
			command.predecessorRegistrationGeneration,
		) ?: return handoffIneligible(
			AmbientStepsProviderHandoffIneligibleReason.PREDECESSOR_NOT_DRAINABLE,
		)
		val predecessorProvider = predecessorRegistration.ambientStepsProviderOrNull()
			?: return handoffIneligible(
				AmbientStepsProviderHandoffIneligibleReason.PREDECESSOR_NOT_DRAINABLE,
			)
		if (predecessorProvider == successor.provider ||
			predecessorRegistration.ownerScope != successor.registration.ownerScope ||
			predecessorRegistration.sourceInstanceId != successor.registration.sourceInstanceId ||
			predecessorRegistration.collectedDataEpoch != lifecycle.epoch ||
			predecessorRegistration.clockDomainId != command.observedBootId ||
			predecessorRegistration.providerResidency !=
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE ||
			predecessorRegistration.status !in setOf(
				ProviderRegistrationGenerationEntity.STATUS_RETIRING,
				ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			)
		) {
			return handoffIneligible(
				AmbientStepsProviderHandoffIneligibleReason.PREDECESSOR_NOT_DRAINABLE,
			)
		}
		val cutoverWallTimeMs = predecessorRegistration.retiredAtMs
			?: return handoffIneligible(
				AmbientStepsProviderHandoffIneligibleReason.PREDECESSOR_NOT_DRAINABLE,
			)
		val cutoverElapsedRealtimeNanos = predecessorRegistration.retiredElapsedRealtimeNanos
			?: return handoffIneligible(
				AmbientStepsProviderHandoffIneligibleReason.PREDECESSOR_NOT_DRAINABLE,
			)
		if (successor.registration.acceptedAtMs != cutoverWallTimeMs ||
			successor.registration.acceptedElapsedRealtimeNanos != cutoverElapsedRealtimeNanos ||
			cutoverWallTimeMs > command.observedAtMs ||
			cutoverElapsedRealtimeNanos > command.observedElapsedRealtimeNanos
		) {
			return handoffStale(AmbientStepsProviderHandoffStaleReason.REGISTRATION_CHANGED)
		}

		val stateDao = database.ambientStepsImportStateDao()
		val storedPredecessorCursor = stateDao.cursor(command.predecessorRegistrationGeneration)
		val successorStartTimeMs = roundForwardToSecond(
			listOfNotNull(
				cutoverWallTimeMs,
				successor.registration.acceptedAtMs,
				successor.authorizationEffectiveWallTimeMs,
				successor.policy.effectiveWallTimeMs,
				successor.consent.effectiveWallTimeMs,
				lifecycle.retainedFromMs,
			).max(),
		)
		if (successorStartTimeMs > command.observedAtMs) {
			return handoffIneligible(
				AmbientStepsProviderHandoffIneligibleReason.REPLACEMENT_NOT_ACCEPTED,
			)
		}
		val successorCursor = stateDao.cursor(command.successorRegistrationGeneration)

		val authLookupTime = cutoverElapsedRealtimeNanos.takeIf { it > 0L }?.minus(1L)
			?: return handoffIneligible(
				AmbientStepsProviderHandoffIneligibleReason.AUTHORITY_NOT_PROVABLE,
			)
		val predecessorAuthorization = brokerDao.authorizationAt(
			SOURCE_KIND,
			predecessorRegistration.registrationGeneration,
			predecessorRegistration.clockDomainId,
			authLookupTime,
		).toAuthorizationSnapshotOrNull()
			?: return handoffIneligible(
				AmbientStepsProviderHandoffIneligibleReason.AUTHORITY_NOT_PROVABLE,
			)
		val cutoffAuthorization = brokerDao.authorizationAt(
			SOURCE_KIND,
			predecessorRegistration.registrationGeneration,
			predecessorRegistration.clockDomainId,
			cutoverElapsedRealtimeNanos,
		).toAuthorizationSnapshotOrNull()
			?: return handoffIneligible(
				AmbientStepsProviderHandoffIneligibleReason.AUTHORITY_NOT_PROVABLE,
			)
		val cutoffDemandIds = cutoffAuthorization.members.mapNotNull { it.demandId }.distinct()
		val cutoffDemands = if (cutoffDemandIds.isEmpty()) {
			emptyList()
		} else {
			brokerDao.demandsByIds(cutoffDemandIds)
		}
		if (cutoffAuthorization.authorizationRevision <=
			predecessorAuthorization.authorizationRevision ||
			cutoffAuthorization.authorizationFingerprint !=
				successor.authorization.authorizationFingerprint ||
			cutoffAuthorization.purposeEligibilityMask !=
				SourceBrokerPurpose.MASK_AMBIENT_PRODUCT ||
			cutoffAuthorization.effectiveElapsedRealtimeNanos != cutoverElapsedRealtimeNanos ||
			cutoffAuthorization.members.any { it.effectiveWallTimeMs != cutoverWallTimeMs } ||
			cutoffDemands.any { it.providerMatches(predecessorProvider) }
		) {
			return handoffIneligible(
				AmbientStepsProviderHandoffIneligibleReason.AUTHORITY_NOT_PROVABLE,
			)
		}
		val demandIds = predecessorAuthorization.members.mapNotNull { it.demandId }.distinct()
		if (demandIds.isEmpty()) {
			return handoffIneligible(
				AmbientStepsProviderHandoffIneligibleReason.AUTHORITY_NOT_PROVABLE,
			)
		}
		val predecessorDemands = brokerDao.demandsByIds(demandIds)
		if (!predecessorAuthorization.isExactRetiredAmbientAuthorization(
				registration = predecessorRegistration,
			demands = predecessorDemands,
			provider = predecessorProvider,
			cutoverBootId = command.observedBootId,
			cutoverElapsedRealtimeNanos = cutoverElapsedRealtimeNanos,
			cutoverWallTimeMs = cutoverWallTimeMs,
		)) {
			return handoffIneligible(
				AmbientStepsProviderHandoffIneligibleReason.AUTHORITY_NOT_PROVABLE,
			)
		}
		val predecessorPolicyRevision = predecessorAuthorization.members
			.mapNotNull { it.sourcePolicyRevision }.distinct().singleOrNull()
			?: return handoffIneligible(
				AmbientStepsProviderHandoffIneligibleReason.AUTHORITY_NOT_PROVABLE,
			)
		val predecessorConsentEpoch = predecessorAuthorization.members
			.mapNotNull { it.consentEpoch }.distinct().singleOrNull()
			?: return handoffIneligible(
				AmbientStepsProviderHandoffIneligibleReason.AUTHORITY_NOT_PROVABLE,
			)
		val policyDao = database.sourcePolicyDao()
		val predecessorPolicy = policyDao.policyAtRevision(predecessorPolicyRevision, SOURCE_KIND)
			?: return handoffIneligible(
				AmbientStepsProviderHandoffIneligibleReason.AUTHORITY_NOT_PROVABLE,
			)
		val predecessorConsent = policyDao.consentEpoch(
			SOURCE_KIND,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
			predecessorConsentEpoch,
		) ?: return handoffIneligible(
			AmbientStepsProviderHandoffIneligibleReason.AUTHORITY_NOT_PROVABLE,
		)
		val predecessorRetention = database.ambientStepsFactRevisionDao().retentionAuthorityAt(
			AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			predecessorAuthorization.effectiveBootId,
			predecessorAuthorization.effectiveElapsedRealtimeNanos,
		)?.takeIf(AmbientStepsRetentionAuthorityIntegrity::isAuthentic)
			?.takeIf { retention ->
				retention.isActive &&
					retention.sourcePolicyRevision == predecessorPolicyRevision &&
					retention.ambientConsentEpoch == predecessorConsentEpoch &&
					retention.collectedDataEpoch == lifecycle.epoch
			}
			?: return handoffIneligible(
				AmbientStepsProviderHandoffIneligibleReason.AUTHORITY_NOT_PROVABLE,
			)
		if (!predecessorPolicy.isHistoricalAmbientPolicy(
				predecessorConsent,
				predecessorAuthorization,
				predecessorRetention,
				cutoverWallTimeMs,
			)
		) {
			return handoffIneligible(
				AmbientStepsProviderHandoffIneligibleReason.AUTHORITY_NOT_PROVABLE,
			)
		}

		val predecessor = HistoricalAmbientAuthority(
			registration = predecessorRegistration,
			provider = predecessorProvider,
			authorization = predecessorAuthorization,
			policy = predecessorPolicy,
			consent = predecessorConsent,
			retention = predecessorRetention,
			lifecycle = lifecycle,
		)
		if (storedPredecessorCursor?.status == AmbientStepsImportCursorEntity.STATUS_RETIRED) {
			return resolveCompletedHandoffReplay(
				command = command,
				predecessor = predecessor,
				successor = successor,
				predecessorCursor = storedPredecessorCursor,
				successorCursor = successorCursor,
			)
		}
		if (predecessorRegistration.status !=
			ProviderRegistrationGenerationEntity.STATUS_RETIRING
		) {
			return handoffStale(AmbientStepsProviderHandoffStaleReason.REGISTRATION_CHANGED)
		}
		if (successorCursor != null) {
			return handoffStale(AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED)
		}
		val predecessorCursor = storedPredecessorCursor ?: predecessor.initialCursor()
		if (!predecessorCursor.matchesHistoricalAuthority(
				predecessor,
				AmbientStepsImportCursorEntity.STATUS_ACTIVE,
			)
		) {
			return handoffStale(AmbientStepsProviderHandoffStaleReason.AUTHORIZATION_CHANGED)
		}
		val predecessorCutoffTimeMs = roundBackwardToSecond(cutoverWallTimeMs)
		if (predecessorCursor.importedThroughTimeMs > successorStartTimeMs ||
			predecessorCutoffTimeMs > successorStartTimeMs ||
			predecessorCursor.lastObservedAtMs > command.observedAtMs
		) {
			return handoffStale(AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED)
		}
		val retainedFloor = lifecycle.retainedFromMs?.let(::roundForwardToSecond)
		val noReadGapReason = when {
			predecessorCursor.lastObservedZoneId ==
				AmbientStepsImportGapEntity.ZONE_AUTHORITY_UNOBSERVED ->
				AmbientStepsImportGapReason.INITIAL_ZONE_AUTHORITY_UNOBSERVED
			predecessorCursor.lastObservedZoneId != command.zoneId.id ->
				AmbientStepsImportGapReason.ZONE_CHANGED
			retainedFloor != null && retainedFloor > predecessorCursor.importedThroughTimeMs ->
				AmbientStepsImportGapReason.RETENTION_ADVANCED
			else -> null
		}
		val drainWindow = if (noReadGapReason == null &&
			predecessorCutoffTimeMs > predecessorCursor.importedThroughTimeMs
		) {
			planner.planProgressive(
				segmentStartTimeMs = predecessorCursor.segmentStartTimeMs,
				importedThroughTimeMs = predecessorCursor.importedThroughTimeMs,
				throughTimeMs = predecessorCutoffTimeMs,
				zoneId = command.zoneId,
			).windows.singleOrNull()
		} else {
			null
		}
		return HandoffPreflight.Ready(
			predecessor = predecessor,
			successor = successor,
			storedPredecessorCursor = storedPredecessorCursor,
			predecessorCursor = predecessorCursor,
			drainWindow = drainWindow,
			noReadGapReason = noReadGapReason,
			predecessorCutoffTimeMs = predecessorCutoffTimeMs,
			successorStartTimeMs = successorStartTimeMs,
			cutoverWallTimeMs = cutoverWallTimeMs,
			cutoverElapsedRealtimeNanos = cutoverElapsedRealtimeNanos,
		)
	}

	private suspend fun resolveCompletedHandoffReplay(
		command: AmbientStepsProviderHandoffCommand,
		predecessor: HistoricalAmbientAuthority,
		successor: AmbientAuthority,
		predecessorCursor: AmbientStepsImportCursorEntity,
		successorCursor: AmbientStepsImportCursorEntity?,
	): HandoffPreflight {
		if (!predecessorCursor.matchesHistoricalAuthority(
				predecessor,
				AmbientStepsImportCursorEntity.STATUS_RETIRED,
			)
		) {
			return handoffStale(AmbientStepsProviderHandoffStaleReason.AUTHORIZATION_CHANGED)
		}
		val exactSuccessorCursor = successorCursor
			?: return handoffStale(AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED)
		if (!exactSuccessorCursor.matchesRegistrationAuthority(successor)) {
			return handoffStale(AmbientStepsProviderHandoffStaleReason.REGISTRATION_CHANGED)
		}
		if (!exactSuccessorCursor.matchesCurrentAuthorization(successor)) {
			return handoffStale(AmbientStepsProviderHandoffStaleReason.AUTHORIZATION_CHANGED)
		}
		if (predecessorCursor.lastObservedAtMs > command.observedAtMs ||
			exactSuccessorCursor.lastObservedAtMs > command.observedAtMs
		) {
			return handoffStale(AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED)
		}

		val stateDao = database.ambientStepsImportStateDao()
		if (!hasExactCursorSiblings(predecessorCursor) ||
			!hasExactCursorSiblings(exactSuccessorCursor)
		) {
			return handoffStale(AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED)
		}
		val providerMarker = stateDao.gaps(exactSuccessorCursor.registrationGeneration)
			.singleOrNull { gap ->
				gap.reason == AmbientStepsImportGapEntity.REASON_PROVIDER_CHANGED &&
					gap.predecessorRegistrationGeneration ==
					predecessorCursor.registrationGeneration &&
					gap.predecessorProvider == predecessorCursor.provider
			}
			?: return handoffStale(AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED)
		val markerIsExact = providerMarker.registrationGeneration ==
			exactSuccessorCursor.registrationGeneration &&
			providerMarker.provider == exactSuccessorCursor.provider &&
			providerMarker.sourceInstanceId == exactSuccessorCursor.sourceInstanceId &&
			providerMarker.collectedDataEpoch == exactSuccessorCursor.collectedDataEpoch &&
			providerMarker.gapSequence <= exactSuccessorCursor.lastGapSequence &&
			providerMarker.gapStartTimeMs == providerMarker.gapEndTimeMs &&
			providerMarker.gapStartTimeMs == predecessorCursor.importedThroughTimeMs &&
			providerMarker.gapStartTimeMs >= requireNotNull(successor.registration.acceptedAtMs) &&
			providerMarker.gapStartTimeMs <= exactSuccessorCursor.importedThroughTimeMs &&
			providerMarker.previousClockDomainId ==
				exactSuccessorCursor.registrationClockDomainId &&
			providerMarker.nextClockDomainId == exactSuccessorCursor.registrationClockDomainId &&
			providerMarker.previousZoneId == providerMarker.nextZoneId &&
			providerMarker.recordedAtMs == predecessorCursor.updatedAtMs &&
			providerMarker.recordedAtMs <= exactSuccessorCursor.updatedAtMs
		if (!markerIsExact) {
			return handoffStale(AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED)
		}

		return HandoffPreflight.Outcome(
			AmbientStepsProviderHandoffResult.Completed(
				predecessorRegistrationGeneration = predecessorCursor.registrationGeneration,
				successorRegistrationGeneration = exactSuccessorCursor.registrationGeneration,
				successorStartTimeMs = providerMarker.gapStartTimeMs,
				drainDisposition = AmbientStepsProviderDrainDisposition.ALREADY_COVERED,
				drainedWindow = null,
				logicalFactId = null,
				replayed = true,
			),
		)
	}

	private suspend fun hasExactCursorSiblings(
		cursor: AmbientStepsImportCursorEntity,
	): Boolean {
		val stateDao = database.ambientStepsImportStateDao()
		val gaps = stateDao.gaps(cursor.registrationGeneration)
		if (gaps.size.toLong() != cursor.lastGapSequence || gaps.withIndex().any { (index, gap) ->
			gap.gapSequence != index + 1L ||
				gap.registrationGeneration != cursor.registrationGeneration ||
				gap.provider != cursor.provider || gap.sourceInstanceId != cursor.sourceInstanceId ||
				gap.collectedDataEpoch != cursor.collectedDataEpoch ||
				gap.recordedAtMs > cursor.updatedAtMs
		}) return false

		val transitions = stateDao.authorityTransitions(cursor.registrationGeneration)
		if (transitions.size.toLong() != cursor.authorityTransitionSequence ||
			transitions.withIndex().any { (index, transition) ->
				transition.transitionSequence != index + 1L ||
					transition.registrationGeneration != cursor.registrationGeneration ||
					transition.provider != cursor.provider ||
					transition.sourceInstanceId != cursor.sourceInstanceId ||
					transition.collectedDataEpoch != cursor.collectedDataEpoch ||
					transition.recordedAtMs > cursor.updatedAtMs
			}
		) return false
		val latestTransition = transitions.lastOrNull() ?: return true
		return latestTransition.toAuthorizationRevision == cursor.authorizationRevision &&
			latestTransition.toAuthorizationFingerprint == cursor.authorizationFingerprint &&
			latestTransition.toAuthorizationEffectiveBootId ==
			cursor.authorizationEffectiveBootId &&
			latestTransition.toAuthorizationEffectiveElapsedRealtimeNanos ==
			cursor.authorizationEffectiveElapsedRealtimeNanos &&
			latestTransition.toAuthorizationEffectiveWallTimeMs ==
			cursor.authorizationEffectiveWallTimeMs &&
			latestTransition.toSourcePolicyRevision == cursor.sourcePolicyRevision &&
			latestTransition.toAmbientConsentEpoch == cursor.ambientConsentEpoch &&
			latestTransition.effectiveBoundaryTimeMs == cursor.eligibleFromTimeMs &&
			latestTransition.toContinuitySegmentGeneration <= cursor.continuitySegmentGeneration
	}

	private suspend fun commitHandoff(
		preflight: HandoffPreflight.Ready,
		command: AmbientStepsProviderHandoffCommand,
		lifecycle: CollectedDataLifecycleSnapshot,
		aggregate: AmbientStepsProviderAggregate?,
	): AmbientStepsProviderHandoffResult {
		val current = resolveHandoffPreflight(command, lifecycle)
		if (current !is HandoffPreflight.Ready || current != preflight) {
			return AmbientStepsProviderHandoffResult.Stale(
				AmbientStepsProviderHandoffStaleReason.CURRENT_STATE_CHANGED,
			)
		}
		if (preflight.predecessor.registration.status !=
			ProviderRegistrationGenerationEntity.STATUS_RETIRING
		) {
			return AmbientStepsProviderHandoffResult.Stale(
				AmbientStepsProviderHandoffStaleReason.REGISTRATION_CHANGED,
			)
		}

		val stateDao = database.ambientStepsImportStateDao()
		if (preflight.storedPredecessorCursor == null &&
			stateDao.insertCursor(preflight.predecessorCursor) == INSERT_IGNORED
		) {
			throw ConcurrentAmbientStepsImportException()
		}
		var predecessorCursor = requireNotNull(
			stateDao.cursor(preflight.predecessor.registration.registrationGeneration),
		)
		var logicalFactId: String? = null
		var changed = false
		if (preflight.drainWindow != null && aggregate != null) {
			val application = applyHandoffAggregate(
				authority = preflight.successor,
				cursor = predecessorCursor,
				window = preflight.drainWindow,
				aggregate = aggregate,
				lifecycle = lifecycle,
			)
			predecessorCursor = application.cursor
			logicalFactId = application.logicalFactId
			changed = application.changed
		}

		val missingStartTimeMs = predecessorCursor.importedThroughTimeMs
		if (missingStartTimeMs < preflight.successorStartTimeMs) {
			predecessorCursor = advanceAcrossGap(
				cursor = predecessorCursor,
				reason = preflight.noReadGapReason
					?: AmbientStepsImportGapReason.AUTHORITY_BOUNDARY_NOT_DRAINED,
				toTimeMs = preflight.successorStartTimeMs,
				nextZoneId = command.zoneId.id,
				boundary = command.importBoundary(preflight.successorStartTimeMs),
			)
		}
		val appliedAtMs = appliedAtMs(
			command.importBoundary(preflight.successorStartTimeMs),
			preflight.successorStartTimeMs,
		)
		if (stateDao.retireExact(
				registrationGeneration = predecessorCursor.registrationGeneration,
				expectedCursorRevision = predecessorCursor.cursorRevision,
				expectedImportedThroughTimeMs = predecessorCursor.importedThroughTimeMs,
				expectedUpdatedAtMs = predecessorCursor.updatedAtMs,
				newCursorRevision = Math.addExact(predecessorCursor.cursorRevision, 1L),
				updatedAtMs = appliedAtMs,
			) != 1
		) {
			throw ConcurrentAmbientStepsImportException()
		}

		var successorCursor = preflight.successor.initialCursor(command.zoneId.id)
		if (stateDao.insertCursor(successorCursor) == INSERT_IGNORED) {
			throw ConcurrentAmbientStepsImportException()
		}
		if (successorCursor.importedThroughTimeMs < preflight.successorStartTimeMs) {
			val retainedFloor = lifecycle.retainedFromMs?.let(::roundForwardToSecond)
			successorCursor = advanceAcrossGap(
				cursor = successorCursor,
				reason = if (retainedFloor != null &&
					retainedFloor == preflight.successorStartTimeMs
				) {
					AmbientStepsImportGapReason.RETENTION_ADVANCED
				} else {
					AmbientStepsImportGapReason.AUTHORITY_BOUNDARY_NOT_DRAINED
				},
				toTimeMs = preflight.successorStartTimeMs,
				nextZoneId = command.zoneId.id,
				boundary = command.importBoundary(preflight.successorStartTimeMs),
			)
		}
		beginProviderChangedSegment(
			cursor = successorCursor,
			predecessor = preflight.predecessor,
			boundary = command.importBoundary(preflight.successorStartTimeMs),
		)

		val disposition = when {
			preflight.drainWindow == null &&
				preflight.predecessorCursor.importedThroughTimeMs ==
				preflight.successorStartTimeMs ->
				AmbientStepsProviderDrainDisposition.ALREADY_COVERED
			preflight.drainWindow == null || aggregate == null ->
				AmbientStepsProviderDrainDisposition.UNAVAILABLE
			preflight.drainWindow?.endTimeMs != preflight.successorStartTimeMs ->
				AmbientStepsProviderDrainDisposition.PARTIAL
			!changed -> AmbientStepsProviderDrainDisposition.UNCHANGED
			else -> AmbientStepsProviderDrainDisposition.APPLIED
		}
		return AmbientStepsProviderHandoffResult.Completed(
			predecessorRegistrationGeneration =
				preflight.predecessor.registration.registrationGeneration,
			successorRegistrationGeneration =
				preflight.successor.registration.registrationGeneration,
			successorStartTimeMs = preflight.successorStartTimeMs,
			drainDisposition = disposition,
			drainedWindow = preflight.drainWindow.takeIf { aggregate != null },
			logicalFactId = logicalFactId,
			replayed = false,
		)
	}

	private suspend fun applyHandoffAggregate(
		authority: AmbientAuthority,
		cursor: AmbientStepsImportCursorEntity,
		window: AmbientStepsStructuralWindow,
		aggregate: AmbientStepsProviderAggregate,
		lifecycle: CollectedDataLifecycleSnapshot,
	): HandoffFactApplication {
		val logicalFactId = AmbientStepsFactIntegrity.logicalFactId(
			provider = cursor.provider,
			registrationGeneration = cursor.registrationGeneration,
			continuitySegmentGeneration = cursor.continuitySegmentGeneration,
			sourceInstanceId = cursor.sourceInstanceId,
			windowStartTimeMs = window.startTimeMs,
			structuralEpochDay = window.day.epochDay,
			storedZoneId = window.day.zoneId,
			collectedDataEpoch = cursor.collectedDataEpoch,
		)
		val factDao = database.ambientStepsFactRevisionDao()
		val latest = factDao.latest(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			logicalFactId,
		)
		if (latest?.operation == AmbientStepsFactRevisionEntity.OPERATION_RETRACT) {
			throw RetractedAmbientStepsFactException()
		}
		val unchanged = latest != null && latest.matchesAggregate(
			authority = authority,
			cursor = cursor,
			window = window,
			aggregate = aggregate,
		)
		if (!unchanged) {
			val nextRevision = Math.addExact(latest?.semanticRevision ?: 0L, 1L)
			val fact = ambientFact(
				authority = authority,
				cursor = cursor,
				window = window,
				aggregate = aggregate,
				semanticRevision = nextRevision,
				appliedAtMs = maxOf(clock.currentTimeMillis(), aggregate.observedAtMs),
			)
			val evidenceDao = database.sourceEvidenceStateDao()
			val lifecycleChanged = evidenceDao.synchronizeLifecycle(
				epoch = lifecycle.epoch,
				retainedFromMs = lifecycle.retainedFromMs,
				updatedAtMs = fact.appliedAtMs,
			)
			val roomLifecycle = requireNotNull(evidenceDao.get())
			if (roomLifecycle.collectedDataEpoch != lifecycle.epoch ||
				roomLifecycle.retainedFromMs != lifecycle.retainedFromMs ||
				factDao.insert(fact) == INSERT_IGNORED ||
				(!lifecycleChanged && evidenceDao.incrementRevision(fact.appliedAtMs) != 1)
			) {
				throw ConcurrentAmbientStepsImportException()
			}
		}
		val appliedAtMs = maxOf(clock.currentTimeMillis(), aggregate.observedAtMs)
		val nextCursorRevision = Math.addExact(cursor.cursorRevision, 1L)
		if (database.ambientStepsImportStateDao().advanceExact(
				registrationGeneration = cursor.registrationGeneration,
				expectedContinuitySegmentGeneration = cursor.continuitySegmentGeneration,
				expectedLastGapSequence = cursor.lastGapSequence,
				expectedCursorRevision = cursor.cursorRevision,
				expectedImportedThroughTimeMs = cursor.importedThroughTimeMs,
				expectedObservedAtMs = cursor.lastObservedAtMs,
				expectedUpdatedAtMs = cursor.updatedAtMs,
				expectedBootId = cursor.lastObservedBootId,
				expectedZoneId = cursor.lastObservedZoneId,
				newImportedThroughTimeMs = window.endTimeMs,
				newObservedAtMs = aggregate.observedAtMs,
				newCursorRevision = nextCursorRevision,
				updatedAtMs = appliedAtMs,
			) != 1
		) {
			throw ConcurrentAmbientStepsImportException()
		}
		return HandoffFactApplication(
			cursor = requireNotNull(
				database.ambientStepsImportStateDao().cursor(cursor.registrationGeneration),
			),
			logicalFactId = logicalFactId,
			changed = !unchanged,
		)
	}

	private suspend fun resolvePreflight(
		boundary: AmbientStepsImportBoundary,
		lifecycle: CollectedDataLifecycleSnapshot,
	): Preflight {
		val authority = when (val resolved = resolveAuthority(boundary, lifecycle)) {
			is AuthorityResolution.Outcome -> return Preflight.Outcome(resolved.result)
			is AuthorityResolution.Ready -> resolved.authority
		}
		val storedCursor = database.ambientStepsImportStateDao().cursor(
			authority.registration.registrationGeneration,
		)
		val cursorResolution = prepareCursor(authority, storedCursor, boundary)
		if (cursorResolution is CursorResolution.Outcome) {
			return Preflight.Outcome(cursorResolution.result)
		}
		cursorResolution as CursorResolution.Ready
		val effectiveCursor = cursorResolution.cursor
		val retainedFromMs = lifecycle.retainedFromMs?.let(::roundForwardToSecond)
		if (retainedFromMs != null && retainedFromMs > effectiveCursor.importedThroughTimeMs) {
			advanceAcrossGap(
				cursor = effectiveCursor,
				reason = AmbientStepsImportGapReason.RETENTION_ADVANCED,
				toTimeMs = retainedFromMs,
				nextZoneId = effectiveCursor.lastObservedZoneId,
				boundary = boundary,
			)
			return Preflight.Outcome(
				AmbientStepsImportResult.Gap(
					reason = AmbientStepsImportGapReason.RETENTION_ADVANCED,
					fromTimeMs = effectiveCursor.importedThroughTimeMs,
					toTimeMs = retainedFromMs,
				),
			)
		}
		if (effectiveCursor.lastObservedZoneId != boundary.zoneId.id) {
			if (boundary.throughTimeMs < effectiveCursor.importedThroughTimeMs) {
				return Preflight.Outcome(
					AmbientStepsImportResult.NoEvidence(
						AmbientStepsImportNoEvidenceReason.NO_WINDOW_AVAILABLE,
					),
				)
			}
			advanceAcrossGap(
				cursor = effectiveCursor,
				reason = AmbientStepsImportGapReason.ZONE_CHANGED,
				toTimeMs = boundary.throughTimeMs,
				nextZoneId = boundary.zoneId.id,
				boundary = boundary,
			)
			return Preflight.Outcome(
				AmbientStepsImportResult.Gap(
					reason = AmbientStepsImportGapReason.ZONE_CHANGED,
					fromTimeMs = effectiveCursor.importedThroughTimeMs,
					toTimeMs = boundary.throughTimeMs,
				),
			)
		}
		if (boundary.throughTimeMs < effectiveCursor.importedThroughTimeMs) {
			return Preflight.Outcome(
				AmbientStepsImportResult.NoEvidence(
					AmbientStepsImportNoEvidenceReason.NO_WINDOW_AVAILABLE,
				),
			)
		}
		val plan = planner.planProgressive(
			segmentStartTimeMs = effectiveCursor.segmentStartTimeMs,
			importedThroughTimeMs = effectiveCursor.importedThroughTimeMs,
			throughTimeMs = boundary.throughTimeMs,
			zoneId = boundary.zoneId,
		)
		val window = plan.windows.singleOrNull()
			?: return Preflight.Outcome(
				AmbientStepsImportResult.NoEvidence(
					AmbientStepsImportNoEvidenceReason.NO_WINDOW_AVAILABLE,
				),
			)
		return Preflight.Ready(
			authority = authority,
			storedCursor = effectiveCursor,
			window = window,
		)
	}

	private suspend fun commitRead(
		preflight: Preflight.Ready,
		boundary: AmbientStepsImportBoundary,
		lifecycle: CollectedDataLifecycleSnapshot,
		aggregate: AmbientStepsProviderAggregate?,
	): AmbientStepsImportResult {
		val current = when (val resolved = resolveAuthority(boundary, lifecycle)) {
			is AuthorityResolution.Outcome -> return stale(resolved.result.toStaleReason())
			is AuthorityResolution.Ready -> resolved.authority
		}
		if (current != preflight.authority) {
			return stale(authorityDifference(preflight.authority, current))
		}
		val currentCursor = database.ambientStepsImportStateDao().cursor(
			current.registration.registrationGeneration,
		)
		if (currentCursor != preflight.storedCursor) {
			return stale(AmbientStepsImportStaleReason.CURSOR_CHANGED)
		}
		if (aggregate == null) {
			if (preflight.window.endTimeMs == preflight.window.day.endTimeMs) {
				advanceAcrossGap(
					cursor = preflight.storedCursor,
					reason = AmbientStepsImportGapReason.PROVIDER_NO_EVIDENCE,
					toTimeMs = preflight.window.endTimeMs,
					nextZoneId = preflight.window.day.zoneId,
					boundary = boundary,
				)
				return AmbientStepsImportResult.Gap(
					reason = AmbientStepsImportGapReason.PROVIDER_NO_EVIDENCE,
					fromTimeMs = preflight.storedCursor.importedThroughTimeMs,
					toTimeMs = preflight.window.endTimeMs,
				)
			}
			return AmbientStepsImportResult.NoEvidence(
				AmbientStepsImportNoEvidenceReason.PROVIDER_RETURNED_NO_EVIDENCE,
			)
		}

		val appliedAtMs = maxOf(clock.currentTimeMillis(), aggregate.observedAtMs)
		val cursor = preflight.storedCursor

		val logicalFactId = AmbientStepsFactIntegrity.logicalFactId(
			provider = cursor.provider,
			registrationGeneration = cursor.registrationGeneration,
			continuitySegmentGeneration = cursor.continuitySegmentGeneration,
			sourceInstanceId = cursor.sourceInstanceId,
			windowStartTimeMs = preflight.window.startTimeMs,
			structuralEpochDay = preflight.window.day.epochDay,
			storedZoneId = preflight.window.day.zoneId,
			collectedDataEpoch = cursor.collectedDataEpoch,
		)
		val factDao = database.ambientStepsFactRevisionDao()
		val latest = factDao.latest(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			logicalFactId,
		)
		if (latest?.operation == AmbientStepsFactRevisionEntity.OPERATION_RETRACT) {
			throw RetractedAmbientStepsFactException()
		}
		val unchanged = latest != null && latest.matchesAggregate(
			authority = current,
			cursor = cursor,
			window = preflight.window,
			aggregate = aggregate,
		)
		if (unchanged && preflight.window.endTimeMs == cursor.importedThroughTimeMs &&
			aggregate.observedAtMs == cursor.lastObservedAtMs
		) {
			return AmbientStepsImportResult.Unchanged(
				window = preflight.window.providerWindow,
				cursorRevision = cursor.cursorRevision,
			)
		}
		val semanticRevision = if (unchanged) {
			requireNotNull(latest).semanticRevision
		} else {
			val nextRevision = Math.addExact(latest?.semanticRevision ?: 0L, 1L)
			val fact = ambientFact(
				authority = current,
				cursor = cursor,
				window = preflight.window,
				aggregate = aggregate,
				semanticRevision = nextRevision,
				appliedAtMs = appliedAtMs,
			)
			val evidenceDao = database.sourceEvidenceStateDao()
			val lifecycleChanged = evidenceDao.synchronizeLifecycle(
				epoch = lifecycle.epoch,
				retainedFromMs = lifecycle.retainedFromMs,
				updatedAtMs = appliedAtMs,
			)
			val roomLifecycle = requireNotNull(evidenceDao.get())
			if (roomLifecycle.collectedDataEpoch != lifecycle.epoch ||
				roomLifecycle.retainedFromMs != lifecycle.retainedFromMs
			) {
				throw ConcurrentAmbientStepsImportException()
			}
			if (factDao.insert(fact) == INSERT_IGNORED) {
				throw ConcurrentAmbientStepsImportException()
			}
			if (!lifecycleChanged && evidenceDao.incrementRevision(appliedAtMs) != 1) {
				throw ConcurrentAmbientStepsImportException()
			}
			nextRevision
		}

		val nextCursorRevision = Math.addExact(cursor.cursorRevision, 1L)
		if (database.ambientStepsImportStateDao().advanceExact(
				registrationGeneration = cursor.registrationGeneration,
				expectedContinuitySegmentGeneration = cursor.continuitySegmentGeneration,
				expectedLastGapSequence = cursor.lastGapSequence,
				expectedCursorRevision = cursor.cursorRevision,
				expectedImportedThroughTimeMs = cursor.importedThroughTimeMs,
				expectedObservedAtMs = cursor.lastObservedAtMs,
				expectedUpdatedAtMs = cursor.updatedAtMs,
				expectedBootId = cursor.lastObservedBootId,
				expectedZoneId = cursor.lastObservedZoneId,
				newImportedThroughTimeMs = preflight.window.endTimeMs,
				newObservedAtMs = aggregate.observedAtMs,
				newCursorRevision = nextCursorRevision,
				updatedAtMs = appliedAtMs,
			) != 1
		) {
			throw ConcurrentAmbientStepsImportException()
		}
		return if (unchanged) {
			AmbientStepsImportResult.Unchanged(preflight.window.providerWindow, nextCursorRevision)
		} else {
			AmbientStepsImportResult.Applied(
				window = preflight.window.providerWindow,
				logicalFactId = logicalFactId,
				semanticRevision = semanticRevision,
				cursorRevision = nextCursorRevision,
			)
		}
	}

	private suspend fun resolveAuthority(
		boundary: AmbientStepsImportBoundary,
		lifecycle: CollectedDataLifecycleSnapshot,
	): AuthorityResolution {
		val brokerDao = database.sourceBrokerDao()
		val state = database.sourceRegistrationStateDao().get(SOURCE_KIND, OWNER_SCOPE)
			?: return AuthorityResolution.Outcome(
				ineligible(AmbientStepsImportIneligibleReason.NO_ACTIVE_REGISTRATION),
			)
		val registration = brokerDao.registration(SOURCE_KIND, state.registrationGeneration)
			?: return AuthorityResolution.Outcome(
				ineligible(AmbientStepsImportIneligibleReason.NO_ACTIVE_REGISTRATION),
			)
		if (!registration.isAcceptedAmbientState(state, lifecycle, boundary)) {
			return AuthorityResolution.Outcome(
				ineligible(AmbientStepsImportIneligibleReason.REGISTRATION_NOT_ACCEPTED),
			)
		}
		val provider = registration.ambientStepsProviderOrNull()
			?: return AuthorityResolution.Outcome(
				ineligible(AmbientStepsImportIneligibleReason.REGISTRATION_NOT_ACCEPTED),
			)

		val policyDao = database.sourcePolicyDao()
		val policyAuthority = policyDao.authority()
		if (policyAuthority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE) {
			return AuthorityResolution.Outcome(
				ineligible(AmbientStepsImportIneligibleReason.POLICY_INACTIVE),
			)
		}
		val policy = policyDao.policyAtRevision(policyAuthority.currentPolicyRevision, SOURCE_KIND)
		if (policy == null || !policy.isCurrentAmbientPolicy(boundary)) {
			return AuthorityResolution.Outcome(
				ineligible(AmbientStepsImportIneligibleReason.RETENTION_AUTHORITY_UNAVAILABLE),
			)
		}
		if (state.appliedRevision != policy.policyRevision) {
			return AuthorityResolution.Outcome(
				ineligible(AmbientStepsImportIneligibleReason.AMBIENT_POLICY_INELIGIBLE),
			)
		}
		val consent = policyDao.latestConsentEpoch(SOURCE_KIND, SourceBrokerPurpose.AMBIENT_PRODUCT)
		if (!consent.isExactEligibleAmbient(policy, boundary)) {
			return AuthorityResolution.Outcome(
				ineligible(AmbientStepsImportIneligibleReason.AMBIENT_CONSENT_INELIGIBLE),
			)
		}
		val retention = database.ambientStepsFactRevisionDao().latestRetentionAuthority(
			AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		)
		if (retention == null ||
			!AmbientStepsRetentionAuthorityIntegrity.isAuthentic(retention) ||
			!retention.isActive ||
			retention.sourcePolicyRevision != policy.policyRevision ||
			retention.ambientConsentEpoch != consent.epoch ||
			retention.collectedDataEpoch != lifecycle.epoch ||
			retention.effectiveBootId != boundary.observedBootId ||
			retention.effectiveElapsedRealtimeNanos > boundary.observedElapsedRealtimeNanos ||
			retention.effectiveWallTimeMs > boundary.observedAtMs
		) {
			return AuthorityResolution.Outcome(
				ineligible(AmbientStepsImportIneligibleReason.AMBIENT_POLICY_INELIGIBLE),
			)
		}

		val demands = SourceProviderPurposeScope.selectDemands(
			SOURCE_KIND,
			OWNER_SCOPE,
			brokerDao.authorizationDemands(SOURCE_KIND),
		)
		if (demands.isEmpty() || demands.any { demand ->
			!demand.isRetentionEligibleAmbientDemand(policy, retention, provider, boundary)
		}) {
			return AuthorityResolution.Outcome(
				ineligible(AmbientStepsImportIneligibleReason.AUTHORIZATION_INELIGIBLE),
			)
		}
		val authorization = brokerDao.latestAuthorization(SOURCE_KIND, state.registrationGeneration)
			.toAuthorizationSnapshotOrNull()
		if (!authorization.isRetentionEligibleAmbientAuthorization(
				demands,
				policy,
				retention,
				registration,
				boundary,
			)
		) {
			return AuthorityResolution.Outcome(
				ineligible(AmbientStepsImportIneligibleReason.AUTHORIZATION_INELIGIBLE),
			)
		}
		val exactAuthorization = requireNotNull(authorization)
		val authorizationEffectiveWallTimeMs = exactAuthorization.members.first().effectiveWallTimeMs
		if (authorizationEffectiveWallTimeMs < policy.effectiveWallTimeMs ||
			authorizationEffectiveWallTimeMs < requireNotNull(consent).effectiveWallTimeMs
		) {
			return AuthorityResolution.Outcome(
				ineligible(AmbientStepsImportIneligibleReason.AUTHORIZATION_INELIGIBLE),
			)
		}

		val owner = database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
		)
		if (owner?.owner != SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS) {
			return AuthorityResolution.Outcome(
				ineligible(AmbientStepsImportIneligibleReason.DESTINATION_OWNER_INELIGIBLE),
			)
		}
		val evidenceState = database.sourceEvidenceStateDao().get()
		if (evidenceState != null && (
			evidenceState.collectedDataEpoch > lifecycle.epoch ||
				evidenceState.retainedFromMs.isAfter(lifecycle.retainedFromMs)
			)
		) {
			return AuthorityResolution.Outcome(
				stale(AmbientStepsImportStaleReason.LIFECYCLE_CHANGED),
			)
		}
		return AuthorityResolution.Ready(
			AmbientAuthority(
				state = state,
				registration = registration,
				provider = provider,
				authorization = exactAuthorization,
				authorizationEffectiveWallTimeMs = authorizationEffectiveWallTimeMs,
				policy = policy,
				consent = requireNotNull(consent),
				retention = retention,
				demands = demands,
				ownerGeneration = owner.ownerGeneration,
				lifecycle = lifecycle,
			),
		)
	}

	private suspend fun prepareCursor(
		authority: AmbientAuthority,
		cursor: AmbientStepsImportCursorEntity?,
		boundary: AmbientStepsImportBoundary,
	): CursorResolution {
		if (cursor == null) {
			val privacyFloor = authority.privacyFloorTimeMs
			if (privacyFloor > boundary.throughTimeMs) {
				return CursorResolution.Outcome(
					AmbientStepsImportResult.NoEvidence(
						AmbientStepsImportNoEvidenceReason.NO_WINDOW_AVAILABLE,
					),
				)
			}
			val initialCursor = authority.initialCursor(
				zoneId = boundary.zoneId.id.takeIf { privacyFloor == boundary.throughTimeMs },
			)
			if (database.ambientStepsImportStateDao().insertCursor(initialCursor) == INSERT_IGNORED) {
				throw ConcurrentAmbientStepsImportException()
			}
			if (privacyFloor < boundary.throughTimeMs) {
				advanceAcrossGap(
					cursor = initialCursor,
					reason = AmbientStepsImportGapReason.INITIAL_ZONE_AUTHORITY_UNOBSERVED,
					toTimeMs = boundary.throughTimeMs,
					nextZoneId = boundary.zoneId.id,
					boundary = boundary,
				)
				return CursorResolution.Outcome(
					AmbientStepsImportResult.Gap(
						reason = AmbientStepsImportGapReason.INITIAL_ZONE_AUTHORITY_UNOBSERVED,
						fromTimeMs = privacyFloor,
						toTimeMs = boundary.throughTimeMs,
					),
				)
			}
			return CursorResolution.Outcome(
				AmbientStepsImportResult.NoEvidence(
					AmbientStepsImportNoEvidenceReason.NO_WINDOW_AVAILABLE,
				),
			)
		}
		if (!cursor.matchesRegistrationAuthority(authority)) {
			return CursorResolution.Outcome(
				stale(AmbientStepsImportStaleReason.REGISTRATION_CHANGED),
			)
		}
		if (boundary.observedAtMs < cursor.lastObservedAtMs) {
			return CursorResolution.Outcome(
				stale(AmbientStepsImportStaleReason.CURSOR_CHANGED),
			)
		}
		if (cursor.matchesCurrentAuthorization(authority)) {
			return CursorResolution.Ready(cursor)
		}
		if (authority.authorization.authorizationRevision <= cursor.authorizationRevision) {
			return CursorResolution.Outcome(
				stale(AmbientStepsImportStaleReason.AUTHORIZATION_CHANGED),
			)
		}
		val boundaryTimeMs = authority.privacyFloorTimeMs
		if (cursor.importedThroughTimeMs < boundaryTimeMs) {
			val advanced = advanceAcrossGap(
				cursor = cursor,
				reason = AmbientStepsImportGapReason.AUTHORITY_BOUNDARY_NOT_DRAINED,
				toTimeMs = boundaryTimeMs,
				nextZoneId = cursor.lastObservedZoneId,
				boundary = boundary,
			)
			val transition = authority.transitionFrom(advanced, boundary.observedAtMs)
			rotateAuthority(
				cursor = advanced,
				authority = authority,
				transition = transition,
				observedAtMs = boundary.observedAtMs,
				appliedAtMs = appliedAtMs(boundary, boundaryTimeMs),
			)
			return CursorResolution.Outcome(
				AmbientStepsImportResult.Gap(
					reason = AmbientStepsImportGapReason.AUTHORITY_BOUNDARY_NOT_DRAINED,
					fromTimeMs = cursor.importedThroughTimeMs,
					toTimeMs = boundaryTimeMs,
				),
			)
		}
		if (cursor.importedThroughTimeMs > boundaryTimeMs) {
			return CursorResolution.Outcome(
				stale(AmbientStepsImportStaleReason.AUTHORIZATION_CHANGED),
			)
		}
		val transition = authority.transitionFrom(cursor, boundary.observedAtMs)
		return CursorResolution.Ready(
			rotateAuthority(
				cursor = cursor,
				authority = authority,
				transition = transition,
				observedAtMs = boundary.observedAtMs,
				appliedAtMs = appliedAtMs(boundary, boundaryTimeMs),
			),
		)
	}

	private suspend fun advanceAcrossGap(
		cursor: AmbientStepsImportCursorEntity,
		reason: AmbientStepsImportGapReason,
		toTimeMs: Long,
		nextZoneId: String,
		boundary: AmbientStepsImportBoundary,
	): AmbientStepsImportCursorEntity {
		require(toTimeMs >= cursor.importedThroughTimeMs)
		val storedReason = reason.toStoredGapReason()
		val nextGapSequence = Math.addExact(cursor.lastGapSequence, 1L)
		val nextContinuityGeneration = Math.addExact(cursor.continuitySegmentGeneration, 1L)
		val recordedAtMs = appliedAtMs(boundary, toTimeMs)
		val previousZoneId = if (
			reason == AmbientStepsImportGapReason.INITIAL_ZONE_AUTHORITY_UNOBSERVED
		) {
			AmbientStepsImportGapEntity.ZONE_AUTHORITY_UNOBSERVED
		} else {
			cursor.lastObservedZoneId
		}
		val gapId = AmbientStepsImportGapIntegrity.gapId(
			registrationGeneration = cursor.registrationGeneration,
			gapSequence = nextGapSequence,
			provider = cursor.provider,
			sourceInstanceId = cursor.sourceInstanceId,
			reason = storedReason,
			gapStartTimeMs = cursor.importedThroughTimeMs,
			gapEndTimeMs = toTimeMs,
			predecessorRegistrationGeneration = null,
			predecessorProvider = null,
			previousClockDomainId = cursor.lastObservedBootId,
			nextClockDomainId = boundary.observedBootId,
			previousZoneId = previousZoneId,
			nextZoneId = nextZoneId,
			collectedDataEpoch = cursor.collectedDataEpoch,
		)
		val gap = AmbientStepsImportGapEntity(
			gapId = gapId,
			registrationGeneration = cursor.registrationGeneration,
			gapSequence = nextGapSequence,
			provider = cursor.provider,
			sourceInstanceId = cursor.sourceInstanceId,
			reason = storedReason,
			gapStartTimeMs = cursor.importedThroughTimeMs,
			gapEndTimeMs = toTimeMs,
			predecessorRegistrationGeneration = null,
			predecessorProvider = null,
			previousClockDomainId = cursor.lastObservedBootId,
			nextClockDomainId = boundary.observedBootId,
			previousZoneId = previousZoneId,
			nextZoneId = nextZoneId,
			collectedDataEpoch = cursor.collectedDataEpoch,
			recordedAtMs = recordedAtMs,
		)
		val dao = database.ambientStepsImportStateDao()
		if (dao.insertGap(gap) == INSERT_IGNORED &&
			dao.gap(cursor.registrationGeneration, nextGapSequence) != gap
		) {
			throw ConcurrentAmbientStepsImportException()
		}
		val nextCursorRevision = Math.addExact(cursor.cursorRevision, 1L)
		if (dao.beginNextSegmentExact(
				registrationGeneration = cursor.registrationGeneration,
				expectedContinuitySegmentGeneration = cursor.continuitySegmentGeneration,
				expectedLastGapSequence = cursor.lastGapSequence,
				expectedCursorRevision = cursor.cursorRevision,
				expectedImportedThroughTimeMs = cursor.importedThroughTimeMs,
				expectedObservedAtMs = cursor.lastObservedAtMs,
				expectedUpdatedAtMs = cursor.updatedAtMs,
				newLastGapSequence = nextGapSequence,
				newContinuitySegmentGeneration = nextContinuityGeneration,
				newSegmentStartTimeMs = toTimeMs,
				newObservedAtMs = boundary.observedAtMs,
				newBootId = boundary.observedBootId,
				newZoneId = nextZoneId,
				newCursorRevision = nextCursorRevision,
				updatedAtMs = recordedAtMs,
			) != 1
		) {
			throw ConcurrentAmbientStepsImportException()
		}
		return requireNotNull(dao.cursor(cursor.registrationGeneration))
	}

	private suspend fun beginProviderChangedSegment(
		cursor: AmbientStepsImportCursorEntity,
		predecessor: HistoricalAmbientAuthority,
		boundary: AmbientStepsImportBoundary,
	): AmbientStepsImportCursorEntity {
		val nextGapSequence = Math.addExact(cursor.lastGapSequence, 1L)
		val nextContinuityGeneration = Math.addExact(cursor.continuitySegmentGeneration, 1L)
		val transitionTimeMs = cursor.importedThroughTimeMs
		val recordedAtMs = appliedAtMs(boundary, transitionTimeMs)
		val gapId = AmbientStepsImportGapIntegrity.gapId(
			registrationGeneration = cursor.registrationGeneration,
			gapSequence = nextGapSequence,
			provider = cursor.provider,
			sourceInstanceId = cursor.sourceInstanceId,
			reason = AmbientStepsImportGapEntity.REASON_PROVIDER_CHANGED,
			gapStartTimeMs = transitionTimeMs,
			gapEndTimeMs = transitionTimeMs,
			predecessorRegistrationGeneration = predecessor.registration.registrationGeneration,
			predecessorProvider = predecessor.provider.name,
			previousClockDomainId = cursor.lastObservedBootId,
			nextClockDomainId = cursor.lastObservedBootId,
			previousZoneId = cursor.lastObservedZoneId,
			nextZoneId = cursor.lastObservedZoneId,
			collectedDataEpoch = cursor.collectedDataEpoch,
		)
		val gap = AmbientStepsImportGapEntity(
			gapId = gapId,
			registrationGeneration = cursor.registrationGeneration,
			gapSequence = nextGapSequence,
			provider = cursor.provider,
			sourceInstanceId = cursor.sourceInstanceId,
			reason = AmbientStepsImportGapEntity.REASON_PROVIDER_CHANGED,
			gapStartTimeMs = transitionTimeMs,
			gapEndTimeMs = transitionTimeMs,
			predecessorRegistrationGeneration = predecessor.registration.registrationGeneration,
			predecessorProvider = predecessor.provider.name,
			previousClockDomainId = cursor.lastObservedBootId,
			nextClockDomainId = cursor.lastObservedBootId,
			previousZoneId = cursor.lastObservedZoneId,
			nextZoneId = cursor.lastObservedZoneId,
			collectedDataEpoch = cursor.collectedDataEpoch,
			recordedAtMs = recordedAtMs,
		)
		val dao = database.ambientStepsImportStateDao()
		if (dao.insertGap(gap) == INSERT_IGNORED &&
			dao.gap(cursor.registrationGeneration, nextGapSequence) != gap
		) {
			throw ConcurrentAmbientStepsImportException()
		}
		if (dao.beginNextSegmentExact(
				registrationGeneration = cursor.registrationGeneration,
				expectedContinuitySegmentGeneration = cursor.continuitySegmentGeneration,
				expectedLastGapSequence = cursor.lastGapSequence,
				expectedCursorRevision = cursor.cursorRevision,
				expectedImportedThroughTimeMs = cursor.importedThroughTimeMs,
				expectedObservedAtMs = cursor.lastObservedAtMs,
				expectedUpdatedAtMs = cursor.updatedAtMs,
				newLastGapSequence = nextGapSequence,
				newContinuitySegmentGeneration = nextContinuityGeneration,
				newSegmentStartTimeMs = transitionTimeMs,
				newObservedAtMs = boundary.observedAtMs,
				newBootId = cursor.lastObservedBootId,
				newZoneId = cursor.lastObservedZoneId,
				newCursorRevision = Math.addExact(cursor.cursorRevision, 1L),
				updatedAtMs = recordedAtMs,
			) != 1
		) {
			throw ConcurrentAmbientStepsImportException()
		}
		return requireNotNull(dao.cursor(cursor.registrationGeneration))
	}

	private suspend fun rotateAuthority(
		cursor: AmbientStepsImportCursorEntity,
		authority: AmbientAuthority,
		transition: AmbientStepsImportAuthorityTransitionEntity,
		observedAtMs: Long,
		appliedAtMs: Long,
	): AmbientStepsImportCursorEntity {
		val dao = database.ambientStepsImportStateDao()
		val insert = dao.insertAuthorityTransition(transition)
		if (insert == INSERT_IGNORED) {
			val stored = dao.authorityTransitions(cursor.registrationGeneration)
				.singleOrNull { it.transitionSequence == transition.transitionSequence }
			if (stored != transition) throw ConcurrentAmbientStepsImportException()
		}
		val nextRevision = Math.addExact(cursor.cursorRevision, 1L)
		if (dao.rotateAuthorityExact(
				registrationGeneration = cursor.registrationGeneration,
				expectedContinuitySegmentGeneration = cursor.continuitySegmentGeneration,
				expectedLastGapSequence = cursor.lastGapSequence,
				expectedAuthorityTransitionSequence = cursor.authorityTransitionSequence,
				expectedCursorRevision = cursor.cursorRevision,
				expectedObservedAtMs = cursor.lastObservedAtMs,
				expectedUpdatedAtMs = cursor.updatedAtMs,
				newAuthorityTransitionSequence = transition.transitionSequence,
				newContinuitySegmentGeneration = transition.toContinuitySegmentGeneration,
				newAuthorizationRevision = transition.toAuthorizationRevision,
				newAuthorizationFingerprint = transition.toAuthorizationFingerprint,
				newAuthorizationEffectiveBootId = transition.toAuthorizationEffectiveBootId,
				newAuthorizationEffectiveElapsedRealtimeNanos =
					transition.toAuthorizationEffectiveElapsedRealtimeNanos,
				newAuthorizationEffectiveWallTimeMs = transition.toAuthorizationEffectiveWallTimeMs,
				newSourcePolicyRevision = transition.toSourcePolicyRevision,
				newAmbientConsentEpoch = transition.toAmbientConsentEpoch,
				newRetentionScope = authority.retention.scope,
				newRetentionPolicyId = authority.retention.opaquePolicyId,
				newRetentionApprovalRevision = authority.retention.approvalRevision,
				effectiveBoundaryTimeMs = transition.effectiveBoundaryTimeMs,
				newObservedAtMs = observedAtMs,
				newCursorRevision = nextRevision,
				updatedAtMs = appliedAtMs,
			) != 1
		) {
			throw ConcurrentAmbientStepsImportException()
		}
		return requireNotNull(dao.cursor(cursor.registrationGeneration))
	}

	private fun ambientFact(
		authority: AmbientAuthority,
		cursor: AmbientStepsImportCursorEntity,
		window: AmbientStepsStructuralWindow,
		aggregate: AmbientStepsProviderAggregate,
		semanticRevision: Long,
		appliedAtMs: Long,
	): AmbientStepsFactRevisionEntity {
		val logicalFactId = AmbientStepsFactIntegrity.logicalFactId(
			provider = cursor.provider,
			registrationGeneration = cursor.registrationGeneration,
			continuitySegmentGeneration = cursor.continuitySegmentGeneration,
			sourceInstanceId = cursor.sourceInstanceId,
			windowStartTimeMs = window.startTimeMs,
			structuralEpochDay = window.day.epochDay,
			storedZoneId = window.day.zoneId,
			collectedDataEpoch = cursor.collectedDataEpoch,
		)
		val unsealed = AmbientStepsFactRevisionEntity(
			logicalFactId = logicalFactId,
			semanticRevision = semanticRevision,
			mutationId = AmbientStepsFactIntegrity.mutationId(
				logicalFactId,
				semanticRevision,
				AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
			),
			writerId = AmbientStepsFactRevisionEntity.WRITER_ID,
			writerVersion = AmbientStepsFactRevisionEntity.WRITER_VERSION,
			writerOwnerGeneration = authority.ownerGeneration,
			operation = AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
			originKind = AmbientStepsFactRevisionEntity.ORIGIN_PROVIDER_AGGREGATE,
			provider = cursor.provider,
			registrationGeneration = cursor.registrationGeneration,
			continuitySegmentGeneration = cursor.continuitySegmentGeneration,
			sourceInstanceId = cursor.sourceInstanceId,
			authorizationRevision = cursor.authorizationRevision,
			authorizationFingerprint = cursor.authorizationFingerprint,
			windowStartTimeMs = window.startTimeMs,
			windowEndTimeMs = window.endTimeMs,
			observedAtMs = aggregate.observedAtMs,
			structuralEpochDay = window.day.epochDay,
			storedZoneId = window.day.zoneId,
			structuralDayStartTimeMs = window.day.startTimeMs,
			structuralDayEndTimeMs = window.day.endTimeMs,
			stepCount = aggregate.stepCount,
			purpose = AmbientStepsFactRevisionEntity.PURPOSE_AMBIENT_PRODUCT,
			sourcePolicyRevision = cursor.sourcePolicyRevision,
			ambientConsentEpoch = cursor.ambientConsentEpoch,
			collectedDataEpoch = cursor.collectedDataEpoch,
			scopeDeletionGeneration = 0L,
			effectChecksum = EMPTY_EFFECT_CHECKSUM,
			appliedAtMs = appliedAtMs,
			retentionScope = authority.retention.scope,
			retentionPolicyId = authority.retention.opaquePolicyId,
			retentionApprovalRevision = authority.retention.approvalRevision,
		)
		return unsealed.copy(effectChecksum = AmbientStepsFactIntegrity.effectChecksum(unsealed))
	}

	private suspend fun lifecycleSnapshotOrNull(): CollectedDataLifecycleSnapshot? = try {
		lifecycleStore.snapshot()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		null
	}

	private fun appliedAtMs(boundary: AmbientStepsImportBoundary, effectiveTimeMs: Long): Long =
		maxOf(clock.currentTimeMillis(), boundary.observedAtMs, effectiveTimeMs)

	internal sealed interface HandoffPreflight {
		data class Ready(
			val predecessor: HistoricalAmbientAuthority,
			val successor: AmbientAuthority,
			val storedPredecessorCursor: AmbientStepsImportCursorEntity?,
			val predecessorCursor: AmbientStepsImportCursorEntity,
			val drainWindow: AmbientStepsStructuralWindow?,
			val noReadGapReason: AmbientStepsImportGapReason?,
			val predecessorCutoffTimeMs: Long,
			val successorStartTimeMs: Long,
			val cutoverWallTimeMs: Long,
			val cutoverElapsedRealtimeNanos: Long,
		) : HandoffPreflight

		data class Outcome(val result: AmbientStepsProviderHandoffResult) : HandoffPreflight
	}

	internal data class HistoricalAmbientAuthority(
		val registration: ProviderRegistrationGenerationEntity,
		val provider: AmbientStepsProvider,
		val authorization: SourceAuthorizationSnapshot,
		val policy: SourcePolicyEntity,
		val consent: SourceConsentEpochEntity,
		val retention: AmbientStepsRetentionAuthorityEntity,
		val lifecycle: CollectedDataLifecycleSnapshot,
	) {
		val authorizationEffectiveWallTimeMs: Long
			get() = authorization.members.first().effectiveWallTimeMs

		val privacyFloorTimeMs: Long
			get() = roundForwardToSecond(
				maxOf(
					requireNotNull(registration.acceptedAtMs),
					authorizationEffectiveWallTimeMs,
					policy.effectiveWallTimeMs,
					consent.effectiveWallTimeMs,
					retention.effectiveWallTimeMs,
				),
			)

		fun initialCursor(): AmbientStepsImportCursorEntity = AmbientStepsImportCursorEntity(
			registrationGeneration = registration.registrationGeneration,
			provider = provider.name,
			sourceInstanceId = registration.sourceInstanceId,
			registrationClockDomainId = registration.clockDomainId,
			registrationAcceptedAtMs = requireNotNull(registration.acceptedAtMs),
			registrationAcceptedElapsedRealtimeNanos =
				requireNotNull(registration.acceptedElapsedRealtimeNanos),
			authorizationRevision = authorization.authorizationRevision,
			authorizationFingerprint = authorization.authorizationFingerprint,
			authorizationEffectiveBootId = authorization.effectiveBootId,
			authorizationEffectiveElapsedRealtimeNanos =
				authorization.effectiveElapsedRealtimeNanos,
			authorizationEffectiveWallTimeMs = authorizationEffectiveWallTimeMs,
			sourcePolicyRevision = policy.policyRevision,
			ambientConsentEpoch = consent.epoch,
			collectedDataEpoch = lifecycle.epoch,
			eligibleFromTimeMs = privacyFloorTimeMs,
			continuitySegmentGeneration = 1L,
			segmentStartTimeMs = privacyFloorTimeMs,
			importedThroughTimeMs = privacyFloorTimeMs,
			lastObservedAtMs = privacyFloorTimeMs,
			lastObservedBootId = registration.clockDomainId,
			lastObservedZoneId = AmbientStepsImportGapEntity.ZONE_AUTHORITY_UNOBSERVED,
			lastGapSequence = 0L,
			authorityTransitionSequence = 0L,
			cursorRevision = 1L,
			status = AmbientStepsImportCursorEntity.STATUS_ACTIVE,
			updatedAtMs = privacyFloorTimeMs,
			retentionScope = retention.scope,
			retentionPolicyId = retention.opaquePolicyId,
			retentionApprovalRevision = retention.approvalRevision,
		)
	}

	private data class HandoffFactApplication(
		val cursor: AmbientStepsImportCursorEntity,
		val logicalFactId: String,
		val changed: Boolean,
	)

	private sealed interface Preflight {
		data class Ready(
			val authority: AmbientAuthority,
			val storedCursor: AmbientStepsImportCursorEntity,
			val window: AmbientStepsStructuralWindow,
		) : Preflight
		data class Outcome(val result: AmbientStepsImportResult) : Preflight
	}

	private sealed interface AuthorityResolution {
		data class Ready(val authority: AmbientAuthority) : AuthorityResolution
		data class Outcome(val result: AmbientStepsImportResult) : AuthorityResolution
	}

	private sealed interface CursorResolution {
		data class Ready(val cursor: AmbientStepsImportCursorEntity) : CursorResolution
		data class Outcome(val result: AmbientStepsImportResult) : CursorResolution
	}

	internal data class AmbientAuthority(
		val state: SourceRegistrationStateEntity,
		val registration: ProviderRegistrationGenerationEntity,
		val provider: AmbientStepsProvider,
		val authorization: SourceAuthorizationSnapshot,
		val authorizationEffectiveWallTimeMs: Long,
		val policy: SourcePolicyEntity,
		val consent: SourceConsentEpochEntity,
		val retention: AmbientStepsRetentionAuthorityEntity,
		val demands: List<SourceDemandEntity>,
		val ownerGeneration: Long,
		val lifecycle: CollectedDataLifecycleSnapshot,
	) {
		val privacyFloorTimeMs: Long
			get() = AmbientStepsImportCursorEntity.privacyFloorTimeMs(
				requireNotNull(registration.acceptedAtMs),
				maxOf(authorizationEffectiveWallTimeMs, retention.effectiveWallTimeMs),
			)

		fun initialCursor(zoneId: String?): AmbientStepsImportCursorEntity =
			AmbientStepsImportCursorEntity(
				registrationGeneration = registration.registrationGeneration,
				provider = provider.name,
				sourceInstanceId = registration.sourceInstanceId,
				registrationClockDomainId = registration.clockDomainId,
				registrationAcceptedAtMs = requireNotNull(registration.acceptedAtMs),
				registrationAcceptedElapsedRealtimeNanos =
					requireNotNull(registration.acceptedElapsedRealtimeNanos),
				authorizationRevision = authorization.authorizationRevision,
				authorizationFingerprint = authorization.authorizationFingerprint,
				authorizationEffectiveBootId = authorization.effectiveBootId,
				authorizationEffectiveElapsedRealtimeNanos =
					authorization.effectiveElapsedRealtimeNanos,
				authorizationEffectiveWallTimeMs = authorizationEffectiveWallTimeMs,
				sourcePolicyRevision = policy.policyRevision,
				ambientConsentEpoch = requireNotNull(policy.ambientConsentEpoch),
				collectedDataEpoch = lifecycle.epoch,
				eligibleFromTimeMs = privacyFloorTimeMs,
				continuitySegmentGeneration = 1L,
				segmentStartTimeMs = privacyFloorTimeMs,
				importedThroughTimeMs = privacyFloorTimeMs,
				lastObservedAtMs = privacyFloorTimeMs,
				lastObservedBootId = registration.clockDomainId,
				lastObservedZoneId = zoneId
					?: AmbientStepsImportGapEntity.ZONE_AUTHORITY_UNOBSERVED,
				lastGapSequence = 0L,
				authorityTransitionSequence = 0L,
				cursorRevision = 1L,
				status = AmbientStepsImportCursorEntity.STATUS_ACTIVE,
				updatedAtMs = privacyFloorTimeMs,
				retentionScope = retention.scope,
				retentionPolicyId = retention.opaquePolicyId,
				retentionApprovalRevision = retention.approvalRevision,
			)

		fun transitionFrom(
			cursor: AmbientStepsImportCursorEntity,
			recordedAtMs: Long,
		): AmbientStepsImportAuthorityTransitionEntity {
			val transitionSequence = Math.addExact(cursor.authorityTransitionSequence, 1L)
			val continuityGeneration = Math.addExact(cursor.continuitySegmentGeneration, 1L)
			val id = AmbientStepsImportAuthorityTransitionIntegrity.transitionId(
				cursor.registrationGeneration,
				transitionSequence,
				cursor.provider,
				cursor.sourceInstanceId,
				cursor.collectedDataEpoch,
				cursor.continuitySegmentGeneration,
				continuityGeneration,
				cursor.authorizationRevision,
				cursor.authorizationFingerprint,
				cursor.sourcePolicyRevision,
				cursor.ambientConsentEpoch,
				authorization.authorizationRevision,
				authorization.authorizationFingerprint,
				authorization.effectiveBootId,
				authorization.effectiveElapsedRealtimeNanos,
				authorizationEffectiveWallTimeMs,
				policy.policyRevision,
				requireNotNull(policy.ambientConsentEpoch),
				requireNotNull(registration.acceptedAtMs),
				privacyFloorTimeMs,
			)
			return AmbientStepsImportAuthorityTransitionEntity(
				transitionId = id,
				registrationGeneration = cursor.registrationGeneration,
				transitionSequence = transitionSequence,
				provider = cursor.provider,
				sourceInstanceId = cursor.sourceInstanceId,
				collectedDataEpoch = cursor.collectedDataEpoch,
				fromContinuitySegmentGeneration = cursor.continuitySegmentGeneration,
				toContinuitySegmentGeneration = continuityGeneration,
				fromAuthorizationRevision = cursor.authorizationRevision,
				fromAuthorizationFingerprint = cursor.authorizationFingerprint,
				fromSourcePolicyRevision = cursor.sourcePolicyRevision,
				fromAmbientConsentEpoch = cursor.ambientConsentEpoch,
				toAuthorizationRevision = authorization.authorizationRevision,
				toAuthorizationFingerprint = authorization.authorizationFingerprint,
				toAuthorizationEffectiveBootId = authorization.effectiveBootId,
				toAuthorizationEffectiveElapsedRealtimeNanos =
					authorization.effectiveElapsedRealtimeNanos,
				toAuthorizationEffectiveWallTimeMs = authorizationEffectiveWallTimeMs,
				toSourcePolicyRevision = policy.policyRevision,
				toAmbientConsentEpoch = requireNotNull(policy.ambientConsentEpoch),
				registrationAcceptedAtMs = requireNotNull(registration.acceptedAtMs),
				effectiveBoundaryTimeMs = privacyFloorTimeMs,
				recordedAtMs = recordedAtMs,
			)
		}
	}

	private companion object {
		val SOURCE_KIND = SourceKind.STEPS.stableCode
		val OWNER_SCOPE = SourceProviderPurposeScope.exactOwnerScope(
			SOURCE_KIND,
			SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
		)
		const val INSERT_IGNORED = -1L
		val EMPTY_EFFECT_CHECKSUM = "0".repeat(64)
	}
}

private fun ProviderRegistrationGenerationEntity.isAcceptedAmbientState(
	state: SourceRegistrationStateEntity,
	lifecycle: CollectedDataLifecycleSnapshot,
	boundary: AmbientStepsImportBoundary,
): Boolean =
	sourceKind == state.sourceKind &&
		registrationGeneration == state.registrationGeneration &&
		sourceInstanceId == state.sourceInstanceId &&
		clockDomainId == state.clockDomainId &&
		collectedDataEpoch == state.collectedDataEpoch &&
		collectedDataEpoch == lifecycle.epoch &&
		ownerScope == state.ownerScope &&
		providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE &&
		providerProcessIncarnationId == null &&
		status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE &&
		acceptedAtMs != null && acceptedAtMs <= boundary.observedAtMs &&
		acceptedElapsedRealtimeNanos != null &&
		acceptedElapsedRealtimeNanos <= boundary.observedElapsedRealtimeNanos &&
		clockDomainId == boundary.observedBootId &&
		state.appliedRevision != null

private fun SourcePolicyEntity.isCurrentAmbientPolicy(
	boundary: AmbientStepsImportBoundary,
): Boolean = ambientPersistenceEligible && ambientConsentEpoch != null &&
	(effectiveBootId != boundary.observedBootId ||
		effectiveElapsedRealtimeNanos <= boundary.observedElapsedRealtimeNanos) &&
	effectiveWallTimeMs <= boundary.observedAtMs

private fun SourceConsentEpochEntity?.isExactEligibleAmbient(
	policy: SourcePolicyEntity,
	boundary: AmbientStepsImportBoundary,
): Boolean =
	this != null &&
		sourceKind == policy.sourceKind &&
		purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
		epoch == policy.ambientConsentEpoch &&
		eligible && persistenceEligible &&
		policyRevision == policy.policyRevision &&
		effectiveBootId == policy.effectiveBootId &&
		(effectiveBootId != boundary.observedBootId ||
			effectiveElapsedRealtimeNanos <= boundary.observedElapsedRealtimeNanos) &&
		effectiveWallTimeMs <= boundary.observedAtMs

private fun SourceDemandEntity.isRetentionEligibleAmbientDemand(
	policy: SourcePolicyEntity,
	retention: AmbientStepsRetentionAuthorityEntity,
	provider: AmbientStepsProvider,
	boundary: AmbientStepsImportBoundary,
): Boolean = purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
	logicalTrackingId == null && serviceRunId == null && manifestRevision == null &&
	lifecycleLeaseGeneration == null &&
	sourcePolicyRevision == policy.policyRevision &&
	consentEpoch == policy.ambientConsentEpoch && persistenceEligible &&
	requestedBootId == boundary.observedBootId &&
	(requestedBootId != policy.effectiveBootId ||
		requestedElapsedRealtimeNanos >= policy.effectiveElapsedRealtimeNanos) &&
	requestedElapsedRealtimeNanos <= boundary.observedElapsedRealtimeNanos &&
	requestedAtMs <= boundary.observedAtMs &&
	requestedBootId == retention.effectiveBootId &&
	requestedElapsedRealtimeNanos >= retention.effectiveElapsedRealtimeNanos &&
	requestedAtMs >= retention.effectiveWallTimeMs &&
	providerMatches(provider)

private fun SourceDemandEntity.providerMatches(provider: AmbientStepsProvider): Boolean {
	val floor = runCatching { toSourceDemandContract().floor as AmbientStepsAcquisitionFloor }
		.getOrNull() ?: return false
	val mechanism = when (provider) {
		AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS ->
			AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS
		AmbientStepsProvider.LOCAL_RECORDING_STEPS ->
			AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS
	}
	return floor.mechanism == mechanism
}

private fun SourceAuthorizationSnapshot.isExactRetiredAmbientAuthorization(
	registration: ProviderRegistrationGenerationEntity,
	demands: List<SourceDemandEntity>,
	provider: AmbientStepsProvider,
	cutoverBootId: String,
	cutoverElapsedRealtimeNanos: Long,
	cutoverWallTimeMs: Long,
): Boolean {
	val effectiveWallTimeMs = members.firstOrNull()?.effectiveWallTimeMs ?: return false
	val authorizedDemandIds = members.mapNotNull { it.demandId }.toSet()
	return !isDenied && purposeEligibilityMask == SourceBrokerPurpose.MASK_AMBIENT_PRODUCT &&
		authorizationFingerprint == SourceBrokerAuthorization.fingerprint(demands) &&
		effectiveBootId == registration.clockDomainId && effectiveBootId == cutoverBootId &&
		effectiveElapsedRealtimeNanos < cutoverElapsedRealtimeNanos &&
		effectiveWallTimeMs <= cutoverWallTimeMs &&
		authorizedDemandIds.isNotEmpty() &&
		authorizedDemandIds == demands.map(SourceDemandEntity::demandId).toSet() &&
		members.all { member ->
			!member.isDenyAll && member.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
				member.persistenceEligible && member.effectiveBootId == effectiveBootId &&
				member.effectiveElapsedRealtimeNanos == effectiveElapsedRealtimeNanos &&
				member.effectiveWallTimeMs == effectiveWallTimeMs
		} &&
		demands.all { demand ->
			demand.sourceKind == registration.sourceKind &&
				demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
				demand.logicalTrackingId == null && demand.serviceRunId == null &&
				demand.manifestRevision == null && demand.lifecycleLeaseGeneration == null &&
				demand.persistenceEligible && demand.providerMatches(provider) &&
				demand.status == SourceDemandEntity.STATUS_RETIRED &&
				demand.retireBootId == cutoverBootId &&
				demand.retireElapsedRealtimeNanos == cutoverElapsedRealtimeNanos &&
				demand.retiredAtMs == cutoverWallTimeMs &&
				demand.requestedBootId == effectiveBootId &&
				demand.requestedElapsedRealtimeNanos <= effectiveElapsedRealtimeNanos &&
				demand.requestedAtMs <= effectiveWallTimeMs
		}
}

private fun SourcePolicyEntity.isHistoricalAmbientPolicy(
	consent: SourceConsentEpochEntity,
	authorization: SourceAuthorizationSnapshot,
	retention: AmbientStepsRetentionAuthorityEntity,
	cutoverWallTimeMs: Long,
): Boolean {
	val authorizationWallTimeMs = authorization.members.firstOrNull()?.effectiveWallTimeMs
		?: return false
	return sourceKind == consent.sourceKind && ambientPersistenceEligible &&
		ambientConsentEpoch == consent.epoch &&
		policyRevision == consent.policyRevision && consent.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
		consent.eligible && consent.persistenceEligible &&
		effectiveBootId == authorization.effectiveBootId &&
		consent.effectiveBootId == authorization.effectiveBootId &&
		retention.effectiveBootId == authorization.effectiveBootId &&
		effectiveElapsedRealtimeNanos <= authorization.effectiveElapsedRealtimeNanos &&
		consent.effectiveElapsedRealtimeNanos <= authorization.effectiveElapsedRealtimeNanos &&
		retention.effectiveElapsedRealtimeNanos <= authorization.effectiveElapsedRealtimeNanos &&
		effectiveWallTimeMs <= authorizationWallTimeMs &&
		consent.effectiveWallTimeMs <= authorizationWallTimeMs &&
		retention.effectiveWallTimeMs <= authorizationWallTimeMs &&
		authorizationWallTimeMs <= cutoverWallTimeMs &&
		authorization.members.all { member ->
			member.sourcePolicyRevision == policyRevision &&
				member.consentEpoch == consent.epoch
		}
}

private fun AmbientStepsImportCursorEntity.matchesHistoricalAuthority(
	authority: AmbientStepsFactImporter.HistoricalAmbientAuthority,
	expectedStatus: String,
): Boolean = registrationGeneration == authority.registration.registrationGeneration &&
	provider == authority.provider.name && sourceInstanceId == authority.registration.sourceInstanceId &&
	registrationClockDomainId == authority.registration.clockDomainId &&
	registrationAcceptedAtMs == authority.registration.acceptedAtMs &&
	registrationAcceptedElapsedRealtimeNanos == authority.registration.acceptedElapsedRealtimeNanos &&
	authorizationRevision == authority.authorization.authorizationRevision &&
	authorizationFingerprint == authority.authorization.authorizationFingerprint &&
	authorizationEffectiveBootId == authority.authorization.effectiveBootId &&
	authorizationEffectiveElapsedRealtimeNanos ==
		authority.authorization.effectiveElapsedRealtimeNanos &&
	authorizationEffectiveWallTimeMs == authority.authorizationEffectiveWallTimeMs &&
	sourcePolicyRevision == authority.policy.policyRevision &&
	ambientConsentEpoch == authority.consent.epoch &&
	retentionScope == authority.retention.scope &&
	retentionPolicyId == authority.retention.opaquePolicyId &&
	retentionApprovalRevision == authority.retention.approvalRevision &&
	collectedDataEpoch == authority.lifecycle.epoch &&
	eligibleFromTimeMs == authority.privacyFloorTimeMs &&
	status == expectedStatus

private fun SourceAuthorizationSnapshot?.isRetentionEligibleAmbientAuthorization(
	demands: List<SourceDemandEntity>,
	policy: SourcePolicyEntity,
	retention: AmbientStepsRetentionAuthorityEntity,
	registration: ProviderRegistrationGenerationEntity,
	boundary: AmbientStepsImportBoundary,
): Boolean = this != null && !isDenied &&
	purposeEligibilityMask == SourceBrokerPurpose.MASK_AMBIENT_PRODUCT &&
	authorizationFingerprint == SourceBrokerAuthorization.fingerprint(demands) &&
	effectiveBootId == registration.clockDomainId &&
	effectiveBootId == boundary.observedBootId &&
	effectiveElapsedRealtimeNanos <= boundary.observedElapsedRealtimeNanos &&
	effectiveBootId == retention.effectiveBootId &&
	effectiveElapsedRealtimeNanos >= retention.effectiveElapsedRealtimeNanos &&
	members.mapNotNull { it.demandId }.toSet() == demands.map { it.demandId }.toSet() &&
	members.all { member ->
		!member.isDenyAll &&
			member.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
			member.sourcePolicyRevision == policy.policyRevision &&
			member.consentEpoch == policy.ambientConsentEpoch &&
			member.persistenceEligible &&
			member.effectiveWallTimeMs >= retention.effectiveWallTimeMs &&
			member.effectiveWallTimeMs <= boundary.observedAtMs &&
			member.effectiveWallTimeMs == members.first().effectiveWallTimeMs
	}

private fun AmbientStepsImportCursorEntity.matchesRegistrationAuthority(
	authority: AmbientStepsFactImporter.AmbientAuthority,
): Boolean = registrationGeneration == authority.registration.registrationGeneration &&
	provider == authority.provider.name &&
	sourceInstanceId == authority.registration.sourceInstanceId &&
	registrationClockDomainId == authority.registration.clockDomainId &&
	registrationAcceptedAtMs == authority.registration.acceptedAtMs &&
	registrationAcceptedElapsedRealtimeNanos == authority.registration.acceptedElapsedRealtimeNanos &&
	collectedDataEpoch == authority.lifecycle.epoch &&
	status == AmbientStepsImportCursorEntity.STATUS_ACTIVE

private fun AmbientStepsImportCursorEntity.matchesCurrentAuthorization(
	authority: AmbientStepsFactImporter.AmbientAuthority,
): Boolean = authorizationRevision == authority.authorization.authorizationRevision &&
	authorizationFingerprint == authority.authorization.authorizationFingerprint &&
	authorizationEffectiveBootId == authority.authorization.effectiveBootId &&
	authorizationEffectiveElapsedRealtimeNanos ==
	authority.authorization.effectiveElapsedRealtimeNanos &&
	authorizationEffectiveWallTimeMs == authority.authorizationEffectiveWallTimeMs &&
	sourcePolicyRevision == authority.policy.policyRevision &&
	ambientConsentEpoch == authority.policy.ambientConsentEpoch &&
	retentionScope == authority.retention.scope &&
	retentionPolicyId == authority.retention.opaquePolicyId &&
	retentionApprovalRevision == authority.retention.approvalRevision &&
	eligibleFromTimeMs == authority.privacyFloorTimeMs

private fun AmbientStepsFactRevisionEntity.matchesAggregate(
	authority: AmbientStepsFactImporter.AmbientAuthority,
	cursor: AmbientStepsImportCursorEntity,
	window: AmbientStepsStructuralWindow,
	aggregate: AmbientStepsProviderAggregate,
): Boolean = AmbientStepsFactIntegrity.hasValidEffectChecksum(this) &&
	operation == AmbientStepsFactRevisionEntity.OPERATION_UPSERT &&
	writerOwnerGeneration == authority.ownerGeneration &&
	provider == cursor.provider && registrationGeneration == cursor.registrationGeneration &&
	continuitySegmentGeneration == cursor.continuitySegmentGeneration &&
	sourceInstanceId == cursor.sourceInstanceId &&
	authorizationRevision == cursor.authorizationRevision &&
	authorizationFingerprint == cursor.authorizationFingerprint &&
	windowStartTimeMs == window.startTimeMs && windowEndTimeMs == window.endTimeMs &&
	structuralEpochDay == window.day.epochDay && storedZoneId == window.day.zoneId &&
	structuralDayStartTimeMs == window.day.startTimeMs &&
	structuralDayEndTimeMs == window.day.endTimeMs &&
	stepCount == aggregate.stepCount &&
	sourcePolicyRevision == cursor.sourcePolicyRevision &&
	ambientConsentEpoch == cursor.ambientConsentEpoch &&
	retentionScope == cursor.retentionScope &&
	retentionPolicyId == cursor.retentionPolicyId &&
	retentionApprovalRevision == cursor.retentionApprovalRevision &&
	collectedDataEpoch == cursor.collectedDataEpoch && scopeDeletionGeneration == 0L

private fun Long?.isAfter(other: Long?): Boolean = when {
	this == null -> false
	other == null -> true
	else -> this > other
}

private fun AmbientStepsImportGapReason.toStoredGapReason(): String = when (this) {
	AmbientStepsImportGapReason.INITIAL_ZONE_AUTHORITY_UNOBSERVED ->
		AmbientStepsImportGapEntity.REASON_INITIAL_ZONE_AUTHORITY_UNOBSERVED
	AmbientStepsImportGapReason.AUTHORITY_BOUNDARY_NOT_DRAINED ->
		AmbientStepsImportGapEntity.REASON_AUTHORITY_BOUNDARY_NOT_DRAINED
	AmbientStepsImportGapReason.PROVIDER_NO_EVIDENCE ->
		AmbientStepsImportGapEntity.REASON_PROVIDER_NO_EVIDENCE
	AmbientStepsImportGapReason.RETENTION_ADVANCED ->
		AmbientStepsImportGapEntity.REASON_PROVIDER_RETENTION_LOSS
	AmbientStepsImportGapReason.ZONE_CHANGED -> AmbientStepsImportGapEntity.REASON_ZONE_CHANGED
}

private fun roundForwardToSecond(timeMs: Long): Long {
	require(timeMs >= 0L)
	val remainder = timeMs % MILLIS_PER_SECOND
	return if (remainder == 0L) timeMs else Math.addExact(timeMs, MILLIS_PER_SECOND - remainder)
}

private fun roundBackwardToSecond(timeMs: Long): Long {
	require(timeMs >= 0L)
	return timeMs - (timeMs % MILLIS_PER_SECOND)
}

private fun AmbientStepsProviderHandoffCommand.importBoundary(
	throughTimeMs: Long = roundBackwardToSecond(observedAtMs),
): AmbientStepsImportBoundary = AmbientStepsImportBoundary(
	observedBootId = observedBootId,
	observedElapsedRealtimeNanos = observedElapsedRealtimeNanos,
	observedAtMs = observedAtMs,
	throughTimeMs = throughTimeMs,
	zoneId = zoneId,
)

private fun handoffIneligible(
	reason: AmbientStepsProviderHandoffIneligibleReason,
): AmbientStepsFactImporter.HandoffPreflight = AmbientStepsFactImporter.HandoffPreflight.Outcome(
	AmbientStepsProviderHandoffResult.Ineligible(reason),
)

private fun handoffStale(
	reason: AmbientStepsProviderHandoffStaleReason,
): AmbientStepsFactImporter.HandoffPreflight = AmbientStepsFactImporter.HandoffPreflight.Outcome(
	AmbientStepsProviderHandoffResult.Stale(reason),
)

private fun authorityDifference(
	before: AmbientStepsFactImporter.AmbientAuthority,
	after: AmbientStepsFactImporter.AmbientAuthority,
): AmbientStepsImportStaleReason = when {
	before.registration != after.registration || before.state != after.state ->
		AmbientStepsImportStaleReason.REGISTRATION_CHANGED
	before.authorization != after.authorization || before.policy != after.policy ||
		before.consent != after.consent || before.retention != after.retention ||
		before.demands != after.demands ->
		AmbientStepsImportStaleReason.AUTHORIZATION_CHANGED
	before.ownerGeneration != after.ownerGeneration ->
		AmbientStepsImportStaleReason.DESTINATION_OWNER_CHANGED
	before.lifecycle != after.lifecycle -> AmbientStepsImportStaleReason.LIFECYCLE_CHANGED
	else -> AmbientStepsImportStaleReason.CURRENT_STATE_CHANGED
}

private fun AmbientStepsImportResult.toStaleReason(): AmbientStepsImportStaleReason = when (this) {
	is AmbientStepsImportResult.Ineligible -> when (reason) {
		AmbientStepsImportIneligibleReason.NO_ACTIVE_REGISTRATION,
		AmbientStepsImportIneligibleReason.REGISTRATION_NOT_ACCEPTED,
		-> AmbientStepsImportStaleReason.REGISTRATION_CHANGED
		AmbientStepsImportIneligibleReason.DESTINATION_OWNER_INELIGIBLE ->
			AmbientStepsImportStaleReason.DESTINATION_OWNER_CHANGED
		AmbientStepsImportIneligibleReason.POLICY_INACTIVE,
		AmbientStepsImportIneligibleReason.AMBIENT_POLICY_INELIGIBLE,
		AmbientStepsImportIneligibleReason.AMBIENT_CONSENT_INELIGIBLE,
		AmbientStepsImportIneligibleReason.RETENTION_AUTHORITY_UNAVAILABLE,
		AmbientStepsImportIneligibleReason.AUTHORIZATION_INELIGIBLE,
		AmbientStepsImportIneligibleReason.PROVIDER_READER_UNAVAILABLE,
		-> AmbientStepsImportStaleReason.AUTHORIZATION_CHANGED
	}
	is AmbientStepsImportResult.Stale -> reason
	else -> AmbientStepsImportStaleReason.CURRENT_STATE_CHANGED
}

private fun ineligible(reason: AmbientStepsImportIneligibleReason) =
	AmbientStepsImportResult.Ineligible(reason)

private fun stale(reason: AmbientStepsImportStaleReason) = AmbientStepsImportResult.Stale(reason)

private fun retryable(reason: AmbientStepsImportRetryableReason) =
	AmbientStepsImportResult.Retryable(reason)

private class ConcurrentAmbientStepsImportException : IllegalStateException()
private class RetractedAmbientStepsFactException : IllegalStateException()

private const val MILLIS_PER_SECOND = 1_000L
