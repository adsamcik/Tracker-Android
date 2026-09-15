package com.adsamcik.tracker.stats.api.repository

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AmbientWifiPortableTransferTest {
	@Test
	fun `ambient Wi-Fi archive is separate identity-free and round trips DST structure`() {
		val zone = ZoneId.of("Europe/Prague")
		val date = LocalDate.of(2026, 10, 25)
		val observed = date.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000L
		val identity = AmbientWifiPortableIntegrity.opaqueIdentity("fact", "local-wifi-1")
		val fact = AmbientWifiFact(
			identity,
			AmbientWifiOrigin.LOCAL_DEVICE,
			observed - 1_000L,
			observed,
			observed + 1L,
			date.toEpochDay(),
			zone.id,
			AmbientWifiCoverage.UNVERIFIABLE,
			3,
			1,
			1,
			1,
			0,
			-40,
			-80,
			-60.0,
		)
		val portable = AmbientWifiPortableIntegrity.createFact(fact)
		val archive = AmbientWifiPortableIntegrity.createArchive(
			AmbientWifiPortableIntegrity.opaqueIdentity("archive", "wifi-dst"),
			listOf(portable),
			listOf(
				AmbientWifiPortableIntegrity.createGap(AmbientWifiGap(
					AmbientWifiPortableIntegrity.opaqueIdentity("gap", "wifi-dst"),
					AmbientWifiOrigin.LOCAL_DEVICE,
					date.toEpochDay(),
					observed + 10L,
					observed + 20L,
					zone.id,
					"PROVIDER_COMPLETENESS_UNVERIFIABLE",
				)),
			),
		)

		assertEquals(AmbientWifiPortableFormatV1.FORMAT, archive.format)
		assertTrue("captured-wifi" !in archive.format)
		assertEquals(date.toEpochDay(), archive.facts.single().structuralEpochDay)
		assertEquals(portable.contentChecksum, archive.facts.single().contentChecksum)
		assertEquals(AmbientWifiOrigin.LOCAL_DEVICE, archive.gaps.single().origin)
		assertFailsWith<IllegalArgumentException> {
			portable.copy(
				contentChecksum = AmbientWifiPortableIntegrity.opaqueIdentity(
					"corrupt",
					portable.contentChecksum,
				),
			)
		}
	}

	@Test
	fun `reads and imports require explicit origin receipt and epoch`() {
		assertFailsWith<IllegalArgumentException> {
			AmbientWifiReadRequest(0L, 1L, emptySet(), 1)
		}
		assertFailsWith<IllegalArgumentException> {
			PortableAmbientWifiImportReceipt("", "entry", "source", 0L)
		}
		assertFailsWith<IllegalArgumentException> {
			ImportPortableAmbientWifiRequest(
				archive = archive(),
				receipt = PortableAmbientWifiImportReceipt("job", "entry", "source", 10L),
				expectedCollectedDataEpoch = -1L,
			)
		}
	}

	@Test
	fun `gap-only archive is valid but a latest-only correction lineage is not`() {
		val gap = AmbientWifiPortableIntegrity.createGap(
			AmbientWifiGap(
				AmbientWifiPortableIntegrity.opaqueIdentity("gap", "only"),
				AmbientWifiOrigin.PORTABLE_IMPORT,
				0L,
				1L,
				2L,
				"UTC",
				"PROVIDER_COMPLETENESS_UNVERIFIABLE",
			),
		)
		val gapOnly = AmbientWifiPortableIntegrity.createArchive(
			AmbientWifiPortableIntegrity.opaqueIdentity("archive", "gap-only"),
			emptyList(),
			listOf(gap),
		)
		assertEquals(1, gapOnly.gaps.size)
		val revisionTwo = AmbientWifiPortableIntegrity.createFact(
			archive().facts.single().let { stored ->
				AmbientWifiFact(
					stored.identity,
					stored.origin,
					stored.coverageStartTimeMs,
					stored.observedTimeMs,
					stored.latestPossibleTimeMs,
					stored.structuralEpochDay,
					stored.storedZoneId,
					stored.coverage,
					stored.observationCount,
					stored.twoPointFourGhzCount,
					stored.fiveGhzCount,
					stored.sixGhzCount,
					stored.otherBandCount,
					stored.strongestSignalDbm,
					stored.weakestSignalDbm,
					stored.meanSignalDbm,
					2L,
					1L,
				)
			},
		)
		assertFailsWith<IllegalArgumentException> {
			AmbientWifiPortableIntegrity.createArchive(
				AmbientWifiPortableIntegrity.opaqueIdentity("archive", "latest-only"),
				listOf(revisionTwo),
				emptyList(),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			AmbientWifiPortableIntegrity.createArchive(
				AmbientWifiPortableIntegrity.opaqueIdentity("archive", "overflow"),
				List(AmbientWifiPortableFormatV1.MAX_FACTS + 1) { archive().facts.single() },
				emptyList(),
			)
		}
	}

	private fun archive(): PortableAmbientWifiArchiveV1 {
		val fact = AmbientWifiFact(
			AmbientWifiPortableIntegrity.opaqueIdentity("fact", "wifi"),
			AmbientWifiOrigin.PORTABLE_IMPORT,
			1L,
			2L,
			3L,
			0L,
			"UTC",
			AmbientWifiCoverage.COMPLETE,
			1,
			1,
			0,
			0,
			0,
			-50,
			-50,
			-50.0,
		)
		return AmbientWifiPortableIntegrity.createArchive(
			AmbientWifiPortableIntegrity.opaqueIdentity("archive", "wifi"),
			listOf(AmbientWifiPortableIntegrity.createFact(fact)),
			emptyList(),
		)
	}
}
