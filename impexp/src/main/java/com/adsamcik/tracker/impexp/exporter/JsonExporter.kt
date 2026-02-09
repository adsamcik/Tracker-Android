package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * Exports location data (and optionally session segments + route cache)
 * as a streaming JSON document with schema versioning.
 *
 * Format is designed for full-fidelity backup/restore:
 * ```json
 * {"schema":1,"exportedAt":1707500000000,"locations":[...],"sessions":[...]}
 * ```
 */
class JsonExporter : Exporter {
	override val canSelectDateRange: Boolean = true
	override val mimeType: String = "application/json"
	override val extension: String = "json"

	override fun export(
		context: Context,
		locationData: Sequence<DatabaseLocation>,
		outputStream: OutputStream,
		dateRange: LongRange?,
	): ExportResult {
		val db = AppDatabase.database(context)

		val sessions = try {
			db.sessionDao().getAll().map { s ->
				SessionSnapshot(
					id = s.id,
					start = s.start,
					end = s.end,
					collections = s.collections,
					distanceInM = s.distanceInM,
					isUserInitiated = s.isUserInitiated,
					steps = s.steps.takeIf { it > 0 },
				)
			}
		} catch (_: Exception) {
			emptyList()
		}

		val segments = try {
			db.sessionSegmentDao().getAllBetween(0L, Long.MAX_VALUE).map { seg ->
				SegmentSnapshot(
					id = seg.id,
					startTimeMs = seg.startTimeMs,
					endTimeMs = seg.endTimeMs,
					distanceM = seg.distanceM,
					sampleCount = seg.sampleCount,
					source = seg.source.name,
					steps = seg.steps,
					primaryActivity = seg.primaryActivity,
				)
			}
		} catch (_: Exception) {
			emptyList()
		}

		return writeJson(outputStream, locationData, sessions, segments, dateRange)
	}

	/**
	 * Core streaming writer, separated for testability.
	 * All parameters are plain Kotlin types — no Android dependency.
	 */
	internal fun writeJson(
		outputStream: OutputStream,
		locations: Sequence<DatabaseLocation>,
		sessions: List<SessionSnapshot> = emptyList(),
		segments: List<SegmentSnapshot> = emptyList(),
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
				locations.forEach { loc ->
					if (!first) w.write(",")
					first = false
					writeLocation(w, loc)
				}
				w.write("]")

				// Sessions array
				w.write(",\"sessions\":[")
				sessions.forEachIndexed { i, s ->
					if (i > 0) w.write(",")
					writeSession(w, s)
				}
				w.write("]")

				// Segments array
				w.write(",\"segments\":[")
				segments.forEachIndexed { i, s ->
					if (i > 0) w.write(",")
					writeSegment(w, s)
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

	private fun writeLocation(w: BufferedWriter, loc: DatabaseLocation) {
		val l = loc.location
		w.write("{\"time\":${l.time}")
		w.write(",\"lat\":${l.latitude}")
		w.write(",\"lon\":${l.longitude}")
		l.altitude?.let { w.write(",\"alt\":$it") }
		l.speed?.let { w.write(",\"spd\":$it") }
		l.horizontalAccuracy?.let { w.write(",\"acc\":$it") }
		l.bearing?.let { w.write(",\"bear\":$it") }
		w.write(",\"act\":${loc.activityInfo.activityType}")
		w.write(",\"actConf\":${loc.activityInfo.confidence}")
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

	private fun writeSegment(w: BufferedWriter, s: SegmentSnapshot) {
		w.write("{\"id\":${s.id}")
		w.write(",\"startTimeMs\":${s.startTimeMs}")
		w.write(",\"endTimeMs\":${s.endTimeMs}")
		w.write(",\"distanceM\":${s.distanceM}")
		w.write(",\"sampleCount\":${s.sampleCount}")
		w.write(",\"source\":\"${escapeJson(s.source)}\"")
		s.steps?.let { w.write(",\"steps\":$it") }
		s.primaryActivity?.let { w.write(",\"primaryActivity\":$it") }
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
 * Lightweight snapshot of a TrackerSession for JSON export.
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

/**
 * Lightweight snapshot of a SessionSegment for JSON export.
 */
data class SegmentSnapshot(
	val id: Long,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val distanceM: Float,
	val sampleCount: Int,
	val source: String,
	val steps: Int? = null,
	val primaryActivity: Int? = null,
)
