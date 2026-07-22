package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import androidx.paging.PagingSource
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.Trip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import javax.inject.Inject

/**
 * Exports a streaming JSON array of self-contained session records.
 *
 * Each record has a `session` summary and the location, Wi-Fi, and cell observations
 * captured during that session. Schema 3 deliberately keeps related raw data together,
 * so a consumer can process one session without retaining the complete export in memory.
 */
class JsonExporter @JvmOverloads @Inject constructor(
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) : CursorAwareExporter {
	override val canSelectDateRange: Boolean = true
	override val mimeType: String = "application/json"
	override val extension: String = "json"

	override suspend fun export(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
	): ExportResult = exportAfter(
		context = context,
		locationData = locationData,
		outputStream = outputStream,
		dateRange = dateRange,
		afterTimeMs = null,
		afterId = null,
	)

	override suspend fun exportAfter(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
		afterTimeMs: Long?,
		afterId: Long?,
	): ExportResult {
		// Session-scoped queries preserve the requested grouping. The caller-provided
		// sequence cannot be rewound per session.
		@Suppress("UNUSED_VARIABLE")
		val ignoredLocationData = locationData
		val database = AppDatabase.database(context)
		return try {
			val progress = withContext(dispatchers.io) {
				writeSessions(outputStream, database, dateRange, afterTimeMs, afterId)
			}
			progress.toResult()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Reporter.report(e)
			ExportResult.Error(
				LocalizedString(
					R.string.export_error_with_reason,
					e.message ?: "Failed to write JSON export",
				),
			)
		}
	}

	/**
	 * Pure streaming writer used by unit tests and non-Room callers.
	 */
	internal fun writeJson(
		outputStream: OutputStream,
		sessions: Sequence<SessionExport>,
	): ExportResult = try {
		val progress = LocationProgress()
		BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use { writer ->
			writer.write("[")
			var first = true
			sessions.forEach { session ->
				if (!first) writer.write(",")
				first = false
				writeSessionRecord(writer, session)
				session.locations.forEach { progress.include(it.id, it.timeMs) }
			}
			writer.write("]")
			writer.flush()
		}
		progress.toResult()
	} catch (e: Exception) {
		Reporter.report(e)
		ExportResult.Error(LocalizedString(R.string.export_error_with_reason, e.message ?: "Failed to write JSON export"))
	}

	private suspend fun writeSessions(
		outputStream: OutputStream,
		database: AppDatabase,
		dateRange: LongRange?,
		afterTimeMs: Long?,
		afterId: Long?,
	): LocationProgress {
		val fromMs = dateRange?.first ?: 0L
		val toMs = dateRange?.last ?: Long.MAX_VALUE
		val progress = LocationProgress()
		val exportedSessionRanges = mutableListOf<LongRange>()
		BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use { writer ->
			writer.write("[")
			var first = true
			forEachOverlappingTrip(database, fromMs, toMs) { trip ->
				if (hasLocationAfterCursor(database, trip, afterTimeMs, afterId)) {
					if (!first) writer.write(",")
					first = false
					writeSessionRecord(writer, database, trip.toModel(), progress)
					exportedSessionRanges += trip.startTimeMs..trip.endTimeMs
				}
			}
			writeOrphanedRecord(
				writer = writer,
				database = database,
				fromMs = fromMs,
				toMs = toMs,
				afterTimeMs = afterTimeMs,
				afterId = afterId,
				exportedSessionRanges = mergeRanges(exportedSessionRanges),
				progress = progress,
				beforeStart = {
					if (!first) writer.write(",")
					first = false
				},
			)
			writer.write("]")
			writer.flush()
		}
		return progress
	}

	private suspend fun writeSessionRecord(
		writer: BufferedWriter,
		database: AppDatabase,
		trip: Trip,
		progress: LocationProgress,
	) {
		writeSessionRecordStart(
			writer,
			SessionSnapshot(
				id = trip.id,
				startTimeMs = trip.startTimeMs,
				endTimeMs = trip.endTimeMs,
				distanceM = trip.distanceM,
				steps = trip.steps,
				primaryActivity = trip.primaryActivity,
				activityConfidence = trip.activityConfidence,
				sampleCount = trip.sampleCount,
				source = trip.source.name,
				hasDistanceAnomaly = trip.hasDistanceAnomaly,
			),
		)
		writeLocations(writer, database, trip.startTimeMs, trip.endTimeMs, progress = progress)
		writer.write("],\"wifiObservations\":[")
		writeWifiObservations(writer, database, trip.startTimeMs, trip.endTimeMs)
		writer.write("],\"cellSamples\":[")
		writeCellSamples(writer, database, trip.startTimeMs, trip.endTimeMs)
		writer.write("]}")
	}

	private fun writeSessionRecord(writer: BufferedWriter, export: SessionExport) {
		writeSessionRecordStart(writer, export.session)
		export.locations.forEachIndexed { index, location ->
			if (index > 0) writer.write(",")
			writeLocation(writer, location)
		}
		writer.write("],\"wifiObservations\":[")
		export.wifiObservations.forEachIndexed { index, observation ->
			if (index > 0) writer.write(",")
			writeWifi(writer, observation)
		}
		writer.write("],\"cellSamples\":[")
		export.cellSamples.forEachIndexed { index, sample ->
			if (index > 0) writer.write(",")
			writeCell(writer, sample)
		}
		writer.write("]}")
	}

	private fun writeSessionRecordStart(writer: BufferedWriter, session: SessionSnapshot) {
		writer.write("{\"schemaVersion\":3,\"session\":")
		writeSession(writer, session)
		writer.write(",\"locations\":[")
	}

	private suspend fun writeLocations(
		writer: BufferedWriter,
		database: AppDatabase,
		fromMs: Long,
		toMs: Long,
		progress: LocationProgress,
	) {
		var pageAfterTimeMs: Long? = null
		var pageAfterId: Long? = null
		var first = true
		while (true) {
			val page = database.locationSampleDao().getChunkBetweenOrdered(
				fromMs, toMs, pageAfterTimeMs, pageAfterId, RECORD_PAGE_SIZE,
			)
			if (page.isEmpty()) return
			page.forEach { sample ->
				if (!first) writer.write(",")
				first = false
				writeLocation(writer, sample.toModel())
				progress.include(sample.id, sample.timeMs)
			}
			page.last().also {
				pageAfterTimeMs = it.timeMs
				pageAfterId = it.id
			}
		}
	}

	private suspend fun forEachOverlappingTrip(
		database: AppDatabase,
		fromMs: Long,
		toMs: Long,
		block: suspend (com.adsamcik.tracker.shared.base.database.data.Trip) -> Unit,
	) {
		val source = database.tripDao().getPagedOverlapping(fromMs, toMs)
		var nextKey: Int? = null
		var firstLoad = true
		try {
			while (true) {
				val params = if (firstLoad) {
					PagingSource.LoadParams.Refresh(
						key = nextKey,
						loadSize = SESSION_PAGE_SIZE,
						placeholdersEnabled = false,
					)
				} else {
					PagingSource.LoadParams.Append(
						key = requireNotNull(nextKey),
						loadSize = SESSION_PAGE_SIZE,
						placeholdersEnabled = false,
					)
				}
				when (val result = source.load(params)) {
					is PagingSource.LoadResult.Page -> {
						result.data.forEach { block(it) }
						nextKey = result.nextKey
						if (nextKey == null) return
						firstLoad = false
					}
					is PagingSource.LoadResult.Error -> throw result.throwable
					is PagingSource.LoadResult.Invalid -> return
				}
			}
		} finally {
			source.invalidate()
		}
	}

	private suspend fun hasLocationAfterCursor(
		database: AppDatabase,
		trip: com.adsamcik.tracker.shared.base.database.data.Trip,
		afterTimeMs: Long?,
		afterId: Long?,
	): Boolean {
		if (afterTimeMs == null) return true
		return database.locationSampleDao().getChunkBetweenOrdered(
			fromMs = trip.startTimeMs,
			toMs = trip.endTimeMs,
			afterTimeMs = afterTimeMs,
			afterId = afterId,
			limit = 1,
		).isNotEmpty()
	}

	private suspend fun writeOrphanedRecord(
		writer: BufferedWriter,
		database: AppDatabase,
		fromMs: Long,
		toMs: Long,
		afterTimeMs: Long?,
		afterId: Long?,
		exportedSessionRanges: List<LongRange>,
		progress: LocationProgress,
		beforeStart: () -> Unit,
	) {
		val record = OrphanRecordWriter(writer, beforeStart)
		var locationAfterTimeMs = afterTimeMs
		var locationAfterId = afterId
		while (true) {
			val page = database.locationSampleDao().getChunkBetweenOrdered(
				fromMs,
				toMs,
				locationAfterTimeMs,
				locationAfterId,
				RECORD_PAGE_SIZE,
			)
			if (page.isEmpty()) break
			page.forEach { sample ->
				if (!exportedSessionRanges.containsTime(sample.timeMs)) {
					record.writeLocation(sample.toModel())
					progress.include(sample.id, sample.timeMs)
				}
			}
			page.last().also {
				locationAfterTimeMs = it.timeMs
				locationAfterId = it.id
			}
		}

		var wifiAfterTimeMs: Long? = null
		var wifiAfterId: Long? = null
		while (true) {
			val page = database.wifiObservationDao().getChunkBetweenOrdered(
				fromMs,
				toMs,
				wifiAfterTimeMs,
				wifiAfterId,
				RECORD_PAGE_SIZE,
			)
			if (page.isEmpty()) break
			page.forEach { observation ->
				if (!exportedSessionRanges.containsTime(observation.timeMs)) {
					record.writeWifi(observation)
				}
			}
			page.last().also {
				wifiAfterTimeMs = it.timeMs
				wifiAfterId = it.id
			}
		}

		var cellAfterTimeMs: Long? = null
		var cellAfterId: Long? = null
		while (true) {
			val page = database.cellSampleDao().getChunkBetweenOrdered(
				fromMs,
				toMs,
				cellAfterTimeMs,
				cellAfterId,
				RECORD_PAGE_SIZE,
			)
			if (page.isEmpty()) break
			page.forEach { sample ->
				if (!exportedSessionRanges.containsTime(sample.timeMs)) {
					record.writeCell(sample)
				}
			}
			page.last().also {
				cellAfterTimeMs = it.timeMs
				cellAfterId = it.id
			}
		}
		record.finish()
	}

	private fun mergeRanges(ranges: List<LongRange>): List<LongRange> {
		if (ranges.size < 2) return ranges
		val sorted = ranges.sortedBy { it.first }
		val merged = mutableListOf<LongRange>()
		var current = sorted.first()
		sorted.drop(1).forEach { range ->
			if (range.first <= current.last) {
				current = current.first..maxOf(current.last, range.last)
			} else {
				merged += current
				current = range
			}
		}
		merged += current
		return merged
	}

	private fun List<LongRange>.containsTime(timeMs: Long): Boolean {
		var low = 0
		var high = lastIndex
		while (low <= high) {
			val middle = (low + high).ushr(1)
			val range = this[middle]
			when {
				timeMs < range.first -> high = middle - 1
				timeMs > range.last -> low = middle + 1
				else -> return true
			}
		}
		return false
	}

	private suspend fun writeWifiObservations(writer: BufferedWriter, database: AppDatabase, fromMs: Long, toMs: Long) {
		var afterTimeMs: Long? = null
		var afterId: Long? = null
		var first = true
		while (true) {
			val page = database.wifiObservationDao().getChunkBetweenOrdered(
				fromMs, toMs, afterTimeMs, afterId, RECORD_PAGE_SIZE,
			)
			if (page.isEmpty()) return
			page.forEach { observation ->
				if (!first) writer.write(",")
				first = false
				writeWifi(writer, observation)
			}
			page.last().also {
				afterTimeMs = it.timeMs
				afterId = it.id
			}
		}
	}

	private suspend fun writeCellSamples(writer: BufferedWriter, database: AppDatabase, fromMs: Long, toMs: Long) {
		var afterTimeMs: Long? = null
		var afterId: Long? = null
		var first = true
		while (true) {
			val page = database.cellSampleDao().getChunkBetweenOrdered(
				fromMs, toMs, afterTimeMs, afterId, RECORD_PAGE_SIZE,
			)
			if (page.isEmpty()) return
			page.forEach { sample ->
				if (!first) writer.write(",")
				first = false
				writeCell(writer, sample)
			}
			page.last().also {
				afterTimeMs = it.timeMs
				afterId = it.id
			}
		}
	}

	private fun writeSession(writer: BufferedWriter, session: SessionSnapshot) {
		writer.write("{\"id\":${session.id},\"startTimeMs\":${session.startTimeMs},\"endTimeMs\":${session.endTimeMs}")
		writer.write(",\"distanceM\":${session.distanceM},\"sampleCount\":${session.sampleCount}")
		session.steps?.let { writer.write(",\"steps\":$it") }
		session.primaryActivity?.let { writer.write(",\"primaryActivity\":$it") }
		session.activityConfidence?.let { writer.write(",\"activityConfidence\":$it") }
		writer.write(",\"source\":\"${escapeJson(session.source)}\",\"hasDistanceAnomaly\":${session.hasDistanceAnomaly}}")
	}

	private fun writeLocation(writer: BufferedWriter, sample: LocationSample) {
		writer.write("{\"timeMs\":${sample.timeMs}")
		sample.latE7?.let { writer.write(",\"latitude\":${it / 1e7}") }
		sample.lonE7?.let { writer.write(",\"longitude\":${it / 1e7}") }
		sample.altitudeM?.takeIf { it.isFinite() }?.let { writer.write(",\"altitudeM\":${it.toDouble()}") }
		sample.rawGpsAltitudeM?.takeIf { it.isFinite() }?.let {
			writer.write(",\"rawGpsAltitudeM\":${it.toDouble()}")
		}
		// JSON has room to preserve the datum rather than labelling a bare number as MSL. A reader
		// that does not understand these fields still treats the altitude conservatively as legacy.
		writer.write(",\"altitudeDatum\":\"${sample.altitudeDatum.storageName}\"")
		writer.write(",\"altitudeSource\":\"${sample.altitudeSource.storageName}\"")
		writer.write(",\"altitudeConversionStatus\":\"${sample.altitudeConversionStatus.storageName}\"")
		writer.write(",\"rawGpsAltitudeDatum\":\"${sample.rawGpsAltitudeDatum.storageName}\"")
		writer.write(",\"altitudeModelVersion\":${sample.altitudeModelVersion}")
		writer.write(",\"altitudeEstimatorVersion\":${sample.estimatorVersion}")
		writer.write(",\"altitudeCalibrationVersion\":${sample.calibrationVersion}")
		sample.hAccM?.let { writer.write(",\"horizontalAccuracyM\":$it") }
		sample.vAccM?.let { writer.write(",\"verticalAccuracyM\":$it") }
		sample.speedMps?.let { writer.write(",\"speedMps\":$it") }
		sample.speedAccuracyMps?.let { writer.write(",\"speedAccuracyMps\":$it") }
		writer.write(",\"provider\":\"${escapeJson(sample.provider)}\",\"quality\":\"${sample.quality.name}\"}")
	}

	private fun writeWifi(writer: BufferedWriter, observation: WifiObservation) {
		writer.write("{\"timeMs\":${observation.timeMs},\"bssid\":\"${escapeJson(observation.bssid)}\"")
		writer.write(",\"ssid\":\"${escapeJson(observation.ssid)}\",\"capabilities\":\"${escapeJson(observation.capabilities)}\"")
		writer.write(",\"frequencyMhz\":${observation.frequency},\"levelDbm\":${observation.level}")
		observation.latE7?.let { writer.write(",\"latitude\":${it / 1e7}") }
		observation.lonE7?.let { writer.write(",\"longitude\":${it / 1e7}") }
		writer.write(",\"coordinateProvenance\":\"${observation.provenance.name}\"}")
	}

	private fun writeCell(writer: BufferedWriter, sample: CellSample) {
		writer.write("{\"timeMs\":${sample.timeMs},\"cellId\":${sample.cellId},\"lac\":${sample.lac}")
		writer.write(",\"mcc\":${sample.mcc},\"mnc\":${sample.mnc},\"networkType\":${sample.networkType}")
		writer.write(",\"signalStrength\":${sample.signalStrength}")
		sample.latE7?.let { writer.write(",\"latitude\":${it / 1e7}") }
		sample.lonE7?.let { writer.write(",\"longitude\":${it / 1e7}") }
		writer.write(",\"coordinateProvenance\":\"${sample.provenance.name}\"}")
	}

	private inner class OrphanRecordWriter(
		private val writer: BufferedWriter,
		private val beforeStart: () -> Unit,
	) {
		private var stage = 0
		private var firstInStage = true

		fun writeLocation(sample: LocationSample) {
			advanceTo(1)
			writeSeparator()
			writeLocation(writer, sample)
		}

		fun writeWifi(observation: WifiObservation) {
			advanceTo(2)
			writeSeparator()
			writeWifi(writer, observation)
		}

		fun writeCell(sample: CellSample) {
			advanceTo(3)
			writeSeparator()
			writeCell(writer, sample)
		}

		fun finish() {
			if (stage == 0) return
			while (stage < 3) advanceTo(stage + 1)
			writer.write("]}")
		}

		private fun advanceTo(target: Int) {
			if (stage == 0) {
				beforeStart()
				writer.write("{\"schemaVersion\":3,\"orphanedData\":true,\"locations\":[")
				stage = 1
				firstInStage = true
			}
			while (stage < target) {
				when (stage) {
					1 -> writer.write("],\"wifiObservations\":[")
					2 -> writer.write("],\"cellSamples\":[")
				}
				stage++
				firstInStage = true
			}
		}

		private fun writeSeparator() {
			if (!firstInStage) writer.write(",")
			firstInStage = false
		}
	}

	private data class LocationProgress(
		var recordCount: Int = 0,
		var maxTimeMs: Long = 0L,
		var maxId: Long = 0L,
	) {
		fun include(id: Long, timeMs: Long) {
			recordCount++
			if (timeMs > maxTimeMs || (timeMs == maxTimeMs && id > maxId)) {
				maxTimeMs = timeMs
				maxId = id
			}
		}

		fun toResult(): ExportResult.Success = ExportResult.Success(
			recordCount = recordCount,
			maxTimeMs = maxTimeMs,
			maxId = maxId,
		)
	}

	companion object {
		internal fun escapeJson(s: String): String = s
			.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r")
			.replace("\t", "\\t")

		private const val SESSION_PAGE_SIZE = 200
		private const val RECORD_PAGE_SIZE = 500
	}
}

data class SessionSnapshot(
	val id: Long,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val distanceM: Float,
	val steps: Int?,
	val primaryActivity: Int?,
	val activityConfidence: Int?,
	val sampleCount: Int,
	val source: String,
	val hasDistanceAnomaly: Boolean,
)

data class SessionExport(
	val session: SessionSnapshot,
	val locations: List<LocationSample>,
	val wifiObservations: List<WifiObservation>,
	val cellSamples: List<CellSample>,
)
