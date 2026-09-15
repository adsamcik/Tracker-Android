package com.adsamcik.tracker.impexp.portable

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import android.util.MalformedJsonException
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.PORTABLE_PRESSURE_ENTRY_ORDER
import com.adsamcik.tracker.stats.api.repository.PortablePressureAvailability
import com.adsamcik.tracker.stats.api.repository.PortablePressureCoverage
import com.adsamcik.tracker.stats.api.repository.PortablePressureDigest
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntrySink
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortablePressureIntegrity
import com.adsamcik.tracker.stats.api.repository.PortablePressureOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureRunV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowV1
import com.adsamcik.tracker.stats.api.repository.PressurePortableFormatV1
import java.io.EOFException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Strict streaming codec for portable Pressure v1.
 *
 * The 64 MiB file ceiling is a codec-owned transport bound layered over the schema's 256-entry,
 * 4,096-run, and 16,384-window limits. Tests may lower every limit but cannot raise a wire bound.
 * Each complete entry is authenticated before reaching its sink. A valid prefix may therefore be
 * committed before a later malformed entry; callers must use stable, replay-safe per-entry receipts.
 * Neither stream is closed by this codec.
 */
@Suppress("LargeClass", "TooManyFunctions")
internal class PortablePressureJsonV1Codec(
	private val limits: PortablePressureJsonLimits = PortablePressureJsonLimits(),
) {
	/**
	 * Writes a canonical envelope around source-authenticated entries.
	 *
	 * Non-success source outcomes write no bytes. If a source emits entries and then refuses the
	 * export, the incomplete artifact is rejected instead of being finalized as successful.
	 */
	@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
	suspend fun encode(
		outputStream: OutputStream,
		produce: suspend (PortablePressureEntrySink) -> ExportPortablePressureResult,
	): ExportPortablePressureResult {
		val writer = JsonWriter(
			OutputStreamWriter(
				BoundedPressureOutputStream(outputStream, limits.maxFileBytes),
				Charsets.UTF_8,
			),
		).apply {
			isLenient = false
		}
		val claims = mutableMapOf<PortablePressureOpaqueIdentity, PressureIdentityClaim>()
		var entryCount = 0
		var totalRuns = 0L
		var totalWindows = 0L
		var previous: PortablePressureEntryV1? = null
		var started = false

		val result = produce(
			PortablePressureEntrySink { candidate ->
				currentCoroutineContext().ensureActive()
				if (entryCount >= limits.maxEntries) {
					formatFailure("Portable Pressure entry count exceeds ${limits.maxEntries}")
				}
				val counts = preflightCandidate(
					candidate,
					remainingRuns = limits.maxTotalRuns.toLong() - totalRuns,
					remainingWindows = limits.maxTotalWindows.toLong() - totalWindows,
				)
				val entry = authenticatedSnapshot(candidate)
				previous?.let { prior ->
					if (PORTABLE_PRESSURE_ENTRY_ORDER.compare(prior, entry) >= 0) {
						formatFailure("Portable Pressure entries are not in canonical order")
					}
				}
				if (entry.runs.size.toLong() != counts.runs ||
					entry.runs.fold(0L) { count, run ->
						Math.addExact(count, run.windows.size.toLong())
					} != counts.windows
				) {
					formatFailure("Portable Pressure entry changed while being snapshotted")
				}
				claimEntry(entry, claims)
				if (!started) {
					writer.beginObject()
					writer.name(FIELD_FORMAT).value(PressurePortableFormatV1.FORMAT)
					writer.name(FIELD_SCHEMA_VERSION)
						.value(PressurePortableFormatV1.SCHEMA_VERSION.toLong())
					writer.name(FIELD_ENTRIES).beginArray()
					started = true
				}
				writeEntry(writer, entry)
				entryCount++
				totalRuns += counts.runs
				totalWindows += counts.windows
				previous = entry
			},
		)

		currentCoroutineContext().ensureActive()
		when (result) {
			is ExportPortablePressureResult.Exported -> {
				if (result.entryCount != entryCount) {
					formatFailure("Portable Pressure export result count does not match emitted entries")
				}
				if (!started) {
					formatFailure("Portable Pressure export completed without an entry")
				}
				writer.endArray()
				writer.endObject()
				writer.flush()
			}
			else -> {
				if (entryCount != 0) {
					formatFailure("Portable Pressure export emitted entries for a non-success result")
				}
			}
		}
		return result
	}

	/**
	 * Decodes one bounded entry at a time.
	 *
	 * The sink is invoked only after the complete entry, semantic checksums, ordering, global
	 * identity ownership, and all count bounds have been authenticated.
	 */
	suspend fun decode(
		inputStream: InputStream,
		sink: PortablePressureEntrySink,
	): Int = try {
		decodeDocument(inputStream, sink)
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (failure: PressureSinkFailure) {
		throw failure.original
	} catch (failure: PressureTransportIOException) {
		throw failure.original
	} catch (failure: PortableJsonTokenLimitException) {
		throw PortablePressureJsonException("Portable Pressure token exceeds its bound", failure)
	} catch (failure: PortablePressureJsonException) {
		throw failure
	} catch (failure: EOFException) {
		throw PortablePressureJsonException("Portable Pressure document is truncated", failure)
	} catch (failure: MalformedJsonException) {
		throw PortablePressureJsonException("Portable Pressure document is malformed", failure)
	} catch (failure: IllegalStateException) {
		throw PortablePressureJsonException("Portable Pressure document has an invalid JSON shape", failure)
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
	private suspend fun decodeDocument(
		inputStream: InputStream,
		sink: PortablePressureEntrySink,
	): Int {
		val reader = JsonReader(
			InputStreamReader(
				PortableJsonTokenLimitInputStream(
					BoundedPressureInputStream(inputStream, limits.maxFileBytes),
				),
				Charsets.UTF_8,
			),
		).apply {
			isLenient = false
		}
		val seen = hashSetOf<String>()
		val claims = mutableMapOf<PortablePressureOpaqueIdentity, PressureIdentityClaim>()
		val budget = PressureReadBudget()
		var hasFormat = false
		var hasSchemaVersion = false
		var hasEntries = false
		var count = 0
		var previous: PortablePressureEntryV1? = null

		expect(reader, JsonToken.BEGIN_OBJECT, "document")
		reader.beginObject()
		while (reader.hasNext()) {
			when (uniqueName(reader, seen, "portable Pressure document")) {
				FIELD_FORMAT -> {
					val format = string(reader, FIELD_FORMAT, MAX_FORMAT_LENGTH)
					if (format != PressurePortableFormatV1.FORMAT) {
						formatFailure("Unsupported portable Pressure format")
					}
					hasFormat = true
				}
				FIELD_SCHEMA_VERSION -> {
					val version = integer(reader, FIELD_SCHEMA_VERSION)
					if (version != PressurePortableFormatV1.SCHEMA_VERSION.toLong()) {
						formatFailure("Unsupported portable Pressure schema version $version")
					}
					hasSchemaVersion = true
				}
				FIELD_ENTRIES -> {
					if (!hasFormat || !hasSchemaVersion) {
						formatFailure("Portable Pressure header must precede entries")
					}
					expect(reader, JsonToken.BEGIN_ARRAY, FIELD_ENTRIES)
					reader.beginArray()
					while (reader.hasNext()) {
						currentCoroutineContext().ensureActive()
						if (count >= limits.maxEntries) {
							formatFailure("Portable Pressure entry count exceeds ${limits.maxEntries}")
						}
						val entry = readEntry(reader, budget)
						previous?.let { prior ->
							if (PORTABLE_PRESSURE_ENTRY_ORDER.compare(prior, entry) >= 0) {
								formatFailure("Portable Pressure entries are not in canonical order")
							}
						}
						claimEntry(entry, claims)
						emitToSink(sink, entry)
						previous = entry
						count++
					}
					reader.endArray()
					hasEntries = true
				}
				else -> formatFailure("Unknown portable Pressure document field")
			}
		}
		reader.endObject()
		if (!hasFormat || !hasSchemaVersion || !hasEntries) {
			formatFailure("Portable Pressure document is missing a required field")
		}
		if (count == 0) {
			formatFailure("Portable Pressure document must contain at least one entry")
		}
		if (reader.peek() != JsonToken.END_DOCUMENT) {
			formatFailure("Portable Pressure document has trailing content")
		}
		currentCoroutineContext().ensureActive()
		return count
	}

	private fun preflightCandidate(
		entry: PortablePressureEntryV1,
		remainingRuns: Long,
		remainingWindows: Long,
	): PressureCandidateCounts {
		val runs = entry.runs.size.toLong()
		if (runs > limits.maxRunsPerEntry.toLong()) {
			formatFailure("Portable Pressure entry exceeds its run bound")
		}
		if (runs > remainingRuns) {
			formatFailure("Portable Pressure document exceeds its total run bound")
		}
		var windows = 0L
		for (run in entry.runs) {
			val runWindows = run.windows.size.toLong()
			if (runWindows > limits.maxWindowsPerRun.toLong()) {
				formatFailure("Portable Pressure run exceeds its window bound")
			}
			windows = try {
				Math.addExact(windows, runWindows)
			} catch (_: ArithmeticException) {
				formatFailure("Portable Pressure window count overflows")
			}
			if (windows > remainingWindows) {
				formatFailure("Portable Pressure document exceeds its total window bound")
			}
		}
		return PressureCandidateCounts(runs, windows)
	}

	private suspend fun emitToSink(
		sink: PortablePressureEntrySink,
		entry: PortablePressureEntryV1,
	) {
		try {
			sink.emit(entry)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			throw PressureSinkFailure(failure)
		}
	}

	private suspend fun authenticatedSnapshot(
		entry: PortablePressureEntryV1,
	): PortablePressureEntryV1 = try {
		if (entry.runs.size > limits.maxRunsPerEntry) {
			formatFailure("Portable Pressure entry exceeds its run bound")
		}
		if (entry.runs.any { run -> run.windows.size > limits.maxWindowsPerRun }) {
			formatFailure("Portable Pressure run exceeds its window bound")
		}
		val runs = buildList {
			for (run in entry.runs) {
				currentCoroutineContext().ensureActive()
				val windows = buildList {
					for (window in run.windows) {
						currentCoroutineContext().ensureActive()
						add(window.copy())
					}
				}
				add(run.copy(windows = windows))
			}
		}
		entry.copy(runs = runs)
	} catch (failure: IllegalArgumentException) {
		throw PortablePressureJsonException("Invalid portable Pressure entry", failure)
	}

	private suspend fun writeEntry(writer: JsonWriter, entry: PortablePressureEntryV1) {
		writer.beginObject()
		writer.name(FIELD_IDENTITY).value(entry.identity.value)
		writer.name(FIELD_CONTENT_CHECKSUM).value(entry.contentChecksum.value)
		writer.name(FIELD_START_TIME_MS).value(entry.startTimeMs)
		writer.name(FIELD_END_TIME_MS).value(entry.endTimeMs)
		writer.name(FIELD_RUNS).beginArray()
		for (run in entry.runs) {
			currentCoroutineContext().ensureActive()
			writeRun(writer, run)
		}
		writer.endArray()
		writer.endObject()
	}

	private suspend fun writeRun(writer: JsonWriter, run: PortablePressureRunV1) {
		writer.beginObject()
		writer.name(FIELD_IDENTITY).value(run.identity.value)
		writer.name(FIELD_START_TIME_MS).value(run.startTimeMs)
		writer.name(FIELD_END_TIME_MS).value(run.endTimeMs)
		writer.name(FIELD_CAPTURED_FOR_WHOLE_RUN).value(run.capturedForWholeRun)
		writer.name(FIELD_AVAILABILITY).value(run.availability.name)
		writer.name(FIELD_COVERAGE).value(run.coverage.name)
		writer.name(FIELD_RETENTION_LOSS).value(run.retentionLoss)
		writer.name(FIELD_WINDOWS).beginArray()
		for (window in run.windows) {
			currentCoroutineContext().ensureActive()
			writeWindow(writer, window)
		}
		writer.endArray()
		writer.endObject()
	}

	@Suppress("LongMethod")
	private fun writeWindow(writer: JsonWriter, window: PortablePressureWindowV1) {
		if (PortablePressureIntegrity.expectedWindowChecksum(window) != window.contentChecksum) {
			formatFailure("Portable Pressure window changed after validation")
		}
		writer.beginObject()
		writer.name(FIELD_IDENTITY).value(window.identity.value)
		writer.name(FIELD_CONTENT_CHECKSUM).value(window.contentChecksum.value)
		writer.name(FIELD_INTERVAL_START_TIME_MS).value(window.intervalStartTimeMs)
		writer.name(FIELD_INTERVAL_END_TIME_MS).value(window.intervalEndTimeMs)
		writer.name(FIELD_WALL_TIME_UNCERTAINTY_MS).value(window.wallTimeUncertaintyMs)
		writer.name(FIELD_OBSERVED_DURATION_NANOS).value(window.observedDurationNanos)
		writer.name(FIELD_SAMPLE_COUNT).value(window.sampleCount.toLong())
		writer.name(FIELD_EXPECTED_SAMPLE_COUNT).value(window.expectedSampleCount.toLong())
		writer.name(FIELD_MEAN_HECTOPASCALS).value(window.meanHectopascals)
		writer.name(FIELD_SUM_SQUARED_DEVIATIONS).value(window.sumSquaredDeviations)
		writer.name(FIELD_MINIMUM_HECTOPASCALS).value(window.minimumHectopascals)
		writer.name(FIELD_MAXIMUM_HECTOPASCALS).value(window.maximumHectopascals)
		writer.name(FIELD_FIRST_HECTOPASCALS).value(window.firstHectopascals)
		writer.name(FIELD_LATEST_HECTOPASCALS).value(window.latestHectopascals)
		window.slopeHectopascalsPerSecond?.let { slope ->
			writer.name(FIELD_SLOPE_HECTOPASCALS_PER_SECOND).value(slope)
		}
		window.rSquared?.let { value -> writer.name(FIELD_R_SQUARED).value(value) }
		writer.name(FIELD_SENSOR_ACCURACY).value(window.sensorAccuracy.name)
		writer.name(FIELD_EFFECTIVE_SAMPLE_PERIOD_MICROS)
			.value(window.effectiveSamplePeriodMicros.toLong())
		writer.name(FIELD_EFFECTIVE_MAXIMUM_REPORT_LATENCY_MICROS)
			.value(window.effectiveMaximumReportLatencyMicros.toLong())
		writer.name(FIELD_TARGET_WINDOW_DURATION_NANOS).value(window.targetWindowDurationNanos)
		writer.name(FIELD_MAXIMUM_INTER_SAMPLE_GAP_NANOS).value(window.maximumInterSampleGapNanos)
		writer.name(FIELD_CLOSURE).value(window.closure.name)
		writer.name(FIELD_QUALIFICATION).value(window.qualification.name)
		writer.name(FIELD_SOURCE_QUALITY_FLAGS).value(window.sourceQualityFlags)
		window.sourceQualityConfidence?.let { confidence ->
			writer.name(FIELD_SOURCE_QUALITY_CONFIDENCE).value(confidence)
		}
		writer.name(FIELD_ZONE_ID).value(window.zoneId)
		writer.endObject()
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private suspend fun readEntry(
		reader: JsonReader,
		budget: PressureReadBudget,
	): PortablePressureEntryV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "entry")
		reader.beginObject()
		val seen = hashSetOf<String>()
		var identity: PortablePressureOpaqueIdentity? = null
		var contentChecksum: PortablePressureDigest? = null
		var startTimeMs: Long? = null
		var endTimeMs: Long? = null
		var runs: List<PortablePressureRunV1>? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, seen, "portable Pressure entry")) {
				FIELD_IDENTITY -> identity = opaqueIdentity(reader, FIELD_IDENTITY)
				FIELD_CONTENT_CHECKSUM -> contentChecksum = digest(reader, FIELD_CONTENT_CHECKSUM)
				FIELD_START_TIME_MS -> startTimeMs = integer(reader, FIELD_START_TIME_MS)
				FIELD_END_TIME_MS -> endTimeMs = integer(reader, FIELD_END_TIME_MS)
				FIELD_RUNS -> runs = readRuns(reader, budget)
				else -> formatFailure("Unknown portable Pressure entry field")
			}
		}
		reader.endObject()
		return validated("portable Pressure entry") {
			PortablePressureEntryV1(
				identity = required(identity, FIELD_IDENTITY),
				contentChecksum = required(contentChecksum, FIELD_CONTENT_CHECKSUM),
				startTimeMs = required(startTimeMs, FIELD_START_TIME_MS),
				endTimeMs = required(endTimeMs, FIELD_END_TIME_MS),
				runs = required(runs, FIELD_RUNS),
			)
		}
	}

	private suspend fun readRuns(
		reader: JsonReader,
		budget: PressureReadBudget,
	): List<PortablePressureRunV1> {
		expect(reader, JsonToken.BEGIN_ARRAY, FIELD_RUNS)
		reader.beginArray()
		val runs = mutableListOf<PortablePressureRunV1>()
		while (reader.hasNext()) {
			currentCoroutineContext().ensureActive()
			if (runs.size >= limits.maxRunsPerEntry) {
				formatFailure("Portable Pressure entry exceeds its run bound")
			}
			if (budget.totalRuns >= limits.maxTotalRuns) {
				formatFailure("Portable Pressure document exceeds its total run bound")
			}
			budget.totalRuns++
			runs += readRun(reader, budget)
		}
		reader.endArray()
		return runs
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private suspend fun readRun(
		reader: JsonReader,
		budget: PressureReadBudget,
	): PortablePressureRunV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "run")
		reader.beginObject()
		val seen = hashSetOf<String>()
		var identity: PortablePressureOpaqueIdentity? = null
		var startTimeMs: Long? = null
		var endTimeMs: Long? = null
		var capturedForWholeRun: Boolean? = null
		var availability: PortablePressureAvailability? = null
		var coverage: PortablePressureCoverage? = null
		var retentionLoss: Boolean? = null
		var windows: List<PortablePressureWindowV1>? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, seen, "portable Pressure run")) {
				FIELD_IDENTITY -> identity = opaqueIdentity(reader, FIELD_IDENTITY)
				FIELD_START_TIME_MS -> startTimeMs = integer(reader, FIELD_START_TIME_MS)
				FIELD_END_TIME_MS -> endTimeMs = integer(reader, FIELD_END_TIME_MS)
				FIELD_CAPTURED_FOR_WHOLE_RUN -> capturedForWholeRun = boolean(
					reader,
					FIELD_CAPTURED_FOR_WHOLE_RUN,
				)
				FIELD_AVAILABILITY -> availability = enum(reader, FIELD_AVAILABILITY)
				FIELD_COVERAGE -> coverage = enum(reader, FIELD_COVERAGE)
				FIELD_RETENTION_LOSS -> retentionLoss = boolean(reader, FIELD_RETENTION_LOSS)
				FIELD_WINDOWS -> windows = readWindows(reader, budget)
				else -> formatFailure("Unknown portable Pressure run field")
			}
		}
		reader.endObject()
		return validated("portable Pressure run") {
			PortablePressureRunV1(
				identity = required(identity, FIELD_IDENTITY),
				startTimeMs = required(startTimeMs, FIELD_START_TIME_MS),
				endTimeMs = required(endTimeMs, FIELD_END_TIME_MS),
				capturedForWholeRun = required(
					capturedForWholeRun,
					FIELD_CAPTURED_FOR_WHOLE_RUN,
				),
				availability = required(availability, FIELD_AVAILABILITY),
				coverage = required(coverage, FIELD_COVERAGE),
				retentionLoss = required(retentionLoss, FIELD_RETENTION_LOSS),
				windows = required(windows, FIELD_WINDOWS),
			)
		}
	}

	private suspend fun readWindows(
		reader: JsonReader,
		budget: PressureReadBudget,
	): List<PortablePressureWindowV1> {
		expect(reader, JsonToken.BEGIN_ARRAY, FIELD_WINDOWS)
		reader.beginArray()
		val windows = mutableListOf<PortablePressureWindowV1>()
		while (reader.hasNext()) {
			currentCoroutineContext().ensureActive()
			if (windows.size >= limits.maxWindowsPerRun) {
				formatFailure("Portable Pressure run exceeds its window bound")
			}
			if (budget.totalWindows >= limits.maxTotalWindows) {
				formatFailure("Portable Pressure document exceeds its total window bound")
			}
			budget.totalWindows++
			windows += readWindow(reader)
		}
		reader.endArray()
		return windows
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private fun readWindow(reader: JsonReader): PortablePressureWindowV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "window")
		reader.beginObject()
		val values = PressureWindowValues()
		val seen = hashSetOf<String>()
		while (reader.hasNext()) {
			when (uniqueName(reader, seen, "portable Pressure window")) {
				FIELD_IDENTITY -> values.identity = opaqueIdentity(reader, FIELD_IDENTITY)
				FIELD_CONTENT_CHECKSUM -> values.contentChecksum = digest(
					reader,
					FIELD_CONTENT_CHECKSUM,
				)
				FIELD_INTERVAL_START_TIME_MS -> values.intervalStartTimeMs = integer(
					reader,
					FIELD_INTERVAL_START_TIME_MS,
				)
				FIELD_INTERVAL_END_TIME_MS -> values.intervalEndTimeMs = integer(
					reader,
					FIELD_INTERVAL_END_TIME_MS,
				)
				FIELD_WALL_TIME_UNCERTAINTY_MS -> values.wallTimeUncertaintyMs = integer(
					reader,
					FIELD_WALL_TIME_UNCERTAINTY_MS,
				)
				FIELD_OBSERVED_DURATION_NANOS -> values.observedDurationNanos = integer(
					reader,
					FIELD_OBSERVED_DURATION_NANOS,
				)
				FIELD_SAMPLE_COUNT -> values.sampleCount = int(reader, FIELD_SAMPLE_COUNT)
				FIELD_EXPECTED_SAMPLE_COUNT -> values.expectedSampleCount = int(
					reader,
					FIELD_EXPECTED_SAMPLE_COUNT,
				)
				FIELD_MEAN_HECTOPASCALS -> values.meanHectopascals = double(
					reader,
					FIELD_MEAN_HECTOPASCALS,
				)
				FIELD_SUM_SQUARED_DEVIATIONS -> values.sumSquaredDeviations = double(
					reader,
					FIELD_SUM_SQUARED_DEVIATIONS,
				)
				FIELD_MINIMUM_HECTOPASCALS -> values.minimumHectopascals = float(
					reader,
					FIELD_MINIMUM_HECTOPASCALS,
				)
				FIELD_MAXIMUM_HECTOPASCALS -> values.maximumHectopascals = float(
					reader,
					FIELD_MAXIMUM_HECTOPASCALS,
				)
				FIELD_FIRST_HECTOPASCALS -> values.firstHectopascals = float(
					reader,
					FIELD_FIRST_HECTOPASCALS,
				)
				FIELD_LATEST_HECTOPASCALS -> values.latestHectopascals = float(
					reader,
					FIELD_LATEST_HECTOPASCALS,
				)
				FIELD_SLOPE_HECTOPASCALS_PER_SECOND -> values.slopeHectopascalsPerSecond =
					double(reader, FIELD_SLOPE_HECTOPASCALS_PER_SECOND)
				FIELD_R_SQUARED -> values.rSquared = double(reader, FIELD_R_SQUARED)
				FIELD_SENSOR_ACCURACY -> values.sensorAccuracy = enum(
					reader,
					FIELD_SENSOR_ACCURACY,
				)
				FIELD_EFFECTIVE_SAMPLE_PERIOD_MICROS -> values.effectiveSamplePeriodMicros =
					int(reader, FIELD_EFFECTIVE_SAMPLE_PERIOD_MICROS)
				FIELD_EFFECTIVE_MAXIMUM_REPORT_LATENCY_MICROS ->
					values.effectiveMaximumReportLatencyMicros = int(
						reader,
						FIELD_EFFECTIVE_MAXIMUM_REPORT_LATENCY_MICROS,
					)
				FIELD_TARGET_WINDOW_DURATION_NANOS -> values.targetWindowDurationNanos = integer(
					reader,
					FIELD_TARGET_WINDOW_DURATION_NANOS,
				)
				FIELD_MAXIMUM_INTER_SAMPLE_GAP_NANOS ->
					values.maximumInterSampleGapNanos = integer(
						reader,
						FIELD_MAXIMUM_INTER_SAMPLE_GAP_NANOS,
					)
				FIELD_CLOSURE -> values.closure = enum(reader, FIELD_CLOSURE)
				FIELD_QUALIFICATION -> values.qualification = enum(
					reader,
					FIELD_QUALIFICATION,
				)
				FIELD_SOURCE_QUALITY_FLAGS -> values.sourceQualityFlags = integer(
					reader,
					FIELD_SOURCE_QUALITY_FLAGS,
				)
				FIELD_SOURCE_QUALITY_CONFIDENCE -> values.sourceQualityConfidence = float(
					reader,
					FIELD_SOURCE_QUALITY_CONFIDENCE,
				)
				FIELD_ZONE_ID -> values.zoneId = string(
					reader,
					FIELD_ZONE_ID,
					PressurePortableFormatV1.MAX_ZONE_ID_LENGTH,
				)
				else -> formatFailure("Unknown portable Pressure window field")
			}
		}
		reader.endObject()
		return values.build()
	}

	private fun PressureWindowValues.build(): PortablePressureWindowV1 =
		validated("portable Pressure window") {
			PortablePressureWindowV1(
				identity = required(identity, FIELD_IDENTITY),
				contentChecksum = required(contentChecksum, FIELD_CONTENT_CHECKSUM),
				intervalStartTimeMs = required(intervalStartTimeMs, FIELD_INTERVAL_START_TIME_MS),
				intervalEndTimeMs = required(intervalEndTimeMs, FIELD_INTERVAL_END_TIME_MS),
				wallTimeUncertaintyMs = required(
					wallTimeUncertaintyMs,
					FIELD_WALL_TIME_UNCERTAINTY_MS,
				),
				observedDurationNanos = required(
					observedDurationNanos,
					FIELD_OBSERVED_DURATION_NANOS,
				),
				sampleCount = required(sampleCount, FIELD_SAMPLE_COUNT),
				expectedSampleCount = required(expectedSampleCount, FIELD_EXPECTED_SAMPLE_COUNT),
				meanHectopascals = required(meanHectopascals, FIELD_MEAN_HECTOPASCALS),
				sumSquaredDeviations = required(
					sumSquaredDeviations,
					FIELD_SUM_SQUARED_DEVIATIONS,
				),
				minimumHectopascals = required(
					minimumHectopascals,
					FIELD_MINIMUM_HECTOPASCALS,
				),
				maximumHectopascals = required(
					maximumHectopascals,
					FIELD_MAXIMUM_HECTOPASCALS,
				),
				firstHectopascals = required(firstHectopascals, FIELD_FIRST_HECTOPASCALS),
				latestHectopascals = required(latestHectopascals, FIELD_LATEST_HECTOPASCALS),
				slopeHectopascalsPerSecond = slopeHectopascalsPerSecond,
				rSquared = rSquared,
				sensorAccuracy = required(sensorAccuracy, FIELD_SENSOR_ACCURACY),
				effectiveSamplePeriodMicros = required(
					effectiveSamplePeriodMicros,
					FIELD_EFFECTIVE_SAMPLE_PERIOD_MICROS,
				),
				effectiveMaximumReportLatencyMicros = required(
					effectiveMaximumReportLatencyMicros,
					FIELD_EFFECTIVE_MAXIMUM_REPORT_LATENCY_MICROS,
				),
				targetWindowDurationNanos = required(
					targetWindowDurationNanos,
					FIELD_TARGET_WINDOW_DURATION_NANOS,
				),
				maximumInterSampleGapNanos = required(
					maximumInterSampleGapNanos,
					FIELD_MAXIMUM_INTER_SAMPLE_GAP_NANOS,
				),
				closure = required(closure, FIELD_CLOSURE),
				qualification = required(qualification, FIELD_QUALIFICATION),
				sourceQualityFlags = required(sourceQualityFlags, FIELD_SOURCE_QUALITY_FLAGS),
				sourceQualityConfidence = sourceQualityConfidence,
				zoneId = required(zoneId, FIELD_ZONE_ID),
			)
		}

	private fun claimEntry(
		entry: PortablePressureEntryV1,
		claims: MutableMap<PortablePressureOpaqueIdentity, PressureIdentityClaim>,
	) {
		claim(
			claims,
			entry.identity,
			PressureIdentityClaim(PortablePressureIdentityKind.LOGICAL_ENTRY, DOCUMENT_OWNER),
		)
		entry.runs.forEach { run ->
			claim(
				claims,
				run.identity,
				PressureIdentityClaim(PortablePressureIdentityKind.PHYSICAL_RUN, entry.identity.value),
			)
			run.windows.forEach { window ->
				claim(
					claims,
					window.identity,
					PressureIdentityClaim(PortablePressureIdentityKind.WINDOW, run.identity.value),
				)
			}
		}
	}

	private fun claim(
		claims: MutableMap<PortablePressureOpaqueIdentity, PressureIdentityClaim>,
		identity: PortablePressureOpaqueIdentity,
		claim: PressureIdentityClaim,
	) {
		val previous = claims.putIfAbsent(identity, claim)
		if (previous != null) {
			val mismatch = previous.kind != claim.kind || previous.owner != claim.owner
			formatFailure(
				if (mismatch) {
					"Portable Pressure opaque identity changes kind or owner"
				} else {
					"Portable Pressure document repeats an opaque identity"
				},
			)
		}
	}

	private fun opaqueIdentity(
		reader: JsonReader,
		field: String,
	): PortablePressureOpaqueIdentity = validated(field) {
		PortablePressureOpaqueIdentity(string(reader, field, SHA_256_LENGTH))
	}

	private fun digest(reader: JsonReader, field: String): PortablePressureDigest =
		validated(field) {
			PortablePressureDigest(string(reader, field, SHA_256_LENGTH))
		}

	private inline fun <reified T : Enum<T>> enum(reader: JsonReader, field: String): T {
		val value = string(reader, field, MAX_ENUM_LENGTH)
		return enumValues<T>().firstOrNull { candidate -> candidate.name == value }
			?: formatFailure("Portable Pressure field $field has an unknown value")
	}

	private fun string(reader: JsonReader, field: String, maximumLength: Int): String {
		expect(reader, JsonToken.STRING, field)
		val value = reader.nextString()
		if (value.length > maximumLength) {
			formatFailure("Portable Pressure field $field exceeds its length bound")
		}
		return value
	}

	private fun integer(reader: JsonReader, field: String): Long {
		expect(reader, JsonToken.NUMBER, field)
		val raw = reader.nextString()
		if (!JSON_INTEGER.matches(raw)) {
			formatFailure("Portable Pressure field $field is not an integer")
		}
		return raw.toLongOrNull()
			?: formatFailure("Portable Pressure field $field exceeds the integer bound")
	}

	private fun int(reader: JsonReader, field: String): Int {
		val value = integer(reader, field)
		if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
			formatFailure("Portable Pressure field $field exceeds the 32-bit integer bound")
		}
		return value.toInt()
	}

	private fun double(reader: JsonReader, field: String): Double {
		val raw = number(reader, field)
		val value = raw.toDoubleOrNull()
			?: formatFailure("Portable Pressure field $field is not a finite double")
		if (!value.isFinite()) {
			formatFailure("Portable Pressure field $field is not a finite double")
		}
		return value
	}

	private fun float(reader: JsonReader, field: String): Float {
		val raw = number(reader, field)
		val value = raw.toFloatOrNull()
			?: formatFailure("Portable Pressure field $field is not a finite float")
		if (!value.isFinite()) {
			formatFailure("Portable Pressure field $field is not a finite float")
		}
		return value
	}

	private fun number(reader: JsonReader, field: String): String {
		expect(reader, JsonToken.NUMBER, field)
		val raw = reader.nextString()
		if (!JSON_NUMBER.matches(raw)) {
			formatFailure("Portable Pressure field $field is not a JSON number")
		}
		return raw
	}

	private fun boolean(reader: JsonReader, field: String): Boolean {
		expect(reader, JsonToken.BOOLEAN, field)
		return reader.nextBoolean()
	}

	private fun uniqueName(
		reader: JsonReader,
		seen: MutableSet<String>,
		context: String,
	): String {
		val name = reader.nextName()
		if (!seen.add(name)) {
			formatFailure("Duplicate field in $context")
		}
		return name
	}

	private fun expect(reader: JsonReader, token: JsonToken, context: String) {
		if (reader.peek() != token) {
			formatFailure("Portable Pressure $context has the wrong JSON type")
		}
	}

	private fun <T : Any> required(value: T?, field: String): T =
		value ?: formatFailure("Portable Pressure object is missing $field")

	private inline fun <T> validated(context: String, block: () -> T): T = try {
		block()
	} catch (failure: IllegalArgumentException) {
		throw PortablePressureJsonException("Invalid $context", failure)
	}

	internal companion object {
		/** 64 MiB, independent of provider or retention configuration. */
		const val MAX_FILE_BYTES: Long = 64L * 1024L * 1024L

		private const val FIELD_FORMAT = "format"
		private const val FIELD_SCHEMA_VERSION = "schemaVersion"
		private const val FIELD_ENTRIES = "entries"
		private const val FIELD_IDENTITY = "identity"
		private const val FIELD_CONTENT_CHECKSUM = "contentChecksum"
		private const val FIELD_START_TIME_MS = "startTimeMs"
		private const val FIELD_END_TIME_MS = "endTimeMs"
		private const val FIELD_RUNS = "runs"
		private const val FIELD_CAPTURED_FOR_WHOLE_RUN = "capturedForWholeRun"
		private const val FIELD_AVAILABILITY = "availability"
		private const val FIELD_COVERAGE = "coverage"
		private const val FIELD_RETENTION_LOSS = "retentionLoss"
		private const val FIELD_WINDOWS = "windows"
		private const val FIELD_INTERVAL_START_TIME_MS = "intervalStartTimeMs"
		private const val FIELD_INTERVAL_END_TIME_MS = "intervalEndTimeMs"
		private const val FIELD_WALL_TIME_UNCERTAINTY_MS = "wallTimeUncertaintyMs"
		private const val FIELD_OBSERVED_DURATION_NANOS = "observedDurationNanos"
		private const val FIELD_SAMPLE_COUNT = "sampleCount"
		private const val FIELD_EXPECTED_SAMPLE_COUNT = "expectedSampleCount"
		private const val FIELD_MEAN_HECTOPASCALS = "meanHectopascals"
		private const val FIELD_SUM_SQUARED_DEVIATIONS = "sumSquaredDeviations"
		private const val FIELD_MINIMUM_HECTOPASCALS = "minimumHectopascals"
		private const val FIELD_MAXIMUM_HECTOPASCALS = "maximumHectopascals"
		private const val FIELD_FIRST_HECTOPASCALS = "firstHectopascals"
		private const val FIELD_LATEST_HECTOPASCALS = "latestHectopascals"
		private const val FIELD_SLOPE_HECTOPASCALS_PER_SECOND = "slopeHectopascalsPerSecond"
		private const val FIELD_R_SQUARED = "rSquared"
		private const val FIELD_SENSOR_ACCURACY = "sensorAccuracy"
		private const val FIELD_EFFECTIVE_SAMPLE_PERIOD_MICROS = "effectiveSamplePeriodMicros"
		private const val FIELD_EFFECTIVE_MAXIMUM_REPORT_LATENCY_MICROS =
			"effectiveMaximumReportLatencyMicros"
		private const val FIELD_TARGET_WINDOW_DURATION_NANOS = "targetWindowDurationNanos"
		private const val FIELD_MAXIMUM_INTER_SAMPLE_GAP_NANOS = "maximumInterSampleGapNanos"
		private const val FIELD_CLOSURE = "closure"
		private const val FIELD_QUALIFICATION = "qualification"
		private const val FIELD_SOURCE_QUALITY_FLAGS = "sourceQualityFlags"
		private const val FIELD_SOURCE_QUALITY_CONFIDENCE = "sourceQualityConfidence"
		private const val FIELD_ZONE_ID = "zoneId"
		private const val MAX_FORMAT_LENGTH = 64
		private const val MAX_ENUM_LENGTH = 64
		private const val SHA_256_LENGTH = 71
		private const val DOCUMENT_OWNER = "portable-pressure-document-v1"
		private val JSON_INTEGER = Regex("-?(0|[1-9][0-9]*)")
		private val JSON_NUMBER =
			Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
	}
}

/** Codec-local limits can only narrow the portable Pressure v1 contract. */
internal data class PortablePressureJsonLimits(
	val maxFileBytes: Long = PortablePressureJsonV1Codec.MAX_FILE_BYTES,
	val maxEntries: Int = PressurePortableFormatV1.MAX_ENTRIES,
	val maxRunsPerEntry: Int = PressurePortableFormatV1.MAX_RUNS_PER_ENTRY,
	val maxWindowsPerRun: Int = PressurePortableFormatV1.MAX_WINDOWS_PER_RUN,
	val maxTotalRuns: Int = PressurePortableFormatV1.MAX_TOTAL_RUNS,
	val maxTotalWindows: Int = PressurePortableFormatV1.MAX_TOTAL_WINDOWS,
) {
	init {
		require(maxFileBytes in 1L..PortablePressureJsonV1Codec.MAX_FILE_BYTES)
		require(maxEntries in 1..PressurePortableFormatV1.MAX_ENTRIES)
		require(maxRunsPerEntry in 1..PressurePortableFormatV1.MAX_RUNS_PER_ENTRY)
		require(maxWindowsPerRun in 1..PressurePortableFormatV1.MAX_WINDOWS_PER_RUN)
		require(maxTotalRuns in 1..PressurePortableFormatV1.MAX_TOTAL_RUNS)
		require(maxTotalWindows in 1..PressurePortableFormatV1.MAX_TOTAL_WINDOWS)
	}
}

internal class PortablePressureJsonException(
	message: String,
	cause: Throwable? = null,
) : IOException(message, cause) {
	private companion object {
		const val serialVersionUID: Long = 1L
	}
}

private data class PressureIdentityClaim(
	val kind: PortablePressureIdentityKind,
	val owner: String,
)

private data class PressureReadBudget(
	var totalRuns: Int = 0,
	var totalWindows: Int = 0,
)

private data class PressureCandidateCounts(
	val runs: Long,
	val windows: Long,
)

private class PressureSinkFailure(
	val original: Exception,
) : RuntimeException(null, null, false, false)

private class PressureWindowValues {
	var identity: PortablePressureOpaqueIdentity? = null
	var contentChecksum: PortablePressureDigest? = null
	var intervalStartTimeMs: Long? = null
	var intervalEndTimeMs: Long? = null
	var wallTimeUncertaintyMs: Long? = null
	var observedDurationNanos: Long? = null
	var sampleCount: Int? = null
	var expectedSampleCount: Int? = null
	var meanHectopascals: Double? = null
	var sumSquaredDeviations: Double? = null
	var minimumHectopascals: Float? = null
	var maximumHectopascals: Float? = null
	var firstHectopascals: Float? = null
	var latestHectopascals: Float? = null
	var slopeHectopascalsPerSecond: Double? = null
	var rSquared: Double? = null
	var sensorAccuracy: PortablePressureSensorAccuracy? = null
	var effectiveSamplePeriodMicros: Int? = null
	var effectiveMaximumReportLatencyMicros: Int? = null
	var targetWindowDurationNanos: Long? = null
	var maximumInterSampleGapNanos: Long? = null
	var closure: PortablePressureWindowClosure? = null
	var qualification: PortablePressureWindowQualification? = null
	var sourceQualityFlags: Long? = null
	var sourceQualityConfidence: Float? = null
	var zoneId: String? = null
}

private fun formatFailure(message: String): Nothing = throw PortablePressureJsonException(message)

private class BoundedPressureInputStream(
	input: InputStream,
	private val maximumBytes: Long,
) : FilterInputStream(input) {
	private var bytesRead = 0L

	override fun read(): Int {
		val value = transportRead { super.read() }
		if (value >= 0) record(1L)
		return value
	}

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
		val count = transportRead { super.read(buffer, offset, length) }
		if (count > 0) record(count.toLong())
		return count
	}

	private inline fun <T> transportRead(block: () -> T): T = try {
		block()
	} catch (failure: IOException) {
		throw PressureTransportIOException(failure)
	}

	private fun record(count: Long) {
		if (bytesRead > maximumBytes - count) {
			formatFailure("Portable Pressure document exceeds its byte bound")
		}
		bytesRead += count
	}
}

private class PressureTransportIOException(
	val original: IOException,
) : IOException(null, original, false, false)

private class BoundedPressureOutputStream(
	output: OutputStream,
	private val maximumBytes: Long,
) : FilterOutputStream(output) {
	private var bytesWritten = 0L

	override fun write(value: Int) {
		record(1L)
		super.write(value)
	}

	override fun write(buffer: ByteArray, offset: Int, length: Int) {
		record(length.toLong())
		out.write(buffer, offset, length)
	}

	private fun record(count: Long) {
		if (bytesWritten > maximumBytes - count) {
			formatFailure("Portable Pressure document exceeds its byte bound")
		}
		bytesWritten += count
	}
}
