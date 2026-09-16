package com.adsamcik.tracker.stats.api.repository

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AmbientCellPortableTransferTest {
	@Test
	fun `ambient Cell archive preserves partial subscription coverage without identities`() {
		val zone = ZoneId.of("America/New_York")
		val date = LocalDate.of(2026, 3, 8)
		val observed = date.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000L
		val fact = AmbientCellFact(
			identity = AmbientCellPortableIntegrity.opaqueIdentity("fact", "cell-1"),
			origin = AmbientCellOrigin.LOCAL_DEVICE,
			coverageStartTimeMs = observed - 1_000L,
			observedTimeMs = observed,
			latestPossibleTimeMs = observed + 1L,
			structuralEpochDay = date.toEpochDay(),
			storedZoneId = zone.id,
			coverage = AmbientCellCoverage.UNVERIFIABLE,
			subscriptionCompleteness = AmbientCellSubscriptionCompleteness.PARTIAL,
			observationCount = 2,
			registeredObservationCount = 1,
			technologyMix = AmbientCellTechnologyMix(0, 0, 0, 0, 1, 1),
			qualityDistribution = AmbientCellQualityDistribution(0, 0, 1, 0, 1, 0),
		)
		val portable = AmbientCellPortableIntegrity.createFact(fact)
		val archive = AmbientCellPortableIntegrity.createArchive(
			AmbientCellPortableIntegrity.opaqueIdentity("archive", "cell-dst"),
			listOf(portable),
			emptyList(),
		)

		assertEquals(
			AmbientCellSubscriptionCompleteness.PARTIAL,
			archive.facts.single().subscriptionCompleteness,
		)
		assertTrue("captured-cell" !in archive.format)
		assertEquals(date.toEpochDay(), archive.facts.single().structuralEpochDay)
	}

	@Test
	fun `Cell contracts reject missing explicit origin and invalid import epoch`() {
		assertFailsWith<IllegalArgumentException> {
			AmbientCellDayReadRequest(0L, "UTC", emptySet(), 1)
		}
		assertFailsWith<IllegalArgumentException> {
			ImportPortableAmbientCellRequest(
				archive(),
				PortableAmbientCellImportReceipt("job", "entry", "source", 10L),
				-1L,
			)
		}
	}

	private fun archive(): PortableAmbientCellArchiveV1 {
		val fact = AmbientCellFact(
			AmbientCellPortableIntegrity.opaqueIdentity("fact", "cell"),
			AmbientCellOrigin.PORTABLE_IMPORT,
			1L,
			2L,
			3L,
			0L,
			"UTC",
			AmbientCellCoverage.COMPLETE,
			AmbientCellSubscriptionCompleteness.UNKNOWN,
			1,
			1,
			AmbientCellTechnologyMix(0, 0, 0, 0, 1, 0),
			AmbientCellQualityDistribution(0, 0, 0, 0, 1, 0),
		)
		return AmbientCellPortableIntegrity.createArchive(
			AmbientCellPortableIntegrity.opaqueIdentity("archive", "cell"),
			listOf(AmbientCellPortableIntegrity.createFact(fact)),
			emptyList(),
		)
	}
}
