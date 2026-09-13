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
				ineligible(AmbientStepsImportIneligibleReason.AMBIENT_POLICY_INELIGIBLE),
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

		val demands = SourceProviderPurposeScope.selectDemands(
			SOURCE_KIND,
			OWNER_SCOPE,
			brokerDao.authorizationDemands(SOURCE_KIND),
		)
		if (demands.isEmpty() || demands.any { demand ->
			!demand.isExactAmbientDemand(policy, provider, boundary)
		}) {
			return AuthorityResolution.Outcome(
				ineligible(AmbientStepsImportIneligibleReason.AUTHORIZATION_INELIGIBLE),
			)
		}
		val authorization = brokerDao.latestAuthorization(SOURCE_KIND, state.registrationGeneration)
			.toAuthorizationSnapshotOrNull()
		if (!authorization.isExactAmbientAuthorization(demands, policy, registration, boundary)) {
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

	private suspend fun rotateAuthority(
		cursor: AmbientStepsImportCursorEntity,
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
		val demands: List<SourceDemandEntity>,
		val ownerGeneration: Long,
		val lifecycle: CollectedDataLifecycleSnapshot,
	) {
		val privacyFloorTimeMs: Long
			get() = AmbientStepsImportCursorEntity.privacyFloorTimeMs(
				requireNotNull(registration.acceptedAtMs),
				authorizationEffectiveWallTimeMs,
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
	effectiveBootId == boundary.observedBootId &&
	effectiveElapsedRealtimeNanos <= boundary.observedElapsedRealtimeNanos &&
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
		effectiveBootId == boundary.observedBootId &&
		effectiveElapsedRealtimeNanos <= boundary.observedElapsedRealtimeNanos &&
		effectiveWallTimeMs <= boundary.observedAtMs

private fun SourceDemandEntity.isExactAmbientDemand(
	policy: SourcePolicyEntity,
	provider: AmbientStepsProvider,
	boundary: AmbientStepsImportBoundary,
): Boolean = purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
	logicalTrackingId == null && serviceRunId == null && manifestRevision == null &&
	lifecycleLeaseGeneration == null &&
	sourcePolicyRevision == policy.policyRevision &&
	consentEpoch == policy.ambientConsentEpoch && persistenceEligible &&
	requestedBootId == policy.effectiveBootId &&
	requestedElapsedRealtimeNanos >= policy.effectiveElapsedRealtimeNanos &&
	requestedElapsedRealtimeNanos <= boundary.observedElapsedRealtimeNanos &&
	requestedAtMs <= boundary.observedAtMs &&
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

private fun SourceAuthorizationSnapshot?.isExactAmbientAuthorization(
	demands: List<SourceDemandEntity>,
	policy: SourcePolicyEntity,
	registration: ProviderRegistrationGenerationEntity,
	boundary: AmbientStepsImportBoundary,
): Boolean = this != null && !isDenied &&
	purposeEligibilityMask == SourceBrokerPurpose.MASK_AMBIENT_PRODUCT &&
	authorizationFingerprint == SourceBrokerAuthorization.fingerprint(demands) &&
	effectiveBootId == registration.clockDomainId &&
	effectiveBootId == boundary.observedBootId &&
	effectiveElapsedRealtimeNanos <= boundary.observedElapsedRealtimeNanos &&
	members.mapNotNull { it.demandId }.toSet() == demands.map { it.demandId }.toSet() &&
	members.all { member ->
		!member.isDenyAll &&
			member.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
			member.sourcePolicyRevision == policy.policyRevision &&
			member.consentEpoch == policy.ambientConsentEpoch &&
			member.persistenceEligible &&
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

private fun authorityDifference(
	before: AmbientStepsFactImporter.AmbientAuthority,
	after: AmbientStepsFactImporter.AmbientAuthority,
): AmbientStepsImportStaleReason = when {
	before.registration != after.registration || before.state != after.state ->
		AmbientStepsImportStaleReason.REGISTRATION_CHANGED
	before.authorization != after.authorization || before.policy != after.policy ||
		before.consent != after.consent || before.demands != after.demands ->
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
