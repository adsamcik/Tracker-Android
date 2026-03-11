package com.adsamcik.tracker.impexp.importer

import com.adsamcik.tracker.impexp.importer.file.FileImport
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Unit tests for [DataImport] — the importer registry and file-type router.
 */
@DisplayName("DataImport")
class DataImportTest {

    private val dataImport = DataImport()

    @Nested
    @DisplayName("Importer registry")
    inner class ImporterRegistry {

        @Test
        fun `has at least four active importers`() {
            dataImport.activeImporterList shouldHaveAtLeastSize 4
        }

        @Test
        fun `supported importer extensions include gpx`() {
            dataImport.supportedImporterExtensions shouldContain "gpx"
        }

        @Test
        fun `supported importer extensions include kml`() {
            dataImport.supportedImporterExtensions shouldContain "kml"
        }

        @Test
        fun `supported importer extensions include db`() {
            dataImport.supportedImporterExtensions shouldContain "db"
        }

        @Test
        fun `supported importer extensions include json`() {
            dataImport.supportedImporterExtensions shouldContain "json"
        }

        @Test
        fun `no duplicate extensions in importer list`() {
            val extensions = dataImport.supportedImporterExtensions
            extensions.size shouldBe extensions.distinct().size
        }
    }

    @Nested
    @DisplayName("Archive extractor registry")
    inner class ArchiveExtractorRegistry {

        @Test
        fun `has at least one archive extractor`() {
            dataImport.activeArchiveExtractorList.shouldNotBeEmpty()
        }

        @Test
        fun `supported archive extensions include zip`() {
            dataImport.supportedArchiveExtractorExtensions shouldContain "zip"
        }
    }

    @Nested
    @DisplayName("Combined extensions")
    inner class CombinedExtensions {

        @Test
        fun `supportedExtensions includes both importer and archive extensions`() {
            val all = dataImport.supportedExtensions
            all shouldContainAll listOf("gpx", "kml", "db", "json", "zip")
        }

        @Test
        fun `supportedExtensions is union — no duplicates even if overlap existed`() {
            val all = dataImport.supportedExtensions
            all.size shouldBe all.toSet().size
        }
    }

    @Nested
    @DisplayName("File type routing")
    inner class FileTypeRouting {

        @Test
        fun `finds importer for gpx extension`() {
            findImporter("gpx").shouldNotBeNull()
        }

        @Test
        fun `finds importer for kml extension`() {
            findImporter("kml").shouldNotBeNull()
        }

        @Test
        fun `finds importer for db extension`() {
            findImporter("db").shouldNotBeNull()
        }

        @Test
        fun `finds importer for json extension`() {
            findImporter("json").shouldNotBeNull()
        }

        @Test
        fun `returns null for unsupported extension`() {
            findImporter("csv").shouldBeNull()
        }

        @Test
        fun `returns null for empty extension`() {
            findImporter("").shouldBeNull()
        }

        @Test
        fun `routing is case insensitive when caller lowercases`() {
            val ext = "GPX".lowercase(Locale.ROOT)
            findImporter(ext).shouldNotBeNull()
        }
    }

    @Nested
    @DisplayName("FileImportStream extension extraction")
    inner class FileImportStreamExtension {

        @Test
        fun `extracts extension from filename`() {
            val stream = FileImportStream("dummy".byteInputStream(), "track.gpx")
            stream.extension shouldBe "gpx"
        }

        @Test
        fun `extracts last extension from double extension`() {
            val stream = FileImportStream("dummy".byteInputStream(), "archive.tar.gz")
            stream.extension shouldBe "gz"
        }

        @Test
        fun `returns empty for filename without extension`() {
            val stream = FileImportStream("dummy".byteInputStream(), "README")
            stream.extension shouldBe ""
        }

        @Test
        fun `handles filename with dot only`() {
            val stream = FileImportStream("dummy".byteInputStream(), ".hidden")
            stream.extension shouldBe "hidden"
        }
    }

    @Nested
    @DisplayName("Archive routing")
    inner class ArchiveRouting {

        @Test
        fun `finds extractor for zip extension`() {
            val extractor = dataImport.activeArchiveExtractorList
                .find { it.supportedExtensions.contains("zip") }
            extractor.shouldNotBeNull()
        }

        @Test
        fun `returns null for rar extension`() {
            val extractor = dataImport.activeArchiveExtractorList
                .find { it.supportedExtensions.contains("rar") }
            extractor.shouldBeNull()
        }
    }

    /** Mimics the lookup logic used in [ImportWorker]. */
    private fun findImporter(extension: String): FileImport? {
        return dataImport.activeImporterList
            .find { it.supportedExtensions.contains(extension) }
    }
}
