package com.adsamcik.tracker.statistics.viewmodel

import com.adsamcik.tracker.stats.api.repository.CellSignalReport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CellSignalReportLoadState")
class CellSignalReportLoadStateTest {

	@Nested
	@DisplayName("State variants")
	inner class StateVariants {

		@Test
		fun `Idle is a singleton`() {
			val state: CellSignalReportLoadState = CellSignalReportLoadState.Idle
			state.shouldBeInstanceOf<CellSignalReportLoadState.Idle>()
		}

		@Test
		fun `Loading is a singleton`() {
			val state: CellSignalReportLoadState = CellSignalReportLoadState.Loading
			state.shouldBeInstanceOf<CellSignalReportLoadState.Loading>()
		}

		@Test
		fun `Empty is a singleton`() {
			val state: CellSignalReportLoadState = CellSignalReportLoadState.Empty
			state.shouldBeInstanceOf<CellSignalReportLoadState.Empty>()
		}

		@Test
		fun `Success stores report`() {
			val report = CellSignalReport(0L, 0L, emptyList(), emptyList())
			val state = CellSignalReportLoadState.Success(report)
			state.report shouldBe report
		}

		@Test
		fun `Error stores message`() {
			val state = CellSignalReportLoadState.Error("Cell repository failed")
			state.message shouldBe "Cell repository failed"
		}
	}

	@Nested
	@DisplayName("Sealed hierarchy")
	inner class SealedHierarchy {

		@Test
		fun `when expression covers all subtypes`() {
			val states: List<CellSignalReportLoadState> = listOf(
				CellSignalReportLoadState.Idle,
				CellSignalReportLoadState.Loading,
				CellSignalReportLoadState.Empty,
				CellSignalReportLoadState.Error("err"),
			)
			val labels = states.map { state ->
				when (state) {
					is CellSignalReportLoadState.Idle -> "idle"
					is CellSignalReportLoadState.Loading -> "loading"
					is CellSignalReportLoadState.Empty -> "empty"
					is CellSignalReportLoadState.Success -> "success"
					is CellSignalReportLoadState.Error -> "error"
				}
			}
			labels shouldBe listOf("idle", "loading", "empty", "error")
		}
	}
}
