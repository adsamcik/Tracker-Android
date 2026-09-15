package com.adsamcik.tracker.impexp.format

import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.importer.file.FileImport
import com.adsamcik.tracker.impexp.importer.file.ImportTransactionMode
import com.adsamcik.tracker.impexp.importer.DataImport
import com.adsamcik.tracker.impexp.importer.file.PortableActivityFileImport
import com.adsamcik.tracker.impexp.importer.file.PortablePressureFileImport
import com.adsamcik.tracker.impexp.importer.worker.importSourceReadLimit
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
			FormatRegistry.allImportExtensions() shouldContainAll
				setOf("gpx", "kml", "json", "db", "trackersteps")
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
		fun `portable Steps resolves one exporter and importer`() {
			FormatRegistry.exporterFor("portable-steps-v1").shouldNotBeNull()
			FormatRegistry.importerForExtension("trackersteps").shouldNotBeNull()
		}

		@Test
		fun `portable Activity and Pressure are available to both real file routes without Location`() {
			val importerList = DataImport().activeImporterList
			listOf(
				"portable-activity-v1" to PortableActivityFileImport.EXTENSION,
				"portable-pressure-v1" to PortablePressureFileImport.EXTENSION,
			).forEach { (formatId, extension) ->
				val entry = FormatRegistry.allEntries().single { it.descriptor.id == formatId }
				val exporter = entry.exporter.shouldNotBeNull()
				val importer = entry.importer.shouldNotBeNull()
				FormatRegistry.exporterFor(formatId) shouldBe exporter
				FormatRegistry.importerForExtension(extension.uppercase()) shouldBe importer
				(importer in importerList) shouldBe true
				importer.transactionMode shouldBe ImportTransactionMode.IMPORTER_MANAGED
				entry.descriptor.supportsImport shouldBe true
				entry.descriptor.supportsExport shouldBe true
				entry.descriptor.supportsDateRange shouldBe true
				entry.descriptor.mimeType shouldBe exporter.mimeType
				exporter.canSelectDateRange shouldBe true
				exporter.requiresLocationData shouldBe false
				exporter.containsSensitiveLocationData shouldBe (formatId == "portable-pressure-v1")
			}
		}

		@Test
		fun `new portable source limits apply before the worker hashes direct input`() {
			importSourceReadLimit("TRACKERACTIVITY") shouldBe PortableActivityFileImport.MAX_FILE_BYTES
			importSourceReadLimit("trackerpressure") shouldBe PortablePressureFileImport.MAX_FILE_BYTES
		}

		@Test
		fun `Pressure privacy confirmation does not claim exported route coordinates`() {
			val pressure = FormatRegistry.exporterFor("portable-pressure-v1").shouldNotBeNull()
			pressure.containsSensitiveLocationData shouldBe true
			pressure.sensitivityTitleRes shouldBe R.string.export_pressure_sensitivity_title
			pressure.sensitivityMessageRes shouldBe R.string.export_pressure_sensitivity_message
			val gpx = FormatRegistry.exporterFor("gpx").shouldNotBeNull()
			gpx.sensitivityTitleRes shouldBe R.string.export_sensitivity_title
			gpx.sensitivityMessageRes shouldBe R.string.export_sensitivity_message
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
		fun `portable Steps importer owns its transactions`() {
			FormatRegistry.importerForExtension("trackersteps")?.transactionMode shouldBe
				ImportTransactionMode.IMPORTER_MANAGED
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

		@Test
		fun `portable Steps descriptor is bidirectional range-aware and versioned`() {
			val steps = FormatRegistry.allEntries().first { it.descriptor.id == "portable-steps-v1" }
			steps.descriptor.supportsImport shouldBe true
			steps.descriptor.supportsExport shouldBe true
			steps.descriptor.supportsDateRange shouldBe true
			steps.descriptor.mimeType shouldBe "application/vnd.adsamcik.tracker.steps+json"
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
