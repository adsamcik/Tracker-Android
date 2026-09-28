package com.adsamcik.tracker.impexp.importer.file

import com.adsamcik.tracker.impexp.portable.LocationFileMeasurementV1
import com.adsamcik.tracker.impexp.portable.LocationFileOrigin
import com.adsamcik.tracker.impexp.portable.LocationFilePointV1
import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.shared.model.MotionState
import com.adsamcik.tracker.shared.model.SampleQuality
import io.kotest.matchers.shouldBe
import org.junit.Test

class LocationFileImportMapperTest {
	@Test fun `saved measurements restore without live tracking identity`() {
		val saved = LocationFilePointV1(
			pointId = "point-1",
			origin = LocationFileOrigin.SESSION,
			measurement = LocationFileMeasurementV1(
				timeMs = 1_700_000_000_000L,
				latE7 = 501_234_567,
				lonE7 = 144_567_890,
				altitudeM = 310.5f,
				rawGpsAltitudeM = 355.25f,
				horizontalAccuracyM = 8.25f,
				verticalAccuracyM = 12.5f,
				speedMps = 3.5f,
				speedAccuracyMps = 0.4f,
				rawSpeedMps = 3.75f,
				rawSpeedAccuracyMps = 0.5f,
				bearingDeg = 47.5f,
				bearingAccuracyDeg = 2.5f,
				provider = "fused",
				quality = SampleQuality.HIGH,
				motionState = MotionState.MOVING,
				permissionPrecision = "FINE",
				isMock = false,
				altitudeDatum = AltitudeDatum.UNKNOWN_LEGACY,
				altitudeSource = AltitudeSource.IMPORTED,
				altitudeConversionStatus = AltitudeConversionStatus.UNKNOWN_LEGACY,
				rawGpsAltitudeDatum = AltitudeDatum.UNKNOWN_LEGACY,
			),
		)

		val imported = saved.toImportedSample(createdAtMs = 1_800_000_000_000L)
		imported.timeMs shouldBe saved.measurement.timeMs
		imported.latE7 shouldBe saved.measurement.latE7
		imported.lonE7 shouldBe saved.measurement.lonE7
		imported.altitudeM shouldBe saved.measurement.altitudeM
		imported.rawGpsAltitudeM shouldBe saved.measurement.rawGpsAltitudeM
		imported.hAccM shouldBe saved.measurement.horizontalAccuracyM
		imported.vAccM shouldBe saved.measurement.verticalAccuracyM
		imported.speedMps shouldBe saved.measurement.speedMps
		imported.rawPlatformSpeedMps shouldBe saved.measurement.rawSpeedMps
		imported.bearingDeg shouldBe saved.measurement.bearingDeg
		imported.provider shouldBe "fused"
		imported.quality.name shouldBe "HIGH"
		imported.motionState?.name shouldBe "MOVING"
		imported.permissionPrecision shouldBe "FINE"
		imported.createdAt shouldBe 1_800_000_000_000L
		imported.sourceEventId shouldBe null
		imported.sourceSignalId shouldBe null
		imported.clockDomainId shouldBe null
		imported.policy shouldBe null
		imported.elapsedRealtimeNanos shouldBe 0L
	}
}
