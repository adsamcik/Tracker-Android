package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonException
import com.adsamcik.tracker.impexp.portable.PortableStepsJsonV1Codec
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.repository.ImportPortableSteps
import com.adsamcik.tracker.stats.api.repository.ImportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableStepsTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV1
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException

/** Resolves the source-local authoritative importer from the application graph. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PortableStepsImportEntryPoint {
	fun importPortableSteps(): ImportPortableSteps
}

/** Strict `.trackersteps` adapter; the codec and source-local command retain their own authority. */
internal class PortableStepsFileImport(
	private val importerProvider: (Context) -> ImportPortableSteps = { context ->
		EntryPointAccessors.fromApplication(
			context.applicationContext,
			PortableStepsImportEntryPoint::class.java,
		).importPortableSteps()
	},
) : FileImport {
	override val supportedExtensions: Collection<String> = listOf(EXTENSION)

	/** Day locks must be acquired before the importer's per-entry Room transaction. */
	override val transactionMode: ImportTransactionMode = ImportTransactionMode.IMPORTER_MANAGED

	override suspend fun import(
		context: Context,
		database: AppDatabase,
		stream: FileImportStream,
	): ImportResult {
		val importer = importerProvider(context)
		var aggregate = ImportResult.EMPTY
		try {
			PortableStepsJsonV1Codec().decode(stream) { entry ->
				aggregate += importer.importEntry(entry).toFileResult()
			}
		} catch (_: PortableStepsJsonException) {
			aggregate += ImportResult(
				failedCount = 1,
				errors = listOf(PERMANENT_FORMAT_ERROR),
			)
		}
		return aggregate
	}

	private fun ImportPortableStepsResult.toFileResult(): ImportResult = when (this) {
		is ImportPortableStepsResult.Applied -> ImportResult(successCount = 1)
		ImportPortableStepsResult.Duplicate -> ImportResult(skippedCount = 1)
		ImportPortableStepsResult.DeletedScope -> ImportResult(
			skippedCount = 1,
			errors = listOf("Portable Steps entry is protected by a deletion fence."),
		)
		ImportPortableStepsResult.OutsideRetention -> ImportResult(
			skippedCount = 1,
			errors = listOf("Portable Steps entry is outside the retained data window."),
		)
		is ImportPortableStepsResult.Conflict -> ImportResult(
			failedCount = 1,
			errors = listOf("Portable Steps entry conflicts with existing ${scope.name.lowercase()} identity."),
		)
		is ImportPortableStepsResult.Unverifiable -> ImportResult(
			failedCount = 1,
			errors = listOf("Portable Steps entry cannot be verified: ${reason.name.lowercase()}."),
		)
		is ImportPortableStepsResult.RetryableFailure -> throw PortableStepsRetryableImportException(reason)
	}

	internal companion object {
		const val EXTENSION = StepsPortableFormatV1.FILE_EXTENSION
		const val MAX_FILE_BYTES = StepsPortableFormatV1.MAX_FILE_BYTES
		const val PERMANENT_FORMAT_ERROR =
			"Portable Steps file is malformed, unsupported, or exceeds its limits."
	}
}

/** Signals WorkManager to retry without collapsing a transient source-local refusal into failure. */
internal class PortableStepsRetryableImportException(
	reason: PortableStepsTransferRetryableReason,
) : IOException("Portable Steps import is temporarily unavailable: ${reason.name.lowercase()}.") {
	private companion object {
		const val serialVersionUID: Long = 1L
	}
}
