package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CellSample - cell tower observation entity")
class CellSampleTest {

	private fun sample(
		id: Long = 0,
		timeMs: Long = 1000L,
		cellId: Long = 12345L,
		lac: Int = 100,
		mcc: Int = 230,
		mnc: Int = 1,
		networkType: Int = 13,
		signalStrength: Int = -80,
		latE7: Int? = 500000000,
		lonE7: Int? = 140000000,
		provenance: CoordinateProvenance = CoordinateProvenance.DIRECT,
		createdAt: Long = 1000L
	) = CellSample(id, timeMs, cellId, lac, mcc, mnc, networkType, signalStrength, latE7, lonE7, provenance, createdAt)

	@Nested
	@DisplayName("Construction")
	inner class Construction {
		@Test
		fun `stores all fields`() {
			val s = sample()
			s.timeMs shouldBe 1000L
			s.cellId shouldBe 12345L
			s.lac shouldBe 100
			s.mcc shouldBe 230
			s.mnc shouldBe 1
			s.networkType shouldBe 13
			s.signalStrength shouldBe -80
			s.latE7 shouldBe 500000000
			s.lonE7 shouldBe 140000000
			s.provenance shouldBe CoordinateProvenance.DIRECT
		}

		@Test
		fun `nullable coordinates accept null`() {
			val s = sample(latE7 = null, lonE7 = null, provenance = CoordinateProvenance.UNKNOWN)
			s.latE7 shouldBe null
			s.lonE7 shouldBe null
			s.provenance shouldBe CoordinateProvenance.UNKNOWN
		}
	}

	@Nested
	@DisplayName("Data class features")
	inner class DataClassFeatures {
		@Test
		fun `equality`() {
			sample() shouldBe sample()
		}

		@Test
		fun `inequality`() {
			sample(cellId = 1) shouldNotBe sample(cellId = 2)
		}

		@Test
		fun `copy`() {
			val s = sample().copy(provenance = CoordinateProvenance.NEAREST_LOCATION)
			s.provenance shouldBe CoordinateProvenance.NEAREST_LOCATION
			s.cellId shouldBe 12345L
		}
	}
}
