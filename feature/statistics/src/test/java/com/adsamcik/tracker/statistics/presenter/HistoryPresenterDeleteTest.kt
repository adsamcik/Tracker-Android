package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for the pending-delete/undo/confirm workflow in [HistoryPresenterViewModel].
 * Uses a lightweight approach that tests the StateFlow logic and DAO interaction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryPresenterDeleteTest {

	private val testDispatcher = StandardTestDispatcher()

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `requestDeleteTrip adds id to pendingDeletes`() = runTest {
		val vm = createViewModel()

		vm.pendingDeletes.test {
			awaitItem().shouldBeEmpty()

			vm.requestDeleteTrip(42L)
			awaitItem().shouldContainExactly(42L)
		}
	}

	@Test
	fun `undoDeleteTrip removes id from pendingDeletes`() = runTest {
		val vm = createViewModel()

		vm.pendingDeletes.test {
			awaitItem().shouldBeEmpty()

			vm.requestDeleteTrip(42L)
			awaitItem().shouldContainExactly(42L)

			vm.undoDeleteTrip(42L)
			awaitItem().shouldBeEmpty()
		}
	}

	@Test
	fun `confirmDeleteTrip removes from pending and calls repository delete`() = runTest {
		val tripPresentationRepository = createMockTripPresentationRepository()
		val vm = createViewModel(tripPresentationRepository = tripPresentationRepository)

		vm.requestDeleteTrip(42L)
		vm.pendingDeletes.value.shouldContainExactly(42L)

		vm.confirmDeleteTrip(42L)
		advanceUntilIdle()
		vm.pendingDeletes.value.shouldBeEmpty()
		coVerify(exactly = 1) { tripPresentationRepository.deleteTrip(42L) }
	}

	@Test
	fun `delete deadline commits trip from viewModelScope`() = runTest {
		val tripPresentationRepository = createMockTripPresentationRepository()
		val vm = createViewModel(tripPresentationRepository = tripPresentationRepository)

		vm.requestDeleteTrip(42L)
		runCurrent()
		advanceTimeBy(HistoryPresenterViewModel.UNDO_DELETE_TIMEOUT_MS)
		runCurrent()

		vm.pendingDeletes.value.shouldBeEmpty()
		coVerify(exactly = 1) { tripPresentationRepository.deleteTrip(42L) }
	}

	@Test
	fun `undo before delete deadline prevents repository deletion`() = runTest {
		val tripPresentationRepository = createMockTripPresentationRepository()
		val vm = createViewModel(tripPresentationRepository = tripPresentationRepository)

		vm.requestDeleteTrip(42L)
		runCurrent()
		advanceTimeBy(HistoryPresenterViewModel.UNDO_DELETE_TIMEOUT_MS - 1)
		vm.undoDeleteTrip(42L)
		runCurrent()
		advanceTimeBy(1)
		runCurrent()

		vm.pendingDeletes.value.shouldBeEmpty()
		coVerify(exactly = 0) { tripPresentationRepository.deleteTrip(any()) }
	}

	@Test
	fun `cancelling snackbar collector does not cancel ViewModel delete deadline`() = runTest {
		val tripPresentationRepository = createMockTripPresentationRepository()
		val vm = createViewModel(tripPresentationRepository = tripPresentationRepository)
		val snackbarCollector = backgroundScope.launch {
			vm.pendingDeleteEvents.collect {}
		}
		runCurrent()

		vm.requestDeleteTrip(42L)
		runCurrent()
		snackbarCollector.cancel()
		advanceTimeBy(HistoryPresenterViewModel.UNDO_DELETE_TIMEOUT_MS)
		runCurrent()

		coVerify(exactly = 1) { tripPresentationRepository.deleteTrip(42L) }
	}

	@Test
	fun `restored pending delete commits immediately after recreation`() = runTest {
		val savedStateHandle = SavedStateHandle(
			mapOf(HistoryPresenterViewModel.KEY_PENDING_DELETE_IDS to longArrayOf(42L)),
		)
		val tripPresentationRepository = createMockTripPresentationRepository()

		createViewModel(
			tripPresentationRepository = tripPresentationRepository,
			savedStateHandle = savedStateHandle,
		)
		runCurrent()

		coVerify(exactly = 1) { tripPresentationRepository.deleteTrip(42L) }
		savedStateHandle.get<LongArray>(HistoryPresenterViewModel.KEY_PENDING_DELETE_IDS)
			?.toSet()
			.orEmpty()
			.shouldBeEmpty()
	}

	@Test
	fun `multiple pending deletes are tracked independently`() = runTest {
		val vm = createViewModel()

		vm.requestDeleteTrip(1L)
		vm.requestDeleteTrip(2L)
		vm.requestDeleteTrip(3L)
		vm.pendingDeletes.value.size shouldBe 3

		vm.undoDeleteTrip(2L)
		vm.pendingDeletes.value shouldBe setOf(1L, 3L)
	}

	private fun createMockTripPresentationRepository(): com.adsamcik.tracker.stats.api.repository.TripPresentationRepository {
		return mockk(relaxed = true) {
			every { getPagedTrips() } returns mockk()
			coEvery { deleteTrip(any()) } returns Unit
			coEvery { getTripsBetween(any(), any()) } returns emptyList()
		}
	}

	private fun createViewModel(
		tripPresentationRepository: com.adsamcik.tracker.stats.api.repository.TripPresentationRepository =
			createMockTripPresentationRepository(),
		savedStateHandle: SavedStateHandle = SavedStateHandle(),
	): HistoryPresenterViewModel {
		val dailySummaryRepository: com.adsamcik.tracker.stats.api.repository.DailySummaryRepository =
			mockk(relaxed = true) {
				every { observeBetween(any(), any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
			}
		val explorationRepository: com.adsamcik.tracker.stats.api.repository.ExplorationRepository =
			mockk(relaxed = true) {
				every { observeCellCount(any()) } returns kotlinx.coroutines.flow.flowOf(0)
			}
		val stepsNumericSummaryRepository =
			mockk<com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRepository> {
				coEvery { read(any()) } returns
					com.adsamcik.tracker.stats.api.repository.StepsNumericSummary.Unverifiable(
						com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason.NOT_CAPTURED,
					)
			}
		return HistoryPresenterViewModel(
			tripPresentationRepository = tripPresentationRepository,
			dailySummaryRepository = dailySummaryRepository,
			stepsNumericSummaryRepository = stepsNumericSummaryRepository,
			explorationRepository = explorationRepository,
			savedStateHandle = savedStateHandle,
		)
	}
}
