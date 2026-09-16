package com.adsamcik.tracker.stats.data.repository

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

class AmbientStepsDayComposerTest {
	@Test
	fun `session attribution is contained and never added to authoritative ambient total`() {
		val result = composeAmbientStepsDay(
			day,
			listOf(fact(0L, DAY_END, 100L)),
			emptyList(),
			listOf(session(20L, 40L, 25L)),
		)

		result.total shouldBe AmbientStepsNumericValue.Exact(100L)
		result.inSession.map { it.stepCount } shouldBe listOf(25L)
		result.betweenSession shouldBe AmbientStepsNumericValue.Exact(75L)
	}

	@Test
	fun `partial ambient coverage preserves observed total but withholds between-session value`() {
		val result = composeAmbientStepsDay(day, listOf(fact(0L, 80L, 12L)), emptyList(), emptyList())

		result.total shouldBe AmbientStepsNumericValue.Partial(
			12L,
			setOf(AmbientStepsDayCause.AMBIENT_COVERAGE_PARTIAL),
		)
		result.betweenSession shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_COVERAGE_PARTIAL),
		)
	}

	@Test
	fun `unproven provider compatibility never fabricates between-session zero`() {
		val result = composeAmbientStepsDay(
			day,
			listOf(fact(0L, DAY_END, 25L)),
			emptyList(),
			listOf(session(20L, 40L, 25L, SessionAmbientCompatibility.Unproven)),
		)

		result.total shouldBe AmbientStepsNumericValue.Exact(25L)
		result.betweenSession shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.SESSION_PROVIDER_COMPATIBILITY_UNPROVEN),
		)
	}

	@Test
	fun `matching provider proof still requires exact ambient coverage of session`() {
		val result = composeAmbientStepsDay(
			day,
			listOf(fact(0L, 30L, 30L), fact(40L, DAY_END, 20L)),
			emptyList(),
			listOf(session(20L, 40L, 10L)),
		)

		(result.betweenSession as AmbientStepsNumericValue.Unavailable).causes shouldBe setOf(
			AmbientStepsDayCause.AMBIENT_COVERAGE_PARTIAL,
			AmbientStepsDayCause.SESSION_NOT_COVERED_BY_COMPATIBLE_AMBIENT_FACT,
		)
	}

	@Test
	fun `overlap and overflow fail closed instead of double counting`() {
		composeAmbientStepsDay(day, listOf(fact(0L, 60L, 1L), fact(50L, DAY_END, 1L, "b")), emptyList(), emptyList()).total shouldBe
			AmbientStepsNumericValue.Unavailable(setOf(AmbientStepsDayCause.AMBIENT_FACT_OVERLAP))
		composeAmbientStepsDay(day, listOf(fact(0L, 50L, Long.MAX_VALUE), fact(50L, DAY_END, 1L, "b")), emptyList(), emptyList()).total shouldBe
			AmbientStepsNumericValue.Unavailable(setOf(AmbientStepsDayCause.AMBIENT_COUNT_OVERFLOW))
	}

	@Test
	fun `effective gap prevents complete claim even with boundary coverage`() {
		val result = composeAmbientStepsDay(
			day,
			listOf(fact(0L, DAY_END, 0L)),
			listOf(EffectiveAmbientStepsGap(30L, 40L)),
			emptyList(),
		)

		result.total shouldBe AmbientStepsNumericValue.Partial(0L, setOf(AmbientStepsDayCause.AMBIENT_GAP))
		result.betweenSession shouldBe AmbientStepsNumericValue.Unavailable(setOf(AmbientStepsDayCause.AMBIENT_GAP))
	}

	@Test
	fun `fact from another structural day fails closed instead of disappearing`() {
		val otherDay = AmbientStepsDayIdentity(1L, "UTC", 86_400_000L, 172_800_000L)
		val result = composeAmbientStepsDay(
			day,
			listOf(fact(0L, 100L, 1L).copy(day = otherDay, startTimeMs = 86_400_000L, endTimeMs = 86_400_100L)),
			emptyList(),
			emptyList(),
		)

		result.total shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_DAY_AUTHORITY_MISMATCH),
		)
	}

	@Test
	fun `wrong-zone session cannot downgrade authoritative ambient total`() {
		val result = composeAmbientStepsDay(
			day,
			listOf(fact(0L, DAY_END, 100L)),
			emptyList(),
			listOf(session(20L, 40L, 25L).copy(storedZoneId = "Europe/Prague")),
		)

		result.total shouldBe AmbientStepsNumericValue.Exact(100L)
		result.inSession shouldBe emptyList()
		result.betweenSession shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.SESSION_OUTSIDE_DAY),
		)
	}

	@Test
	fun `overlapping sessions leave ambient total exact but make subtraction unavailable`() {
		val result = composeAmbientStepsDay(
			day,
			listOf(fact(0L, DAY_END, 100L)),
			emptyList(),
			listOf(session(20L, 50L, 20L), session(40L, 60L, 10L)),
		)

		result.total shouldBe AmbientStepsNumericValue.Exact(100L)
		result.betweenSession shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.SESSION_OVERLAP),
		)
	}

	@Test
	fun `unavailable session value never becomes zero subtraction`() {
		val result = composeAmbientStepsDay(
			day,
			listOf(fact(0L, DAY_END, 100L)),
			emptyList(),
			listOf(session(20L, 40L, null)),
		)

		result.total shouldBe AmbientStepsNumericValue.Exact(100L)
		result.betweenSession shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.SESSION_VALUE_UNAVAILABLE),
		)
	}

	@Test
	fun `session count greater than ambient total fails subtraction closed`() {
		val result = composeAmbientStepsDay(
			day,
			listOf(fact(0L, DAY_END, 10L)),
			emptyList(),
			listOf(session(20L, 40L, 11L)),
		)

		result.total shouldBe AmbientStepsNumericValue.Exact(10L)
		result.betweenSession shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.SESSION_COUNT_EXCEEDS_AMBIENT_TOTAL),
		)
	}

	@Test
	fun `compatible session must be covered by one provider domain even when day has multiple origins`() {
		val otherProvenance = AmbientStepsProviderProvenance("provider-b", "instance-b", 2L, 1L)
		val result = composeAmbientStepsDay(
			day,
			listOf(
				fact(0L, 50L, 40L),
				fact(50L, DAY_END, 60L, "b").copy(provenance = otherProvenance),
			),
			emptyList(),
			listOf(session(40L, 60L, 10L)),
		)

		result.total shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_ORIGIN_IDENTITY_CONFLICT),
		)
		result.betweenSession shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_ORIGIN_IDENTITY_CONFLICT),
		)
	}

	@Test
	fun `adjacent facts from different native providers are not additive`() {
		val other = AmbientStepsProviderProvenance("provider-b", "instance-b", 2L, 1L)
		val result = composeAmbientStepsDay(
			day,
			listOf(
				fact(0L, 50L, 1L),
				fact(50L, DAY_END, 2L, "b").copy(provenance = other),
			),
			emptyList(),
			emptyList(),
		)

		result.total shouldBe AmbientStepsNumericValue.Unavailable(
			setOf(AmbientStepsDayCause.AMBIENT_ORIGIN_IDENTITY_CONFLICT),
		)
	}

	@Test
	fun `adjacent native and imported facts are not additive for zero or positive splits`() {
		listOf(0L, 2L).forEach { importedCount ->
			val result = composeAmbientStepsDay(
				day,
				listOf(
					fact(0L, 50L, 1L),
					importedFact(50L, DAY_END, importedCount, "portable-$importedCount"),
				),
				emptyList(),
				emptyList(),
			)

			result.total shouldBe AmbientStepsNumericValue.Unavailable(
				setOf(AmbientStepsDayCause.AMBIENT_ORIGIN_IDENTITY_CONFLICT),
			)
		}
	}

	@Test
	fun `adjacent imported facts from one authenticated day revision remain additive`() {
		val result = composeAmbientStepsDay(
			day,
			listOf(
				importedFact(0L, 50L, 1L, "portable-a"),
				importedFact(50L, DAY_END, 2L, "portable-b"),
			),
			emptyList(),
			emptyList(),
		)

		result.total shouldBe AmbientStepsNumericValue.Exact(3L)
	}

	@Test
	fun `zero-width gaps are rejected before product composition`() {
		assertThrows<IllegalArgumentException> { EffectiveAmbientStepsGap(40L, 40L) }
	}

	@Test
	fun `positive session aggregate cannot claim a zero-width capture window`() {
		assertThrows<IllegalArgumentException> { session(40L, 40L, 1L) }
	}

	@Test
	fun `session origin remains explicit in contained attribution`() {
		val imported = session(20L, 40L, 5L).copy(
			origin = QualifiedSessionStepsOrigin.PORTABLE_IMPORT,
		)
		val result = composeAmbientStepsDay(
			day,
			listOf(fact(0L, DAY_END, 10L)),
			emptyList(),
			listOf(imported),
		)

		result.inSession.single().origin shouldBe QualifiedSessionStepsOrigin.PORTABLE_IMPORT
	}

	@Test
	fun `exact local and imported portable identity is counted once with both origins explicit`() {
		val imported = importedFact(0L, DAY_END, 10L, "portable")
		val local = fact(0L, DAY_END, 10L, id = "local").copy(
			portableIdentity = "portable",
			contentChecksum = imported.contentChecksum,
		)

		val result = composeAmbientStepsDay(day, listOf(local, imported), emptyList(), emptyList())

		result.total shouldBe AmbientStepsNumericValue.Exact(10L)
		result.origins shouldBe setOf(
			QualifiedAmbientStepsFactOrigin.LOCAL_PROVIDER,
			QualifiedAmbientStepsFactOrigin.PORTABLE_IMPORT,
		)
	}

	@Test
	fun `exact imported duplicate cannot hide local provider compatibility`() {
		val imported = importedFact(0L, DAY_END, 10L, "portable")
		val local = fact(0L, DAY_END, 10L, id = "local").copy(
			portableIdentity = "portable",
			contentChecksum = imported.contentChecksum,
		)

		val result = composeAmbientStepsDay(
			day,
			listOf(imported, local),
			emptyList(),
			listOf(session(20L, 40L, 5L)),
		)

		result.total shouldBe AmbientStepsNumericValue.Exact(10L)
		result.betweenSession shouldBe AmbientStepsNumericValue.Exact(5L)
	}

	@Test
	fun `wall overlap with distinct ownership is unavailable rather than additive`() {
		val local = fact(0L, DAY_END, 10L, id = "local")
		val imported = importedFact(0L, DAY_END, 12L, "portable")

		composeAmbientStepsDay(day, listOf(local, imported), emptyList(), emptyList()).total shouldBe
			AmbientStepsNumericValue.Unavailable(
				setOf(AmbientStepsDayCause.AMBIENT_ORIGIN_IDENTITY_CONFLICT),
			)
	}

	@Test
	fun `conflicting correction content under one portable identity is never summed`() {
		val old = importedFact(0L, DAY_END, 10L, "portable")
		val correction = importedFact(0L, DAY_END, 12L, "portable")

		composeAmbientStepsDay(day, listOf(old, correction), emptyList(), emptyList()).total shouldBe
			AmbientStepsNumericValue.Unavailable(
				setOf(AmbientStepsDayCause.AMBIENT_ORIGIN_IDENTITY_CONFLICT),
			)
	}

	private val day = AmbientStepsDayIdentity(0L, "UTC", 0L, DAY_END)
	private val provenance = AmbientStepsProviderProvenance("provider", "instance", 1L, 1L)

	private fun fact(start: Long, end: Long, count: Long, id: String = "a") = QualifiedAmbientStepsFact(
		id,
		day,
		start,
		end,
		count,
		provenance,
		contentChecksum = "native-$id-$start-$end-$count",
	)

	private fun importedFact(
		start: Long,
		end: Long,
		count: Long,
		identity: String,
	) = QualifiedAmbientStepsFact(
		logicalFactId = identity,
		day = day,
		startTimeMs = start,
		endTimeMs = end,
		stepCount = count,
		provenance = null,
		portableIdentity = identity,
		origin = QualifiedAmbientStepsFactOrigin.PORTABLE_IMPORT,
		importedProvenance = ImportedAmbientStepsFactProvenance("archive", "day", 1L),
		contentChecksum = "portable-$identity-$start-$end-$count",
	)

	private fun session(
		start: Long,
		end: Long,
		count: Long?,
		compatibility: SessionAmbientCompatibility = SessionAmbientCompatibility.ExactProviderDomain(
			"provider",
			"instance",
		),
	) = QualifiedSessionStepsWindow("tracking", "run-$start", start, end, count, "UTC", compatibility)

	private companion object {
		const val DAY_END = 86_400_000L
	}
}
