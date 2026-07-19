package com.adsamcik.tracker.impexp.format

import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.impexp.importer.file.FileImport
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [FormatRegistry] — the central format→exporter/importer resolver.
 */
@DisplayName("FormatRegistry")
class FormatRegistryTest {

	@Nested
	@DisplayName("Built-in format registration")
	inner class BuiltInFormats {

		@Test
		fun `registers at least four formats`() {
			FormatRegistry.allEntries() shouldHaveAtLeastSize 4
		}

		@Test
		fun `all export formats have exporters`() {
			FormatRegistry.allExportFormats().shouldNotBeEmpty()
			FormatRegistry.allExportFormats().forEach { descriptor ->
				FormatRegistry.exporterFor(descriptor.id).shouldNotBeNull()
			}
		}

		@Test
		fun `import extensions include gpx kml json db`() {
			FormatRegistry.allImportExtensions() shouldContainAll setOf("gpx", "kml", "json", "db")
		}

		@Test
		fun `all import formats have importers`() {
			FormatRegistry.allImportFormats().shouldNotBeEmpty()
			FormatRegistry.allImportFormats().forEach { descriptor ->
				FormatRegistry.importerForExtension(descriptor.extensions.first()).shouldNotBeNull()
			}
		}
	}

	@Nested
	@DisplayName("Exporter resolution")
	inner class ExporterResolution {

		@Test
		fun `exporterFor gpx returns non-null`() {
			FormatRegistry.exporterFor("gpx").shouldNotBeNull()
		}

		@Test
		fun `exporterFor kml returns non-null`() {
			FormatRegistry.exporterFor("kml").shouldNotBeNull()
		}

		@Test
		fun `exporterFor json returns non-null`() {
			FormatRegistry.exporterFor("json").shouldNotBeNull()
		}

		@Test
		fun `exporterFor db returns non-null`() {
			FormatRegistry.exporterFor("db").shouldNotBeNull()
		}

		@Test
		fun `exporterFor unknown returns null`() {
			FormatRegistry.exporterFor("csv").shouldBeNull()
		}
	}

	@Nested
	@DisplayName("Importer resolution")
	inner class ImporterResolution {

		@Test
		fun `importerForExtension gpx returns non-null`() {
			FormatRegistry.importerForExtension("gpx").shouldNotBeNull()
		}

		@Test
		fun `importerForExtension kml returns non-null`() {
			FormatRegistry.importerForExtension("kml").shouldNotBeNull()
		}

		@Test
		fun `importerForExtension json returns non-null`() {
			FormatRegistry.importerForExtension("json").shouldNotBeNull()
		}

		@Test
		fun `importerForExtension db returns non-null`() {
			FormatRegistry.importerForExtension("db").shouldNotBeNull()
		}

		@Test
		fun `importerForExtension is case insensitive`() {
			FormatRegistry.importerForExtension("GPX").shouldNotBeNull()
		}

		@Test
		fun `importerForExtension unknown returns null`() {
			FormatRegistry.importerForExtension("csv").shouldBeNull()
		}
	}

	@Nested
	@DisplayName("Descriptor metadata")
	inner class DescriptorMetadata {

		@Test
		fun `gpx descriptor has correct mimeType`() {
			val gpx = FormatRegistry.allEntries().first { it.descriptor.id == "gpx" }
			gpx.descriptor.mimeType shouldBe "application/gpx+xml"
		}

		@Test
		fun `db descriptor does not support date range`() {
			val db = FormatRegistry.allEntries().first { it.descriptor.id == "db" }
			db.descriptor.supportsDateRange shouldBe false
		}

		@Test
		fun `db descriptor exports a ZIP backup`() {
			val db = FormatRegistry.allEntries().first { it.descriptor.id == "db" }
			db.descriptor.mimeType shouldBe "application/zip"
			db.descriptor.extensions shouldContainAll setOf("zip", "db")
		}

		@Test
		fun `gpx descriptor supports date range`() {
			val gpx = FormatRegistry.allEntries().first { it.descriptor.id == "gpx" }
			gpx.descriptor.supportsDateRange shouldBe true
		}
	}

	@Nested
	@DisplayName("allImporters consistency")
	inner class AllImporters {

		@Test
		fun `allImporters returns same count as import entries`() {
			val importerCount = FormatRegistry.allEntries().count { it.importer != null }
			FormatRegistry.allImporters().size shouldBe importerCount
		}
	}
}
