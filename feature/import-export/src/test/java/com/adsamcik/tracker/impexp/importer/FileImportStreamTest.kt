package com.adsamcik.tracker.impexp.importer

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertFailsWith

@DisplayName("FileImportStream")
class FileImportStreamTest {

	private fun createStream(
		data: ByteArray = ByteArray(0),
		fileName: String = "test.txt"
	): FileImportStream = FileImportStream(ByteArrayInputStream(data), fileName)

	@Nested
	@DisplayName("File name")
	inner class FileName {

		@Test
		fun `returns provided fileName`() {
			createStream(fileName = "export.gpx").fileName shouldBe "export.gpx"
		}

		@Test
		fun `preserves path separators in filename`() {
			createStream(fileName = "subdir/data.json").fileName shouldBe "subdir/data.json"
		}
	}

	@Nested
	@DisplayName("Extension parsing")
	inner class ExtensionParsing {

		@Test
		fun `extracts simple extension`() {
			createStream(fileName = "data.json").extension shouldBe "json"
		}

		@Test
		fun `extracts last extension from multiple dots`() {
			createStream(fileName = "my.data.backup.gpx").extension shouldBe "gpx"
		}

		@Test
		fun `returns empty string when no extension`() {
			createStream(fileName = "README").extension shouldBe ""
		}

		@Test
		fun `returns empty string for filename ending with dot`() {
			createStream(fileName = "file.").extension shouldBe ""
		}

		@Test
		fun `handles dotfile with extension`() {
			createStream(fileName = ".hidden.txt").extension shouldBe "txt"
		}

		@Test
		fun `treats dotfile name as extension when no other dot`() {
			createStream(fileName = ".gitignore").extension shouldBe "gitignore"
		}
	}

	@Nested
	@DisplayName("Stream delegation")
	inner class StreamDelegation {

		@Test
		fun `read delegates to underlying stream`() {
			val stream = createStream(data = byteArrayOf(10, 20, 30))
			stream.read() shouldBe 10
			stream.read() shouldBe 20
			stream.read() shouldBe 30
		}

		@Test
		fun `read returns -1 at end of stream`() {
			createStream(data = ByteArray(0)).read() shouldBe -1
		}

		@Test
		fun `reads all bytes from non-trivial content`() {
			val data = "Hello, World!".toByteArray()
			val stream = createStream(data = data)
			stream.readBytes() shouldBe data
		}
	}

	@Nested
	@DisplayName("Durable receipt provenance")
	inner class DurableReceiptProvenance {
		@Test
		fun `binding preserves exact provenance and lazy caller owned stream`() {
			var opens = 0
			var closes = 0
			val original = FileImportStream(
				fileName = "pressure.trackerpressure",
				receiptKey = "archive-entry-2",
				streamProvider = {
					opens++
					ByteArrayInputStream(byteArrayOf(10, 20))
				},
				onClose = { closes++ },
			)
			val bound = original.withImportReceipt("content-job", 123L)

			bound.importReceipt shouldBe FileImportReceiptContext(
				"content-job", "archive-entry-2", "pressure.trackerpressure", 123L,
			)
			original.importReceipt shouldBe bound.importReceipt
			opens shouldBe 0
			closes shouldBe 0
			bound.read() shouldBe 10
			bound.read() shouldBe 20
			opens shouldBe 1
			bound.close()
			original.close()
			closes shouldBe 1
		}

		@Test
		fun `same receipt replay is stable and conflicting rebinding fails`() {
			val original = createStream()
			val bound = original.withImportReceipt("job", 100L)

			(bound.withImportReceipt("job", 100L) === bound) shouldBe true
			assertFailsWith<IllegalArgumentException> { bound.withImportReceipt("other-job", 100L) }
			assertFailsWith<IllegalArgumentException> { bound.withImportReceipt("job", 101L) }
			assertFailsWith<IllegalArgumentException> { original.withImportReceipt("other-job", 100L) }
		}

		@Test
		fun `close before first read releases the bound stream without opening it`() {
			var opens = 0
			var closes = 0
			val original = FileImportStream(
				fileName = "source.json",
				streamProvider = {
					opens++
					ByteArrayInputStream(byteArrayOf(1))
				},
				onClose = { closes++ },
			)
			val bound = original.withImportReceipt("job", 1L)

			bound.close()
			closes shouldBe 1
			opens shouldBe 0
			original.close()
			closes shouldBe 1
			assertFailsWith<IllegalStateException> { original.withImportReceipt("job", 1L) }
		}

		@Test
		fun `receipt metadata cannot be blank oversized or negative`() {
			assertFailsWith<IllegalArgumentException> {
				createStream().withImportReceipt("", 1L)
			}
			assertFailsWith<IllegalArgumentException> {
				createStream().withImportReceipt("j".repeat(4_097), 1L)
			}
			assertFailsWith<IllegalArgumentException> {
				createStream().withImportReceipt("job", -1L)
			}
			listOf("", "n".repeat(4_097)).forEach { sourceName ->
				assertFailsWith<IllegalArgumentException> {
					FileImportStream(sourceName, "valid-entry", { ByteArrayInputStream(byteArrayOf()) })
						.withImportReceipt("job", 1L)
				}
			}
			listOf("", "k".repeat(4_097)).forEach { entryKey ->
				assertFailsWith<IllegalArgumentException> {
					FileImportStream("source.json", entryKey, { ByteArrayInputStream(byteArrayOf()) })
						.withImportReceipt("job", 1L)
				}
			}
		}
	}
}
