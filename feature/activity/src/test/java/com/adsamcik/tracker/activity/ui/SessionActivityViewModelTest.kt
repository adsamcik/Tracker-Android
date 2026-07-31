package com.adsamcik.tracker.activity.ui

import com.adsamcik.tracker.activity.data.CreateSessionActivityCommand
import com.adsamcik.tracker.activity.data.SessionActivityItem
import com.adsamcik.tracker.activity.data.SessionActivityRepository
import com.adsamcik.tracker.activity.data.UpdateSessionActivityCommand
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionActivityViewModelTest {
	private val dispatcher = UnconfinedTestDispatcher()
	private lateinit var repository: RecordingSessionActivityRepository
	private lateinit var viewModel: SessionActivityViewModel

	@Before
	fun setUp() {
		Dispatchers.setMain(dispatcher)
		repository = RecordingSessionActivityRepository(
			listOf(
				SessionActivityItem(id = 5, name = "Hiking", iconName = "hiking"),
				SessionActivityItem(id = -2, name = "Walking", iconName = "walking"),
			),
		)
		viewModel = SessionActivityViewModel(
			activityRepository = repository,
		)
	}

	@After
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `state exposes repository-owned activities using feature model`() {
		assertEquals("Hiking", viewModel.uiState.value.items.first().name)
		assertTrue(viewModel.uiState.value.items.any { it.id < 0 })
	}

	@Test
	fun `create trims name before sending command`() {
		viewModel.insertActivity("  Trail running  ")

		assertEquals(
			CreateSessionActivityCommand(name = "Trail running"),
			repository.created.single(),
		)
	}

	@Test
	fun `update and delete send explicit feature commands`() {
		val item = SessionActivityItem(id = 5, name = "Trail hiking", iconName = "trail")

		viewModel.updateActivity(item)
		viewModel.deleteActivity(item)

		assertEquals(
			UpdateSessionActivityCommand(id = 5, name = "Trail hiking", iconName = "trail"),
			repository.updated.single(),
		)
		assertEquals(listOf(5L), repository.deleted)
	}
}

private class RecordingSessionActivityRepository(
	private val items: List<SessionActivityItem>,
) : SessionActivityRepository {
	val created = mutableListOf<CreateSessionActivityCommand>()
	val updated = mutableListOf<UpdateSessionActivityCommand>()
	val deleted = mutableListOf<Long>()

	override suspend fun getActivities(): List<SessionActivityItem> = items

	override suspend fun delete(activityId: Long) {
		deleted += activityId
	}

	override suspend fun create(command: CreateSessionActivityCommand) {
		created += command
	}

	override suspend fun update(command: UpdateSessionActivityCommand) {
		updated += command
	}
}
