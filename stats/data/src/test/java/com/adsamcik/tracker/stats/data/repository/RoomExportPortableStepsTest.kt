package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableStepsExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableStepsTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV1
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass", "TooManyFunctions")
class RoomExportPortableStepsTest {
	private lateinit var database: AppDatabase
	private lateinit var exporter: RoomExportPortableSteps
	private var fileDatabaseName: String? = null

	@Before
	fun setUp() = runTest { initializeDatabase() }

	private suspend fun initializeDatabase() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		database.sourceProjectionStateDao().installProductLane(productLane())
		exporter = createExporter()
	}

	private suspend fun resetDatabase() {
		database.close()
		initializeDatabase()
	}

	@After
	fun tearDown() {
		database.close()
		fileDatabaseName?.let { name ->
			ApplicationProvider.getApplicationContext<Application>().deleteDatabase(name)
		}
	}

	@Test
	fun `zero-sample Steps-only entry exports positive and covered-zero without fabricated siblings`() =
		runTest {
			insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 2_000L)
			insertSettledRun(
				logicalId = LOGICAL_ONE,
				runId = RUN_ONE,
				segmentId = 41L,
				manifestRevision = 1L,
				startMs = 1_000L,
				endMs = 2_000L,
				sampleCount = 0,
				includeControlMembership = true,
				facts = listOf(
					FactSeed("baseline", 1L, coverage = StepFactRevisionEntity.COVERAGE_BASELINE),
					FactSeed("positive", 2L, count = 7L),
					FactSeed("zero", 3L, count = 0L),
					FactSeed("reset", 4L, coverage = StepFactRevisionEntity.COVERAGE_RESET_GAP),
					FactSeed("partial", 5L, count = 2L, coverage = StepFactRevisionEntity.COVERAGE_PARTIAL),
				),
			)

			val entries = mutableListOf<com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1>()
			val result = exporter.export(request()) { entry ->
				database.inTransaction() shouldBe false
				entries += entry
			}

			result shouldBe ExportPortableStepsResult.Exported(1)
			val run = entries.single().runs.single()
			run.facts.map { fact -> fact.coverage to fact.stepCount } shouldContainExactly listOf(
				PortableStepsFactCoverage.BASELINE to null,
				PortableStepsFactCoverage.COVERED to 7L,
				PortableStepsFactCoverage.COVERED to 0L,
				PortableStepsFactCoverage.RESET_GAP to null,
				PortableStepsFactCoverage.PARTIAL to null,
			)
			run.manifests.all { manifest -> manifest.source.name == "STEPS" } shouldBe true
		}

	@Test
	fun `range discovery expands exact replacement runs in canonical order`() = runTest {
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 3_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = "later-run",
			segmentId = 42L,
			manifestRevision = 2L,
			startMs = 2_000L,
			endMs = 3_000L,
			facts = listOf(FactSeed("later-fact", 2L, count = 4L)),
		)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = "earlier-run",
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 1_500L,
			facts = listOf(
				FactSeed("restart-baseline", 1L, coverage = StepFactRevisionEntity.COVERAGE_BASELINE),
			),
		)

		val entries = mutableListOf<com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1>()
		val result = exporter.export(ExportPortableStepsRequest(1_100L, 1_200L)) { entries += it }

		result shouldBe ExportPortableStepsResult.Exported(1)
		entries.single().runs.map { run -> run.identity } shouldContainExactly listOf(
			PortableStepsOpaqueIdentity.derive(
				com.adsamcik.tracker.stats.api.repository.PortableStepsIdentityKind.PHYSICAL_RUN,
				"earlier-run",
			),
			PortableStepsOpaqueIdentity.derive(
				com.adsamcik.tracker.stats.api.repository.PortableStepsIdentityKind.PHYSICAL_RUN,
				"later-run",
			),
		)
	}

	@Test
	fun `unbound no-fact Steps run is materializing rather than an empty export`() = runTest {
		insertLogicalSession(
			logicalId = LOGICAL_ONE,
			startMs = 1_000L,
			endMs = null,
			state = "ACTIVE",
			currentRunId = RUN_ONE,
		)
		insertRunManifest(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = null,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = null,
			state = "ACTIVE",
		)
		val emitted = mutableListOf<Any>()

		val result = exporter.export(request()) { emitted += it }

		result shouldBe ExportPortableStepsResult.Unverifiable(
			PortableStepsExportUnverifiableReason.ENTRY_MATERIALIZING,
		)
		emitted shouldBe emptyList()
	}

	@Test
	fun `pending presentation and lagging exact lane remain materializing`() = runTest {
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 2_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 2_000L,
			facts = listOf(FactSeed("covered", 1L, count = 1L)),
		)
		val run = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ONE))
		database.sourceSessionDao().updateServiceRun(
			run.copy(
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
				presentationAcknowledgedAtMs = null,
			),
		)

		exporter.export(request()) {} shouldBe ExportPortableStepsResult.Unverifiable(
			PortableStepsExportUnverifiableReason.ENTRY_MATERIALIZING,
		)

		resetDatabase()
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 2_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 2_000L,
			facts = listOf(FactSeed("covered", 1L, count = 1L)),
		)
		database.sourceProjectionStateDao().deleteAllProductLanes()
		database.sourceProjectionStateDao().installProductLane(
			productLane().copy(contiguousAdmissionOrdinal = 0L),
		)
		exporter.export(request()) {} shouldBe ExportPortableStepsResult.Unverifiable(
			PortableStepsExportUnverifiableReason.ENTRY_MATERIALIZING,
		)
	}

	@Test
	fun `latest correction is exported`() = runTest {
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 2_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 2_000L,
			facts = listOf(
				FactSeed("corrected", 1L, semanticRevision = 1L, count = 4L),
				FactSeed("corrected", 2L, semanticRevision = 2L, count = 9L),
			),
		)
		val entries = mutableListOf<com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1>()

		exporter.export(request()) { entries += it } shouldBe ExportPortableStepsResult.Exported(1)

		entries.single().runs.single().facts.map { fact -> fact.stepCount } shouldBe listOf(9L)
	}

	@Test
	fun `surviving retraction without exact production fence fails before emission`() = runTest {
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 2_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 2_000L,
			facts = listOf(FactSeed("deleted", 1L, count = 5L)),
		)
		database.stepFactRevisionDao().insert(retraction("deleted", semanticRevision = 2L))

		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}

	@Test
	fun `attribution disagreement and portable-import origin fail before sink emission`() = runTest {
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 2_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 2_000L,
			facts = listOf(FactSeed("bad-policy", 1L, count = 1L, sourcePolicyRevision = 2L)),
		)
		val emitted = mutableListOf<Any>()

		exporter.export(request()) { emitted += it } shouldBe ExportPortableStepsResult.Unverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
		emitted shouldBe emptyList()

		resetDatabase()
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 2_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 2_000L,
			facts = listOf(FactSeed("imported", 1L, count = 1L, portableImport = true)),
		)
		exporter.export(request()) { emitted += it } shouldBe ExportPortableStepsResult.Unverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
		emitted shouldBe emptyList()
	}

	@Test
	fun `foreign writer portable import in the same run is rejected rather than filtered`() = runTest {
		insertReadyFixture(
			facts = listOf(
				FactSeed("covered", 1L, count = 1L),
				FactSeed(
					logicalFactId = "foreign-import",
					ordinal = 2L,
					count = 7L,
					portableImport = true,
					writerProjectionId = "foreign-steps-writer",
					writerProjectionVersion = 7,
					writerBindingGeneration = 9L,
				),
			),
		)

		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
	}

	@Test
	fun `same-run writer projection version and binding must each match the manifest`() = runTest {
		val mismatches = listOf(
			FactSeed("wrong-projection", 2L, count = 1L, writerProjectionId = "foreign-writer"),
			FactSeed("wrong-version", 2L, count = 1L, writerProjectionVersion = WRITER_VERSION + 1),
			FactSeed("wrong-binding", 2L, count = 1L, writerBindingGeneration = BINDING_GENERATION + 1L),
		)
		mismatches.forEachIndexed { index, mismatch ->
			if (index > 0) {
				resetDatabase()
			}
			insertReadyFixture(facts = listOf(FactSeed("covered", 1L, count = 1L), mismatch))
			assertZeroSinkUnverifiable(
				PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
			)
		}

		resetDatabase()
		insertReadyFixture(
			facts = listOf(
				FactSeed(
					"historical-binding",
					1L,
					semanticRevision = 1L,
					count = 1L,
					writerBindingGeneration = BINDING_GENERATION + 1L,
				),
				FactSeed("historical-binding", 2L, semanticRevision = 2L, count = 2L),
			),
		)
		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
	}

	@Test
	@Suppress("LongMethod")
	fun `fact ordinals require exact executable lane and completeness authority`() = runTest {
		insertReadyFixture()
		replaceProductLane(productLane(activationOrdinal = 2L))
		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)

		resetDatabase()
		insertReadyFixture(facts = listOf(FactSeed("covered", 2L, count = 1L)))
		replaceProductLane(
			productLane(
				contiguousAdmissionOrdinal = 1L,
				captureAdmissionCutoffOrdinal = 1L,
			),
		)
		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)

		resetDatabase()
		insertReadyFixture()
		replaceProductLane(productLane(contiguousAdmissionOrdinal = 0L))
		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.ENTRY_MATERIALIZING)

		resetDatabase()
		insertReadyFixture(facts = listOf(FactSeed("covered", 2L, count = 1L)))
		val belowFact = stepsCompleteness().copy(lastAdmissionOrdinal = 1L, lastSourceSequence = 1L)
		database.sourceSessionDao().saveCompleteness(belowFact)
		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)

		resetDatabase()
		insertReadyFixture(sessionMode = "AUTOMATIC")
		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)

		resetDatabase()
		insertReadyFixture(rolloutRevision = 1L)
		replaceProductLane(productLane(activatedRolloutRevision = 2L))
		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)

		resetDatabase()
		insertReadyFixture()
		replaceProductLane(
			productLane().copy(
				status = SourceProductProjectionLaneEntity.STATUS_RETIRED,
				retentionRequired = false,
			),
		)
		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}

	@Test
	@Suppress("LongMethod")
	fun `completeness high waters ranges and terminal fields fail closed`() = runTest {
		val mutations = listOf<(SourceSessionCompletenessEntity) -> SourceSessionCompletenessEntity>(
			{ row -> row.copy(logicalTrackingId = "other-logical") },
			{ row -> row.copy(sourceInstanceId = "") },
			{ row -> row.copy(registrationGeneration = 0L) },
			{ row -> row.copy(lastAdmissionOrdinal = null, lastSourceSequence = null) },
			{ row -> row.copy(lastSourceSequence = null) },
			{ row -> row.copy(lastAdmissionOrdinal = 0L, lastSourceSequence = 0L) },
			{ row -> row.copy(unresolvedSequenceStart = 0L, unresolvedSequenceEnd = 0L) },
			{ row -> row.copy(unresolvedSequenceStart = 3L, unresolvedSequenceEnd = 2L) },
			{ row -> row.copy(unresolvedSequenceStart = 3L, unresolvedSequenceEnd = null) },
			{ row -> row.copy(appDrainComplete = false, stopStatus = "COMPLETE") },
			{ row -> row.copy(providerCoverage = "UNKNOWN") },
			{ row -> row.copy(stopStatus = "UNKNOWN") },
			{ row -> row.copy(updatedAtMs = -1L) },
		)
		mutations.forEachIndexed { index, mutate ->
			if (index > 0) {
				resetDatabase()
			}
			insertReadyFixture()
			database.sourceSessionDao().saveCompleteness(mutate(stepsCompleteness()))
			assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}

		resetDatabase()
		insertReadyFixture()
		database.sourceSessionDao().saveCompleteness(
			stepsCompleteness().copy(sourceInstanceId = "duplicate-terminal", registrationGeneration = 2L),
		)
		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}

	@Test
	@Suppress("LongMethod")
	fun `discovered segments require exact bidirectional membership`() = runTest {
		insertReadyFixture()
		val run = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ONE))
		database.sourceSessionDao().updateServiceRun(run.copy(sessionSegmentId = 42L))
		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)

		resetDatabase()
		insertReadyFixture()
		val wrongLogical = requireNotNull(database.sessionSegmentDao().getById(41L)).copy(
			logicalTrackingId = "other-logical",
		)
		database.sessionSegmentDao().update(wrongLogical)
		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)

		resetDatabase()
		insertReadyFixture()
		val partial = requireNotNull(database.sessionSegmentDao().getById(41L)).copy(serviceRunId = null)
		database.sessionSegmentDao().update(partial)
		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)

		resetDatabase()
		insertReadyFixture()
		val blankPartial = requireNotNull(database.sessionSegmentDao().getById(41L)).copy(
			logicalTrackingId = "",
			serviceRunId = null,
		)
		database.sessionSegmentDao().update(blankPartial)
		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)

		resetDatabase()
		insertReadyFixture()
		val bothBlank = requireNotNull(database.sessionSegmentDao().getById(41L)).copy(
			logicalTrackingId = "",
			serviceRunId = "",
		)
		database.sessionSegmentDao().update(bothBlank)
		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)

		resetDatabase()
		insertReadyFixture()
		val blankLogicalRun = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ONE)).copy(
			logicalTrackingId = "",
		)
		database.sourceSessionDao().updateServiceRun(blankLogicalRun)
		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)

		resetDatabase()
		insertReadyFixture()
		val orphan = requireNotNull(database.sessionSegmentDao().getById(41L)).copy(
			id = 42L,
			logicalTrackingId = "orphan-logical",
			serviceRunId = "orphan-run",
		)
		database.sessionSegmentDao().insert(orphan)
		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)

		resetDatabase()
		insertReadyFixture()
		database.sessionSegmentDao().deleteById(41L)
		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}

	@Test
	fun `later storage validation failure emits none of the already-valid snapshot`() = runTest {
		insertLogicalSession("logical-valid", startMs = 1_000L, endMs = 2_000L)
		insertSettledRun(
			logicalId = "logical-valid",
			runId = "run-valid",
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 2_000L,
			facts = listOf(FactSeed("valid", 1L, count = 1L)),
		)
		insertLogicalSession("logical-invalid", startMs = 2_000L, endMs = 3_000L)
		insertSettledRun(
			logicalId = "logical-invalid",
			runId = "run-invalid",
			segmentId = 42L,
			manifestRevision = 2L,
			startMs = 2_000L,
			endMs = 3_000L,
			facts = listOf(FactSeed("invalid", 2L, count = 1L, sourcePolicyRevision = 3L)),
		)
		val emitted = mutableListOf<Any>()

		exporter.export(request(toMs = 4_000L)) { emitted += it } shouldBe
			ExportPortableStepsResult.Unverifiable(
				PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
			)
		emitted shouldBe emptyList()
	}

	@Test
	@Suppress("LongMethod")
	fun `mixed deleted replacement fails while all-deleted logical entry is omitted`() =
		runTest {
			insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 3_000L)
			insertSettledRun(
				logicalId = LOGICAL_ONE,
				runId = RUN_ONE,
				segmentId = 41L,
				manifestRevision = 1L,
				startMs = 1_000L,
				endMs = 2_000L,
				facts = listOf(FactSeed("kept", 1L, count = 3L)),
			)
			insertRunManifest(
				logicalId = LOGICAL_ONE,
				runId = "missing-run",
				segmentId = 42L,
				manifestRevision = 2L,
				startMs = 2_000L,
				endMs = 3_000L,
			)
			insertFence(LOGICAL_ONE, "missing-run")
			assertZeroSinkUnverifiable(
				reason = PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
				selection = request(toMs = 4_000L),
			)

			resetDatabase()
			insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 3_000L)
			insertSettledRun(
				logicalId = LOGICAL_ONE,
				runId = RUN_ONE,
				segmentId = 41L,
				manifestRevision = 1L,
				startMs = 1_000L,
				endMs = 2_000L,
				facts = listOf(FactSeed("kept", 1L, count = 3L)),
			)
			insertRunManifest(
				logicalId = LOGICAL_ONE,
				runId = "missing-run",
				segmentId = 42L,
				manifestRevision = 2L,
				startMs = 2_000L,
				endMs = 3_000L,
			)
			exporter.export(request(toMs = 4_000L)) {} shouldBe ExportPortableStepsResult.Unverifiable(
				PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
			)

			resetDatabase()
			insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 2_000L)
			insertSettledRun(
				logicalId = LOGICAL_ONE,
				runId = RUN_ONE,
				segmentId = 41L,
				manifestRevision = 1L,
				startMs = 1_000L,
				endMs = 2_000L,
				facts = listOf(FactSeed("fenced", 1L, count = 3L)),
			)
			insertFence(LOGICAL_ONE, RUN_ONE)
			exporter.export(request()) {} shouldBe ExportPortableStepsResult.NoEntries
		}

	@Test
	fun `deleted fence cannot hide a same-run foreign writer fact`() = runTest {
		insertReadyFixture(
			facts = listOf(
				FactSeed("covered", 1L, count = 1L),
				FactSeed("foreign", 2L, count = 1L, writerProjectionId = "foreign-writer"),
			),
		)
		insertFence(LOGICAL_ONE, RUN_ONE)

		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
	}

	@Test
	fun `deleted run retraction must match the exact fence writer generation and epoch`() = runTest {
		insertReadyFixture()
		database.stepFactRevisionDao().insert(
			retraction("covered", semanticRevision = 2L, deletionGeneration = 2L),
		)
		insertFence(LOGICAL_ONE, RUN_ONE)
		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)

		resetDatabase()
		insertReadyFixture()
		database.stepFactRevisionDao().insert(
			retraction("covered", semanticRevision = 2L).copy(
				writerBindingGeneration = BINDING_GENERATION + 1L,
			),
		)
		insertFence(LOGICAL_ONE, RUN_ONE)
		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)

		resetDatabase()
		insertReadyFixture()
		database.stepFactRevisionDao().insert(
			retraction("covered", semanticRevision = 2L).copy(collectedDataEpoch = 1L),
		)
		insertFence(LOGICAL_ONE, RUN_ONE)
		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)

		resetDatabase()
		insertReadyFixture()
		database.stepFactRevisionDao().insert(retraction("covered", semanticRevision = 2L))
		insertFence(LOGICAL_ONE, RUN_ONE)
		exporter.export(request()) {} shouldBe ExportPortableStepsResult.NoEntries
	}

	@Test
	fun `retention boundary omits whole entry and rejects a crossing entry`() = runTest {
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 2_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 2_000L,
			facts = listOf(FactSeed("covered", 1L, count = 2L)),
		)
		database.sourceEvidenceStateDao().updateLifecycle(0L, 2_000L, 2_000L)

		exporter.export(request()) {} shouldBe ExportPortableStepsResult.NoEntries

		resetDatabase()
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 2_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 2_000L,
			facts = listOf(FactSeed("covered", 1L, count = 2L)),
		)
		database.sourceEvidenceStateDao().updateLifecycle(0L, 1_500L, 2_001L)
		exporter.export(request()) {} shouldBe ExportPortableStepsResult.Unverifiable(
			PortableStepsExportUnverifiableReason.RETENTION_CROSSES_ENTRY,
		)
	}

	@Test
	fun `latest-state bound accepts 2048 and rejects 2049 before emission`() = runTest {
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 10_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 10_000L,
			facts = (1L..StepsPortableFormatV1.MAX_FACTS_PER_RUN).map { ordinal ->
				FactSeed("fact-$ordinal", ordinal, count = 1L)
			},
		)

		exporter.export(request(toMs = 11_000L)) {} shouldBe ExportPortableStepsResult.Exported(1)
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = LOGICAL_ONE,
				runId = RUN_ONE,
				manifestRevision = 1L,
				seed = FactSeed("fact-2049", 2_049L, count = 1L),
				startMs = 1_000L,
			),
		)
		val emitted = mutableListOf<Any>()

		exporter.export(request(toMs = 11_000L)) { emitted += it } shouldBe
			ExportPortableStepsResult.Unverifiable(
				PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW,
			)
		emitted shouldBe emptyList()
	}

	@Test
	fun `historical correction bound accepts 2048 and rejects 2049 before emission`() = runTest {
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 10_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 10_000L,
			facts = (1L..2_048L).map { revision ->
				FactSeed("corrected", revision, semanticRevision = revision, count = revision)
			},
		)

		exporter.export(request(toMs = 11_000L)) {} shouldBe ExportPortableStepsResult.Exported(1)
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = LOGICAL_ONE,
				runId = RUN_ONE,
				manifestRevision = 1L,
				seed = FactSeed("corrected", 2_049L, semanticRevision = 2_049L, count = 2_049L),
				startMs = 1_000L,
			),
		)
		val emitted = mutableListOf<Any>()

		exporter.export(request(toMs = 11_000L)) { emitted += it } shouldBe
			ExportPortableStepsResult.Unverifiable(
				PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW,
			)
		emitted shouldBe emptyList()
	}

	@Test
	fun `closed storage failure has zero emission`() {
		database.close()
		runTest {
			val emitted = mutableListOf<Any>()

			exporter.export(request()) { emitted += it } shouldBe
				ExportPortableStepsResult.RetryableFailure(
					PortableStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
				)
			emitted shouldBe emptyList()
		}
	}

	@Test
	fun `sink cancellation is not converted to a transfer result`() = runTest {
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 2_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 2_000L,
			facts = listOf(FactSeed("covered", 1L, count = 1L)),
		)
		var cancellationPropagated = false
		try {
			exporter.export(request()) { throw CancellationException("sink cancelled") }
		} catch (_: CancellationException) {
			cancellationPropagated = true
		}
		cancellationPropagated shouldBe true
	}

	@Test
	fun `replacement count does not create per-run query fan-out`() = runTest {
		val queryCount = AtomicInteger()
		replaceWithCountingDatabase(queryCount)
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 5_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = "run-1",
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 1_100L,
			facts = listOf(FactSeed("fact-1", 1L, count = 1L)),
		)
		exporter.export(request(toMs = 6_000L)) {}
		queryCount.set(0)
		exporter.export(request(toMs = 6_000L)) {} shouldBe ExportPortableStepsResult.Exported(1)
		val singleRunQueries = queryCount.get()

		(2L..10L).forEach { index ->
			val startMs = 1_000L + index * 200L
			insertSettledRun(
				logicalId = LOGICAL_ONE,
				runId = "run-$index",
				segmentId = 40L + index,
				manifestRevision = index,
				startMs = startMs,
				endMs = startMs + 500L,
				facts = listOf(FactSeed("fact-$index", index, count = index)),
			)
		}
		exporter.export(request(toMs = 6_000L)) {}
		queryCount.set(0)
		exporter.export(request(toMs = 6_000L)) {} shouldBe ExportPortableStepsResult.Exported(1)
		val tenRunQueries = queryCount.get()

		(singleRunQueries > 0) shouldBe true
		(tenRunQueries <= singleRunQueries + 1) shouldBe true
	}

	@Test
	@Suppress("LongMethod")
	fun `query batching stays bounded across the 400 identity boundary`() = runTest {
		val queryCount = AtomicInteger()
		replaceWithCountingDatabase(queryCount)
		(1..401).forEach { index ->
			val logicalId = "batch-logical-${index.toString().padStart(3, '0')}"
			val runId = "batch-run-${index.toString().padStart(3, '0')}"
			val startMs = 10_000L + (index - 1L) * 3_000L
			insertLogicalSession(logicalId, startMs, startMs + 2_000L)
			insertSettledRun(
				logicalId = logicalId,
				runId = runId,
				segmentId = 1_000L + index,
				manifestRevision = 1L,
				startMs = startMs,
				endMs = startMs + 2_000L,
				facts = listOf(FactSeed("fact-$index", index.toLong(), count = 1L)),
			)
		}
		val boundaryStartMs = 10_000L + 400L * 3_000L
		val firstBatch = ExportPortableStepsRequest(0L, boundaryStartMs)
		val secondBatch = ExportPortableStepsRequest(0L, boundaryStartMs + 3_000L)
		exporter.export(firstBatch) {}
		queryCount.set(0)
		exporter.export(firstBatch) {} shouldBe ExportPortableStepsResult.Exported(400)
		val atBoundary = queryCount.get()
		exporter.export(secondBatch) {}
		queryCount.set(0)
		exporter.export(secondBatch) {} shouldBe ExportPortableStepsResult.Exported(401)
		val afterBoundary = queryCount.get()

		(atBoundary > 0) shouldBe true
		(afterBoundary <= atBoundary + MAX_QUERY_BATCH_INCREMENT) shouldBe true
	}

	@Test
	@Suppress("LongMethod")
	fun `concurrent writer cannot split the Room export snapshot`() = runTest {
		val pause = QueryPause()
		replaceWithObservedFileDatabase(pause)
		insertReadyFixture()
		val writerDatabase = openFileDatabase(requireNotNull(fileDatabaseName))
		try {
			writerDatabase.sourceEvidenceStateDao().get()
			val entries = mutableListOf<com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1>()
			pause.arm()
			val export = async(Dispatchers.IO) {
				exporter.export(request()) { entry -> entries += entry }
			}
			val entered = withContext(Dispatchers.IO) { pause.awaitEntered() }
			if (!entered) {
				pause.release()
			}
			entered shouldBe true
			try {
				withContext(Dispatchers.IO) {
					writerDatabase.stepFactRevisionDao().insert(
						fact(
							logicalId = LOGICAL_ONE,
							runId = RUN_ONE,
							manifestRevision = 1L,
							seed = FactSeed(
								logicalFactId = "foreign-concurrent",
								ordinal = 2L,
								count = 5L,
								portableImport = true,
								writerProjectionId = "foreign-concurrent-writer",
								writerProjectionVersion = 4,
								writerBindingGeneration = 8L,
							),
							startMs = 1_000L,
						),
					)
				}
			} finally {
				pause.release()
			}

			export.await() shouldBe ExportPortableStepsResult.Exported(1)
			entries.single().runs.single().facts.single().stepCount shouldBe 1L
			assertZeroSinkUnverifiable(
				PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
			)
		} finally {
			pause.release()
			writerDatabase.close()
		}
	}

	@Test
	fun `caller cancellation during Room storage is propagated with zero emission`() = runTest {
		val pause = QueryPause()
		replaceWithObservedFileDatabase(pause)
		insertReadyFixture()
		val emitted = mutableListOf<Any>()
		pause.arm()
		val export = async(Dispatchers.IO) {
			exporter.export(request()) { emitted += it }
		}
		val entered = withContext(Dispatchers.IO) { pause.awaitEntered() }
		if (!entered) {
			pause.release()
		}
		entered shouldBe true
		export.cancel(CancellationException("cancelled during Room snapshot"))
		pause.release()
		var propagated = false
		try {
			export.await()
		} catch (_: CancellationException) {
			propagated = true
		}

		propagated shouldBe true
		emitted shouldBe emptyList()
	}

	private fun createExporter() = RoomExportPortableSteps(
		reader = PortableStepsRoomReader(database, StepsSegmentHistorySelector(database)),
		ioDispatcher = Dispatchers.Unconfined,
	)

	private suspend fun insertReadyFixture(
		facts: List<FactSeed> = listOf(FactSeed("covered", 1L, count = 1L)),
		sessionMode: String = "MANUAL",
		rolloutRevision: Long = 1L,
	) {
		insertLogicalSession(
			logicalId = LOGICAL_ONE,
			startMs = 1_000L,
			endMs = 2_000L,
			sessionMode = sessionMode,
		)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 2_000L,
			facts = facts,
			sessionMode = sessionMode,
			rolloutRevision = rolloutRevision,
		)
	}

	private suspend fun assertZeroSinkUnverifiable(
		reason: PortableStepsExportUnverifiableReason,
		selection: ExportPortableStepsRequest = request(),
	) {
		val emitted = mutableListOf<Any>()
		exporter.export(selection) { emitted += it } shouldBe ExportPortableStepsResult.Unverifiable(reason)
		emitted shouldBe emptyList()
	}

	private suspend fun replaceProductLane(lane: SourceProductProjectionLaneEntity) {
		database.sourceProjectionStateDao().deleteAllProductLanes()
		database.sourceProjectionStateDao().installProductLane(lane)
	}

	private suspend fun stepsCompleteness(): SourceSessionCompletenessEntity =
		database.sourceSessionDao().completeness(LOGICAL_ONE).single { row ->
			row.serviceRunId == RUN_ONE && row.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS
		}

	private suspend fun replaceWithObservedFileDatabase(pause: QueryPause) {
		database.close()
		// Keep the native SQLite path below Windows MAX_PATH inside Robolectric's named sandbox.
		val name = "ps.db"
		fileDatabaseName = name
		database = openFileDatabase(name, pause)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		database.sourceProjectionStateDao().installProductLane(productLane())
		exporter = RoomExportPortableSteps(
			reader = PortableStepsRoomReader(database, StepsSegmentHistorySelector(database)),
			ioDispatcher = Dispatchers.IO,
		)
	}

	private fun openFileDatabase(name: String, pause: QueryPause? = null): AppDatabase {
		val context: Application = ApplicationProvider.getApplicationContext()
		val parent = requireNotNull(context.getDatabasePath(name).parentFile)
		check(parent.isDirectory || parent.mkdirs()) { "Unable to create test database directory" }
		val builder = Room.databaseBuilder(context, AppDatabase::class.java, name)
			.allowMainThreadQueries()
			.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
		if (pause != null) {
			builder.setQueryCallback(
				{ sql, _ -> pause.onQuery(sql) },
				Executor { command -> command.run() },
			)
		}
		return builder.build()
	}

	private suspend fun replaceWithCountingDatabase(queryCount: AtomicInteger) {
		database.close()
		val context: Application = ApplicationProvider.getApplicationContext()
		database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
			.allowMainThreadQueries()
			.setQueryCallback(
				{ sql, _ ->
					val normalized = sql.trimStart()
					if (normalized.startsWith("SELECT", ignoreCase = true) ||
						normalized.startsWith("WITH", ignoreCase = true)
					) {
						queryCount.incrementAndGet()
					}
				},
				Executor { command -> command.run() },
			)
			.build()
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		database.sourceProjectionStateDao().installProductLane(productLane())
		exporter = createExporter()
	}

	private suspend fun insertLogicalSession(
		logicalId: String,
		startMs: Long,
		endMs: Long?,
		state: String = "FINALIZED",
		currentRunId: String? = null,
		sessionMode: String = "MANUAL",
	) {
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = logicalId,
				state = state,
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				clockDomainId = "boot-1",
				startedAtMs = startMs,
				startedElapsedNanos = startMs,
				cutoffAtMs = endMs,
				cutoffElapsedNanos = endMs,
				completedAtMs = endMs,
				finalAdmissionOrdinal = endMs?.let { 10_000L },
				failureCode = null,
				sessionMode = sessionMode,
				currentManifestRevision = null,
				currentIntentRevision = null,
				currentServiceRunId = currentRunId,
			),
		)
	}

	private suspend fun insertSettledRun(
		logicalId: String,
		runId: String,
		segmentId: Long,
		manifestRevision: Long,
		startMs: Long,
		endMs: Long,
		facts: List<FactSeed>,
		sampleCount: Int = 0,
		includeControlMembership: Boolean = false,
		sessionMode: String = "MANUAL",
		rolloutRevision: Long = 1L,
	) {
		insertRunManifest(
			logicalId = logicalId,
			runId = runId,
			segmentId = segmentId,
			manifestRevision = manifestRevision,
			startMs = startMs,
			endMs = endMs,
			includeControlMembership = includeControlMembership,
			sessionMode = sessionMode,
			rolloutRevision = rolloutRevision,
		)
		database.sessionSegmentDao().insert(
			SessionSegment(
				id = segmentId,
				startTimeMs = startMs,
				endTimeMs = endMs,
				distanceM = 0f,
				steps = null,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = sampleCount,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = "portable-test",
				createdAt = endMs,
				logicalTrackingId = logicalId,
				serviceRunId = runId,
			),
		)
		database.withTransaction {
			facts.forEach { seed ->
				database.stepFactRevisionDao().insert(
					fact(logicalId, runId, manifestRevision, seed, startMs),
				)
			}
		}
		database.sourceSessionDao().saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = logicalId,
				serviceRunId = runId,
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				sourceInstanceId = "steps-$runId",
				registrationGeneration = 1L,
				lastAdmissionOrdinal = facts.maxOfOrNull(FactSeed::ordinal),
				lastSourceSequence = facts.maxOfOrNull(FactSeed::ordinal),
				appDrainComplete = true,
				providerCoverage = COMPLETE_PROVIDER_COVERAGE,
				stopStatus = "COMPLETE",
				unresolvedSequenceStart = null,
				unresolvedSequenceEnd = null,
				updatedAtMs = endMs,
			),
		)
	}

	@Suppress("LongMethod")
	private suspend fun insertRunManifest(
		logicalId: String,
		runId: String,
		segmentId: Long?,
		manifestRevision: Long,
		startMs: Long,
		endMs: Long?,
		state: String = "FINALIZED",
		includeControlMembership: Boolean = false,
		sessionMode: String = "MANUAL",
		rolloutRevision: Long = 1L,
	) {
		ensurePolicy(manifestRevision)
		val terminal = state == "FINALIZED" || state == "FAILED"
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = runId,
				logicalTrackingId = logicalId,
				state = state,
				desiredPlanRevision = 1L,
				rolloutRevision = rolloutRevision,
				foregroundCapabilityFlags = 0L,
				startedAtMs = startMs,
				startedElapsedNanos = startMs,
				completedAtMs = endMs.takeIf { terminal },
				completionReason = "STOPPED".takeIf { terminal },
				bootId = "boot-1",
				sessionSegmentId = segmentId,
				presentationAcknowledgement = if (terminal) {
					SourceServiceRunEntity.PRESENTATION_QUIESCED
				} else {
					SourceServiceRunEntity.PRESENTATION_PENDING
				},
				presentationAcknowledgedAtMs = endMs.takeIf { terminal },
			),
		)
		val steps = SessionManifestSourceEntity(
			logicalTrackingId = logicalId,
			manifestRevision = manifestRevision,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			consentEpoch = 1L,
			persistenceEligible = true,
			qosCode = 1,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			writerOwner = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS,
			writerOwnerGeneration = 2L,
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = BINDING_GENERATION,
		)
		val sources = buildList {
			add(steps)
			if (includeControlMembership) {
				add(
					SessionManifestSourceEntity(
						logicalTrackingId = logicalId,
						manifestRevision = manifestRevision,
						sourceKind = TrackingSourceComponent.ACTIVITY.stableCode,
						purpose = SessionManifestPurposeCode.CONTROL,
						consentEpoch = 1L,
						persistenceEligible = false,
						qosCode = 1,
					),
				)
			}
		}
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = logicalId,
			manifestRevision = manifestRevision,
			serviceRunId = runId,
			sessionMode = sessionMode,
			sourcePolicyRevision = manifestRevision,
			acquisitionPlanRevision = manifestRevision,
			rolloutRevision = rolloutRevision,
			startOrigin = "MANUAL_FOREGROUND_START",
			effectiveBootId = "boot-1",
			effectiveElapsedRealtimeNanos = startMs,
			effectiveWallTimeMs = startMs,
			zoneId = "UTC",
			automationEpoch = null,
			changeReason = "TEST",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, sources)),
		)
		database.sourceSessionDao().insertManifestSources(sources)
	}

	private suspend fun ensurePolicy(revision: Long) {
		if (database.sourcePolicyDao().policyAtRevision(
				revision,
				SourceDestinationOwnerEntity.SOURCE_STEPS,
			) == null
		) {
			database.sourcePolicyDao().insertPolicies(
				listOf(
					SourcePolicyEntity(
						policyRevision = revision,
						sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
						enabled = true,
						qosCode = 1,
						locationMinTimeSeconds = null,
						locationMinDistanceMeters = null,
						locationRequiredAccuracyMeters = null,
						capturePersistenceEligible = true,
						controlPersistenceEligible = false,
						ambientPersistenceEligible = false,
						captureConsentEpoch = 1L,
						controlConsentEpoch = null,
						ambientConsentEpoch = null,
						effectiveBootId = "boot-1",
						effectiveElapsedRealtimeNanos = revision,
						effectiveWallTimeMs = revision,
						changeReason = "TEST",
					),
				),
			)
		}
	}

	private fun fact(
		logicalId: String,
		runId: String,
		manifestRevision: Long,
		seed: FactSeed,
		startMs: Long,
	): StepFactRevisionEntity {
		val intervalStart = startMs + 10L + (seed.ordinal % 100L) * 10L
		val intervalEnd = intervalStart + 5L
		val portableOrigin = seed.portableImport
		return StepFactRevisionEntity(
			logicalFactId = seed.logicalFactId,
			semanticRevision = seed.semanticRevision,
			mutationId = "mutation-${seed.logicalFactId}-${seed.semanticRevision}",
			stepIntervalId = null,
			sourceEventId = "event-${seed.ordinal}".takeUnless { portableOrigin },
			sourceAdmissionOrdinal = seed.ordinal.takeUnless { portableOrigin },
			originKind = if (portableOrigin) {
				StepFactRevisionEntity.ORIGIN_PORTABLE_IMPORT
			} else {
				StepFactRevisionEntity.ORIGIN_LIVE_WAL
			},
			originIdentity = "origin-${seed.ordinal}",
			writerProjectionId = seed.writerProjectionId,
			writerProjectionVersion = seed.writerProjectionVersion,
			writerBindingGeneration = seed.writerBindingGeneration,
			operation = StepFactRevisionEntity.OPERATION_UPSERT,
			intervalStartTimeMs = intervalStart,
			intervalEndTimeMs = intervalEnd,
			intervalStartElapsedRealtimeNanos = intervalStart,
			intervalEndElapsedRealtimeNanos = intervalEnd,
			clockDomainId = "boot-1",
			bootClockDomainId = "boot-1",
			cumulativeStepCountStart = 100L,
			cumulativeStepCountEnd = when (seed.coverage) {
				StepFactRevisionEntity.COVERAGE_RESET_GAP -> 99L
				else -> 100L + seed.count
			},
			wallTimeUncertaintyMs = 0L,
			coverageKind = seed.coverage,
			effectiveStepCount = when (seed.coverage) {
				StepFactRevisionEntity.COVERAGE_BASELINE,
				StepFactRevisionEntity.COVERAGE_RESET_GAP,
				-> 0L
				else -> seed.count
			},
			logicalTrackingId = logicalId,
			serviceRunId = runId,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			manifestRevision = manifestRevision,
			sourcePolicyRevision = seed.sourcePolicyRevision ?: manifestRevision,
			captureConsentEpoch = 1L,
			collectedDataEpoch = 0L,
			scopeDeletionGeneration = 0L,
			effectChecksum = "effect-${seed.logicalFactId}-${seed.semanticRevision}",
			appliedAtMs = intervalEnd,
		)
	}

	private fun retraction(
		logicalFactId: String,
		semanticRevision: Long,
		deletionGeneration: Long = 1L,
	) = StepFactRevisionEntity(
		logicalFactId = logicalFactId,
		semanticRevision = semanticRevision,
		mutationId = "delete-$logicalFactId-$semanticRevision",
		stepIntervalId = null,
		sourceEventId = null,
		sourceAdmissionOrdinal = null,
		originKind = StepFactRevisionEntity.ORIGIN_LOCAL_DELETE,
		originIdentity = "delete-request-$logicalFactId",
		writerProjectionId = WRITER_ID,
		writerProjectionVersion = WRITER_VERSION,
		writerBindingGeneration = BINDING_GENERATION,
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
		purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = null,
		sourcePolicyRevision = null,
		captureConsentEpoch = null,
		collectedDataEpoch = 0L,
		scopeDeletionGeneration = deletionGeneration,
		effectChecksum = "delete-effect-$logicalFactId-$semanticRevision",
		appliedAtMs = 3_000L,
	)

	private suspend fun insertFence(logicalId: String, runId: String) {
		database.sourceDeletionFenceDao().upsert(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				logicalTrackingId = logicalId,
				serviceRunId = runId,
				fenceGeneration = 1L,
				collectedDataEpoch = 0L,
				deletedAtMs = 3_000L,
			),
		)
	}

	private fun productLane(
		captureModeMask: Long = 1L,
		activatedRolloutRevision: Long = 1L,
		activationOrdinal: Long = 1L,
		contiguousAdmissionOrdinal: Long = 20_000L,
		captureAdmissionCutoffOrdinal: Long? = null,
	) = SourceProductProjectionLaneEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		bindingGeneration = BINDING_GENERATION,
		projectionId = WRITER_ID,
		projectionVersion = WRITER_VERSION,
		captureModeMask = captureModeMask,
		productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		activatedRolloutRevision = activatedRolloutRevision,
		activationOrdinal = activationOrdinal,
		contiguousAdmissionOrdinal = contiguousAdmissionOrdinal,
		captureAdmissionCutoffOrdinal = captureAdmissionCutoffOrdinal,
		retentionRequired = true,
		status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
		installedAtMs = 1L,
		updatedAtMs = 1L,
	)

	private fun request(fromMs: Long = 900L, toMs: Long = 3_500L) =
		ExportPortableStepsRequest(fromMs, toMs)

	private data class FactSeed(
		val logicalFactId: String,
		val ordinal: Long,
		val semanticRevision: Long = 1L,
		val count: Long = 0L,
		val coverage: String = StepFactRevisionEntity.COVERAGE_COVERED,
		val sourcePolicyRevision: Long? = null,
		val portableImport: Boolean = false,
		val writerProjectionId: String = WRITER_ID,
		val writerProjectionVersion: Int = WRITER_VERSION,
		val writerBindingGeneration: Long = BINDING_GENERATION,
	)

	private class QueryPause {
		private val armed = AtomicBoolean(false)
		private val entered = CountDownLatch(1)
		private val released = CountDownLatch(1)

		fun arm() {
			armed.set(true)
		}

		fun onQuery(sql: String) {
			val normalized = sql.trimStart()
			val replacementRunRead = normalized.startsWith("SELECT", ignoreCase = true) &&
				normalized.contains("FROM source_service_run", ignoreCase = true) &&
				normalized.contains("logical_tracking_id IN", ignoreCase = true)
			if (replacementRunRead && armed.compareAndSet(true, false)) {
				entered.countDown()
				check(released.await(10L, TimeUnit.SECONDS)) { "Timed out waiting to release Room query" }
			}
		}

		fun awaitEntered(): Boolean = entered.await(10L, TimeUnit.SECONDS)

		fun release() {
			released.countDown()
		}
	}

	private companion object {
		const val LOGICAL_ONE = "logical-one"
		const val RUN_ONE = "run-one"
		const val WRITER_ID = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION
		const val BINDING_GENERATION = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION
		const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
		const val MAX_QUERY_BATCH_INCREMENT = 16
	}
}
