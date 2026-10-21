package com.adsamcik.tracker.impexp.portable

import androidx.room.withTransaction
import com.adsamcik.tracker.impexp.importer.ImportResult
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.AllDaySensorObservationIdentity
import com.adsamcik.tracker.shared.base.database.data.ObservationStampColumns
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Explicit file transfers reuse the native rows and identities; they grant no sensor authority. */
internal object AllDaySensorPortableRows {
    suspend fun forEachSelected(database: AppDatabase, source: String, identities: List<String>,
        dates: LongRange, epoch: Long, emit: suspend (JsonObject) -> Unit): Unit = database.withTransaction {
        require(identities.isNotEmpty() && identities.size <= AllDaySensorSourceFile.MAX_OBSERVATIONS)
        val state = requireNotNull(database.sourceEvidenceStateDao().get())
        check(state.collectedDataEpoch == epoch) { "Recorded data was cleared" }
        val floor = maxOf(dates.first, state.retainedFromMs ?: 0L)
        for (ids in identities.chunked(SENSOR_ROW_PAGE_SIZE)) {
            currentCoroutineContext().ensureActive()
            val rows = if (source == "ACTIVITY") {
                database.activitySnapshotDao().bySourceSignalIds(ids).map(::activityJson)
            } else {
                database.pressureSampleDao().bySourceSignalIds(ids).map(::pressureJson)
            }
            check(rows.map { it.getValue("id").jsonPrimitive.content }.toSet() == ids.toSet()) {
                "Saved observations changed"
            }
            for (row in rows) {
                if (row.getValue("timeMs").jsonPrimitive.long in floor..dates.last) {
                    emit(row)
                }
            }
        }
    }

	fun validate(source: String, rows: List<JsonObject>): Unit = try {
		require(rows.size <= AllDaySensorSourceFile.MAX_OBSERVATIONS)
		when (source) {
			"ACTIVITY" -> rows.forEach { activityRow(it) }
			"PRESSURE" -> rows.forEach { pressureRow(it) }
			else -> error("Unsupported sensor source")
		}
	} catch (failure: IllegalArgumentException) {
		throw SensorSourceFileFormatException(failure)
	} catch (failure: IllegalStateException) {
		throw SensorSourceFileFormatException(failure)
	} catch (failure: NoSuchElementException) {
		throw SensorSourceFileFormatException(failure)
	}
    suspend fun hasAny(database: AppDatabase, source: String, from: Long, to: Long): Boolean =
        database.withTransaction {
            val floor = maxOf(from, database.sourceEvidenceStateDao().get()?.retainedFromMs ?: 0L)
            if (source == "ACTIVITY") {
                database.activitySnapshotDao().allDayBetween(floor, to, 1).isNotEmpty()
            } else {
                database.pressureSampleDao().allDayBetween(floor, to, 1).isNotEmpty()
            }
        }

    /** Phone-local staging is paged inside one finite Room snapshot; no external destination is held. */
    suspend fun forEach(database: AppDatabase, source: String, from: Long, to: Long,
        emit: suspend (JsonObject) -> Unit): Unit = database.withTransaction {
        val floor = maxOf(from, database.sourceEvidenceStateDao().get()?.retainedFromMs ?: 0L)
        var after = 0L
        var count = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = if (source == "ACTIVITY") {
                database.activitySnapshotDao().allDayPage(floor, to, after, SENSOR_ROW_PAGE_SIZE)
                    .map { it.id to activityJson(it) }
            } else {
                database.pressureSampleDao().allDayPage(floor, to, after, SENSOR_ROW_PAGE_SIZE)
                    .map { it.id to pressureJson(it) }
            }
            if (page.isEmpty()) {
                break
            }
            for ((id, row) in page) {
                require(++count <= AllDaySensorSourceFile.MAX_OBSERVATIONS)
                emit(row)
                after = id
            }
        }
    }

    suspend fun import(database: AppDatabase, source: String, file: SensorSourceFileInput, epoch: Long): ImportResult {
        if (file.observationCount == 0) {
            return ImportResult.EMPTY
        }
        return importRows(database, source, epoch) { consume -> file.forEachObservation(consume) }
    }

    private suspend fun importRows(database: AppDatabase, source: String, epoch: Long,
        produce: suspend (suspend (JsonObject) -> Unit) -> Unit): ImportResult = database.withTransaction {
        val state = database.sourceEvidenceStateDao().get()
        if (state == null || state.collectedDataEpoch != epoch) {
            return@withTransaction ImportResult(failedCount = 1,
                errors = listOf("Import stopped because recorded data was cleared"))
        }
        var result = ImportResult.EMPTY
        produce { json ->
            currentCoroutineContext().ensureActive()
            val time = json.getValue("timeMs").jsonPrimitive.long
            val observed = if (state.retainedFromMs?.let { time < it } == true) {
                ImportResult(skippedCount = 1)
            } else if (source == "ACTIVITY") {
                val row = activityRow(json)
                val existing = database.activitySnapshotDao().getBySourceSignalId(requireNotNull(row.sourceSignalId))
                if (existing == null) {
                    check(database.activitySnapshotDao().insert(row) != -1L)
                    ImportResult(successCount = 1)
                } else {
                    duplicateResult(activityJson(existing), activityJson(row))
                }
            } else {
                val row = pressureRow(json)
                val existing = database.pressureSampleDao().getBySourceSignalId(requireNotNull(row.sourceSignalId))
                if (existing == null) {
                    check(database.pressureSampleDao().insert(row) != -1L)
                    ImportResult(successCount = 1)
                } else {
                    duplicateResult(pressureJson(existing), pressureJson(row))
                }
            }
            result = (result + observed).let { it.copy(errors = it.errors.distinct()) }
        }
        result
    }
}

private fun duplicateResult(existing: JsonObject, incoming: JsonObject): ImportResult =
	if (existing - "stamp" == incoming - "stamp" && withoutImportFlag(existing) == withoutImportFlag(incoming)) {
		ImportResult(skippedCount = 1)
	} else {
        ImportResult(failedCount = 1, errors = listOf(
            "An observation with this ID already has different values; the saved data was kept."))
    }

private fun withoutImportFlag(row: JsonObject): JsonObject = JsonObject(row.getValue("stamp").jsonObject
	.toMutableMap().apply { this["capabilityFlags"] = JsonPrimitive(this["capabilityFlags"]
		?.takeUnless { it == JsonNull }?.jsonPrimitive?.content.orEmpty().split(',')
		.filter { it.isNotBlank() && it != AllDaySensorObservationIdentity.IMPORTED_CAPABILITY }
		.sorted().joinToString(",")) })

private fun activityJson(row: ActivitySnapshot): JsonObject = buildJsonObject {
	put("id", requireNotNull(row.sourceSignalId)); put("timeMs", row.timeMs)
	put("activityType", row.activityType); put("confidence", row.confidence.takeUnless { row.isTransition })
	put("isTransition", row.isTransition); put("createdAt", row.createdAt)
	put("stamp", stampJson(row.observationStamp))
}

private fun pressureJson(row: PressureSample): JsonObject = buildJsonObject {
	put("id", requireNotNull(row.sourceSignalId)); put("timeMs", row.timeMs)
	put("elapsedRealtimeNanos", row.elapsedRealtimeNanos); put("pressureHpa", row.pressureHpa)
	val altitudeKnown = AllDaySensorObservationIdentity.UNKNOWN_ALTITUDE_CAPABILITY !in
		row.observationStamp.capabilityFlags.orEmpty().split(',')
	put("altitudeM", row.altitudeM.takeIf { altitudeKnown && it.isFinite() }); put("createdAt", row.createdAt)
	put("sampleCount", row.sampleCount); put("minimumHpa", row.minPressureHpa)
	put("maximumHpa", row.maxPressureHpa); put("standardDeviationHpa", row.standardDeviationHpa)
	put("windowStartNanos", row.windowStartElapsedRealtimeNanos)
	put("windowEndNanos", row.windowEndElapsedRealtimeNanos)
	put("stamp", stampJson(row.observationStamp))
}

private fun activityRow(json: JsonObject): ActivitySnapshot {
	val id = json.getValue("id").jsonPrimitive.content
	require(AllDaySensorObservationIdentity.activityEventId(id) != null && id.length <= MAX_ID_LENGTH)
	val time = json.getValue("timeMs").jsonPrimitive.long
	val transition = json.getValue("isTransition").jsonPrimitive.boolean
	val confidence = json["confidence"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.int
		?: if (transition) {
            0
        } else {
            throw IllegalArgumentException("Activity confidence is missing")
        }
	require(time >= 0L && confidence in 0..100)
	return ActivitySnapshot(timeMs = time, activityType = json.getValue("activityType").jsonPrimitive.int,
		confidence = confidence, isTransition = transition,
		createdAt = json.getValue("createdAt").jsonPrimitive.long.also { require(it >= 0L) },
		sourceSignalId = id, observationStamp = stampRow(json.getValue("stamp").jsonObject))
}

private fun pressureRow(json: JsonObject): PressureSample {
	val id = json.getValue("id").jsonPrimitive.content
	require(AllDaySensorObservationIdentity.pressureEventId(id) != null && id.length <= MAX_ID_LENGTH)
	val time = json.getValue("timeMs").jsonPrimitive.long
	val mean = json.getValue("pressureHpa").jsonPrimitive.float
	val count = json.getValue("sampleCount").jsonPrimitive.int
	require(time >= 0L && mean.isFinite() && mean > 0f && count > 0)
	val start = json.longOrNull("windowStartNanos")
	val end = json.longOrNull("windowEndNanos")
	validatePressureWindow(start, end)
	val minimum = json.floatOrNull("minimumHpa")
	val maximum = json.floatOrNull("maximumHpa")
	val deviation = json.floatOrNull("standardDeviationHpa")
	validatePressureRange(minimum, maximum, deviation)
	val altitude = json.floatOrNull("altitudeM")
	val stamp = stampRow(json.getValue("stamp").jsonObject).let {
		if (altitude != null) {
            it
        } else {
            it.copy(capabilityFlags =
                (it.capabilityFlags.orEmpty().split(',') + AllDaySensorObservationIdentity.UNKNOWN_ALTITUDE_CAPABILITY)
                    .filter(String::isNotBlank).distinct().sorted().joinToString(","))
        }
	}
	return PressureSample(timeMs = time,
		elapsedRealtimeNanos = json.getValue("elapsedRealtimeNanos").jsonPrimitive.long.also { require(it >= 0L) },
		pressureHpa = mean, altitudeM = altitude ?: 0f, bucketId = null,
		createdAt = json.getValue("createdAt").jsonPrimitive.long.also { require(it >= 0L) }, sourceSignalId = id,
		sampleCount = count, minPressureHpa = minimum, maxPressureHpa = maximum,
		standardDeviationHpa = deviation,
		windowStartElapsedRealtimeNanos = start,
		windowEndElapsedRealtimeNanos = end,
		observationStamp = stamp)
}

private const val MAX_ID_LENGTH = 4_096
private const val SENSOR_ROW_PAGE_SIZE = 500

private fun validatePressureWindow(start: Long?, end: Long?) {
    require((start == null) == (end == null))
    require(start == null || start >= 0L && requireNotNull(end) >= start)
}

private fun validatePressureRange(minimum: Float?, maximum: Float?, deviation: Float?) {
    require(minimum == null || minimum > 0f)
    require(maximum == null || maximum > 0f)
    require(minimum == null || maximum == null || maximum >= minimum)
    require(deviation == null || deviation >= 0f)
}


private fun stampJson(stamp: ObservationStampColumns): JsonObject = buildJsonObject {
	put("sourceTimeMs", stamp.sourceTimeMs); put("sourceElapsedNanos", stamp.sourceElapsedRealtimeNanos)
	put("sourceFirstElapsedNanos", stamp.sourceFirstElapsedRealtimeNanos)
	put("receivedTimeMs", stamp.receivedTimeMs); put("receivedElapsedNanos", stamp.receivedElapsedRealtimeNanos)
	put("sourceSequence", stamp.sourceSequence); put("sourceFirstSequence", stamp.sourceFirstSequence)
	put("clockDomainId", stamp.clockDomainId); put("bootClockDomainId", stamp.bootClockDomainId)
	put("sourceAgeMs", stamp.sourceAgeMs); put("timeUncertaintyMs", stamp.timeUncertaintyMs)
	put("capabilityFlags", stamp.capabilityFlags); put("permissionPrecision", stamp.permissionPrecision)
}

private fun stampRow(json: JsonObject): ObservationStampColumns = ObservationStampColumns(
	sourceTimeMs = json.longOrNull("sourceTimeMs"), sourceElapsedRealtimeNanos = json.longOrNull("sourceElapsedNanos"),
	sourceFirstElapsedRealtimeNanos = json.longOrNull("sourceFirstElapsedNanos"),
    receivedTimeMs = json.longOrNull("receivedTimeMs"),
	receivedElapsedRealtimeNanos = json.longOrNull("receivedElapsedNanos"),
    sourceSequence = json.longOrNull("sourceSequence"),
	sourceFirstSequence = json.longOrNull("sourceFirstSequence"), clockDomainId = json.textOrNull("clockDomainId"),
	bootClockDomainId = json.textOrNull("bootClockDomainId"), sourceAgeMs = json.longOrNull("sourceAgeMs"),
	timeUncertaintyMs = json.longOrNull("timeUncertaintyMs"), permissionPrecision = json.textOrNull("permissionPrecision"),
	capabilityFlags = (json.textOrNull("capabilityFlags").orEmpty().split(',').filter(String::isNotBlank) +
		AllDaySensorObservationIdentity.IMPORTED_CAPABILITY).distinct().sorted().joinToString(","),
)

private fun JsonObject.longOrNull(key: String): Long? = this[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.long
private fun JsonObject.floatOrNull(key: String): Float? = this[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.float
	?.also { require(it.isFinite()) }
private fun JsonObject.textOrNull(key: String): String? = this[key]?.takeUnless { it == JsonNull }
    ?.jsonPrimitive?.content?.also { require(it.length <= MAX_ID_LENGTH) }
