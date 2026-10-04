package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.annotation.Keep
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.PortableActivityFormatException
import com.adsamcik.tracker.impexp.portable.PortableActivityJsonV1Codec
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.ExportSelectedPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.PortableActivityExportUnverifiableReason
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PortableActivityExportEntryPoint {
	fun exportPortableCapturedActivity(): ExportPortableCapturedActivity
}

/** User-selected portable captured-Activity file export; it acquires no source demand. */
@Keep // Export activity reconstructs the selected class through its public no-argument constructor.
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
		return writeResult {
			PortableActivityJsonV1Codec().encode(outputStream) { sink ->
				exporterProvider(context).export(request, sink)
			}
		}
	}

	/**
	 * Exact saved records for the shared bundle. The caller owns the I/O dispatcher, retained-data
	 * transfer, destination closure/discard on failure and final handoff; no tracking is started.
	 */
	internal suspend fun exportSession(
		context: Context,
		request: ExportSelectedPortableCapturedActivityRequest,
		outputStream: OutputStream,
	): ExportResult {
		currentCoroutineContext().ensureActive()
		val selection = request.copy(nativeSegmentIds = request.nativeSegmentIds.toList(),
			importedEntryIdentities = request.importedEntryIdentities.toList())
		val encode: suspend (OutputStream) -> ExportPortableCapturedActivityResult = { output ->
			PortableActivityJsonV1Codec().encode(output) { sink ->
				exporterProvider(context).export(selection, sink)
			}
		}
		return writeResult {
			FileBackedExportSpool(context, "portable-activity-v1-",
				ActivityCapturedPortableFormatV1.MAX_FILE_BYTES).use { spool ->
				val digest = MessageDigest.getInstance("SHA-256")
				val result = spool.write { encode(DigestOutputStream(it, digest)) }
				if (result !is ExportPortableCapturedActivityResult.Exported) return@use result
				val expectedHash = digest.digest()
				verifyContents(result, expectedHash, encode)?.let { return@use it }
				spool.copyTo(outputStream)
				verifyContents(result, expectedHash, encode) ?: result
			}
		}
	}

	/** Uses the same codec and exact selection; unrelated source revisions are not inputs. */
	private suspend fun verifyContents(
		expectedResult: ExportPortableCapturedActivityResult.Exported,
		expectedHash: ByteArray,
		encode: suspend (OutputStream) -> ExportPortableCapturedActivityResult,
	): ExportPortableCapturedActivityResult? {
		currentCoroutineContext().ensureActive()
		val digest = MessageDigest.getInstance("SHA-256")
		val discarded = object : OutputStream() {
			override fun write(value: Int) = Unit
			override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
		}
		val current = encode(DigestOutputStream(discarded, digest))
		currentCoroutineContext().ensureActive()
		return when {
			current == ExportPortableCapturedActivityResult.StorageUnavailable -> current
			current == expectedResult && digest.digest().contentEquals(expectedHash) -> null
			else -> ExportPortableCapturedActivityResult.Unverifiable(
				PortableActivityExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
	}

	private suspend fun writeResult(
		write: suspend () -> ExportPortableCapturedActivityResult,
	): ExportResult {
		val result = try {
			write()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: PortableActivityFormatException) {
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
