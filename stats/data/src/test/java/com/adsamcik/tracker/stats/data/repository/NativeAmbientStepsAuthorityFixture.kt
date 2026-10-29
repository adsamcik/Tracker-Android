package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import io.kotest.matchers.shouldBe

/** Concrete real-Room native Steps authority and its matching portable records. */
internal class NativeAmbientStepsAuthorityFixture(private val database: AppDatabase) {
	// Keep one native Steps provider fixture and its matching local/portable day identities together for
	// import-overlap checks.
	@Suppress("LongMethod")
	suspend fun seedNative(revoked: Boolean): NativeFixture {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH, null, 0L) shouldBe 1
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
				SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS,
				OWNER_GENERATION,
				0L,
			),
		)
		val historicalPolicy = policy(HISTORICAL_POLICY, ACTIVE_CONSENT, true)
		val currentPolicy = if (revoked) {
			policy(CURRENT_POLICY, null, false)
		} else {
			historicalPolicy
		}
		database.sourcePolicyDao().insertPolicies(
			listOf(historicalPolicy) + listOfNotNull(
				currentPolicy.takeIf { it.policyRevision != historicalPolicy.policyRevision },
			),
		)
		database.sourcePolicyDao().insertConsentEpochs(
			listOf(consent(ACTIVE_CONSENT, HISTORICAL_POLICY, true)) +
				listOfNotNull(consent(REVOKED_CONSENT, CURRENT_POLICY, false).takeIf { revoked }),
		)
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = currentPolicy.policyRevision,
				legacySettingsFingerprint = null,
				updatedAtMs = if (revoked) {
					2_000L
				} else {
					0L
				},
			),
		)
		database.ambientStepsFactRevisionDao().insertRetentionAuthority(
			AmbientStepsRetentionAuthorityIntegrity.create(
				AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
				1L,
				AmbientStepsRetentionAuthorityEntity.STATE_ACTIVE,
				RETENTION_POLICY_ID,
				HISTORICAL_POLICY,
				ACTIVE_CONSENT,
				EPOCH,
				BOOT_ID,
				0L,
				0L,
			),
		)
		val demand = SourceDemandEntity(
			demandId = "native-authority-demand",
			consumerId = "native-authority-consumer",
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
			logicalTrackingId = null,
			serviceRunId = null,
			manifestRevision = null,
			lifecycleLeaseGeneration = null,
			sourcePolicyRevision = HISTORICAL_POLICY,
			consentEpoch = ACTIVE_CONSENT,
			persistenceEligible = true,
			qosCode = 1,
			minimumAcquisitionSpec = "ambient-steps:v1:LOCAL_RECORDING_STEPS",
			maximumAgeMs = 0L,
			desiredLatencyMs = 0L,
			requestedBootId = BOOT_ID,
			requestedElapsedRealtimeNanos = 0L,
			requestedAtMs = 0L,
			status = SourceDemandEntity.STATUS_RETIRED,
			retireBootId = BOOT_ID,
			retireElapsedRealtimeNanos = DAY_END,
			retiredAtMs = DAY_END,
		)
		database.sourceBrokerDao().insertDemands(listOf(demand))
		val fingerprint = SourceBrokerAuthorization.fingerprint(listOf(demand))
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				registrationGeneration = REGISTRATION,
				sourceInstanceId = SOURCE_INSTANCE,
				ownerScope = SourceProviderPurposeScope.exactOwnerScope(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
				),
				clockDomainId = BOOT_ID,
				physicalConfigurationFingerprint =
					"ambient-steps-provider:v1:mechanism=$PROVIDER",
				collectedDataEpoch = EPOCH,
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
				reservedAtMs = 0L,
				reservedElapsedRealtimeNanos = 0L,
				acceptedAtMs = 0L,
				acceptedElapsedRealtimeNanos = 0L,
				retiredAtMs = DAY_END,
				retiredElapsedRealtimeNanos = DAY_END,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				REGISTRATION,
				AUTHORIZATION,
				listOf(demand),
				BOOT_ID,
				0L,
				0L,
			),
		)
		database.ambientStepsImportStateDao().insertCursor(
			AmbientStepsImportCursorEntity(
				registrationGeneration = REGISTRATION,
				provider = PROVIDER,
				sourceInstanceId = SOURCE_INSTANCE,
				registrationClockDomainId = BOOT_ID,
				registrationAcceptedAtMs = 0L,
				registrationAcceptedElapsedRealtimeNanos = 0L,
				authorizationRevision = AUTHORIZATION,
				authorizationFingerprint = fingerprint,
				authorizationEffectiveBootId = BOOT_ID,
				authorizationEffectiveElapsedRealtimeNanos = 0L,
				authorizationEffectiveWallTimeMs = 0L,
				sourcePolicyRevision = HISTORICAL_POLICY,
				ambientConsentEpoch = ACTIVE_CONSENT,
				collectedDataEpoch = EPOCH,
				eligibleFromTimeMs = 0L,
				continuitySegmentGeneration = 1L,
				segmentStartTimeMs = 0L,
				importedThroughTimeMs = DAY_END,
				lastObservedAtMs = DAY_END,
				lastObservedBootId = BOOT_ID,
				lastObservedZoneId = "UTC",
				lastGapSequence = 0L,
				authorityTransitionSequence = 0L,
				cursorRevision = 2L,
				status = AmbientStepsImportCursorEntity.STATUS_RETIRED,
				updatedAtMs = DAY_END,
				retentionScope = AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
				retentionPolicyId = RETENTION_POLICY_ID,
				retentionApprovalRevision = 1L,
			),
		)
		val logicalFactId = AmbientStepsFactIntegrity.logicalFactId(
			PROVIDER,
			REGISTRATION,
			1L,
			SOURCE_INSTANCE,
			0L,
			0L,
			"UTC",
			EPOCH,
		)
		val unsigned = AmbientStepsFactRevisionEntity(
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			mutationId = AmbientStepsFactIntegrity.mutationId(
				logicalFactId,
				1L,
				AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
			),
			writerId = AmbientStepsFactRevisionEntity.WRITER_ID,
			writerVersion = AmbientStepsFactRevisionEntity.WRITER_VERSION,
			writerOwnerGeneration = OWNER_GENERATION,
			operation = AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
			originKind = AmbientStepsFactRevisionEntity.ORIGIN_PROVIDER_AGGREGATE,
			provider = PROVIDER,
			registrationGeneration = REGISTRATION,
			continuitySegmentGeneration = 1L,
			sourceInstanceId = SOURCE_INSTANCE,
			authorizationRevision = AUTHORIZATION,
			authorizationFingerprint = fingerprint,
			windowStartTimeMs = 0L,
			windowEndTimeMs = DAY_END,
			observedAtMs = DAY_END,
			structuralEpochDay = 0L,
			storedZoneId = "UTC",
			structuralDayStartTimeMs = 0L,
			structuralDayEndTimeMs = DAY_END,
			stepCount = 5L,
			purpose = AmbientStepsFactRevisionEntity.PURPOSE_AMBIENT_PRODUCT,
			sourcePolicyRevision = HISTORICAL_POLICY,
			ambientConsentEpoch = ACTIVE_CONSENT,
			collectedDataEpoch = EPOCH,
			scopeDeletionGeneration = 0L,
			effectChecksum = EMPTY_CHECKSUM,
			appliedAtMs = DAY_END,
			retentionScope = AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			retentionPolicyId = RETENTION_POLICY_ID,
			retentionApprovalRevision = 1L,
		)
		val fact = unsigned.copy(effectChecksum = AmbientStepsFactIntegrity.effectChecksum(unsigned))
		val portableFact = PortableAmbientStepsFactV1.create(
			AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.FACT,
				logicalFactId,
			),
			0L,
			DAY_END,
			5L,
		)
		val portableDay = PortableAmbientStepsDayV1.create(
			identity = AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.DAY,
				"0|UTC|0|$DAY_END",
			),
			structuralEpochDay = 0L,
			storedZoneId = "UTC",
			structuralDayStartTimeMs = 0L,
			structuralDayEndTimeMs = DAY_END,
			retainedFromTimeMs = null,
			coverage = PortableAmbientStepsCoverage.COMPLETE,
			partialCauses = emptyList(),
			retainedStepCount = 5L,
			facts = listOf(portableFact),
			gaps = emptyList(),
		)
		return NativeFixture(fact, PortableAmbientStepsArchiveV1.create(listOf(portableDay)))
	}

	private fun policy(revision: Long, consentEpoch: Long?, eligible: Boolean) = SourcePolicyEntity(
		policyRevision = revision,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		enabled = true,
		qosCode = 1,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = false,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = eligible,
		captureConsentEpoch = null,
		controlConsentEpoch = null,
		ambientConsentEpoch = consentEpoch,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = if (eligible) {
			0L
		} else {
			2_000L
		},
		effectiveWallTimeMs = if (eligible) {
			0L
		} else {
			2_000L
		},
		changeReason = "test",
	)

	private fun consent(epoch: Long, policyRevision: Long, eligible: Boolean) =
		SourceConsentEpochEntity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
			epoch = epoch,
			eligible = eligible,
			persistenceEligible = eligible,
			policyRevision = policyRevision,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = if (eligible) {
				0L
			} else {
				2_000L
			},
			effectiveWallTimeMs = if (eligible) {
				0L
			} else {
				2_000L
			},
			changeReason = "test",
		)

	data class NativeFixture(
		val fact: AmbientStepsFactRevisionEntity,
		val archive: PortableAmbientStepsArchiveV1,
	)

	companion object {
		const val EPOCH = 7L
		const val OWNER_GENERATION = 1L
		const val REGISTRATION = 1L
		const val AUTHORIZATION = 1L
		const val HISTORICAL_POLICY = 1L
		const val CURRENT_POLICY = 2L
		const val ACTIVE_CONSENT = 1L
		const val REVOKED_CONSENT = 2L
		const val SOURCE_INSTANCE = "native-authority-source"
		const val BOOT_ID = "native-authority-boot"
		const val RETENTION_POLICY_ID = "test-retention"
		const val PROVIDER = AmbientStepsFactRevisionEntity.PROVIDER_LOCAL_RECORDING_STEPS
		const val DAY_END = 86_400_000L
		const val DELETE_TIME = 90_000_000L
		const val EMPTY_CHECKSUM =
			"0000000000000000000000000000000000000000000000000000000000000000"
	}
}
