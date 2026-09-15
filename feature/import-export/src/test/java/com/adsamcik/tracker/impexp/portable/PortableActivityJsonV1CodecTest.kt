package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.PortableActivityEnvelopeV1
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortableActivityJsonV1CodecTest {
	@Test
	fun `canonical portable Activity bytes round trip complete envelope`() = runTest {
		val envelope = activityEnvelope(
			activityEntry("first", 1_000L),
			activityEntry("second", 5_000L),
		)

		val first = encode(envelope)
		val second = encode(envelope)

		first.contentEquals(second) shouldBe true
		PortableActivityJsonV1Codec().decode(ByteArrayInputStream(first)) shouldBe envelope
	}

	@Test
	fun `malformed checksum duplicate field and truncation fail before decode returns`() = runTest {
		val bytes = encode(activityEnvelope(activityEntry()))
		val json = bytes.decodeToString()
		val invalid = listOf(
			json.replaceFirst("\"contentChecksum\":\"", "\"contentChecksum\":\"0"),
			json.replaceFirst("\"format\":", "\"format\":\"duplicate\",\"format\":"),
			json.dropLast(1),
		)

		invalid.forEach { value ->
			shouldThrow<PortableActivityFormatException> {
				PortableActivityJsonV1Codec().decode(
					ByteArrayInputStream(value.encodeToByteArray()),
				)
			}
		}
	}

	@Test
	fun `byte and entry caps reject an otherwise valid envelope`() = runTest {
		val twoEntries = activityEnvelope(
			activityEntry("first", 1_000L),
			activityEntry("second", 5_000L),
		)
		val bytes = encode(twoEntries)

		shouldThrow<PortableActivityFormatException> {
			PortableActivityJsonV1Codec(
				PortableActivityJsonLimits(maxFileBytes = bytes.size.toLong() - 1L),
			).decode(ByteArrayInputStream(bytes))
		}
		shouldThrow<PortableActivityFormatException> {
			PortableActivityJsonV1Codec(
				PortableActivityJsonLimits(maxEntries = 1),
			).decode(ByteArrayInputStream(bytes))
		}
		shouldThrow<PortableActivityFormatException> {
			PortableActivityJsonV1Codec(
				PortableActivityJsonLimits(maxFileBytes = 10L),
			).encode(ByteArrayOutputStream()) { sink ->
				sink.emit(twoEntries)
				ExportPortableCapturedActivityResult.Exported(twoEntries.entries.size)
			}
		}
	}

	@Test
	fun `cross entry opaque identity collision is rejected`() = runTest {
		val first = activityEntry("first", 1_000L)
		val second = activityEntry("second", 5_000L)
		val originalRun = second.runs.single()
		val collidingRun = originalRun.copy(
			identity = first.runs.single().identity,
			contentChecksum = com.adsamcik.tracker.shared.base.database
				.ActivityCapturedPortableIntegrity.runChecksum(
					identity = first.runs.single().identity,
					deletionScopeDigest = originalRun.deletionScopeDigest,
					startTimeMs = originalRun.startTimeMs,
					endTimeMs = originalRun.endTimeMs,
					captureCoverage = originalRun.captureCoverage,
					zoneEpochs = originalRun.zoneEpochs,
					windows = originalRun.windows,
				),
		)
		val correctedSecond = second.copy(
			contentChecksum = com.adsamcik.tracker.shared.base.database
				.ActivityCapturedPortableIntegrity.entryChecksum(
					identity = second.identity,
					sessionMode = second.sessionMode,
					startTimeMs = second.startTimeMs,
					endTimeMs = second.endTimeMs,
					runs = listOf(collidingRun),
				),
			runs = listOf(collidingRun),
		)
		val envelope = activityEnvelope(first, correctedSecond)

		shouldThrow<PortableActivityFormatException> {
			encode(envelope)
		}
	}

	@Test
	fun `transport IOException remains retryable rather than becoming permanent format failure`() =
		runTest {
			val failure = shouldThrow<IOException> {
				PortableActivityJsonV1Codec().decode(
					object : InputStream() {
						override fun read(): Int = throw IOException("transport unavailable")
					},
				)
			}

			failure.message shouldBe "transport unavailable"
		}

	@Test
	fun `raw source EOF after a valid prefix escapes with exact identity`() = runTest {
		val bytes = encode(activityEnvelope(activityEntry()))
		val original = EOFException("transport interrupted")
		val failure = shouldThrow<EOFException> {
			PortableActivityJsonV1Codec().decode(
				FailingSourceInputStream(bytes.dropLast(1).toByteArray(), original),
			)
		}

		(failure === original) shouldBe true
	}

	@Test
	fun `lexical guard permanently rejects oversized known string name number and depth`() = runTest {
		val invalidDocuments = listOf(
			"{\"format\":\"${"x".repeat(769)}\"}",
			"{\"${"n".repeat(385)}\":0}",
			"{\"schemaVersion\":${"1".repeat(65)}}",
			"[".repeat(33) + "]".repeat(33),
		)

		invalidDocuments.forEach { document ->
			shouldThrow<PortableActivityFormatException> {
				PortableActivityJsonV1Codec().decode(
					ByteArrayInputStream(document.encodeToByteArray()),
				)
			}
		}
	}

	@Test
	fun `oversized token is rejected with bounded prefetch before whole token consumption`() =
		runTest {
			val document = "{\"format\":\"${"x".repeat(10_000)}\"}".encodeToByteArray()
			val source = CountingInputStream(document)

			shouldThrow<PortableActivityFormatException> {
				PortableActivityJsonV1Codec().decode(source)
			}

			(source.bytesRead < document.size) shouldBe true
			(source.bytesRead <= 1_280) shouldBe true
		}

	@Test
	fun `shared lexical guard accepts exact escaped and multibyte string boundary`() {
		val token = "é".repeat(3) + "\\u0041".repeat(127)
		token.encodeToByteArray().size shouldBe PortableJsonTokenLimits().maxStringBytes
		val document = "{\"name\":\"$token\"}".encodeToByteArray()

		PortableJsonTokenLimitInputStream(ByteArrayInputStream(document)).readBytes()
			.contentEquals(document) shouldBe true
	}

	@Test
	fun `cancellation propagates and non success writes no bytes`() = runTest {
		val output = ByteArrayOutputStream()
		shouldThrow<CancellationException> {
			PortableActivityJsonV1Codec().encode(output) {
				throw CancellationException("cancelled")
			}
		}
		output.size() shouldBe 0

		PortableActivityJsonV1Codec().encode(output) {
			ExportPortableCapturedActivityResult.NoEntries
		} shouldBe ExportPortableCapturedActivityResult.NoEntries
		output.size() shouldBe 0
	}

	private suspend fun encode(envelope: PortableActivityEnvelopeV1): ByteArray {
		val output = ByteArrayOutputStream()
		PortableActivityJsonV1Codec().encode(output) { sink ->
			sink.emit(envelope)
			ExportPortableCapturedActivityResult.Exported(envelope.entries.size)
		}
		return output.toByteArray()
	}

	private class CountingInputStream(
		private val bytes: ByteArray,
	) : InputStream() {
		var bytesRead: Int = 0
			private set

		override fun read(): Int =
			if (bytesRead >= bytes.size) {
				-1
			} else {
				bytes[bytesRead++].toInt() and 0xff
			}

		override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
			if (bytesRead >= bytes.size) return -1
			val count = minOf(length, bytes.size - bytesRead)
			bytes.copyInto(buffer, offset, bytesRead, bytesRead + count)
			bytesRead += count
			return count
		}
	}

	private class FailingSourceInputStream(
		private val prefix: ByteArray,
		private val failure: IOException,
	) : InputStream() {
		private var offset = 0

		override fun read(): Int {
			if (offset >= prefix.size) throw failure
			return prefix[offset++].toInt() and 0xff
		}

		override fun read(buffer: ByteArray, targetOffset: Int, length: Int): Int {
			if (offset >= prefix.size) throw failure
			val count = minOf(length, prefix.size - offset)
			prefix.copyInto(buffer, targetOffset, offset, offset + count)
			offset += count
			return count
		}
	}
}
