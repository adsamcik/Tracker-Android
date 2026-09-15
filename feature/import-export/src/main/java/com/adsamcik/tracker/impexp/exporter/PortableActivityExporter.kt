package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.PortableActivityJsonException
import com.adsamcik.tracker.impexp.portable.PortableActivityJsonV1Codec
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.OutputStream
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PortableActivityExportEntryPoint {
	fun exportPortableCapturedActivity(): ExportPortableCapturedActivity
}

/** User-selected portable captured-Activity file export; it acquires no source demand. */
internal class PortableActivityExporter(
	private val exporterProvider: (Context) -> ExportPortableCapturedActivity = { context ->
		EntryPointAccessors.fromApplication(
			context.applicationContext,
			PortableActivityExportEntryPoint::class.java,
		).exportPortableCapturedActivity()
	},
) : Exporter {
	override val requiresLocationData: Boolean = false
	override val containsSensitiveLocationData: Boolean = false
	override val canSelectDateRange: Boolean = true
	override val mimeType: String = ActivityCapturedPortableFormatV1.MIME_TYPE
	override val extension: String = ActivityCapturedPortableFormatV1.FILE_EXTENSION

	override suspend fun export(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
	): ExportResult {
		val request = dateRange?.toPortableRequest() ?: FULL_HISTORY
		val result = try {
			PortableActivityJsonV1Codec().encode(outputStream) { sink ->
				exporterProvider(context).export(request, sink)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: PortableActivityJsonException) {
			return ExportResult.Error(
				LocalizedString(R.string.export_error_with_reason, "Portable Activity file write failed"),
			)
		}
		return when (result) {
			is ExportPortableCapturedActivityResult.Exported ->
				ExportResult.Success(recordCount = result.entryCount)
			ExportPortableCapturedActivityResult.NoEntries -> ExportResult.Error(
				LocalizedString(R.string.export_error_with_reason, "No captured Activity entries"),
			)
			is ExportPortableCapturedActivityResult.Unverifiable -> ExportResult.Error(
				LocalizedString(
					R.string.export_error_with_reason,
					"Captured Activity cannot be verified: ${result.reason.name.lowercase()}",
				),
			)
			ExportPortableCapturedActivityResult.StorageUnavailable -> ExportResult.Error(
				LocalizedString(
					R.string.export_error_with_reason,
					"Captured Activity storage is unavailable",
				),
			)
		}
	}

	internal companion object {
		val FULL_HISTORY = ExportPortableCapturedActivityRequest(0L, Long.MAX_VALUE)
	}
}

private fun LongRange.toPortableRequest(): ExportPortableCapturedActivityRequest {
	require(first >= 0L && !isEmpty())
	val exclusiveEnd = if (last == Long.MAX_VALUE) Long.MAX_VALUE else Math.addExact(last, 1L)
	return ExportPortableCapturedActivityRequest(first, exclusiveEnd)
}
