package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.impexp.portable.PortableActivityJsonV1Codec
import com.adsamcik.tracker.impexp.portable.activityEntry
import com.adsamcik.tracker.impexp.portable.activityEnvelope
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.ExportSelectedPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.PortableActivityEnvelopeSink
import com.adsamcik.tracker.shared.base.database.PortableActivityExportUnverifiableReason
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
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
class PortableActivityExporterTest {
	@Test
	fun `range export writes exact Activity envelope without reading Location`() = runTest {
		val envelope = activityEnvelope(activityEntry())
		var request: ExportPortableCapturedActivityRequest? = null
		val exporter = PortableActivityExporter {
			fakeExporter { received, sink ->
				request = received
				sink.emit(envelope)
				ExportPortableCapturedActivityResult.Exported(1)
			}
		}
		val output = ByteArrayOutputStream()
		val locationData = Sequence<com.adsamcik.tracker.shared.model.LocationSample> {
			error("Portable Activity export must not inspect Location")
		}

		exporter.export(
			context = mockk<Context>(relaxed = true),
			locationData = locationData,
			outputStream = output,
			dateRange = 1_000L..1_999L,
		) shouldBe ExportResult.Success(recordCount = 1)

		request shouldBe ExportPortableCapturedActivityRequest(1_000L, 2_000L)
		PortableActivityJsonV1Codec().decode(
			ByteArrayInputStream(output.toByteArray()),
		) shouldBe envelope
		exporter.requiresLocationData shouldBe false
		exporter.containsSensitiveLocationData shouldBe false
		exporter.extension shouldBe ActivityCapturedPortableFormatV1.FILE_EXTENSION
		exporter.mimeType shouldBe ActivityCapturedPortableFormatV1.MIME_TYPE
	}

	@Test
	fun `typed refusal writes no fake successful file`() = runTest {
		val output = ByteArrayOutputStream()
		val exporter = PortableActivityExporter {
			fakeExporter { _, _ -> ExportPortableCapturedActivityResult.NoEntries }
		}

		(exporter.export(mockk(relaxed = true), emptySequence(), output, null) is ExportResult.Error) shouldBe
			true
		output.size() shouldBe 0
	}

	@Test
	fun `exact Activity selection forwards native and imported IDs and preserves original gaps and bounds`() = runTest {
		val native = activityEntry("native")
		val imported = activityEntry("imported", 3_000L)
		val envelope = activityEnvelope(native, imported)
		val request = selectedRequest(listOf(imported.identity))
		val received = mutableListOf<ExportSelectedPortableCapturedActivityRequest>()
		val before = ownedSpools()
		val output = ByteArrayOutputStream()
		val exporter = exactExporter { current, sink ->
			received += current
			sink.emit(envelope)
			ExportPortableCapturedActivityResult.Exported(2)
		}

		exporter.exportSession(ApplicationProvider.getApplicationContext(), request, output) shouldBe ExportResult.Success(2)
		received shouldContainExactly listOf(request, request, request)
		PortableActivityJsonV1Codec().decode(ByteArrayInputStream(output.toByteArray())) shouldBe envelope
		ownedSpools() shouldBe before
	}

	@Test
	fun `Activity caller list edits cannot widen exact membership between rereads`() = runTest {
		val native = mutableListOf(41L)
		val imported = mutableListOf(activityEntry("imported").identity)
		val request = ExportSelectedPortableCapturedActivityRequest(native, imported, 7L)
		val expected = request.copy(nativeSegmentIds = native.toList(), importedEntryIdentities = imported.toList())
		val received = mutableListOf<ExportSelectedPortableCapturedActivityRequest>()
		val output = MutationOutputStream { native += 42L; imported.clear() }
		val exporter = exactExporter { current, sink ->
			received += current
			sink.emit(activityEnvelope(activityEntry()))
			ExportPortableCapturedActivityResult.Exported(1)
		}

		exporter.exportSession(ApplicationProvider.getApplicationContext(), request, output) shouldBe ExportResult.Success(1)
		received shouldContainExactly listOf(expected, expected, expected)
	}

	@Test
	fun `Activity timestamp correction before copying refuses bytes and removes its spool`() = runTest {
		var calls = 0
		val before = ownedSpools()
		val output = ByteArrayOutputStream()
		val exporter = exactExporter { _, sink ->
			calls++
			sink.emit(activityEnvelope(activityEntry(startTimeMs = if (calls == 1) {
				1_000L
			} else {
				2_000L
			})))
			ExportPortableCapturedActivityResult.Exported(1)
		}

		val result = exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
		(result is ExportResult.Error) shouldBe true
		calls shouldBe 2
		output.size() shouldBe 0
		ownedSpools() shouldBe before
	}

	@Test
	fun `Activity change during destination copying cannot report a successful artifact`() = runTest {
		var start = 1_000L
		var calls = 0
		val before = ownedSpools()
		val output = MutationOutputStream { start = 2_000L }
		val exporter = exactExporter { _, sink ->
			calls++
			sink.emit(activityEnvelope(activityEntry(startTimeMs = start)))
			ExportPortableCapturedActivityResult.Exported(1)
		}

		val result = exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
		(result is ExportResult.Error) shouldBe true
		calls shouldBe 3
		// The enclosing transfer discards the failed destination; copied bytes still describe the original data.
		PortableActivityJsonV1Codec().decode(ByteArrayInputStream(output.bytes.toByteArray())) shouldBe
			activityEnvelope(activityEntry())
		ownedSpools() shouldBe before
	}

	@Test
	fun `unrelated source writes do not invalidate exact Activity bytes`() = runTest {
		var unrelatedRevision = 20L
		val observed = mutableListOf<Long>()
		val output = MutationOutputStream { unrelatedRevision++ }
		val envelope = activityEnvelope(activityEntry())
		val exporter = exactExporter { _, sink ->
			observed += unrelatedRevision
			sink.emit(envelope)
			ExportPortableCapturedActivityResult.Exported(1)
		}

		exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output) shouldBe ExportResult.Success(1)
		observed shouldContainExactly listOf(20L, 20L, 21L)
		PortableActivityJsonV1Codec().decode(ByteArrayInputStream(output.bytes.toByteArray())) shouldBe envelope
	}

	@Test
	fun `stale Activity epoch remains fixed and never adopts current data`() = runTest {
		val request = selectedRequest()
		val output = ByteArrayOutputStream()
		var calls = 0
		val exporter = exactExporter { current, _ ->
			current shouldBe request
			calls++
			ExportPortableCapturedActivityResult.Unverifiable(PortableActivityExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}

		(exporter.exportSession(ApplicationProvider.getApplicationContext(), request, output) is ExportResult.Error) shouldBe true
		calls shouldBe 1
		output.size() shouldBe 0
	}

	@Test
	fun `Activity epoch change during copying fails the original selection`() = runTest {
		val request = selectedRequest()
		var epoch = request.expectedCollectedDataEpoch
		val output = MutationOutputStream { epoch++ }
		var calls = 0
		val exporter = exactExporter { current, sink ->
			calls++
			if (current.expectedCollectedDataEpoch != epoch) {
				ExportPortableCapturedActivityResult.Unverifiable(PortableActivityExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
			} else {
				sink.emit(activityEnvelope(activityEntry()))
				ExportPortableCapturedActivityResult.Exported(1)
			}
		}

		(exporter.exportSession(ApplicationProvider.getApplicationContext(), request, output) is ExportResult.Error) shouldBe true
		calls shouldBe 3
	}

	@Test
	fun `Activity empty unavailable and storage outcomes write no destination bytes`() = runTest {
		val outcomes = listOf(ExportPortableCapturedActivityResult.NoEntries,
			ExportPortableCapturedActivityResult.StorageUnavailable,
			ExportPortableCapturedActivityResult.Unverifiable(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE))
		outcomes.forEach { outcome ->
			val before = ownedSpools()
			val output = ByteArrayOutputStream()
			val result = exactExporter { _, _ -> outcome }
				.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
			(result is ExportResult.Error) shouldBe true
			output.size() shouldBe 0
			ownedSpools() shouldBe before
		}
	}

	@Test
	fun `Activity cancellation after source emission escapes and removes its private spool`() = runTest {
		val before = ownedSpools()
		val output = ByteArrayOutputStream()
		val exporter = exactExporter { _, sink ->
			sink.emit(activityEnvelope(activityEntry()))
			throw CancellationException("cancel exact Activity")
		}

		kotlin.test.assertFailsWith<CancellationException> {
			exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output)
		}
		output.size() shouldBe 0
		ownedSpools() shouldBe before
	}

	@Test
	fun `Activity destination cancellation drains the private file without a final reread`() = runTest {
		val before = ownedSpools()
		var calls = 0
		var returned = false
		var writes = 0
		val exporter = exactExporter { _, sink ->
			calls++
			sink.emit(activityEnvelope(activityEntry()))
			ExportPortableCapturedActivityResult.Exported(1)
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
	fun `Activity refusal after emission cannot publish its staged envelope`() = runTest {
		val before = ownedSpools()
		val output = ByteArrayOutputStream()
		val exporter = exactExporter { _, sink ->
			sink.emit(activityEnvelope(activityEntry()))
			ExportPortableCapturedActivityResult.StorageUnavailable
		}

		(exporter.exportSession(ApplicationProvider.getApplicationContext(), selectedRequest(), output) is ExportResult.Error) shouldBe true
		output.size() shouldBe 0
		ownedSpools() shouldBe before
	}

	@Test
	fun `Activity destination IO failure propagates only after its spool is removed`() = runTest {
		val before = ownedSpools()
		val exporter = exactExporter { _, sink ->
			sink.emit(activityEnvelope(activityEntry()))
			ExportPortableCapturedActivityResult.Exported(1)
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

	private fun selectedRequest(imported: List<PortableActivityOpaqueIdentity> = emptyList()) =
		ExportSelectedPortableCapturedActivityRequest(listOf(41L), imported, 7L)

	private fun exactExporter(
		block: suspend (ExportSelectedPortableCapturedActivityRequest, PortableActivityEnvelopeSink) -> ExportPortableCapturedActivityResult,
	): PortableActivityExporter = PortableActivityExporter {
		object : ExportPortableCapturedActivity {
			override suspend fun export(request: ExportPortableCapturedActivityRequest, sink: PortableActivityEnvelopeSink):
				ExportPortableCapturedActivityResult = error("Exact Activity export must not widen to dates")
			override suspend fun export(request: ExportSelectedPortableCapturedActivityRequest, sink: PortableActivityEnvelopeSink):
				ExportPortableCapturedActivityResult = block(request, sink)
		}
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
			ExportPortableCapturedActivityRequest,
			PortableActivityEnvelopeSink,
		) -> ExportPortableCapturedActivityResult,
	): ExportPortableCapturedActivity = object : ExportPortableCapturedActivity {
		override suspend fun export(
			request: ExportPortableCapturedActivityRequest,
			sink: PortableActivityEnvelopeSink,
		): ExportPortableCapturedActivityResult = block(request, sink)

		override suspend fun export(
			request: ExportSelectedPortableCapturedActivityRequest,
			sink: PortableActivityEnvelopeSink,
		): ExportPortableCapturedActivityResult = error("Standalone export must not invoke exact session selection")
	}
}
