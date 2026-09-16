package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableIntegrity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedActivity
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedActivityRequest
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedActivityResult
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.PortableActivityCaptureCoverage
import com.adsamcik.tracker.shared.base.database.PortableActivityDeletionScopeDigest
import com.adsamcik.tracker.shared.base.database.PortableActivityEntryV1
import com.adsamcik.tracker.shared.base.database.PortableActivityFragmentV1
import com.adsamcik.tracker.shared.base.database.PortableActivityIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableActivityImportReceipt
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableActivityRunV1
import com.adsamcik.tracker.shared.base.database.PortableActivitySessionMode
import com.adsamcik.tracker.shared.base.database.PortableActivityWindowCoverage
import com.adsamcik.tracker.shared.base.database.PortableActivityWindowV1
import com.adsamcik.tracker.shared.base.database.PortableActivityZoneEpochV1
import com.adsamcik.tracker.shared.base.database.RoomDeleteSelectedImportedActivity
import com.adsamcik.tracker.shared.base.database.RoomImportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.SelectedImportedActivityDeletionBlockedReason
import com.adsamcik.tracker.shared.base.database.SelectedImportedActivityRunDeletionScope
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivityHistorySelection
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryDigest
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryRunDeletionScope
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistorySelection
import com.adsamcik.tracker.stats.api.repository.ActivitySelectionDeletionRequest
import com.adsamcik.tracker.stats.api.repository.ActivitySelectionDeletionResult
import com.adsamcik.tracker.stats.api.repository.ActivitySelectionDeletionBlockedReason
import com.adsamcik.tracker.stats.api.repository.ActivitySelectionDeletionUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.ActivitySessionDeletion
import com.adsamcik.tracker.stats.api.repository.ActivitySessionDeletionResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class RoomActivitySelectionDeletionTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runTest {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `imported selection forwards exact opaque hierarchy and never invokes local deletion`() = runTest {
		val entry = entry()
		RoomImportPortableCapturedActivity(
			database,
			UnconfinedTestDispatcher(testScheduler),
		).importEntry(importRequest(entry)) shouldBe
			ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1)
		var importedRequest: DeleteSelectedImportedActivityRequest? = null
		var localCalls = 0
		val subject = RoomActivitySelectionDeletion(
			database = database,
			localDeletion = localDeletion {
				localCalls++
				ActivitySessionDeletionResult.Deleted
			},
			importedDeletion = importedDeletion { request ->
				importedRequest = request
				DeleteSelectedImportedActivityResult.Deleted(1, 1)
			},
			ioDispatcher = UnconfinedTestDispatcher(testScheduler),
		)

		subject.delete(
			ActivitySelectionDeletionRequest(
				selection = ActivityHistorySelection.Imported(importedSelection(entry)),
				deletedAtMs = 100L,
			),
		) shouldBe ActivitySelectionDeletionResult.Deleted(ActivityHistoryOrigin.IMPORTED)

		localCalls shouldBe 0
		importedRequest?.expectedCollectedDataEpoch shouldBe EPOCH
		importedRequest?.expectedSourceEvidenceRevision shouldBe 0L
		importedRequest?.selected?.entryIdentity shouldBe entry.identity
		importedRequest?.selected?.importRevision shouldBe 1L
		importedRequest?.selected?.contentChecksum shouldBe entry.contentChecksum
		importedRequest?.selected?.runDeletionScopes shouldBe entry.runs.map { run ->
			SelectedImportedActivityRunDeletionScope(
				runIdentity = run.identity,
				deletionScopeDigest = run.deletionScopeDigest,
			)
		}
		importedRequest?.selected?.windowIdentities shouldBe entry.runs.flatMap { run ->
			run.windows.map(PortableActivityWindowV1::identity)
		}
	}

	@Test
	fun `stale imported range selection remains typed and cannot fall through to local`() = runTest {
		val entry = entry()
		RoomImportPortableCapturedActivity(
			database,
			UnconfinedTestDispatcher(testScheduler),
		).importEntry(importRequest(entry))
		var localCalls = 0
		val subject = RoomActivitySelectionDeletion(
			database,
			localDeletion {
				localCalls++
				ActivitySessionDeletionResult.Deleted
			},
			importedDeletion {
				DeleteSelectedImportedActivityResult.Blocked(
					SelectedImportedActivityDeletionBlockedReason.STALE_SELECTION,
				)
			},
			UnconfinedTestDispatcher(testScheduler),
		)

		subject.delete(
			ActivitySelectionDeletionRequest(
				ActivityHistorySelection.Imported(importedSelection(entry)),
				deletedAtMs = 100L,
			),
		) shouldBe ActivitySelectionDeletionResult.Blocked(
			ActivitySelectionDeletionBlockedReason.STALE_SELECTION,
		)
		localCalls shouldBe 0
	}

	@Test
	fun `declared local origin cannot borrow imported selection even at the same wall time`() = runTest {
		val entry = entry()
		RoomImportPortableCapturedActivity(
			database,
			UnconfinedTestDispatcher(testScheduler),
		).importEntry(importRequest(entry))
		var localCalls = 0
		var importedCalls = 0
		val subject = RoomActivitySelectionDeletion(
			database,
			localDeletion {
				localCalls++
				ActivitySessionDeletionResult.Deleted
			},
			importedDeletion {
				importedCalls++
				DeleteSelectedImportedActivityResult.Deleted(1, 1)
			},
			UnconfinedTestDispatcher(testScheduler),
		)

		subject.delete(
			ActivitySelectionDeletionRequest(
				ActivityHistorySelection.Local(
					ActivityHistoryEntryKey("activity-imported:${entry.identity.value}"),
				),
				deletedAtMs = entry.endTimeMs,
			),
		) shouldBe ActivitySelectionDeletionResult.NotFound
		localCalls shouldBe 0
		importedCalls shouldBe 0
	}

	@Test
	fun `corrupt imported hierarchy fails before mutation`() = runTest {
		val entry = entry()
		RoomImportPortableCapturedActivity(
			database,
			UnconfinedTestDispatcher(testScheduler),
		).importEntry(importRequest(entry))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_activity_run SET content_checksum = ? WHERE entry_identity = ?",
			arrayOf("f".repeat(64), entry.identity.value),
		)
		val subject = RoomActivitySelectionDeletion(
			database,
			localDeletion { ActivitySessionDeletionResult.NotFound },
			RoomDeleteSelectedImportedActivity(
				database,
				UnconfinedTestDispatcher(testScheduler),
			),
			UnconfinedTestDispatcher(testScheduler),
		)

		subject.delete(
			ActivitySelectionDeletionRequest(
				ActivityHistorySelection.Imported(importedSelection(entry)),
				deletedAtMs = 100L,
			),
		) shouldBe ActivitySelectionDeletionResult.Unverifiable(
			ActivitySelectionDeletionUnverifiableReason.IMPORTED_EVIDENCE_UNVERIFIABLE,
		)
		database.importedActivityDao().entryDeletion(entry.identity.value) shouldBe null
	}

	@Test
	fun `exact imported selection deletes revision and replays idempotently`() = runTest {
		val entry = entry()
		RoomImportPortableCapturedActivity(
			database,
			UnconfinedTestDispatcher(testScheduler),
		).importEntry(importRequest(entry)) shouldBe
			ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1)
		val request = ActivitySelectionDeletionRequest(
			ActivityHistorySelection.Imported(importedSelection(entry)),
			deletedAtMs = 100L,
		)
		val subject = RoomActivitySelectionDeletion(
			database,
			localDeletion { ActivitySessionDeletionResult.NotFound },
			RoomDeleteSelectedImportedActivity(
				database,
				UnconfinedTestDispatcher(testScheduler),
			),
			UnconfinedTestDispatcher(testScheduler),
		)

		subject.delete(request) shouldBe
			ActivitySelectionDeletionResult.Deleted(ActivityHistoryOrigin.IMPORTED)
		subject.delete(request.copy(deletedAtMs = 110L)) shouldBe
			ActivitySelectionDeletionResult.AlreadyDeleted(ActivityHistoryOrigin.IMPORTED)

		val dao = database.importedActivityDao()
		dao.latestHistoryCandidate(entry.identity.value) shouldBe null
		dao.entryDeletion(entry.identity.value)?.deletedImportRevision shouldBe 1L
		dao.entryDeletionReceipt(entry.identity.value)?.deletedContentChecksum shouldBe
			entry.contentChecksum.value
	}

	@Test
	fun `correction after observed revision rejects stale action without mutation`() = runTest {
		val original = entry()
		val corrected = entry(gapReason = "PROVIDER_DISCONTINUITY")
		val importer = RoomImportPortableCapturedActivity(
			database,
			UnconfinedTestDispatcher(testScheduler),
		)
		importer.importEntry(importRequest(original)) shouldBe
			ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1)
		val stale = importedSelection(original)
		importer.importEntry(importRequest(corrected, "correction", 40L)) shouldBe
			ImportPortableCapturedActivityResult.Applied(2L, 1, 1, 1)
		val subject = RoomActivitySelectionDeletion(
			database,
			localDeletion { ActivitySessionDeletionResult.NotFound },
			RoomDeleteSelectedImportedActivity(
				database,
				UnconfinedTestDispatcher(testScheduler),
			),
			UnconfinedTestDispatcher(testScheduler),
		)

		subject.delete(
			ActivitySelectionDeletionRequest(
				ActivityHistorySelection.Imported(stale),
				deletedAtMs = 100L,
			),
		) shouldBe ActivitySelectionDeletionResult.Blocked(
			ActivitySelectionDeletionBlockedReason.STALE_SELECTION,
		)
		assertLive(corrected, 2L)
	}

	@Test
	fun `revision and checksum forgery reject without marker or payload mutation`() = runTest {
		val entry = entry()
		RoomImportPortableCapturedActivity(
			database,
			UnconfinedTestDispatcher(testScheduler),
		).importEntry(importRequest(entry))
		val exact = importedSelection(entry)
		val subject = RoomActivitySelectionDeletion(
			database,
			localDeletion { ActivitySessionDeletionResult.NotFound },
			RoomDeleteSelectedImportedActivity(
				database,
				UnconfinedTestDispatcher(testScheduler),
			),
			UnconfinedTestDispatcher(testScheduler),
		)

		listOf(
			exact.copy(importRevision = 2L),
			exact.copy(contentChecksum = ActivityImportedHistoryDigest("f".repeat(64))),
		).forEach { forged ->
			subject.delete(
				ActivitySelectionDeletionRequest(
					ActivityHistorySelection.Imported(forged),
					deletedAtMs = 100L,
				),
			) shouldBe ActivitySelectionDeletionResult.Blocked(
				ActivitySelectionDeletionBlockedReason.STALE_SELECTION,
			)
			assertLive(entry, 1L)
		}
	}

	@Test
	fun `correction race between dispatch and deletion remains stale and mutation free`() = runTest {
		val original = entry()
		val corrected = entry(gapReason = "PROVIDER_DISCONTINUITY")
		val importer = RoomImportPortableCapturedActivity(
			database,
			UnconfinedTestDispatcher(testScheduler),
		)
		importer.importEntry(importRequest(original))
		val selected = importedSelection(original)
		val realDeletion = RoomDeleteSelectedImportedActivity(
			database,
			UnconfinedTestDispatcher(testScheduler),
		)
		val subject = RoomActivitySelectionDeletion(
			database,
			localDeletion { ActivitySessionDeletionResult.NotFound },
			importedDeletion { request ->
				importer.importEntry(importRequest(corrected, "racing-correction", 40L)) shouldBe
					ImportPortableCapturedActivityResult.Applied(2L, 1, 1, 1)
				realDeletion.delete(request)
			},
			UnconfinedTestDispatcher(testScheduler),
		)

		subject.delete(
			ActivitySelectionDeletionRequest(
				ActivityHistorySelection.Imported(selected),
				deletedAtMs = 100L,
			),
		) shouldBe ActivitySelectionDeletionResult.Blocked(
			ActivitySelectionDeletionBlockedReason.STALE_SELECTION,
		)
		assertLive(corrected, 2L)
	}

	@Test
	fun `read snapshot revision and epoch mismatches reject as stale requests`() = runTest {
		val entry = entry()
		RoomImportPortableCapturedActivity(
			database,
			UnconfinedTestDispatcher(testScheduler),
		).importEntry(importRequest(entry))
		val revisionSelection = importedSelection(entry)
		val subject = RoomActivitySelectionDeletion(
			database,
			localDeletion { ActivitySessionDeletionResult.NotFound },
			RoomDeleteSelectedImportedActivity(
				database,
				UnconfinedTestDispatcher(testScheduler),
			),
			UnconfinedTestDispatcher(testScheduler),
		)
		database.sourceEvidenceStateDao().incrementRevision(50L) shouldBe 1

		subject.delete(
			ActivitySelectionDeletionRequest(
				ActivityHistorySelection.Imported(revisionSelection),
				deletedAtMs = 100L,
			),
		) shouldBe ActivitySelectionDeletionResult.Blocked(
			ActivitySelectionDeletionBlockedReason.STALE_REQUEST,
		)
		assertLive(entry, 1L)

		val epochSelection = importedSelection(entry)
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH + 1L, null, 60L) shouldBe 1
		subject.delete(
			ActivitySelectionDeletionRequest(
				ActivityHistorySelection.Imported(epochSelection),
				deletedAtMs = 100L,
			),
		) shouldBe ActivitySelectionDeletionResult.Blocked(
			ActivitySelectionDeletionBlockedReason.STALE_REQUEST,
		)
		assertLive(entry, 1L)
	}

	private fun localDeletion(
		block: suspend (Long) -> ActivitySessionDeletionResult,
	): ActivitySessionDeletion = object : ActivitySessionDeletion {
		override suspend fun deleteSelectedSession(
			sessionSegmentId: Long,
		): ActivitySessionDeletionResult = block(sessionSegmentId)
	}

	private fun importedDeletion(
		block: suspend (DeleteSelectedImportedActivityRequest) -> DeleteSelectedImportedActivityResult,
	): DeleteSelectedImportedActivity = object : DeleteSelectedImportedActivity {
		override suspend fun delete(
			request: DeleteSelectedImportedActivityRequest,
		): DeleteSelectedImportedActivityResult = block(request)
	}

	private fun importRequest(
		entry: PortableActivityEntryV1,
		jobId: String = "job",
		receivedAtMs: Long = 30L,
	) = ImportPortableCapturedActivityRequest(
		entry = entry,
		receipt = PortableActivityImportReceipt(
			jobId,
			"entry",
			"activity.trackeractivity",
			receivedAtMs,
		),
		expectedCollectedDataEpoch = EPOCH,
	)

	private suspend fun importedSelection(
		entry: PortableActivityEntryV1,
		importRevision: Long = 1L,
	): ActivityImportedHistorySelection {
		val state = requireNotNull(database.sourceEvidenceStateDao().get())
		return ActivityImportedHistorySelection(
			key = ActivityHistoryEntryKey("activity-imported:${entry.identity.value}"),
			identity = ActivityImportedHistoryIdentity(entry.identity.value),
			importRevision = importRevision,
			contentChecksum = ActivityImportedHistoryDigest(entry.contentChecksum.value),
			runDeletionScopes = entry.runs.map { run ->
				ActivityImportedHistoryRunDeletionScope(
					runIdentity = ActivityImportedHistoryIdentity(run.identity.value),
					deletionScopeDigest = ActivityImportedHistoryDeletionScopeDigest(
						run.deletionScopeDigest.value,
					),
				)
			},
			windowIdentities = entry.runs.flatMap { run ->
				run.windows.map { window ->
					ActivityImportedHistoryIdentity(window.identity.value)
				}
			},
			readSnapshot = ActivityImportedHistoryReadSnapshot(
				collectedDataEpoch = state.collectedDataEpoch,
				sourceEvidenceRevision = state.revision,
			),
		)
	}

	private suspend fun assertLive(
		entry: PortableActivityEntryV1,
		importRevision: Long,
	) {
		val dao = database.importedActivityDao()
		dao.latestEntryRevision(entry.identity.value)?.importRevision shouldBe importRevision
		dao.latestEntryRevision(entry.identity.value)?.contentChecksum shouldBe
			entry.contentChecksum.value
		dao.allRunsForAdmission(entry.identity.value).isNotEmpty() shouldBe true
		dao.allWindowsForAdmission(entry.identity.value).isNotEmpty() shouldBe true
		dao.entryDeletion(entry.identity.value) shouldBe null
		dao.entryDeletionReceipt(entry.identity.value) shouldBe null
	}

	private fun entry(
		gapReason: String = "NO_QUALIFIED_EVIDENCE",
	): PortableActivityEntryV1 {
		val fragment = PortableActivityFragmentV1.Gap(
			0L,
			100L,
			gapReason,
		)
		val windowIdentity = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.CAPTURE_WINDOW,
			"window",
		)
		val window = PortableActivityWindowV1(
			windowIdentity,
			ActivityCapturedPortableIntegrity.windowChecksum(
				windowIdentity,
				0L,
				100L,
				"UTC",
				PortableActivityWindowCoverage.NONE,
				0L,
				0L,
				0L,
				100L,
				listOf(fragment),
			),
			0L,
			100L,
			"UTC",
			PortableActivityWindowCoverage.NONE,
			0L,
			0L,
			0L,
			100L,
			listOf(fragment),
		)
		val runIdentity = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.PHYSICAL_RUN,
			"run",
		)
		val scope = PortableActivityDeletionScopeDigest("a".repeat(64))
		val zones = listOf(PortableActivityZoneEpochV1(1_000L, "UTC"))
		val run = PortableActivityRunV1(
			runIdentity,
			scope,
			ActivityCapturedPortableIntegrity.runChecksum(
				runIdentity,
				scope,
				1_000L,
				2_000L,
				PortableActivityCaptureCoverage.PARTIAL_RUN,
				zones,
				listOf(window),
			),
			1_000L,
			2_000L,
			PortableActivityCaptureCoverage.PARTIAL_RUN,
			zones,
			listOf(window),
		)
		val identity = PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.LOGICAL_ENTRY,
			"entry",
		)
		return PortableActivityEntryV1(
			identity,
			ActivityCapturedPortableIntegrity.entryChecksum(
				identity,
				PortableActivitySessionMode.MANUAL,
				1_000L,
				2_000L,
				listOf(run),
			),
			PortableActivitySessionMode.MANUAL,
			1_000L,
			2_000L,
			listOf(run),
		)
	}

	private companion object {
		const val EPOCH = 7L
	}
}
