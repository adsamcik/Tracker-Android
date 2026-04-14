package com.adsamcik.tracker.impexp.format

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("FormatDescriptor")
class FormatDescriptorTest {

	@Nested
	@DisplayName("Data class properties")
	inner class Properties {

		@Test
		fun `stores id correctly`() {
			val descriptor = createDescriptor(id = "gpx")
			descriptor.id shouldBe "gpx"
		}

		@Test
		fun `stores displayNameRes correctly`() {
			val descriptor = createDescriptor(displayNameRes = 42)
			descriptor.displayNameRes shouldBe 42
		}

		@Test
		fun `stores mimeType correctly`() {
			val descriptor = createDescriptor(mimeType = "application/gpx+xml")
			descriptor.mimeType shouldBe "application/gpx+xml"
		}

		@Test
		fun `stores extensions as a set`() {
			val descriptor = createDescriptor(extensions = setOf("gpx", "xml"))
			descriptor.extensions shouldContainAll setOf("gpx", "xml")
			descriptor.extensions shouldHaveSize 2
		}

		@Test
		fun `stores empty extensions set`() {
			val descriptor = createDescriptor(extensions = emptySet())
			descriptor.extensions.shouldBeEmpty()
		}

		@Test
		fun `stores supportsExport flag`() {
			val withExport = createDescriptor(supportsExport = true)
			val withoutExport = createDescriptor(supportsExport = false)
			withExport.supportsExport shouldBe true
			withoutExport.supportsExport shouldBe false
		}

		@Test
		fun `stores supportsImport flag`() {
			val withImport = createDescriptor(supportsImport = true)
			val withoutImport = createDescriptor(supportsImport = false)
			withImport.supportsImport shouldBe true
			withoutImport.supportsImport shouldBe false
		}

		@Test
		fun `stores supportsDateRange flag`() {
			val withDateRange = createDescriptor(supportsDateRange = true)
			val withoutDateRange = createDescriptor(supportsDateRange = false)
			withDateRange.supportsDateRange shouldBe true
			withoutDateRange.supportsDateRange shouldBe false
		}
	}

	@Nested
	@DisplayName("Data class equality")
	inner class Equality {

		@Test
		fun `equal descriptors are equal`() {
			val a = createDescriptor()
			val b = createDescriptor()
			a shouldBe b
		}

		@Test
		fun `different id produces inequality`() {
			val a = createDescriptor(id = "gpx")
			val b = createDescriptor(id = "kml")
			a shouldNotBe b
		}

		@Test
		fun `different mimeType produces inequality`() {
			val a = createDescriptor(mimeType = "application/gpx+xml")
			val b = createDescriptor(mimeType = "application/vnd.google-earth.kml+xml")
			a shouldNotBe b
		}

		@Test
		fun `different extensions produces inequality`() {
			val a = createDescriptor(extensions = setOf("gpx"))
			val b = createDescriptor(extensions = setOf("kml"))
			a shouldNotBe b
		}

		@Test
		fun `different supportsExport produces inequality`() {
			val a = createDescriptor(supportsExport = true)
			val b = createDescriptor(supportsExport = false)
			a shouldNotBe b
		}
	}

	@Nested
	@DisplayName("Data class copy")
	inner class Copy {

		@Test
		fun `copy preserves all fields`() {
			val original = createDescriptor()
			val copy = original.copy()
			copy shouldBe original
		}

		@Test
		fun `copy can override id`() {
			val original = createDescriptor(id = "gpx")
			val modified = original.copy(id = "kml")
			modified.id shouldBe "kml"
			modified.mimeType shouldBe original.mimeType
		}

		@Test
		fun `copy can override supportsDateRange`() {
			val original = createDescriptor(supportsDateRange = false)
			val modified = original.copy(supportsDateRange = true)
			modified.supportsDateRange shouldBe true
		}
	}

	@Nested
	@DisplayName("hashCode and toString")
	inner class HashCodeToString {

		@Test
		fun `equal objects have same hashCode`() {
			val a = createDescriptor()
			val b = createDescriptor()
			a.hashCode() shouldBe b.hashCode()
		}

		@Test
		fun `toString contains id`() {
			val descriptor = createDescriptor(id = "test_format")
			descriptor.toString().contains("test_format") shouldBe true
		}
	}

	private fun createDescriptor(
		id: String = "test",
		displayNameRes: Int = 1,
		mimeType: String = "application/octet-stream",
		extensions: Set<String> = setOf("test"),
		supportsExport: Boolean = true,
		supportsImport: Boolean = true,
		supportsDateRange: Boolean = false,
	) = FormatDescriptor(
		id = id,
		displayNameRes = displayNameRes,
		mimeType = mimeType,
		extensions = extensions,
		supportsExport = supportsExport,
		supportsImport = supportsImport,
		supportsDateRange = supportsDateRange,
	)
}
