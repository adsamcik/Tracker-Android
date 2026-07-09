package com.adsamcik.tracker.dashboard.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardLayoutRepositoryTest {

	private lateinit var repo: DashboardLayoutRepository

	@Before
	fun setup() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		repo = DashboardLayoutRepository(context)
		// Reset to defaults for test isolation
		repo.resetToDefault()
	}

	// region Default layout
	@Test
	fun `default layout has default order`() = runTest {
		val layout = repo.layout.first()
		layout.widgetOrder shouldBe DashboardWidget.defaultOrder
	}

	@Test
	fun `default layout has no hidden widgets`() = runTest {
		val layout = repo.layout.first()
		layout.hiddenWidgets shouldBe emptySet()
	}
	// endregion

	// region reorder
	@Test
	fun `reorder persists custom order`() = runTest {
		val customOrder = listOf(
			"exploration",
			"streak",
			"today_progress",
			"latest_achievement",
			"recent_trips",
			"last_session",
		)
		repo.reorder(customOrder)

		val layout = repo.layout.first()
		layout.widgetOrder shouldBe customOrder
	}

	@Test
	fun `reorder with partial list appends missing`() = runTest {
		val partialOrder = listOf("streak", "today_progress")
		repo.reorder(partialOrder)

		val layout = repo.layout.first()
		// First two should match our order
		layout.widgetOrder[0] shouldBe "streak"
		layout.widgetOrder[1] shouldBe "today_progress"
		// All 6 widgets should be present
		layout.widgetOrder shouldHaveSize 6
	}

	@Test
	fun `reorder filters out unknown ids`() = runTest {
		val orderWithUnknown = listOf(
			"unknown_widget",
			"today_progress",
			"streak",
			"last_session",
			"recent_trips",
			"exploration",
		)
		repo.reorder(orderWithUnknown)

		val layout = repo.layout.first()
		layout.widgetOrder shouldNotContain "unknown_widget"
		layout.widgetOrder shouldHaveSize 6
	}
	// endregion

	// region toggleVisibility
	@Test
	fun `toggle hides widget`() = runTest {
		repo.toggleVisibility("streak")

		val layout = repo.layout.first()
		layout.hiddenWidgets shouldContain "streak"
	}

	@Test
	fun `toggle shows hidden widget`() = runTest {
		repo.toggleVisibility("streak")
		repo.toggleVisibility("streak")

		val layout = repo.layout.first()
		layout.hiddenWidgets shouldNotContain "streak"
	}

	@Test
	fun `toggle multiple widgets`() = runTest {
		repo.toggleVisibility("streak")
		repo.toggleVisibility("exploration")

		val layout = repo.layout.first()
		layout.hiddenWidgets shouldContain "streak"
		layout.hiddenWidgets shouldContain "exploration"
		layout.hiddenWidgets shouldHaveSize 2
	}
	// endregion

	// region resetToDefault
	@Test
	fun `reset clears customizations`() = runTest {
		repo.reorder(listOf("exploration", "streak", "today_progress", "latest_achievement", "recent_trips", "last_session"))
		repo.toggleVisibility("streak")

		repo.resetToDefault()

		val layout = repo.layout.first()
		layout.widgetOrder shouldBe DashboardWidget.defaultOrder
		layout.hiddenWidgets shouldBe emptySet()
	}
	// endregion

	// region Serialization
	@Test
	fun `order round trips`() {
		val ids = listOf("a", "b", "c")
		val serialized = DashboardLayoutRepository.serializeOrder(ids)
		val deserialized = DashboardLayoutRepository.deserializeOrder(serialized)
		deserialized shouldBe ids
	}

	@Test
	fun `empty list serializes`() {
		val serialized = DashboardLayoutRepository.serializeOrder(emptyList())
		val deserialized = DashboardLayoutRepository.deserializeOrder(serialized)
		deserialized shouldBe emptyList()
	}
	// endregion
}
