package com.adsamcik.tracker.shared.base.database.converter

import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SkiSegmentType
import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("SessionlessTypeConverter - sessionless enum conversions")
class SessionlessTypeConverterTest {

	private val converter = SessionlessTypeConverter()

	@Nested
	@DisplayName("SampleQuality")
	inner class SampleQualityTests {
		@Test
		fun `all values round-trip`() {
			SampleQuality.entries.forEach { quality ->
				val serialized = converter.fromSampleQuality(quality)
				val deserialized = converter.toSampleQuality(serialized)
				deserialized shouldBe quality
			}
		}

		@Test
		fun `fromSampleQuality returns enum name`() {
			converter.fromSampleQuality(SampleQuality.HIGH) shouldBe "HIGH"
			converter.fromSampleQuality(SampleQuality.MEDIUM) shouldBe "MEDIUM"
			converter.fromSampleQuality(SampleQuality.LOW) shouldBe "LOW"
			converter.fromSampleQuality(SampleQuality.COARSE) shouldBe "COARSE"
		}

		@Test
		fun `toSampleQuality parses valid string`() {
			converter.toSampleQuality("HIGH") shouldBe SampleQuality.HIGH
		}

		@Test
		fun `toSampleQuality maps unknown quality to coarse fallback`() {
			converter.toSampleQuality("INVALID") shouldBe SampleQuality.COARSE
		}
	}

	@Nested
	@DisplayName("MotionState")
	inner class MotionStateTests {
		@Test
		fun `all values round-trip`() {
			MotionState.entries.forEach { state ->
				val serialized = converter.fromMotionState(state)
				val deserialized = converter.toMotionState(serialized)
				deserialized shouldBe state
			}
		}

		@Test
		fun `fromMotionState returns enum name`() {
			converter.fromMotionState(MotionState.MOVING) shouldBe "MOVING"
			converter.fromMotionState(MotionState.STILL) shouldBe "STILL"
			converter.fromMotionState(MotionState.UNKNOWN) shouldBe "UNKNOWN"
		}

		@Test
		fun `toMotionState throws for invalid string`() {
			assertThrows<IllegalArgumentException> {
				converter.toMotionState("FAST")
			}
		}
	}

	@Nested
	@DisplayName("CoordinateProvenance")
	inner class CoordinateProvenanceTests {
		@Test
		fun `all values round-trip`() {
			CoordinateProvenance.entries.forEach { prov ->
				val serialized = converter.fromCoordinateProvenance(prov)
				val deserialized = converter.toCoordinateProvenance(serialized)
				deserialized shouldBe prov
			}
		}

		@Test
		fun `fromCoordinateProvenance returns enum name`() {
			converter.fromCoordinateProvenance(CoordinateProvenance.DIRECT) shouldBe "DIRECT"
			converter.fromCoordinateProvenance(CoordinateProvenance.UNKNOWN) shouldBe "UNKNOWN"
			converter.fromCoordinateProvenance(CoordinateProvenance.NEAREST_LOCATION) shouldBe "NEAREST_LOCATION"
			converter.fromCoordinateProvenance(CoordinateProvenance.INTERPOLATED) shouldBe "INTERPOLATED"
			converter.fromCoordinateProvenance(CoordinateProvenance.DWELL_CENTER) shouldBe "DWELL_CENTER"
		}

		@Test
		fun `toCoordinateProvenance throws for invalid string`() {
			assertThrows<IllegalArgumentException> {
				converter.toCoordinateProvenance("GPS")
			}
		}
	}

	@Nested
	@DisplayName("SegmentSource")
	inner class SegmentSourceTests {
		@Test
		fun `all values round-trip`() {
			SegmentSource.entries.forEach { source ->
				val serialized = converter.fromSegmentSource(source)
				val deserialized = converter.toSegmentSource(serialized)
				deserialized shouldBe source
			}
		}

		@Test
		fun `fromSegmentSource returns enum name`() {
			converter.fromSegmentSource(SegmentSource.USER_CREATED) shouldBe "USER_CREATED"
			converter.fromSegmentSource(SegmentSource.LEGACY_MIGRATION) shouldBe "LEGACY_MIGRATION"
		}

		@Test
		fun `toSegmentSource throws for invalid string`() {
			assertThrows<IllegalArgumentException> {
				converter.toSegmentSource("MANUAL")
			}
		}
	}

	@Nested
	@DisplayName("SkiSegmentType")
	inner class SkiSegmentTypeTests {
		@Test
		fun `all values round-trip`() {
			SkiSegmentType.entries.forEach { type ->
				val serialized = converter.fromSkiSegmentType(type)
				val deserialized = converter.toSkiSegmentType(serialized)
				deserialized shouldBe type
			}
		}

		@Test
		fun `fromSkiSegmentType returns enum name`() {
			converter.fromSkiSegmentType(SkiSegmentType.DOWNHILL_RUN) shouldBe "DOWNHILL_RUN"
			converter.fromSkiSegmentType(SkiSegmentType.LIFT_UP) shouldBe "LIFT_UP"
			converter.fromSkiSegmentType(SkiSegmentType.IDLE) shouldBe "IDLE"
			converter.fromSkiSegmentType(SkiSegmentType.WALK) shouldBe "WALK"
		}

		@Test
		fun `toSkiSegmentType throws for invalid string`() {
			assertThrows<IllegalArgumentException> {
				converter.toSkiSegmentType("SNOWBOARD")
			}
		}
	}

	@Nested
	@DisplayName("Altitude contract")
	inner class AltitudeContractTests {
		@Test
		fun `all altitude contract values round trip through stable storage names`() {
			AltitudeDatum.entries.forEach { datum ->
				converter.toAltitudeDatum(converter.fromAltitudeDatum(datum)) shouldBe datum
			}
			AltitudeSource.entries.forEach { source ->
				converter.toAltitudeSource(converter.fromAltitudeSource(source)) shouldBe source
			}
			AltitudeConversionStatus.entries.forEach { status ->
				converter.toAltitudeConversionStatus(
					converter.fromAltitudeConversionStatus(status),
				) shouldBe status
			}
		}

		@Test
		fun `unknown durable altitude codes are conservative legacy values`() {
			converter.toAltitudeDatum("future_datum") shouldBe AltitudeDatum.UNKNOWN_LEGACY
			converter.toAltitudeSource("future_source") shouldBe AltitudeSource.UNKNOWN_LEGACY
			converter.toAltitudeConversionStatus("future_status") shouldBe
				AltitudeConversionStatus.UNKNOWN_LEGACY
		}
	}
}
