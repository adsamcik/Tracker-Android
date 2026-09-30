package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellFactEntity
import com.adsamcik.tracker.shared.base.database.data.PortableRadioChildIntegrity
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class ImportedAmbientCellTowerReadTest {
	@Test
	fun `imported tower details require exact parent and original content`() {
		val parent = mockk<ImportedAmbientCellFactEntity>()
		every { parent.archiveId } returns ARCHIVE
		every { parent.factId } returns FACT
		every { parent.semanticRevision } returns 2L
		val checksum = PortableRadioChildIntegrity.digest("cell", listOf(FACT), listOf(
			2L, 3, 7_000L, 2_000L, 1L, "LTE:123", "LTE", true, -72,
		))
		val child = ImportedAmbientCellChildEntity(ARCHIVE, FACT, 2L, 3, 7_000L,
			2_000L, 1L, "LTE:123", "LTE", true, -72, checksum)

		assertEquals("LTE:123", child.toObservedTowerIfValid(parent)?.identity)
		assertNull(child.copy(archiveId = "other").toObservedTowerIfValid(parent))
		assertNull(child.copy(towerIdentity = "changed").toObservedTowerIfValid(parent))
		val hiddenChecksum = PortableRadioChildIntegrity.digest("cell", listOf(FACT), listOf(
			2L, 3, 7_000L, 2_000L, 1L, "", "LTE", true, -72,
		))
		val hidden = child.copy(towerIdentity = "", contentChecksum = hiddenChecksum)
		assertNull(hidden.toObservedTowerIfValid(parent)?.identity)
		val invalidTime = child.copy(providerTimestampNanos = 0L,
			contentChecksum = PortableRadioChildIntegrity.digest("cell", listOf(FACT), listOf(
				2L, 3, 0L, 2_000L, 1L, "LTE:123", "LTE", true, -72,
			)))
		assertNull(invalidTime.toObservedTowerIfValid(parent))
	}

	private companion object {
		const val ARCHIVE = "archive-a"
		const val FACT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	}
}
