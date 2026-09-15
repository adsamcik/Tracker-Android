package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapReason
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsPartialCause
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortableAmbientStepsJsonV1CodecTest {
	@Test
	fun `canonical round trip preserves covered zero partial gaps outside authority and zones`() =
		runTest {
			val archive = ambientArchive(
				completeAmbientDay(LocalDate.of(2026, 10, 25), 0L, "Europe/Prague"),
				partialAmbientDay(LocalDate.of(2026, 10, 26), 12L, "Europe/Prague"),
				partialAmbientDay(
					LocalDate.of(2026, 10, 27),
					4L,
					"UTC",
					cause = PortableAmbientStepsPartialCause.OUTSIDE_AUTHORITY,
				),
				partialAmbientDay(
					LocalDate.of(2026, 10, 28),
					5L,
					"UTC",
					cause = PortableAmbientStepsPartialCause.RETENTION,
				),
			)
			val first = encodeAmbientStepsArchive(archive)
			val second = encodeAmbientStepsArchive(archive)

			first.contentEquals(second) shouldBe true
			val decoded = PortableAmbientStepsJsonV1Codec().decode(ByteArrayInputStream(first))
			decoded.archive shouldBe archive
			decoded.metadata.encodedByteCount shouldBe first.size.toLong()
			decoded.metadata.dayCount shouldBe 4
			decoded.metadata.factCount shouldBe 4
			decoded.metadata.gapCount shouldBe 1
			decoded.archive.days.first().retainedStepCount shouldBe 0L
			decoded.archive.days[1].partialCauses shouldContainExactly
				listOf(PortableAmbientStepsPartialCause.EXPLICIT_GAP)
			decoded.archive.days[2].partialCauses shouldContainExactly
				listOf(PortableAmbientStepsPartialCause.OUTSIDE_AUTHORITY)
			decoded.archive.days[3].partialCauses shouldContainExactly
				listOf(PortableAmbientStepsPartialCause.RETENTION)
		}

	@Test
	fun `strict integer grammar rejects fraction exponent string and boolean counterfeits`() = runTest {
		val json = encodeAmbientStepsArchive(
			ambientArchive(completeAmbientDay(LocalDate.of(2026, 1, 1), 12L)),
		).decodeToString()
		listOf(
			json.replaceFirst("\"stepCount\":12", "\"stepCount\":12.0"),
			json.replaceFirst("\"stepCount\":12", "\"stepCount\":12e0"),
			json.replaceFirst("\"stepCount\":12", "\"stepCount\":\"12\""),
			json.replaceFirst("\"stepCount\":12", "\"stepCount\":true"),
		).forEach { invalid ->
			shouldThrow<PortableAmbientStepsFormatException> {
				PortableAmbientStepsJsonV1Codec().decode(
					ByteArrayInputStream(invalid.encodeToByteArray()),
				)
			}
		}
	}

	@Test
	fun `out of domain structural date is a permanent format failure`() = runTest {
		val archive = ambientArchive(
			completeAmbientDay(LocalDate.of(2026, 1, 1), 1L),
		)
		val day = archive.days.single()
		val invalid = encodeAmbientStepsArchive(archive).decodeToString()
			.replaceFirst(
				"\"structuralEpochDay\":${day.structuralEpochDay}",
				"\"structuralEpochDay\":${Long.MAX_VALUE}",
			)

		shouldThrow<PortableAmbientStepsFormatException> {
			PortableAmbientStepsJsonV1Codec().decode(
				ByteArrayInputStream(invalid.encodeToByteArray()),
			)
		}
	}

	@Test
	fun `unknown duplicate header ordering version trailing and truncation fail closed`() = runTest {
		val original = encodeAmbientStepsArchive(
			ambientArchive(completeAmbientDay(LocalDate.of(2026, 1, 1), 1L)),
		).decodeToString()
		val days = original.substringAfter("\"days\":").dropLast(1)
		val zeroDigest = "sha256:" + "0".repeat(64)
		val invalid = listOf(
			original.replaceFirst("\"schemaVersion\":1", "\"unknown\":0,\"schemaVersion\":1"),
			original.replaceFirst("\"format\":", "\"format\":\"duplicate\",\"format\":"),
			"""{"days":$days,"format":"tracker-portable-ambient-steps","schemaVersion":1,"contentChecksum":"$zeroDigest"}""",
			original.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2"),
			"$original true",
			original.dropLast(1),
		)

		invalid.forEach { document ->
			shouldThrow<PortableAmbientStepsFormatException> {
				PortableAmbientStepsJsonV1Codec().decode(
					ByteArrayInputStream(document.encodeToByteArray()),
				)
			}
		}
	}

	@Test
	fun `rehashed semantic corruption is rejected rather than only stale checksums`() = runTest {
		val archive = ambientArchive(completeAmbientDay(LocalDate.of(2026, 1, 1), 12L))
		val day = archive.days.single()
		val fact = day.facts.single()
		val invalidCount = -1L
		val factChecksum = rehashedAmbientFactChecksum(fact, invalidCount)
		val dayChecksum = rehashedAmbientDayChecksum(day, invalidCount, factChecksum)
		val archiveChecksum = rehashedAmbientArchiveChecksum(day, dayChecksum)
		val invalid = encodeAmbientStepsArchive(archive).decodeToString()
			.replaceFirst(archive.contentChecksum.value, archiveChecksum.value)
			.replaceFirst(day.contentChecksum.value, dayChecksum.value)
			.replaceFirst(fact.contentChecksum.value, factChecksum.value)
			.replaceFirst("\"retainedStepCount\":12", "\"retainedStepCount\":$invalidCount")
			.replaceFirst("\"stepCount\":12", "\"stepCount\":$invalidCount")

		factChecksum shouldNotBe fact.contentChecksum
		shouldThrow<PortableAmbientStepsFormatException> {
			PortableAmbientStepsJsonV1Codec().decode(
				ByteArrayInputStream(invalid.encodeToByteArray()),
			)
		}
	}

	@Test
	fun `shared lexical limits reject huge tokens early and retain valid escaped UTF8`() = runTest {
		val huge = "x".repeat(10_000)
		val hugeNumber = "1".repeat(10_000)
		listOf(
			"""{"format":"$huge"}""",
			"""{"$huge":0}""",
			"""{"schemaVersion":$hugeNumber}""",
		).forEach { document ->
			val input = CountingAmbientInputStream(document.encodeToByteArray())
			shouldThrow<PortableAmbientStepsFormatException> {
				PortableAmbientStepsJsonV1Codec().decode(input)
			}
			(input.bytesRead < document.length / 2) shouldBe true
		}

		val archive = ambientArchive(
			completeAmbientDay(LocalDate.of(2026, 1, 1), 12L, "Europe/Prague"),
		)
		val escaped = encodeAmbientStepsArchive(archive).decodeToString()
			.replaceFirst("\"format\":", "\"for\\u006dat\":")
			.replaceFirst(
				"\"tracker-portable-ambient-steps\"",
				"\"tracker-portable-ambient-\\u0073teps\"",
			)
			.replaceFirst("\"Europe/Prague\"", "\"Europe\\/Prague\"")
		PortableAmbientStepsJsonV1Codec().decode(
			ByteArrayInputStream(escaped.encodeToByteArray()),
		).archive shouldBe archive
	}

	@Test
	fun `exact narrowed byte and collection limits pass while cap plus one fails`() = runTest {
		val archive = ambientArchive(
			partialAmbientDay(LocalDate.of(2026, 1, 1), 2L),
			completeAmbientDay(LocalDate.of(2026, 1, 2), 3L),
		)
		val bytes = encodeAmbientStepsArchive(archive)
		val exact = PortableAmbientStepsJsonV1Codec(
			PortableAmbientStepsJsonLimits(
				maxFileBytes = bytes.size.toLong(),
				maxDays = 2,
				maxFacts = 2,
				maxGaps = 1,
				maxFactsPerDay = 1,
				maxGapsPerDay = 1,
			),
		)

		exact.decode(ByteArrayInputStream(bytes)).archive shouldBe archive
		shouldThrow<PortableAmbientStepsFormatException> {
			PortableAmbientStepsJsonV1Codec(
				PortableAmbientStepsJsonLimits(maxFileBytes = bytes.size.toLong() - 1L),
			).decode(ByteArrayInputStream(bytes))
		}
		listOf(
			PortableAmbientStepsJsonLimits(maxDays = 1),
			PortableAmbientStepsJsonLimits(maxFacts = 1),
			PortableAmbientStepsJsonLimits(maxGaps = 0),
		).forEach { constrained ->
			shouldThrow<PortableAmbientStepsFormatException> {
				PortableAmbientStepsJsonV1Codec(constrained).decode(ByteArrayInputStream(bytes))
			}
			shouldThrow<PortableAmbientStepsFormatException> {
				PortableAmbientStepsJsonV1Codec(constrained).encode(ByteArrayOutputStream()) { sink ->
					sink.emit(archive)
					ExportPortableAmbientStepsResult.Exported(2, 2, 1)
				}
			}
		}
	}

	@Test
	fun `per day fact and gap caps reject before archive materialization`() = runTest {
		val date = LocalDate.of(2026, 1, 1)
		val (start, end) = ambientDayBounds(date, "UTC")
		val boundaryOne = start + 1_000L
		val boundaryTwo = start + 2_000L
		val boundaryThree = start + 3_000L
		val day = PortableAmbientStepsDayV1.create(
			identity = ambientIdentity(
				com.adsamcik.tracker.shared.model.steps.portable
					.AmbientStepsPortableIdentityKind.DAY,
				"bounded-day",
			),
			structuralEpochDay = date.toEpochDay(),
			storedZoneId = "UTC",
			structuralDayStartTimeMs = start,
			structuralDayEndTimeMs = end,
			retainedFromTimeMs = null,
			coverage = PortableAmbientStepsCoverage.PARTIAL,
			partialCauses = listOf(PortableAmbientStepsPartialCause.EXPLICIT_GAP),
			retainedStepCount = 3L,
			facts = listOf(
				PortableAmbientStepsFactV1.create(
					ambientIdentity(
						com.adsamcik.tracker.shared.model.steps.portable
							.AmbientStepsPortableIdentityKind.FACT,
						"bounded-fact-one",
					),
					boundaryOne,
					boundaryTwo,
					1L,
				),
				PortableAmbientStepsFactV1.create(
					ambientIdentity(
						com.adsamcik.tracker.shared.model.steps.portable
							.AmbientStepsPortableIdentityKind.FACT,
						"bounded-fact-two",
					),
					boundaryThree,
					end,
					2L,
				),
			),
			gaps = listOf(
				PortableAmbientStepsGapV1.create(
					ambientIdentity(
						com.adsamcik.tracker.shared.model.steps.portable
							.AmbientStepsPortableIdentityKind.GAP,
						"bounded-gap-one",
					),
					start,
					boundaryOne,
					PortableAmbientStepsGapReason.PROCESS_ABSENCE,
				),
				PortableAmbientStepsGapV1.create(
					ambientIdentity(
						com.adsamcik.tracker.shared.model.steps.portable
							.AmbientStepsPortableIdentityKind.GAP,
						"bounded-gap-two",
					),
					boundaryTwo,
					boundaryThree,
					PortableAmbientStepsGapReason.PROVIDER_NO_EVIDENCE,
				),
			),
		)
		val archive = ambientArchive(day)
		val bytes = encodeAmbientStepsArchive(archive)

		listOf(
			PortableAmbientStepsJsonLimits(maxFactsPerDay = 1),
			PortableAmbientStepsJsonLimits(maxGapsPerDay = 1),
		).forEach { limits ->
			shouldThrow<PortableAmbientStepsFormatException> {
				PortableAmbientStepsJsonV1Codec(limits).decode(ByteArrayInputStream(bytes))
			}
			shouldThrow<PortableAmbientStepsFormatException> {
				PortableAmbientStepsJsonV1Codec(limits).encode(ByteArrayOutputStream()) { sink ->
					sink.emit(archive)
					ExportPortableAmbientStepsResult.Exported(1, 2, 2)
				}
			}
		}
	}

	@Test
	fun `mutable graph totals are rejected before deep copy or semantic revalidation`() = runTest {
		val original = ambientArchive(
			completeAmbientDay(LocalDate.of(2026, 1, 1), 1L),
		)
		val guardedDays = GuardedAmbientList(original.days)
		val mutableArchive = PortableAmbientStepsArchiveV1(
			contentChecksum = original.contentChecksum,
			days = guardedDays,
		)
		guardedDays.add(completeAmbientDay(LocalDate.of(2026, 1, 2), 2L))
		guardedDays.throwOnElementRead = true

		shouldThrow<PortableAmbientStepsFormatException> {
			PortableAmbientStepsJsonV1Codec(
				PortableAmbientStepsJsonLimits(maxDays = 1),
			).encode(ByteArrayOutputStream()) { sink ->
				sink.emit(mutableArchive)
				ExportPortableAmbientStepsResult.Exported(2, 2, 0)
			}
		}

		val originalDay = original.days.single()
		val guardedFacts = GuardedAmbientList(originalDay.facts)
		val mutableDay = PortableAmbientStepsDayV1(
			identity = originalDay.identity,
			contentChecksum = originalDay.contentChecksum,
			structuralEpochDay = originalDay.structuralEpochDay,
			storedZoneId = originalDay.storedZoneId,
			structuralDayStartTimeMs = originalDay.structuralDayStartTimeMs,
			structuralDayEndTimeMs = originalDay.structuralDayEndTimeMs,
			retainedFromTimeMs = originalDay.retainedFromTimeMs,
			coverage = originalDay.coverage,
			partialCauses = originalDay.partialCauses,
			retainedStepCount = originalDay.retainedStepCount,
			facts = guardedFacts,
			gaps = originalDay.gaps,
		)
		val factMutableArchive = PortableAmbientStepsArchiveV1.create(listOf(mutableDay))
		guardedFacts.add(
			PortableAmbientStepsFactV1.create(
				ambientIdentity(
					com.adsamcik.tracker.shared.model.steps.portable
						.AmbientStepsPortableIdentityKind.FACT,
					"later-fact",
				),
				originalDay.structuralDayStartTimeMs,
				originalDay.structuralDayStartTimeMs + 1L,
				0L,
			),
		)
		guardedFacts.throwOnElementRead = true
		shouldThrow<PortableAmbientStepsFormatException> {
			PortableAmbientStepsJsonV1Codec(
				PortableAmbientStepsJsonLimits(maxFacts = 1),
			).encode(ByteArrayOutputStream()) { sink ->
				sink.emit(factMutableArchive)
				ExportPortableAmbientStepsResult.Exported(1, 2, 0)
			}
		}
	}

	@Test
	fun `same kind fact identity cannot move between structural day owners`() = runTest {
		val first = completeAmbientDay(LocalDate.of(2026, 1, 1), 1L)
		val secondBase = completeAmbientDay(LocalDate.of(2026, 1, 2), 2L)
		val movedFact = PortableAmbientStepsFactV1.create(
			first.facts.single().identity,
			secondBase.structuralDayStartTimeMs,
			secondBase.structuralDayEndTimeMs,
			2L,
		)
		val second = PortableAmbientStepsDayV1.create(
			identity = secondBase.identity,
			structuralEpochDay = secondBase.structuralEpochDay,
			storedZoneId = secondBase.storedZoneId,
			structuralDayStartTimeMs = secondBase.structuralDayStartTimeMs,
			structuralDayEndTimeMs = secondBase.structuralDayEndTimeMs,
			retainedFromTimeMs = null,
			coverage = secondBase.coverage,
			partialCauses = secondBase.partialCauses,
			retainedStepCount = 2L,
			facts = listOf(movedFact),
			gaps = emptyList(),
		)
		val archive = ambientArchive(first, second)

		shouldThrow<PortableAmbientStepsFormatException> {
			encodeAmbientStepsArchive(archive)
		}
	}

	@Test
	fun `raw IO cancellation producer failure and caller stream ownership are preserved`() = runTest {
		val archive = ambientArchive(completeAmbientDay(LocalDate.of(2026, 1, 1), 1L))
		val bytes = encodeAmbientStepsArchive(archive)
		val input = CloseTrackingAmbientInputStream(bytes)
		PortableAmbientStepsJsonV1Codec().decode(input).archive shouldBe archive
		input.closed shouldBe false

		val output = CloseTrackingAmbientOutputStream()
		PortableAmbientStepsJsonV1Codec().encode(output) { sink ->
			sink.emit(archive)
			ExportPortableAmbientStepsResult.Exported(1, 1, 0)
		}
		output.closed shouldBe false

		shouldThrow<IOException> {
			PortableAmbientStepsJsonV1Codec().decode(FailingAmbientInputStream())
		}.message shouldBe "transport failed"
		val transportEof = EOFException("transport EOF")
		val propagatedEof = shouldThrow<EOFException> {
			PortableAmbientStepsJsonV1Codec().decode(
				PrefixThenFailingAmbientInputStream(bytes, transportEof),
			)
		}
		(propagatedEof === transportEof) shouldBe true
		shouldThrow<PortableAmbientStepsFormatException> {
			PortableAmbientStepsJsonV1Codec().decode(
				ByteArrayInputStream(bytes.copyOf(bytes.size - 1)),
			)
		}
		val producerOutput = ByteArrayOutputStream()
		shouldThrow<IOException> {
			PortableAmbientStepsJsonV1Codec().encode(producerOutput) { sink ->
				sink.emit(archive)
				throw EOFException("producer EOF")
			}
		}.message shouldBe "producer EOF"
		producerOutput.size() shouldBe 0
		shouldThrow<CancellationException> {
			PortableAmbientStepsJsonV1Codec().encode(ByteArrayOutputStream()) {
				throw CancellationException("cancelled")
			}
		}
		shouldThrow<CancellationException> {
			PortableAmbientStepsJsonV1Codec().decode(
				object : InputStream() {
					override fun read(): Int = throw CancellationException("cancelled")
				},
			)
		}
		val noDataOutput = ByteArrayOutputStream()
		PortableAmbientStepsJsonV1Codec().encode(noDataOutput) {
			ExportPortableAmbientStepsResult.NoData
		} shouldBe ExportPortableAmbientStepsResult.NoData
		noDataOutput.size() shouldBe 0
		shouldThrow<PortableAmbientStepsFormatException> {
			PortableAmbientStepsJsonV1Codec().encode(ByteArrayOutputStream()) { sink ->
				sink.emit(archive)
				ExportPortableAmbientStepsResult.NoData
			}
		}
	}
}

private class GuardedAmbientList<T>(
	values: List<T>,
) : AbstractMutableList<T>() {
	private val delegate = values.toMutableList()
	var throwOnElementRead = false

	override val size: Int get() = delegate.size

	override fun get(index: Int): T {
		check(!throwOnElementRead) { "Element access occurred before count rejection" }
		return delegate[index]
	}

	override fun set(index: Int, element: T): T = delegate.set(index, element)
	override fun add(index: Int, element: T) = delegate.add(index, element)
	override fun removeAt(index: Int): T = delegate.removeAt(index)
}

private class CountingAmbientInputStream(
	private val bytes: ByteArray,
) : InputStream() {
	var bytesRead = 0
		private set

	override fun read(): Int = if (bytesRead == bytes.size) {
		-1
	} else {
		bytes[bytesRead++].toInt() and 0xff
	}

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
		if (bytesRead == bytes.size) return -1
		val count = minOf(length, bytes.size - bytesRead)
		bytes.copyInto(buffer, offset, bytesRead, bytesRead + count)
		bytesRead += count
		return count
	}
}

private class CloseTrackingAmbientInputStream(
	bytes: ByteArray,
) : ByteArrayInputStream(bytes) {
	var closed = false

	override fun close() {
		closed = true
		super.close()
	}
}

private class CloseTrackingAmbientOutputStream : ByteArrayOutputStream() {
	var closed = false

	override fun close() {
		closed = true
		super.close()
	}
}

private class FailingAmbientInputStream : InputStream() {
	override fun read(): Int = throw IOException("transport failed")
	override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
		throw IOException("transport failed")
}

private class PrefixThenFailingAmbientInputStream(
	bytes: ByteArray,
	private val failure: IOException,
) : InputStream() {
	private val delegate = ByteArrayInputStream(bytes)

	override fun read(): Int = delegate.read().takeUnless { it < 0 } ?: throw failure

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
		delegate.read(buffer, offset, length).takeUnless { it < 0 } ?: throw failure
}
