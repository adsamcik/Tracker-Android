package com.adsamcik.tracker.activity.data

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomSessionActivityRepositoryTest {
	private val dispatcher = UnconfinedTestDispatcher()
	private val context = mockk<Context> {
		every { getString(any()) } answers { "native-${firstArg<Int>()}" }
	}
	private val activityDao = mockk<ActivityDao>()
	private val repository = RoomSessionActivityRepository(
		context = context,
		activityDao = activityDao,
		dispatchers = TestDispatchersProvider(dispatcher),
	)

	@Test
	fun `maps Room rows to immutable feature items`() = runTest(dispatcher) {
		coEvery { activityDao.getAllUser() } returns listOf(
			SessionActivity(id = 42, name = "Hiking", iconName = "hiking"),
		)

		assertEquals(
			SessionActivityItem(id = 42, name = "Hiking", iconName = "hiking"),
			repository.getActivities().first { it.id == 42L },
		)
	}

	@Test
	fun `maps create update and delete commands to DAO operations`() = runTest(dispatcher) {
		coEvery { activityDao.insert(any<SessionActivity>()) } returns 7L
		coEvery { activityDao.update(any<SessionActivity>()) } returns Unit
		every { activityDao.delete(7L) } returns Unit

		repository.create(CreateSessionActivityCommand(name = "Hiking", iconName = "hiking"))
		repository.update(
			UpdateSessionActivityCommand(id = 7, name = "Trail hiking", iconName = "trail"),
		)
		repository.delete(7)

		coVerify(exactly = 1) {
			activityDao.insert(
				match<SessionActivity> {
					it.id == 0L && it.name == "Hiking" && it.iconName == "hiking"
				},
			)
		}
		coVerify(exactly = 1) {
			activityDao.update(
				match<SessionActivity> {
					it.id == 7L && it.name == "Trail hiking" && it.iconName == "trail"
				},
			)
		}
		verify(exactly = 1) { activityDao.delete(7) }
	}
}
