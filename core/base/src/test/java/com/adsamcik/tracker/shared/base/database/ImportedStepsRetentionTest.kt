package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsAdmissionRows
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsReadFailure
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCaptureCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsCompletenessV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsEntryV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsFactCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsManifestV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsProviderCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsRunV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableStepsSessionMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedStepsRetentionTest {
	private lateinit var database: AppDatabase
	private val completeFactReads = AtomicInteger()

	@Before
	fun setUp() {
		database = Room.inMemoryDatabaseBuilder(
			ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java,
		).allowMainThreadQueries().setQueryCallback({ sql, _ ->
			if (sql.startsWith("SELECT * FROM step_fact_revision WHERE service_run_id IN")) {
				completeFactReads.incrementAndGet()
			}
		}, Executor(Runnable::run)).build()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `raw prune keeps boundary and straddling evidence with a truthful partial receipt`() = runTest {
		val entry = seed()
		database.stepsGoalEffectDao().recordDecision(goalEffect())
		database.sourceEvidenceStateDao().updateLifecycle(1L, 25L, 100L)
		database.markAuthenticatedStepsRunsAffectedByRetentionFloor(25L, 1L, 100L) shouldBe 1
		database.stepsGoalRepairDayDao().get(0L)?.sourceEvidenceRevision shouldBe 1L
		read(entry) shouldBe ImportedStepsRetainedRead.Unverifiable(ImportedStepsReadFailure.RETENTION)
		database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(25L, 1L, 100L) shouldBe 1
		val retained = ready(entry)
		retained.portable shouldBe null
		retained.metadata.contentChecksum shouldBe entry.contentChecksum.value
		retained.retentionTruncatedRunIds shouldBe setOf(entry.runs.single().identity.value)
		retained.factsByRun.values.flatten().map { it.effectiveStepCount } shouldBe listOf(0L, null)
		database.markAuthenticatedStepsRunsAffectedByRetentionFloor(25L, 1L, 101L) shouldBe 0
		database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(25L, 1L, 101L) shouldBe 0
	}

	@Test
	fun `last payload can expire without turning a missing run into numeric zero`() = runTest {
		val entry = seed()
		database.sourceEvidenceStateDao().updateLifecycle(1L, 41L, 100L)
		database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(41L, 1L, 100L) shouldBe 3
		val retained = ready(entry)
		retained.factsByRun.values.flatten() shouldBe emptyList()
		retained.portableRunsById.values.single().facts shouldBe emptyList()
		retained.retentionTruncatedRunIds.size shouldBe 1
		retained.portable shouldBe null
		database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(41L, 1L, 101L) shouldBe 0
	}

	@Test
	fun `exact cutoff is retained and marking alone never grants deletion authority`() = runTest {
		val entry = seed()
		database.sourceEvidenceStateDao().updateLifecycle(1L, 20L, 100L)
		database.markAuthenticatedStepsRunsAffectedByRetentionFloor(20L, 1L, 100L) shouldBe 1
		database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(20L, 1L, 100L) shouldBe 0
		ready(entry).factsByRun.values.flatten().size shouldBe 3
		fence(entry.runs.single(), StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE) shouldBe null
		fence(entry.runs.single(), StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE).shouldNotBeNull()
	}

	@Test
	fun `trip retention removes exact member and fences original scope while sibling survives`() = runTest {
		val entry = seed(runCount = 2)
		database.stepsGoalEffectDao().recordDecision(goalEffect())
		val first = ready(entry).runs.first()
		database.sessionSegmentDao().deleteOlderThan(41L) shouldBe 0
		database.pruneImportedStepsSegmentsBefore(41L, 100L) shouldBe 1
		database.stepsGoalRepairDayDao().get(0L)?.sourceEvidenceRevision shouldBe 1L
		val retained = ready(entry)
		retained.runs.map { it.identity } shouldBe listOf(entry.runs.last().identity.value)
		retained.portable shouldBe null
		database.sessionSegmentDao().getById(requireNotNull(first.sessionSegmentId)) shouldBe null
		fence(entry.runs.first(), StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE).shouldNotBeNull()
		fence(entry.runs.last(), StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE) shouldBe null
		database.pruneImportedStepsSegmentsBefore(81L, 101L) shouldBe 1
		database.importedStepsDao().entry(entry.identity.value) shouldBe null
		database.stepFactRevisionDao().countAll() shouldBe 0L
		database.pruneImportedStepsSegmentsBefore(81L, 102L) shouldBe 0
	}

	@Test
	fun `trip age never selects foreign payload by wall overlap`() = runTest {
		val entry = seed()
		val unrelatedId = database.sessionSegmentDao().insert(segment(10L, 40L).copy(source = SegmentSource.USER_CREATED))
		database.pruneImportedStepsSegmentsBefore(41L, 100L) shouldBe 1
		database.sessionSegmentDao().getById(unrelatedId).shouldNotBeNull()
		fence(entry.runs.single(), StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE).shouldNotBeNull()
	}

	@Test
	fun `tampered fact prevents raw retention and leaves all payload and markers unchanged`() = runTest {
		seed()
		database.openHelper.writableDatabase.execSQL("UPDATE step_fact_revision SET interval_end_time_ms = interval_end_time_ms + 1")
		val failure = runCatching { database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(41L, 1L, 100L) }
		failure.isFailure shouldBe true
		database.stepFactRevisionDao().countAll() shouldBe 3L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `failed second deletion rolls back first payload receipt and fence`() = runTest {
		val entry = seed(runCount = 2)
		val original = ready(entry)
		database.openHelper.writableDatabase.execSQL(
			"CREATE TRIGGER reject_imported_retention BEFORE DELETE ON imported_steps_run " +
				"WHEN OLD.identity = '${entry.runs.last().identity.value}' BEGIN SELECT RAISE(ABORT, 'test rollback'); END",
		)
		runCatching { database.pruneImportedStepsSegmentsBefore(81L, 100L) }.isFailure shouldBe true
		ready(entry) shouldBe original
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `raw retention rejects mismatched epoch without mutating imported rows`() = runTest {
		val entry = seed()
		runCatching { database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(41L, 2L, 100L) }.isFailure shouldBe true
		ready(entry).portable shouldBe entry
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `failed retained receipt update rolls back raw facts and truncation marker`() = runTest {
		val entry = seed()
		val original = ready(entry)
		database.openHelper.writableDatabase.execSQL(
			"CREATE TRIGGER reject_imported_receipt BEFORE UPDATE OF retained_checksum ON imported_steps_run " +
				"BEGIN SELECT RAISE(ABORT, 'test receipt rollback'); END",
		)
		runCatching { database.pruneAuthenticatedStepsFactsAffectedByRetentionFloor(25L, 1L, 100L) }
			.isFailure shouldBe true
		ready(entry) shouldBe original
		database.stepFactRevisionDao().countAll() shouldBe 3L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `multi-page fact audit reads complete imported entry once per pass not per fact page`() = runTest {
		val denseRun = run(100, 10L).copy(
			endTimeMs = 610L,
			facts = (0 until 600).map { index ->
				PortableStepsFactV1.create(identity(10_000 + index), 1L, 10L + index, 11L + index,
					0L, PortableStepsFactCoverage.COVERED, 1L)
			},
		)
		seed(runs = listOf(denseRun))
		completeFactReads.set(0)
		database.markAuthenticatedStepsRunsAffectedByRetentionFloor(5L, 1L, 100L) shouldBe 0
		// One full authentication pass, then one marking pass. The raw audit spans three pages.
		completeFactReads.get() shouldBe 2
		database.stepFactRevisionDao().countAll() shouldBe 600L
	}

	@Test
	fun `legal multi-entry dependency overflow is split rather than blocking all retention`() = runTest {
		val first = seed(runCount = 33)
		val second = seed(entryIndex = 2, runCount = 33)
		database.pruneImportedStepsSegmentsBefore(2_000L, 3_000L) shouldBe 66
		database.importedStepsDao().entry(first.identity.value) shouldBe null
		database.importedStepsDao().entry(second.identity.value) shouldBe null
		database.stepFactRevisionDao().countAll() shouldBe 0L
		database.sourceDeletionFenceDao().countAll() shouldBe 66L
	}

	private suspend fun read(entry: PortableStepsEntryV1) = database.withTransaction {
		ImportedStepsRetainedReader(database).readEntriesInTransaction(listOf(entry.identity.value))
	}

	private suspend fun ready(entry: PortableStepsEntryV1) = (read(entry) as ImportedStepsRetainedRead.Ready).entries.single()

	private suspend fun fence(run: PortableStepsRunV1, purpose: String) = database.sourceDeletionFenceDao().get(
		SourceDestinationOwnerEntity.SOURCE_STEPS, purpose, SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
		run.deletionScopeDigest.value,
	)

	private suspend fun seed(
		entryIndex: Int = 1,
		runCount: Int = 1,
		runs: List<PortableStepsRunV1> = (0 until runCount).map { runIndex -> run(entryIndex * 100 + runIndex, 10L + runIndex * 40L) },
	): PortableStepsEntryV1 {
		val portable = PortableStepsEntryV1.create(identity(entryIndex), PortableStepsSessionMode.MANUAL,
			runs.first().startTimeMs, runs.last().endTimeMs, runs)
		val metadata = ImportedStepsAdmissionRows.entry(portable, 1L, 2L)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 1L))
		database.withTransaction {
			database.importedStepsDao().insertEntry(metadata)
			for (run in runs) {
				val segmentId = database.sessionSegmentDao().insert(segment(run.startTimeMs, run.endTimeMs).copy(
					logicalTrackingId = metadata.identity, serviceRunId = run.identity.value,
				))
				database.importedStepsDao().insertRun(ImportedStepsAdmissionRows.run(metadata, run, segmentId))
				ImportedStepsAdmissionRows.manifests(run).forEach { database.importedStepsDao().insertManifest(it) }
				run.facts.forEach { database.stepFactRevisionDao().insert(ImportedStepsAdmissionRows.fact(metadata, run, it, 100L)) }
			}
		}
		return portable
	}

	private fun run(index: Int, start: Long) = PortableStepsRunV1(
		identity(index), PortableStepsDeletionScopeDigest(index.toString(16).padStart(64, '0')), start, start + 30L, "Europe/Prague",
		listOf(PortableStepsManifestV1(1L, start, 5L, 3L)),
		PortableStepsCompletenessV1(PortableStepsCaptureCoverage.WHOLE_RUN, PortableStepsProviderCoverage.COMPLETE,
			appDrainComplete = true, stopComplete = true, hasUnresolvedProviderRange = false),
		listOf(
			PortableStepsFactV1.create(identity(index * 100 + 1), 1L, start, start + 10L, 0L, PortableStepsFactCoverage.COVERED, 2L),
			PortableStepsFactV1.create(identity(index * 100 + 2), 1L, start + 10L, start + 20L, 0L, PortableStepsFactCoverage.COVERED, 0L),
			PortableStepsFactV1.create(identity(index * 100 + 3), 1L, start + 20L, start + 30L, 0L, PortableStepsFactCoverage.PARTIAL, null),
		),
	)

	private fun identity(index: Int) = PortableStepsOpaqueIdentity("sha256:${index.toString(16).padStart(64, '0')}")

	private fun segment(start: Long, end: Long) = SessionSegment(
		startTimeMs = start, endTimeMs = end, distanceM = 0f, steps = null,
		primaryActivity = null, activityConfidence = null, sampleCount = 0,
		source = SegmentSource.PORTABLE_STEPS_IMPORT, inferenceVersion = null, createdAt = 100L,
	)

	private fun goalEffect() = StepsGoalEffectEntity(
		effectIdentity = StepsGoalEffectEntity.identity(StepsGoalEffectEntity.PERIOD_DAY, 0L),
		periodKind = StepsGoalEffectEntity.PERIOD_DAY,
		periodStartEpochDay = 0L,
		periodEndEpochDay = 0L,
		qualifiedThroughEpochDay = 0L,
		calendarAuthority = "0=UTC",
		targetSteps = 10_000L,
		weeklyDailyLimitBits = null,
		decisionState = StepsGoalEffectEntity.STATE_READY_INCOMPLETE,
		unavailableReason = null,
		qualifiedSteps = 0L,
		sourceAuthorityDigest = "a".repeat(64),
		sourceEvidenceRevision = 0L,
		effectRevision = 1L,
		completionPointsMicros = 100_000_000L,
		completionXp = 50,
		desiredPointsMicros = 0L,
		desiredXp = 0,
		firstCompletedAtMs = null,
		pointsAppliedRevision = 0L,
		xpAppliedRevision = 0L,
		notificationClaimedRevision = null,
		notificationClaimedAtMs = null,
		updatedAtMs = 0L,
	)
}
