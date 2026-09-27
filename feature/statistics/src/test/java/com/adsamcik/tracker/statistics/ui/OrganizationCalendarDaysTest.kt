package com.adsamcik.tracker.statistics.ui

import com.adsamcik.tracker.stats.api.repository.OrganizationSessionId
import com.adsamcik.tracker.stats.api.repository.OrganizedSession
import com.adsamcik.tracker.stats.api.repository.SessionOrganizationRead
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.jupiter.api.Test

class OrganizationCalendarDaysTest {
	@Test fun `cross-midnight group appears on overlap days but not its exclusive end day`() {
		val zone = ZoneId.of("Europe/Prague")
		fun time(day: Int) = LocalDate.of(2026, 3, day).atStartOfDay(zone).toInstant().toEpochMilli()
		val session = OrganizedSession(OrganizationSessionId.Annotation("local"), 1, 0, null, null, null, zone.id,
			time(28) + 12 * 3_600_000L, time(30), false, false)
		organizationCalendarDays(SessionOrganizationRead.Content(listOf(session), false, 0), YearMonth.of(2026, 3), zone) shouldBe
			setOf(LocalDate.of(2026, 3, 28), LocalDate.of(2026, 3, 29))
	}
	@Test fun `unavailable metadata produces no invented recording marker`() {
		organizationCalendarDays(SessionOrganizationRead.Unavailable, YearMonth.of(2026, 9)) shouldBe emptySet()
	}
}
