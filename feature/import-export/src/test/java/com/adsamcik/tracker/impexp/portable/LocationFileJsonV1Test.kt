package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.shared.model.MotionState
import com.adsamcik.tracker.shared.model.SampleQuality
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationFileJsonV1Test {
	@Test fun `measured point values and honest origin survive a file round trip`() = runBlocking {
		val expected = LocationFilePointV1(
			pointId = "point-123",
			origin = LocationFileOrigin.UNKNOWN,
			measurement = measurement(),
		)
		val bytes = ByteArrayOutputStream()
		val writer = LocationFileJsonV1().writer(bytes)
		writer.point(expected)
		assertEquals(1, writer.finish())

		val actual = mutableListOf<LocationFilePointV1>()
		assertEquals(1, LocationFileJsonV1().read(ByteArrayInputStream(bytes.toByteArray())) {
			actual += it
		})
		assertEquals(listOf(expected), actual)
	}

	@Test fun `valid user edit to measurements is accepted without changing point identity`() = runBlocking {
		val bytes = ByteArrayOutputStream()
		LocationFileJsonV1().writer(bytes).apply {
			point(LocationFilePointV1("point-123", LocationFileOrigin.SESSION, measurement()))
			finish()
		}
		val edited = bytes.toString(Charsets.UTF_8.name()).replace("500000000", "500000001")
		val points = mutableListOf<LocationFilePointV1>()
		LocationFileJsonV1().read(ByteArrayInputStream(edited.toByteArray(Charsets.UTF_8))) {
			points += it
		}
		assertEquals("point-123", points.single().pointId)
		assertEquals(500_000_001, points.single().measurement.latE7)
	}

	@Test fun `truncated file with no closing count is rejected`() {
		val bytes = ByteArrayOutputStream()
		LocationFileJsonV1().writer(bytes).apply {
			point(LocationFilePointV1("point-123", LocationFileOrigin.SESSION, measurement()))
			finish()
		}
		val truncated = bytes.toString(Charsets.UTF_8.name()).trimEnd().lines().dropLast(1)
			.joinToString("\n", postfix = "\n")
		assertThrows(InvalidLocationFile::class.java) {
			runBlocking { LocationFileJsonV1().read(ByteArrayInputStream(truncated.toByteArray())) {} }
		}
	}

	@Test fun `partial saved coordinates remain partial after import`() = runBlocking {
		val partial = listOf(
			"""{"format":"tracker-location","version":1}""",
			"""{"type":"point","id":"p","origin":"unknown","timeMs":1,"latE7":500000000,"provider":"gps","quality":"high","permissionPrecision":"FINE","isMock":false,"altitudeDatum":"unknown_legacy","altitudeSource":"unknown_legacy","altitudeConversionStatus":"unknown_legacy","rawGpsAltitudeDatum":"unknown_legacy"}""",
			"""{"type":"end","pointCount":1}""",
		).joinToString("\n", postfix = "\n")
		val points = mutableListOf<LocationFilePointV1>()
		LocationFileJsonV1().read(ByteArrayInputStream(partial.toByteArray())) {
			points += it
		}
		assertEquals(500_000_000, points.single().measurement.latE7)
		assertEquals(null, points.single().measurement.lonE7)
	}

	@Test fun `invalid edited point ID cannot change on UTF-8 re-export`() {
		assertThrows(IllegalArgumentException::class.java) {
			LocationFilePointV1("\uD800", LocationFileOrigin.UNKNOWN, measurement())
		}
	}

	@Test fun `unusual retained measurements remain exact in a backup`() = runBlocking {
		val unusual = LocationFilePointV1(
			"point-legacy", LocationFileOrigin.UNKNOWN,
			measurement().copy(horizontalAccuracyM = -1f, rawGpsAltitudeM = Float.POSITIVE_INFINITY),
		)
		val bytes = ByteArrayOutputStream()
		LocationFileJsonV1().writer(bytes).apply { point(unusual); finish() }
		val points = mutableListOf<LocationFilePointV1>()
		LocationFileJsonV1().read(ByteArrayInputStream(bytes.toByteArray())) { points += it }
		assertEquals(unusual, points.single())
	}

	private fun measurement() = LocationFileMeasurementV1(
		timeMs = 1_727_488_000_000L,
		latE7 = 500_000_000,
		lonE7 = 140_000_000,
		altitudeM = 255.25f,
		rawGpsAltitudeM = 300.5f,
		horizontalAccuracyM = 8.5f,
		verticalAccuracyM = 12f,
		speedMps = 1.5f,
		speedAccuracyMps = 0.4f,
		rawSpeedMps = 1.4f,
		rawSpeedAccuracyMps = 0.5f,
		bearingDeg = 87.5f,
		bearingAccuracyDeg = 10f,
		provider = "gps",
		quality = SampleQuality.HIGH,
		motionState = MotionState.MOVING,
		permissionPrecision = "FINE",
		isMock = false,
		altitudeDatum = AltitudeDatum.UNKNOWN_LEGACY,
		altitudeSource = AltitudeSource.UNKNOWN_LEGACY,
		altitudeConversionStatus = AltitudeConversionStatus.UNKNOWN_LEGACY,
		rawGpsAltitudeDatum = AltitudeDatum.UNKNOWN_LEGACY,
	)
}
