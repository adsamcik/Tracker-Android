package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.WifiObservation
import com.adsamcik.tracker.shared.base.mapper.toEntity
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.SampleQuality
import com.adsamcik.tracker.shared.model.SegmentSource
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import kotlin.math.roundToInt

/**
 * Imports JSON files produced by [com.adsamcik.tracker.impexp.exporter.JsonExporter].
 *
 * Streaming reader — processes locations one at a time via [JsonReader]
 * to avoid loading the entire file into memory.
 *
 * Session IDs are reassigned on import. This allows merging data from
 * multiple devices but breaks references to specific session IDs.
 */
internal class JsonImport(
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) : FileImport {
	override val supportedExtensions: Collection<String> = listOf("json")

	override suspend fun import(
		context: Context,
		database: AppDatabase,
		stream: FileImportStream,
	): ImportResult = withContext(dispatchers.io) {
		var successCount = 0
		JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
			reader.isLenient = true

			if (reader.peek() == JsonToken.BEGIN_ARRAY) {
				successCount += importSessionRecords(reader, database)
			} else {
				reader.beginObject()
				while (reader.hasNext()) {
					when (reader.nextName()) {
						"schema" -> {
							val version = reader.nextInt()
							require(version in 1..CURRENT_SCHEMA_VERSION) {
								"Unsupported JSON schema version: $version"
							}
						}
						"exportedAt" -> reader.nextLong()
						"dateRangeStart" -> reader.nextLong()
						"dateRangeEnd" -> reader.nextLong()
						"locations" -> successCount += importLocations(reader, database)
						"sessions" -> successCount += importSessions(reader, database)
						"segments" -> skipArray(reader) // segments can be regenerated
						else -> reader.skipValue()
					}
				}
				reader.endObject()
			}
		}
		ImportResult(successCount = successCount)
	}

	private suspend fun importSessionRecords(reader: JsonReader, database: AppDatabase): Int {
		var count = 0
		reader.beginArray()
		while (reader.hasNext()) {
			reader.beginObject()
			while (reader.hasNext()) {
				when (reader.nextName()) {
					"schemaVersion" -> {
						require(reader.nextInt() == 2) { "Unsupported JSON session schema" }
					}
					"session" -> readSessionAsSegment(reader)?.let {
						database.sessionSegmentDao().insert(it)
						count++
					}
					"locations" -> count += importLocations(reader, database)
					"wifiObservations" -> count += importWifiObservations(reader, database)
					"cellSamples" -> count += importCellSamples(reader, database)
					else -> reader.skipValue()
				}
			}
			reader.endObject()
		}
		reader.endArray()
		return count
	}

	private suspend fun importWifiObservations(reader: JsonReader, database: AppDatabase): Int {
		val dao = database.wifiObservationDao()
		val batch = mutableListOf<WifiObservation>()
		var count = 0
		reader.beginArray()
		while (reader.hasNext()) {
			readWifiObservation(reader)?.let { observation ->
				batch.add(observation)
				count++
				if (batch.size >= BATCH_SIZE) {
					dao.insert(batch)
					batch.clear()
				}
			}
		}
		reader.endArray()
		if (batch.isNotEmpty()) dao.insert(batch)
		return count
	}

	private fun readWifiObservation(reader: JsonReader): WifiObservation? {
		var timeMs = 0L
		var bssid = ""
		var ssid = ""
		var capabilities = ""
		var frequencyMhz = 0
		var levelDbm = 0
		var latitude: Double? = null
		var longitude: Double? = null
		var provenance = CoordinateProvenance.UNKNOWN
		reader.beginObject()
		while (reader.hasNext()) {
			when (reader.nextName()) {
				"timeMs" -> timeMs = reader.nextLong()
				"bssid" -> bssid = reader.nextString()
				"ssid" -> ssid = reader.nextString()
				"capabilities" -> capabilities = reader.nextString()
				"frequencyMhz" -> frequencyMhz = reader.nextInt()
				"levelDbm" -> levelDbm = reader.nextInt()
				"latitude" -> latitude = readNullableDouble(reader)
				"longitude" -> longitude = readNullableDouble(reader)
				"coordinateProvenance" -> provenance = readCoordinateProvenance(reader)
				else -> reader.skipValue()
			}
		}
		reader.endObject()
		if (timeMs !in MIN_VALID_TIMESTAMP..MAX_VALID_TIMESTAMP || bssid.isBlank()) return null
		return WifiObservation(
			timeMs = timeMs,
			bssid = bssid,
			ssid = ssid,
			capabilities = capabilities,
			frequency = frequencyMhz,
			level = levelDbm,
			latE7 = latitude?.takeIf(Double::isFinite)?.times(1e7)?.roundToInt(),
			lonE7 = longitude?.takeIf(Double::isFinite)?.times(1e7)?.roundToInt(),
			provenance = provenance,
			createdAt = System.currentTimeMillis(),
		)
	}

	private suspend fun importCellSamples(reader: JsonReader, database: AppDatabase): Int {
		val dao = database.cellSampleDao()
		val batch = mutableListOf<CellSample>()
		var count = 0
		reader.beginArray()
		while (reader.hasNext()) {
			readCellSample(reader)?.let { sample ->
				batch.add(sample)
				count++
				if (batch.size >= BATCH_SIZE) {
					dao.insert(batch)
					batch.clear()
				}
			}
		}
		reader.endArray()
		if (batch.isNotEmpty()) dao.insert(batch)
		return count
	}

	private fun readCellSample(reader: JsonReader): CellSample? {
		var timeMs = 0L
		var cellId = 0L
		var lac = 0
		var mcc = 0
		var mnc = 0
		var networkType = 0
		var signalStrength = 0
		var latitude: Double? = null
		var longitude: Double? = null
		var provenance = CoordinateProvenance.UNKNOWN
		reader.beginObject()
		while (reader.hasNext()) {
			when (reader.nextName()) {
				"timeMs" -> timeMs = reader.nextLong()
				"cellId" -> cellId = reader.nextLong()
				"lac" -> lac = reader.nextInt()
				"mcc" -> mcc = reader.nextInt()
				"mnc" -> mnc = reader.nextInt()
				"networkType" -> networkType = reader.nextInt()
				"signalStrength" -> signalStrength = reader.nextInt()
				"latitude" -> latitude = readNullableDouble(reader)
				"longitude" -> longitude = readNullableDouble(reader)
				"coordinateProvenance" -> provenance = readCoordinateProvenance(reader)
				else -> reader.skipValue()
			}
		}
		reader.endObject()
		if (timeMs !in MIN_VALID_TIMESTAMP..MAX_VALID_TIMESTAMP) return null
		return CellSample(
			timeMs = timeMs,
			cellId = cellId,
			lac = lac,
			mcc = mcc,
			mnc = mnc,
			networkType = networkType,
			signalStrength = signalStrength,
			latE7 = latitude?.takeIf(Double::isFinite)?.times(1e7)?.roundToInt(),
			lonE7 = longitude?.takeIf(Double::isFinite)?.times(1e7)?.roundToInt(),
			provenance = provenance,
			createdAt = System.currentTimeMillis(),
		)
	}

	private fun readCoordinateProvenance(reader: JsonReader): CoordinateProvenance =
		runCatching { CoordinateProvenance.valueOf(reader.nextString()) }
			.getOrDefault(CoordinateProvenance.UNKNOWN)

	private suspend fun importLocations(reader: JsonReader, database: AppDatabase): Int {
		val sampleDao = database.locationSampleDao()
		val batch = mutableListOf<LocationSample>()
		var count = 0

		reader.beginArray()
		while (reader.hasNext()) {
			val sample = readLocationAsSample(reader)
			if (sample != null) {
				batch.add(sample)
				count++
				if (batch.size >= BATCH_SIZE) {
					sampleDao.insert(batch.map { it.toEntity() })
					batch.clear()
				}
			}
		}
		reader.endArray()

		if (batch.isNotEmpty()) {
			sampleDao.insert(batch.map { it.toEntity() })
		}
		return count
	}

	private fun readLocationAsSample(reader: JsonReader): LocationSample? {
		var time = 0L
		var lat: Double? = null
		var lon: Double? = null
		var alt: Double? = null
		var speed: Float? = null
		var accuracy: Float? = null

		reader.beginObject()
		while (reader.hasNext()) {
			when (reader.nextName()) {
				"time", "timeMs" -> time = reader.nextLong()
				"lat", "latitude" -> lat = reader.nextDouble()
				"lon", "longitude" -> lon = reader.nextDouble()
				"alt", "altitudeM" -> alt = readNullableDouble(reader)
				"spd", "speedMps" -> speed = readNullableDouble(reader)?.toFloat()
				"acc", "horizontalAccuracyM" -> accuracy = readNullableDouble(reader)?.toFloat()
				"act" -> reader.nextInt()
				"actConf" -> reader.nextInt()
				else -> reader.skipValue()
			}
		}
		reader.endObject()

		if (time == 0L) return null
		if (time < MIN_VALID_TIMESTAMP || time > MAX_VALID_TIMESTAMP) return null
		val coordinates = lat?.takeIf { it.isFinite() && it in -90.0..90.0 }
			?.let { validLatitude ->
				lon?.takeIf { it.isFinite() && it in -180.0..180.0 }
					?.let { validLongitude -> validLatitude to validLongitude }
			}

		return LocationSample(
			timeMs = time,
			elapsedRealtimeNanos = 0L,
			latE7 = coordinates?.first?.times(1e7)?.roundToInt(),
			lonE7 = coordinates?.second?.times(1e7)?.roundToInt(),
			altitudeM = alt?.toFloat(),
			rawGpsAltitudeM = null,
			hAccM = accuracy,
			vAccM = null,
			speedMps = speed,
			speedAccuracyMps = null,
			provider = "import",
			quality = SampleQuality.MEDIUM,
			motionState = null,
			policy = null,
			bucketId = null,
			createdAt = System.currentTimeMillis(),
		)
	}

	private fun readNullableDouble(reader: JsonReader): Double? {
		return if (reader.peek() == JsonToken.NULL) {
			reader.nextNull()
			null
		} else {
			reader.nextDouble()
		}
	}

	private suspend fun importSessions(reader: JsonReader, database: AppDatabase): Int {
		val segmentDao = database.sessionSegmentDao()
		var count = 0

		reader.beginArray()
		while (reader.hasNext()) {
			val segment = readSessionAsSegment(reader)
			if (segment != null) {
				segmentDao.insert(segment)
				count++
			}
		}
		reader.endArray()
		return count
	}

	private fun readSessionAsSegment(reader: JsonReader): SessionSegment? {
		var start = 0L
		var end = 0L
		var collections = 0
		var distanceInM = 0f
		var steps: Int? = null

		reader.beginObject()
		while (reader.hasNext()) {
			when (reader.nextName()) {
				"id" -> reader.nextLong()
				"start", "startTimeMs" -> start = reader.nextLong()
				"end", "endTimeMs" -> end = reader.nextLong()
				"collections", "sampleCount" -> collections = reader.nextInt()
				"distanceInM", "distanceM" -> distanceInM = reader.nextDouble().toFloat()
				"isUserInitiated" -> reader.nextBoolean()
				"steps" -> steps = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.nextInt()
				else -> reader.skipValue()
			}
		}
		reader.endObject()

		if (start == 0L) return null
		if (start < MIN_VALID_TIMESTAMP || start > MAX_VALID_TIMESTAMP) return null
		if (end < start) return null

		return SessionSegment(
			startTimeMs = start,
			endTimeMs = end,
			distanceM = distanceInM,
			steps = steps,
			primaryActivity = null,
			activityConfidence = null,
			sampleCount = collections,
			source = SegmentSource.USER_CREATED.toEntity(),
			inferenceVersion = null,
			createdAt = System.currentTimeMillis(),
		)
	}

	private fun skipArray(reader: JsonReader) {
		reader.beginArray()
		while (reader.hasNext()) {
			reader.skipValue()
		}
		reader.endArray()
	}

	companion object {
		private const val BATCH_SIZE = 200
		private const val CURRENT_SCHEMA_VERSION = 1
		// 2010-01-01 00:00:00 UTC
		private const val MIN_VALID_TIMESTAMP = 1_262_304_000_000L
		// 2100-01-01 00:00:00 UTC
		private const val MAX_VALID_TIMESTAMP = 4_102_444_800_000L
	}
}
