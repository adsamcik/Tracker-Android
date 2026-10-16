package com.adsamcik.tracker.tracker.source.lifecycle

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.persistPressureFactRetentionAuthority
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.stats.api.repository.SourceEvidenceIdentity
import com.adsamcik.tracker.stats.api.repository.SourceProofKey
import com.adsamcik.tracker.stats.api.repository.SourceProofReason
import com.adsamcik.tracker.stats.api.repository.SourceProofResult
import com.adsamcik.tracker.stats.api.repository.SourceProofScope
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedWalAdmissionAdapter
import com.adsamcik.tracker.tracker.source.cell.CellWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.location.LocationWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.runtime.pressureProviderDeliveryIdentity
import com.adsamcik.tracker.tracker.source.wifi.WifiWalQualificationAdapter
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceRecordingHistoricalAuthorityRoomTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `positive steps delta without historical plan and provider authority is unavailable`() =
		runTest {
			val payload = StepCounterWindowPayload(
				bootClockDomainId = BOOT_ID,
				firstCumulativeCount = 100L,
				lastCumulativeCount = 105L,
				deltaCount = 5L,
				windowStartElapsedRealtimeNanos = 1_000L,
				windowEndElapsedRealtimeNanos = 2_000L,
				firstProviderSequence = 1L,
				lastProviderSequence = 2L,
				boundaryKind = StepBoundaryKind.COVERED,
			)
			insertWal(SourceKind.STEPS, payload, payloadVersion = 3)

			val result = adapter(TrackingSource.STEPS).recording(key(TrackingSource.STEPS))

			(result as SourceProofResult.Unavailable).reason shouldBe
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING
		}

	@Test
	fun `unrelated retained Pressure lineage does not invalidate surviving WAL proof`() = runTest {
		val payload = PressureWindowPayload(
			sampleCount = 2,
			meanHectopascals = 1_000.5,
			sumSquaredDeviations = 0.5,
			minimumHectopascals = 1_000f,
			maximumHectopascals = 1_001f,
			windowStartElapsedRealtimeNanos = 1_000_000L,
			windowEndElapsedRealtimeNanos = 2_000_000L,
			firstProviderSequence = 1L,
			lastProviderSequence = 2L,
			firstHectopascals = 1_000f,
			lastHectopascals = 1_001f,
			slopeHectopascalsPerSecond = 1_000.0,
			rSquared = 1.0,
			sensorAccuracy = PressureSensorAccuracy.HIGH,
			effectiveSamplePeriodMicros = 1_000,
			effectiveMaximumReportLatencyMicros = 0,
			targetWindowDurationNanos = 2_000_000L,
			expectedSampleCount = 2,
			maximumInterSampleGapNanos = 1_000_000L,
			closureKind = PressureWindowClosureKind.TARGET_ELAPSED,
		)
		insertWal(SourceKind.PRESSURE, payload, payloadVersion = 4)
		database.sourceEvidenceStateDao().updateLifecycle(
			DATA_EPOCH,
			RETENTION_FLOOR_MS,
			2L,
		)
		database.persistPressureFactRetentionAuthority(
			revisions = listOf(retainedPressureFact()),
			retainedFromMs = RETENTION_FLOOR_MS,
			retainedAtMs = RETENTION_FLOOR_MS,
		)

		val result = adapter(TrackingSource.PRESSURE).recording(key(TrackingSource.PRESSURE))

		(result as SourceProofResult.Unavailable).reason shouldBe
			SourceProofReason.IMMUTABLE_AUTHORITY_MISSING
	}

	@Test
	fun `qualified pressure window without historical plan and provider authority is unavailable`() =
		runTest {
			val payload = PressureWindowPayload(
				sampleCount = 2,
				meanHectopascals = 1_000.5,
				sumSquaredDeviations = 0.5,
				minimumHectopascals = 1_000f,
				maximumHectopascals = 1_001f,
				windowStartElapsedRealtimeNanos = 1_000_000L,
				windowEndElapsedRealtimeNanos = 2_000_000L,
				firstProviderSequence = 1L,
				lastProviderSequence = 2L,
				firstHectopascals = 1_000f,
				lastHectopascals = 1_001f,
				slopeHectopascalsPerSecond = 1_000.0,
				rSquared = 1.0,
				sensorAccuracy = PressureSensorAccuracy.HIGH,
				effectiveSamplePeriodMicros = 1_000,
				effectiveMaximumReportLatencyMicros = 0,
				targetWindowDurationNanos = 2_000_000L,
				expectedSampleCount = 2,
				maximumInterSampleGapNanos = 1_000_000L,
				closureKind = PressureWindowClosureKind.TARGET_ELAPSED,
			)
			insertWal(SourceKind.PRESSURE, payload, payloadVersion = 4)

			val result = adapter(TrackingSource.PRESSURE).recording(key(TrackingSource.PRESSURE))

			(result as SourceProofResult.Unavailable).reason shouldBe
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING
		}

	@Test
	fun `exact projection failure write invalidates lifecycle proof flow`() = runTest {
		val emissions = async {
			database.lifecycleInvalidations().take(2).toList()
		}
		runCurrent()

		database.sourceProjectionStateDao().saveFailure(
			SourceProjectionFailureEntity(
				projectionId = "writer",
				projectionVersion = 1,
				admissionOrdinal = 1L,
				attemptCount = 1,
				failureCode = "terminal",
				terminal = true,
				lastAttemptAtMs = 1L,
			),
		)
		database.invalidationTracker.refreshAsync()
		runCurrent()

		withTimeout(5_000L) { emissions.await() }.size shouldBe 2
	}

	@Test
	fun `authority-only policy pointer change invalidates lifecycle proof flow`() = runTest {
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = 1L,
				legacySettingsFingerprint = null,
				updatedAtMs = 1L,
			),
		)
		val emissions = async {
			database.lifecycleInvalidations().take(2).toList()
		}
		runCurrent()

		database.sourcePolicyDao().compareAndSetAuthority(
			expectedBootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			expectedRevision = 1L,
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			newRevision = 2L,
			legacySettingsFingerprint = null,
			updatedAtMs = 2L,
		) shouldBe 1
		database.invalidationTracker.refreshAsync()
		runCurrent()

		withTimeout(5_000L) { emissions.await() }.size shouldBe 2
	}

	private fun adapter(source: TrackingSource): SourceLifecycleProofAdapter =
		DefaultSourceLifecycleProofAdapterFactory(
			database = database,
			payloadCodec = DefaultSourcePayloadCodec(),
			planCodec = SourcePlanCodec(),
			locationQualifier = mockk<LocationWalQualificationAdapter>(),
			activityQualifier = mockk<ActivityCapturedWalAdmissionAdapter>(),
			wifiQualifier = mockk<WifiWalQualificationAdapter>(),
			cellQualifier = mockk<CellWalQualificationAdapter>(),
			bootClockDomainProvider = BootClockDomainProvider { BOOT_ID },
		).create(source)

	private suspend fun insertWal(
		source: SourceKind,
		payload: com.adsamcik.tracker.tracker.source.model.SourcePayload,
		payloadVersion: Int,
	) {
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(DATA_EPOCH, null, 1L)
		val encoded = DefaultSourcePayloadCodec().encode(payload, payloadVersion)
		val raw = SourceEventWalEntity(
			admissionOrdinal = 1L,
			eventId = EVENT_ID,
			providerDedupKey = "dedup".takeIf { source == SourceKind.STEPS },
			deliveryIdentity = (payload as? PressureWindowPayload)?.let {
				pressureProviderDeliveryIdentity(BOOT_ID, it).value
			},
			deliveryUnitIndex = 0.takeIf { source == SourceKind.PRESSURE },
			deliveryUnitCount = 1.takeIf { source == SourceKind.PRESSURE },
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = RUN_ID,
			sourceKind = source.stableCode,
			sourceInstanceId = "provider",
			registrationGeneration = 1L,
			physicalConfigurationFingerprint = "fingerprint",
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = "a".repeat(64),
			sourceSequence = 2L,
			configRevision = 1L,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION.ordinal,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = when (payload) {
				is StepCounterWindowPayload -> payload.windowEndElapsedRealtimeNanos
				is PressureWindowPayload -> payload.windowEndElapsedRealtimeNanos
				else -> error("Unexpected payload")
			},
			observedIntervalStartNanos = when (payload) {
				is StepCounterWindowPayload -> payload.windowStartElapsedRealtimeNanos
				is PressureWindowPayload -> payload.windowStartElapsedRealtimeNanos
				else -> error("Unexpected payload")
			},
			receivedElapsedNanos = 3_000_000L,
			receivedWallTimeMs = 10_001L,
			wallTimeMs = 10_000L,
			wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = DATA_EPOCH,
			sourcePolicyRevision = POLICY_REVISION,
			captureConsentEpoch = CONSENT_EPOCH,
			sessionManifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			acquiredAtMs = 10_000L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = payloadVersion,
			payload = encoded.bytes,
			payloadChecksum = encoded.checksum,
			integrityIdentity = "pending",
			createdAtMs = 10_000L,
		)
		val stored = raw.copy(integrityIdentity = raw.calculatedIntegrityIdentity())
		database.sourceEventWalDao().insertIgnoringDuplicate(stored)
	}

	private fun key(source: TrackingSource) = SourceProofKey(
		source = source,
		purpose = TrackingPurpose.SESSION_CAPTURE,
		evidenceIdentity = SourceEvidenceIdentity(EVENT_ID),
		sourcePolicyRevision = POLICY_REVISION,
		captureConsentEpoch = CONSENT_EPOCH,
		collectedDataEpoch = DATA_EPOCH,
		scope = SourceProofScope.Run(LOGICAL_ID, RUN_ID),
	)

	private fun retainedPressureFact() = PressureFactRevisionEntity(
		logicalFactId =
			"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:retained-unrelated",
		semanticRevision = 1L,
		mutationId =
			"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:retained-unrelated:1",
		sourceEventId = "retained-unrelated",
		sourceAdmissionOrdinal = 2L,
		writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
		payloadVersion = PressureFactRevisionEntity.QUALIFIED_PRESSURE_PAYLOAD_VERSION,
		intervalStartTimeMs = 8_000L,
		intervalEndTimeMs = 8_001L,
		windowStartElapsedRealtimeNanos = 8_000_000L,
		windowEndElapsedRealtimeNanos = 9_000_000L,
		clockDomainId = BOOT_ID,
		wallTimeUncertaintyMs = 1L,
		sampleCount = 2,
		meanHectopascals = 1_000.5,
		sumSquaredDeviations = 0.5,
		minimumHectopascals = 1_000f,
		maximumHectopascals = 1_001f,
		firstProviderSequence = 3L,
		lastProviderSequence = 4L,
		firstHectopascals = 1_000f,
		lastHectopascals = 1_001f,
		slopeHectopascalsPerSecond = 1_000.0,
		rSquared = 1.0,
		sensorAccuracy = PressureFactRevisionEntity.SENSOR_ACCURACY_HIGH,
		effectiveSamplePeriodMicros = 1_000,
		effectiveMaximumReportLatencyMicros = 0,
		targetWindowDurationNanos = 2_000_000L,
		expectedSampleCount = 2,
		maximumInterSampleGapNanos = 1_000_000L,
		closureKind = PressureFactRevisionEntity.CLOSURE_TARGET_ELAPSED,
		qualification = PressureFactRevisionEntity.QUALIFICATION_COMPLETE,
		sourceQualityFlags = 0L,
		sourceQualityConfidence = null,
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = RUN_ID,
		purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = 1L,
		sourcePolicyRevision = POLICY_REVISION,
		captureConsentEpoch = CONSENT_EPOCH,
		collectedDataEpoch = DATA_EPOCH,
		effectChecksum = "retained-effect",
		appliedAtMs = 8_001L,
	)

	private companion object {
		const val EVENT_ID = "historical-authority-event"
		const val LOGICAL_ID = "historical-authority-logical"
		const val RUN_ID = "historical-authority-run"
		const val BOOT_ID = "historical-authority-boot"
		const val POLICY_REVISION = 1L
		const val CONSENT_EPOCH = 1L
		const val DATA_EPOCH = 7L
		const val RETENTION_FLOOR_MS = 9_000L
	}
}
