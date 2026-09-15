package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.PortablePressureJsonV1Codec
import com.adsamcik.tracker.impexp.portable.pressureEntry
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressure
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntrySink
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.PressurePortableFormatV1
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PortablePressureExporterTest {
	@Test
	fun `range export reads source only and preserves the sensitive Pressure warning`() = runTest {
		val entry = pressureEntry()
		var request: ExportPortablePressureRequest? = null
		val exporter = PortablePressureExporter(
			exporterProvider = {
				fakeExporter { received, sink ->
					request = received
					sink.emit(entry)
					ExportPortablePressureResult.Exported(1)
				}
			},
		)
		val locationData = Sequence<com.adsamcik.tracker.shared.model.LocationSample> {
			error("Portable Pressure export must not inspect Location rows")
		}
		val output = ByteArrayOutputStream()

		exporter.export(
			context = mockk(relaxed = true),
			locationData = locationData,
			outputStream = output,
			dateRange = 1_000L..1_999L,
		) shouldBe ExportResult.Success(recordCount = 1)

		request shouldBe ExportPortablePressureRequest(1_000L, 2_000L)
		exporter.requiresLocationData shouldBe false
		exporter.containsSensitiveLocationData shouldBe true
		exporter.canSelectDateRange shouldBe true
		exporter.extension shouldBe PressurePortableFormatV1.FILE_EXTENSION
		exporter.mimeType shouldBe PressurePortableFormatV1.MIME_TYPE
		val decoded = mutableListOf<PortablePressureEntryV1>()
		PortablePressureJsonV1Codec().decode(
			ByteArrayInputStream(output.toByteArray()),
			PortablePressureEntrySink { decoded += it },
		)
		decoded shouldContainExactly listOf(entry)
	}

	@Test
	fun `full history and pruned no entry outcomes are honest failures with no artifact`() = runTest {
		var request: ExportPortablePressureRequest? = null
		val noEntries = PortablePressureExporter(
			exporterProvider = {
				fakeExporter { received, _ ->
					request = received
					ExportPortablePressureResult.NoEntries
				}
			},
		)
		val emptyOutput = ByteArrayOutputStream()

		noEntries.export(mockk(relaxed = true), emptySequence(), emptyOutput, null) shouldBe
			ExportResult.Error(
				com.adsamcik.tracker.shared.base.misc.LocalizedString(
					R.string.export_error_no_portable_pressure,
				),
			)
		request shouldBe PortablePressureExporter.FULL_HISTORY
		emptyOutput.size() shouldBe 0

		val prunedOutput = ByteArrayOutputStream()
		val pruned = PortablePressureExporter(
			exporterProvider = {
				fakeExporter { _, _ ->
					ExportPortablePressureResult.Unverifiable(
						PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
					)
				}
			},
		).export(mockk(relaxed = true), emptySequence(), prunedOutput, null)
		(pruned as ExportResult.Error).message?.stringRes shouldBe
			R.string.export_error_portable_pressure_data_pruned
		prunedOutput.size() shouldBe 0

		val unverifiable = PortablePressureExporter(
			exporterProvider = {
				fakeExporter { _, _ ->
					ExportPortablePressureResult.Unverifiable(
						PortablePressureExportUnverifiableReason.ENTRY_MATERIALIZING,
					)
				}
			},
		).export(mockk(relaxed = true), emptySequence(), ByteArrayOutputStream(), null)
		(unverifiable as ExportResult.Error).message?.stringRes shouldBe
			R.string.export_error_portable_pressure_unverifiable
	}

	@Test
	fun `retryable source refusal remains a localized error without success fallback`() = runTest {
		val result = PortablePressureExporter(
			exporterProvider = {
				fakeExporter { _, _ ->
					ExportPortablePressureResult.RetryableFailure(
						PortablePressureTransferRetryableReason.STORAGE_UNAVAILABLE,
					)
				}
			},
		).export(mockk(relaxed = true), emptySequence(), ByteArrayOutputStream(), null)

		(result as ExportResult.Error).message?.stringRes shouldBe
			R.string.export_error_portable_pressure_retryable
		result.isSuccess shouldBe false
	}

	private fun fakeExporter(
		block: suspend (
			ExportPortablePressureRequest,
			PortablePressureEntrySink,
		) -> ExportPortablePressureResult,
	): ExportPortablePressure = object : ExportPortablePressure {
		override suspend fun export(
			request: ExportPortablePressureRequest,
			sink: PortablePressureEntrySink,
		): ExportPortablePressureResult = block(request, sink)
	}
}
