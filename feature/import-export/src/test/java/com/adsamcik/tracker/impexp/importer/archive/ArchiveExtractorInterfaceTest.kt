package com.adsamcik.tracker.impexp.importer.archive

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ArchiveExtractor interface")
class ArchiveExtractorInterfaceTest {

	@Nested
	@DisplayName("ZipArchiveExtractor implementation")
	inner class ZipImplementation {

		private val extractor = ZipArchiveExtractor()

		@Test
		fun `supports zip extension`() {
			extractor.supportedExtensions.shouldNotBeEmpty()
			extractor.supportedExtensions.contains("zip") shouldBe true
		}

		@Test
		fun `supported extensions collection is not empty`() {
			extractor.supportedExtensions.shouldNotBeEmpty()
		}
	}

	@Nested
	@DisplayName("Contract verification via stub")
	inner class ContractVerification {

		@Test
		fun `implementor exposes supported extensions`() = runTest {
			val stub = object : ArchiveExtractor {
				override val supportedExtensions = listOf("tar", "7z")
				override suspend fun extract(
					context: android.content.Context,
					file: androidx.documentfile.provider.DocumentFile,
					shouldExtract: suspend (ArchiveEntryMetadata) -> Boolean,
					consume: suspend (com.adsamcik.tracker.impexp.importer.FileImportStream) -> Unit,
				) = false
			}
			stub.supportedExtensions shouldBe listOf("tar", "7z")
		}

		@Test
		fun `extract can report an unavailable archive stream`() = runTest {
			val stub = object : ArchiveExtractor {
				override val supportedExtensions = listOf("custom")
				override suspend fun extract(
					context: android.content.Context,
					file: androidx.documentfile.provider.DocumentFile,
					shouldExtract: suspend (ArchiveEntryMetadata) -> Boolean,
					consume: suspend (com.adsamcik.tracker.impexp.importer.FileImportStream) -> Unit,
				) = false
			}
			stub.extract(
				context = io.mockk.mockk(),
				file = io.mockk.mockk(),
				shouldExtract = { true },
				consume = {},
			) shouldBe false
		}
	}
}
