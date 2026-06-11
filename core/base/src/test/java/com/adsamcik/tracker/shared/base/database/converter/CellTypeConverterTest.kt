package com.adsamcik.tracker.shared.base.database.converter

import com.adsamcik.tracker.shared.base.data.CellType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("CellTypeConverter - CellType enum to/from String")
class CellTypeConverterTest {

	private val converter = CellTypeConverter()

	@Nested
	@DisplayName("fromCellType")
	inner class FromCellType {
		@Test
		fun `converts Unknown to string`() {
			converter.fromCellType(CellType.Unknown) shouldBe "Unknown"
		}

		@Test
		fun `converts GSM to string`() {
			converter.fromCellType(CellType.GSM) shouldBe "GSM"
		}

		@Test
		fun `converts CDMA to string`() {
			converter.fromCellType(CellType.CDMA) shouldBe "CDMA"
		}

		@Test
		fun `converts WCDMA to string`() {
			converter.fromCellType(CellType.WCDMA) shouldBe "WCDMA"
		}

		@Test
		fun `converts LTE to string`() {
			converter.fromCellType(CellType.LTE) shouldBe "LTE"
		}

		@Test
		fun `converts NR to string`() {
			converter.fromCellType(CellType.NR) shouldBe "NR"
		}

		@Test
		fun `converts None to string`() {
			converter.fromCellType(CellType.None) shouldBe "None"
		}
	}

	@Nested
	@DisplayName("toCellType")
	inner class ToCellType {
		@Test
		fun `converts string to Unknown`() {
			converter.toCellType("Unknown") shouldBe CellType.Unknown
		}

		@Test
		fun `converts string to LTE`() {
			converter.toCellType("LTE") shouldBe CellType.LTE
		}

		@Test
		fun `converts string to NR`() {
			converter.toCellType("NR") shouldBe CellType.NR
		}

		@Test
		fun `throws for invalid string`() {
			assertThrows<IllegalArgumentException> {
				converter.toCellType("INVALID")
			}
		}
	}

	@Nested
	@DisplayName("Round-trip consistency")
	inner class RoundTrip {
		@Test
		fun `all enum values survive round-trip`() {
			CellType.entries.forEach { cellType ->
				val serialized = converter.fromCellType(cellType)
				val deserialized = converter.toCellType(serialized)
				deserialized shouldBe cellType
			}
		}
	}
}
