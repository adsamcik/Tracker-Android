package com.adsamcik.tracker.impexp.exporter

import android.content.Context
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
 * captured during that session. Schema 2 deliberately keeps related raw data together,
 * so a consumer can process one session without retaining the complete export in memory.
 */
class JsonExporter @JvmOverloads @Inject constructor(
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) : Exporter {
	override val canSelectDateRange: Boolean = true
	override val mimeType: String = "application/json"
	override val extension: String = "json"

	override suspend fun export(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
	): ExportResult {
		// Session-scoped queries preserve the requested grouping. The caller-provided
		// sequence cannot be rewound per session.
		@Suppress("UNUSED_VARIABLE")
		val ignoredLocationData = locationData
		val database = AppDatabase.database(context)
		return try {
			withContext(dispatchers.io) {
				writeSessions(outputStream, database, dateRange)
			}
			ExportResult.Success
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
		BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use { writer ->
			writer.write("[")
			var first = true
			sessions.forEach { session ->
				if (!first) writer.write(",")
				first = false
				writeSessionRecord(writer, session)
			}
			writer.write("]")
			writer.flush()
		}
		ExportResult.Success
	} catch (e: Exception) {
		Reporter.report(e)
		ExportResult.Error(LocalizedString(R.string.export_error_with_reason, e.message ?: "Failed to write JSON export"))
	}

	private suspend fun writeSessions(
		outputStream: OutputStream,
		database: AppDatabase,
		dateRange: LongRange?,
	) {
		val fromMs = dateRange?.first ?: 0L
		val toMs = dateRange?.last ?: Long.MAX_VALUE
		BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use { writer ->
			writer.write("[")
			var first = true
			var offset = 0
			while (true) {
				val page = database.tripDao().getBetweenPage(
					fromMs = fromMs,
					toMs = toMs,
					limit = SESSION_PAGE_SIZE,
					offset = offset,
				)
				if (page.isEmpty()) break
				page.forEach { trip ->
					if (!first) writer.write(",")
					first = false
					writeSessionRecord(writer, database, trip.toModel())
				}
				offset += page.size
				if (page.size < SESSION_PAGE_SIZE) break
			}
			writer.write("]")
			writer.flush()
		}
	}

	private suspend fun writeSessionRecord(writer: BufferedWriter, database: AppDatabase, trip: Trip) {
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
		writeLocations(writer, database, trip.startTimeMs, trip.endTimeMs)
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
		writer.write("{\"schemaVersion\":2,\"session\":")
		writeSession(writer, session)
		writer.write(",\"locations\":[")
	}

	private suspend fun writeLocations(writer: BufferedWriter, database: AppDatabase, fromMs: Long, toMs: Long) {
		var afterTimeMs: Long? = null
		var afterId: Long? = null
		var first = true
		while (true) {
			val page = database.locationSampleDao().getChunkBetweenOrdered(
				fromMs, toMs, afterTimeMs, afterId, RECORD_PAGE_SIZE,
			)
			if (page.isEmpty()) return
			page.forEach { sample ->
				if (!first) writer.write(",")
				first = false
				writeLocation(writer, sample.toModel())
			}
			page.last().also {
				afterTimeMs = it.timeMs
				afterId = it.id
			}
		}
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
		sample.altitudeM?.let { writer.write(",\"altitudeM\":${it.toDouble()}") }
		sample.rawGpsAltitudeM?.let { writer.write(",\"rawGpsAltitudeM\":${it.toDouble()}") }
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
