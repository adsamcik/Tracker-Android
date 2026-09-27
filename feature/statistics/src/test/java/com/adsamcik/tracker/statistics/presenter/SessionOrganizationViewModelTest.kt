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
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.every
import io.mockk.coEvery
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
	private val snapshots = MutableStateFlow(CollectedDataLifecycleSnapshot(0, null))
	private val content = MutableStateFlow<SessionOrganizationRead>(SessionOrganizationRead.Content(listOf(session()), false, 0))
	@BeforeEach fun setup() {
		Dispatchers.setMain(dispatcher)
		every { lifecycle.snapshots } returns snapshots
		every { repo.observe(any(), any(), any()) } returns content
		coEvery { repo.activityLabels() } returns emptyList()
	}
	@AfterEach fun cleanup() = Dispatchers.resetMain()
	@Test fun `privacy epoch removes private draft list and calendar before delayed Room refresh`() = runTest(dispatcher) {
		val vm = SessionOrganizationViewModel(repo, FixedClock(1_000), lifecycle)
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
		coEvery { repo.edit(any(), any(), any(), any(), any()) } returns OrganizationMutationResult.Rejected(OrganizationEditProblem.STALE)
		val vm = SessionOrganizationViewModel(repo, FixedClock(1_000), lifecycle)
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
		val vm = SessionOrganizationViewModel(repo, FixedClock(1_000), lifecycle)
		advanceUntilIdle(); vm.edit(outside); advanceUntilIdle()
		vm.read.value?.let { (it as SessionOrganizationRead.Content).sessions.none { group -> group.id == outside.id } } shouldBe true
		vm.reload(); advanceUntilIdle()
		vm.editor.value?.session?.name shouldBe "Fresh"
		vm.problem.value shouldBe null
	}

	private fun session() = OrganizedSession(OrganizationSessionId.Annotation("local"), 1, 0, "Private",
		null, null, "UTC", 100, 200, false, false)
}
