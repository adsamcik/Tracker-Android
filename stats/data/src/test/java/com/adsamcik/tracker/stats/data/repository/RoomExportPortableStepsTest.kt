package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
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
	fun `physical run envelope includes manifest before delayed presentation segment`() = runTest {
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 2_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_200L,
			endMs = 1_900L,
			runStartedAtMs = 1_000L,
			runCompletedAtMs = 2_000L,
			facts = listOf(FactSeed("covered", 1L, count = 3L)),
		)
		val entries = mutableListOf<com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1>()

		exporter.export(request()) { entry -> entries += entry } shouldBe
			ExportPortableStepsResult.Exported(1)
		val run = entries.single().runs.single()
		run.startTimeMs shouldBe 1_000L
		run.endTimeMs shouldBe 2_000L
		run.manifests.single().effectiveWallTimeMs shouldBe 1_000L
		run.facts.single().stepCount shouldBe 3L
	}

	@Test
	fun `elapsed ownership survives a wall clock regression outside the run envelope`() = runTest {
		insertReadyFixture(facts = emptyList())
		insertAdditionalManifest(
			manifestRevision = 2L,
			effectiveWallTimeMs = 900L,
			effectiveElapsedRealtimeNanos = 20_001_000L,
		)
		val canonical = fact(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			manifestRevision = 2L,
			seed = FactSeed("clock-jump", 1L, count = 3L),
			startMs = 1_000L,
		).copy(
			intervalStartTimeMs = 105L,
			intervalEndTimeMs = 110L,
			appliedAtMs = 110L,
		)
		database.stepFactRevisionDao().insert(
			canonical.copy(
				effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(canonical),
			),
		)
		database.sourceSessionDao().saveCompleteness(
			stepsCompleteness().copy(lastAdmissionOrdinal = 1L, lastSourceSequence = 1L),
		)
		val entries = mutableListOf<com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1>()

		exporter.export(request()) { entry -> entries += entry } shouldBe
			ExportPortableStepsResult.Exported(1)
		val exported = entries.single().runs.single()
		exported.startTimeMs shouldBe 1_000L
		exported.manifests.last().effectiveWallTimeMs shouldBe 900L
		exported.facts.single().intervalStartTimeMs shouldBe 105L
	}

	@Test
	fun `terminal completion wall regression remains exportable`() = runTest {
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 2_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 2_000L,
			runStartedAtMs = 1_000L,
			runCompletedAtMs = 900L,
			facts = listOf(FactSeed("covered", 1L, count = 3L)),
		)

		exporter.export(request()) {} shouldBe ExportPortableStepsResult.Exported(1)
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
	fun `missing logical manifest revision blocks replacement export before emission`() = runTest {
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 3_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 1_500L,
			facts = listOf(FactSeed("first-fact", 1L, count = 3L)),
		)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_TWO,
			segmentId = 42L,
			manifestRevision = 3L,
			startMs = 2_000L,
			endMs = 3_000L,
			facts = listOf(FactSeed("later-fact", 2L, count = 4L)),
		)
		val emitted = mutableListOf<Any>()

		exporter.export(request(toMs = 4_000L)) { emitted += it } shouldBe
			ExportPortableStepsResult.Unverifiable(
				PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
			)
		emitted shouldBe emptyList()
	}

	@Test
	fun `unrelated source runs beyond snapshot budget still yield no entries`() = runTest {
		insertUnrelatedActivityRuns(UNRELATED_RUNS_OVER_SNAPSHOT_BUDGET)
		val emitted = mutableListOf<Any>()

		val result = exporter.export(request()) { emitted += it }

		result shouldBe ExportPortableStepsResult.NoEntries
		emitted shouldBe emptyList()
	}

	@Test
	fun `unrelated malformed attribution does not block a valid Steps entry`() = runTest {
		insertReadyFixture()
		database.sessionSegmentDao().insert(
			SessionSegment(
				id = 42L,
				startTimeMs = 1_000L,
				endTimeMs = 2_000L,
				distanceM = 9_999f,
				steps = 9_999,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 9_999,
				source = SegmentSource.INFERRED_HIGH_CONFIDENCE,
				inferenceVersion = "unrelated-partial-attribution",
				createdAt = 2_000L,
				logicalTrackingId = "unrelated-logical",
				serviceRunId = null,
			),
		)
		val entries = mutableListOf<com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1>()

		val result = exporter.export(request()) { entries += it }

		result shouldBe ExportPortableStepsResult.Exported(1)
		entries.single().runs.single().facts.single().stepCount shouldBe 1L
	}

	@Test
	fun `source-filtered discovery keeps an unbound no-fact Steps run materializing`() = runTest {
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
	fun `noncanonical LIVE_WAL semantic correction is rejected`() = runTest {
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
		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
	}

	@Test
	fun `fact corrected outside the selected run fails before emission`() = runTest {
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 2_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 2_000L,
			facts = listOf(
				FactSeed("selected-covered", 1L, count = 1L),
				FactSeed("moved", 2L, count = 2L),
			),
		)
		insertLogicalSession(LOGICAL_TWO, startMs = 4_000L, endMs = 5_000L)
		insertSettledRun(
			logicalId = LOGICAL_TWO,
			runId = RUN_TWO,
			segmentId = 42L,
			manifestRevision = 2L,
			startMs = 4_000L,
			endMs = 5_000L,
			facts = listOf(FactSeed("moved", 3L, semanticRevision = 2L, count = 3L)),
		)

		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
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
	fun `retarget to durable run outside export cannot hide a corrupt selected fact`() = runTest {
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 2_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 2_000L,
			facts = listOf(
				FactSeed("hidden", 1L, count = 4L),
				FactSeed("survivor", 2L, count = 6L),
			),
		)
		insertLogicalSession(LOGICAL_TWO, startMs = 5_000L, endMs = 6_000L)
		insertSettledRun(
			logicalId = LOGICAL_TWO,
			runId = RUN_TWO,
			segmentId = 42L,
			manifestRevision = 2L,
			startMs = 5_000L,
			endMs = 6_000L,
			facts = listOf(FactSeed("outside", 10L, count = 3L)),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET logical_tracking_id = ?, service_run_id = ? " +
				"WHERE logical_fact_id = ? AND operation = 'UPSERT'",
			arrayOf(LOGICAL_TWO, RUN_TWO, "$WRITER_ID:event-hidden"),
		)

		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
	}

	@Test
	@Suppress("LongMethod")
	fun `LIVE_WAL facts require canonical identity clocks consent and effect semantics`() = runTest {
		val mutations = listOf<(StepFactRevisionEntity) -> StepFactRevisionEntity>(
			{ fact ->
				fact.copy(
					semanticRevision = 2L,
					mutationId =
						"${fact.logicalFactId}:2:${StepFactRevisionEntity.OPERATION_UPSERT}",
				)
			},
			{ fact -> fact.copy(logicalFactId = "forged-logical-fact") },
			{ fact -> fact.copy(mutationId = "forged-mutation") },
			{ fact -> fact.copy(originIdentity = "forged-origin") },
			{ fact -> fact.copy(clockDomainId = "other-boot") },
			{ fact -> fact.copy(bootClockDomainId = "other-boot") },
			{ fact -> fact.copy(captureConsentEpoch = 2L) },
			{ fact -> fact.copy(appliedAtMs = requireNotNull(fact.intervalEndTimeMs) + 1L) },
			{ fact -> fact.copy(intervalStartTimeMs = requireNotNull(fact.intervalStartTimeMs) + 1L) },
			{ fact -> fact.copy(effectiveStepCount = requireNotNull(fact.effectiveStepCount) + 1L) },
			{ fact ->
				fact.copy(
					intervalStartElapsedRealtimeNanos = 999L,
					intervalEndElapsedRealtimeNanos = 5_000_999L,
				)
			},
		)
		mutations.forEachIndexed { index, transform ->
			if (index > 0) {
				resetDatabase()
			}
			insertReadyFixture(
				factTransform = { fact ->
					val transformed = transform(fact)
					transformed.copy(
						effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(transformed),
					)
				},
			)
			assertZeroSinkUnverifiable(
				PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
			)
		}
	}

	@Test
	fun `retained LIVE_WAL checksum mismatch fails before emission`() = runTest {
		insertReadyFixture()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE step_fact_revision SET wall_time_uncertainty_ms = " +
				"wall_time_uncertainty_ms + 1 WHERE logical_fact_id = ?",
			arrayOf("$WRITER_ID:event-covered"),
		)

		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
	}

	@Test
	fun `malformed retained Room entity becomes typed zero-sink export`() = runTest {
		insertReadyFixture()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET presentation_acknowledgement = 'BROKEN' " +
				"WHERE service_run_id = ?",
			arrayOf(RUN_ONE),
		)
		val emitted = mutableListOf<Any>()

		exporter.export(request()) { emitted += it } shouldBe
			ExportPortableStepsResult.Unverifiable(
				PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
			)
		emitted shouldBe emptyList()
	}

	@Test
	fun `checksum valid covered facts with an impossible run timeline emit nothing`() = runTest {
		insertReadyFixture(
			facts = listOf(
				FactSeed("first-covered", 1L, count = 5L),
				FactSeed("second-covered", 2L, count = 6L),
			),
			factTransform = { fact ->
				val transformed = when (fact.sourceAdmissionOrdinal) {
					1L -> fact.copy(
						intervalStartTimeMs = 1_020L,
						intervalEndTimeMs = 1_030L,
						intervalStartElapsedRealtimeNanos = 20_001_000L,
						intervalEndElapsedRealtimeNanos = 30_001_000L,
						cumulativeStepCountStart = 100L,
						cumulativeStepCountEnd = 105L,
						appliedAtMs = 1_030L,
					)
					else -> fact.copy(
						intervalStartTimeMs = 1_025L,
						intervalEndTimeMs = 1_035L,
						intervalStartElapsedRealtimeNanos = 25_001_000L,
						intervalEndElapsedRealtimeNanos = 35_001_000L,
						cumulativeStepCountStart = 500L,
						cumulativeStepCountEnd = 506L,
						appliedAtMs = 1_035L,
					)
				}
				val signed = transformed.copy(
					effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(transformed),
				)
				StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(signed) shouldBe true
				signed
			},
		)

		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
	}

	@Test
	fun `missing or ineligible historical capture authority emits nothing`() = runTest {
		insertReadyFixture()
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_policy WHERE source_kind = ?",
			arrayOf(SourceDestinationOwnerEntity.SOURCE_STEPS),
		)
		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)

		resetDatabase()
		insertReadyFixture()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_consent_epoch SET eligible = 0 WHERE source_kind = ? AND purpose = ?",
			arrayOf<Any>(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SessionManifestPurposeCode.SESSION_CAPTURE,
			),
		)
		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
	}

	@Test
	@Suppress("LongMethod")
	fun `manifest effective timeline must retain exact run authority`() = runTest {
		val mutations = listOf<(SessionManifestVersionEntity) -> SessionManifestVersionEntity>(
			{ manifest -> manifest.copy(effectiveBootId = "other-boot") },
			{ manifest -> manifest.copy(effectiveElapsedRealtimeNanos = 1_001L) },
			{ manifest -> manifest.copy(effectiveWallTimeMs = 1_001L) },
			{ manifest -> manifest.copy(startOrigin = "AUTOMATIC_START") },
			{ manifest -> manifest.copy(acquisitionPlanRevision = 2L) },
			{ manifest -> manifest.copy(rolloutRevision = 2L) },
		)
		mutations.forEachIndexed { index, transform ->
			if (index > 0) {
				resetDatabase()
			}
			insertReadyFixture(manifestTransform = transform)
			assertZeroSinkUnverifiable(
				PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
			)
		}

		resetDatabase()
		insertReadyFixture()
		insertAdditionalManifest(
			manifestRevision = 3L,
			effectiveWallTimeMs = 1_500L,
			effectiveElapsedRealtimeNanos = 500_001_000L,
		)
		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)

		resetDatabase()
		insertReadyFixture()
		insertAdditionalManifest(
			manifestRevision = 2L,
			effectiveWallTimeMs = 1_000L,
			effectiveElapsedRealtimeNanos = 500_001_000L,
		)
		exporter.export(request()) {} shouldBe ExportPortableStepsResult.Exported(1)
	}

	@Test
	fun `manifest effective boundary belongs exclusively to the new revision`() = runTest {
		insertReadyFixture(facts = emptyList())
		insertAdditionalManifest(
			manifestRevision = 2L,
			effectiveWallTimeMs = 1_020L,
			effectiveElapsedRealtimeNanos = 20_001_000L,
		)
		database.stepFactRevisionDao().insert(
			fact(
				logicalId = LOGICAL_ONE,
				runId = RUN_ONE,
				manifestRevision = 2L,
				seed = FactSeed("new-boundary", 1L, count = 1L),
				startMs = 1_000L,
			),
		)
		database.sourceSessionDao().saveCompleteness(
			stepsCompleteness().copy(lastAdmissionOrdinal = 1L, lastSourceSequence = 1L),
		)
		exporter.export(request()) {} shouldBe ExportPortableStepsResult.Exported(1)

		resetDatabase()
		insertReadyFixture()
		insertAdditionalManifest(
			manifestRevision = 2L,
			effectiveWallTimeMs = 1_025L,
			effectiveElapsedRealtimeNanos = 25_001_000L,
		)
		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
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
		insertReadyFixture()
		replaceProductLane(productLane(captureModeMask = ALL_SESSION_CAPTURE_MASK))
		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)

		resetDatabase()
		insertReadyFixture()
		exporter = createExporter(SourceProductLaneExecutionAuthority { false })
		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)

		resetDatabase()
		insertReadyFixture(
			facts = listOf(
				FactSeed(
					"unknown-generation",
					1L,
					count = 1L,
					writerBindingGeneration = 99L,
				),
			),
			bindingGeneration = 99L,
		)
		replaceProductLane(productLane(bindingGeneration = 99L))
		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)

		resetDatabase()
		insertReadyFixture(
			facts = listOf(
				FactSeed(
					"manual-v2",
					1L,
					count = 1L,
					writerBindingGeneration = AUTOMATIC_BINDING_GENERATION,
				),
			),
			bindingGeneration = AUTOMATIC_BINDING_GENERATION,
		)
		replaceProductLane(
			productLane(
				bindingGeneration = AUTOMATIC_BINDING_GENERATION,
				captureModeMask = ALL_SESSION_CAPTURE_MASK,
			),
		)
		exporter.export(request()) {} shouldBe ExportPortableStepsResult.Exported(1)

		resetDatabase()
		insertReadyFixture(
			facts = listOf(
				FactSeed(
					"automatic-v2",
					1L,
					count = 1L,
					writerBindingGeneration = AUTOMATIC_BINDING_GENERATION,
				),
			),
			sessionMode = "AUTOMATIC",
			bindingGeneration = AUTOMATIC_BINDING_GENERATION,
		)
		replaceProductLane(
			productLane(
				bindingGeneration = AUTOMATIC_BINDING_GENERATION,
				captureModeMask = ALL_SESSION_CAPTURE_MASK,
			),
		)
		exporter.export(request()) {} shouldBe ExportPortableStepsResult.Exported(1)

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
			stepsCompleteness().copy(lastAdmissionOrdinal = null, lastSourceSequence = null),
		)
		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)

		resetDatabase()
		insertReadyFixture(facts = emptyList())
		exporter.export(request()) {} shouldBe ExportPortableStepsResult.NoEntries

		resetDatabase()
		insertReadyFixture()
		database.sourceSessionDao().saveCompleteness(
			stepsCompleteness().copy(
				sourceInstanceId = "same-terminal",
				registrationGeneration = 2L,
				lastAdmissionOrdinal = 2L,
				lastSourceSequence = 2L,
			),
		)
		exporter.export(request()) {} shouldBe ExportPortableStepsResult.Exported(1)
	}

	@Test
	fun `duplicate completeness registration generation fails closed before export`() = runTest {
		insertReadyFixture()
		val firstGeneration = stepsCompleteness()
		database.sourceSessionDao().saveCompleteness(
			firstGeneration.copy(
				sourceInstanceId = "steps-$RUN_ONE-duplicate",
				lastAdmissionOrdinal = 2L,
				lastSourceSequence = 2L,
			),
		)

		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}

	@Test
	fun `generation ordered completeness admission high water cannot regress`() = runTest {
		insertReadyFixture()
		val firstGeneration = stepsCompleteness()
		database.sourceSessionDao().saveCompleteness(
			firstGeneration.copy(
				lastAdmissionOrdinal = 2L,
				lastSourceSequence = 2L,
			),
		)
		database.sourceSessionDao().saveCompleteness(
			firstGeneration.copy(
				sourceInstanceId = "second-generation-instance",
				registrationGeneration = 2L,
				lastAdmissionOrdinal = 1L,
				lastSourceSequence = 1L,
			),
		)

		assertZeroSinkUnverifiable(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}

	@Test
	@Suppress("LongMethod")
	fun `Steps evidence requires exact reverse binding while unrelated orphans are ignored`() = runTest {
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
		exporter.export(request()) {} shouldBe ExportPortableStepsResult.Exported(1)

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
			retraction("covered", semanticRevision = 2L, collectedDataEpoch = 1L),
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
	fun `retention boundary follows end-before pruning and rejects every crossing entry`() = runTest {
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

		exporter.export(request()) {} shouldBe ExportPortableStepsResult.Unverifiable(
			PortableStepsExportUnverifiableReason.RETENTION_CROSSES_ENTRY,
		)

		resetDatabase()
		insertReadyFixture()
		database.sourceEvidenceStateDao().updateLifecycle(0L, 2_001L, 2_001L)
		exporter.export(request()) {} shouldBe ExportPortableStepsResult.NoEntries

		resetDatabase()
		insertReadyFixture()
		database.sourceEvidenceStateDao().updateLifecycle(0L, 1_000L, 2_001L)
		exporter.export(request()) {} shouldBe ExportPortableStepsResult.Exported(1)

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
	fun `retention rejects an old fact even when its run envelope is newer than the floor`() = runTest {
		insertReadyFixture(facts = emptyList())
		insertAdditionalManifest(
			manifestRevision = 2L,
			effectiveWallTimeMs = 900L,
			effectiveElapsedRealtimeNanos = 20_001_000L,
		)
		val unsigned = fact(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			manifestRevision = 2L,
			seed = FactSeed("retained-clock-jump", 1L, count = 3L),
			startMs = 1_000L,
		).copy(
			intervalStartTimeMs = 105L,
			intervalEndTimeMs = 110L,
			appliedAtMs = 110L,
		)
		database.stepFactRevisionDao().insert(
			unsigned.copy(
				effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsigned),
			),
		)
		database.sourceSessionDao().saveCompleteness(
			stepsCompleteness().copy(lastAdmissionOrdinal = 1L, lastSourceSequence = 1L),
		)
		database.sourceEvidenceStateDao().updateLifecycle(0L, 500L, 2_001L)
		val emitted = mutableListOf<Any>()

		exporter.export(request()) { emitted += it } shouldBe ExportPortableStepsResult.Unverifiable(
			PortableStepsExportUnverifiableReason.RETENTION_CROSSES_ENTRY,
		)
		emitted shouldBe emptyList()
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
	fun `unsupported historical corrections fail closed independent of lineage size`() = runTest {
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

		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
			request(toMs = 11_000L),
		)
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
				PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
			)
		emitted shouldBe emptyList()
	}

	@Test
	fun `off-scope unsupported corrections cannot evade selected-run integrity`() = runTest {
		insertLogicalSession(LOGICAL_ONE, startMs = 1_000L, endMs = 2_000L)
		insertSettledRun(
			logicalId = LOGICAL_ONE,
			runId = RUN_ONE,
			segmentId = 41L,
			manifestRevision = 1L,
			startMs = 1_000L,
			endMs = 2_000L,
			facts = listOf(FactSeed("moved", 1L, count = 1L)),
		)
		insertLogicalSession(LOGICAL_TWO, startMs = 4_000L, endMs = 7_000L)
		insertSettledRun(
			logicalId = LOGICAL_TWO,
			runId = RUN_TWO,
			segmentId = 42L,
			manifestRevision = 2L,
			startMs = 4_000L,
			endMs = 7_000L,
			facts = (2L..2_049L).map { revision ->
				FactSeed("moved", revision, semanticRevision = revision, count = revision)
			},
		)

		assertZeroSinkUnverifiable(
			PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
		)
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
				registrationGeneration = index.toLong(),
			)
		}
		val boundaryStartMs = 10_000L + 400L * 3_000L
		val firstBatch = ExportPortableStepsRequest(0L, boundaryStartMs)
		val secondBatch = ExportPortableStepsRequest(0L, boundaryStartMs + 3_000L)
		exporter.export(firstBatch) {}
		queryCount.set(0)
		exporter.export(firstBatch) {} shouldBe ExportPortableStepsResult.Exported(400)
		val atBoundary = queryCount.get()
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

	private fun createExporter(
		laneAuthority: SourceProductLaneExecutionAuthority = executableLaneAuthority(),
	) = RoomExportPortableSteps(
		reader = PortableStepsRoomReader(
			database,
			StepsSegmentHistorySelector(database, laneAuthority),
			laneAuthority,
		),
		ioDispatcher = Dispatchers.Unconfined,
	)

	/** Builds a source-local independence boundary without spending minutes in per-row DAO calls. */
	private suspend fun insertUnrelatedActivityRuns(count: Int) {
		require(count > 0)
		database.withTransaction {
			val sqlite = database.openHelper.writableDatabase
			sqlite.insertUnrelatedRuns(count)
			sqlite.insertUnrelatedManifests(count)
			sqlite.insertUnrelatedSources(count)
		}
	}

	private fun SupportSQLiteDatabase.insertUnrelatedRuns(count: Int) {
		execSQL(
			"""
			WITH RECURSIVE sequence(value) AS (
			  SELECT 1
			  UNION ALL
			  SELECT value + 1 FROM sequence WHERE value < ?
			)
			INSERT INTO source_service_run (
			  service_run_id,
			  logical_tracking_id,
			  state,
			  desired_plan_revision,
			  rollout_revision,
			  foreground_capability_flags,
			  started_at_ms,
			  started_elapsed_nanos,
			  completed_at_ms,
			  completion_reason
			)
			SELECT 'unrelated-run-' || value,
			  'unrelated-logical-' || value,
			  'FINALIZED',
			  1,
			  1,
			  0,
			  1000,
			  1000,
			  2000,
			  'STOPPED'
			FROM sequence
			""".trimIndent(),
			arrayOf(count),
		)
	}

	private fun SupportSQLiteDatabase.insertUnrelatedManifests(count: Int) {
		execSQL(
			"""
			WITH RECURSIVE sequence(value) AS (
			  SELECT 1
			  UNION ALL
			  SELECT value + 1 FROM sequence WHERE value < ?
			)
			INSERT INTO session_manifest_version (
			  logical_tracking_id,
			  manifest_revision,
			  service_run_id,
			  session_mode,
			  source_policy_revision,
			  acquisition_plan_revision,
			  rollout_revision,
			  start_origin,
			  effective_boot_id,
			  effective_elapsed_realtime_nanos,
			  effective_wall_time_ms,
			  zone_id,
			  change_reason,
			  manifest_checksum
			)
			SELECT 'unrelated-logical-' || value,
			  1,
			  'unrelated-run-' || value,
			  'MANUAL',
			  1,
			  1,
			  1,
			  'MANUAL_FOREGROUND_START',
			  'unrelated-boot',
			  1000,
			  1000,
			  'UTC',
			  'TEST',
			  'unrelated-checksum'
			FROM sequence
			""".trimIndent(),
			arrayOf(count),
		)
	}

	private fun SupportSQLiteDatabase.insertUnrelatedSources(count: Int) {
		execSQL(
			"""
			WITH RECURSIVE sequence(value) AS (
			  SELECT 1
			  UNION ALL
			  SELECT value + 1 FROM sequence WHERE value < ?
			)
			INSERT INTO session_manifest_source (
			  logical_tracking_id,
			  manifest_revision,
			  source_kind,
			  purpose,
			  consent_epoch,
			  persistence_eligible,
			  qos_code
			)
			SELECT 'unrelated-logical-' || value,
			  1,
			  ?,
			  ?,
			  1,
			  1,
			  1
			FROM sequence
			""".trimIndent(),
			arrayOf<Any>(
				count,
				TrackingSourceComponent.ACTIVITY.stableCode,
				SessionManifestPurposeCode.SESSION_CAPTURE,
			),
		)
	}

	private fun executableLaneAuthority() = SourceProductLaneExecutionAuthority { lane ->
		lane.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
			lane.projectionId == WRITER_ID && lane.projectionVersion == WRITER_VERSION &&
			when (lane.bindingGeneration) {
				BINDING_GENERATION -> lane.captureModeMask == MANUAL_CAPTURE_MASK
				AUTOMATIC_BINDING_GENERATION -> lane.captureModeMask == ALL_SESSION_CAPTURE_MASK
				else -> false
			}
	}

	private suspend fun insertReadyFixture(
		facts: List<FactSeed> = listOf(FactSeed("covered", 1L, count = 1L)),
		sessionMode: String = "MANUAL",
		rolloutRevision: Long = 1L,
		bindingGeneration: Long = BINDING_GENERATION,
		registrationGeneration: Long = 1L,
		factTransform: (StepFactRevisionEntity) -> StepFactRevisionEntity = { it },
		manifestTransform: (SessionManifestVersionEntity) -> SessionManifestVersionEntity = { it },
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
			bindingGeneration = bindingGeneration,
			registrationGeneration = registrationGeneration,
			factTransform = factTransform,
			manifestTransform = manifestTransform,
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
			reader = PortableStepsRoomReader(
				database,
				StepsSegmentHistorySelector(database, executableLaneAuthority()),
				executableLaneAuthority(),
			),
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
		val startOrigin = if (sessionMode == "AUTOMATIC") {
			"AUTOMATIC_BACKGROUND_START"
		} else {
			"MANUAL_FOREGROUND_START"
		}
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = logicalId,
				state = state,
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = startOrigin,
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
				automationEpoch = 1L.takeIf { sessionMode == "AUTOMATIC" },
			),
		)
	}

	@Suppress("LongMethod")
	private suspend fun insertSettledRun(
		logicalId: String,
		runId: String,
		segmentId: Long,
		manifestRevision: Long,
		startMs: Long,
		endMs: Long,
		facts: List<FactSeed>,
		runStartedAtMs: Long = startMs,
		runCompletedAtMs: Long = endMs,
		sampleCount: Int = 0,
		includeControlMembership: Boolean = false,
		sessionMode: String = "MANUAL",
		rolloutRevision: Long = 1L,
		bindingGeneration: Long = BINDING_GENERATION,
		registrationGeneration: Long = 1L,
		factTransform: (StepFactRevisionEntity) -> StepFactRevisionEntity = { it },
		manifestTransform: (SessionManifestVersionEntity) -> SessionManifestVersionEntity = { it },
	) {
		insertRunManifest(
			logicalId = logicalId,
			runId = runId,
			segmentId = segmentId,
			manifestRevision = manifestRevision,
			startMs = runStartedAtMs,
			endMs = runCompletedAtMs,
			includeControlMembership = includeControlMembership,
			sessionMode = sessionMode,
			rolloutRevision = rolloutRevision,
			bindingGeneration = bindingGeneration,
			manifestTransform = manifestTransform,
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
		var previousCanonicalLiveWal: StepFactRevisionEntity? = null
		val canonicalFacts = facts.map { seed ->
			val raw = fact(logicalId, runId, manifestRevision, seed, startMs)
			val canonical = previousCanonicalLiveWal?.let { previous ->
				continueCanonicalLiveWalFact(previous, raw)
			} ?: raw
			if (canonical.originKind == StepFactRevisionEntity.ORIGIN_LIVE_WAL) {
				previousCanonicalLiveWal = canonical
			}
			canonical
		}
		database.withTransaction {
			canonicalFacts.forEach { canonical ->
				database.stepFactRevisionDao().insert(
					factTransform(canonical),
				)
			}
		}
		database.sourceSessionDao().saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = logicalId,
				serviceRunId = runId,
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				sourceInstanceId = "steps-$runId",
				registrationGeneration = registrationGeneration,
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
		bindingGeneration: Long = BINDING_GENERATION,
		manifestTransform: (SessionManifestVersionEntity) -> SessionManifestVersionEntity = { it },
	) {
		ensurePolicy(manifestRevision)
		val terminal = state == "FINALIZED" || state == "FAILED"
		val startOrigin = if (sessionMode == "AUTOMATIC") {
			"AUTOMATIC_BACKGROUND_START"
		} else {
			"MANUAL_FOREGROUND_START"
		}
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = runId,
				logicalTrackingId = logicalId,
				state = state,
				desiredPlanRevision = manifestRevision,
				rolloutRevision = rolloutRevision,
				foregroundCapabilityFlags = 0L,
				startedAtMs = startMs,
				startedElapsedNanos = startMs,
				completedAtMs = endMs.takeIf { terminal },
				completionReason = "STOPPED".takeIf { terminal },
				bootId = "boot-1",
				leaseGeneration = 1L,
				startOrigin = startOrigin,
				runRevision = 1L,
				startDeliveryToken = "delivery-$runId",
				startCommandGeneration = 1L,
				preparedManifestRevision = manifestRevision,
				preparedIntentRevision = 1L,
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
			writerBindingGeneration = bindingGeneration,
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
		val unsigned = manifestTransform(SessionManifestVersionEntity(
			logicalTrackingId = logicalId,
			manifestRevision = manifestRevision,
			serviceRunId = runId,
			sessionMode = sessionMode,
			sourcePolicyRevision = manifestRevision,
			acquisitionPlanRevision = manifestRevision,
			rolloutRevision = rolloutRevision,
			startOrigin = startOrigin,
			effectiveBootId = "boot-1",
			effectiveElapsedRealtimeNanos = startMs,
			effectiveWallTimeMs = startMs,
			zoneId = "UTC",
			automationEpoch = 1L.takeIf { sessionMode == "AUTOMATIC" },
			changeReason = "TEST",
			manifestChecksum = "",
		))
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, sources)),
		)
		database.sourceSessionDao().insertManifestSources(sources)
	}

	private suspend fun insertAdditionalManifest(
		manifestRevision: Long,
		effectiveWallTimeMs: Long,
		effectiveElapsedRealtimeNanos: Long,
		bindingGeneration: Long = BINDING_GENERATION,
	) {
		ensurePolicy(manifestRevision)
		val run = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ONE))
		val firstManifest = requireNotNull(
			database.sourceSessionDao().manifestByServiceRunRevision(
				RUN_ONE,
				run.preparedManifestRevision,
			),
		)
		database.sourceSessionDao().updateServiceRun(
			run.copy(desiredPlanRevision = manifestRevision),
		)
		val steps = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_ONE,
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
			writerBindingGeneration = bindingGeneration,
		)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ONE,
			manifestRevision = manifestRevision,
			serviceRunId = RUN_ONE,
			sessionMode = firstManifest.sessionMode,
			sourcePolicyRevision = manifestRevision,
			acquisitionPlanRevision = manifestRevision,
			rolloutRevision = firstManifest.rolloutRevision,
			startOrigin = POLICY_RECONCILIATION_ORIGIN,
			effectiveBootId = firstManifest.effectiveBootId,
			effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = effectiveWallTimeMs,
			zoneId = firstManifest.zoneId,
			automationEpoch = firstManifest.automationEpoch,
			changeReason = POLICY_RECONCILIATION_ORIGIN,
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(
				manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(steps)),
			),
		)
		database.sourceSessionDao().insertManifestSources(listOf(steps))
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
		if (database.sourcePolicyDao().consentEpoch(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				epoch = 1L,
			) == null
		) {
			database.sourcePolicyDao().insertConsentEpochs(
				listOf(
					SourceConsentEpochEntity(
						sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
						purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
						epoch = 1L,
						eligible = true,
						persistenceEligible = true,
						policyRevision = FIRST_POLICY_REVISION,
						effectiveBootId = "boot-1",
						effectiveElapsedRealtimeNanos = revision,
						effectiveWallTimeMs = revision,
						changeReason = "TEST",
					),
				),
			)
		}
	}

	@Suppress("LongMethod")
	private fun fact(
		logicalId: String,
		runId: String,
		manifestRevision: Long,
		seed: FactSeed,
		startMs: Long,
	): StepFactRevisionEntity {
		val intervalStart = startMs + 10L + (seed.ordinal % 100L) * 10L
		val intervalEnd = intervalStart + 5L
		val canonicalIntervalStart = if (
			seed.coverage == StepFactRevisionEntity.COVERAGE_BASELINE
		) {
			intervalEnd
		} else {
			intervalStart
		}
		val portableOrigin = seed.portableImport
		val sourceEventId = "event-${seed.logicalFactId}"
		val logicalFactId = "${seed.writerProjectionId}:$sourceEventId"
		val elapsedDuration = (intervalEnd - canonicalIntervalStart) * NANOS_PER_MILLISECOND
		val intervalStartElapsed = startMs + (
			10L + seed.ordinal * 10L +
				if (seed.coverage == StepFactRevisionEntity.COVERAGE_BASELINE) {
					5L
				} else {
					0L
				}
			) * NANOS_PER_MILLISECOND
		val intervalEndElapsed = intervalStartElapsed + elapsedDuration
		val unsigned = StepFactRevisionEntity(
			logicalFactId = logicalFactId,
			semanticRevision = seed.semanticRevision,
			mutationId = "$logicalFactId:${seed.semanticRevision}:${StepFactRevisionEntity.OPERATION_UPSERT}",
			stepIntervalId = null,
			sourceEventId = sourceEventId.takeUnless { portableOrigin },
			sourceAdmissionOrdinal = seed.ordinal.takeUnless { portableOrigin },
			originKind = if (portableOrigin) {
				StepFactRevisionEntity.ORIGIN_PORTABLE_IMPORT
			} else {
				StepFactRevisionEntity.ORIGIN_LIVE_WAL
			},
			originIdentity = sourceEventId,
			writerProjectionId = seed.writerProjectionId,
			writerProjectionVersion = seed.writerProjectionVersion,
			writerBindingGeneration = seed.writerBindingGeneration,
			operation = StepFactRevisionEntity.OPERATION_UPSERT,
			intervalStartTimeMs = canonicalIntervalStart,
			intervalEndTimeMs = intervalEnd,
			intervalStartElapsedRealtimeNanos = intervalStartElapsed,
			intervalEndElapsedRealtimeNanos = intervalEndElapsed,
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
				StepFactRevisionEntity.COVERAGE_PARTIAL,
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
			effectChecksum = "pending-test-effect",
			appliedAtMs = intervalEnd,
		)
		return if (portableOrigin) {
			unsigned.copy(effectChecksum = "portable-effect-${seed.logicalFactId}-${seed.semanticRevision}")
		} else {
			unsigned.copy(effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsigned))
		}
	}

	private fun continueCanonicalLiveWalFact(
		previous: StepFactRevisionEntity,
		current: StepFactRevisionEntity,
	): StepFactRevisionEntity {
		if (current.originKind != StepFactRevisionEntity.ORIGIN_LIVE_WAL ||
			current.coverageKind == StepFactRevisionEntity.COVERAGE_BASELINE
		) {
			return current
		}
		val startElapsed = requireNotNull(previous.intervalEndElapsedRealtimeNanos)
		val durationNanos = Math.multiplyExact(
			requireNotNull(current.intervalEndTimeMs) - requireNotNull(current.intervalStartTimeMs),
			NANOS_PER_MILLISECOND,
		)
		val cumulativeStart = requireNotNull(previous.cumulativeStepCountEnd)
		val cumulativeEnd = when (current.coverageKind) {
			StepFactRevisionEntity.COVERAGE_COVERED ->
				Math.addExact(cumulativeStart, requireNotNull(current.effectiveStepCount))
			StepFactRevisionEntity.COVERAGE_RESET_GAP -> cumulativeStart - 1L
			StepFactRevisionEntity.COVERAGE_PARTIAL -> cumulativeStart
			else -> error("Unexpected canonical coverage ${current.coverageKind}")
		}
		val unsigned = current.copy(
			intervalStartElapsedRealtimeNanos = startElapsed,
			intervalEndElapsedRealtimeNanos = Math.addExact(startElapsed, durationNanos),
			cumulativeStepCountStart = cumulativeStart,
			cumulativeStepCountEnd = cumulativeEnd,
			effectChecksum = "pending-test-effect",
		)
		return unsigned.copy(
			effectChecksum = StepFactRevisionIntegrity.liveWalEffectChecksum(unsigned),
		)
	}

	private fun retraction(
		logicalFactId: String,
		semanticRevision: Long,
		deletionGeneration: Long = 1L,
		collectedDataEpoch: Long = 0L,
	): StepFactRevisionEntity {
		val canonicalLogicalFactId = "$WRITER_ID:event-$logicalFactId"
		val scopeIdentityDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = LOGICAL_ONE,
			serviceRunId = RUN_ONE,
		)
		val unsigned = StepFactRevisionEntity(
			logicalFactId = canonicalLogicalFactId,
			semanticRevision = semanticRevision,
			mutationId = StepFactRevisionIntegrity.localDeleteMutationId(
				scopeIdentityDigest = scopeIdentityDigest,
				logicalFactId = canonicalLogicalFactId,
				semanticRevision = semanticRevision,
				scopeDeletionGeneration = deletionGeneration,
			),
			stepIntervalId = null,
			sourceEventId = null,
			sourceAdmissionOrdinal = null,
			originKind = StepFactRevisionEntity.ORIGIN_LOCAL_DELETE,
			originIdentity = scopeIdentityDigest,
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
			collectedDataEpoch = collectedDataEpoch,
			scopeDeletionGeneration = deletionGeneration,
			effectChecksum = "pending-local-delete-effect",
			appliedAtMs = 3_000L,
		)
		return unsigned.copy(
			effectChecksum = StepFactRevisionIntegrity.localDeleteEffectChecksum(unsigned),
		)
	}

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
		bindingGeneration: Long = BINDING_GENERATION,
		captureModeMask: Long = MANUAL_CAPTURE_MASK,
		activatedRolloutRevision: Long = 1L,
		activationOrdinal: Long = 1L,
		contiguousAdmissionOrdinal: Long = 20_000L,
		captureAdmissionCutoffOrdinal: Long? = null,
	) = SourceProductProjectionLaneEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
		bindingGeneration = bindingGeneration,
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
		const val FIRST_POLICY_REVISION = 1L
		const val POLICY_RECONCILIATION_ORIGIN = "POLICY_RECONCILIATION"
		const val LOGICAL_ONE = "logical-one"
		const val LOGICAL_TWO = "logical-two"
		const val RUN_ONE = "run-one"
		const val RUN_TWO = "run-two"
		const val WRITER_ID = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID
		const val WRITER_VERSION = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION
		const val BINDING_GENERATION = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION
		const val AUTOMATIC_BINDING_GENERATION =
			SourceDestinationOwnerEntity.STEPS_FACT_AUTOMATIC_BINDING_GENERATION
		const val MANUAL_CAPTURE_MASK = 1L
		const val ALL_SESSION_CAPTURE_MASK = 3L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
		const val MAX_QUERY_BATCH_INCREMENT = 16
		const val UNRELATED_RUNS_OVER_SNAPSHOT_BUDGET = 16_385
	}
}
