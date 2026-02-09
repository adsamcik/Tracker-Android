package com.adsamcik.tracker.impexp.importer.file

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.adsamcik.tracker.impexp.importer.FileImportStream
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.MutableTrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

/**
 * Imports JSON files produced by [com.adsamcik.tracker.impexp.exporter.JsonExporter].
 *
 * Streaming reader — processes locations one at a time via [JsonReader]
 * to avoid loading the entire file into memory.
 */
internal class JsonImport : FileImport {
	override val supportedExtensions: Collection<String> = listOf("json")

	override suspend fun import(
		context: Context,
		database: AppDatabase,
		stream: FileImportStream,
	) = withContext(Dispatchers.IO) {
		val reader = JsonReader(InputStreamReader(stream, Charsets.UTF_8))
		reader.isLenient = true

		reader.beginObject()
		while (reader.hasNext()) {
			when (reader.nextName()) {
				"schema" -> reader.nextInt() // consume; forward-compatible
				"exportedAt" -> reader.nextLong()
				"dateRangeStart" -> reader.nextLong()
				"dateRangeEnd" -> reader.nextLong()
				"locations" -> importLocations(reader, database)
				"sessions" -> importSessions(reader, database)
				"segments" -> skipArray(reader) // segments can be regenerated
				else -> reader.skipValue()
			}
		}
		reader.endObject()
	}

	private fun importLocations(reader: JsonReader, database: AppDatabase) {
		val locationDao = database.locationDao()
		val batch = mutableListOf<DatabaseLocation>()

		reader.beginArray()
		while (reader.hasNext()) {
			val loc = readLocation(reader)
			if (loc != null) {
				batch.add(loc)
				if (batch.size >= BATCH_SIZE) {
					locationDao.insert(batch)
					batch.clear()
				}
			}
		}
		reader.endArray()

		if (batch.isNotEmpty()) {
			locationDao.insert(batch)
		}
	}

	private fun readLocation(reader: JsonReader): DatabaseLocation? {
		var time = 0L
		var lat = 0.0
		var lon = 0.0
		var alt: Double? = null
		var speed: Float? = null
		var accuracy: Float? = null
		var activityType = 0
		var activityConf = 0

		reader.beginObject()
		while (reader.hasNext()) {
			when (reader.nextName()) {
				"time" -> time = reader.nextLong()
				"lat" -> lat = reader.nextDouble()
				"lon" -> lon = reader.nextDouble()
				"alt" -> alt = readNullableDouble(reader)
				"spd" -> speed = readNullableDouble(reader)?.toFloat()
				"acc" -> accuracy = readNullableDouble(reader)?.toFloat()
				"act" -> activityType = reader.nextInt()
				"actConf" -> activityConf = reader.nextInt()
				else -> reader.skipValue()
			}
		}
		reader.endObject()

		if (time == 0L) return null

		val location = Location(time, lat, lon, alt, accuracy, null, speed, null)
		return DatabaseLocation(location, ActivityInfo(activityType, activityConf))
	}

	private fun readNullableDouble(reader: JsonReader): Double? {
		return if (reader.peek() == JsonToken.NULL) {
			reader.nextNull()
			null
		} else {
			reader.nextDouble()
		}
	}

	private fun importSessions(reader: JsonReader, database: AppDatabase) {
		val sessionDao = database.sessionDao()

		reader.beginArray()
		while (reader.hasNext()) {
			val session = readSession(reader)
			if (session != null) {
				sessionDao.insert(session)
			}
		}
		reader.endArray()
	}

	private fun readSession(reader: JsonReader): MutableTrackerSession? {
		var start = 0L
		var end = 0L
		var collections = 0
		var distanceInM = 0f
		var isUserInitiated = true
		var steps: Int? = null

		reader.beginObject()
		while (reader.hasNext()) {
			when (reader.nextName()) {
				"id" -> reader.nextLong() // skip — auto-generated on insert
				"start" -> start = reader.nextLong()
				"end" -> end = reader.nextLong()
				"collections" -> collections = reader.nextInt()
				"distanceInM" -> distanceInM = reader.nextDouble().toFloat()
				"isUserInitiated" -> isUserInitiated = reader.nextBoolean()
				"steps" -> steps = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.nextInt()
				else -> reader.skipValue()
			}
		}
		reader.endObject()

		if (start == 0L) return null

		val session = MutableTrackerSession(start = start, isUserInitiated = isUserInitiated)
		session.end = end
		session.collections = collections
		session.distanceInM = distanceInM
		session.steps = steps ?: 0
		return session
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
	}
}
