package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.PortablePressureJsonException
import com.adsamcik.tracker.impexp.portable.PortablePressureJsonLimits
import com.adsamcik.tracker.impexp.portable.PortablePressureJsonV1Codec
import com.adsamcik.tracker.impexp.portable.pressureEntry
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressure
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.ExportSelectedPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntrySink
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.PressurePortableFormatV1
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortablePressureExporterTest {
	@Test
	fun `range export reads source only and preserves the sensitive Pressure warning`() = runTest {
		val entry = pressureEntry()
		var request: ExportPortablePressureRequest? = null
		val exporter = PortablePressureExporter(nativeDatabaseProvider = { null },
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
		val noEntries = PortablePressureExporter(nativeDatabaseProvider = { null },
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
		val pruned = PortablePressureExporter(nativeDatabaseProvider = { null },
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

		val unverifiable = PortablePressureExporter(nativeDatabaseProvider = { null },
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
		val result = PortablePressureExporter(nativeDatabaseProvider = { null },
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

	@Test
	fun `Pressure codec preserves saved source ending before session cutoff without extending measurements`() = runTest {
		val original = pressureEntry()
		val savedRun = original.runs.single().copy(startTimeMs = 1_000L, endTimeMs = 2_000L)
		val sourceEntry = PortablePressureEntryV1.create(original.identity, savedRun.startTimeMs,
			savedRun.endTimeMs, listOf(savedRun))
		val output = ByteArrayOutputStream()
		val exporter = PortablePressureExporter(nativeDatabaseProvider = { null },exporterProvider = { fakeExporter { request, sink ->
			request shouldBe ExportPortablePressureRequest(1_000L, 3_000L)
			sink.emit(sourceEntry)
			ExportPortablePressureResult.Exported(1)
		} })
		exporter.export(mockk(relaxed = true), emptySequence(), output, 1_000L..2_999L) shouldBe ExportResult.Success(1)
		val decoded = mutableListOf<PortablePressureEntryV1>()
		PortablePressureJsonV1Codec().decode(ByteArrayInputStream(output.toByteArray()), PortablePressureEntrySink { decoded += it })
		decoded shouldContainExactly listOf(sourceEntry)
		decoded.single().endTimeMs shouldBe 2_000L
		decoded.single().runs.single().windows shouldBe original.runs.single().windows
	}

	@Test
	fun `exact Pressure export forwards native and imported IDs and preserves all original runs and bounds`() = runTest {
		val native = pressureEntry("native", runCount = 2)
		val imported = pressureEntry("imported", startTimeMs = 30_000L)
		val request = selectedRequest(listOf(imported.identity))
		val received = mutableListOf<ExportSelectedPortablePressureRequest>()
		val before = ownedSpools()
		val output = ByteArrayOutputStream()
		val exporter = exactExporter { current, sink ->
			received += current
			sink.emit(native)
			sink.emit(imported)
			ExportPortablePressureResult.Exported(2)
		}

		exporter.exportSession(ApplicationProvider.getApplicationContext(), request, output) shouldBe ExportResult.Success(2)
		received shouldContainExactly listOf(request, request, request)
		decode(output.toByteArray()) shouldContainExactly listOf(native, imported)
		ownedSpools() shouldBe before
	}

	@Test
	fun `Pressure caller list mutation cannot widen exact membership across content rechecks`() = runTest {
		val native = mutableListOf(41L)
		val imported = mutableListOf(pressureEntry("imported").identity)
		val request = ExportSelectedPortablePressureRequest(native, imported, 7L)
		val expected = request.copy(nativeSegmentIds = native.toList(), importedEntryIdentities = imported.toList())
		val received = mutableListOf<ExportSelectedPortablePressureRequest>()
		val output = MutationOutputStream { native += 42L; imported.clear() }
		val exporter = exactExporter { current, sink ->
			received += current
			sink.emit(pressureEntry())
			ExportPortablePressureResult.Exported(1)
		}

		exporter.exportSession(ApplicationProvider.getApplicationContext(), request, output) shouldBe ExportResult.Success(1)
		received shouldContainExactly listOf(expected, expected, expected)
	}

	@Test
	fun `Pressure correction before copying refuses bytes and removes the private spool`() = runTest {
		var calls = 0
		val before = ownedSpools()
		val output = ByteArrayOutputStream()
		val exporter = exactExporter { _, sink ->
			calls++
			sink.emit(pressureEntry(meanHectopascals = if (calls == 1) {
				1_000.25
			} else {
				1_000.5
			}))
			ExportPortablePressureResult.Exported(1)
		}

		val result = exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
		(result as ExportResult.Error).message?.stringRes shouldBe R.string.export_error_portable_pressure_retryable
		calls shouldBe 2
		output.size() shouldBe 0
		ownedSpools() shouldBe before
	}

	@Test
	fun `Pressure change during destination copying cannot report successful bytes`() = runTest {
		var mean = 1_000.25
		var calls = 0
		val before = ownedSpools()
		val output = MutationOutputStream { mean = 1_000.5 }
		val exporter = exactExporter { _, sink ->
			calls++
			sink.emit(pressureEntry(meanHectopascals = mean))
			ExportPortablePressureResult.Exported(1)
		}

		val result = exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
		(result as ExportResult.Error).message?.stringRes shouldBe R.string.export_error_portable_pressure_retryable
		calls shouldBe 3
		// The enclosing transfer discards the failed destination; it contains only the original snapshot.
		decode(output.bytes.toByteArray()) shouldContainExactly listOf(pressureEntry())
		ownedSpools() shouldBe before
	}

	@Test
	fun `unrelated source writes do not invalidate selected Pressure data`() = runTest {
		var unrelatedRevision = 20L
		val observed = mutableListOf<Long>()
		val output = MutationOutputStream { unrelatedRevision++ }
		val entry = pressureEntry()
		val exporter = exactExporter { _, sink ->
			observed += unrelatedRevision
			sink.emit(entry)
			ExportPortablePressureResult.Exported(1)
		}

		exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output) shouldBe ExportResult.Success(1)
		observed shouldContainExactly listOf(20L, 20L, 21L)
		decode(output.bytes.toByteArray()) shouldContainExactly listOf(entry)
	}

	@Test
	fun `Pressure stale epoch remains the original request without an implicit retry`() = runTest {
		val request = selectedRequest()
		val output = ByteArrayOutputStream()
		var calls = 0
		val exporter = exactExporter { current, _ ->
			current shouldBe request
			calls++
			ExportPortablePressureResult.RetryableFailure(PortablePressureTransferRetryableReason.CONCURRENT_STATE_CHANGE)
		}

		val result = exporter.exportSession(ApplicationProvider.getApplicationContext(), request, output)
		(result as ExportResult.Error).message?.stringRes shouldBe R.string.export_error_portable_pressure_retryable
		calls shouldBe 1
		output.size() shouldBe 0
	}

	@Test
	fun `Pressure Clear during destination copy rejects the original epoch`() = runTest {
		val request = selectedRequest()
		var epoch = request.expectedCollectedDataEpoch
		val output = MutationOutputStream { epoch++ }
		var calls = 0
		val exporter = exactExporter { current, sink ->
			calls++
			if (current.expectedCollectedDataEpoch != epoch) {
				ExportPortablePressureResult.RetryableFailure(PortablePressureTransferRetryableReason.CONCURRENT_STATE_CHANGE)
			} else {
				sink.emit(pressureEntry())
				ExportPortablePressureResult.Exported(1)
			}
		}

		val result = exporter.exportSession(ApplicationProvider.getApplicationContext(), request, output)
		(result as ExportResult.Error).message?.stringRes shouldBe R.string.export_error_portable_pressure_retryable
		calls shouldBe 3
	}

	@Test
	fun `Pressure empty unavailable and storage outcomes preserve honest errors and no bytes`() = runTest {
		val outcomes = listOf(ExportPortablePressureResult.NoEntries,
			ExportPortablePressureResult.Unverifiable(PortablePressureExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE),
			ExportPortablePressureResult.RetryableFailure(PortablePressureTransferRetryableReason.STORAGE_UNAVAILABLE))
		val messages = listOf(R.string.export_error_no_portable_pressure,
			R.string.export_error_portable_pressure_data_pruned, R.string.export_error_portable_pressure_retryable)
		outcomes.zip(messages).forEach { (outcome, message) ->
			val before = ownedSpools()
			val output = ByteArrayOutputStream()
			val result = exactExporter { _, _ -> outcome }
				.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
			(result as ExportResult.Error).message?.stringRes shouldBe message
			output.size() shouldBe 0
			ownedSpools() shouldBe before
		}
	}

	@Test
	fun `Pressure source cancellation after emission escapes and removes the staged private file`() = runTest {
		val before = ownedSpools()
		val output = ByteArrayOutputStream()
		val exporter = exactExporter { _, sink ->
			sink.emit(pressureEntry())
			throw CancellationException("cancel exact Pressure")
		}

		kotlin.test.assertFailsWith<CancellationException> {
			exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
		}
		output.size() shouldBe 0
		ownedSpools() shouldBe before
	}

	@Test
	fun `Pressure destination cancellation physically drains its spool and stops the final reread`() = runTest {
		val before = ownedSpools()
		var calls = 0
		var writes = 0
		var returned = false
		val exporter = exactExporter { _, sink ->
			calls++
			sink.emit(pressureEntry(windowsPerRun = 16))
			ExportPortablePressureResult.Exported(1)
		}
		val job = launch(start = CoroutineStart.UNDISPATCHED) {
			val caller = currentCoroutineContext()
			val output = object : OutputStream() {
				override fun write(value: Int) = error("Expected buffered copy")
				override fun write(bytes: ByteArray, offset: Int, length: Int) { writes++; caller.cancel() }
			}
			exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
			returned = true
		}
		job.join()
		job.isCancelled shouldBe true
		writes shouldBe 1
		calls shouldBe 2
		returned shouldBe false
		ownedSpools() shouldBe before
	}

	@Test
	fun `Pressure refusal after source emission discards its staged prefix`() = runTest {
		val before = ownedSpools()
		val output = ByteArrayOutputStream()
		val exporter = exactExporter { _, sink ->
			sink.emit(pressureEntry())
			ExportPortablePressureResult.RetryableFailure(PortablePressureTransferRetryableReason.CONCURRENT_STATE_CHANGE)
		}

		kotlin.test.assertFailsWith<PortablePressureJsonException> {
			exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
		}
		output.size() shouldBe 0
		ownedSpools() shouldBe before
	}

	@Test
	fun `Pressure existing codec size ceiling fails before destination copying and removes its spool`() = runTest {
		val before = ownedSpools()
		val output = ByteArrayOutputStream()
		val exporter = exactExporter(codec = PortablePressureJsonV1Codec(PortablePressureJsonLimits(maxFileBytes = 32L))) { _, sink ->
			sink.emit(pressureEntry())
			ExportPortablePressureResult.Exported(1)
		}

		kotlin.test.assertFailsWith<PortablePressureJsonException> {
			exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
		}
		output.size() shouldBe 0
		ownedSpools() shouldBe before
	}

	@Test
	fun `Pressure destination IO failure propagates after owned private cleanup`() = runTest {
		val before = ownedSpools()
		val exporter = exactExporter { _, sink ->
			sink.emit(pressureEntry())
			ExportPortablePressureResult.Exported(1)
		}
		val output = object : OutputStream() {
			override fun write(value: Int): Unit = throw IOException("destination unavailable")
			override fun write(bytes: ByteArray, offset: Int, length: Int): Unit = throw IOException("destination unavailable")
		}

		kotlin.test.assertFailsWith<IOException> {
			exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
		}
		ownedSpools() shouldBe before
	}

	private fun selectedRequest(imported: List<PortablePressureOpaqueIdentity> = emptyList()) =
		ExportSelectedPortablePressureRequest(listOf(41L), imported, 7L)

	private fun exactExporter(
		codec: PortablePressureJsonV1Codec = PortablePressureJsonV1Codec(),
		block: suspend (ExportSelectedPortablePressureRequest, PortablePressureEntrySink) -> ExportPortablePressureResult,
	): PortablePressureExporter = PortablePressureExporter(nativeDatabaseProvider = { null },exporterProvider = {
		object : ExportPortablePressure {
			override suspend fun export(request: ExportPortablePressureRequest, sink: PortablePressureEntrySink):
				ExportPortablePressureResult = error("Exact Pressure export must not widen to dates")
			override suspend fun export(request: ExportSelectedPortablePressureRequest, sink: PortablePressureEntrySink):
				ExportPortablePressureResult = block(request, sink)
		}
	}, codec = codec)

	private suspend fun decode(bytes: ByteArray): List<PortablePressureEntryV1> {
		val entries = mutableListOf<PortablePressureEntryV1>()
		PortablePressureJsonV1Codec().decode(ByteArrayInputStream(bytes), PortablePressureEntrySink { entries += it })
		return entries
	}

	private fun ownedSpools(): Set<String> = File(
		ApplicationProvider.getApplicationContext<Context>().cacheDir, "portable-export-spool",
	).listFiles().orEmpty().filter { it.name.startsWith("tracker-portable-export-spool-v1-") }
		.map { it.absolutePath }.toSet()

	private class MutationOutputStream(private val onCopy: () -> Unit) : OutputStream() {
		val bytes = ByteArrayOutputStream()
		private var copied = false
		override fun write(value: Int) = error("Expected buffered copy")
		override fun write(buffer: ByteArray, offset: Int, length: Int) {
			bytes.write(buffer, offset, length)
			if (!copied) { copied = true; onCopy() }
		}
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

		override suspend fun export(
			request: ExportSelectedPortablePressureRequest,
			sink: PortablePressureEntrySink,
		): ExportPortablePressureResult = error("Standalone date export must not use exact session selection")
	}
}
