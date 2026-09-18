package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.dao.synchronizeLifecycle
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapIntegrity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsPartialCause
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmbientStepsPortableRoomReaderTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `one transaction exports a complete retained structural day without local authority`() = runTest {
		val fixture = seed()
		database.ambientStepsFactRevisionDao().insert(fixture.fact(0L, DAY_END, 42L))
		val checkpoints = mutableListOf<AmbientStepsPortableReadCheckpoint>()

		val result = AmbientStepsPortableRoomReader(database).read(
			AmbientStepsPortableReadRequest(1L, DAY_END),
			AmbientStepsPortableReadLimits(),
		) { checkpoint ->
			database.inTransaction() shouldBe true
			checkpoints += checkpoint
		}

		val archive = (result as AmbientStepsPortableSnapshot.Ready).archive
		checkpoints shouldContainExactly listOf(
			AmbientStepsPortableReadCheckpoint.TRANSACTION_STARTED,
			AmbientStepsPortableReadCheckpoint.AUTHORITY_AUTHENTICATED,
			AmbientStepsPortableReadCheckpoint.SNAPSHOT_ASSEMBLED,
		)
		archive.days.single().run {
			coverage shouldBe PortableAmbientStepsCoverage.COMPLETE
			retainedStepCount shouldBe 42L
		}
		val portableText = archive.toString()
		(portableText.contains(SOURCE_INSTANCE)) shouldBe false
		(portableText.contains(PROVIDER)) shouldBe false
		(portableText.contains(BOOT_ID)) shouldBe false
	}

	@Test
	fun `exact immutable gap remains partial and never becomes covered zero`() = runTest {
		val fixture = seed(
			continuityGeneration = 2L,
			segmentStartMs = 2_000L,
			lastGapSequence = 1L,
		)
		database.ambientStepsImportStateDao().insertGap(gap(1_000L, 2_000L))
		database.ambientStepsFactRevisionDao().insert(fixture.fact(0L, 1_000L, 3L, 1L))
		database.ambientStepsFactRevisionDao().insert(fixture.fact(2_000L, DAY_END, 5L, 2L))

		val result = AmbientStepsPortableRoomReader(database).read(
			AmbientStepsPortableReadRequest(0L, DAY_END),
		)

		val day = (result as AmbientStepsPortableSnapshot.Ready).archive.days.single()
		day.coverage shouldBe PortableAmbientStepsCoverage.PARTIAL
		day.partialCauses shouldContainExactly listOf(
			PortableAmbientStepsPartialCause.EXPLICIT_GAP,
		)
		day.retainedStepCount shouldBe 8L
		day.gaps.single().run {
			intervalStartTimeMs shouldBe 1_000L
			intervalEndTimeMs shouldBe 2_000L
		}
	}

	@Test
	fun `retention crossing and active structural day fail closed without an archive`() = runTest {
		val retained = seed(retainedFromMs = 1_500L)
		database.ambientStepsFactRevisionDao().insert(retained.fact(1_000L, 2_000L, 4L))

		AmbientStepsPortableRoomReader(database).read(
			AmbientStepsPortableReadRequest(0L, DAY_END),
		) shouldBe AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.RETENTION_CROSSES_FACT,
		)

		database.close()
		setUp()
		val materializing = seed(cursorStatus = AmbientStepsImportCursorEntity.STATUS_ACTIVE,
			importedThroughMs = 2_000L)
		database.ambientStepsFactRevisionDao().insert(materializing.fact(0L, 2_000L, 4L))
		AmbientStepsPortableRoomReader(database).read(
			AmbientStepsPortableReadRequest(0L, DAY_END),
		) shouldBe AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.MATERIALIZING,
		)
	}

	@Test
	fun `authenticated successor backlog before a retired fact day remains materializing`() = runTest {
		val retired = seed(importedThroughMs = SECOND_DAY_END)
		database.ambientStepsFactRevisionDao().insert(
			retired.fact(
				startTimeMs = DAY_END,
				endTimeMs = SECOND_DAY_END,
				stepCount = 11L,
				structuralEpochDay = 1L,
				structuralDayStartTimeMs = DAY_END,
				structuralDayEndTimeMs = SECOND_DAY_END,
			),
		)
		insertActiveBacklogCursor(
			registrationGeneration = SUCCESSOR_REGISTRATION,
			sourceInstanceId = SUCCESSOR_SOURCE_INSTANCE,
			importedThroughTimeMs = 1_000L,
		)

		AmbientStepsPortableRoomReader(database).read(
			AmbientStepsPortableReadRequest(DAY_END, SECOND_DAY_END),
		) shouldBe AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.MATERIALIZING,
		)
	}

	@Test
	fun `unauthenticated active backlog cursor fails closed instead of exporting retired facts`() = runTest {
		val retired = seed(importedThroughMs = SECOND_DAY_END)
		database.ambientStepsFactRevisionDao().insert(
			retired.fact(
				startTimeMs = DAY_END,
				endTimeMs = SECOND_DAY_END,
				stepCount = 11L,
				structuralEpochDay = 1L,
				structuralDayStartTimeMs = DAY_END,
				structuralDayEndTimeMs = SECOND_DAY_END,
			),
		)
		insertActiveBacklogCursor(
			registrationGeneration = SUCCESSOR_REGISTRATION,
			sourceInstanceId = SUCCESSOR_SOURCE_INSTANCE,
			importedThroughTimeMs = 1_000L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_authorization SET consumer_id = 'tampered' " +
				"WHERE source_kind = ${SourceDestinationOwnerEntity.SOURCE_STEPS} " +
				"AND registration_generation = $SUCCESSOR_REGISTRATION",
		)

		AmbientStepsPortableRoomReader(database).read(
			AmbientStepsPortableReadRequest(DAY_END, SECOND_DAY_END),
		) shouldBe AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.CORRUPT_RETAINED_STATE,
		)
	}

	@Test
	fun `later deny all invalidates an older active backlog cursor before export`() = runTest {
		val retired = seed(importedThroughMs = SECOND_DAY_END)
		database.ambientStepsFactRevisionDao().insert(
			retired.fact(
				startTimeMs = DAY_END,
				endTimeMs = SECOND_DAY_END,
				stepCount = 11L,
				structuralEpochDay = 1L,
				structuralDayStartTimeMs = DAY_END,
				structuralDayEndTimeMs = SECOND_DAY_END,
			),
		)
		insertActiveBacklogCursor(
			registrationGeneration = SUCCESSOR_REGISTRATION,
			sourceInstanceId = SUCCESSOR_SOURCE_INSTANCE,
			importedThroughTimeMs = 1_000L,
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SUCCESSOR_REGISTRATION,
				AUTHORIZATION + 1L,
				emptyList(),
				BOOT_ID,
				2_000_000_000L,
				2_000L,
			),
		)

		AmbientStepsPortableRoomReader(database).read(
			AmbientStepsPortableReadRequest(DAY_END, SECOND_DAY_END),
		) shouldBe AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.CORRUPT_RETAINED_STATE,
		)
	}

	@Test
	fun `terminal deletion and delayed exact replay remain clean no data after cursor removal`() = runTest {
		val fixture = seed(revoked = true)
		val fact = fixture.fact(0L, DAY_END, 9L)
		database.ambientStepsFactRevisionDao().insert(fact)
		database.deleteAmbientStepsAfterConsentReset(
			EPOCH,
			REVOKED_CONSENT,
			DAY_END + 1_000L,
		) shouldBe AmbientStepsSourceDeletionResult.Deleted(1, 1)

		AmbientStepsPortableRoomReader(database).read(
			AmbientStepsPortableReadRequest(0L, DAY_END),
		) shouldBe AmbientStepsPortableSnapshot.NoData

		(database.ambientStepsFactRevisionDao().insert(fact) >= 0L) shouldBe true
		AmbientStepsPortableRoomReader(database).read(
			AmbientStepsPortableReadRequest(0L, DAY_END),
		) shouldBe AmbientStepsPortableSnapshot.NoData
	}

	@Test
	fun `foreign payload version fails closed before any portable value is assembled`() = runTest {
		val fixture = seed()
		database.ambientStepsFactRevisionDao().insert(fixture.fact(0L, DAY_END, 9L))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE ambient_steps_fact_revision SET writer_version = 99",
		)

		AmbientStepsPortableRoomReader(database).read(
			AmbientStepsPortableReadRequest(0L, DAY_END),
		) shouldBe AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.CORRUPT_RETAINED_STATE,
		)
	}

	@Test
	fun `configured fact overflow and cancellation never return a partial snapshot`() = runTest {
		val fixture = seed()
		database.ambientStepsFactRevisionDao().insert(fixture.fact(0L, 1_000L, 1L))
		database.ambientStepsFactRevisionDao().insert(fixture.fact(1_000L, DAY_END, 2L))
		val reader = AmbientStepsPortableRoomReader(database)

		reader.read(
			AmbientStepsPortableReadRequest(0L, DAY_END),
			AmbientStepsPortableReadLimits(maximumFacts = 1),
		) {} shouldBe AmbientStepsPortableSnapshot.Unverifiable(
			AmbientStepsPortableReadFailure.DEPENDENCY_OVERFLOW,
		)

		val cancelled = runCatching {
			reader.read(
				AmbientStepsPortableReadRequest(0L, DAY_END),
				AmbientStepsPortableReadLimits(),
			) { checkpoint ->
				if (checkpoint == AmbientStepsPortableReadCheckpoint.AUTHORITY_AUTHENTICATED) {
					throw CancellationException("cancel before assembly")
				}
			}
		}.exceptionOrNull()
		(cancelled is CancellationException) shouldBe true
	}

	@Suppress("LongMethod")
	private suspend fun seed(
		revoked: Boolean = false,
		retainedFromMs: Long? = null,
		cursorStatus: String = AmbientStepsImportCursorEntity.STATUS_RETIRED,
		importedThroughMs: Long = DAY_END,
		continuityGeneration: Long = 1L,
		segmentStartMs: Long = 0L,
		lastGapSequence: Long = 0L,
	): Fixture {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().synchronizeLifecycle(EPOCH, retainedFromMs, 0L)
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
		val currentPolicy = if (revoked) policy(CURRENT_POLICY, null, false) else historicalPolicy
		database.sourcePolicyDao().insertPolicies(listOf(historicalPolicy) +
			listOfNotNull(currentPolicy.takeIf { it != historicalPolicy }))
		database.sourcePolicyDao().insertConsentEpochs(
			listOf(consent(ACTIVE_CONSENT, HISTORICAL_POLICY, true)) +
				listOfNotNull(consent(REVOKED_CONSENT, CURRENT_POLICY, false).takeIf { revoked }),
		)
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = currentPolicy.policyRevision,
				legacySettingsFingerprint = null,
				updatedAtMs = if (revoked) 2_000L else 0L,
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
				retainedFromMs,
			),
		)
		val demand = SourceDemandEntity(
			demandId = "ambient-demand",
			consumerId = "ambient-consumer",
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
			minimumAcquisitionSpec = AMBIENT_ACQUISITION_SPEC,
			maximumAgeMs = 0L,
			desiredLatencyMs = 0L,
			requestedBootId = BOOT_ID,
			requestedElapsedRealtimeNanos = 0L,
			requestedAtMs = 0L,
			status = if (cursorStatus == AmbientStepsImportCursorEntity.STATUS_ACTIVE) {
				SourceDemandEntity.STATUS_ACTIVE
			} else {
				SourceDemandEntity.STATUS_RETIRED
			},
			retireBootId = BOOT_ID.takeIf {
				cursorStatus == AmbientStepsImportCursorEntity.STATUS_RETIRED
			},
			retireElapsedRealtimeNanos = (importedThroughMs * 1_000_000L).takeIf {
				cursorStatus == AmbientStepsImportCursorEntity.STATUS_RETIRED
			},
			retiredAtMs = importedThroughMs.takeIf {
				cursorStatus == AmbientStepsImportCursorEntity.STATUS_RETIRED
			},
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
				status = if (cursorStatus == AmbientStepsImportCursorEntity.STATUS_ACTIVE) {
					ProviderRegistrationGenerationEntity.STATUS_ACTIVE
				} else {
					ProviderRegistrationGenerationEntity.STATUS_RETIRED
				},
				reservedAtMs = 0L,
				reservedElapsedRealtimeNanos = 0L,
				acceptedAtMs = 0L,
				acceptedElapsedRealtimeNanos = 0L,
				retiredAtMs = importedThroughMs.takeIf {
					cursorStatus == AmbientStepsImportCursorEntity.STATUS_RETIRED
				},
				retiredElapsedRealtimeNanos = (importedThroughMs * 1_000_000L).takeIf {
					cursorStatus == AmbientStepsImportCursorEntity.STATUS_RETIRED
				},
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
		if (cursorStatus == AmbientStepsImportCursorEntity.STATUS_ACTIVE) {
			database.sourceRegistrationStateDao().insertIfAbsent(
				registrationState(REGISTRATION, SOURCE_INSTANCE, importedThroughMs),
			)
		}
		val cursor = AmbientStepsImportCursorEntity(
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
			continuitySegmentGeneration = continuityGeneration,
			segmentStartTimeMs = segmentStartMs,
			importedThroughTimeMs = importedThroughMs,
			lastObservedAtMs = importedThroughMs,
			lastObservedBootId = BOOT_ID,
			lastObservedZoneId = "UTC",
			lastGapSequence = lastGapSequence,
			authorityTransitionSequence = 0L,
			cursorRevision = 2L,
			status = cursorStatus,
			updatedAtMs = importedThroughMs,
			retentionScope = AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			retentionPolicyId = RETENTION_POLICY_ID,
			retentionApprovalRevision = 1L,
		)
		database.ambientStepsImportStateDao().insertCursor(cursor)
		return Fixture(fingerprint)
	}

	private suspend fun insertActiveBacklogCursor(
		registrationGeneration: Long,
		sourceInstanceId: String,
		importedThroughTimeMs: Long,
	) {
		val demand = SourceDemandEntity(
			demandId = "ambient-demand-$registrationGeneration",
			consumerId = "ambient-consumer-$registrationGeneration",
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
			minimumAcquisitionSpec = AMBIENT_ACQUISITION_SPEC,
			maximumAgeMs = 0L,
			desiredLatencyMs = 0L,
			requestedBootId = BOOT_ID,
			requestedElapsedRealtimeNanos = 0L,
			requestedAtMs = 0L,
			status = SourceDemandEntity.STATUS_ACTIVE,
			retireBootId = null,
			retireElapsedRealtimeNanos = null,
			retiredAtMs = null,
		)
		database.sourceBrokerDao().insertDemands(listOf(demand))
		val fingerprint = SourceBrokerAuthorization.fingerprint(listOf(demand))
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				registrationGeneration = registrationGeneration,
				sourceInstanceId = sourceInstanceId,
				ownerScope = SourceProviderPurposeScope.exactOwnerScope(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
				),
				clockDomainId = BOOT_ID,
				physicalConfigurationFingerprint =
					"ambient-steps-provider:v1:mechanism=$PROVIDER",
				collectedDataEpoch = EPOCH,
				providerResidency =
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 0L,
				reservedElapsedRealtimeNanos = 0L,
				acceptedAtMs = 0L,
				acceptedElapsedRealtimeNanos = 0L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				registrationGeneration,
				AUTHORIZATION,
				listOf(demand),
				BOOT_ID,
				0L,
				0L,
			),
		)
		database.sourceRegistrationStateDao().replace(
			registrationState(registrationGeneration, sourceInstanceId, importedThroughTimeMs),
		)
		database.ambientStepsImportStateDao().insertCursor(
			AmbientStepsImportCursorEntity(
				registrationGeneration = registrationGeneration,
				provider = PROVIDER,
				sourceInstanceId = sourceInstanceId,
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
				importedThroughTimeMs = importedThroughTimeMs,
				lastObservedAtMs = importedThroughTimeMs,
				lastObservedBootId = BOOT_ID,
				lastObservedZoneId = "UTC",
				lastGapSequence = 0L,
				authorityTransitionSequence = 0L,
				cursorRevision = 1L,
				status = AmbientStepsImportCursorEntity.STATUS_ACTIVE,
				updatedAtMs = importedThroughTimeMs,
				retentionScope = AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
				retentionPolicyId = RETENTION_POLICY_ID,
				retentionApprovalRevision = 1L,
			),
		)
	}

	private fun registrationState(
		registrationGeneration: Long,
		sourceInstanceId: String,
		updatedAtMs: Long,
	) = SourceRegistrationStateEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		ownerScope = SourceProviderPurposeScope.exactOwnerScope(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
		),
		sourceInstanceId = sourceInstanceId,
		clockDomainId = BOOT_ID,
		registrationGeneration = registrationGeneration,
		nextSequence = 1L,
		appliedRevision = HISTORICAL_POLICY,
		collectedDataEpoch = EPOCH,
		updatedAtMs = updatedAtMs,
	)

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
		effectiveElapsedRealtimeNanos = if (eligible) 0L else 2_000_000_000L,
		effectiveWallTimeMs = if (eligible) 0L else 2_000L,
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
			effectiveElapsedRealtimeNanos = if (eligible) 0L else 2_000_000_000L,
			effectiveWallTimeMs = if (eligible) 0L else 2_000L,
			changeReason = "test",
		)

	private data class Fixture(val authorizationFingerprint: String) {
		fun fact(
			startTimeMs: Long,
			endTimeMs: Long,
			stepCount: Long,
			continuityGeneration: Long = 1L,
			structuralEpochDay: Long = 0L,
			structuralDayStartTimeMs: Long = 0L,
			structuralDayEndTimeMs: Long = DAY_END,
		): AmbientStepsFactRevisionEntity {
			val logicalFactId = AmbientStepsFactIntegrity.logicalFactId(
				PROVIDER,
				REGISTRATION,
				continuityGeneration,
				SOURCE_INSTANCE,
				startTimeMs,
				structuralEpochDay,
				"UTC",
				EPOCH,
			)
			val unsigned = AmbientStepsFactRevisionEntity(
				logicalFactId,
				1L,
				AmbientStepsFactIntegrity.mutationId(
					logicalFactId,
					1L,
					AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
				),
				AmbientStepsFactRevisionEntity.WRITER_ID,
				AmbientStepsFactRevisionEntity.WRITER_VERSION,
				OWNER_GENERATION,
				AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
				AmbientStepsFactRevisionEntity.ORIGIN_PROVIDER_AGGREGATE,
				PROVIDER,
				REGISTRATION,
				continuityGeneration,
				SOURCE_INSTANCE,
				AUTHORIZATION,
				authorizationFingerprint,
				startTimeMs,
				endTimeMs,
				endTimeMs,
				0L,
				"UTC",
				structuralDayStartTimeMs,
				structuralDayEndTimeMs,
				stepCount,
				AmbientStepsFactRevisionEntity.PURPOSE_AMBIENT_PRODUCT,
				HISTORICAL_POLICY,
				ACTIVE_CONSENT,
				EPOCH,
				0L,
				"0".repeat(64),
				endTimeMs,
				AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
				RETENTION_POLICY_ID,
				1L,
			)
			return unsigned.copy(effectChecksum = AmbientStepsFactIntegrity.effectChecksum(unsigned))
		}
	}

	private fun gap(startMs: Long, endMs: Long): AmbientStepsImportGapEntity {
		val gapId = AmbientStepsImportGapIntegrity.gapId(
			REGISTRATION,
			1L,
			PROVIDER,
			SOURCE_INSTANCE,
			AmbientStepsImportGapEntity.REASON_PROCESS_ABSENCE,
			startMs,
			endMs,
			null,
			null,
			BOOT_ID,
			BOOT_ID,
			"UTC",
			"UTC",
			EPOCH,
		)
		return AmbientStepsImportGapEntity(
			gapId,
			REGISTRATION,
			1L,
			PROVIDER,
			SOURCE_INSTANCE,
			AmbientStepsImportGapEntity.REASON_PROCESS_ABSENCE,
			startMs,
			endMs,
			null,
			null,
			BOOT_ID,
			BOOT_ID,
			"UTC",
			"UTC",
			EPOCH,
			endMs,
		)
	}

	private companion object {
		const val EPOCH = 7L
		const val OWNER_GENERATION = 3L
		const val REGISTRATION = 1L
		const val SUCCESSOR_REGISTRATION = 2L
		const val AUTHORIZATION = 1L
		const val HISTORICAL_POLICY = 1L
		const val CURRENT_POLICY = 2L
		const val ACTIVE_CONSENT = 4L
		const val REVOKED_CONSENT = 5L
		const val SOURCE_INSTANCE = "ambient-source"
		const val SUCCESSOR_SOURCE_INSTANCE = "ambient-source-successor"
		const val BOOT_ID = "boot-a"
		const val RETENTION_POLICY_ID = "test-retention"
		const val PROVIDER =
			AmbientStepsFactRevisionEntity.PROVIDER_LOCAL_RECORDING_STEPS
		const val DAY_END = 86_400_000L
		const val SECOND_DAY_END = DAY_END * 2L
		const val AMBIENT_ACQUISITION_SPEC =
			"ambient-steps:v1:mechanism=LOCAL_RECORDING_STEPS;" +
				"coverage=OPPORTUNISTIC;record_freshness=SOURCE_NATIVE_CURSOR"
	}
}
