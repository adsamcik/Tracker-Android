package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.repository.OrganizationEditProblem
import com.adsamcik.tracker.stats.api.repository.OrganizationMutationResult
import com.adsamcik.tracker.stats.api.repository.OrganizationSessionId
import com.adsamcik.tracker.stats.api.repository.OrganizedSession
import com.adsamcik.tracker.stats.api.repository.PagedSessionOrganizationRepository
import com.adsamcik.tracker.stats.api.repository.SessionOrganizationRead
import com.adsamcik.tracker.stats.api.repository.HistoryContinuation
import com.adsamcik.tracker.stats.api.repository.HistoryLocationCoverage
import com.adsamcik.tracker.stats.api.repository.HistoryLocationPoint
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRead
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import com.adsamcik.tracker.feature.statistics.api.export.SessionBundleExportResult
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionOrganizationViewModelTest {
	private val dispatcher = StandardTestDispatcher()
	private val repo = mockk<PagedSessionOrganizationRepository>()
	private val lifecycle = mockk<CollectedDataLifecycleStore>()
	private val history = mockk<com.adsamcik.tracker.stats.api.repository.PagedTrackingHistoryRepository>()
	private val exporter = mockk<com.adsamcik.tracker.feature.statistics.api.export.SessionBundleExporter>()
	private val snapshots = MutableStateFlow(CollectedDataLifecycleSnapshot(0, null))
	private val content = MutableStateFlow<SessionOrganizationRead>(SessionOrganizationRead.Content(listOf(session()), false, 0))
	@BeforeEach fun setup() {
		Dispatchers.setMain(dispatcher)
		every { lifecycle.snapshots } returns snapshots
		every { repo.observe(any(), any(), any()) } returns content
		every { repo.observeSession(any()) } returns content
		coEvery { repo.activityLabels() } returns emptyList()
	}
	@AfterEach fun cleanup() = Dispatchers.resetMain()
	@Test fun `privacy epoch removes private draft list and calendar before delayed Room refresh`() = runTest(dispatcher) {
		val vm = SessionOrganizationViewModel(repo, FixedClock(1_000), lifecycle, history, exporter)
		advanceUntilIdle()
		vm.edit(session()); advanceUntilIdle()
		vm.editor.value?.session?.name shouldBe "Private"
		snapshots.value = CollectedDataLifecycleSnapshot(1, null)
		advanceUntilIdle()
		vm.editor.value shouldBe null
		vm.read.value shouldBe null
		vm.monthRead.value shouldBe null
		vm.problem.value shouldBe OrganizationEditProblem.STALE
		vm.edit(session()); advanceUntilIdle()
		vm.editor.value shouldBe null
		content.value = SessionOrganizationRead.Content(listOf(session().copy(name = "Late old payload")), false, 0)
		advanceUntilIdle()
		vm.read.value shouldBe null
	}
	@Test fun `stale save keeps draft until explicit reload`() = runTest(dispatcher) {
		coEvery { repo.edit(any(), any(), any(), any(), any(), any()) } returns OrganizationMutationResult.Rejected(OrganizationEditProblem.STALE)
		val vm = SessionOrganizationViewModel(repo, FixedClock(1_000), lifecycle, history, exporter)
		advanceUntilIdle(); vm.edit(session()); advanceUntilIdle()
		vm.save(null, "UTC", "Draft", null); advanceUntilIdle()
		vm.editor.value?.session?.name shouldBe "Private"
		content.value = SessionOrganizationRead.Content(listOf(session().copy(name = "External", revision = 2)), false, 0)
		advanceUntilIdle()
		vm.editor.value?.session?.name shouldBe "Private"
		coEvery { repo.find(any()) } returns SessionOrganizationRead.Content(listOf(session().copy(name = "External", revision = 2)), false, 0)
		vm.reload(); advanceUntilIdle()
		vm.editor.value?.session?.name shouldBe "External"
	}
	@Test fun `reload uses exact lookup for a group beyond visible first page`() = runTest(dispatcher) {
		val outside = session().copy(id = OrganizationSessionId.Annotation("outside-first-page"))
		coEvery { repo.find(outside.id) } returns SessionOrganizationRead.Content(listOf(outside.copy(name = "Fresh", revision = 2)), false, 0)
		val vm = SessionOrganizationViewModel(repo, FixedClock(1_000), lifecycle, history, exporter)
		advanceUntilIdle(); vm.edit(outside); advanceUntilIdle()
		vm.read.value?.let { (it as SessionOrganizationRead.Content).sessions.none { group -> group.id == outside.id } } shouldBe true
		vm.reload(); advanceUntilIdle()
		vm.editor.value?.session?.name shouldBe "Fresh"
		vm.problem.value shouldBe null
	}

	@Test fun `name-only imported save passes the default preserve-label request`() = runTest(dispatcher) {
		val imported = session().copy(activityLabelName = "Hike", importedSourceSelection = emptyList())
		coEvery { repo.edit(imported, null, "UTC", "New name", null, false) } returns OrganizationMutationResult.Saved
		val vm = SessionOrganizationViewModel(repo, FixedClock(1_000), lifecycle, history, exporter)
		advanceUntilIdle(); vm.edit(imported); advanceUntilIdle()
		vm.save(null, "UTC", "New name", null); advanceUntilIdle()
		coVerify(exactly = 1) { repo.edit(imported, null, "UTC", "New name", null, false) }
		vm.editor.value shouldBe null
		vm.problem.value shouldBe null
	}
	@Test fun `explicit imported-label removal reaches the same edit and can retry without closing the draft`() = runTest(dispatcher) {
		val imported = session().copy(activityLabelName = "Hike", importedSourceSelection = emptyList())
		coEvery { repo.edit(imported, null, "UTC", "Private", null, true) } returns
			OrganizationMutationResult.Rejected(OrganizationEditProblem.STORAGE_UNAVAILABLE) andThen OrganizationMutationResult.Saved
		val vm = SessionOrganizationViewModel(repo, FixedClock(1_000), lifecycle, history, exporter)
		advanceUntilIdle(); vm.edit(imported); advanceUntilIdle()
		vm.save(null, "UTC", "Private", null, clearImportedActivityLabel = true); advanceUntilIdle()
		vm.editor.value?.session shouldBe imported
		vm.problem.value shouldBe OrganizationEditProblem.STORAGE_UNAVAILABLE
		vm.busy.value shouldBe false
		vm.save(null, "UTC", "Private", null, clearImportedActivityLabel = true); advanceUntilIdle()
		coVerify(exactly = 2) { repo.edit(imported, null, "UTC", "Private", null, true) }
		vm.editor.value shouldBe null
		vm.problem.value shouldBe null
	}

	@Test fun `selected contents follow exact metadata edits and disappear after removal without editing readings`() = runTest(dispatcher) {
		val vm = SessionOrganizationViewModel(repo, FixedClock(1_000), lifecycle, history, exporter)
		advanceUntilIdle(); vm.showContents(session()); advanceUntilIdle()
		vm.contents.value shouldBe session()
		content.value = SessionOrganizationRead.Content(listOf(session().copy(name = "Walk", fromMs = 120, revision = 2)), false, 0)
		advanceUntilIdle()
		vm.contents.value?.name shouldBe "Walk"
		vm.contents.value?.fromMs shouldBe 120L
		content.value = SessionOrganizationRead.Content(emptyList(), false, 0)
		advanceUntilIdle()
		vm.contents.value shouldBe null
		coVerify(exactly = 0) { repo.remove(any()) }
	}

	@Test fun `source availability follows selected session pages and a failed sibling does not hide saved sources`() = runTest(dispatcher) {
		coEvery { history.readRange(any()) } answers {
			val request = firstArg<HistoryRangeRequest>()
			request.range.organizationSessionId shouldBe session().id
			when (request.range.sources.single()) {
				HistorySource.LOCATION -> page(listOf(locationRow()))
				HistorySource.STEPS -> if (request.after == null) {
					page(emptyList(),
					HistoryContinuation(request.range, HistoryPosition(190, "local", "steps-frontier")))
				}
					else {
					page(listOf(locationRow().copy(measurement = HistoryMeasurement.Gap(HistorySource.STEPS, "provider gap"))))
				}
				HistorySource.WIFI -> HistoryRangeRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)
				else -> page(emptyList())
			}
		}
		val vm = SessionOrganizationViewModel(repo, FixedClock(1_000), lifecycle, history, exporter)
		advanceUntilIdle(); vm.showContents(session()); advanceUntilIdle()
		vm.chooseExportSources(); advanceUntilIdle()
		vm.exportChoices.value?.availableSources shouldBe setOf(HistorySource.LOCATION, HistorySource.STEPS)
		vm.exportChoices.value?.unavailableSources shouldBe setOf(HistorySource.WIFI)
		val uri = mockk<android.net.Uri>()
		coEvery { exporter.export(uri.toString(), any()) } returns SessionBundleExportResult.Success()
		vm.prepareExport(setOf(HistorySource.LOCATION)) shouldBe true
		vm.exportTo(uri); advanceUntilIdle()
		coVerify(exactly = 1) { exporter.export(uri.toString(), match {
			it.sessionId == session().id && it.expectedOrganizationRevision == 1L &&
				it.expectedCollectedDataEpoch == 0L && it.sources == setOf(HistorySource.LOCATION)
		}) }
		vm.exportBusy.value shouldBe false
		vm.exportFailure.value shouldBe null
	}

	@Test fun `clear invalidates contents and a prepared file choice before an old result can write`() = runTest(dispatcher) {
		coEvery { history.readRange(any()) } answers {
			val request = firstArg<HistoryRangeRequest>()
			page(if (HistorySource.LOCATION in request.range.sources) {
				listOf(locationRow())
			} else {
				emptyList()
			})
		}
		val vm = SessionOrganizationViewModel(repo, FixedClock(1_000), lifecycle, history, exporter)
		advanceUntilIdle(); vm.showContents(session()); advanceUntilIdle()
		vm.chooseExportSources(); advanceUntilIdle()
		vm.prepareExport(setOf(HistorySource.LOCATION)) shouldBe true
		snapshots.value = CollectedDataLifecycleSnapshot(1, null)
		advanceUntilIdle()
		vm.contents.value shouldBe null
		vm.exportChoices.value shouldBe null
		vm.exportTo(mockk()); advanceUntilIdle()
		coVerify(exactly = 0) { exporter.export(any(), any()) }
	}

	private fun page(rows: List<UnifiedHistoryRow>, next: HistoryContinuation? = null) =
		HistoryRangeRead.Page(rows, TrackingHistoryReadSnapshot(0, 0), next)
	private fun locationRow() = UnifiedHistoryRow(HistoryPosition(150, "local", "saved"), null,
		setOf(HistoryOrigin.LOCAL), HistoryPurpose.BACKGROUND, sections = emptyList(), measurement = HistoryMeasurement.Location(
			HistoryLocationPoint(150, 500_000_000, 140_000_000, null, null), HistoryLocationCoverage.LEGACY_UNVERIFIABLE))

	private fun session() = OrganizedSession(OrganizationSessionId.Annotation("local"), 1, 0, "Private",
		null, null, "UTC", 100, 200, false, false)
}
