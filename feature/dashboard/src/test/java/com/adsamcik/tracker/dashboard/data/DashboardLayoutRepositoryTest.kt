package com.adsamcik.tracker.dashboard.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class DashboardLayoutRepositoryTest {

	private lateinit var repo: DashboardLayoutRepository

	@BeforeEach
	fun setup() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		repo = DashboardLayoutRepository(context)
		// Reset to defaults for test isolation
		repo.resetToDefault()
	}

	@Nested
	@DisplayName("Default layout")
	inner class Defaults {
		@Test
		@DisplayName("default layout has default widget order")
		fun `default layout has default order`() = runTest {
			val layout = repo.layout.first()
			layout.widgetOrder shouldBe DashboardWidget.defaultOrder
		}

		@Test
		@DisplayName("default layout has no hidden widgets")
		fun `default layout has no hidden widgets`() = runTest {
			val layout = repo.layout.first()
			layout.hiddenWidgets shouldBe emptySet()
		}
	}

	@Nested
	@DisplayName("reorder")
	inner class Reorder {
		@Test
		@DisplayName("reorder persists custom order")
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
		@DisplayName("reorder with partial list appends missing widgets")
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
		@DisplayName("reorder filters out unknown widget IDs")
		fun `reorder filters out unknown ids`() = runTest {
			val orderWithUnknown = listOf(
				"unknown_widget",
				"today_progress",
				"streak",
				"challenges",
				"last_session",
				"recent_trips",
				"exploration",
			)
			repo.reorder(orderWithUnknown)

			val layout = repo.layout.first()
			layout.widgetOrder shouldNotContain "unknown_widget"
			layout.widgetOrder shouldHaveSize 6
		}
	}

	@Nested
	@DisplayName("toggleVisibility")
	inner class ToggleVisibility {
		@Test
		@DisplayName("toggling a widget hides it")
		fun `toggle hides widget`() = runTest {
			repo.toggleVisibility("streak")

			val layout = repo.layout.first()
			layout.hiddenWidgets shouldContain "streak"
		}

		@Test
		@DisplayName("toggling a hidden widget shows it")
		fun `toggle shows hidden widget`() = runTest {
			repo.toggleVisibility("streak")
			repo.toggleVisibility("streak")

			val layout = repo.layout.first()
			layout.hiddenWidgets shouldNotContain "streak"
		}

		@Test
		@DisplayName("toggling multiple widgets independently")
		fun `toggle multiple widgets`() = runTest {
			repo.toggleVisibility("streak")
			repo.toggleVisibility("exploration")

			val layout = repo.layout.first()
			layout.hiddenWidgets shouldContain "streak"
			layout.hiddenWidgets shouldContain "exploration"
			layout.hiddenWidgets shouldHaveSize 2
		}
	}

	@Nested
	@DisplayName("resetToDefault")
	inner class ResetToDefault {
		@Test
		@DisplayName("reset clears custom order and hidden widgets")
		fun `reset clears customizations`() = runTest {
			repo.reorder(listOf("exploration", "streak", "today_progress", "challenges", "recent_trips", "last_session"))
			repo.toggleVisibility("streak")

			repo.resetToDefault()

			val layout = repo.layout.first()
			layout.widgetOrder shouldBe DashboardWidget.defaultOrder
			layout.hiddenWidgets shouldBe emptySet()
		}
	}

	@Nested
	@DisplayName("Serialization")
	inner class Serialization {
		@Test
		@DisplayName("order round-trips through serialization")
		fun `order round trips`() {
			val ids = listOf("a", "b", "c")
			val serialized = DashboardLayoutRepository.serializeOrder(ids)
			val deserialized = DashboardLayoutRepository.deserializeOrder(serialized)
			deserialized shouldBe ids
		}

		@Test
		@DisplayName("empty list serializes correctly")
		fun `empty list serializes`() {
			val serialized = DashboardLayoutRepository.serializeOrder(emptyList())
			val deserialized = DashboardLayoutRepository.deserializeOrder(serialized)
			deserialized shouldBe emptyList()
		}
	}
}
