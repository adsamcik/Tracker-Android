package com.adsamcik.tracker.dashboard.data

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("DashboardWidget")
class DashboardWidgetTest {

	@Nested
	@DisplayName("Widget instances")
	inner class WidgetInstances {

		@Test
		fun `TodayProgress has correct id`() {
			DashboardWidget.TodayProgress.id shouldBe "today_progress"
		}

		@Test
		fun `Streak has correct id`() {
			DashboardWidget.Streak.id shouldBe "streak"
		}

		@Test
		fun `Challenges has correct id`() {
			DashboardWidget.Challenges.id shouldBe "challenges"
		}

		@Test
		fun `LastSession has correct id`() {
			DashboardWidget.LastSession.id shouldBe "last_session"
		}

		@Test
		fun `RecentTrips has correct id`() {
			DashboardWidget.RecentTrips.id shouldBe "recent_trips"
		}

		@Test
		fun `Exploration has correct id`() {
			DashboardWidget.Exploration.id shouldBe "exploration"
		}
	}

	@Nested
	@DisplayName("Default priority ordering")
	inner class DefaultPriority {

		@Test
		fun `TodayProgress has lowest priority value`() {
			DashboardWidget.TodayProgress.defaultPriority shouldBe 0
		}

		@Test
		fun `priorities increase monotonically`() {
			val widgets = DashboardWidget.all
			for (i in 0 until widgets.size - 1) {
				(widgets[i].defaultPriority < widgets[i + 1].defaultPriority) shouldBe true
			}
		}

		@Test
		fun `Exploration has highest priority value`() {
			DashboardWidget.Exploration.defaultPriority shouldBe 50
		}
	}

	@Nested
	@DisplayName("Title resources")
	inner class TitleResources {

		@Test
		fun `all widgets have non-zero titleRes`() {
			DashboardWidget.all.forEach { widget ->
				(widget.titleRes != 0) shouldBe true
			}
		}

		@Test
		fun `all widgets have unique titleRes`() {
			val titleResSet = DashboardWidget.all.map { it.titleRes }.toSet()
			titleResSet shouldHaveSize DashboardWidget.all.size
		}
	}

	@Nested
	@DisplayName("Companion object")
	inner class CompanionTests {

		@Test
		fun `all contains six widgets`() {
			DashboardWidget.all shouldHaveSize 6
		}

		@Test
		fun `all widgets have unique ids`() {
			val ids = DashboardWidget.all.map { it.id }.toSet()
			ids shouldHaveSize 6
		}

		@Test
		fun `fromId returns correct widget`() {
			DashboardWidget.fromId("streak").shouldNotBeNull()
			DashboardWidget.fromId("streak") shouldBe DashboardWidget.Streak
		}

		@Test
		fun `fromId returns null for unknown id`() {
			DashboardWidget.fromId("nonexistent").shouldBeNull()
		}

		@Test
		fun `fromId returns null for empty id`() {
			DashboardWidget.fromId("").shouldBeNull()
		}

		@Test
		fun `fromId works for all known widgets`() {
			DashboardWidget.all.forEach { widget ->
				DashboardWidget.fromId(widget.id) shouldBe widget
			}
		}

		@Test
		fun `defaultOrder contains all widget ids`() {
			DashboardWidget.defaultOrder shouldHaveSize 6
			DashboardWidget.defaultOrder shouldContainAll DashboardWidget.all.map { it.id }
		}

		@Test
		fun `defaultOrder matches all list ordering`() {
			DashboardWidget.defaultOrder shouldBe DashboardWidget.all.map { it.id }
		}
	}

	@Nested
	@DisplayName("Sealed interface exhaustiveness")
	inner class SealedInterface {

		@Test
		fun `all known types covered`() {
			DashboardWidget.all.forEach { widget ->
				when (widget) {
					DashboardWidget.TodayProgress -> widget.id shouldBe "today_progress"
					DashboardWidget.Streak -> widget.id shouldBe "streak"
					DashboardWidget.Challenges -> widget.id shouldBe "challenges"
					DashboardWidget.LastSession -> widget.id shouldBe "last_session"
					DashboardWidget.RecentTrips -> widget.id shouldBe "recent_trips"
					DashboardWidget.Exploration -> widget.id shouldBe "exploration"
				}
			}
		}

		@Test
		fun `different widgets are not equal`() {
			DashboardWidget.TodayProgress shouldNotBe DashboardWidget.Streak
			DashboardWidget.Challenges shouldNotBe DashboardWidget.LastSession
		}
	}
}
