package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PressureFactRevisionDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: PressureFactRevisionDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.pressureFactRevisionDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun insertReplayLatestCountAndFullClearRemainExact() = runTest {
		val first = revision(semanticRevision = 1L, admissionOrdinal = 10L)
		val second = revision(semanticRevision = 2L, admissionOrdinal = 11L)

		dao.insert(first) shouldBe 1L
		dao.insert(first) shouldBe -1L
		dao.replay(
			first.writerProjectionId,
			first.writerProjectionVersion,
			first.logicalFactId,
			first.semanticRevision,
			first.mutationId,
			first.sourceAdmissionOrdinal,
		) shouldBe first
		dao.insert(second) shouldBe 2L
		dao.latest(first.writerProjectionId, first.writerProjectionVersion, first.logicalFactId) shouldBe second
		dao.count() shouldBe 2L

		dao.deleteAll()

		dao.count() shouldBe 0L
		dao.latest(first.writerProjectionId, first.writerProjectionVersion, first.logicalFactId) shouldBe null
	}

	@Test
	fun replayRequiresAllUniqueIdentitiesToResolveToOneExactRow() = runTest {
		val stored = revision(semanticRevision = 1L, admissionOrdinal = 10L)
		dao.insert(stored) shouldBe 1L
		val mutationCollision = revision(semanticRevision = 2L, admissionOrdinal = 11L).copy(
			mutationId = stored.mutationId,
		)
		dao.insert(mutationCollision) shouldBe -1L
		dao.replay(
			mutationCollision.writerProjectionId,
			mutationCollision.writerProjectionVersion,
			mutationCollision.logicalFactId,
			mutationCollision.semanticRevision,
			mutationCollision.mutationId,
			mutationCollision.sourceAdmissionOrdinal,
		) shouldBe null

		val admissionCollision = revision(semanticRevision = 2L, admissionOrdinal = 10L)
		dao.insert(admissionCollision) shouldBe -1L
		dao.replay(
			admissionCollision.writerProjectionId,
			admissionCollision.writerProjectionVersion,
			admissionCollision.logicalFactId,
			admissionCollision.semanticRevision,
			admissionCollision.mutationId,
			admissionCollision.sourceAdmissionOrdinal,
		) shouldBe null
		dao.count() shouldBe 1L
	}

	@Test
	fun scopedRevisionReadsAreBoundedDeterministicAndExactDeletionPreservesSiblings() = runTest {
		val selectedSecond = revision(semanticRevision = 2L, admissionOrdinal = 12L)
		val selectedFirst = revision(semanticRevision = 1L, admissionOrdinal = 11L)
		val selectedOtherFact = revision(semanticRevision = 1L, admissionOrdinal = 10L).copy(
			logicalFactId = "pressure-session-facts:window-0",
			mutationId = "pressure-session-facts:window-0:1",
			sourceEventId = "window-0",
		)
		val sibling = revision(semanticRevision = 1L, admissionOrdinal = 20L).copy(
			logicalFactId = "pressure-session-facts:sibling",
			mutationId = "pressure-session-facts:sibling:1",
			sourceEventId = "sibling",
			logicalTrackingId = "tracking-2",
			serviceRunId = "run-2",
		)
		listOf(selectedSecond, sibling, selectedFirst, selectedOtherFact).forEach { fact ->
			dao.insert(fact)
		}

		dao.firstExactServiceRunPage("tracking-1", "run-1", limit = 2) shouldBe
			listOf(selectedOtherFact, selectedFirst)
		dao.exactServiceRunPageAfter(
			logicalTrackingId = "tracking-1",
			serviceRunId = "run-1",
			afterWriterId = selectedFirst.writerProjectionId,
			afterWriterVersion = selectedFirst.writerProjectionVersion,
			afterLogicalFactId = selectedFirst.logicalFactId,
			afterSemanticRevision = selectedFirst.semanticRevision,
			limit = 2,
		) shouldBe listOf(selectedSecond)
		dao.hasServiceRunScopeMismatch("tracking-1", "run-1") shouldBe false
		dao.hasCrossScopeRevisions("tracking-1", "run-1") shouldBe false
		dao.deleteExactServiceRunPageThrough(
			"tracking-1",
			"run-1",
			SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
			selectedSecond.logicalFactId,
			selectedSecond.semanticRevision,
		) shouldBe 3
		dao.firstExactServiceRunPage("tracking-1", "run-1", limit = 4) shouldBe emptyList()
		dao.firstExactServiceRunPage("tracking-2", "run-2", limit = 4) shouldBe listOf(sibling)
	}

	@Test
	fun crossScopeCorrectionIsDetectedWithoutDeletingEitherScope() = runTest {
		val selected = revision(semanticRevision = 1L, admissionOrdinal = 10L)
		val escapedCorrection = revision(semanticRevision = 2L, admissionOrdinal = 11L).copy(
			logicalTrackingId = "tracking-2",
			serviceRunId = "run-2",
		)
		dao.insert(selected)
		dao.insert(escapedCorrection)

		dao.hasServiceRunScopeMismatch("tracking-1", "run-1") shouldBe false
		dao.hasCrossScopeRevisions("tracking-1", "run-1") shouldBe true
		dao.firstExactServiceRunPage("tracking-1", "run-1", limit = 2) shouldBe listOf(selected)
		dao.firstExactServiceRunPage("tracking-2", "run-2", limit = 2) shouldBe
			listOf(escapedCorrection)
	}

	@Test
	@Suppress(
		"CyclomaticComplexMethod",
		"LongMethod",
	) // One assertion block proves every exact scoped plan uses the schema index.
	fun exactRunScopeIndexMatchesRoomSchemaAndEveryScopedOperationSeeksIt() {
		val indexColumns = buildList {
			database.openHelper.readableDatabase.query(
				"PRAGMA index_info('$SCOPE_INDEX')",
			).use { cursor ->
				val nameColumn = cursor.getColumnIndexOrThrow("name")
				while (cursor.moveToNext()) add(cursor.getString(nameColumn))
			}
		}
		indexColumns shouldBe listOf(
			"service_run_id",
			"logical_tracking_id",
			"writer_projection_id",
			"writer_projection_version",
			"logical_fact_id",
			"semantic_revision",
		)

		val firstPagePlan = queryPlan(
			"EXPLAIN QUERY PLAN SELECT * FROM pressure_fact_revision " +
				"WHERE service_run_id = ? AND logical_tracking_id = ? " +
				"ORDER BY writer_projection_id, writer_projection_version, " +
				"logical_fact_id, semantic_revision LIMIT ?",
			arrayOf<Any?>("run-1", "tracking-1", 256),
		)
		val seekPagePlan = queryPlan(
			"EXPLAIN QUERY PLAN SELECT * FROM pressure_fact_revision " +
				"WHERE service_run_id = ? AND logical_tracking_id = ? " +
				"AND (writer_projection_id, writer_projection_version, logical_fact_id, " +
				"semantic_revision) > (?, ?, ?, ?) " +
				"ORDER BY writer_projection_id, writer_projection_version, " +
				"logical_fact_id, semantic_revision LIMIT ?",
			arrayOf<Any?>("run-1", "tracking-1", "writer", 1, "fact", 1L, 256),
		)
		val deletePlan = queryPlan(
			"EXPLAIN QUERY PLAN DELETE FROM pressure_fact_revision " +
				"WHERE service_run_id = ? AND logical_tracking_id = ? " +
				"AND writer_projection_id = ? AND writer_projection_version = ? " +
				"AND (logical_fact_id, semantic_revision) <= (?, ?)",
			arrayOf<Any?>(
				"run-1",
				"tracking-1",
				SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
				"fact-2",
				1L,
			),
		)
		val mismatchPlan = queryPlan(
			"EXPLAIN QUERY PLAN SELECT (" +
				"EXISTS(SELECT 1 FROM pressure_fact_revision WHERE service_run_id = ? " +
				"AND logical_tracking_id < ? LIMIT 1) OR " +
				"EXISTS(SELECT 1 FROM pressure_fact_revision WHERE service_run_id = ? " +
				"AND logical_tracking_id > ? LIMIT 1))",
			arrayOf<Any?>("run-1", "tracking-1", "run-1", "tracking-1"),
		)
		val crossScopePlan = queryPlan(
			"EXPLAIN QUERY PLAN SELECT EXISTS(" +
				"SELECT 1 FROM pressure_fact_revision AS selected " +
				"INNER JOIN pressure_fact_revision AS correction " +
				"ON correction.writer_projection_id = selected.writer_projection_id " +
				"AND correction.writer_projection_version = selected.writer_projection_version " +
				"AND correction.logical_fact_id = selected.logical_fact_id " +
				"WHERE selected.logical_tracking_id = ? AND selected.service_run_id = ? " +
				"AND (correction.logical_tracking_id != ? OR correction.service_run_id != ?) LIMIT 1)",
			arrayOf<Any?>("tracking-1", "run-1", "tracking-1", "run-1"),
		)

		listOf(firstPagePlan, seekPagePlan, deletePlan).forEach { plan ->
			check(plan.none { detail -> detail.contains("USE TEMP B-TREE") }) {
				"Scoped Pressure plan required an unbounded sort:\n${plan.joinToString("\n")}"
			}
		}
		check(firstPagePlan.any { detail ->
			detail.contains("INDEX $SCOPE_INDEX") && detail.contains("service_run_id=?") &&
				detail.contains("logical_tracking_id=?")
		}) {
			"Pressure first-page plan did not seek the exact scope prefix:\n" +
				firstPagePlan.joinToString("\n")
		}
		check(seekPagePlan.any { detail ->
			detail.contains("INDEX $SCOPE_INDEX") && detail.contains("service_run_id=?") &&
				detail.contains("logical_tracking_id=?") &&
				detail.contains(
					"(writer_projection_id,writer_projection_version,logical_fact_id," +
						"semantic_revision)>(?,?,?,?)",
				)
		}) {
			"Pressure keyset plan did not seek beyond the scope prefix:\n" +
				seekPagePlan.joinToString("\n")
		}
		check(deletePlan.any { detail ->
			detail.contains("INDEX $SCOPE_INDEX") && detail.contains("service_run_id=?") &&
				detail.contains("logical_tracking_id=?") && detail.contains("writer_projection_id=?") &&
				detail.contains("writer_projection_version=?") &&
				detail.contains("(logical_fact_id,semantic_revision)<(?,?)")
		}) {
			"Pressure batch delete did not seek through its exact validated cursor:\n" +
				deletePlan.joinToString("\n")
		}
		val mismatchScopeSeeks = mismatchPlan.filter { detail -> detail.contains("INDEX $SCOPE_INDEX") }
		check(mismatchScopeSeeks.size == 2 &&
			mismatchScopeSeeks.any { detail -> detail.contains("logical_tracking_id<?") } &&
			mismatchScopeSeeks.any { detail -> detail.contains("logical_tracking_id>?") }
		) {
			"Pressure mismatch guard did not use two bounded scope ranges:\n" +
				mismatchPlan.joinToString("\n")
		}
		check(crossScopePlan.any { detail ->
			detail.contains("selected") && detail.contains("COVERING INDEX $SCOPE_INDEX") &&
				detail.contains("service_run_id=?") && detail.contains("logical_tracking_id=?")
		} && crossScopePlan.any { detail ->
			detail.contains("correction") && detail.contains("INDEX") &&
				detail.contains("writer_projection_id=?") &&
				detail.contains("writer_projection_version=?") && detail.contains("logical_fact_id=?")
		} && crossScopePlan.none { detail ->
			detail.contains("SCAN selected") || detail.contains("SCAN correction")
		}
		) {
			"Pressure correction guard did not seek both identity sides:\n" +
				crossScopePlan.joinToString("\n")
		}
	}

	private fun queryPlan(sql: String, arguments: Array<Any?>): List<String> = buildList {
		database.openHelper.readableDatabase.query(sql, arguments).use { cursor ->
			val detailColumn = cursor.getColumnIndexOrThrow("detail")
			while (cursor.moveToNext()) add(cursor.getString(detailColumn))
		}
	}

	@Test
	fun entityRejectsLegacyOrInternallyContradictoryPressureFacts() {
		shouldThrow<IllegalArgumentException> { revision().copy(payloadVersion = 3) }
		shouldThrow<IllegalArgumentException> {
			revision().copy(writerBindingGeneration = 2L)
		}
		shouldThrow<IllegalArgumentException> {
			revision().copy(lastProviderSequence = 5L)
		}
		shouldThrow<IllegalArgumentException> {
			revision().copy(qualification = PressureFactRevisionEntity.QUALIFICATION_PARTIAL)
		}
		shouldThrow<IllegalArgumentException> {
			revision().copy(closureKind = PressureFactRevisionEntity.CLOSURE_SOURCE_BOUNDARY)
		}
		shouldThrow<IllegalArgumentException> {
			revision().copy(intervalStartTimeMs = 851L)
		}
	}

	private fun revision(
		semanticRevision: Long = 1L,
		admissionOrdinal: Long = 10L,
	) = PressureFactRevisionEntity(
		logicalFactId = LOGICAL_FACT_ID,
		semanticRevision = semanticRevision,
		mutationId = "pressure-mutation-$semanticRevision",
		sourceEventId = "pressure-event-$admissionOrdinal",
		sourceAdmissionOrdinal = admissionOrdinal,
		writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
		payloadVersion = PressureFactRevisionEntity.QUALIFIED_PRESSURE_PAYLOAD_VERSION,
		intervalStartTimeMs = 850L,
		intervalEndTimeMs = 1_000L,
		windowStartElapsedRealtimeNanos = 1_000_000_000L,
		windowEndElapsedRealtimeNanos = 1_150_000_000L,
		clockDomainId = "boot-1",
		wallTimeUncertaintyMs = 25L,
		sampleCount = 4,
		meanHectopascals = 1_001.5,
		sumSquaredDeviations = 5.0,
		minimumHectopascals = 1_000f,
		maximumHectopascals = 1_003f,
		firstProviderSequence = 1L,
		lastProviderSequence = 4L,
		firstHectopascals = 1_000f,
		lastHectopascals = 1_003f,
		slopeHectopascalsPerSecond = 20.0,
		rSquared = 1.0,
		sensorAccuracy = PressureFactRevisionEntity.SENSOR_ACCURACY_HIGH,
		effectiveSamplePeriodMicros = 50_000,
		effectiveMaximumReportLatencyMicros = 200_000,
		targetWindowDurationNanos = 200_000_000L,
		expectedSampleCount = 4,
		maximumInterSampleGapNanos = 50_000_000L,
		closureKind = PressureFactRevisionEntity.CLOSURE_TARGET_ELAPSED,
		qualification = PressureFactRevisionEntity.QUALIFICATION_COMPLETE,
		sourceQualityFlags = 0L,
		sourceQualityConfidence = 1f,
		logicalTrackingId = "tracking-1",
		serviceRunId = "run-1",
		purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = 1L,
		sourcePolicyRevision = 1L,
		captureConsentEpoch = 1L,
		collectedDataEpoch = 1L,
		effectChecksum = "pressure-effect-$semanticRevision",
		appliedAtMs = 1_000L,
	)

	private companion object {
		const val LOGICAL_FACT_ID = "pressure-session-facts:window-1"
		const val SCOPE_INDEX = "idx_pressure_fact_revision_service_run_scope"
	}
}
