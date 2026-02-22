package com.adsamcik.tracker.statistics.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.LocationDataDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.Trip
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripDetailViewModelTest {

	private val testDispatcher = StandardTestDispatcher()
	private val tripDao: TripDao = mockk()
	private val locationDataDao: LocationDataDao = mockk()
	private lateinit var dispatchersProvider: TestDispatchersProvider

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		dispatchersProvider = TestDispatchersProvider(testDispatcher)
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	private fun createViewModel(tripId: Long): TripDetailViewModel {
		val savedStateHandle = SavedStateHandle(mapOf("tripId" to tripId))
		return TripDetailViewModel(tripDao, locationDataDao, dispatchersProvider, savedStateHandle)
	}

	private fun createViewModelWithoutTripId(): TripDetailViewModel {
		val savedStateHandle = SavedStateHandle()
		return TripDetailViewModel(tripDao, locationDataDao, dispatchersProvider, savedStateHandle)
	}

	private val sampleTrip = Trip(
		id = 42L,
		startTimeMs = 1000L,
		endTimeMs = 5000L,
		distanceM = 1500f,
		steps = 300,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 20,
		source = SegmentSource.USER_CREATED,
		createdAt = 1000L
	)

	@Test
	fun `initial state is Loading`() = runTest {
		coEvery { tripDao.getById(42L) } returns sampleTrip
		coEvery { locationDataDao.getAllBetweenOrdered(any(), any()) } returns emptyList()

		val vm = createViewModel(42L)
		vm.state.value shouldBe TripDetailState.Loading
	}

	@Test
	fun `loads trip successfully`() = runTest {
		coEvery { tripDao.getById(42L) } returns sampleTrip
		coEvery { locationDataDao.getAllBetweenOrdered(any(), any()) } returns emptyList()

		val vm = createViewModel(42L)
		advanceUntilIdle()

		val state = vm.state.value.shouldBeInstanceOf<TripDetailState.Loaded>()
		state.trip shouldBe sampleTrip
	}

	@Test
	fun `shows not found for missing trip`() = runTest {
		coEvery { tripDao.getById(999L) } returns null

		val vm = createViewModel(999L)
		advanceUntilIdle()

		vm.state.value shouldBe TripDetailState.NotFound
	}

	@Test
	fun `missing tripId produces error state instead of crash`() = runTest {
		val vm = createViewModelWithoutTripId()
		advanceUntilIdle()

		val state = vm.state.value.shouldBeInstanceOf<TripDetailState.Error>()
		state.message shouldContain "Trip ID"
	}

	@Test
	fun `retry with missing tripId stays in error state`() = runTest {
		val vm = createViewModelWithoutTripId()
		advanceUntilIdle()

		vm.retry()
		advanceUntilIdle()

		vm.state.value.shouldBeInstanceOf<TripDetailState.Error>()
	}
}
