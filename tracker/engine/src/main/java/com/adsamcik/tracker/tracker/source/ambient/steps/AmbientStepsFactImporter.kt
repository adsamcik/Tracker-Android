package com.adsamcik.tracker.tracker.source.ambient.steps

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportAuthorityTransitionIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.model.SourceKind
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
		require(throughTimeMs % AMBIENT_STEPS_IMPORT_MILLIS_PER_SECOND == 0L)
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
	internal val database: AppDatabase,
	private val lifecycleStore: CollectedDataLifecycleStore,
	internal val clock: Clock,
	private val readers: Map<AmbientStepsProvider, AmbientStepsProviderReader>,
	internal val planner: AmbientStepsStructuralWindowPlanner =
		AmbientStepsStructuralWindowPlanner(maximumWindowsPerPass = 1),
	internal val startupGate: TrackingStartupGate? = null,
) {
	@Inject
	constructor(
		database: AppDatabase,
		lifecycleStore: CollectedDataLifecycleStore,
		clock: Clock,
		healthConnectReader: HealthConnectAmbientStepsProviderReader,
		localRecordingReader: LocalRecordingAmbientStepsProviderReader,
		startupGate: TrackingStartupGate,
	) : this(
		database = database,
		lifecycleStore = lifecycleStore,
		clock = clock,
		readers = mapOf(
			healthConnectReader.provider to healthConnectReader,
			localRecordingReader.provider to localRecordingReader,
		),
		startupGate = startupGate,
	)

	suspend fun importNext(boundary: AmbientStepsImportBoundary): AmbientStepsImportResult {
		if (startupGate?.permitsNewCollection == false) {
			return ineligible(AmbientStepsImportIneligibleReason.AUTHORIZATION_INELIGIBLE)
		}
		val lifecycle = lifecycleSnapshotOrNull()
			?: return retryable(AmbientStepsImportRetryableReason.STORAGE_UNAVAILABLE)
		// throughTimeMs is second-aligned. Comparing the raw retention floor is safe even
		// near Long.MAX_VALUE and prevents advancing beyond this attempt's upper bound.
		if (lifecycle.retainedFromMs?.let { it > boundary.throughTimeMs } == true) {
			return AmbientStepsImportResult.NoEvidence(AmbientStepsImportNoEvidenceReason.NO_WINDOW_AVAILABLE)
		}
		return when (val preflight = loadImportPreflight(boundary, lifecycle)) {
			is Preflight.Outcome -> preflight.result
			is Preflight.Ready -> readPreparedImport(preflight, boundary, lifecycle)
		}
	}

	private suspend fun loadImportPreflight(
		boundary: AmbientStepsImportBoundary,
		lifecycle: CollectedDataLifecycleSnapshot,
	): Preflight = try {
		database.withTransaction {
			if (database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null) {
				Preflight.Outcome(retryable(AmbientStepsImportRetryableReason.STORAGE_UNAVAILABLE))
			} else {
				resolvePreflight(boundary, lifecycle)
			}
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		Preflight.Outcome(retryable(AmbientStepsImportRetryableReason.STORAGE_UNAVAILABLE))
	}

	private suspend fun readPreparedImport(
		preflight: Preflight.Ready,
		boundary: AmbientStepsImportBoundary,
		lifecycle: CollectedDataLifecycleSnapshot,
	): AmbientStepsImportResult {
		val reader = readers[preflight.authority.provider]
		return when {
			reader == null -> ineligible(AmbientStepsImportIneligibleReason.PROVIDER_READER_UNAVAILABLE)
			reader.provider != preflight.authority.provider ->
				retryable(AmbientStepsImportRetryableReason.PROVIDER_RESULT_MISMATCH)
			!collectionWindowAllowed(preflight.window.startTimeMs) ->
				ineligible(AmbientStepsImportIneligibleReason.AUTHORIZATION_INELIGIBLE)
			else -> readImportProvider(preflight, boundary, lifecycle, reader)
		}
	}

	private suspend fun readImportProvider(
		preflight: Preflight.Ready,
		boundary: AmbientStepsImportBoundary,
		lifecycle: CollectedDataLifecycleSnapshot,
		reader: AmbientStepsProviderReader,
	): AmbientStepsImportResult {
		val aggregate = try {
			reader.read(preflight.window.providerWindow, boundary.observedAtMs)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return retryable(AmbientStepsImportRetryableReason.PROVIDER_READ_FAILED)
		}
		return commitImportProviderResult(preflight, boundary, lifecycle, aggregate)
	}

	private suspend fun commitImportProviderResult(
		preflight: Preflight.Ready,
		boundary: AmbientStepsImportBoundary,
		lifecycle: CollectedDataLifecycleSnapshot,
		aggregate: AmbientStepsProviderAggregate?,
	): AmbientStepsImportResult {
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
		return commitImportTransaction(preflight, boundary, commitLifecycle, aggregate)
	}

	private suspend fun commitImportTransaction(
		preflight: Preflight.Ready,
		boundary: AmbientStepsImportBoundary,
		lifecycle: CollectedDataLifecycleSnapshot,
		aggregate: AmbientStepsProviderAggregate?,
	): AmbientStepsImportResult = try {
		database.withTransaction {
			if (database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null) {
				retryable(AmbientStepsImportRetryableReason.STORAGE_UNAVAILABLE)
			} else {
				commitRead(preflight, boundary, lifecycle, aggregate)
			}
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

	/**
	 * Drains one already-retiring predecessor and partitions its coverage from the accepted
	 * successor before the provider lifecycle removes its system-owned subscription.
	 */
	internal suspend fun handoffAcceptedProvider(
		command: AmbientStepsProviderHandoffCommand,
	): AmbientStepsProviderHandoffResult {
		val lifecycle = lifecycleSnapshotOrNull()
			?: return AmbientStepsProviderHandoffResult.Retryable(
				AmbientStepsProviderHandoffRetryableReason.STORAGE_UNAVAILABLE,
			)
		return when (val preflight = loadHandoffPreflight(command, lifecycle)) {
			is HandoffPreflight.Outcome -> preflight.result
			is HandoffPreflight.Ready -> readPreparedHandoff(preflight, command, lifecycle)
		}
	}

	private suspend fun loadHandoffPreflight(
		command: AmbientStepsProviderHandoffCommand,
		lifecycle: CollectedDataLifecycleSnapshot,
	): HandoffPreflight = try {
		database.withTransaction { resolveHandoffPreflight(command, lifecycle) }
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: IllegalArgumentException) {
		HandoffPreflight.Outcome(AmbientStepsProviderHandoffResult.Stale(
			AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED,
		))
	} catch (_: Exception) {
		HandoffPreflight.Outcome(AmbientStepsProviderHandoffResult.Retryable(
			AmbientStepsProviderHandoffRetryableReason.STORAGE_UNAVAILABLE,
		))
	}

	private suspend fun readPreparedHandoff(
		preflight: HandoffPreflight.Ready,
		command: AmbientStepsProviderHandoffCommand,
		lifecycle: CollectedDataLifecycleSnapshot,
	): AmbientStepsProviderHandoffResult {
		val drainWindow = preflight.drainWindow
		if (!command.predecessorReadAllowed || drainWindow == null ||
			!collectionWindowAllowed(drainWindow.startTimeMs)
		) {
			return commitHandoffProviderResult(preflight, command, lifecycle, null)
		}
		val reader = readers[preflight.predecessor.provider]
		return when {
			reader == null -> AmbientStepsProviderHandoffResult.Ineligible(
				AmbientStepsProviderHandoffIneligibleReason.PROVIDER_READER_UNAVAILABLE,
			)
			reader.provider != preflight.predecessor.provider ->
				AmbientStepsProviderHandoffResult.Retryable(
					AmbientStepsProviderHandoffRetryableReason.PROVIDER_RESULT_MISMATCH,
				)
			else -> readHandoffProvider(preflight, command, lifecycle, reader)
		}
	}

	private suspend fun readHandoffProvider(
		preflight: HandoffPreflight.Ready,
		command: AmbientStepsProviderHandoffCommand,
		lifecycle: CollectedDataLifecycleSnapshot,
		reader: AmbientStepsProviderReader,
	): AmbientStepsProviderHandoffResult {
		val aggregate = try {
			reader.read(requireNotNull(preflight.drainWindow).providerWindow, command.observedAtMs)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: SecurityException) {
			// Permission can be revoked after capability inspection; never estimate the missing count.
			null
		} catch (_: Exception) {
			return AmbientStepsProviderHandoffResult.Retryable(
				AmbientStepsProviderHandoffRetryableReason.PROVIDER_READ_FAILED,
			)
		}
		return commitHandoffProviderResult(preflight, command, lifecycle, aggregate)
	}

	private suspend fun commitHandoffProviderResult(
		preflight: HandoffPreflight.Ready,
		command: AmbientStepsProviderHandoffCommand,
		lifecycle: CollectedDataLifecycleSnapshot,
		aggregate: AmbientStepsProviderAggregate?,
	): AmbientStepsProviderHandoffResult {
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
		return commitHandoffTransaction(preflight, command, commitLifecycle, aggregate)
	}

	private suspend fun commitHandoffTransaction(
		preflight: HandoffPreflight.Ready,
		command: AmbientStepsProviderHandoffCommand,
		lifecycle: CollectedDataLifecycleSnapshot,
		aggregate: AmbientStepsProviderAggregate?,
	): AmbientStepsProviderHandoffResult = try {
		database.withTransaction {
			commitHandoff(preflight, command, lifecycle, aggregate.takeIf {
				preflight.drainWindow?.let { collectionWindowAllowed(it.startTimeMs) } != false
			})
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: RetractedAmbientStepsFactException) {
		AmbientStepsProviderHandoffResult.Stale(AmbientStepsProviderHandoffStaleReason.FACT_RETRACTED)
	} catch (_: ConcurrentAmbientStepsImportException) {
		AmbientStepsProviderHandoffResult.Stale(AmbientStepsProviderHandoffStaleReason.CURRENT_STATE_CHANGED)
	} catch (_: Exception) {
		AmbientStepsProviderHandoffResult.Retryable(AmbientStepsProviderHandoffRetryableReason.STORAGE_UNAVAILABLE)
	}

	/** Checks this window against the current Stop and resume boundary. */
	internal fun collectionWindowAllowed(startTimeMs: Long): Boolean =
		startupGate?.let { it.permitsNewCollection &&
            it.collectionResumeBoundary?.permitsWindow(startTimeMs) != false } != false

	private suspend fun lifecycleSnapshotOrNull(): CollectedDataLifecycleSnapshot? = try {
		lifecycleStore.snapshot()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		null
	}

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

	internal data class HandoffFactApplication(
		val cursor: AmbientStepsImportCursorEntity,
		val logicalFactId: String,
		val changed: Boolean,
	)

	internal sealed interface Preflight {
		data class Ready(
			val authority: AmbientAuthority,
			val storedCursor: AmbientStepsImportCursorEntity,
			val window: AmbientStepsStructuralWindow,
		) : Preflight
		data class Outcome(val result: AmbientStepsImportResult) : Preflight
	}

	internal sealed interface AuthorityResolution {
		data class Ready(val authority: AmbientAuthority) : AuthorityResolution
		data class Outcome(val result: AmbientStepsImportResult) : AuthorityResolution
	}

	internal sealed interface CursorResolution {
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

	internal companion object {
		val SOURCE_KIND = SourceKind.STEPS.stableCode
		val OWNER_SCOPE = SourceProviderPurposeScope.exactOwnerScope(
			SOURCE_KIND,
			SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
		)
		const val INSERT_IGNORED = -1L
		val EMPTY_EFFECT_CHECKSUM = "0".repeat(64)
	}
}

internal class ConcurrentAmbientStepsImportException : IllegalStateException()
internal class RetractedAmbientStepsFactException : IllegalStateException()

internal const val AMBIENT_STEPS_IMPORT_MILLIS_PER_SECOND = 1_000L
