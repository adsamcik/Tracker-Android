package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

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
		ImportResult(successCount = successCount)
	}

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
					sampleDao.insert(batch)
					batch.clear()
				}
			}
		}
		reader.endArray()

		if (batch.isNotEmpty()) {
			sampleDao.insert(batch)
		}
		return count
	}

	private fun readLocationAsSample(reader: JsonReader): LocationSample? {
		var time = 0L
		var lat = 0.0
		var lon = 0.0
		var alt: Double? = null
		var speed: Float? = null
		var accuracy: Float? = null

		reader.beginObject()
		while (reader.hasNext()) {
			when (reader.nextName()) {
				"time" -> time = reader.nextLong()
				"lat" -> lat = reader.nextDouble()
				"lon" -> lon = reader.nextDouble()
				"alt" -> alt = readNullableDouble(reader)
				"spd" -> speed = readNullableDouble(reader)?.toFloat()
				"acc" -> accuracy = readNullableDouble(reader)?.toFloat()
				"act" -> reader.nextInt()
				"actConf" -> reader.nextInt()
				else -> reader.skipValue()
			}
		}
		reader.endObject()

		if (time == 0L) return null
		if (time < MIN_VALID_TIMESTAMP || time > MAX_VALID_TIMESTAMP) return null
		if (lat < -90.0 || lat > 90.0) return null
		if (lon < -180.0 || lon > 180.0) return null

		return LocationSample(
			timeMs = time,
			elapsedRealtimeNanos = 0L,
			latE7 = (lat * 1e7).toInt(),
			lonE7 = (lon * 1e7).toInt(),
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
				"start" -> start = reader.nextLong()
				"end" -> end = reader.nextLong()
				"collections" -> collections = reader.nextInt()
				"distanceInM" -> distanceInM = reader.nextDouble().toFloat()
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
			source = SegmentSource.USER_CREATED,
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
