package com.adsamcik.tracker.impexp.portable

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import android.util.MalformedJsonException
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.PortableActivityCaptureCoverage
import com.adsamcik.tracker.shared.base.database.PortableActivityDeletionScopeDigest
import com.adsamcik.tracker.shared.base.database.PortableActivityDigest
import com.adsamcik.tracker.shared.base.database.PortableActivityEntryV1
import com.adsamcik.tracker.shared.base.database.PortableActivityEnvelopeSink
import com.adsamcik.tracker.shared.base.database.PortableActivityEnvelopeV1
import com.adsamcik.tracker.shared.base.database.PortableActivityFragmentV1
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueOwnershipVerifier
import com.adsamcik.tracker.shared.base.database.PortableActivityRunV1
import com.adsamcik.tracker.shared.base.database.PortableActivitySessionMode
import com.adsamcik.tracker.shared.base.database.PortableActivityWindowCoverage
import com.adsamcik.tracker.shared.base.database.PortableActivityWindowV1
import com.adsamcik.tracker.shared.base.database.PortableActivityZoneEpochV1
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Strict bounded JSON codec for portable captured Activity v1.
 *
 * Decode validates the complete envelope checksum, canonical ordering, count limits, and opaque
 * identity hierarchy before returning anything to a mutation adapter. Streams remain caller-owned.
 */
@Suppress("LargeClass", "TooManyFunctions")
internal class PortableActivityJsonV1Codec(
	private val limits: PortableActivityJsonLimits = PortableActivityJsonLimits(),
) {
	suspend fun encode(
		outputStream: OutputStream,
		produce: suspend (PortableActivityEnvelopeSink) -> ExportPortableCapturedActivityResult,
	): ExportPortableCapturedActivityResult {
		var envelope: PortableActivityEnvelopeV1? = null
		val result = produce(
			PortableActivityEnvelopeSink { emitted ->
				currentCoroutineContext().ensureActive()
				if (envelope != null) formatFailure("Portable Activity exporter emitted more than one envelope")
				envelope = emitted
			},
		)
		currentCoroutineContext().ensureActive()
		return when (result) {
			is ExportPortableCapturedActivityResult.Exported -> {
				val value = envelope
					?: formatFailure("Portable Activity export completed without an envelope")
				if (result.entryCount != value.entries.size) {
					formatFailure("Portable Activity export count does not match its envelope")
				}
				validateEnvelopeLimits(value)
				writeEnvelope(outputStream, value)
				result
			}
			else -> {
				if (envelope != null) {
					formatFailure("Portable Activity exporter emitted an envelope for a non-success result")
				}
				result
			}
		}
	}

	suspend fun decode(inputStream: InputStream): PortableActivityEnvelopeV1 {
		val boundedInput = BoundedInputStream(inputStream, limits.maxFileBytes)
		val tokenLimitedInput = PortableJsonTokenLimitInputStream(boundedInput)
		val reader = JsonReader(
			InputStreamReader(tokenLimitedInput, Charsets.UTF_8),
		).apply { isLenient = false }
		return try {
			val counts = DecodeCounts()
			val envelope = readEnvelope(reader, counts)
			if (reader.peek() != JsonToken.END_DOCUMENT) {
				formatFailure("Portable Activity document contains trailing content")
			}
			validateEnvelopeLimits(envelope)
			envelope
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: ActivitySourceReadFailure) {
			throw failure.original
		} catch (format: PortableActivityFormatException) {
			throw format
		} catch (failure: PortableJsonTokenLimitException) {
			throw PortableActivityFormatException(
				"Portable Activity JSON token exceeds its lexical bound",
				failure,
			)
		} catch (failure: MalformedJsonException) {
			throw PortableActivityFormatException("Malformed portable Activity document", failure)
		} catch (failure: EOFException) {
			throw PortableActivityFormatException("Truncated portable Activity document", failure)
		} catch (failure: IOException) {
			throw failure
		} catch (failure: ArithmeticException) {
			throw PortableActivityFormatException("Portable Activity count overflow", failure)
		} catch (failure: IllegalArgumentException) {
			throw PortableActivityFormatException("Invalid portable Activity document", failure)
		} catch (failure: IllegalStateException) {
			throw PortableActivityFormatException("Invalid portable Activity document", failure)
		}
	}

	private suspend fun writeEnvelope(
		outputStream: OutputStream,
		envelope: PortableActivityEnvelopeV1,
	) {
		val writer = JsonWriter(
			OutputStreamWriter(BoundedOutputStream(outputStream, limits.maxFileBytes), Charsets.UTF_8),
		).apply { isLenient = false }
		try {
			writer.beginObject()
			writer.name(FORMAT).value(envelope.format)
			writer.name(SCHEMA_VERSION).value(envelope.schemaVersion.toLong())
			writer.name(CONTENT_CHECKSUM).value(envelope.contentChecksum.value)
			writer.name(ENTRIES).beginArray()
			envelope.entries.forEach { entry ->
				currentCoroutineContext().ensureActive()
				writeEntry(writer, entry)
			}
			writer.endArray()
			writer.endObject()
			writer.flush()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (format: PortableActivityFormatException) {
			throw format
		} catch (failure: IOException) {
			throw failure
		} catch (failure: ArithmeticException) {
			throw PortableActivityFormatException("Portable Activity output count overflow", failure)
		}
	}

	private fun writeEntry(writer: JsonWriter, entry: PortableActivityEntryV1) {
		writer.beginObject()
		writer.name(IDENTITY).value(entry.identity.value)
		writer.name(CONTENT_CHECKSUM).value(entry.contentChecksum.value)
		writer.name(SESSION_MODE).value(entry.sessionMode.name)
		writer.name(START_TIME_MS).value(entry.startTimeMs)
		writer.name(END_TIME_MS).value(entry.endTimeMs)
		writer.name(RUNS).beginArray()
		entry.runs.forEach { writeRun(writer, it) }
		writer.endArray()
		writer.endObject()
	}

	private fun writeRun(writer: JsonWriter, run: PortableActivityRunV1) {
		writer.beginObject()
		writer.name(IDENTITY).value(run.identity.value)
		writer.name(DELETION_SCOPE_DIGEST).value(run.deletionScopeDigest.value)
		writer.name(CONTENT_CHECKSUM).value(run.contentChecksum.value)
		writer.name(START_TIME_MS).value(run.startTimeMs)
		writer.name(END_TIME_MS).value(run.endTimeMs)
		writer.name(CAPTURE_COVERAGE).value(run.captureCoverage.name)
		writer.name(ZONE_EPOCHS).beginArray()
		run.zoneEpochs.forEach { zone ->
			writer.beginObject()
			writer.name(EFFECTIVE_WALL_TIME_MS).value(zone.effectiveWallTimeMs)
			writer.name(ZONE_ID).value(zone.zoneId)
			writer.endObject()
		}
		writer.endArray()
		writer.name(WINDOWS).beginArray()
		run.windows.forEach { writeWindow(writer, it) }
		writer.endArray()
		writer.endObject()
	}

	private fun writeWindow(writer: JsonWriter, window: PortableActivityWindowV1) {
		writer.beginObject()
		writer.name(IDENTITY).value(window.identity.value)
		writer.name(CONTENT_CHECKSUM).value(window.contentChecksum.value)
		writer.name(START_OFFSET_NANOS).value(window.startOffsetNanos)
		writer.name(END_OFFSET_NANOS).value(window.endOffsetNanos)
		writer.name(STORED_ZONE_ID).value(window.storedZoneId)
		writer.name(COVERAGE).value(window.coverage.name)
		writer.name(KNOWN_ACTIVE_DURATION_NANOS).value(window.knownActiveDurationNanos)
		writer.name(KNOWN_INACTIVE_DURATION_NANOS).value(window.knownInactiveDurationNanos)
		writer.name(UNKNOWN_ACTIVITY_DURATION_NANOS).value(window.unknownActivityDurationNanos)
		writer.name(UNOBSERVED_DURATION_NANOS).value(window.unobservedDurationNanos)
		writer.name(FRAGMENTS).beginArray()
		window.fragments.forEach { fragment -> writeFragment(writer, fragment) }
		writer.endArray()
		writer.endObject()
	}

	private fun writeFragment(writer: JsonWriter, fragment: PortableActivityFragmentV1) {
		writer.beginObject()
		when (fragment) {
			is PortableActivityFragmentV1.Gap -> {
				writer.name(TYPE).value(GAP)
				writer.name(START_OFFSET_NANOS).value(fragment.startOffsetNanos)
				writer.name(END_OFFSET_NANOS).value(fragment.endOffsetNanos)
				writer.name(REASON).value(fragment.reason)
			}
			is PortableActivityFragmentV1.Band -> {
				writer.name(TYPE).value(BAND)
				writer.name(START_OFFSET_NANOS).value(fragment.startOffsetNanos)
				writer.name(END_OFFSET_NANOS).value(fragment.endOffsetNanos)
				writer.name(ACTIVITY).value(fragment.activity)
				writer.name(MECHANISM).value(fragment.mechanism)
				writer.name(REFINED_TRANSITION_ACTIVITY)
				nullableValue(writer, fragment.refinedTransitionActivity)
				writer.name(CONFIDENCE_KIND).value(fragment.confidenceKind)
				writer.name(CONFIDENCE_MINIMUM_PERCENT)
				nullableValue(writer, fragment.confidenceMinimumPercent)
				writer.name(CONFIDENCE_MAXIMUM_PERCENT)
				nullableValue(writer, fragment.confidenceMaximumPercent)
				writer.name(CONFIDENCE_OBSERVATION_COUNT)
				nullableValue(writer, fragment.confidenceObservationCount)
				writer.name(START_WALL_TIME_MS).value(fragment.startWallTimeMs)
				writer.name(START_WALL_TIME_UNCERTAINTY_MS)
				.value(fragment.startWallTimeUncertaintyMs)
				writer.name(START_BOUNDARY_KIND).value(fragment.startBoundaryKind)
				writer.name(END_WALL_TIME_MS).value(fragment.endWallTimeMs)
				writer.name(END_WALL_TIME_UNCERTAINTY_MS)
				.value(fragment.endWallTimeUncertaintyMs)
				writer.name(END_BOUNDARY_KIND).value(fragment.endBoundaryKind)
				writer.name(WALL_TIME_CONTINUITY).value(fragment.wallTimeContinuity)
			}
		}
		writer.endObject()
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod")
	private suspend fun readEnvelope(
		reader: JsonReader,
		counts: DecodeCounts,
	): PortableActivityEnvelopeV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "portable Activity document")
		reader.beginObject()
		val fields = hashSetOf<String>()
		var format: String? = null
		var schemaVersion: Int? = null
		var checksum: PortableActivityDigest? = null
		var entries: List<PortableActivityEntryV1>? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, fields, "portable Activity document")) {
				FORMAT -> format = boundedString(reader, FORMAT)
				SCHEMA_VERSION -> schemaVersion = exactInt(reader, SCHEMA_VERSION)
				CONTENT_CHECKSUM -> checksum = digest(reader, CONTENT_CHECKSUM)
				ENTRIES -> {
					if (format != ActivityCapturedPortableFormatV1.FORMAT ||
						schemaVersion != ActivityCapturedPortableFormatV1.SCHEMA_VERSION ||
						checksum == null
					) formatFailure("Portable Activity header must precede entries")
					entries = readArray(reader, ENTRIES, limits.maxEntries) {
						currentCoroutineContext().ensureActive()
						counts.entries = Math.addExact(counts.entries, 1)
						readEntry(reader, counts)
					}
				}
				else -> formatFailure("Unknown portable Activity document field")
			}
		}
		reader.endObject()
		return PortableActivityEnvelopeV1(
			format = format ?: formatFailure("Portable Activity format is missing"),
			schemaVersion = schemaVersion ?: formatFailure("Portable Activity schema version is missing"),
			contentChecksum = checksum ?: formatFailure("Portable Activity checksum is missing"),
			entries = entries ?: formatFailure("Portable Activity entries are missing"),
		)
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod")
	private fun readEntry(reader: JsonReader, counts: DecodeCounts): PortableActivityEntryV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "Activity entry")
		reader.beginObject()
		val fields = hashSetOf<String>()
		var identity: PortableActivityOpaqueIdentity? = null
		var checksum: PortableActivityDigest? = null
		var sessionMode: PortableActivitySessionMode? = null
		var startTimeMs: Long? = null
		var endTimeMs: Long? = null
		var runs: List<PortableActivityRunV1>? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, fields, "Activity entry")) {
				IDENTITY -> identity = identity(reader, IDENTITY)
				CONTENT_CHECKSUM -> checksum = digest(reader, CONTENT_CHECKSUM)
				SESSION_MODE -> sessionMode = enumValue(reader, SESSION_MODE)
				START_TIME_MS -> startTimeMs = long(reader, START_TIME_MS)
				END_TIME_MS -> endTimeMs = long(reader, END_TIME_MS)
				RUNS -> runs = readArray(reader, RUNS, limits.maxRunsPerEntry) {
					counts.runs = Math.addExact(counts.runs, 1)
					if (counts.runs > limits.maxRuns) formatFailure("Portable Activity run count exceeded")
					readRun(reader, counts)
				}
				else -> formatFailure("Unknown Activity entry field")
			}
		}
		reader.endObject()
		return PortableActivityEntryV1(
			identity = identity ?: formatFailure("Activity entry identity is missing"),
			contentChecksum = checksum ?: formatFailure("Activity entry checksum is missing"),
			sessionMode = sessionMode ?: formatFailure("Activity entry session mode is missing"),
			startTimeMs = startTimeMs ?: formatFailure("Activity entry start time is missing"),
			endTimeMs = endTimeMs ?: formatFailure("Activity entry end time is missing"),
			runs = runs ?: formatFailure("Activity entry runs are missing"),
		)
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod")
	private fun readRun(reader: JsonReader, counts: DecodeCounts): PortableActivityRunV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "Activity run")
		reader.beginObject()
		val fields = hashSetOf<String>()
		var identity: PortableActivityOpaqueIdentity? = null
		var deletionScope: PortableActivityDeletionScopeDigest? = null
		var checksum: PortableActivityDigest? = null
		var startTimeMs: Long? = null
		var endTimeMs: Long? = null
		var coverage: PortableActivityCaptureCoverage? = null
		var zones: List<PortableActivityZoneEpochV1>? = null
		var windows: List<PortableActivityWindowV1>? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, fields, "Activity run")) {
				IDENTITY -> identity = identity(reader, IDENTITY)
				DELETION_SCOPE_DIGEST ->
					deletionScope = PortableActivityDeletionScopeDigest(
						boundedString(reader, DELETION_SCOPE_DIGEST),
					)
				CONTENT_CHECKSUM -> checksum = digest(reader, CONTENT_CHECKSUM)
				START_TIME_MS -> startTimeMs = long(reader, START_TIME_MS)
				END_TIME_MS -> endTimeMs = long(reader, END_TIME_MS)
				CAPTURE_COVERAGE -> coverage = enumValue(reader, CAPTURE_COVERAGE)
				ZONE_EPOCHS -> zones = readArray(
					reader,
					ZONE_EPOCHS,
					limits.maxZoneEpochsPerRun,
				) {
					counts.zoneEpochs = Math.addExact(counts.zoneEpochs, 1)
					if (counts.zoneEpochs > limits.maxZoneEpochs) {
						formatFailure("Portable Activity zone-epoch count exceeded")
					}
					readZoneEpoch(reader)
				}
				WINDOWS -> windows = readArray(reader, WINDOWS, limits.maxWindowsPerRun) {
					counts.windows = Math.addExact(counts.windows, 1)
					if (counts.windows > limits.maxWindows) {
						formatFailure("Portable Activity window count exceeded")
					}
					readWindow(reader, counts)
				}
				else -> formatFailure("Unknown Activity run field")
			}
		}
		reader.endObject()
		return PortableActivityRunV1(
			identity = identity ?: formatFailure("Activity run identity is missing"),
			deletionScopeDigest = deletionScope
				?: formatFailure("Activity run deletion scope is missing"),
			contentChecksum = checksum ?: formatFailure("Activity run checksum is missing"),
			startTimeMs = startTimeMs ?: formatFailure("Activity run start time is missing"),
			endTimeMs = endTimeMs ?: formatFailure("Activity run end time is missing"),
			captureCoverage = coverage ?: formatFailure("Activity run capture coverage is missing"),
			zoneEpochs = zones ?: formatFailure("Activity run zone epochs are missing"),
			windows = windows ?: formatFailure("Activity run windows are missing"),
		)
	}

	private fun readZoneEpoch(reader: JsonReader): PortableActivityZoneEpochV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "Activity zone epoch")
		reader.beginObject()
		val fields = hashSetOf<String>()
		var effectiveWallTimeMs: Long? = null
		var zoneId: String? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, fields, "Activity zone epoch")) {
				EFFECTIVE_WALL_TIME_MS -> effectiveWallTimeMs = long(reader, EFFECTIVE_WALL_TIME_MS)
				ZONE_ID -> zoneId = boundedString(reader, ZONE_ID)
				else -> formatFailure("Unknown Activity zone-epoch field")
			}
		}
		reader.endObject()
		return PortableActivityZoneEpochV1(
			effectiveWallTimeMs = effectiveWallTimeMs
				?: formatFailure("Activity zone-epoch time is missing"),
			zoneId = zoneId ?: formatFailure("Activity zone-epoch zone is missing"),
		)
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod")
	private fun readWindow(reader: JsonReader, counts: DecodeCounts): PortableActivityWindowV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "Activity window")
		reader.beginObject()
		val fields = hashSetOf<String>()
		var identity: PortableActivityOpaqueIdentity? = null
		var checksum: PortableActivityDigest? = null
		var startOffsetNanos: Long? = null
		var endOffsetNanos: Long? = null
		var storedZoneId: String? = null
		var coverage: PortableActivityWindowCoverage? = null
		var knownActive: Long? = null
		var knownInactive: Long? = null
		var unknownActivity: Long? = null
		var unobserved: Long? = null
		var fragments: List<PortableActivityFragmentV1>? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, fields, "Activity window")) {
				IDENTITY -> identity = identity(reader, IDENTITY)
				CONTENT_CHECKSUM -> checksum = digest(reader, CONTENT_CHECKSUM)
				START_OFFSET_NANOS -> startOffsetNanos = long(reader, START_OFFSET_NANOS)
				END_OFFSET_NANOS -> endOffsetNanos = long(reader, END_OFFSET_NANOS)
				STORED_ZONE_ID -> storedZoneId = boundedString(reader, STORED_ZONE_ID)
				COVERAGE -> coverage = enumValue(reader, COVERAGE)
				KNOWN_ACTIVE_DURATION_NANOS ->
					knownActive = long(reader, KNOWN_ACTIVE_DURATION_NANOS)
				KNOWN_INACTIVE_DURATION_NANOS ->
					knownInactive = long(reader, KNOWN_INACTIVE_DURATION_NANOS)
				UNKNOWN_ACTIVITY_DURATION_NANOS ->
					unknownActivity = long(reader, UNKNOWN_ACTIVITY_DURATION_NANOS)
				UNOBSERVED_DURATION_NANOS ->
					unobserved = long(reader, UNOBSERVED_DURATION_NANOS)
				FRAGMENTS -> fragments = readArray(
					reader,
					FRAGMENTS,
					limits.maxFragmentsPerWindow,
				) {
					counts.fragments = Math.addExact(counts.fragments, 1)
					if (counts.fragments > limits.maxFragments) {
						formatFailure("Portable Activity fragment count exceeded")
					}
					readFragment(reader)
				}
				else -> formatFailure("Unknown Activity window field")
			}
		}
		reader.endObject()
		return PortableActivityWindowV1(
			identity = identity ?: formatFailure("Activity window identity is missing"),
			contentChecksum = checksum ?: formatFailure("Activity window checksum is missing"),
			startOffsetNanos = startOffsetNanos
				?: formatFailure("Activity window start offset is missing"),
			endOffsetNanos = endOffsetNanos
				?: formatFailure("Activity window end offset is missing"),
			storedZoneId = storedZoneId ?: formatFailure("Activity window zone is missing"),
			coverage = coverage ?: formatFailure("Activity window coverage is missing"),
			knownActiveDurationNanos = knownActive
				?: formatFailure("Activity window active duration is missing"),
			knownInactiveDurationNanos = knownInactive
				?: formatFailure("Activity window inactive duration is missing"),
			unknownActivityDurationNanos = unknownActivity
				?: formatFailure("Activity window unknown duration is missing"),
			unobservedDurationNanos = unobserved
				?: formatFailure("Activity window unobserved duration is missing"),
			fragments = fragments ?: formatFailure("Activity window fragments are missing"),
		)
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod")
	private fun readFragment(reader: JsonReader): PortableActivityFragmentV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "Activity fragment")
		reader.beginObject()
		val fields = hashSetOf<String>()
		var type: String? = null
		var startOffsetNanos: Long? = null
		var endOffsetNanos: Long? = null
		var reason: String? = null
		var activity: String? = null
		var mechanism: String? = null
		var refined: String? = null
		var confidenceKind: String? = null
		var confidenceMinimum: Int? = null
		var confidenceMaximum: Int? = null
		var confidenceCount: Int? = null
		var startWallTimeMs: Long? = null
		var startUncertaintyMs: Long? = null
		var startBoundaryKind: String? = null
		var endWallTimeMs: Long? = null
		var endUncertaintyMs: Long? = null
		var endBoundaryKind: String? = null
		var wallTimeContinuity: String? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, fields, "Activity fragment")) {
				TYPE -> type = boundedString(reader, TYPE)
				START_OFFSET_NANOS -> startOffsetNanos = long(reader, START_OFFSET_NANOS)
				END_OFFSET_NANOS -> endOffsetNanos = long(reader, END_OFFSET_NANOS)
				REASON -> reason = boundedString(reader, REASON)
				ACTIVITY -> activity = boundedString(reader, ACTIVITY)
				MECHANISM -> mechanism = boundedString(reader, MECHANISM)
				REFINED_TRANSITION_ACTIVITY ->
					refined = nullableString(reader, REFINED_TRANSITION_ACTIVITY)
				CONFIDENCE_KIND -> confidenceKind = boundedString(reader, CONFIDENCE_KIND)
				CONFIDENCE_MINIMUM_PERCENT ->
					confidenceMinimum = nullableInt(reader, CONFIDENCE_MINIMUM_PERCENT)
				CONFIDENCE_MAXIMUM_PERCENT ->
					confidenceMaximum = nullableInt(reader, CONFIDENCE_MAXIMUM_PERCENT)
				CONFIDENCE_OBSERVATION_COUNT ->
					confidenceCount = nullableInt(reader, CONFIDENCE_OBSERVATION_COUNT)
				START_WALL_TIME_MS -> startWallTimeMs = long(reader, START_WALL_TIME_MS)
				START_WALL_TIME_UNCERTAINTY_MS ->
					startUncertaintyMs = long(reader, START_WALL_TIME_UNCERTAINTY_MS)
				START_BOUNDARY_KIND ->
					startBoundaryKind = boundedString(reader, START_BOUNDARY_KIND)
				END_WALL_TIME_MS -> endWallTimeMs = long(reader, END_WALL_TIME_MS)
				END_WALL_TIME_UNCERTAINTY_MS ->
					endUncertaintyMs = long(reader, END_WALL_TIME_UNCERTAINTY_MS)
				END_BOUNDARY_KIND -> endBoundaryKind = boundedString(reader, END_BOUNDARY_KIND)
				WALL_TIME_CONTINUITY ->
					wallTimeContinuity = boundedString(reader, WALL_TIME_CONTINUITY)
				else -> formatFailure("Unknown Activity fragment field")
			}
		}
		reader.endObject()
		val start = startOffsetNanos ?: formatFailure("Activity fragment start offset is missing")
		val end = endOffsetNanos ?: formatFailure("Activity fragment end offset is missing")
		return when (type ?: formatFailure("Activity fragment type is missing")) {
			GAP -> {
				requireOnlyFields(fields, GAP_FIELDS, "Activity gap")
				PortableActivityFragmentV1.Gap(
					startOffsetNanos = start,
					endOffsetNanos = end,
					reason = reason ?: formatFailure("Activity gap reason is missing"),
				)
			}
			BAND -> {
				requireOnlyFields(fields, BAND_FIELDS, "Activity band")
				PortableActivityFragmentV1.Band(
					startOffsetNanos = start,
					endOffsetNanos = end,
					activity = activity ?: formatFailure("Activity band activity is missing"),
					mechanism = mechanism ?: formatFailure("Activity band mechanism is missing"),
					refinedTransitionActivity = refined,
					confidenceKind = confidenceKind
						?: formatFailure("Activity band confidence kind is missing"),
					confidenceMinimumPercent = confidenceMinimum,
					confidenceMaximumPercent = confidenceMaximum,
					confidenceObservationCount = confidenceCount,
					startWallTimeMs = startWallTimeMs
						?: formatFailure("Activity band start time is missing"),
					startWallTimeUncertaintyMs = startUncertaintyMs
						?: formatFailure("Activity band start uncertainty is missing"),
					startBoundaryKind = startBoundaryKind
						?: formatFailure("Activity band start boundary is missing"),
					endWallTimeMs = endWallTimeMs
						?: formatFailure("Activity band end time is missing"),
					endWallTimeUncertaintyMs = endUncertaintyMs
						?: formatFailure("Activity band end uncertainty is missing"),
					endBoundaryKind = endBoundaryKind
						?: formatFailure("Activity band end boundary is missing"),
					wallTimeContinuity = wallTimeContinuity
						?: formatFailure("Activity band wall continuity is missing"),
				)
			}
			else -> formatFailure("Unknown Activity fragment type")
		}
	}

	private fun validateEnvelopeLimits(envelope: PortableActivityEnvelopeV1) {
		var runCount = 0
		var zoneCount = 0
		var windowCount = 0
		var fragmentCount = 0
		try {
			envelope.entries.forEach { entry ->
				runCount = Math.addExact(runCount, entry.runs.size)
				entry.runs.forEach { run ->
					zoneCount = Math.addExact(zoneCount, run.zoneEpochs.size)
					windowCount = Math.addExact(windowCount, run.windows.size)
					run.windows.forEach { window ->
						fragmentCount = Math.addExact(fragmentCount, window.fragments.size)
					}
				}
			}
		} catch (_: ArithmeticException) {
			formatFailure("Portable Activity envelope count overflow")
		}
		if (envelope.entries.size > limits.maxEntries || runCount > limits.maxRuns ||
			zoneCount > limits.maxZoneEpochs || windowCount > limits.maxWindows ||
			fragmentCount > limits.maxFragments ||
			PortableActivityOpaqueOwnershipVerifier.fromEntries(envelope.entries) == null
		) {
			formatFailure("Portable Activity envelope exceeds limits or has conflicting identities")
		}
	}

	private inline fun <T> readArray(
		reader: JsonReader,
		name: String,
		maximum: Int,
		readItem: () -> T,
	): List<T> {
		expect(reader, JsonToken.BEGIN_ARRAY, name)
		reader.beginArray()
		val values = ArrayList<T>()
		while (reader.hasNext()) {
			if (values.size >= maximum) formatFailure("$name count exceeds $maximum")
			values += readItem()
		}
		reader.endArray()
		return values
	}

	private fun uniqueName(reader: JsonReader, fields: MutableSet<String>, owner: String): String {
		expect(reader, JsonToken.NAME, owner)
		val name = reader.nextName()
		if (!fields.add(name)) formatFailure("$owner repeats field $name")
		return name
	}

	private fun expect(reader: JsonReader, token: JsonToken, owner: String) {
		if (reader.peek() != token) formatFailure("$owner must contain $token")
	}

	private fun boundedString(reader: JsonReader, name: String): String {
		expect(reader, JsonToken.STRING, name)
		val value = reader.nextString()
		if (value.isBlank() || value.length > limits.maxTextLength) {
			formatFailure("$name is blank or too long")
		}
		return value
	}

	private fun nullableString(reader: JsonReader, name: String): String? =
		if (reader.peek() == JsonToken.NULL) {
			reader.nextNull()
			null
		} else {
			boundedString(reader, name)
		}

	private fun long(reader: JsonReader, name: String): Long {
		expect(reader, JsonToken.NUMBER, name)
		val literal = reader.nextString()
		if (!INTEGER_LITERAL.matches(literal)) {
			formatFailure("$name is not a canonical integer")
		}
		return try {
			literal.toLong()
		} catch (failure: NumberFormatException) {
			throw PortableActivityFormatException("$name is not an exact integer", failure)
		}
	}

	private fun exactInt(reader: JsonReader, name: String): Int = try {
		Math.toIntExact(long(reader, name))
	} catch (failure: ArithmeticException) {
		throw PortableActivityFormatException("$name is outside the integer range", failure)
	}

	private fun nullableInt(reader: JsonReader, name: String): Int? =
		if (reader.peek() == JsonToken.NULL) {
			reader.nextNull()
			null
		} else {
			exactInt(reader, name)
		}

	private fun identity(reader: JsonReader, name: String) =
		PortableActivityOpaqueIdentity(boundedString(reader, name))

	private fun digest(reader: JsonReader, name: String) =
		PortableActivityDigest(boundedString(reader, name))

	private inline fun <reified T : Enum<T>> enumValue(reader: JsonReader, name: String): T =
		try {
			enumValueOf<T>(boundedString(reader, name))
		} catch (failure: IllegalArgumentException) {
			throw PortableActivityFormatException("Unknown $name value", failure)
		}

	private fun requireOnlyFields(actual: Set<String>, expected: Set<String>, owner: String) {
		if (actual != expected) formatFailure("$owner fields are incomplete or incompatible")
	}

	private fun nullableValue(writer: JsonWriter, value: String?) {
		if (value == null) writer.nullValue() else writer.value(value)
	}

	private fun nullableValue(writer: JsonWriter, value: Int?) {
		if (value == null) writer.nullValue() else writer.value(value.toLong())
	}

	private class DecodeCounts {
		var entries: Int = 0
		var runs: Int = 0
		var zoneEpochs: Int = 0
		var windows: Int = 0
		var fragments: Int = 0
	}

	private companion object {
		const val FORMAT = "format"
		const val SCHEMA_VERSION = "schemaVersion"
		const val CONTENT_CHECKSUM = "contentChecksum"
		const val ENTRIES = "entries"
		const val IDENTITY = "identity"
		const val SESSION_MODE = "sessionMode"
		const val START_TIME_MS = "startTimeMs"
		const val END_TIME_MS = "endTimeMs"
		const val RUNS = "runs"
		const val DELETION_SCOPE_DIGEST = "deletionScopeDigest"
		const val CAPTURE_COVERAGE = "captureCoverage"
		const val ZONE_EPOCHS = "zoneEpochs"
		const val EFFECTIVE_WALL_TIME_MS = "effectiveWallTimeMs"
		const val ZONE_ID = "zoneId"
		const val WINDOWS = "windows"
		const val START_OFFSET_NANOS = "startOffsetNanos"
		const val END_OFFSET_NANOS = "endOffsetNanos"
		const val STORED_ZONE_ID = "storedZoneId"
		const val COVERAGE = "coverage"
		const val KNOWN_ACTIVE_DURATION_NANOS = "knownActiveDurationNanos"
		const val KNOWN_INACTIVE_DURATION_NANOS = "knownInactiveDurationNanos"
		const val UNKNOWN_ACTIVITY_DURATION_NANOS = "unknownActivityDurationNanos"
		const val UNOBSERVED_DURATION_NANOS = "unobservedDurationNanos"
		const val FRAGMENTS = "fragments"
		const val TYPE = "type"
		const val GAP = "GAP"
		const val BAND = "BAND"
		const val REASON = "reason"
		const val ACTIVITY = "activity"
		const val MECHANISM = "mechanism"
		const val REFINED_TRANSITION_ACTIVITY = "refinedTransitionActivity"
		const val CONFIDENCE_KIND = "confidenceKind"
		const val CONFIDENCE_MINIMUM_PERCENT = "confidenceMinimumPercent"
		const val CONFIDENCE_MAXIMUM_PERCENT = "confidenceMaximumPercent"
		const val CONFIDENCE_OBSERVATION_COUNT = "confidenceObservationCount"
		const val START_WALL_TIME_MS = "startWallTimeMs"
		const val START_WALL_TIME_UNCERTAINTY_MS = "startWallTimeUncertaintyMs"
		const val START_BOUNDARY_KIND = "startBoundaryKind"
		const val END_WALL_TIME_MS = "endWallTimeMs"
		const val END_WALL_TIME_UNCERTAINTY_MS = "endWallTimeUncertaintyMs"
		const val END_BOUNDARY_KIND = "endBoundaryKind"
		const val WALL_TIME_CONTINUITY = "wallTimeContinuity"

		val GAP_FIELDS = setOf(TYPE, START_OFFSET_NANOS, END_OFFSET_NANOS, REASON)
		val BAND_FIELDS = setOf(
			TYPE,
			START_OFFSET_NANOS,
			END_OFFSET_NANOS,
			ACTIVITY,
			MECHANISM,
			REFINED_TRANSITION_ACTIVITY,
			CONFIDENCE_KIND,
			CONFIDENCE_MINIMUM_PERCENT,
			CONFIDENCE_MAXIMUM_PERCENT,
			CONFIDENCE_OBSERVATION_COUNT,
			START_WALL_TIME_MS,
			START_WALL_TIME_UNCERTAINTY_MS,
			START_BOUNDARY_KIND,
			END_WALL_TIME_MS,
			END_WALL_TIME_UNCERTAINTY_MS,
			END_BOUNDARY_KIND,
			WALL_TIME_CONTINUITY,
		)
		val INTEGER_LITERAL = Regex("-?(0|[1-9][0-9]*)")
	}
}

internal data class PortableActivityJsonLimits(
	val maxFileBytes: Long = ActivityCapturedPortableFormatV1.MAX_FILE_BYTES,
	val maxEntries: Int = ActivityCapturedPortableFormatV1.MAX_ENTRIES,
	val maxRuns: Int = ActivityCapturedPortableFormatV1.MAX_RUNS,
	val maxRunsPerEntry: Int = ActivityCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY,
	val maxZoneEpochs: Int = ActivityCapturedPortableFormatV1.MAX_ZONE_EPOCHS,
	val maxZoneEpochsPerRun: Int = ActivityCapturedPortableFormatV1.MAX_ZONE_EPOCHS_PER_RUN,
	val maxWindows: Int = ActivityCapturedPortableFormatV1.MAX_WINDOWS,
	val maxWindowsPerRun: Int = ActivityCapturedPortableFormatV1.MAX_WINDOWS_PER_RUN,
	val maxFragments: Int = ActivityCapturedPortableFormatV1.MAX_FRAGMENTS,
	val maxFragmentsPerWindow: Int = ActivityCapturedPortableFormatV1.MAX_FRAGMENTS_PER_WINDOW,
	val maxTextLength: Int = ActivityCapturedPortableFormatV1.MAX_IMPORT_RECEIPT_FIELD_LENGTH,
) {
	init {
		require(maxFileBytes > 0L)
		listOf(
			maxEntries,
			maxRuns,
			maxRunsPerEntry,
			maxZoneEpochs,
			maxZoneEpochsPerRun,
			maxWindows,
			maxWindowsPerRun,
			maxFragments,
			maxFragmentsPerWindow,
			maxTextLength,
		).forEach { require(it > 0) }
	}
}

internal class PortableActivityFormatException(
	message: String,
	cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

private class BoundedInputStream(
	input: InputStream,
	private val maximumBytes: Long,
) : FilterInputStream(input) {
	private var count = 0L

	override fun read(): Int {
		val value = readSource { super.read() }
		if (value >= 0) include(1)
		return value
	}

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
		val read = readSource { super.read(buffer, offset, length) }
		if (read > 0) include(read)
		return read
	}

	private inline fun <T> readSource(block: () -> T): T = try {
		block()
	} catch (failure: IOException) {
		throw ActivitySourceReadFailure(failure)
	}

	private fun include(bytes: Int) {
		count = Math.addExact(count, bytes.toLong())
		if (count > maximumBytes) formatFailure("Portable Activity file exceeds $maximumBytes bytes")
	}
}

private class ActivitySourceReadFailure(
	val original: IOException,
) : RuntimeException(null, null, false, false)

private class BoundedOutputStream(
	output: OutputStream,
	private val maximumBytes: Long,
) : FilterOutputStream(output) {
	private var count = 0L

	override fun write(value: Int) {
		include(1)
		out.write(value)
	}

	override fun write(buffer: ByteArray, offset: Int, length: Int) {
		include(length)
		out.write(buffer, offset, length)
	}

	private fun include(bytes: Int) {
		count = Math.addExact(count, bytes.toLong())
		if (count > maximumBytes) formatFailure("Portable Activity file exceeds $maximumBytes bytes")
	}
}

private fun formatFailure(message: String): Nothing = throw PortableActivityFormatException(message)
