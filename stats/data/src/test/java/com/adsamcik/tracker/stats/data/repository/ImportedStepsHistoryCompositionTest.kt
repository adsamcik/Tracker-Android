package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.PortableStepsCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsCompletenessV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsManifestV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableStepsProviderCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsRunV1
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ImportedStepsHistoryCompositionTest {
	@Test
	fun `covered positive and zero are retained products not local provider availability`() {
		listOf(0L, 12L).forEach { count ->
			val history = run(listOf(fact('3', 10L, 20L, count))).toImportedStepsHistory()
			history.count shouldBe count
			history.hasCompleteValue shouldBe true
			history.availability shouldBe HistoryAvailability.RETAINED_IMPORTED
		}
	}

	@Test
	fun `missing baseline and reset do not fabricate zero`() {
		listOf(emptyList(), listOf(fact('3', 10L, 10L, null, PortableStepsFactCoverage.BASELINE)),
			listOf(fact('3', 10L, 20L, null, PortableStepsFactCoverage.RESET_GAP))).forEach { facts ->
			val history = run(facts).toImportedStepsHistory()
			history.count shouldBe null
			history.hasCompleteValue shouldBe false
		}
	}

	@Test
	fun `declared complete settlement cannot fill missing captured time`() {
		val history = run(listOf(fact('3', 11L, 19L, 9L))).toImportedStepsHistory()
		history.count shouldBe 9L
		history.isLowerBound shouldBe true
		history.hasCompleteValue shouldBe false
		(StepsHistoryCause.PROVIDER_GAP in history.causes) shouldBe true
	}

	@Test
	fun `each settlement axis independently prevents complete totals`() {
		val complete = run(listOf(fact('3', 10L, 20L, 9L)))
		listOf(
			complete.completeness.copy(captureCoverage = PortableStepsCaptureCoverage.PARTIAL),
			complete.completeness.copy(providerCoverage = PortableStepsProviderCoverage.PARTIAL),
			complete.completeness.copy(providerCoverage = PortableStepsProviderCoverage.UNOBSERVABLE),
			complete.completeness.copy(appDrainComplete = false),
			complete.completeness.copy(stopComplete = false),
			complete.completeness.copy(hasUnresolvedProviderRange = true),
		).forEach { state ->
			val history = complete.copy(completeness = state).toImportedStepsHistory()
			history.count shouldBe 9L
			history.productState shouldBe HistoryProductState.PARTIAL
			history.hasCompleteValue shouldBe false
		}
	}

	@Test
	fun `retention receipt prevents original complete claim even with a covered surviving envelope`() {
		val history = run(listOf(fact('3', 10L, 20L, 9L))).toImportedStepsHistory(retentionTruncated = true)
		history.count shouldBe 9L
		history.isLowerBound shouldBe true
		history.hasCompleteValue shouldBe false
		(StepsHistoryCause.RETENTION_LIMIT in history.causes) shouldBe true
	}

	@Test
	fun `overlapping counts and arithmetic overflow are nonnumeric`() {
		val overlapping = run(listOf(fact('3', 10L, 16L, 3L), fact('4', 15L, 20L, 4L)))
		overlapping.toImportedStepsHistory().count shouldBe null
		val overflow = run(listOf(fact('3', 10L, 15L, Long.MAX_VALUE), fact('4', 15L, 20L, 1L)))
		overflow.toImportedStepsHistory().count shouldBe null
	}

	private fun run(facts: List<PortableStepsFactV1>) = PortableStepsRunV1(
		identity = opaque('2'), deletionScopeDigest = PortableStepsDeletionScopeDigest("4".repeat(64)),
		startTimeMs = 10L, endTimeMs = 20L, storedZoneId = "UTC",
		manifests = listOf(PortableStepsManifestV1(1L, 10L, 1L, 0L)),
		completeness = PortableStepsCompletenessV1(
			PortableStepsCaptureCoverage.WHOLE_RUN, PortableStepsProviderCoverage.COMPLETE, true, true, false,
		), facts = facts,
	)

	private fun fact(
		id: Char, start: Long, end: Long, count: Long?,
		coverage: PortableStepsFactCoverage = PortableStepsFactCoverage.COVERED,
	) = PortableStepsFactV1.create(opaque(id), 1L, start, end, 0L, coverage, count)

	private fun opaque(character: Char) = PortableStepsOpaqueIdentity("sha256:${character.toString().repeat(64)}")
}
