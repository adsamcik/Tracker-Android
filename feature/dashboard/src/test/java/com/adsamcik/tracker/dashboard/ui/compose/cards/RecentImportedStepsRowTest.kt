package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.ImportedStepsHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ImportedStepsHistoryMember
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentImportedStepsRowTest {
	@get:Rule val composeRule = createComposeRule()

	@Test
	fun replacementEntryExposesEachExactMemberInsteadOfLatestOrAggregateCount() {
		val selected = mutableListOf<Long>()
		render(listOf(member(12, 100), member(34, 200))) { selected += it }
		composeRule.onNodeWithText("Imported Steps").assertIsDisplayed()
		composeRule.onNodeWithText("Recording 1", substring = true).assertDoesNotExist()
		composeRule.onNodeWithText("Show 2 recordings").performClick()
		composeRule.onNodeWithText("Recording 1 · 100 steps").performClick()
		composeRule.onNodeWithText("Recording 2 · 200 steps").performClick()
		selected shouldBe listOf(12L, 34L)
		composeRule.onNodeWithText("300 steps", substring = true).assertDoesNotExist()
		composeRule.onNodeWithText("km", substring = true).assertDoesNotExist()
	}

	@Test
	fun verifiedZeroRemainsSelectable() {
		var selected: Long? = null
		render(listOf(member(42, 0))) { selected = it }
		composeRule.onNodeWithText("Recording 1 · 0 steps").performClick()
		selected shouldBe 42L
	}

	@Test
	fun missingEvidenceNeverBecomesZero() {
		render(listOf(member(42, null)), null)
		composeRule.onNodeWithText("Recording 1 · Steps unavailable").assertIsDisplayed()
		composeRule.onNodeWithText("0 steps", substring = true).assertDoesNotExist()
	}

	@Test
	fun partialCoveredZeroRemainsAnExplicitLowerBound() {
		val member = member(42, 0)
		render(listOf(member.copy(steps = member.steps.copy(
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.PARTIAL,
			causes = setOf(StepsHistoryCause.FACTS_MISSING),
		))), null)
		composeRule.onNodeWithText("Recording 1 · At least 0 steps").assertIsDisplayed()
		composeRule.onNodeWithText("Recording 1 · 0 steps").assertDoesNotExist()
	}

	@Test
	fun materializingEvidenceDoesNotBecomeZeroOrUnavailable() {
		val member = member(42, null)
		render(listOf(member.copy(steps = member.steps.copy(
			productState = HistoryProductState.MATERIALIZING,
		))), null)
		composeRule.onNodeWithText("Steps unavailable", substring = true).assertDoesNotExist()
		composeRule.onNodeWithText("0 steps", substring = true).assertDoesNotExist()
		composeRule.onNodeWithText("Recording 1 · Preparing Steps history…").assertIsDisplayed()
	}

	private fun render(members: List<ImportedStepsHistoryMember>, onClick: ((Long) -> Unit)?) {
		val entry = ImportedStepsHistoryEntry(
			key = TrackingHistoryEntryKey("imported-entry"),
			startTime = members.minOf { it.startTime },
			endTime = members.maxOf { it.endTime },
			physicalMembers = members,
		)
		composeRule.setContent { MaterialTheme { RecentImportedStepsRow(entry, onClick) } }
	}

	private fun member(id: Long, count: Long?) = ImportedStepsHistoryMember(
		segmentId = id,
		startTime = EpochMs(id * 1_000),
		endTime = EpochMs(id * 1_000 + 500),
		steps = StepsHistory(
			count = count,
			availability = HistoryAvailability.RETAINED_IMPORTED,
			evidence = when {
				count == null -> HistoryEvidence.NONE
				count == 0L -> HistoryEvidence.ACTIVE
				else -> HistoryEvidence.RECORDED
			},
			productState = if (count == null) { HistoryProductState.PARTIAL } else { HistoryProductState.READY },
			coverage = if (count == null) { StepsHistoryCoverage.NONE } else { StepsHistoryCoverage.COMPLETE },
			causes = if (count == null) { setOf(StepsHistoryCause.FACTS_MISSING) } else { emptySet() },
		),
	)
}
