package com.adsamcik.tracker.tracker.source.ambient.steps

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

class AmbientStepsStructuralWindowPlannerTest {
	private val prague = ZoneId.of("Europe/Prague")

	@Test
	fun `splits a range into exact immutable civil-day windows`() {
		val firstDay = LocalDate.of(2026, 2, 4)
		val from = firstDay.atTime(12, 30).atZone(prague).toInstant().toEpochMilli()
		val through = firstDay.plusDays(2L).atTime(6, 15).atZone(prague).toInstant().toEpochMilli()

		val plan = AmbientStepsStructuralWindowPlanner().plan(from, through, prague)

		plan.deferredFromTimeMs shouldBe null
		plan.windows.map { it.day.epochDay } shouldContainExactly listOf(
			firstDay.toEpochDay(),
			firstDay.plusDays(1L).toEpochDay(),
			firstDay.plusDays(2L).toEpochDay(),
		)
		plan.windows.first().startTimeMs shouldBe from
		plan.windows.last().endTimeMs shouldBe through
		plan.windows.zipWithNext().all { (left, right) ->
			left.endTimeMs == right.startTimeMs
		} shouldBe true
	}

	@Test
	fun `preserves spring-forward and fall-back structural bounds`() {
		val spring = LocalDate.of(2026, 3, 29)
		val fall = LocalDate.of(2026, 10, 25)

		val springWindow = fullDay(spring)
		val fallWindow = fullDay(fall)

		springWindow.endTimeMs - springWindow.startTimeMs shouldBe 23L * HOUR_MILLIS
		fallWindow.endTimeMs - fallWindow.startTimeMs shouldBe 25L * HOUR_MILLIS
		springWindow.completesStructuralDay shouldBe true
		fallWindow.completesStructuralDay shouldBe true
	}

	@Test
	fun `current partial day remains covered only through the observed boundary`() {
		val day = LocalDate.of(2026, 6, 8)
		val start = day.atStartOfDay(prague).toInstant().toEpochMilli()
		val through = day.atTime(9, 45).atZone(prague).toInstant().toEpochMilli()

		val window = AmbientStepsStructuralWindowPlanner().plan(start, through, prague).windows.single()

		window.startTimeMs shouldBe start
		window.endTimeMs shouldBe through
		window.completesStructuralDay shouldBe false
		window.providerWindow.endTimeMs shouldBe through
	}

	@Test
	fun `bounded pass surfaces the first deferred instant`() {
		val firstDay = LocalDate.of(2026, 1, 1)
		val from = firstDay.atStartOfDay(prague).toInstant().toEpochMilli()
		val through = firstDay.plusDays(4L).atStartOfDay(prague).toInstant().toEpochMilli()

		val plan = AmbientStepsStructuralWindowPlanner(maximumWindowsPerPass = 2)
			.plan(from, through, prague)

		plan.windows.size shouldBe 2
		plan.deferredFromTimeMs shouldBe
			firstDay.plusDays(2L).atStartOfDay(prague).toInstant().toEpochMilli()
	}

	@Test
	fun `progressive plan extends the current structural window from stable segment identity`() {
		val day = LocalDate.of(2026, 6, 8)
		val segmentStart = day.atTime(2, 0).atZone(prague).toInstant().toEpochMilli()
		val importedThrough = day.atTime(8, 0).atZone(prague).toInstant().toEpochMilli()
		val through = day.atTime(9, 45).atZone(prague).toInstant().toEpochMilli()

		val window = AmbientStepsStructuralWindowPlanner().planProgressive(
			segmentStartTimeMs = segmentStart,
			importedThroughTimeMs = importedThrough,
			throughTimeMs = through,
			zoneId = prague,
		).windows.single()

		window.startTimeMs shouldBe segmentStart
		window.endTimeMs shouldBe through
	}

	@Test
	fun `same high water replays the same non-empty structural window`() {
		val day = LocalDate.of(2026, 6, 8)
		val segmentStart = day.atTime(2, 0).atZone(prague).toInstant().toEpochMilli()
		val importedThrough = day.atTime(8, 0).atZone(prague).toInstant().toEpochMilli()

		val window = AmbientStepsStructuralWindowPlanner().planProgressive(
			segmentStartTimeMs = segmentStart,
			importedThroughTimeMs = importedThrough,
			throughTimeMs = importedThrough,
			zoneId = prague,
		).windows.single()

		window.startTimeMs shouldBe segmentStart
		window.endTimeMs shouldBe importedThrough
	}

	@Test
	fun `advancing after a completed day starts a new structural fact`() {
		val day = LocalDate.of(2026, 6, 8)
		val segmentStart = day.atTime(2, 0).atZone(prague).toInstant().toEpochMilli()
		val nextDayStart = day.plusDays(1L).atStartOfDay(prague).toInstant().toEpochMilli()
		val through = day.plusDays(1L).atTime(1, 0).atZone(prague).toInstant().toEpochMilli()

		val window = AmbientStepsStructuralWindowPlanner().planProgressive(
			segmentStartTimeMs = segmentStart,
			importedThroughTimeMs = nextDayStart,
			throughTimeMs = through,
			zoneId = prague,
		).windows.single()

		window.startTimeMs shouldBe nextDayStart
		window.endTimeMs shouldBe through
	}

	@Test
	fun `rejects sub-second cursor boundaries`() {
		shouldThrow<IllegalArgumentException> {
			AmbientStepsStructuralWindowPlanner().plan(1L, 1_000L, prague)
		}
	}

	private fun fullDay(date: LocalDate): AmbientStepsStructuralWindow {
		val start = date.atStartOfDay(prague).toInstant().toEpochMilli()
		val end = date.plusDays(1L).atStartOfDay(prague).toInstant().toEpochMilli()
		return AmbientStepsStructuralWindowPlanner().plan(start, end, prague).windows.single()
	}

	private companion object {
		const val HOUR_MILLIS = 60L * 60L * 1_000L
	}
}
