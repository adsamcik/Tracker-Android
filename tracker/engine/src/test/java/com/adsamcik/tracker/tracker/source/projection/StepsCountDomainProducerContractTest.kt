package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLookupKey
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerRead
import com.adsamcik.tracker.shared.base.database.StepsCountDomainSchema
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.StepsCountDomainWriteResult
import com.adsamcik.tracker.shared.base.database.StepsCountDomainRetirementEvidence
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
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
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
		check(
			StepsCountDomainSchema.installIfAbsent(database.openHelper.writableDatabase) ==
				com.adsamcik.tracker.shared.base.database.StepsCountDomainSchemaState.ValidV2,
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `same provider token survives independent registrations and QoS fingerprints`() =
		runTest {
			val store = StepsCountDomainStore(database)
			val token = token('a')
			val wal = insertWal(
				token = token,
				sourceInstance = "session-owner-instance",
				registrationGeneration = 1L,
				physicalConfigurationFingerprint = "qos-low-latency",
			)
			store.recordSessionWal(wal, token) shouldBe StepsCountDomainWriteResult.INSERTED
			val reconfiguredWal = insertWal(
				token = token,
				eventId = "event-reconfigured",
				sourceInstance = "session-owner-instance",
				registrationGeneration = 2L,
				physicalConfigurationFingerprint = "qos-batched-high-latency",
			)
			store.recordSessionWal(reconfiguredWal, token) shouldBe
				StepsCountDomainWriteResult.INSERTED
			val fact = sessionFact(wal)
			database.stepFactRevisionDao().insert(fact) shouldNotBe -1L
			store.recordSessionFact(fact) shouldBe StepsCountDomainWriteResult.INSERTED
			val completeness = completeness(wal)
			database.sourceSessionDao().saveCompleteness(completeness)
			store.recordSessionCompleteness(
				completeness,
				completeRetirementEvidence(),
			) shouldBe
				StepsCountDomainWriteResult.INSERTED
			val ambient = ambientFact(
				sourceInstance = "session-owner-instance",
				registrationGeneration = 9L,
			)
			database.ambientStepsFactRevisionDao().insert(ambient) shouldNotBe -1L
			store.recordAmbientFact(ambient, token) shouldBe
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
			val reconfiguredWalOwner = StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
				StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
					reconfiguredWal.admissionOrdinal,
					reconfiguredWal.eventId,
				),
				1L,
			)
			val recovered = StepsCountDomainStore(database).readOwners(
				listOf(
					sessionFactOwner,
					completenessOwner,
					ambientOwner,
					reconfiguredWalOwner,
				),
			) as StepsCountDomainOwnerRead.Ready
			val domains = recovered.owners.values.mapNotNull { it.receipt?.domainIdentity }.toSet()

			domains.size shouldBe 1
			recovered.owners.values.mapNotNull { it.receipt?.registrationGeneration }.toSet() shouldBe
				setOf(1L, 2L, 9L)
		}

	@Test
	fun `correction retains old receipt and terminal session retraction blocks resurrection`() =
		runTest {
			val store = StepsCountDomainStore(database)
			val token = token('a')
			val wal = insertWal(token)
			store.recordSessionWal(wal, token)
			val fact = sessionFact(wal)
			store.recordSessionFact(fact) shouldBe StepsCountDomainWriteResult.INSERTED

			val retraction = sessionRetraction(fact)
			store.recordSessionFactRetraction(
				retraction,
				LOGICAL_TRACKING_ID,
				SERVICE_RUN_ID,
			) shouldBe StepsCountDomainWriteResult.INSERTED
			store.recordSessionFact(fact) shouldBe
				StepsCountDomainWriteResult.TERMINAL_OWNER

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
			store.recordAmbientFact(ambientOne, token) shouldBe
				StepsCountDomainWriteResult.INSERTED
			store.recordAmbientFact(ambientTwo, token) shouldBe
				StepsCountDomainWriteResult.INSERTED
			val changedDomainUnsigned = ambientTwo.copy(
				semanticRevision = 3L,
				mutationId = AmbientStepsFactIntegrity.mutationId(
					ambientTwo.logicalFactId,
					3L,
					AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
				),
				stepCount = 9L,
				observedAtMs = 4_000L,
				appliedAtMs = 4_000L,
				effectChecksum = "0".repeat(64),
			)
			val changedDomain = changedDomainUnsigned.copy(
				effectChecksum = AmbientStepsFactIntegrity.effectChecksum(changedDomainUnsigned),
			)
			store.recordAmbientFact(changedDomain, token('b')) shouldBe
				StepsCountDomainWriteResult.IDENTITY_CONFLICT

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
		val token = token('a')
		val fact = ambientFact()
		database.ambientStepsFactRevisionDao().insert(fact) shouldNotBe -1L
		store.recordAmbientFact(fact, token) shouldBe
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
		store.recordAmbientFact(fact, token) shouldBe
			StepsCountDomainWriteResult.TERMINAL_OWNER
		val directResurrectionRejected = try {
			database.ambientStepsFactRevisionDao().insert(
				ambientFact().copy(
					semanticRevision = 3L,
					mutationId = AmbientStepsFactIntegrity.mutationId(
						fact.logicalFactId,
						3L,
						AmbientStepsFactRevisionEntity.OPERATION_UPSERT,
					),
				),
			)
			false
		} catch (_: SQLiteException) {
			true
		}
		directResurrectionRejected shouldBe true
	}

	@Test
	fun `partial retirement is terminal unproven and cannot upgrade to complete`() = runTest {
		val store = StepsCountDomainStore(database)
		val token = token('a')
		val wal = insertWal(token)
		store.recordSessionWal(wal, token) shouldBe StepsCountDomainWriteResult.INSERTED
		val partial = completeness(wal).copy(
			appDrainComplete = false,
			providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
			stopStatus = "TIMED_OUT",
			unresolvedSequenceStart = 2L,
			unresolvedSequenceEnd = 3L,
		)
		database.sourceSessionDao().saveCompleteness(partial)
		store.recordSessionCompleteness(
			partial,
			StepsCountDomainRetirementEvidence("FAILED", "REMOVED"),
		) shouldBe StepsCountDomainWriteResult.INSERTED

		val completed = completeness(wal).copy(updatedAtMs = partial.updatedAtMs + 1L)
		database.sourceSessionDao().saveCompleteness(completed)
		store.recordSessionCompleteness(
			completed,
			completeRetirementEvidence(),
		) shouldBe StepsCountDomainWriteResult.TERMINAL_OWNER
	}

	@Test
	fun `transient retirement stays pending then reconstructed store binds current complete retry`() =
		runTest {
			val token = token('a')
			val wal = insertWal(token)
			val initialStore = StepsCountDomainStore(database)
			initialStore.recordSessionWal(wal, token) shouldBe StepsCountDomainWriteResult.INSERTED
			val pending = completeness(wal).copy(stopStatus = "PROVIDER_FAILED")
			database.sourceSessionDao().saveCompleteness(pending)

			initialStore.recordSessionCompleteness(
				pending,
				StepsCountDomainRetirementEvidence("NOT_REQUESTED", "FAILED"),
			) shouldBe StepsCountDomainWriteResult.AUTHORITY_PENDING
			database.openHelper.writableDatabase.query(
				"SELECT COUNT(*) FROM steps_count_domain_owner_revision " +
					"WHERE owner_kind = 'SESSION_COMPLETENESS'",
			).use { cursor ->
				cursor.moveToFirst()
				cursor.getLong(0) shouldBe 0L
			}
			database.openHelper.writableDatabase.query(
				"SELECT COUNT(*) FROM steps_count_domain_completeness_marker",
			).use { cursor ->
				cursor.moveToFirst()
				cursor.getLong(0) shouldBe 0L
			}

			val complete = completeness(wal).copy(updatedAtMs = pending.updatedAtMs + 1L)
			database.sourceSessionDao().saveCompleteness(complete)
			val reconstructedStore = StepsCountDomainStore(database)
			reconstructedStore.recordSessionCompleteness(
				complete,
				completeRetirementEvidence(),
			) shouldBe StepsCountDomainWriteResult.INSERTED
			reconstructedStore.recordSessionCompleteness(
				complete,
				completeRetirementEvidence(),
			) shouldBe StepsCountDomainWriteResult.EXACT_REPLAY
			database.openHelper.writableDatabase.query(
				"SELECT operation FROM steps_count_domain_owner_revision " +
					"WHERE owner_kind = 'SESSION_COMPLETENESS' ORDER BY owner_revision",
			).use { cursor ->
				cursor.moveToFirst()
				cursor.getString(0) shouldBe StepsCountDomainOwnerRevisionEntity.OPERATION_BIND
				cursor.moveToNext() shouldBe false
			}
			database.openHelper.writableDatabase.query(
				"SELECT terminal_state, provider_flush_outcome, registration_removal_outcome " +
					"FROM steps_count_domain_completeness_marker",
			).use { cursor ->
				cursor.moveToFirst()
				cursor.getString(0) shouldBe "COMPLETE"
				cursor.getString(1) shouldBe "COMPLETE"
				cursor.getString(2) shouldBe "REMOVED"
				cursor.moveToNext() shouldBe false
			}
		}

	@Test
	fun `complete retirement remains retryable until canonical WAL authority exists`() = runTest {
		val store = StepsCountDomainStore(database)
		val token = token('a')
		val wal = insertWal(token)
		val completeness = completeness(wal)
		database.sourceSessionDao().saveCompleteness(completeness)

		store.recordSessionCompleteness(
			completeness,
			completeRetirementEvidence(),
		) shouldBe StepsCountDomainWriteResult.AUTHORITY_PENDING
		database.openHelper.writableDatabase.query(
			"SELECT COUNT(*) FROM steps_count_domain_owner_revision " +
				"WHERE owner_kind = 'SESSION_COMPLETENESS'",
		).use { cursor ->
			cursor.moveToFirst()
			cursor.getLong(0) shouldBe 0L
		}

		store.recordSessionWal(wal, token) shouldBe StepsCountDomainWriteResult.INSERTED
		store.recordSessionCompleteness(
			completeness,
			completeRetirementEvidence(),
		) shouldBe StepsCountDomainWriteResult.INSERTED
	}

	private suspend fun insertWal(
		token: StepsCounterDomainToken,
		eventId: String = EVENT_ID,
		sourceInstance: String = SOURCE_INSTANCE,
		registrationGeneration: Long = 1L,
		physicalConfigurationFingerprint: String = "configuration-not-domain",
		payloadVersion: Int = StepsCounterDomainToken.COUNTER_EPOCH_GENERATION_PAYLOAD_VERSION,
		sourceSequence: Long = 1L,
		boundaryKind: StepBoundaryKind = StepBoundaryKind.COVERED,
		firstCumulativeCount: Long = 10L,
		lastCumulativeCount: Long = 15L,
	): SourceEventWalEntity {
		val deltaCount = if (boundaryKind == StepBoundaryKind.COVERED) {
			lastCumulativeCount - firstCumulativeCount
		} else {
			0L
		}
		val payload = StepCounterWindowPayload(
			bootClockDomainId = "boot",
			firstCumulativeCount = firstCumulativeCount,
			lastCumulativeCount = lastCumulativeCount,
			deltaCount = deltaCount,
			windowStartElapsedRealtimeNanos = 1_000_000_000L,
			windowEndElapsedRealtimeNanos = 2_000_000_000L,
			firstProviderSequence = sourceSequence,
			lastProviderSequence = sourceSequence,
			boundaryKind = boundaryKind,
			counterDomainToken = token,
			counterEpochGeneration =
				if (payloadVersion >=
					StepsCounterDomainToken.COUNTER_EPOCH_GENERATION_PAYLOAD_VERSION
				) {
					1L
				} else {
					null
				},
		)
		val encoded = DefaultSourcePayloadCodec().encode(
			payload,
			payloadVersion,
		)
		val unsigned = SourceEventWalEntity(
			eventId = eventId,
			providerDedupKey = "steps-dedup-$eventId",
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			sourceInstanceId = sourceInstance,
			registrationGeneration = registrationGeneration,
			physicalConfigurationFingerprint = physicalConfigurationFingerprint,
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = "a".repeat(64),
			sourceSequence = sourceSequence,
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
			payloadVersion = payloadVersion,
			payload = encoded.bytes,
			payloadChecksum = encoded.checksum,
			createdAtMs = 2_000L,
		)
		val signed = unsigned.copy(
			integrityIdentity = unsigned.calculatedIntegrityIdentity(),
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
		sourceInstanceId = wal.sourceInstanceId,
		registrationGeneration = wal.registrationGeneration,
		lastAdmissionOrdinal = wal.admissionOrdinal,
		lastSourceSequence = wal.sourceSequence,
		appDrainComplete = true,
		providerCoverage = "CALLBACKS_ENTERED_BEFORE_BARRIER",
		stopStatus = "COMPLETE",
		unresolvedSequenceStart = null,
		unresolvedSequenceEnd = null,
		updatedAtMs = 3_000L,
	)

	private fun ambientFact(
		sourceInstance: String = SOURCE_INSTANCE,
		registrationGeneration: Long = 1L,
	): AmbientStepsFactRevisionEntity {
		val logicalFactId = AmbientStepsFactIntegrity.logicalFactId(
			AmbientStepsFactRevisionEntity.PROVIDER_LOCAL_RECORDING_STEPS,
			registrationGeneration,
			1L,
			sourceInstance,
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
			registrationGeneration = registrationGeneration,
			continuitySegmentGeneration = 1L,
			sourceInstanceId = sourceInstance,
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

	private fun completeRetirementEvidence() = StepsCountDomainRetirementEvidence(
		providerFlushOutcome = "COMPLETE",
		registrationRemovalOutcome = "REMOVED",
	)

	private fun token(digit: Char) =
		StepsCounterDomainToken.opaque("sha256:${digit.toString().repeat(64)}")

	private companion object {
		const val LOGICAL_TRACKING_ID = "tracking"
		const val SERVICE_RUN_ID = "run"
		const val EVENT_ID = "event"
		const val SOURCE_INSTANCE = "shared-instance"
	}
}
