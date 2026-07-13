package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.data.GeoRepository
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Signal Aurora")
class SignalAuroraCatalogTest {

	@Test
	fun `catalog pipeline has stable id`() {
		signalAurora(mockk<GeoRepository>()).id shouldBe "signal_aurora"
	}

	@Test
	fun `aurora ramps begin transparent`() {
		AURORA_HALO_RAMP.first().second shouldBe 0x007E57C2
		AURORA_CORE_RAMP.first().second shouldBe 0x0000E5FF
	}
}
