package com.adsamcik.tracker.shared.base.database.data

import org.junit.Assert.assertFalse
import org.junit.Test

class RadioChildEntityRedactionTest {
	private val checksum = "a".repeat(64)

	@Test fun `native child rows do not stringify network tower or location data`() {
		val wifiSession = WifiCapturedChildEntity("writer", 1, "fact", 1, 0,
			1L, 1L, 0L, "aa:bb:cc:dd:ee:ff", "Home", 2_412, -50, checksum)
		val wifiBackground = AmbientWifiChildEntity("writer", 1, "fact", 1, 0,
			1L, 1L, 0L, "aa:bb:cc:dd:ee:ff", "Home", 2_412, -50, checksum)
		val cellSession = CellCapturedChildEntity("writer", 1, "fact", 1, 0,
			1L, 1L, 0L, "310:260:123", "LTE", true, -75, checksum)
		val cellBackground = AmbientCellChildEntity("writer", 1, "fact", 1, 0,
			1L, 1L, 0L, "310:260:123", "LTE", true, -75, checksum)
		listOf(wifiSession, wifiBackground, cellSession, cellBackground).forEach { row ->
			assertFalse(row.toString().contains("Home"))
			assertFalse(row.toString().contains("aa:bb"))
			assertFalse(row.toString().contains("310:260"))
		}
	}

	@Test fun `migrated child rows do not stringify saved identities or coordinates`() {
		val wifi = LegacyWifiChildEntity(1L, 1L, "aa:bb", "Home", "WPA2",
			2_412, -50, 50_000_000, 14_000_000, "legacy", 1L, null, null)
		val cell = LegacyCellChildEntity(2L, 1L, 123_456L, 1, 310, 260, 13,
			-75, 50_000_000, 14_000_000, "legacy", 1L, null, null)
		listOf(wifi, cell).forEach { row ->
			assertFalse(row.toString().contains("Home"))
			assertFalse(row.toString().contains("aa:bb"))
			assertFalse(row.toString().contains("123456"))
			assertFalse(row.toString().contains("50000000"))
		}
	}
}
