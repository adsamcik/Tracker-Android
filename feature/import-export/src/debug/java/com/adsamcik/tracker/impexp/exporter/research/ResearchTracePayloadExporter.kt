package com.adsamcik.tracker.impexp.exporter.research

import android.content.Context
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.TrackerRun
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A synchronization event recorded alongside external ground truth. */
data class ResearchTraceMarker(
	val id: String,
	val label: String,
	val wallTimeMs: Long,
	val elapsedRealtimeNanos: Long,
	val attributes: Map<String, String> = emptyMap(),
)

/** Optional externally supplied metadata embedded in the trace manifest. */
data class ResearchTraceMetadata(
	val traceId: String? = null,
	val sessionLabel: String? = null,
	val attributes: Map<String, String> = emptyMap(),
	val markers: List<ResearchTraceMarker> = emptyList(),
)

/**
 * Debug-only streaming NDJSON payload for offline estimator and calibration research.
 *
 * This exporter is intentionally absent from the production format registry. For a real trace,
 * call [encrypted] and write that exporter directly to the destination: the NDJSON then flows from
 * Room into the authenticated encryption stream without a plaintext staging file.
 *
 * Records are self-describing via `recordType`. A manifest comes first, followed by external clock
 * markers, raw provider observations, overlapping tracker runs, the caller-provided accepted
 * location sequence, and a terminal count record. Database pages use an
 * ID watermark captured before writing, preventing rows appended during an export from leaking
 * into an otherwise bounded snapshot.
 */
class ResearchTracePayloadExporter(
	private val metadata: ResearchTraceMetadata = ResearchTraceMetadata(),
	private val databaseProvider: (Context) -> AppDatabase = { AppDatabase.database(it) },
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
	private val pageSize: Int = DEFAULT_PAGE_SIZE,
	private val nowMillis: () -> Long = System::currentTimeMillis,
) : Exporter {

	override val canSelectDateRange: Boolean = true
	override val mimeType: String = "application/x-ndjson"
	override val extension: String = "ndjson"

	init {
		require(pageSize > 0) { "Research trace page size must be positive" }
		metadata.traceId?.let { require(it.isNotBlank()) { "Trace id must not be blank" } }
		metadata.sessionLabel?.let { require(it.isNotBlank()) { "Session label must not be blank" } }
		require(metadata.attributes.keys.none(String::isBlank)) { "Metadata keys must not be blank" }
		require(metadata.markers.map { it.id }.distinct().size == metadata.markers.size) {
			"Research trace marker ids must be unique"
		}
		metadata.markers.forEach { marker ->
			require(marker.id.isNotBlank()) { "Research trace marker id must not be blank" }
			require(marker.label.isNotBlank()) { "Research trace marker label must not be blank" }
			require(marker.attributes.keys.none(String::isBlank)) { "Marker attribute keys must not be blank" }
		}
	}

	/** Compose the payload directly with the debug-only authenticated encryption envelope. */
	fun encrypted(
		passphraseProvider: suspend () -> CharArray,
	): EncryptedResearchTraceExporter = EncryptedResearchTraceExporter(
		delegate = this,
		passphraseProvider = passphraseProvider,
	)

	override suspend fun export(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
	): ExportResult {
		if (dateRange == null || dateRange.isEmpty()) {
			return ExportResult.Error(
				LocalizedString(R.string.export_error_with_reason, "A non-empty research trace range is required"),
			)
		}

		return try {
			withContext(dispatchers.io) {
				writePayload(
					database = databaseProvider(context),
					acceptedLocations = locationData,
					outputStream = outputStream,
					fromMs = dateRange.first,
					toMsInclusive = dateRange.last,
				)
			}
			ExportResult.Success
		} catch (exception: CancellationException) {
			throw exception
		} catch (exception: Exception) {
			Reporter.report(exception)
			ExportResult.Error(
				LocalizedString(
					R.string.export_error_with_reason,
					exception.message ?: "Failed to write research trace payload",
				),
			)
		}
	}

	private suspend fun writePayload(
		database: AppDatabase,
		acceptedLocations: Sequence<LocationSample>,
		outputStream: OutputStream,
		fromMs: Long,
		toMsInclusive: Long,
	) {
		val observations = database.locationObservationDao()
		val runs = database.trackerRunDao()
		val watermarks = SourceWatermarks(
			locationObservationId = observations.maxId(),
			trackerRunId = runs.maxId(),
		)
		val toMsExclusive = incrementSaturated(toMsInclusive)
		val counts = RecordCounts(marker = metadata.markers.size.toLong())

		BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use { writer ->
			writer.writeRecord(manifest(fromMs, toMsInclusive, watermarks))
			metadata.markers.forEach { marker -> writer.writeRecord(markerRecord(marker)) }

			var afterId = 0L
			while (afterId < watermarks.locationObservationId) {
				val page = observations.getExportChunk(
					fromMs = fromMs,
					toMsInclusive = toMsInclusive,
					afterId = afterId,
					throughId = watermarks.locationObservationId,
					limit = pageSize,
				)
				if (page.isEmpty()) break
				page.forEach { writer.writeRecord(observationRecord(it)) }
				counts.locationObservation += page.size
				afterId = advanceCursor(afterId, page.last().id)
			}

			afterId = 0L
			while (afterId < watermarks.trackerRunId) {
				val page = runs.getOverlappingChunk(
					fromMs = fromMs,
					toMsExclusive = toMsExclusive,
					afterId = afterId,
					throughId = watermarks.trackerRunId,
					limit = pageSize,
				)
				if (page.isEmpty()) break
				page.forEach { writer.writeRecord(trackerRunRecord(it)) }
				counts.trackerRun += page.size
				afterId = advanceCursor(afterId, page.last().id)
			}

			acceptedLocations.forEach { sample ->
				if (sample.timeMs in fromMs..toMsInclusive) {
					writer.writeRecord(acceptedLocationRecord(sample))
					counts.acceptedLocationSample++
				}
			}
			writer.writeRecord(endRecord(counts))
			writer.flush()
		}
	}

	private fun manifest(
		fromMs: Long,
		toMsInclusive: Long,
		watermarks: SourceWatermarks,
	): JSONObject = JSONObject().apply {
		put(RECORD_TYPE, "manifest")
		put("format", FORMAT)
		put("schemaVersion", SCHEMA_VERSION)
		put("createdAtMs", nowMillis())
		put("rangeStartMs", fromMs)
		put("rangeEndInclusiveMs", toMsInclusive)
		put("coordinateEncoding", "E7")
		put("wallClockUnit", "unix_epoch_milliseconds")
		put("monotonicClockUnit", "elapsed_realtime_nanoseconds")
		put("nonFiniteNumberEncoding", "string")
		put("evidenceCapabilities", JSONObject().apply {
			// Schema v3 deliberately declares the historical export's limits. These facts are
			// not inferred from missing rows, and this exporter must not claim replay completeness.
			put("rawPressureEvents", false)
			put("pressureAggregateWindows", false)
			put("altitudeConversionOutcomes", false)
			put("altitudeEstimatorDecisions", false)
			put("canonicalSegmentationObservations", false)
			put("segmentationReducerOutputs", false)
			put("truthMarkers", metadata.markers.isNotEmpty())
		})
		put("loss", JSONObject().apply {
			put("lossOccurred", false)
			put("assessment", "HISTORICAL_EXPORT_NOT_CAPTURE_TRACE")
			put("replayComplete", false)
		})
		put("sourceWatermarks", JSONObject().apply {
			put("locationObservationId", watermarks.locationObservationId)
			put("trackerRunId", watermarks.trackerRunId)
		})
		put("session", JSONObject().apply {
			putNullable("traceId", metadata.traceId)
			putNullable("label", metadata.sessionLabel)
			put("attributes", attributesObject(metadata.attributes))
		})
		put("markerCount", metadata.markers.size)
	}

	private fun markerRecord(marker: ResearchTraceMarker): JSONObject = JSONObject().apply {
		put(RECORD_TYPE, "trace_marker")
		put("id", marker.id)
		put("label", marker.label)
		put("wallTimeMs", marker.wallTimeMs)
		put("elapsedRealtimeNanos", marker.elapsedRealtimeNanos)
		put("attributes", attributesObject(marker.attributes))
	}

	private fun observationRecord(value: LocationObservation): JSONObject = JSONObject().apply {
		put(RECORD_TYPE, "location_observation")
		put("id", value.id)
		put("fixTimeMs", value.fixTimeMs)
		put("fixElapsedRealtimeNanos", value.fixElapsedRealtimeNanos)
		put("receivedAtMs", value.receivedAtMs)
		put("receivedElapsedRealtimeNanos", value.receivedElapsedRealtimeNanos)
		putNullable("deliveryAgeMs", value.deliveryAgeMs)
		putNullable("latE7", value.latE7)
		putNullable("lonE7", value.lonE7)
		putFinite("rawAltitudeM", value.rawAltitudeM)
		putFinite("horizontalAccuracyM", value.hAccM)
		putFinite("verticalAccuracyM", value.vAccM)
		putFinite("speedMps", value.speedMps)
		putFinite("speedAccuracyMps", value.speedAccuracyMps)
		put("provider", value.provider)
		put("acquisitionMode", value.acquisitionMode)
		put("requestPriority", value.requestPriority)
		put("permissionPrecision", value.permissionPrecision)
		put("batchIndex", value.batchIndex)
		put("batchSize", value.batchSize)
		put("isMock", value.isMock)
		put("ingressDisposition", value.ingressDisposition)
		put("estimatorVersion", value.estimatorVersion)
		put("calibrationVersion", value.calibrationVersion)
		put("createdAtMs", value.createdAt)
	}

	private fun trackerRunRecord(value: TrackerRun): JSONObject = JSONObject().apply {
		put(RECORD_TYPE, "tracker_run")
		put("id", value.id)
		put("startTimeMs", value.startTimeMs)
		putNullable("endTimeMs", value.endTimeMs)
		put("policy", value.policy)
		putNullable("policyParams", value.policyParams)
		put("userInitiated", value.userInitiated)
		put("createdAtMs", value.createdAt)
	}

	private fun acceptedLocationRecord(value: LocationSample): JSONObject = JSONObject().apply {
		put(RECORD_TYPE, "accepted_location_sample")
		put("id", value.id)
		put("timeMs", value.timeMs)
		put("elapsedRealtimeNanos", value.elapsedRealtimeNanos)
		putNullable("latE7", value.latE7)
		putNullable("lonE7", value.lonE7)
		putFinite("altitudeM", value.altitudeM)
		putFinite("rawGpsAltitudeM", value.rawGpsAltitudeM)
		putFinite("horizontalAccuracyM", value.hAccM)
		putFinite("verticalAccuracyM", value.vAccM)
		putFinite("speedMps", value.speedMps)
		putFinite("speedAccuracyMps", value.speedAccuracyMps)
		put("provider", value.provider)
		put("quality", value.quality.name)
		putNullable("motionState", value.motionState?.name)
		putNullable("policy", value.policy)
		putNullable("bucketId", value.bucketId)
		put("createdAtMs", value.createdAt)
		put("receivedElapsedRealtimeNanos", value.receivedElapsedRealtimeNanos)
		putNullable("deliveryAgeMs", value.deliveryAgeMs)
		put("acquisitionMode", value.acquisitionMode)
		put("requestPriority", value.requestPriority)
		put("permissionPrecision", value.permissionPrecision)
		put("batchIndex", value.batchIndex)
		put("batchSize", value.batchSize)
		put("isMock", value.isMock)
		put("estimatorVersion", value.estimatorVersion)
		put("calibrationVersion", value.calibrationVersion)
		// A numerical altitude without this contract is not an MSL claim. The
		// debug research payload preserves the same interpretation metadata as
		// the durable/exported sample instead of silently relabelling it.
		put("altitudeDatum", value.altitudeDatum.storageName)
		put("altitudeSource", value.altitudeSource.storageName)
		put("altitudeConversionStatus", value.altitudeConversionStatus.storageName)
		put("rawGpsAltitudeDatum", value.rawGpsAltitudeDatum.storageName)
		put("altitudeModelVersion", value.altitudeModelVersion)
	}

	private fun endRecord(counts: RecordCounts): JSONObject = JSONObject().apply {
		put(RECORD_TYPE, "end")
		put("complete", true)
		put("counts", JSONObject().apply {
			put("traceMarker", counts.marker)
			put("locationObservation", counts.locationObservation)
			put("trackerRun", counts.trackerRun)
			put("acceptedLocationSample", counts.acceptedLocationSample)
		})
	}

	private fun BufferedWriter.writeRecord(record: JSONObject) {
		write(record.toString())
		newLine()
	}

	private fun attributesObject(attributes: Map<String, String>): JSONObject = JSONObject().apply {
		attributes.toSortedMap().forEach { (key, value) -> put(key, value) }
	}

	private fun JSONObject.putNullable(key: String, value: Any?) {
		put(key, value ?: JSONObject.NULL)
	}

	private fun JSONObject.putFinite(key: String, value: Float?) {
		put(key, finiteJsonValue(value?.toDouble()))
	}

	private fun JSONObject.putFinite(key: String, value: Double?) {
		put(key, finiteJsonValue(value))
	}

	private fun finiteJsonValue(value: Double?): Any = when {
		value == null -> JSONObject.NULL
		value.isFinite() -> value
		else -> value.toString()
	}

	private fun advanceCursor(previous: Long, next: Long): Long {
		check(next > previous) { "Research trace DAO page did not advance its id cursor" }
		return next
	}

	private fun incrementSaturated(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L

	private data class SourceWatermarks(
		val locationObservationId: Long,
		val trackerRunId: Long,
	)

	private data class RecordCounts(
		val marker: Long,
		var locationObservation: Long = 0,
		var trackerRun: Long = 0,
		var acceptedLocationSample: Long = 0,
	)

	private companion object {
		const val RECORD_TYPE = "recordType"
		const val FORMAT = "tracker-research-trace-ndjson"
		const val SCHEMA_VERSION = 3
		const val DEFAULT_PAGE_SIZE = 1_000
	}
}
