package com.adsamcik.tracker.tracker.source.summary

import android.app.Application
import app.cash.turbine.test
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
class RoomStepsNumericSummaryRepositoryRoomTest {
	private lateinit var database: AppDatabase
	private lateinit var repository: RoomStepsNumericSummaryRepository

	@Before
	fun setUp() = runBlocking {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
		database.sourcePolicyDao().insertPolicies(listOf(stepsCapturePolicy()))
		database.sourcePolicyDao().insertConsentEpochs(listOf(stepsCaptureConsent()))
		repository = RoomStepsNumericSummaryRepository(database, Dispatchers.IO)
	}

	@After
	fun tearDown() {
		if (::database.isInitialized && database.isOpen) {
			database.close()
		}
	}

	@Test
	fun `persisted zone wins while a missing day requires a valid fallback`() = runBlocking<Unit> {
		database.dailySummaryDao().upsert(
			dateEpochDay = DAY,
			totalDistanceM = 0f,
			totalSteps = 0,
			totalDurationMs = 0L,
			tripCount = 0,
			activeTrackingMs = 0L,
			lastUpdatedMs = 1L,
			calendarZoneId = "UTC",
		)
		val invalidFallback = request(fallbackZoneId = "not-a-zone")

		repository.read(invalidFallback) shouldBe notCaptured()
		database.dailySummaryDao().deleteByDay(DAY)
		repository.read(invalidFallback) shouldBe calendarUnavailable()
	}

	@Test
	fun `invalid persisted zone cannot be replaced by a fallback`() = runBlocking<Unit> {
		database.dailySummaryDao().upsert(
			dateEpochDay = DAY,
			totalDistanceM = 0f,
			totalSteps = 0,
			totalDurationMs = 0L,
			tripCount = 0,
			activeTrackingMs = 0L,
			lastUpdatedMs = 1L,
			calendarZoneId = "not-a-zone",
		)

		repository.read(request()) shouldBe calendarUnavailable()
	}

	@Test
	fun `overlapping adjacent structural days are calendar unavailable`() = runBlocking<Unit> {
		database.dailySummaryDao().upsert(
			dateEpochDay = DAY,
			totalDistanceM = 0f,
			totalSteps = 0,
			totalDurationMs = 0L,
			tripCount = 0,
			activeTrackingMs = 0L,
			lastUpdatedMs = 1L,
			calendarZoneId = "Pacific/Honolulu",
		)
		database.dailySummaryDao().upsert(
			dateEpochDay = DAY + 1L,
			totalDistanceM = 0f,
			totalSteps = 0,
			totalDurationMs = 0L,
			tripCount = 0,
			activeTrackingMs = 0L,
			lastUpdatedMs = 1L,
			calendarZoneId = "Pacific/Kiritimati",
		)

		repository.read(request(lastEpochDay = DAY + 1L)) shouldBe calendarUnavailable()
	}

	@Test
	fun `qualified covered zero is the only zero-valued Ready result`() = runBlocking<Unit> {
		insertCoveredCandidate()

		repository.read(request()) shouldBe StepsNumericSummary.Ready(
			listOf(StepsNumericDay(epochDay = DAY, steps = 0L)),
		)
	}

	@Test
	fun `observation refreshes when terminal presentation settles without a daily summary write`() =
		runBlocking<Unit> {
			insertCoveredCandidate(
				stepsPerFact = 5L,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			)
			val run = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ID))

			repository.observe(request()).test {
				awaitItem() shouldBe StepsNumericSummary.Materializing
				database.sourceSessionDao().acknowledgePresentationQuiescedExact(
					logicalTrackingId = LOGICAL_ID,
					serviceRunId = RUN_ID,
					sessionSegmentId = requireNotNull(run.sessionSegmentId),
					acknowledgedAtMs = DAY_START + HOUR_MS + 1L,
				) shouldBe 1
				awaitItem() shouldBe StepsNumericSummary.Ready(
					listOf(StepsNumericDay(epochDay = DAY, steps = 5L)),
				)
				cancelAndIgnoreRemainingEvents()
			}
		}

	@Test
	fun `authority repair invalidates unavailable numeric observation`() = runBlocking<Unit> {
		insertCoveredCandidate(stepsPerFact = 5L)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_policy WHERE policy_revision = ? AND source_kind = ?",
			arrayOf<Any>(SOURCE_POLICY_REVISION, SourceDestinationOwnerEntity.SOURCE_STEPS),
		)
		val unavailable = StepsNumericSummary.Unverifiable(
			StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
		)
		repository.read(request()) shouldBe unavailable

		repository.observe(request()).test {
			awaitItem() shouldBe unavailable
			database.sourcePolicyDao().insertPolicies(listOf(stepsCapturePolicy()))
			val ready = StepsNumericSummary.Ready(
				listOf(StepsNumericDay(epochDay = DAY, steps = 5L)),
			)
			awaitItem() shouldBe ready

			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM source_consent_epoch WHERE source_kind = ? AND purpose = ? AND epoch = ?",
				arrayOf<Any>(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
					CAPTURE_CONSENT_EPOCH,
				),
			)
			database.invalidationTracker.refreshAsync()
			awaitItem() shouldBe unavailable
			database.sourcePolicyDao().insertConsentEpochs(listOf(stepsCaptureConsent()))
			awaitItem() shouldBe ready
			cancelAndIgnoreRemainingEvents()
		}
	}

	@Test
	fun `terminal session with more than 2048 covered facts remains Ready`() = runBlocking<Unit> {
		insertCoveredCandidate(
			factCount = LARGE_FACT_COUNT,
			stepsPerFact = 1L,
		)

		repository.read(request()) shouldBe StepsNumericSummary.Ready(
			listOf(StepsNumericDay(epochDay = DAY, steps = LARGE_FACT_COUNT.toLong())),
		)
	}

	@Test
	fun `retarget to a durable run outside the day cannot reduce the numeric total`() =
		runBlocking<Unit> {
			insertCoveredCandidate(factCount = 2, stepsPerFact = 4L)
			val selectedSession = requireNotNull(database.sourceSessionDao().session(LOGICAL_ID))
			val selectedRun = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ID))
			val outsideLogicalId = "numeric-outside-logical"
			val outsideRunId = "numeric-outside-run"
			val outsideStartMs = DAY_START + 3L * 24L * HOUR_MS
			database.sourceSessionDao().insertSession(
				selectedSession.copy(
					logicalTrackingId = outsideLogicalId,
					startedAtMs = outsideStartMs,
					cutoffAtMs = outsideStartMs + HOUR_MS,
					completedAtMs = outsideStartMs + HOUR_MS,
				),
			)
			database.sourceSessionDao().insertServiceRun(
				selectedRun.copy(
					serviceRunId = outsideRunId,
					logicalTrackingId = outsideLogicalId,
					startedAtMs = outsideStartMs,
					completedAtMs = outsideStartMs + HOUR_MS,
					startDeliveryToken = "outside-delivery",
					sessionSegmentId = null,
					presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
					presentationAcknowledgedAtMs = null,
				),
			)
			val hiddenLogicalFactId =
				"${SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID}:$RUN_ID-event-0"
			database.openHelper.writableDatabase.execSQL(
				"UPDATE step_fact_revision SET logical_tracking_id = ?, service_run_id = ? " +
					"WHERE logical_fact_id = ? AND operation = 'UPSERT'",
				arrayOf(outsideLogicalId, outsideRunId, hiddenLogicalFactId),
			)

			repository.read(request()) shouldBe StepsNumericSummary.Unverifiable(
				StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
			)
		}

	@Test
	fun `latest corrected fact state remains nonnumeric through the Room repository`() = runBlocking<Unit> {
		insertCoveredCandidate(stepsPerFact = 2L)
		val correctionOrdinal = ADMISSION_ORDINAL + 1L
		val correctionEventId = "$RUN_ID-correction-event"
		val unsignedCorrection = coveredFact(
			runId = RUN_ID,
			manifestRevision = 1L,
			factIndex = 0,
			admissionOrdinal = ADMISSION_ORDINAL,
			startMs = DAY_START,
			endMs = DAY_START + HOUR_MS,
			cumulativeStart = 100L,
			steps = 3L,
			wallTimeUncertaintyMs = 0L,
		).copy(
			semanticRevision = 2L,
			mutationId = "${SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID}:" +
				"$RUN_ID-event-0:2:${StepFactRevisionEntity.OPERATION_UPSERT}",
			sourceEventId = correctionEventId,
			sourceAdmissionOrdinal = correctionOrdinal,
			originIdentity = correctionEventId,
			effectChecksum = "pending-corrected-effect",
			appliedAtMs = DAY_START + HOUR_MS + 1L,
		)
		val corrected = unsignedCorrection.copy(
			effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsignedCorrection),
		)
		database.stepFactRevisionDao().insert(corrected) shouldNotBe -1L
		check(
			database.sourceProjectionStateDao().advanceProductLaneCursor(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				bindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
				projectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
				projectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
				expectedCurrentOrdinal = ADMISSION_ORDINAL,
				throughOrdinal = correctionOrdinal,
				updatedAtMs = DAY_START + HOUR_MS + 1L,
			) == 1,
		)
		val completeness = database.sourceSessionDao()
			.completenessForServiceRun(LOGICAL_ID, RUN_ID)
			.single()
		database.sourceSessionDao().saveCompleteness(
			completeness.copy(
				lastAdmissionOrdinal = correctionOrdinal,
				lastSourceSequence = 2L,
			),
		)
		val session = requireNotNull(database.sourceSessionDao().session(LOGICAL_ID))
		database.sourceSessionDao().updateSession(
			session.copy(finalAdmissionOrdinal = correctionOrdinal),
		)

		repository.read(request()) shouldBe StepsNumericSummary.Unverifiable(
			StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
		)
	}

	@Test
	fun `covered zero whose uncertainty crosses midnight remains nonnumeric`() = runBlocking<Unit> {
		insertCoveredCandidate(wallTimeUncertaintyMs = 1L)

		repository.read(request()) shouldBe StepsNumericSummary.Unverifiable(
			StepsNumericUnverifiableReason.PARTIAL_CAPTURE,
		)
	}

	@Test
	fun `active run with more than 2048 facts remains Materializing`() = runBlocking<Unit> {
		insertCoveredCandidate()
		insertActiveUnboundReplacement()
		insertCoveredFacts(
			runId = REPLACEMENT_RUN_ID,
			manifestRevision = 2L,
			firstAdmissionOrdinal = ADMISSION_ORDINAL + 1L,
			intervalStartMs = DAY_START + 2L * HOUR_MS,
			factCount = LARGE_FACT_COUNT,
			stepsPerFact = 1L,
		)
		val finalOrdinal = ADMISSION_ORDINAL + LARGE_FACT_COUNT
		check(
			database.sourceProjectionStateDao().advanceProductLaneCursor(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				bindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
				projectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
				projectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
				expectedCurrentOrdinal = ADMISSION_ORDINAL,
				throughOrdinal = finalOrdinal,
				updatedAtMs = DAY_START + 3L * HOUR_MS,
			) == 1,
		)

		repository.read(request()) shouldBe StepsNumericSummary.Materializing
	}

	@Test
	fun `closed storage is typed unavailable`() = runBlocking<Unit> {
		database.close()

		repository.read(request()) shouldBe StepsNumericSummary.Unverifiable(
			StepsNumericUnverifiableReason.STORAGE_UNAVAILABLE,
		)
	}

	@Test
	fun `dispatcher cancellation is never converted to storage failure`() {
		val cancellation = CancellationException("expected cancellation")
		val cancellingDispatcher = object : CoroutineDispatcher() {
			override fun dispatch(context: CoroutineContext, block: Runnable) {
				throw cancellation
			}
		}
		val cancellingRepository = RoomStepsNumericSummaryRepository(
			database,
			cancellingDispatcher,
		)

		assertFailsWith<CancellationException> {
			runBlocking { cancellingRepository.read(request()) }
		}
	}

	@Suppress("LongMethod")
	private suspend fun insertCoveredCandidate(
		wallTimeUncertaintyMs: Long = 0L,
		factCount: Int = 1,
		stepsPerFact: Long = 0L,
		presentationAcknowledgement: String = SourceServiceRunEntity.PRESENTATION_QUIESCED,
	) {
		require(factCount > 0)
		require(stepsPerFact >= 0L)
		val finalAdmissionOrdinal = ADMISSION_ORDINAL + factCount - 1L
		val totalSteps = Math.multiplyExact(factCount.toLong(), stepsPerFact)
		require(totalSteps <= Int.MAX_VALUE)
		database.dailySummaryDao().upsert(
			dateEpochDay = DAY,
			totalDistanceM = 0f,
			totalSteps = totalSteps.toInt(),
			totalDurationMs = 0L,
			tripCount = 0,
			activeTrackingMs = 0L,
			lastUpdatedMs = 1L,
			calendarZoneId = ZONE.id,
		)
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
				contiguousAdmissionOrdinal = finalAdmissionOrdinal,
				captureAdmissionCutoffOrdinal = null,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1L,
				updatedAtMs = 2L,
			),
		)
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_ID,
				state = "FINALIZED",
				lifecycleRevision = 2L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = MANUAL_START_ORIGIN,
				clockDomainId = "boot",
				startedAtMs = DAY_START,
				startedElapsedNanos = elapsedAt(DAY_START),
				cutoffAtMs = DAY_START + HOUR_MS,
				cutoffElapsedNanos = elapsedAt(DAY_START + HOUR_MS),
				completedAtMs = DAY_START + HOUR_MS,
				finalAdmissionOrdinal = finalAdmissionOrdinal,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = 1L,
				currentIntentRevision = 1L,
				currentServiceRunId = null,
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = "boot",
			),
		)
		val segmentId = database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = DAY_START,
				endTimeMs = DAY_START + HOUR_MS,
				distanceM = 0f,
				steps = null,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 0,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = null,
				createdAt = DAY_START + HOUR_MS,
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = RUN_ID,
				logicalTrackingId = LOGICAL_ID,
				state = "FINALIZED",
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = DAY_START,
				startedElapsedNanos = elapsedAt(DAY_START),
				completedAtMs = DAY_START + HOUR_MS,
				completionReason = "USER_STOP",
				bootId = "boot",
				leaseGeneration = 1L,
				startOrigin = MANUAL_START_ORIGIN,
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "STOP_ACCEPTED",
				runtimeFailureCode = null,
				runRevision = 2L,
				startDeliveryToken = "delivery",
				startCommandGeneration = 1L,
				preparedManifestRevision = 1L,
				preparedIntentRevision = 1L,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = DAY_START + HOUR_MS,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = presentationAcknowledgement,
				presentationAcknowledgedAtMs = if (
					presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_QUIESCED
				) {
					DAY_START + HOUR_MS
				} else {
					null
				},
			),
		)
		val source = SessionManifestSourceEntity(
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
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = 1L,
			serviceRunId = RUN_ID,
			sessionMode = "MANUAL",
			sourcePolicyRevision = SOURCE_POLICY_REVISION,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = MANUAL_START_ORIGIN,
			effectiveBootId = "boot",
			effectiveElapsedRealtimeNanos = elapsedAt(DAY_START),
			effectiveWallTimeMs = DAY_START,
			zoneId = ZONE.id,
			automationEpoch = null,
			changeReason = "TEST",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
		database.sourceSessionDao().saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				sourceInstanceId = "steps-instance",
				registrationGeneration = 1L,
				lastAdmissionOrdinal = finalAdmissionOrdinal,
				lastSourceSequence = factCount.toLong(),
				appDrainComplete = true,
				providerCoverage = "CALLBACKS_ENTERED_BEFORE_BARRIER",
				stopStatus = "COMPLETE",
				unresolvedSequenceStart = null,
				unresolvedSequenceEnd = null,
				updatedAtMs = DAY_START + HOUR_MS,
			),
		)
		insertCoveredFacts(
			runId = RUN_ID,
			manifestRevision = 1L,
			firstAdmissionOrdinal = ADMISSION_ORDINAL,
			intervalStartMs = DAY_START,
			factCount = factCount,
			stepsPerFact = stepsPerFact,
			wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		)
	}

	@Suppress("LongMethod")
	private suspend fun insertActiveUnboundReplacement() = database.withTransaction {
		val current = requireNotNull(database.sourceSessionDao().session(LOGICAL_ID))
		database.sourceSessionDao().updateSession(
			current.copy(
				state = "ACTIVE",
				desiredPlanRevision = 1L,
				clockDomainId = "boot",
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				completedAtMs = null,
				currentManifestRevision = 2L,
				currentIntentRevision = 2L,
				currentServiceRunId = REPLACEMENT_RUN_ID,
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = "boot",
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = REPLACEMENT_RUN_ID,
				logicalTrackingId = LOGICAL_ID,
				state = "ACTIVE",
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = DAY_START + 2L * HOUR_MS,
				startedElapsedNanos = elapsedAt(DAY_START + 2L * HOUR_MS),
				completedAtMs = null,
				completionReason = null,
				bootId = "boot",
				leaseGeneration = 1L,
				startOrigin = MANUAL_START_ORIGIN,
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "START_ACCEPTED",
				runtimeFailureCode = null,
				runRevision = 2L,
				startDeliveryToken = "replacement-delivery",
				startCommandGeneration = 2L,
				preparedManifestRevision = 2L,
				preparedIntentRevision = 2L,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = DAY_START + 2L * HOUR_MS,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = null,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
				presentationAcknowledgedAtMs = null,
			),
		)
		val source = stepsSource(manifestRevision = 2L)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = 2L,
			serviceRunId = REPLACEMENT_RUN_ID,
			sessionMode = "MANUAL",
			sourcePolicyRevision = SOURCE_POLICY_REVISION,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = MANUAL_START_ORIGIN,
			effectiveBootId = "boot",
			effectiveElapsedRealtimeNanos = elapsedAt(DAY_START + 2L * HOUR_MS),
			effectiveWallTimeMs = DAY_START + 2L * HOUR_MS,
			zoneId = ZONE.id,
			automationEpoch = null,
			changeReason = "REPLACEMENT",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
	}

	private fun stepsSource(manifestRevision: Long) = SessionManifestSourceEntity(
		logicalTrackingId = LOGICAL_ID,
		manifestRevision = manifestRevision,
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

	private fun stepsCapturePolicy() = SourcePolicyEntity(
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
		effectiveBootId = "boot",
		effectiveElapsedRealtimeNanos = RUN_START_ELAPSED_NANOS,
		effectiveWallTimeMs = DAY_START,
		changeReason = "TEST",
	)

	private fun stepsCaptureConsent() = SourceConsentEpochEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		epoch = CAPTURE_CONSENT_EPOCH,
		eligible = true,
		persistenceEligible = true,
		policyRevision = SOURCE_POLICY_REVISION,
		effectiveBootId = "boot",
		effectiveElapsedRealtimeNanos = RUN_START_ELAPSED_NANOS,
		effectiveWallTimeMs = DAY_START,
		changeReason = "TEST",
	)

	private suspend fun insertCoveredFacts(
		runId: String,
		manifestRevision: Long,
		firstAdmissionOrdinal: Long,
		intervalStartMs: Long,
		factCount: Int,
		stepsPerFact: Long,
		wallTimeUncertaintyMs: Long = 0L,
	) = database.withTransaction {
		require(factCount > 0)
		require(HOUR_MS >= factCount)
		require(stepsPerFact >= 0L)
		repeat(factCount) { index ->
			val indexLong = index.toLong()
			val startMs = intervalStartMs + HOUR_MS * indexLong / factCount
			val endMs = intervalStartMs + HOUR_MS * (indexLong + 1L) / factCount
			val cumulativeStart = 100L + stepsPerFact * indexLong
			database.stepFactRevisionDao().insert(
				coveredFact(
					runId = runId,
					manifestRevision = manifestRevision,
					factIndex = index,
					admissionOrdinal = firstAdmissionOrdinal + indexLong,
					startMs = startMs,
					endMs = endMs,
					cumulativeStart = cumulativeStart,
					steps = stepsPerFact,
					wallTimeUncertaintyMs = wallTimeUncertaintyMs,
				),
			)
		}
	}

	@Suppress("LongParameterList")
	private fun coveredFact(
		runId: String,
		manifestRevision: Long,
		factIndex: Int,
		admissionOrdinal: Long,
		startMs: Long,
		endMs: Long,
		cumulativeStart: Long,
		steps: Long,
		wallTimeUncertaintyMs: Long,
	): StepFactRevisionEntity {
		val writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID
		val sourceEventId = "$runId-event-$factIndex"
		val logicalFactId = "$writerProjectionId:$sourceEventId"
		val unsigned = StepFactRevisionEntity(
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			mutationId = "$logicalFactId:1:${StepFactRevisionEntity.OPERATION_UPSERT}",
			stepIntervalId = null,
			sourceEventId = sourceEventId,
			sourceAdmissionOrdinal = admissionOrdinal,
			originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
			originIdentity = sourceEventId,
			writerProjectionId = writerProjectionId,
			writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
			operation = StepFactRevisionEntity.OPERATION_UPSERT,
			intervalStartTimeMs = startMs,
			intervalEndTimeMs = endMs,
			intervalStartElapsedRealtimeNanos = elapsedAt(startMs),
			intervalEndElapsedRealtimeNanos = elapsedAt(endMs),
			clockDomainId = "boot",
			bootClockDomainId = "boot",
			cumulativeStepCountStart = cumulativeStart,
			cumulativeStepCountEnd = cumulativeStart + steps,
			wallTimeUncertaintyMs = wallTimeUncertaintyMs,
			coverageKind = StepFactRevisionEntity.COVERAGE_COVERED,
			effectiveStepCount = steps,
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = runId,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = manifestRevision,
			sourcePolicyRevision = SOURCE_POLICY_REVISION,
			captureConsentEpoch = CAPTURE_CONSENT_EPOCH,
			collectedDataEpoch = EPOCH,
			scopeDeletionGeneration = 0L,
			effectChecksum = "pending-test-effect",
			appliedAtMs = endMs,
		)
		return unsigned.copy(
			effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsigned),
		)
	}

	private fun elapsedAt(wallTimeMs: Long): Long = RUN_START_ELAPSED_NANOS +
		(wallTimeMs - DAY_START) * NANOS_PER_MILLISECOND

	private fun request(
		fallbackZoneId: String = ZONE.id,
		lastEpochDay: Long = DAY,
	) = StepsNumericSummaryRequest(
		firstEpochDay = DAY,
		lastEpochDayInclusive = lastEpochDay,
		fallbackCalendarZoneId = fallbackZoneId,
	)

	private fun notCaptured() = StepsNumericSummary.Unverifiable(
		StepsNumericUnverifiableReason.NOT_CAPTURED,
	)

	private fun calendarUnavailable() = StepsNumericSummary.Unverifiable(
		StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
	)

	private companion object {
		val ZONE: ZoneId = ZoneId.of("Europe/Prague")
		val DAY: Long = LocalDate.of(2026, 4, 2).toEpochDay()
		val DAY_START: Long = LocalDate.ofEpochDay(DAY).atStartOfDay(ZONE)
			.toInstant().toEpochMilli()
		const val HOUR_MS = 60L * 60_000L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val RUN_START_ELAPSED_NANOS = 1L
		const val SOURCE_POLICY_REVISION = 1L
		const val CAPTURE_CONSENT_EPOCH = 1L
		const val CAPTURE_QOS_CODE = 1
		const val MANUAL_START_ORIGIN = "MANUAL_FOREGROUND_START"
		const val LARGE_FACT_COUNT = 2_049
		const val EPOCH = 2L
		const val ADMISSION_ORDINAL = 10L
		const val LOGICAL_ID = "numeric-room-logical"
		const val RUN_ID = "numeric-room-run"
		const val REPLACEMENT_RUN_ID = "numeric-room-replacement-run"
	}
}
