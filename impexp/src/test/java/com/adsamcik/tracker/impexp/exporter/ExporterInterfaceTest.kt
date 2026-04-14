package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

@DisplayName("Exporter interface")
class ExporterInterfaceTest {

	private class StubExporter(
		override val canSelectDateRange: Boolean = true,
		override val mimeType: String = "application/test",
		override val extension: String = "tst",
	) : Exporter {
		var exportCalled = false
		var receivedDateRange: LongRange? = null
		var receivedLocationCount = 0

		override suspend fun export(
			context: Context,
			locationData: Sequence<LocationSample>,
			outputStream: OutputStream,
			dateRange: LongRange?,
		): ExportResult {
			exportCalled = true
			receivedDateRange = dateRange
			receivedLocationCount = locationData.count()
			return ExportResult.Success
		}
	}

	@Nested
	@DisplayName("Properties")
	inner class Properties {

		@Test
		fun `canSelectDateRange returns configured value`() {
			StubExporter(canSelectDateRange = true).canSelectDateRange shouldBe true
			StubExporter(canSelectDateRange = false).canSelectDateRange shouldBe false
		}

		@Test
		fun `mimeType returns configured value`() {
			StubExporter(mimeType = "text/xml").mimeType shouldBe "text/xml"
		}

		@Test
		fun `extension returns configured value`() {
			StubExporter(extension = "gpx").extension shouldBe "gpx"
		}
	}

	@Nested
	@DisplayName("Export contract")
	inner class ExportContract {

		@Test
		fun `export receives context and sequence`() = runTest {
			val exporter = StubExporter()
			val context = mockk<Context>()
			val output = ByteArrayOutputStream()

			exporter.export(context, emptySequence(), output)

			exporter.exportCalled shouldBe true
			exporter.receivedLocationCount shouldBe 0
		}

		@Test
		fun `export receives dateRange when provided`() = runTest {
			val exporter = StubExporter()
			val context = mockk<Context>()
			val output = ByteArrayOutputStream()
			val range = 1000L..2000L

			exporter.export(context, emptySequence(), output, range)

			exporter.receivedDateRange shouldBe range
		}

		@Test
		fun `export dateRange defaults to null`() = runTest {
			val exporter = StubExporter()
			val context = mockk<Context>()
			val output = ByteArrayOutputStream()

			exporter.export(context, emptySequence(), output)

			exporter.receivedDateRange shouldBe null
		}
	}
}
