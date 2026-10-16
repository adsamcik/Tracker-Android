package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import android.database.sqlite.SQLiteException
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.FileImportReceiptContext
import com.adsamcik.tracker.impexp.portable.WifiSourceFileV2
import com.adsamcik.tracker.impexp.portable.CellSourceFileV2
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.portable.RadioSourceFileFormatException
import com.adsamcik.tracker.impexp.portable.RadioSourceFileJsonV2Codec
import com.adsamcik.tracker.impexp.portable.RadioSourceFileV2
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedCellRequest
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.PortableCellImportReceipt
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCell
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCellRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCellResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifi
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifiResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientCellImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiImportReceipt
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RadioSourceImportEntryPoint {
	fun importCapturedWifi(): ImportPortableCapturedWifi
	fun importBackgroundWifi(): ImportPortableAmbientWifi
	fun importCapturedCell(): ImportPortableCapturedCell
	fun importBackgroundCell(): ImportPortableAmbientCell
}

/** A chosen Wi-Fi file carries session and background Wi-Fi, never another source. */
internal class PortableWifiSourceFileImport(
	private val dependencies: (Context) -> RadioSourceImportEntryPoint = { context ->
		EntryPointAccessors.fromApplication(context.applicationContext,
			RadioSourceImportEntryPoint::class.java)
	},
	private val codec: RadioSourceFileJsonV2Codec = RadioSourceFileJsonV2Codec(),
) : FileImport {
	override val supportedExtensions = listOf(RadioSourceFileV2.WIFI_EXTENSION)
	override val transactionMode = ImportTransactionMode.IMPORTER_MANAGED

	override suspend fun import(context: Context, database: AppDatabase,
		stream: FileImportStream): ImportResult {
		val receipt = stream.importReceipt ?: throw IOException("Wi-Fi import needs a saved file job")
		val epoch = stream.requireOriginalDataEpoch()
		val file = try { codec.readWifi(stream) } catch (invalid: RadioSourceFileFormatException) {
			return ImportResult(failedCount = 1, errors = listOf(invalid.message ?: "Invalid Wi-Fi file"))
		}
		val source = dependencies(context)
		val attachment = ImportedRadioChildAttachment(database)
		var total = ImportResult.EMPTY
		file.sessions.forEach { session ->
			val product = session.entry
			val result = source.importCapturedWifi().importEntry(ImportPortableCapturedWifiRequest(
				product,
				PortableCapturedWifiImportReceipt(receipt.jobId,
					radioEntryKey(receipt.entryKey, product.identity.value),
					receipt.sourceName, receipt.receivedAtMs),
				epoch,
				mergeSelectedReadings = file.schemaVersion >= 3,
			))
			when (result) {
				is ImportPortableCapturedWifiResult.Applied -> total +=
					attachResult { attachment.wifiSession(session.children, result.importRevision, epoch) }
				is ImportPortableCapturedWifiResult.Duplicate -> total +=
					attachResult(true) { attachment.wifiSession(session.children, result.importRevision, epoch) }
				is ImportPortableCapturedWifiResult.Blocked -> total += ImportResult(skippedCount = 1,
					errors = listOf("Wi-Fi session skipped: ${result.reason.name.lowercase()}"))
				is ImportPortableCapturedWifiResult.Unverifiable -> total += ImportResult(failedCount = 1,
					errors = listOf("Wi-Fi session invalid: ${result.reason.name.lowercase()}"))
				is ImportPortableCapturedWifiResult.RetryableFailure ->
					throw IOException("Wi-Fi import temporarily unavailable: ${result.reason.name.lowercase()}")
			}
		}
		total += importBackground(file, source, receipt, epoch, attachment)
		return total
	}


	private suspend fun importBackground(
		file: WifiSourceFileV2,
		source: RadioSourceImportEntryPoint,
		receipt: FileImportReceiptContext,
		epoch: Long,
		attachment: ImportedRadioChildAttachment,
	): ImportResult {
		val archive = file.background ?: return ImportResult.EMPTY
		val result = source.importBackgroundWifi().importArchive(ImportPortableAmbientWifiRequest(
			archive,
			PortableAmbientWifiImportReceipt(receipt.jobId,
				radioEntryKey(receipt.entryKey, archive.archiveId),
				receipt.sourceName, receipt.receivedAtMs),
			epoch,
		))
		return when (result) {
			is ImportPortableAmbientWifiResult.Applied ->
				attachResult { attachment.wifiBackground(file.backgroundChildren, epoch) }
			ImportPortableAmbientWifiResult.Duplicate ->
				attachResult(true) { attachment.wifiBackground(file.backgroundChildren, epoch) }
			ImportPortableAmbientWifiResult.DeletedArchive,
			ImportPortableAmbientWifiResult.RetentionBoundary -> ImportResult(skippedCount = 1,
				errors = listOf("Wi-Fi background is no longer retained"))
			ImportPortableAmbientWifiResult.StorageUnavailable,
			ImportPortableAmbientWifiResult.RetentionAuthorityUnavailable ->
				throw IOException("Wi-Fi background import temporarily unavailable")
			else -> ImportResult(failedCount = 1,
				errors = listOf("Wi-Fi background import was rejected"))
		}
	}
}

/** A chosen Cell file carries session and background Cell, never another source. */
internal class PortableCellSourceFileImport(
	private val dependencies: (Context) -> RadioSourceImportEntryPoint = { context ->
		EntryPointAccessors.fromApplication(context.applicationContext,
			RadioSourceImportEntryPoint::class.java)
	},
	private val codec: RadioSourceFileJsonV2Codec = RadioSourceFileJsonV2Codec(),
) : FileImport {
	override val supportedExtensions = listOf(RadioSourceFileV2.CELL_EXTENSION)
	override val transactionMode = ImportTransactionMode.IMPORTER_MANAGED

	override suspend fun import(context: Context, database: AppDatabase,
		stream: FileImportStream): ImportResult {
		val receipt = stream.importReceipt ?: throw IOException("Cell import needs a saved file job")
		val epoch = stream.requireOriginalDataEpoch()
		val file = try { codec.readCell(stream) } catch (invalid: RadioSourceFileFormatException) {
			return ImportResult(failedCount = 1, errors = listOf(invalid.message ?: "Invalid Cell file"))
		}
		val source = dependencies(context)
		val attachment = ImportedRadioChildAttachment(database)
		var total = ImportResult.EMPTY
		file.sessions.forEach { session ->
			val product = session.entry
			val result = source.importCapturedCell().importEntry(ImportPortableCapturedCellRequest(
				product,
				PortableCellImportReceipt(receipt.jobId,
					radioEntryKey(receipt.entryKey, product.identity.value),
					receipt.sourceName, receipt.receivedAtMs),
				epoch,
				mergeSelectedReadings = file.schemaVersion >= 3,
			))
			when (result) {
				is ImportPortableCapturedCellResult.Applied -> total +=
					attachResult { attachment.cellSession(session.children, result.importRevision, epoch) }
				is ImportPortableCapturedCellResult.Duplicate -> total +=
					attachResult(true) { attachment.cellSession(session.children, result.importRevision, epoch) }
				is ImportPortableCapturedCellResult.Blocked -> total += ImportResult(skippedCount = 1,
					errors = listOf("Cell session skipped: ${result.reason.name.lowercase()}"))
				is ImportPortableCapturedCellResult.Unverifiable -> total += ImportResult(failedCount = 1,
					errors = listOf("Cell session invalid: ${result.reason.name.lowercase()}"))
				is ImportPortableCapturedCellResult.RetryableFailure ->
					throw IOException("Cell import temporarily unavailable: ${result.reason.name.lowercase()}")
			}
		}
		total += importBackground(file, source, receipt, epoch, attachment)
		return total
	}


	private suspend fun importBackground(
		file: CellSourceFileV2,
		source: RadioSourceImportEntryPoint,
		receipt: FileImportReceiptContext,
		epoch: Long,
		attachment: ImportedRadioChildAttachment,
	): ImportResult {
		val archive = file.background ?: return ImportResult.EMPTY
		val result = source.importBackgroundCell().importArchive(ImportPortableAmbientCellRequest(
			archive,
			PortableAmbientCellImportReceipt(receipt.jobId,
				radioEntryKey(receipt.entryKey, archive.archiveId),
				receipt.sourceName, receipt.receivedAtMs),
			epoch,
		))
		return when (result) {
			is ImportPortableAmbientCellResult.Applied ->
				attachResult { attachment.cellBackground(file.backgroundChildren, epoch) }
			ImportPortableAmbientCellResult.Duplicate ->
				attachResult(true) { attachment.cellBackground(file.backgroundChildren, epoch) }
			ImportPortableAmbientCellResult.DeletedArchive,
			ImportPortableAmbientCellResult.RetentionBoundary -> ImportResult(skippedCount = 1,
				errors = listOf("Cell background is no longer retained"))
			ImportPortableAmbientCellResult.StorageUnavailable,
			ImportPortableAmbientCellResult.RetentionAuthorityUnavailable ->
				throw IOException("Cell background import temporarily unavailable")
			else -> ImportResult(failedCount = 1,
				errors = listOf("Cell background import was rejected"))
		}
	}
}

private suspend fun attachResult(duplicate: Boolean = false, block: suspend () -> Unit): ImportResult =
	try {
		block()
		if (duplicate) {
			ImportResult(skippedCount = 1)
		} else {
			ImportResult(successCount = 1)
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: SQLiteException) {
		throw IOException("Radio detail storage is temporarily unavailable")
	} catch (_: IllegalStateException) {
		ImportResult(failedCount = 1, errors = listOf("Radio details conflict with saved data"))
	}

internal fun radioEntryKey(fileKey: String, sourceIdentity: String): String {
	val digest = MessageDigest.getInstance("SHA-256")
	listOf("tracker-radio-source-entry-v2", fileKey, sourceIdentity).forEach { part ->
		val bytes = part.toByteArray(Charsets.UTF_8)
		digest.update(java.nio.ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
		digest.update(bytes)
	}
	return digest.digest().joinToString("") { "%02x".format(it.toInt() and UNSIGNED_BYTE_MASK) }
}

private const val UNSIGNED_BYTE_MASK = 0xff
