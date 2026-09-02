package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableStepsCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsCompletenessV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntrySink
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableStepsManifestV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableStepsProviderCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsRunV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsSessionMode
import com.adsamcik.tracker.stats.api.repository.PortableStepsTransferRetryableReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortableStepsJsonV1CodecTest {
	@Test
	fun `canonical bytes round trip replacement runs without materializing the document`() = runTest {
		val entries = listOf(
			entry(seed = "entry-one", startTimeMs = 1_000L, replacementRun = true),
			entry(seed = "entry-two", startTimeMs = 5_000L),
		)
		val firstBytes = encode(entries)
		val secondBytes = encode(entries)
		val decoded = mutableListOf<PortableStepsEntryV1>()

		firstBytes.contentEquals(secondBytes) shouldBe true
		PortableStepsJsonV1Codec().decode(
			ByteArrayInputStream(firstBytes),
			PortableStepsEntrySink { entry -> decoded += entry },
		) shouldBe entries.size
		decoded shouldContainExactly entries
	}

	@Test
	fun `input object order does not change canonical semantic content`() = runTest {
		val entry = entry()
		val canonical = encode(listOf(entry)).decodeToString()
		val reorderedHeader = canonical.replaceFirst(
			"{\"format\":\"tracker-portable-steps\",\"schemaVersion\":1",
			"{\"schemaVersion\":1,\"format\":\"tracker-portable-steps\"",
		)
		val decoded = mutableListOf<PortableStepsEntryV1>()

		reorderedHeader shouldNotBe canonical
		PortableStepsJsonV1Codec().decode(
			ByteArrayInputStream(reorderedHeader.encodeToByteArray()),
			PortableStepsEntrySink { value -> decoded += value },
		) shouldBe 1
		decoded shouldContainExactly listOf(entry)
		encode(decoded).decodeToString() shouldBe canonical
	}

	@Test
	@Suppress("LongMethod")
	fun `wire vocabulary is a privacy whitelist and absence never becomes zero`() = runTest {
		val rawEntryIdentity = "raw-logical-entry-id"
		val rawRunIdentity = "raw-physical-run-id"
		val rawFactIdentity = "raw-fact-id"
		val baseline = entry(
			seed = rawEntryIdentity,
			startTimeMs = 1_000L,
			factCoverage = PortableStepsFactCoverage.BASELINE,
			stepCount = null,
			runIdentitySeed = rawRunIdentity,
			factIdentitySeed = rawFactIdentity,
		)
		val json = encode(listOf(baseline)).decodeToString()

		json.startsWith(
			"{\"format\":\"tracker-portable-steps\",\"schemaVersion\":1,\"entries\":[",
		) shouldBe true
		json.contains("\"source\":\"STEPS\",\"purpose\":\"SESSION_CAPTURE\"") shouldBe true
		json.contains("\"deletionScopeDigest\":") shouldBe true
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
			"sessionMode",
			"startTimeMs",
			"endTimeMs",
			"runs",
			"deletionScopeDigest",
			"storedZoneId",
			"manifests",
			"completeness",
			"facts",
			"revision",
			"effectiveWallTimeMs",
			"originSourcePolicyRevision",
			"captureConsentEpoch",
			"source",
			"purpose",
			"captureCoverage",
			"providerCoverage",
			"appDrainComplete",
			"stopComplete",
			"hasUnresolvedProviderRange",
			"manifestRevision",
			"intervalStartTimeMs",
			"intervalEndTimeMs",
			"wallTimeUncertaintyMs",
			"coverage",
		)
		listOf(
			rawEntryIdentity,
			rawRunIdentity,
			rawFactIdentity,
			"\"stepCount\"",
			"\"latitude\"",
			"\"longitude\"",
			"\"distanceM\"",
			"\"pressure\"",
			"\"activity\"",
			"\"ssid\"",
			"\"bssid\"",
			"\"cellId\"",
			"\"providerId\"",
			"\"bootClockDomainId\"",
			"\"clockDomainId\"",
			"\"elapsedRealtimeNanos\"",
			"\"sourceEventId\"",
			"\"sourceAdmissionOrdinal\"",
			"\"sourceInstanceId\"",
			"\"registrationGeneration\"",
			"\"sampleCount\"",
			"\"control\"",
			"\"deletionFence\"",
			"\"retentionFloor\"",
			"\"currentAuthority\"",
		).forEach { forbidden ->
			json.contains(forbidden) shouldBe false
		}
	}

	@Test
	fun `portable deletion scope is exactly compatible with the durable v28 fence key`() {
		listOf(
			"logical-secret" to "run-secret",
			"logical-č" to "run-🚶",
		).forEach { (logicalTrackingId, serviceRunId) ->
			val portable = PortableStepsDeletionScopeDigest.derive(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
			)
			val durable = SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
			)

			portable.value shouldBe durable
			portable.value.contains(logicalTrackingId) shouldBe false
			portable.value.contains(serviceRunId) shouldBe false
		}
	}

	@Test
	fun `duplicate and unknown fields are rejected before an entry is emitted`() = runTest {
		val original = encode(listOf(entry())).decodeToString()
		val duplicateFormat = original.replaceFirst(
			"\"format\":\"tracker-portable-steps\"",
			"\"format\":\"tracker-portable-steps\",\"format\":\"tracker-portable-steps\"",
		)
		val unknownField = original.replaceFirst(
			"\"schemaVersion\":1",
			"\"unexpected\":true,\"schemaVersion\":1",
		)

		listOf(duplicateFormat, unknownField).forEach { invalid ->
			val decoded = mutableListOf<PortableStepsEntryV1>()
			shouldThrow<PortableStepsJsonException> {
				PortableStepsJsonV1Codec().decode(
					ByteArrayInputStream(invalid.encodeToByteArray()),
					PortableStepsEntrySink { entry -> decoded += entry },
				)
			}
			decoded.shouldBeEmpty()
		}
	}

	@Test
	fun `unknown or non-integral schema and non Steps source are rejected`() = runTest {
		val original = encode(listOf(entry())).decodeToString()
		val unknownSchema = original.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2")
		val decimalSchema = original.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":1.0")
		val controlSource = original.replaceFirst("\"source\":\"STEPS\"", "\"source\":\"ACTIVITY\"")

		listOf(unknownSchema, decimalSchema, controlSource).forEach { invalid ->
			shouldThrow<PortableStepsJsonException> {
				PortableStepsJsonV1Codec().decode(
					ByteArrayInputStream(invalid.encodeToByteArray()),
					PortableStepsEntrySink { error("An invalid entry must not reach its sink") },
				)
			}
		}
	}

	@Test
	fun `semantic checksum tampering is rejected before import`() = runTest {
		val original = encode(listOf(entry(stepCount = 12L))).decodeToString()
		val tampered = original.replaceFirst("\"stepCount\":12", "\"stepCount\":13")
		val decoded = mutableListOf<PortableStepsEntryV1>()

		tampered shouldNotBe original
		shouldThrow<PortableStepsJsonException> {
			PortableStepsJsonV1Codec().decode(
				ByteArrayInputStream(tampered.encodeToByteArray()),
				PortableStepsEntrySink { entry -> decoded += entry },
			)
		}
		decoded.shouldBeEmpty()
	}

	@Test
	fun `lower codec collection bounds apply to encoding and decoding`() = runTest {
		val entry = entry(factCount = 2)
		val encoded = encode(listOf(entry))
		val constrained = PortableStepsJsonV1Codec(
			PortableStepsJsonLimits(maxFactsPerRun = 1),
		)

		shouldThrow<PortableStepsJsonException> {
			constrained.encode(ByteArrayOutputStream()) { sink ->
				sink.emit(entry)
				ExportPortableStepsResult.Exported(1)
			}
		}
		shouldThrow<PortableStepsJsonException> {
			constrained.decode(
				ByteArrayInputStream(encoded),
				PortableStepsEntrySink { error("An oversized entry must not reach its sink") },
			)
		}
	}

	@Test
	fun `encoder rechecks a graph whose backing collection changed after construction`() = runTest {
		val original = entry(seed = "mutable", startTimeMs = 1_000L)
		val mutableRuns = original.runs.toMutableList()
		val mutableEntry = PortableStepsEntryV1.create(
			identity = original.identity,
			sessionMode = original.sessionMode,
			startTimeMs = original.startTimeMs,
			endTimeMs = original.endTimeMs,
			runs = mutableRuns,
		)
		mutableRuns += entry(seed = "later", startTimeMs = 3_000L).runs.single()

		shouldThrow<PortableStepsJsonException> {
			PortableStepsJsonV1Codec().encode(ByteArrayOutputStream()) { sink ->
				sink.emit(mutableEntry)
				ExportPortableStepsResult.Exported(1)
			}
		}
	}

	@Test
	fun `byte bounds reject oversized input and output`() = runTest {
		val encoded = encode(listOf(entry()))
		val constrained = PortableStepsJsonV1Codec(
			PortableStepsJsonLimits(maxFileBytes = 32L),
		)

		shouldThrow<PortableStepsJsonException> {
			constrained.encode(ByteArrayOutputStream()) { sink ->
				sink.emit(entry())
				ExportPortableStepsResult.Exported(1)
			}
		}
		shouldThrow<PortableStepsJsonException> {
			constrained.decode(
				ByteArrayInputStream(encoded),
				PortableStepsEntrySink { error("An oversized document must not reach its sink") },
			)
		}
	}

	@Test
	fun `no entries returns its typed outcome without creating an empty portable file`() = runTest {
		val output = ByteArrayOutputStream()
		PortableStepsJsonV1Codec().encode(output) {
			ExportPortableStepsResult.NoEntries
		} shouldBe ExportPortableStepsResult.NoEntries
		output.size() shouldBe 0

		shouldThrow<PortableStepsJsonException> {
			PortableStepsJsonV1Codec().decode(
				ByteArrayInputStream(
					"""{"format":"tracker-portable-steps","schemaVersion":1,"entries":[]}"""
						.encodeToByteArray(),
				),
				PortableStepsEntrySink { error("An empty document has no entry to emit") },
			)
		}
	}

	@Test
	fun `only an exact successful producer result can finalize a portable file`() = runTest {
		shouldThrow<PortableStepsJsonException> {
			PortableStepsJsonV1Codec().encode(ByteArrayOutputStream()) { sink ->
				sink.emit(entry())
				ExportPortableStepsResult.Exported(entryCount = 2)
			}
		}

		shouldThrow<PortableStepsJsonException> {
			PortableStepsJsonV1Codec().encode(ByteArrayOutputStream()) { sink ->
				sink.emit(entry())
				ExportPortableStepsResult.RetryableFailure(
					PortableStepsTransferRetryableReason.CONCURRENT_STATE_CHANGE,
				)
			}
		}

		val output = ByteArrayOutputStream()
		PortableStepsJsonV1Codec().encode(output) {
			ExportPortableStepsResult.RetryableFailure(
				PortableStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		} shouldBe ExportPortableStepsResult.RetryableFailure(
			PortableStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
		)
		output.size() shouldBe 0
	}

	@Test
	fun `canonical entry order and identity uniqueness are mandatory`() = runTest {
		val earlier = entry(seed = "earlier-entry", startTimeMs = 1_000L)
		val later = entry(seed = "later-entry", startTimeMs = 5_000L)
		val repeatedIdentity = entry(seed = "earlier-entry", startTimeMs = 7_000L)

		shouldThrow<PortableStepsJsonException> {
			PortableStepsJsonV1Codec().encode(ByteArrayOutputStream()) { sink ->
				sink.emit(later)
				sink.emit(earlier)
				ExportPortableStepsResult.Exported(2)
			}
		}
		shouldThrow<PortableStepsJsonException> {
			PortableStepsJsonV1Codec().encode(ByteArrayOutputStream()) { sink ->
				sink.emit(earlier)
				sink.emit(repeatedIdentity)
				ExportPortableStepsResult.Exported(2)
			}
		}

		val emitted = mutableListOf<PortableStepsEntryV1>()
		val outOfOrder = envelope(entryFragment(later), entryFragment(earlier))
		shouldThrow<PortableStepsJsonException> {
			PortableStepsJsonV1Codec().decode(
				ByteArrayInputStream(outOfOrder.encodeToByteArray()),
				PortableStepsEntrySink { entry -> emitted += entry },
			)
		}
		emitted shouldContainExactly listOf(later)
	}

	@Test
	fun `nested run fact and deletion identities are unique across the whole document`() = runTest {
		val first = entry(
			seed = "first",
			startTimeMs = 1_000L,
			runIdentitySeed = "shared-run",
			factIdentitySeed = "first-fact",
		)
		val repeatedRun = entry(
			seed = "second",
			startTimeMs = 5_000L,
			runIdentitySeed = "shared-run",
			factIdentitySeed = "second-fact",
		)
		val repeatedFactFirst = entry(
			seed = "fact-first",
			startTimeMs = 1_000L,
			runIdentitySeed = "fact-run-one",
			factIdentitySeed = "shared-fact",
		)
		val repeatedFactSecond = entry(
			seed = "fact-second",
			startTimeMs = 5_000L,
			runIdentitySeed = "fact-run-two",
			factIdentitySeed = "shared-fact",
		)
		val distinctLater = entry(seed = "scope-second", startTimeMs = 5_000L)
		val scopeCopiedRun = distinctLater.runs.single().copy(
			deletionScopeDigest = first.runs.single().deletionScopeDigest,
		)
		val repeatedScope = PortableStepsEntryV1.create(
			identity = distinctLater.identity,
			sessionMode = distinctLater.sessionMode,
			startTimeMs = distinctLater.startTimeMs,
			endTimeMs = distinctLater.endTimeMs,
			runs = listOf(scopeCopiedRun),
		)

		listOf(
			listOf(first, repeatedRun),
			listOf(repeatedFactFirst, repeatedFactSecond),
			listOf(first, repeatedScope),
		).forEach { invalidEntries ->
			shouldThrow<PortableStepsJsonException> {
				encode(invalidEntries)
			}
		}

		val emitted = mutableListOf<PortableStepsEntryV1>()
		val repeatedRunDocument = envelope(entryFragment(first), entryFragment(repeatedRun))
		shouldThrow<PortableStepsJsonException> {
			PortableStepsJsonV1Codec().decode(
				ByteArrayInputStream(repeatedRunDocument.encodeToByteArray()),
				PortableStepsEntrySink { value -> emitted += value },
			)
		}
		emitted shouldContainExactly listOf(first)
	}

	@Test
	fun `codec propagates cancellation and never closes caller streams`() = runTest {
		val output = CloseTrackingOutputStream()
		shouldThrow<CancellationException> {
			PortableStepsJsonV1Codec().encode(output) {
				throw CancellationException("cancel export")
			}
		}
		output.closed shouldBe false

		val input = CloseTrackingInputStream(encode(listOf(entry())))
		shouldThrow<CancellationException> {
			PortableStepsJsonV1Codec().decode(input) {
				throw CancellationException("cancel import")
			}
		}
		input.closed shouldBe false

		val successfulOutput = CloseTrackingOutputStream()
		PortableStepsJsonV1Codec().encode(successfulOutput) { sink ->
			sink.emit(entry())
			ExportPortableStepsResult.Exported(1)
		}
		successfulOutput.closed shouldBe false
		val successfulInput = CloseTrackingInputStream(successfulOutput.toByteArray())
		var decodedCount = 0
		PortableStepsJsonV1Codec().decode(successfulInput) { decodedCount++ }
		decodedCount shouldBe 1
		successfulInput.closed shouldBe false
	}

	private suspend fun encode(entries: List<PortableStepsEntryV1>): ByteArray {
		val output = ByteArrayOutputStream()
		PortableStepsJsonV1Codec().encode(output) { sink ->
			entries.forEach { entry -> sink.emit(entry) }
			ExportPortableStepsResult.Exported(entries.size)
		}
		return output.toByteArray()
	}

	private suspend fun entryFragment(entry: PortableStepsEntryV1): String {
		val document = encode(listOf(entry)).decodeToString()
		return document.removePrefix(ENVELOPE_PREFIX).removeSuffix("]}")
	}

	private fun envelope(vararg entries: String): String =
		ENVELOPE_PREFIX + entries.joinToString(separator = ",") + "]}"

	@Suppress("LongParameterList")
	private fun entry(
		seed: String = "entry",
		startTimeMs: Long = 1_000L,
		stepCount: Long? = 12L,
		factCoverage: PortableStepsFactCoverage = PortableStepsFactCoverage.COVERED,
		factCount: Int = 1,
		replacementRun: Boolean = false,
		runIdentitySeed: String = "$seed-run",
		factIdentitySeed: String = "$seed-fact",
	): PortableStepsEntryV1 {
		val firstRun = run(
			logicalTrackingId = seed,
			identitySeed = runIdentitySeed,
			factIdentitySeed = factIdentitySeed,
			startTimeMs = startTimeMs,
			endTimeMs = startTimeMs + 1_000L,
			stepCount = stepCount,
			factCoverage = factCoverage,
			factCount = factCount,
		)
		val runs = if (replacementRun) {
			listOf(
				firstRun,
				run(
					logicalTrackingId = seed,
					identitySeed = "$runIdentitySeed-replacement",
					factIdentitySeed = "$factIdentitySeed-replacement",
					startTimeMs = startTimeMs + 1_000L,
					endTimeMs = startTimeMs + 2_000L,
					stepCount = stepCount,
					factCoverage = factCoverage,
					factCount = factCount,
				),
			)
		} else {
			listOf(firstRun)
		}
		return PortableStepsEntryV1.create(
			identity = identity(PortableStepsIdentityKind.LOGICAL_ENTRY, seed),
			sessionMode = PortableStepsSessionMode.MANUAL,
			startTimeMs = runs.first().startTimeMs,
			endTimeMs = runs.last().endTimeMs,
			runs = runs,
		)
	}

	@Suppress("LongParameterList")
	private fun run(
		logicalTrackingId: String,
		identitySeed: String,
		factIdentitySeed: String,
		startTimeMs: Long,
		endTimeMs: Long,
		stepCount: Long?,
		factCoverage: PortableStepsFactCoverage,
		factCount: Int,
	): PortableStepsRunV1 {
		val facts = List(factCount) { index ->
			val factStart = startTimeMs + index * 100L
			PortableStepsFactV1.create(
				identity = identity(PortableStepsIdentityKind.FACT, "$factIdentitySeed-$index"),
				manifestRevision = 1L,
				intervalStartTimeMs = factStart,
				intervalEndTimeMs = factStart + if (
					factCoverage == PortableStepsFactCoverage.BASELINE
				) {
					0L
				} else {
					100L
				},
				wallTimeUncertaintyMs = 25L,
				coverage = factCoverage,
				stepCount = stepCount,
			)
		}
		return PortableStepsRunV1(
			identity = identity(PortableStepsIdentityKind.PHYSICAL_RUN, identitySeed),
			deletionScopeDigest = PortableStepsDeletionScopeDigest.derive(
				logicalTrackingId = logicalTrackingId,
				serviceRunId = identitySeed,
			),
			startTimeMs = startTimeMs,
			endTimeMs = endTimeMs,
			storedZoneId = "Europe/Prague",
			manifests = listOf(
				PortableStepsManifestV1(
					revision = 1L,
					effectiveWallTimeMs = startTimeMs,
					originSourcePolicyRevision = 7L,
					captureConsentEpoch = 3L,
				),
			),
			completeness = PortableStepsCompletenessV1(
				captureCoverage = PortableStepsCaptureCoverage.WHOLE_RUN,
				providerCoverage = PortableStepsProviderCoverage.COMPLETE,
				appDrainComplete = true,
				stopComplete = true,
				hasUnresolvedProviderRange = false,
			),
			facts = facts,
		)
	}

	private fun identity(kind: PortableStepsIdentityKind, seed: String) =
		PortableStepsOpaqueIdentity.derive(kind, seed)

	private class CloseTrackingOutputStream : ByteArrayOutputStream() {
		var closed = false
			private set

		override fun close() {
			closed = true
			super.close()
		}
	}

	private class CloseTrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
		var closed = false
			private set

		override fun close() {
			closed = true
			super.close()
		}
	}

	private companion object {
		const val ENVELOPE_PREFIX =
			"{\"format\":\"tracker-portable-steps\",\"schemaVersion\":1,\"entries\":["
	}
}
