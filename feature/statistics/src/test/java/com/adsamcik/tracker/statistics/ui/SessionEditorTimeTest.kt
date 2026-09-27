package com.adsamcik.tracker.statistics.ui

import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.jupiter.api.Test

class SessionEditorTimeTest {
	@Test fun `spring gap never silently normalizes`() {
		resolveSessionEditorTime("2026-03-29", "02:30", "Europe/Prague", null) shouldBe SessionEditorTimeResult.Nonexistent
	}
	@Test fun `autumn overlap requires an explicit choice and yields distinct instants`() {
		val choice = resolveSessionEditorTime("2026-10-25", "02:30", "Europe/Prague", null) as SessionEditorTimeResult.ChooseOffset
		choice.offsets.size shouldBe 2
		val early = resolveSessionEditorTime("2026-10-25", "02:30", "Europe/Prague", "+02:00") as SessionEditorTimeResult.Exact
		val late = resolveSessionEditorTime("2026-10-25", "02:30", "Europe/Prague", "+01:00") as SessionEditorTimeResult.Exact
		late.epochMs - early.epochMs shouldBe 3_600_000L
	}
	@Test fun `seconds and milliseconds remain exact while malformed drafts are rejected`() {
		resolveSessionEditorTime("2026-09-27", "10:12:13.456", "UTC", "Z") shouldBe
			SessionEditorTimeResult.Exact(Instant.parse("2026-09-27T10:12:13.456Z").toEpochMilli())
		resolveSessionEditorTime("2026-02-30", "12:00", "UTC", null) shouldBe SessionEditorTimeResult.Invalid
		resolveSessionEditorTime("2026-09-27", "25:00", "UTC", null) shouldBe SessionEditorTimeResult.Invalid
	}
}
