package com.adsamcik.tracker.shared.base.database

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
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
@Suppress("LargeClass", "TooManyFunctions") // Import, read, export, and deletion share one Room fixture.
class RoomImportPortableCapturedActivityTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runTest {
		database = newDatabase()
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `admission persists exact captured hierarchy without live authority`() = runTest {
		val request = request()

		importer(testScheduler).importEntry(request) shouldBe
			ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1)

		val dao = database.importedActivityDao()
		val header = requireNotNull(dao.entryRevision(request.entry.identity.value, 1L))
		header.contentChecksum shouldBe request.entry.contentChecksum.value
		header.sourceFormat shouldBe ActivityCapturedPortableFormatV1.FORMAT
		header.collectedDataEpoch shouldBe EPOCH
		val run = dao.runs(header.identity, 1L).single()
		run.deletionScopeDigest shouldBe request.entry.runs.single().deletionScopeDigest.value
		dao.zoneEpochs(header.identity, 1L, run.identity).single().zoneId shouldBe "Europe/Prague"
		val window = dao.windows(header.identity, 1L, run.identity).single()
		window.contentChecksum shouldBe request.entry.runs.single().windows.single().contentChecksum.value
		dao.fragments(header.identity, 1L, run.identity, window.identity).single().activity shouldBe "WALKING"
		database.activityCapturedFactDao().revisionCount() shouldBe 0L
		database.sourceEventWalDao().countAll() shouldBe 0L
		database.sourceSessionDao().session(header.identity) shouldBe null
		database.sourceSessionDao().serviceRun(run.identity) shouldBe null
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
		listOf(
			"source_demand",
			"provider_registration_generation",
			"source_authorization",
			"logical_tracking_session",
			"source_service_run",
			"session_manifest_version",
			"session_manifest_source",
			"source_destination_owner",
			"source_product_projection_lane",
			"source_registration_state",
			"source_desired_plan",
			"source_applied_plan_state",
			"source_runtime_state",
			"source_deletion_fence",
			"activity_captured_registration_plan",
		).forEach { table -> rowCount(table) shouldBe 0L }
	}

	@Test
	fun `replacement membership preserves explicit not captured runs without fabricating windows`() = runTest {
		val runs = listOf(run(), notCapturedRun()).sortedWith(
			compareBy<PortableActivityRunV1>(PortableActivityRunV1::startTimeMs)
				.thenBy(PortableActivityRunV1::endTimeMs)
				.thenBy { it.identity.value },
		)
		val value = entry(entryLocalId = "replacement-entry", runs = runs)

		importer(testScheduler).importEntry(request(value)) shouldBe
			ImportPortableCapturedActivityResult.Applied(1L, 2, 1, 1)

		val stored = database.importedActivityDao().runs(value.identity.value, 1L)
		stored.map { it.captureCoverage }.toSet() shouldBe setOf("WHOLE_RUN", "NOT_CAPTURED")
		val notCaptured = stored.single { it.captureCoverage == "NOT_CAPTURED" }
		database.importedActivityDao().windows(value.identity.value, 1L, notCaptured.identity) shouldBe
			emptyList()
	}

	@Test
	fun `exact and alternate receipts are idempotent while retargeting is blocked`() = runTest {
		val original = request()
		val importer = importer(testScheduler)
		importer.importEntry(original)

		importer.importEntry(original) shouldBe ImportPortableCapturedActivityResult.Duplicate(1L)
		val alternate = original.copy(receipt = receipt("job-alt", 40L))
		importer.importEntry(alternate) shouldBe ImportPortableCapturedActivityResult.Duplicate(1L)
		database.importedActivityDao().receipt("job-alt", "entry-1")?.entryImportRevision shouldBe 1L

		listOf(
			alternate.copy(entry = entry(endUncertaintyMs = 12L)),
			alternate.copy(receipt = alternate.receipt.copy(sourceName = "other.trackeractivity")),
		).forEach { conflicting ->
			importer.importEntry(conflicting) shouldBe ImportPortableCapturedActivityResult.Blocked(
				PortableActivityImportBlockedReason.RECEIPT_CONFLICT,
			)
		}
	}

	@Test
	fun `full entry correction appends immediate local successor and preserves original`() = runTest {
		val first = request()
		val second = request(entry(endUncertaintyMs = 12L), receipt("job-2", 40L))
		val importer = importer(testScheduler)
		importer.importEntry(first)

		importer.importEntry(second) shouldBe
			ImportPortableCapturedActivityResult.Applied(2L, 1, 1, 1)

		val dao = database.importedActivityDao()
		dao.entryRevision(first.entry.identity.value, 1L)?.contentChecksum shouldBe
			first.entry.contentChecksum.value
		val successor = requireNotNull(dao.entryRevision(second.entry.identity.value, 2L))
		successor.supersedesImportRevision shouldBe 1L
		successor.contentChecksum shouldBe second.entry.contentChecksum.value
		importer.importEntry(first.copy(receipt = receipt("job-old", 50L))) shouldBe
			ImportPortableCapturedActivityResult.Duplicate(1L)
		dao.latestEntryRevision(second.entry.identity.value)?.contentChecksum shouldBe
			second.entry.contentChecksum.value
		val changedMode = request(
			entry(sessionMode = PortableActivitySessionMode.AUTOMATIC, endUncertaintyMs = 13L),
			receipt("job-mode-change", 60L),
		)
		importer.importEntry(changedMode) shouldBe ImportPortableCapturedActivityResult.Blocked(
			PortableActivityImportBlockedReason.CORRECTION_CONFLICT,
		)
		val currentRun = second.entry.runs.single()
		val changedCoverage = PortableActivityCaptureCoverage.PARTIAL_RUN
		val structurallyChangedRun = currentRun.copy(
			contentChecksum = ActivityCapturedPortableIntegrity.runChecksum(
				currentRun.identity,
				currentRun.deletionScopeDigest,
				currentRun.startTimeMs,
				currentRun.endTimeMs,
				changedCoverage,
				currentRun.zoneEpochs,
				currentRun.windows,
			),
			captureCoverage = changedCoverage,
		)
		val structurallyChangedEntry = entry(runs = listOf(structurallyChangedRun))
		importer.importEntry(
			request(structurallyChangedEntry, receipt("job-structure-change", 61L)),
		) shouldBe ImportPortableCapturedActivityResult.Blocked(
			PortableActivityImportBlockedReason.CORRECTION_CONFLICT,
		)
		dao.latestEntryRevision(second.entry.identity.value)?.importRevision shouldBe 2L
	}

	@Test
	fun `opaque kind owner and deletion scope retargeting fail closed`() = runTest {
		val original = request()
		val importer = importer(testScheduler)
		importer.importEntry(original)
		val originalRun = original.entry.runs.single()
		val movedRun = entry(
			entryLocalId = "other-entry",
			runIdentity = originalRun.identity,
			windowIdentity = identity(PortableActivityIdentityKind.CAPTURE_WINDOW, "moved-run-window"),
		)
		val crossKind = entry(
			entryIdentity = originalRun.identity,
			runIdentity = identity(PortableActivityIdentityKind.PHYSICAL_RUN, "fresh-run"),
			deletionScope = scope("cross-kind"),
			windowIdentity = identity(PortableActivityIdentityKind.CAPTURE_WINDOW, "cross-kind-window"),
		)
		val changedScope = entry(
			runIdentity = originalRun.identity,
			deletionScope = PortableActivityDeletionScopeDigest("f".repeat(64)),
		)
		val reusedScope = entry(
			entryLocalId = "scope-owner-conflict",
			runIdentity = identity(PortableActivityIdentityKind.PHYSICAL_RUN, "scope-owner-run"),
			deletionScope = originalRun.deletionScopeDigest,
			windowIdentity = identity(PortableActivityIdentityKind.CAPTURE_WINDOW, "scope-owner-window"),
		)
		val scopeAsEntryIdentity = entry(
			entryIdentity = PortableActivityOpaqueIdentity(originalRun.deletionScopeDigest.value),
			runIdentity = identity(PortableActivityIdentityKind.PHYSICAL_RUN, "scope-as-entry-run"),
			deletionScope = scope("scope-as-entry-owner"),
			windowIdentity = identity(PortableActivityIdentityKind.CAPTURE_WINDOW, "scope-as-entry-window"),
		)
		val entryIdentityAsScope = entry(
			entryLocalId = "entry-as-scope-owner",
			runIdentity = identity(PortableActivityIdentityKind.PHYSICAL_RUN, "entry-as-scope-run"),
			deletionScope = PortableActivityDeletionScopeDigest(original.entry.identity.value),
			windowIdentity = identity(PortableActivityIdentityKind.CAPTURE_WINDOW, "entry-as-scope-window"),
		)

		listOf(
			movedRun,
			crossKind,
			changedScope,
			reusedScope,
			scopeAsEntryIdentity,
			entryIdentityAsScope,
		).forEachIndexed { index, value ->
			importer.importEntry(request(value, receipt("conflict-$index", 50L + index))) shouldBe
				ImportPortableCapturedActivityResult.Blocked(
					PortableActivityImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
				)
			if (value.identity != original.entry.identity) {
				database.importedActivityDao().latestEntryRevision(value.identity.value) shouldBe null
			}
		}
		database.importedActivityDao().latestEntryRevision(original.entry.identity.value)?.contentChecksum shouldBe
			original.entry.contentChecksum.value

		val selfIdentity = identity(PortableActivityIdentityKind.LOGICAL_ENTRY, "self-scope")
		val selfCollidingScope = entry(
			entryIdentity = selfIdentity,
			runIdentity = identity(PortableActivityIdentityKind.PHYSICAL_RUN, "self-scope-run"),
			deletionScope = PortableActivityDeletionScopeDigest(selfIdentity.value),
			windowIdentity = identity(PortableActivityIdentityKind.CAPTURE_WINDOW, "self-scope-window"),
		)
		importer.importEntry(
			request(selfCollidingScope, receipt("self-scope", 60L)),
		) shouldBe ImportPortableCapturedActivityResult.Unverifiable(
			PortableActivityImportUnverifiableReason.ENTRY_INVALID,
		)
	}

	@Test
	fun `cross kind tombstone identities block admission without consuming privacy markers`() = runTest {
		val dao = database.importedActivityDao()
		val entryMarkerRun = identity(PortableActivityIdentityKind.PHYSICAL_RUN, "entry-marker-run")
		val entryMarkerWindow = identity(PortableActivityIdentityKind.CAPTURE_WINDOW, "entry-marker-window")
		val runMarkerEntry = identity(PortableActivityIdentityKind.LOGICAL_ENTRY, "run-marker-entry")
		val runMarkerWindow = identity(PortableActivityIdentityKind.CAPTURE_WINDOW, "run-marker-window")
		dao.insertEntryDeletion(
			ImportedActivityEntryDeletionEntity.create(entryMarkerRun.value, EPOCH, 1L, 61L),
		)
		dao.insertEntryDeletion(
			ImportedActivityEntryDeletionEntity.create(entryMarkerWindow.value, EPOCH, 1L, 62L),
		)
		dao.insertDeletionGeneration(
			ImportedActivityDeletionGenerationEntity.create(runMarkerEntry.value, EPOCH, 1L, 63L),
		)
		dao.insertDeletionGeneration(
			ImportedActivityDeletionGenerationEntity.create(runMarkerWindow.value, EPOCH, 1L, 64L),
		)

		val values = listOf(
			entry(
				entryLocalId = "entry-marker-run-owner",
				runIdentity = entryMarkerRun,
				deletionScope = scope("entry-marker-run-owner"),
				windowIdentity = identity(PortableActivityIdentityKind.CAPTURE_WINDOW, "entry-marker-run-window"),
			),
			entry(
				entryLocalId = "entry-marker-window-owner",
				runIdentity = identity(PortableActivityIdentityKind.PHYSICAL_RUN, "entry-marker-window-run"),
				deletionScope = scope("entry-marker-window-owner"),
				windowIdentity = entryMarkerWindow,
			),
			entry(
				entryIdentity = runMarkerEntry,
				runIdentity = identity(PortableActivityIdentityKind.PHYSICAL_RUN, "run-marker-entry-run"),
				deletionScope = scope("run-marker-entry-owner"),
				windowIdentity = identity(PortableActivityIdentityKind.CAPTURE_WINDOW, "run-marker-entry-window"),
			),
			entry(
				entryLocalId = "run-marker-window-owner",
				runIdentity = identity(PortableActivityIdentityKind.PHYSICAL_RUN, "run-marker-window-run"),
				deletionScope = scope("run-marker-window-owner"),
				windowIdentity = runMarkerWindow,
			),
		)
		values.forEachIndexed { index, value ->
			importer(testScheduler).importEntry(
				request(value, receipt("tombstone-conflict-$index", 70L + index)),
			) shouldBe ImportPortableCapturedActivityResult.Blocked(
				PortableActivityImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
			)
			dao.latestEntryRevision(value.identity.value) shouldBe null
		}
		dao.entryDeletion(entryMarkerRun.value)?.deletedAtMs shouldBe 61L
		dao.entryDeletion(entryMarkerWindow.value)?.deletedAtMs shouldBe 62L
		dao.deletionGenerations(listOf(runMarkerEntry.value)).single().deletedAtMs shouldBe 63L
		dao.deletionGenerations(listOf(runMarkerWindow.value)).single().deletedAtMs shouldBe 64L

		val staleEntryMarkerRun = identity(
			PortableActivityIdentityKind.PHYSICAL_RUN,
			"stale-entry-marker-run",
		)
		dao.insertEntryDeletion(
			ImportedActivityEntryDeletionEntity.create(staleEntryMarkerRun.value, EPOCH - 1L, 1L, 65L),
		)
		val staleMarkerValue = entry(
			entryLocalId = "stale-marker-owner",
			runIdentity = staleEntryMarkerRun,
			deletionScope = scope("stale-marker-owner"),
			windowIdentity = identity(PortableActivityIdentityKind.CAPTURE_WINDOW, "stale-marker-window"),
		)
		importer(testScheduler).importEntry(
			request(staleMarkerValue, receipt("stale-marker", 75L)),
		) shouldBe ImportPortableCapturedActivityResult.Unverifiable(
			PortableActivityImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
		dao.latestEntryRevision(staleMarkerValue.identity.value) shouldBe null
		dao.entryDeletion(staleEntryMarkerRun.value)?.collectedDataEpoch shouldBe EPOCH - 1L
	}

	@Test
	fun `epoch and authenticated tombstones precede duplicate admission`() = runTest {
		val request = request()
		val importer = importer(testScheduler)
		importer.importEntry(request)
		val dao = database.importedActivityDao()
		dao.insertDeletionGeneration(
			ImportedActivityDeletionGenerationEntity.create(
				request.entry.runs.single().identity.value,
				EPOCH,
				1L,
				50L,
			),
		)
		dao.deleteAllEntries()
		dao.latestEntryRevision(request.entry.identity.value) shouldBe null
		importer.importEntry(request) shouldBe ImportPortableCapturedActivityResult.Blocked(
			PortableActivityImportBlockedReason.DELETED_RUN,
		)

		database.close()
		database = newDatabase()
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
		importer(testScheduler).importEntry(request)
		val currentDao = daoForCurrentDatabase()
		currentDao.insertEntryDeletion(
			ImportedActivityEntryDeletionEntity.create(request.entry.identity.value, EPOCH, 1L, 60L),
		)
		currentDao.deleteAllEntries()
		currentDao.latestEntryRevision(request.entry.identity.value) shouldBe null
		importer(testScheduler).importEntry(request) shouldBe ImportPortableCapturedActivityResult.Blocked(
			PortableActivityImportBlockedReason.DELETED_ENTRY,
		)

		database.sourceEvidenceStateDao().updateLifecycle(EPOCH + 1L, null, 70L) shouldBe 1
		importer(testScheduler).importEntry(request) shouldBe ImportPortableCapturedActivityResult.Blocked(
			PortableActivityImportBlockedReason.COLLECTED_DATA_EPOCH_CHANGED,
		)
	}

	@Test
	fun `source deletion scope blocks initial replay alternate receipt and correction without mutation`() = runTest {
		val deletionScope = PortableActivityDeletionScopeDigest.derive(LOGICAL_TRACKING_ID, SERVICE_RUN_ID)
		val original = request(entry(deletionScope = deletionScope))
		val fence = sourceDeletionFence()
		val importer = importer(testScheduler)
		database.sourceDeletionFenceDao().insertIfAbsent(fence)

		importer.importEntry(original) shouldBe ImportPortableCapturedActivityResult.Blocked(
			PortableActivityImportBlockedReason.DELETED_SCOPE,
		)
		rowCount("imported_activity_entry_revision") shouldBe 0L
		rowCount("imported_activity_receipt") shouldBe 0L
		rowCount("imported_activity_run") shouldBe 0L
		database.sourceDeletionFenceDao().countAll() shouldBe 1L

		database.sourceDeletionFenceDao().deleteAll()
		importer.importEntry(original) shouldBe ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1)
		database.sourceDeletionFenceDao().insertIfAbsent(fence)
		val alternate = original.copy(receipt = receipt("scope-alternate", 50L))
		val correction = request(
			entry(deletionScope = deletionScope, endUncertaintyMs = 12L),
			receipt("scope-correction", 60L),
		)

		listOf(original, alternate, correction).forEach { blockedRequest ->
			importer.importEntry(blockedRequest) shouldBe ImportPortableCapturedActivityResult.Blocked(
				PortableActivityImportBlockedReason.DELETED_SCOPE,
			)
		}
		rowCount("imported_activity_entry_revision") shouldBe 1L
		rowCount("imported_activity_receipt") shouldBe 1L
		rowCount("imported_activity_run") shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 1L
	}

	@Test
	fun `stale exact source deletion scope is stored evidence corruption`() = runTest {
		val deletionScope = PortableActivityDeletionScopeDigest.derive(LOGICAL_TRACKING_ID, SERVICE_RUN_ID)
		val request = request(entry(deletionScope = deletionScope))
		database.sourceDeletionFenceDao().insertIfAbsent(sourceDeletionFence(epoch = EPOCH - 1L))

		importer(testScheduler).importEntry(request) shouldBe ImportPortableCapturedActivityResult.Unverifiable(
			PortableActivityImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
		rowCount("imported_activity_entry_revision") shouldBe 0L
		rowCount("imported_activity_receipt") shouldBe 0L
		rowCount("imported_activity_run") shouldBe 0L
		database.sourceDeletionFenceDao().countAll() shouldBe 1L
	}

	@Test
	fun `unrelated source purpose and logical run fences do not block admission`() = runTest {
		val deletionScope = PortableActivityDeletionScopeDigest.derive(LOGICAL_TRACKING_ID, SERVICE_RUN_ID)
		listOf(
			sourceDeletionFence(logicalTrackingId = "other-logical"),
			sourceDeletionFence(sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS),
			sourceDeletionFence(purpose = SourceBrokerPurpose.CONTROL_CONTINUATION),
		).forEach { database.sourceDeletionFenceDao().insertIfAbsent(it) }

		importer(testScheduler).importEntry(request(entry(deletionScope = deletionScope))) shouldBe
			ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1)
		rowCount("imported_activity_entry_revision") shouldBe 1L
		rowCount("imported_activity_receipt") shouldBe 1L
		rowCount("imported_activity_run") shouldBe 1L
		database.sourceDeletionFenceDao().countAll() shouldBe 3L
	}

	@Test
	fun `local retention floor rejects uncertain and unanchored imported evidence`() = runTest {
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH, 995L, 40L) shouldBe 1

		importer(testScheduler).importEntry(request()) shouldBe
			ImportPortableCapturedActivityResult.Blocked(
				PortableActivityImportBlockedReason.RETENTION_BOUNDARY,
			)
		database.importedActivityDao().latestEntryRevision(entry().identity.value) shouldBe null

		val gapRun = runFromParts(
			"gap-run",
			listOf(PortableActivityZoneEpochV1(1_000L, "UTC")),
			listOf(gapWindow("gap-window")),
		)
		val gapEntry = entry(entryLocalId = "gap-entry", runs = listOf(gapRun))
		importer(testScheduler).importEntry(
			request(gapEntry, receipt("gap-retention", 41L)),
		) shouldBe ImportPortableCapturedActivityResult.Blocked(
			PortableActivityImportBlockedReason.RETENTION_BOUNDARY,
		)
		database.importedActivityDao().latestEntryRevision(gapEntry.identity.value) shouldBe null
	}

	@Test
	fun `missing state mutated input and corrupt stored lineage are typed unverifiable`() = runTest {
		val missing = newDatabase()
		try {
			importer(testScheduler, missing).importEntry(request()) shouldBe
				ImportPortableCapturedActivityResult.Unverifiable(
					PortableActivityImportUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING,
				)
		} finally {
			missing.close()
		}

		val originalRun = run()
		val callerRuns = mutableListOf(originalRun)
		val mutable = entry(runs = callerRuns)
		callerRuns[0] = run(endUncertaintyMs = 12L)
		importer(testScheduler).importEntry(request(mutable)) shouldBe
			ImportPortableCapturedActivityResult.Unverifiable(
				PortableActivityImportUnverifiableReason.ENTRY_INVALID,
			)

		val valid = request()
		importer(testScheduler).importEntry(valid)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_activity_window SET content_checksum = ? WHERE identity = ?",
			arrayOf("e".repeat(64), valid.entry.runs.single().windows.single().identity.value),
		)
		importer(testScheduler).importEntry(valid) shouldBe
			ImportPortableCapturedActivityResult.Unverifiable(
				PortableActivityImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
	}

	@Test
	fun `missing captured hierarchy remains stored evidence corruption`() = runTest {
		val value = request()
		importer(testScheduler).importEntry(value)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM imported_activity_fragment WHERE entry_identity = ?",
			arrayOf(value.entry.identity.value),
		)

		importer(testScheduler).importEntry(value) shouldBe
			ImportPortableCapturedActivityResult.Unverifiable(
				PortableActivityImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		database.importedActivityDao().latestEntryRevision(value.entry.identity.value)?.importRevision shouldBe
			1L
	}

	@Test
	fun `missing original receipt and noncontiguous correction lineage are unverifiable`() = runTest {
		val first = request()
		val importer = importer(testScheduler)
		importer.importEntry(first)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM imported_activity_receipt WHERE import_job_id = ? AND import_entry_key = ?",
			arrayOf(first.receipt.jobId, first.receipt.entryKey),
		)
		importer.importEntry(first) shouldBe ImportPortableCapturedActivityResult.Unverifiable(
			PortableActivityImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)

		database.close()
		database = newDatabase()
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
		val second = request(entry(endUncertaintyMs = 12L), receipt("job-2", 40L))
		importer(testScheduler).importEntry(first)
		importer(testScheduler).importEntry(second)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM imported_activity_entry_revision WHERE identity = ? AND import_revision = 1",
			arrayOf(first.entry.identity.value),
		)
		val third = request(entry(endUncertaintyMs = 13L), receipt("job-3", 50L))
		importer(testScheduler).importEntry(third) shouldBe
			ImportPortableCapturedActivityResult.Unverifiable(
				PortableActivityImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
	}

	@Test
	fun `failure and cancellation roll back entry through receipt atomically`() = runTest {
		val request = request()
		val failing = importer(testScheduler) { checkpoint ->
			if (checkpoint == PortableActivityImportWriteCheckpoint.RECEIPT_INSERTED) error("injected")
		}
		failing.importEntry(request) shouldBe ImportPortableCapturedActivityResult.RetryableFailure(
			PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE,
		)
		database.importedActivityDao().latestEntryRevision(request.entry.identity.value) shouldBe null

		val cancelling = importer(testScheduler) { checkpoint ->
			if (checkpoint == PortableActivityImportWriteCheckpoint.FRAGMENT_INSERTED) {
				throw CancellationException("cancelled")
			}
		}
		shouldThrow<CancellationException> { cancelling.importEntry(request) }
		database.importedActivityDao().latestEntryRevision(request.entry.identity.value) shouldBe null
	}

	@Test
	fun `post construction collection growth has source specific bounded outcomes`() = runTest {
		val baseRun = run()
		val callerRuns = mutableListOf(baseRun)
		val runOverflow = entry(entryLocalId = "run-overflow", runs = callerRuns)
		repeat(ActivityCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) { callerRuns += baseRun }

		val callerZones = mutableListOf(PortableActivityZoneEpochV1(1_000L, "UTC"))
		val zoneRun = runFromParts("zone-run", callerZones, listOf(window(11L)))
		val zoneOverflow = entry(entryLocalId = "zone-overflow", runs = listOf(zoneRun))
		repeat(ActivityCapturedPortableFormatV1.MAX_ZONE_EPOCHS_PER_RUN) {
			callerZones += PortableActivityZoneEpochV1(1_001L + it, "UTC")
		}

		val callerWindows = mutableListOf(window(11L))
		val windowRun = runFromParts("window-run", listOf(PortableActivityZoneEpochV1(1_000L, "UTC")), callerWindows)
		val windowOverflow = entry(entryLocalId = "window-overflow", runs = listOf(windowRun))
		repeat(ActivityCapturedPortableFormatV1.MAX_WINDOWS_PER_RUN) { callerWindows += window(11L) }

		val baseFragment = window(11L).fragments.single()
		val callerFragments = mutableListOf(baseFragment)
		val fragmentWindow = windowFromFragments("fragment-window", callerFragments)
		val fragmentRun = runFromParts(
			"fragment-run",
			listOf(PortableActivityZoneEpochV1(1_000L, "UTC")),
			listOf(fragmentWindow),
		)
		val fragmentOverflow = entry(entryLocalId = "fragment-overflow", runs = listOf(fragmentRun))
		repeat(ActivityCapturedPortableFormatV1.MAX_FRAGMENTS_PER_WINDOW) {
			callerFragments += baseFragment
		}

		val cases = listOf(
			runOverflow to PortableActivityImportUnverifiableReason.RUN_OVERFLOW,
			zoneOverflow to PortableActivityImportUnverifiableReason.ZONE_EPOCH_OVERFLOW,
			windowOverflow to PortableActivityImportUnverifiableReason.WINDOW_OVERFLOW,
			fragmentOverflow to PortableActivityImportUnverifiableReason.FRAGMENT_OVERFLOW,
		)
		cases.forEachIndexed { index, (value, reason) ->
			importer(testScheduler).importEntry(request(value, receipt("overflow-$index", 80L + index))) shouldBe
				ImportPortableCapturedActivityResult.Unverifiable(reason)
		}
	}

	@Test
	fun `product reader selects the latest authenticated correction and exporter round trips it`() = runTest {
		val first = request()
		val corrected = request(entry(endUncertaintyMs = 12L), receipt("job-correction", 40L))
		val importer = importer(testScheduler)
		importer.importEntry(first) shouldBe ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1)
		importer.importEntry(corrected) shouldBe ImportPortableCapturedActivityResult.Applied(2L, 1, 1, 1)

		val evaluation = database.withTransaction {
			ImportedActivityProductReader(database).selectRecentInTransaction(10).single()
		} as ImportedActivityProductEvaluation.Readable
		evaluation.entry shouldBe corrected.entry
		evaluation.isReExportable shouldBe true

		var emitted: PortableActivityEnvelopeV1? = null
		var emittedInsideTransaction: Boolean? = null
		val exporter = RoomExportPortableCapturedActivity(
			database,
			SourceProductLaneExecutionAuthority { false },
			UnconfinedTestDispatcher(testScheduler),
		)
		exporter.export(ExportPortableCapturedActivityRequest(999L, 2_001L)) {
			emittedInsideTransaction = database.inTransaction()
			emitted = it
		} shouldBe ExportPortableCapturedActivityResult.Exported(1)
		emittedInsideTransaction shouldBe false
		emitted?.entries shouldBe listOf(corrected.entry)

		val target = newDatabase()
		try {
			target.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
			val roundTrip = ImportPortableCapturedActivityRequest(
				requireNotNull(emitted).entries.single(),
				receipt("target-round-trip", 80L),
				EPOCH,
			)
			importer(testScheduler, target).importEntry(roundTrip) shouldBe
				ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1)
			val targetEvaluation = target.withTransaction {
				ImportedActivityProductReader(target).selectRecentInTransaction(1).single()
			} as ImportedActivityProductEvaluation.Readable
			targetEvaluation.entry shouldBe corrected.entry
		} finally {
			target.close()
		}
	}

	@Test
	fun `entry and run tombstones prevent portable resurrection while retaining typed product truth`() = runTest {
		val value = request()
		importer(testScheduler).importEntry(value)
		val runIdentity = value.entry.runs.single().identity.value
		database.importedActivityDao().insertDeletionGeneration(
			ImportedActivityDeletionGenerationEntity.create(runIdentity, EPOCH, 1L, 50L),
		)

		val runDeleted = database.withTransaction {
			ImportedActivityProductReader(database).selectRecentInTransaction(1).single()
		} as ImportedActivityProductEvaluation.Readable
		runDeleted.deletedRunIdentities shouldBe setOf(runIdentity)
		runDeleted.isReExportable shouldBe false

		database.importedActivityDao().insertEntryDeletion(
			ImportedActivityEntryDeletionEntity.create(value.entry.identity.value, EPOCH, 1L, 60L),
		)
		val entryDeleted = database.withTransaction {
			ImportedActivityProductReader(database).selectRecentInTransaction(1).single()
		} as ImportedActivityProductEvaluation.Readable
		entryDeleted.entryDeleted shouldBe true
		entryDeleted.isReExportable shouldBe false

		var sinkCalls = 0
		RoomExportPortableCapturedActivity(
			database,
			SourceProductLaneExecutionAuthority { false },
			UnconfinedTestDispatcher(testScheduler),
		).export(ExportPortableCapturedActivityRequest(999L, 2_001L)) {
			sinkCalls++
		} shouldBe ExportPortableCapturedActivityResult.NoEntries
		sinkCalls shouldBe 0
	}

	@Test
	fun `exact local source deletion fence hides the matching imported run from product and re-export`() = runTest {
		val deletionScope = PortableActivityDeletionScopeDigest.derive(LOGICAL_TRACKING_ID, SERVICE_RUN_ID)
		val value = request(entry(deletionScope = deletionScope))
		importer(testScheduler).importEntry(value) shouldBe
			ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1)
		database.sourceDeletionFenceDao().insertIfAbsent(sourceDeletionFence()) shouldBe 1L

		val evaluation = database.withTransaction {
			ImportedActivityProductReader(database).selectRecentInTransaction(1).single()
		} as ImportedActivityProductEvaluation.Readable
		evaluation.deletedRunIdentities shouldBe setOf(value.entry.runs.single().identity.value)
		evaluation.isReExportable shouldBe false

		var sinkCalls = 0
		RoomExportPortableCapturedActivity(
			database,
			SourceProductLaneExecutionAuthority { false },
			UnconfinedTestDispatcher(testScheduler),
		).export(ExportPortableCapturedActivityRequest(999L, 2_001L)) {
			sinkCalls++
		} shouldBe ExportPortableCapturedActivityResult.NoEntries
		sinkCalls shouldBe 0
	}

	@Test
	fun `advanced privacy epoch and retention floor cannot expose stale imported values`() = runTest {
		importer(testScheduler).importEntry(request())
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH, 1_500L, 40L) shouldBe 1
		val retained = database.withTransaction {
			ImportedActivityProductReader(database).selectRecentInTransaction(1).single()
		} as ImportedActivityProductEvaluation.Readable
		retained.retainedFromMs shouldBe 1_500L
		retained.isReExportable shouldBe false

		database.sourceEvidenceStateDao().updateLifecycle(EPOCH + 1L, null, 50L) shouldBe 1
		val stale = database.withTransaction {
			ImportedActivityProductReader(database).selectRecentInTransaction(1).single()
		} as ImportedActivityProductEvaluation.Unverifiable
		stale.reason shouldBe ImportedActivityProductFailure.STALE_COLLECTED_DATA_EPOCH
	}

	@Test
	fun `stored checksum corruption is typed and cannot reach the portable sink`() = runTest {
		val value = request()
		importer(testScheduler).importEntry(value) shouldBe
			ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_activity_entry_revision SET content_checksum = ? WHERE identity = ?",
			arrayOf("e".repeat(64), value.entry.identity.value),
		)

		val evaluation = database.withTransaction {
			ImportedActivityProductReader(database).selectRecentInTransaction(1).single()
		} as ImportedActivityProductEvaluation.Unverifiable
		evaluation.reason shouldBe ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE
		var sinkCalls = 0
		RoomExportPortableCapturedActivity(
			database,
			SourceProductLaneExecutionAuthority { false },
			UnconfinedTestDispatcher(testScheduler),
		).export(ExportPortableCapturedActivityRequest(999L, 2_001L)) {
			sinkCalls++
		} shouldBe ExportPortableCapturedActivityResult.Unverifiable(
			PortableActivityExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
		)
		sinkCalls shouldBe 0
	}

	@Test
	fun `imported revision overflow fails closed before product reconstruction`() = runTest {
		val value = request()
		importer(testScheduler).importEntry(value) shouldBe
			ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1)
		val sqlite = database.openHelper.writableDatabase
		for (revision in 2L..17L) {
			sqlite.execSQL(
				"""
				INSERT INTO imported_activity_entry_revision (
				  identity, import_revision, supersedes_import_revision, content_checksum,
				  source_format, source_schema_version, session_mode, start_time_ms, end_time_ms,
				  collected_data_epoch, import_job_id, import_entry_key, import_source_name,
				  received_at_ms
				)
				SELECT identity, ?, ?, content_checksum, source_format, source_schema_version,
				       session_mode, start_time_ms, end_time_ms, collected_data_epoch, ?, ?,
				       import_source_name, received_at_ms + ?
				FROM imported_activity_entry_revision
				WHERE identity = ? AND import_revision = 1
				""".trimIndent(),
				arrayOf(
					revision,
					revision - 1L,
					"overflow-job-$revision",
					"overflow-entry-$revision",
					revision,
					value.entry.identity.value,
				),
			)
		}

		val evaluation = database.withTransaction {
			ImportedActivityProductReader(database).selectRecentInTransaction(1).single()
		} as ImportedActivityProductEvaluation.Unverifiable
		evaluation.reason shouldBe ImportedActivityProductFailure.DEPENDENCY_OVERFLOW
	}

	@Test
	fun `selected imported deletion fences exact correction and cannot be reimported or re-exported`() =
		runTest {
			database.sourceEvidenceStateDao().updateAfterFullDeletion(
				epoch = EPOCH,
				retainedFromMs = null,
				deletedSourceEventHighWaterOrdinal = 17L,
				updatedAtMs = 20L,
			) shouldBe 1
			val evidenceBefore = requireNotNull(database.sourceEvidenceStateDao().get())
			val original = request()
			val corrected = request(entry(endUncertaintyMs = 12L), receipt("job-correction", 40L))
			val otherEntry = entry(
				entryLocalId = "other-entry",
				runIdentity = identity(PortableActivityIdentityKind.PHYSICAL_RUN, "other-run"),
				deletionScope = scope("other-run"),
				windowIdentity = identity(PortableActivityIdentityKind.CAPTURE_WINDOW, "other-window"),
			)
			val other = request(
				otherEntry,
				PortableActivityImportReceipt("job-other", "entry-other", "backup.trackeractivity", 50L),
			)
			val importer = importer(testScheduler)
			importer.importEntry(original) shouldBe
				ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1)
			importer.importEntry(corrected) shouldBe
				ImportPortableCapturedActivityResult.Applied(2L, 1, 1, 1)
			importer.importEntry(other) shouldBe
				ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1)
			val unrelatedFence = sourceDeletionFence(
				logicalTrackingId = "unrelated-logical",
				serviceRunId = "unrelated-run",
			)
			database.sourceDeletionFenceDao().insertIfAbsent(unrelatedFence) shouldBe 1L

			deleter(testScheduler).delete(deleteRequest(corrected.entry, 2L, deletedAtMs = 100L)) shouldBe
				DeleteSelectedImportedActivityResult.Deleted(2, 1)

			val dao = database.importedActivityDao()
			dao.latestHistoryCandidate(corrected.entry.identity.value) shouldBe null
			dao.entryRevisionsForAdmission(corrected.entry.identity.value) shouldBe emptyList()
			dao.receiptsForAdmission(corrected.entry.identity.value) shouldBe emptyList()
			dao.allRunsForAdmission(corrected.entry.identity.value) shouldBe emptyList()
			dao.allZoneEpochsForAdmission(corrected.entry.identity.value) shouldBe emptyList()
			dao.allWindowsForAdmission(corrected.entry.identity.value) shouldBe emptyList()
			dao.allFragmentsForAdmission(corrected.entry.identity.value) shouldBe emptyList()
			dao.entryDeletion(corrected.entry.identity.value) shouldBe
				ImportedActivityEntryDeletionEntity.create(
					corrected.entry.identity.value,
					EPOCH,
					2L,
					100L,
				)
			dao.deletionGenerations(corrected.entry.runs.map { it.identity.value }) shouldBe listOf(
				ImportedActivityDeletionGenerationEntity.create(
					corrected.entry.runs.single().identity.value,
					EPOCH,
					1L,
					100L,
				),
			)
			dao.latestEntryRevision(otherEntry.identity.value)?.contentChecksum shouldBe
				otherEntry.contentChecksum.value
			database.sourceDeletionFenceDao().get(
				unrelatedFence.sourceKind,
				unrelatedFence.purpose,
				unrelatedFence.scopeKind,
				unrelatedFence.scopeIdentityDigest,
			) shouldBe unrelatedFence
			database.sourceEvidenceStateDao().get() shouldBe evidenceBefore

			deleter(testScheduler).delete(deleteRequest(corrected.entry, 2L, deletedAtMs = 110L)) shouldBe
				DeleteSelectedImportedActivityResult.AlreadyDeleted(2L)
			importer.importEntry(corrected.copy(receipt = receipt("job-reimport", 120L))) shouldBe
				ImportPortableCapturedActivityResult.Blocked(PortableActivityImportBlockedReason.DELETED_ENTRY)
			var envelope: PortableActivityEnvelopeV1? = null
			RoomExportPortableCapturedActivity(
				database,
				SourceProductLaneExecutionAuthority { false },
				UnconfinedTestDispatcher(testScheduler),
			).export(ExportPortableCapturedActivityRequest(999L, 2_001L)) {
				envelope = it
			} shouldBe ExportPortableCapturedActivityResult.Exported(1)
			envelope?.entries shouldBe listOf(otherEntry)
		}

	@Test
	fun `selected imported deletion preserves exact prior run and source fences`() = runTest {
		val sourceScopedRun = run(
			identity = identity(PortableActivityIdentityKind.PHYSICAL_RUN, "source-fenced-run"),
			deletionScope = PortableActivityDeletionScopeDigest.derive(
				LOGICAL_TRACKING_ID,
				SERVICE_RUN_ID,
			),
			windowIdentity = identity(PortableActivityIdentityKind.CAPTURE_WINDOW, "source-fenced-window"),
		)
		val runFenced = run(
			identity = identity(PortableActivityIdentityKind.PHYSICAL_RUN, "run-fenced-run"),
			deletionScope = scope("run-fenced-run"),
			windowIdentity = identity(PortableActivityIdentityKind.CAPTURE_WINDOW, "run-fenced-window"),
		)
		val value = entry(
			entryLocalId = "partially-deleted",
			runs = listOf(sourceScopedRun, runFenced).sortedWith(
				compareBy<PortableActivityRunV1>(PortableActivityRunV1::startTimeMs)
					.thenBy(PortableActivityRunV1::endTimeMs)
					.thenBy { it.identity.value },
			),
		)
		importer(testScheduler).importEntry(request(value)) shouldBe
			ImportPortableCapturedActivityResult.Applied(1L, 2, 2, 2)
		val retainedRunDeletion = ImportedActivityDeletionGenerationEntity.create(
			runFenced.identity.value,
			EPOCH,
			1L,
			70L,
		)
		database.importedActivityDao().insertDeletionGeneration(retainedRunDeletion)
		val retainedSourceFence = sourceDeletionFence()
		database.sourceDeletionFenceDao().insertIfAbsent(retainedSourceFence) shouldBe 1L

		deleter(testScheduler).delete(deleteRequest(value, deletedAtMs = 100L)) shouldBe
			DeleteSelectedImportedActivityResult.Deleted(1, 2)

		val deletions = database.importedActivityDao()
			.deletionGenerations(value.runs.map { it.identity.value })
			.associateBy { it.runIdentity }
		deletions[runFenced.identity.value] shouldBe retainedRunDeletion
		deletions[sourceScopedRun.identity.value] shouldBe
			ImportedActivityDeletionGenerationEntity.create(sourceScopedRun.identity.value, EPOCH, 1L, 100L)
		database.sourceDeletionFenceDao().get(
			retainedSourceFence.sourceKind,
			retainedSourceFence.purpose,
			retainedSourceFence.scopeKind,
			retainedSourceFence.scopeIdentityDigest,
		) shouldBe retainedSourceFence
	}

	@Test
	fun `stale selected correction and stale deletion time leave imported hierarchy untouched`() = runTest {
		val first = request()
		val corrected = request(entry(endUncertaintyMs = 12L), receipt("job-correction", 40L))
		val importer = importer(testScheduler)
		importer.importEntry(first)
		importer.importEntry(corrected)

		deleter(testScheduler).delete(deleteRequest(first.entry, 1L, deletedAtMs = 100L)) shouldBe
			DeleteSelectedImportedActivityResult.Blocked(
				SelectedImportedActivityDeletionBlockedReason.STALE_SELECTION,
			)
		deleter(testScheduler).delete(deleteRequest(corrected.entry, 2L, deletedAtMs = 39L)) shouldBe
			DeleteSelectedImportedActivityResult.Blocked(
				SelectedImportedActivityDeletionBlockedReason.STALE_REQUEST,
			)
		database.importedActivityDao().latestEntryRevision(corrected.entry.identity.value)
			?.importRevision shouldBe 2L
		database.importedActivityDao().entryDeletion(corrected.entry.identity.value) shouldBe null
	}

	@Test
	fun `privacy epoch and retention floor block selected imported deletion without mutation`() = runTest {
		val value = request()
		importer(testScheduler).importEntry(value)
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH, 1_500L, 60L) shouldBe 1

		deleter(testScheduler).delete(deleteRequest(value.entry, deletedAtMs = 100L)) shouldBe
			DeleteSelectedImportedActivityResult.Blocked(
				SelectedImportedActivityDeletionBlockedReason.RETENTION_BOUNDARY,
			)
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH + 1L, 1_500L, 70L) shouldBe 1
		deleter(testScheduler).delete(deleteRequest(value.entry, deletedAtMs = 100L)) shouldBe
			DeleteSelectedImportedActivityResult.Blocked(
				SelectedImportedActivityDeletionBlockedReason.COLLECTED_DATA_EPOCH_CHANGED,
			)
		database.importedActivityDao().latestEntryRevision(value.entry.identity.value) shouldBe
			database.importedActivityDao().entryRevision(value.entry.identity.value, 1L)
		database.importedActivityDao().entryDeletion(value.entry.identity.value) shouldBe null
	}

	@Test
	fun `corrupt selected imported hierarchy fails closed before tombstones`() = runTest {
		val value = request()
		importer(testScheduler).importEntry(value)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM imported_activity_fragment WHERE entry_identity = ?",
			arrayOf(value.entry.identity.value),
		)

		deleter(testScheduler).delete(deleteRequest(value.entry, deletedAtMs = 100L)) shouldBe
			DeleteSelectedImportedActivityResult.Unverifiable(
				ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		database.importedActivityDao().latestEntryRevision(value.entry.identity.value) shouldBe
			database.importedActivityDao().entryRevision(value.entry.identity.value, 1L)
		database.importedActivityDao().entryDeletion(value.entry.identity.value) shouldBe null
		database.importedActivityDao().deletionGenerations(value.entry.runs.map { it.identity.value }) shouldBe
			emptyList()
	}

	@Test
	fun `conflicting imported owner authority fails closed before selected deletion`() = runTest {
		val value = request()
		importer(testScheduler).importEntry(value)
		val runIdentity = value.entry.runs.single().identity.value
		val conflictingOwner = ImportedActivityEntryDeletionEntity.create(runIdentity, EPOCH, 1L, 60L)
		database.importedActivityDao().insertEntryDeletion(conflictingOwner)

		deleter(testScheduler).delete(deleteRequest(value.entry, deletedAtMs = 100L)) shouldBe
			DeleteSelectedImportedActivityResult.Unverifiable(
				ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT,
			)
		database.importedActivityDao().latestEntryRevision(value.entry.identity.value)?.importRevision shouldBe 1L
		database.importedActivityDao().entryDeletion(value.entry.identity.value) shouldBe null
		database.importedActivityDao().entryDeletion(runIdentity) shouldBe conflictingOwner
	}

	@Test
	fun `unsupported imported deletion generation fails closed before selected deletion`() = runTest {
		val value = request()
		importer(testScheduler).importEntry(value)
		val unsupported = ImportedActivityDeletionGenerationEntity.create(
			value.entry.runs.single().identity.value,
			EPOCH,
			2L,
			60L,
		)
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO imported_activity_deletion_generation VALUES (?, ?, ?, ?, ?)",
			arrayOf(
				unsupported.runIdentity,
				unsupported.collectedDataEpoch,
				unsupported.generation,
				unsupported.deletedAtMs,
				unsupported.effectChecksum,
			),
		)

		deleter(testScheduler).delete(deleteRequest(value.entry, deletedAtMs = 100L)) shouldBe
			DeleteSelectedImportedActivityResult.Unverifiable(
				ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		database.importedActivityDao().latestEntryRevision(value.entry.identity.value)?.importRevision shouldBe 1L
		database.importedActivityDao().entryDeletion(value.entry.identity.value) shouldBe null
	}

	@Test
	fun `cancellation after imported tombstones rolls back the whole selected deletion`() = runTest {
		val value = request()
		importer(testScheduler).importEntry(value)
		val cancelling = deleter(testScheduler) { checkpoint ->
			if (checkpoint == ImportedActivityDeletionCheckpoint.TOMBSTONES_RECORDED) {
				throw CancellationException("cancel selected deletion")
			}
		}

		shouldThrow<CancellationException> {
			cancelling.delete(deleteRequest(value.entry, deletedAtMs = 100L))
		}

		database.importedActivityDao().latestEntryRevision(value.entry.identity.value)?.importRevision shouldBe 1L
		database.importedActivityDao().entryDeletion(value.entry.identity.value) shouldBe null
		database.importedActivityDao().deletionGenerations(value.entry.runs.map { it.identity.value }) shouldBe
			emptyList()
	}

	@Test
	fun `storage failure after imported tombstones rolls back the whole selected deletion`() = runTest {
		val value = request()
		importer(testScheduler).importEntry(value)
		val failing = deleter(testScheduler) { checkpoint ->
			if (checkpoint == ImportedActivityDeletionCheckpoint.TOMBSTONES_RECORDED) {
				throw SQLiteException("selected deletion write failed")
			}
		}

		failing.delete(deleteRequest(value.entry, deletedAtMs = 100L)) shouldBe
			DeleteSelectedImportedActivityResult.RetryableFailure(
				PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		database.importedActivityDao().latestEntryRevision(value.entry.identity.value)?.importRevision shouldBe 1L
		database.importedActivityDao().entryDeletion(value.entry.identity.value) shouldBe null
		database.importedActivityDao().deletionGenerations(value.entry.runs.map { it.identity.value }) shouldBe
			emptyList()
	}

	@Test
	fun `missing and unavailable selected imported entries remain typed`() = runTest {
		val value = entry()
		deleter(testScheduler).delete(deleteRequest(value, deletedAtMs = 100L)) shouldBe
			DeleteSelectedImportedActivityResult.NotFound

		val closed = newDatabase()
		closed.close()
		deleter(testScheduler, closed).delete(deleteRequest(value, deletedAtMs = 100L)) shouldBe
			DeleteSelectedImportedActivityResult.RetryableFailure(
				PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
	}

	@Test
	fun `portable re-export maps imported storage failure without invoking the sink`() = runTest {
		val closed = newDatabase()
		closed.close()
		var sinkCalls = 0

		RoomExportPortableCapturedActivity(
			closed,
			SourceProductLaneExecutionAuthority { false },
			UnconfinedTestDispatcher(testScheduler),
		).export(ExportPortableCapturedActivityRequest(999L, 2_001L)) {
			sinkCalls++
		} shouldBe ExportPortableCapturedActivityResult.StorageUnavailable
		sinkCalls shouldBe 0
	}

	private fun importer(
		scheduler: TestCoroutineScheduler,
		database: AppDatabase = this.database,
		checkpoint: suspend (PortableActivityImportWriteCheckpoint) -> Unit = {},
	) = RoomImportPortableCapturedActivity(
		database,
		UnconfinedTestDispatcher(scheduler),
		checkpoint,
	)

	private fun deleter(
		scheduler: TestCoroutineScheduler,
		database: AppDatabase = this.database,
		checkpoint: suspend (ImportedActivityDeletionCheckpoint) -> Unit = {},
	) = RoomDeleteSelectedImportedActivity(
		database,
		UnconfinedTestDispatcher(scheduler),
		checkpoint,
	)

	private fun deleteRequest(
		entry: PortableActivityEntryV1,
		importRevision: Long = 1L,
		deletedAtMs: Long,
	) = DeleteSelectedImportedActivityRequest(
		selected = SelectedImportedActivityIdentity(
			entryIdentity = entry.identity,
			importRevision = importRevision,
			contentChecksum = entry.contentChecksum,
		),
		expectedCollectedDataEpoch = EPOCH,
		deletedAtMs = deletedAtMs,
	)

	private fun request(
		entry: PortableActivityEntryV1 = entry(),
		receipt: PortableActivityImportReceipt = receipt(),
	) = ImportPortableCapturedActivityRequest(entry, receipt, EPOCH)

	private fun receipt(jobId: String = "job-1", receivedAtMs: Long = 30L) =
		PortableActivityImportReceipt(jobId, "entry-1", "backup.trackeractivity", receivedAtMs)

	private fun entry(
		entryLocalId: String = "entry",
		entryIdentity: PortableActivityOpaqueIdentity = identity(
			PortableActivityIdentityKind.LOGICAL_ENTRY,
			entryLocalId,
		),
		runIdentity: PortableActivityOpaqueIdentity = identity(
			PortableActivityIdentityKind.PHYSICAL_RUN,
			"run",
		),
		deletionScope: PortableActivityDeletionScopeDigest = PortableActivityDeletionScopeDigest(
			"a".repeat(64),
		),
		sessionMode: PortableActivitySessionMode = PortableActivitySessionMode.MANUAL,
		endUncertaintyMs: Long = 11L,
		windowIdentity: PortableActivityOpaqueIdentity = identity(
			PortableActivityIdentityKind.CAPTURE_WINDOW,
			"window",
		),
		runs: List<PortableActivityRunV1>? = null,
	): PortableActivityEntryV1 {
		val exactRuns = runs ?: listOf(run(runIdentity, deletionScope, endUncertaintyMs, windowIdentity))
		val checksum = ActivityCapturedPortableIntegrity.entryChecksum(
			entryIdentity,
			sessionMode,
			1_000L,
			2_000L,
			exactRuns,
		)
		return PortableActivityEntryV1(
			entryIdentity,
			checksum,
			sessionMode,
			1_000L,
			2_000L,
			exactRuns,
		)
	}

	private fun run(
		identity: PortableActivityOpaqueIdentity = identity(
			PortableActivityIdentityKind.PHYSICAL_RUN,
			"run",
		),
		deletionScope: PortableActivityDeletionScopeDigest = PortableActivityDeletionScopeDigest(
			"a".repeat(64),
		),
		endUncertaintyMs: Long = 11L,
		windowIdentity: PortableActivityOpaqueIdentity = identity(
			PortableActivityIdentityKind.CAPTURE_WINDOW,
			"window",
		),
	): PortableActivityRunV1 {
		val zones = listOf(PortableActivityZoneEpochV1(1_000L, "Europe/Prague"))
		val windows = listOf(window(endUncertaintyMs, windowIdentity))
		val checksum = ActivityCapturedPortableIntegrity.runChecksum(
			identity,
			deletionScope,
			1_000L,
			2_000L,
			PortableActivityCaptureCoverage.WHOLE_RUN,
			zones,
			windows,
		)
		return PortableActivityRunV1(
			identity,
			deletionScope,
			checksum,
			1_000L,
			2_000L,
			PortableActivityCaptureCoverage.WHOLE_RUN,
			zones,
			windows,
		)
	}

	private fun notCapturedRun(): PortableActivityRunV1 {
		val identity = identity(PortableActivityIdentityKind.PHYSICAL_RUN, "not-captured-run")
		val scope = PortableActivityDeletionScopeDigest("b".repeat(64))
		val zones = listOf(PortableActivityZoneEpochV1(1_000L, "Europe/Prague"))
		val checksum = ActivityCapturedPortableIntegrity.runChecksum(
			identity, scope, 1_000L, 2_000L, PortableActivityCaptureCoverage.NOT_CAPTURED,
			zones, emptyList(),
		)
		return PortableActivityRunV1(
			identity, scope, checksum, 1_000L, 2_000L,
			PortableActivityCaptureCoverage.NOT_CAPTURED, zones, emptyList(),
		)
	}

	private fun window(
		endUncertaintyMs: Long,
		identity: PortableActivityOpaqueIdentity = identity(
			PortableActivityIdentityKind.CAPTURE_WINDOW,
			"window",
		),
	): PortableActivityWindowV1 {
		val fragments = listOf(
			PortableActivityFragmentV1.Band(
				0L, 100L, "WALKING", "TRANSITION", null, "TRANSITION_SIGNAL",
				null, null, null, 1_000L, 10L, "EXACT_PROVIDER_OBSERVATION",
				1_001L, endUncertaintyMs, "SAME_CLOCK_EXTRAPOLATION", "SAME_ANCHOR",
			),
		)
		val checksum = ActivityCapturedPortableIntegrity.windowChecksum(
			identity, 0L, 100L, "Europe/Prague", PortableActivityWindowCoverage.COMPLETE,
			100L, 0L, 0L, 0L, fragments,
		)
		return PortableActivityWindowV1(
			identity, checksum, 0L, 100L, "Europe/Prague",
			PortableActivityWindowCoverage.COMPLETE, 100L, 0L, 0L, 0L, fragments,
		)
	}

	private fun windowFromFragments(
		localIdentity: String,
		fragments: List<PortableActivityFragmentV1>,
	): PortableActivityWindowV1 {
		val identity = identity(PortableActivityIdentityKind.CAPTURE_WINDOW, localIdentity)
		val checksum = ActivityCapturedPortableIntegrity.windowChecksum(
			identity, 0L, 100L, "UTC", PortableActivityWindowCoverage.COMPLETE,
			100L, 0L, 0L, 0L, fragments,
		)
		return PortableActivityWindowV1(
			identity, checksum, 0L, 100L, "UTC", PortableActivityWindowCoverage.COMPLETE,
			100L, 0L, 0L, 0L, fragments,
		)
	}

	private fun gapWindow(localIdentity: String): PortableActivityWindowV1 {
		val identity = identity(PortableActivityIdentityKind.CAPTURE_WINDOW, localIdentity)
		val fragments = listOf(
			PortableActivityFragmentV1.Gap(0L, 100L, "NO_QUALIFIED_EVIDENCE"),
		)
		val checksum = ActivityCapturedPortableIntegrity.windowChecksum(
			identity, 0L, 100L, "UTC", PortableActivityWindowCoverage.NONE,
			0L, 0L, 0L, 100L, fragments,
		)
		return PortableActivityWindowV1(
			identity, checksum, 0L, 100L, "UTC", PortableActivityWindowCoverage.NONE,
			0L, 0L, 0L, 100L, fragments,
		)
	}

	private fun runFromParts(
		localIdentity: String,
		zones: List<PortableActivityZoneEpochV1>,
		windows: List<PortableActivityWindowV1>,
	): PortableActivityRunV1 {
		val identity = identity(PortableActivityIdentityKind.PHYSICAL_RUN, localIdentity)
		val scope = PortableActivityDeletionScopeDigest(
			ActivityCapturedPortableIntegrity.digest("test-scope", listOf(localIdentity)),
		)
		val checksum = ActivityCapturedPortableIntegrity.runChecksum(
			identity, scope, 1_000L, 2_000L, PortableActivityCaptureCoverage.WHOLE_RUN,
			zones, windows,
		)
		return PortableActivityRunV1(
			identity, scope, checksum, 1_000L, 2_000L,
			PortableActivityCaptureCoverage.WHOLE_RUN, zones, windows,
		)
	}

	private fun identity(kind: PortableActivityIdentityKind, local: String) =
		PortableActivityOpaqueIdentity.derive(kind, local)

	private fun scope(local: String) = PortableActivityDeletionScopeDigest(
		ActivityCapturedPortableIntegrity.digest("test-scope", listOf(local)),
	)

	private fun sourceDeletionFence(
		logicalTrackingId: String = LOGICAL_TRACKING_ID,
		serviceRunId: String = SERVICE_RUN_ID,
		sourceKind: Int = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
		purpose: String = SessionManifestPurposeCode.SESSION_CAPTURE,
		epoch: Long = EPOCH,
	) = SourceDeletionFenceEntity.createLogicalServiceRun(
		sourceKind = sourceKind,
		purpose = purpose,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		fenceGeneration = 1L,
		collectedDataEpoch = epoch,
		deletedAtMs = 70L,
	)

	private fun daoForCurrentDatabase() = database.importedActivityDao()

	private fun rowCount(table: String): Long = database.openHelper.readableDatabase
		.query("SELECT COUNT(*) FROM $table")
		.use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private fun newDatabase(): AppDatabase = AppDatabase.testDatabase(
		ApplicationProvider.getApplicationContext<Application>(),
	)

	private companion object {
		const val EPOCH = 7L
		const val LOGICAL_TRACKING_ID = "logical-entry"
		const val SERVICE_RUN_ID = "service-run"
	}
}
