package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.ImportedPressureHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.PORTABLE_PRESSURE_RUN_ORDER
import com.adsamcik.tracker.stats.api.repository.PortablePressureAvailability
import com.adsamcik.tracker.stats.api.repository.PortablePressureCoverage
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntrySink
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortablePressureOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureRunV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowV1
import com.adsamcik.tracker.stats.api.repository.PressureHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.PressureHistoryPresentationState
import com.adsamcik.tracker.stats.api.repository.PressurePortableFormatV1
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.TruncateImportedPressureRetentionRequest
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class ImportedPressureHistoryEvaluatorTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runTest {
		database = newDatabase()
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `imported only entry is discoverable without a live run or fact`() = runTest {
		val request = request()
		importer(database, testScheduler).importEntry(request) shouldBe
			ImportPortablePressureResult.Applied(1L, 1, 1)

		val public = pageReader(database).selectRecent(10).single()

		public.origin shouldBe PressureHistoryOrigin.Imported(
			com.adsamcik.tracker.stats.api.repository.ImportedPressureHistoryIdentity(
				request.entry.identity.value,
			),
		)
		public.state shouldBe PressureHistoryPresentationState.READY
		public.pressure.summary?.latestHectopascals shouldBe 1_003f
		public.pressure.zoneAuthorities shouldBe setOf("Europe/Prague")
		database.pressureFactRevisionDao().count() shouldBe 0L
		database.sourceSessionDao().serviceRun(request.entry.runs.single().identity.value) shouldBe null
	}

	@Test
	fun `shared bridge carries exact lineage metadata and authenticated imported recency`() = runTest {
		val request = request()
		importer(database, testScheduler).importEntry(request)

		val page = database.withTransaction {
			pageReader(database).recentImportedEligibleForSharedHistoryInTransaction(10)
		} as ImportedHistoryEligiblePage.Available
		val row = page.entries.single()
		val newest = request.entry.runs.maxWith(pressureSourceRecencyRunOrder)

		row.identity shouldBe ImportedPressureHistoryIdentity(request.entry.identity.value)
		row.importRevision shouldBe 1L
		row.contentChecksum shouldBe request.entry.contentChecksum
		row.recency shouldBe ImportedHistoryRecency(
			HistorySource.PRESSURE,
			newest.startTimeMs,
			ImportedHistoryRecencyTieIdentity(newest.identity.value),
		)
	}

	@Test
	fun `retained shell bridge uses stored newest member tuple not public envelope`() = runTest {
		val older = entry(runLocalId = "older-run", windowLocalId = "older-window")
		val newestRun = PortablePressureRunV1(
			identity = identity(PortablePressureIdentityKind.PHYSICAL_RUN, "newest-run"),
			startTimeMs = 2_100L,
			endTimeMs = 2_200L,
			capturedForWholeRun = true,
			availability = PortablePressureAvailability.NO_RETAINED_OBSERVATION,
			coverage = PortablePressureCoverage.PARTIAL,
			retentionLoss = true,
			windows = emptyList(),
		)
		val retainedEntry = PortablePressureEntryV1.create(
			identity = older.identity,
			startTimeMs = older.startTimeMs,
			endTimeMs = newestRun.endTimeMs,
			runs = older.runs + newestRun,
		)
		val request = request(retainedEntry)
		importer(database, testScheduler).importEntry(request)
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH, 1_100L, 50L) shouldBe 1
		RoomTruncateImportedPressureRetention(
			database,
			UnconfinedTestDispatcher(testScheduler),
			{},
		).truncate(
			TruncateImportedPressureRetentionRequest(EPOCH, 1L, 1_100L, 50L),
		)

		val page = database.withTransaction {
			pageReader(database).recentImportedEligibleForSharedHistoryInTransaction(10)
		} as ImportedHistoryEligiblePage.Available
		val row = page.entries.single()

		row.recency shouldBe ImportedHistoryRecency(
			HistorySource.PRESSURE,
			newestRun.startTimeMs,
			ImportedHistoryRecencyTieIdentity(newestRun.identity.value),
		)
		row.recency.newestMemberStartTimeMs shouldBe 2_100L
		row.entry.startTime.raw shouldBe 1_000L
		row.identity shouldBe ImportedPressureHistoryIdentity(request.entry.identity.value)
		row.importRevision shouldBe 1L
		row.contentChecksum shouldBe request.entry.contentChecksum
	}

	@Test
	fun `shared bridge orders equal newest starts by opaque newest run tie identity`() = runTest {
		val first = retentionOnlyEntry(
			entryLocalId = "tie-entry-a",
			olderRunLocalId = "tie-older-a",
			newestRunLocalId = "tie-newest-a",
			envelopeStartTimeMs = 100L,
			newestStartTimeMs = 2_000L,
		)
		val second = retentionOnlyEntry(
			entryLocalId = "tie-entry-b",
			olderRunLocalId = "tie-older-b",
			newestRunLocalId = "tie-newest-b",
			envelopeStartTimeMs = 1_000L,
			newestStartTimeMs = 2_000L,
		)
		val writer = importer(database, testScheduler)
		writer.importEntry(request(first, receipt("tie-job-a", "tie-entry-a")))
		writer.importEntry(request(second, receipt("tie-job-b", "tie-entry-b")))
		val expected = listOf(first, second).sortedByDescending {
			it.runs.maxWith(pressureSourceRecencyRunOrder).identity.value
		}
		val firstCandidate = database.importedPressureDao().recentHistoryCandidatePage(
			limit = 1,
			beforeRecencyStartTimeMs = null,
			beforeRecencyTieIdentity = null,
		).single()
		firstCandidate.identity shouldBe expected.first().identity.value
		val secondCandidate = database.importedPressureDao().recentHistoryCandidatePage(
			limit = 1,
			beforeRecencyStartTimeMs = firstCandidate.recencyStartTimeMs,
			beforeRecencyTieIdentity = firstCandidate.recencyTieIdentity,
		).single()
		secondCandidate.identity shouldBe expected.last().identity.value

		val page = database.withTransaction {
			pageReader(database).recentImportedEligibleForSharedHistoryInTransaction(2)
		} as ImportedHistoryEligiblePage.Available

		page.entries.map { it.identity } shouldBe expected.map {
			ImportedPressureHistoryIdentity(it.identity.value)
		}
		page.entries.map { it.recency.newestMemberStartTimeMs } shouldBe listOf(2_000L, 2_000L)
	}

	@Test
	fun `shared bridge scans past a filled envelope page before applying recency cutoff`() = runTest {
		val newerEnvelope = retentionOnlyEntry(
			entryLocalId = "newer-envelope-entry",
			olderRunLocalId = "newer-envelope-old-run",
			newestRunLocalId = "newer-envelope-new-run",
			envelopeStartTimeMs = 1_000L,
			newestStartTimeMs = 2_000L,
		)
		val olderEnvelopeNewerMember = retentionOnlyEntry(
			entryLocalId = "older-envelope-entry",
			olderRunLocalId = "older-envelope-old-run",
			newestRunLocalId = "older-envelope-new-run",
			envelopeStartTimeMs = 100L,
			newestStartTimeMs = 3_000L,
		)
		val writer = importer(database, testScheduler)
		writer.importEntry(request(
			newerEnvelope,
			receipt("newer-envelope-job", "newer-envelope-entry"),
		)) shouldBe ImportPortablePressureResult.Applied(1L, 2, 0)
		writer.importEntry(request(
			olderEnvelopeNewerMember,
			receipt("older-envelope-job", "older-envelope-entry"),
		)) shouldBe ImportPortablePressureResult.Applied(1L, 2, 0)

		val page = database.withTransaction {
			pageReader(database).recentImportedEligibleForSharedHistoryInTransaction(1)
		} as ImportedHistoryEligiblePage.Available

		page.entries.single().identity shouldBe
			ImportedPressureHistoryIdentity(olderEnvelopeNewerMember.identity.value)
		page.entries.single().recency.newestMemberStartTimeMs shouldBe 3_000L
	}

	@Test
	fun `retained recency keyset survives reopen and limit one continuation`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		context.deleteDatabase(REOPEN_DATABASE)
		var openedDatabase: AppDatabase? = null
		try {
			val initialDatabase = Room.databaseBuilder(
				context,
				AppDatabase::class.java,
				REOPEN_DATABASE,
			).allowMainThreadQueries().build()
			openedDatabase = initialDatabase
			initialDatabase.sourceEvidenceStateDao().ensure(
				SourceEvidenceState(collectedDataEpoch = EPOCH),
			)
			val newerEnvelope = retentionOnlyEntry(
				entryLocalId = "reopen-newer-envelope",
				olderRunLocalId = "reopen-newer-envelope-old",
				newestRunLocalId = "reopen-newer-envelope-new",
				envelopeStartTimeMs = 1_000L,
				newestStartTimeMs = 2_000L,
			)
			val olderEnvelopeNewerMember = retentionOnlyEntry(
				entryLocalId = "reopen-older-envelope",
				olderRunLocalId = "reopen-older-envelope-old",
				newestRunLocalId = "reopen-older-envelope-new",
				envelopeStartTimeMs = 100L,
				newestStartTimeMs = 3_000L,
			)
			val writer = importer(initialDatabase, testScheduler)
			writer.importEntry(request(
				newerEnvelope,
				receipt("reopen-newer-job", "reopen-newer-entry"),
			)) shouldBe ImportPortablePressureResult.Applied(1L, 2, 0)
			writer.importEntry(request(
				olderEnvelopeNewerMember,
				receipt("reopen-older-job", "reopen-older-entry"),
			)) shouldBe ImportPortablePressureResult.Applied(1L, 2, 0)
			val liveFirst = initialDatabase.importedPressureDao().recentHistoryCandidatePage(
				limit = 1,
				beforeRecencyStartTimeMs = null,
				beforeRecencyTieIdentity = null,
			).single()
			liveFirst.identity shouldBe olderEnvelopeNewerMember.identity.value
			liveFirst.candidateState shouldBe "LIVE"
			liveFirst.recencyStartTimeMs shouldBe 3_000L
			initialDatabase.sourceEvidenceStateDao().updateLifecycle(EPOCH, 1_100L, 50L) shouldBe 1
			RoomTruncateImportedPressureRetention(
				initialDatabase,
				UnconfinedTestDispatcher(testScheduler),
				{},
			).truncate(
				TruncateImportedPressureRetentionRequest(EPOCH, 1L, 1_100L, 50L),
			)
			initialDatabase.close()
			openedDatabase = null

			val reopenedDatabase = Room.databaseBuilder(
				context,
				AppDatabase::class.java,
				REOPEN_DATABASE,
			).allowMainThreadQueries().build()
			openedDatabase = reopenedDatabase
			val first = reopenedDatabase.importedPressureDao().recentHistoryCandidatePage(
				limit = 1,
				beforeRecencyStartTimeMs = null,
				beforeRecencyTieIdentity = null,
			).single()
			first.identity shouldBe olderEnvelopeNewerMember.identity.value
			first.candidateState shouldBe "RETAINED"
			first.startTimeMs shouldBe 100L
			first.recencyStartTimeMs shouldBe 3_000L
			val second = reopenedDatabase.importedPressureDao().recentHistoryCandidatePage(
				limit = 1,
				beforeRecencyStartTimeMs = first.recencyStartTimeMs,
				beforeRecencyTieIdentity = first.recencyTieIdentity,
			).single()
			second.identity shouldBe newerEnvelope.identity.value
			second.candidateState shouldBe "RETAINED"
			second.recencyStartTimeMs shouldBe 2_000L

			val page = reopenedDatabase.withTransaction {
				pageReader(reopenedDatabase).recentImportedEligibleForSharedHistoryInTransaction(1)
			} as ImportedHistoryEligiblePage.Available
			page.entries.single().identity shouldBe
				ImportedPressureHistoryIdentity(olderEnvelopeNewerMember.identity.value)
		} finally {
			openedDatabase?.close()
			context.deleteDatabase(REOPEN_DATABASE)
		}
	}

	@Test
	fun `corrupt retained recency authority is a typed shared history failure`() = runTest {
		val entry = retentionOnlyEntry(
			entryLocalId = "corrupt-retained-recency-entry",
			olderRunLocalId = "corrupt-retained-recency-old",
			newestRunLocalId = "corrupt-retained-recency-new",
			envelopeStartTimeMs = 100L,
			newestStartTimeMs = 3_000L,
		)
		importer(database, testScheduler).importEntry(request(entry)) shouldBe
			ImportPortablePressureResult.Applied(1L, 2, 0)
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH, 1_100L, 50L) shouldBe 1
		RoomTruncateImportedPressureRetention(
			database,
			UnconfinedTestDispatcher(testScheduler),
			{},
		).truncate(
			TruncateImportedPressureRetentionRequest(EPOCH, 1L, 1_100L, 50L),
		)
		val forgedTie = identity(
			PortablePressureIdentityKind.PHYSICAL_RUN,
			"forged-retained-recency",
		).value
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_pressure_retention_receipt SET recency_tie_identity = ? " +
				"WHERE entry_identity = ?",
			arrayOf(forgedTie, entry.identity.value),
		)

		database.withTransaction {
			pageReader(database).recentImportedEligibleForSharedHistoryInTransaction(1)
		} shouldBe ImportedHistoryEligiblePage.Unavailable(
			SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
		)
	}

	@Test
	fun `shared bridge returns source budget failure when an imported candidate remains beyond cap`() =
		runTest {
			val writer = importer(database, testScheduler)
			repeat(PressurePortableFormatV1.MAX_ENTRIES + 1) { index ->
				val entry = retentionOnlyEntry(
					entryLocalId = "budget-entry-$index",
					olderRunLocalId = "budget-old-run-$index",
					newestRunLocalId = "budget-new-run-$index",
					envelopeStartTimeMs = index * 10L,
					newestStartTimeMs = 10_000L + index,
				)
				writer.importEntry(request(
					entry,
					receipt("budget-job-$index", "budget-entry-$index"),
				)) shouldBe ImportPortablePressureResult.Applied(1L, 2, 0)
			}

			database.withTransaction {
				pageReader(database).recentImportedEligibleForSharedHistoryInTransaction(1)
			} shouldBe ImportedHistoryEligiblePage.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
			)
		}

	@Test
	fun `equal run starts select opaque identity rather than longer end for live and retained recency`() =
		runTest {
			val entry = equalStartIdentityOrderedEntry()
			val expectedNewest = entry.runs.filter { it.startTimeMs == 2_000L }
				.maxBy { it.identity.value }
			val writer = importer(database, testScheduler)
			writer.importEntry(request(entry)) shouldBe
				ImportPortablePressureResult.Applied(1L, 3, 0)

			val live = database.withTransaction {
				pageReader(database).recentImportedEligibleForSharedHistoryInTransaction(1)
			} as ImportedHistoryEligiblePage.Available
			live.entries.single().recency.newestMemberTieIdentity shouldBe
				ImportedHistoryRecencyTieIdentity(expectedNewest.identity.value)

			database.sourceEvidenceStateDao().updateLifecycle(EPOCH, 1_100L, 50L) shouldBe 1
			RoomTruncateImportedPressureRetention(
				database,
				UnconfinedTestDispatcher(testScheduler),
				{},
			).truncate(
				TruncateImportedPressureRetentionRequest(EPOCH, 1L, 1_100L, 50L),
			)
			val retained = database.importedPressureDao().retentionReceipt(entry.identity.value)
			retained?.recencyTieIdentity shouldBe expectedNewest.identity.value
			retained?.recencyEndTimeMs shouldBe expectedNewest.endTimeMs
		}

	@Test
	fun `latest authenticated correction is the only imported product revision`() = runTest {
		val first = request()
		val corrected = request(
			entry(wallTimeUncertaintyMs = 26L),
			receipt(jobId = "job-2", entryKey = "entry-2", receivedAtMs = 40L),
		)
		val writer = importer(database, testScheduler)
		writer.importEntry(first) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)
		writer.importEntry(corrected) shouldBe ImportPortablePressureResult.Applied(2L, 1, 1)

		val selected = evaluate(database).single() as ImportedPressureHistoryEvaluation.Readable
		selected.latest.header.importRevision shouldBe 2L
		selected.latest.entry shouldBe corrected.entry
		val eligible = database.withTransaction {
			pageReader(database).recentImportedEligibleForSharedHistoryInTransaction(1)
		} as ImportedHistoryEligiblePage.Available
		eligible.entries.single().importRevision shouldBe 2L
		eligible.entries.single().contentChecksum shouldBe corrected.entry.contentChecksum
	}

	@Test
	fun `retention-only imported evidence remains partial and never fabricates pressure`() = runTest {
		val retainedLoss = PortablePressureRunV1(
			identity = identity(PortablePressureIdentityKind.PHYSICAL_RUN, "retained-loss"),
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			capturedForWholeRun = true,
			availability = PortablePressureAvailability.NO_RETAINED_OBSERVATION,
			coverage = PortablePressureCoverage.PARTIAL,
			retentionLoss = true,
			windows = emptyList(),
		)
		val portable = PortablePressureEntryV1.create(
			identity = identity(PortablePressureIdentityKind.LOGICAL_ENTRY, "retained-entry"),
			startTimeMs = retainedLoss.startTimeMs,
			endTimeMs = retainedLoss.endTimeMs,
			runs = listOf(retainedLoss),
		)
		importer(database, testScheduler).importEntry(request(portable)) shouldBe
			ImportPortablePressureResult.Applied(1L, 1, 0)

		val public = evaluate(database).single().toPublicPressureOnlyEntry()
		public.state shouldBe PressureHistoryPresentationState.PARTIAL
		public.pressure.availability shouldBe HistoryAvailability.AVAILABLE
		public.pressure.evidence shouldBe HistoryEvidence.NONE
		public.pressure.summary shouldBe null
		public.pressure.windows shouldBe emptyList()
		public.pressure.productState shouldBe HistoryProductState.PARTIAL
	}

	@Test
	fun `local retained floor makes a crossing import partial and prevents full re-export`() = runTest {
		importer(database, testScheduler).importEntry(request())
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = EPOCH,
			retainedFromMs = 1_100L,
			updatedAtMs = 50L,
		) shouldBe 1

		val public = evaluate(database).single().toPublicPressureOnlyEntry()
		public.state shouldBe PressureHistoryPresentationState.PARTIAL
		public.pressure.summary shouldBe null
		public.pressure.windows shouldBe emptyList()
		exporter(database, testScheduler).export(
			ExportPortablePressureRequest(0L, 3_000L),
			PortablePressureEntrySink { error("Retention-limited import must not be re-exported whole") },
		) shouldBe ExportPortablePressureResult.NoEntries
	}

	@Test
	fun `exact local portable content suppresses the imported duplicate`() = runTest {
		val request = request()
		importer(database, testScheduler).importEntry(request)
		val imported = evaluate(database).single() as ImportedPressureHistoryEvaluation.Readable
		val importedPublic = imported.toPublicPressureOnlyEntry()
		val localPublic = importedPublic.copy(
			key = TrackingHistoryEntryKey("pressure:local-test"),
			origin = PressureHistoryOrigin.Local,
		)

		val composed = PressureHistoryPageComposer.compose(
			live = listOf(LocalPressurePageCandidate(localPublic, request.entry)),
			imported = listOf(imported),
			limit = 10,
		)

		composed shouldBe listOf(localPublic)
	}

	@Test
	fun `same portable identity with different proven content preserves both origins`() = runTest {
		val request = request()
		importer(database, testScheduler).importEntry(request)
		val imported = evaluate(database).single() as ImportedPressureHistoryEvaluation.Readable
		val importedPublic = imported.toPublicPressureOnlyEntry()
		val localPublic = importedPublic.copy(
			key = TrackingHistoryEntryKey("pressure:local-distinct"),
			origin = PressureHistoryOrigin.Local,
		)
		val differentContent = entry(wallTimeUncertaintyMs = 27L)

		val composed = PressureHistoryPageComposer.compose(
			live = listOf(LocalPressurePageCandidate(localPublic, differentContent)),
			imported = listOf(imported),
			limit = 10,
		)

		composed.size shouldBe 2
		composed.map { it.origin }.toSet() shouldBe setOf(
			PressureHistoryOrigin.Local,
			importedPublic.origin,
		)
	}

	@Test
	fun `retained run tombstone is deleted history and blocks old receipt replay`() = runTest {
		val request = request()
		val writer = importer(database, testScheduler)
		writer.importEntry(request) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)
		val runIdentity = request.entry.runs.single().identity.value
		database.importedPressureDao().insertDeletionGeneration(
			ImportedPressureDeletionGenerationEntity.create(
				runIdentity = runIdentity,
				collectedDataEpoch = EPOCH,
				generation = 1L,
				deletedAtMs = 60L,
			),
		)

		val public = evaluate(database).single().toPublicPressureOnlyEntry()
		public.state shouldBe PressureHistoryPresentationState.DELETED
		public.pressure.summary shouldBe null
		writer.importEntry(request) shouldBe ImportPortablePressureResult.Blocked(
			PortablePressureImportBlockedReason.DELETED_RUN,
		)
		exporter(database, testScheduler).export(
			ExportPortablePressureRequest(0L, 3_000L),
			PortablePressureEntrySink { error("Tombstoned import must not be emitted") },
		) shouldBe ExportPortablePressureResult.NoEntries
	}

	@Test
	fun `superseded run tombstone makes corrected history nonnumeric and unexportable`() = runTest {
		val first = request(
			entry(runLocalId = "run-a", windowLocalId = "window-a"),
			receipt(jobId = "job-a", entryKey = "entry-a", receivedAtMs = 30L),
		)
		val corrected = request(
			entry(runLocalId = "run-b", windowLocalId = "window-b", wallTimeUncertaintyMs = 26L),
			receipt(jobId = "job-b", entryKey = "entry-b", receivedAtMs = 40L),
		)
		val writer = importer(database, testScheduler)
		writer.importEntry(first) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)
		writer.importEntry(corrected) shouldBe ImportPortablePressureResult.Applied(2L, 1, 1)
		database.importedPressureDao().insertDeletionGeneration(
			ImportedPressureDeletionGenerationEntity.create(
				runIdentity = first.entry.runs.single().identity.value,
				collectedDataEpoch = EPOCH,
				generation = 1L,
				deletedAtMs = 60L,
			),
		)

		val selected = evaluate(database).single()
		selected shouldBe ImportedPressureHistoryEvaluation.Unverifiable(
			selected.candidate,
			ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
		)
		val public = selected.toPublicPressureOnlyEntry()
		public.state shouldBe PressureHistoryPresentationState.UNVERIFIABLE
		public.pressure.summary shouldBe null
		public.pressure.windows shouldBe emptyList()
		var emitted = false
		exporter(database, testScheduler).export(
			ExportPortablePressureRequest(0L, 3_000L),
			PortablePressureEntrySink { emitted = true },
		) shouldBe ExportPortablePressureResult.Unverifiable(
			PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
		)
		emitted shouldBe false
	}

	@Test
	fun `run identity reused by latest correction preserves typed deleted history`() = runTest {
		val first = request()
		val corrected = request(
			entry(wallTimeUncertaintyMs = 26L),
			receipt(jobId = "job-2", entryKey = "entry-2", receivedAtMs = 40L),
		)
		val writer = importer(database, testScheduler)
		writer.importEntry(first) shouldBe ImportPortablePressureResult.Applied(1L, 1, 1)
		writer.importEntry(corrected) shouldBe ImportPortablePressureResult.Applied(2L, 1, 1)
		first.entry.runs.single().identity shouldBe corrected.entry.runs.single().identity
		database.importedPressureDao().insertDeletionGeneration(
			ImportedPressureDeletionGenerationEntity.create(
				runIdentity = first.entry.runs.single().identity.value,
				collectedDataEpoch = EPOCH,
				generation = 1L,
				deletedAtMs = 60L,
			),
		)

		val selected = evaluate(database).single() as ImportedPressureHistoryEvaluation.Readable
		selected.deletedRunIdentities shouldBe setOf(first.entry.runs.single().identity.value)
		val public = selected.toPublicPressureOnlyEntry()
		public.state shouldBe PressureHistoryPresentationState.DELETED
		public.pressure.summary shouldBe null
		public.pressure.windows shouldBe emptyList()
	}

	@Test
	fun `stored checksum corruption is a typed unverifiable history row`() = runTest {
		val request = request()
		importer(database, testScheduler).importEntry(request)
		val corrupt = identity(PortablePressureIdentityKind.WINDOW, "corrupt-checksum").value
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_pressure_entry_revision SET content_checksum = ? WHERE identity = ?",
			arrayOf(corrupt, request.entry.identity.value),
		)

		val selected = evaluate(database).single()
		selected shouldBe ImportedPressureHistoryEvaluation.Unverifiable(
			selected.candidate,
			ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
		)
		selected.toPublicPressureOnlyEntry().state shouldBe
			PressureHistoryPresentationState.UNVERIFIABLE
		database.withTransaction {
			pageReader(database).recentImportedEligibleForSharedHistoryInTransaction(1)
		} shouldBe ImportedHistoryEligiblePage.Unavailable(
			SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
		)
	}

	@Test
	fun `missing source evidence returns unavailable imported recency authority`() = runTest {
		importer(database, testScheduler).importEntry(request())
		database.openHelper.writableDatabase.execSQL("DELETE FROM source_evidence_state")

		database.withTransaction {
			pageReader(database).recentImportedEligibleForSharedHistoryInTransaction(1)
		} shouldBe ImportedHistoryEligiblePage.Unavailable(
			SourceAwareHistoryPageUnavailableReason.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE,
		)
	}

	@Test
	fun `stale imported epoch exposes no pressure value`() = runTest {
		importer(database, testScheduler).importEntry(request())
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = EPOCH + 1L,
			retainedFromMs = null,
			updatedAtMs = 70L,
		) shouldBe 1

		val selected = evaluate(database).single()
		selected shouldBe ImportedPressureHistoryEvaluation.Unverifiable(
			selected.candidate,
			ImportedPressureHistoryFailure.STALE_COLLECTED_DATA_EPOCH,
		)
		selected.toPublicPressureOnlyEntry().pressure.summary shouldBe null
	}

	@Test
	fun `revision overflow is a typed dependency overflow`() = runTest {
		val request = request()
		importer(database, testScheduler).importEntry(request)
		val sqlite = database.openHelper.writableDatabase
		for (revision in 2L..17L) {
			sqlite.execSQL(
				"""
				INSERT INTO imported_pressure_entry_revision (
				  identity, import_revision, supersedes_import_revision, content_checksum,
				  source_format, source_schema_version, start_time_ms, end_time_ms,
				  collected_data_epoch, import_job_id, import_entry_key, import_source_name,
				  received_at_ms
				)
				SELECT identity, ?, ?, content_checksum, source_format, source_schema_version,
				       start_time_ms, end_time_ms, collected_data_epoch, ?, ?, import_source_name,
				       received_at_ms + ?
				FROM imported_pressure_entry_revision
				WHERE identity = ? AND import_revision = 1
				""".trimIndent(),
				arrayOf(
					revision,
					revision - 1L,
					"overflow-job-$revision",
					"overflow-entry-$revision",
					revision,
					request.entry.identity.value,
				),
			)
		}

		val selected = evaluate(database).single()
		selected shouldBe ImportedPressureHistoryEvaluation.Unverifiable(
			selected.candidate,
			ImportedPressureHistoryFailure.DEPENDENCY_OVERFLOW,
		)
		database.withTransaction {
			pageReader(database).recentImportedEligibleForSharedHistoryInTransaction(1)
		} shouldBe ImportedHistoryEligiblePage.Unavailable(
			SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
		)
	}

	@Test
	fun `import history export and second database import preserve portable v1 content`() = runTest {
		val original = request()
		importer(database, testScheduler).importEntry(original)
		val emitted = mutableListOf<PortablePressureEntryV1>()

		exporter(database, testScheduler).export(
			ExportPortablePressureRequest(0L, 3_000L),
			PortablePressureEntrySink { emitted += it },
		) shouldBe ExportPortablePressureResult.Exported(1)
		emitted shouldBe listOf(original.entry)

		val target = newDatabase()
		try {
			target.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
			val targetRequest = request(
				emitted.single(),
				receipt(jobId = "target-job", entryKey = "target-entry", receivedAtMs = 80L),
			)
			importer(target, testScheduler).importEntry(targetRequest) shouldBe
				ImportPortablePressureResult.Applied(1L, 1, 1)
			val roundTrip = evaluate(target).single() as ImportedPressureHistoryEvaluation.Readable
			roundTrip.latest.entry shouldBe original.entry
		} finally {
			target.close()
		}
	}

	private suspend fun evaluate(
		database: AppDatabase,
	): List<ImportedPressureHistoryEvaluation> = database.withTransaction {
		ImportedPressureHistoryEvaluator(database).selectRecentInTransaction(10)
	}

	private fun importer(
		database: AppDatabase,
		scheduler: TestCoroutineScheduler,
	) = RoomImportPortablePressure(database, UnconfinedTestDispatcher(scheduler)) {}

	private fun exporter(
		database: AppDatabase,
		scheduler: TestCoroutineScheduler,
	): RoomExportPortablePressure {
		val evaluator = ImportedPressureHistoryEvaluator(database)
		val selector = PressureHistorySelector(
			database,
			SourceProductLaneExecutionAuthority { false },
		)
		return RoomExportPortablePressure(
			PortablePressureRoomReader(database, selector, evaluator),
			UnconfinedTestDispatcher(scheduler),
		)
	}

	private fun pageReader(database: AppDatabase): PressureHistoryPageReader {
		val evaluator = ImportedPressureHistoryEvaluator(database)
		val selector = PressureHistorySelector(
			database,
			SourceProductLaneExecutionAuthority { false },
		)
		return PressureHistoryPageReader(
			database = database,
			liveSelector = selector,
			importedEvaluator = evaluator,
			portableReader = PortablePressureRoomReader(database, selector, evaluator),
		)
	}

	private fun request(
		portableEntry: PortablePressureEntryV1 = entry(),
		receipt: PortablePressureImportReceipt = receipt(),
	) = ImportPortablePressureRequest(portableEntry, receipt, EPOCH)

	private fun receipt(
		jobId: String = "job-1",
		entryKey: String = "entry-1",
		receivedAtMs: Long = 30L,
	) = PortablePressureImportReceipt(
		jobId = jobId,
		entryKey = entryKey,
		sourceName = "backup.trackerpressure",
		receivedAtMs = receivedAtMs,
	)

	private fun entry(
		wallTimeUncertaintyMs: Long = 25L,
		runLocalId: String = "run",
		windowLocalId: String = "window",
		entryLocalId: String = "entry",
	): PortablePressureEntryV1 {
		val run = PortablePressureRunV1(
			identity = identity(PortablePressureIdentityKind.PHYSICAL_RUN, runLocalId),
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			capturedForWholeRun = true,
			availability = PortablePressureAvailability.RETAINED,
			coverage = PortablePressureCoverage.COMPLETE,
			retentionLoss = false,
			windows = listOf(window(wallTimeUncertaintyMs, windowLocalId)),
		)
		return PortablePressureEntryV1.create(
			identity = identity(PortablePressureIdentityKind.LOGICAL_ENTRY, entryLocalId),
			startTimeMs = run.startTimeMs,
			endTimeMs = run.endTimeMs,
			runs = listOf(run),
		)
	}

	private fun retentionOnlyEntry(
		entryLocalId: String,
		olderRunLocalId: String,
		newestRunLocalId: String,
		envelopeStartTimeMs: Long,
		newestStartTimeMs: Long,
	): PortablePressureEntryV1 {
		val older = PortablePressureRunV1(
			identity = identity(PortablePressureIdentityKind.PHYSICAL_RUN, olderRunLocalId),
			startTimeMs = envelopeStartTimeMs,
			endTimeMs = envelopeStartTimeMs + 100L,
			capturedForWholeRun = true,
			availability = PortablePressureAvailability.NO_RETAINED_OBSERVATION,
			coverage = PortablePressureCoverage.PARTIAL,
			retentionLoss = true,
			windows = emptyList(),
		)
		val newest = PortablePressureRunV1(
			identity = identity(PortablePressureIdentityKind.PHYSICAL_RUN, newestRunLocalId),
			startTimeMs = newestStartTimeMs,
			endTimeMs = newestStartTimeMs + 100L,
			capturedForWholeRun = true,
			availability = PortablePressureAvailability.NO_RETAINED_OBSERVATION,
			coverage = PortablePressureCoverage.PARTIAL,
			retentionLoss = true,
			windows = emptyList(),
		)
		return PortablePressureEntryV1.create(
			identity = identity(PortablePressureIdentityKind.LOGICAL_ENTRY, entryLocalId),
			startTimeMs = envelopeStartTimeMs,
			endTimeMs = newest.endTimeMs,
			runs = listOf(older, newest),
		)
	}

	private fun equalStartIdentityOrderedEntry(): PortablePressureEntryV1 {
		val identities = listOf(
			identity(PortablePressureIdentityKind.PHYSICAL_RUN, "same-start-a"),
			identity(PortablePressureIdentityKind.PHYSICAL_RUN, "same-start-z"),
		).sortedBy { it.value }
		val older = PortablePressureRunV1(
			identity = identity(PortablePressureIdentityKind.PHYSICAL_RUN, "same-start-older"),
			startTimeMs = 1_000L,
			endTimeMs = 1_100L,
			capturedForWholeRun = true,
			availability = PortablePressureAvailability.NO_RETAINED_OBSERVATION,
			coverage = PortablePressureCoverage.PARTIAL,
			retentionLoss = true,
			windows = emptyList(),
		)
		val longerLowerIdentity = PortablePressureRunV1(
			identity = identities.first(),
			startTimeMs = 2_000L,
			endTimeMs = 2_300L,
			capturedForWholeRun = true,
			availability = PortablePressureAvailability.NO_RETAINED_OBSERVATION,
			coverage = PortablePressureCoverage.PARTIAL,
			retentionLoss = true,
			windows = emptyList(),
		)
		val shorterHigherIdentity = PortablePressureRunV1(
			identity = identities.last(),
			startTimeMs = 2_000L,
			endTimeMs = 2_100L,
			capturedForWholeRun = true,
			availability = PortablePressureAvailability.NO_RETAINED_OBSERVATION,
			coverage = PortablePressureCoverage.PARTIAL,
			retentionLoss = true,
			windows = emptyList(),
		)
		val runs = listOf(older, longerLowerIdentity, shorterHigherIdentity)
			.sortedWith(PORTABLE_PRESSURE_RUN_ORDER)
		return PortablePressureEntryV1.create(
			identity = identity(PortablePressureIdentityKind.LOGICAL_ENTRY, "same-start-entry"),
			startTimeMs = older.startTimeMs,
			endTimeMs = longerLowerIdentity.endTimeMs,
			runs = runs,
		)
	}

	@Suppress("LongMethod")
	private fun window(
		wallTimeUncertaintyMs: Long,
		windowLocalId: String,
	) = PortablePressureWindowV1.create(
		identity = identity(PortablePressureIdentityKind.WINDOW, windowLocalId),
		intervalStartTimeMs = 1_000L,
		intervalEndTimeMs = 1_150L,
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		observedDurationNanos = 150_000_000L,
		sampleCount = 4,
		expectedSampleCount = 4,
		meanHectopascals = 1_001.5,
		sumSquaredDeviations = 5.0,
		minimumHectopascals = 1_000f,
		maximumHectopascals = 1_003f,
		firstHectopascals = 1_000f,
		latestHectopascals = 1_003f,
		slopeHectopascalsPerSecond = 15.0,
		rSquared = 1.0,
		sensorAccuracy = PortablePressureSensorAccuracy.HIGH,
		effectiveSamplePeriodMicros = 50_000,
		effectiveMaximumReportLatencyMicros = 0,
		targetWindowDurationNanos = 200_000_000L,
		maximumInterSampleGapNanos = 50_000_000L,
		closure = PortablePressureWindowClosure.TARGET_ELAPSED,
		qualification = PortablePressureWindowQualification.COMPLETE,
		sourceQualityFlags = 0L,
		sourceQualityConfidence = 1f,
		zoneId = "Europe/Prague",
	)

	private fun identity(kind: PortablePressureIdentityKind, local: String) =
		PortablePressureOpaqueIdentity.derive(kind, local)

	private fun newDatabase(): AppDatabase = AppDatabase.testDatabase(
		ApplicationProvider.getApplicationContext<Application>(),
	)

	private companion object {
		const val EPOCH = 7L
		const val REOPEN_DATABASE = "pressure-imported-history-recency-reopen"
	}
}
