package com.adsamcik.tracker.tracker.source.summary

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import io.kotest.matchers.shouldBe
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
				startOrigin = "MANUAL_UI",
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
				startOrigin = "MANUAL_UI",
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
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
				presentationAcknowledgedAtMs = DAY_START + HOUR_MS,
			),
		)
		val source = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = 1L,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			consentEpoch = 1L,
			persistenceEligible = true,
			qosCode = 0,
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
			sourcePolicyRevision = 1L,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = "MANUAL_UI",
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
				startOrigin = "MANUAL_UI",
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
			sourcePolicyRevision = 1L,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = "MANUAL_UI",
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
		consentEpoch = 1L,
		persistenceEligible = true,
		qosCode = 0,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
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
		return StepFactRevisionEntity(
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
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			collectedDataEpoch = EPOCH,
			scopeDeletionGeneration = 0L,
			effectChecksum = "$runId-checksum-$factIndex",
			appliedAtMs = endMs,
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
		const val LARGE_FACT_COUNT = 2_049
		const val EPOCH = 2L
		const val ADMISSION_ORDINAL = 10L
		const val LOGICAL_ID = "numeric-room-logical"
		const val RUN_ID = "numeric-room-run"
		const val REPLACEMENT_RUN_ID = "numeric-room-replacement-run"
	}
}
