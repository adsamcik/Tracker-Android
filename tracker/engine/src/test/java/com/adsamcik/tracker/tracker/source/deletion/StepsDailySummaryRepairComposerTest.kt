package com.adsamcik.tracker.tracker.source.deletion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryAggregator
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
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionUnsupportedReason
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass")
class StepsDailySummaryRepairComposerTest {
	private lateinit var database: AppDatabase
	private var ordinal = 1L

	@Before
	fun setUp() = runTest {
		resetDatabase()
	}

	private suspend fun resetDatabase() {
		if (::database.isInitialized && database.isOpen) {
			database.close()
		}
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
		database.dailySummaryDao().upsert(
			dateEpochDay = DAY,
			totalDistanceM = 0f,
			totalSteps = 0,
			totalDurationMs = 0L,
			tripCount = 0,
			activeTrackingMs = 0L,
			lastUpdatedMs = 1L,
			calendarZoneId = ZONE.id,
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `zero-sample v28 survivor uses covered facts instead of presentation metadata`() = runTest {
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "zero-sample-logical",
			runId = "zero-sample-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 17L,
			sampleCount = 0,
			presentationSteps = null,
		)

		val result = composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE)

		result shouldBe StepsDayRepairPreflight.Ready(
			listOf(
				StepsDayRepairPlan(
					epochDay = DAY,
					zoneId = ZONE,
					totals = com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryTotals(
						distanceM = 10f,
						steps = 17,
						durationMs = HOUR_MS,
						tripCount = 1,
					),
				),
			),
		)
	}

	@Test
	fun `source repair fails closed when deterministic SQL cap plus one is reached`() = runTest {
		repeat(257) { index ->
			database.sessionSegmentDao().insert(
				segment(
					startMs = DAY_START + HOUR_MS + index,
					endMs = DAY_START + 2L * HOUR_MS + index,
					steps = null,
					sampleCount = 0,
				),
			)
		}

		composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE) shouldBe
			StepsDayRepairPreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)
	}

	@Test
	fun `persisted current-zone alias composes under its exact stored calendar authority`() =
		runTest {
			val manifestZone = ZoneId.of("Pacific/Kiritimati")
			val writerZone = ZoneId.of("Pacific/Honolulu")
			val manifestDay = LocalDate.of(2026, 4, 2)
			val startMs = manifestDay.atTime(0, 30).atZone(manifestZone).toInstant().toEpochMilli()
			val writerDay = java.time.Instant.ofEpochMilli(startMs)
				.atZone(writerZone)
				.toLocalDate()
				.toEpochDay()
			installLane(cursor = 100L)
			insertCandidate(
				logicalId = "alias-logical",
				runId = "alias-run",
				manifestRevision = 1L,
				startMs = startMs,
				endMs = startMs + HOUR_MS,
				steps = 7L,
				zoneId = manifestZone,
			)
			DailySummaryAggregator(
				database.dailySummaryDao(),
				database.sessionSegmentDao(),
				zoneId = writerZone,
			).materializeDayFromSegments(writerDay)

			composer().compose(listOf(writerDay), excludedSegmentId = Long.MIN_VALUE) shouldBe
				StepsDayRepairPreflight.Ready(
					listOf(
						StepsDayRepairPlan(
							epochDay = writerDay,
							zoneId = writerZone,
							totals = com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryTotals(
								distanceM = 10f,
								steps = 7,
								durationMs = HOUR_MS,
								tripCount = 1,
							),
						),
					),
				)
			database.dailySummaryDao().getByDay(writerDay)?.totalSteps shouldBe 7
		}

	@Test
	fun `null or invalid persisted calendar authority fails closed`() = runTest {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE daily_summary SET calendar_zone_id = NULL WHERE date_epoch_day = ?",
			arrayOf(DAY),
		)
		assertDayUnverifiable()

		resetDatabase()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE daily_summary SET calendar_zone_id = 'not-a-zone' WHERE date_epoch_day = ?",
			arrayOf(DAY),
		)
		assertDayUnverifiable()
	}

	@Test
	fun `nonoverlapping replacement members compose one logical trip and preserve exact fact totals`() =
		runTest {
			installLane(cursor = 100L)
			insertCandidate(
				logicalId = "replacement-logical",
				runId = "replacement-run-a",
				manifestRevision = 1L,
				startMs = DAY_START + HOUR_MS,
				endMs = DAY_START + 2L * HOUR_MS,
				steps = 7L,
			)
			insertCandidate(
				logicalId = "replacement-logical",
				runId = "replacement-run-b",
				manifestRevision = 2L,
				startMs = DAY_START + 2L * HOUR_MS,
				endMs = DAY_START + 3L * HOUR_MS,
				steps = 11L,
			)

			val result = composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE)
			val ready = result as StepsDayRepairPreflight.Ready

			ready.plans.single().totals?.steps shouldBe 18
			ready.plans.single().totals?.tripCount shouldBe 1
			ready.plans.single().totals?.durationMs shouldBe 2L * HOUR_MS
		}

	@Test
	fun `overlapping replacement members fail closed instead of double counting`() = runTest {
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "overlap-logical",
			runId = "overlap-run-a",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 3L * HOUR_MS,
			steps = 7L,
		)
		insertCandidate(
			logicalId = "overlap-logical",
			runId = "overlap-run-b",
			manifestRevision = 2L,
			startMs = DAY_START + 2L * HOUR_MS,
			endMs = DAY_START + 4L * HOUR_MS,
			steps = 11L,
		)

		composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE) shouldBe
			StepsDayRepairPreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)
	}

	@Test
	fun `null legacy and partial candidate evidence never become zero`() = runTest {
		database.sessionSegmentDao().insert(
			segment(
				startMs = DAY_START + HOUR_MS,
				endMs = DAY_START + 2L * HOUR_MS,
				steps = null,
				sampleCount = 99,
			),
		)

		composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE) shouldBe
			StepsDayRepairPreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)

		resetDatabase()
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "partial-logical",
			runId = "partial-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
			coverage = StepFactRevisionEntity.COVERAGE_PARTIAL,
		)

		composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE) shouldBe
			StepsDayRepairPreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)
	}

	@Test
	fun `lane behind settled target returns typed materializing without a fabricated total`() = runTest {
		installLane(cursor = 1L)
		insertCandidate(
			logicalId = "materializing-logical",
			runId = "materializing-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
			admissionOrdinal = 3L,
		)

		composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE) shouldBe
			StepsDayRepairPreflight.Materializing
	}

	@Test
	fun `zero-sample Location survivor contributes only with exact settled source evidence`() = runTest {
		insertAttributedSurvivor(
			logicalId = "location-logical",
			runId = "location-run",
			sources = listOf(nonStepsCaptureSource("location-logical", SourceKind.LOCATION.stableCode)),
			completenessRows = listOf(
				completeness(
					logicalId = "location-logical",
					runId = "location-run",
					admissionOrdinal = 4L,
					sourceKind = SourceKind.LOCATION.stableCode,
				),
			),
		)

		composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE) shouldBe
			StepsDayRepairPreflight.Ready(
				listOf(
					StepsDayRepairPlan(
						epochDay = DAY,
						zoneId = ZONE,
						totals = com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryTotals(
							distanceM = 10f,
							steps = 0,
							durationMs = HOUR_MS,
							tripCount = 1,
						),
					),
				),
			)
	}

	@Test
	fun `non-Steps manifest without durable observation fails closed`() = runTest {
		insertAttributedSurvivor(
			logicalId = "intent-only-logical",
			runId = "intent-only-run",
			sources = listOf(nonStepsCaptureSource("intent-only-logical", SourceKind.LOCATION.stableCode)),
			completenessRows = listOf(
				completeness(
					logicalId = "intent-only-logical",
					runId = "intent-only-run",
					admissionOrdinal = null,
					sourceKind = SourceKind.LOCATION.stableCode,
				),
			),
		)

		assertDayUnverifiable()
	}

	@Test
	fun `control-only unknown and no-capture manifests fail closed`() = runTest {
		insertAttributedSurvivor(
			logicalId = "control-logical",
			runId = "control-run",
			sources = listOf(
				nonStepsCaptureSource("control-logical", SourceKind.LOCATION.stableCode).copy(
					purpose = "CONTROL",
					persistenceEligible = false,
				),
			),
		)
		assertDayUnverifiable()

		resetDatabase()
		insertAttributedSurvivor(
			logicalId = "unknown-source-logical",
			runId = "unknown-source-run",
			sources = listOf(nonStepsCaptureSource("unknown-source-logical", 999)),
		)
		assertDayUnverifiable()

		resetDatabase()
		insertAttributedSurvivor(
			logicalId = "unknown-purpose-logical",
			runId = "unknown-purpose-run",
			sources = listOf(
				nonStepsCaptureSource("unknown-purpose-logical", SourceKind.LOCATION.stableCode).copy(
					purpose = "UNKNOWN",
				),
			),
		)
		assertDayUnverifiable()

		resetDatabase()
		insertAttributedSurvivor(
			logicalId = "no-capture-logical",
			runId = "no-capture-run",
			sources = emptyList(),
		)
		assertDayUnverifiable()
	}

	@Test
	fun `incomplete or gapped non-Steps completeness fails closed`() = runTest {
		insertAttributedSurvivor(
			logicalId = "incomplete-logical",
			runId = "incomplete-run",
			sources = listOf(nonStepsCaptureSource("incomplete-logical", SourceKind.LOCATION.stableCode)),
			completenessRows = listOf(
				completeness(
					logicalId = "incomplete-logical",
					runId = "incomplete-run",
					admissionOrdinal = 4L,
					sourceKind = SourceKind.LOCATION.stableCode,
					appDrainComplete = false,
				),
			),
		)
		assertDayUnverifiable()

		resetDatabase()
		insertAttributedSurvivor(
			logicalId = "gapped-logical",
			runId = "gapped-run",
			sources = listOf(nonStepsCaptureSource("gapped-logical", SourceKind.LOCATION.stableCode)),
			completenessRows = listOf(
				completeness(
					logicalId = "gapped-logical",
					runId = "gapped-run",
					admissionOrdinal = 4L,
					sourceKind = SourceKind.LOCATION.stableCode,
					unresolvedSequenceStart = 2L,
					unresolvedSequenceEnd = 3L,
				),
			),
		)
		assertDayUnverifiable()
	}

	@Test
	fun `Steps intent cannot fall back to otherwise qualified Location evidence`() = runTest {
		val logicalId = "mixed-steps-logical"
		insertAttributedSurvivor(
			logicalId = logicalId,
			runId = "mixed-steps-run",
			sources = listOf(
				nonStepsCaptureSource(logicalId, SourceKind.LOCATION.stableCode),
				manifestSource(logicalId, 1L),
			),
			completenessRows = listOf(
				completeness(
					logicalId = logicalId,
					runId = "mixed-steps-run",
					admissionOrdinal = 4L,
					sourceKind = SourceKind.LOCATION.stableCode,
				),
			),
		)

		assertDayUnverifiable()
	}

	@Test
	fun `non-Steps qualification rejects contradictory Steps facts`() = runTest {
		val logicalId = "location-with-stray-steps-logical"
		val runId = "location-with-stray-steps-run"
		insertAttributedSurvivor(
			logicalId = logicalId,
			runId = runId,
			sources = listOf(nonStepsCaptureSource(logicalId, SourceKind.LOCATION.stableCode)),
			completenessRows = listOf(
				completeness(
					logicalId = logicalId,
					runId = runId,
					admissionOrdinal = 4L,
					sourceKind = SourceKind.LOCATION.stableCode,
				),
			),
		)
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 1L,
				startMs = DAY_START + HOUR_MS,
				endMs = DAY_START + 2L * HOUR_MS,
				steps = 3L,
				coverage = StepFactRevisionEntity.COVERAGE_COVERED,
				admissionOrdinal = 4L,
			),
		)

		assertDayUnverifiable()
	}

	@Test
	fun `candidate fact beyond settled completeness fails closed`() = runTest {
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "unsettled-fact-logical",
			runId = "unsettled-fact-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
			admissionOrdinal = 5L,
			completenessOrdinal = 4L,
		)

		assertDayUnverifiable()
	}

	@Test
	fun `replacement trip uses logical start even when preceding sibling is outside query window`() =
		runTest {
			installLane(cursor = 100L)
			val logicalId = "long-replacement-logical"
			val previousStart = LocalDate.ofEpochDay(DAY - 2L).atStartOfDay(ZONE)
				.toInstant().toEpochMilli() + HOUR_MS
			insertCandidate(
				logicalId = logicalId,
				runId = "long-replacement-a",
				manifestRevision = 1L,
				startMs = previousStart,
				endMs = previousStart + HOUR_MS,
				steps = 3L,
				logicalStartedAtMs = previousStart,
			)
			insertCandidate(
				logicalId = logicalId,
				runId = "long-replacement-b",
				manifestRevision = 2L,
				startMs = DAY_START + HOUR_MS,
				endMs = DAY_START + 2L * HOUR_MS,
				steps = 8L,
				logicalStartedAtMs = previousStart,
			)

			val ready = composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE)
				as StepsDayRepairPreflight.Ready

			ready.plans.single().totals?.steps shouldBe 8
			ready.plans.single().totals?.tripCount shouldBe 0
		}

	@Test
	fun `survivor policy or consent mismatch fails closed`() = runTest {
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "policy-mismatch-logical",
			runId = "policy-mismatch-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
			factPolicyRevision = 2L,
		)
		assertDayUnverifiable()

		resetDatabase()
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "consent-mismatch-logical",
			runId = "consent-mismatch-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
			factConsentEpoch = 2L,
		)
		assertDayUnverifiable()
	}

	private suspend fun assertDayUnverifiable() {
		composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE) shouldBe
			StepsDayRepairPreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)
	}

	private fun composer() = StepsDailySummaryRepairComposer(database)

	private suspend fun insertCandidate(
		logicalId: String,
		runId: String,
		manifestRevision: Long,
		startMs: Long,
		endMs: Long,
		steps: Long,
		sampleCount: Int = 1,
		presentationSteps: Int? = steps.toInt(),
		coverage: String = StepFactRevisionEntity.COVERAGE_COVERED,
		admissionOrdinal: Long = ordinal++,
		completenessOrdinal: Long = admissionOrdinal,
		logicalStartedAtMs: Long = startMs,
		zoneId: ZoneId = ZONE,
		manifestPolicyRevision: Long = 1L,
		bindingConsentEpoch: Long = 1L,
		factPolicyRevision: Long = manifestPolicyRevision,
		factConsentEpoch: Long = bindingConsentEpoch,
	) {
		ensureLogicalSession(logicalId, logicalStartedAtMs)
		val segmentId = database.sessionSegmentDao().insert(
			segment(startMs, endMs, presentationSteps, sampleCount).copy(
				logicalTrackingId = logicalId,
				serviceRunId = runId,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			serviceRun(logicalId, runId, segmentId, endMs),
		)
		val source = manifestSource(logicalId, manifestRevision, consentEpoch = bindingConsentEpoch)
		val unsigned = manifest(
			logicalId,
			runId,
			manifestRevision,
			startMs,
			zoneId = zoneId,
			sourcePolicyRevision = manifestPolicyRevision,
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
		database.sourceSessionDao().saveCompleteness(
			completeness(logicalId, runId, completenessOrdinal),
		)
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = manifestRevision,
				startMs = startMs,
				endMs = endMs,
				steps = steps,
				coverage = coverage,
				admissionOrdinal = admissionOrdinal,
				sourcePolicyRevision = factPolicyRevision,
				captureConsentEpoch = factConsentEpoch,
			),
		)
	}

	private suspend fun insertAttributedSurvivor(
		logicalId: String,
		runId: String,
		sources: List<SessionManifestSourceEntity>,
		completenessRows: List<SourceSessionCompletenessEntity> = emptyList(),
		startMs: Long = DAY_START + HOUR_MS,
		endMs: Long = DAY_START + 2L * HOUR_MS,
		sampleCount: Int = 0,
	) {
		ensureLogicalSession(logicalId, startMs)
		val segmentId = database.sessionSegmentDao().insert(
			segment(startMs, endMs, steps = null, sampleCount = sampleCount).copy(
				logicalTrackingId = logicalId,
				serviceRunId = runId,
			),
		)
		database.sourceSessionDao().insertServiceRun(serviceRun(logicalId, runId, segmentId, endMs))
		val unsigned = manifest(logicalId, runId, revision = 1L, startMs = startMs)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, sources)),
		)
		if (sources.isNotEmpty()) {
			database.sourceSessionDao().insertManifestSources(sources)
		}
		completenessRows.forEach { row -> database.sourceSessionDao().saveCompleteness(row) }
	}

	private fun nonStepsCaptureSource(logicalId: String, sourceKind: Int) =
		SessionManifestSourceEntity(
			logicalTrackingId = logicalId,
			manifestRevision = 1L,
			sourceKind = sourceKind,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			consentEpoch = 1L,
			persistenceEligible = true,
			qosCode = 0,
		)

	private suspend fun ensureLogicalSession(logicalId: String, startedAtMs: Long) {
		if (database.sourceSessionDao().session(logicalId) != null) {
			return
		}
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = logicalId,
				state = "FINALIZED",
				lifecycleRevision = 2L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_UI",
				clockDomainId = "boot",
				startedAtMs = startedAtMs,
				startedElapsedNanos = 1L,
				cutoffAtMs = startedAtMs + HOUR_MS,
				cutoffElapsedNanos = 2L,
				completedAtMs = startedAtMs + HOUR_MS,
				finalAdmissionOrdinal = null,
				failureCode = null,
				sessionMode = "MANUAL",
			),
		)
	}

	private suspend fun installLane(cursor: Long) {
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
				contiguousAdmissionOrdinal = cursor,
				captureAdmissionCutoffOrdinal = null,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1L,
				updatedAtMs = 2L,
			),
		)
	}

	private fun segment(startMs: Long, endMs: Long, steps: Int?, sampleCount: Int) = SessionSegment(
		startTimeMs = startMs,
		endTimeMs = endMs,
		distanceM = 10f,
		steps = steps,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = sampleCount,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = null,
		createdAt = endMs,
	)

	private fun serviceRun(logicalId: String, runId: String, segmentId: Long, endMs: Long) =
		SourceServiceRunEntity(
			serviceRunId = runId,
			logicalTrackingId = logicalId,
			state = "FINALIZED",
			desiredPlanRevision = 1L,
			rolloutRevision = 1L,
			foregroundCapabilityFlags = 0L,
			startedAtMs = endMs - HOUR_MS,
			startedElapsedNanos = 1L,
			completedAtMs = endMs,
			completionReason = "USER_STOP",
			bootId = "boot",
			leaseGeneration = 1L,
			startOrigin = "MANUAL_UI",
			desiredForegroundCapabilityFlags = 0L,
			appliedForegroundCapabilityFlags = 0L,
			runtimeAcknowledgement = "STOP_ACCEPTED",
			runtimeFailureCode = null,
			runRevision = 2L,
			startDeliveryToken = "delivery-$runId",
			startCommandGeneration = 1L,
			preparedManifestRevision = 1L,
			preparedIntentRevision = 1L,
			androidDeliveryState = "FOREGROUND_ACCEPTED",
			androidDeliveryUpdatedAtMs = endMs,
			startIsUserInitiated = true,
			startIsAmbient = false,
			sessionSegmentId = segmentId,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = endMs,
		)

	private fun manifest(
		logicalId: String,
		runId: String,
		revision: Long,
		startMs: Long,
		zoneId: ZoneId = ZONE,
		sourcePolicyRevision: Long = 1L,
	) =
		SessionManifestVersionEntity(
			logicalTrackingId = logicalId,
			manifestRevision = revision,
			serviceRunId = runId,
			sessionMode = "MANUAL",
			sourcePolicyRevision = sourcePolicyRevision,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = "MANUAL_UI",
			effectiveBootId = "boot",
			effectiveElapsedRealtimeNanos = 1L,
			effectiveWallTimeMs = startMs,
			zoneId = zoneId.id,
			automationEpoch = null,
			changeReason = "TEST",
			manifestChecksum = "",
		)

	private fun manifestSource(
		logicalId: String,
		revision: Long,
		consentEpoch: Long = 1L,
	) = SessionManifestSourceEntity(
		logicalTrackingId = logicalId,
		manifestRevision = revision,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		consentEpoch = consentEpoch,
		persistenceEligible = true,
		qosCode = 0,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
	)

	private fun completeness(
		logicalId: String,
		runId: String,
		admissionOrdinal: Long?,
		sourceKind: Int = SourceDestinationOwnerEntity.SOURCE_STEPS,
		appDrainComplete: Boolean = true,
		unresolvedSequenceStart: Long? = null,
		unresolvedSequenceEnd: Long? = null,
	) =
		SourceSessionCompletenessEntity(
			logicalTrackingId = logicalId,
			serviceRunId = runId,
			sourceKind = sourceKind,
			sourceInstanceId = "steps-$runId",
			registrationGeneration = 1L,
			lastAdmissionOrdinal = admissionOrdinal,
			lastSourceSequence = admissionOrdinal,
			appDrainComplete = appDrainComplete,
			providerCoverage = "CALLBACKS_ENTERED_BEFORE_BARRIER",
			stopStatus = "COMPLETE",
			unresolvedSequenceStart = unresolvedSequenceStart,
			unresolvedSequenceEnd = unresolvedSequenceEnd,
			updatedAtMs = 2L,
		)

	@Suppress("LongMethod")
	private fun fact(
		logicalId: String,
		runId: String,
		manifestRevision: Long,
		startMs: Long,
		endMs: Long,
		steps: Long,
		coverage: String,
		admissionOrdinal: Long,
		sourcePolicyRevision: Long = 1L,
		captureConsentEpoch: Long = 1L,
	) = StepFactRevisionEntity(
		logicalFactId = "fact-$runId-$admissionOrdinal",
		semanticRevision = 1L,
		mutationId = "mutation-$runId-$admissionOrdinal",
		stepIntervalId = null,
		sourceEventId = "event-$runId-$admissionOrdinal",
		sourceAdmissionOrdinal = admissionOrdinal,
		originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
		originIdentity = "event-$runId-$admissionOrdinal",
		writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
		operation = StepFactRevisionEntity.OPERATION_UPSERT,
		intervalStartTimeMs = startMs,
		intervalEndTimeMs = endMs,
		intervalStartElapsedRealtimeNanos = 1L,
		intervalEndElapsedRealtimeNanos = 2L,
		clockDomainId = "boot",
		bootClockDomainId = "boot",
		cumulativeStepCountStart = 100L,
		cumulativeStepCountEnd = 100L + steps,
		wallTimeUncertaintyMs = 1L,
		coverageKind = coverage,
		effectiveStepCount = steps,
		logicalTrackingId = logicalId,
		serviceRunId = runId,
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = manifestRevision,
		sourcePolicyRevision = sourcePolicyRevision,
		captureConsentEpoch = captureConsentEpoch,
		collectedDataEpoch = EPOCH,
		scopeDeletionGeneration = 0L,
		effectChecksum = "checksum-$runId-$admissionOrdinal",
		appliedAtMs = endMs,
	)

	private companion object {
		val ZONE: ZoneId = ZoneId.of("Europe/Prague")
		val DAY: Long = LocalDate.of(2026, 4, 2).toEpochDay()
		val DAY_START: Long = LocalDate.ofEpochDay(DAY).atStartOfDay(ZONE).toInstant().toEpochMilli()
		const val HOUR_MS = 60L * 60_000L
		const val EPOCH = 2L
	}
}
