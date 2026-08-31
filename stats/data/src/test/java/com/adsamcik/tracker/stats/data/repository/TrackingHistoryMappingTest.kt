package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage as ApiStepsHistoryCoverage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

class TrackingHistoryMappingTest {
	@Test
	fun `candidate covered zero maps to ready active public history`() {
		val history = result(
			count = 0L,
			evidence = StepsHistoryEvidence.COVERED_ZERO,
			coverage = StepsHistoryCoverage.COMPLETE,
		).toPublicHistory()

		history.count shouldBe 0L
		history.availability shouldBe HistoryAvailability.AVAILABLE
		history.evidence shouldBe HistoryEvidence.ACTIVE
		history.productState shouldBe HistoryProductState.READY
		history.coverage shouldBe ApiStepsHistoryCoverage.COMPLETE
		history.hasCompleteValue shouldBe true
	}

	@Test
	fun `baseline and positive partial remain qualified rather than becoming complete totals`() {
		val baseline = result(
			count = null,
			evidence = StepsHistoryEvidence.BASELINE,
			coverage = StepsHistoryCoverage.NONE,
		).toPublicHistory()
		baseline.evidence shouldBe HistoryEvidence.NONE
		baseline.productState shouldBe HistoryProductState.PARTIAL
		baseline.count shouldBe null
		baseline.causes shouldContainExactly setOf(StepsHistoryCause.BASELINE_ONLY)

		val partial = result(
			count = 8L,
			evidence = StepsHistoryEvidence.RECORDED,
			coverage = StepsHistoryCoverage.PARTIAL,
			reasons = setOf(StepsHistoryReason.RESET_GAP),
		).toPublicHistory()
		partial.evidence shouldBe HistoryEvidence.RECORDED
		partial.productState shouldBe HistoryProductState.PARTIAL
		partial.isLowerBound shouldBe true
		partial.causes shouldContainExactly setOf(StepsHistoryCause.PROVIDER_GAP)
	}

	@Test
	fun `active run with no observation is starting and materializing`() {
		val history = result(
			count = null,
			evidence = StepsHistoryEvidence.NO_OBSERVATION,
			materialization = StepsHistoryMaterialization.MATERIALIZING,
			coverage = StepsHistoryCoverage.NONE,
			reasons = setOf(StepsHistoryReason.SERVICE_RUN_ACTIVE),
		).toPublicHistory()

		history.evidence shouldBe HistoryEvidence.STARTING
		history.productState shouldBe HistoryProductState.MATERIALIZING
		history.causes shouldContainExactly setOf(StepsHistoryCause.SESSION_STILL_ACTIVE)
	}

	@Test
	fun `legacy values and retention limits stay visible but qualified`() {
		val legacy = result(
			count = 9L,
			evidence = StepsHistoryEvidence.LEGACY_RECORDED,
			materialization = StepsHistoryMaterialization.DEGRADED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			reasons = setOf(StepsHistoryReason.LEGACY_REPLAY_UNVERIFIED),
		).toPublicHistory()
		legacy.evidence shouldBe HistoryEvidence.RECORDED
		legacy.productState shouldBe HistoryProductState.DEGRADED
		legacy.isLowerBound shouldBe false

		val retainedFloor = result(
			count = null,
			availability = StepsHistoryAvailability.UNAVAILABLE,
			evidence = StepsHistoryEvidence.NO_OBSERVATION,
			materialization = StepsHistoryMaterialization.FAILED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			reasons = setOf(StepsHistoryReason.OUTSIDE_RETAINED_FLOOR),
		).toPublicHistory()
		retainedFloor.availability shouldBe HistoryAvailability.UNAVAILABLE
		retainedFloor.productState shouldBe HistoryProductState.PARTIAL
		retainedFloor.causes shouldContainExactly setOf(
			StepsHistoryCause.RETENTION_LIMIT,
			StepsHistoryCause.AVAILABILITY_UNAVAILABLE,
		)
	}

	@Test
	fun `presentation binding failures stay typed by verification boundary`() {
		val mismatched = result(
			count = null,
			availability = StepsHistoryAvailability.UNAVAILABLE,
			evidence = StepsHistoryEvidence.NO_OBSERVATION,
			materialization = StepsHistoryMaterialization.FAILED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			reasons = setOf(StepsHistoryReason.SERVICE_RUN_SEGMENT_BINDING_MISMATCH),
		).toPublicHistory()
		mismatched.causes shouldContainExactly setOf(
			StepsHistoryCause.HISTORY_MEMBERSHIP_UNAVAILABLE,
			StepsHistoryCause.AVAILABILITY_UNAVAILABLE,
		)

		val legacyUnverifiable = result(
			count = null,
			availability = StepsHistoryAvailability.UNAVAILABLE,
			evidence = StepsHistoryEvidence.NO_OBSERVATION,
			materialization = StepsHistoryMaterialization.FAILED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			reasons = setOf(StepsHistoryReason.SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE),
		).toPublicHistory()
		legacyUnverifiable.causes shouldContainExactly setOf(
			StepsHistoryCause.LEGACY_UNVERIFIED,
			StepsHistoryCause.AVAILABILITY_UNAVAILABLE,
		)
	}

	@Test
	fun `disabled and deleted history remain different public combinations`() {
		val disabled = result(
			count = null,
			availability = StepsHistoryAvailability.DISABLED,
			evidence = StepsHistoryEvidence.NO_OBSERVATION,
			materialization = StepsHistoryMaterialization.NOT_APPLICABLE,
			coverage = StepsHistoryCoverage.NONE,
			reasons = setOf(StepsHistoryReason.SOURCE_NOT_CAPTURED),
		).toPublicHistory()
		disabled.availability shouldBe HistoryAvailability.DISABLED
		disabled.productState shouldBe HistoryProductState.READY

		val deleted = result(
			count = null,
			availability = StepsHistoryAvailability.DELETED,
			evidence = StepsHistoryEvidence.NO_OBSERVATION,
			coverage = StepsHistoryCoverage.NONE,
			reasons = setOf(StepsHistoryReason.DELETED_FACTS),
		).toPublicHistory()
		deleted.availability shouldBe HistoryAvailability.AVAILABLE
		deleted.productState shouldBe HistoryProductState.PARTIAL
		deleted.causes shouldContainExactly setOf(StepsHistoryCause.DELETED)
	}

	@Test
	fun `active baseline remains no observation with explicit causes`() {
		val history = result(
			count = null,
			evidence = StepsHistoryEvidence.BASELINE,
			materialization = StepsHistoryMaterialization.MATERIALIZING,
			coverage = StepsHistoryCoverage.NONE,
			reasons = setOf(StepsHistoryReason.SERVICE_RUN_ACTIVE),
		).toPublicHistory()

		history.evidence shouldBe HistoryEvidence.NONE
		history.productState shouldBe HistoryProductState.MATERIALIZING
		history.causes shouldContainExactly setOf(
			StepsHistoryCause.SESSION_STILL_ACTIVE,
			StepsHistoryCause.BASELINE_ONLY,
		)
	}

	@Test
	fun `every internal selector reason has an explicit public cause`() {
		StepsHistoryReason.entries.forEach { reason ->
			reason.toPublicCause()
		}
	}

	private fun result(
		count: Long?,
		availability: StepsHistoryAvailability = StepsHistoryAvailability.AVAILABLE,
		evidence: StepsHistoryEvidence,
		materialization: StepsHistoryMaterialization = StepsHistoryMaterialization.READY,
		coverage: StepsHistoryCoverage,
		reasons: Set<StepsHistoryReason> = emptySet(),
	) = StepsSegmentHistoryResult(
		count = count,
		availability = availability,
		evidence = evidence,
		materialization = materialization,
		coverage = coverage,
		reasons = reasons,
	)
}
