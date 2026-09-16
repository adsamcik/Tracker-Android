package com.adsamcik.tracker.shared.model.steps.portable

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class AmbientStepsPortableFormatV1Test {
	@Test
	fun `covered zero remains an authenticated provider fact rather than fabricated absence`() {
		val fact = fact("local-fact", 0L, DAY_END, 0L)
		val day = day(
			facts = listOf(fact),
			coverage = PortableAmbientStepsCoverage.COMPLETE,
			causes = emptyList(),
		)

		day.retainedStepCount shouldBe 0L
		day.facts.single().stepCount shouldBe 0L
		PortableAmbientStepsArchiveV1.create(listOf(day)).days.single() shouldBe day
	}

	@Test
	fun `partial coverage retains observed value and exact gap without claiming all-day zero`() {
		val fact = fact("local-fact", 3_600_000L, DAY_END, 12L)
		val gap = PortableAmbientStepsGapV1.create(
			AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.GAP,
				"local-gap",
			),
			0L,
			3_600_000L,
			PortableAmbientStepsGapReason.PROCESS_ABSENCE,
		)
		val day = day(
			facts = listOf(fact),
			gaps = listOf(gap),
			coverage = PortableAmbientStepsCoverage.PARTIAL,
			causes = listOf(PortableAmbientStepsPartialCause.EXPLICIT_GAP),
		)

		day.retainedStepCount shouldBe 12L
		day.coverage shouldBe PortableAmbientStepsCoverage.PARTIAL
		day.gaps.single().reason shouldBe PortableAmbientStepsGapReason.PROCESS_ABSENCE
	}

	@Test
	fun `semantic mutation with a retained checksum is rejected at every level`() {
		val fact = fact("local-fact", 0L, DAY_END, 7L)
		shouldThrow<IllegalArgumentException> {
			fact.copy(stepCount = 8L)
		}
		val day = day(
			facts = listOf(fact),
			coverage = PortableAmbientStepsCoverage.COMPLETE,
			causes = emptyList(),
		)
		val archive = PortableAmbientStepsArchiveV1.create(listOf(day))
		shouldThrow<IllegalArgumentException> {
			archive.copy(days = listOf(day.copy(retainedStepCount = 8L)))
		}
	}

	@Test
	fun `noncanonical overlapping facts and gaps fail closed`() {
		val first = fact("first", 0L, 2_000L, 1L)
		val second = fact("second", 1_000L, DAY_END, 2L)
		shouldThrow<IllegalArgumentException> {
			day(
				facts = listOf(first, second),
				coverage = PortableAmbientStepsCoverage.PARTIAL,
				causes = listOf(PortableAmbientStepsPartialCause.OUTSIDE_AUTHORITY),
			)
		}
	}

	@Test
	fun `archive and deletion scope identities use distinct portable namespaces`() {
		val day = day(
			facts = listOf(fact("local-fact", 0L, DAY_END, 7L)),
			coverage = PortableAmbientStepsCoverage.COMPLETE,
			causes = emptyList(),
		)
		val archive = PortableAmbientStepsArchiveV1.create(listOf(day))

		archive.identity shouldBe AmbientStepsPortableOpaqueIdentity.derive(
			AmbientStepsPortableIdentityKind.ARCHIVE,
			archive.contentChecksum.value,
		)
		day.deletionScopeIdentity shouldBe AmbientStepsPortableOpaqueIdentity.derive(
			AmbientStepsPortableIdentityKind.DELETION_SCOPE,
			day.identity.value,
		)
		archive.identity shouldNotBe day.identity
		day.deletionScopeIdentity shouldNotBe day.identity
	}

	private fun fact(
		localId: String,
		startTimeMs: Long,
		endTimeMs: Long,
		stepCount: Long,
	) = PortableAmbientStepsFactV1.create(
		AmbientStepsPortableOpaqueIdentity.derive(AmbientStepsPortableIdentityKind.FACT, localId),
		startTimeMs,
		endTimeMs,
		stepCount,
	)

	private fun day(
		facts: List<PortableAmbientStepsFactV1>,
		gaps: List<PortableAmbientStepsGapV1> = emptyList(),
		coverage: PortableAmbientStepsCoverage,
		causes: List<PortableAmbientStepsPartialCause>,
	): PortableAmbientStepsDayV1 = PortableAmbientStepsDayV1.create(
		identity = AmbientStepsPortableOpaqueIdentity.derive(
			AmbientStepsPortableIdentityKind.DAY,
			"0|UTC|0|$DAY_END",
		),
		structuralEpochDay = 0L,
		storedZoneId = "UTC",
		structuralDayStartTimeMs = 0L,
		structuralDayEndTimeMs = DAY_END,
		retainedFromTimeMs = null,
		coverage = coverage,
		partialCauses = causes,
		retainedStepCount = facts.sumOf { it.stepCount },
		facts = facts,
		gaps = gaps,
	)

	private companion object {
		const val DAY_END = 86_400_000L
	}
}
