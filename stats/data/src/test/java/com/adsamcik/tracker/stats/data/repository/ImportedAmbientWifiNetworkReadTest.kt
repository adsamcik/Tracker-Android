package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiFactEntity
import com.adsamcik.tracker.shared.base.database.data.PortableRadioChildIntegrity
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class ImportedAmbientWifiNetworkReadTest {
	@Test
	fun `imported network details require the exact parent and original content`() {
		val parent = mockk<ImportedAmbientWifiFactEntity>()
		every { parent.archiveId } returns ARCHIVE
		every { parent.factId } returns FACT
		every { parent.semanticRevision } returns 2L
		val checksum = PortableRadioChildIntegrity.digest("wifi", listOf(FACT), listOf(
			2L, 3, 7_000L, 2_000L, 1L, "aa:bb:cc:dd:ee:ff", "Cafe", 2_412, -55,
		))
		val child = ImportedAmbientWifiChildEntity(ARCHIVE, FACT, 2L, 3, 7_000L,
			2_000L, 1L, "aa:bb:cc:dd:ee:ff", "Cafe", 2_412, -55, checksum)

		assertEquals("Cafe", child.toObservedNetworkIfValid(parent)?.ssid)
		assertNull(child.copy(archiveId = "other").toObservedNetworkIfValid(parent))
		assertNull(child.copy(ssid = "changed").toObservedNetworkIfValid(parent))
		val hiddenChecksum = PortableRadioChildIntegrity.digest("wifi", listOf(FACT), listOf(
			2L, 3, 7_000L, 2_000L, 1L, "", "", 2_412, -55,
		))
		val hidden = child.copy(bssid = "", ssid = "", contentChecksum = hiddenChecksum)
		assertNull(hidden.toObservedNetworkIfValid(parent)?.ssid)
		assertNull(hidden.toObservedNetworkIfValid(parent)?.bssid)
		val invalidTime = child.copy(providerTimestampNanos = 0L,
			contentChecksum = PortableRadioChildIntegrity.digest("wifi", listOf(FACT), listOf(
				2L, 3, 0L, 2_000L, 1L, "aa:bb:cc:dd:ee:ff", "Cafe", 2_412, -55,
			)))
		assertNull(invalidTime.toObservedNetworkIfValid(parent))
		val spaces = child.copy(ssid = "   ", contentChecksum =
			PortableRadioChildIntegrity.digest("wifi", listOf(FACT), listOf(
				2L, 3, 7_000L, 2_000L, 1L, "aa:bb:cc:dd:ee:ff", "   ", 2_412, -55,
			)))
		assertEquals("   ", spaces.toObservedNetworkIfValid(parent)?.ssid)
	}

	private companion object {
		const val ARCHIVE = "archive-a"
		const val FACT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	}
}
