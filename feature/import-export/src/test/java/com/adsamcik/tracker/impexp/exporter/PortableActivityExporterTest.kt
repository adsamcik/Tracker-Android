package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.impexp.portable.PortableActivityJsonV1Codec
import com.adsamcik.tracker.impexp.portable.activityEntry
import com.adsamcik.tracker.impexp.portable.activityEnvelope
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.PortableActivityEnvelopeSink
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
	}
}
