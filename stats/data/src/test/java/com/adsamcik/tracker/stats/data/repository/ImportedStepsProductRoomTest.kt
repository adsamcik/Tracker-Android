package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.insertAuthenticatedGraph
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableCountDomainIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainBindingEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainGraphEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFileReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsAdmissionRows
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.steps.portable.withExplicitUnprovenCountDomain
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.ExportSelectedPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.PortableStepsCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsCompletenessV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV2
import com.adsamcik.tracker.stats.api.repository.PortableStepsArchiveV2
import com.adsamcik.tracker.stats.api.repository.PortableStepsExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsManifestV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableStepsProviderCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsRunV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsSessionMode
import com.adsamcik.tracker.stats.api.repository.PortableStepsTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageQuery
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.StepsAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

/** Host Room product tests; fixture row mapping deliberately does not activate an importer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedStepsProductRoomTest {
	private lateinit var database: AppDatabase
	private lateinit var history: DefaultTrackingHistoryRepository
	private lateinit var exporter: RoomExportPortableSteps

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		// No local provider lane, policy, consent, service run, or logical session is installed.
		val lane = SourceProductLaneExecutionAuthority { false }
		val selector = StepsSegmentHistorySelector(database, lane)
		history = DefaultTrackingHistoryRepository(
			database, selector, LogicalTrackingHistoryReader(database, selector), Dispatchers.IO,
		)
		exporter = RoomExportPortableSteps(PortableStepsRoomReader(database, selector, lane), Dispatchers.IO)
	}

	@After
	fun tearDown() = database.close()

    @Test
    fun `exact selected import is reauthenticated and missing or corrupt evidence is typed`() = runTest {
        val original = entry(listOf(run('2', 10L, 20L, 9L)))
        val reader = ImportedStepsProductReader(database)
        database.withTransaction { reader.selectedInTransaction(original.identity.value) } shouldBe
            ImportedStepsRecentRead.Unavailable(
                com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsReadFailure.MISSING)
        seed(original)
        val found = database.withTransaction { reader.selectedInTransaction(original.identity.value) }
            as ImportedStepsRecentRead.Ready
        found.entries.single().physicalMembers.single().steps.count shouldBe 9L
        mutate("UPDATE imported_steps_run SET retained_checksum = '${"0".repeat(64)}' WHERE session_segment_id = 41")
        database.withTransaction { reader.selectedInTransaction(original.identity.value) } shouldBe
            ImportedStepsRecentRead.Unavailable(
                com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsReadFailure.INTEGRITY)
    }

    @Test
    fun `exact selected import keeps independently surviving deleted-entry siblings`() = runTest {
        val original = entry(listOf(run('2', 10L, 20L, 9L), run('3', 30L, 40L, 4L)))
        seed(original)
        mutate("DELETE FROM step_fact_revision WHERE service_run_id = '${original.runs.first().identity.value}'")
        mutate("DELETE FROM imported_steps_run WHERE session_segment_id = 41")
        mutate("DELETE FROM session_segment WHERE id = 41")
        val selected = database.withTransaction {
            ImportedStepsProductReader(database).selectedInTransaction(original.identity.value)
        } as ImportedStepsRecentRead.Ready
        selected.entries.single().physicalMembers.map { it.segmentId } shouldBe listOf(42L)
        selected.entries.single().physicalMembers.single().steps.count shouldBe 4L
    }

	@Test
	fun `source-aware assembly preserves exact imported Steps origin instead of a physical row`() = runTest {
		seed(entry(listOf(run('2', 10L, 20L, 9L), run('3', 30L, 40L, 4L))))
		val result = history.observeRecentSourceAwarePage(listOf(41L, 42L), 10).first()
		val page = (result as SourceAwareHistoryPageQuery.Content).entries
		val imported = (page.single() as SourceAwareHistoryPageEntry.ImportedSteps).history
		imported.physicalMembers.map { it.segmentId } shouldBe listOf(41L, 42L)
		imported.physicalMembers.map { it.steps.count } shouldBe listOf(9L, 4L)
		database.trackingHistoryReadDao().serviceRuns(listOf("sha256:${"2".repeat(64)}")) shouldBe emptyList()
		val snapshot = history.observeLiveSession(41L).first()
		(snapshot.session as SessionHistoryQuery.Found).history.steps.count shouldBe 9L
		(snapshot.activity as com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery.Found)
			.entry.capturesOnlyActivity shouldBe false
		(snapshot.pressure as com.adsamcik.tracker.stats.api.repository.PressureSessionHistoryQuery.Found)
			.history.capture shouldBe (snapshot.session as SessionHistoryQuery.Found).history.capture
	}

	@Test
	fun `original wire round trips unchanged and does not manufacture full capture or local authority`() = runTest {
		val original = entry(listOf(run('2', 10L, 20L, 9L)))
		seed(original)
		val selected = (history.observeSession(41L).first() as SessionHistoryQuery.Found).history
		(selected.capture is HistoryCapture.ImportedSteps) shouldBe true
		selected.capturesOnlySteps shouldBe false
		selected.qualifiedSources shouldBe setOf(HistorySource.STEPS)
		selected.steps.count shouldBe 9L
		selected.steps.hasCompleteValue shouldBe true
		selected.steps.availability shouldBe HistoryAvailability.RETAINED_IMPORTED
		database.trackingHistoryReadDao().serviceRuns(listOf(original.runs.single().identity.value)) shouldBe emptyList()

		val page = history.observeRecentStepsAwarePage(listOf(41L), 10).first()
		val imported = (page.single() as StepsAwareHistoryPageEntry.ImportedSteps).history
		imported.physicalMembers.map { it.segmentId } shouldBe listOf(41L)
		val exported = mutableListOf<PortableStepsEntryV1>()
		exporter.export(request()) {
			database.inTransaction() shouldBe false
			exported += it
		} shouldBe ExportPortableStepsResult.Exported(1)
		exported shouldBe listOf(original)
		exported.single().contentChecksum shouldBe original.contentChecksum
	}

	@Test
	fun `missing v2 binding is integrity failure while true graphless v1 is graph unavailable`() =
		runTest {
			val original = entry(listOf(run('2', 10L, 20L, 9L)))
			seed(original)
			database.withTransaction {
				ImportedStepsProductReader(database).exportV2InTransaction(request())
			} shouldBe PortableStepsV2Snapshot.Outcome(
				ExportPortableStepsResult.Unverifiable(
					PortableStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE,
				),
			)
			val graph = original.withExplicitUnprovenCountDomain().countDomainGraph
			val dao = database.importedPortableStepsCountDomainDao()
			val receiptIdentity = ImportedPortableCountDomainIdentity.fileReceipt(
				"v2-job",
				"v2-entry",
			)
			dao.insertAuthenticatedGraph(
				graph,
				ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS,
			)
			val binding = ImportedPortableStepsCountDomainBindingEntity(
				productKind =
					ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
				productIdentity = original.identity.value,
				productRevision = 1L,
				graphIdentity = graph.identity.value,
				sourceSchemaVersion = 2,
				sourceReceiptIdentity = receiptIdentity,
				sourceArchiveContentChecksum = original.contentChecksum.value,
			)
			dao.insertBinding(binding)
			dao.insertFileReceipt(
				ImportedPortableStepsFileReceiptEntity(
					importJobId = "v2-job",
					entryKey = "v2-entry",
					receiptIdentity = receiptIdentity,
					sourceName = "v2.trackersteps",
					receivedAtMs = 1L,
					archiveContentChecksum = original.contentChecksum.value,
					entryOrdinal = 0,
					entryIdentity = original.identity.value,
					graphIdentity = graph.identity.value,
				),
			)
			dao.deleteBindingExact(
				binding.productKind,
				binding.productIdentity,
				binding.productRevision,
				binding.graphIdentity,
			) shouldBe 1

			database.withTransaction {
				ImportedStepsProductReader(database).exportV2InTransaction(request())
			} shouldBe PortableStepsV2Snapshot.Outcome(
				ExportPortableStepsResult.Unverifiable(
					PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
				),
			)
		}

	@Test
	fun `replacement runs group exact positive physical members and recency uses newest survivor`() = runTest {
		val original = entry(listOf(run('2', 10L, 20L, 9L), run('3', 30L, 40L, 4L)))
		seed(original)
		seed(entry(listOf(run('5', 25L, 28L, 1L)), '4'), firstSegmentId = 51L)
		val page = history.observeRecentStepsAwarePage(listOf(41L, 42L, 51L), 10).first()
		page.size shouldBe 2
		val imported = (page.first() as StepsAwareHistoryPageEntry.ImportedSteps).history
		imported.physicalMembers.map { it.segmentId } shouldBe listOf(41L, 42L)
		imported.physicalMembers.map { it.steps.count } shouldBe listOf(9L, 4L)
		imported.startTime.raw shouldBe 10L
		imported.endTime.raw shouldBe 40L
	}

	@Test
	fun `two legal entries with 33 runs each survive aggregate batch limits in detail list and export`() = runTest {
		val first = numberedEntry(1, runCount = 33)
		val second = numberedEntry(2, runCount = 33)
		seed(first, firstSegmentId = 1_000L)
		seed(second, firstSegmentId = 2_000L)
		val members = (1_000L..1_032L).toList() + (2_000L..2_032L).toList()
		val selected = database.withTransaction {
			ImportedStepsProductReader(database).selectSessionsInTransaction(members)
		}
		selected.size shouldBe 66
		selected.values.all { it.steps.count == 1L && it.steps.hasCompleteValue } shouldBe true
		val page = history.observeRecentStepsAwarePage(members, 10).first()
		page.size shouldBe 2
		page.map { (it as StepsAwareHistoryPageEntry.ImportedSteps).history.physicalMembers.size } shouldBe listOf(33, 33)
		val exported = mutableListOf<PortableStepsEntryV1>()
		exporter.export(ExportPortableStepsRequest(0L, 100_000L)) { exported += it } shouldBe
			ExportPortableStepsResult.Exported(2)
		exported shouldBe listOf(first, second)
	}

	@Test
	fun `selected sessions spanning more than 32 original entries retain every independent value`() = runTest {
		val ids = (1..33).map { index ->
			val segmentId = index * 100L
			seed(numberedEntry(index, runCount = 1), firstSegmentId = segmentId)
			segmentId
		}
		val selected = database.withTransaction {
			ImportedStepsProductReader(database).selectSessionsInTransaction(ids)
		}
		selected.keys shouldBe ids.toSet()
		selected.values.all { it.steps.count == 1L && it.steps.hasCompleteValue } shouldBe true
	}

	@Test
	fun `covered zero is numeric but missing and partial cannot become complete zero`() = runTest {
		seed(entry(listOf(run('2', 10L, 20L, 0L))))
		seed(entry(listOf(run('4', 30L, 40L, null)), '3'), 51L)
		seed(entry(listOf(run('6', 50L, 60L, 0L).let {
			it.copy(completeness = it.completeness.copy(stopComplete = false))
		}), '5'), 61L)
		val zero = (history.observeSession(41L).first() as SessionHistoryQuery.Found).history.steps
		zero.count shouldBe 0L
		zero.hasCompleteValue shouldBe true
		val missing = (history.observeSession(51L).first() as SessionHistoryQuery.Found).history.steps
		missing.count shouldBe null
		missing.hasCompleteValue shouldBe false
		val partial = (history.observeSession(61L).first() as SessionHistoryQuery.Found).history.steps
		partial.count shouldBe 0L
		partial.hasCompleteValue shouldBe false
		partial.isLowerBound shouldBe true
	}

	@Test
	fun `swapped reverse presentation binding rejects detail and export before sink`() = runTest {
		seed(entry(listOf(run('2', 10L, 20L, 9L), run('3', 30L, 40L, 4L))))
		mutate("UPDATE imported_steps_run SET session_segment_id = 99 WHERE session_segment_id = 41")
		val selected = (history.observeSession(41L).first() as SessionHistoryQuery.Found).history
		selected.steps.count shouldBe null
		selected.capture shouldBe HistoryCapture.Unverifiable
		history.observeRecentStepsAwarePage(listOf(41L, 42L), 10).first() shouldBe emptyList()
		exporter.export(request()) { error("Invalid membership must fail before sink") } shouldBe unavailable()
	}

	@Test
	fun `missing imported hierarchy cannot fall through to raw physical history`() = runTest {
		seed(entry(listOf(run('2', 10L, 20L, 9L))))
		mutate("DELETE FROM imported_steps_entry")
		val selected = (history.observeSession(41L).first() as SessionHistoryQuery.Found).history
		selected.steps.count shouldBe null
		selected.capture shouldBe HistoryCapture.Unverifiable
		history.observeRecentStepsAwarePage(listOf(41L), 10).first() shouldBe emptyList()
	}

	@Test
	fun `one malformed imported receipt does not hide verified peers in the same page`() = runTest {
		seed(entry(listOf(run('2', 10L, 20L, 9L))))
		seed(entry(listOf(run('4', 30L, 40L, 2L)), '3'), 51L)
		mutate("UPDATE imported_steps_run SET retained_checksum = '${"0".repeat(64)}' WHERE session_segment_id = 41")
		val page = history.observeRecentStepsAwarePage(listOf(41L, 51L), 10).first()
		(page.single() as StepsAwareHistoryPageEntry.ImportedSteps).history.physicalMembers.single().segmentId shouldBe 51L
		exporter.export(request()) { error("A partial export cannot silently omit a failed selected entry") } shouldBe unavailable()
	}

	@Test
	fun `corrupt newest imported entry makes coordinated recent page unavailable`() = runTest {
		seed(entry(listOf(run('2', 10L, 20L, 9L))))
		seed(entry(listOf(run('4', 30L, 40L, 2L)), '3'), 51L)
		mutate(
			"UPDATE imported_steps_run SET retained_checksum = '${"0".repeat(64)}' " +
				"WHERE session_segment_id = 51",
		)

		history.observeRecentSourceAwarePage(listOf(41L, 51L), 10).first() shouldBe
			SourceAwareHistoryPageQuery.Unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
				HistorySource.STEPS,
			)
	}

	@Test
	fun `authenticated retained straddle stays a session lower bound and cannot restore complete export`() = runTest {
		val original = entry(listOf(run('2', 10L, 30L, 9L)))
		seed(original)
		val wireRun = original.runs.single()
		database.sourceDeletionFenceDao().upsert(SourceDeletionFenceEntity.createForOriginalRunDigest(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
			scopeIdentityDigest = wireRun.deletionScopeDigest.value,
			fenceGeneration = 1L, collectedDataEpoch = 0L, deletedAtMs = 100L,
		))
		mutate("UPDATE source_evidence_state SET retained_from_ms = 20")
		val selected = (history.observeSession(41L).first() as SessionHistoryQuery.Found).history.steps
		selected.count shouldBe 9L
		selected.isLowerBound shouldBe true
		selected.hasCompleteValue shouldBe false
		(StepsHistoryCause.RETENTION_LIMIT in selected.causes) shouldBe true
		exporter.export(request()) { error("Retained subset cannot restore original complete export") } shouldBe unavailable()
	}

	@Test
	fun `surviving sibling stays discoverable but cannot be reexported with original whole entry checksum`() = runTest {
		val original = entry(listOf(run('2', 10L, 20L, 9L), run('3', 30L, 40L, 4L)))
		seed(original)
		// Exact privacy-action aftermath: removed fact/member/presentation, original metadata retained.
		mutate("DELETE FROM step_fact_revision WHERE service_run_id = '${original.runs.first().identity.value}'")
		mutate("DELETE FROM imported_steps_run WHERE session_segment_id = 41")
		mutate("DELETE FROM session_segment WHERE id = 41")
		val selected = (history.observeSession(42L).first() as SessionHistoryQuery.Found).history
		selected.steps.count shouldBe 4L
		selected.steps.hasCompleteValue shouldBe true
		val page = history.observeRecentStepsAwarePage(listOf(42L), 10).first()
		(page.single() as StepsAwareHistoryPageEntry.ImportedSteps).history.physicalMembers.single().segmentId shouldBe 42L
		exporter.export(request()) { error("Cannot mint replacement original entry identity") } shouldBe unavailable()
	}

	@Test
	fun `exact imported Steps exports original full members and excludes coincident unselected entries`() = runTest {
		val chosen = entry(listOf(run('2', 10L, 20L, 9L), run('3', 30L, 40L, 4L)))
		val unrelated = entry(listOf(run('5', 10L, 20L, 99L)), '4')
		seed(chosen)
		seed(unrelated, 51L)
		val emitted = mutableListOf<PortableStepsEntryV1>()
		exporter.export(selectedStepsExportRequest(listOf(chosen.identity, chosen.identity))) { emitted += it } shouldBe
			ExportPortableStepsResult.Exported(1)
		emitted shouldBe listOf(chosen)
		emitted.single().runs.map { it.facts.single().stepCount } shouldBe listOf(9L, 4L)
	}

	@Test
	fun `missing exact imported Steps ID prevents a valid selected entry from becoming partial success`() = runTest {
		val chosen = entry(listOf(run('2', 10L, 20L, 9L)))
		seed(chosen)
		val emitted = mutableListOf<PortableStepsEntryV1>()
		exporter.export(selectedStepsExportRequest(listOf(chosen.identity, opaque('9')))) { emitted += it } shouldBe unavailable()
		emitted shouldBe emptyList()
	}

	@Test
	fun `deleted exact imported Steps member cannot be replaced with a surviving sibling or matching dates`() = runTest {
		val chosen = entry(listOf(run('2', 10L, 20L, 9L), run('3', 30L, 40L, 4L)))
		seed(chosen)
		seed(entry(listOf(run('5', 10L, 20L, 99L)), '4'), 51L)
		mutate("DELETE FROM step_fact_revision WHERE service_run_id = '${chosen.runs.first().identity.value}'")
		mutate("DELETE FROM imported_steps_run WHERE session_segment_id = 41")
		mutate("DELETE FROM session_segment WHERE id = 41")
		val emitted = mutableListOf<PortableStepsEntryV1>()
		exporter.export(selectedStepsExportRequest(listOf(chosen.identity))) { emitted += it } shouldBe unavailable()
		emitted shouldBe emptyList()
	}

	@Test
	fun `exact imported Steps epoch is fenced both before emission and on completion`() = runTest {
		val chosen = entry(listOf(run('2', 10L, 20L, 9L)))
		seed(chosen)
		val selected = selectedStepsExportRequest(listOf(chosen.identity))
		val emitted = mutableListOf<PortableStepsEntryV1>()
		exporter.export(selected.copy(expectedCollectedDataEpoch = 1L)) { emitted += it } shouldBe unavailable()
		emitted shouldBe emptyList()
		exporter.export(selected) {
			emitted += it
			database.sourceEvidenceStateDao().updateLifecycle(1L, null, 200L)
		} shouldBe ExportPortableStepsResult.RetryableFailure(PortableStepsTransferRetryableReason.CONCURRENT_STATE_CHANGE)
		emitted shouldBe listOf(chosen)
	}

	@Test
	fun `mutating selected imported Steps content during sink refuses final success`() = runTest {
		val chosen = entry(listOf(run('2', 10L, 20L, 9L)))
		seed(chosen)
		val emitted = mutableListOf<PortableStepsEntryV1>()
		exporter.export(selectedStepsExportRequest(listOf(chosen.identity))) {
			database.inTransaction() shouldBe false
			emitted += it
			mutate("UPDATE imported_steps_run SET retained_checksum = '${"0".repeat(64)}' WHERE session_segment_id = 41")
		} shouldBe ExportPortableStepsResult.RetryableFailure(PortableStepsTransferRetryableReason.CONCURRENT_STATE_CHANGE)
		emitted shouldBe listOf(chosen)
	}

	@Test
	fun `unselected imported Steps and evidence revisions leave exact selected data exportable`() = runTest {
		val chosen = entry(listOf(run('2', 10L, 20L, 0L)))
		seed(chosen)
		val emitted = mutableListOf<PortableStepsEntryV1>()
		exporter.export(selectedStepsExportRequest(listOf(chosen.identity))) {
			emitted += it
			seed(numberedEntry(2, 1), 51L)
			database.sourceEvidenceStateDao().incrementRevision(200L)
		} shouldBe ExportPortableStepsResult.Exported(1)
		emitted shouldBe listOf(chosen)
		emitted.single().runs.single().facts.single().stepCount shouldBe 0L
	}

	@Test
	fun `exact imported Steps retention crossing remains unavailable instead of exporting a suffix`() = runTest {
		val chosen = entry(listOf(run('2', 10L, 20L, 9L)))
		seed(chosen)
		database.sourceEvidenceStateDao().updateLifecycle(0L, 15L, 200L)
		val emitted = mutableListOf<PortableStepsEntryV1>()
		exporter.export(selectedStepsExportRequest(listOf(chosen.identity))) { emitted += it } shouldBe
			ExportPortableStepsResult.Unverifiable(PortableStepsExportUnverifiableReason.RETENTION_CROSSES_ENTRY)
		emitted shouldBe emptyList()
	}

	@Test
	fun `exact graphless imported Steps preserves v1 while v2 reports graph unavailable`() = runTest {
		val chosen = entry(listOf(run('2', 10L, 20L, 0L)))
		seed(chosen)
		val selected = selectedStepsExportRequest(listOf(chosen.identity))
		selectedV2Exporter().export(selected) { error("Graphless v1 must not emit fabricated v2") } shouldBe
			ExportPortableStepsResult.Unverifiable(PortableStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE)
		val emitted = mutableListOf<PortableStepsEntryV1>()
		exporter.export(selected) { emitted += it } shouldBe ExportPortableStepsResult.Exported(1)
		emitted shouldBe listOf(chosen)
	}

	@Test
	fun `exact imported Steps v2 preserves stored graph and both reads leave storage revisions unchanged`() = runTest {
		val chosen = entry(listOf(run('2', 10L, 20L, 9L)))
		seed(chosen)
		val original = seedSelectedStepsGraph(chosen)
		seed(entry(listOf(run('5', 10L, 20L, 99L)), '4'), 51L)
		val stateBefore = database.sourceEvidenceStateDao().get()
		val dao = database.importedPortableStepsCountDomainDao()
		val graphBefore = dao.graph(original.countDomainGraph.identity.value)
		val bindingBefore = dao.bindingsForProduct(ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
			chosen.identity.value, 2)
		val emitted = mutableListOf<PortableStepsArchiveV2>()
		selectedV2Exporter().export(selectedStepsExportRequest(listOf(chosen.identity)).copy(expectedCollectedDataEpoch = 1L)) {
			error("A stored graph cannot override the original export epoch")
		} shouldBe unavailable()
		selectedV2Exporter().export(selectedStepsExportRequest(listOf(chosen.identity))) {
			database.inTransaction() shouldBe false
			emitted += it
		} shouldBe ExportPortableStepsResult.Exported(1)
		emitted.single().entries shouldBe listOf(original)
		database.sourceEvidenceStateDao().get() shouldBe stateBefore
		dao.graph(original.countDomainGraph.identity.value) shouldBe graphBefore
		dao.bindingsForProduct(ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
			chosen.identity.value, 2) shouldBe bindingBefore
		dao.fileReceiptCountForEntry(chosen.identity.value) shouldBe 1
	}

	@Test
	fun `deleting selected v2 Steps binding during sink prevents success and does not fall back midfile`() = runTest {
		val chosen = entry(listOf(run('2', 10L, 20L, 9L)))
		seed(chosen)
		val original = seedSelectedStepsGraph(chosen)
		val emitted = mutableListOf<PortableStepsArchiveV2>()
		selectedV2Exporter().export(selectedStepsExportRequest(listOf(chosen.identity))) {
			emitted += it
			database.importedPortableStepsCountDomainDao().deleteBindingExact(
				ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
				chosen.identity.value, 1L, original.countDomainGraph.identity.value) shouldBe 1
		} shouldBe ExportPortableStepsResult.RetryableFailure(PortableStepsTransferRetryableReason.CONCURRENT_STATE_CHANGE)
		emitted.single().entries shouldBe listOf(original)
	}

	@Test
	fun `exact imported Steps selection reads every batch beyond 32 entries without date fallback`() = runTest {
		val chosen = (1..33).map { numberedEntry(it, 1) }
		chosen.forEachIndexed { index, entry -> seed(entry, 1_000L + index) }
		val emitted = mutableListOf<PortableStepsEntryV1>()
		exporter.export(selectedStepsExportRequest(chosen.map { it.identity })) { emitted += it } shouldBe
			ExportPortableStepsResult.Exported(chosen.size)
		emitted.toSet() shouldBe chosen.toSet()
		emitted.size shouldBe chosen.size
	}

	@Test
	fun `exact Steps v2 sink cancellation propagates and preserves its saved graph`() = runTest {
		val chosen = entry(listOf(run('2', 10L, 20L, 9L)))
		seed(chosen)
		val original = seedSelectedStepsGraph(chosen)
		assertFailsWith<CancellationException> {
			selectedV2Exporter().export(selectedStepsExportRequest(listOf(chosen.identity))) {
				throw CancellationException("cancel selected v2 child")
			}
		}
		(database.importedPortableStepsCountDomainDao().graph(original.countDomainGraph.identity.value) != null) shouldBe true
	}

	private fun selectedStepsExportRequest(identities: List<PortableStepsOpaqueIdentity>) =
		ExportSelectedPortableStepsRequest(emptyList(), identities, 0L)

	private fun selectedV2Exporter(): RoomExportPortableStepsV2 {
		val lane = SourceProductLaneExecutionAuthority { false }
		return RoomExportPortableStepsV2(PortableStepsRoomReader(database,
			StepsSegmentHistorySelector(database, lane), lane), Dispatchers.IO)
	}

	private suspend fun seedSelectedStepsGraph(product: PortableStepsEntryV1): PortableStepsEntryV2 = database.withTransaction {
		val original = product.withExplicitUnprovenCountDomain()
		val graph = original.countDomainGraph
		val dao = database.importedPortableStepsCountDomainDao()
		val receiptIdentity = ImportedPortableCountDomainIdentity.fileReceipt("selected-v2-job", "selected-v2-entry")
		dao.insertAuthenticatedGraph(graph, ImportedPortableStepsCountDomainGraphEntity.SOURCE_SESSION_STEPS)
		dao.insertBinding(ImportedPortableStepsCountDomainBindingEntity(
			productKind = ImportedPortableStepsCountDomainBindingEntity.PRODUCT_SESSION_ENTRY,
			productIdentity = product.identity.value, productRevision = 1L,
			graphIdentity = graph.identity.value, sourceSchemaVersion = 2,
			sourceReceiptIdentity = receiptIdentity, sourceArchiveContentChecksum = product.contentChecksum.value))
		dao.insertFileReceipt(ImportedPortableStepsFileReceiptEntity(
			importJobId = "selected-v2-job", entryKey = "selected-v2-entry", receiptIdentity = receiptIdentity,
			sourceName = "selected.trackersteps", receivedAtMs = 1L,
			archiveContentChecksum = product.contentChecksum.value, entryOrdinal = 0,
			entryIdentity = product.identity.value, graphIdentity = graph.identity.value))
		original
	}

	private suspend fun seed(portable: PortableStepsEntryV1, firstSegmentId: Long = 41L) = database.withTransaction {
		val entry = ImportedStepsAdmissionRows.entry(portable, epoch = 0L, ownerGeneration = 1L)
		database.importedStepsDao().insertEntry(entry)
		portable.runs.forEachIndexed { index, run ->
			val id = firstSegmentId + index
			database.sessionSegmentDao().insert(SessionSegment(
				id = id, startTimeMs = run.startTimeMs, endTimeMs = run.endTimeMs,
				distanceM = 0f, steps = null, primaryActivity = null, activityConfidence = null,
				sampleCount = 0, source = SegmentSource.PORTABLE_STEPS_IMPORT, inferenceVersion = null,
				createdAt = 100L, logicalTrackingId = entry.identity, serviceRunId = run.identity.value,
			))
			database.importedStepsDao().insertRun(ImportedStepsAdmissionRows.run(entry, run, id))
			ImportedStepsAdmissionRows.manifests(run).forEach { database.importedStepsDao().insertManifest(it) }
			run.facts.forEach {
				database.stepFactRevisionDao().insert(ImportedStepsAdmissionRows.fact(entry, run, it, 100L))
			}
		}
	}

	private suspend fun mutate(sql: String) = database.withTransaction {
		database.openHelper.writableDatabase.execSQL(sql)
	}

	private fun entry(runs: List<PortableStepsRunV1>, id: Char = '1') = PortableStepsEntryV1.create(
		opaque(id), PortableStepsSessionMode.MANUAL, runs.minOf { it.startTimeMs }, runs.maxOf { it.endTimeMs }, runs,
	)

	private fun run(id: Char, start: Long, end: Long, count: Long?) = PortableStepsRunV1(
		identity = opaque(id), deletionScopeDigest = PortableStepsDeletionScopeDigest(id.toString().repeat(64)),
		startTimeMs = start, endTimeMs = end, storedZoneId = "UTC",
		manifests = listOf(PortableStepsManifestV1(1L, start, 7L, 8L)),
		completeness = PortableStepsCompletenessV1(
			PortableStepsCaptureCoverage.WHOLE_RUN, PortableStepsProviderCoverage.COMPLETE, true, true, false,
		),
		facts = if (count == null) { emptyList() } else { listOf(PortableStepsFactV1.create(
			PortableStepsOpaqueIdentity("sha256:${id.toString().repeat(63)}f"),
			1L, start, end, 0L, PortableStepsFactCoverage.COVERED, count,
		)) },
	)

	private fun opaque(id: Char) = PortableStepsOpaqueIdentity("sha256:${id.toString().repeat(64)}")
	private fun numberedEntry(index: Int, runCount: Int): PortableStepsEntryV1 {
		fun identity(prefix: Char, value: Int) = PortableStepsOpaqueIdentity(
			"sha256:$prefix${value.toString(16).padStart(63, '0')}",
		)
		val runs = (0 until runCount).map { offset ->
			val start = index * 1_000L + offset * 10L
			val runIdentity = identity('b', index * 100 + offset)
			run('2', start, start + 10L, 1L).copy(
				identity = runIdentity,
				deletionScopeDigest = PortableStepsDeletionScopeDigest(runIdentity.value.removePrefix("sha256:")),
				facts = listOf(PortableStepsFactV1.create(
					identity('c', index * 100 + offset), 1L, start, start + 10L, 0L,
					PortableStepsFactCoverage.COVERED, 1L,
				)),
			)
		}
		return PortableStepsEntryV1.create(
			identity('a', index), PortableStepsSessionMode.MANUAL,
			runs.first().startTimeMs, runs.last().endTimeMs, runs,
		)
	}

	private fun request() = ExportPortableStepsRequest(0L, 100L)
	private fun unavailable() = ExportPortableStepsResult.Unverifiable(
		PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
	)
}
