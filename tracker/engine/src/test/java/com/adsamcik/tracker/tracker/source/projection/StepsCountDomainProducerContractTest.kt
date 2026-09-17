package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLookupKey
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerRead
import com.adsamcik.tracker.shared.base.database.StepsCountDomainSchema
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.StepsCountDomainWriteResult
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsCountDomainProducerContractTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		StepsCountDomainSchema.createStatements.forEach { statement ->
			database.openHelper.writableDatabase.execSQL(statement)
		}
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `session admission fact completeness and native ambient fact preserve one exact domain`() =
		runTest {
			val store = StepsCountDomainStore(database)
			val wal = insertWal()
			store.recordSessionWal(wal) shouldBe StepsCountDomainWriteResult.INSERTED
			val fact = sessionFact(wal)
			database.stepFactRevisionDao().insert(fact) shouldNotBe -1L
			store.recordSessionFact(fact) shouldBe StepsCountDomainWriteResult.INSERTED
			val completeness = completeness(wal)
			database.sourceSessionDao().saveCompleteness(completeness)
			store.recordSessionCompleteness(completeness) shouldBe
				StepsCountDomainWriteResult.INSERTED
			val ambient = ambientFact()
			database.ambientStepsFactRevisionDao().insert(ambient) shouldNotBe -1L
			store.recordAmbientFact(ambient, PROVIDER_DOMAIN) shouldBe
				StepsCountDomainWriteResult.INSERTED

			val sessionFactOwner = StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
				StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
					fact.writerProjectionId,
					fact.writerProjectionVersion,
					fact.logicalFactId,
				),
				fact.semanticRevision,
			)
			val completenessOwner = StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS,
				StepsCountDomainReceiptIntegrity.sessionCompletenessOwnerIdentity(
					completeness.logicalTrackingId,
					completeness.serviceRunId,
					completeness.sourceInstanceId,
					completeness.registrationGeneration,
				),
				StepsCountDomainReceiptIntegrity.completenessOwnerRevision(completeness),
			)
			val ambientOwner = StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
				StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
					ambient.writerId,
					ambient.writerVersion,
					ambient.logicalFactId,
				),
				ambient.semanticRevision,
			)
			val recovered = StepsCountDomainStore(database).readOwners(
				listOf(sessionFactOwner, completenessOwner, ambientOwner),
			) as StepsCountDomainOwnerRead.Ready
			val domains = recovered.owners.values.mapNotNull { it.receipt?.domainIdentity }.toSet()

			domains.size shouldBe 1
		}

	@Test
	fun `correction retains old receipt and terminal session retraction blocks resurrection`() =
		runTest {
			val store = StepsCountDomainStore(database)
			val wal = insertWal()
			store.recordSessionWal(wal)
			val fact = sessionFact(wal)
			store.recordSessionFact(fact) shouldBe StepsCountDomainWriteResult.INSERTED

			val retraction = sessionRetraction(fact)
			store.recordSessionFactRetraction(
				retraction,
				LOGICAL_TRACKING_ID,
				SERVICE_RUN_ID,
			) shouldBe StepsCountDomainWriteResult.INSERTED
			store.recordSessionFact(fact) shouldBe
				StepsCountDomainWriteResult.TERMINALLY_RETRACTED

			val ambientOne = ambientFact()
			val ambientTwoUnsigned = ambientOne.copy(
				semanticRevision = 2L,
				mutationId = AmbientStepsFactIntegrity.mutationId(
					ambientOne.logicalFactId,
					2L,
					AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
				),
				stepCount = 8L,
				observedAtMs = 3_000L,
				appliedAtMs = 3_000L,
				effectChecksum = "0".repeat(64),
			)
			val ambientTwo = ambientTwoUnsigned.copy(
				effectChecksum = AmbientStepsFactIntegrity.effectChecksum(ambientTwoUnsigned),
			)
			store.recordAmbientFact(ambientOne, PROVIDER_DOMAIN) shouldBe
				StepsCountDomainWriteResult.INSERTED
			store.recordAmbientFact(ambientTwo, PROVIDER_DOMAIN) shouldBe
				StepsCountDomainWriteResult.INSERTED

			val ownerIdentity = StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
				ambientOne.writerId,
				ambientOne.writerVersion,
				ambientOne.logicalFactId,
			)
			val read = store.readOwners(
				listOf(
					StepsCountDomainOwnerLookupKey(
						StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
						ownerIdentity,
						1L,
					),
					StepsCountDomainOwnerLookupKey(
						StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
						ownerIdentity,
						2L,
					),
				),
			) as StepsCountDomainOwnerRead.Ready
			read.owners.values.mapNotNull { it.receipt?.receiptIdentity }.distinct().size shouldBe 2
			read.owners.values.mapNotNull { it.receipt?.domainIdentity }.distinct().size shouldBe 1
		}

	@Test
	fun `ambient database retraction trigger is terminal across a new store instance`() = runTest {
		val store = StepsCountDomainStore(database)
		val fact = ambientFact()
		database.ambientStepsFactRevisionDao().insert(fact) shouldNotBe -1L
		store.recordAmbientFact(fact, PROVIDER_DOMAIN) shouldBe
			StepsCountDomainWriteResult.INSERTED
		val retraction = ambientRetraction(fact)

		database.ambientStepsFactRevisionDao().insert(retraction) shouldNotBe -1L

		val ownerIdentity = StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
			fact.writerId,
			fact.writerVersion,
			fact.logicalFactId,
		)
		val read = StepsCountDomainStore(database).readOwners(
			listOf(
				StepsCountDomainOwnerLookupKey(
					StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
					ownerIdentity,
					retraction.semanticRevision,
				),
			),
		) as StepsCountDomainOwnerRead.Ready
		read.owners.values.single().owner.operation shouldBe
			StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT
		store.recordAmbientFact(fact, PROVIDER_DOMAIN) shouldBe
			StepsCountDomainWriteResult.TERMINALLY_RETRACTED
	}

	private suspend fun insertWal(): SourceEventWalEntity {
		val unsigned = SourceEventWalEntity(
			eventId = EVENT_ID,
			providerDedupKey = "steps-dedup",
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			sourceInstanceId = SOURCE_INSTANCE,
			registrationGeneration = 1L,
			physicalConfigurationFingerprint = PROVIDER_DOMAIN,
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = "a".repeat(64),
			sourceSequence = 1L,
			configRevision = 1L,
			planAttribution = 0,
			clockDomainId = "boot",
			observedElapsedNanos = 2_000_000_000L,
			receivedElapsedNanos = 2_000_000_000L,
			wallTimeMs = 2_000L,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 7L,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			sessionManifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			acquiredAtMs = 2_000L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 3,
			payload = byteArrayOf(1, 2, 3),
			payloadChecksum = "",
			createdAtMs = 2_000L,
		)
		val payloadSigned = unsigned.copy(payloadChecksum = unsigned.calculatedPayloadChecksum())
		val signed = payloadSigned.copy(
			integrityIdentity = payloadSigned.calculatedIntegrityIdentity(),
		)
		val rowId = database.sourceEventWalDao().insertAbortingOnUnexpectedConflict(signed)
		return signed.copy(admissionOrdinal = rowId)
	}

	private fun sessionFact(wal: SourceEventWalEntity): StepFactRevisionEntity {
		val logicalFactId =
			"${SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID}:${wal.eventId}"
		val unsigned = StepFactRevisionEntity(
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			mutationId = "$logicalFactId:1:${StepFactRevisionEntity.OPERATION_UPSERT}",
			stepIntervalId = null,
			sourceEventId = wal.eventId,
			sourceAdmissionOrdinal = wal.admissionOrdinal,
			originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
			originIdentity = wal.eventId,
			writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
			writerBindingGeneration = 1L,
			operation = StepFactRevisionEntity.OPERATION_UPSERT,
			intervalStartTimeMs = 1_000L,
			intervalEndTimeMs = 2_000L,
			intervalStartElapsedRealtimeNanos = 1_000_000_000L,
			intervalEndElapsedRealtimeNanos = 2_000_000_000L,
			clockDomainId = "boot",
			bootClockDomainId = "boot",
			cumulativeStepCountStart = 10L,
			cumulativeStepCountEnd = 15L,
			wallTimeUncertaintyMs = 0L,
			coverageKind = StepFactRevisionEntity.COVERAGE_COVERED,
			effectiveStepCount = 5L,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = 1L,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			collectedDataEpoch = 7L,
			scopeDeletionGeneration = 0L,
			effectChecksum = "0".repeat(64),
			appliedAtMs = 2_000L,
		)
		return unsigned.copy(
			effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsigned),
		)
	}

	private fun completeness(wal: SourceEventWalEntity) = SourceSessionCompletenessEntity(
		logicalTrackingId = LOGICAL_TRACKING_ID,
		serviceRunId = SERVICE_RUN_ID,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		sourceInstanceId = SOURCE_INSTANCE,
		registrationGeneration = 1L,
		lastAdmissionOrdinal = wal.admissionOrdinal,
		lastSourceSequence = 1L,
		appDrainComplete = true,
		providerCoverage = "CALLBACKS_ENTERED_BEFORE_BARRIER",
		stopStatus = "COMPLETE",
		unresolvedSequenceStart = null,
		unresolvedSequenceEnd = null,
		updatedAtMs = 3_000L,
	)

	private fun ambientFact(): AmbientStepsFactRevisionEntity {
		val logicalFactId = AmbientStepsFactIntegrity.logicalFactId(
			AmbientStepsFactRevisionEntity.PROVIDER_LOCAL_RECORDING_STEPS,
			1L,
			1L,
			SOURCE_INSTANCE,
			1_000L,
			0L,
			"UTC",
			7L,
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
			writerOwnerGeneration = 1L,
			operation = AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
			originKind = AmbientStepsFactRevisionEntity.ORIGIN_PROVIDER_AGGREGATE,
			provider = AmbientStepsFactRevisionEntity.PROVIDER_LOCAL_RECORDING_STEPS,
			registrationGeneration = 1L,
			continuitySegmentGeneration = 1L,
			sourceInstanceId = SOURCE_INSTANCE,
			authorizationRevision = 2L,
			authorizationFingerprint = "b".repeat(64),
			windowStartTimeMs = 1_000L,
			windowEndTimeMs = 2_000L,
			observedAtMs = 2_000L,
			structuralEpochDay = 0L,
			storedZoneId = "UTC",
			structuralDayStartTimeMs = 0L,
			structuralDayEndTimeMs = 86_400_000L,
			stepCount = 5L,
			purpose = AmbientStepsFactRevisionEntity.PURPOSE_AMBIENT_PRODUCT,
			sourcePolicyRevision = 2L,
			ambientConsentEpoch = 2L,
			collectedDataEpoch = 7L,
			scopeDeletionGeneration = 0L,
			effectChecksum = "0".repeat(64),
			appliedAtMs = 2_000L,
		)
		return unsigned.copy(
			effectChecksum = AmbientStepsFactIntegrity.effectChecksum(unsigned),
		)
	}

	private fun sessionRetraction(fact: StepFactRevisionEntity): StepFactRevisionEntity {
		val scopeIdentity = com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
			.logicalServiceRunIdentity(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				LOGICAL_TRACKING_ID,
				SERVICE_RUN_ID,
			)
		val unsigned = fact.copy(
			semanticRevision = 2L,
			mutationId = StepFactRevisionIntegrity.localDeleteMutationId(
				scopeIdentity,
				fact.logicalFactId,
				2L,
				1L,
			),
			sourceEventId = null,
			sourceAdmissionOrdinal = null,
			originKind = StepFactRevisionEntity.ORIGIN_LOCAL_DELETE,
			originIdentity = scopeIdentity,
			operation = StepFactRevisionEntity.OPERATION_RETRACT,
			intervalStartTimeMs = null,
			intervalEndTimeMs = null,
			intervalStartElapsedRealtimeNanos = null,
			intervalEndElapsedRealtimeNanos = null,
			clockDomainId = null,
			bootClockDomainId = null,
			cumulativeStepCountStart = null,
			cumulativeStepCountEnd = null,
			wallTimeUncertaintyMs = null,
			coverageKind = null,
			effectiveStepCount = null,
			logicalTrackingId = null,
			serviceRunId = null,
			manifestRevision = null,
			sourcePolicyRevision = null,
			captureConsentEpoch = null,
			scopeDeletionGeneration = 1L,
			effectChecksum = "0".repeat(64),
			appliedAtMs = 3_000L,
		)
		return unsigned.copy(
			effectChecksum = StepFactRevisionIntegrity.localDeleteEffectChecksum(unsigned),
		)
	}

	private fun ambientRetraction(
		fact: AmbientStepsFactRevisionEntity,
	): AmbientStepsFactRevisionEntity {
		val unsigned = fact.copy(
			semanticRevision = 2L,
			mutationId = AmbientStepsFactIntegrity.mutationId(
				fact.logicalFactId,
				2L,
				AmbientStepsFactRevisionEntity.OPERATION_RETRACT,
			),
			operation = AmbientStepsFactRevisionEntity.OPERATION_RETRACT,
			originKind = AmbientStepsFactRevisionEntity.ORIGIN_LOCAL_DELETE,
			provider = null,
			registrationGeneration = null,
			continuitySegmentGeneration = null,
			sourceInstanceId = null,
			authorizationRevision = null,
			authorizationFingerprint = null,
			windowStartTimeMs = null,
			windowEndTimeMs = null,
			observedAtMs = null,
			structuralEpochDay = null,
			storedZoneId = null,
			structuralDayStartTimeMs = null,
			structuralDayEndTimeMs = null,
			stepCount = null,
			sourcePolicyRevision = null,
			ambientConsentEpoch = null,
			scopeDeletionGeneration = 1L,
			effectChecksum = "0".repeat(64),
			appliedAtMs = 3_000L,
		)
		return unsigned.copy(
			effectChecksum = AmbientStepsFactIntegrity.effectChecksum(unsigned),
		)
	}

	private companion object {
		const val LOGICAL_TRACKING_ID = "tracking"
		const val SERVICE_RUN_ID = "run"
		const val EVENT_ID = "event"
		const val SOURCE_INSTANCE = "shared-instance"
		const val PROVIDER_DOMAIN = "shared-provider-domain"
	}
}
