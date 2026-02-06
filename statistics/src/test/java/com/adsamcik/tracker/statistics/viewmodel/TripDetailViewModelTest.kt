package com.adsamcik.tracker.statistics.viewmodel

import androidx.lifecycle.SavedStateHandle
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripDetailViewModelTest {

	private val testDispatcher = StandardTestDispatcher()
	private val tripDao: TripDao = mockk()

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	private fun createViewModel(tripId: Long): TripDetailViewModel {
		val savedStateHandle = SavedStateHandle(mapOf("tripId" to tripId))
		return TripDetailViewModel(tripDao, savedStateHandle)
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

		val vm = createViewModel(42L)
		assertEquals(TripDetailState.Loading, vm.state.value)
	}

	@Test
	fun `loads trip successfully`() = runTest {
		coEvery { tripDao.getById(42L) } returns sampleTrip

		val vm = createViewModel(42L)
		advanceUntilIdle()

		val state = vm.state.value
		assertTrue(state is TripDetailState.Loaded)
		assertEquals(sampleTrip, (state as TripDetailState.Loaded).trip)
	}

	@Test
	fun `shows not found for missing trip`() = runTest {
		coEvery { tripDao.getById(999L) } returns null

		val vm = createViewModel(999L)
		advanceUntilIdle()

		assertEquals(TripDetailState.NotFound, vm.state.value)
	}
}
