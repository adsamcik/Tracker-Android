package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.AmbientStepsSourceDeletionResult
import com.adsamcik.tracker.shared.base.database.deleteAmbientStepsAfterConsentReset
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
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
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportMetadata
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportUnverifiableReason
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomImportPortableAmbientStepsNativeAuthorityTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = newDatabase()
	}

	@After
	fun tearDown() {
		if (::database.isInitialized && database.isOpen) database.close()
	}

	@Test
	fun `authenticated native fact blocks its exact portable identity`() = runTest {
		val fixture = seedNative(revoked = false)
		database.ambientStepsFactRevisionDao().insert(fixture.fact)

		importer().importArchive(request(fixture.archive)) shouldBe
			ImportPortableAmbientStepsResult.Blocked(
				PortableAmbientStepsImportBlockedReason.LOCAL_ORIGIN_OVERLAP,
			)
		database.importedAmbientStepsDao().archiveCount() shouldBe 0L
	}

	@Test
	fun `missing native cursor is retained evidence corruption not an identity conflict`() = runTest {
		assertNativeAuthorityCorruption { database.ambientStepsImportStateDao().deleteAllCursors() }
	}

	@Test
	fun `missing native destination owner is retained evidence corruption`() = runTest {
		assertNativeAuthorityCorruption {
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM source_destination_owner WHERE source_kind = ? AND destination = ?",
				arrayOf(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
				),
			)
		}
	}

	@Test
	fun `corrupt native registration is retained evidence corruption`() = runTest {
		assertNativeAuthorityCorruption {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE provider_registration_generation SET source_instance_id = 'corrupt' " +
					"WHERE source_kind = ? AND registration_generation = ?",
				arrayOf(SourceDestinationOwnerEntity.SOURCE_STEPS, REGISTRATION),
			)
		}
	}

	@Test
	fun `wrong native cursor epoch is retained evidence corruption`() = runTest {
		assertNativeAuthorityCorruption {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE ambient_steps_import_cursor SET collected_data_epoch = ? " +
					"WHERE registration_generation = ?",
				arrayOf(EPOCH + 1L, REGISTRATION),
			)
		}
	}

	@Test
	fun `wrong native fact epoch is retained evidence corruption`() = runTest {
		assertNativeAuthorityCorruption {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE ambient_steps_fact_revision SET collected_data_epoch = ?",
				arrayOf(EPOCH + 1L),
			)
		}
	}

	@Test
	fun `broken native gap linkage is retained evidence corruption`() = runTest {
		assertNativeAuthorityCorruption {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE ambient_steps_import_cursor SET last_gap_sequence = 1, " +
					"continuity_segment_generation = 2 WHERE registration_generation = ?",
				arrayOf(REGISTRATION),
			)
		}
	}

	@Test
	fun `broken native transition linkage is retained evidence corruption`() = runTest {
		assertNativeAuthorityCorruption {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE ambient_steps_import_cursor SET authority_transition_sequence = 1, " +
					"continuity_segment_generation = 2 WHERE registration_generation = ?",
				arrayOf(REGISTRATION),
			)
		}
	}

	@Test
	fun `unknown native writer payload is retained evidence corruption`() = runTest {
		assertNativeAuthorityCorruption {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE ambient_steps_fact_revision SET writer_id = 'unknown-writer'",
			)
		}
	}

	@Test
	fun `terminal native retraction blocks old portable replay without provider authority`() = runTest {
		val fixture = seedNative(revoked = true)
		database.ambientStepsFactRevisionDao().insert(fixture.fact)
		database.deleteAmbientStepsAfterConsentReset(EPOCH, REVOKED_CONSENT, DELETE_TIME) shouldBe
			AmbientStepsSourceDeletionResult.Deleted(1, 1)

		importer().importArchive(request(fixture.archive)) shouldBe
			ImportPortableAmbientStepsResult.Blocked(
				PortableAmbientStepsImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
			)
		database.importedAmbientStepsDao().archiveCount() shouldBe 0L
	}

	private suspend fun assertNativeAuthorityCorruption(
		mutate: suspend (NativeFixture) -> Unit,
	) {
		val fixture = seedNative(revoked = false)
		database.ambientStepsFactRevisionDao().insert(fixture.fact)
		mutate(fixture)

		importer().importArchive(request(fixture.archive)) shouldBe
			ImportPortableAmbientStepsResult.Unverifiable(
				PortableAmbientStepsImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		database.importedAmbientStepsDao().archiveCount() shouldBe 0L
	}

	private suspend fun seedNative(revoked: Boolean): NativeFixture {
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
				updatedAtMs = if (revoked) 2_000L else 0L,
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
		effectiveElapsedRealtimeNanos = if (eligible) 0L else 2_000L,
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
			effectiveElapsedRealtimeNanos = if (eligible) 0L else 2_000L,
			effectiveWallTimeMs = if (eligible) 0L else 2_000L,
			changeReason = "test",
		)

	private fun request(archive: PortableAmbientStepsArchiveV1) = ImportPortableAmbientStepsRequest(
		archive = archive,
		receipt = PortableAmbientStepsImportReceipt(
			"native-authority-job",
			"native-authority-archive",
			"backup.trackerambientsteps",
			DAY_END,
		),
		metadata = PortableAmbientStepsImportMetadata(
			encodedByteCount = 1_024L,
			archiveContentChecksum = archive.contentChecksum,
			dayCount = archive.days.size,
			factCount = archive.days.sumOf { it.facts.size },
			gapCount = archive.days.sumOf { it.gaps.size },
		),
		expectedCollectedDataEpoch = EPOCH,
	)

	private fun importer() = RoomImportPortableAmbientSteps(
		database,
		database.importedAmbientStepsDao(),
		Dispatchers.Unconfined,
	)

	private fun newDatabase(): AppDatabase = AppDatabase.testDatabase(
		ApplicationProvider.getApplicationContext<Application>(),
	)

	private data class NativeFixture(
		val fact: AmbientStepsFactRevisionEntity,
		val archive: PortableAmbientStepsArchiveV1,
	)

	private companion object {
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
		const val PROVIDER = AmbientStepsFactRevisionEntity.PROVIDER_LOCAL_RECORDING_STEPS
		const val DAY_END = 86_400_000L
		const val DELETE_TIME = 90_000_000L
		const val EMPTY_CHECKSUM =
			"0000000000000000000000000000000000000000000000000000000000000000"
	}
}
