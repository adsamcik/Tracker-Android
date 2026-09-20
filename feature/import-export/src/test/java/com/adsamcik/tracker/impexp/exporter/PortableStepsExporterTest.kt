package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonException
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV1Codec
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV2Codec
import com.adsamcik.tracker.stats.api.repository.ExportPortableSteps
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsV2
import com.adsamcik.tracker.stats.api.repository.PortableStepsCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsCompletenessV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableStepsArchiveV2
import com.adsamcik.tracker.stats.api.repository.PortableStepsArchiveV2Sink
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntrySink
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableStepsManifestV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableStepsProviderCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsRunV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsSessionMode
import com.adsamcik.tracker.stats.api.repository.PortableStepsTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.withExplicitUnprovenCountDomain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortableStepsExporterTest {
	@Test
	fun `range export writes canonical portable entry without reading Location`() = runTest {
		val entry = entry().withExplicitUnprovenCountDomain()
		var receivedRequest: ExportPortableStepsRequest? = null
		val exporter = PortableStepsExporter {
			fakeExporter { request, sink ->
				receivedRequest = request
				sink.emit(PortableStepsArchiveV2.create(listOf(entry)))
				ExportPortableStepsResult.Exported(1)
			}
		}
		val output = ByteArrayOutputStream()
		val locationData = Sequence<com.adsamcik.tracker.shared.model.LocationSample> {
			error("Portable Steps export must not inspect Location")
		}

		val result = exporter.export(
			context = mockk<Context>(relaxed = true),
			locationData = locationData,
			outputStream = output,
			dateRange = 1_000L..1_999L,
		)

		result shouldBe ExportResult.Success(recordCount = 1)
		receivedRequest shouldBe ExportPortableStepsRequest(1_000L, 2_000L)
		val decoded = PortableStepsJsonV2Codec()
			.decode(ByteArrayInputStream(output.toByteArray())).archive
		decoded.entries shouldContainExactly listOf(entry)
	}

	@Test
	fun `whole-history export uses bounded full request and privacy-minimized metadata`() = runTest {
		var receivedRequest: ExportPortableStepsRequest? = null
		val exporter = PortableStepsExporter {
			fakeExporter { request, _ ->
				receivedRequest = request
				ExportPortableStepsResult.NoEntries
			}
		}
		val output = ByteArrayOutputStream()

		val result = exporter.export(mockk(relaxed = true), emptySequence(), output, null)

		receivedRequest shouldBe PortableStepsExporter.FULL_HISTORY
		result shouldBe ExportResult.Error(
			com.adsamcik.tracker.shared.base.misc.LocalizedString(R.string.export_error_no_portable_steps),
		)
		output.size() shouldBe 0
		exporter.requiresLocationData shouldBe false
		exporter.containsSensitiveLocationData shouldBe false
		exporter.canSelectDateRange shouldBe true
		exporter.extension shouldBe StepsPortableFormatV1.FILE_EXTENSION
		exporter.mimeType shouldBe StepsPortableFormatV1.MIME_TYPE
	}

	@Test
	fun `typed source refusal remains distinct from retryable export failure`() = runTest {
		val unavailableOutput = ByteArrayOutputStream()
		val unavailable = PortableStepsExporter {
			fakeExporter { _, _ ->
				ExportPortableStepsResult.Unverifiable(
					PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
				)
			}
		}.export(mockk(relaxed = true), emptySequence(), unavailableOutput, null)
		val retryableOutput = ByteArrayOutputStream()
		val retryable = PortableStepsExporter {
			fakeExporter { _, _ ->
				ExportPortableStepsResult.RetryableFailure(
					PortableStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
				)
			}
		}.export(mockk(relaxed = true), emptySequence(), retryableOutput, null)

		(unavailable as ExportResult.Error).message?.stringRes shouldBe
			R.string.export_error_portable_steps_unverifiable
		(retryable as ExportResult.Error).message?.stringRes shouldBe
			R.string.export_error_portable_steps_retryable
		unavailableOutput.size() shouldBe 0
		retryableOutput.size() shouldBe 0
	}

	@Test
	fun `missing v2 graph falls back to canonical v1 before writing destination bytes`() = runTest {
		val legacyEntry = entry()
		var v2Calls = 0
		var v1Calls = 0
		val exporter = PortableStepsExporter(
			legacyExporterProvider = {
				fakeLegacyExporter { _, sink ->
					v1Calls++
					sink.emit(legacyEntry)
					ExportPortableStepsResult.Exported(1)
				}
			},
			exporterProvider = {
				fakeExporter { _, _ ->
					v2Calls++
					ExportPortableStepsResult.Unverifiable(
						PortableStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE,
					)
				}
			},
		)
		val output = ByteArrayOutputStream()

		exporter.export(
			ApplicationProvider.getApplicationContext(),
			emptySequence(),
			output,
			null,
		) shouldBe ExportResult.Success(recordCount = 1)

		v2Calls shouldBe 1
		v1Calls shouldBe 1
		val decoded = mutableListOf<PortableStepsEntryV1>()
		PortableStepsJsonV1Codec().decode(ByteArrayInputStream(output.toByteArray())) {
			decoded += it
		}
		decoded shouldContainExactly listOf(legacyEntry)
	}

	@Test
	fun `large v2 export streams bounded chunks without a whole-file destination write`() = runTest {
		val entry = largeEntry(256).withExplicitUnprovenCountDomain()
		val output = MaximumWriteOutputStream(MAX_STREAM_WRITE_SIZE)
		val exporter = PortableStepsExporter {
			fakeExporter { _, sink ->
				sink.emit(PortableStepsArchiveV2.create(listOf(entry)))
				ExportPortableStepsResult.Exported(1)
			}
		}

		exporter.export(
			mockk(relaxed = true),
			emptySequence(),
			output,
			null,
		) shouldBe ExportResult.Success(1)

		(output.writtenBytes > MAX_STREAM_WRITE_SIZE) shouldBe true
	}

	@Test
	fun `failed v1 fallback leaves destination empty`() = runTest {
		val legacyEntry = entry()
		val output = ByteArrayOutputStream()
		val exporter = PortableStepsExporter(
			legacyExporterProvider = {
				fakeLegacyExporter { _, sink ->
					sink.emit(legacyEntry)
					ExportPortableStepsResult.RetryableFailure(
						PortableStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
					)
				}
			},
			exporterProvider = {
				fakeExporter { _, _ ->
					ExportPortableStepsResult.Unverifiable(
						PortableStepsExportUnverifiableReason.COUNT_DOMAIN_GRAPH_UNAVAILABLE,
					)
				}
			},
		)

		kotlin.test.assertFailsWith<PortableStepsJsonException> {
			exporter.export(
				ApplicationProvider.getApplicationContext(),
				emptySequence(),
				output,
				null,
			)
		}
		output.size() shouldBe 0
	}

	private fun fakeExporter(
		block: suspend (ExportPortableStepsRequest, PortableStepsArchiveV2Sink) -> ExportPortableStepsResult,
	): ExportPortableStepsV2 = object : ExportPortableStepsV2 {
		override suspend fun export(
			request: ExportPortableStepsRequest,
			sink: PortableStepsArchiveV2Sink,
		): ExportPortableStepsResult = block(request, sink)
	}

	private fun fakeLegacyExporter(
		block: suspend (
			ExportPortableStepsRequest,
			PortableStepsEntrySink,
		) -> ExportPortableStepsResult,
	): ExportPortableSteps = object : ExportPortableSteps {
		override suspend fun export(
			request: ExportPortableStepsRequest,
			sink: PortableStepsEntrySink,
		): ExportPortableStepsResult = block(request, sink)
	}

	private fun entry(): PortableStepsEntryV1 {
		val run = PortableStepsRunV1(
			identity = identity(PortableStepsIdentityKind.PHYSICAL_RUN, "run"),
			deletionScopeDigest = PortableStepsDeletionScopeDigest.derive("logical", "run"),
			startTimeMs = 1_100L,
			endTimeMs = 1_900L,
			storedZoneId = "Europe/Prague",
			manifests = listOf(
				PortableStepsManifestV1(
					revision = 1L,
					effectiveWallTimeMs = 1_100L,
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
			facts = listOf(
				PortableStepsFactV1.create(
					identity = identity(PortableStepsIdentityKind.FACT, "fact"),
					manifestRevision = 1L,
					intervalStartTimeMs = 1_100L,
					intervalEndTimeMs = 1_900L,
					wallTimeUncertaintyMs = 0L,
					coverage = PortableStepsFactCoverage.COVERED,
					stepCount = 12L,
				),
			),
		)
		return PortableStepsEntryV1.create(
			identity = identity(PortableStepsIdentityKind.LOGICAL_ENTRY, "logical"),
			sessionMode = PortableStepsSessionMode.MANUAL,
			startTimeMs = run.startTimeMs,
			endTimeMs = run.endTimeMs,
			runs = listOf(run),
		)
	}

	private fun largeEntry(factCount: Int): PortableStepsEntryV1 {
		val start = 10_000L
		val run = PortableStepsRunV1(
			identity = identity(PortableStepsIdentityKind.PHYSICAL_RUN, "large-run"),
			deletionScopeDigest = PortableStepsDeletionScopeDigest.derive("large", "run"),
			startTimeMs = start,
			endTimeMs = start + factCount,
			storedZoneId = "Europe/Prague",
			manifests = listOf(PortableStepsManifestV1(1L, start, 7L, 3L)),
			completeness = PortableStepsCompletenessV1(
				PortableStepsCaptureCoverage.WHOLE_RUN,
				PortableStepsProviderCoverage.COMPLETE,
				appDrainComplete = true,
				stopComplete = true,
				hasUnresolvedProviderRange = false,
			),
			facts = (0 until factCount).map { index ->
				PortableStepsFactV1.create(
					identity(PortableStepsIdentityKind.FACT, "large-fact-$index"),
					1L,
					start + index,
					start + index + 1L,
					0L,
					PortableStepsFactCoverage.COVERED,
					index.toLong(),
				)
			},
		)
		return PortableStepsEntryV1.create(
			identity(PortableStepsIdentityKind.LOGICAL_ENTRY, "large-entry"),
			PortableStepsSessionMode.MANUAL,
			run.startTimeMs,
			run.endTimeMs,
			listOf(run),
		)
	}

	private fun identity(kind: PortableStepsIdentityKind, seed: String) =
		PortableStepsOpaqueIdentity.derive(kind, seed)

	private class MaximumWriteOutputStream(
		private val maximumWriteSize: Int,
	) : OutputStream() {
		var writtenBytes = 0L
			private set

		override fun write(value: Int) {
			writtenBytes++
		}

		override fun write(buffer: ByteArray, offset: Int, length: Int) {
			require(length <= maximumWriteSize) {
				"Exporter attempted a whole-file write of $length bytes"
			}
			writtenBytes += length
		}
	}

	private companion object {
		const val MAX_STREAM_WRITE_SIZE = 16 * 1_024
	}
}
