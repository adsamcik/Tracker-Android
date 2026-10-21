package com.adsamcik.tracker.impexp.portable

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.adsamcik.tracker.impexp.exporter.FileBackedExportSpool
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.repository.PressurePortableFormatV1
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** V2 adds timestamped observations around an unchanged optional V1 session document. */
internal object AllDaySensorSourceFile {
    const val MAX_OBSERVATIONS = 100_000
    const val MAX_FILE_BYTES = 64L * 1_024 * 1_024
    private const val HEADER_BYTES = 4_096

    suspend fun writeNativeExport(context: Context, database: AppDatabase, source: String,
        dates: LongRange, sessions: File?, destination: OutputStream): Int =
        FileBackedExportSpool(context, "sensor-source-", MAX_FILE_BYTES).use { spool ->
            val count = spool.write { output ->
                sessions?.inputStream().use { input -> write(output, source, input) { emit ->
                    AllDaySensorPortableRows.forEach(database, source, dates.first,
                        if (dates.last == Long.MAX_VALUE) { Long.MAX_VALUE } else { dates.last + 1L }, emit)
                } }
            }
            spool.copyTo(destination)
            count
        }

    suspend fun open(input: InputStream, source: String, context: Context): SensorSourceFileInput {
        return try {
            SensorFileStaging().use { staging ->
                openStaged(input, source, context, staging.owned).also { staging.transferred = true }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalArgumentException) {
            invalidSensorSourceFile(failure)
        } catch (failure: IllegalStateException) {
            invalidSensorSourceFile(failure)
        } catch (failure: NoSuchElementException) {
            invalidSensorSourceFile(failure)
        } catch (failure: IOException) {
            if (failure.isSensorSourceFormatFailure) {
                invalidSensorSourceFile(failure)
            }
            throw failure
        }
    }

    private suspend fun openStaged(input: InputStream, source: String, context: Context,
        owned: MutableList<FileBackedExportSpool>): SensorSourceFileInput {
            val buffered = BufferedInputStream(input)
            if (hasStandardV1Header(buffered, source)) {
                return SensorSourceFileInput(buffered, null, 0, 1, owned)
            }
            val original = newSpool(context, "sensor-input-", owned)
            original.write { output -> copyInput(PortableJsonTokenLimitInputStream(buffered,
                PortableJsonTokenLimits(maxStringBytes = 4_096 * 6)), output) }
            val version = original.backingFile.inputStream().use { input ->
                readStagedSensorJson { readVersion(input) }
            }
            if (version == 1) {
                return SensorSourceFileInput(original.backingFile.inputStream(), null, 0, 1, owned)
            }
            require(version == 2) { "Unsupported source file version" }
            var sessions: FileBackedExportSpool? = null
            val count = original.backingFile.inputStream().use { stream ->
                readStagedSensorJson {
                    readV2(stream, source) { reader ->
                        val staged = newSpool(context, "sensor-sessions-", owned)
                        sessions = staged
                        staged.write { nested ->
                            val writer = JsonWriter(OutputStreamWriter(nested, Charsets.UTF_8))
                                .apply { isLenient = false }
                            copyJson(reader, writer)
                            writer.flush()
                        }
                    }
                }
            }
            require(sessions != null || count > 0) { "Empty source file" }
            return SensorSourceFileInput(sessions?.backingFile?.inputStream(), original, count, 2, owned)
    }

    /** Streams one row at a time. The caller stages this in the existing bounded private spool. */
    suspend fun write(output: OutputStream, source: String, sessionInput: InputStream?,
        observations: suspend (suspend (JsonObject) -> Unit) -> Unit): Int {
        val writer = JsonWriter(OutputStreamWriter(output, Charsets.UTF_8)).apply { isLenient = false }
        writer.beginObject().name("format").value(sensorSourceFormats.getValue(source)).name("schemaVersion").value(2)
        writer.name("observations").beginArray()
        var count = 0
        observations { row ->
            currentCoroutineContext().ensureActive()
            require(++count <= MAX_OBSERVATIONS)
            JsonReader(row.toString().reader()).use { copyJson(it, writer) }
        }
        writer.endArray().name("sessionData")
        if (sessionInput == null) {
            writer.nullValue()
        } else {
            JsonReader(InputStreamReader(sessionInput, Charsets.UTF_8)).use { copyJson(it, writer) }
        }
        writer.endObject().flush()
        require(count > 0)
        return count
    }

    /** Small fixture/selection overload; production date exports use the paged producer. */
    suspend fun write(output: OutputStream, source: String, sessions: ByteArray, rows: List<JsonObject>) {
        write(output, source, sessions.takeIf { it.isNotEmpty() }?.inputStream()) { emit -> rows.forEach { emit(it) } }
    }

    private fun newSpool(context: Context, prefix: String, owned: MutableList<FileBackedExportSpool>) =
        FileBackedExportSpool(context, prefix, MAX_FILE_BYTES).also(owned::add)

    private fun hasStandardV1Header(input: BufferedInputStream, source: String): Boolean {
        input.mark(HEADER_BYTES)
        val header = StringBuilder()
        val version = Regex("\"schemaVersion\"\\s*:\\s*([0-9]+)\\s*[,}]")
        while (header.length < HEADER_BYTES && !version.containsMatchIn(header)) {
            val next = input.read()
            if (next < 0) {
                break
            }
            header.append(next.toChar())
        }
        input.reset()
        return Regex("^\\s*\\{\\s*\"format\"\\s*:\\s*\"${Regex.escape(sensorSourceFormats.getValue(source))}\"" +
            "\\s*,\\s*\"schemaVersion\"\\s*:\\s*1\\s*[,}]").containsMatchIn(header)
    }

    private fun readVersion(input: InputStream): Int {
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8)).apply { isLenient = false }
        var version: Int? = null
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "schemaVersion") {
                require(version == null)
                version = reader.nextInt()
            } else reader.skipValue()
        }
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT)
        return requireNotNull(version)
    }

    private suspend fun readV2(input: InputStream, source: String,
        sessions: suspend (JsonReader) -> Unit): Int {
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8)).apply { isLenient = false }
        val fields = hashSetOf<String>()
        var count = 0
        reader.beginObject()
        while (reader.hasNext()) {
            currentCoroutineContext().ensureActive()
            val name = reader.nextName()
            require(fields.add(name)) { "Duplicate source field" }
            when (name) {
                "format" -> require(reader.nextString() == sensorSourceFormats.getValue(source))
                "schemaVersion" -> require(reader.nextInt() == 2)
                "sessionData" -> if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                } else {
                    sessions(reader)
                }
                "observations" -> count = readObservationArray(reader, source)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        require(fields.containsAll(setOf("format", "schemaVersion", "observations")))
        return count
    }

    private suspend fun copyInput(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8_192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            if (count > 0) {
                output.write(buffer, 0, count)
            }
        }
    }

    private suspend fun readObservationArray(reader: JsonReader, source: String): Int {
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            currentCoroutineContext().ensureActive()
            require(++count <= MAX_OBSERVATIONS)
            AllDaySensorPortableRows.validate(source, listOf(readSensorRow(reader)))
        }
        reader.endArray()
        return count
    }
}

private val sensorSourceFormats = mapOf(
    "ACTIVITY" to ActivityCapturedPortableFormatV1.FORMAT,
    "PRESSURE" to PressurePortableFormatV1.FORMAT,
)

/** Only private, fully staged JSON is parsed here; caller transport EOF is never relabelled. */
private inline fun <T> readStagedSensorJson(parse: () -> T): T = try {
    parse()
} catch (truncated: EOFException) {
    invalidSensorSourceFile(truncated)
}

private class SensorFileStaging : Closeable {
    val owned = mutableListOf<FileBackedExportSpool>()
    var transferred = false
    override fun close() {
        if (!transferred) {
            closeSpools(owned)
        }
    }
}

/** Owns only private staged files and streams; no caller input or database is closed. */
internal class SensorSourceFileInput(val sessionInput: InputStream?,
    private val observations: FileBackedExportSpool?, val observationCount: Int,
    val schemaVersion: Int, private val owned: List<FileBackedExportSpool>) : Closeable {
    suspend fun forEachObservation(consume: suspend (JsonObject) -> Unit) {
        observations?.backingFile?.inputStream()?.use { input ->
            readStagedSensorJson {
                val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8)).apply { isLenient = false }
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "observations") {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            currentCoroutineContext().ensureActive()
                            consume(readSensorRow(reader))
                        }
                        reader.endArray()
                    } else reader.skipValue()
                }
                reader.endObject()
            }
        }
    }

    override fun close() {
        var failure: IOException? = null
        try {
            if (owned.isNotEmpty()) {
                sessionInput?.close()
            }
        } catch (closed: IOException) { failure = closed }
        closeSpools(owned, failure)
        failure?.let { throw it }
    }
}

private fun readSensorRow(reader: JsonReader): JsonObject {
    val bytes = SensorRowJsonBuffer()
    val writer = JsonWriter(OutputStreamWriter(bytes, Charsets.UTF_8))
    copyJson(reader, writer)
    writer.flush()
    return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8.name())).jsonObject
}

/** The lexical guard above bounds nesting; this copies a single JSON value without a DOM. */
private fun copyJson(reader: JsonReader, writer: JsonWriter) {
    when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            writer.beginObject(); reader.beginObject()
            while (reader.hasNext()) { writer.name(reader.nextName()); copyJson(reader, writer) }
            reader.endObject(); writer.endObject()
        }
        JsonToken.BEGIN_ARRAY -> {
            writer.beginArray(); reader.beginArray()
            while (reader.hasNext()) copyJson(reader, writer)
            reader.endArray(); writer.endArray()
        }
        JsonToken.STRING -> writer.value(reader.nextString())
        JsonToken.NUMBER -> writer.value(BigDecimal(reader.nextString()))
        JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
        JsonToken.NULL -> { reader.nextNull(); writer.nullValue() }
        else -> throw IllegalArgumentException("Invalid JSON value")
    }
}

/** Known bounded sensor fields fit well below this per-row limit; the full file stays at 64 MB. */
private class SensorRowJsonBuffer : ByteArrayOutputStream() {
    override fun write(value: Int) {
        require(size() < MAX_ROW_BYTES) { "Sensor observation exceeds its field bound" }
        super.write(value)
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(length <= MAX_ROW_BYTES - size()) { "Sensor observation exceeds its field bound" }
        super.write(bytes, offset, length)
    }
    private companion object { const val MAX_ROW_BYTES = 128 * 1_024 }
}

private fun closeSpools(spools: List<FileBackedExportSpool>, primary: Throwable? = null) {
    var failure = primary
    for (spool in spools.asReversed()) try { spool.close() } catch (cleanup: IOException) {
        if (failure == null) {
            failure = cleanup
        } else {
            failure.addSuppressed(cleanup)
        }
    }
    if (primary == null) {
        failure?.let { throw it }
    }
}

private val IOException.isSensorSourceFormatFailure: Boolean
    get() = this is PortableJsonTokenLimitException || this is PortableEncodedSizeLimitFailure ||
        this is android.util.MalformedJsonException

private fun invalidSensorSourceFile(failure: Exception): Nothing = throw SensorSourceFileFormatException(failure)

internal class SensorSourceFileFormatException(cause: Exception) : IOException("Invalid source file", cause) {
    private companion object { const val serialVersionUID: Long = 1L }
}
