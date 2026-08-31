package com.adsamcik.tracker.dashboard.ui.compose.state

import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import io.kotest.matchers.shouldBe
import org.junit.Test

class DashboardLiveStepsStateTest {
	@Test
	fun `complete positive value stays complete`() {
		completeHistory(count = 42L).toDashboardLiveStepsValue() shouldBe
			DashboardLiveStepsValue.Complete(42L)
	}

	@Test
	fun `only complete covered zero becomes numeric zero`() {
		completeHistory(count = 0L).toDashboardLiveStepsValue() shouldBe
			DashboardLiveStepsValue.CoveredZero

		stepsHistory(
			count = 0L,
			evidence = HistoryEvidence.ACTIVE,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.PARTIAL,
			causes = setOf(StepsHistoryCause.PROVIDER_GAP),
		).toDashboardLiveStepsValue() shouldBe DashboardLiveStepsValue.Partial()
	}

	@Test
	fun `positive partial value remains a lower bound`() {
		stepsHistory(
			count = 17L,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.PARTIAL,
			causes = setOf(StepsHistoryCause.PROVIDER_GAP),
		).toDashboardLiveStepsValue() shouldBe DashboardLiveStepsValue.Partial(17L)
	}

	@Test
	fun `active projection work remains materializing`() {
		stepsHistory(
			count = null,
			evidence = HistoryEvidence.STARTING,
			productState = HistoryProductState.MATERIALIZING,
			coverage = StepsHistoryCoverage.NONE,
			causes = setOf(StepsHistoryCause.SESSION_STILL_ACTIVE),
		).toDashboardLiveStepsValue() shouldBe DashboardLiveStepsValue.Materializing
	}

	@Test
	fun `baseline without a value remains missing`() {
		stepsHistory(
			count = null,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.NONE,
			causes = setOf(StepsHistoryCause.BASELINE_ONLY),
		).toDashboardLiveStepsValue() shouldBe DashboardLiveStepsValue.Missing
	}

	@Test
	fun `unavailable acquisition remains unavailable`() {
		stepsHistory(
			count = null,
			availability = HistoryAvailability.UNAVAILABLE,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.UNKNOWN,
			causes = setOf(StepsHistoryCause.AVAILABILITY_UNAVAILABLE),
		).toDashboardLiveStepsValue() shouldBe DashboardLiveStepsValue.Unavailable

		stepsHistory(
			count = 0L,
			availability = HistoryAvailability.UNAVAILABLE,
			evidence = HistoryEvidence.ACTIVE,
			productState = HistoryProductState.READY,
			coverage = StepsHistoryCoverage.COMPLETE,
		).toDashboardLiveStepsValue() shouldBe DashboardLiveStepsValue.Unavailable
	}

	private fun completeHistory(count: Long): StepsHistory = stepsHistory(
		count = count,
		evidence = if (count == 0L) {
			HistoryEvidence.ACTIVE
		} else {
			HistoryEvidence.RECORDED
		},
		productState = HistoryProductState.READY,
		coverage = StepsHistoryCoverage.COMPLETE,
	)

	private fun stepsHistory(
		count: Long?,
		availability: HistoryAvailability = HistoryAvailability.AVAILABLE,
		evidence: HistoryEvidence,
		productState: HistoryProductState,
		coverage: StepsHistoryCoverage,
		causes: Set<StepsHistoryCause> = emptySet(),
	): StepsHistory = StepsHistory(
		count = count,
		availability = availability,
		evidence = evidence,
		productState = productState,
		coverage = coverage,
		causes = causes,
	)
}
