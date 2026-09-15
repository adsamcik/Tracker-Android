package com.adsamcik.tracker.impexp.portable

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import android.util.MalformedJsonException
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableDigest
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableFormatV1
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapReason
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsGapV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsPartialCause
import com.adsamcik.tracker.shared.model.steps.portable.deletionScopeIdentity
import com.adsamcik.tracker.shared.model.steps.portable.identity
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsArchiveSink
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportMetadata
import java.io.EOFException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Canonical bounded JSON codec for one portable Ambient Steps v1 archive.
 *
 * Decode authenticates the complete archive before returning it. This mirrors the source import
 * contract, which admits one archive atomically rather than committing day prefixes. Streams remain
 * caller-owned. The shared lexical guard bounds names, escaped strings, integer tokens, nesting,
 * and parser prefetch before [JsonReader] materializes a token.
 */
@Suppress("LargeClass", "TooManyFunctions")
internal class PortableAmbientStepsJsonV1Codec @JvmOverloads constructor(
	private val limits: PortableAmbientStepsJsonLimits = PortableAmbientStepsJsonLimits(),
) {
	suspend fun encode(
		outputStream: OutputStream,
		produce: suspend (PortableAmbientStepsArchiveSink) -> ExportPortableAmbientStepsResult,
	): ExportPortableAmbientStepsResult {
		var archive: PortableAmbientStepsArchiveV1? = null
		val result = produce(
			PortableAmbientStepsArchiveSink { candidate ->
				currentCoroutineContext().ensureActive()
				if (archive != null) {
					formatFailure("Portable Ambient Steps exporter emitted more than one archive")
				}
				val counts = preflight(candidate)
				val snapshot = authenticatedSnapshot(candidate)
				if (snapshot.days.size.toLong() != counts.days ||
					snapshot.days.sumOf { it.facts.size.toLong() } != counts.facts ||
					snapshot.days.sumOf { it.gaps.size.toLong() } != counts.gaps
				) {
					formatFailure("Portable Ambient Steps archive changed while being snapshotted")
				}
				claimArchive(snapshot)
				archive = snapshot
			},
		)
		currentCoroutineContext().ensureActive()
		return when (result) {
			is ExportPortableAmbientStepsResult.Exported -> {
				val value = archive
					?: formatFailure("Portable Ambient Steps export completed without an archive")
				if (result.dayCount != value.days.size ||
					result.factCount != value.days.sumOf { it.facts.size } ||
					result.gapCount != value.days.sumOf { it.gaps.size }
				) {
					formatFailure("Portable Ambient Steps source result does not match its archive")
				}
				writeArchive(outputStream, value)
				result
			}
			else -> {
				if (archive != null) {
					formatFailure(
						"Portable Ambient Steps exporter emitted an archive for a non-success result",
					)
				}
				result
			}
		}
	}

	suspend fun decode(inputStream: InputStream): PortableAmbientStepsDecodedArchive {
		val bounded = BoundedAmbientInputStream(inputStream, limits.maxFileBytes)
		val tokenLimited = PortableJsonTokenLimitInputStream(bounded, AMBIENT_TOKEN_LIMITS)
		val reader = JsonReader(InputStreamReader(tokenLimited, Charsets.UTF_8)).apply {
			isLenient = false
		}
		return try {
			val counts = AmbientDecodeCounts()
			val archive = readArchive(reader, counts)
			if (reader.peek() != JsonToken.END_DOCUMENT) {
				formatFailure("Portable Ambient Steps document contains trailing content")
			}
			claimArchive(archive)
			PortableAmbientStepsDecodedArchive(
				archive = archive,
				metadata = PortableAmbientStepsImportMetadata(
					encodedByteCount = bounded.bytesRead,
					archiveContentChecksum = archive.contentChecksum,
					dayCount = counts.days,
					factCount = counts.facts,
					gapCount = counts.gaps,
				),
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (format: PortableAmbientStepsFormatException) {
			throw format
		} catch (failure: PortableJsonTokenLimitException) {
			throw PortableAmbientStepsFormatException(
				"Portable Ambient Steps JSON token exceeds its lexical bound",
				failure,
			)
		} catch (failure: MalformedJsonException) {
			throw PortableAmbientStepsFormatException(
				"Malformed portable Ambient Steps document",
				failure,
			)
		} catch (failure: EOFException) {
			throw PortableAmbientStepsFormatException(
				"Truncated portable Ambient Steps document",
				failure,
			)
		} catch (failure: IOException) {
			throw failure
		} catch (failure: ArithmeticException) {
			throw PortableAmbientStepsFormatException(
				"Portable Ambient Steps count overflow",
				failure,
			)
		} catch (failure: IllegalArgumentException) {
			throw PortableAmbientStepsFormatException(
				"Invalid portable Ambient Steps document",
				failure,
			)
		} catch (failure: IllegalStateException) {
			throw PortableAmbientStepsFormatException(
				"Invalid portable Ambient Steps document",
				failure,
			)
		}
	}

	private fun preflight(archive: PortableAmbientStepsArchiveV1): AmbientArchiveCounts {
		return try {
			val days = archive.days.size.toLong()
			if (days !in 1L..limits.maxDays.toLong()) {
				formatFailure("Portable Ambient Steps day count exceeds its bound")
			}
			var facts = 0L
			var gaps = 0L
			for (day in archive.days) {
				if (day.facts.size !in 1..limits.maxFactsPerDay) {
					formatFailure("Portable Ambient Steps day fact count exceeds its bound")
				}
				if (day.gaps.size > limits.maxGapsPerDay) {
					formatFailure("Portable Ambient Steps day gap count exceeds its bound")
				}
				if (day.partialCauses.size > PortableAmbientStepsPartialCause.entries.size) {
					formatFailure("Portable Ambient Steps partial-cause count exceeds its bound")
				}
				facts = Math.addExact(facts, day.facts.size.toLong())
				gaps = Math.addExact(gaps, day.gaps.size.toLong())
				if (facts > limits.maxFacts.toLong()) {
					formatFailure("Portable Ambient Steps total fact count exceeds its bound")
				}
				if (gaps > limits.maxGaps.toLong()) {
					formatFailure("Portable Ambient Steps total gap count exceeds its bound")
				}
			}
			AmbientArchiveCounts(days, facts, gaps)
		} catch (format: PortableAmbientStepsFormatException) {
			throw format
		} catch (failure: ArithmeticException) {
			throw PortableAmbientStepsFormatException(
				"Portable Ambient Steps count overflow",
				failure,
			)
		}
	}

	private suspend fun authenticatedSnapshot(
		archive: PortableAmbientStepsArchiveV1,
	): PortableAmbientStepsArchiveV1 = try {
		val days = buildList {
			for (day in archive.days) {
				currentCoroutineContext().ensureActive()
				val facts = buildList {
					for (fact in day.facts) {
						currentCoroutineContext().ensureActive()
						add(fact.copy())
					}
				}
				val gaps = buildList {
					for (gap in day.gaps) {
						currentCoroutineContext().ensureActive()
						add(gap.copy())
					}
				}
				add(
					day.copy(
						partialCauses = day.partialCauses.toList(),
						facts = facts,
						gaps = gaps,
					),
				)
			}
		}
		archive.copy(days = days)
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (format: PortableAmbientStepsFormatException) {
		throw format
	} catch (failure: ArithmeticException) {
		throw PortableAmbientStepsFormatException(
			"Invalid portable Ambient Steps archive",
			failure,
		)
	} catch (failure: IllegalArgumentException) {
		throw PortableAmbientStepsFormatException(
			"Invalid portable Ambient Steps archive",
			failure,
		)
	}

	private suspend fun writeArchive(
		outputStream: OutputStream,
		archive: PortableAmbientStepsArchiveV1,
	) {
		val writer = JsonWriter(
			OutputStreamWriter(
				BoundedAmbientOutputStream(outputStream, limits.maxFileBytes),
				Charsets.UTF_8,
			),
		).apply {
			isLenient = false
		}
		writer.beginObject()
		writer.name(FORMAT).value(archive.format)
		writer.name(SCHEMA_VERSION).value(archive.schemaVersion.toLong())
		writer.name(CONTENT_CHECKSUM).value(archive.contentChecksum.value)
		writer.name(DAYS).beginArray()
		for (day in archive.days) {
			currentCoroutineContext().ensureActive()
			writeDay(writer, day)
		}
		writer.endArray()
		writer.endObject()
		writer.flush()
	}

	private suspend fun writeDay(writer: JsonWriter, day: PortableAmbientStepsDayV1) {
		writer.beginObject()
		writer.name(IDENTITY).value(day.identity.value)
		writer.name(CONTENT_CHECKSUM).value(day.contentChecksum.value)
		writer.name(STRUCTURAL_EPOCH_DAY).value(day.structuralEpochDay)
		writer.name(STORED_ZONE_ID).value(day.storedZoneId)
		writer.name(STRUCTURAL_DAY_START_TIME_MS).value(day.structuralDayStartTimeMs)
		writer.name(STRUCTURAL_DAY_END_TIME_MS).value(day.structuralDayEndTimeMs)
		day.retainedFromTimeMs?.let { retained ->
			writer.name(RETAINED_FROM_TIME_MS).value(retained)
		}
		writer.name(COVERAGE).value(day.coverage.name)
		writer.name(PARTIAL_CAUSES).beginArray()
		day.partialCauses.forEach { cause -> writer.value(cause.name) }
		writer.endArray()
		writer.name(RETAINED_STEP_COUNT).value(day.retainedStepCount)
		writer.name(FACTS).beginArray()
		for (fact in day.facts) {
			currentCoroutineContext().ensureActive()
			writeFact(writer, fact)
		}
		writer.endArray()
		writer.name(GAPS).beginArray()
		for (gap in day.gaps) {
			currentCoroutineContext().ensureActive()
			writeGap(writer, gap)
		}
		writer.endArray()
		writer.endObject()
	}

	private fun writeFact(writer: JsonWriter, fact: PortableAmbientStepsFactV1) {
		writer.beginObject()
		writer.name(IDENTITY).value(fact.identity.value)
		writer.name(CONTENT_CHECKSUM).value(fact.contentChecksum.value)
		writer.name(INTERVAL_START_TIME_MS).value(fact.intervalStartTimeMs)
		writer.name(INTERVAL_END_TIME_MS).value(fact.intervalEndTimeMs)
		writer.name(STEP_COUNT).value(fact.stepCount)
		writer.endObject()
	}

	private fun writeGap(writer: JsonWriter, gap: PortableAmbientStepsGapV1) {
		writer.beginObject()
		writer.name(IDENTITY).value(gap.identity.value)
		writer.name(CONTENT_CHECKSUM).value(gap.contentChecksum.value)
		writer.name(INTERVAL_START_TIME_MS).value(gap.intervalStartTimeMs)
		writer.name(INTERVAL_END_TIME_MS).value(gap.intervalEndTimeMs)
		writer.name(REASON).value(gap.reason.name)
		writer.endObject()
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private suspend fun readArchive(
		reader: JsonReader,
		counts: AmbientDecodeCounts,
	): PortableAmbientStepsArchiveV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "document")
		reader.beginObject()
		val fields = hashSetOf<String>()
		var format: String? = null
		var schemaVersion: Int? = null
		var checksum: AmbientStepsPortableDigest? = null
		var days: List<PortableAmbientStepsDayV1>? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, fields, "portable Ambient Steps document")) {
				FORMAT -> format = boundedString(reader, FORMAT, MAX_FORMAT_LENGTH)
				SCHEMA_VERSION -> schemaVersion = exactInt(reader, SCHEMA_VERSION)
				CONTENT_CHECKSUM -> checksum = digest(reader, CONTENT_CHECKSUM)
				DAYS -> {
					if (format != AmbientStepsPortableFormatV1.FORMAT ||
						schemaVersion != AmbientStepsPortableFormatV1.SCHEMA_VERSION ||
						checksum == null
					) {
						formatFailure("Portable Ambient Steps header must precede days")
					}
					days = readArray(reader, DAYS, limits.maxDays) {
						currentCoroutineContext().ensureActive()
						counts.days = Math.addExact(counts.days, 1)
						readDay(reader, counts)
					}
				}
				else -> formatFailure("Unknown portable Ambient Steps document field")
			}
		}
		reader.endObject()
		return PortableAmbientStepsArchiveV1(
			format = format ?: formatFailure("Portable Ambient Steps format is missing"),
			schemaVersion = schemaVersion
				?: formatFailure("Portable Ambient Steps schema version is missing"),
			contentChecksum = checksum
				?: formatFailure("Portable Ambient Steps archive checksum is missing"),
			days = days ?: formatFailure("Portable Ambient Steps days are missing"),
		)
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private suspend fun readDay(
		reader: JsonReader,
		counts: AmbientDecodeCounts,
	): PortableAmbientStepsDayV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "day")
		reader.beginObject()
		val fields = hashSetOf<String>()
		var identity: AmbientStepsPortableOpaqueIdentity? = null
		var checksum: AmbientStepsPortableDigest? = null
		var structuralEpochDay: Long? = null
		var storedZoneId: String? = null
		var startTimeMs: Long? = null
		var endTimeMs: Long? = null
		var retainedFromTimeMs: Long? = null
		var coverage: PortableAmbientStepsCoverage? = null
		var partialCauses: List<PortableAmbientStepsPartialCause>? = null
		var retainedStepCount: Long? = null
		var facts: List<PortableAmbientStepsFactV1>? = null
		var gaps: List<PortableAmbientStepsGapV1>? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, fields, "portable Ambient Steps day")) {
				IDENTITY -> identity = identity(reader, IDENTITY)
				CONTENT_CHECKSUM -> checksum = digest(reader, CONTENT_CHECKSUM)
				STRUCTURAL_EPOCH_DAY -> structuralEpochDay = exactLong(reader, STRUCTURAL_EPOCH_DAY)
				STORED_ZONE_ID -> storedZoneId = boundedString(
					reader,
					STORED_ZONE_ID,
					AmbientStepsPortableFormatV1.MAX_ZONE_ID_LENGTH,
				)
				STRUCTURAL_DAY_START_TIME_MS -> startTimeMs = exactLong(
					reader,
					STRUCTURAL_DAY_START_TIME_MS,
				)
				STRUCTURAL_DAY_END_TIME_MS -> endTimeMs = exactLong(
					reader,
					STRUCTURAL_DAY_END_TIME_MS,
				)
				RETAINED_FROM_TIME_MS -> retainedFromTimeMs = exactLong(
					reader,
					RETAINED_FROM_TIME_MS,
				)
				COVERAGE -> coverage = enumValue(reader, COVERAGE)
				PARTIAL_CAUSES -> partialCauses = readArray(
					reader,
					PARTIAL_CAUSES,
					PortableAmbientStepsPartialCause.entries.size,
				) {
					enumValue(reader, PARTIAL_CAUSES)
				}
				RETAINED_STEP_COUNT -> retainedStepCount = exactLong(reader, RETAINED_STEP_COUNT)
				FACTS -> facts = readArray(reader, FACTS, limits.maxFactsPerDay) {
					currentCoroutineContext().ensureActive()
					counts.facts = Math.addExact(counts.facts, 1)
					if (counts.facts > limits.maxFacts) {
						formatFailure("Portable Ambient Steps total fact count exceeds its bound")
					}
					readFact(reader)
				}
				GAPS -> gaps = readArray(reader, GAPS, limits.maxGapsPerDay) {
					currentCoroutineContext().ensureActive()
					counts.gaps = Math.addExact(counts.gaps, 1)
					if (counts.gaps > limits.maxGaps) {
						formatFailure("Portable Ambient Steps total gap count exceeds its bound")
					}
					readGap(reader)
				}
				else -> formatFailure("Unknown portable Ambient Steps day field")
			}
		}
		reader.endObject()
		return PortableAmbientStepsDayV1(
			identity = identity ?: formatFailure("Portable Ambient Steps day identity is missing"),
			contentChecksum = checksum
				?: formatFailure("Portable Ambient Steps day checksum is missing"),
			structuralEpochDay = structuralEpochDay
				?: formatFailure("Portable Ambient Steps structural day is missing"),
			storedZoneId = storedZoneId
				?: formatFailure("Portable Ambient Steps stored zone is missing"),
			structuralDayStartTimeMs = startTimeMs
				?: formatFailure("Portable Ambient Steps day start is missing"),
			structuralDayEndTimeMs = endTimeMs
				?: formatFailure("Portable Ambient Steps day end is missing"),
			retainedFromTimeMs = retainedFromTimeMs,
			coverage = coverage ?: formatFailure("Portable Ambient Steps coverage is missing"),
			partialCauses = partialCauses
				?: formatFailure("Portable Ambient Steps partial causes are missing"),
			retainedStepCount = retainedStepCount
				?: formatFailure("Portable Ambient Steps retained count is missing"),
			facts = facts ?: formatFailure("Portable Ambient Steps facts are missing"),
			gaps = gaps ?: formatFailure("Portable Ambient Steps gaps are missing"),
		)
	}

	private fun readFact(reader: JsonReader): PortableAmbientStepsFactV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "fact")
		reader.beginObject()
		val fields = hashSetOf<String>()
		var identity: AmbientStepsPortableOpaqueIdentity? = null
		var checksum: AmbientStepsPortableDigest? = null
		var startTimeMs: Long? = null
		var endTimeMs: Long? = null
		var stepCount: Long? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, fields, "portable Ambient Steps fact")) {
				IDENTITY -> identity = identity(reader, IDENTITY)
				CONTENT_CHECKSUM -> checksum = digest(reader, CONTENT_CHECKSUM)
				INTERVAL_START_TIME_MS -> startTimeMs = exactLong(reader, INTERVAL_START_TIME_MS)
				INTERVAL_END_TIME_MS -> endTimeMs = exactLong(reader, INTERVAL_END_TIME_MS)
				STEP_COUNT -> stepCount = exactLong(reader, STEP_COUNT)
				else -> formatFailure("Unknown portable Ambient Steps fact field")
			}
		}
		reader.endObject()
		return PortableAmbientStepsFactV1(
			identity = identity ?: formatFailure("Portable Ambient Steps fact identity is missing"),
			contentChecksum = checksum
				?: formatFailure("Portable Ambient Steps fact checksum is missing"),
			intervalStartTimeMs = startTimeMs
				?: formatFailure("Portable Ambient Steps fact start is missing"),
			intervalEndTimeMs = endTimeMs
				?: formatFailure("Portable Ambient Steps fact end is missing"),
			stepCount = stepCount ?: formatFailure("Portable Ambient Steps count is missing"),
		)
	}

	private fun readGap(reader: JsonReader): PortableAmbientStepsGapV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "gap")
		reader.beginObject()
		val fields = hashSetOf<String>()
		var identity: AmbientStepsPortableOpaqueIdentity? = null
		var checksum: AmbientStepsPortableDigest? = null
		var startTimeMs: Long? = null
		var endTimeMs: Long? = null
		var reason: PortableAmbientStepsGapReason? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, fields, "portable Ambient Steps gap")) {
				IDENTITY -> identity = identity(reader, IDENTITY)
				CONTENT_CHECKSUM -> checksum = digest(reader, CONTENT_CHECKSUM)
				INTERVAL_START_TIME_MS -> startTimeMs = exactLong(reader, INTERVAL_START_TIME_MS)
				INTERVAL_END_TIME_MS -> endTimeMs = exactLong(reader, INTERVAL_END_TIME_MS)
				REASON -> reason = enumValue(reader, REASON)
				else -> formatFailure("Unknown portable Ambient Steps gap field")
			}
		}
		reader.endObject()
		return PortableAmbientStepsGapV1(
			identity = identity ?: formatFailure("Portable Ambient Steps gap identity is missing"),
			contentChecksum = checksum
				?: formatFailure("Portable Ambient Steps gap checksum is missing"),
			intervalStartTimeMs = startTimeMs
				?: formatFailure("Portable Ambient Steps gap start is missing"),
			intervalEndTimeMs = endTimeMs
				?: formatFailure("Portable Ambient Steps gap end is missing"),
			reason = reason ?: formatFailure("Portable Ambient Steps gap reason is missing"),
		)
	}

	private fun claimArchive(archive: PortableAmbientStepsArchiveV1) {
		val claims = mutableMapOf<AmbientStepsPortableOpaqueIdentity, AmbientIdentityClaim>()
		claim(
			claims,
			archive.identity,
			AmbientIdentityClaim(AmbientStepsPortableIdentityKind.ARCHIVE, DOCUMENT_OWNER),
		)
		archive.days.forEach { day ->
			claim(
				claims,
				day.identity,
				AmbientIdentityClaim(AmbientStepsPortableIdentityKind.DAY, archive.identity.value),
			)
			claim(
				claims,
				day.deletionScopeIdentity,
				AmbientIdentityClaim(
					AmbientStepsPortableIdentityKind.DELETION_SCOPE,
					day.identity.value,
				),
			)
			day.facts.forEach { fact ->
				claim(
					claims,
					fact.identity,
					AmbientIdentityClaim(AmbientStepsPortableIdentityKind.FACT, day.identity.value),
				)
			}
			day.gaps.forEach { gap ->
				claim(
					claims,
					gap.identity,
					AmbientIdentityClaim(AmbientStepsPortableIdentityKind.GAP, day.identity.value),
				)
			}
		}
	}

	private fun claim(
		claims: MutableMap<AmbientStepsPortableOpaqueIdentity, AmbientIdentityClaim>,
		identity: AmbientStepsPortableOpaqueIdentity,
		claim: AmbientIdentityClaim,
	) {
		val previous = claims.putIfAbsent(identity, claim)
		if (previous != null) {
			formatFailure(
				if (previous == claim) {
					"Portable Ambient Steps document repeats an opaque identity"
				} else {
					"Portable Ambient Steps opaque identity changes kind or owner"
				},
			)
		}
	}

	private fun identity(
		reader: JsonReader,
		field: String,
	): AmbientStepsPortableOpaqueIdentity = AmbientStepsPortableOpaqueIdentity(
		boundedString(reader, field, SHA_256_LENGTH),
	)

	private fun digest(reader: JsonReader, field: String): AmbientStepsPortableDigest =
		AmbientStepsPortableDigest(boundedString(reader, field, SHA_256_LENGTH))

	private inline fun <reified T : Enum<T>> enumValue(reader: JsonReader, field: String): T {
		val value = boundedString(reader, field, MAX_ENUM_LENGTH)
		return enumValues<T>().firstOrNull { candidate -> candidate.name == value }
			?: formatFailure("Portable Ambient Steps field $field has an unknown value")
	}

	private fun exactInt(reader: JsonReader, field: String): Int {
		val value = exactLong(reader, field)
		if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
			formatFailure("Portable Ambient Steps field $field exceeds the 32-bit integer bound")
		}
		return value.toInt()
	}

	private fun exactLong(reader: JsonReader, field: String): Long {
		expect(reader, JsonToken.NUMBER, field)
		val raw = reader.nextString()
		if (!JSON_INTEGER.matches(raw)) {
			formatFailure("Portable Ambient Steps field $field is not an integer")
		}
		return raw.toLongOrNull()
			?: formatFailure("Portable Ambient Steps field $field exceeds the integer bound")
	}

	private fun boundedString(
		reader: JsonReader,
		field: String,
		maximumLength: Int,
	): String {
		expect(reader, JsonToken.STRING, field)
		val value = reader.nextString()
		if (value.length > maximumLength) {
			formatFailure("Portable Ambient Steps field $field exceeds its length bound")
		}
		return value
	}

	private suspend fun <T> readArray(
		reader: JsonReader,
		field: String,
		maximumCount: Int,
		readItem: suspend () -> T,
	): List<T> {
		expect(reader, JsonToken.BEGIN_ARRAY, field)
		reader.beginArray()
		val values = mutableListOf<T>()
		while (reader.hasNext()) {
			if (values.size >= maximumCount) {
				formatFailure("Portable Ambient Steps field $field exceeds its item bound")
			}
			values += readItem()
		}
		reader.endArray()
		return values
	}

	private fun uniqueName(
		reader: JsonReader,
		fields: MutableSet<String>,
		context: String,
	): String {
		val name = reader.nextName()
		if (!fields.add(name)) {
			formatFailure("Duplicate field in $context")
		}
		return name
	}

	private fun expect(reader: JsonReader, token: JsonToken, context: String) {
		if (reader.peek() != token) {
			formatFailure("Portable Ambient Steps $context has the wrong JSON type")
		}
	}

	private companion object {
		const val FORMAT = "format"
		const val SCHEMA_VERSION = "schemaVersion"
		const val CONTENT_CHECKSUM = "contentChecksum"
		const val DAYS = "days"
		const val IDENTITY = "identity"
		const val STRUCTURAL_EPOCH_DAY = "structuralEpochDay"
		const val STORED_ZONE_ID = "storedZoneId"
		const val STRUCTURAL_DAY_START_TIME_MS = "structuralDayStartTimeMs"
		const val STRUCTURAL_DAY_END_TIME_MS = "structuralDayEndTimeMs"
		const val RETAINED_FROM_TIME_MS = "retainedFromTimeMs"
		const val COVERAGE = "coverage"
		const val PARTIAL_CAUSES = "partialCauses"
		const val RETAINED_STEP_COUNT = "retainedStepCount"
		const val FACTS = "facts"
		const val GAPS = "gaps"
		const val INTERVAL_START_TIME_MS = "intervalStartTimeMs"
		const val INTERVAL_END_TIME_MS = "intervalEndTimeMs"
		const val STEP_COUNT = "stepCount"
		const val REASON = "reason"
		const val MAX_FORMAT_LENGTH = 64
		const val MAX_ENUM_LENGTH = 64
		const val SHA_256_LENGTH = 71
		const val DOCUMENT_OWNER = "portable-ambient-steps-document-v1"
		val JSON_INTEGER = Regex("-?(0|[1-9][0-9]*)")
		val AMBIENT_TOKEN_LIMITS = PortableJsonTokenLimits(
			maxNameBytes = 64 * 6,
			maxStringBytes = AmbientStepsPortableFormatV1.MAX_ZONE_ID_LENGTH * 6,
			maxNumberBytes = 32,
			maxNestingDepth = 16,
			maxReadChunkBytes = 256,
		)
	}
}

internal data class PortableAmbientStepsDecodedArchive(
	val archive: PortableAmbientStepsArchiveV1,
	val metadata: PortableAmbientStepsImportMetadata,
)

internal data class PortableAmbientStepsJsonLimits(
	val maxFileBytes: Long = AmbientStepsPortableFormatV1.MAX_FILE_BYTES,
	val maxDays: Int = AmbientStepsPortableFormatV1.MAX_DAYS,
	val maxFacts: Int = AmbientStepsPortableFormatV1.MAX_FACTS,
	val maxGaps: Int = AmbientStepsPortableFormatV1.MAX_GAPS,
	val maxFactsPerDay: Int = AmbientStepsPortableFormatV1.MAX_FACTS_PER_DAY,
	val maxGapsPerDay: Int = AmbientStepsPortableFormatV1.MAX_GAPS_PER_DAY,
) {
	init {
		require(maxFileBytes in 1L..AmbientStepsPortableFormatV1.MAX_FILE_BYTES)
		require(maxDays in 1..AmbientStepsPortableFormatV1.MAX_DAYS)
		require(maxFacts in 1..AmbientStepsPortableFormatV1.MAX_FACTS)
		require(maxGaps in 0..AmbientStepsPortableFormatV1.MAX_GAPS)
		require(maxFactsPerDay in 1..AmbientStepsPortableFormatV1.MAX_FACTS_PER_DAY)
		require(maxGapsPerDay in 0..AmbientStepsPortableFormatV1.MAX_GAPS_PER_DAY)
	}
}

internal class PortableAmbientStepsFormatException(
	message: String,
	cause: Throwable? = null,
) : IOException(message, cause) {
	private companion object {
		const val serialVersionUID: Long = 1L
	}
}

private data class AmbientArchiveCounts(
	val days: Long,
	val facts: Long,
	val gaps: Long,
)

private data class AmbientDecodeCounts(
	var days: Int = 0,
	var facts: Int = 0,
	var gaps: Int = 0,
)

private data class AmbientIdentityClaim(
	val kind: AmbientStepsPortableIdentityKind,
	val owner: String,
)

private fun formatFailure(message: String): Nothing =
	throw PortableAmbientStepsFormatException(message)

private class BoundedAmbientInputStream(
	input: InputStream,
	private val maximumBytes: Long,
) : FilterInputStream(input) {
	var bytesRead: Long = 0L
		private set

	override fun read(): Int {
		val value = super.read()
		if (value >= 0) record(1L)
		return value
	}

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
		val count = super.read(buffer, offset, length)
		if (count > 0) record(count.toLong())
		return count
	}

	private fun record(count: Long) {
		if (bytesRead > maximumBytes - count) {
			formatFailure("Portable Ambient Steps document exceeds its byte bound")
		}
		bytesRead += count
	}
}

private class BoundedAmbientOutputStream(
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
			formatFailure("Portable Ambient Steps document exceeds its byte bound")
		}
		bytesWritten += count
	}
}
