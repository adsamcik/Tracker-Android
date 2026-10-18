package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import org.junit.Test

class RawSourceSessionCompletenessTest {
	@Test
	fun `empty Activity high water remains zero without requiring a WAL observation`() {
		val completeness = requireNotNull(raw().validatedOrNull())

		completeness.lastAdmissionOrdinal shouldBe 0L
		completeness.lastSourceSequence shouldBe 0L
		completeness.appDrainComplete shouldBe true
		completeness.providerCoverage shouldBe "PROVIDER_COMPLETENESS_UNOBSERVABLE"
	}

	@Test
	fun `positive and absent high waters retain their existing meaning`() {
		raw(7L, 4L).validatedOrNull()?.lastAdmissionOrdinal shouldBe 7L
		raw(null, null).validatedOrNull()?.lastAdmissionOrdinal shouldBe null
		raw(null, null).validatedOrNull()?.appDrainComplete shouldBe true
	}

	@Test
	fun `negative and unpaired high waters remain unverifiable`() {
		raw(-1L, 0L).validatedOrNull() shouldBe null
		raw(0L, -1L).validatedOrNull() shouldBe null
		raw(0L, null).validatedOrNull() shouldBe null
		raw(null, 0L).validatedOrNull() shouldBe null
	}

	@Test
	fun `zero high water still requires integer SQL storage`() {
		val malformedTypes = requireNotNull(raw().storageClassSignature).split('|').toMutableList()
		malformedTypes[5] = "real"

		raw().copy(storageClassSignature = malformedTypes.joinToString("|"))
			.validatedOrNull() shouldBe null
	}

	private fun raw(ordinal: Long? = 0L, sequence: Long? = 0L): RawSourceSessionCompleteness {
		val values = listOf("logical", "run", SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			"activity-owner", 1L, ordinal, sequence, 1L, "PROVIDER_COMPLETENESS_UNOBSERVABLE",
			"PARTIAL_UNOBSERVABLE", null, null, 2_000L)
		return RawSourceSessionCompleteness(
			storageClassSignature = values.joinToString("|") { value ->
				when (value) {
					null -> "null"
					is String -> "text"
					else -> "integer"
				}
			},
			logicalTrackingId = "logical", serviceRunId = "run",
			sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY.toLong(),
			sourceInstanceId = "activity-owner", registrationGeneration = 1L,
			lastAdmissionOrdinal = ordinal, lastSourceSequence = sequence, appDrainComplete = 1L,
			providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE", stopStatus = "PARTIAL_UNOBSERVABLE",
			unresolvedSequenceStart = null, unresolvedSequenceEnd = null, updatedAtMs = 2_000L,
		)
	}
}
