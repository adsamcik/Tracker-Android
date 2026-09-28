package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.portable.InvalidLocationFile
import com.adsamcik.tracker.impexp.portable.LocationFileJsonV1
import com.adsamcik.tracker.impexp.portable.LocationFileMeasurementV1
import com.adsamcik.tracker.impexp.portable.LocationFileOrigin
import com.adsamcik.tracker.impexp.portable.LocationFilePointV1
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationPathSampleRow
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import java.io.OutputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Exports the Location data itself, independent of whether a session exists. */
internal class LocationFileExporter @JvmOverloads @Inject constructor(
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) : CursorAwareExporter {
	/** The source-owned reader admits coordinate-free points and reports its own empty selection. */
	override val requiresLocationData: Boolean = false
	override val canSelectDateRange: Boolean = true
	override val selectsCalendarDates: Boolean = true
	override val mimeType: String = LocationFileJsonV1.MIME_TYPE
	override val extension: String = LocationFileJsonV1.EXTENSION

	override suspend fun export(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
	): ExportResult = exportAfter(
		context, locationData, outputStream, dateRange, null, null, null, {},
	)

	override suspend fun exportAfter(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
		afterTimeMs: Long?,
		afterId: Long?,
		database: AppDatabase?,
		verifyCollectedDataAccess: () -> Unit,
	): ExportResult {
		@Suppress("UNUSED_VARIABLE") val ignoredSequence = locationData
		return try {
			withContext(dispatchers.io) {
				writePoints(
					database ?: AppDatabase.database(context), outputStream,
					dateRange, afterTimeMs, afterId, verifyCollectedDataAccess,
				)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			ExportResult.Error(LocalizedString(
				R.string.export_error_with_reason,
				failure.message ?: "Location file could not be saved",
			))
		}
	}

	private suspend fun writePoints(
		database: AppDatabase,
		outputStream: OutputStream,
		dateRange: LongRange?,
		initialAfterTimeMs: Long?,
		initialAfterId: Long?,
		verifyCollectedDataAccess: () -> Unit,
	): ExportResult {
		if (dateRange != null && dateRange.isEmpty()) {
			throw InvalidLocationFile("Selected Location dates are empty")
		}
		val fromMs = dateRange?.first ?: Long.MIN_VALUE
		val toMs = dateRange?.last ?: Long.MAX_VALUE
		val purposeDao = database.locationPurposeDao()
		val identityDao = database.locationFileIdentityDao()
		val writer = LocationFileJsonV1().writer(outputStream)
		var afterTimeMs = initialAfterTimeMs
		var afterId = initialAfterId
		var count = 0
		var maxTimeMs = 0L
		var maxId = 0L
		var authorityStamp: String? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			verifyCollectedDataAccess()
			val before = purposeDao.ambientExportAuthorityStamp()
			if (authorityStamp != null && before != authorityStamp) {
				throw InvalidLocationFile("Location permissions or retention changed during export")
			}
			val page = purposeDao.visiblePathChunk(
				fromMs, toMs, afterTimeMs, afterId, PAGE_SIZE,
			)
			if (purposeDao.ambientExportAuthorityStamp() != before) {
				throw InvalidLocationFile("Location permissions or retention changed during export")
			}
			if (page.isEmpty()) break
			authorityStamp = before
			for (row in page) {
				currentCoroutineContext().ensureActive()
				verifyCollectedDataAccess()
				val identity = identityDao.localIdentityFor(row.sample.id)
				val origin = if (identity.imported) {
					LocationFileOrigin.entries.firstOrNull {
						it.name.equals(identity.reportedOrigin, ignoreCase = true)
					} ?: LocationFileOrigin.UNKNOWN
				} else {
					row.fileOrigin()
				}
				writer.point(LocationFilePointV1(
					pointId = identity.portableId,
					origin = origin,
					measurement = LocationFileMeasurementV1.from(row.sample.toModel()),
				))
				count++
				maxTimeMs = row.sample.timeMs
				maxId = row.sample.id
			}
			val last = page.last().sample
			afterTimeMs = last.timeMs
			afterId = last.id
			if (purposeDao.ambientExportAuthorityStamp() != before) {
				throw InvalidLocationFile("Location permissions or retention changed during export")
			}
		}
		verifyCollectedDataAccess()
		if (authorityStamp != null && purposeDao.ambientExportAuthorityStamp() != authorityStamp) {
			throw InvalidLocationFile("Location permissions or retention changed during export")
		}
		if (count == 0) throw InvalidLocationFile("No Location points in these dates")
		check(writer.finish() == count)
		return ExportResult.Success(recordCount = count, maxTimeMs = maxTimeMs, maxId = maxId)
	}

	internal companion object { const val PAGE_SIZE = 256 }
}

internal fun LocationPathSampleRow.fileOrigin(): LocationFileOrigin = when {
	hasSessionPurpose && hasVisibleBackgroundPurpose -> LocationFileOrigin.BOTH
	hasSessionPurpose -> LocationFileOrigin.SESSION
	hasVisibleBackgroundPurpose -> LocationFileOrigin.BACKGROUND
	else -> LocationFileOrigin.UNKNOWN
}
