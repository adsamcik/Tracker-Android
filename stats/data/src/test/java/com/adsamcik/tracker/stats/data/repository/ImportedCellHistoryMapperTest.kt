package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedCellRequest
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedCellResult
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedCellRequest
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellEntryV1
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellObservationV1
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellRunV1
import com.adsamcik.tracker.shared.base.database.PortableCellAcquisitionCompleteness
import com.adsamcik.tracker.shared.base.database.PortableCellCaptureCoverage
import com.adsamcik.tracker.shared.base.database.PortableCellChildCompleteness
import com.adsamcik.tracker.shared.base.database.PortableCellDeletionScopeDigest
import com.adsamcik.tracker.shared.base.database.PortableCellDigest
import com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableCellImportReceipt
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableCellRunAvailability
import com.adsamcik.tracker.shared.base.database.PortableCellSessionMode
import com.adsamcik.tracker.shared.base.database.PortableCellSubscriptionGrouping
import com.adsamcik.tracker.shared.base.database.RoomImportPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.RoomDeleteSelectedImportedCell
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryPage
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryQuery
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangePage
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeScope
import com.adsamcik.tracker.stats.api.repository.CellHistoryStructuralDay
import com.adsamcik.tracker.stats.api.repository.CellHistoryStructuralDayCompleteness
import com.adsamcik.tracker.stats.api.repository.CellHistoryTechnology
import com.adsamcik.tracker.stats.api.repository.LocalCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.LocalCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryDigest
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistorySelection
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
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
class ImportedCellHistoryMapperTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(
			ApplicationProvider.getApplicationContext<Application>(),
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `imported-only repository exposes opaque origin and exact identity-free quality truth`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
		val value = cellEntry(
			logicalLocal = "imported-only",
			runLocal = "run",
			observation = cellObservation(
				localId = "observation",
				childCompleteness = PortableCellChildCompleteness.PARTIAL,
			),
			acquisition = PortableCellAcquisitionCompleteness.PARTIAL,
		)
		RoomImportPortableCapturedCell(database, Dispatchers.Unconfined).importEntry(
			importRequest(value),
		) shouldBe ImportPortableCapturedCellResult.Applied(1L, 1, 1)
		val repository = repository()

		val page = repository.recent(1) as CellHistoryPage.Available
		val public = page.entries.single()
		val origin = public.origin as CellHistoryOrigin.Imported
		origin.selection.identity.value shouldBe value.identity.value
		origin.selection.importRevision shouldBe 1L
		origin.selection.contentChecksum.value shouldBe value.contentChecksum.value
		public.state shouldBe CellHistoryProductState.PARTIAL
		public.causes shouldBe setOf(
			CellHistoryCause.ACQUISITION_INCOMPLETE,
			CellHistoryCause.CHILDREN_PARTIAL,
			CellHistoryCause.SUBSCRIPTION_GROUPING_UNKNOWN,
		)
		public.observations.single().let { observation ->
			observation.technologyMix.shouldContainExactly(
				mapOf(CellHistoryTechnology.LTE to 1, CellHistoryTechnology.NR to 1),
			)
			observation.signalQuality.unknownCount shouldBe 1
			observation.signalQuality.noneOrUnknownCount shouldBe 1
			observation.signalQuality.knownCount shouldBe 1
			observation.weakObservationCount shouldBe 1
			observation.allKnownQualityIsWeak shouldBe true
			observation.submittedChildCount shouldBe 3
			observation.acceptedChildCount shouldBe 2
			observation.rejectedChildCount shouldBe 1
		}
		repository.imported(origin.selection) shouldBe CellHistoryQuery.Found(public)
		repository.detail(requireNotNull(public.selection)) shouldBe CellHistoryQuery.Found(public)
		val sourcePage = database.withTransaction {
			repository.recentCellHistoryInTransaction(10)
		} as CellSourceComposedPage.Available
		(sourcePage.entries.single() as CellSourceComposedEntry.Imported).let { source ->
			source.entry shouldBe public
			source.entry.origin shouldBe origin
			source.selection shouldBe origin.selection
			source.recency.memberStartTimeMs shouldBe value.runs.single().startTimeMs
			source.recency.tieIdentity shouldBe value.runs.single().identity
			source.toString().contains("segment") shouldBe false
			source.toString().contains("run") shouldBe false
		}
		listOf(
			"source_event_wal",
			"source_demand",
			"provider_registration_generation",
			"source_authorization",
			"logical_tracking_session",
			"session_manifest_version",
			"cell_captured_fact_revision",
		).forEach { table -> rowCount(table) shouldBe 0L }
	}

	@Test
	fun `public imported states distinguish retention deletion and unverifiable evidence`() {
		val value = cellEntry()
		val candidate = candidate(value)
		readable(value, candidate, retentionLimited = true).toPublicCellEntry().let { public ->
			public.state shouldBe CellHistoryProductState.UNAVAILABLE
			public.causes shouldBe setOf(CellHistoryCause.RETENTION_LIMIT)
			public.observations shouldBe emptyList()
		}
		readable(value, candidate, entryDeleted = true).toPublicCellEntry().let { public ->
			public.state shouldBe CellHistoryProductState.DELETED
			public.causes shouldBe setOf(CellHistoryCause.DELETED)
			public.observations shouldBe emptyList()
		}
		ImportedCellProductEvaluation.Unverifiable(
			candidate,
			com.adsamcik.tracker.shared.base.database.ImportedCellProductFailure
				.STORED_EVIDENCE_UNVERIFIABLE,
		).toPublicCellEntry().let { public ->
			public.state shouldBe CellHistoryProductState.UNVERIFIABLE
			public.causes shouldBe setOf(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
			public.observations shouldBe emptyList()
		}
	}

	@Test
	fun `mixed-origin composition collapses only exact full-v1 duplicates`() {
		val exact = cellEntry(logicalLocal = "same", runLocal = "same-run")
		val imported = readable(exact, candidate(exact))
		val local = imported.toPublicCellEntry().copy(
			key = CellHistoryEntryKey("cell-logical:same"),
			origin = CellHistoryOrigin.Local,
			selection = LocalCellHistorySelection(
				LocalCellHistoryIdentity(exact.identity.value),
			),
		)
		val exactPage = CellHistoryOriginComposer.compose(
			live = listOf(local),
			visibleLocalEntryIdentities = setOf(exact.identity.value),
			localCollisionIdentities = setOf(exact.identity.value),
			imported = listOf(imported),
			localPortableEntriesByIdentity = mapOf(exact.identity.value to exact),
			limit = 2,
		)
		exactPage shouldBe listOf(local)

		val overlapping = cellEntry(logicalLocal = "other", runLocal = "other-run")
		val distinctPage = CellHistoryOriginComposer.compose(
			live = listOf(local),
			visibleLocalEntryIdentities = setOf(exact.identity.value),
			localCollisionIdentities = emptySet(),
			imported = listOf(readable(overlapping, candidate(overlapping))),
			localPortableEntriesByIdentity = mapOf(exact.identity.value to exact),
			limit = 2,
		)
		distinctPage shouldHaveSize 2
		distinctPage.map(CellHistoryEntry::origin).toSet() shouldBe setOf(
			CellHistoryOrigin.Local,
			readable(overlapping, candidate(overlapping)).toPublicCellEntry().origin,
		)
	}

	@Test
	fun `wrong child owner collision is typed instead of collapsed by time or counts`() {
		val sharedObservation = cellObservation(localId = "shared")
		val localPortable = cellEntry(
			logicalLocal = "local",
			runLocal = "local-run",
			observation = sharedObservation,
		)
		val importedPortable = cellEntry(
			logicalLocal = "imported",
			runLocal = "imported-run",
			observation = sharedObservation,
		)
		val localPublic = CellHistoryEntry(
			key = CellHistoryEntryKey("local"),
			startTime = EpochMs(localPortable.startTimeMs),
			endTime = EpochMs(localPortable.endTimeMs),
			storedZoneIds = setOf("UTC"),
			state = CellHistoryProductState.PARTIAL,
			coverage = com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage.PARTIAL,
			observations = readable(localPortable, candidate(localPortable)).toPublicCellEntry().observations,
			causes = setOf(CellHistoryCause.SUBSCRIPTION_GROUPING_UNKNOWN),
		)

		val result = CellHistoryOriginComposer.compose(
			live = listOf(localPublic),
			visibleLocalEntryIdentities = setOf(localPortable.identity.value),
			localCollisionIdentities = emptySet(),
			imported = listOf(readable(importedPortable, candidate(importedPortable))),
			localPortableEntriesByIdentity = mapOf(localPortable.identity.value to localPortable),
			limit = 2,
		)

		result.single { it.origin is CellHistoryOrigin.Imported }.let { imported ->
			imported.state shouldBe CellHistoryProductState.UNVERIFIABLE
			imported.causes shouldBe setOf(CellHistoryCause.ORIGIN_IDENTITY_CONFLICT)
			imported.observations shouldBe emptyList()
		}
	}

	@Test
	fun `wall range discovers an imported Cell entry older than the requested recent page`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
		val older = cellEntry(
			logicalLocal = "older",
			runLocal = "older-run",
			observation = cellObservation(
				localId = "older-observation",
				coverageStartTimeMs = 998L,
				observedTimeMs = 1_000L,
				wallTimeUncertaintyMs = 2L,
			),
			startTimeMs = 900L,
			endTimeMs = 1_100L,
		)
		val newer = cellEntry(
			logicalLocal = "newer",
			runLocal = "newer-run",
			observation = cellObservation(
				localId = "newer-observation",
				coverageStartTimeMs = 1_998L,
				observedTimeMs = 2_000L,
				wallTimeUncertaintyMs = 2L,
			),
			startTimeMs = 1_900L,
			endTimeMs = 2_100L,
		)
		val importer = RoomImportPortableCapturedCell(database, Dispatchers.Unconfined)
		importer.importEntry(importRequest(older, "older")) shouldBe
			ImportPortableCapturedCellResult.Applied(1L, 1, 1)
		importer.importEntry(importRequest(newer, "newer")) shouldBe
			ImportPortableCapturedCellResult.Applied(1L, 1, 1)
		val repository = repository()

		(repository.recent(1) as CellHistoryPage.Available).entries.single().startTime.raw shouldBe
			newer.startTimeMs
		val range = repository.range(
			CellHistoryRangeRequest(
				CellHistoryRangeScope.WallTime(EpochMs(800L), EpochMs(1_200L)),
				limit = 1,
			),
		) as CellHistoryRangePage.Available
		range.entries.single().entry.startTime.raw shouldBe older.startTimeMs
		range.entries.single().entry.origin shouldBe CellHistoryOrigin.Imported(
			older.toSelection(),
		)
		range.continuation shouldBe null
	}

	@Test
	fun `structural range preserves uncertainty crossing and stored DST zone authority`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
		val boundary = 86_400_000L
		val crossing = cellEntry(
			logicalLocal = "crossing",
			runLocal = "crossing-run",
			observation = cellObservation(
				localId = "crossing-observation",
				coverageStartTimeMs = boundary - 2L,
				observedTimeMs = boundary,
				wallTimeUncertaintyMs = 2L,
				storedZoneId = "UTC",
			),
			startTimeMs = boundary - 100L,
			endTimeMs = boundary + 100L,
		)
		val dstObserved = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli()
		val dstZone = "Europe/Prague"
		val dstDay = Instant.ofEpochMilli(dstObserved).atZone(ZoneId.of(dstZone))
			.toLocalDate().toEpochDay()
		val dst = cellEntry(
			logicalLocal = "dst",
			runLocal = "dst-run",
			observation = cellObservation(
				localId = "dst-observation",
				coverageStartTimeMs = dstObserved,
				observedTimeMs = dstObserved,
				wallTimeUncertaintyMs = 0L,
				storedZoneId = dstZone,
			),
			startTimeMs = dstObserved - 100L,
			endTimeMs = dstObserved + 100L,
		)
		val importer = RoomImportPortableCapturedCell(database, Dispatchers.Unconfined)
		importer.importEntry(importRequest(crossing, "crossing")) shouldBe
			ImportPortableCapturedCellResult.Applied(1L, 1, 1)
		importer.importEntry(importRequest(dst, "dst")) shouldBe
			ImportPortableCapturedCellResult.Applied(1L, 1, 1)
		val repository = repository()

		val crossingRange = repository.range(
			CellHistoryRangeRequest(
				CellHistoryRangeScope.StructuralDays(0L, 1L),
				limit = 10,
			),
		) as CellHistoryRangePage.Available
		crossingRange.entries.single().let { entry ->
			entry.structuralDays shouldBe setOf(
				CellHistoryStructuralDay(0L, "UTC"),
				CellHistoryStructuralDay(1L, "UTC"),
			)
			entry.structuralDayCompleteness shouldBe
				CellHistoryStructuralDayCompleteness.AMBIGUOUS
		}
		val dstRange = repository.range(
			CellHistoryRangeRequest(
				CellHistoryRangeScope.StructuralDays(dstDay, dstDay),
				limit = 10,
			),
		) as CellHistoryRangePage.Available
		dstRange.entries.single().let { entry ->
			entry.structuralDays shouldBe setOf(CellHistoryStructuralDay(dstDay, dstZone))
			entry.structuralDayCompleteness shouldBe CellHistoryStructuralDayCompleteness.EXACT
		}
	}

	@Test
	fun `structural range verifies stored zone membership before accepting broad SQL candidates`() =
		runTest {
			database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
			val targetDay = 20_000L
			val dayStart = targetDay * 86_400_000L
			val correct = cellEntry(
				logicalLocal = "target-day",
				runLocal = "target-run",
				observation = cellObservation(
					localId = "target-observation",
					coverageStartTimeMs = dayStart + 3_600_000L,
					observedTimeMs = dayStart + 3_600_000L,
					wallTimeUncertaintyMs = 0L,
					storedZoneId = "UTC",
				),
				startTimeMs = dayStart,
				endTimeMs = dayStart + 7_200_000L,
			)
			val previousDay = cellEntry(
				logicalLocal = "previous-zone-day",
				runLocal = "previous-zone-run",
				observation = cellObservation(
					localId = "previous-zone-observation",
					coverageStartTimeMs = dayStart + 5L * 3_600_000L,
					observedTimeMs = dayStart + 5L * 3_600_000L,
					wallTimeUncertaintyMs = 0L,
					storedZoneId = "-12:00",
				),
				startTimeMs = dayStart,
				endTimeMs = dayStart + 6L * 3_600_000L,
			)
			val nextDay = cellEntry(
				logicalLocal = "next-zone-day",
				runLocal = "next-zone-run",
				observation = cellObservation(
					localId = "next-zone-observation",
					coverageStartTimeMs = dayStart + 12L * 3_600_000L,
					observedTimeMs = dayStart + 12L * 3_600_000L,
					wallTimeUncertaintyMs = 0L,
					storedZoneId = "+14:00",
				),
				startTimeMs = dayStart + 11L * 3_600_000L,
				endTimeMs = dayStart + 13L * 3_600_000L,
			)
			val importer = RoomImportPortableCapturedCell(database, Dispatchers.Unconfined)
			listOf(correct, previousDay, nextDay).forEachIndexed { index, entry ->
				importer.importEntry(importRequest(entry, "zone-$index")) shouldBe
					ImportPortableCapturedCellResult.Applied(1L, 1, 1)
			}
			val request = CellHistoryRangeRequest(
				CellHistoryRangeScope.StructuralDays(targetDay, targetDay),
				limit = 10,
			)

			(repository().range(request) as CellHistoryRangePage.Available).entries
				.map { it.entry.origin } shouldBe listOf(CellHistoryOrigin.Imported(correct.toSelection()))

			val run = correct.runs.single()
			database.importedCellDao().insertDeletionGeneration(
				ImportedCellDeletionGenerationEntity.create(
					run.identity.value,
					correct.identity.value,
					run.deletionScopeDigest.value,
					EPOCH,
					1L,
					600L,
				),
			)
			(repository().range(request) as CellHistoryRangePage.Available).entries shouldBe emptyList()

			database.openHelper.writableDatabase.execSQL(
				"UPDATE imported_cell_observation SET known_quality_observation_count = 0 " +
					"WHERE entry_identity = ?",
				arrayOf(previousDay.identity.value),
			)
			repository().range(request) shouldBe CellHistoryRangePage.Failed(
				CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
		}

	@Test
	fun `structural range omits authenticated imported entries outside the retained floor`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
		val targetDay = 20_000L
		val observed = targetDay * 86_400_000L + 1_000L
		val value = cellEntry(
			logicalLocal = "retained-out",
			runLocal = "retained-out-run",
			observation = cellObservation(
				localId = "retained-out-observation",
				coverageStartTimeMs = observed,
				observedTimeMs = observed,
				wallTimeUncertaintyMs = 0L,
			),
			startTimeMs = observed,
			endTimeMs = observed + 1_000L,
		)
		RoomImportPortableCapturedCell(database, Dispatchers.Unconfined).importEntry(
			importRequest(value, "retained-out"),
		) shouldBe ImportPortableCapturedCellResult.Applied(1L, 1, 1)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_evidence_state SET retained_from_ms = ? WHERE id = 1",
			arrayOf(observed + 1L),
		)

		(repository().range(
			CellHistoryRangeRequest(
				CellHistoryRangeScope.StructuralDays(targetDay, targetDay),
				limit = 10,
			),
		) as CellHistoryRangePage.Available).entries shouldBe emptyList()
	}

	@Test
	fun `exact imported detail remains truthfully deleted after selected payload cascade`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
		val value = cellEntry(logicalLocal = "deleted", runLocal = "deleted-run")
		RoomImportPortableCapturedCell(database, Dispatchers.Unconfined).importEntry(
			importRequest(value, "deleted"),
		) shouldBe ImportPortableCapturedCellResult.Applied(1L, 1, 1)
		val selection = value.toSelection()

		RoomDeleteSelectedImportedCell(database, Dispatchers.Unconfined).delete(
			DeleteSelectedImportedCellRequest(
				value.identity,
				1L,
				value.contentChecksum,
				EPOCH,
				600L,
			),
		) shouldBe DeleteSelectedImportedCellResult.Deleted(1, 1, 1)

		(repository().detail(selection) as CellHistoryQuery.Found).entry.let { deleted ->
			deleted.state shouldBe CellHistoryProductState.DELETED
			deleted.causes shouldBe setOf(CellHistoryCause.DELETED)
			deleted.origin shouldBe CellHistoryOrigin.Imported(selection)
			deleted.selection shouldBe selection
		}
	}

	@Test
	fun `same-origin conflict carries source recency while rehashed corrupt source page fails`() =
		runTest {
			database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
			val logicalId = "same-origin-corrupt"
			val value = cellEntry(logicalLocal = logicalId, runLocal = "imported-run")
			RoomImportPortableCapturedCell(database, Dispatchers.Unconfined).importEntry(
				importRequest(value, "same-origin"),
			) shouldBe ImportPortableCapturedCellResult.Applied(1L, 1, 1)
			database.sourceSessionDao().insertSession(
				LogicalTrackingSessionEntity(
					logicalTrackingId = logicalId,
					state = "FINALIZED",
					lifecycleRevision = 1L,
					desiredPlanRevision = 1L,
					rolloutRevision = 1L,
					startOrigin = "MANUAL",
					clockDomainId = "boot",
					startedAtMs = 1L,
					startedElapsedNanos = 1L,
					cutoffAtMs = 2L,
					cutoffElapsedNanos = 2L,
					completedAtMs = 2L,
					finalAdmissionOrdinal = 0L,
					failureCode = null,
				),
			)
			val repository = repository()

			val readableConflict =
				(repository.recent(1) as CellHistoryPage.Available).entries.single()
			readableConflict.causes shouldBe setOf(CellHistoryCause.ORIGIN_IDENTITY_CONFLICT)
			val readableSourcePage = database.withTransaction {
				repository.recentCellHistoryInTransaction(10)
			} as CellSourceComposedPage.Available
			(readableSourcePage.entries.single() as CellSourceComposedEntry.Imported).let { source ->
				source.entry shouldBe readableConflict
				source.selection shouldBe value.toSelection()
				source.recency.memberStartTimeMs shouldBe value.runs.single().startTimeMs
				source.recency.tieIdentity shouldBe value.runs.single().identity
			}

			val corruptChecksum = rehashStoredKnownQualityCount(value, 0)
			val selection = ImportedCellHistorySelection(
				ImportedCellHistoryIdentity(value.identity.value),
				1L,
				ImportedCellHistoryDigest(corruptChecksum),
			)
			val publicConflict =
				(repository.recent(1) as CellHistoryPage.Available).entries.single()
			publicConflict.let { conflict ->
				conflict.state shouldBe CellHistoryProductState.UNVERIFIABLE
				conflict.causes shouldBe setOf(CellHistoryCause.ORIGIN_IDENTITY_CONFLICT)
				conflict.observations shouldBe emptyList()
				conflict.origin shouldBe CellHistoryOrigin.Imported(selection)
			}
			database.withTransaction {
				repository.recentCellHistoryInTransaction(10)
			} shouldBe CellSourceComposedPage.Failed(CellHistoryCause.ORIGIN_IDENTITY_CONFLICT)
			(repository.detail(selection) as CellHistoryQuery.Found).entry.let { conflict ->
				conflict.state shouldBe CellHistoryProductState.UNVERIFIABLE
				conflict.causes shouldBe setOf(CellHistoryCause.ORIGIN_IDENTITY_CONFLICT)
				conflict.observations shouldBe emptyList()
			}
		}

	private fun repository() = DefaultCellHistoryRepository(
		database,
		SourceProductLaneExecutionAuthority { false },
		Dispatchers.Unconfined,
	)

	private fun importRequest(
		entry: PortableCapturedCellEntryV1,
		suffix: String = "default",
	) =
		ImportPortableCapturedCellRequest(
			entry = entry,
			receipt = PortableCellImportReceipt(
				jobId = "job-$suffix",
				entryKey = "entry-$suffix",
				sourceName = "backup.trackercell",
				receivedAtMs = 500L,
			),
			expectedCollectedDataEpoch = EPOCH,
		)

	private fun rowCount(table: String): Long = database.openHelper.readableDatabase
		.query("SELECT COUNT(*) FROM $table").use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private fun rehashStoredKnownQualityCount(
		entry: PortableCapturedCellEntryV1,
		knownQualityObservationCount: Int,
	): String {
		val run = entry.runs.single()
		val observation = run.observations.single()
		val observationChecksum = portableCellDigest("tracker-portable-cell-observation-v1") {
			writeCellString(observation.identity.value)
			writeLong(observation.semanticRevision)
			writeNullableLong(observation.supersedesSemanticRevision)
			writeCellString(observation.aggregateOwnerIdentity?.value)
			writeNullableLong(observation.aggregateOwnerSemanticRevision)
			writeLong(observation.coverageStartTimeMs)
			writeLong(observation.observedTimeMs)
			writeLong(observation.latestPossibleTimeMs)
			writeLong(observation.wallTimeUncertaintyMs)
			writeCellString(observation.storedZoneId)
			writeCellString(observation.childCompleteness.name)
			writeCellString(observation.subscriptionGrouping.name)
			listOf(
				observation.submittedChildCount,
				observation.acceptedChildCount,
				observation.staleChildCount,
				observation.futureTimeChildCount,
				observation.missingTimeChildCount,
				observation.clockUnverifiableChildCount,
				observation.authorityMismatchChildCount,
				observation.unsupportedTechnologyChildCount,
				observation.observationCount,
				observation.registeredObservationCount,
				observation.gsmCount,
				observation.cdmaCount,
				observation.wcdmaCount,
				observation.tdscdmaCount,
				observation.lteCount,
				observation.nrCount,
				observation.qualityUnknownCount,
				observation.qualityNoneOrUnknownCount,
				observation.qualityPoorCount,
				observation.qualityModerateCount,
				observation.qualityGoodCount,
				observation.qualityGreatCount,
				observation.weakObservationCount,
				knownQualityObservationCount,
			).forEach(::writeInt)
			writeBoolean(observation.allKnownQualityIsWeak)
			writeLong(observation.qualityFlags)
			writeNullableDouble(observation.qualityConfidence)
		}
		val runChecksum = portableCellDigest("tracker-portable-cell-run-v1") {
			writeCellString(run.identity.value)
			writeCellString(run.deletionScopeDigest.value)
			writeLong(run.startTimeMs)
			writeLong(run.endTimeMs)
			writeCellString(run.captureCoverage.name)
			writeCellString(run.availability.name)
			writeCellString(run.acquisitionCompleteness.name)
			writeBoolean(run.retentionLoss)
			writeCellString(run.subscriptionGrouping.name)
			writeInt(1)
			writeCellString(observation.identity.value)
			writeCellString(observationChecksum)
		}
		val entryChecksum = portableCellDigest("tracker-portable-cell-entry-v1") {
			writeCellString(entry.format)
			writeInt(entry.schemaVersion)
			writeCellString(entry.identity.value)
			writeCellString(entry.sessionMode.name)
			writeLong(entry.startTimeMs)
			writeLong(entry.endTimeMs)
			writeCellString(entry.subscriptionGrouping.name)
			writeInt(1)
			writeCellString(run.identity.value)
			writeCellString(runChecksum)
		}
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_cell_observation SET known_quality_observation_count = ?, " +
				"content_checksum = ? WHERE entry_identity = ?",
			arrayOf(knownQualityObservationCount, observationChecksum, entry.identity.value),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_cell_run SET content_checksum = ? WHERE entry_identity = ?",
			arrayOf(runChecksum, entry.identity.value),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_cell_entry_revision SET content_checksum = ? WHERE identity = ?",
			arrayOf(entryChecksum, entry.identity.value),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_cell_receipt SET entry_content_checksum = ? WHERE entry_identity = ?",
			arrayOf(entryChecksum, entry.identity.value),
		)
		return entryChecksum
	}

	private companion object {
		const val EPOCH = 4L
	}
}

private fun candidate(entry: PortableCapturedCellEntryV1) = ImportedCellHistoryCandidate(
	identity = entry.identity.value,
	importRevision = 1L,
	contentChecksum = entry.contentChecksum.value,
	startTimeMs = entry.startTimeMs,
	endTimeMs = entry.endTimeMs,
	receivedAtMs = 500L,
)

private fun PortableCapturedCellEntryV1.toSelection() =
	com.adsamcik.tracker.stats.api.repository.ImportedCellHistorySelection(
		com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryIdentity(identity.value),
		1L,
		com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryDigest(contentChecksum.value),
	)

private fun readable(
	entry: PortableCapturedCellEntryV1,
	candidate: ImportedCellHistoryCandidate,
	entryDeleted: Boolean = false,
	deletedRunIdentities: Set<String> = emptySet(),
	retentionLimited: Boolean = false,
) = ImportedCellProductEvaluation.Readable(
	candidate = candidate,
	entry = entry,
	entryDeleted = entryDeleted,
	deletedRunIdentities = deletedRunIdentities,
	retainedFromMs = null,
	retentionLimited = retentionLimited,
	localOriginHandle = null,
)

private fun cellEntry(
	logicalLocal: String = "logical",
	runLocal: String = "run",
	observation: PortableCapturedCellObservationV1 = cellObservation(),
	acquisition: PortableCellAcquisitionCompleteness = PortableCellAcquisitionCompleteness.COMPLETE,
	startTimeMs: Long = 10L,
	endTimeMs: Long = 20L,
): PortableCapturedCellEntryV1 {
	val runIdentity = PortableCellOpaqueIdentity.derive(
		PortableCellIdentityKind.PHYSICAL_RUN,
		runLocal,
	)
	val runChecksum = portableCellDigest("tracker-portable-cell-run-v1") {
		writeCellString(runIdentity.value)
		writeCellString(PortableCellDeletionScopeDigest.derive(logicalLocal, runLocal).value)
		writeLong(startTimeMs)
		writeLong(endTimeMs)
		writeCellString(PortableCellCaptureCoverage.WHOLE_RUN.name)
		writeCellString(PortableCellRunAvailability.RETAINED.name)
		writeCellString(acquisition.name)
		writeBoolean(false)
		writeCellString(PortableCellSubscriptionGrouping.UNKNOWN.name)
		writeInt(1)
		writeCellString(observation.identity.value)
		writeCellString(observation.contentChecksum.value)
	}
	val run = PortableCapturedCellRunV1(
		identity = runIdentity,
		deletionScopeDigest = PortableCellDeletionScopeDigest.derive(logicalLocal, runLocal),
		contentChecksum = PortableCellDigest(runChecksum),
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		captureCoverage = PortableCellCaptureCoverage.WHOLE_RUN,
		availability = PortableCellRunAvailability.RETAINED,
		acquisitionCompleteness = acquisition,
		retentionLoss = false,
		subscriptionGrouping = PortableCellSubscriptionGrouping.UNKNOWN,
		observations = listOf(observation),
	)
	val entryIdentity = PortableCellOpaqueIdentity.derive(
		PortableCellIdentityKind.LOGICAL_ENTRY,
		logicalLocal,
	)
	val entryChecksum = portableCellDigest("tracker-portable-cell-entry-v1") {
		writeCellString("tracker-portable-captured-cell")
		writeInt(1)
		writeCellString(entryIdentity.value)
		writeCellString(PortableCellSessionMode.MANUAL.name)
		writeLong(startTimeMs)
		writeLong(endTimeMs)
		writeCellString(PortableCellSubscriptionGrouping.UNKNOWN.name)
		writeInt(1)
		writeCellString(run.identity.value)
		writeCellString(run.contentChecksum.value)
	}
	return PortableCapturedCellEntryV1(
		identity = entryIdentity,
		contentChecksum = PortableCellDigest(entryChecksum),
		sessionMode = PortableCellSessionMode.MANUAL,
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		subscriptionGrouping = PortableCellSubscriptionGrouping.UNKNOWN,
		runs = listOf(run),
	)
}

private fun cellObservation(
	localId: String = "observation",
	childCompleteness: PortableCellChildCompleteness = PortableCellChildCompleteness.COMPLETE,
	coverageStartTimeMs: Long = 100L,
	observedTimeMs: Long = 110L,
	wallTimeUncertaintyMs: Long = 2L,
	storedZoneId: String = "UTC",
): PortableCapturedCellObservationV1 {
	val identity = PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.OBSERVATION, localId)
	val latestPossibleTimeMs = Math.addExact(observedTimeMs, wallTimeUncertaintyMs)
	val submittedChildCount =
		if (childCompleteness == PortableCellChildCompleteness.COMPLETE) 2 else 3
	val staleChildCount = submittedChildCount - 2
	val checksum = portableCellDigest("tracker-portable-cell-observation-v1") {
		writeCellString(identity.value)
		writeLong(1L)
		writeNullableLong(null)
		writeCellString(null)
		writeNullableLong(null)
		writeLong(coverageStartTimeMs)
		writeLong(observedTimeMs)
		writeLong(latestPossibleTimeMs)
		writeLong(wallTimeUncertaintyMs)
		writeCellString(storedZoneId)
		writeCellString(childCompleteness.name)
		writeCellString(PortableCellSubscriptionGrouping.UNKNOWN.name)
		listOf(
			submittedChildCount, 2, staleChildCount, 0, 0, 0, 0, 0,
			2, 2, 0, 0, 0, 0, 1, 1,
			1, 1, 0, 0, 0, 0, 1, 1,
		).forEach(::writeInt)
		writeBoolean(true)
		writeLong(0L)
		writeNullableDouble(0.5)
	}
	return PortableCapturedCellObservationV1(
		identity = identity,
		semanticRevision = 1L,
		supersedesSemanticRevision = null,
		aggregateOwnerIdentity = null,
		aggregateOwnerSemanticRevision = null,
		contentChecksum = PortableCellDigest(checksum),
		coverageStartTimeMs = coverageStartTimeMs,
		observedTimeMs = observedTimeMs,
		latestPossibleTimeMs = latestPossibleTimeMs,
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		storedZoneId = storedZoneId,
		childCompleteness = childCompleteness,
		subscriptionGrouping = PortableCellSubscriptionGrouping.UNKNOWN,
		submittedChildCount = submittedChildCount,
		acceptedChildCount = 2,
		staleChildCount = staleChildCount,
		futureTimeChildCount = 0,
		missingTimeChildCount = 0,
		clockUnverifiableChildCount = 0,
		authorityMismatchChildCount = 0,
		unsupportedTechnologyChildCount = 0,
		observationCount = 2,
		registeredObservationCount = 2,
		gsmCount = 0,
		cdmaCount = 0,
		wcdmaCount = 0,
		tdscdmaCount = 0,
		lteCount = 1,
		nrCount = 1,
		qualityUnknownCount = 1,
		qualityNoneOrUnknownCount = 1,
		qualityPoorCount = 0,
		qualityModerateCount = 0,
		qualityGoodCount = 0,
		qualityGreatCount = 0,
		weakObservationCount = 1,
		knownQualityObservationCount = 1,
		allKnownQualityIsWeak = true,
		qualityFlags = 0L,
		qualityConfidence = 0.5,
	)
}

private fun portableCellDigest(
	namespace: String,
	body: DataOutputStream.() -> Unit,
): String {
	val bytes = ByteArrayOutputStream().use { buffer ->
		DataOutputStream(buffer).use { output ->
			output.writeUTF(namespace)
			output.body()
		}
		buffer.toByteArray()
	}
	return MessageDigest.getInstance("SHA-256").digest(bytes)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun DataOutputStream.writeCellString(value: String?) {
	writeBoolean(value != null)
	if (value != null) writeUTF(value)
}

private fun DataOutputStream.writeNullableLong(value: Long?) {
	writeBoolean(value != null)
	if (value != null) writeLong(value)
}

private fun DataOutputStream.writeNullableDouble(value: Double?) {
	writeBoolean(value != null)
	if (value != null) writeDouble(value)
}
