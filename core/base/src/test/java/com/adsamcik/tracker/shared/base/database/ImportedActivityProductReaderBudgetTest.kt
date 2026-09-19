package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityProductBatchPreflight
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedActivityProductReaderBudgetTest {
	private lateinit var database: AppDatabase
	private val queries = CopyOnWriteArrayList<String>()

	@Before
	fun setUp() {
		database = AppDatabase.inMemoryBuilder(
			ApplicationProvider.getApplicationContext<Application>(),
		).allowMainThreadQueries()
			.setQueryCallback({ sql, _ -> queries += sql.normalizedSql() }, Executor(Runnable::run))
			.build()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `numeric preflight rejects rows UTF8 text and public bands before detail allocation`() =
		runTest {
		val entry = seedImportedEntry(testScheduler)
		val longUtf8Value = "\u00e9".repeat(2_000)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_activity_entry_revision SET import_source_name = ? WHERE identity = ?",
			arrayOf(longUtf8Value, entry.identity.value),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_activity_receipt SET import_source_name = ? WHERE entry_identity = ?",
			arrayOf(longUtf8Value, entry.identity.value),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_activity_zone_epoch SET zone_id = ? WHERE entry_identity = ?",
			arrayOf("\u00e9".repeat(64), entry.identity.value),
		)
		val preflight = preflight(entry)
		preflight.fragmentCount shouldBe 2L
		(preflight.sourceTextBytes > longUtf8Value.length.toLong() * 2L) shouldBe true
		val sourceRows = preflight.sourceRows()
		val candidateBytes = CANDIDATE_FIXED_BYTES +
			entry.identity.value.utf8Size() +
			entry.contentChecksum.value.utf8Size() +
			"LIVE".utf8Size()
		val preOwnerTextBytes = candidateBytes + preflight.sourceTextBytes +
			sourceRows * SOURCE_ROW_FIXED_BYTES

		listOf(
			ImportedActivityProductScanLimits(
				maximumSourceRows = sourceRows,
			),
			ImportedActivityProductScanLimits(
				maximumSourceTextBytes = preOwnerTextBytes - 1L,
			),
			ImportedActivityProductScanLimits(
				maximumPublicPayloadElements =
					preflight.projectedPublicPayloadElements() - 1L,
			),
		).forEach { limits ->
			queries.clear()
			scan(limits) shouldBe ImportedActivityProductScanPage.Unverifiable(
				ImportedActivityProductFailure.DEPENDENCY_OVERFLOW,
			)
			queries.count(::isProductPreflightQuery) shouldBe 1
			queries.count(::isHierarchyDetailQuery) shouldBe 0
			queries.count(::isOwnerAuditQuery) shouldBe 0
		}
	}

	@Test
	fun `exact preflight boundary permits sentinel loads while owner and query budgets stop first`() =
		runTest {
		val entry = seedImportedEntry(testScheduler)
		val preflight = preflight(entry)
		val sourceRowsThroughOwnerAudit =
			1L + preflight.sourceRows() + EXPECTED_OWNER_AUDIT_ROWS
		val projectedPublic = preflight.projectedPublicPayloadElements()

		queries.clear()
		scan(
			ImportedActivityProductScanLimits(
				maximumSourceRows = sourceRowsThroughOwnerAudit - EXPECTED_OWNER_AUDIT_ROWS,
			),
		) shouldBe ImportedActivityProductScanPage.Unverifiable(
			ImportedActivityProductFailure.DEPENDENCY_OVERFLOW,
		)
		queries.count(::isHierarchyDetailQuery).shouldBeGreaterThan(0)
		queries.count(::isOwnerAuditQuery) shouldBe 0

		queries.clear()
		scan(
			ImportedActivityProductScanLimits(
				maximumQueries = QUERIES_THROUGH_OWNER_PREFLIGHT,
			),
		) shouldBe ImportedActivityProductScanPage.Unverifiable(
			ImportedActivityProductFailure.DEPENDENCY_OVERFLOW,
		)
		queries.count(::isHierarchyDetailQuery).shouldBeGreaterThan(0)
		queries.count(::isOwnerAuditQuery) shouldBe 0

		queries.clear()
		val exact = scan(
			ImportedActivityProductScanLimits(
				maximumSourceRows = sourceRowsThroughOwnerAudit,
				maximumPortableElements = projectedPublic,
				maximumPublicPayloadElements = projectedPublic,
				maximumQueries = QUERIES_THROUGH_OWNER_PREFLIGHT + 1,
			),
		) as ImportedActivityProductScanPage.Ready
		(exact.evaluations.single() is ImportedActivityProductEvaluation.Readable) shouldBe true
		queries.count(::isHierarchyDetailQuery).shouldBeGreaterThan(0)
		queries.count(::isOwnerAuditQuery) shouldBe 1
	}

	@Test
	fun `portable preflight counts dense old revision while public payload remains latest only`() =
		runTest {
		val latest = seedDenseCorrectionLineage(testScheduler)
		val preflight = preflight(latest)
		preflight.entryRevisionCount shouldBe 2L
		preflight.runCount shouldBe 2L
		preflight.zoneEpochCount shouldBe 2L
		preflight.windowCount shouldBe 2L
		preflight.fragmentCount shouldBe 1_025L
		preflight.latestFragmentCount shouldBe 1L
		val totalPortable = preflight.totalPortableElements()
		val latestPublic = preflight.projectedPublicPayloadElements()

		queries.clear()
		scan(
			ImportedActivityProductScanLimits(
				maximumPortableElements = latestPublic,
				maximumPublicPayloadElements = latestPublic,
			),
		) shouldBe ImportedActivityProductScanPage.Unverifiable(
			ImportedActivityProductFailure.DEPENDENCY_OVERFLOW,
		)
		queries.count(::isProductPreflightQuery) shouldBe 1
		queries.count(::isHierarchyDetailQuery) shouldBe 0
		queries.count(::isOwnerAuditQuery) shouldBe 0

		queries.clear()
		val exact = scan(
			ImportedActivityProductScanLimits(
				maximumPortableElements = totalPortable,
				maximumPublicPayloadElements = latestPublic,
			),
		) as ImportedActivityProductScanPage.Ready
		val readable = exact.evaluations.single() as ImportedActivityProductEvaluation.Readable
		readable.candidate.importRevision shouldBe 2L
		readable.entry.runs.single().windows.single().fragments.size shouldBe 1
		queries.count(::isHierarchyDetailQuery).shouldBeGreaterThan(0)
	}

	@Test
	fun `cancelled scan never converts cancellation into a bounded result`() = runTest {
		seedImportedEntry(testScheduler)
		queries.clear()

		kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
			withContext(Job().apply { cancel() }) {
				scan(ImportedActivityProductScanLimits())
			}
		}
	}

	private suspend fun seedImportedEntry(
		scheduler: TestCoroutineScheduler,
	): PortableActivityEntryV1 {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 0L))
		val entry = portableEntry()
		RoomImportPortableCapturedActivity(
			database,
			UnconfinedTestDispatcher(scheduler),
		).importEntry(
			ImportPortableCapturedActivityRequest(
				entry = entry,
				receipt = PortableActivityImportReceipt(
					jobId = "budget-job",
					entryKey = "budget-entry",
					sourceName = "budget.trackeractivity",
					receivedAtMs = 3_000L,
				),
				expectedCollectedDataEpoch = 0L,
			),
		) shouldBe ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 2)
		queries.clear()
		return entry
	}

	private suspend fun seedDenseCorrectionLineage(
		scheduler: TestCoroutineScheduler,
	): PortableActivityEntryV1 {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 0L))
		val dense = portableEntry(
			(0 until 1_024).map { index ->
				band(
					startOffsetNanos = index.toLong(),
					endOffsetNanos = index.toLong() + 1L,
					startWallTimeMs = 1_000L + index,
					endWallTimeMs = 1_001L + index,
				)
			},
		)
		val sparse = portableEntry(
			listOf(band(0L, 1_024L, 1_000L, 2_024L)),
		)
		val importer = RoomImportPortableCapturedActivity(
			database,
			UnconfinedTestDispatcher(scheduler),
		)
		importer.importEntry(
			ImportPortableCapturedActivityRequest(
				entry = dense,
				receipt = PortableActivityImportReceipt(
					"dense-old-job",
					"dense-old-entry",
					"dense.trackeractivity",
					3_000L,
				),
				expectedCollectedDataEpoch = 0L,
			),
		) shouldBe ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1_024)
		importer.importEntry(
			ImportPortableCapturedActivityRequest(
				entry = sparse,
				receipt = PortableActivityImportReceipt(
					"sparse-latest-job",
					"sparse-latest-entry",
					"sparse.trackeractivity",
					4_000L,
				),
				expectedCollectedDataEpoch = 0L,
			),
		) shouldBe ImportPortableCapturedActivityResult.Applied(2L, 1, 1, 1)
		queries.clear()
		return sparse
	}

	private suspend fun preflight(
		entry: PortableActivityEntryV1,
	): ImportedActivityProductBatchPreflight = database.importedActivityDao().productBatchPreflight(
		identities = listOf(entry.identity.value),
		activitySourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
		sessionCapturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		logicalRunScopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
	)

	private suspend fun scan(
		limits: ImportedActivityProductScanLimits,
	): ImportedActivityProductScanPage = database.withTransaction {
		ImportedActivityProductReader(database).selectAllForSharedHistoryInTransaction(limits)
	}

	private fun portableEntry(
		fragments: List<PortableActivityFragmentV1> = listOf(
			band(0L, 50L, 1_000L, 1_001L),
			band(50L, 100L, 1_001L, 1_002L),
		),
	): PortableActivityEntryV1 {
		val entryIdentity = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.LOGICAL_ENTRY,
			"budget-entry",
		)
		val runIdentity = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.PHYSICAL_RUN,
			"budget-run",
		)
		val windowIdentity = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.CAPTURE_WINDOW,
			"budget-window",
		)
		val deletionScope = PortableActivityDeletionScopeDigest.derive(
			"budget-logical",
			"budget-run",
		)
		val durationNanos = fragments.last().endOffsetNanos
		val window = PortableActivityWindowV1(
			identity = windowIdentity,
			contentChecksum = ActivityCapturedPortableIntegrity.windowChecksum(
				windowIdentity,
				0L,
				durationNanos,
				"Europe/Prague",
				PortableActivityWindowCoverage.COMPLETE,
				durationNanos,
				0L,
				0L,
				0L,
				fragments,
			),
			startOffsetNanos = 0L,
			endOffsetNanos = durationNanos,
			storedZoneId = "Europe/Prague",
			coverage = PortableActivityWindowCoverage.COMPLETE,
			knownActiveDurationNanos = durationNanos,
			knownInactiveDurationNanos = 0L,
			unknownActivityDurationNanos = 0L,
			unobservedDurationNanos = 0L,
			fragments = fragments,
		)
		val zones = listOf(PortableActivityZoneEpochV1(1_000L, "Europe/Prague"))
		val run = PortableActivityRunV1(
			identity = runIdentity,
			deletionScopeDigest = deletionScope,
			contentChecksum = ActivityCapturedPortableIntegrity.runChecksum(
				runIdentity,
				deletionScope,
				1_000L,
				2_000L,
				PortableActivityCaptureCoverage.WHOLE_RUN,
				zones,
				listOf(window),
			),
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			captureCoverage = PortableActivityCaptureCoverage.WHOLE_RUN,
			zoneEpochs = zones,
			windows = listOf(window),
		)
		return PortableActivityEntryV1(
			identity = entryIdentity,
			contentChecksum = ActivityCapturedPortableIntegrity.entryChecksum(
				entryIdentity,
				PortableActivitySessionMode.MANUAL,
				1_000L,
				2_000L,
				listOf(run),
			),
			sessionMode = PortableActivitySessionMode.MANUAL,
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			runs = listOf(run),
		)
	}

	private fun band(
		startOffsetNanos: Long,
		endOffsetNanos: Long,
		startWallTimeMs: Long,
		endWallTimeMs: Long,
	) = PortableActivityFragmentV1.Band(
		startOffsetNanos = startOffsetNanos,
		endOffsetNanos = endOffsetNanos,
		activity = "WALKING",
		mechanism = "TRANSITION",
		refinedTransitionActivity = null,
		confidenceKind = "TRANSITION_SIGNAL",
		confidenceMinimumPercent = null,
		confidenceMaximumPercent = null,
		confidenceObservationCount = null,
		startWallTimeMs = startWallTimeMs,
		startWallTimeUncertaintyMs = 0L,
		startBoundaryKind = "EXACT_PROVIDER_OBSERVATION",
		endWallTimeMs = endWallTimeMs,
		endWallTimeUncertaintyMs = 0L,
		endBoundaryKind = "SAME_CLOCK_EXTRAPOLATION",
		wallTimeContinuity = "SAME_ANCHOR",
	)

	private fun ImportedActivityProductBatchPreflight.sourceRows(): Long = listOf(
		entryRevisionCount,
		importReceiptCount,
		runCount,
		zoneEpochCount,
		windowCount,
		fragmentCount,
		retentionReceiptCount,
		retainedIdentityCount,
		entryDeletionCount,
		entryDeletionReceiptCount,
		runDeletionCount,
		sourceFenceCount,
	).sum()

	private fun ImportedActivityProductBatchPreflight.projectedPublicPayloadElements(): Long =
		latestEntryCount + latestRunCount + latestZoneEpochCount + latestWindowCount +
			latestFragmentCount + retainedStructuralRangeCount

	private fun ImportedActivityProductBatchPreflight.totalPortableElements(): Long =
		entryRevisionCount + runCount + zoneEpochCount + windowCount + fragmentCount +
			retentionReceiptCount + retainedIdentityCount + retainedStructuralRangeCount

	private fun String.utf8Size(): Long = toByteArray(Charsets.UTF_8).size.toLong()

	private fun String.normalizedSql(): String =
		replace(Regex("\\s+"), " ").trim().lowercase()

	private fun isProductPreflightQuery(sql: String): Boolean =
		"latest_fragment_count" in sql && "source_text_bytes" in sql

	private fun isHierarchyDetailQuery(sql: String): Boolean =
		"select * from imported_activity_" in sql &&
			" where " in sql &&
			"source_text_bytes" !in sql

	private fun isOwnerAuditQuery(sql: String): Boolean =
		sql.startsWith("select owner_kind, protected_identity") &&
			"group by owner_kind, protected_identity" in sql

	private companion object {
		const val SOURCE_ROW_FIXED_BYTES = 256L
		const val CANDIDATE_FIXED_BYTES = 256L
		const val EXPECTED_OWNER_AUDIT_ROWS = 13L
		const val QUERIES_THROUGH_OWNER_PREFLIGHT = 15
	}
}
