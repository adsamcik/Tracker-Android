package com.adsamcik.tracker.shared.base.database.steps.imported

import android.app.Application
import androidx.room.withTransaction
import androidx.room.useReaderConnection
import androidx.room.deferredTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
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
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedStepsRetainedReaderTest {
	private lateinit var database: AppDatabase
	private lateinit var reader: ImportedStepsRetainedReader

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		reader = ImportedStepsRetainedReader(database)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `exact imported rows preserve covered zero and unknown count without runtime authority`() = runTest {
		val portable = seed()
		val result = ready()
		result.portable shouldBe portable
		val facts = result.factsByRun.values.flatten()
		result.metadata.writerOwnerGeneration shouldBe 7L
		facts.all { it.writerBindingGeneration == 1L } shouldBe true
		facts.map { it.effectiveStepCount } shouldBe listOf(0L, null, 0L, null)
		facts.all { it.bootClockDomainId == null && it.sourceEventId == null &&
			it.intervalStartElapsedRealtimeNanos == null && it.cumulativeStepCountEnd == null } shouldBe true
		database.sourceSessionDao().serviceRun(portable.runs.first().identity.value) shouldBe null
		result.segmentsByRun.values.all { it.steps == null && it.sampleCount == 0 } shouldBe true
	}

	@Test
	fun `destination owner generation cannot masquerade as portable writer binding`() = runTest {
		seed()
		val original = ready().factsByRun.values.first().first()
		val changed = original.copy(writerBindingGeneration = 7L)
		val checksum = StepFactRevisionIntegrity.portableImportEffectChecksum(changed)
		StepFactRevisionIntegrity.hasValidPortableImportFact(changed.copy(effectChecksum = checksum)) shouldBe false
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET writer_binding_generation = 7, effect_checksum = ? WHERE logical_fact_id = ?",
			arrayOf(checksum, original.logicalFactId),
		)
		read() shouldBe ImportedStepsRetainedRead.Unverifiable(ImportedStepsReadFailure.INTEGRITY)
	}

	@Test
	fun `aggregate run budget splits without dropping two individually legal entries`() = runTest {
		val entries = listOf('a', 'b').mapIndexed { index, character ->
			val runs = (1..33).map { ordinal ->
				val id = (index * 33 + ordinal).toString(16).padStart(64, '0')
				portableRun('2', '4', '6', 10L).copy(
					identity = PortableStepsOpaqueIdentity("sha256:$id"),
					deletionScopeDigest = PortableStepsDeletionScopeDigest(id), facts = emptyList(),
				)
			}
			PortableStepsEntryV1.create(identity(character), PortableStepsSessionMode.MANUAL, 10L, 30L, runs)
		}
		entries.forEach { seed(portableEntry = it) }
		val result = database.withTransaction { reader.readEntriesInTransaction(entries.map { it.identity.value }) }
		val ready = result as ImportedStepsRetainedRead.Ready
		ready.entries.map { it.runs.size } shouldBe listOf(33, 33)
		ready.unverifiableEntries shouldBe emptyMap()
		ready.entries.map { it.portable }.toSet() shouldBe entries.toSet()
	}

	@Test
	fun `member checksum permits surviving sibling but refuses original whole export`() = runTest {
		val portable = seed()
		val removed = ready().runs.first()
		database.openHelper.writableDatabase.execSQL("DELETE FROM step_fact_revision WHERE service_run_id = ?", arrayOf(removed.identity))
		database.importedStepsDao().deleteRunExact(removed.identity, removed.entryIdentity,
			requireNotNull(removed.sessionSegmentId), requireNotNull(removed.retainedChecksum)) shouldBe 1
		database.sessionSegmentDao().deleteById(requireNotNull(removed.sessionSegmentId))
		val retained = ready()
		retained.runs.size shouldBe 1
		retained.portable shouldBe null
		retained.portableRunsById.values.single() shouldBe portable.runs.last()
	}

	@Test
	fun `tampered original metadata run receipt and reverse binding are unverifiable`() = runTest {
		seed()
		val original = ready()
		val run = original.runs.first()
		val sql = database.openHelper.writableDatabase
		sql.execSQL("UPDATE imported_steps_entry SET end_time_ms = end_time_ms + 1")
		read() shouldBe ImportedStepsRetainedRead.Unverifiable(ImportedStepsReadFailure.INTEGRITY)
		sql.execSQL("UPDATE imported_steps_entry SET end_time_ms = end_time_ms - 1")
		sql.execSQL("UPDATE imported_steps_run SET retained_checksum = ? WHERE identity = ?", arrayOf("f".repeat(64), run.identity))
		read() shouldBe ImportedStepsRetainedRead.Unverifiable(ImportedStepsReadFailure.INTEGRITY)
		sql.execSQL("UPDATE imported_steps_run SET retained_checksum = ? WHERE identity = ?", arrayOf(run.retainedChecksum, run.identity))
		sql.execSQL("UPDATE session_segment SET logical_tracking_id = 'other' WHERE id = ?", arrayOf(run.sessionSegmentId))
		read() shouldBe ImportedStepsRetainedRead.Unverifiable(ImportedStepsReadFailure.INTEGRITY)
	}

	@Test
	fun `full clear fences original imported scopes across repeated clears and epoch changes`() = runTest {
		val portable = seed()
		AppDatabase.deleteAllCollectedData(database, 2L, null, 500L)
		AppDatabase.deleteAllCollectedData(database, 3L, null, 600L)
		for (run in portable.runs) {
			val fence = database.sourceDeletionFenceDao().get(SourceDestinationOwnerEntity.SOURCE_STEPS,
				StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE, SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				run.deletionScopeDigest.value).shouldNotBeNull()
			fence.collectedDataEpoch shouldBe 2L
			fence.deletedAtMs shouldBe 500L
		}
		database.importedStepsDao().entry(portable.identity.value) shouldBe null
		database.stepFactRevisionDao().countAll() shouldBe 0L
		// Reinsert the same minimized file in a new epoch: the exact original scope still rejects it.
		seed(epoch = 3L)
		read() shouldBe ImportedStepsRetainedRead.Unverifiable(ImportedStepsReadFailure.DELETION)
	}

	@Test
	fun `retention preflight authenticates before normal floor rejection`() = runTest {
		seed()
		database.sourceEvidenceStateDao().updateLifecycle(1L, 15L, 100L)
		read() shouldBe ImportedStepsRetainedRead.Unverifiable(ImportedStepsReadFailure.RETENTION)
		database.withTransaction { reader.readEntriesForRetentionInTransaction(listOf(identity('1').value)) }
			.let { (it as ImportedStepsRetainedRead.Ready).entries.size shouldBe 1 }
	}

	@Test
	fun `coroutine reader transaction and writer transaction produce the same authenticated snapshot`() = runTest {
		seed()
		val writer = read()
		database.useReaderConnection { connection ->
			connection.deferredTransaction {
				reader.readEntriesInTransaction(listOf(identity('1').value)) shouldBe writer
			}
		}
	}

	@Test
	fun `out of Int writer version cannot narrow into valid imported provenance`() = runTest {
		seed()
		database.openHelper.writableDatabase.execSQL("UPDATE step_fact_revision SET writer_projection_version = 4294967297")
		read() shouldBe ImportedStepsRetainedRead.Unverifiable(ImportedStepsReadFailure.INTEGRITY)
	}

	@Test
	fun `raw boolean and numeric presentation corruption cannot narrow into authentic values`() = runTest {
		seed()
		val sql = database.openHelper.writableDatabase
		sql.execSQL("UPDATE imported_steps_run SET app_drain_complete = 2")
		read() shouldBe ImportedStepsRetainedRead.Unverifiable(ImportedStepsReadFailure.INTEGRITY)
		sql.execSQL("UPDATE imported_steps_run SET app_drain_complete = 1")
		sql.execSQL("UPDATE session_segment SET sample_count = 4294967296")
		read() shouldBe ImportedStepsRetainedRead.Unverifiable(ImportedStepsReadFailure.INTEGRITY)
		sql.execSQL("UPDATE session_segment SET sample_count = 0, distance_m = 1e-99")
		read() shouldBe ImportedStepsRetainedRead.Unverifiable(ImportedStepsReadFailure.INTEGRITY)
		sql.execSQL("UPDATE session_segment SET distance_m = 0")
		ready().portable.shouldNotBeNull()
	}

	@Test
	fun `malformed stored zone becomes typed integrity failure`() = runTest {
		seed()
		database.openHelper.writableDatabase.execSQL("UPDATE imported_steps_run SET stored_zone_id = 'invalid-zone'")
		read() shouldBe ImportedStepsRetainedRead.Unverifiable(ImportedStepsReadFailure.INTEGRITY)
	}

	@Test
	fun `malformed zone in one entry does not hide an independently authenticated peer`() = runTest {
		val damaged = seed()
		val healthy = PortableStepsEntryV1.create(identity('f'), PortableStepsSessionMode.MANUAL, 60L, 80L,
			listOf(portableRun('a', 'b', 'c', 60L)))
		seed(portableEntry = healthy)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_steps_run SET stored_zone_id = 'invalid-zone' WHERE identity = ?",
			arrayOf(damaged.runs.first().identity.value),
		)
		val result = database.withTransaction {
			reader.readEntriesInTransaction(listOf(damaged.identity.value, healthy.identity.value))
		} as ImportedStepsRetainedRead.Ready
		result.entries.single().portable shouldBe healthy
		result.unverifiableEntries shouldBe mapOf(damaged.identity.value to ImportedStepsReadFailure.INTEGRITY)
	}

	@Test
	fun `corrupt original deletion digest aborts full clear atomically instead of dropping its authority`() = runTest {
		val portable = seed()
		database.openHelper.writableDatabase.execSQL("UPDATE imported_steps_run SET deletion_scope_digest = 'corrupt' WHERE identity = ?",
			arrayOf(portable.runs.first().identity.value))
		shouldThrow<IllegalArgumentException> { AppDatabase.deleteAllCollectedData(database, 2L, null, 500L) }
		database.sourceEvidenceStateDao().get().shouldNotBeNull().collectedDataEpoch shouldBe 1L
		database.importedStepsDao().entry(portable.identity.value).shouldNotBeNull()
		database.stepFactRevisionDao().countAll() shouldBe 4L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `authenticated retention marker permits straddles but never expired facts or whole export`() = runTest {
		seed()
		val retained = ready()
		val firstRun = retained.runs.first()
		database.sourceEvidenceStateDao().updateLifecycle(1L, 15L, 100L)
		database.sourceDeletionFenceDao().insertIfAbsent(SourceDeletionFenceEntity.createForOriginalRunDigest(
			SourceDestinationOwnerEntity.SOURCE_STEPS, StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
			firstRun.deletionScopeDigest, 1L, 1L, 100L,
		))
		ready().retentionTruncatedRunIds shouldBe setOf(firstRun.identity)
		ready().portable shouldBe null
		database.sourceEvidenceStateDao().updateLifecycle(1L, 21L, 101L)
		read() shouldBe ImportedStepsRetainedRead.Unverifiable(ImportedStepsReadFailure.RETENTION)
	}

	@Test
	fun `full clear preserves exact live capture export scopes without promoting control`() = runTest {
		seedLiveMembership("capture", "SESSION_CAPTURE", true)
		seedLiveMembership("control", "CONTROL", false)
		seedLiveMembership("disabled", "SESSION_CAPTURE", false)
		AppDatabase.deleteAllCollectedData(database, 2L, null, 500L)
		AppDatabase.deleteAllCollectedData(database)
		for (name in listOf("capture", "control", "disabled")) {
			val digest = PortableStepsDeletionScopeDigest.derive("entry-$name", "run-$name")
			database.sourceDeletionFenceDao().contains(SourceDestinationOwnerEntity.SOURCE_STEPS,
				StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE, SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				digest.value) shouldBe (name == "capture")
		}
		database.sourceDeletionFenceDao().countAll() shouldBe 1L
		database.sourceSessionDao().serviceRun("run-capture") shouldBe null
	}

	private suspend fun seedLiveMembership(name: String, purpose: String, persistenceEligible: Boolean) {
		val logical = "entry-$name"
		val run = "run-$name"
		val dao = database.sourceSessionDao()
		dao.insertServiceRun(SourceServiceRunEntity(run, logical, "PREPARED", 1L, 1L, 0L, 10L, 10L, null, null))
		dao.insertManifest(SessionManifestVersionEntity(logical, 1L, run, "MANUAL", 1L, 1L, 1L,
			"MANUAL", "boot", 10L, 10L, "UTC", null, "START", "test-checksum"))
		dao.insertManifestSources(listOf(SessionManifestSourceEntity(logical, 1L, SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose, 1L, persistenceEligible, 1,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS.takeIf { persistenceEligible },
			writerOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL.takeIf { persistenceEligible },
			writerOwnerGeneration = 1L.takeIf { persistenceEligible })))
	}

	private suspend fun read() = database.withTransaction { reader.readEntriesInTransaction(listOf(identity('1').value)) }
	private suspend fun ready() = (read() as ImportedStepsRetainedRead.Ready).entries.single()

	private suspend fun seed(epoch: Long = 1L, portableEntry: PortableStepsEntryV1? = null): PortableStepsEntryV1 {
		val runs = portableEntry?.runs ?: listOf(portableRun('2', '4', '6', 10L), portableRun('3', '5', '7', 30L))
		val portable = portableEntry ?: PortableStepsEntryV1.create(identity('1'), PortableStepsSessionMode.MANUAL, 10L, 50L, runs)
		val metadata = ImportedStepsAdmissionRows.entry(portable, epoch, 7L)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = epoch))
		database.withTransaction {
			database.importedStepsDao().insertEntry(metadata)
			for (run in runs) {
				val segmentId = database.sessionSegmentDao().insert(SessionSegment(
					startTimeMs = run.startTimeMs, endTimeMs = run.endTimeMs, distanceM = 0f,
					steps = null, primaryActivity = null, activityConfidence = null, sampleCount = 0,
					source = SegmentSource.PORTABLE_STEPS_IMPORT, inferenceVersion = null, createdAt = 100L,
					logicalTrackingId = metadata.identity, serviceRunId = run.identity.value,
				))
				database.importedStepsDao().insertRun(ImportedStepsAdmissionRows.run(metadata, run, segmentId))
				ImportedStepsAdmissionRows.manifests(run).forEach { database.importedStepsDao().insertManifest(it) }
				run.facts.forEach { database.stepFactRevisionDao().insert(ImportedStepsAdmissionRows.fact(metadata, run, it, 100L)) }
			}
		}
		return portable
	}

	private fun portableRun(run: Char, scope: Char, fact: Char, start: Long) = PortableStepsRunV1(
		identity(run), PortableStepsDeletionScopeDigest(scope.toString().repeat(64)), start, start + 20L, "Europe/Prague",
		listOf(PortableStepsManifestV1(1L, start, 5L, 3L)),
		PortableStepsCompletenessV1(PortableStepsCaptureCoverage.WHOLE_RUN, PortableStepsProviderCoverage.PARTIAL,
			appDrainComplete = true, stopComplete = true, hasUnresolvedProviderRange = true),
		listOf(
			PortableStepsFactV1.create(identity(fact), 1L, start, start + 10L, 0L, PortableStepsFactCoverage.COVERED, 0L),
			PortableStepsFactV1.create(identity(fact + 2), 1L, start + 10L, start + 20L, 0L, PortableStepsFactCoverage.PARTIAL, null),
		),
	)

	private fun identity(character: Char) = PortableStepsOpaqueIdentity("sha256:${character.toString().repeat(64)}")
}
