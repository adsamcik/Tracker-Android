package com.adsamcik.tracker.tracker.component.consumer.post

import com.adsamcik.tracker.shared.base.data.ProcessedAltitudeData
import com.adsamcik.tracker.shared.model.AltitudeDatum
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SkiSegmentWriterAltitudeTest {
	@Test
	fun `uses compatible Android model MSL estimates within one clock domain`() {
		processedAltitudeDeltaIfContinuous(
			altitude(550f, AltitudeDatum.ANDROID_MODEL_MSL, "boot-a"),
			altitude(430f, AltitudeDatum.FUSED_ANDROID_MODEL_MSL, "boot-a"),
		) shouldBe 120f
	}

	@Test
	fun `does not bridge unknown datum datum change or clock-domain boundary`() {
		val msl = altitude(550f, AltitudeDatum.ANDROID_MODEL_MSL, "boot-a")

		processedAltitudeDeltaIfContinuous(
			msl,
			altitude(430f, AltitudeDatum.UNKNOWN_LEGACY, "boot-a"),
		).shouldBeNull()
		processedAltitudeDeltaIfContinuous(
			msl,
			altitude(430f, AltitudeDatum.WGS84_ELLIPSOID, "boot-a"),
		).shouldBeNull()
		processedAltitudeDeltaIfContinuous(
			msl,
			altitude(430f, AltitudeDatum.ANDROID_MODEL_MSL, "boot-b"),
		).shouldBeNull()
	}

	private fun altitude(
		value: Float,
		datum: AltitudeDatum,
		clockDomainId: String,
	) = ProcessedAltitudeData(
		altitudeM = value,
		datum = datum,
		clockDomainId = clockDomainId,
	)
}
