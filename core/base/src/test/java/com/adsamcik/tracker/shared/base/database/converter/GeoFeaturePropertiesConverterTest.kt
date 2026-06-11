package com.adsamcik.tracker.shared.base.database.converter

import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("GeoFeaturePropertiesConverter - Map<String,Double> to/from JSON")
class GeoFeaturePropertiesConverterTest {

	private val converter = GeoFeaturePropertiesConverter()

	@Nested
	@DisplayName("fromMap")
	inner class FromMap {
		@Test
		fun `null map returns null`() {
			converter.fromMap(null).shouldBeNull()
		}

		@Test
		fun `empty map returns null`() {
			converter.fromMap(emptyMap()).shouldBeNull()
		}

		@Test
		fun `non-empty map returns JSON string`() {
			val json = converter.fromMap(mapOf("speed" to 5.5))
			json shouldContain "speed"
			json shouldContain "5.5"
		}

		@Test
		fun `multiple entries serialized`() {
			val json = converter.fromMap(mapOf("a" to 1.0, "b" to 2.0))!!
			json shouldContain "a"
			json shouldContain "b"
		}
	}

	@Nested
	@DisplayName("toMap")
	inner class ToMap {
		@Test
		fun `null json returns empty map`() {
			converter.toMap(null).shouldBeEmpty()
		}

		@Test
		fun `blank string returns empty map`() {
			converter.toMap("   ").shouldBeEmpty()
		}

		@Test
		fun `empty string returns empty map`() {
			converter.toMap("").shouldBeEmpty()
		}

		@Test
		fun `valid JSON returns correct map`() {
			val result = converter.toMap("""{"speed":5.5,"distance":10.2}""")
			result shouldContainExactly mapOf("speed" to 5.5, "distance" to 10.2)
		}
	}

	@Nested
	@DisplayName("Round-trip consistency")
	inner class RoundTrip {
		@Test
		fun `single entry survives round-trip`() {
			val original = mapOf("elevation" to 123.456)
			val json = converter.fromMap(original)
			val restored = converter.toMap(json)
			restored shouldContainExactly original
		}

		@Test
		fun `multiple entries survive round-trip`() {
			val original = mapOf("a" to 1.0, "b" to -2.5, "c" to 0.0)
			val json = converter.fromMap(original)
			val restored = converter.toMap(json)
			restored shouldContainExactly original
		}

		@Test
		fun `null roundtrips via empty map`() {
			val json = converter.fromMap(null)
			val result = converter.toMap(json)
			result.shouldBeEmpty()
		}
	}
}
