package com.adsamcik.tracker.impexp.portable

import android.util.JsonReader
import android.util.JsonToken
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV2
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV1
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV2
import java.io.InputStreamReader

internal fun portableStepsSchemaVersion(bytes: ByteArray): Int =
	portableStepsSchemaVersion(PortableJsonBytes.wrap(bytes))

internal fun portableStepsSchemaVersion(bytes: PortableJsonBytes): Int = schemaVersion(
	bytes,
	StepsPortableFormatV1.FORMAT,
	StepsPortableFormatV1.MAX_FILE_BYTES,
	{ message -> PortableStepsJsonException(message) },
).also { version ->
	if (version !in setOf(StepsPortableFormatV1.SCHEMA_VERSION, StepsPortableFormatV2.SCHEMA_VERSION)) {
		throw PortableStepsJsonException("Unsupported Portable Steps schema version")
	}
}

internal fun portableAmbientStepsSchemaVersion(bytes: ByteArray): Int =
	portableAmbientStepsSchemaVersion(PortableJsonBytes.wrap(bytes))

internal fun portableAmbientStepsSchemaVersion(bytes: PortableJsonBytes): Int = schemaVersion(
	bytes,
	AmbientStepsPortableFormatV1.FORMAT,
	AmbientStepsPortableFormatV1.MAX_FILE_BYTES,
	{ message -> PortableAmbientStepsFormatException(message) },
).also { version ->
	if (version !in setOf(
			AmbientStepsPortableFormatV1.SCHEMA_VERSION,
			AmbientStepsPortableFormatV2.SCHEMA_VERSION,
		)
	) {
		throw PortableAmbientStepsFormatException("Unsupported Ambient Steps schema version")
	}
}

private fun schemaVersion(
	bytes: PortableJsonBytes,
	expectedFormat: String,
	maximumBytes: Long,
	failure: (String) -> Exception,
): Int {
	if (bytes.size == 0 || bytes.size.toLong() > maximumBytes) {
		throw failure("Portable document exceeds its byte bound")
	}
	val reader = JsonReader(InputStreamReader(bytes.inputStream(), Charsets.UTF_8)).apply {
		isLenient = false
	}
	return try {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) throw failure("Portable root must be an object")
		reader.beginObject()
		var format: String? = null
		var version: Int? = null
		val fields = hashSetOf<String>()
		while (reader.hasNext()) {
			val name = reader.nextName()
			if (!fields.add(name)) throw failure("Duplicate portable root field $name")
			when (name) {
				"format" -> {
					if (reader.peek() != JsonToken.STRING) throw failure("Invalid portable format")
					format = reader.nextString()
				}
				"schemaVersion" -> {
					if (reader.peek() != JsonToken.NUMBER) throw failure("Invalid schema version")
					val token = reader.nextString()
					if (!DISPATCH_INTEGER.matches(token)) throw failure("Invalid schema version")
					version = token.toIntOrNull() ?: throw failure("Invalid schema version")
				}
				else -> reader.skipValue()
			}
		}
		reader.endObject()
		if (reader.peek() != JsonToken.END_DOCUMENT) throw failure("Trailing portable content")
		if (format != expectedFormat) throw failure("Unexpected portable format")
		version ?: throw failure("Missing schema version")
	} catch (known: PortableStepsJsonException) {
		throw known
	} catch (known: PortableAmbientStepsFormatException) {
		throw known
	} catch (error: Exception) {
		throw failure("Malformed portable document")
	}
}

private val DISPATCH_INTEGER = Regex("(0|[1-9][0-9]*)")
