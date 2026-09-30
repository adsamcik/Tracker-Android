package com.adsamcik.tracker.shared.base.database.data

import io.kotest.matchers.shouldBe
import org.junit.Test

class AmbientRadioGroupIdentityTest {
	@Test
	fun `older background radio facts retain their original identity`() {
		val delivery = "a".repeat(64)
		AmbientWifiFactIntegrity.logicalFactId(delivery, 2L, 3L, 4L) shouldBe
			AmbientWifiAuthorityIntegrity.digest("ambient-wifi-fact-v1", delivery, 2L, 3L, 4L)
		AmbientCellFactIntegrity.logicalFactId(delivery, 2L, 3L, 4L) shouldBe
			AmbientCellAuthorityIntegrity.digest("ambient-cell-fact-v1", delivery, 2L, 3L, 4L)
	}

	@Test
	fun `sequential groups in one delivery have independent stable identities`() {
		val delivery = "a".repeat(64)
		val wifiIds = listOf(
			AmbientWifiFactIntegrity.logicalFactId(delivery, 2L, 3L, 4L),
			AmbientWifiFactIntegrity.logicalFactId(delivery, 2L, 3L, 4L, "first-demand"),
			AmbientWifiFactIntegrity.logicalFactId(delivery, 2L, 3L, 4L, "second-demand"),
		)
		wifiIds.distinct().size shouldBe 3
		AmbientWifiFactIntegrity.logicalFactId(delivery, 2L, 3L, 4L, "first-demand") shouldBe wifiIds[1]
		val cellIds = listOf(
			AmbientCellFactIntegrity.logicalFactId(delivery, 2L, 3L, 4L),
			AmbientCellFactIntegrity.logicalFactId(delivery, 2L, 3L, 4L, "first-demand"),
			AmbientCellFactIntegrity.logicalFactId(delivery, 2L, 3L, 4L, "second-demand"),
		)
		cellIds.distinct().size shouldBe 3
		AmbientCellFactIntegrity.logicalFactId(delivery, 2L, 3L, 4L, "second-demand") shouldBe cellIds[2]
	}
}
