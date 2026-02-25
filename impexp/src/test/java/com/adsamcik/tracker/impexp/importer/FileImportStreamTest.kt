package com.adsamcik.tracker.impexp.importer

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

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
}
