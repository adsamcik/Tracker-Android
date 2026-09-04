package com.adsamcik.tracker.tracker.source.deletion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.markStepsRetentionTruncation
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryAggregator
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryTotals
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionUnsupportedReason
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
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
		database.sourcePolicyDao().insertPolicies(listOf(stepsCapturePolicy()))
		database.sourcePolicyDao().insertConsentEpochs(listOf(stepsCaptureConsent()))
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
					numericSteps = StepsDayNumericComposition.Complete(17L),
				),
			),
		)
	}

	@Test
	fun `numeric composition keeps an empty day nonnumeric instead of fabricating zero`() = runTest {
		composer().composeForNumericRead(mapOf(DAY to ZONE)) shouldBe
			StepsDayRepairPreflight.Ready(
				listOf(
					StepsDayRepairPlan(
						epochDay = DAY,
						zoneId = ZONE,
						totals = null,
						numericSteps = StepsDayNumericComposition.NotCaptured,
					),
				),
			)
	}

	@Test
	fun `settled deletion validation without a summary returns no repair plans`() = runTest {
		database.dailySummaryDao().deleteByDay(DAY) shouldBe 1
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "no-summary-settled-logical",
			runId = "no-summary-settled-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
		)

		composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE) shouldBe
			StepsDayRepairPreflight.Ready(emptyList())
	}

	@Test
	fun `deletion validation without a summary preserves materializing state`() = runTest {
		database.dailySummaryDao().deleteByDay(DAY) shouldBe 1
		installLane(cursor = 100L)
		val runId = "no-summary-materializing-run"
		insertCandidate(
			logicalId = "no-summary-materializing-logical",
			runId = runId,
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET presentation_acknowledgement = 'PENDING', " +
				"presentation_acknowledged_at_ms = NULL WHERE service_run_id = ?",
			arrayOf(runId),
		)

		composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE) shouldBe
			StepsDayRepairPreflight.Materializing
	}

	@Test
	fun `deletion validation without a summary rejects invalid facts before materializing`() = runTest {
		database.dailySummaryDao().deleteByDay(DAY) shouldBe 1
		installLane(cursor = 100L)
		val runId = "no-summary-invalid-materializing-run"
		insertCandidate(
			logicalId = "no-summary-invalid-materializing-logical",
			runId = runId,
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
			factPolicyRevision = 2L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET presentation_acknowledgement = 'PENDING', " +
				"presentation_acknowledged_at_ms = NULL WHERE service_run_id = ?",
			arrayOf(runId),
		)

		composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE) shouldBe
			StepsDayRepairPreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)
	}

	@Test
	fun `unbound Steps and production non-Steps materialize while numeric non-Steps stays not captured`() =
		runTest {
			installLane(cursor = 100L)
			insertUnboundRun(
				logicalId = "unbound-starting-logical",
				runId = "unbound-starting-run",
				logicalState = "STARTING",
				runState = "STARTING",
				sources = listOf(manifestSource("unbound-starting-logical", 1L)),
			)

			composer().composeForMaterialization(DAY, ZONE) shouldBe
				StepsDayRepairPreflight.Materializing
			composer().composeForNumericRead(mapOf(DAY to ZONE)) shouldBe
				StepsDayRepairPreflight.Materializing

			resetDatabase()
			insertUnboundRun(
				logicalId = "unbound-location-logical",
				runId = "unbound-location-run",
				logicalState = "ACTIVE",
				runState = "ACTIVE",
				sources = listOf(
					nonStepsCaptureSource("unbound-location-logical", SourceKind.LOCATION.stableCode),
				),
			)

			composer().composeForNumericRead(mapOf(DAY to ZONE)) shouldBe
				StepsDayRepairPreflight.Ready(
					listOf(
						StepsDayRepairPlan(
							epochDay = DAY,
							zoneId = ZONE,
							totals = null,
							numericSteps = StepsDayNumericComposition.NotCaptured,
						),
					),
				)
		}

	@Test
	fun `active unbound Steps replacement prevents a stale settled total`() = runTest {
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "unbound-replacement-logical",
			runId = "unbound-replacement-settled",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 7L,
		)
		val session = requireNotNull(
			database.sourceSessionDao().session("unbound-replacement-logical"),
		)
		database.sourceSessionDao().updateSession(
			session.copy(
				state = "ACTIVE",
				completedAtMs = null,
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				desiredPlanRevision = 1L,
				currentManifestRevision = 2L,
				currentIntentRevision = 3L,
				currentServiceRunId = "unbound-replacement-current",
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = "boot",
			),
		)
		insertUnboundRun(
			logicalId = "unbound-replacement-logical",
			runId = "unbound-replacement-current",
			logicalState = "ACTIVE",
			runState = "ACTIVE",
			manifestRevision = 2L,
			preparedIntentRevision = 3L,
			sources = listOf(manifestSource("unbound-replacement-logical", 2L)),
			insertLogicalSession = false,
		)

		composer().composeForNumericRead(mapOf(DAY to ZONE)) shouldBe
			StepsDayRepairPreflight.Materializing
	}

	@Test
	fun `active unbound non-Steps replacement makes an earlier settled Steps total partial`() =
		runTest {
			installLane(cursor = 100L)
			val logicalId = "mixed-unbound-replacement-logical"
			insertCandidate(
				logicalId = logicalId,
				runId = "mixed-unbound-replacement-settled",
				manifestRevision = 1L,
				startMs = DAY_START + HOUR_MS,
				endMs = DAY_START + 2L * HOUR_MS,
				steps = 7L,
			)
			val session = requireNotNull(database.sourceSessionDao().session(logicalId))
			database.sourceSessionDao().updateSession(
				session.copy(
					state = "ACTIVE",
					completedAtMs = null,
					cutoffAtMs = null,
					cutoffElapsedNanos = null,
					currentManifestRevision = 2L,
					currentIntentRevision = 3L,
					currentServiceRunId = "mixed-unbound-replacement-current",
					lifecycleLeaseGeneration = 1L,
					lifecycleBootId = "boot",
				),
			)
			insertUnboundRun(
				logicalId = logicalId,
				runId = "mixed-unbound-replacement-current",
				logicalState = "ACTIVE",
				runState = "ACTIVE",
				manifestRevision = 2L,
				preparedIntentRevision = 3L,
				sources = listOf(
					nonStepsCaptureSource(logicalId, 2L, SourceKind.LOCATION.stableCode),
				),
				insertLogicalSession = false,
			)

			val ready = composer().composeForNumericRead(mapOf(DAY to ZONE))
				as StepsDayRepairPreflight.Ready

			ready.plans.single().numericSteps shouldBe StepsDayNumericComposition.PartialCapture
			ready.plans.single().totals?.steps shouldBe 7
		}

	@Test
	fun `terminal unbound Steps authority is permanently unverifiable`() = runTest {
		installLane(cursor = 100L)
		insertUnboundRun(
			logicalId = "terminal-unbound-logical",
			runId = "terminal-unbound-run",
			logicalState = "FINALIZED",
			runState = "FINALIZED",
			sources = listOf(manifestSource("terminal-unbound-logical", 1L)),
		)

		assertDayUnverifiableForNumeric()
		}

	@Test
	fun `deletion discovers a surviving source envelope and still excludes the selected run`() = runTest {
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "deletion-selected-logical",
			runId = "deletion-selected-run",
			manifestRevision = 1L,
			startMs = DAY_START + 3L * HOUR_MS,
			endMs = DAY_START + 4L * HOUR_MS,
			steps = 40L,
		)
		val selectedSegmentId = requireNotNull(
			database.sourceSessionDao().serviceRun("deletion-selected-run")?.sessionSegmentId,
		)
		val survivorSegmentStartMs = DAY_START + 4L * 24L * HOUR_MS
		insertCandidate(
			logicalId = "deletion-source-envelope-logical",
			runId = "deletion-source-envelope-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 9L,
			segmentStartMs = survivorSegmentStartMs,
			segmentEndMs = survivorSegmentStartMs + HOUR_MS,
		)

		val ready = composer().compose(listOf(DAY), excludedSegmentId = selectedSegmentId)
			as StepsDayRepairPreflight.Ready

		ready.plans.single().numericSteps shouldBe StepsDayNumericComposition.Complete(9L)
		ready.plans.single().totals shouldBe DailySummaryTotals(
			distanceM = 0f,
			steps = 9,
			durationMs = 0L,
			tripCount = 1,
		)
	}

	@Test
	fun `source envelope may start before and complete before its presentation segment`() = runTest {
		installLane(cursor = 100L)
		val runStartMs = DAY_START + HOUR_MS
		val runEndMs = DAY_START + 2L * HOUR_MS
		insertCandidate(
			logicalId = "source-envelope-logical",
			runId = "source-envelope-run",
			manifestRevision = 1L,
			startMs = runStartMs,
			endMs = runEndMs,
			steps = 9L,
			segmentStartMs = runStartMs + 5L * 60_000L,
			segmentEndMs = runEndMs + 5L * 60_000L,
		)

		val ready = composer().composeForNumericRead(mapOf(DAY to ZONE))
			as StepsDayRepairPreflight.Ready

		ready.plans.single().numericSteps shouldBe StepsDayNumericComposition.Complete(9L)
		ready.plans.single().totals?.durationMs shouldBe HOUR_MS
	}

	@Test
	fun `fact wall projection discovers a run whose source and presentation envelopes are outside day`() =
		runTest {
			installLane(cursor = 100L)
			val outsideStartMs = DAY_START + 2L * 24L * HOUR_MS
			insertCandidate(
				logicalId = "fact-projection-discovery-logical",
				runId = "fact-projection-discovery-run",
				manifestRevision = 1L,
				startMs = outsideStartMs,
				endMs = outsideStartMs + HOUR_MS,
				steps = 6L,
				factStartMs = DAY_START + HOUR_MS,
				factEndMs = DAY_START + 2L * HOUR_MS,
				factStartElapsedRealtimeNanos = 1L,
				factEndElapsedRealtimeNanos = 1L + HOUR_MS * NANOS_PER_MILLISECOND,
			)

			val ready = composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE)
				as StepsDayRepairPreflight.Ready

			ready.plans.single().numericSteps shouldBe StepsDayNumericComposition.Complete(6L)
			ready.plans.single().totals shouldBe DailySummaryTotals(
				distanceM = 0f,
				steps = 6,
				durationMs = 0L,
				tripCount = 0,
			)
		}

	@Test
	fun `retention-truncated run cannot certify a fact day outside its presentation envelope`() =
		runTest {
			installLane(cursor = 100L)
			val logicalId = "retention-truncated-logical"
			val runId = "retention-truncated-run"
			val outsideStartMs = DAY_START + 2L * 24L * HOUR_MS
			val retainedFromMs = DAY_START + 3L * HOUR_MS
			insertCandidate(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 1L,
				startMs = outsideStartMs,
				endMs = outsideStartMs + HOUR_MS,
				steps = 6L,
				factStartMs = DAY_START + HOUR_MS,
				factEndMs = DAY_START + 2L * HOUR_MS,
				factStartElapsedRealtimeNanos = 1L,
				factEndElapsedRealtimeNanos = 1L + HOUR_MS * NANOS_PER_MILLISECOND,
			)
			database.sourceEvidenceStateDao().updateLifecycle(
				epoch = EPOCH,
				retainedFromMs = retainedFromMs,
				updatedAtMs = retainedFromMs,
			) shouldBe 1
			database.markStepsRetentionTruncation(
				logicalTrackingId = logicalId,
				serviceRunId = runId,
				collectedDataEpoch = EPOCH,
				markedAtMs = retainedFromMs,
			) shouldBe true
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM step_fact_revision WHERE service_run_id = ?",
				arrayOf(runId),
			)
			database.stepFactRevisionDao().countAll() shouldBe 0L

			composer().composeForNumericRead(mapOf(DAY to ZONE)) shouldBe
				StepsDayRepairPreflight.Unsupported(
					StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
				)
			composer().composeForMaterialization(DAY, ZONE) shouldBe
				StepsDayRepairPreflight.Unsupported(
					StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
				)
			composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE) shouldBe
				StepsDayRepairPreflight.Unsupported(
					StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
				)
		}

	@Test
	fun `source interval day is discoverable when its presentation segment starts next day`() =
		runTest {
			installLane(cursor = 100L)
			val nextDayStartMs = LocalDate.ofEpochDay(DAY + 1L).atStartOfDay(ZONE)
				.toInstant().toEpochMilli()
			val runStartMs = nextDayStartMs - 10L * 60_000L
			val runEndMs = nextDayStartMs + 10L * 60_000L
			insertCandidate(
				logicalId = "source-day-discovery-logical",
				runId = "source-day-discovery-run",
				manifestRevision = 1L,
				startMs = runStartMs,
				endMs = runEndMs,
				steps = 8L,
				segmentStartMs = nextDayStartMs + 60_000L,
				segmentEndMs = nextDayStartMs + 20L * 60_000L,
			)

			val before = database.dailySummaryDao().getByDay(DAY)
			val ready = composer().composeForNumericRead(mapOf(DAY to ZONE))
				as StepsDayRepairPreflight.Ready

			ready.plans.single().numericSteps shouldBe StepsDayNumericComposition.PartialCapture
			composer().composeForMaterialization(DAY, ZONE) shouldBe
				StepsDayRepairPreflight.Unsupported(
					StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
				)
			composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE) shouldBe
				StepsDayRepairPreflight.Unsupported(
					StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
				)
			database.dailySummaryDao().getByDay(DAY) shouldBe before
		}

	@Test
	fun `captured-zone endpoint uncertainty is partial even when summary zone is unambiguous`() =
		runTest {
			val capturedZone = ZoneId.of("Pacific/Kiritimati")
			val summaryZone = ZoneId.of("UTC")
			val capturedMidnightMs = LocalDate.of(2026, 4, 2).atStartOfDay(capturedZone)
				.toInstant().toEpochMilli()
			val runStartMs = capturedMidnightMs + 500L
			val summaryDay = Instant.ofEpochMilli(runStartMs).atZone(summaryZone).toLocalDate().toEpochDay()
			installLane(cursor = 100L)
			insertCandidate(
				logicalId = "captured-zone-uncertainty-logical",
				runId = "captured-zone-uncertainty-run",
				manifestRevision = 1L,
				startMs = runStartMs,
				endMs = runStartMs + HOUR_MS,
				steps = 6L,
				zoneId = capturedZone,
				factUncertaintyMs = 1_000L,
			)

			database.dailySummaryDao().upsert(
				dateEpochDay = summaryDay,
				totalDistanceM = 123f,
				totalSteps = 123,
				totalDurationMs = 123L,
				tripCount = 123,
				activeTrackingMs = 123L,
				lastUpdatedMs = 123L,
				calendarZoneId = summaryZone.id,
			)
			val before = database.dailySummaryDao().getByDay(summaryDay)
			val ready = composer().composeForNumericRead(mapOf(summaryDay to summaryZone))
				as StepsDayRepairPreflight.Ready

			ready.plans.single().numericSteps shouldBe StepsDayNumericComposition.PartialCapture
			composer().composeForMaterialization(summaryDay, summaryZone) shouldBe
				StepsDayRepairPreflight.Unsupported(
					StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
				)
			composer().compose(listOf(summaryDay), excludedSegmentId = Long.MIN_VALUE) shouldBe
				StepsDayRepairPreflight.Unsupported(
					StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
				)
			database.dailySummaryDao().getByDay(summaryDay) shouldBe before
		}

	@Test
	fun `positive fact crossing captured-zone midnight is partial inside one summary day`() = runTest {
		val capturedZone = ZoneId.of("UTC")
		val summaryZone = ZoneId.of("America/Los_Angeles")
		val capturedMidnightMs = LocalDate.of(2026, 4, 3).atStartOfDay(capturedZone)
			.toInstant().toEpochMilli()
		val runStartMs = capturedMidnightMs - 30L * 60_000L
		val runEndMs = capturedMidnightMs + 30L * 60_000L
		val summaryDay = Instant.ofEpochMilli(runStartMs).atZone(summaryZone).toLocalDate().toEpochDay()
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "captured-midnight-logical",
			runId = "captured-midnight-run",
			manifestRevision = 1L,
			startMs = runStartMs,
			endMs = runEndMs,
			steps = 6L,
			zoneId = capturedZone,
		)

		val ready = composer().composeForNumericRead(mapOf(summaryDay to summaryZone))
			as StepsDayRepairPreflight.Ready

		ready.plans.single().numericSteps shouldBe StepsDayNumericComposition.PartialCapture
	}

	@Test
	fun `semantically corrupt LIVE_WAL facts fail typed unsupported`() = runTest {
		val corruptions = listOf(
			"cumulative_step_count_end = 99, effective_step_count = 0",
			"effective_step_count = 4",
			"coverage_kind = 'BASELINE', effective_step_count = 0",
			"clock_domain_id = 'other-boot'",
			"boot_clock_domain_id = 'other-boot'",
			"applied_at_ms = interval_end_time_ms - 1",
			"interval_start_elapsed_realtime_nanos = 0",
			"interval_end_elapsed_realtime_nanos = interval_end_elapsed_realtime_nanos + 1000000",
			"semantic_revision = 2",
			"logical_fact_id = 'other-logical-fact'",
			"mutation_id = 'other-mutation'",
			"origin_identity = 'other-origin'",
		)
		for ((index, corruption) in corruptions.withIndex()) {
			if (index > 0) {
				resetDatabase()
			}
			installLane(cursor = 100L)
			val logicalId = "corrupt-live-wal-$index-logical"
			val runId = "corrupt-live-wal-$index-run"
			insertCandidate(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 1L,
				startMs = DAY_START + HOUR_MS,
				endMs = DAY_START + 2L * HOUR_MS,
				steps = 5L,
			)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE step_fact_revision SET $corruption WHERE service_run_id = ?",
				arrayOf(runId),
			)
			resignLiveWalFact(logicalId, runId)

			assertDayUnverifiableForNumeric()
		}
	}

	@Test
	fun `same-run covered facts with overlapping elapsed windows are unverifiable without mutation`() =
		runTest {
			installLane(cursor = 100L)
			val logicalId = "elapsed-overlap-logical"
			val runId = "elapsed-overlap-run"
			val startMs = DAY_START + HOUR_MS
			val midpointMs = startMs + HOUR_MS / 2L
			val endMs = startMs + HOUR_MS
			val firstEndElapsedNanos = 1L + (midpointMs - startMs) * NANOS_PER_MILLISECOND
			insertCandidate(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 1L,
				startMs = startMs,
				endMs = endMs,
				steps = 5L,
				admissionOrdinal = 1L,
				completenessOrdinal = 2L,
				factEndMs = midpointMs,
			)
			database.stepFactRevisionDao().insert(
				fact(
					logicalId = logicalId,
					runId = runId,
					manifestRevision = 1L,
					startMs = midpointMs,
					endMs = endMs,
					steps = 3L,
					coverage = StepFactRevisionEntity.COVERAGE_COVERED,
					admissionOrdinal = 2L,
					cumulativeStepCountStart = 105L,
					intervalStartElapsedRealtimeNanos = firstEndElapsedNanos - 1L,
				),
			) shouldNotBe -1L
			val beforeFacts = database.stepFactRevisionDao().upsertsForServiceRun(logicalId, runId)
			beforeFacts.size shouldBe 2
			beforeFacts.all(StepFactRevisionIntegrity::hasValidCanonicalLiveWalFact) shouldBe true
			val beforeSummary = database.dailySummaryDao().getByDay(DAY)

			assertDayUnverifiableForNumeric()

			database.stepFactRevisionDao().upsertsForServiceRun(logicalId, runId) shouldBe beforeFacts
			database.dailySummaryDao().getByDay(DAY) shouldBe beforeSummary
		}

	@Test
	fun `same-run covered facts with broken cumulative continuation are unverifiable without mutation`() =
		runTest {
			installLane(cursor = 100L)
			val logicalId = "cumulative-discontinuity-logical"
			val runId = "cumulative-discontinuity-run"
			val startMs = DAY_START + HOUR_MS
			val midpointMs = startMs + HOUR_MS / 2L
			val endMs = startMs + HOUR_MS
			val firstEndElapsedNanos = 1L + (midpointMs - startMs) * NANOS_PER_MILLISECOND
			insertCandidate(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 1L,
				startMs = startMs,
				endMs = endMs,
				steps = 5L,
				admissionOrdinal = 1L,
				completenessOrdinal = 2L,
				factEndMs = midpointMs,
			)
			database.stepFactRevisionDao().insert(
				fact(
					logicalId = logicalId,
					runId = runId,
					manifestRevision = 1L,
					startMs = midpointMs,
					endMs = endMs,
					steps = 3L,
					coverage = StepFactRevisionEntity.COVERAGE_COVERED,
					admissionOrdinal = 2L,
					cumulativeStepCountStart = 200L,
					intervalStartElapsedRealtimeNanos = firstEndElapsedNanos,
				),
			) shouldNotBe -1L
			val beforeFacts = database.stepFactRevisionDao().upsertsForServiceRun(logicalId, runId)
			beforeFacts.size shouldBe 2
			beforeFacts.all(StepFactRevisionIntegrity::hasValidCanonicalLiveWalFact) shouldBe true
			val beforeSummary = database.dailySummaryDao().getByDay(DAY)

			assertDayUnverifiableForNumeric()

			database.stepFactRevisionDao().upsertsForServiceRun(logicalId, runId) shouldBe beforeFacts
			database.dailySummaryDao().getByDay(DAY) shouldBe beforeSummary
		}

	@Test
	fun `LIVE_WAL checksum tamper fails typed unsupported`() = runTest {
		installLane(cursor = 100L)
		val runId = "checksum-tamper-run"
		insertCandidate(
			logicalId = "checksum-tamper-logical",
			runId = runId,
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET effect_checksum = 'tampered' WHERE service_run_id = ?",
			arrayOf(runId),
		)

		assertDayUnverifiableForNumeric()
	}

	@Test
	fun `retarget to a durable run outside the day cannot hide a corrupt fact`() = runTest {
		installLane(cursor = 100L)
		val selectedLogicalId = "retarget-selected-logical"
		val selectedRunId = "retarget-selected-run"
		val selectedStartMs = DAY_START + HOUR_MS
		val selectedEndMs = DAY_START + 2L * HOUR_MS
		insertCandidate(
			logicalId = selectedLogicalId,
			runId = selectedRunId,
			manifestRevision = 1L,
			startMs = selectedStartMs,
			endMs = selectedEndMs,
			steps = 4L,
			admissionOrdinal = 1L,
			completenessOrdinal = 2L,
		)
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = selectedLogicalId,
				runId = selectedRunId,
				manifestRevision = 1L,
				startMs = selectedStartMs,
				endMs = selectedEndMs,
				steps = 6L,
				coverage = StepFactRevisionEntity.COVERAGE_COVERED,
				admissionOrdinal = 2L,
			),
		)
		val outsideLogicalId = "retarget-outside-logical"
		val outsideRunId = "retarget-outside-run"
		insertCandidate(
			logicalId = outsideLogicalId,
			runId = outsideRunId,
			manifestRevision = 1L,
			startMs = DAY_START + 3L * 24L * HOUR_MS,
			endMs = DAY_START + 3L * 24L * HOUR_MS + HOUR_MS,
			steps = 3L,
			admissionOrdinal = 100L,
		)
		val hiddenLogicalFactId =
			"${SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID}:event-$selectedRunId-1"
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET logical_tracking_id = ?, service_run_id = ? " +
				"WHERE logical_fact_id = ? AND operation = 'UPSERT'",
			arrayOf(outsideLogicalId, outsideRunId, hiddenLogicalFactId),
		)

		assertDayUnverifiableForNumeric()
	}

	@Test
	fun `elapsed projection accepts the exact sub-millisecond floor and epoch clamp`() = runTest {
		installLane(cursor = 100L)
		val floorStartMs = DAY_START + HOUR_MS
		insertCandidate(
			logicalId = "elapsed-floor-logical",
			runId = "elapsed-floor-run",
			manifestRevision = 1L,
			startMs = floorStartMs,
			endMs = floorStartMs + 1L,
			steps = 3L,
			factStartElapsedRealtimeNanos = 1L,
			factEndElapsedRealtimeNanos = 1L + 1_999_999L,
		)

		val floored = composer().composeForNumericRead(mapOf(DAY to ZONE))
			as StepsDayRepairPreflight.Ready
		floored.plans.single().numericSteps shouldBe StepsDayNumericComposition.Complete(3L)

		resetDatabase()
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "elapsed-clamp-logical",
			runId = "elapsed-clamp-run",
			manifestRevision = 1L,
			startMs = 0L,
			endMs = 2L,
			steps = 4L,
			zoneId = ZoneId.of("UTC"),
			factStartElapsedRealtimeNanos = 1L,
			factEndElapsedRealtimeNanos = 3_000_001L,
		)

		val clamped = composer().composeForNumericRead(mapOf(0L to ZoneId.of("UTC")))
			as StepsDayRepairPreflight.Ready
		clamped.plans.single().numericSteps shouldBe StepsDayNumericComposition.Complete(4L)
	}

	@Test
	@Suppress("LongMethod")
	fun `wall uncertainty crossing a structural boundary never becomes numeric zero or positive`() =
		runTest {
			installLane(cursor = 100L)
			insertCandidate(
				logicalId = "uncertain-positive-logical",
				runId = "uncertain-positive-run",
				manifestRevision = 1L,
				startMs = DAY_START + 500L,
				endMs = DAY_START + HOUR_MS,
				steps = 4L,
				factUncertaintyMs = 1_000L,
			)
		val positive = composer().composeForNumericRead(mapOf(DAY to ZONE))
			as StepsDayRepairPreflight.Ready
		positive.plans.single().numericSteps shouldBe StepsDayNumericComposition.PartialCapture

		resetDatabase()
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "uncertain-zero-logical",
			runId = "uncertain-zero-run",
			manifestRevision = 1L,
			startMs = DAY_START,
			endMs = DAY_START + HOUR_MS,
			steps = 0L,
			factUncertaintyMs = 1L,
		)
		val zero = composer().composeForNumericRead(mapOf(DAY to ZONE))
			as StepsDayRepairPreflight.Ready
		zero.plans.single().numericSteps shouldBe StepsDayNumericComposition.PartialCapture

		resetDatabase()
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "uncertain-safe-logical",
			runId = "uncertain-safe-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 6L,
			factUncertaintyMs = 1_000L,
		)
		val safe = composer().composeForNumericRead(mapOf(DAY to ZONE))
			as StepsDayRepairPreflight.Ready
		safe.plans.single().numericSteps shouldBe StepsDayNumericComposition.Complete(6L)

		resetDatabase()
		installLane(cursor = 100L)
		val dstDay = LocalDate.of(2026, 3, 29)
		val dstStart = dstDay.atTime(1, 30).atZone(ZONE).toInstant().toEpochMilli()
		insertCandidate(
			logicalId = "uncertain-dst-logical",
			runId = "uncertain-dst-run",
			manifestRevision = 1L,
			startMs = dstStart,
			endMs = dstStart + 2L * HOUR_MS,
			steps = 8L,
			factUncertaintyMs = HOUR_MS,
		)
		val dst = composer().composeForNumericRead(mapOf(dstDay.toEpochDay() to ZONE))
			as StepsDayRepairPreflight.Ready
		dst.plans.single().numericSteps shouldBe StepsDayNumericComposition.PartialCapture

		resetDatabase()
		installLane(cursor = 100L)
		val utc = ZoneId.of("UTC")
		val nearMaximumDay = Instant.ofEpochMilli(Long.MAX_VALUE - 3L * 24L * HOUR_MS)
			.atZone(utc)
			.toLocalDate()
		val nearMaximumStart = nearMaximumDay.atStartOfDay(utc).toInstant().toEpochMilli() + HOUR_MS
		insertCandidate(
			logicalId = "uncertain-overflow-logical",
			runId = "uncertain-overflow-run",
			manifestRevision = 1L,
			startMs = nearMaximumStart,
			endMs = nearMaximumStart + HOUR_MS,
			steps = 9L,
			factUncertaintyMs = Long.MAX_VALUE,
			zoneId = utc,
		)
		val overflow = composer().composeForNumericRead(mapOf(nearMaximumDay.toEpochDay() to utc))
			as StepsDayRepairPreflight.Ready
		overflow.plans.single().numericSteps shouldBe StepsDayNumericComposition.PartialCapture
	}

	@Test
	fun `numeric composition refuses a complete total when only some sessions captured Steps`() =
		runTest {
			installLane(cursor = 100L)
			insertCandidate(
				logicalId = "partial-steps-logical",
				runId = "partial-steps-run",
				manifestRevision = 1L,
				startMs = DAY_START + HOUR_MS,
				endMs = DAY_START + 2L * HOUR_MS,
				steps = 9L,
			)
			insertAttributedSurvivor(
				logicalId = "partial-location-logical",
				runId = "partial-location-run",
				sources = listOf(
					nonStepsCaptureSource(
						"partial-location-logical",
						SourceKind.LOCATION.stableCode,
					),
				),
				completenessRows = listOf(
					completeness(
						logicalId = "partial-location-logical",
						runId = "partial-location-run",
						admissionOrdinal = 80L,
						sourceKind = SourceKind.LOCATION.stableCode,
					),
				),
				startMs = DAY_START + 3L * HOUR_MS,
				endMs = DAY_START + 4L * HOUR_MS,
			)

			val result = composer().composeForNumericRead(mapOf(DAY to ZONE))
				as StepsDayRepairPreflight.Ready

			result.plans.single().totals?.steps shouldBe 9
			result.plans.single().numericSteps shouldBe StepsDayNumericComposition.PartialCapture
		}

	@Test
	@Suppress("LongMethod")
	fun `mixed manifest membership is partial in either revision order`() = runTest {
		installLane(cursor = 100L)
		val stepsFirstStartMs = DAY_START + HOUR_MS
		val stepsFirstEndMs = DAY_START + 2L * HOUR_MS
		val stepsFirstChangeAtMs = stepsFirstStartMs + HOUR_MS / 2L
		insertCandidate(
			logicalId = "steps-first-logical",
			runId = "steps-first-run",
			manifestRevision = 1L,
			startMs = stepsFirstStartMs,
			endMs = stepsFirstEndMs,
			steps = 5L,
			factEndMs = stepsFirstChangeAtMs,
		)
		insertManifestRevision(
			logicalId = "steps-first-logical",
			runId = "steps-first-run",
			revision = 2L,
			effectiveAtMs = stepsFirstChangeAtMs,
			sources = listOf(
				nonStepsCaptureSource(
					logicalId = "steps-first-logical",
					revision = 2L,
					sourceKind = SourceKind.LOCATION.stableCode,
				),
			),
		)

		val stepsFirst = composer().composeForNumericRead(mapOf(DAY to ZONE))
			as StepsDayRepairPreflight.Ready
		stepsFirst.plans.single().totals?.steps shouldBe 5
		stepsFirst.plans.single().numericSteps shouldBe StepsDayNumericComposition.PartialCapture

		resetDatabase()
		installLane(cursor = 100L)
		val stepsLastStartMs = DAY_START + 3L * HOUR_MS
		val stepsLastEndMs = DAY_START + 4L * HOUR_MS
		val stepsLastChangeAtMs = stepsLastStartMs + HOUR_MS / 2L
		insertCandidate(
			logicalId = "steps-last-logical",
			runId = "steps-last-run",
			manifestRevision = 2L,
			preparedManifestRevision = 1L,
			startMs = stepsLastStartMs,
			endMs = stepsLastEndMs,
			steps = 7L,
			manifestEffectiveAtMs = stepsLastChangeAtMs,
			factStartMs = stepsLastChangeAtMs,
		)
		insertManifestRevision(
			logicalId = "steps-last-logical",
			runId = "steps-last-run",
			revision = 1L,
			effectiveAtMs = stepsLastStartMs,
			sources = listOf(
				nonStepsCaptureSource(
					logicalId = "steps-last-logical",
					revision = 1L,
					sourceKind = SourceKind.LOCATION.stableCode,
				),
			),
		)

		val stepsLast = composer().composeForNumericRead(mapOf(DAY to ZONE))
			as StepsDayRepairPreflight.Ready
		stepsLast.plans.single().totals?.steps shouldBe 7
		stepsLast.plans.single().numericSteps shouldBe StepsDayNumericComposition.PartialCapture
	}

	@Test
	fun `every Steps manifest revision requires its own covered settlement`() = runTest {
		installLane(cursor = 100L)
		val logicalId = "revision-coverage-logical"
		val runId = "revision-coverage-run"
		val startMs = DAY_START + HOUR_MS
		val endMs = DAY_START + 2L * HOUR_MS
		val changeAtMs = startMs + HOUR_MS / 2L
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = startMs,
			endMs = endMs,
			steps = 5L,
			admissionOrdinal = 10L,
			completenessOrdinal = 11L,
			factEndMs = changeAtMs,
		)
		insertManifestRevision(
			logicalId = logicalId,
			runId = runId,
			revision = 2L,
			effectiveAtMs = changeAtMs,
			sources = listOf(manifestSource(logicalId, revision = 2L)),
		)
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 2L,
				startMs = changeAtMs,
				endMs = changeAtMs,
				steps = 0L,
				coverage = StepFactRevisionEntity.COVERAGE_BASELINE,
				admissionOrdinal = 11L,
				intervalStartElapsedRealtimeNanos = 1L +
					(changeAtMs - startMs) * NANOS_PER_MILLISECOND + 1L,
			),
		)

		composer().composeForNumericRead(mapOf(DAY to ZONE)) shouldBe
			StepsDayRepairPreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)
	}

	@Test
	fun `adjacent Steps manifest slices compose only from facts inside their exact authority`() =
		runTest {
		val logicalId = "manifest-slices-logical"
		val runId = "manifest-slices-run"
		val startMs = DAY_START + HOUR_MS
		val changeAtMs = startMs + HOUR_MS / 2L
		val endMs = DAY_START + 2L * HOUR_MS
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = startMs,
			endMs = endMs,
			steps = 5L,
			admissionOrdinal = 30L,
			completenessOrdinal = 32L,
			factEndMs = changeAtMs,
		)
		insertManifestRevision(
			logicalId = logicalId,
			runId = runId,
			revision = 2L,
			effectiveAtMs = changeAtMs,
			sources = listOf(manifestSource(logicalId, revision = 2L)),
		)
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 2L,
				startMs = changeAtMs,
				endMs = changeAtMs,
				steps = 0L,
				coverage = StepFactRevisionEntity.COVERAGE_BASELINE,
				admissionOrdinal = 31L,
				intervalStartElapsedRealtimeNanos = 1L +
					(changeAtMs - startMs) * NANOS_PER_MILLISECOND + 1L,
			),
		)
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 2L,
				startMs = changeAtMs,
				endMs = endMs,
				steps = 7L,
				coverage = StepFactRevisionEntity.COVERAGE_COVERED,
				admissionOrdinal = 32L,
				intervalStartElapsedRealtimeNanos = 1L +
					(changeAtMs - startMs) * NANOS_PER_MILLISECOND + 1L,
			),
		)

		val ready = composer().composeForNumericRead(mapOf(DAY to ZONE))
			as StepsDayRepairPreflight.Ready
		ready.plans.single().numericSteps shouldBe StepsDayNumericComposition.Complete(12L)
	}

	@Test
	@Suppress("LongMethod")
	fun `backward wall jump keeps elapsed manifest ownership and fact civil allocation exact`() =
		runTest {
		val logicalId = "clock-jump-steps-logical"
		val runId = "clock-jump-steps-run"
		val startMs = DAY_START + 5L * HOUR_MS
		val changeAtMs = startMs + HOUR_MS / 2L
		val jumpedFactStartMs = startMs - HOUR_MS / 2L
		val endMs = startMs + HOUR_MS
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = startMs,
			endMs = endMs,
			steps = 5L,
			admissionOrdinal = 30L,
			completenessOrdinal = 32L,
			factEndMs = changeAtMs,
		)
		insertManifestRevision(
			logicalId = logicalId,
			runId = runId,
			revision = 2L,
			effectiveAtMs = changeAtMs,
			sources = listOf(manifestSource(logicalId, revision = 2L)),
		)
		rewriteManifestVersion(logicalId, runId, manifestRevision = 2L) { manifest ->
			manifest.copy(effectiveWallTimeMs = jumpedFactStartMs)
		}
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 2L,
				startMs = jumpedFactStartMs,
				endMs = jumpedFactStartMs,
				steps = 0L,
				coverage = StepFactRevisionEntity.COVERAGE_BASELINE,
				admissionOrdinal = 31L,
				intervalStartElapsedRealtimeNanos = 1L +
					(changeAtMs - startMs) * NANOS_PER_MILLISECOND + 1L,
			),
		)
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 2L,
				startMs = jumpedFactStartMs,
				endMs = startMs,
				steps = 7L,
				coverage = StepFactRevisionEntity.COVERAGE_COVERED,
				admissionOrdinal = 32L,
				intervalStartElapsedRealtimeNanos = 1L +
					(changeAtMs - startMs) * NANOS_PER_MILLISECOND + 1L,
			),
		)

		val ready = composer().composeForNumericRead(mapOf(DAY to ZONE))
			as StepsDayRepairPreflight.Ready

		ready.plans.single().numericSteps shouldBe StepsDayNumericComposition.Complete(12L)
		ready.plans.single().totals?.steps shouldBe 12
	}

	@Test
	fun `backward wall jump with a non-Steps manifest remains partial instead of zero`() =
		runTest {
		val logicalId = "clock-jump-mixed-logical"
		val runId = "clock-jump-mixed-run"
		val startMs = DAY_START + 8L * HOUR_MS
		val changeAtMs = startMs + HOUR_MS / 2L
		val endMs = startMs + HOUR_MS
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = startMs,
			endMs = endMs,
			steps = 5L,
			factEndMs = changeAtMs,
		)
		insertManifestRevision(
			logicalId = logicalId,
			runId = runId,
			revision = 2L,
			effectiveAtMs = changeAtMs,
			sources = listOf(
				nonStepsCaptureSource(
					logicalId,
					revision = 2L,
					sourceKind = SourceKind.LOCATION.stableCode,
				),
			),
		)
		rewriteManifestVersion(logicalId, runId, manifestRevision = 2L) { manifest ->
			manifest.copy(effectiveWallTimeMs = startMs - HOUR_MS)
		}

		val ready = composer().composeForNumericRead(mapOf(DAY to ZONE))
			as StepsDayRepairPreflight.Ready

		ready.plans.single().totals?.steps shouldBe 5
		ready.plans.single().numericSteps shouldBe StepsDayNumericComposition.PartialCapture
	}

	@Test
	fun `covered fact crossing a manifest authority boundary fails closed`() = runTest {
		val logicalId = "manifest-crossing-logical"
		val runId = "manifest-crossing-run"
		val startMs = DAY_START + HOUR_MS
		val changeAtMs = startMs + HOUR_MS / 2L
		val endMs = DAY_START + 2L * HOUR_MS
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = startMs,
			endMs = endMs,
			steps = 5L,
			admissionOrdinal = 40L,
			completenessOrdinal = 41L,
		)
		insertManifestRevision(
			logicalId = logicalId,
			runId = runId,
			revision = 2L,
			effectiveAtMs = changeAtMs,
			sources = listOf(manifestSource(logicalId, revision = 2L)),
		)
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 2L,
				startMs = changeAtMs,
				endMs = endMs,
				steps = 7L,
				coverage = StepFactRevisionEntity.COVERAGE_COVERED,
				admissionOrdinal = 41L,
				intervalStartElapsedRealtimeNanos = 1L +
					(changeAtMs - startMs) * NANOS_PER_MILLISECOND + 1L,
			),
		)

		assertDayUnverifiableForNumeric()
	}

	@Test
	fun `every Steps registration requires a terminal covered event`() = runTest {
		installLane(cursor = 100L)
		val logicalId = "registration-coverage-logical"
		val runId = "registration-coverage-run"
		val startMs = DAY_START + HOUR_MS
		val endMs = DAY_START + 2L * HOUR_MS
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = startMs,
			endMs = endMs,
			steps = 5L,
			admissionOrdinal = 20L,
		)
		database.sourceSessionDao().saveCompleteness(
			completeness(
				logicalId = logicalId,
				runId = runId,
				admissionOrdinal = null,
				sourceInstanceId = "steps-$runId-restarted",
				registrationGeneration = 2L,
			),
		)
		assertDayUnverifiableForNumeric()

		resetDatabase()
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = startMs,
			endMs = endMs,
			steps = 5L,
			admissionOrdinal = 20L,
		)
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 1L,
				startMs = startMs,
				endMs = endMs,
				steps = 0L,
				coverage = StepFactRevisionEntity.COVERAGE_BASELINE,
				admissionOrdinal = 21L,
			),
		)
		database.sourceSessionDao().saveCompleteness(
			completeness(
				logicalId = logicalId,
				runId = runId,
				admissionOrdinal = 21L,
				sourceInstanceId = "steps-$runId-restarted",
				registrationGeneration = 2L,
			),
		)
		assertDayUnverifiableForNumeric()
	}

	@Test
	fun `duplicate Steps registration generation across source instances is unverifiable`() = runTest {
		installLane(cursor = 100L)
		val logicalId = "duplicate-registration-logical"
		val runId = "duplicate-registration-run"
		val startMs = DAY_START + HOUR_MS
		val midpointMs = startMs + HOUR_MS / 2L
		val endMs = startMs + HOUR_MS
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = startMs,
			endMs = endMs,
			steps = 5L,
			admissionOrdinal = 20L,
			completenessOrdinal = 20L,
			factEndMs = midpointMs,
		)
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 1L,
				startMs = midpointMs,
				endMs = endMs,
				steps = 1L,
				coverage = StepFactRevisionEntity.COVERAGE_COVERED,
				admissionOrdinal = 21L,
				cumulativeStepCountStart = 105L,
				intervalStartElapsedRealtimeNanos = 1L +
					(midpointMs - startMs) * NANOS_PER_MILLISECOND,
			),
		) shouldNotBe -1L
		database.sourceSessionDao().saveCompleteness(
			completeness(
				logicalId = logicalId,
				runId = runId,
				admissionOrdinal = 21L,
				sourceInstanceId = "steps-$runId-duplicate",
				registrationGeneration = 1L,
			),
		)
		val factCount = database.stepFactRevisionDao().countAll()

		assertDayUnverifiableForNumeric()
		database.stepFactRevisionDao().countAll() shouldBe factCount
	}

	@Test
	fun `descending Steps admission high water across registrations is unverifiable`() = runTest {
		installLane(cursor = 100L)
		val logicalId = "descending-high-water-logical"
		val runId = "descending-high-water-run"
		val startMs = DAY_START + HOUR_MS
		val midpointMs = startMs + HOUR_MS / 2L
		val endMs = startMs + HOUR_MS
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = startMs,
			endMs = endMs,
			steps = 5L,
			admissionOrdinal = 20L,
			completenessOrdinal = 20L,
			factStartMs = midpointMs,
		)
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 1L,
				startMs = startMs,
				endMs = midpointMs,
				steps = 0L,
				coverage = StepFactRevisionEntity.COVERAGE_COVERED,
				admissionOrdinal = 19L,
			),
		) shouldNotBe -1L
		database.sourceSessionDao().saveCompleteness(
			completeness(
				logicalId = logicalId,
				runId = runId,
				admissionOrdinal = 19L,
				sourceInstanceId = "steps-$runId-restarted",
				registrationGeneration = 2L,
			),
		)
		val factCount = database.stepFactRevisionDao().countAll()

		assertDayUnverifiableForNumeric()
		database.stepFactRevisionDao().countAll() shouldBe factCount
	}

	@Test
	fun `numeric calendar authority requires one exact contiguous wall-time chain`() {
		val honolulu = ZoneId.of("Pacific/Honolulu")
		val kiritimati = ZoneId.of("Pacific/Kiritimati")
		stepsNumericReadQueryBounds(
			mapOf(DAY to honolulu, DAY + 1L to kiritimati),
		) shouldBe null

		val springDay = LocalDate.of(2026, 3, 28).toEpochDay()
		val bounds = stepsNumericReadQueryBounds(
			mapOf(
				springDay to ZONE,
				springDay + 1L to ZONE,
			),
		)
		bounds?.fromMs shouldBe LocalDate.ofEpochDay(springDay).atStartOfDay(ZONE)
			.toInstant().toEpochMilli()
		bounds?.toMs shouldBe LocalDate.ofEpochDay(springDay + 2L).atStartOfDay(ZONE)
			.toInstant().toEpochMilli()
	}

	@Test
	fun `positive covered fact crossing a structural day is never presented as an exact split`() =
		runTest {
		val springDay = LocalDate.of(2026, 3, 28).toEpochDay()
		val startMs = LocalDate.ofEpochDay(springDay).atTime(23, 0).atZone(ZONE)
			.toInstant().toEpochMilli()
		val endMs = startMs + 4L * HOUR_MS
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "dst-logical",
			runId = "dst-run",
			manifestRevision = 1L,
			startMs = startMs,
			endMs = endMs,
			steps = 17L,
			zoneId = ZONE,
		)

		val ready = composer().composeForNumericRead(
			mapOf(springDay to ZONE, springDay + 1L to ZONE),
		) as StepsDayRepairPreflight.Ready

		ready.plans.sumOf { plan -> plan.totals?.steps ?: 0 } shouldBe 17
		ready.plans.map(StepsDayRepairPlan::numericSteps) shouldBe listOf(
			StepsDayNumericComposition.PartialCapture,
			StepsDayNumericComposition.PartialCapture,
		)
	}

	@Test
	fun `adjacent day-local covered facts preserve an exact total across a DST boundary`() = runTest {
		val springDay = LocalDate.of(2026, 3, 28).toEpochDay()
		val startMs = LocalDate.ofEpochDay(springDay).atTime(23, 0).atZone(ZONE)
			.toInstant().toEpochMilli()
		val dayTwoStartMs = LocalDate.ofEpochDay(springDay + 1L).atStartOfDay(ZONE)
			.toInstant().toEpochMilli()
		val endMs = startMs + 4L * HOUR_MS
		val logicalId = "dst-exact-logical"
		val runId = "dst-exact-run"
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = startMs,
			endMs = endMs,
			steps = 7L,
			zoneId = ZONE,
			admissionOrdinal = 10L,
			completenessOrdinal = 11L,
			factEndMs = dayTwoStartMs,
		)
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 1L,
				startMs = dayTwoStartMs,
				endMs = endMs,
				steps = 10L,
				coverage = StepFactRevisionEntity.COVERAGE_COVERED,
				admissionOrdinal = 11L,
				cumulativeStepCountStart = 107L,
				intervalStartElapsedRealtimeNanos = 1L +
					(dayTwoStartMs - startMs) * NANOS_PER_MILLISECOND,
			),
		)

		val ready = composer().composeForNumericRead(
			mapOf(springDay to ZONE, springDay + 1L to ZONE),
		) as StepsDayRepairPreflight.Ready

		ready.plans.map(StepsDayRepairPlan::numericSteps) shouldBe listOf(
			StepsDayNumericComposition.Complete(7L),
			StepsDayNumericComposition.Complete(10L),
		)
	}

	@Test
	fun `cross-midnight session without day-local covered evidence cannot fabricate day two zero`() =
		runTest {
		val dayTwoStartMs = LocalDate.ofEpochDay(DAY + 1L).atStartOfDay(ZONE)
			.toInstant().toEpochMilli()
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "day-local-logical",
			runId = "day-local-run",
			manifestRevision = 1L,
			startMs = dayTwoStartMs - HOUR_MS,
			endMs = dayTwoStartMs + HOUR_MS,
			steps = 4L,
			factEndMs = dayTwoStartMs,
		)

		val ready = composer().composeForNumericRead(
			mapOf(DAY to ZONE, DAY + 1L to ZONE),
		) as StepsDayRepairPreflight.Ready

		ready.plans.map(StepsDayRepairPlan::numericSteps) shouldBe listOf(
			StepsDayNumericComposition.Complete(4L),
			StepsDayNumericComposition.PartialCapture,
		)
	}

	@Test
	fun `covered zero crossing midnight remains exactly zero in both clipped intervals`() = runTest {
		val dayTwoStartMs = LocalDate.ofEpochDay(DAY + 1L).atStartOfDay(ZONE)
			.toInstant().toEpochMilli()
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "cross-day-zero-logical",
			runId = "cross-day-zero-run",
			manifestRevision = 1L,
			startMs = dayTwoStartMs - HOUR_MS,
			endMs = dayTwoStartMs + HOUR_MS,
			steps = 0L,
		)

		val ready = composer().composeForNumericRead(
			mapOf(DAY to ZONE, DAY + 1L to ZONE),
		) as StepsDayRepairPreflight.Ready

		ready.plans.map(StepsDayRepairPlan::numericSteps) shouldBe listOf(
			StepsDayNumericComposition.Complete(0L),
			StepsDayNumericComposition.Complete(0L),
		)
	}

	@Test
	fun `internal covered interval gap remains partial instead of filling missing time with zero`() =
		runTest {
		val logicalId = "covered-gap-logical"
		val runId = "covered-gap-run"
		val startMs = DAY_START + HOUR_MS
		val endMs = DAY_START + 3L * HOUR_MS
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = startMs,
			endMs = endMs,
			steps = 4L,
			admissionOrdinal = 20L,
			completenessOrdinal = 21L,
			factEndMs = DAY_START + 2L * HOUR_MS,
		)
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 1L,
				startMs = DAY_START + 150L * 60_000L,
				endMs = endMs,
				steps = 3L,
				coverage = StepFactRevisionEntity.COVERAGE_COVERED,
				admissionOrdinal = 21L,
				cumulativeStepCountStart = 104L,
				// Wall time jumped forward by 30 minutes; the monotonic source chain stayed contiguous.
				intervalStartElapsedRealtimeNanos = 1L + HOUR_MS * NANOS_PER_MILLISECOND,
			),
		)

		val ready = composer().composeForNumericRead(mapOf(DAY to ZONE))
			as StepsDayRepairPreflight.Ready
		ready.plans.single().numericSteps shouldBe StepsDayNumericComposition.PartialCapture
	}

	@Test
	fun `numeric query ignores an irrelevant segment outside the exact structural day`() = runTest {
		val utc = ZoneId.of("UTC")
		val utcDayStart = LocalDate.ofEpochDay(DAY).atStartOfDay(utc).toInstant().toEpochMilli()
		database.sessionSegmentDao().insert(
			segment(
				startMs = utcDayStart - HOUR_MS,
				endMs = utcDayStart,
				steps = null,
				sampleCount = 99,
			),
		)

		composer().composeForNumericRead(mapOf(DAY to utc)) shouldBe
			StepsDayRepairPreflight.Ready(
				listOf(
					StepsDayRepairPlan(
						epochDay = DAY,
						zoneId = utc,
						totals = null,
						numericSteps = StepsDayNumericComposition.NotCaptured,
					),
				),
			)
	}

	@Test
	fun `numeric composition rejects cross-logical physical overlap`() = runTest {
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "overlap-one",
			runId = "overlap-run-one",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 3L * HOUR_MS,
			steps = 5L,
		)
		insertCandidate(
			logicalId = "overlap-two",
			runId = "overlap-run-two",
			manifestRevision = 1L,
			startMs = DAY_START + 2L * HOUR_MS,
			endMs = DAY_START + 4L * HOUR_MS,
			steps = 7L,
		)

		assertDayUnverifiableForNumeric()
	}

	@Test
	fun `materializing sibling cannot mask a permanent physical overlap`() = runTest {
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "settled-overlap-logical",
			runId = "settled-overlap-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 3L * HOUR_MS,
			steps = 5L,
		)
		val materializingRun = "materializing-overlap-run"
		insertCandidate(
			logicalId = "materializing-overlap-logical",
			runId = materializingRun,
			manifestRevision = 1L,
			startMs = DAY_START + 2L * HOUR_MS,
			endMs = DAY_START + 4L * HOUR_MS,
			steps = 7L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET presentation_acknowledgement = 'PENDING', " +
				"presentation_acknowledged_at_ms = NULL WHERE service_run_id = ?",
			arrayOf(materializingRun),
		)

		assertDayUnverifiableForNumeric()
	}

	@Test
	fun `materializing replacement cannot mask incompatible immutable zone authority`() = runTest {
		installLane(cursor = 100L)
		val logicalId = "materializing-zone-logical"
		insertCandidate(
			logicalId = logicalId,
			runId = "materializing-zone-ready-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
			logicalStartedAtMs = DAY_START + HOUR_MS,
		)
		val materializingRun = "materializing-zone-pending-run"
		insertCandidate(
			logicalId = logicalId,
			runId = materializingRun,
			manifestRevision = 2L,
			startMs = DAY_START + 2L * HOUR_MS,
			endMs = DAY_START + 3L * HOUR_MS,
			steps = 7L,
			logicalStartedAtMs = DAY_START + HOUR_MS,
			zoneId = ZoneId.of("UTC"),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET presentation_acknowledgement = 'PENDING', " +
				"presentation_acknowledged_at_ms = NULL WHERE service_run_id = ?",
			arrayOf(materializingRun),
		)

		assertDayUnverifiableForNumeric()
	}

	@Test
	fun `permanent unverifiability dominates materializing independent of segment order`() = runTest {
		assertUnverifiableDominatesMaterializing(materializingFirst = true)
		resetDatabase()
		assertUnverifiableDominatesMaterializing(materializingFirst = false)
	}

	@Test
	@Suppress("LongMethod")
	fun `malformed corrected fact dominates an otherwise exact materializing run`() = runTest {
		installLane(cursor = 10L)
		val correctedLogicalId = "stream-corrected-logical"
		val correctedRunId = "stream-corrected-run"
		val correctedStartMs = DAY_START + HOUR_MS
		val correctedEndMs = correctedStartMs + HOUR_MS
		val correctedOrdinal = 10L
		val correctionOrdinal = correctedOrdinal + 1L
		val correctionEventId = "event-$correctedRunId-correction"
		insertCandidate(
			logicalId = correctedLogicalId,
			runId = correctedRunId,
			manifestRevision = 1L,
			startMs = correctedStartMs,
			endMs = correctedEndMs,
			steps = 4L,
			admissionOrdinal = correctedOrdinal,
		)
		val unsignedCorrection = fact(
			logicalId = correctedLogicalId,
			runId = correctedRunId,
			manifestRevision = 1L,
			startMs = correctedStartMs,
			endMs = correctedEndMs,
			steps = 9L,
			coverage = StepFactRevisionEntity.COVERAGE_COVERED,
			admissionOrdinal = correctedOrdinal,
		).copy(
			semanticRevision = 2L,
			mutationId = "${SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID}:" +
				"event-$correctedRunId-$correctedOrdinal:" +
				"2:${StepFactRevisionEntity.OPERATION_UPSERT}",
			sourceEventId = correctionEventId,
			sourceAdmissionOrdinal = correctionOrdinal,
			originIdentity = correctionEventId,
			effectChecksum = "pending-corrected-effect",
			appliedAtMs = correctedEndMs + 1L,
		)
		val corrected = unsignedCorrection.copy(
			effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsignedCorrection),
		)
		database.stepFactRevisionDao().insert(corrected) shouldNotBe -1L
		val completeness = database.sourceSessionDao()
			.completenessForServiceRun(correctedLogicalId, correctedRunId)
			.single()
		database.sourceSessionDao().saveCompleteness(
			completeness.copy(
				lastAdmissionOrdinal = correctionOrdinal,
				lastSourceSequence = 2L,
			),
		)

		val materializingRunId = "stream-materializing-run"
		insertCandidate(
			logicalId = "stream-materializing-logical",
			runId = materializingRunId,
			manifestRevision = 1L,
			startMs = correctedEndMs,
			endMs = correctedEndMs + HOUR_MS,
			steps = 6L,
			admissionOrdinal = 20L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET presentation_acknowledgement = 'PENDING', " +
				"presentation_acknowledged_at_ms = NULL WHERE service_run_id = ?",
			arrayOf(materializingRunId),
		)

		assertDayUnverifiableForNumeric()
	}

	@Test
	fun `production composer pages the full 370-session 370-day numeric envelope`() = runTest {
		val zoneByDay = (0 until StepsNumericSummaryRequest.MAX_DAY_COUNT).associate { offset ->
			(DAY + offset.toLong()) to ZONE
		}
		installLane(cursor = StepsNumericSummaryRequest.MAX_DAY_COUNT.toLong())
		zoneByDay.keys.forEachIndexed { index, epochDay ->
			val startMs = LocalDate.ofEpochDay(epochDay).atStartOfDay(ZONE)
				.toInstant().toEpochMilli() + HOUR_MS
			val logicalId = "paged-envelope-$index-logical"
			val runId = "paged-envelope-$index-run"
			val admissionOrdinal = index.toLong() + 1L
			insertCandidate(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 1L,
				startMs = startMs,
				endMs = startMs + HOUR_MS,
				steps = 1L,
				admissionOrdinal = admissionOrdinal,
				completenessOrdinal = admissionOrdinal,
			)
		}

		val ready = composer().composeForNumericRead(zoneByDay)
			as StepsDayRepairPreflight.Ready

		ready.plans.size shouldBe StepsNumericSummaryRequest.MAX_DAY_COUNT
		ready.plans.map(StepsDayRepairPlan::epochDay) shouldBe zoneByDay.keys.toList()
		ready.plans.all { plan -> plan.numericSteps == StepsDayNumericComposition.Complete(1L) } shouldBe true
		ready.plans.all { plan -> plan.totals?.tripCount == 1 } shouldBe true
		composer().composeForNumericRead(
			zoneByDay + ((DAY + StepsNumericSummaryRequest.MAX_DAY_COUNT) to ZONE),
		) shouldBe StepsDayRepairPreflight.Unsupported(
			StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
		)
	}

	@Test
	fun `fact stream finalizes exact runs across and within 256 row pages`() = runTest {
		val firstLogicalId = "stream-page-a-logical"
		val firstRunId = "stream-page-a-run"
		val firstStartMs = DAY_START + HOUR_MS
		val firstEndMs = firstStartMs + HOUR_MS
		val firstFactEndMs = firstStartMs + HOUR_MS / STREAM_PAGE_FACT_COUNT
		installLane(cursor = 300L)
		insertCandidate(
			logicalId = firstLogicalId,
			runId = firstRunId,
			manifestRevision = 1L,
			startMs = firstStartMs,
			endMs = firstEndMs,
			steps = 1L,
			admissionOrdinal = 1L,
			completenessOrdinal = STREAM_PAGE_FACT_COUNT.toLong(),
			factEndMs = firstFactEndMs,
		)
		for (index in 1 until STREAM_PAGE_FACT_COUNT) {
			val indexLong = index.toLong()
			database.stepFactRevisionDao().insert(
				fact(
					logicalId = firstLogicalId,
					runId = firstRunId,
					manifestRevision = 1L,
					startMs = firstStartMs + HOUR_MS * indexLong / STREAM_PAGE_FACT_COUNT,
					endMs = firstStartMs + HOUR_MS * (indexLong + 1L) / STREAM_PAGE_FACT_COUNT,
					steps = 1L,
					coverage = StepFactRevisionEntity.COVERAGE_COVERED,
					admissionOrdinal = indexLong + 1L,
					cumulativeStepCountStart = 100L + indexLong,
					intervalStartElapsedRealtimeNanos = 1L +
						HOUR_MS * indexLong / STREAM_PAGE_FACT_COUNT * NANOS_PER_MILLISECOND,
				),
			)
		}
		insertCandidate(
			logicalId = "stream-page-b-logical",
			runId = "stream-page-b-run",
			manifestRevision = 1L,
			startMs = firstEndMs,
			endMs = firstEndMs + HOUR_MS,
			steps = 5L,
			admissionOrdinal = STREAM_PAGE_FACT_COUNT + 1L,
			completenessOrdinal = STREAM_PAGE_FACT_COUNT + 1L,
		)

		val ready = composer().composeForNumericRead(mapOf(DAY to ZONE))
			as StepsDayRepairPreflight.Ready

		ready.plans.single().numericSteps shouldBe
			StepsDayNumericComposition.Complete(STREAM_PAGE_FACT_COUNT + 5L)
		ready.plans.single().totals shouldBe DailySummaryTotals(
			distanceM = 20f,
			steps = STREAM_PAGE_FACT_COUNT + 5,
			durationMs = 2L * HOUR_MS,
			tripCount = 2,
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
							numericSteps = StepsDayNumericComposition.Complete(7L),
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
	fun `missing manifest revision across replacement members fails day repair closed`() = runTest {
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "revision-gap-logical",
			runId = "revision-gap-run-a",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 7L,
		)
		insertCandidate(
			logicalId = "revision-gap-logical",
			runId = "revision-gap-run-b",
			manifestRevision = 3L,
			startMs = DAY_START + 2L * HOUR_MS,
			endMs = DAY_START + 3L * HOUR_MS,
			steps = 11L,
		)

		composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE) shouldBe
			StepsDayRepairPreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)
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
	fun `historical lane must authorize the immutable manifest capture mode`() = runTest {
		installLane(
			cursor = 100L,
			captureModeMask = CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE.mask,
		)
		insertCandidate(
			logicalId = "wrong-mode-logical",
			runId = "wrong-mode-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
		)

		assertDayUnverifiableForNumeric()
	}

	@Test
	fun `historical lane activation cannot postdate the captured rollout`() = runTest {
		installLane(cursor = 100L, activatedRolloutRevision = 2L)
		insertCandidate(
			logicalId = "future-lane-logical",
			runId = "future-lane-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
		)

		assertDayUnverifiableForNumeric()
	}

	@Test
	fun `manifest chain is exact to prepared revision and cannot contain gaps`() = runTest {
		installLane(cursor = 100L)
		val mismatchRun = "prepared-mismatch-run"
		insertCandidate(
			logicalId = "prepared-mismatch-logical",
			runId = mismatchRun,
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
		)
		val mismatch = requireNotNull(database.sourceSessionDao().serviceRun(mismatchRun))
		database.sourceSessionDao().updateServiceRun(mismatch.copy(preparedManifestRevision = 2L))
		assertDayUnverifiableForNumeric()

		resetDatabase()
		installLane(cursor = 100L)
		val logicalId = "manifest-gap-logical"
		val runId = "manifest-gap-run"
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
		)
		insertManifestRevision(
			logicalId = logicalId,
			runId = runId,
			revision = 3L,
			effectiveAtMs = DAY_START + HOUR_MS + 1_000L,
			sources = listOf(manifestSource(logicalId, 3L)),
		)
		assertDayUnverifiableForNumeric()
	}

	@Test
	fun `run logical and manifest plan mode and rollout envelopes must agree`() = runTest {
		assertImmutableEnvelopeMutation { _, runId ->
			val run = requireNotNull(database.sourceSessionDao().serviceRun(runId))
			database.sourceSessionDao().updateServiceRun(run.copy(rolloutRevision = 2L))
		}
		resetDatabase()
		assertImmutableEnvelopeMutation { logicalId, _ ->
			val session = requireNotNull(database.sourceSessionDao().session(logicalId))
			database.sourceSessionDao().updateSession(session.copy(sessionMode = "AUTOMATIC"))
		}
		resetDatabase()
		assertImmutableEnvelopeMutation { _, runId ->
			val run = requireNotNull(database.sourceSessionDao().serviceRun(runId))
			database.sourceSessionDao().updateServiceRun(run.copy(desiredPlanRevision = 2L))
		}
	}

	@Test
	fun `first manifest wall origin and elapsed authority are unverifiable`() = runTest {
		assertImmutableEnvelopeMutation { logicalId, runId ->
			rewriteManifestVersion(logicalId, runId) { manifest ->
				manifest.copy(effectiveWallTimeMs = manifest.effectiveWallTimeMs + 1L)
			}
		}
		resetDatabase()
		assertImmutableEnvelopeMutation { logicalId, runId ->
			rewriteManifestVersion(logicalId, runId) { manifest ->
				manifest.copy(startOrigin = POLICY_RECONCILIATION_ORIGIN)
			}
		}
		resetDatabase()
		assertImmutableEnvelopeMutation { logicalId, runId ->
			rewriteManifestVersion(logicalId, runId) { manifest ->
				manifest.copy(
					effectiveElapsedRealtimeNanos = manifest.effectiveElapsedRealtimeNanos + 1L,
				)
			}
		}
	}

	@Test
	fun `successor manifest origin and elapsed ordering are unverifiable`() = runTest {
		installLane(cursor = 100L)
		val logicalId = "successor-timeline-logical"
		val runId = "successor-timeline-run"
		val startMs = DAY_START + HOUR_MS
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = startMs,
			endMs = startMs + HOUR_MS,
			steps = 5L,
		)
		insertManifestRevision(
			logicalId = logicalId,
			runId = runId,
			revision = 2L,
			effectiveAtMs = startMs + 1_000L,
			sources = listOf(manifestSource(logicalId, 2L)),
		)
		val successor = database.sourceSessionDao().manifestsForServiceRun(runId)
			.single { manifest -> manifest.manifestRevision == 2L }

		rewriteManifestVersion(logicalId, runId, manifestRevision = 2L) {
			successor.copy(startOrigin = MANUAL_START_ORIGIN)
		}
		assertDayUnverifiableForNumeric()

		rewriteManifestVersion(logicalId, runId, manifestRevision = 2L) {
			successor.copy(effectiveElapsedRealtimeNanos = 0L)
		}
		assertDayUnverifiableForNumeric()
	}

	@Test
	fun `terminal projection failure dominates an otherwise transient lane cursor`() = runTest {
		installLane(cursor = 1L)
		insertCandidate(
			logicalId = "failed-behind-logical",
			runId = "failed-behind-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
			admissionOrdinal = 3L,
		)
		database.sourceProjectionStateDao().saveFailure(
			SourceProjectionFailureEntity(
				projectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
				projectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
				admissionOrdinal = 3L,
				attemptCount = 1,
				failureCode = "terminal",
				terminal = true,
				lastAttemptAtMs = 3L,
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET presentation_acknowledgement = 'PENDING', " +
				"presentation_acknowledged_at_ms = NULL WHERE service_run_id = ?",
			arrayOf("failed-behind-run"),
		)

		assertDayUnverifiableForNumeric()
	}

	@Test
	fun `terminal run with an incomplete app drain is permanently unverifiable`() = runTest {
		installLane(cursor = 100L)
		val logicalId = "incomplete-drain-logical"
		val runId = "incomplete-drain-run"
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
			admissionOrdinal = 4L,
		)
		database.sourceSessionDao().saveCompleteness(
			completeness(
				logicalId = logicalId,
				runId = runId,
				admissionOrdinal = 4L,
				appDrainComplete = false,
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET presentation_acknowledgement = 'PENDING', " +
				"presentation_acknowledged_at_ms = NULL WHERE service_run_id = ?",
			arrayOf(runId),
		)

		assertDayUnverifiableForNumeric()
	}

	@Test
	fun `presentation pending cannot mask an exact Steps deletion fence`() = runTest {
		installLane(cursor = 100L)
		val logicalId = "pending-fenced-logical"
		val runId = "pending-fenced-run"
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET presentation_acknowledgement = 'PENDING', " +
				"presentation_acknowledged_at_ms = NULL WHERE service_run_id = ?",
			arrayOf(runId),
		)
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				logicalTrackingId = logicalId,
				serviceRunId = runId,
				fenceGeneration = 1L,
				collectedDataEpoch = EPOCH,
				deletedAtMs = DAY_START + 2L * HOUR_MS,
			),
		)

		assertDayUnverifiableForNumeric()
	}

	@Test
	@Suppress("LongMethod")
	fun `bound lifecycle accepts only reachable live and terminal tuples`() = runTest {
		assertReachableBoundLiveLifecycle(logicalState = "ACTIVE", runState = "ACTIVE")

		resetDatabase()
		assertReachableBoundLiveLifecycle(logicalState = "RECONFIGURING", runState = "ACTIVE")

		resetDatabase()
		assertReachableBoundLiveLifecycle(logicalState = "ACTIVE", runState = "STOPPING")

		resetDatabase()
		assertReachableBoundLiveLifecycle(logicalState = "STOPPING", runState = "STOPPING")

		resetDatabase()
		installLane(cursor = 100L)
		val restartGapLogicalId = "restart-gap-logical"
		insertCandidate(
			logicalId = restartGapLogicalId,
			runId = "restart-gap-settled-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
		)
		val restartGap = requireNotNull(database.sourceSessionDao().session(restartGapLogicalId))
		database.sourceSessionDao().updateSession(
			restartGap.copy(
				state = "ACTIVE",
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				completedAtMs = null,
				currentManifestRevision = 1L,
				currentIntentRevision = 2L,
				currentServiceRunId = null,
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = "boot",
			),
		)
		composer().composeForNumericRead(mapOf(DAY to ZONE)) shouldBe
			StepsDayRepairPreflight.Materializing

		resetDatabase()
		assertInvalidBoundLifecycle(
			"UPDATE source_service_run SET state = 'FINALIZED', completed_at_ms = NULL " +
				"WHERE service_run_id = ?",
		)
		resetDatabase()
		assertInvalidBoundLifecycle(
			"UPDATE source_service_run SET state = 'ACTIVE' WHERE service_run_id = ?",
		)
		resetDatabase()
		assertInvalidBoundLifecycle(
			"UPDATE source_service_run SET state = 'STARTING', completed_at_ms = NULL, " +
				"presentation_acknowledgement = 'PENDING', presentation_acknowledged_at_ms = NULL " +
				"WHERE service_run_id = ?",
		)
		resetDatabase()
		assertInvalidBoundLifecycle(
			"UPDATE source_service_run SET state = 'CLOSED' WHERE service_run_id = ?",
		)
	}

	@Test
	fun `current run requires exact manifest intent plan boot and lease authority`() = runTest {
		val mutations = listOf<(LogicalTrackingSessionEntity) -> LogicalTrackingSessionEntity>(
			{ session -> session.copy(currentManifestRevision = 2L) },
			{ session -> session.copy(currentIntentRevision = null) },
			{ session -> session.copy(desiredPlanRevision = 2L) },
			{ session -> session.copy(clockDomainId = "other-clock") },
			{ session -> session.copy(lifecycleBootId = "other-boot") },
			{ session -> session.copy(lifecycleLeaseGeneration = 2L) },
		)
		for ((index, mutation) in mutations.withIndex()) {
			if (index > 0) {
				resetDatabase()
			}
			val logicalId = "current-authority-$index-logical"
			assertReachableBoundLiveLifecycle(
				logicalState = "ACTIVE",
				runState = "ACTIVE",
				logicalId = logicalId,
				runId = "current-authority-$index-run",
				assertMaterializing = false,
			)
			val session = requireNotNull(database.sourceSessionDao().session(logicalId))
			database.sourceSessionDao().updateSession(mutation(session))

			assertDayUnverifiableForNumeric()
		}
	}

	@Test
	@Suppress("LongMethod")
	fun `latest finalized and failed authority require exact completion plan boot lease and intent`() =
		runTest {
			val mutations = listOf<(LogicalTrackingSessionEntity) -> LogicalTrackingSessionEntity>(
				{ session -> session.copy(completedAtMs = requireNotNull(session.completedAtMs) + 1L) },
				{ session -> session.copy(desiredPlanRevision = 2L) },
				{ session -> session.copy(clockDomainId = "other-clock") },
				{ session -> session.copy(lifecycleBootId = "other-boot") },
				{ session -> session.copy(lifecycleLeaseGeneration = 2L) },
				{ session -> session.copy(currentIntentRevision = null) },
			)
			for (outcome in listOf("FINALIZED", "FAILED")) {
				if (outcome == "FAILED") {
					resetDatabase()
				}
				insertLatestTerminalCandidate(outcome, suffix = "exact")
				val exact = composer().composeForNumericRead(mapOf(DAY to ZONE))
					as StepsDayRepairPreflight.Ready
				exact.plans.single().numericSteps shouldBe StepsDayNumericComposition.Complete(5L)
				for ((index, mutation) in mutations.withIndex()) {
					resetDatabase()
					val logicalId = insertLatestTerminalCandidate(outcome, suffix = index.toString())
					val session = requireNotNull(database.sourceSessionDao().session(logicalId))
					database.sourceSessionDao().updateSession(mutation(session))

					assertDayUnverifiableForNumeric()
				}
			}
		}

	@Test
	fun `complete Steps registration requires a nonnegative source sequence high water`() = runTest {
		assertInvalidSourceSequenceHighWater(lastSourceSequence = null)
		resetDatabase()
		assertInvalidSourceSequenceHighWater(lastSourceSequence = -1L)
	}

	@Test
	fun `foreign completeness ownership fails both Steps and non-Steps composition closed`() =
		runTest {
			installLane(cursor = 100L)
			val stepsRun = "foreign-completeness-steps-run"
			insertCandidate(
				logicalId = "foreign-completeness-steps-logical",
				runId = stepsRun,
				manifestRevision = 1L,
				startMs = DAY_START + HOUR_MS,
				endMs = DAY_START + 2L * HOUR_MS,
				steps = 5L,
			)
			database.sourceSessionDao().saveCompleteness(
				completeness(
					logicalId = "foreign-logical",
					runId = stepsRun,
					admissionOrdinal = 5L,
					sourceInstanceId = "foreign-steps",
				),
			)
			assertDayUnverifiableForNumeric()

			resetDatabase()
			val locationRun = "foreign-completeness-location-run"
			insertAttributedSurvivor(
				logicalId = "foreign-completeness-location-logical",
				runId = locationRun,
				sources = listOf(
					nonStepsCaptureSource(
						"foreign-completeness-location-logical",
						SourceKind.LOCATION.stableCode,
					),
				),
				completenessRows = listOf(
					completeness(
						logicalId = "foreign-completeness-location-logical",
						runId = locationRun,
						admissionOrdinal = 7L,
						sourceKind = SourceKind.LOCATION.stableCode,
					),
					completeness(
						logicalId = "foreign-logical",
						runId = locationRun,
						admissionOrdinal = 8L,
						sourceKind = SourceKind.LOCATION.stableCode,
						sourceInstanceId = "foreign-location",
					),
				),
			)
			assertDayUnverifiableForNumeric()
		}

	@Test
	fun `active current run on a retired lane is unverifiable instead of materializing forever`() =
		runTest {
			installLane(
				cursor = 7L,
				captureAdmissionCutoffOrdinal = 7L,
				retentionRequired = false,
				status = SourceProductProjectionLaneEntity.STATUS_RETIRED,
				terminalDisposition =
					SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
				terminalAtMs = 2L,
				installedAtMs = 1L,
				updatedAtMs = 3L,
			)
			val logicalId = "retired-live-logical"
			val runId = "retired-live-run"
			insertCandidate(
				logicalId = logicalId,
				runId = runId,
				manifestRevision = 1L,
				startMs = DAY_START + HOUR_MS,
				endMs = DAY_START + 2L * HOUR_MS,
				steps = 5L,
				admissionOrdinal = 7L,
			)
			deleteCandidateEvidence(runId)
			val session = requireNotNull(database.sourceSessionDao().session(logicalId))
			database.sourceSessionDao().updateSession(
				session.copy(
					state = "ACTIVE",
					cutoffAtMs = null,
					cutoffElapsedNanos = null,
					completedAtMs = null,
					finalAdmissionOrdinal = null,
					currentManifestRevision = 1L,
					currentIntentRevision = 1L,
					currentServiceRunId = runId,
				),
			)
			val run = requireNotNull(database.sourceSessionDao().serviceRun(runId))
			database.sourceSessionDao().updateServiceRun(
				run.copy(
					state = "ACTIVE",
					completedAtMs = null,
					completionReason = null,
					runtimeAcknowledgement = "START_ACCEPTED",
					runtimeFailureCode = null,
					presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
					presentationAcknowledgedAtMs = null,
				),
			)

			assertDayUnverifiableForNumeric()
		}

	@Test
	fun `retired lane requires one exact contained-after-drain terminal shape`() = runTest {
		assertRetiredLane(
			terminalDisposition = SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
			terminalAtMs = 2L,
			installedAtMs = 1L,
			updatedAtMs = 3L,
			expectedReady = true,
		)
		val invalidShapes = listOf(
			RetiredLaneShape("OTHER", 2L, 1L, 3L),
			RetiredLaneShape(null, 2L, 1L, 3L),
			RetiredLaneShape(
				SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
				null,
				1L,
				3L,
			),
			RetiredLaneShape(
				SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
				1L,
				2L,
				3L,
			),
			RetiredLaneShape(
				SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
				3L,
				1L,
				2L,
			),
		)
		for (shape in invalidShapes) {
			resetDatabase()
			assertRetiredLane(
				terminalDisposition = shape.terminalDisposition,
				terminalAtMs = shape.terminalAtMs,
				installedAtMs = shape.installedAtMs,
				updatedAtMs = shape.updatedAtMs,
				expectedReady = false,
			)
		}
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
						numericSteps = StepsDayNumericComposition.NotCaptured,
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
	fun `candidate fact beyond settled completeness fails closed even while lane is behind`() = runTest {
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

		resetDatabase()
		installLane(cursor = 3L)
		insertCandidate(
			logicalId = "unsettled-materializing-fact-logical",
			runId = "unsettled-materializing-fact-run",
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

	@Test
	fun `missing immutable Steps capture authority makes day repair unsupported`() = runTest {
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = "missing-authority-logical",
			runId = "missing-authority-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
		)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_consent_epoch WHERE source_kind = ? AND purpose = ? AND epoch = ?",
			arrayOf<Any>(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				CAPTURE_CONSENT_EPOCH,
			),
		)

		assertDayUnverifiable()
	}

	private suspend fun assertDayUnverifiable() {
		composer().compose(listOf(DAY), excludedSegmentId = Long.MIN_VALUE) shouldBe
			StepsDayRepairPreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)
	}

	private suspend fun assertDayUnverifiableForNumeric() {
		composer().composeForNumericRead(mapOf(DAY to ZONE)) shouldBe
			StepsDayRepairPreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)
	}

	private suspend fun assertUnverifiableDominatesMaterializing(materializingFirst: Boolean) {
		installLane(cursor = 100L)
		val materializingStart = DAY_START + if (materializingFirst) {
			HOUR_MS
		} else {
			3L * HOUR_MS
		}
		val legacyStart = DAY_START + if (materializingFirst) {
			3L * HOUR_MS
		} else {
			HOUR_MS
		}
		val runId = "materializing-order-$materializingFirst"
		insertCandidate(
			logicalId = "materializing-order-$materializingFirst",
			runId = runId,
			manifestRevision = 1L,
			startMs = materializingStart,
			endMs = materializingStart + HOUR_MS,
			steps = 5L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET presentation_acknowledgement = 'PENDING', " +
				"presentation_acknowledged_at_ms = NULL " +
				"WHERE service_run_id = ?",
			arrayOf(runId),
		)
		database.sessionSegmentDao().insert(
			segment(
				startMs = legacyStart,
				endMs = legacyStart + HOUR_MS,
				steps = null,
				sampleCount = 1,
			),
		)

		assertDayUnverifiableForNumeric()
	}

	private suspend fun assertInvalidSourceSequenceHighWater(lastSourceSequence: Long?) {
		installLane(cursor = 100L)
		val suffix = lastSourceSequence?.toString() ?: "null"
		val logicalId = "source-sequence-$suffix-logical"
		val runId = "source-sequence-$suffix-run"
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
			admissionOrdinal = 6L,
		)
		database.sourceSessionDao().saveCompleteness(
			completeness(
				logicalId = logicalId,
				runId = runId,
				admissionOrdinal = 6L,
				lastSourceSequence = lastSourceSequence,
			),
		)
		assertDayUnverifiableForNumeric()
	}

	private suspend fun assertRetiredLane(
		terminalDisposition: String?,
		terminalAtMs: Long?,
		installedAtMs: Long,
		updatedAtMs: Long,
		expectedReady: Boolean,
	) {
		installLane(
			cursor = 7L,
			captureAdmissionCutoffOrdinal = 7L,
			retentionRequired = false,
			status = SourceProductProjectionLaneEntity.STATUS_RETIRED,
			terminalDisposition = terminalDisposition,
			terminalAtMs = terminalAtMs,
			installedAtMs = installedAtMs,
			updatedAtMs = updatedAtMs,
		)
		insertCandidate(
			logicalId = "retired-lane-logical",
			runId = "retired-lane-run",
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
			admissionOrdinal = 7L,
		)

		val result = composer().composeForNumericRead(mapOf(DAY to ZONE))
		if (expectedReady) {
			(result as StepsDayRepairPreflight.Ready).plans.single().numericSteps shouldBe
				StepsDayNumericComposition.Complete(5L)
		} else {
			result shouldBe StepsDayRepairPreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)
		}
	}

	private fun composer() = StepsDailySummaryRepairComposer(database)

	@Suppress("LongMethod")
	private suspend fun insertUnboundRun(
		logicalId: String,
		runId: String,
		logicalState: String,
		runState: String,
		sources: List<SessionManifestSourceEntity>,
		manifestRevision: Long = 1L,
		preparedIntentRevision: Long = 1L,
		startMs: Long = DAY_START + 3L * HOUR_MS,
		insertLogicalSession: Boolean = true,
	) {
		val terminal = runState == "FINALIZED" || runState == "FAILED"
		val completedAtMs = (startMs + HOUR_MS).takeIf { terminal }
		if (insertLogicalSession) {
			database.sourceSessionDao().insertSession(
				LogicalTrackingSessionEntity(
					logicalTrackingId = logicalId,
					state = logicalState,
					lifecycleRevision = 2L,
					desiredPlanRevision = 1L,
					rolloutRevision = 1L,
					startOrigin = MANUAL_START_ORIGIN,
					clockDomainId = "boot",
					startedAtMs = startMs,
					startedElapsedNanos = manifestRevision,
					cutoffAtMs = completedAtMs,
					cutoffElapsedNanos = completedAtMs?.let { manifestRevision + 1L },
					completedAtMs = completedAtMs,
					finalAdmissionOrdinal = null,
					failureCode = "TEST".takeIf { logicalState == "FAILED" },
					sessionMode = "MANUAL",
					currentManifestRevision = manifestRevision,
					currentIntentRevision = if (terminal) {
						preparedIntentRevision + 1L
					} else {
						preparedIntentRevision
					},
					currentServiceRunId = runId.takeUnless { terminal },
					lifecycleLeaseGeneration = 1L,
					lifecycleBootId = "boot",
				),
			)
		}
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = runId,
				logicalTrackingId = logicalId,
				state = runState,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = startMs,
				startedElapsedNanos = manifestRevision,
				completedAtMs = completedAtMs,
				completionReason = "TEST".takeIf { terminal },
				bootId = "boot",
				leaseGeneration = 1L,
				startOrigin = MANUAL_START_ORIGIN,
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = if (terminal) {
					"STOP_ACCEPTED"
				} else {
					"PENDING"
				},
				runtimeFailureCode = null,
				runRevision = 2L,
				startDeliveryToken = "delivery-$runId",
				startCommandGeneration = 1L,
				preparedManifestRevision = manifestRevision,
				preparedIntentRevision = preparedIntentRevision,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = startMs,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = null,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
				presentationAcknowledgedAtMs = null,
			),
		)
		val unsigned = manifest(logicalId, runId, manifestRevision, startMs)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, sources)),
		)
		if (sources.isNotEmpty()) {
			database.sourceSessionDao().insertManifestSources(sources)
		}
	}

	private suspend fun assertImmutableEnvelopeMutation(
		mutation: suspend (logicalId: String, runId: String) -> Unit,
	) {
		installLane(cursor = 100L)
		val logicalId = "immutable-envelope-logical"
		val runId = "immutable-envelope-run"
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
		)
		mutation(logicalId, runId)
		assertDayUnverifiableForNumeric()
	}

	private suspend fun rewriteManifestVersion(
		logicalId: String,
		runId: String,
		manifestRevision: Long = 1L,
		transform: (SessionManifestVersionEntity) -> SessionManifestVersionEntity,
	) {
		val current = database.sourceSessionDao().manifestsForServiceRun(runId)
			.single { manifest -> manifest.manifestRevision == manifestRevision }
		val sources = database.sourceSessionDao().manifestSources(logicalId, manifestRevision)
		val unsigned = transform(current).copy(manifestChecksum = "")
		check(unsigned.logicalTrackingId == current.logicalTrackingId &&
			unsigned.manifestRevision == current.manifestRevision &&
			unsigned.serviceRunId == current.serviceRunId)
		val changed = unsigned.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsigned, sources),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_version SET session_mode = ?, source_policy_revision = ?, " +
				"acquisition_plan_revision = ?, rollout_revision = ?, start_origin = ?, " +
				"effective_boot_id = ?, effective_elapsed_realtime_nanos = ?, " +
				"effective_wall_time_ms = ?, zone_id = ?, automation_epoch = ?, change_reason = ?, " +
				"manifest_checksum = ? WHERE logical_tracking_id = ? AND manifest_revision = ?",
			arrayOf<Any?>(
				changed.sessionMode,
				changed.sourcePolicyRevision,
				changed.acquisitionPlanRevision,
				changed.rolloutRevision,
				changed.startOrigin,
				changed.effectiveBootId,
				changed.effectiveElapsedRealtimeNanos,
				changed.effectiveWallTimeMs,
				changed.zoneId,
				changed.automationEpoch,
				changed.changeReason,
				changed.manifestChecksum,
				changed.logicalTrackingId,
				changed.manifestRevision,
			),
		)
	}

	private suspend fun assertReachableBoundLiveLifecycle(
		logicalState: String,
		runState: String,
		logicalId: String = "live-$logicalState-$runState-logical",
		runId: String = "live-$logicalState-$runState-run",
		assertMaterializing: Boolean = true,
	) {
		installLane(cursor = 100L)
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
		)
		deleteCandidateEvidence(runId)
		val reconfiguring = logicalState == "RECONFIGURING"
		if (reconfiguring) {
			insertManifestRevision(
				logicalId = logicalId,
				runId = runId,
				revision = 2L,
				effectiveAtMs = DAY_START + HOUR_MS + 30L * 60_000L,
				sources = listOf(manifestSource(logicalId, 2L)),
			)
		}
		val session = requireNotNull(database.sourceSessionDao().session(logicalId))
		val stopping = logicalState == "STOPPING"
		val currentIntentRevision = if (reconfiguring || runState == "STOPPING") {
			2L
		} else {
			1L
		}
		database.sourceSessionDao().updateSession(
			session.copy(
				state = logicalState,
				cutoffAtMs = (DAY_START + 2L * HOUR_MS).takeIf { stopping },
				cutoffElapsedNanos = 2L.takeIf { stopping },
				completedAtMs = null,
				currentManifestRevision = if (reconfiguring) {
					2L
				} else {
					1L
				},
				currentIntentRevision = currentIntentRevision,
				currentServiceRunId = runId,
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = "boot",
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET state = ?, completed_at_ms = NULL, " +
				"completion_reason = NULL, presentation_acknowledgement = 'PENDING', " +
				"presentation_acknowledged_at_ms = NULL WHERE service_run_id = ?",
			arrayOf(runState, runId),
		)
		if (assertMaterializing) {
			composer().composeForNumericRead(mapOf(DAY to ZONE)) shouldBe
				StepsDayRepairPreflight.Materializing
		}
	}

	private fun deleteCandidateEvidence(runId: String) {
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM step_fact_revision WHERE service_run_id = ?",
			arrayOf(runId),
		)
			database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_session_completeness WHERE service_run_id = ?",
			arrayOf(runId),
		)
	}

	private suspend fun assertInvalidBoundLifecycle(sql: String) {
		installLane(cursor = 100L)
		val runId = "invalid-lifecycle-run"
		insertCandidate(
			logicalId = "invalid-lifecycle-logical",
			runId = runId,
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
		)
		database.openHelper.writableDatabase.execSQL(sql, arrayOf(runId))
		assertDayUnverifiableForNumeric()
	}

	private suspend fun insertLatestTerminalCandidate(
		outcome: String,
		suffix: String,
	): String {
		installLane(cursor = 100L)
		val logicalId = "latest-$outcome-$suffix-logical"
		val runId = "latest-$outcome-$suffix-run"
		insertCandidate(
			logicalId = logicalId,
			runId = runId,
			manifestRevision = 1L,
			startMs = DAY_START + HOUR_MS,
			endMs = DAY_START + 2L * HOUR_MS,
			steps = 5L,
		)
		if (outcome == "FAILED") {
			val run = requireNotNull(database.sourceSessionDao().serviceRun(runId))
			database.sourceSessionDao().updateServiceRun(
				run.copy(
					state = "FAILED",
					completionReason = "TEST_FAILURE",
					runtimeFailureCode = "TEST_FAILURE",
				),
			)
		}
		val session = requireNotNull(database.sourceSessionDao().session(logicalId))
		database.sourceSessionDao().updateSession(
			session.copy(
				state = outcome,
				failureCode = "TEST_FAILURE".takeIf { outcome == "FAILED" },
			),
		)
		return logicalId
	}

	@Suppress("LongMethod")
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
		manifestEffectiveAtMs: Long = startMs,
		manifestPolicyRevision: Long = SOURCE_POLICY_REVISION,
		bindingConsentEpoch: Long = CAPTURE_CONSENT_EPOCH,
		factPolicyRevision: Long = manifestPolicyRevision,
		factConsentEpoch: Long = bindingConsentEpoch,
		factStartMs: Long = startMs,
		factEndMs: Long = endMs,
		factUncertaintyMs: Long = 0L,
		preparedManifestRevision: Long = manifestRevision,
		segmentStartMs: Long = startMs,
		segmentEndMs: Long = endMs,
		factStartElapsedRealtimeNanos: Long = preparedManifestRevision +
			(factStartMs - startMs) * NANOS_PER_MILLISECOND +
			if (manifestRevision > preparedManifestRevision) {
				1L
			} else {
				0L
			},
		factEndElapsedRealtimeNanos: Long = factStartElapsedRealtimeNanos +
			(factEndMs - factStartMs) * NANOS_PER_MILLISECOND,
	) {
		ensureLogicalSession(logicalId, logicalStartedAtMs, endMs, manifestRevision)
		val segmentId = database.sessionSegmentDao().insert(
			segment(segmentStartMs, segmentEndMs, presentationSteps, sampleCount).copy(
				logicalTrackingId = logicalId,
				serviceRunId = runId,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			serviceRun(
				logicalId = logicalId,
				runId = runId,
				segmentId = segmentId,
				startMs = startMs,
				endMs = endMs,
				preparedManifestRevision = preparedManifestRevision,
				presentationAcknowledgedAtMs = segmentEndMs,
			),
		)
		val source = manifestSource(logicalId, manifestRevision, consentEpoch = bindingConsentEpoch)
		val unsigned = manifest(
			logicalId,
			runId,
			manifestRevision,
			manifestEffectiveAtMs,
			zoneId = zoneId,
			sourcePolicyRevision = manifestPolicyRevision,
			startOrigin = if (manifestRevision == preparedManifestRevision) {
				MANUAL_START_ORIGIN
			} else {
				POLICY_RECONCILIATION_ORIGIN
			},
			changeReason = if (manifestRevision == preparedManifestRevision) {
				"TEST"
			} else {
				POLICY_RECONCILIATION_ORIGIN
			},
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
				startMs = factStartMs,
				endMs = factEndMs,
				steps = steps,
				coverage = coverage,
				admissionOrdinal = admissionOrdinal,
				sourcePolicyRevision = factPolicyRevision,
				captureConsentEpoch = factConsentEpoch,
				wallTimeUncertaintyMs = factUncertaintyMs,
				intervalStartElapsedRealtimeNanos = factStartElapsedRealtimeNanos,
				intervalEndElapsedRealtimeNanos = factEndElapsedRealtimeNanos,
			),
		)
	}

	private suspend fun insertManifestRevision(
		logicalId: String,
		runId: String,
		revision: Long,
		effectiveAtMs: Long,
		sources: List<SessionManifestSourceEntity>,
		zoneId: ZoneId = ZONE,
	) {
		val run = requireNotNull(database.sourceSessionDao().serviceRun(runId))
		val effectiveElapsedRealtimeNanos = run.startedElapsedNanos +
			(effectiveAtMs - run.startedAtMs) * NANOS_PER_MILLISECOND +
			if (effectiveAtMs > run.startedAtMs) {
				1L
			} else {
				0L
			}
		val unsigned = manifest(
			logicalId = logicalId,
			runId = runId,
			revision = revision,
			startMs = effectiveAtMs,
			zoneId = zoneId,
			effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
			startOrigin = if (revision == run.preparedManifestRevision) {
				run.startOrigin
			} else {
				POLICY_RECONCILIATION_ORIGIN
			},
			changeReason = if (revision == run.preparedManifestRevision) {
				"TEST"
			} else {
				POLICY_RECONCILIATION_ORIGIN
			},
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(
				manifestChecksum = SessionManifestIntegrity.compute(unsigned, sources),
			),
		)
		database.sourceSessionDao().insertManifestSources(sources)
		val logicalSession = requireNotNull(database.sourceSessionDao().session(logicalId))
		database.sourceSessionDao().updateSession(
			logicalSession.copy(
				currentManifestRevision = maxOf(
					logicalSession.currentManifestRevision ?: revision,
					revision,
				),
				currentIntentRevision = maxOf(
					logicalSession.currentIntentRevision ?: 1L,
					revision + 1L,
				),
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = "boot",
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
		ensureLogicalSession(logicalId, startMs, endMs, manifestRevision = 1L)
		val segmentId = database.sessionSegmentDao().insert(
			segment(startMs, endMs, steps = null, sampleCount = sampleCount).copy(
				logicalTrackingId = logicalId,
				serviceRunId = runId,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			serviceRun(logicalId, runId, segmentId, startMs, endMs, preparedManifestRevision = 1L),
		)
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
		nonStepsCaptureSource(logicalId, revision = 1L, sourceKind = sourceKind)

	private fun nonStepsCaptureSource(
		logicalId: String,
		revision: Long,
		sourceKind: Int,
	) =
		SessionManifestSourceEntity(
			logicalTrackingId = logicalId,
			manifestRevision = revision,
			sourceKind = sourceKind,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			consentEpoch = 1L,
			persistenceEligible = true,
			qosCode = CAPTURE_QOS_CODE,
		)

	private suspend fun ensureLogicalSession(
		logicalId: String,
		startedAtMs: Long,
		completedAtMs: Long,
		manifestRevision: Long,
	) {
		val existing = database.sourceSessionDao().session(logicalId)
		if (existing != null) {
			database.sourceSessionDao().updateSession(
				existing.copy(
					startedAtMs = minOf(existing.startedAtMs, startedAtMs),
					cutoffAtMs = maxOf(existing.cutoffAtMs ?: completedAtMs, completedAtMs),
					completedAtMs = maxOf(existing.completedAtMs ?: completedAtMs, completedAtMs),
					currentManifestRevision = maxOf(
						existing.currentManifestRevision ?: manifestRevision,
						manifestRevision,
					),
					currentIntentRevision = maxOf(existing.currentIntentRevision ?: 2L, 2L),
					lifecycleLeaseGeneration = 1L,
					lifecycleBootId = "boot",
				),
			)
			return
		}
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = logicalId,
				state = "FINALIZED",
				lifecycleRevision = 2L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = MANUAL_START_ORIGIN,
				clockDomainId = "boot",
				startedAtMs = startedAtMs,
				startedElapsedNanos = 1L,
				cutoffAtMs = completedAtMs,
				cutoffElapsedNanos = 2L,
				completedAtMs = completedAtMs,
				finalAdmissionOrdinal = null,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = manifestRevision,
				currentIntentRevision = 2L,
				currentServiceRunId = null,
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = "boot",
			),
		)
	}

	private suspend fun installLane(
		cursor: Long,
		activatedRolloutRevision: Long = 1L,
		captureModeMask: Long = CaptureReachabilityMode.MANUAL_SESSION_CAPTURE.mask,
		captureAdmissionCutoffOrdinal: Long? = null,
		retentionRequired: Boolean = true,
		status: String = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
		terminalDisposition: String? = null,
		terminalAtMs: Long? = null,
		installedAtMs: Long = 1L,
		updatedAtMs: Long = 2L,
	) {
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				bindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
				projectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
				projectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
				captureModeMask = captureModeMask,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = activatedRolloutRevision,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = cursor,
				captureAdmissionCutoffOrdinal = captureAdmissionCutoffOrdinal,
				retentionRequired = retentionRequired,
				status = status,
				terminalDisposition = terminalDisposition,
				terminalAtMs = terminalAtMs,
				installedAtMs = installedAtMs,
				updatedAtMs = updatedAtMs,
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

	private fun serviceRun(
		logicalId: String,
		runId: String,
		segmentId: Long,
		startMs: Long,
		endMs: Long,
		preparedManifestRevision: Long,
		presentationAcknowledgedAtMs: Long = endMs,
	) =
		SourceServiceRunEntity(
			serviceRunId = runId,
			logicalTrackingId = logicalId,
			state = "FINALIZED",
			desiredPlanRevision = 1L,
			rolloutRevision = 1L,
			foregroundCapabilityFlags = 0L,
			startedAtMs = startMs,
			startedElapsedNanos = preparedManifestRevision,
			completedAtMs = endMs,
			completionReason = "USER_STOP",
			bootId = "boot",
			leaseGeneration = 1L,
			startOrigin = MANUAL_START_ORIGIN,
			desiredForegroundCapabilityFlags = 0L,
			appliedForegroundCapabilityFlags = 0L,
			runtimeAcknowledgement = "STOP_ACCEPTED",
			runtimeFailureCode = null,
			runRevision = 2L,
			startDeliveryToken = "delivery-$runId",
			startCommandGeneration = 1L,
			preparedManifestRevision = preparedManifestRevision,
			preparedIntentRevision = 1L,
			androidDeliveryState = "FOREGROUND_ACCEPTED",
			androidDeliveryUpdatedAtMs = endMs,
			startIsUserInitiated = true,
			startIsAmbient = false,
			sessionSegmentId = segmentId,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = presentationAcknowledgedAtMs,
		)

	private fun manifest(
		logicalId: String,
		runId: String,
		revision: Long,
		startMs: Long,
		zoneId: ZoneId = ZONE,
		sourcePolicyRevision: Long = SOURCE_POLICY_REVISION,
		effectiveElapsedRealtimeNanos: Long = revision,
		startOrigin: String = MANUAL_START_ORIGIN,
		changeReason: String = "TEST",
	) =
		SessionManifestVersionEntity(
			logicalTrackingId = logicalId,
			manifestRevision = revision,
			serviceRunId = runId,
			sessionMode = "MANUAL",
			sourcePolicyRevision = sourcePolicyRevision,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = startOrigin,
			effectiveBootId = "boot",
			effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = startMs,
			zoneId = zoneId.id,
			automationEpoch = null,
			changeReason = changeReason,
			manifestChecksum = "",
		)

	private fun manifestSource(
		logicalId: String,
		revision: Long,
		consentEpoch: Long = CAPTURE_CONSENT_EPOCH,
	) = SessionManifestSourceEntity(
		logicalTrackingId = logicalId,
		manifestRevision = revision,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		consentEpoch = consentEpoch,
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
		effectiveElapsedRealtimeNanos = 1L,
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
		effectiveElapsedRealtimeNanos = 1L,
		effectiveWallTimeMs = DAY_START,
		changeReason = "TEST",
	)

	private fun completeness(
		logicalId: String,
		runId: String,
		admissionOrdinal: Long?,
		sourceKind: Int = SourceDestinationOwnerEntity.SOURCE_STEPS,
		sourceInstanceId: String = "steps-$runId",
		registrationGeneration: Long = 1L,
		appDrainComplete: Boolean = true,
		lastSourceSequence: Long? = admissionOrdinal,
		unresolvedSequenceStart: Long? = null,
		unresolvedSequenceEnd: Long? = null,
	) =
		SourceSessionCompletenessEntity(
			logicalTrackingId = logicalId,
			serviceRunId = runId,
			sourceKind = sourceKind,
			sourceInstanceId = sourceInstanceId,
			registrationGeneration = registrationGeneration,
			lastAdmissionOrdinal = admissionOrdinal,
			lastSourceSequence = lastSourceSequence,
			appDrainComplete = appDrainComplete,
			providerCoverage = "CALLBACKS_ENTERED_BEFORE_BARRIER",
			stopStatus = "COMPLETE",
			unresolvedSequenceStart = unresolvedSequenceStart,
			unresolvedSequenceEnd = unresolvedSequenceEnd,
			updatedAtMs = 2L,
		)

	private data class RetiredLaneShape(
		val terminalDisposition: String?,
		val terminalAtMs: Long?,
		val installedAtMs: Long,
		val updatedAtMs: Long,
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
		sourcePolicyRevision: Long = SOURCE_POLICY_REVISION,
		captureConsentEpoch: Long = CAPTURE_CONSENT_EPOCH,
		wallTimeUncertaintyMs: Long = 0L,
		cumulativeStepCountStart: Long = 100L,
		intervalStartElapsedRealtimeNanos: Long = manifestRevision,
		intervalEndElapsedRealtimeNanos: Long = intervalStartElapsedRealtimeNanos +
			(endMs - startMs) * NANOS_PER_MILLISECOND,
	): StepFactRevisionEntity {
		val sourceEventId = "event-$runId-$admissionOrdinal"
		val logicalFactId =
			"${SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID}:$sourceEventId"
		val unsigned = StepFactRevisionEntity(
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			mutationId = "$logicalFactId:1:${StepFactRevisionEntity.OPERATION_UPSERT}",
			stepIntervalId = null,
			sourceEventId = sourceEventId,
			sourceAdmissionOrdinal = admissionOrdinal,
			originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
			originIdentity = sourceEventId,
			writerProjectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
			operation = StepFactRevisionEntity.OPERATION_UPSERT,
			intervalStartTimeMs = startMs,
			intervalEndTimeMs = endMs,
			intervalStartElapsedRealtimeNanos = intervalStartElapsedRealtimeNanos,
			intervalEndElapsedRealtimeNanos = intervalEndElapsedRealtimeNanos,
			clockDomainId = "boot",
			bootClockDomainId = "boot",
			cumulativeStepCountStart = cumulativeStepCountStart,
			cumulativeStepCountEnd = when (coverage) {
				StepFactRevisionEntity.COVERAGE_RESET_GAP -> cumulativeStepCountStart - 1L
				else -> cumulativeStepCountStart + steps
			},
			wallTimeUncertaintyMs = wallTimeUncertaintyMs,
			coverageKind = coverage,
			effectiveStepCount = when (coverage) {
				StepFactRevisionEntity.COVERAGE_BASELINE,
				StepFactRevisionEntity.COVERAGE_RESET_GAP,
				StepFactRevisionEntity.COVERAGE_PARTIAL,
				-> 0L
				else -> steps
			},
			logicalTrackingId = logicalId,
			serviceRunId = runId,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = manifestRevision,
			sourcePolicyRevision = sourcePolicyRevision,
			captureConsentEpoch = captureConsentEpoch,
			collectedDataEpoch = EPOCH,
			scopeDeletionGeneration = 0L,
			effectChecksum = "pending-test-effect",
			appliedAtMs = endMs,
		)
		return unsigned.copy(
			effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsigned),
		)
	}

	private suspend fun resignLiveWalFact(logicalId: String, runId: String) {
		val fact = database.stepFactRevisionDao()
			.upsertsForServiceRun(logicalId, runId)
			.single()
		val checksum = StepFactRevisionIntegrity.liveWalEffectChecksum(fact)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET effect_checksum = ? WHERE service_run_id = ?",
			arrayOf(checksum, runId),
		)
		val resigned = database.stepFactRevisionDao()
			.upsertsForServiceRun(logicalId, runId)
			.single()
		StepFactRevisionIntegrity.hasValidLiveWalEffectChecksum(resigned) shouldBe true
	}

	private companion object {
		val ZONE: ZoneId = ZoneId.of("Europe/Prague")
		val DAY: Long = LocalDate.of(2026, 4, 2).toEpochDay()
		val DAY_START: Long = LocalDate.ofEpochDay(DAY).atStartOfDay(ZONE).toInstant().toEpochMilli()
		const val HOUR_MS = 60L * 60_000L
		const val STREAM_PAGE_FACT_COUNT = 257
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val SOURCE_POLICY_REVISION = 1L
		const val CAPTURE_CONSENT_EPOCH = 1L
		const val CAPTURE_QOS_CODE = 1
		const val MANUAL_START_ORIGIN = "MANUAL_FOREGROUND_START"
		const val POLICY_RECONCILIATION_ORIGIN = "POLICY_RECONCILIATION"
		const val EPOCH = 2L
	}
}
