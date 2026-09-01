package com.adsamcik.tracker.tracker.source.deletion

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryAggregator
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SkiRunSegment
import com.adsamcik.tracker.shared.base.database.data.SkiSegmentType
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionResult
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionRetryableReason
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionUnsupportedReason
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactDrainResult
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactProjectionLane
import com.adsamcik.tracker.tracker.worker.DailySummaryMaterializationOutcome
import com.adsamcik.tracker.tracker.worker.materializeDailySummaryDayInTransaction
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass") // One Room fixture keeps atomic deletion and recovery assertions coherent.
class RoomStepsSelectedSessionDeletionServiceTest {
	private lateinit var database: AppDatabase
	private lateinit var dirtyTracker: MetricDirtyTracker
	private var drainRequests: Int = 0

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dirtyTracker = mockk(relaxed = true)
		drainRequests = 0
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = COLLECTED_DATA_EPOCH),
		)
		database.sourceDestinationOwnerDao().insertIfAbsent(candidateOwner())
	}

	@After
	fun tearDown() = database.close()

	@Test
	@Suppress("LongMethod") // One transaction scenario keeps commit, replay, and isolation evidence together.
	fun `exact candidate Steps deletion is atomic idempotent and rejects delayed WAL resurrection`() =
		runTest {
			val day = LocalDate.of(2026, 4, 2).toEpochDay()
			val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(ZONE).toInstant().toEpochMilli()
			val selectedId = insertTerminalStepsSession(
				startMs = dayStart + 12 * HOUR_MS,
				endMs = dayStart + 13 * HOUR_MS,
				steps = 12,
				insertFact = false,
			)
			val unrelatedId = database.sessionSegmentDao().insert(
				segment(
					startMs = dayStart + 14 * HOUR_MS,
					endMs = dayStart + 15 * HOUR_MS,
					steps = 8,
				).copy(
					logicalTrackingId = "logical-location-survivor",
					serviceRunId = "run-location-survivor",
				),
			)
			attributeLocationSurvivor(
				segmentId = unrelatedId,
				logicalId = "logical-location-survivor",
				runId = "run-location-survivor",
				startMs = dayStart + 14 * HOUR_MS,
				endMs = dayStart + 15 * HOUR_MS,
			)
			val selectedFact = stepFact(
				logicalFactId = SELECTED_FACT_ID,
				admissionOrdinal = 1L,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = SERVICE_RUN_ID,
				stepCount = 12L,
				startMs = dayStart + 12 * HOUR_MS,
				endMs = dayStart + 13 * HOUR_MS,
			)
			val unrelatedFact = stepFact(
				logicalFactId = "steps-session-facts:unrelated",
				admissionOrdinal = 2L,
				logicalTrackingId = "logical-unrelated",
				serviceRunId = "run-unrelated",
				stepCount = 8L,
			)
			database.stepFactRevisionDao().insert(selectedFact) shouldBe 1L
			database.stepFactRevisionDao().insert(unrelatedFact) shouldBe 2L
			DailySummaryAggregator(
				database.dailySummaryDao(),
				database.sessionSegmentDao(),
				zoneId = ZONE,
			).materializeDayFromSegments(day)
			database.dailySummaryDao().getByDay(day)?.totalSteps shouldBe 20
			database.openHelper.writableDatabase.execSQL(
				"UPDATE daily_summary SET active_tracking_ms = 60000 WHERE date_epoch_day = ?",
				arrayOf(day),
			)
			val subject = subject()

			subject.deleteSelectedSession(selectedId) shouldBe StepsSessionDeletionResult.Deleted

			database.sessionSegmentDao().getById(selectedId) shouldBe null
			database.sessionSegmentDao().getById(unrelatedId).shouldNotBeNull().steps shouldBe 8
			database.sourceDeletionFenceDao().countAll() shouldBe 1L
			val selectedRevisions = database.stepFactRevisionDao().revisions(
				SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
				SELECTED_FACT_ID,
			)
			selectedRevisions.shouldHaveSize(1)
			selectedRevisions.single().also { retraction ->
				retraction.operation shouldBe StepFactRevisionEntity.OPERATION_RETRACT
				retraction.logicalTrackingId shouldBe null
				retraction.serviceRunId shouldBe null
				retraction.effectiveStepCount shouldBe null
				retraction.scopeDeletionGeneration shouldBe 1L
			}
			database.stepFactRevisionDao().revision(
				unrelatedFact.writerProjectionId,
				unrelatedFact.writerProjectionVersion,
				unrelatedFact.logicalFactId,
				unrelatedFact.semanticRevision,
			) shouldBe unrelatedFact
			database.dailySummaryDao().getByDay(day).shouldNotBeNull().also { summary ->
				summary.totalSteps shouldBe 0
				summary.activeTrackingMs shouldBe 60_000L
				summary.calendarZoneId shouldBe ZONE.id
			}
			database.sourceEvidenceStateDao().get()?.revision shouldBe 1L
			verify(exactly = 1) {
				dirtyTracker.markDirty(
					setOf(MetricKeys.TABLE_SESSION_SEGMENT, MetricKeys.TABLE_DAILY_SUMMARY),
				)
			}
			drainRequests shouldBe 1

			subject.deleteSelectedSession(selectedId) shouldBe StepsSessionDeletionResult.NotFound
			database.sourceDeletionFenceDao().countAll() shouldBe 1L
			verify(exactly = 1) { dirtyTracker.markDirty(any<Set<String>>()) }
			drainRequests shouldBe 1

			installCanonicalLane()
			val lateIngress = mockk<DurableSourceIngress>()
			coEvery {
				lateIngress.committedSourceBatch(SourceKind.STEPS, 0L, 3L, 64)
			} returns listOf(lateStepEvent(3L))
			StepsSessionFactProjectionLane(database, lateIngress).drainThrough(3L) shouldBe
				StepsSessionFactDrainResult.Complete(
					lastCompletedOrdinal = 3L,
					factsInserted = 0,
					eventsValidated = 1,
				)
			database.stepFactRevisionDao().countAll() shouldBe 2L
		}

	@Test
	@Suppress("LongMethod")
	fun `production rematerialization preserves authoritative replacement facts after deletion`() =
		runTest {
			val day = LocalDate.of(2026, 4, 2).toEpochDay()
			val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(ZONE).toInstant().toEpochMilli()
			val selectedId = insertTerminalStepsSession(
				startMs = dayStart + HOUR_MS,
				endMs = dayStart + 2L * HOUR_MS,
				steps = 4,
			)
			val replacementLogicalId = "logical-production-replacement"
			database.sourceSessionDao().insertSession(
				logicalSession(state = "FINALIZED", currentServiceRunId = null).copy(
					logicalTrackingId = replacementLogicalId,
					startedAtMs = dayStart + 3L * HOUR_MS,
					cutoffAtMs = dayStart + 6L * HOUR_MS,
					completedAtMs = dayStart + 6L * HOUR_MS,
					currentManifestRevision = 2L,
				),
			)
			insertAttributedCandidateSurvivor(
				logicalId = replacementLogicalId,
				runId = "run-production-replacement-a",
				startMs = dayStart + 3L * HOUR_MS,
				endMs = dayStart + 4L * HOUR_MS,
				manifestRevision = 1L,
				admissionOrdinal = 2L,
				stepCount = 5L,
				segmentSteps = 500,
				sampleCount = 1,
				insertLogicalSession = false,
				logicalStartedAtMs = dayStart + 3L * HOUR_MS,
			)
			insertAttributedCandidateSurvivor(
				logicalId = replacementLogicalId,
				runId = "run-production-replacement-b",
				startMs = dayStart + 5L * HOUR_MS,
				endMs = dayStart + 6L * HOUR_MS,
				manifestRevision = 2L,
				admissionOrdinal = 3L,
				stepCount = 7L,
				segmentSteps = 700,
				sampleCount = 1,
				insertLogicalSession = false,
				logicalStartedAtMs = dayStart + 3L * HOUR_MS,
			)
			installCanonicalLane(contiguousAdmissionOrdinal = 3L)
			DailySummaryAggregator(
				database.dailySummaryDao(),
				database.sessionSegmentDao(),
				zoneId = ZONE,
			).materializeDayFromSegments(day)
			database.dailySummaryDao().getByDay(day).shouldNotBeNull().also { stale ->
				stale.totalSteps shouldBe 1_204
				stale.tripCount shouldBe 3
			}

			subject().deleteSelectedSession(selectedId) shouldBe StepsSessionDeletionResult.Deleted
			database.dailySummaryDao().getByDay(day).shouldNotBeNull().also { repaired ->
				repaired.totalSteps shouldBe 12
				repaired.tripCount shouldBe 1
				repaired.calendarZoneId shouldBe ZONE.id
			}

			val recapturedZone = ZoneId.of("Pacific/Honolulu")
			val outcome = materializeDailySummaryDayInTransaction(
				database = database,
				aggregator = DailySummaryAggregator(
					database.dailySummaryDao(),
					database.sessionSegmentDao(),
					zoneId = recapturedZone,
				),
				epochDay = day,
				capturedZoneId = recapturedZone,
			)

			outcome shouldBe DailySummaryMaterializationOutcome.Ready
			database.dailySummaryDao().getByDay(day).shouldNotBeNull().also { stable ->
				stable.totalSteps shouldBe 12
				stable.tripCount shouldBe 1
				stable.calendarZoneId shouldBe ZONE.id
			}
		}

	@Test
	fun `production materializer leaves summary unchanged for terminal pending attribution`() = runTest {
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(ZONE).toInstant().toEpochMilli()
		insertAttributedCandidateSurvivor(
			logicalId = "logical-worker-pending",
			runId = "run-worker-pending",
			startMs = dayStart + HOUR_MS,
			endMs = dayStart + 2L * HOUR_MS,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			segmentSteps = 900,
			sampleCount = 1,
		)
		database.dailySummaryDao().upsert(
			dateEpochDay = day,
			totalDistanceM = 123f,
			totalSteps = 123,
			totalDurationMs = 123L,
			tripCount = 123,
			activeTrackingMs = 123L,
			lastUpdatedMs = 123L,
			calendarZoneId = ZONE.id,
		)
		val before = database.dailySummaryDao().getByDay(day)

		val outcome = materializeDailySummaryDayInTransaction(
			database = database,
			aggregator = DailySummaryAggregator(
				database.dailySummaryDao(),
				database.sessionSegmentDao(),
				zoneId = ZONE,
			),
			epochDay = day,
			capturedZoneId = ZONE,
		)

		outcome shouldBe DailySummaryMaterializationOutcome.Materializing
		database.dailySummaryDao().getByDay(day) shouldBe before
	}

	@Test
	fun `production materializer upgrades null-zone pure legacy row under one captured zone`() = runTest {
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(ZONE).toInstant().toEpochMilli()
		database.sessionSegmentDao().insert(
			segment(dayStart + HOUR_MS, dayStart + 2L * HOUR_MS, steps = 31),
		)
		database.dailySummaryDao().upsert(
			dateEpochDay = day,
			totalDistanceM = 9f,
			totalSteps = 9,
			totalDurationMs = 9L,
			tripCount = 9,
			activeTrackingMs = 45L,
			lastUpdatedMs = 9L,
			calendarZoneId = ZONE.id,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE daily_summary SET calendar_zone_id = NULL WHERE date_epoch_day = ?",
			arrayOf(day),
		)

		val outcome = materializeDailySummaryDayInTransaction(
			database = database,
			aggregator = DailySummaryAggregator(
				database.dailySummaryDao(),
				database.sessionSegmentDao(),
				zoneId = ZONE,
			),
			epochDay = day,
			capturedZoneId = ZONE,
		)

		outcome shouldBe DailySummaryMaterializationOutcome.Ready
		database.dailySummaryDao().getByDay(day).shouldNotBeNull().also { summary ->
			summary.totalSteps shouldBe 31
			summary.tripCount shouldBe 1
			summary.activeTrackingMs shouldBe 45L
			summary.calendarZoneId shouldBe ZONE.id
		}
	}

	@Test
	fun `production materializer never upgrades null-zone mixed attribution with generic totals`() = runTest {
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(ZONE).toInstant().toEpochMilli()
		database.sessionSegmentDao().insert(
			segment(dayStart + HOUR_MS, dayStart + 2L * HOUR_MS, steps = 400),
		)
		database.sessionSegmentDao().insert(
			segment(dayStart + 3L * HOUR_MS, dayStart + 4L * HOUR_MS, steps = 500).copy(
				logicalTrackingId = "partial-logical",
				serviceRunId = null,
			),
		)
		database.dailySummaryDao().upsert(
			dateEpochDay = day,
			totalDistanceM = 77f,
			totalSteps = 77,
			totalDurationMs = 77L,
			tripCount = 77,
			activeTrackingMs = 77L,
			lastUpdatedMs = 77L,
			calendarZoneId = ZONE.id,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE daily_summary SET calendar_zone_id = NULL WHERE date_epoch_day = ?",
			arrayOf(day),
		)
		val before = database.dailySummaryDao().getByDay(day)

		val outcome = materializeDailySummaryDayInTransaction(
			database = database,
			aggregator = DailySummaryAggregator(
				database.dailySummaryDao(),
				database.sessionSegmentDao(),
				zoneId = ZONE,
			),
			epochDay = day,
			capturedZoneId = ZONE,
		)

		outcome shouldBe DailySummaryMaterializationOutcome.Unverifiable
		database.dailySummaryDao().getByDay(day) shouldBe before
	}

	@Test
	fun `production materializer never invents zone authority for attributable null-zone row`() = runTest {
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(ZONE).toInstant().toEpochMilli()
		insertAttributedCandidateSurvivor(
			logicalId = "logical-null-zone-attributed",
			runId = "run-null-zone-attributed",
			startMs = dayStart + 12L * HOUR_MS,
			endMs = dayStart + 13L * HOUR_MS,
			stepCount = 5L,
			segmentSteps = 900,
		)
		installCanonicalLane(contiguousAdmissionOrdinal = 2L)
		database.dailySummaryDao().upsert(
			dateEpochDay = day,
			totalDistanceM = 77f,
			totalSteps = 77,
			totalDurationMs = 77L,
			tripCount = 77,
			activeTrackingMs = 77L,
			lastUpdatedMs = 77L,
			calendarZoneId = ZONE.id,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE daily_summary SET calendar_zone_id = NULL WHERE date_epoch_day = ?",
			arrayOf(day),
		)
		val before = database.dailySummaryDao().getByDay(day)
		val capturedZone = ZoneId.of("Pacific/Honolulu")

		val outcome = materializeDailySummaryDayInTransaction(
			database = database,
			aggregator = DailySummaryAggregator(
				database.dailySummaryDao(),
				database.sessionSegmentDao(),
				zoneId = capturedZone,
			),
			epochDay = day,
			capturedZoneId = capturedZone,
		)

		outcome shouldBe DailySummaryMaterializationOutcome.Unverifiable
		database.dailySummaryDao().getByDay(day) shouldBe before
	}

	@Test
	fun `QUIESCED never authorizes deletion of an active exact run`() = runTest {
		val segmentId = insertActiveStepsSessionWithQuiescedPresentation()
		val subject = subject()

		subject.deleteSelectedSession(segmentId) shouldBe StepsSessionDeletionResult.BlockedActive

		database.sessionSegmentDao().getById(segmentId).shouldNotBeNull()
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
	}

	@Test
	fun `terminal presentation pending blocks deletion until delayed Ski flush is exactly settled`() =
		runTest {
			val selectedId = insertTerminalStepsSession(
				startMs = 1_000L,
				endMs = 2_000L,
				steps = 5,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			)
			val subject = subject()

			subject.deleteSelectedSession(selectedId) shouldBe StepsSessionDeletionResult.BlockedActive
			database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
			database.stepFactRevisionDao().countAll() shouldBe 1L
			database.sourceDeletionFenceDao().countAll() shouldBe 0L
			database.skiRunSegmentDao().insert(skiSegment(selectedId)) shouldBe 1L

			database.sourceSessionDao().acknowledgePresentationQuiescedExact(
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = SERVICE_RUN_ID,
				sessionSegmentId = selectedId,
				acknowledgedAtMs = 2_001L,
			) shouldBe 1
			subject.deleteSelectedSession(selectedId) shouldBe StepsSessionDeletionResult.Deleted

			database.sessionSegmentDao().getById(selectedId) shouldBe null
			database.skiRunSegmentDao().hasSkiSegments(selectedId) shouldBe false
			database.sourceDeletionFenceDao().countAll() shouldBe 1L
	}

	@Test
	fun `legacy and mismatched reverse bindings fail closed without mutation`() = runTest {
		val legacyId = database.sessionSegmentDao().insert(
			segment(startMs = 1_000L, endMs = 2_000L, steps = 3),
		)
		val mismatchedId = insertTerminalStepsSession(
			startMs = 3_000L,
			endMs = 4_000L,
			steps = 4,
			boundSegmentIdOverride = 999L,
		)
		val subject = subject()

		subject.deleteSelectedSession(legacyId) shouldBe
			StepsSessionDeletionResult.LegacyUnverifiable
		subject.deleteSelectedSession(mismatchedId) shouldBe
			StepsSessionDeletionResult.UnsupportedScope(
				StepsSessionDeletionUnsupportedReason.SERVICE_RUN_BINDING_MISMATCH,
			)

		database.sessionSegmentDao().getById(legacyId).shouldNotBeNull()
		database.sessionSegmentDao().getById(mismatchedId).shouldNotBeNull()
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `selected manifest cap plus one fails before source or payload materialization`() = runTest {
		val selectedId = insertTerminalStepsSession(1_000L, 2_000L, steps = 4)
		for (revision in 2L..257L) {
			database.sourceSessionDao().insertManifest(
				unsignedManifest().copy(
					manifestRevision = revision,
					effectiveElapsedRealtimeNanos = revision,
					effectiveWallTimeMs = revision,
					manifestChecksum = "bounded-manifest-$revision",
				),
			)
		}

		subject().deleteSelectedSession(selectedId) shouldBe
			StepsSessionDeletionResult.UnsupportedScope(
				StepsSessionDeletionUnsupportedReason.MANIFEST_INTEGRITY_FAILED,
			)

		database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
		database.stepFactRevisionDao().countAll() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `cross-midnight deletion repairs every affected day under one explicit zone`() = runTest {
		val dayA = LocalDate.of(2026, 3, 7)
		val dayB = dayA.plusDays(1)
		val midnight = dayB.atStartOfDay(ZONE).toInstant().toEpochMilli()
		val selectedId = insertTerminalStepsSession(
			startMs = midnight - 10 * MINUTE_MS,
			endMs = midnight + 10 * MINUTE_MS,
			steps = 20,
		)
		val aggregator = DailySummaryAggregator(
			database.dailySummaryDao(),
			database.sessionSegmentDao(),
			zoneId = ZONE,
		)
		aggregator.materializeDayFromSegments(dayA.toEpochDay())
		aggregator.materializeDayFromSegments(dayB.toEpochDay())
		database.dailySummaryDao().getByDay(dayA.toEpochDay()).shouldNotBeNull()
		database.dailySummaryDao().getByDay(dayB.toEpochDay()).shouldNotBeNull()

		subject().deleteSelectedSession(selectedId) shouldBe StepsSessionDeletionResult.Deleted

		database.dailySummaryDao().getByDay(dayA.toEpochDay()) shouldBe null
		database.dailySummaryDao().getByDay(dayB.toEpochDay()) shouldBe null
	}

	@Test
	fun `production current-zone materialization is removed for a different manifest zone`() =
		runTest {
			val manifestZone = ZoneId.of("Pacific/Kiritimati")
			val deviceZone = ZoneId.of("Pacific/Honolulu")
			val manifestDay = LocalDate.of(2026, 4, 2)
			val startMs = manifestDay.atTime(0, 30).atZone(manifestZone).toInstant().toEpochMilli()
			val deviceDay = java.time.Instant.ofEpochMilli(startMs)
				.atZone(deviceZone)
				.toLocalDate()
				.toEpochDay()
			deviceDay shouldBe manifestDay.minusDays(1L).toEpochDay()
			val selectedId = insertTerminalStepsSession(
				startMs = startMs,
				endMs = startMs + HOUR_MS,
				steps = 9,
				zoneId = manifestZone,
			)
			val originalTimeZone = TimeZone.getDefault()
			try {
				TimeZone.setDefault(TimeZone.getTimeZone(deviceZone))
				// Mirrors the Orchestrator writer: the aggregator captures one default ZoneId and
				// uses it for both the day bounds and the persisted row authority.
				DailySummaryAggregator(
					database.dailySummaryDao(),
					database.sessionSegmentDao(),
				).materializeDayFromSegments(deviceDay)
				database.dailySummaryDao().getByDay(deviceDay).shouldNotBeNull()
					.calendarZoneId shouldBe deviceZone.id

				subject().deleteSelectedSession(selectedId) shouldBe StepsSessionDeletionResult.Deleted
			} finally {
				TimeZone.setDefault(originalTimeZone)
			}

			database.dailySummaryDao().getByDay(deviceDay) shouldBe null
			database.dailySummaryDao().getByDay(manifestDay.toEpochDay()) shouldBe null
		}

	@Test
	fun `null or invalid summary zone authority blocks before selected mutation`() = runTest {
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(ZONE).toInstant().toEpochMilli()
		val selectedId = insertTerminalStepsSession(
			startMs = dayStart + HOUR_MS,
			endMs = dayStart + 2L * HOUR_MS,
			steps = 4,
		)
		DailySummaryAggregator(
			database.dailySummaryDao(),
			database.sessionSegmentDao(),
			zoneId = ZONE,
		).materializeDayFromSegments(day)
		val subject = subject()

		database.openHelper.writableDatabase.execSQL(
			"UPDATE daily_summary SET calendar_zone_id = NULL WHERE date_epoch_day = ?",
			arrayOf(day),
		)
		subject.deleteSelectedSession(selectedId) shouldBe
			StepsSessionDeletionResult.UnsupportedScope(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)
		database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
		database.stepFactRevisionDao().countAll() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L

		database.openHelper.writableDatabase.execSQL(
			"UPDATE daily_summary SET calendar_zone_id = 'not-a-zone' WHERE date_epoch_day = ?",
			arrayOf(day),
		)
		subject.deleteSelectedSession(selectedId) shouldBe
			StepsSessionDeletionResult.UnsupportedScope(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)
		database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
		database.stepFactRevisionDao().countAll() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `selected fact policy mismatch fails before fence or payload mutation`() = runTest {
		val selectedId = insertTerminalStepsSession(
			startMs = 1_000L,
			endMs = 2_000L,
			steps = 4,
			factPolicyRevision = SOURCE_POLICY_REVISION + 1L,
		)

		subject().deleteSelectedSession(selectedId) shouldBe
			StepsSessionDeletionResult.UnsupportedScope(
				StepsSessionDeletionUnsupportedReason.FACT_ATTRIBUTION_MISMATCH,
			)

		database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
		database.stepFactRevisionDao().countAll() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `selected fact consent mismatch fails before fence or payload mutation`() = runTest {
		val selectedId = insertTerminalStepsSession(
			startMs = 1_000L,
			endMs = 2_000L,
			steps = 4,
			factConsentEpoch = CAPTURE_CONSENT_EPOCH + 1L,
		)

		subject().deleteSelectedSession(selectedId) shouldBe
			StepsSessionDeletionResult.UnsupportedScope(
				StepsSessionDeletionUnsupportedReason.FACT_ATTRIBUTION_MISMATCH,
			)

		database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
		database.stepFactRevisionDao().countAll() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `survivor fact policy mismatch fails before selected-session mutation`() = runTest {
		val selectedId = insertTerminalStepsSession(1_000L, 2_000L, steps = 4)
		installCanonicalLane(contiguousAdmissionOrdinal = 2L)
		insertAttributedCandidateSurvivor(
			logicalId = "logical-policy-mismatch-survivor",
			runId = "run-policy-mismatch-survivor",
			startMs = 3_000L,
			endMs = 4_000L,
			factPolicyRevision = SOURCE_POLICY_REVISION + 1L,
		)

		subject().deleteSelectedSession(selectedId) shouldBe
			StepsSessionDeletionResult.UnsupportedScope(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)

		database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
		database.stepFactRevisionDao().countAll() shouldBe 2L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `survivor fact consent mismatch fails before selected-session mutation`() = runTest {
		val selectedId = insertTerminalStepsSession(1_000L, 2_000L, steps = 4)
		installCanonicalLane(contiguousAdmissionOrdinal = 2L)
		insertAttributedCandidateSurvivor(
			logicalId = "logical-consent-mismatch-survivor",
			runId = "run-consent-mismatch-survivor",
			startMs = 3_000L,
			endMs = 4_000L,
			factConsentEpoch = CAPTURE_CONSENT_EPOCH + 1L,
		)

		subject().deleteSelectedSession(selectedId) shouldBe
			StepsSessionDeletionResult.UnsupportedScope(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)

		database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
		database.stepFactRevisionDao().countAll() shouldBe 2L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `terminal failure dependency overflow blocks before selected-session mutation`() = runTest {
		val selectedId = insertTerminalStepsSession(1_000L, 2_000L, steps = 4)
		insertAttributedCandidateSurvivor(
			logicalId = "logical-terminal-failure-early-survivor",
			runId = "run-terminal-failure-early-survivor",
			startMs = 3_000L,
			endMs = 4_000L,
			admissionOrdinal = 1L,
			writerProjectionId = OVERFLOW_EARLY_WRITER_ID,
			writerProjectionVersion = OVERFLOW_WRITER_VERSION,
			writerBindingGeneration = OVERFLOW_EARLY_BINDING_GENERATION,
		)
		insertAttributedCandidateSurvivor(
			logicalId = "logical-terminal-failure-late-survivor",
			runId = "run-terminal-failure-late-survivor",
			startMs = 5_000L,
			endMs = 6_000L,
			admissionOrdinal = TERMINAL_FAILURE_DEPENDENCY_CAP + 2L,
			writerProjectionId = OVERFLOW_LATE_WRITER_ID,
			writerProjectionVersion = OVERFLOW_WRITER_VERSION,
			writerBindingGeneration = OVERFLOW_LATE_BINDING_GENERATION,
		)
		installCanonicalLane(
			contiguousAdmissionOrdinal = 1L,
			activationOrdinal = 1L,
			writerProjectionId = OVERFLOW_EARLY_WRITER_ID,
			writerProjectionVersion = OVERFLOW_WRITER_VERSION,
			writerBindingGeneration = OVERFLOW_EARLY_BINDING_GENERATION,
		)
		installCanonicalLane(
			contiguousAdmissionOrdinal = TERMINAL_FAILURE_DEPENDENCY_CAP + 2L,
			activationOrdinal = TERMINAL_FAILURE_DEPENDENCY_CAP + 2L,
			writerProjectionId = OVERFLOW_LATE_WRITER_ID,
			writerProjectionVersion = OVERFLOW_WRITER_VERSION,
			writerBindingGeneration = OVERFLOW_LATE_BINDING_GENERATION,
		)
		for (ordinal in 2L..TERMINAL_FAILURE_DEPENDENCY_CAP + 2L) {
			database.sourceProjectionStateDao().saveFailure(
				SourceProjectionFailureEntity(
					projectionId = OVERFLOW_EARLY_WRITER_ID,
					projectionVersion = OVERFLOW_WRITER_VERSION,
					admissionOrdinal = ordinal,
					attemptCount = 1,
					failureCode = "terminal-$ordinal",
					terminal = true,
					lastAttemptAtMs = ordinal,
				),
			)
		}

		subject().deleteSelectedSession(selectedId) shouldBe
			StepsSessionDeletionResult.UnsupportedScope(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)
		database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
		database.stepFactRevisionDao().countAll() shouldBe 3L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
	}

	@Test
	fun `latest exact lifecycle action blocks until every family and source claim is terminal`() =
		runTest {
			val selectedId = insertTerminalStepsSession(1_000L, 2_000L, steps = 4)
			val pending = lifecycleAction(
				actionId = "unattempted-pending",
				actionRevision = 1L,
				status = "PENDING",
			).copy(
				desiredState = "UNRECOGNIZED_PENDING_STATE",
				attemptCount = 0,
			)
			database.sourceSessionDao().insertLifecycleActions(listOf(pending))

			subject().deleteSelectedSession(selectedId) shouldBe StepsSessionDeletionResult.BlockedActive
			database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
			database.sourceDeletionFenceDao().countAll() shouldBe 0L

			database.sourceSessionDao().updateLifecycleAction(
				pending.copy(status = "STOP_ACCEPTED"),
			) shouldBe 1
			subject().deleteSelectedSession(selectedId) shouldBe StepsSessionDeletionResult.Deleted
	}

	@Test
	fun `unverifiable zero-sample survivor fails before fence or selected payload mutation`() = runTest {
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(ZONE).toInstant().toEpochMilli()
		val selectedId = insertTerminalStepsSession(
			startMs = dayStart + HOUR_MS,
			endMs = dayStart + 2L * HOUR_MS,
			steps = 4,
		)
		val unavailableId = database.sessionSegmentDao().insert(
			segment(dayStart + 3L * HOUR_MS, dayStart + 4L * HOUR_MS, steps = 0).copy(
				steps = null,
				sampleCount = 0,
			),
		)

		subject().deleteSelectedSession(selectedId) shouldBe
			StepsSessionDeletionResult.UnsupportedScope(
				StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
			)

		database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
		database.sessionSegmentDao().getById(unavailableId).shouldNotBeNull()
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
		drainRequests shouldBe 0
	}

	@Test
	fun `materializing survivor returns retryable before installing deletion authority`() = runTest {
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(ZONE).toInstant().toEpochMilli()
		val selectedId = insertTerminalStepsSession(
			startMs = dayStart + HOUR_MS,
			endMs = dayStart + 2L * HOUR_MS,
			steps = 4,
		)
		insertMaterializingCandidateSurvivor(
			startMs = dayStart + 3L * HOUR_MS,
			endMs = dayStart + 4L * HOUR_MS,
		)

		subject().deleteSelectedSession(selectedId) shouldBe
			StepsSessionDeletionResult.RetryableFailure(
				StepsSessionDeletionRetryableReason.DAY_REPAIR_MATERIALIZING,
			)

		database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `terminal pending survivor prevents every deletion mutation until exact presentation ack`() = runTest {
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(ZONE).toInstant().toEpochMilli()
		val selectedId = insertTerminalStepsSession(
			startMs = dayStart + HOUR_MS,
			endMs = dayStart + 2L * HOUR_MS,
			steps = 4,
		)
		database.skiRunSegmentDao().insert(skiSegment(selectedId)) shouldBe 1L
		val survivorId = insertAttributedCandidateSurvivor(
			logicalId = "logical-pending-survivor",
			runId = "run-pending-survivor",
			startMs = dayStart + 3L * HOUR_MS,
			endMs = dayStart + 4L * HOUR_MS,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
		)
		installCanonicalLane(contiguousAdmissionOrdinal = 2L)

		subject().deleteSelectedSession(selectedId) shouldBe
			StepsSessionDeletionResult.RetryableFailure(
				StepsSessionDeletionRetryableReason.DAY_REPAIR_MATERIALIZING,
			)
		database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
		database.skiRunSegmentDao().hasSkiSegments(selectedId) shouldBe true
		database.stepFactRevisionDao().countAll() shouldBe 2L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L

		database.sourceSessionDao().acknowledgePresentationQuiescedExact(
			logicalTrackingId = "logical-pending-survivor",
			serviceRunId = "run-pending-survivor",
			sessionSegmentId = survivorId,
			acknowledgedAtMs = dayStart + 4L * HOUR_MS + 1L,
		) shouldBe 1
		subject().deleteSelectedSession(selectedId) shouldBe StepsSessionDeletionResult.Deleted

		database.sessionSegmentDao().getById(selectedId) shouldBe null
		database.sessionSegmentDao().getById(survivorId).shouldNotBeNull()
		database.skiRunSegmentDao().hasSkiSegments(selectedId) shouldBe false
		database.sourceDeletionFenceDao().countAll() shouldBe 1L
	}

	@Test
	fun `cancellation at final pre-mutation point preserves all durable state`() = runTest {
		val selectedId = insertTerminalStepsSession(1_000L, 2_000L, steps = 4)
		val before = database.sourceEvidenceStateDao().get()
		var cancellationObserved = false
		try {
			subject(beforeMutation = { throw CancellationException("test cancellation") })
				.deleteSelectedSession(selectedId)
		} catch (_: CancellationException) {
			cancellationObserved = true
		}

		cancellationObserved shouldBe true
		database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get() shouldBe before
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
		drainRequests shouldBe 0
	}

	@Test
	fun `concurrent selected-row loss rolls back the entire deletion transaction`() = runTest {
		val selectedId = insertTerminalStepsSession(1_000L, 2_000L, steps = 4)
		val before = database.sourceEvidenceStateDao().get()

		val result = subject(
			beforeMutation = {
				database.sessionSegmentDao().deleteExact(
					selectedId,
					LOGICAL_TRACKING_ID,
					SERVICE_RUN_ID,
				) shouldBe 1
			},
		).deleteSelectedSession(selectedId)

		result shouldBe StepsSessionDeletionResult.RetryableFailure(
			StepsSessionDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
		)
		database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get() shouldBe before
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
		drainRequests shouldBe 0
	}

	@Test
	fun `summary row authority change after day locks retries before deletion mutation`() = runTest {
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(ZONE).toInstant().toEpochMilli()
		val selectedId = insertTerminalStepsSession(
			startMs = dayStart + HOUR_MS,
			endMs = dayStart + 2L * HOUR_MS,
			steps = 4,
		)
		DailySummaryAggregator(
			database.dailySummaryDao(),
			database.sessionSegmentDao(),
			zoneId = ZONE,
		).materializeDayFromSegments(day)

		val result = subject(
			afterDayLocksAcquired = {
				database.openHelper.writableDatabase.execSQL(
					"UPDATE daily_summary SET calendar_zone_id = 'Pacific/Honolulu' " +
						"WHERE date_epoch_day = ?",
					arrayOf(day),
				)
			},
		).deleteSelectedSession(selectedId)

		result shouldBe StepsSessionDeletionResult.RetryableFailure(
			StepsSessionDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
		)
		database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
		database.stepFactRevisionDao().countAll() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		verify(exactly = 0) { dirtyTracker.markDirty(any<Set<String>>()) }
		drainRequests shouldBe 0
	}

	@Test
	fun `summary row insertion after day locks retries before deletion mutation`() = runTest {
		val day = LocalDate.of(2026, 4, 2).toEpochDay()
		val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(ZONE).toInstant().toEpochMilli()
		val selectedId = insertTerminalStepsSession(
			startMs = dayStart + HOUR_MS,
			endMs = dayStart + 2L * HOUR_MS,
			steps = 4,
		)

		val result = subject(
			afterDayLocksAcquired = {
				database.dailySummaryDao().upsert(
					dateEpochDay = day,
					totalDistanceM = 4f,
					totalSteps = 4,
					totalDurationMs = HOUR_MS,
					tripCount = 1,
					activeTrackingMs = 0L,
					lastUpdatedMs = 3_000L,
					calendarZoneId = ZONE.id,
				)
			},
		).deleteSelectedSession(selectedId)

		result shouldBe StepsSessionDeletionResult.RetryableFailure(
			StepsSessionDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
		)
		database.sessionSegmentDao().getById(selectedId).shouldNotBeNull()
		database.stepFactRevisionDao().countAll() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `existing fence wins while Ski payload is removed and attribution audit is retained`() = runTest {
		val selectedId = insertTerminalStepsSession(
			1_000L,
			2_000L,
			steps = 4,
			insertFact = false,
		)
		database.stepFactRevisionDao().insert(
			stepFact(
				logicalFactId = SELECTED_FACT_ID,
				admissionOrdinal = 1L,
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = SERVICE_RUN_ID,
				stepCount = 4L,
			),
		) shouldBe 1L
		database.skiRunSegmentDao().insert(skiSegment(selectedId)) shouldBe 1L
		val fence = SourceDeletionFenceEntity.createLogicalServiceRun(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			fenceGeneration = 7L,
			collectedDataEpoch = COLLECTED_DATA_EPOCH,
			deletedAtMs = 4_000L,
		)
		database.sourceDeletionFenceDao().insertIfAbsent(fence) shouldBe 1L

		subject().deleteSelectedSession(selectedId) shouldBe StepsSessionDeletionResult.Deleted

		database.skiRunSegmentDao().hasSkiSegments(selectedId) shouldBe false
		database.sourceSessionDao().session(LOGICAL_TRACKING_ID).shouldNotBeNull()
		database.sourceSessionDao().serviceRun(SERVICE_RUN_ID).shouldNotBeNull()
		database.sourceSessionDao().manifestsForServiceRun(SERVICE_RUN_ID).shouldHaveSize(1)
		database.sourceSessionDao().manifestSources(LOGICAL_TRACKING_ID, MANIFEST_REVISION)
			.shouldHaveSize(1)
		val persistedFence = database.sourceDeletionFenceDao().get(
			fence.sourceKind,
			fence.purpose,
			fence.scopeKind,
			fence.scopeIdentityDigest,
		)
		persistedFence shouldBe fence
		database.stepFactRevisionDao().revisions(
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
			SELECTED_FACT_ID,
		).single().scopeDeletionGeneration shouldBe 7L
	}

	@Test
	@OptIn(ExperimentalCoroutinesApi::class)
	fun `deletion and worker materialization share lock-before-transaction order without deadlock`() =
		runTest {
			val day = LocalDate.of(2026, 4, 2).toEpochDay()
			val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(ZONE).toInstant().toEpochMilli()
			val selectedId = insertTerminalStepsSession(
				startMs = dayStart + HOUR_MS,
				endMs = dayStart + 2L * HOUR_MS,
				steps = 4,
			)
			val deletionHasDayLock = CompletableDeferred<Unit>()
			val releaseDeletion = CompletableDeferred<Unit>()
			val workerEnteredTransaction = CompletableDeferred<Unit>()
			val deletion = async {
				subject(
					afterDayLocksAcquired = {
						deletionHasDayLock.complete(Unit)
						releaseDeletion.await()
					},
				).deleteSelectedSession(selectedId)
			}
			deletionHasDayLock.await()
			val aggregator = DailySummaryAggregator(
				database.dailySummaryDao(),
				database.sessionSegmentDao(),
				zoneId = ZONE,
			)
			val worker = async {
				materializeDailySummaryDayInTransaction(
					database = database,
					aggregator = aggregator,
					epochDay = day,
					capturedZoneId = ZONE,
					beforeMaterialize = { workerEnteredTransaction.complete(Unit) },
				)
			}
			runCurrent()
			workerEnteredTransaction.isCompleted shouldBe false

			releaseDeletion.complete(Unit)
			deletion.await() shouldBe StepsSessionDeletionResult.Deleted
			worker.await()

			workerEnteredTransaction.isCompleted shouldBe true
			database.sessionSegmentDao().getById(selectedId) shouldBe null
			database.dailySummaryDao().getByDay(day) shouldBe null
		}

	@Test
	@Suppress("LongMethod")
	fun `durable fence survives reopen and rejects delayed WAL resurrection`(): Unit = runBlocking {
		val inMemoryDatabase = database
		val context: Application = ApplicationProvider.getApplicationContext()
		val databaseName = "steps-session-deletion-reopen-${System.nanoTime()}.db"
		// Robolectric's per-test data directory includes the full method name and can exceed the
		// Windows SQLite path limit. The system temp directory keeps this isolated file short.
		val databaseFile = File(System.getProperty("java.io.tmpdir"), databaseName)
		val databaseDirectory = requireNotNull(databaseFile.parentFile)
		check(databaseDirectory.exists() || databaseDirectory.mkdirs())
		check(!databaseFile.exists() || databaseFile.delete())
		var reopenedDatabase: AppDatabase? = null
		try {
			val selectedId = insertTerminalStepsSession(
				1_000L,
				2_000L,
				steps = 5,
				insertFact = false,
			)
			database.stepFactRevisionDao().insert(
				stepFact(
					logicalFactId = SELECTED_FACT_ID,
					admissionOrdinal = 1L,
					logicalTrackingId = LOGICAL_TRACKING_ID,
					serviceRunId = SERVICE_RUN_ID,
					stepCount = 5L,
				),
			) shouldBe 1L

			subject().deleteSelectedSession(selectedId) shouldBe StepsSessionDeletionResult.Deleted
			database.sourceDeletionFenceDao().countAll() shouldBe 1L
			// Export the exact committed Room image, then close the original connection. The following
			// assertions are executed only through a fresh Room instance over that durable image.
			database.openHelper.writableDatabase.execSQL(
				"VACUUM INTO ?",
				arrayOf(databaseFile.path),
			)
			inMemoryDatabase.close()

			reopenedDatabase = Room.databaseBuilder(
				context,
				AppDatabase::class.java,
				databaseFile.path,
			)
				.allowMainThreadQueries()
				.build()
			database = reopenedDatabase
			database.sourceDeletionFenceDao().countAll() shouldBe 1L
			database.sessionSegmentDao().getById(selectedId) shouldBe null
			database.sourceSessionDao().serviceRun(SERVICE_RUN_ID).shouldNotBeNull()
			installCanonicalLane()
			val lateIngress = mockk<DurableSourceIngress>()
			coEvery {
				lateIngress.committedSourceBatch(SourceKind.STEPS, 0L, 1L, 64)
			} returns listOf(lateStepEvent(1L))

			StepsSessionFactProjectionLane(database, lateIngress).drainThrough(1L) shouldBe
				StepsSessionFactDrainResult.Complete(
					lastCompletedOrdinal = 1L,
					factsInserted = 0,
					eventsValidated = 1,
				)
			database.stepFactRevisionDao().countAll() shouldBe 1L
			database.sessionSegmentDao().getById(selectedId) shouldBe null
		} finally {
			reopenedDatabase?.close()
			if (database === inMemoryDatabase) {
				inMemoryDatabase.close()
			}
			database = AppDatabase.testDatabase(context)
			context.deleteDatabase(databaseFile.path)
		}
	}

	private fun subject(
		afterDayLocksAcquired: suspend () -> Unit = {},
		beforeMutation: suspend () -> Unit = {},
	) = RoomStepsSelectedSessionDeletionService(
		database = database,
		dirtyTracker = dirtyTracker,
		wallTimeMsProvider = { DELETED_AT_MS },
		requestStepsDrain = { drainRequests += 1 },
		afterDayLocksAcquired = afterDayLocksAcquired,
		beforeMutation = beforeMutation,
	)

	private suspend fun insertMaterializingCandidateSurvivor(startMs: Long, endMs: Long) {
		val logicalId = "logical-materializing-survivor"
		val runId = "run-materializing-survivor"
		val segmentId = database.sessionSegmentDao().insert(
			segment(startMs, endMs, steps = 5).copy(
				logicalTrackingId = logicalId,
				serviceRunId = runId,
				sampleCount = 0,
			),
		)
		database.sourceSessionDao().insertSession(
			logicalSession(state = "FINALIZED", currentServiceRunId = null).copy(
				logicalTrackingId = logicalId,
				startedAtMs = startMs,
				cutoffAtMs = endMs,
				completedAtMs = endMs,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			serviceRun(
				state = "ACTIVE",
				completedAtMs = null,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
				presentationAcknowledgedAtMs = null,
			).copy(
				serviceRunId = runId,
				logicalTrackingId = logicalId,
				startDeliveryToken = "delivery-materializing-survivor",
			),
		)
		val source = candidateManifestSource().copy(logicalTrackingId = logicalId)
		val unsigned = unsignedManifest().copy(
			logicalTrackingId = logicalId,
			serviceRunId = runId,
			effectiveWallTimeMs = startMs,
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
	}

	@Suppress("LongMethod")
	private suspend fun insertAttributedCandidateSurvivor(
		logicalId: String,
		runId: String,
		startMs: Long,
		endMs: Long,
		factPolicyRevision: Long = SOURCE_POLICY_REVISION,
		factConsentEpoch: Long = CAPTURE_CONSENT_EPOCH,
		presentationAcknowledgement: String = SourceServiceRunEntity.PRESENTATION_QUIESCED,
		manifestRevision: Long = MANIFEST_REVISION,
		admissionOrdinal: Long = 2L,
		stepCount: Long = 5L,
		segmentSteps: Int = 5,
		sampleCount: Int = 0,
		insertLogicalSession: Boolean = true,
		logicalStartedAtMs: Long = startMs,
		writerProjectionId: String = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
		writerProjectionVersion: Int = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		writerBindingGeneration: Long = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
	): Long {
		val segmentId = database.sessionSegmentDao().insert(
			segment(startMs, endMs, steps = segmentSteps).copy(
				logicalTrackingId = logicalId,
				serviceRunId = runId,
				sampleCount = sampleCount,
			),
		)
		if (insertLogicalSession) {
			database.sourceSessionDao().insertSession(
				logicalSession(state = "FINALIZED", currentServiceRunId = null).copy(
					logicalTrackingId = logicalId,
					startedAtMs = logicalStartedAtMs,
					cutoffAtMs = endMs,
					completedAtMs = endMs,
					currentManifestRevision = manifestRevision,
				),
			)
		}
		database.sourceSessionDao().insertServiceRun(
			serviceRun(
				state = "FINALIZED",
				completedAtMs = endMs,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = presentationAcknowledgement,
				presentationAcknowledgedAtMs = endMs.takeIf {
					presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_QUIESCED
				},
			).copy(
				serviceRunId = runId,
				logicalTrackingId = logicalId,
				startedAtMs = startMs,
				startDeliveryToken = "delivery-$runId",
				preparedManifestRevision = manifestRevision,
			),
		)
		val source = candidateManifestSource(
			writerProjectionId = writerProjectionId,
			writerProjectionVersion = writerProjectionVersion,
			writerBindingGeneration = writerBindingGeneration,
		).copy(
			logicalTrackingId = logicalId,
			manifestRevision = manifestRevision,
		)
		val unsigned = unsignedManifest().copy(
			logicalTrackingId = logicalId,
			manifestRevision = manifestRevision,
			serviceRunId = runId,
			effectiveWallTimeMs = startMs,
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
		database.sourceSessionDao().saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = logicalId,
				serviceRunId = runId,
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				sourceInstanceId = "steps-$runId",
				registrationGeneration = 1L,
				lastAdmissionOrdinal = admissionOrdinal,
				lastSourceSequence = admissionOrdinal,
				appDrainComplete = true,
				providerCoverage = "CALLBACKS_ENTERED_BEFORE_BARRIER",
				stopStatus = "COMPLETE",
				unresolvedSequenceStart = null,
				unresolvedSequenceEnd = null,
				updatedAtMs = endMs,
			),
		)
		database.stepFactRevisionDao().insert(
			stepFact(
				logicalFactId = "steps-session-facts:$runId",
				admissionOrdinal = admissionOrdinal,
				logicalTrackingId = logicalId,
				serviceRunId = runId,
				stepCount = stepCount,
				startMs = startMs,
				endMs = endMs,
				sourcePolicyRevision = factPolicyRevision,
				captureConsentEpoch = factConsentEpoch,
				manifestRevision = manifestRevision,
				writerProjectionId = writerProjectionId,
				writerProjectionVersion = writerProjectionVersion,
				writerBindingGeneration = writerBindingGeneration,
			),
		)
		return segmentId
	}

	private suspend fun attributeLocationSurvivor(
		segmentId: Long,
		logicalId: String,
		runId: String,
		startMs: Long,
		endMs: Long,
	) {
		database.sourceSessionDao().insertSession(
			logicalSession(state = "FINALIZED", currentServiceRunId = null).copy(
				logicalTrackingId = logicalId,
				startedAtMs = startMs,
				cutoffAtMs = endMs,
				completedAtMs = endMs,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			serviceRun(
				state = "FINALIZED",
				completedAtMs = endMs,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
				presentationAcknowledgedAtMs = endMs,
			).copy(
				serviceRunId = runId,
				logicalTrackingId = logicalId,
				startDeliveryToken = "delivery-$runId",
			),
		)
		val source = SessionManifestSourceEntity(
			logicalTrackingId = logicalId,
			manifestRevision = MANIFEST_REVISION,
			sourceKind = SourceKind.LOCATION.stableCode,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			consentEpoch = 1L,
			persistenceEligible = true,
			qosCode = 0,
		)
		val unsigned = unsignedManifest().copy(
			logicalTrackingId = logicalId,
			serviceRunId = runId,
			effectiveWallTimeMs = startMs,
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
		database.sourceSessionDao().saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = logicalId,
				serviceRunId = runId,
				sourceKind = SourceKind.LOCATION.stableCode,
				sourceInstanceId = "location-provider",
				registrationGeneration = 1L,
				lastAdmissionOrdinal = 3L,
				lastSourceSequence = 3L,
				appDrainComplete = true,
				providerCoverage = "CALLBACKS_ENTERED_BEFORE_BARRIER",
				stopStatus = "COMPLETE",
				unresolvedSequenceStart = null,
				unresolvedSequenceEnd = null,
				updatedAtMs = endMs,
			),
		)
	}

	private fun lifecycleAction(
		actionId: String,
		actionRevision: Long,
		status: String,
	) = LifecycleDesiredActionEntity(
		actionId = actionId,
		logicalTrackingId = LOGICAL_TRACKING_ID,
		serviceRunId = SERVICE_RUN_ID,
		manifestRevision = MANIFEST_REVISION,
		actionRevision = actionRevision,
		actionFamily = "SOURCE_RUNTIME",
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		desiredState = "FINALIZED",
		desiredPlanRevision = 1L,
		sourcePolicyRevision = SOURCE_POLICY_REVISION,
		consentEpoch = CAPTURE_CONSENT_EPOCH,
		startOrigin = "MANUAL_UI",
		bootId = "boot-1",
		leaseGeneration = 1L,
		requestedAtMs = 2_000L,
		requestedElapsedRealtimeNanos = 2_000L,
		status = status,
		attemptCount = 1,
		acknowledgedAtMs = null,
		acknowledgedElapsedRealtimeNanos = null,
		failureCode = null,
		retryTrigger = null,
		sourceInstanceId = "steps-provider",
		registrationGeneration = 1L,
	)

	private fun skiSegment(sessionId: Long) = SkiRunSegment(
		sessionId = sessionId,
		runIndex = 0,
		segmentType = SkiSegmentType.DOWNHILL_RUN,
		startTimeMs = 1_000L,
		endTimeMs = 2_000L,
		verticalM = -10f,
		distanceM = 20f,
		maxSpeedMps = 5f,
		avgSpeedMps = 3f,
		createdAt = 2_000L,
	)

	private suspend fun insertTerminalStepsSession(
		startMs: Long,
		endMs: Long,
		steps: Int,
		boundSegmentIdOverride: Long? = null,
		zoneId: ZoneId = ZONE,
		manifestPolicyRevision: Long = SOURCE_POLICY_REVISION,
		bindingConsentEpoch: Long = CAPTURE_CONSENT_EPOCH,
		factPolicyRevision: Long = manifestPolicyRevision,
		factConsentEpoch: Long = bindingConsentEpoch,
		insertFact: Boolean = true,
		presentationAcknowledgement: String = SourceServiceRunEntity.PRESENTATION_QUIESCED,
	): Long {
		val segmentId = database.sessionSegmentDao().insert(
			segment(startMs, endMs, steps).copy(
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = SERVICE_RUN_ID,
			),
		)
		database.sourceSessionDao().insertSession(logicalSession(
			state = "FINALIZED",
			currentServiceRunId = null,
		))
		database.sourceSessionDao().insertServiceRun(serviceRun(
			state = "FINALIZED",
			completedAtMs = endMs,
			sessionSegmentId = boundSegmentIdOverride ?: segmentId,
			presentationAcknowledgement = presentationAcknowledgement,
			presentationAcknowledgedAtMs = endMs.takeIf {
				presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_QUIESCED
			},
		))
		insertCandidateManifest(zoneId, manifestPolicyRevision, bindingConsentEpoch)
		if (insertFact) {
			database.stepFactRevisionDao().insert(
				stepFact(
					logicalFactId = SELECTED_FACT_ID,
					admissionOrdinal = 1L,
					logicalTrackingId = LOGICAL_TRACKING_ID,
					serviceRunId = SERVICE_RUN_ID,
					stepCount = steps.toLong(),
					startMs = startMs,
					endMs = endMs,
					sourcePolicyRevision = factPolicyRevision,
					captureConsentEpoch = factConsentEpoch,
				),
			) shouldBe 1L
		}
		return segmentId
	}

	private suspend fun insertActiveStepsSessionWithQuiescedPresentation(): Long {
		val segmentId = database.sessionSegmentDao().insert(
			segment(1_000L, 2_000L, 5).copy(
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = SERVICE_RUN_ID,
			),
		)
		database.sourceSessionDao().insertSession(logicalSession(
			state = "ACTIVE",
			currentServiceRunId = SERVICE_RUN_ID,
		))
		database.sourceSessionDao().insertServiceRun(serviceRun(
			state = "ACTIVE",
			completedAtMs = null,
			sessionSegmentId = segmentId,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = 2_000L,
		))
		return segmentId
	}

	private fun logicalSession(
		state: String,
		currentServiceRunId: String?,
	) = LogicalTrackingSessionEntity(
		logicalTrackingId = LOGICAL_TRACKING_ID,
		state = state,
		lifecycleRevision = 2L,
		desiredPlanRevision = 1L,
		rolloutRevision = 2L,
		startOrigin = "MANUAL_UI",
		clockDomainId = "boot-1",
		startedAtMs = 1_000L,
		startedElapsedNanos = 1_000L,
		cutoffAtMs = 2_000L.takeIf { state != "ACTIVE" },
		cutoffElapsedNanos = 2_000L.takeIf { state != "ACTIVE" },
		completedAtMs = 2_000L.takeIf { state != "ACTIVE" },
		finalAdmissionOrdinal = 1L.takeIf { state != "ACTIVE" },
		failureCode = null,
		sessionMode = "MANUAL",
		currentManifestRevision = MANIFEST_REVISION,
		currentIntentRevision = null,
		currentServiceRunId = currentServiceRunId,
		lifecycleLeaseGeneration = 1L,
		lifecycleBootId = "boot-1",
		automationEpoch = null,
	)

	private fun serviceRun(
		state: String,
		completedAtMs: Long?,
		sessionSegmentId: Long,
		presentationAcknowledgement: String,
		presentationAcknowledgedAtMs: Long?,
	) = SourceServiceRunEntity(
		serviceRunId = SERVICE_RUN_ID,
		logicalTrackingId = LOGICAL_TRACKING_ID,
		state = state,
		desiredPlanRevision = 1L,
		rolloutRevision = 2L,
		foregroundCapabilityFlags = 0L,
		startedAtMs = 1_000L,
		startedElapsedNanos = 1_000L,
		completedAtMs = completedAtMs,
		completionReason = "USER_STOP".takeIf { completedAtMs != null },
		bootId = "boot-1",
		leaseGeneration = 1L,
		startOrigin = "MANUAL_UI",
		desiredForegroundCapabilityFlags = 0L,
		appliedForegroundCapabilityFlags = 0L,
		runtimeAcknowledgement = "STOP_ACCEPTED".takeIf { completedAtMs != null } ?: "START_ACCEPTED",
		runtimeFailureCode = null,
		runRevision = 2L,
		startDeliveryToken = "delivery-token",
		startCommandGeneration = 1L,
		preparedManifestRevision = MANIFEST_REVISION,
		preparedIntentRevision = 1L,
		androidDeliveryState = "FOREGROUND_ACCEPTED",
		androidDeliveryUpdatedAtMs = 1_000L,
		startIsUserInitiated = true,
		startIsAmbient = false,
		sessionSegmentId = sessionSegmentId,
		presentationAcknowledgement = presentationAcknowledgement,
		presentationAcknowledgedAtMs = presentationAcknowledgedAtMs,
	)

	private suspend fun insertCandidateManifest(
		zoneId: ZoneId = ZONE,
		sourcePolicyRevision: Long = SOURCE_POLICY_REVISION,
		consentEpoch: Long = CAPTURE_CONSENT_EPOCH,
	) {
		val source = candidateManifestSource(consentEpoch)
		val unsigned = unsignedManifest(zoneId, sourcePolicyRevision)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
	}

	private fun candidateManifestSource(
		consentEpoch: Long = CAPTURE_CONSENT_EPOCH,
		writerProjectionId: String = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
		writerProjectionVersion: Int = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		writerBindingGeneration: Long = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
	) = SessionManifestSourceEntity(
		logicalTrackingId = LOGICAL_TRACKING_ID,
		manifestRevision = MANIFEST_REVISION,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		consentEpoch = consentEpoch,
		persistenceEligible = true,
		qosCode = 0,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		writerProjectionId = writerProjectionId,
		writerProjectionVersion = writerProjectionVersion,
		writerBindingGeneration = writerBindingGeneration,
	)

	private fun unsignedManifest(
		zoneId: ZoneId = ZONE,
		sourcePolicyRevision: Long = SOURCE_POLICY_REVISION,
	) = SessionManifestVersionEntity(
		logicalTrackingId = LOGICAL_TRACKING_ID,
		manifestRevision = MANIFEST_REVISION,
		serviceRunId = SERVICE_RUN_ID,
		sessionMode = "MANUAL",
		sourcePolicyRevision = sourcePolicyRevision,
		acquisitionPlanRevision = 1L,
		rolloutRevision = 2L,
		startOrigin = "MANUAL_UI",
		effectiveBootId = "boot-1",
		effectiveElapsedRealtimeNanos = 1_000L,
		effectiveWallTimeMs = 1_000L,
		zoneId = zoneId.id,
		automationEpoch = null,
		changeReason = "TEST",
		manifestChecksum = "",
	)

	private fun candidateOwner() = SourceDestinationOwnerEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
		owner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
		ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		updatedAtMs = 1_000L,
	)

	private fun segment(startMs: Long, endMs: Long, steps: Int) = SessionSegment(
		startTimeMs = startMs,
		endTimeMs = endMs,
		distanceM = 0f,
		steps = steps,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 1,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = null,
		createdAt = endMs,
	)

	private fun stepFact(
		logicalFactId: String,
		admissionOrdinal: Long,
		logicalTrackingId: String,
		serviceRunId: String,
		stepCount: Long,
		startMs: Long = 1_000L,
		endMs: Long = 2_000L,
		sourcePolicyRevision: Long = SOURCE_POLICY_REVISION,
		captureConsentEpoch: Long = CAPTURE_CONSENT_EPOCH,
		manifestRevision: Long = MANIFEST_REVISION,
		writerProjectionId: String = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
		writerProjectionVersion: Int = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		writerBindingGeneration: Long = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
	) = StepFactRevisionEntity(
		logicalFactId = logicalFactId,
		semanticRevision = 1L,
		mutationId = "mutation-$admissionOrdinal",
		stepIntervalId = null,
		sourceEventId = "event-$admissionOrdinal",
		sourceAdmissionOrdinal = admissionOrdinal,
		originKind = StepFactRevisionEntity.ORIGIN_LIVE_WAL,
		originIdentity = "event-$admissionOrdinal",
		writerProjectionId = writerProjectionId,
		writerProjectionVersion = writerProjectionVersion,
		writerBindingGeneration = writerBindingGeneration,
		operation = StepFactRevisionEntity.OPERATION_UPSERT,
		intervalStartTimeMs = startMs,
		intervalEndTimeMs = endMs,
		intervalStartElapsedRealtimeNanos = 1_000L,
		intervalEndElapsedRealtimeNanos = 2_000L,
		clockDomainId = "boot-1",
		bootClockDomainId = "boot-1",
		cumulativeStepCountStart = 100L,
		cumulativeStepCountEnd = 100L + stepCount,
		wallTimeUncertaintyMs = 1L,
		coverageKind = StepFactRevisionEntity.COVERAGE_COVERED,
		effectiveStepCount = stepCount,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = manifestRevision,
		sourcePolicyRevision = sourcePolicyRevision,
		captureConsentEpoch = captureConsentEpoch,
		collectedDataEpoch = COLLECTED_DATA_EPOCH,
		scopeDeletionGeneration = 0L,
		effectChecksum = "checksum-$admissionOrdinal",
		appliedAtMs = 2_000L,
	)

	private suspend fun installCanonicalLane(
		contiguousAdmissionOrdinal: Long = 0L,
		activationOrdinal: Long = 1L,
		writerProjectionId: String = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
		writerProjectionVersion: Int = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		writerBindingGeneration: Long = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
	) {
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				bindingGeneration = writerBindingGeneration,
				projectionId = writerProjectionId,
				projectionVersion = writerProjectionVersion,
				captureModeMask = StepsSessionFactProjectionLane.MANUAL_CAPTURE_MODE_MASK,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = 2L,
				activationOrdinal = activationOrdinal,
				contiguousAdmissionOrdinal = contiguousAdmissionOrdinal,
				captureAdmissionCutoffOrdinal = null,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1_000L,
				updatedAtMs = 1_000L,
			),
		)
	}

	private fun lateStepEvent(ordinal: Long): AdmittedSourceEvent<StepCounterWindowPayload> =
		AdmittedSourceEvent(
			eventId = SourceEventId("late-event-$ordinal"),
			admissionOrdinal = ordinal,
			evidence = SourceEvidenceCandidate(
				providerDedupKey = "late-dedup-$ordinal",
				logicalTrackingId = LogicalTrackingId(LOGICAL_TRACKING_ID),
				serviceRunId = ServiceRunId(SERVICE_RUN_ID),
				source = SourceKind.STEPS,
				sourceInstanceId = SourceInstanceId("steps-provider"),
				registrationGeneration = 1L,
				physicalConfigurationFingerprint = "steps-config",
				authorizationRevision = 1L,
				registrationPurposeEligibilityMask = 1L,
				registrationEligibilityFingerprint = "steps-capture",
				sourceSequence = ordinal,
				configRevision = 1L,
				planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
				clockDomainId = "boot-1",
				observedElapsedRealtimeNanos = 2_000L,
				receivedElapsedRealtimeNanos = 2_001L,
				wallTimeMs = 2_000L,
				wallTimeUncertaintyMs = 1L,
				capturedCollectedDataEpoch = COLLECTED_DATA_EPOCH,
				sourcePolicyRevision = SOURCE_POLICY_REVISION,
				captureConsentEpoch = CAPTURE_CONSENT_EPOCH,
				sessionManifestRevision = MANIFEST_REVISION,
				lifecycleLeaseGeneration = 1L,
				acquiredAtMs = 2_000L,
				quality = SourceQuality(),
				payloadVersion = 3,
				payload = StepCounterWindowPayload(
					bootClockDomainId = "boot-1",
					firstCumulativeCount = 100L,
					lastCumulativeCount = 105L,
					deltaCount = 5L,
					windowStartElapsedRealtimeNanos = 1_000L,
					windowEndElapsedRealtimeNanos = 2_000L,
					firstProviderSequence = ordinal,
					lastProviderSequence = ordinal,
					boundaryKind = StepBoundaryKind.COVERED,
				),
			),
		)

	private companion object {
		val ZONE: ZoneId = ZoneId.of("America/New_York")
		const val HOUR_MS = 60L * 60_000L
		const val MINUTE_MS = 60_000L
		const val LOGICAL_TRACKING_ID = "logical-steps"
		const val SERVICE_RUN_ID = "run-steps"
		const val MANIFEST_REVISION = 1L
		const val SOURCE_POLICY_REVISION = 1L
		const val CAPTURE_CONSENT_EPOCH = 1L
		const val COLLECTED_DATA_EPOCH = 2L
		const val TERMINAL_FAILURE_DEPENDENCY_CAP = 2_048L
		const val OVERFLOW_EARLY_WRITER_ID = "overflow-early-writer"
		const val OVERFLOW_LATE_WRITER_ID = "overflow-late-writer"
		const val OVERFLOW_WRITER_VERSION = 1
		const val OVERFLOW_EARLY_BINDING_GENERATION = 201L
		const val OVERFLOW_LATE_BINDING_GENERATION = 202L
		const val DELETED_AT_MS = 5_000L
		const val SELECTED_FACT_ID = "steps-session-facts:selected"
	}
}
