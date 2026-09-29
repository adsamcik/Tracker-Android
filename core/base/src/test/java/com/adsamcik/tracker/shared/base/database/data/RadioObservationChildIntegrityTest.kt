package com.adsamcik.tracker.shared.base.database.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RadioObservationChildIntegrityTest {
	@Test fun `same Wi-Fi reading belongs to different purpose revisions independently`() {
		val owner = RadioChildOwner(RadioChildPurpose.SESSION, "writer", 1, "fact", 1, 0)
		val child = WifiChildChecksumInput(owner, 100, 1000, 5, "aa:bb", "Cafe", 2412, -60)
		val sessionChecksum = RadioObservationChildIntegrity.wifi(child)
		assertEquals(64, sessionChecksum.length)
		assertEquals(sessionChecksum, RadioObservationChildIntegrity.wifi(child))
		assertNotEquals(sessionChecksum, RadioObservationChildIntegrity.wifi(
			child.copy(owner = owner.copy(purpose = RadioChildPurpose.BACKGROUND)),
		))
		assertNotEquals(sessionChecksum, RadioObservationChildIntegrity.wifi(child.copy(ssid = "Edited")))
		assertFalse(child.toString().contains("Cafe"))
		assertFalse(child.toString().contains("aa:bb"))
	}

	@Test fun `Cell identity and absence are distinct`() {
		val owner = RadioChildOwner(RadioChildPurpose.BACKGROUND, "writer", 1, "fact", 2, 3)
		val child = CellChildChecksumInput(owner, 100, 1000, 5, null, "LTE", true, -90)
		assertNotEquals(
			RadioObservationChildIntegrity.cell(child),
			RadioObservationChildIntegrity.cell(child.copy(towerIdentity = "")),
		)
		assertNotEquals(
			RadioObservationChildIntegrity.cell(child),
			RadioObservationChildIntegrity.cell(child.copy(registered = false)),
		)
		assertFalse(child.copy(towerIdentity = "310:260:123:456").toString()
			.contains("310:260:123:456"))
	}
}
