package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.Room
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
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
class RoomExportPortableStepsTest {
	private lateinit var database: AppDatabase
	private lateinit var exporter: RoomExportPortableSteps

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
	fun tearDown() = database.close()

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
	fun `latest correction is exported and latest retraction remains redacted`() = runTest {
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
				FactSeed("deleted", 3L, count = 5L),
			),
		)
		database.stepFactRevisionDao().insert(retraction("deleted", semanticRevision = 2L))
		val entries = mutableListOf<com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1>()

		exporter.export(request()) { entries += it } shouldBe ExportPortableStepsResult.Exported(1)

		entries.single().runs.single().facts.map { fact -> fact.stepCount } shouldBe listOf(9L)
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
	fun `exact fenced missing replacement is skipped while unfenced missing replacement fails`() =
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
			val entries = mutableListOf<com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1>()

			exporter.export(request(toMs = 4_000L)) { entries += it } shouldBe
				ExportPortableStepsResult.Exported(1)
			entries.single().runs.size shouldBe 1

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
			exporter.export(request()) {} shouldBe ExportPortableStepsResult.Unverifiable(
				PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
			)
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
		database.close()
		val context: Application = ApplicationProvider.getApplicationContext()
		database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
			.allowMainThreadQueries()
			.setQueryCallback(
				{ sql, _ ->
					if (sql.trimStart().startsWith("SELECT", ignoreCase = true)) {
						queryCount.incrementAndGet()
					}
				},
				Executor { command -> command.run() },
			).build()
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		database.sourceProjectionStateDao().installProductLane(productLane())
		exporter = createExporter()
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

	private fun createExporter() = RoomExportPortableSteps(
		reader = PortableStepsRoomReader(database, StepsSegmentHistorySelector(database)),
		ioDispatcher = Dispatchers.Unconfined,
	)

	private suspend fun insertLogicalSession(
		logicalId: String,
		startMs: Long,
		endMs: Long?,
		state: String = "FINALIZED",
		currentRunId: String? = null,
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
				sessionMode = "MANUAL",
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
	) {
		insertRunManifest(
			logicalId = logicalId,
			runId = runId,
			segmentId = segmentId,
			manifestRevision = manifestRevision,
			startMs = startMs,
			endMs = endMs,
			includeControlMembership = includeControlMembership,
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

	private suspend fun insertRunManifest(
		logicalId: String,
		runId: String,
		segmentId: Long?,
		manifestRevision: Long,
		startMs: Long,
		endMs: Long?,
		state: String = "FINALIZED",
		includeControlMembership: Boolean = false,
	) {
		ensurePolicy(manifestRevision)
		val terminal = state == "FINALIZED" || state == "FAILED"
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = runId,
				logicalTrackingId = logicalId,
				state = state,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
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
			sessionMode = "MANUAL",
			sourcePolicyRevision = manifestRevision,
			acquisitionPlanRevision = manifestRevision,
			rolloutRevision = 1L,
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
			writerProjectionId = WRITER_ID,
			writerProjectionVersion = WRITER_VERSION,
			writerBindingGeneration = BINDING_GENERATION,
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
			effectiveStepCount = if (
				seed.coverage == StepFactRevisionEntity.COVERAGE_BASELINE ||
				seed.coverage == StepFactRevisionEntity.COVERAGE_RESET_GAP
			) 0L else seed.count,
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

	private fun retraction(logicalFactId: String, semanticRevision: Long) = StepFactRevisionEntity(
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
		scopeDeletionGeneration = 1L,
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

	private fun productLane() = SourceProductProjectionLaneEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		bindingGeneration = BINDING_GENERATION,
		projectionId = WRITER_ID,
		projectionVersion = WRITER_VERSION,
		captureModeMask = 1L,
		productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		activatedRolloutRevision = 1L,
		activationOrdinal = 1L,
		contiguousAdmissionOrdinal = 20_000L,
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
	)

	private companion object {
		const val LOGICAL_ONE = "logical-one"
		const val RUN_ONE = "run-one"
		const val WRITER_ID = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION
		const val BINDING_GENERATION = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION
		const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
	}
}
