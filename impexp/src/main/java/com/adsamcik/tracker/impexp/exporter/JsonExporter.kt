package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * Exports location data and session metadata as a streaming JSON document
 * with schema versioning.
 *
 * Segments are intentionally excluded because they can be regenerated
 * from raw location data during import (see [JsonImport]).
 *
 * Format:
 * ```json
 * {"schema":1,"exportedAt":...,"locations":[...],"sessions":[...]}
 * ```
 */
class JsonExporter : Exporter {
	override val canSelectDateRange: Boolean = true
	override val mimeType: String = "application/json"
	override val extension: String = "json"

	override suspend fun export(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
	): ExportResult {
		val db = AppDatabase.database(context)

		val sessions = try {
			val trips = withContext(DefaultDispatchersProvider.io) {
				val fromMs = dateRange?.first ?: 0L
				val toMs = dateRange?.last ?: Long.MAX_VALUE
				db.tripDao().getBetween(fromMs, toMs)
			}
			trips.map { t ->
				SessionSnapshot(
					id = t.id,
					start = t.startTimeMs,
					end = t.endTimeMs,
					collections = t.sampleCount,
					distanceInM = t.distanceM,
					isUserInitiated = t.isUserInitiated,
					steps = t.steps?.takeIf { it > 0 },
				)
			}
		} catch (e: Exception) {
			Reporter.w(EXPORT_LOG_SOURCE, "Failed to load sessions for JSON export: ${e.message}")
			emptyList()
		}

		return writeJson(outputStream, locationData, sessions, dateRange)
	}

	/**
	 * Core streaming writer, separated for testability.
	 * All parameters are plain Kotlin types — no Android dependency.
	 */
	internal fun writeJson(
		outputStream: OutputStream,
		locations: Sequence<LocationSample>,
		sessions: List<SessionSnapshot> = emptyList(),
		dateRange: LongRange? = null,
	): ExportResult {
		return try {
			BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use { w ->
				w.write("{\"schema\":1")
				w.write(",\"exportedAt\":${System.currentTimeMillis()}")

				if (dateRange != null) {
					w.write(",\"dateRangeStart\":${dateRange.first}")
					w.write(",\"dateRangeEnd\":${dateRange.last}")
				}

				// Locations array (streaming — one item at a time)
				w.write(",\"locations\":[")
				var first = true
				locations.forEach { sample ->
					val lat = sample.latE7 ?: return@forEach
					val lon = sample.lonE7 ?: return@forEach
					if (!first) w.write(",")
					first = false
					writeLocation(w, sample, lat, lon)
				}
				w.write("]")

				// Sessions array
				w.write(",\"sessions\":[")
				sessions.forEachIndexed { i, s ->
					if (i > 0) w.write(",")
					writeSession(w, s)
				}
				w.write("]")

				w.write("}")
				w.flush()
			}
			ExportResult.Success
		} catch (e: Exception) {
			ExportResult.Error()
		}
	}

	private fun writeLocation(w: BufferedWriter, sample: LocationSample, latE7: Int, lonE7: Int) {
		w.write("{\"time\":${sample.timeMs}")
		w.write(",\"lat\":${latE7 / 1e7}")
		w.write(",\"lon\":${lonE7 / 1e7}")
		sample.altitudeM?.let { w.write(",\"alt\":${it.toDouble()}") }
		sample.speedMps?.let { w.write(",\"spd\":$it") }
		sample.hAccM?.let { w.write(",\"acc\":$it") }
		w.write("}")
	}

	private fun writeSession(w: BufferedWriter, s: SessionSnapshot) {
		w.write("{\"id\":${s.id}")
		w.write(",\"start\":${s.start}")
		w.write(",\"end\":${s.end}")
		w.write(",\"collections\":${s.collections}")
		w.write(",\"distanceInM\":${s.distanceInM}")
		w.write(",\"isUserInitiated\":${s.isUserInitiated}")
		s.steps?.let { w.write(",\"steps\":$it") }
		w.write("}")
	}

	companion object {
		internal fun escapeJson(s: String): String = s
			.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r")
			.replace("\t", "\\t")
	}
}

/**
 * Lightweight snapshot of a Trip for JSON export.
 * Avoids coupling the exporter to Room entity internals.
 */
data class SessionSnapshot(
	val id: Long,
	val start: Long,
	val end: Long,
	val collections: Int,
	val distanceInM: Float,
	val isUserInitiated: Boolean,
	val steps: Int? = null,
)
