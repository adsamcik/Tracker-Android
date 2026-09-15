package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.assertions.throwables.shouldThrow
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
class PressureRetentionTruncationTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = COLLECTED_DATA_EPOCH),
		)
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = COLLECTED_DATA_EPOCH,
			retainedFromMs = RETENTION_FLOOR_MS,
			updatedAtMs = MARKED_AT_MS,
		) shouldBe 1
		database.sourcePolicyDao().insertPolicies(listOf(pressurePolicy()))
		database.sourcePolicyDao().insertConsentEpochs(listOf(pressureConsent()))
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `total revision overflow rolls back an earlier marker and lineage deletion`() = runTest {
		insertRetainedPressureRun("a", 1L)
		insertRetainedPressureRun("b", 2L)

		shouldThrow<IllegalStateException> {
			database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
				beforeMs = RETENTION_FLOOR_MS,
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
				markedAtMs = MARKED_AT_MS,
				limits = limits(maximumFactRevisions = 1L),
				checkpoint = {},
			)
		}

		database.pressureFactRevisionDao().count() shouldBe 2L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `cancellation after marker insertion rolls back marker and exact lineage deletion`() = runTest {
		insertRetainedPressureRun("cancel", 1L)

		shouldThrow<CancellationException> {
			database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
				beforeMs = RETENTION_FLOOR_MS,
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
				markedAtMs = MARKED_AT_MS,
				limits = limits(),
				checkpoint = { checkpoint ->
					if (checkpoint == PressureRetentionCheckpoint.RUN_MARKED) {
						throw CancellationException("cancel retention after marker")
					}
				},
			)
		}

		database.pressureFactRevisionDao().count() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	private suspend fun insertRetainedPressureRun(key: String, admissionOrdinal: Long) {
		val logicalId = "logical-pressure-$key"
		val runId = "run-pressure-$key"
		val segmentId = database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = RUN_START_MS,
				endTimeMs = RUN_END_MS,
				distanceM = 0f,
				steps = null,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 0,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = null,
				createdAt = RUN_END_MS,
				logicalTrackingId = logicalId,
				serviceRunId = runId,
			),
		)
		database.sourceSessionDao().insertSession(logicalSession(logicalId))
		database.sourceSessionDao().insertServiceRun(serviceRun(logicalId, runId, segmentId))
		val binding = pressureManifestSource(logicalId)
		val unsignedManifest = pressureManifest(logicalId, runId)
		val manifest = unsignedManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(binding)),
		)
		database.sourceSessionDao().insertManifest(manifest)
		database.sourceSessionDao().insertManifestSources(listOf(binding))
		val unsignedFact = pressureFact(logicalId, runId, key, admissionOrdinal)
		val fact = unsignedFact.copy(
			effectChecksum = PressureFactRevisionIntegrity.effectChecksum(unsignedFact, binding),
		)
		database.pressureFactRevisionDao().insert(fact).let { inserted ->
			check(inserted > 0L)
		}
	}

	private fun logicalSession(logicalId: String) = LogicalTrackingSessionEntity(
		logicalTrackingId = logicalId,
		state = "FINALIZED",
		lifecycleRevision = 2L,
		desiredPlanRevision = 1L,
		rolloutRevision = 2L,
		startOrigin = "MANUAL_FOREGROUND_START",
		clockDomainId = BOOT_ID,
		startedAtMs = RUN_START_MS,
		startedElapsedNanos = 0L,
		cutoffAtMs = RUN_END_MS,
		cutoffElapsedNanos = RUN_END_MS,
		completedAtMs = RUN_END_MS,
		finalAdmissionOrdinal = 2L,
		failureCode = null,
		sessionMode = "MANUAL",
		currentManifestRevision = MANIFEST_REVISION,
		currentIntentRevision = 1L,
		currentServiceRunId = null,
		lifecycleLeaseGeneration = 1L,
		lifecycleBootId = BOOT_ID,
		automationEpoch = null,
	)

	private fun serviceRun(logicalId: String, runId: String, segmentId: Long) =
		SourceServiceRunEntity(
			serviceRunId = runId,
			logicalTrackingId = logicalId,
			state = "FINALIZED",
			desiredPlanRevision = 1L,
			rolloutRevision = 2L,
			foregroundCapabilityFlags = 0L,
			startedAtMs = RUN_START_MS,
			startedElapsedNanos = 0L,
			completedAtMs = RUN_END_MS,
			completionReason = "USER_STOP",
			bootId = BOOT_ID,
			leaseGeneration = 1L,
			startOrigin = "MANUAL_FOREGROUND_START",
			desiredForegroundCapabilityFlags = 0L,
			appliedForegroundCapabilityFlags = 0L,
			runtimeAcknowledgement = "STOP_ACCEPTED",
			runtimeFailureCode = null,
			runRevision = 2L,
			startDeliveryToken = "delivery-$runId",
			startCommandGeneration = 1L,
			preparedManifestRevision = MANIFEST_REVISION,
			preparedIntentRevision = 1L,
			androidDeliveryState = "FOREGROUND_ACCEPTED",
			androidDeliveryUpdatedAtMs = RUN_START_MS,
			startIsUserInitiated = true,
			startIsAmbient = false,
			sessionSegmentId = segmentId,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = RUN_END_MS,
		)

	private fun pressureManifest(logicalId: String, runId: String) = SessionManifestVersionEntity(
		logicalTrackingId = logicalId,
		manifestRevision = MANIFEST_REVISION,
		serviceRunId = runId,
		sessionMode = "MANUAL",
		sourcePolicyRevision = POLICY_REVISION,
		acquisitionPlanRevision = 1L,
		rolloutRevision = 2L,
		startOrigin = "MANUAL_FOREGROUND_START",
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = 0L,
		effectiveWallTimeMs = RUN_START_MS,
		zoneId = "UTC",
		automationEpoch = null,
		changeReason = "TEST",
		manifestChecksum = "",
	)

	private fun pressureManifestSource(logicalId: String) = SessionManifestSourceEntity(
		logicalTrackingId = logicalId,
		manifestRevision = MANIFEST_REVISION,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		consentEpoch = CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = CAPTURE_QOS,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		writerOwner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
	)

	@Suppress("LongMethod")
	private fun pressureFact(
		logicalId: String,
		runId: String,
		key: String,
		admissionOrdinal: Long,
	): PressureFactRevisionEntity {
		val eventId = "pressure-$key"
		val logicalFactId = "${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:$eventId"
		return PressureFactRevisionEntity(
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			mutationId = "$logicalFactId:1",
			sourceEventId = eventId,
			sourceAdmissionOrdinal = admissionOrdinal,
			writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
			payloadVersion = PressureFactRevisionEntity.QUALIFIED_PRESSURE_PAYLOAD_VERSION,
			intervalStartTimeMs = RUN_START_MS,
			intervalEndTimeMs = RUN_START_MS + WINDOW_MS,
			windowStartElapsedRealtimeNanos = admissionOrdinal * SECOND_NANOS,
			windowEndElapsedRealtimeNanos = admissionOrdinal * SECOND_NANOS + WINDOW_NANOS,
			clockDomainId = BOOT_ID,
			wallTimeUncertaintyMs = 1L,
			sampleCount = 4,
			meanHectopascals = 1_001.5,
			sumSquaredDeviations = 5.0,
			minimumHectopascals = 1_000f,
			maximumHectopascals = 1_003f,
			firstProviderSequence = admissionOrdinal * 10L + 1L,
			lastProviderSequence = admissionOrdinal * 10L + 4L,
			firstHectopascals = 1_000f,
			lastHectopascals = 1_003f,
			slopeHectopascalsPerSecond = 20.0,
			rSquared = 1.0,
			sensorAccuracy = PressureFactRevisionEntity.SENSOR_ACCURACY_HIGH,
			effectiveSamplePeriodMicros = 50_000,
			effectiveMaximumReportLatencyMicros = 200_000,
			targetWindowDurationNanos = 200_000_000L,
			expectedSampleCount = 4,
			maximumInterSampleGapNanos = 50_000_000L,
			closureKind = PressureFactRevisionEntity.CLOSURE_TARGET_ELAPSED,
			qualification = PressureFactRevisionEntity.QUALIFICATION_COMPLETE,
			sourceQualityFlags = 0L,
			sourceQualityConfidence = null,
			logicalTrackingId = logicalId,
			serviceRunId = runId,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			manifestRevision = MANIFEST_REVISION,
			sourcePolicyRevision = POLICY_REVISION,
			captureConsentEpoch = CONSENT_EPOCH,
			collectedDataEpoch = COLLECTED_DATA_EPOCH,
			effectChecksum = "pending",
			appliedAtMs = RUN_START_MS + WINDOW_MS,
		)
	}

	private fun pressurePolicy() = SourcePolicyEntity(
		policyRevision = POLICY_REVISION,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		enabled = true,
		qosCode = CAPTURE_QOS,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = true,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = false,
		captureConsentEpoch = CONSENT_EPOCH,
		controlConsentEpoch = null,
		ambientConsentEpoch = null,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = 0L,
		effectiveWallTimeMs = 0L,
		changeReason = "TEST",
	)

	private fun pressureConsent() = SourceConsentEpochEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		epoch = CONSENT_EPOCH,
		eligible = true,
		persistenceEligible = true,
		policyRevision = POLICY_REVISION,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = 0L,
		effectiveWallTimeMs = 0L,
		changeReason = "TEST",
	)

	private fun limits(maximumFactRevisions: Long = 16L) = PressureRetentionTraversalLimits(
		maximumRuns = 8L,
		maximumTraversalRows = 128L,
		maximumFactRevisions = maximumFactRevisions,
		maximumFactLineages = 16L,
		maximumFactRevisionsPerRun = maximumFactRevisions.coerceAtMost(8L).toInt(),
		maximumFactLineagesPerRun = 8,
	)

	private companion object {
		const val COLLECTED_DATA_EPOCH = 1L
		const val POLICY_REVISION = 1L
		const val CONSENT_EPOCH = 1L
		const val MANIFEST_REVISION = 1L
		const val CAPTURE_QOS = 2
		const val BOOT_ID = "boot-1"
		const val RUN_START_MS = 1_000L
		const val RUN_END_MS = 5_000L
		const val RETENTION_FLOOR_MS = 1_500L
		const val MARKED_AT_MS = 6_000L
		const val WINDOW_MS = 150L
		const val WINDOW_NANOS = 150_000_000L
		const val SECOND_NANOS = 1_000_000_000L
	}
}
