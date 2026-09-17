package com.adsamcik.tracker.app.tracking

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableReadRequest
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableRoomReader
import com.adsamcik.tracker.shared.base.database.AmbientStepsPortableSnapshot
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.AmbientStepsRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientStepsRetentionDecision
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
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.repository.DeleteImportedAmbientStepsDayRequest
import com.adsamcik.tracker.stats.api.repository.DeleteImportedAmbientStepsDayResult
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production native Room -> file -> imported Room reader -> file re-export contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortableAmbientStepsFileProductionRoundTripTest {
	private lateinit var context: Application
	private lateinit var source: AppDatabase
	private lateinit var destination: AppDatabase

	@Before
	fun setUp() = runTest {
		context = ApplicationProvider.getApplicationContext()
		source = AppDatabase.testDatabase(context)
		destination = AppDatabase.testDatabase(context)
		seedNativeAuthority()
		ensureEpoch(destination)
	}

	@After
	fun tearDown() {
		source.close()
		destination.close()
	}

	@Test
	fun `native partial outside-authority zero and nonzero facts survive file import and reexport`() =
		runTest {
			source.ambientStepsFactRevisionDao().insert(nativeFact(0L, 1_000L, 0L))
			source.ambientStepsFactRevisionDao().insert(nativeFact(2_000L, DAY_END, 12L))
			val sourceArchive = (
				AmbientStepsPortableRoomReader(source).read(
					AmbientStepsPortableReadRequest(0L, DAY_END),
				) as AmbientStepsPortableSnapshot.Ready
				).archive
			val nativeExporter = PortableAmbientStepsFileInternals.nativeFileExporter(
				source,
				Dispatchers.Unconfined,
			)
			val exported = ByteArrayOutputStream()

			nativeExporter.export(
				context,
				emptySequence<LocationSample>(),
				exported,
				0L until DAY_END,
			) shouldBe ExportResult.Success(recordCount = 1)

			val fileText = exported.toString(Charsets.UTF_8.name())
			fileText.contains("\"coverage\":\"PARTIAL\"") shouldBe true
			fileText.contains("\"partialCauses\":[\"OUTSIDE_AUTHORITY\"]") shouldBe true
			fileText.contains("\"stepCount\":0") shouldBe true
			fileText.contains("\"stepCount\":12") shouldBe true

			val fileImporter = PortableAmbientStepsFileInternals.fileImporter(
				destination,
				FixedAmbientLifecycleStore(),
				Dispatchers.Unconfined,
			)
			fileImporter.import(
				context,
				destination,
				PortableAmbientStepsFileInternals.boundStream(
					exported.toByteArray(),
					"ambient.trackerambientsteps",
					"direct-source-v1",
					"content-addressed-job",
					DAY_END + 1_000L,
				),
			).successCount shouldBe 1

			destination.ambientStepsFactRevisionDao().countAll() shouldBe 0L
			destination.ambientStepsImportStateDao().countCursors() shouldBe 0L
			destination.ambientStepsImportStateDao().countGaps() shouldBe 0L
			val reexported = ByteArrayOutputStream()
			PortableAmbientStepsFileInternals.importedFileExporter(
				destination,
				Dispatchers.Unconfined,
			).export(
				context,
				emptySequence(),
				reexported,
				0L until DAY_END,
			) shouldBe ExportResult.Success(recordCount = 1)
			reexported.toByteArray().contentEquals(exported.toByteArray()) shouldBe true

			PortableAmbientStepsFileInternals.dayDeleter(
				destination,
				Dispatchers.Unconfined,
			).deleteDay(
				DeleteImportedAmbientStepsDayRequest(
					sourceArchive.days.single().identity,
					EPOCH,
					DAY_END + 2_000L,
				),
			) shouldBe DeleteImportedAmbientStepsDayResult.Deleted(1)
			fileImporter.import(
				context,
				destination,
				PortableAmbientStepsFileInternals.boundStream(
					exported.toByteArray(),
					"ambient.trackerambientsteps",
					"replay-source-v1",
					"replay-content-job",
					DAY_END + 3_000L,
				),
			).skippedCount shouldBe 1
		}

	@Suppress("LongMethod")
	private suspend fun seedNativeAuthority() {
		ensureEpoch(source)
		source.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				destination = SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
				owner = SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS,
				ownerGeneration = OWNER_GENERATION,
				updatedAtMs = 0L,
			),
		)
		source.sourcePolicyDao().insertPolicies(listOf(policy()))
		source.sourcePolicyDao().insertConsentEpochs(listOf(consent()))
		source.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = POLICY_REVISION,
				legacySettingsFingerprint = null,
				updatedAtMs = 0L,
			),
		)
		source.applyAmbientStepsRetentionDecision(
			AmbientStepsRetentionDecision.GrantLiveAmbient(
				opaquePolicyId = RETENTION_POLICY_ID,
				expectedCollectedDataEpoch = EPOCH,
				expectedSourcePolicyRevision = POLICY_REVISION,
				expectedAmbientConsentEpoch = CONSENT_EPOCH,
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = 0L,
				effectiveWallTimeMs = 0L,
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
			sourcePolicyRevision = POLICY_REVISION,
			consentEpoch = CONSENT_EPOCH,
			persistenceEligible = true,
			qosCode = 1,
			minimumAcquisitionSpec = ACQUISITION_SPEC,
			maximumAgeMs = 0L,
			desiredLatencyMs = 0L,
			requestedBootId = BOOT_ID,
			requestedElapsedRealtimeNanos = 0L,
			requestedAtMs = 0L,
			status = SourceDemandEntity.STATUS_RETIRED,
			retireBootId = BOOT_ID,
			retireElapsedRealtimeNanos = DAY_END * 1_000_000L,
			retiredAtMs = DAY_END,
		)
		source.sourceBrokerDao().insertDemands(listOf(demand))
		val fingerprint = SourceBrokerAuthorization.fingerprint(listOf(demand))
		source.sourceBrokerDao().insertRegistration(
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
				providerResidency =
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE,
				providerProcessIncarnationId = null,
				status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
				reservedAtMs = 0L,
				reservedElapsedRealtimeNanos = 0L,
				acceptedAtMs = 0L,
				acceptedElapsedRealtimeNanos = 0L,
				retiredAtMs = DAY_END,
				retiredElapsedRealtimeNanos = DAY_END * 1_000_000L,
				failureCode = null,
			),
		)
		source.sourceBrokerDao().insertAuthorizations(
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
		source.ambientStepsImportStateDao().insertCursor(
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
				sourcePolicyRevision = POLICY_REVISION,
				ambientConsentEpoch = CONSENT_EPOCH,
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
				retentionScope = LIVE_SCOPE,
				retentionPolicyId = RETENTION_POLICY_ID,
				retentionApprovalRevision = 1L,
			),
		)
	}

	private suspend fun ensureEpoch(database: AppDatabase) {
		val dao = database.sourceEvidenceStateDao()
		dao.ensure(SourceEvidenceState())
		if (dao.get()?.collectedDataEpoch != EPOCH) {
			check(dao.updateLifecycle(EPOCH, null, 0L) == 1)
		}
	}

	private fun nativeFact(
		startTimeMs: Long,
		endTimeMs: Long,
		stepCount: Long,
	): AmbientStepsFactRevisionEntity {
		val logicalFactId = AmbientStepsFactIntegrity.logicalFactId(
			PROVIDER,
			REGISTRATION,
			1L,
			SOURCE_INSTANCE,
			startTimeMs,
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
			authorizationFingerprint = SourceBrokerAuthorization.fingerprint(listOf(demand())),
			windowStartTimeMs = startTimeMs,
			windowEndTimeMs = endTimeMs,
			observedAtMs = endTimeMs,
			structuralEpochDay = 0L,
			storedZoneId = "UTC",
			structuralDayStartTimeMs = 0L,
			structuralDayEndTimeMs = DAY_END,
			stepCount = stepCount,
			purpose = AmbientStepsFactRevisionEntity.PURPOSE_AMBIENT_PRODUCT,
			sourcePolicyRevision = POLICY_REVISION,
			ambientConsentEpoch = CONSENT_EPOCH,
			collectedDataEpoch = EPOCH,
			scopeDeletionGeneration = 0L,
			effectChecksum = "0".repeat(64),
			appliedAtMs = endTimeMs,
			retentionScope = LIVE_SCOPE,
			retentionPolicyId = RETENTION_POLICY_ID,
			retentionApprovalRevision = 1L,
		)
		return unsigned.copy(effectChecksum = AmbientStepsFactIntegrity.effectChecksum(unsigned))
	}

	private fun policy() = SourcePolicyEntity(
		policyRevision = POLICY_REVISION,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		enabled = true,
		qosCode = 1,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = false,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = true,
		captureConsentEpoch = null,
		controlConsentEpoch = null,
		ambientConsentEpoch = CONSENT_EPOCH,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = 0L,
		effectiveWallTimeMs = 0L,
		changeReason = "file round trip",
	)

	private fun consent() = SourceConsentEpochEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
		epoch = CONSENT_EPOCH,
		eligible = true,
		persistenceEligible = true,
		policyRevision = POLICY_REVISION,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = 0L,
		effectiveWallTimeMs = 0L,
		changeReason = "file round trip",
	)

	private fun demand() = SourceDemandEntity(
		demandId = "ambient-demand",
		consumerId = "ambient-consumer",
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		lifecycleLeaseGeneration = null,
		sourcePolicyRevision = POLICY_REVISION,
		consentEpoch = CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = 1,
		minimumAcquisitionSpec = ACQUISITION_SPEC,
		maximumAgeMs = 0L,
		desiredLatencyMs = 0L,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = 0L,
		requestedAtMs = 0L,
		status = SourceDemandEntity.STATUS_RETIRED,
		retireBootId = BOOT_ID,
		retireElapsedRealtimeNanos = DAY_END * 1_000_000L,
		retiredAtMs = DAY_END,
	)

	private class FixedAmbientLifecycleStore : CollectedDataLifecycleStore {
		private val state = MutableStateFlow(CollectedDataLifecycleSnapshot(EPOCH, null))
		override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state
		override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value
		override suspend fun beginFullDeletion(deletedAtMs: Long) = error("Not used")
		override suspend fun advanceRetainedFrom(retainedFromMs: Long) = error("Not used")
	}

	private companion object {
		const val EPOCH = 7L
		const val OWNER_GENERATION = 3L
		const val REGISTRATION = 1L
		const val AUTHORIZATION = 1L
		const val POLICY_REVISION = 1L
		const val CONSENT_EPOCH = 4L
		const val SOURCE_INSTANCE = "ambient-source"
		const val BOOT_ID = "boot-a"
		const val RETENTION_POLICY_ID = "test-retention"
		const val LIVE_SCOPE = "LIVE_AMBIENT"
		const val PROVIDER = AmbientStepsFactRevisionEntity.PROVIDER_LOCAL_RECORDING_STEPS
		const val DAY_END = 86_400_000L
		const val ACQUISITION_SPEC =
			"ambient-steps:v1:mechanism=LOCAL_RECORDING_STEPS;" +
				"coverage=OPPORTUNISTIC;record_freshness=SOURCE_NATIVE_CURSOR"
	}
}
