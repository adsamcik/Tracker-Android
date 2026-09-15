package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryValue
import io.kotest.matchers.shouldBe
import org.junit.Test

class DefaultAmbientStepsHistoryRepositoryContractTest {
	@Test
	fun `native and imported exact portable duplicate is counted once with both origins`() {
		val day = AmbientStepsDayIdentity(0L, "UTC", 0L, DAY_END)
		val native = nativeFact(day, 12L, "portable")
		val imported = importedFact(day, 12L, "portable")

		val product = composeAmbientStepsDay(
			day,
			listOf(native, imported),
			emptyList(),
			emptyList(),
		)

		product.total shouldBe AmbientStepsNumericValue.Exact(12L)
		product.origins shouldBe setOf(
			QualifiedAmbientStepsFactOrigin.LOCAL_PROVIDER,
			QualifiedAmbientStepsFactOrigin.PORTABLE_IMPORT,
		)
	}

	@Test
	fun `uncertain local versus imported overlap is unavailable rather than an arbitrary winner`() {
		val day = AmbientStepsDayIdentity(0L, "UTC", 0L, DAY_END)

		composeAmbientStepsDay(
			day,
			listOf(nativeFact(day, 12L, "native"), importedFact(day, 12L, "imported")),
			emptyList(),
			emptyList(),
		).total shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_ORIGIN_IDENTITY_CONFLICT),
		)
	}

	@Test
	fun `gap with no fact remains unavailable partial evidence not complete zero`() {
		val day = AmbientStepsDayIdentity(0L, "UTC", 0L, DAY_END)

		val product = composeAmbientStepsDay(
			day,
			emptyList(),
			listOf(EffectiveAmbientStepsGap(0L, DAY_END)),
			emptyList(),
		)

		product.total shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(
				AmbientStepsDayCause.NO_AMBIENT_FACT,
				AmbientStepsDayCause.AMBIENT_GAP,
			),
		)
		product.total.toPublicTestValue() shouldBe AmbientStepsHistoryValue.Unavailable(
			setOf(
				AmbientStepsHistoryCause.NO_EVIDENCE,
				AmbientStepsHistoryCause.EXPLICIT_GAP,
			),
		)
	}

	@Test
	fun `observer declares every source and imported correction dependency`() {
		DefaultAmbientStepsHistoryRepository.AMBIENT_HISTORY_DEPENDENCY_TABLES.toSet()
			.containsAll(
				setOf(
					"ambient_steps_fact_revision",
					"ambient_steps_import_cursor",
					"ambient_steps_import_gap",
					"ambient_steps_import_authority_transition",
					"imported_ambient_steps_day_revision",
					"imported_ambient_steps_fact",
					"imported_ambient_steps_gap",
					"imported_ambient_steps_day_fence",
					"imported_ambient_steps_source_fence",
					"step_fact_revision",
					"imported_steps_run",
				),
			) shouldBe true
	}

	private fun nativeFact(
		day: AmbientStepsDayIdentity,
		count: Long,
		identity: String,
	) = QualifiedAmbientStepsFact(
		logicalFactId = "native-$identity",
		day = day,
		startTimeMs = day.startTimeMs,
		endTimeMs = day.endTimeMs,
		stepCount = count,
		provenance = AmbientStepsProviderProvenance("provider", "instance", 1L, 1L),
		portableIdentity = identity,
		contentChecksum = "checksum-$identity-$count",
	)

	private fun importedFact(
		day: AmbientStepsDayIdentity,
		count: Long,
		identity: String,
	) = QualifiedAmbientStepsFact(
		logicalFactId = "imported-$identity",
		day = day,
		startTimeMs = day.startTimeMs,
		endTimeMs = day.endTimeMs,
		stepCount = count,
		provenance = null,
		portableIdentity = identity,
		origin = QualifiedAmbientStepsFactOrigin.PORTABLE_IMPORT,
		importedProvenance = ImportedAmbientStepsFactProvenance("archive", "day", 1L),
		contentChecksum = "checksum-$identity-$count",
	)

	private fun AmbientStepsNumericValue.toPublicTestValue(): AmbientStepsHistoryValue = when (this) {
		is AmbientStepsNumericValue.Exact -> AmbientStepsHistoryValue.Exact(count)
		is AmbientStepsNumericValue.Partial -> AmbientStepsHistoryValue.Partial(
			count,
			causes.mapTo(linkedSetOf()) {
				when (it) {
					AmbientStepsDayCause.AMBIENT_GAP -> AmbientStepsHistoryCause.EXPLICIT_GAP
					AmbientStepsDayCause.AMBIENT_COVERAGE_PARTIAL ->
						AmbientStepsHistoryCause.PARTIAL_COVERAGE
					else -> AmbientStepsHistoryCause.NO_EVIDENCE
				}
			},
		)
		is AmbientStepsNumericValue.Unavailable -> AmbientStepsHistoryValue.Unavailable(
			causes.mapTo(linkedSetOf()) {
				when (it) {
					AmbientStepsDayCause.AMBIENT_GAP -> AmbientStepsHistoryCause.EXPLICIT_GAP
					else -> AmbientStepsHistoryCause.NO_EVIDENCE
				}
			},
		)
	}

	private companion object {
		const val DAY_END = 86_400_000L
	}
}
