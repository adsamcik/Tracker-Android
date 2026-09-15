package com.adsamcik.tracker.shared.base.database.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class ImportedCellEntityTest {
	@Test
	fun `imported observation requires canonical uncertainty upper bound`() {
		shouldThrow<IllegalArgumentException> {
			observation(latestPossibleTimeMs = 113L)
		}
		shouldThrow<ArithmeticException> {
			observation(
				observedTimeMs = Long.MAX_VALUE,
				latestPossibleTimeMs = Long.MAX_VALUE,
				wallTimeUncertaintyMs = 1L,
			)
		}
	}

	@Test
	fun `imported observation authenticates conservative uncertainty lower bound without clamping`() {
		observation().copy(coverageStartTimeMs = 108L).coverageStartTimeMs shouldBe 108L
		shouldThrow<IllegalArgumentException> { observation().copy(coverageStartTimeMs = 110L) }
		shouldThrow<IllegalArgumentException> {
			observation().copy(coverageStartTimeMs = 0L, observedTimeMs = 1L,
				wallTimeUncertaintyMs = 2L, latestPossibleTimeMs = 3L)
		}
	}

	@Test
	fun `imported observation authenticates known and weak quality bucket arithmetic`() {
		shouldThrow<IllegalArgumentException> { observation().copy(knownQualityObservationCount = 0) }
		shouldThrow<IllegalArgumentException> {
			observation().copy(weakObservationCount = 1, allKnownQualityIsWeak = true)
		}
		val weak = observation().copy(qualityGoodCount = 0, qualityPoorCount = 1,
			weakObservationCount = 1, allKnownQualityIsWeak = true)
		weak.knownQualityObservationCount shouldBe 1
		weak.weakObservationCount shouldBe 1
		val unknown = observation().copy(qualityGoodCount = 0, qualityUnknownCount = 1,
			knownQualityObservationCount = 0)
		unknown.knownQualityObservationCount shouldBe 0
		unknown.allKnownQualityIsWeak shouldBe false
		shouldThrow<ArithmeticException> {
			observation().copy(qualityNoneOrUnknownCount = Int.MAX_VALUE, qualityPoorCount = 1)
		}
	}

	@Test
	fun `run deletion marker authenticates entry scope and generation`() {
		val marker = ImportedCellDeletionGenerationEntity.create(
			runIdentity = digest('1'),
			entryIdentity = digest('2'),
			deletionScopeDigest = digest('3'),
			collectedDataEpoch = 4L,
			generation = 1L,
			deletedAtMs = 5L,
		)

		marker.generation shouldBe 1L
		shouldThrow<IllegalArgumentException> { marker.copy(entryIdentity = digest('4')) }
	}

	private fun observation(
		observedTimeMs: Long = 110L,
		latestPossibleTimeMs: Long = 112L,
		wallTimeUncertaintyMs: Long = 2L,
	) = ImportedCellObservationEntity(
		entryIdentity = digest('1'),
		entryImportRevision = 1L,
		runIdentity = digest('2'),
		identity = digest('3'),
		semanticRevision = 1L,
		supersedesSemanticRevision = null,
		aggregateOwnerIdentity = null,
		aggregateOwnerSemanticRevision = null,
		contentChecksum = digest('4'),
		coverageStartTimeMs = 100L,
		observedTimeMs = observedTimeMs,
		latestPossibleTimeMs = latestPossibleTimeMs,
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		storedZoneId = "UTC",
		childCompleteness = "COMPLETE",
		subscriptionGrouping = "UNKNOWN",
		submittedChildCount = 1,
		acceptedChildCount = 1,
		staleChildCount = 0,
		futureTimeChildCount = 0,
		missingTimeChildCount = 0,
		clockUnverifiableChildCount = 0,
		authorityMismatchChildCount = 0,
		unsupportedTechnologyChildCount = 0,
		observationCount = 1,
		registeredObservationCount = 1,
		gsmCount = 0,
		cdmaCount = 0,
		wcdmaCount = 0,
		tdscdmaCount = 0,
		lteCount = 1,
		nrCount = 0,
		qualityUnknownCount = 0,
		qualityNoneOrUnknownCount = 0,
		qualityPoorCount = 0,
		qualityModerateCount = 0,
		qualityGoodCount = 1,
		qualityGreatCount = 0,
		weakObservationCount = 0,
		knownQualityObservationCount = 1,
		allKnownQualityIsWeak = false,
		qualityFlags = 0L,
		qualityConfidence = 1.0,
	)

	private fun digest(value: Char) = value.toString().repeat(64)
}
