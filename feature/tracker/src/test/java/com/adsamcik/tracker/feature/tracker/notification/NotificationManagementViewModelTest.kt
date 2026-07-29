package com.adsamcik.tracker.feature.tracker.notification

import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.tracker.notification.TrackerNotificationSetting
import com.adsamcik.tracker.tracker.notification.TrackerNotificationSettingsRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationManagementViewModelTest {
	private val dispatcher = UnconfinedTestDispatcher()

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(dispatcher)
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `loads settings once through the api repository`() = runTest(dispatcher) {
		val repository = FakeTrackerNotificationSettingsRepository(settings())
		val viewModel = viewModel(repository)

		viewModel.load()
		viewModel.load()

		viewModel.items.shouldContainExactly(settings())
		repository.loadCount shouldBe 1
	}

	@Test
	fun `persists reordered settings in display order`() = runTest(dispatcher) {
		val repository = FakeTrackerNotificationSettingsRepository(settings())
		val viewModel = viewModel(repository)
		viewModel.load()

		viewModel.moveItem(from = 0, to = 2)
		viewModel.persistOrder()

		viewModel.items.map(TrackerNotificationSetting::id)
			.shouldContainExactly("altitude", "battery", "activity")
		repository.saved.single().shouldContainExactly(viewModel.items)
	}

	@Test
	fun `updates flags and persists the complete current catalog`() = runTest(dispatcher) {
		val repository = FakeTrackerNotificationSettingsRepository(settings())
		val viewModel = viewModel(repository)
		viewModel.load()

		viewModel.updateFlags(
			id = "altitude",
			inTitle = true,
			inContent = false,
		)

		viewModel.items.first { it.id == "altitude" }.isInTitle shouldBe true
		viewModel.items.first { it.id == "altitude" }.isInContent shouldBe false
		repository.saved.single().shouldContainExactly(viewModel.items)
	}

	private fun viewModel(
		repository: TrackerNotificationSettingsRepository,
	) = NotificationManagementViewModel(
		settingsRepository = repository,
		dispatchers = TestDispatchersProvider(dispatcher),
	)

	private fun settings() = listOf(
		setting("activity", inTitle = false, inContent = true),
		setting("altitude", inTitle = false, inContent = true),
		setting("battery", inTitle = true, inContent = false),
	)

	private fun setting(
		id: String,
		inTitle: Boolean,
		inContent: Boolean,
	) = TrackerNotificationSetting(
		id = id,
		titleRes = android.R.string.ok,
		isInTitle = inTitle,
		isInContent = inContent,
	)
}

private class FakeTrackerNotificationSettingsRepository(
	private val loaded: List<TrackerNotificationSetting>,
) : TrackerNotificationSettingsRepository {
	var loadCount = 0
		private set
	val saved = mutableListOf<List<TrackerNotificationSetting>>()

	override suspend fun loadSettings(): List<TrackerNotificationSetting> {
		loadCount += 1
		return loaded
	}

	override suspend fun saveSettings(
		settingsInDisplayOrder: List<TrackerNotificationSetting>,
	) {
		saved += settingsInDisplayOrder
	}
}
