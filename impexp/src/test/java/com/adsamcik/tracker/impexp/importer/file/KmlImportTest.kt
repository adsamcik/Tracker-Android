package com.adsamcik.tracker.impexp.importer.file

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("KmlImport")
class KmlImportTest {

	private val kmlImport = KmlImport()

	@Nested
	@DisplayName("Supported extensions")
	inner class SupportedExtensions {

		@Test
		fun `supports kml extension`() {
			kmlImport.supportedExtensions shouldContain "kml"
		}

		@Test
		fun `supports exactly one extension`() {
			kmlImport.supportedExtensions shouldHaveSize 1
		}
	}

	@Nested
	@DisplayName("FileImport contract")
	inner class FileImportContract {

		@Test
		fun `implements FileImport interface`() {
			val importer: FileImport = kmlImport
			importer.supportedExtensions shouldContain "kml"
		}

		@Test
		fun `supportedExtensions is a list`() {
			(kmlImport.supportedExtensions is List<*>) shouldBe true
		}
	}

	@Nested
	@DisplayName("Timestamp parsing")
	inner class TimestampParsing {

		@Test
		fun `parses exporter ISO instant timestamp with timezone`() {
			KmlImport.parseWhenTimestamp("2023-11-14T22:13:20Z") shouldBe 1_700_000_000_000L
		}

		@Test
		fun `returns null for timestamp without timezone`() {
			KmlImport.parseWhenTimestamp("2023-11-14T22:13:20").shouldBe(null)
		}
	}
}
