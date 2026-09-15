package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.PortableActivityEnvelopeV1
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
}
