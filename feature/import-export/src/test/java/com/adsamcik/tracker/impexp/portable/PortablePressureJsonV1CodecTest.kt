package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntrySink
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortablePressureJsonV1CodecTest {
	@Test
	fun `canonical round trip preserves native float double quality and checksum semantics`() = runTest {
		val extreme = pressureWindow(
			seed = "extreme",
			startTimeMs = 1_000L,
			meanHectopascals = 1_000.0,
			minimumHectopascals = 1f,
			maximumHectopascals = Float.MAX_VALUE,
			firstHectopascals = 1f,
			latestHectopascals = Float.MAX_VALUE,
			sumSquaredDeviations = Double.MAX_VALUE,
			slopeHectopascalsPerSecond = -Double.MAX_VALUE,
			rSquared = 1.0,
			sourceQualityConfidence = Float.fromBits(0x3f7fffff),
		)
		val nullable = singleSamplePressureWindow("nullable", 3_000L)
		val entry = pressureEntryWithWindows("boundary", listOf(extreme, nullable))
		val first = encodePressureEntries(listOf(entry))
		val second = encodePressureEntries(listOf(entry))
		val decoded = mutableListOf<PortablePressureEntryV1>()

		first.contentEquals(second) shouldBe true
		first.decodeToString().startsWith(
			"""{"format":"tracker-portable-pressure","schemaVersion":1,"entries":[""",
		) shouldBe true
		PortablePressureJsonV1Codec().decode(
			ByteArrayInputStream(first),
			PortablePressureEntrySink { decoded += it },
		) shouldBe 1
		decoded shouldContainExactly listOf(entry)
		val decodedExtreme = decoded.single().runs.single().windows.first()
		decodedExtreme.maximumHectopascals.toRawBits() shouldBe Float.MAX_VALUE.toRawBits()
		decodedExtreme.sumSquaredDeviations.toRawBits() shouldBe Double.MAX_VALUE.toRawBits()
		decodedExtreme.slopeHectopascalsPerSecond?.toRawBits() shouldBe
			(-Double.MAX_VALUE).toRawBits()
		decoded.single().runs.single().windows.last().let { window ->
			window.slopeHectopascalsPerSecond shouldBe null
			window.rSquared shouldBe null
			window.sourceQualityConfidence shouldBe null
		}
		val nullableJson = encodePressureEntries(
			listOf(pressureEntryWithWindows("nullable-only", listOf(nullable))),
		).decodeToString()
		nullableJson.contains("\"slopeHectopascalsPerSecond\"") shouldBe false
		nullableJson.contains("\"rSquared\"") shouldBe false
		nullableJson.contains("\"sourceQualityConfidence\"") shouldBe false
	}

	@Test
	fun `wire vocabulary excludes location elevation provider and lifecycle authority`() = runTest {
		val localIdentity = "raw-pressure-logical-secret"
		val json = encodePressureEntries(listOf(pressureEntry(localIdentity))).decodeToString()
		val objectKeys = Regex("\"([A-Za-z][A-Za-z0-9]*)\":")
			.findAll(json)
			.map { match -> match.groupValues[1] }
			.toSet()

		objectKeys shouldBe setOf(
			"format",
			"schemaVersion",
			"entries",
			"identity",
			"contentChecksum",
			"startTimeMs",
			"endTimeMs",
			"runs",
			"capturedForWholeRun",
			"availability",
			"coverage",
			"retentionLoss",
			"windows",
			"intervalStartTimeMs",
			"intervalEndTimeMs",
			"wallTimeUncertaintyMs",
			"observedDurationNanos",
			"sampleCount",
			"expectedSampleCount",
			"meanHectopascals",
			"sumSquaredDeviations",
			"minimumHectopascals",
			"maximumHectopascals",
			"firstHectopascals",
			"latestHectopascals",
			"slopeHectopascalsPerSecond",
			"rSquared",
			"sensorAccuracy",
			"effectiveSamplePeriodMicros",
			"effectiveMaximumReportLatencyMicros",
			"targetWindowDurationNanos",
			"maximumInterSampleGapNanos",
			"closure",
			"qualification",
			"sourceQualityFlags",
			"sourceQualityConfidence",
			"zoneId",
		)
		listOf(
			"latitude",
			"longitude",
			"elevation",
			"ascent",
			"provider",
			"registration",
			"serviceRunId",
			"manifest",
			"consent",
			"collectedDataEpoch",
			"control",
		).forEach { forbidden -> json.contains(forbidden, ignoreCase = true) shouldBe false }
		json.contains(localIdentity) shouldBe false
	}

	@Test
	fun `unknown duplicate header version type and invalid window are rejected before mutation`() = runTest {
		val original = encodePressureEntries(listOf(pressureEntry())).decodeToString()
		val entryArray = original.substringAfter("\"entries\":").dropLast(1)
		val entriesFirst =
			"""{"entries":$entryArray,"format":"tracker-portable-pressure","schemaVersion":1}"""
		val invalid = listOf(
			original.replaceFirst("\"schemaVersion\":1", "\"unexpected\":true,\"schemaVersion\":1"),
			original.replaceFirst(
				"\"format\":\"tracker-portable-pressure\"",
				"\"format\":\"tracker-portable-pressure\",\"format\":\"tracker-portable-pressure\"",
			),
			entriesFirst,
			original.replaceFirst(
				"tracker-portable-pressure",
				"tracker-portable-pressure-unknown",
			),
			original.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2"),
			original.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
			original.replaceFirst("\"sampleCount\":2", "\"sampleCount\":\"2\""),
			original.replaceFirst("\"meanHectopascals\":1000.25", "\"meanHectopascals\":-1.0"),
		)

		invalid.forEach { document ->
			val emitted = mutableListOf<PortablePressureEntryV1>()
			shouldThrow<PortablePressureJsonException> {
				PortablePressureJsonV1Codec().decode(
					ByteArrayInputStream(document.encodeToByteArray()),
					PortablePressureEntrySink { emitted += it },
				)
			}
			emitted.shouldBeEmpty()
		}
	}

	@Test
	fun `truncated and non finite numeric documents are rejected before mutation`() = runTest {
		val original = encodePressureEntries(listOf(pressureEntry())).decodeToString()
		val truncated = original.substringBefore("\"zoneId\"")
		val overflow = original.replaceFirst(
			"\"meanHectopascals\":1000.25",
			"\"meanHectopascals\":1e309",
		)

		listOf(truncated, overflow).forEach { invalid ->
			shouldThrow<PortablePressureJsonException> {
				PortablePressureJsonV1Codec().decode(
					ByteArrayInputStream(invalid.encodeToByteArray()),
					PortablePressureEntrySink { error("Invalid input must not be emitted") },
				)
			}
		}
	}

	@Test
	fun `trailing content is rejected after only the authenticated prefix`() = runTest {
		val entry = pressureEntry()
		val trailing = encodePressureEntries(listOf(entry)).decodeToString() + " true"
		val emitted = mutableListOf<PortablePressureEntryV1>()

		shouldThrow<PortablePressureJsonException> {
			PortablePressureJsonV1Codec().decode(
				ByteArrayInputStream(trailing.encodeToByteArray()),
				PortablePressureEntrySink { emitted += it },
			)
		}
		emitted shouldContainExactly listOf(entry)
	}

	@Test
	fun `per entry total and byte caps reject before that entry reaches mutation`() = runTest {
		val entry = pressureEntry(windowsPerRun = 2)
		val encoded = encodePressureEntries(listOf(entry))
		val totalBounded = PortablePressureJsonV1Codec(
			PortablePressureJsonLimits(maxTotalWindows = 1),
		)
		var calls = 0

		shouldThrow<PortablePressureJsonException> {
			totalBounded.decode(ByteArrayInputStream(encoded)) { calls++ }
		}
		calls shouldBe 0

		val byteBounded = PortablePressureJsonV1Codec(
			PortablePressureJsonLimits(maxFileBytes = 32L),
		)
		shouldThrow<PortablePressureJsonException> {
			byteBounded.decode(ByteArrayInputStream(encoded)) { calls++ }
		}
		calls shouldBe 0
		shouldThrow<PortablePressureJsonException> {
			byteBounded.encode(ByteArrayOutputStream()) { sink ->
				sink.emit(entry)
				ExportPortablePressureResult.Exported(1)
			}
		}
	}

	@Test
	fun `lower run and window collection caps apply to encoding and decoding`() = runTest {
		val twoRuns = pressureEntry(runCount = 2)
		val twoWindows = pressureEntry(windowsPerRun = 2)
		val runCodecs = listOf(
			PortablePressureJsonV1Codec(
				PortablePressureJsonLimits(maxRunsPerEntry = 1),
			),
			PortablePressureJsonV1Codec(
				PortablePressureJsonLimits(maxTotalRuns = 1),
			),
		)

		runCodecs.forEach { codec ->
			var calls = 0
			shouldThrow<PortablePressureJsonException> {
				codec.decode(
					ByteArrayInputStream(encodePressureEntries(listOf(twoRuns))),
				) { calls++ }
			}
			calls shouldBe 0
			shouldThrow<PortablePressureJsonException> {
				codec.encode(ByteArrayOutputStream()) { sink ->
					sink.emit(twoRuns)
					ExportPortablePressureResult.Exported(1)
				}
			}
		}

		val windowCodec = PortablePressureJsonV1Codec(
			PortablePressureJsonLimits(maxWindowsPerRun = 1),
		)
		var calls = 0
		shouldThrow<PortablePressureJsonException> {
			windowCodec.decode(
				ByteArrayInputStream(encodePressureEntries(listOf(twoWindows))),
			) { calls++ }
		}
		calls shouldBe 0
		shouldThrow<PortablePressureJsonException> {
			windowCodec.encode(ByteArrayOutputStream()) { sink ->
				sink.emit(twoWindows)
				ExportPortablePressureResult.Exported(1)
			}
		}
	}

	@Test
	fun `canonical order and global kind owner identity uniqueness are mandatory`() = runTest {
		val first = pressureEntry("first", 1_000L)
		val later = pressureEntry("later", 5_000L)
		shouldThrow<PortablePressureJsonException> {
			encodePressureEntries(listOf(later, first))
		}
		val outOfOrder = envelope(entryFragment(later), entryFragment(first))
		val orderedPrefix = mutableListOf<PortablePressureEntryV1>()
		shouldThrow<PortablePressureJsonException> {
			PortablePressureJsonV1Codec().decode(
				ByteArrayInputStream(outOfOrder.encodeToByteArray()),
				PortablePressureEntrySink { orderedPrefix += it },
			)
		}
		orderedPrefix shouldContainExactly listOf(later)

		val crossKind = pressureEntryWithWindows(
			seed = "second",
			windows = listOf(pressureWindow("second", 5_000L)),
			runIdentity = first.identity,
		)
		val combined = envelope(entryFragment(first), entryFragment(crossKind))
		val emitted = mutableListOf<PortablePressureEntryV1>()
		shouldThrow<PortablePressureJsonException> {
			PortablePressureJsonV1Codec().decode(
				ByteArrayInputStream(combined.encodeToByteArray()),
				PortablePressureEntrySink { emitted += it },
			)
		}
		emitted shouldContainExactly listOf(first)
	}

	@Test
	fun `entry count failure exposes only the authenticated replay safe prefix`() = runTest {
		val entries = listOf(
			pressureEntry("first", 1_000L),
			pressureEntry("second", 5_000L),
		)
		val encoded = encodePressureEntries(entries)
		val emitted = mutableListOf<PortablePressureEntryV1>()

		shouldThrow<PortablePressureJsonException> {
			PortablePressureJsonV1Codec(
				PortablePressureJsonLimits(maxEntries = 1),
			).decode(ByteArrayInputStream(encoded)) { emitted += it }
		}
		emitted shouldContainExactly listOf(entries.first())
	}

	@Test
	fun `non success producer outcomes cannot finalize emitted data`() = runTest {
		val output = ByteArrayOutputStream()
		PortablePressureJsonV1Codec().encode(output) {
			ExportPortablePressureResult.NoEntries
		} shouldBe ExportPortablePressureResult.NoEntries
		output.size() shouldBe 0

		shouldThrow<PortablePressureJsonException> {
			PortablePressureJsonV1Codec().encode(ByteArrayOutputStream()) { sink ->
				sink.emit(pressureEntry())
				ExportPortablePressureResult.NoEntries
			}
		}
	}

	@Test
	fun `cancellation and IO failures propagate and caller streams remain open`() = runTest {
		val output = CloseTrackingOutputStream()
		shouldThrow<CancellationException> {
			PortablePressureJsonV1Codec().encode(output) {
				throw CancellationException("cancel export")
			}
		}
		output.closed shouldBe false

		val bytes = encodePressureEntries(listOf(pressureEntry()))
		val input = CloseTrackingInputStream(bytes)
		shouldThrow<CancellationException> {
			PortablePressureJsonV1Codec().decode(input) {
				throw CancellationException("cancel import")
			}
		}
		input.closed shouldBe false

		val inputFailure = shouldThrow<IOException> {
			PortablePressureJsonV1Codec().decode(FailingInputStream()) {
				error("I/O failure must precede an entry")
			}
		}
		inputFailure.message shouldBe "read failed"

		val sinkFailure = shouldThrow<IOException> {
			PortablePressureJsonV1Codec().decode(ByteArrayInputStream(bytes)) {
				throw IOException("sink failed")
			}
		}
		sinkFailure.message shouldBe "sink failed"

		val outputFailure = shouldThrow<IOException> {
			PortablePressureJsonV1Codec().encode(FailingOutputStream()) { sink ->
				sink.emit(pressureEntry())
				ExportPortablePressureResult.Exported(1)
			}
		}
		outputFailure.message shouldBe "write failed"

		val successfulOutput = CloseTrackingOutputStream()
		PortablePressureJsonV1Codec().encode(successfulOutput) { sink ->
			sink.emit(pressureEntry())
			ExportPortablePressureResult.Exported(1)
		}
		successfulOutput.closed shouldBe false
		val successfulInput = CloseTrackingInputStream(successfulOutput.toByteArray())
		PortablePressureJsonV1Codec().decode(successfulInput) {}
		successfulInput.closed shouldBe false
	}

	private suspend fun entryFragment(entry: PortablePressureEntryV1): String {
		val document = encodePressureEntries(listOf(entry)).decodeToString()
		return document.removePrefix(ENVELOPE_PREFIX).removeSuffix("]}")
	}

	private fun envelope(vararg entries: String): String =
		ENVELOPE_PREFIX + entries.joinToString(",") + "]}"

	private companion object {
		const val ENVELOPE_PREFIX =
			"""{"format":"tracker-portable-pressure","schemaVersion":1,"entries":["""
	}
}

private class CloseTrackingInputStream(
	bytes: ByteArray,
) : ByteArrayInputStream(bytes) {
	var closed: Boolean = false

	override fun close() {
		closed = true
		super.close()
	}
}

private class CloseTrackingOutputStream : ByteArrayOutputStream() {
	var closed: Boolean = false

	override fun close() {
		closed = true
		super.close()
	}
}

private class FailingInputStream : InputStream() {
	override fun read(): Int = throw IOException("read failed")
	override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
		throw IOException("read failed")
}

private class FailingOutputStream : OutputStream() {
	override fun write(value: Int) = throw IOException("write failed")
	override fun write(buffer: ByteArray, offset: Int, length: Int) =
		throw IOException("write failed")
}
