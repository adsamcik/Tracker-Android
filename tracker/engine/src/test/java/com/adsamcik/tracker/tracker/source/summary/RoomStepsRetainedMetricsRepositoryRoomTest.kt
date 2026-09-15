package com.adsamcik.tracker.tracker.source.summary

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCaptureCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCompletenessV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsFactCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsManifestV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsProviderCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsRunV1
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.StepsRetainedMetrics
import com.adsamcik.tracker.stats.api.repository.StepsRetainedMetricsDecision
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass")
class RoomStepsRetainedMetricsRepositoryRoomTest {
	private lateinit var database: AppDatabase
	private lateinit var repository: RoomStepsRetainedMetricsRepository

	@Before
	fun setUp() = runBlocking {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
		repository = RoomStepsRetainedMetricsRepository(database, Dispatchers.IO)
	}

	@After
	fun tearDown() {
		if (::database.isInitialized && database.isOpen) database.close()
	}

	@Test
	fun `source-only qualified days produce lifetime and best-day metrics without daily summaries`() =
		runBlocking<Unit> {
			seedImported(
				portableRun(runIdentity = '2', factIdentity = '3', day = DAY, steps = 7L),
				entryIdentity = '1',
				segmentId = 41L,
			)
			seedImported(
				portableRun(runIdentity = '5', factIdentity = '6', day = DAY + 1L, steps = 11L),
				entryIdentity = '4',
				segmentId = 42L,
			)

			database.dailySummaryDao().getBetween(DAY, DAY + 1L) shouldBe emptyList()
			val decision = snapshot()

			decision.sourceEvidenceRevision shouldBe 0L
			decision.result shouldBe StepsRetainedMetrics.Ready(
				totalSteps = 18L,
				bestDailySteps = 11L,
				qualifiedDayCount = 2L,
			)
			SHA_256_HEX.matches(decision.sourceResultDigest) shouldBe true
			database.dailySummaryDao().getBetween(DAY, DAY + 1L) shouldBe emptyList()
		}

	@Test
	fun `source-qualified covered zero remains a real retained day`() = runBlocking<Unit> {
		seedImported(
			portableRun(runIdentity = '2', factIdentity = '3', day = DAY, steps = 0L),
			entryIdentity = '1',
			segmentId = 41L,
		)

		snapshot().result shouldBe StepsRetainedMetrics.Ready(
			totalSteps = 0L,
			bestDailySteps = 0L,
			qualifiedDayCount = 1L,
		)
		database.dailySummaryDao().getBetween(DAY, DAY) shouldBe emptyList()
	}

	@Test
	fun `active source-only native run is materializing rather than zero`() = runBlocking<Unit> {
		seedActiveNativeRun()

		snapshot().result shouldBe StepsRetainedMetrics.Materializing
		database.dailySummaryDao().getBetween(DAY, DAY) shouldBe emptyList()
	}

	@Test
	fun `active run crossing retained floor remains materializing without a fabricated zero`() =
		runBlocking<Unit> {
			val retainedFromMs = startOfDay(DAY)
			seedActiveNativeRun(startMs = retainedFromMs - HOUR_MS)
			database.sourceEvidenceStateDao().updateLifecycle(
				epoch = EPOCH,
				retainedFromMs = retainedFromMs,
				updatedAtMs = retainedFromMs,
			) shouldBe 1

			snapshot().result shouldBe StepsRetainedMetrics.Materializing
			database.dailySummaryDao().getBetween(DAY, DAY) shouldBe emptyList()
		}

	@Test
	fun `legacy segment steps with no exact native run binding are typed unavailable`() =
		runBlocking<Unit> {
			seedImported(
				portableRun(runIdentity = '2', factIdentity = '3', day = DAY, steps = 9L),
				entryIdentity = '1',
				segmentId = 41L,
			)
			database.withTransaction {
				database.openHelper.writableDatabase.execSQL(
					"UPDATE session_segment SET steps = 9 WHERE id = 41",
				)
			}

			snapshot().result shouldBe unavailable(
				StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
			)
		}

	@Test
	fun `orphan Steps manifest source cannot leave an otherwise valid retained total Ready`() =
		runBlocking<Unit> {
			seedImported(
				portableRun(runIdentity = '2', factIdentity = '3', day = DAY, steps = 9L),
				entryIdentity = '1',
				segmentId = 41L,
			)
			database.sourceSessionDao().insertManifestSources(
				listOf(
					stepsManifestSource().copy(
						logicalTrackingId = "orphan-retained-logical",
						manifestRevision = 99L,
					),
				),
			)

			snapshot().result shouldBe unavailable(
				StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
			)
		}

	@Test
	fun `retained boundary needs its exact truncation fence and never fabricates a suffix total`() =
		runBlocking<Unit> {
			val firstRetainedMs = startOfDay(DAY)
			val runStartMs = firstRetainedMs - 1_000L
			val runEndMs = firstRetainedMs + HOUR_MS
			seedImported(
				portableRun(
					runIdentity = '2',
					factIdentity = '3',
					day = DAY,
					steps = 0L,
					startMs = runStartMs,
					endMs = runEndMs,
				),
				entryIdentity = '1',
				segmentId = 41L,
			)
			database.sourceEvidenceStateDao().updateLifecycle(
				epoch = EPOCH,
				retainedFromMs = firstRetainedMs,
				updatedAtMs = runEndMs,
			) shouldBe 1

			snapshot().result shouldBe unavailable(
				StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
			)
			markImportedRetentionFence(deletedAtMs = runEndMs)

			snapshot().result shouldBe unavailable(StepsNumericUnverifiableReason.PARTIAL_CAPTURE)
		}

	private suspend fun snapshot(): StepsRetainedMetricsDecision.Snapshot =
		repository.readDecision() as StepsRetainedMetricsDecision.Snapshot

	private suspend fun seedImported(
		run: PortableStepsRunV1,
		entryIdentity: Char,
		segmentId: Long,
	) = database.seedImportedNumericTestRun(
		run = run,
		entryId = opaque(entryIdentity),
		segmentId = segmentId,
		epoch = EPOCH,
	)

	@Suppress("LongMethod")
	private suspend fun seedActiveNativeRun(
		startMs: Long = startOfDay(DAY) + HOUR_MS,
	) = database.withTransaction {
		database.sourcePolicyDao().insertPolicies(listOf(stepsCapturePolicy(startMs)))
		database.sourcePolicyDao().insertConsentEpochs(listOf(stepsCaptureConsent(startMs)))
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				bindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
				projectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
				projectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
				captureModeMask = 1L,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = 1L,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = startMs,
				updatedAtMs = startMs,
			),
		)
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_ID,
				state = "ACTIVE",
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = MANUAL_START_ORIGIN,
				clockDomainId = BOOT_ID,
				startedAtMs = startMs,
				startedElapsedNanos = START_ELAPSED_NANOS,
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				completedAtMs = null,
				finalAdmissionOrdinal = null,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = 1L,
				currentIntentRevision = 1L,
				currentServiceRunId = RUN_ID,
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = BOOT_ID,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = RUN_ID,
				logicalTrackingId = LOGICAL_ID,
				state = "ACTIVE",
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = startMs,
				startedElapsedNanos = START_ELAPSED_NANOS,
				completedAtMs = null,
				completionReason = null,
				bootId = BOOT_ID,
				leaseGeneration = 1L,
				startOrigin = MANUAL_START_ORIGIN,
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "START_ACCEPTED",
				runtimeFailureCode = null,
				runRevision = 1L,
				startDeliveryToken = "retained-room-delivery",
				startCommandGeneration = 1L,
				preparedManifestRevision = 1L,
				preparedIntentRevision = 1L,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = startMs,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = null,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
				presentationAcknowledgedAtMs = null,
			),
		)
		val source = stepsManifestSource()
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = 1L,
			serviceRunId = RUN_ID,
			sessionMode = "MANUAL",
			sourcePolicyRevision = SOURCE_POLICY_REVISION,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = MANUAL_START_ORIGIN,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = START_ELAPSED_NANOS,
			effectiveWallTimeMs = startMs,
			zoneId = ZONE.id,
			automationEpoch = null,
			changeReason = "TEST",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
	}

	private suspend fun markImportedRetentionFence(deletedAtMs: Long) = database.withTransaction {
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity.createForOriginalRunDigest(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
				scopeIdentityDigest = "2".repeat(64),
				fenceGeneration = 1L,
				collectedDataEpoch = EPOCH,
				deletedAtMs = deletedAtMs,
			),
		)
	}

	private fun stepsManifestSource() = SessionManifestSourceEntity(
		logicalTrackingId = LOGICAL_ID,
		manifestRevision = 1L,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		consentEpoch = CAPTURE_CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = CAPTURE_QOS_CODE,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
	)

	private fun stepsCapturePolicy(effectiveMs: Long) = SourcePolicyEntity(
		policyRevision = SOURCE_POLICY_REVISION,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		enabled = true,
		qosCode = CAPTURE_QOS_CODE,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = true,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = false,
		captureConsentEpoch = CAPTURE_CONSENT_EPOCH,
		controlConsentEpoch = null,
		ambientConsentEpoch = null,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = START_ELAPSED_NANOS,
		effectiveWallTimeMs = effectiveMs,
		changeReason = "TEST",
	)

	private fun stepsCaptureConsent(effectiveMs: Long) = SourceConsentEpochEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		epoch = CAPTURE_CONSENT_EPOCH,
		eligible = true,
		persistenceEligible = true,
		policyRevision = SOURCE_POLICY_REVISION,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = START_ELAPSED_NANOS,
		effectiveWallTimeMs = effectiveMs,
		changeReason = "TEST",
	)

	private fun portableRun(
		runIdentity: Char,
		factIdentity: Char,
		day: Long,
		steps: Long,
		startMs: Long = startOfDay(day) + HOUR_MS,
		endMs: Long = startMs + HOUR_MS,
	): PortableStepsRunV1 {
		val identity = opaque(runIdentity)
		return PortableStepsRunV1(
			identity = identity,
			deletionScopeDigest = PortableStepsDeletionScopeDigest(
				identity.value.removePrefix("sha256:"),
			),
			startTimeMs = startMs,
			endTimeMs = endMs,
			storedZoneId = ZONE.id,
			manifests = listOf(
				PortableStepsManifestV1(
					revision = 1L,
					effectiveWallTimeMs = startMs,
					originSourcePolicyRevision = 7L,
					captureConsentEpoch = 8L,
				),
			),
			completeness = PortableStepsCompletenessV1(
				captureCoverage = PortableStepsCaptureCoverage.WHOLE_RUN,
				providerCoverage = PortableStepsProviderCoverage.COMPLETE,
				appDrainComplete = true,
				stopComplete = true,
				hasUnresolvedProviderRange = false,
			),
			facts = listOf(
				PortableStepsFactV1.create(
					identity = opaque(factIdentity),
					manifestRevision = 1L,
					intervalStartTimeMs = startMs,
					intervalEndTimeMs = endMs,
					wallTimeUncertaintyMs = 0L,
					coverage = PortableStepsFactCoverage.COVERED,
					stepCount = steps,
				),
			),
		)
	}

	private fun opaque(value: Char) = PortableStepsOpaqueIdentity(
		"sha256:${value.toString().repeat(64)}",
	)

	private fun unavailable(reason: StepsNumericUnverifiableReason) =
		StepsRetainedMetrics.Unverifiable(reason)

	private companion object {
		val ZONE: ZoneId = ZoneId.of("UTC")
		val DAY: Long = LocalDate.of(2026, 4, 2).toEpochDay()
		val SHA_256_HEX = Regex("[0-9a-f]{64}")
		const val HOUR_MS = 60L * 60_000L
		const val EPOCH = 2L
		const val SOURCE_POLICY_REVISION = 1L
		const val CAPTURE_CONSENT_EPOCH = 1L
		const val CAPTURE_QOS_CODE = 1
		const val START_ELAPSED_NANOS = 1L
		const val MANUAL_START_ORIGIN = "MANUAL_FOREGROUND_START"
		const val BOOT_ID = "retained-room-boot"
		const val LOGICAL_ID = "retained-room-logical"
		const val RUN_ID = "retained-room-run"

		fun startOfDay(epochDay: Long): Long = LocalDate.ofEpochDay(epochDay)
			.atStartOfDay(ZONE)
			.toInstant()
			.toEpochMilli()
	}
}
