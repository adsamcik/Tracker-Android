package com.adsamcik.tracker.dashboard.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.dashboard.ui.compose.state.ChallengeUiModel
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.ExplorationUiState
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
class DashboardWidgetRegistryTest {

	private lateinit var registry: DashboardWidgetRegistry

	@BeforeEach
	fun setup() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		registry = DashboardWidgetRegistry(DashboardLayoutRepository(context))
	}

	private val fullState = DashboardUiState(
		dashboardMode = DashboardMode.IDLE,
		sessionData = TrackerSession(
			id = 1L,
			start = System.currentTimeMillis() - 3600_000,
			end = System.currentTimeMillis(),
			isUserInitiated = true,
			collections = 100,
			distanceInM = 5000f,
			steps = 8000,
		),
		activeChallenges = listOf(
			ChallengeUiModel(
				id = 1L,
				title = "Walk 10km",
				description = "Walk 10 kilometers",
				progress = 0.5f,
				iconResName = "",
				difficulty = "Medium",
				timeRemainingMs = 86_400_000L,
				rewardPoints = 100,
			),
		),
		explorationState = ExplorationUiState(
			newCellsToday = 5,
			totalCells = 100,
			seasonsCovered = 2,
			hasExplorationData = true,
		),
	)

	private val minimalState = DashboardUiState(
		dashboardMode = DashboardMode.IDLE,
		sessionData = null,
		activeChallenges = emptyList(),
		explorationState = ExplorationUiState(hasExplorationData = false),
	)

	private val defaultLayout = DashboardLayout()

	@Nested
	@DisplayName("resolveVisibleWidgets")
	inner class ResolveVisible {

		@Test
		@DisplayName("full state with default layout returns all 6 widgets")
		fun `full state returns all widgets`() {
			val result = registry.resolveWidgets(fullState, defaultLayout)
			result shouldHaveSize 6
		}

		@Test
		@DisplayName("minimal state hides conditional widgets")
		fun `minimal state hides conditional widgets`() {
			val result = registry.resolveWidgets(minimalState, defaultLayout)
			val ids = result.map { it.id }
			ids shouldNotContain "challenges"
			ids shouldNotContain "last_session"
			ids shouldNotContain "exploration"
			ids shouldContain "today_progress"
			ids shouldContain "streak"
			ids shouldContain "recent_trips"
			result shouldHaveSize 3
		}

		@Test
		@DisplayName("default layout preserves default order")
		fun `default layout preserves default order`() {
			val result = registry.resolveWidgets(fullState, defaultLayout)
			val ids = result.map { it.id }
			ids shouldBe listOf(
				"today_progress",
				"streak",
				"challenges",
				"last_session",
				"recent_trips",
				"exploration",
			)
		}

		@Test
		@DisplayName("custom order is respected")
		fun `custom order is respected`() {
			val layout = DashboardLayout(
				widgetOrder = listOf(
					"exploration",
					"streak",
					"today_progress",
					"challenges",
					"recent_trips",
					"last_session",
				),
			)
			val result = registry.resolveWidgets(fullState, layout)
			result.first().id shouldBe "exploration"
			result[1].id shouldBe "streak"
			result[2].id shouldBe "today_progress"
		}

		@Test
		@DisplayName("hidden widgets are excluded")
		fun `hidden widgets are excluded`() {
			val layout = DashboardLayout(
				hiddenWidgets = setOf("streak", "exploration"),
			)
			val result = registry.resolveWidgets(fullState, layout)
			val ids = result.map { it.id }
			ids shouldNotContain "streak"
			ids shouldNotContain "exploration"
			ids shouldContain "today_progress"
		}

		@Test
		@DisplayName("all widgets hidden returns empty")
		fun `all widgets hidden returns empty`() {
			val layout = DashboardLayout(
				hiddenWidgets = DashboardWidget.all.map { it.id }.toSet(),
			)
			val result = registry.resolveWidgets(fullState, layout)
			result.shouldBeEmpty()
		}

		@Test
		@DisplayName("unknown widget IDs in order are ignored")
		fun `unknown widget IDs in order are ignored`() {
			val layout = DashboardLayout(
				widgetOrder = listOf("unknown_widget", "today_progress", "streak"),
			)
			val result = registry.resolveWidgets(minimalState, layout)
			val ids = result.map { it.id }
			ids shouldNotContain "unknown_widget"
			ids shouldContain "today_progress"
		}
	}

	@Nested
	@DisplayName("resolveAllWidgets")
	inner class ResolveAll {

		@Test
		@DisplayName("returns all widgets regardless of state availability")
		fun `returns all widgets`() {
			val result = registry.resolveAllWidgets(defaultLayout)
			result shouldHaveSize 6
		}

		@Test
		@DisplayName("marks hidden widgets correctly")
		fun `marks hidden widgets correctly`() {
			val layout = DashboardLayout(
				hiddenWidgets = setOf("streak"),
			)
			val result = registry.resolveAllWidgets(layout)
			val streakEntry = result.find { it.widget.id == "streak" }
			streakEntry?.visible shouldBe false

			val todayEntry = result.find { it.widget.id == "today_progress" }
			todayEntry?.visible shouldBe true
		}

		@Test
		@DisplayName("preserves custom order")
		fun `preserves custom order`() {
			val layout = DashboardLayout(
				widgetOrder = listOf(
					"recent_trips",
					"today_progress",
					"streak",
					"challenges",
					"last_session",
					"exploration",
				),
			)
			val result = registry.resolveAllWidgets(layout)
			result.first().widget.id shouldBe "recent_trips"
		}
	}

	@Nested
	@DisplayName("isAvailable - conditional inclusion")
	inner class IsAvailable {

		@Test
		@DisplayName("Challenges unavailable when activeChallenges is empty")
		fun `challenges unavailable when empty`() {
			val state = fullState.copy(activeChallenges = emptyList())
			registry.isAvailable(DashboardWidget.Challenges, state) shouldBe false
		}

		@Test
		@DisplayName("Challenges available when activeChallenges is not empty")
		fun `challenges available when present`() {
			registry.isAvailable(DashboardWidget.Challenges, fullState) shouldBe true
		}

		@Test
		@DisplayName("LastSession unavailable when sessionData is null")
		fun `last session unavailable when null`() {
			val state = fullState.copy(sessionData = null)
			registry.isAvailable(DashboardWidget.LastSession, state) shouldBe false
		}

		@Test
		@DisplayName("LastSession available when sessionData exists")
		fun `last session available when present`() {
			registry.isAvailable(DashboardWidget.LastSession, fullState) shouldBe true
		}

		@Test
		@DisplayName("Exploration unavailable when hasExplorationData is false")
		fun `exploration unavailable when no data`() {
			val state = fullState.copy(
				explorationState = ExplorationUiState(hasExplorationData = false),
			)
			registry.isAvailable(DashboardWidget.Exploration, state) shouldBe false
		}

		@Test
		@DisplayName("TodayProgress always available")
		fun `today progress always available`() {
			registry.isAvailable(DashboardWidget.TodayProgress, minimalState) shouldBe true
		}

		@Test
		@DisplayName("Streak always available")
		fun `streak always available`() {
			registry.isAvailable(DashboardWidget.Streak, minimalState) shouldBe true
		}

		@Test
		@DisplayName("RecentTrips always available")
		fun `recent trips always available`() {
			registry.isAvailable(DashboardWidget.RecentTrips, minimalState) shouldBe true
		}
	}

	@Nested
	@DisplayName("DashboardWidget companion")
	inner class WidgetCompanion {

		@Test
		@DisplayName("all widgets have unique IDs")
		fun `all widgets have unique ids`() {
			val ids = DashboardWidget.all.map { it.id }
			ids.size shouldBe ids.toSet().size
		}

		@Test
		@DisplayName("all widgets have unique priorities")
		fun `all widgets have unique priorities`() {
			val priorities = DashboardWidget.all.map { it.defaultPriority }
			priorities.size shouldBe priorities.toSet().size
		}

		@Test
		@DisplayName("fromId returns correct widget")
		fun `fromId returns correct widget`() {
			DashboardWidget.fromId("today_progress") shouldBe DashboardWidget.TodayProgress
			DashboardWidget.fromId("streak") shouldBe DashboardWidget.Streak
			DashboardWidget.fromId("challenges") shouldBe DashboardWidget.Challenges
			DashboardWidget.fromId("last_session") shouldBe DashboardWidget.LastSession
			DashboardWidget.fromId("recent_trips") shouldBe DashboardWidget.RecentTrips
			DashboardWidget.fromId("exploration") shouldBe DashboardWidget.Exploration
		}

		@Test
		@DisplayName("fromId returns null for unknown ID")
		fun `fromId returns null for unknown`() {
			DashboardWidget.fromId("nonexistent") shouldBe null
		}

		@Test
		@DisplayName("defaultOrder matches all widget IDs")
		fun `defaultOrder matches all`() {
			DashboardWidget.defaultOrder shouldBe DashboardWidget.all.map { it.id }
		}

		@Test
		@DisplayName("all contains exactly 6 widgets")
		fun `all contains six widgets`() {
			DashboardWidget.all shouldHaveSize 6
		}
	}
}
