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
import com.adsamcik.tracker.shared.base.database.RoomImportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.SelectedImportedActivityDeletionBlockedReason
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
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
	fun `imported origin resolves exact opaque hierarchy and never invokes local deletion`() = runTest {
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
				key = ActivityHistoryEntryKey("activity-imported:${entry.identity.value}"),
				origin = ActivityHistoryOrigin.IMPORTED,
				deletedAtMs = 100L,
			),
		) shouldBe ActivitySelectionDeletionResult.Deleted(ActivityHistoryOrigin.IMPORTED)

		localCalls shouldBe 0
		importedRequest?.expectedCollectedDataEpoch shouldBe EPOCH
		importedRequest?.selected?.entryIdentity shouldBe entry.identity
		importedRequest?.selected?.runDeletionScopes?.single()?.runIdentity shouldBe
			entry.runs.single().identity
		importedRequest?.selected?.windowIdentities?.single() shouldBe
			entry.runs.single().windows.single().identity
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
				ActivityHistoryEntryKey("activity-imported:${entry.identity.value}"),
				ActivityHistoryOrigin.IMPORTED,
				100L,
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
				ActivityHistoryEntryKey("activity-imported:${entry.identity.value}"),
				ActivityHistoryOrigin.LOCAL,
				entry.endTimeMs,
			),
		) shouldBe ActivitySelectionDeletionResult.NotFound
		localCalls shouldBe 0
		importedCalls shouldBe 0
	}

	@Test
	fun `corrupt imported hierarchy fails before deletion dispatcher`() = runTest {
		val entry = entry()
		RoomImportPortableCapturedActivity(
			database,
			UnconfinedTestDispatcher(testScheduler),
		).importEntry(importRequest(entry))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_activity_run SET content_checksum = ? WHERE entry_identity = ?",
			arrayOf("f".repeat(64), entry.identity.value),
		)
		var importedCalls = 0
		val subject = RoomActivitySelectionDeletion(
			database,
			localDeletion { ActivitySessionDeletionResult.NotFound },
			importedDeletion {
				importedCalls++
				DeleteSelectedImportedActivityResult.Deleted(1, 1)
			},
			UnconfinedTestDispatcher(testScheduler),
		)

		subject.delete(
			ActivitySelectionDeletionRequest(
				ActivityHistoryEntryKey("activity-imported:${entry.identity.value}"),
				ActivityHistoryOrigin.IMPORTED,
				100L,
			),
		) shouldBe ActivitySelectionDeletionResult.Unverifiable(
			ActivitySelectionDeletionUnverifiableReason.IMPORTED_EVIDENCE_UNVERIFIABLE,
		)
		importedCalls shouldBe 0
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

	private fun importRequest(entry: PortableActivityEntryV1) = ImportPortableCapturedActivityRequest(
		entry = entry,
		receipt = PortableActivityImportReceipt("job", "entry", "activity.trackeractivity", 30L),
		expectedCollectedDataEpoch = EPOCH,
	)

	private fun entry(): PortableActivityEntryV1 {
		val fragment = PortableActivityFragmentV1.Gap(
			0L,
			100L,
			"NO_QUALIFIED_EVIDENCE",
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
