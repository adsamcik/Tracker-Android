package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntrySink
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureRunV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowV1
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
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
	fun `rehashed semantically invalid window and entry are rejected before mutation`() = runTest {
		val entry = pressureEntry()
		val window = entry.runs.single().windows.single()
		val original = encodePressureEntries(listOf(entry)).decodeToString()
		val invalidMean = -1.0
		val invalidWindowChecksum = rehashedPressureWindowChecksum(
			window,
			meanHectopascals = invalidMean,
		)
		val invalidWindow = original
			.replaceFirst(window.contentChecksum.value, invalidWindowChecksum.value)
			.replaceFirst(
				"\"meanHectopascals\":${window.meanHectopascals}",
				"\"meanHectopascals\":$invalidMean",
			)
		val invalidStart = entry.startTimeMs - 1L
		val invalidEntryChecksum = rehashedPressureEntryChecksum(entry, invalidStart)
		val invalidEntry = original
			.replaceFirst(entry.contentChecksum.value, invalidEntryChecksum.value)
			.replaceFirst(
				"\"startTimeMs\":${entry.startTimeMs}",
				"\"startTimeMs\":$invalidStart",
			)

		invalidWindowChecksum shouldNotBe window.contentChecksum
		invalidEntryChecksum shouldNotBe entry.contentChecksum
		listOf(invalidWindow, invalidEntry).forEach { document ->
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
	fun `lexical token bounds reject huge tokens without consuming the whole token`() = runTest {
		val huge = "x".repeat(10_000)
		val hugeNumber = "1".repeat(10_000)
		val documents = listOf(
			"""{"format":"$huge","schemaVersion":1,"entries":[]}""",
			"""{"$huge":true}""",
			"""{"format":"tracker-portable-pressure","schemaVersion":$hugeNumber,"entries":[]}""",
		)

		documents.forEach { document ->
			val input = CountingInputStream(document.encodeToByteArray())
			shouldThrow<PortablePressureJsonException> {
				PortablePressureJsonV1Codec().decode(input) {
					error("Oversized lexical tokens must not reach the sink")
				}
			}
			(input.bytesRead < document.length / 2) shouldBe true
		}
	}

	@Test
	fun `shared lexical guard bounds every bare literal candidate and invalid number suffix`() {
		val limits = PortableJsonTokenLimits()
		val candidates = listOf(
			"x".repeat(10_000),
			"1_" + "x".repeat(10_000),
			"@".repeat(10_000),
		)

		candidates.forEach { candidate ->
			val input = CountingInputStream(candidate.encodeToByteArray())
			shouldThrow<PortableJsonTokenLimitException> {
				PortableJsonTokenLimitInputStream(input, limits).readBytes()
			}
			(input.bytesRead <= limits.maxLiteralBytes + limits.maxReadChunkBytes) shouldBe true
		}
		val valid = "true false null -1.25e+2".encodeToByteArray()
		PortableJsonTokenLimitInputStream(ByteArrayInputStream(valid), limits)
			.readBytes()
			.contentEquals(valid) shouldBe true
	}

	@Test
	fun `Pressure codec bounds alphabetic suffixed numeric and punctuation literals`() = runTest {
		val prefix =
			"""{"format":"tracker-portable-pressure","schemaVersion":"""
		val suffix = ""","entries":[]}"""
		val limits = PortableJsonTokenLimits()
		listOf(
			"x".repeat(10_000),
			"1_" + "x".repeat(10_000),
			"@".repeat(10_000),
		).forEach { literal ->
			val document = prefix + literal + suffix
			val input = CountingInputStream(document.encodeToByteArray())
			val failure = shouldThrow<PortablePressureJsonException> {
				PortablePressureJsonV1Codec().decode(input) {
					error("Oversized literal must not reach the sink")
				}
			}
			(failure.cause is PortableJsonTokenLimitException) shouldBe true
			(
				input.bytesRead <=
					prefix.encodeToByteArray().size +
					limits.maxLiteralBytes +
					limits.maxReadChunkBytes
				) shouldBe true
		}
	}

	@Test
	fun `valid escaped strings names and exponent numerics remain accepted`() = runTest {
		val entry = pressureEntry()
		val escaped = encodePressureEntries(listOf(entry)).decodeToString()
			.replaceFirst("\"format\":", "\"for\\u006dat\":")
			.replaceFirst(
				"\"tracker-portable-pressure\"",
				"\"\\u0074racker-portable-pressure\"",
			)
			.replaceFirst("\"meanHectopascals\":1000.25", "\"meanHectopascals\":1.00025e3")
			.replaceFirst("\"Europe/Prague\"", "\"Europe\\/Prague\"")
		val decoded = mutableListOf<PortablePressureEntryV1>()

		PortablePressureJsonV1Codec().decode(
			ByteArrayInputStream(escaped.encodeToByteArray()),
			PortablePressureEntrySink { decoded += it },
		) shouldBe 1
		decoded shouldContainExactly listOf(entry)
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
		shouldThrow<PortablePressureJsonException> {
			totalBounded.encode(ByteArrayOutputStream()) { sink ->
				sink.emit(entry)
				ExportPortablePressureResult.Exported(1)
			}
		}

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
	fun `exact narrowed byte run window and document totals are accepted`() = runTest {
		val entry = pressureEntry(runCount = 2, windowsPerRun = 2)
		val encoded = encodePressureEntries(listOf(entry))
		val codec = PortablePressureJsonV1Codec(
			PortablePressureJsonLimits(
				maxFileBytes = encoded.size.toLong(),
				maxEntries = 1,
				maxRunsPerEntry = 2,
				maxWindowsPerRun = 2,
				maxTotalRuns = 2,
				maxTotalWindows = 4,
			),
		)
		val decoded = mutableListOf<PortablePressureEntryV1>()

		codec.decode(ByteArrayInputStream(encoded)) { decoded += it } shouldBe 1
		decoded shouldContainExactly listOf(entry)
		val output = ByteArrayOutputStream()
		codec.encode(output) { sink ->
			sink.emit(entry)
			ExportPortablePressureResult.Exported(1)
		}
		output.toByteArray().contentEquals(encoded) shouldBe true
	}

	@Test
	fun `raw mutable graph overflow is rejected before deep copy or checksum revalidation`() = runTest {
		val original = pressureEntry()
		val guardedRuns = GuardedMutableList(original.runs)
		val mutableRunsEntry = PortablePressureEntryV1(
			identity = original.identity,
			contentChecksum = original.contentChecksum,
			startTimeMs = original.startTimeMs,
			endTimeMs = original.endTimeMs,
			runs = guardedRuns,
		)
		guardedRuns.add(pressureEntry("later", 5_000L).runs.single())
		guardedRuns.throwOnElementRead = true

		shouldThrow<PortablePressureJsonException> {
			PortablePressureJsonV1Codec(
				PortablePressureJsonLimits(maxTotalRuns = 1),
			).encode(ByteArrayOutputStream()) { sink ->
				sink.emit(mutableRunsEntry)
				ExportPortablePressureResult.Exported(1)
			}
		}

		val originalRun = original.runs.single()
		val guardedWindows = GuardedMutableList(originalRun.windows)
		val mutableRun = PortablePressureRunV1(
			identity = originalRun.identity,
			startTimeMs = originalRun.startTimeMs,
			endTimeMs = originalRun.endTimeMs,
			capturedForWholeRun = originalRun.capturedForWholeRun,
			availability = originalRun.availability,
			coverage = originalRun.coverage,
			retentionLoss = originalRun.retentionLoss,
			windows = guardedWindows,
		)
		val mutableWindowsEntry = PortablePressureEntryV1.create(
			identity = original.identity,
			startTimeMs = mutableRun.startTimeMs,
			endTimeMs = mutableRun.endTimeMs,
			runs = listOf(mutableRun),
		)
		guardedWindows.add(pressureWindow("later-window", 2_000L))
		guardedWindows.throwOnElementRead = true

		shouldThrow<PortablePressureJsonException> {
			PortablePressureJsonV1Codec(
				PortablePressureJsonLimits(maxTotalWindows = 1),
			).encode(ByteArrayOutputStream()) { sink ->
				sink.emit(mutableWindowsEntry)
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

		val movedRun = pressureEntryWithWindows(
			seed = "moved-owner",
			windows = listOf(pressureWindow("moved-owner", 5_000L)),
			runIdentity = first.runs.single().identity,
		)
		shouldThrow<PortablePressureJsonException> {
			encodePressureEntries(listOf(first, movedRun))
		}
		val movedOwnerDocument = envelope(entryFragment(first), entryFragment(movedRun))
		val movedOwnerPrefix = mutableListOf<PortablePressureEntryV1>()
		shouldThrow<PortablePressureJsonException> {
			PortablePressureJsonV1Codec().decode(
				ByteArrayInputStream(movedOwnerDocument.encodeToByteArray()),
				PortablePressureEntrySink { movedOwnerPrefix += it },
			)
		}
		movedOwnerPrefix shouldContainExactly listOf(first)
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

		val transportEof = EOFException("source transport EOF")
		var eofPrefixCount = 0
		val propagatedEof = shouldThrow<EOFException> {
			PortablePressureJsonV1Codec().decode(
				PrefixThenFailingInputStream(bytes, transportEof),
			) {
				eofPrefixCount++
			}
		}
		(propagatedEof === transportEof) shouldBe true
		eofPrefixCount shouldBe 1
		val genuinelyTruncated = bytes.copyOf(bytes.size - 1)
		shouldThrow<PortablePressureJsonException> {
			PortablePressureJsonV1Codec().decode(ByteArrayInputStream(genuinelyTruncated)) {
				error("Truncated document must not reach the sink")
			}
		}

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

		val producerOutput = CloseTrackingOutputStream()
		val producerFailure = shouldThrow<IOException> {
			PortablePressureJsonV1Codec().encode(producerOutput) { sink ->
				sink.emit(pressureEntry())
				throw IOException("producer failed")
			}
		}
		producerFailure.message shouldBe "producer failed"
		producerOutput.closed shouldBe false

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

private class GuardedMutableList<T>(
	values: List<T>,
) : AbstractMutableList<T>() {
	private val delegate = values.toMutableList()
	var throwOnElementRead: Boolean = false

	override val size: Int get() = delegate.size

	override fun get(index: Int): T {
		check(!throwOnElementRead) { "Element access occurred before count rejection" }
		return delegate[index]
	}

	override fun set(index: Int, element: T): T = delegate.set(index, element)

	override fun add(index: Int, element: T) {
		delegate.add(index, element)
	}

	override fun removeAt(index: Int): T = delegate.removeAt(index)
}

private class CountingInputStream(
	bytes: ByteArray,
) : InputStream() {
	private val delegate = ByteArrayInputStream(bytes)
	var bytesRead: Int = 0
		private set

	override fun read(): Int = delegate.read().also { value ->
		if (value >= 0) bytesRead++
	}

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
		delegate.read(buffer, offset, length).also { count ->
			if (count > 0) bytesRead += count
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

private class PrefixThenFailingInputStream(
	bytes: ByteArray,
	private val failure: IOException,
) : InputStream() {
	private val delegate = ByteArrayInputStream(bytes)

	override fun read(): Int = delegate.read().takeUnless { it < 0 } ?: throw failure

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
		delegate.read(buffer, offset, length).takeUnless { it < 0 } ?: throw failure
}

private class FailingOutputStream : OutputStream() {
	override fun write(value: Int) = throw IOException("write failed")
	override fun write(buffer: ByteArray, offset: Int, length: Int) =
		throw IOException("write failed")
}
