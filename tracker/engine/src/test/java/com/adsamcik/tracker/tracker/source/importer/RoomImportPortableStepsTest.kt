package com.adsamcik.tracker.tracker.source.importer

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.markAuthenticatedStepsRunsAffectedByRetentionFloor
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import com.adsamcik.tracker.stats.api.repository.ExportPortableSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableStepsCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsCompletenessV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsConflictScope
import com.adsamcik.tracker.stats.api.repository.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntrySink
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableStepsImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableStepsManifestV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableStepsProviderCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsRunV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsSessionMode
import com.adsamcik.tracker.stats.api.repository.PortableStepsTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionResult
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionUnsupportedReason
import com.adsamcik.tracker.tracker.source.deletion.RoomStepsSelectedSessionDeletionService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass")
class RoomImportPortableStepsTest {
	private lateinit var database: AppDatabase
	private lateinit var lifecycle: FixedLifecycleStore
	private lateinit var startupGate: ReadyStartupGate
	private lateinit var dirtyTracker: RecordingMetricDirtyTracker

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		lifecycle = FixedLifecycleStore(CollectedDataLifecycleSnapshot(epoch = 3L, retainedFromMs = null))
		startupGate = ReadyStartupGate()
		dirtyTracker = RecordingMetricDirtyTracker()
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
				owner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
				ownerGeneration = OWNER_GENERATION,
				updatedAtMs = 10L,
			),
		) shouldBe 1L
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `applied import preserves portable attribution without fabricating live authority`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 3L),
		)
		database.stepsGoalEffectDao().recordDecision(goalEffect(ENTRY_DAY, sourceRevision = 0L))
		val entry = entry(stepCounts = listOf(4L, 7L))

		subject().importEntry(entry) shouldBe ImportPortableStepsResult.Applied(
			physicalRunCount = 1,
			factCount = 2,
		)

		val storedEntry = requireNotNull(database.importedStepsDao().entry(entry.identity.value))
		storedEntry.contentChecksum shouldBe entry.contentChecksum.value
		storedEntry.collectedDataEpoch shouldBe 3L
		storedEntry.writerOwnerGeneration shouldBe OWNER_GENERATION
		val sourceRun = entry.runs.single()
		val storedRun = requireNotNull(database.importedStepsDao().run(sourceRun.identity.value))
		storedRun.entryIdentity shouldBe entry.identity.value
		storedRun.deletionScopeDigest shouldBe sourceRun.deletionScopeDigest.value
		storedRun.storedZoneId shouldBe "Europe/Prague"
		database.importedStepsDao().manifests(sourceRun.identity.value).single().also { manifest ->
			manifest.revision shouldBe 1L
			manifest.originSourcePolicyRevision shouldBe 7L
			manifest.captureConsentEpoch shouldBe 5L
		}
		val segment = requireNotNull(
			database.sessionSegmentDao().getById(requireNotNull(storedRun.sessionSegmentId)),
		)
		segment.source shouldBe SegmentSource.PORTABLE_STEPS_IMPORT
		segment.sampleCount shouldBe 0
		segment.distanceM shouldBe 0f
		segment.steps shouldBe null
		segment.primaryActivity shouldBe null
		segment.logicalTrackingId shouldBe entry.identity.value
		segment.serviceRunId shouldBe sourceRun.identity.value
		requireNotNull(database.dailySummaryDao().getByDay(ENTRY_DAY)).also { summary ->
			summary.totalDistanceM shouldBe 0f
			summary.totalSteps shouldBe 11
			summary.totalDurationMs shouldBe 0L
			summary.tripCount shouldBe 1
			summary.activeTrackingMs shouldBe 0L
			summary.calendarZoneId shouldBe ENTRY_ZONE_ID
		}
		entry.runs.single().facts.forEach { portable ->
			val fact = requireNotNull(
				database.stepFactRevisionDao().latest(
					SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
					SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
					portable.identity.value,
				),
			)
			fact.originKind shouldBe StepFactRevisionEntity.ORIGIN_PORTABLE_IMPORT
			fact.originIdentity shouldBe portable.identity.value
			fact.writerBindingGeneration shouldBe
				StepFactRevisionIntegrity.PORTABLE_IMPORT_BINDING_GENERATION
			fact.sourceEventId shouldBe null
			fact.sourceAdmissionOrdinal shouldBe null
			fact.intervalStartElapsedRealtimeNanos shouldBe null
			fact.intervalEndElapsedRealtimeNanos shouldBe null
			fact.logicalTrackingId shouldBe entry.identity.value
			fact.serviceRunId shouldBe sourceRun.identity.value
			fact.sourcePolicyRevision shouldBe 7L
			fact.captureConsentEpoch shouldBe 5L
			fact.collectedDataEpoch shouldBe 3L
			fact.effectiveStepCount shouldBe portable.stepCount
		}
		database.sourceEvidenceStateDao().get()?.revision shouldBe 1L
		requireNotNull(database.stepsGoalRepairDayDao().next()).let { queued ->
			queued.epochDay shouldBe ENTRY_DAY
			queued.sourceEvidenceRevision shouldBe 1L
		}
		database.sessionSegmentDao().countTotal() shouldBe 1L
		tableCount("source_service_run") shouldBe 0L
		tableCount("logical_tracking_session") shouldBe 0L
		dirtyTracker.marked.shouldContainExactly(
			setOf(MetricKeys.TABLE_SESSION_SEGMENT, MetricKeys.TABLE_DAILY_SUMMARY),
		)
	}

	@Test
	fun `bounded fact batches remain one atomic imported hierarchy`() = runTest {
		val entry = entry(stepCounts = List(257) { index -> index.toLong() })

		subject().importEntry(entry) shouldBe ImportPortableStepsResult.Applied(1, 257)

		database.stepFactRevisionDao().countAll() shouldBe 257L
		tableCount("imported_steps_entry") shouldBe 1L
		tableCount("imported_steps_run") shouldBe 1L
		tableCount("imported_steps_manifest") shouldBe 1L
		database.sessionSegmentDao().countTotal() shouldBe 1L
	}

	@Test
	fun `selecting one replacement member deletes the complete imported entry and blocks replay`() =
		runTest {
			val entry = replacementEntry()
			subject().importEntry(entry) shouldBe ImportPortableStepsResult.Applied(2, 2)
			val storedRuns = database.importedStepsDao().runsForEntries(
				entryIdentities = listOf(entry.identity.value),
				limit = 3,
			)
			val selectedSegmentId = requireNotNull(storedRuns.last().sessionSegmentId)
			database.stepsGoalEffectDao().recordDecision(goalEffect(ENTRY_DAY, sourceRevision = 1L))
			dirtyTracker.marked.clear()
			var drainRequests = 0

			deletionSubject { drainRequests++ }.deleteSelectedSession(selectedSegmentId) shouldBe
				StepsSessionDeletionResult.Deleted

			tableCount("imported_steps_entry") shouldBe 0L
			tableCount("imported_steps_run") shouldBe 0L
			tableCount("imported_steps_manifest") shouldBe 0L
			database.sessionSegmentDao().countTotal() shouldBe 0L
			requireNotNull(database.stepsGoalRepairDayDao().next()).let { queued ->
				queued.epochDay shouldBe ENTRY_DAY
				queued.sourceEvidenceRevision shouldBe 2L
			}
			database.stepFactRevisionDao().countAll() shouldBe 2L
			entry.runs.forEach { run ->
				val originalDigest = run.deletionScopeDigest.value
				val localDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
					logicalTrackingId = entry.identity.value,
					serviceRunId = run.identity.value,
				)
				database.sourceDeletionFenceDao().contains(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
					SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
					originalDigest,
				) shouldBe true
				database.sourceDeletionFenceDao().contains(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
					SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
					localDigest,
				) shouldBe true
				run.facts.forEach { portableFact ->
					requireNotNull(database.stepFactRevisionDao().latest(
						SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
						SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
						portableFact.identity.value,
					)).also { retained ->
						retained.operation shouldBe StepFactRevisionEntity.OPERATION_RETRACT
						retained.originKind shouldBe StepFactRevisionEntity.ORIGIN_LOCAL_DELETE
						retained.originIdentity shouldBe localDigest
						retained.effectiveStepCount shouldBe null
					}
				}
			}
			database.dailySummaryDao().getByDay(ENTRY_DAY) shouldBe null
			drainRequests shouldBe 0
			dirtyTracker.marked.shouldContainExactly(
				setOf(MetricKeys.TABLE_SESSION_SEGMENT, MetricKeys.TABLE_DAILY_SUMMARY),
			)

			dirtyTracker.marked.clear()
			subject().importEntry(entry) shouldBe ImportPortableStepsResult.DeletedScope
			tableCount("imported_steps_entry") shouldBe 0L
			tableCount("imported_steps_run") shouldBe 0L
			tableCount("imported_steps_manifest") shouldBe 0L
			database.sessionSegmentDao().countTotal() shouldBe 0L
			database.stepFactRevisionDao().countAll() shouldBe 2L
			dirtyTracker.marked shouldBe emptyList()
		}

	@Test
	fun `imported deletion redacts a preserved partial compatibility total`() = runTest {
		upsertDailySummary(totalSteps = 123)
		val entry = entry(captureCoverage = PortableStepsCaptureCoverage.PARTIAL)
		subject().importEntry(entry) shouldBe ImportPortableStepsResult.Applied(1, 1)
		val segmentId = requireNotNull(
			database.importedStepsDao().run(entry.runs.single().identity.value)?.sessionSegmentId,
		)

		deletionSubject().deleteSelectedSession(segmentId) shouldBe StepsSessionDeletionResult.Deleted

		requireNotNull(database.dailySummaryDao().getByDay(ENTRY_DAY)).also { summary ->
			summary.totalSteps shouldBe 0
			summary.activeTrackingMs shouldBe 99L
			summary.calendarZoneId shouldBe ENTRY_ZONE_ID
		}
	}

	@Test
	fun `incomplete imported hierarchy is typed and remains untouched`() = runTest {
		val entry = entry()
		subject().importEntry(entry) shouldBe ImportPortableStepsResult.Applied(1, 1)
		val run = requireNotNull(database.importedStepsDao().run(entry.runs.single().identity.value))
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM imported_steps_manifest WHERE run_identity = ?",
			arrayOf(run.identity),
		)

		deletionSubject().deleteSelectedSession(requireNotNull(run.sessionSegmentId)) shouldBe
			StepsSessionDeletionResult.UnsupportedScope(
				StepsSessionDeletionUnsupportedReason.IMPORTED_AUTHORITY_UNVERIFIABLE,
			)

		tableCount("imported_steps_entry") shouldBe 1L
		tableCount("imported_steps_run") shouldBe 1L
		database.sessionSegmentDao().countTotal() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `extra imported correction lineage blocks deletion before mutation`() = runTest {
		val entry = entry()
		subject().importEntry(entry) shouldBe ImportPortableStepsResult.Applied(1, 1)
		val portableRun = entry.runs.single()
		val storedRun = requireNotNull(database.importedStepsDao().run(portableRun.identity.value))
		val original = requireNotNull(database.stepFactRevisionDao().latest(
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
			portableRun.facts.single().identity.value,
		))
		val localScope = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			entry.identity.value,
			portableRun.identity.value,
		)
		database.stepFactRevisionDao().insert(localRetraction(original, localScope)) shouldNotBe -1L

		deletionSubject().deleteSelectedSession(requireNotNull(storedRun.sessionSegmentId)) shouldBe
			StepsSessionDeletionResult.UnsupportedScope(
				StepsSessionDeletionUnsupportedReason.IMPORTED_AUTHORITY_UNVERIFIABLE,
			)

		tableCount("imported_steps_entry") shouldBe 1L
		tableCount("imported_steps_run") shouldBe 1L
		database.sessionSegmentDao().countTotal() shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `retention loss blocks imported deletion before capture fences or payload removal`() = runTest {
		val entry = entry()
		subject().importEntry(entry) shouldBe ImportPortableStepsResult.Applied(1, 1)
		val storedRun = requireNotNull(database.importedStepsDao().run(entry.runs.single().identity.value))
		database.markAuthenticatedStepsRunsAffectedByRetentionFloor(
			beforeMs = START_MS + 1L,
			collectedDataEpoch = 3L,
			markedAtMs = 50_050L,
		) shouldBe 1

		deletionSubject().deleteSelectedSession(requireNotNull(storedRun.sessionSegmentId)) shouldBe
			StepsSessionDeletionResult.UnsupportedScope(
				StepsSessionDeletionUnsupportedReason.RETENTION_TRUNCATED_HISTORY,
			)

		tableCount("imported_steps_entry") shouldBe 1L
		tableCount("imported_steps_run") shouldBe 1L
		database.sessionSegmentDao().countTotal() shouldBe 1L
		database.stepFactRevisionDao().countAll() shouldBe 1L
		database.sourceDeletionFenceDao().contains(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			entry.runs.single().deletionScopeDigest.value,
		) shouldBe false
	}

	@Test
	fun `cancellation before imported deletion mutation leaves payload and fences untouched`() = runTest {
		val entry = replacementEntry()
		subject().importEntry(entry) shouldBe ImportPortableStepsResult.Applied(2, 2)
		val segmentId = requireNotNull(
			database.importedStepsDao().runsForEntries(listOf(entry.identity.value), 3)
				.last().sessionSegmentId,
		)
		dirtyTracker.marked.clear()

		shouldThrow<CancellationException> {
			deletionSubject(
				beforeMutation = { throw CancellationException("cancel imported deletion") },
			).deleteSelectedSession(segmentId)
		}

		tableCount("imported_steps_entry") shouldBe 1L
		tableCount("imported_steps_run") shouldBe 2L
		tableCount("imported_steps_manifest") shouldBe 2L
		database.sessionSegmentDao().countTotal() shouldBe 2L
		database.stepFactRevisionDao().countAll() shouldBe 2L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		requireNotNull(database.dailySummaryDao().getByDay(ENTRY_DAY)).totalSteps shouldBe 11
		dirtyTracker.marked shouldBe emptyList()
	}

	@Test
	@Suppress("LongMethod")
	fun `deleted imported scope survives Room reopen and still rejects replay`(): Unit = runBlocking {
		val inMemoryDatabase = database
		val context: Application = ApplicationProvider.getApplicationContext()
		val databaseName = "portable-steps-deletion-reopen-${System.nanoTime()}.db"
		val databaseFile = File(System.getProperty("java.io.tmpdir"), databaseName)
		val databaseDirectory = requireNotNull(databaseFile.parentFile)
		check(databaseDirectory.exists() || databaseDirectory.mkdirs())
		check(!databaseFile.exists() || databaseFile.delete())
		var reopenedDatabase: AppDatabase? = null
		try {
			val entry = entry()
			subject().importEntry(entry) shouldBe ImportPortableStepsResult.Applied(1, 1)
			val segmentId = requireNotNull(
				database.importedStepsDao().run(entry.runs.single().identity.value)?.sessionSegmentId,
			)
			deletionSubject().deleteSelectedSession(segmentId) shouldBe StepsSessionDeletionResult.Deleted
			database.openHelper.writableDatabase.execSQL(
				"VACUUM INTO ?",
				arrayOf(databaseFile.path),
			)
			inMemoryDatabase.close()

			reopenedDatabase = AppDatabase.fileBuilder(
				context,
				databaseFile.path,
			)
				.allowMainThreadQueries()
				.build()
			database = reopenedDatabase
			dirtyTracker.marked.clear()

			subject().importEntry(entry) shouldBe ImportPortableStepsResult.DeletedScope

			tableCount("imported_steps_entry") shouldBe 0L
			tableCount("imported_steps_run") shouldBe 0L
			tableCount("imported_steps_manifest") shouldBe 0L
			database.sessionSegmentDao().countTotal() shouldBe 0L
			database.stepFactRevisionDao().countAll() shouldBe 1L
			database.sourceDeletionFenceDao().countAll() shouldBe 2L
			database.dailySummaryDao().getByDay(ENTRY_DAY) shouldBe null
			dirtyTracker.marked shouldBe emptyList()
		} finally {
			reopenedDatabase?.close()
			if (database === inMemoryDatabase) {
				inMemoryDatabase.close()
			}
			database = AppDatabase.testDatabase(context)
			context.deleteDatabase(databaseFile.path)
		}
	}

	@Test
	fun `partial imported Steps cannot create a fabricated compatibility count`() = runTest {
		val candidate = entry(captureCoverage = PortableStepsCaptureCoverage.PARTIAL)

		subject().importEntry(candidate) shouldBe ImportPortableStepsResult.Unverifiable(
			PortableStepsImportUnverifiableReason.DAY_REPAIR_UNVERIFIABLE,
		)

		assertNoImportedPayload()
		database.dailySummaryDao().getByDay(ENTRY_DAY) shouldBe null
		database.sourceEvidenceStateDao().get() shouldBe null
		dirtyTracker.marked shouldBe emptyList()
	}

	@Test
	fun `partial imported Steps preserves an existing compatibility count while repairing other totals`() =
		runTest {
			upsertDailySummary(totalSteps = 123, totalDistanceM = 45f, totalDurationMs = 67L, tripCount = 8)

			subject().importEntry(entry(captureCoverage = PortableStepsCaptureCoverage.PARTIAL)) shouldBe
				ImportPortableStepsResult.Applied(1, 1)

			requireNotNull(database.dailySummaryDao().getByDay(ENTRY_DAY)).also { summary ->
				summary.totalDistanceM shouldBe 0f
				summary.totalSteps shouldBe 123
				summary.totalDurationMs shouldBe 0L
				summary.tripCount shouldBe 1
				summary.activeTrackingMs shouldBe 99L
				summary.calendarZoneId shouldBe ENTRY_ZONE_ID
			}
		}

	@Test
	fun `conflicting persisted calendar authority rejects and rolls back the imported hierarchy`() =
		runTest {
			upsertDailySummary(totalSteps = 123, calendarZoneId = "America/Los_Angeles")

			subject().importEntry(entry()) shouldBe ImportPortableStepsResult.Unverifiable(
				PortableStepsImportUnverifiableReason.DAY_REPAIR_UNVERIFIABLE,
			)

			assertNoImportedPayload()
			requireNotNull(database.dailySummaryDao().getByDay(ENTRY_DAY)).also { summary ->
				summary.totalSteps shouldBe 123
				summary.calendarZoneId shouldBe "America/Los_Angeles"
			}
			database.sourceEvidenceStateDao().get() shouldBe null
			dirtyTracker.marked shouldBe emptyList()
		}

	@Test
	fun `exact replay is a side effect free duplicate`() = runTest {
		val entry = entry()
		val importer = subject()
		importer.importEntry(entry) shouldBe ImportPortableStepsResult.Applied(1, 1)
		val revision = database.sourceEvidenceStateDao().get()?.revision
		dirtyTracker.marked.clear()

		importer.importEntry(entry) shouldBe ImportPortableStepsResult.Duplicate

		tableCount("imported_steps_entry") shouldBe 1L
		tableCount("imported_steps_run") shouldBe 1L
		tableCount("imported_steps_manifest") shouldBe 1L
		database.stepFactRevisionDao().countAll() shouldBe 1L
		database.sessionSegmentDao().countTotal() shouldBe 1L
		database.sourceEvidenceStateDao().get()?.revision shouldBe revision
		dirtyTracker.marked shouldBe emptyList()
	}

	@Test
	fun `malformed retained imported state cannot be accepted as an exact replay`() = runTest {
		val candidate = entry()
		val importer = subject()
		importer.importEntry(candidate) shouldBe ImportPortableStepsResult.Applied(1, 1)
		val revision = database.sourceEvidenceStateDao().get()?.revision
		dirtyTracker.marked.clear()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_steps_run SET app_drain_complete = 2 WHERE identity = ?",
			arrayOf(candidate.runs.single().identity.value),
		)

		importer.importEntry(candidate) shouldBe ImportPortableStepsResult.Unverifiable(
			PortableStepsImportUnverifiableReason.ATTRIBUTION_UNVERIFIABLE,
		)

		tableCount("imported_steps_entry") shouldBe 1L
		tableCount("imported_steps_run") shouldBe 1L
		database.stepFactRevisionDao().countAll() shouldBe 1L
		database.sessionSegmentDao().countTotal() shouldBe 1L
		database.sourceEvidenceStateDao().get()?.revision shouldBe revision
		dirtyTracker.marked shouldBe emptyList()
	}

	@Test
	fun `extra retained correction lineage cannot be collapsed into an exact replay`() = runTest {
		val candidate = entry()
		val importer = subject()
		importer.importEntry(candidate) shouldBe ImportPortableStepsResult.Applied(1, 1)
		val revision = database.sourceEvidenceStateDao().get()?.revision
		val original = requireNotNull(
			database.stepFactRevisionDao().latest(
				SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
				candidate.runs.single().facts.single().identity.value,
			),
		)
		val retraction = localRetraction(
			original = original,
			scopeIdentity = candidate.runs.single().deletionScopeDigest.value,
		)
		(database.stepFactRevisionDao().insert(retraction) > 0L) shouldBe true
		dirtyTracker.marked.clear()

		importer.importEntry(candidate) shouldBe ImportPortableStepsResult.Unverifiable(
			PortableStepsImportUnverifiableReason.ATTRIBUTION_UNVERIFIABLE,
		)

		database.stepFactRevisionDao().countAll() shouldBe 2L
		database.sourceEvidenceStateDao().get()?.revision shouldBe revision
		dirtyTracker.marked shouldBe emptyList()
	}

	@Test
	@Suppress("LongMethod")
	fun `committed imported authority survives Room reopen as a side effect free duplicate`(): Unit =
		runBlocking {
			val inMemoryDatabase = database
			val context: Application = ApplicationProvider.getApplicationContext()
			val databaseName = "portable-steps-import-reopen-${System.nanoTime()}.db"
			val databaseFile = File(System.getProperty("java.io.tmpdir"), databaseName)
			val databaseDirectory = requireNotNull(databaseFile.parentFile)
			check(databaseDirectory.exists() || databaseDirectory.mkdirs())
			check(!databaseFile.exists() || databaseFile.delete())
			var reopenedDatabase: AppDatabase? = null
			try {
				val candidate = entry()
				subject().importEntry(candidate) shouldBe ImportPortableStepsResult.Applied(1, 1)
				val revision = requireNotNull(database.sourceEvidenceStateDao().get()).revision
				database.openHelper.writableDatabase.execSQL(
					"VACUUM INTO ?",
					arrayOf(databaseFile.path),
				)
				inMemoryDatabase.close()

				reopenedDatabase = AppDatabase.fileBuilder(
					context,
					databaseFile.path,
				)
					.allowMainThreadQueries()
					.build()
				database = reopenedDatabase
				dirtyTracker.marked.clear()

				subject().importEntry(candidate) shouldBe ImportPortableStepsResult.Duplicate

				tableCount("imported_steps_entry") shouldBe 1L
				tableCount("imported_steps_run") shouldBe 1L
				database.stepFactRevisionDao().countAll() shouldBe 1L
				database.sessionSegmentDao().countTotal() shouldBe 1L
				requireNotNull(database.dailySummaryDao().getByDay(ENTRY_DAY)).totalSteps shouldBe 11
				database.sourceEvidenceStateDao().get()?.revision shouldBe revision
				dirtyTracker.marked shouldBe emptyList()
			} finally {
				reopenedDatabase?.close()
				if (database === inMemoryDatabase) {
					inMemoryDatabase.close()
				}
				database = AppDatabase.testDatabase(context)
				context.deleteDatabase(databaseFile.path)
			}
		}

	@Test
	fun `same imported logical identity with changed content is a conflict`() = runTest {
		val original = entry()
		val importer = subject()
		importer.importEntry(original) shouldBe ImportPortableStepsResult.Applied(1, 1)
		val revision = database.sourceEvidenceStateDao().get()?.revision
		dirtyTracker.marked.clear()

		importer.importEntry(original.withFirstStepCount(99L)) shouldBe
			ImportPortableStepsResult.Conflict(PortableStepsConflictScope.LOGICAL_ENTRY)

		database.stepFactRevisionDao().countAll() shouldBe 1L
		database.sourceEvidenceStateDao().get()?.revision shouldBe revision
		dirtyTracker.marked shouldBe emptyList()
	}

	@Test
	fun `global authority distinguishes exact duplicate from entry run and fact conflicts`() = runTest {
		val candidate = entry()
		subject(nativeExporter(listOf(candidate))).importEntry(candidate) shouldBe
			ImportPortableStepsResult.Duplicate
		subject(nativeExporter(listOf(candidate.withFirstStepCount(99L)))).importEntry(candidate) shouldBe
			ImportPortableStepsResult.Conflict(PortableStepsConflictScope.LOGICAL_ENTRY)

		val runCollision = entry(
			logicalSeed = "other-logical",
			runIdentity = candidate.runs.single().identity,
			factIdentity = opaque(PortableStepsIdentityKind.FACT, "other-fact"),
			deletionScope = deletionScope("other-logical", "other-run"),
		)
		subject(nativeExporter(listOf(runCollision))).importEntry(candidate) shouldBe
			ImportPortableStepsResult.Conflict(PortableStepsConflictScope.PHYSICAL_RUN)

		val scopeCollision = entry(
			logicalSeed = "scope-logical",
			runIdentity = opaque(PortableStepsIdentityKind.PHYSICAL_RUN, "scope-run"),
			factIdentity = opaque(PortableStepsIdentityKind.FACT, "scope-fact"),
			deletionScope = candidate.runs.single().deletionScopeDigest,
		)
		subject(nativeExporter(listOf(scopeCollision))).importEntry(candidate) shouldBe
			ImportPortableStepsResult.Conflict(PortableStepsConflictScope.PHYSICAL_RUN)

		val factCollision = entry(
			logicalSeed = "fact-logical",
			runIdentity = opaque(PortableStepsIdentityKind.PHYSICAL_RUN, "fact-run"),
			factIdentity = candidate.runs.single().facts.single().identity,
			deletionScope = deletionScope("fact-logical", "fact-run"),
		)
		subject(nativeExporter(listOf(factCollision))).importEntry(candidate) shouldBe
			ImportPortableStepsResult.Conflict(PortableStepsConflictScope.FACT)

		assertNoImportedPayload()
		database.sourceEvidenceStateDao().get() shouldBe null
		dirtyTracker.marked shouldBe emptyList()
	}

	@Test
	fun `capture deletion and retention fences reject the original portable scope`() = runTest {
		val candidate = entry()
		val digest = candidate.runs.single().deletionScopeDigest.value
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity.createForOriginalRunDigest(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				scopeIdentityDigest = digest,
				fenceGeneration = 1L,
				collectedDataEpoch = 3L,
				deletedAtMs = 9_000L,
			),
		) shouldBe 1L

		subject().importEntry(candidate) shouldBe ImportPortableStepsResult.DeletedScope
		assertNoImportedPayload()

		val retainedCandidate = entry(
			logicalSeed = "retained-logical",
			runIdentity = opaque(PortableStepsIdentityKind.PHYSICAL_RUN, "retained-run"),
			factIdentity = opaque(PortableStepsIdentityKind.FACT, "retained-fact"),
			deletionScope = deletionScope("retained-logical", "retained-run"),
		)
		database.sourceDeletionFenceDao().upsert(
			SourceDeletionFenceEntity.createForOriginalRunDigest(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
				scopeIdentityDigest = retainedCandidate.runs.single().deletionScopeDigest.value,
				fenceGeneration = 1L,
				collectedDataEpoch = 3L,
				deletedAtMs = 10_000L,
			),
		)
		subject().importEntry(retainedCandidate) shouldBe ImportPortableStepsResult.OutsideRetention
		assertNoImportedPayload()
	}

	@Test
	fun `retention floor rejects any member whose evidence predates it`() = runTest {
		lifecycle.update(CollectedDataLifecycleSnapshot(epoch = 3L, retainedFromMs = START_MS + 1L))

		subject().importEntry(entry()) shouldBe ImportPortableStepsResult.OutsideRetention

		assertNoImportedPayload()
		database.sourceEvidenceStateDao().get() shouldBe null
	}

	@Test
	fun `cancellation after payload insertion propagates and rolls back the whole entry`() = runTest {
		val importer = subject(afterPayloadInserted = { throw CancellationException("cancel") })

		shouldThrow<CancellationException> { importer.importEntry(entry()) }

		assertNoImportedPayload()
		database.sourceEvidenceStateDao().get() shouldBe null
		dirtyTracker.marked shouldBe emptyList()
	}

	@Test
	fun `storage failure after payload insertion is retryable and rolls back the whole entry`() = runTest {
		val importer = subject(afterPayloadInserted = { throw SQLiteException("forced") })

		importer.importEntry(entry()) shouldBe ImportPortableStepsResult.RetryableFailure(
			PortableStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
		)

		assertNoImportedPayload()
		database.sourceEvidenceStateDao().get() shouldBe null
		dirtyTracker.marked shouldBe emptyList()
	}

	@Test
	fun `lifecycle change after payload insertion is retryable and rolls back day repair`() = runTest {
		val importer = subject(afterPayloadInserted = {
			lifecycle.update(CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = null))
		})

		importer.importEntry(entry()) shouldBe ImportPortableStepsResult.RetryableFailure(
			PortableStepsTransferRetryableReason.CONCURRENT_STATE_CHANGE,
		)

		assertNoImportedPayload()
		database.dailySummaryDao().getByDay(ENTRY_DAY) shouldBe null
		database.sourceEvidenceStateDao().get() shouldBe null
		dirtyTracker.marked shouldBe emptyList()
	}

	@Test
	fun `lifecycle storage failure is typed and cannot touch Room`() = runTest {
		val failingLifecycle = object : CollectedDataLifecycleStore by lifecycle {
			override suspend fun snapshot(): CollectedDataLifecycleSnapshot =
				throw SQLiteException("lifecycle unavailable")
		}

		subject(lifecycleStore = failingLifecycle).importEntry(entry()) shouldBe
			ImportPortableStepsResult.RetryableFailure(
				PortableStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
			)

		assertNoImportedPayload()
		database.sourceEvidenceStateDao().get() shouldBe null
		dirtyTracker.marked shouldBe emptyList()
	}

	@Test
	fun `caller graph mutation during authority preflight is retryable and cannot partially import`() =
		runTest {
			val original = entry()
			val mutableFacts = original.runs.single().facts.toMutableList()
			val mutableRun = original.runs.single().copy(facts = mutableFacts)
			val mutableRuns = mutableListOf(mutableRun)
			val callerEntry = original.copy(runs = mutableRuns)
			val importer = subject(nativeExporter(onExport = { mutableFacts.clear() }))

			importer.importEntry(callerEntry) shouldBe ImportPortableStepsResult.RetryableFailure(
				PortableStepsTransferRetryableReason.CONCURRENT_STATE_CHANGE,
			)

			assertNoImportedPayload()
			database.sourceEvidenceStateDao().get() shouldBe null
			dirtyTracker.marked shouldBe emptyList()
		}

	@Test
	fun `unknown destination owner fails closed without synchronizing lifecycle evidence`() = runTest {
		database.sourceDestinationOwnerDao().compareAndSetOwner(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			expectedOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
			expectedOwnerGeneration = OWNER_GENERATION,
			newOwner = "UNKNOWN_OWNER",
			newOwnerGeneration = OWNER_GENERATION + 1L,
			updatedAtMs = 20L,
		) shouldBe 1

		subject().importEntry(entry()) shouldBe ImportPortableStepsResult.Unverifiable(
			PortableStepsImportUnverifiableReason.ATTRIBUTION_UNVERIFIABLE,
		)

		assertNoImportedPayload()
		database.sourceEvidenceStateDao().get() shouldBe null
		dirtyTracker.marked shouldBe emptyList()
	}

	private fun subject(
		nativeStepsExporter: ExportPortableSteps = nativeExporter(),
		lifecycleStore: CollectedDataLifecycleStore = lifecycle,
		afterDayLocksAcquired: suspend () -> Unit = {},
		beforeMutation: suspend () -> Unit = {},
		afterPayloadInserted: suspend () -> Unit = {},
	) = RoomImportPortableSteps(
		database = database,
		lifecycleStore = lifecycleStore,
		startupGate = startupGate,
		clock = FixedClock(50_000L),
		ioDispatcher = Dispatchers.Unconfined,
		dirtyTracker = dirtyTracker,
		nativeStepsExporter = nativeStepsExporter,
		afterDayLocksAcquired = afterDayLocksAcquired,
		beforeMutation = beforeMutation,
		afterPayloadInserted = afterPayloadInserted,
	)

	private fun deletionSubject(
		requestStepsDrain: () -> Unit = {},
		afterDayLocksAcquired: suspend () -> Unit = {},
		beforeMutation: suspend () -> Unit = {},
	) = RoomStepsSelectedSessionDeletionService(
		database = database,
		dirtyTracker = dirtyTracker,
		wallTimeMsProvider = { 50_100L },
		requestStepsDrain = requestStepsDrain,
		afterDayLocksAcquired = afterDayLocksAcquired,
		beforeMutation = beforeMutation,
	)

	private fun nativeExporter(
		entries: List<PortableStepsEntryV1> = emptyList(),
		result: ExportPortableStepsResult? = null,
		onExport: suspend () -> Unit = {},
	): ExportPortableSteps = object : ExportPortableSteps {
		override suspend fun export(
			request: ExportPortableStepsRequest,
			sink: PortableStepsEntrySink,
		): ExportPortableStepsResult {
			request shouldBe ExportPortableStepsRequest(0L, Long.MAX_VALUE)
			onExport()
			entries.forEach { sink.emit(it) }
			return result ?: if (entries.isEmpty()) {
				ExportPortableStepsResult.NoEntries
			} else {
				ExportPortableStepsResult.Exported(entries.size)
			}
		}
	}

	private suspend fun assertNoImportedPayload() {
		tableCount("imported_steps_entry") shouldBe 0L
		tableCount("imported_steps_run") shouldBe 0L
		tableCount("imported_steps_manifest") shouldBe 0L
		database.stepFactRevisionDao().countAll() shouldBe 0L
		database.sessionSegmentDao().countTotal() shouldBe 0L
	}

	private fun tableCount(table: String): Long {
		val cursor = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table")
		return cursor.use {
			check(it.moveToFirst())
			it.getLong(0)
		}
	}

	@Suppress("LongParameterList")
	private suspend fun upsertDailySummary(
		totalSteps: Int,
		totalDistanceM: Float = 12f,
		totalDurationMs: Long = 34L,
		tripCount: Int = 2,
		calendarZoneId: String = ENTRY_ZONE_ID,
	) {
		database.dailySummaryDao().upsert(
			dateEpochDay = ENTRY_DAY,
			totalDistanceM = totalDistanceM,
			totalSteps = totalSteps,
			totalDurationMs = totalDurationMs,
			tripCount = tripCount,
			activeTrackingMs = 99L,
			lastUpdatedMs = 1L,
			calendarZoneId = calendarZoneId,
		)
	}

	private fun entry(
		logicalSeed: String = SOURCE_LOGICAL_ID,
		runIdentity: PortableStepsOpaqueIdentity =
			opaque(PortableStepsIdentityKind.PHYSICAL_RUN, SOURCE_RUN_ID),
		factIdentity: PortableStepsOpaqueIdentity =
			opaque(PortableStepsIdentityKind.FACT, SOURCE_FACT_ID),
		deletionScope: PortableStepsDeletionScopeDigest = deletionScope(SOURCE_LOGICAL_ID, SOURCE_RUN_ID),
		stepCounts: List<Long> = listOf(11L),
		captureCoverage: PortableStepsCaptureCoverage = PortableStepsCaptureCoverage.WHOLE_RUN,
	): PortableStepsEntryV1 {
		require(stepCounts.isNotEmpty())
		val durationMs = END_MS - START_MS
		val facts = stepCounts.mapIndexed { index, count ->
			val intervalStartTimeMs = START_MS + index.toLong() * durationMs / stepCounts.size
			val intervalEndTimeMs = START_MS + (index + 1L) * durationMs / stepCounts.size
			PortableStepsFactV1.create(
				identity = if (index == 0) factIdentity else {
					opaque(PortableStepsIdentityKind.FACT, "$logicalSeed-fact-$index")
				},
				manifestRevision = 1L,
				intervalStartTimeMs = intervalStartTimeMs,
				intervalEndTimeMs = intervalEndTimeMs,
				wallTimeUncertaintyMs = 25L,
				coverage = PortableStepsFactCoverage.COVERED,
				stepCount = count,
			)
		}
		val run = PortableStepsRunV1(
			identity = runIdentity,
			deletionScopeDigest = deletionScope,
			startTimeMs = START_MS,
			endTimeMs = END_MS,
			storedZoneId = ENTRY_ZONE_ID,
			manifests = listOf(
				PortableStepsManifestV1(
					revision = 1L,
					effectiveWallTimeMs = START_MS,
					originSourcePolicyRevision = 7L,
					captureConsentEpoch = 5L,
				),
			),
			completeness = PortableStepsCompletenessV1(
				captureCoverage = captureCoverage,
				providerCoverage = PortableStepsProviderCoverage.COMPLETE,
				appDrainComplete = true,
				stopComplete = true,
				hasUnresolvedProviderRange = false,
			),
			facts = facts,
		)
		return PortableStepsEntryV1.create(
			identity = opaque(PortableStepsIdentityKind.LOGICAL_ENTRY, logicalSeed),
			sessionMode = PortableStepsSessionMode.MANUAL,
			startTimeMs = START_MS,
			endTimeMs = END_MS,
			runs = listOf(run),
		)
	}

	private fun replacementEntry(): PortableStepsEntryV1 {
		val entryIdentity = opaque(PortableStepsIdentityKind.LOGICAL_ENTRY, SOURCE_LOGICAL_ID)
		val midpoint = START_MS + (END_MS - START_MS) / 2L
		fun run(suffix: String, startMs: Long, endMs: Long, steps: Long): PortableStepsRunV1 {
			val runSeed = "$SOURCE_RUN_ID-$suffix"
			return PortableStepsRunV1(
				identity = opaque(PortableStepsIdentityKind.PHYSICAL_RUN, runSeed),
				deletionScopeDigest = deletionScope(SOURCE_LOGICAL_ID, runSeed),
				startTimeMs = startMs,
				endTimeMs = endMs,
				storedZoneId = ENTRY_ZONE_ID,
				manifests = listOf(
					PortableStepsManifestV1(
						revision = 1L,
						effectiveWallTimeMs = startMs,
						originSourcePolicyRevision = 7L,
						captureConsentEpoch = 5L,
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
						identity = opaque(PortableStepsIdentityKind.FACT, "$SOURCE_FACT_ID-$suffix"),
						manifestRevision = 1L,
						intervalStartTimeMs = startMs,
						intervalEndTimeMs = endMs,
						wallTimeUncertaintyMs = 25L,
						coverage = PortableStepsFactCoverage.COVERED,
						stepCount = steps,
					),
				),
			)
		}
		return PortableStepsEntryV1.create(
			identity = entryIdentity,
			sessionMode = PortableStepsSessionMode.MANUAL,
			startTimeMs = START_MS,
			endTimeMs = END_MS,
			runs = listOf(
				run("a", START_MS, midpoint, 4L),
				run("b", midpoint, END_MS, 7L),
			),
		)
	}

	private fun PortableStepsEntryV1.withFirstStepCount(stepCount: Long): PortableStepsEntryV1 {
		val originalRun = runs.single()
		val originalFact = originalRun.facts.first()
		val changedFact = PortableStepsFactV1.create(
			identity = originalFact.identity,
			manifestRevision = originalFact.manifestRevision,
			intervalStartTimeMs = originalFact.intervalStartTimeMs,
			intervalEndTimeMs = originalFact.intervalEndTimeMs,
			wallTimeUncertaintyMs = originalFact.wallTimeUncertaintyMs,
			coverage = originalFact.coverage,
			stepCount = stepCount,
		)
		val changedRun = originalRun.copy(facts = listOf(changedFact) + originalRun.facts.drop(1))
		return PortableStepsEntryV1.create(
			identity = identity,
			sessionMode = sessionMode,
			startTimeMs = startTimeMs,
			endTimeMs = endTimeMs,
			runs = listOf(changedRun),
		)
	}

	private fun localRetraction(
		original: StepFactRevisionEntity,
		scopeIdentity: String,
	): StepFactRevisionEntity {
		val semanticRevision = original.semanticRevision + 1L
		val scopeDeletionGeneration = original.scopeDeletionGeneration + 1L
		val unsigned = StepFactRevisionEntity(
			logicalFactId = original.logicalFactId,
			semanticRevision = semanticRevision,
			mutationId = StepFactRevisionIntegrity.localDeleteMutationId(
				scopeIdentityDigest = scopeIdentity,
				logicalFactId = original.logicalFactId,
				semanticRevision = semanticRevision,
				scopeDeletionGeneration = scopeDeletionGeneration,
			),
			stepIntervalId = null,
			sourceEventId = null,
			sourceAdmissionOrdinal = null,
			originKind = StepFactRevisionEntity.ORIGIN_LOCAL_DELETE,
			originIdentity = scopeIdentity,
			writerProjectionId = original.writerProjectionId,
			writerProjectionVersion = original.writerProjectionVersion,
			writerBindingGeneration = original.writerBindingGeneration,
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
			purpose = original.purpose,
			manifestRevision = null,
			sourcePolicyRevision = null,
			captureConsentEpoch = null,
			collectedDataEpoch = original.collectedDataEpoch,
			scopeDeletionGeneration = scopeDeletionGeneration,
			effectChecksum = "pending",
			appliedAtMs = 50_001L,
		)
		return unsigned.copy(
			effectChecksum = StepFactRevisionIntegrity.localDeleteEffectChecksum(unsigned),
		)
	}

	private fun goalEffect(day: Long, sourceRevision: Long) = StepsGoalEffectEntity(
		effectIdentity = StepsGoalEffectEntity.identity(StepsGoalEffectEntity.PERIOD_DAY, day),
		periodKind = StepsGoalEffectEntity.PERIOD_DAY,
		periodStartEpochDay = day,
		periodEndEpochDay = day,
		qualifiedThroughEpochDay = day,
		calendarAuthority = "$day=$ENTRY_ZONE_ID",
		targetSteps = 10_000L,
		weeklyDailyLimitBits = null,
		decisionState = StepsGoalEffectEntity.STATE_READY_COMPLETE,
		unavailableReason = null,
		qualifiedSteps = 12_000L,
		sourceAuthorityDigest = "a".repeat(64),
		sourceEvidenceRevision = sourceRevision,
		effectRevision = 1L,
		completionPointsMicros = 100_000_000L,
		completionXp = 50,
		desiredPointsMicros = 100_000_000L,
		desiredXp = 50,
		firstCompletedAtMs = 1L,
		pointsAppliedRevision = 0L,
		xpAppliedRevision = 0L,
		notificationClaimedRevision = null,
		notificationClaimedAtMs = null,
		updatedAtMs = 1L,
	)

	private fun opaque(kind: PortableStepsIdentityKind, value: String) =
		PortableStepsOpaqueIdentity.derive(kind, value)

	private fun deletionScope(logicalId: String, runId: String) =
		PortableStepsDeletionScopeDigest.derive(logicalId, runId)

	private class FixedLifecycleStore(
		initial: CollectedDataLifecycleSnapshot,
	) : CollectedDataLifecycleStore {
		private val state = MutableStateFlow(initial)
		override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state
		override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value
		override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot =
			error("Not used by portable import")
		override suspend fun advanceRetainedFrom(retainedFromMs: Long): CollectedDataLifecycleSnapshot =
			error("Not used by portable import")
		fun update(snapshot: CollectedDataLifecycleSnapshot) {
			state.value = snapshot
		}
	}

	private class ReadyStartupGate : TrackingStartupGate {
		override val isReady: Boolean = true
		override val currentGeneration: Long = 4L
		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			TrackingStartupResult.Ready(false, 0L)
	}

	private class RecordingMetricDirtyTracker : MetricDirtyTracker {
		val marked = mutableListOf<Set<String>>()
		override fun markDirty(table: String) {
			marked += setOf(table)
		}
		override fun markDirty(tables: Set<String>) {
			marked += tables
		}
		override suspend fun snapshotDirty(
			consumer: MetricDirtyTracker.Consumer,
		): MetricDirtyTracker.DirtySnapshot = MetricDirtyTracker.DirtySnapshot(emptyMap())
		override suspend fun acknowledgeDirty(
			consumer: MetricDirtyTracker.Consumer,
			snapshot: MetricDirtyTracker.DirtySnapshot,
		): Boolean = true
	}

	private companion object {
		const val OWNER_GENERATION = 8L
		const val START_MS = 1_000L
		const val END_MS = 2_000L
		const val ENTRY_DAY = 0L
		const val ENTRY_ZONE_ID = "Europe/Prague"
		const val SOURCE_LOGICAL_ID = "source-logical"
		const val SOURCE_RUN_ID = "source-run"
		const val SOURCE_FACT_ID = "source-fact"
	}
}
