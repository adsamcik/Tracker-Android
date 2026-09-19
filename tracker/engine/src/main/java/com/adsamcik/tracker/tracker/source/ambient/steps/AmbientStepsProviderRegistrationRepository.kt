package com.adsamcik.tracker.tracker.source.ambient.steps

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.decodeCurrentModelOrNull
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.hasExactAmbientStepsRetentionBinding
import com.adsamcik.tracker.tracker.source.runtime.toSourceDemandContract
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal data class AmbientStepsProviderRegistration(
	val provider: AmbientStepsProvider,
	val expectedDemandId: String,
	val state: SourceRegistrationStateEntity,
	val authorization: SourceAuthorizationSnapshot,
	val requiresProviderAcceptance: Boolean,
	val predecessorState: SourceRegistrationStateEntity?,
	val providerRequestAtMs: Long,
	val providerRequestElapsedRealtimeNanos: Long,
) {
	val physicalConfigurationFingerprint: String = provider.physicalConfigurationFingerprint()
}

/**
 * Owns the durable half of Ambient Steps provider transitions.
 *
 * Provider APIs are deliberately absent here. A caller first reserves an exact system-rearmable
 * identity, performs the provider side effect, and only then accepts the reservation. Acceptance
 * revalidates the external deletion epoch immediately before checking rollout, policy, consent,
 * demand identity, and provider mechanism together in Room and moving the authoritative pointer.
 */
@Singleton
internal class AmbientStepsProviderRegistrationRepository @Inject constructor(
	private val database: AppDatabase,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val bootClockDomainProvider: BootClockDomainProvider,
) {
	suspend fun reserve(
		provider: AmbientStepsProvider,
		expectedDemandId: String,
		boundary: AmbientStepsDemandBoundary,
	): AmbientStepsProviderRegistration {
		require(expectedDemandId.isNotBlank())
		val lifecycle = lifecycleStore.snapshot()
		val clockDomainId = bootClockDomainProvider.current()
		require(boundary.bootId == clockDomainId) {
			"Ambient Steps reservation boundary belongs to another boot"
		}
		return database.withTransaction {
			val demands = requireEligibleDemands(provider, expectedDemandId, boundary)
			val brokerDao = database.sourceBrokerDao()
			val authorizationFingerprint = SourceBrokerAuthorization.fingerprint(demands)
			val stateDao = database.sourceRegistrationStateDao()
			val current = stateDao.get(SOURCE_KIND, OWNER_SCOPE)
			val currentPhysical = current?.let { state ->
				brokerDao.registration(state.sourceKind, state.registrationGeneration)
			}
			val fingerprint = provider.physicalConfigurationFingerprint()

			val pending = brokerDao.currentPhysicalRegistrations(SOURCE_KIND)
				.filter { registration ->
					registration.ownerScope == OWNER_SCOPE &&
						registration.status == ProviderRegistrationGenerationEntity.STATUS_RESERVED
				}
			if (pending.isNotEmpty()) {
				val reusable = pending.singleOrNull()?.takeIf { registration ->
					registration.clockDomainId == clockDomainId &&
						registration.collectedDataEpoch == lifecycle.epoch &&
						registration.physicalConfigurationFingerprint == fingerprint &&
						brokerDao.latestAuthorization(SOURCE_KIND, registration.registrationGeneration)
							.toAuthorizationSnapshotOrNull()
							?.authorizationFingerprint == authorizationFingerprint
				} ?: error("A different Ambient Steps provider reservation requires cleanup")
				return@withTransaction AmbientStepsProviderRegistration(
					provider = provider,
					expectedDemandId = expectedDemandId,
					state = stateForReservation(
						current = current,
						registration = reusable,
						appliedRevision = demands.maxOf(SourceDemandEntity::sourcePolicyRevision),
						updatedAtMs = boundary.wallTimeMs,
					),
					authorization = requireNotNull(
						brokerDao.latestAuthorization(SOURCE_KIND, reusable.registrationGeneration)
							.toAuthorizationSnapshotOrNull(),
					),
					requiresProviderAcceptance = true,
					predecessorState = current,
					providerRequestAtMs = reusable.reservedAtMs,
					providerRequestElapsedRealtimeNanos = reusable.reservedElapsedRealtimeNanos,
				)
			}

			if (current != null && currentPhysical != null &&
				current.clockDomainId == clockDomainId &&
				current.collectedDataEpoch == lifecycle.epoch &&
				currentPhysical.providerResidency ==
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE &&
				currentPhysical.providerProcessIncarnationId == null &&
				currentPhysical.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE &&
				currentPhysical.physicalConfigurationFingerprint == fingerprint
			) {
				val reused = current.copy(
					appliedRevision = demands.maxOf(SourceDemandEntity::sourcePolicyRevision),
					updatedAtMs = boundary.wallTimeMs,
				)
				stateDao.replace(reused)
				val authorization = appendAuthorizationIfChanged(
					registrationGeneration = reused.registrationGeneration,
					demands = demands,
					boundary = boundary,
				)
				return@withTransaction AmbientStepsProviderRegistration(
					provider = provider,
					expectedDemandId = expectedDemandId,
					state = reused,
					authorization = authorization,
					requiresProviderAcceptance = false,
					predecessorState = current,
					providerRequestAtMs = requireNotNull(currentPhysical.acceptedAtMs),
					providerRequestElapsedRealtimeNanos =
						requireNotNull(currentPhysical.acceptedElapsedRealtimeNanos),
				)
			}

			val registrationGeneration = brokerDao.maximumRegistrationGeneration(SOURCE_KIND) + 1L
			check(registrationGeneration > 0L) { "Ambient Steps registration generation exhausted" }
			val next = nextState(
				current = current,
				clockDomainId = clockDomainId,
				collectedDataEpoch = lifecycle.epoch,
				registrationGeneration = registrationGeneration,
				appliedRevision = demands.maxOf(SourceDemandEntity::sourcePolicyRevision),
				updatedAtMs = boundary.wallTimeMs,
			)
			brokerDao.insertRegistration(
				ProviderRegistrationGenerationEntity(
					sourceKind = SOURCE_KIND,
					registrationGeneration = registrationGeneration,
					sourceInstanceId = next.sourceInstanceId,
					ownerScope = OWNER_SCOPE,
					clockDomainId = clockDomainId,
					physicalConfigurationFingerprint = fingerprint,
					collectedDataEpoch = lifecycle.epoch,
					providerResidency =
						ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
					providerProcessIncarnationId = null,
					status = ProviderRegistrationGenerationEntity.STATUS_RESERVED,
					reservedAtMs = boundary.wallTimeMs,
					reservedElapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
					acceptedAtMs = null,
					acceptedElapsedRealtimeNanos = null,
					retiredAtMs = null,
					retiredElapsedRealtimeNanos = null,
					failureCode = null,
				),
			)
			val authorization = appendAuthorizationIfChanged(
				registrationGeneration = registrationGeneration,
				demands = demands,
				boundary = boundary,
			)
			AmbientStepsProviderRegistration(
				provider = provider,
				expectedDemandId = expectedDemandId,
				state = next,
				authorization = authorization,
				requiresProviderAcceptance = true,
				predecessorState = current,
				providerRequestAtMs = boundary.wallTimeMs,
				providerRequestElapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
			)
		}
	}

	/** Accepts only the exact still-current provider-specific demand captured by [registration]. */
	suspend fun accept(
		registration: AmbientStepsProviderRegistration,
	): ProviderRegistrationGenerationEntity? {
		check(registration.requiresProviderAcceptance)
		val lifecycle = lifecycleStore.snapshot()
		check(lifecycle.epoch == registration.state.collectedDataEpoch) {
			"Collected-data epoch changed during Ambient Steps provider activation"
		}
		check(bootClockDomainProvider.current() == registration.state.clockDomainId) {
			"Boot changed during Ambient Steps provider activation"
		}
		return database.withTransaction {
			val boundary = AmbientStepsDemandBoundary(
				bootId = registration.state.clockDomainId,
				elapsedRealtimeNanos = registration.providerRequestElapsedRealtimeNanos,
				wallTimeMs = registration.providerRequestAtMs,
			)
			val demands = requireEligibleDemands(
				registration.provider,
				registration.expectedDemandId,
				boundary,
			)
			check(
				SourceBrokerAuthorization.fingerprint(demands) ==
					registration.authorization.authorizationFingerprint,
			) { "Ambient Steps demand changed during provider activation" }
			database.sourceBrokerDao().acceptReservedReplacement(
				reservedState = registration.state,
				expectedPointerGeneration = registration.predecessorState?.registrationGeneration,
				expectedPointerInstanceId = registration.predecessorState?.sourceInstanceId,
				requiredAuthorizationFingerprint =
					registration.authorization.authorizationFingerprint,
				acceptedAtMs = registration.providerRequestAtMs,
				acceptedElapsedRealtimeNanos =
					registration.providerRequestElapsedRealtimeNanos,
			)
		}
	}

	suspend fun failUnaccepted(
		registration: AmbientStepsProviderRegistration,
		failureCode: String,
		boundary: AmbientStepsDemandBoundary,
	): Boolean {
		val physical = database.sourceBrokerDao().registration(
			SOURCE_KIND,
			registration.state.registrationGeneration,
		) ?: return false
		return failUnaccepted(physical, failureCode, boundary)
	}

	internal suspend fun failUnaccepted(
		registration: ProviderRegistrationGenerationEntity,
		failureCode: String,
		boundary: AmbientStepsDemandBoundary,
	): Boolean {
		require(failureCode.isNotBlank())
		return database.withTransaction {
			val physical = database.sourceBrokerDao().registration(
				SOURCE_KIND,
				registration.registrationGeneration,
			) ?: return@withTransaction false
			if (physical.sourceInstanceId != registration.sourceInstanceId ||
				physical.ownerScope != OWNER_SCOPE ||
				physical.providerResidency !=
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE ||
				physical.status != ProviderRegistrationGenerationEntity.STATUS_RESERVED
			) return@withTransaction false
			database.sourceBrokerDao().finishRegistration(
				sourceKind = SOURCE_KIND,
				registrationGeneration = physical.registrationGeneration,
				sourceInstanceId = physical.sourceInstanceId,
				status = ProviderRegistrationGenerationEntity.STATUS_FAILED,
				retiredAtMs = boundary.wallTimeMs,
				retiredElapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
				failureCode = failureCode,
			) == 1
		}
	}

	internal suspend fun currentActive(): ProviderRegistrationGenerationEntity? =
		database.sourceRegistrationStateDao().get(SOURCE_KIND, OWNER_SCOPE)?.let { state ->
			database.sourceBrokerDao().registration(SOURCE_KIND, state.registrationGeneration)
		}
			?.takeIf { registration ->
				registration.ownerScope == OWNER_SCOPE &&
					registration.providerResidency ==
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE &&
					registration.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE
			}

	internal suspend fun pendingRetirements(): List<ProviderRegistrationGenerationEntity> =
		database.sourceBrokerDao().pendingProviderRemovals(SOURCE_KIND).filter { registration ->
			registration.ownerScope == OWNER_SCOPE &&
				registration.providerResidency ==
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE
		}

	internal suspend fun pendingReservations(): List<ProviderRegistrationGenerationEntity> =
		database.sourceBrokerDao().currentPhysicalRegistrations(SOURCE_KIND).filter { registration ->
			registration.ownerScope == OWNER_SCOPE &&
				registration.providerResidency ==
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE &&
				registration.status == ProviderRegistrationGenerationEntity.STATUS_RESERVED
		}

	internal suspend fun hasNonterminalProvider(provider: AmbientStepsProvider): Boolean =
		(
			database.sourceBrokerDao().currentPhysicalRegistrations(SOURCE_KIND) +
				database.sourceBrokerDao().pendingProviderRemovals(SOURCE_KIND)
		)
			.distinctBy(ProviderRegistrationGenerationEntity::registrationGeneration)
			.any { registration ->
				registration.ownerScope == OWNER_SCOPE &&
					registration.providerResidency ==
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE &&
					registration.ambientStepsProviderOrNull() == provider
			}

	internal suspend fun markActiveRetiring(
		registration: ProviderRegistrationGenerationEntity,
		reason: String,
		boundary: AmbientStepsDemandBoundary,
	): ProviderRegistrationGenerationEntity? {
		require(reason.isNotBlank())
		return database.withTransaction {
			val current = database.sourceBrokerDao().registration(
				SOURCE_KIND,
				registration.registrationGeneration,
			) ?: return@withTransaction null
			if (current.sourceInstanceId != registration.sourceInstanceId ||
				current.ownerScope != OWNER_SCOPE ||
				current.providerResidency !=
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE ||
				current.status != ProviderRegistrationGenerationEntity.STATUS_ACTIVE
			) return@withTransaction null
			check(
				database.sourceBrokerDao().markRegistrationRetiring(
					sourceKind = SOURCE_KIND,
					registrationGeneration = current.registrationGeneration,
					sourceInstanceId = current.sourceInstanceId,
					retiredAtMs = boundary.wallTimeMs,
					retiredElapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
					reason = reason,
				) == 1,
			)
			database.sourceBrokerDao().registration(SOURCE_KIND, current.registrationGeneration)
		}
	}

	internal suspend fun completeRetirement(
		registration: ProviderRegistrationGenerationEntity,
	): Boolean = database.withTransaction {
		val current = database.sourceBrokerDao().registration(
			SOURCE_KIND,
			registration.registrationGeneration,
		) ?: return@withTransaction false
		if (current.sourceInstanceId != registration.sourceInstanceId ||
			current.ownerScope != OWNER_SCOPE ||
			current.providerResidency !=
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE ||
			current.status != ProviderRegistrationGenerationEntity.STATUS_RETIRING
		) return@withTransaction false
		database.sourceBrokerDao().completeRegistrationRetirement(
			SOURCE_KIND,
			current.registrationGeneration,
			current.sourceInstanceId,
		) == 1
	}

	private suspend fun requireEligibleDemands(
		provider: AmbientStepsProvider,
		expectedDemandId: String,
		boundary: AmbientStepsDemandBoundary,
	): List<SourceDemandEntity> {
		val rollout = database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull()
		check(rollout?.isCaptureReachable(SourceKind.STEPS, CaptureReachabilityMode.AMBIENT) == true) {
			"Ambient Steps acquisition is contained"
		}
		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority()
		check(authority?.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE) {
			"Source policy authority is inactive"
		}
		val policy = requireNotNull(
			policyDao.policyAtRevision(authority.currentPolicyRevision, SOURCE_KIND),
		) { "Ambient Steps policy is missing" }
		val consentEpoch = requireNotNull(policy.ambientConsentEpoch) {
			"Ambient Steps consent is revoked"
		}
		val consent = policyDao.latestConsentEpoch(
			SOURCE_KIND,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
		check(policy.hasExactEligibleAmbientConsentReference(consent)) {
			"Ambient Steps consent reference is unavailable"
		}
		check(
			policy.isEffectiveAtOrBefore(
				boundary.bootId,
				boundary.elapsedRealtimeNanos,
				boundary.wallTimeMs,
			) && requireNotNull(consent).isEffectiveAtOrBefore(
				boundary.bootId,
				boundary.elapsedRealtimeNanos,
				boundary.wallTimeMs,
			),
		) { "Ambient Steps policy or consent is not yet effective" }
		val evidence = requireNotNull(database.sourceEvidenceStateDao().get()) {
			"Ambient Steps evidence authority is unavailable"
		}
		val retention = requireNotNull(
			database.ambientStepsFactRevisionDao().latestRetentionAuthority(
				AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		) { "Ambient Steps retention authority is unavailable" }
		check(AmbientStepsRetentionAuthorityIntegrity.isAuthentic(retention) && retention.isActive) {
			"Ambient Steps retention authority is invalid or revoked"
		}
		check(
			retention.sourcePolicyRevision == authority.currentPolicyRevision &&
				retention.ambientConsentEpoch == consentEpoch &&
				retention.collectedDataEpoch == evidence.collectedDataEpoch &&
				retention.retainedFromMs == evidence.retainedFromMs &&
				retention.effectiveBootId == boundary.bootId &&
				retention.effectiveElapsedRealtimeNanos <= boundary.elapsedRealtimeNanos &&
				retention.effectiveWallTimeMs <= boundary.wallTimeMs,
		) { "Ambient Steps retention authority no longer matches policy or lifecycle" }

		val demands = SourceProviderPurposeScope.selectDemands(
			SOURCE_KIND,
			OWNER_SCOPE,
			database.sourceBrokerDao().authorizationDemands(SOURCE_KIND),
		)
		check(demands.any { demand -> demand.demandId == expectedDemandId }) {
			"Expected Ambient Steps demand is no longer active"
		}
		check(demands.all { demand ->
			demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
				demand.sourcePolicyRevision == authority.currentPolicyRevision &&
				demand.consentEpoch == consentEpoch &&
				demand.persistenceEligible &&
				demand.requestedBootId == boundary.bootId &&
				demand.requestedElapsedRealtimeNanos >=
					retention.effectiveElapsedRealtimeNanos &&
				demand.requestedElapsedRealtimeNanos <= boundary.elapsedRealtimeNanos &&
				demand.requestedAtMs >= retention.effectiveWallTimeMs &&
				demand.requestedAtMs <= boundary.wallTimeMs &&
				demand.hasExactAmbientStepsRetentionBinding(retention) &&
				demand.providerMatches(provider)
		}) { "Ambient Steps demand does not authorize the selected provider" }
		return demands
	}

	private suspend fun appendAuthorizationIfChanged(
		registrationGeneration: Long,
		demands: List<SourceDemandEntity>,
		boundary: AmbientStepsDemandBoundary,
	): SourceAuthorizationSnapshot {
		val dao = database.sourceBrokerDao()
		val fingerprint = SourceBrokerAuthorization.fingerprint(demands)
		val current = dao.latestAuthorization(SOURCE_KIND, registrationGeneration)
			.toAuthorizationSnapshotOrNull()
		if (current?.authorizationFingerprint == fingerprint) return current
		val revision = dao.maximumAuthorizationRevision(SOURCE_KIND) + 1L
		check(revision > 0L) { "Ambient Steps authorization revision exhausted" }
		val rows = SourceBrokerAuthorization.rows(
			sourceKind = SOURCE_KIND,
			registrationGeneration = registrationGeneration,
			authorizationRevision = revision,
			demands = demands,
			effectiveBootId = boundary.bootId,
			effectiveElapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
			effectiveWallTimeMs = boundary.wallTimeMs,
		)
		dao.insertAuthorizations(rows)
		return requireNotNull(rows.toAuthorizationSnapshotOrNull())
	}

	private fun nextState(
		current: SourceRegistrationStateEntity?,
		clockDomainId: String,
		collectedDataEpoch: Long,
		registrationGeneration: Long,
		appliedRevision: Long,
		updatedAtMs: Long,
	): SourceRegistrationStateEntity {
		val continuesSequence = current != null &&
			current.clockDomainId == clockDomainId &&
			current.collectedDataEpoch == collectedDataEpoch
		return SourceRegistrationStateEntity(
			sourceKind = SOURCE_KIND,
			ownerScope = OWNER_SCOPE,
			sourceInstanceId = if (continuesSequence) {
				requireNotNull(current).sourceInstanceId
			} else {
				UUID.randomUUID().toString()
			},
			clockDomainId = clockDomainId,
			registrationGeneration = registrationGeneration,
			nextSequence = if (continuesSequence) requireNotNull(current).nextSequence else 0L,
			appliedRevision = appliedRevision,
			collectedDataEpoch = collectedDataEpoch,
			updatedAtMs = updatedAtMs,
		)
	}

	private fun stateForReservation(
		current: SourceRegistrationStateEntity?,
		registration: ProviderRegistrationGenerationEntity,
		appliedRevision: Long,
		updatedAtMs: Long,
	): SourceRegistrationStateEntity = SourceRegistrationStateEntity(
		sourceKind = SOURCE_KIND,
		ownerScope = OWNER_SCOPE,
		sourceInstanceId = registration.sourceInstanceId,
		clockDomainId = registration.clockDomainId,
		registrationGeneration = registration.registrationGeneration,
		nextSequence = if (
			current != null && current.sourceInstanceId == registration.sourceInstanceId &&
				current.clockDomainId == registration.clockDomainId &&
				current.collectedDataEpoch == registration.collectedDataEpoch
		) current.nextSequence else 0L,
		appliedRevision = appliedRevision,
		collectedDataEpoch = registration.collectedDataEpoch,
		updatedAtMs = updatedAtMs,
	)

	private companion object {
		val SOURCE_KIND = SourceKind.STEPS.stableCode
		val OWNER_SCOPE = SourceProviderPurposeScope.exactOwnerScope(
			SOURCE_KIND,
			SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
		)
	}
}

internal fun AmbientStepsProvider.physicalConfigurationFingerprint(): String =
	"ambient-steps-provider:v1:mechanism=$name"

internal fun ProviderRegistrationGenerationEntity.ambientStepsProviderOrNull(): AmbientStepsProvider? =
	AmbientStepsProvider.entries.singleOrNull { provider ->
		provider.physicalConfigurationFingerprint() == physicalConfigurationFingerprint
	}

private fun SourceDemandEntity.providerMatches(provider: AmbientStepsProvider): Boolean {
	val floor = runCatching { toSourceDemandContract().floor as AmbientStepsAcquisitionFloor }
		.getOrNull() ?: return false
	return floor.mechanism == when (provider) {
		AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS ->
			AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS
		AmbientStepsProvider.LOCAL_RECORDING_STEPS ->
			AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS
	}
}
