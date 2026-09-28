package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.MotionState
import com.adsamcik.tracker.shared.model.SampleQuality
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import org.json.JSONException
import org.json.JSONObject

/** One saved point and its measured values. Origin describes the file; it grants no app authority. */
internal data class LocationFilePointV1(
	val pointId: String,
	val origin: LocationFileOrigin,
	val measurement: LocationFileMeasurementV1,
) {
	init {
		require(POINT_ID.matches(pointId))
	}
}

private val POINT_ID = Regex("[A-Za-z0-9._:-]{1,128}")

internal enum class LocationFileOrigin { SESSION, BACKGROUND, BOTH, UNKNOWN }

/** The saved point values that remain useful after transfer to another installation. */
@Suppress("LongParameterList")
internal data class LocationFileMeasurementV1(
	val timeMs: Long,
	val latE7: Int?,
	val lonE7: Int?,
	val altitudeM: Float?,
	val rawGpsAltitudeM: Float?,
	val horizontalAccuracyM: Float?,
	val verticalAccuracyM: Float?,
	val speedMps: Float?,
	val speedAccuracyMps: Float?,
	val rawSpeedMps: Float?,
	val rawSpeedAccuracyMps: Float?,
	val bearingDeg: Float?,
	val bearingAccuracyDeg: Float?,
	val provider: String,
	val quality: SampleQuality,
	val motionState: MotionState?,
	val permissionPrecision: String,
	val isMock: Boolean,
	val altitudeDatum: AltitudeDatum,
	val altitudeSource: AltitudeSource,
	val altitudeConversionStatus: AltitudeConversionStatus,
	val rawGpsAltitudeDatum: AltitudeDatum,
) {
	init {
		require(latE7 == null || latE7 in -900_000_000..900_000_000)
		require(lonE7 == null || lonE7 in -1_800_000_000..1_800_000_000)
		require(provider.length <= LocationFileJsonV1.MAX_POINT_TEXT_LENGTH)
		require(permissionPrecision.length <= LocationFileJsonV1.MAX_POINT_TEXT_LENGTH)
	}

	companion object {
		fun from(sample: LocationSample): LocationFileMeasurementV1 = LocationFileMeasurementV1(
			timeMs = sample.timeMs,
			latE7 = sample.latE7,
			lonE7 = sample.lonE7,
			altitudeM = sample.altitudeM,
			rawGpsAltitudeM = sample.rawGpsAltitudeM,
			horizontalAccuracyM = sample.hAccM,
			verticalAccuracyM = sample.vAccM,
			speedMps = sample.speedMps,
			speedAccuracyMps = sample.speedAccuracyMps,
			rawSpeedMps = sample.rawPlatformSpeedMps,
			rawSpeedAccuracyMps = sample.rawPlatformSpeedAccuracyMps,
			bearingDeg = sample.bearingDeg,
			bearingAccuracyDeg = sample.bearingAccuracyDeg,
			provider = sample.provider,
			quality = sample.quality,
			motionState = sample.motionState,
			permissionPrecision = sample.permissionPrecision,
			isMock = sample.isMock,
			altitudeDatum = sample.altitudeDatum,
			altitudeSource = sample.altitudeSource,
			altitudeConversionStatus = sample.altitudeConversionStatus,
			rawGpsAltitudeDatum = sample.rawGpsAltitudeDatum,
		)
	}
}

internal class InvalidLocationFile(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Line-delimited JSON keeps large date ranges streamable and leaves user-edited files readable. */
internal class LocationFileJsonV1 {
	fun writer(output: OutputStream): Writer = Writer(output)

	class Writer(output: OutputStream) {
		private val sink = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))
		private var finished = false
		private var pointCount = 0
		private var byteCount = 0L

		init {
			writeLine(JSONObject().put("format", FORMAT).put("version", VERSION).toString())
		}

		fun point(value: LocationFilePointV1) {
			check(!finished)
			if (pointCount >= MAX_POINTS) throw InvalidLocationFile("Too many Location points")
			val record = JSONObject()
				.put("type", "point")
				.put("id", value.pointId)
				.put("origin", value.origin.name.lowercase())
				.put("timeMs", value.measurement.timeMs)
				.put("provider", value.measurement.provider)
				.put("quality", value.measurement.quality.name.lowercase())
				.put("permissionPrecision", value.measurement.permissionPrecision)
				.put("isMock", value.measurement.isMock)
				.put("altitudeDatum", value.measurement.altitudeDatum.storageName)
				.put("altitudeSource", value.measurement.altitudeSource.storageName)
				.put("altitudeConversionStatus", value.measurement.altitudeConversionStatus.storageName)
				.put("rawGpsAltitudeDatum", value.measurement.rawGpsAltitudeDatum.storageName)
			value.measurement.run {
				record.putNullable("latE7", latE7)
				record.putNullable("lonE7", lonE7)
				record.putFloat("altitudeM", altitudeM)
				record.putFloat("rawGpsAltitudeM", rawGpsAltitudeM)
				record.putFloat("horizontalAccuracyM", horizontalAccuracyM)
				record.putFloat("verticalAccuracyM", verticalAccuracyM)
				record.putFloat("speedMps", speedMps)
				record.putFloat("speedAccuracyMps", speedAccuracyMps)
				record.putFloat("rawSpeedMps", rawSpeedMps)
				record.putFloat("rawSpeedAccuracyMps", rawSpeedAccuracyMps)
				record.putFloat("bearingDeg", bearingDeg)
				record.putFloat("bearingAccuracyDeg", bearingAccuracyDeg)
				record.putNullable("motionState", motionState?.name?.lowercase())
			}
			writeLine(record.toString())
			pointCount++
		}

		fun finish(): Int {
			check(!finished)
			writeLine(JSONObject().put("type", "end").put("pointCount", pointCount).toString())
			finished = true
			sink.flush()
			return pointCount
		}

		private fun writeLine(line: String) {
			val size = line.toByteArray(Charsets.UTF_8).size
			if (size > MAX_LINE_BYTES) throw InvalidLocationFile("Location file line is too long")
			if (byteCount > MAX_FILE_BYTES - size - 1L) {
				throw InvalidLocationFile("Location file is too large")
			}
			sink.write(line)
			sink.write("\n")
			byteCount += size + 1L
		}
	}

	/** The caller must roll back [onPoint] if the footer or any later record is invalid. */
	suspend fun read(input: InputStream, onPoint: suspend (LocationFilePointV1) -> Unit): Int {
		val lines = BoundedLocationLines(input)
		val header = lines.next() ?: throw InvalidLocationFile("Location file is empty")
		val metadata = header.parseObject()
		if (metadata.requireString("format") != FORMAT || metadata.requireInt("version") != VERSION) {
			throw InvalidLocationFile("Unsupported Location file")
		}
		var count = 0
		var ended = false
		while (true) {
			val line = lines.next() ?: break
			val record = line.parseObject()
			if (record.requireString("type") == "end") {
				if (record.requireInt("pointCount") != count || lines.next() != null) {
					throw InvalidLocationFile("Location file ended unexpectedly")
				}
				ended = true
				break
			}
			if (count >= MAX_POINTS) throw InvalidLocationFile("Too many Location points")
			if (record.requireString("type") != "point") {
				throw InvalidLocationFile("Unknown Location file record")
			}
			val point = try {
				LocationFilePointV1(
					pointId = record.requireString("id"),
					origin = enumValue<LocationFileOrigin>(record.requireString("origin")),
					measurement = LocationFileMeasurementV1(
						timeMs = record.requireLong("timeMs"),
						latE7 = record.optionalInt("latE7"),
						lonE7 = record.optionalInt("lonE7"),
						altitudeM = record.optionalFloat("altitudeM"),
						rawGpsAltitudeM = record.optionalFloat("rawGpsAltitudeM"),
						horizontalAccuracyM = record.optionalFloat("horizontalAccuracyM"),
						verticalAccuracyM = record.optionalFloat("verticalAccuracyM"),
						speedMps = record.optionalFloat("speedMps"),
						speedAccuracyMps = record.optionalFloat("speedAccuracyMps"),
						rawSpeedMps = record.optionalFloat("rawSpeedMps"),
						rawSpeedAccuracyMps = record.optionalFloat("rawSpeedAccuracyMps"),
						bearingDeg = record.optionalFloat("bearingDeg"),
						bearingAccuracyDeg = record.optionalFloat("bearingAccuracyDeg"),
						provider = record.requireString("provider"),
						quality = enumValue<SampleQuality>(record.requireString("quality")),
						motionState = record.optionalString("motionState")?.let { enumValue<MotionState>(it) },
						permissionPrecision = record.requireString("permissionPrecision"),
						isMock = record.requireBoolean("isMock"),
						altitudeDatum = altitudeDatum(record.requireString("altitudeDatum")),
						altitudeSource = altitudeSource(record.requireString("altitudeSource")),
						altitudeConversionStatus = altitudeConversionStatus(
							record.requireString("altitudeConversionStatus"),
						),
						rawGpsAltitudeDatum = altitudeDatum(record.requireString("rawGpsAltitudeDatum")),
					),
				)
			} catch (failure: IllegalArgumentException) {
				throw InvalidLocationFile("Invalid Location point", failure)
			}
			onPoint(point)
			count++
		}
		if (!ended) throw InvalidLocationFile("Location file is incomplete")
		return count
	}

	internal companion object {
		const val FORMAT = "tracker-location"
		const val VERSION = 1
		const val EXTENSION = "trackerlocation"
		const val MIME_TYPE = "application/x-ndjson"
		const val MAX_LINE_BYTES = 16_384
		const val MAX_FILE_BYTES = 256L * 1024L * 1024L
		const val MAX_POINTS = 2_000_000
		const val MAX_POINT_TEXT_LENGTH = 1_024
	}
}

private fun JSONObject.putNullable(name: String, value: Any?) {
	put(name, value ?: JSONObject.NULL)
}

private fun JSONObject.putFloat(name: String, value: Float?) {
	put(name, when {
		value == null -> JSONObject.NULL
		value.isNaN() -> "NaN"
		value == Float.POSITIVE_INFINITY -> "Infinity"
		value == Float.NEGATIVE_INFINITY -> "-Infinity"
		else -> value
	})
}

private fun String.parseObject(): JSONObject = try {
	JSONObject(this)
} catch (failure: JSONException) {
	throw InvalidLocationFile("Malformed Location file", failure)
}

private fun JSONObject.requireString(name: String): String =
	(valueOrNull(name) as? String) ?: throw InvalidLocationFile("Missing or invalid $name")

private fun JSONObject.optionalString(name: String): String? =
	valueOrNull(name)?.let { it as? String ?: throw InvalidLocationFile("Invalid $name") }

private fun JSONObject.requireBoolean(name: String): Boolean =
	(valueOrNull(name) as? Boolean) ?: throw InvalidLocationFile("Missing or invalid $name")

private fun JSONObject.requireLong(name: String): Long =
	(valueOrNull(name) as? Number)?.exactLong() ?: throw InvalidLocationFile("Missing or invalid $name")

private fun JSONObject.requireInt(name: String): Int =
	requireLong(name).takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
		?: throw InvalidLocationFile("Invalid $name")

private fun JSONObject.optionalInt(name: String): Int? = valueOrNull(name)?.let {
	val number = it as? Number ?: throw InvalidLocationFile("Invalid $name")
	number.exactLong().takeIf { value -> value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
		?: throw InvalidLocationFile("Invalid $name")
}

private fun JSONObject.optionalFloat(name: String): Float? = valueOrNull(name)?.let {
	when (it) {
		is Number -> it.toFloat().takeIf(Float::isFinite) ?: throw InvalidLocationFile("Invalid $name")
		"NaN" -> Float.NaN
		"Infinity" -> Float.POSITIVE_INFINITY
		"-Infinity" -> Float.NEGATIVE_INFINITY
		else -> throw InvalidLocationFile("Invalid $name")
	}
}

private fun JSONObject.valueOrNull(name: String): Any? =
	if (!has(name) || isNull(name)) null else get(name)

private fun Number.exactLong(): Long {
	return try {
		BigDecimal(toString()).longValueExact()
	} catch (failure: ArithmeticException) {
		throw InvalidLocationFile("Invalid whole number", failure)
	} catch (failure: NumberFormatException) {
		throw InvalidLocationFile("Invalid whole number", failure)
	}
}

private inline fun <reified T : Enum<T>> enumValue(value: String): T =
	enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
		?: throw InvalidLocationFile("Unknown Location value")

private fun altitudeDatum(value: String): AltitudeDatum = AltitudeDatum.entries.firstOrNull {
	it.storageName == value
} ?: throw InvalidLocationFile("Unknown altitude datum")

private fun altitudeSource(value: String): AltitudeSource = AltitudeSource.entries.firstOrNull {
	it.storageName == value
} ?: throw InvalidLocationFile("Unknown altitude source")

private fun altitudeConversionStatus(value: String): AltitudeConversionStatus =
	AltitudeConversionStatus.entries.firstOrNull { it.storageName == value }
		?: throw InvalidLocationFile("Unknown altitude conversion status")

/** A bounded line reader; a malformed file cannot allocate an unbounded JSON record. */
private class BoundedLocationLines(input: InputStream) {
	private val source = BufferedInputStream(input)
	private var byteCount = 0L

	fun next(): String? {
		val bytes = ByteArrayOutputStream()
		while (true) {
			val value = source.read()
			if (value != -1 && ++byteCount > LocationFileJsonV1.MAX_FILE_BYTES) {
				throw InvalidLocationFile("Location file is too large")
			}
			if (value == -1 || value == '\n'.code) {
				if (value == -1 && bytes.size() == 0) return null
				if (bytes.size() == 0) throw InvalidLocationFile("Empty Location file line")
				return try {
					Charsets.UTF_8.newDecoder()
						.onMalformedInput(CodingErrorAction.REPORT)
						.onUnmappableCharacter(CodingErrorAction.REPORT)
						.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
				} catch (failure: CharacterCodingException) {
					throw InvalidLocationFile("Location file is not valid UTF-8", failure)
				}
			}
			if (bytes.size() >= LocationFileJsonV1.MAX_LINE_BYTES) {
				throw InvalidLocationFile("Location file line is too long")
			}
			bytes.write(value)
		}
	}
}
