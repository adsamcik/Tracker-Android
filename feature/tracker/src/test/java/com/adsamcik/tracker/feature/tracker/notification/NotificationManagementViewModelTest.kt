package com.adsamcik.tracker.feature.tracker.notification

import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.tracker.notification.TrackerNotificationSetting
import com.adsamcik.tracker.tracker.notification.TrackerNotificationSettingsRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
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

	@Test
	fun `a multi-step drag saves its final order on release`() = runTest(dispatcher) {
		val repository = FakeTrackerNotificationSettingsRepository(settings())
		val viewModel = viewModel(repository)
		viewModel.load()
		viewModel.setDragging(0)
		viewModel.moveItem(0, 1)
		viewModel.moveItem(1, 2)
		repository.saved.size shouldBe 0
		viewModel.setDragging(null)
		viewModel.persistOrder()
		repository.saved.single().map(TrackerNotificationSetting::id)
			.shouldContainExactly("altitude", "battery", "activity")
	}

	@Test
	fun `failed load can be retried without losing the screen`() = runTest(dispatcher) {
		val repository = FakeTrackerNotificationSettingsRepository(settings()).apply { failNextLoad = true }
		val viewModel = viewModel(repository)
		viewModel.load()
		viewModel.loadFailed shouldBe true
		viewModel.load()
		viewModel.loadFailed shouldBe false
		viewModel.items.shouldContainExactly(settings())
		repository.loadCount shouldBe 2
	}

	@Test
	fun `failed save retains edits and retry saves current settings`() = runTest(dispatcher) {
		val repository = FakeTrackerNotificationSettingsRepository(settings()).apply { failNextSave = true }
		val viewModel = viewModel(repository)
		viewModel.load()
		viewModel.moveItemAndPersist(0, 1)
		viewModel.saveFailed shouldBe true
		viewModel.items.map(TrackerNotificationSetting::id)
			.shouldContainExactly("altitude", "activity", "battery")
		viewModel.retrySave()
		viewModel.saveFailed shouldBe false
		repository.saved.single().shouldContainExactly(viewModel.items)
	}

	@Test
	fun `rapid edits cannot let an older save overwrite the latest choice`() = runTest(dispatcher) {
		val firstSave = CompletableDeferred<Unit>()
		val repository = FakeTrackerNotificationSettingsRepository(settings()).apply { firstSaveGate = firstSave }
		val viewModel = viewModel(repository)
		viewModel.load()
		viewModel.moveItemAndPersist(0, 1)
		viewModel.moveItemAndPersist(1, 2)
		firstSave.complete(Unit)
		viewModel.items.map(TrackerNotificationSetting::id)
			.shouldContainExactly("altitude", "battery", "activity")
		repository.saved.size shouldBe 2
		repository.saved.last().shouldContainExactly(viewModel.items)
	}

	@Test
	fun `an older failed save does not prompt when the latest queued choices save`() = runTest(dispatcher) {
		val firstSave = CompletableDeferred<Unit>()
		val repository = FakeTrackerNotificationSettingsRepository(settings()).apply {
			firstSaveGate = firstSave
			failNextSave = true
		}
		val viewModel = viewModel(repository)
		var exits = 0
		viewModel.load()
		viewModel.moveItemAndPersist(0, 1)
		viewModel.moveItemAndPersist(1, 2)
		viewModel.exitWhenSaved { exits += 1 }
		firstSave.complete(Unit)
		exits shouldBe 1
		viewModel.unsavedExitRequested shouldBe false
		viewModel.saveFailed shouldBe false
		repository.saved.single().shouldContainExactly(viewModel.items)
	}

	@Test
	fun `Back waits for the latest pending save before leaving`() = runTest(dispatcher) {
		val firstSave = CompletableDeferred<Unit>()
		val repository = FakeTrackerNotificationSettingsRepository(settings()).apply { firstSaveGate = firstSave }
		val viewModel = viewModel(repository)
		var exits = 0
		viewModel.load()
		viewModel.moveItemAndPersist(0, 1)
		viewModel.exitWhenSaved { exits += 1 }
		exits shouldBe 0
		viewModel.unsavedExitRequested shouldBe false
		firstSave.complete(Unit)
		exits shouldBe 1
		viewModel.unsavedExitRequested shouldBe false
		repository.saved.single().shouldContainExactly(viewModel.items)
	}

	@Test
	fun `failed pending save keeps the screen open with retry available`() = runTest(dispatcher) {
		val firstSave = CompletableDeferred<Unit>()
		val repository = FakeTrackerNotificationSettingsRepository(settings()).apply {
			firstSaveGate = firstSave
			failNextSave = true
		}
		val viewModel = viewModel(repository)
		var exits = 0
		viewModel.load()
		viewModel.moveItemAndPersist(0, 1)
		viewModel.exitWhenSaved { exits += 1 }
		firstSave.complete(Unit)
		exits shouldBe 0
		viewModel.saveFailed shouldBe true
		viewModel.unsavedExitRequested shouldBe true
		viewModel.retrySave()
		viewModel.saveFailed shouldBe false
		repository.saved.single().shouldContainExactly(viewModel.items)
	}

	@Test
	fun `failed save prompts on Back and Retry saves edits before leaving`() = runTest(dispatcher) {
		val repository = FakeTrackerNotificationSettingsRepository(settings()).apply { failNextSave = true }
		val viewModel = viewModel(repository)
		var exits = 0
		viewModel.load()
		viewModel.moveItemAndPersist(0, 2)
		viewModel.unsavedExitRequested shouldBe false
		viewModel.exitWhenSaved { exits += 1 }
		viewModel.unsavedExitRequested shouldBe true
		exits shouldBe 0
		viewModel.retrySaveAndExit { exits += 1 }
		exits shouldBe 1
		viewModel.unsavedExitRequested shouldBe false
		repository.saved.single().shouldContainExactly(viewModel.items)
	}

	@Test
	fun `Leave without saving requires a failed save and keeps edits until chosen`() = runTest(dispatcher) {
		val repository = FakeTrackerNotificationSettingsRepository(settings()).apply { failNextSave = true }
		val viewModel = viewModel(repository)
		var exits = 0
		viewModel.load()
		viewModel.moveItemAndPersist(0, 2)
		viewModel.leaveWithoutSaving { exits += 1 }
		exits shouldBe 0
		viewModel.exitWhenSaved { exits += 1 }
		viewModel.items.map(TrackerNotificationSetting::id)
			.shouldContainExactly("altitude", "battery", "activity")
		viewModel.leaveWithoutSaving { exits += 1 }
		exits shouldBe 1
		viewModel.unsavedExitRequested shouldBe false
		repository.saved shouldBe emptyList()
	}

	@Test
	fun `dismissing failed-save exit keeps edits and a repeated failure asks again`() = runTest(dispatcher) {
		val repository = FakeTrackerNotificationSettingsRepository(settings()).apply { failNextSave = true }
		val viewModel = viewModel(repository)
		var exits = 0
		viewModel.load()
		viewModel.updateFlags("activity", inTitle = true, inContent = true)
		viewModel.exitWhenSaved { exits += 1 }
		viewModel.dismissUnsavedExit()
		viewModel.unsavedExitRequested shouldBe false
		viewModel.items.first().isInTitle shouldBe true
		viewModel.exitWhenSaved { exits += 1 }
		repository.failNextSave = true
		viewModel.retrySaveAndExit { exits += 1 }
		viewModel.unsavedExitRequested shouldBe true
		exits shouldBe 0
	}

	@Test
	fun `Back saves an unreleased drag and ignores its later cancellation save`() = runTest(dispatcher) {
		val firstSave = CompletableDeferred<Unit>()
		val repository = FakeTrackerNotificationSettingsRepository(settings()).apply { firstSaveGate = firstSave }
		val viewModel = viewModel(repository)
		var exits = 0
		viewModel.load()
		viewModel.setDragging(0)
		viewModel.moveItem(0, 1)
		viewModel.moveItem(1, 2)
		viewModel.exitWhenSaved { exits += 1 }
		exits shouldBe 0
		viewModel.draggingIndex shouldBe null
		repository.saved shouldBe emptyList()
		// The route's drag-cancel callback can follow Back. It must not queue an identical edit.
		viewModel.setDragging(null)
		viewModel.persistOrder()
		firstSave.complete(Unit)
		exits shouldBe 1
		repository.saved.single().map(TrackerNotificationSetting::id)
			.shouldContainExactly("altitude", "battery", "activity")
	}

	@Test
	fun `an unreleased drag that fails on Back keeps its final order for Retry`() = runTest(dispatcher) {
		val repository = FakeTrackerNotificationSettingsRepository(settings()).apply { failNextSave = true }
		val viewModel = viewModel(repository)
		var exits = 0
		viewModel.load()
		viewModel.setDragging(0)
		viewModel.moveItem(0, 2)
		viewModel.exitWhenSaved { exits += 1 }
		exits shouldBe 0
		viewModel.saveFailed shouldBe true
		viewModel.unsavedExitRequested shouldBe true
		viewModel.items.map(TrackerNotificationSetting::id)
			.shouldContainExactly("altitude", "battery", "activity")
		viewModel.retrySaveAndExit { exits += 1 }
		exits shouldBe 1
		viewModel.unsavedExitRequested shouldBe false
		repository.saved.single().shouldContainExactly(viewModel.items)
	}

	@Test
	fun `a new drag while Back waits must save before the screen exits`() = runTest(dispatcher) {
		val firstSave = CompletableDeferred<Unit>()
		val repository = FakeTrackerNotificationSettingsRepository(settings()).apply { firstSaveGate = firstSave }
		val viewModel = viewModel(repository)
		var exits = 0
		viewModel.load()
		viewModel.moveItemAndPersist(0, 1)
		viewModel.exitWhenSaved { exits += 1 }
		// The screen is still editable while its first save is suspended.
		viewModel.setDragging(1)
		viewModel.moveItem(1, 2)
		firstSave.complete(Unit)
		exits shouldBe 1
		viewModel.draggingIndex shouldBe null
		repository.saved.size shouldBe 2
		repository.saved.first().map(TrackerNotificationSetting::id)
			.shouldContainExactly("altitude", "activity", "battery")
		repository.saved.last().map(TrackerNotificationSetting::id)
			.shouldContainExactly("altitude", "battery", "activity")
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
	var failNextLoad = false
	var failNextSave = false
	var firstSaveGate: CompletableDeferred<Unit>? = null

	override suspend fun loadSettings(): List<TrackerNotificationSetting> {
		loadCount += 1
		if (failNextLoad) {
			failNextLoad = false
			error("load unavailable")
		}
		return loaded
	}

	override suspend fun saveSettings(
		settingsInDisplayOrder: List<TrackerNotificationSetting>,
	) {
		firstSaveGate?.let { gate ->
			firstSaveGate = null
			gate.await()
		}
		if (failNextSave) {
			failNextSave = false
			error("save unavailable")
		}
		saved += settingsInDisplayOrder
	}
}
