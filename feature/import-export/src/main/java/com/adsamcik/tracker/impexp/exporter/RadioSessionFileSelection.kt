package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.room.withTransaction
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.RadioSourceFileFormatException
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.OrganizationSessionRow
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelectionCodec
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class RadioSessionFileSelection(val dates: SourceExportDates?,
	val captured: List<OrganizationSourceSelector.CapturedEntry>, val backgroundArchives: Set<String>,
	val retrospective: Boolean)

/** Reuses the existing spool and exact organization snapshot; destination I/O holds no Room transaction. */
internal suspend fun exportRadioSessionFile(context: Context, database: AppDatabase,
	request: SessionLocationFileRequest, source: HistorySource, output: OutputStream, maximumBytes: Long,
	verifyAccess: () -> Unit, encode: suspend (RadioSessionFileSelection, OutputStream) -> Int): ExportResult = try {
	val reader = SessionLocationFileReader(database)
	val snapshot = database.withTransaction { reader.snapshot(request).copy(retentionStamp = "") }
	val selection = radioFileSelection(snapshot.organization, source)
	val hash = MessageDigest.getInstance("SHA-256")
	FileBackedExportSpool(context, "session-${source.name.lowercase()}-", maximumBytes).use { spool ->
		val staged = spool.stage { destination -> database.withTransaction {
			verifyAccess(); currentCoroutineContext().ensureActive()
			encode(selection, DigestOutputStream(destination, hash))
		} }
		if (staged !is FileBackedExportStage.Complete) {
			throw RadioSourceFileFormatException("Radio file exceeds the size limit")
		}
		val expected = hash.digest()
		suspend fun verify() {
			verifyAccess(); currentCoroutineContext().ensureActive()
			val actual = MessageDigest.getInstance("SHA-256")
			val discard = object : OutputStream() { override fun write(value: Int) = Unit
				override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit }
			database.withTransaction {
				if (reader.snapshot(request).copy(retentionStamp = "") != snapshot) {
					throw RadioSourceFileFormatException("The selected session changed")
				}
				verifyEncodedRadioSelection(selection, encode, actual, expected, staged.value, discard)
			}
		}
		verify(); spool.copyTo(output); verify()
		ExportResult.Success(staged.value)
	}
} catch (cancelled: CancellationException) { throw cancelled
} catch (_: RadioSourceNoDataException) { ExportResult.Error(LocalizedString(R.string.export_radio_no_data))
} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
	// Report this source-file failure after cancellation and no-data were handled above.
	ExportResult.Error(LocalizedString(R.string.export_error_with_reason,
		failure.message ?: "Radio export is unavailable"))
}

private suspend fun verifyEncodedRadioSelection(
	selection: RadioSessionFileSelection,
	encode: suspend (RadioSessionFileSelection, OutputStream) -> Int,
	actual: MessageDigest, expected: ByteArray, expectedCount: Int, discard: OutputStream,
) {
	if (encode(selection, DigestOutputStream(discard, actual)) != expectedCount ||
                 !expected.contentEquals(actual.digest())) {
		throw RadioSourceFileFormatException("The selected radio data changed")
	}
}

private fun radioFileSelection(row: OrganizationSessionRow, source: HistorySource): RadioSessionFileSelection {
	val imported = row.importedSourceSelection?.let(OrganizationSourceSelectionCodec::decode)
	val dates = if (row.fromMs != null && row.toMs != null && row.toMs!! >= row.fromMs!! &&
        row.fromMs!! < Long.MAX_VALUE) {
		SourceExportDates(row.fromMs!!, if (row.toMs == row.fromMs) {
			row.toMs!! + 1L
		} else {
			row.toMs!!
		})
	} else {
		null
	}
	val selection = RadioSessionFileSelection(dates,
		imported.orEmpty().filterIsInstance<OrganizationSourceSelector.CapturedEntry>().filter { it.source == source },
		imported.orEmpty().filterIsInstance<OrganizationSourceSelector.BackgroundRadioArchive>()
			.filter { it.source == source }.mapTo(linkedSetOf()) { it.archiveIdentity },
		imported == null)
	if (selection.retrospective && selection.dates == null) {
		throw RadioSourceFileFormatException("Session dates are unavailable")
	}
	return selection
}
