package com.adsamcik.tracker.shared.base.constant

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CoordinateConstants - WGS-84 coordinate bounds")
class CoordinateConstantsTest {

	@Test
	fun `MIN_LATITUDE is negative 90`() {
		CoordinateConstants.MIN_LATITUDE shouldBe -90.0
	}

	@Test
	fun `MAX_LATITUDE is positive 90`() {
		CoordinateConstants.MAX_LATITUDE shouldBe 90.0
	}

	@Test
	fun `MIN_LONGITUDE is negative 180`() {
		CoordinateConstants.MIN_LONGITUDE shouldBe -180.0
	}

	@Test
	fun `MAX_LONGITUDE is positive 180`() {
		CoordinateConstants.MAX_LONGITUDE shouldBe 180.0
	}

	@Test
	fun `latitude range is symmetric around zero`() {
		CoordinateConstants.MIN_LATITUDE shouldBe -CoordinateConstants.MAX_LATITUDE
	}

	@Test
	fun `longitude range is symmetric around zero`() {
		CoordinateConstants.MIN_LONGITUDE shouldBe -CoordinateConstants.MAX_LONGITUDE
	}

	@Test
	fun `longitude range is double the latitude range`() {
		val latRange = CoordinateConstants.MAX_LATITUDE - CoordinateConstants.MIN_LATITUDE
		val lonRange = CoordinateConstants.MAX_LONGITUDE - CoordinateConstants.MIN_LONGITUDE
		lonRange shouldBe 2 * latRange
	}
}
