package com.adsamcik.tracker.impexp.portable

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.PORTABLE_STEPS_ENTRY_ORDER
import com.adsamcik.tracker.stats.api.repository.PortableStepsCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsCompletenessV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableStepsDigest
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntrySink
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsIntegrity
import com.adsamcik.tracker.stats.api.repository.PortableStepsManifestV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableStepsProviderCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsPurpose
import com.adsamcik.tracker.stats.api.repository.PortableStepsRunV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsSessionMode
import com.adsamcik.tracker.stats.api.repository.PortableStepsSource
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV1
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Strict streaming codec for the standalone portable Steps schema v1.
 *
 * The codec owns no Room, provider, policy, consent, or lifecycle authority. It accepts only the
 * privacy-minimized schema vocabulary, verifies canonical semantic checksums before emitting an
 * entry, and leaves both streams open for their caller.
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class PortableStepsJsonV1Codec(
	private val limits: PortableStepsJsonLimits = PortableStepsJsonLimits(),
) {
	/**
	 * Writes the canonical envelope while [produce] streams already validated logical entries.
	 * A zero-entry product result writes no bytes. Failure after the first entry leaves an
	 * intentionally incomplete document for its caller to delete; cancellation is never converted
	 * into a format failure.
	 */
	@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
	suspend fun encode(
		outputStream: OutputStream,
		produce: suspend (PortableStepsEntrySink) -> ExportPortableStepsResult,
	): ExportPortableStepsResult {
		val bounded = BoundedOutputStream(outputStream, limits.maxFileBytes)
		val writer = JsonWriter(OutputStreamWriter(bounded, Charsets.UTF_8)).apply {
			isLenient = false
		}

		var count = 0
		var started = false
		var previous: PortableStepsEntryV1? = null
		val identities = hashSetOf<PortableStepsOpaqueIdentity>()
		val runIdentities = hashSetOf<PortableStepsOpaqueIdentity>()
		val deletionScopes = hashSetOf<PortableStepsDeletionScopeDigest>()
		val factIdentities = hashSetOf<PortableStepsOpaqueIdentity>()
		val result = produce(
			PortableStepsEntrySink { entry ->
				currentCoroutineContext().ensureActive()
				if (count >= limits.maxEntries) {
					formatFailure("Portable Steps entry count exceeds ${limits.maxEntries}")
				}
				if (!identities.add(entry.identity)) {
					formatFailure("Portable Steps document repeats a logical entry identity")
				}
				previous?.let { prior ->
					if (PORTABLE_STEPS_ENTRY_ORDER.compare(prior, entry) >= 0) {
						formatFailure("Portable Steps entries are not in canonical order")
					}
				}
				claimNestedIdentities(entry, runIdentities, deletionScopes, factIdentities)
				if (!started) {
					writer.beginObject()
					writer.name(FIELD_FORMAT).value(StepsPortableFormatV1.FORMAT)
					writer.name(FIELD_SCHEMA_VERSION)
						.value(StepsPortableFormatV1.SCHEMA_VERSION.toLong())
					writer.name(FIELD_ENTRIES).beginArray()
					started = true
				}
				writeEntry(writer, entry)
				previous = entry
				count++
			},
		)

		currentCoroutineContext().ensureActive()
		when (result) {
			is ExportPortableStepsResult.Exported -> {
				if (result.entryCount != count) {
					formatFailure("Portable Steps export result count does not match emitted entries")
				}
				if (!started) {
					formatFailure("Portable Steps export completed without an entry")
				}
				writer.endArray()
				writer.endObject()
				writer.flush()
			}
			else -> {
				if (count != 0) {
					formatFailure("Portable Steps export emitted entries for a non-success result")
				}
			}
		}
		return result
	}

	/**
	 * Reads and verifies one bounded entry at a time; no complete-document materialization occurs.
	 * A valid prefix can reach [sink] before a later file error, so the authoritative sink must keep
	 * its per-entry operation atomic and replay-safe.
	 */
	@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
	suspend fun decode(
		inputStream: InputStream,
		sink: PortableStepsEntrySink,
	): Int {
		val reader = JsonReader(
			InputStreamReader(BoundedInputStream(inputStream, limits.maxFileBytes), Charsets.UTF_8),
		).apply {
			isLenient = false
		}
		val seen = hashSetOf<String>()
		var hasFormat = false
		var hasSchemaVersion = false
		var hasEntries = false
		var count = 0
		var previous: PortableStepsEntryV1? = null
		val identities = hashSetOf<PortableStepsOpaqueIdentity>()
		val runIdentities = hashSetOf<PortableStepsOpaqueIdentity>()
		val deletionScopes = hashSetOf<PortableStepsDeletionScopeDigest>()
		val factIdentities = hashSetOf<PortableStepsOpaqueIdentity>()

		expect(reader, JsonToken.BEGIN_OBJECT, "portable Steps document")
		reader.beginObject()
		while (reader.hasNext()) {
			when (uniqueName(reader, seen, "portable Steps document")) {
				FIELD_FORMAT -> {
					val format = string(reader, FIELD_FORMAT, MAX_FORMAT_LENGTH)
					if (format != StepsPortableFormatV1.FORMAT) {
						formatFailure("Unsupported portable Steps format")
					}
					hasFormat = true
				}
				FIELD_SCHEMA_VERSION -> {
					val version = integer(reader, FIELD_SCHEMA_VERSION)
					if (version != StepsPortableFormatV1.SCHEMA_VERSION.toLong()) {
						formatFailure("Unsupported portable Steps schema version $version")
					}
					hasSchemaVersion = true
				}
				FIELD_ENTRIES -> {
					if (!hasFormat || !hasSchemaVersion) {
						formatFailure("Portable Steps header must precede entries")
					}
					expect(reader, JsonToken.BEGIN_ARRAY, FIELD_ENTRIES)
					reader.beginArray()
					while (reader.hasNext()) {
						currentCoroutineContext().ensureActive()
						if (count >= limits.maxEntries) {
							formatFailure("Portable Steps entry count exceeds ${limits.maxEntries}")
						}
						val entry = readEntry(reader)
						if (!identities.add(entry.identity)) {
							formatFailure("Portable Steps document repeats a logical entry identity")
						}
						previous?.let { prior ->
							if (PORTABLE_STEPS_ENTRY_ORDER.compare(prior, entry) >= 0) {
								formatFailure("Portable Steps entries are not in canonical order")
							}
						}
						claimNestedIdentities(
							entry,
							runIdentities,
							deletionScopes,
							factIdentities,
						)
						sink.emit(entry)
						previous = entry
						count++
					}
					reader.endArray()
					hasEntries = true
				}
				else -> formatFailure("Unknown portable Steps document field")
			}
		}
		reader.endObject()
		if (!hasFormat || !hasSchemaVersion || !hasEntries) {
			formatFailure("Portable Steps document is missing a required field")
		}
		if (count == 0) {
			formatFailure("Portable Steps document must contain at least one entry")
		}
		if (reader.peek() != JsonToken.END_DOCUMENT) {
			formatFailure("Portable Steps document has trailing content")
		}
		currentCoroutineContext().ensureActive()
		return count
	}

	private fun writeEntry(writer: JsonWriter, entry: PortableStepsEntryV1) {
		if (PortableStepsIntegrity.expectedEntryChecksum(entry) != entry.contentChecksum) {
			formatFailure("Portable Steps entry changed after validation")
		}
		if (entry.runs.size > limits.maxRunsPerEntry) {
			formatFailure("Portable Steps entry exceeds its run bound")
		}
		entry.runs.forEach { run ->
			if (run.manifests.size > limits.maxManifestsPerRun) {
				formatFailure("Portable Steps run exceeds its manifest bound")
			}
			if (run.facts.size > limits.maxFactsPerRun) {
				formatFailure("Portable Steps run exceeds its fact bound")
			}
		}
		writer.beginObject()
		writer.name(FIELD_IDENTITY).value(entry.identity.value)
		writer.name(FIELD_CONTENT_CHECKSUM).value(entry.contentChecksum.value)
		writer.name(FIELD_SESSION_MODE).value(entry.sessionMode.name)
		writer.name(FIELD_START_TIME_MS).value(entry.startTimeMs)
		writer.name(FIELD_END_TIME_MS).value(entry.endTimeMs)
		writer.name(FIELD_RUNS).beginArray()
		entry.runs.forEach { run -> writeRun(writer, run) }
		writer.endArray()
		writer.endObject()
	}

	private fun writeRun(writer: JsonWriter, run: PortableStepsRunV1) {
		writer.beginObject()
		writer.name(FIELD_IDENTITY).value(run.identity.value)
		writer.name(FIELD_DELETION_SCOPE_DIGEST).value(run.deletionScopeDigest.value)
		writer.name(FIELD_START_TIME_MS).value(run.startTimeMs)
		writer.name(FIELD_END_TIME_MS).value(run.endTimeMs)
		writer.name(FIELD_STORED_ZONE_ID).value(run.storedZoneId)
		writer.name(FIELD_MANIFESTS).beginArray()
		run.manifests.forEach { manifest -> writeManifest(writer, manifest) }
		writer.endArray()
		writer.name(FIELD_COMPLETENESS)
		writeCompleteness(writer, run.completeness)
		writer.name(FIELD_FACTS).beginArray()
		run.facts.forEach { fact -> writeFact(writer, fact) }
		writer.endArray()
		writer.endObject()
	}

	private fun writeManifest(writer: JsonWriter, manifest: PortableStepsManifestV1) {
		writer.beginObject()
		writer.name(FIELD_REVISION).value(manifest.revision)
		writer.name(FIELD_EFFECTIVE_WALL_TIME_MS).value(manifest.effectiveWallTimeMs)
		writer.name(FIELD_ORIGIN_SOURCE_POLICY_REVISION)
			.value(manifest.originSourcePolicyRevision)
		writer.name(FIELD_CAPTURE_CONSENT_EPOCH).value(manifest.captureConsentEpoch)
		writer.name(FIELD_SOURCE).value(manifest.source.name)
		writer.name(FIELD_PURPOSE).value(manifest.purpose.name)
		writer.endObject()
	}

	private fun writeCompleteness(
		writer: JsonWriter,
		completeness: PortableStepsCompletenessV1,
	) {
		writer.beginObject()
		writer.name(FIELD_CAPTURE_COVERAGE).value(completeness.captureCoverage.name)
		writer.name(FIELD_PROVIDER_COVERAGE).value(completeness.providerCoverage.name)
		writer.name(FIELD_APP_DRAIN_COMPLETE).value(completeness.appDrainComplete)
		writer.name(FIELD_STOP_COMPLETE).value(completeness.stopComplete)
		writer.name(FIELD_HAS_UNRESOLVED_PROVIDER_RANGE)
			.value(completeness.hasUnresolvedProviderRange)
		writer.endObject()
	}

	private fun writeFact(writer: JsonWriter, fact: PortableStepsFactV1) {
		writer.beginObject()
		writer.name(FIELD_IDENTITY).value(fact.identity.value)
		writer.name(FIELD_CONTENT_CHECKSUM).value(fact.contentChecksum.value)
		writer.name(FIELD_MANIFEST_REVISION).value(fact.manifestRevision)
		writer.name(FIELD_INTERVAL_START_TIME_MS).value(fact.intervalStartTimeMs)
		writer.name(FIELD_INTERVAL_END_TIME_MS).value(fact.intervalEndTimeMs)
		writer.name(FIELD_WALL_TIME_UNCERTAINTY_MS).value(fact.wallTimeUncertaintyMs)
		writer.name(FIELD_COVERAGE).value(fact.coverage.name)
		fact.stepCount?.let { count -> writer.name(FIELD_STEP_COUNT).value(count) }
		writer.endObject()
	}

	@Suppress("LongMethod")
	private fun readEntry(reader: JsonReader): PortableStepsEntryV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "portable Steps entry")
		reader.beginObject()
		val seen = hashSetOf<String>()
		var identity: PortableStepsOpaqueIdentity? = null
		var contentChecksum: PortableStepsDigest? = null
		var sessionMode: PortableStepsSessionMode? = null
		var startTimeMs: Long? = null
		var endTimeMs: Long? = null
		var runs: List<PortableStepsRunV1>? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, seen, "portable Steps entry")) {
				FIELD_IDENTITY -> identity = opaqueIdentity(reader, FIELD_IDENTITY)
				FIELD_CONTENT_CHECKSUM -> contentChecksum = digest(reader, FIELD_CONTENT_CHECKSUM)
				FIELD_SESSION_MODE -> sessionMode = enum(reader, FIELD_SESSION_MODE)
				FIELD_START_TIME_MS -> startTimeMs = integer(reader, FIELD_START_TIME_MS)
				FIELD_END_TIME_MS -> endTimeMs = integer(reader, FIELD_END_TIME_MS)
				FIELD_RUNS -> runs = array(reader, FIELD_RUNS, limits.maxRunsPerEntry, ::readRun)
				else -> formatFailure("Unknown portable Steps entry field")
			}
		}
		reader.endObject()
		return validated("portable Steps entry") {
			PortableStepsEntryV1(
				identity = required(identity, FIELD_IDENTITY),
				contentChecksum = required(contentChecksum, FIELD_CONTENT_CHECKSUM),
				sessionMode = required(sessionMode, FIELD_SESSION_MODE),
				startTimeMs = required(startTimeMs, FIELD_START_TIME_MS),
				endTimeMs = required(endTimeMs, FIELD_END_TIME_MS),
				runs = required(runs, FIELD_RUNS),
			)
		}
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private fun readRun(reader: JsonReader): PortableStepsRunV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "portable Steps run")
		reader.beginObject()
		val seen = hashSetOf<String>()
		var identity: PortableStepsOpaqueIdentity? = null
		var scopeDigest: PortableStepsDeletionScopeDigest? = null
		var startTimeMs: Long? = null
		var endTimeMs: Long? = null
		var storedZoneId: String? = null
		var manifests: List<PortableStepsManifestV1>? = null
		var completeness: PortableStepsCompletenessV1? = null
		var facts: List<PortableStepsFactV1>? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, seen, "portable Steps run")) {
				FIELD_IDENTITY -> identity = opaqueIdentity(reader, FIELD_IDENTITY)
				FIELD_DELETION_SCOPE_DIGEST -> scopeDigest = deletionScopeDigest(
					reader,
					FIELD_DELETION_SCOPE_DIGEST,
				)
				FIELD_START_TIME_MS -> startTimeMs = integer(reader, FIELD_START_TIME_MS)
				FIELD_END_TIME_MS -> endTimeMs = integer(reader, FIELD_END_TIME_MS)
				FIELD_STORED_ZONE_ID -> storedZoneId = string(
					reader,
					FIELD_STORED_ZONE_ID,
					StepsPortableFormatV1.MAX_ZONE_ID_LENGTH,
				)
				FIELD_MANIFESTS -> manifests = array(
					reader,
					FIELD_MANIFESTS,
					limits.maxManifestsPerRun,
					::readManifest,
				)
				FIELD_COMPLETENESS -> completeness = readCompleteness(reader)
				FIELD_FACTS -> facts = array(
					reader,
					FIELD_FACTS,
					limits.maxFactsPerRun,
					::readFact,
				)
				else -> formatFailure("Unknown portable Steps run field")
			}
		}
		reader.endObject()
		return validated("portable Steps run") {
			PortableStepsRunV1(
				identity = required(identity, FIELD_IDENTITY),
				deletionScopeDigest = required(
					scopeDigest,
					FIELD_DELETION_SCOPE_DIGEST,
				),
				startTimeMs = required(startTimeMs, FIELD_START_TIME_MS),
				endTimeMs = required(endTimeMs, FIELD_END_TIME_MS),
				storedZoneId = required(storedZoneId, FIELD_STORED_ZONE_ID),
				manifests = required(manifests, FIELD_MANIFESTS),
				completeness = required(completeness, FIELD_COMPLETENESS),
				facts = required(facts, FIELD_FACTS),
			)
		}
	}

	@Suppress("LongMethod")
	private fun readManifest(reader: JsonReader): PortableStepsManifestV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "portable Steps manifest")
		reader.beginObject()
		val seen = hashSetOf<String>()
		var revision: Long? = null
		var effectiveWallTimeMs: Long? = null
		var originSourcePolicyRevision: Long? = null
		var captureConsentEpoch: Long? = null
		var source: PortableStepsSource? = null
		var purpose: PortableStepsPurpose? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, seen, "portable Steps manifest")) {
				FIELD_REVISION -> revision = integer(reader, FIELD_REVISION)
				FIELD_EFFECTIVE_WALL_TIME_MS -> effectiveWallTimeMs = integer(
					reader,
					FIELD_EFFECTIVE_WALL_TIME_MS,
				)
				FIELD_ORIGIN_SOURCE_POLICY_REVISION -> originSourcePolicyRevision = integer(
					reader,
					FIELD_ORIGIN_SOURCE_POLICY_REVISION,
				)
				FIELD_CAPTURE_CONSENT_EPOCH -> captureConsentEpoch = integer(
					reader,
					FIELD_CAPTURE_CONSENT_EPOCH,
				)
				FIELD_SOURCE -> source = enum(reader, FIELD_SOURCE)
				FIELD_PURPOSE -> purpose = enum(reader, FIELD_PURPOSE)
				else -> formatFailure("Unknown portable Steps manifest field")
			}
		}
		reader.endObject()
		return validated("portable Steps manifest") {
			PortableStepsManifestV1(
				revision = required(revision, FIELD_REVISION),
				effectiveWallTimeMs = required(effectiveWallTimeMs, FIELD_EFFECTIVE_WALL_TIME_MS),
				originSourcePolicyRevision = required(
					originSourcePolicyRevision,
					FIELD_ORIGIN_SOURCE_POLICY_REVISION,
				),
				captureConsentEpoch = required(captureConsentEpoch, FIELD_CAPTURE_CONSENT_EPOCH),
				source = required(source, FIELD_SOURCE),
				purpose = required(purpose, FIELD_PURPOSE),
			)
		}
	}

	@Suppress("LongMethod")
	private fun readCompleteness(reader: JsonReader): PortableStepsCompletenessV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "portable Steps completeness")
		reader.beginObject()
		val seen = hashSetOf<String>()
		var captureCoverage: PortableStepsCaptureCoverage? = null
		var providerCoverage: PortableStepsProviderCoverage? = null
		var appDrainComplete: Boolean? = null
		var stopComplete: Boolean? = null
		var hasUnresolvedProviderRange: Boolean? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, seen, "portable Steps completeness")) {
				FIELD_CAPTURE_COVERAGE -> captureCoverage = enum(reader, FIELD_CAPTURE_COVERAGE)
				FIELD_PROVIDER_COVERAGE -> providerCoverage = enum(reader, FIELD_PROVIDER_COVERAGE)
				FIELD_APP_DRAIN_COMPLETE -> appDrainComplete = boolean(reader, FIELD_APP_DRAIN_COMPLETE)
				FIELD_STOP_COMPLETE -> stopComplete = boolean(reader, FIELD_STOP_COMPLETE)
				FIELD_HAS_UNRESOLVED_PROVIDER_RANGE -> hasUnresolvedProviderRange = boolean(
					reader,
					FIELD_HAS_UNRESOLVED_PROVIDER_RANGE,
				)
				else -> formatFailure("Unknown portable Steps completeness field")
			}
		}
		reader.endObject()
		return validated("portable Steps completeness") {
			PortableStepsCompletenessV1(
				captureCoverage = required(captureCoverage, FIELD_CAPTURE_COVERAGE),
				providerCoverage = required(providerCoverage, FIELD_PROVIDER_COVERAGE),
				appDrainComplete = required(appDrainComplete, FIELD_APP_DRAIN_COMPLETE),
				stopComplete = required(stopComplete, FIELD_STOP_COMPLETE),
				hasUnresolvedProviderRange = required(
					hasUnresolvedProviderRange,
					FIELD_HAS_UNRESOLVED_PROVIDER_RANGE,
				),
			)
		}
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private fun readFact(reader: JsonReader): PortableStepsFactV1 {
		expect(reader, JsonToken.BEGIN_OBJECT, "portable Steps fact")
		reader.beginObject()
		val seen = hashSetOf<String>()
		var identity: PortableStepsOpaqueIdentity? = null
		var contentChecksum: PortableStepsDigest? = null
		var manifestRevision: Long? = null
		var intervalStartTimeMs: Long? = null
		var intervalEndTimeMs: Long? = null
		var wallTimeUncertaintyMs: Long? = null
		var coverage: PortableStepsFactCoverage? = null
		var stepCount: Long? = null
		while (reader.hasNext()) {
			when (uniqueName(reader, seen, "portable Steps fact")) {
				FIELD_IDENTITY -> identity = opaqueIdentity(reader, FIELD_IDENTITY)
				FIELD_CONTENT_CHECKSUM -> contentChecksum = digest(reader, FIELD_CONTENT_CHECKSUM)
				FIELD_MANIFEST_REVISION -> manifestRevision = integer(reader, FIELD_MANIFEST_REVISION)
				FIELD_INTERVAL_START_TIME_MS -> intervalStartTimeMs = integer(
					reader,
					FIELD_INTERVAL_START_TIME_MS,
				)
				FIELD_INTERVAL_END_TIME_MS -> intervalEndTimeMs = integer(
					reader,
					FIELD_INTERVAL_END_TIME_MS,
				)
				FIELD_WALL_TIME_UNCERTAINTY_MS -> wallTimeUncertaintyMs = integer(
					reader,
					FIELD_WALL_TIME_UNCERTAINTY_MS,
				)
				FIELD_COVERAGE -> coverage = enum(reader, FIELD_COVERAGE)
				FIELD_STEP_COUNT -> stepCount = integer(reader, FIELD_STEP_COUNT)
				else -> formatFailure("Unknown portable Steps fact field")
			}
		}
		reader.endObject()
		return validated("portable Steps fact") {
			PortableStepsFactV1(
				identity = required(identity, FIELD_IDENTITY),
				contentChecksum = required(contentChecksum, FIELD_CONTENT_CHECKSUM),
				manifestRevision = required(manifestRevision, FIELD_MANIFEST_REVISION),
				intervalStartTimeMs = required(intervalStartTimeMs, FIELD_INTERVAL_START_TIME_MS),
				intervalEndTimeMs = required(intervalEndTimeMs, FIELD_INTERVAL_END_TIME_MS),
				wallTimeUncertaintyMs = required(
					wallTimeUncertaintyMs,
					FIELD_WALL_TIME_UNCERTAINTY_MS,
				),
				coverage = required(coverage, FIELD_COVERAGE),
				stepCount = stepCount,
			)
		}
	}

	private fun opaqueIdentity(reader: JsonReader, field: String): PortableStepsOpaqueIdentity =
		validated(field) {
			PortableStepsOpaqueIdentity(string(reader, field, SHA_256_LENGTH))
		}

	private fun digest(reader: JsonReader, field: String): PortableStepsDigest = validated(field) {
		PortableStepsDigest(string(reader, field, SHA_256_LENGTH))
	}

	private fun deletionScopeDigest(
		reader: JsonReader,
		field: String,
	): PortableStepsDeletionScopeDigest = validated(field) {
		PortableStepsDeletionScopeDigest(string(reader, field, SHA_256_HEX_LENGTH))
	}

	private fun claimNestedIdentities(
		entry: PortableStepsEntryV1,
		runIdentities: MutableSet<PortableStepsOpaqueIdentity>,
		deletionScopes: MutableSet<PortableStepsDeletionScopeDigest>,
		factIdentities: MutableSet<PortableStepsOpaqueIdentity>,
	) {
		entry.runs.forEach { run ->
			if (!runIdentities.add(run.identity)) {
				formatFailure("Portable Steps document repeats a physical run identity")
			}
			if (!deletionScopes.add(run.deletionScopeDigest)) {
				formatFailure("Portable Steps document repeats a deletion scope")
			}
			run.facts.forEach { fact ->
				if (!factIdentities.add(fact.identity)) {
					formatFailure("Portable Steps document repeats a fact identity")
				}
			}
		}
	}

	private inline fun <reified T : Enum<T>> enum(reader: JsonReader, field: String): T {
		val value = string(reader, field, MAX_ENUM_LENGTH)
		return enumValues<T>().firstOrNull { candidate -> candidate.name == value }
			?: formatFailure("Portable Steps field $field has an unknown value")
	}

	private fun string(reader: JsonReader, field: String, maxLength: Int): String {
		expect(reader, JsonToken.STRING, field)
		val value = reader.nextString()
		if (value.length > maxLength) {
			formatFailure("Portable Steps field $field exceeds its length bound")
		}
		return value
	}

	private fun integer(reader: JsonReader, field: String): Long {
		expect(reader, JsonToken.NUMBER, field)
		val raw = reader.nextString()
		if (!JSON_INTEGER.matches(raw)) {
			formatFailure("Portable Steps field $field is not an integer")
		}
		return raw.toLongOrNull()
			?: formatFailure("Portable Steps field $field exceeds the integer bound")
	}

	private fun boolean(reader: JsonReader, field: String): Boolean {
		expect(reader, JsonToken.BOOLEAN, field)
		return reader.nextBoolean()
	}

	private fun <T> array(
		reader: JsonReader,
		field: String,
		maximumCount: Int,
		readItem: (JsonReader) -> T,
	): List<T> {
		expect(reader, JsonToken.BEGIN_ARRAY, field)
		reader.beginArray()
		val result = mutableListOf<T>()
		while (reader.hasNext()) {
			if (result.size >= maximumCount) {
				formatFailure("Portable Steps field $field exceeds its item bound")
			}
			result += readItem(reader)
		}
		reader.endArray()
		return result
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
			formatFailure("Portable Steps $context has the wrong JSON type")
		}
	}

	private fun <T : Any> required(value: T?, field: String): T =
		value ?: formatFailure("Portable Steps object is missing $field")

	private inline fun <T> validated(context: String, block: () -> T): T = try {
		block()
	} catch (failure: IllegalArgumentException) {
		throw PortableStepsJsonException("Invalid $context", failure)
	}

	private companion object {
		const val FIELD_FORMAT = "format"
		const val FIELD_SCHEMA_VERSION = "schemaVersion"
		const val FIELD_ENTRIES = "entries"
		const val FIELD_IDENTITY = "identity"
		const val FIELD_DELETION_SCOPE_DIGEST = "deletionScopeDigest"
		const val FIELD_CONTENT_CHECKSUM = "contentChecksum"
		const val FIELD_SESSION_MODE = "sessionMode"
		const val FIELD_START_TIME_MS = "startTimeMs"
		const val FIELD_END_TIME_MS = "endTimeMs"
		const val FIELD_RUNS = "runs"
		const val FIELD_STORED_ZONE_ID = "storedZoneId"
		const val FIELD_MANIFESTS = "manifests"
		const val FIELD_COMPLETENESS = "completeness"
		const val FIELD_FACTS = "facts"
		const val FIELD_REVISION = "revision"
		const val FIELD_EFFECTIVE_WALL_TIME_MS = "effectiveWallTimeMs"
		const val FIELD_ORIGIN_SOURCE_POLICY_REVISION = "originSourcePolicyRevision"
		const val FIELD_CAPTURE_CONSENT_EPOCH = "captureConsentEpoch"
		const val FIELD_SOURCE = "source"
		const val FIELD_PURPOSE = "purpose"
		const val FIELD_CAPTURE_COVERAGE = "captureCoverage"
		const val FIELD_PROVIDER_COVERAGE = "providerCoverage"
		const val FIELD_APP_DRAIN_COMPLETE = "appDrainComplete"
		const val FIELD_STOP_COMPLETE = "stopComplete"
		const val FIELD_HAS_UNRESOLVED_PROVIDER_RANGE = "hasUnresolvedProviderRange"
		const val FIELD_MANIFEST_REVISION = "manifestRevision"
		const val FIELD_INTERVAL_START_TIME_MS = "intervalStartTimeMs"
		const val FIELD_INTERVAL_END_TIME_MS = "intervalEndTimeMs"
		const val FIELD_WALL_TIME_UNCERTAINTY_MS = "wallTimeUncertaintyMs"
		const val FIELD_COVERAGE = "coverage"
		const val FIELD_STEP_COUNT = "stepCount"
		const val MAX_FORMAT_LENGTH = 64
		const val MAX_ENUM_LENGTH = 64
		const val SHA_256_LENGTH = 71
		const val SHA_256_HEX_LENGTH = 64
		val JSON_INTEGER = Regex("-?(0|[1-9][0-9]*)")
	}
}

/** Codec-local limits may be lowered by tests but never raised above the wire-domain contract. */
internal data class PortableStepsJsonLimits(
	val maxFileBytes: Long = StepsPortableFormatV1.MAX_FILE_BYTES,
	val maxEntries: Int = StepsPortableFormatV1.MAX_ENTRIES,
	val maxRunsPerEntry: Int = StepsPortableFormatV1.MAX_RUNS_PER_ENTRY,
	val maxManifestsPerRun: Int = StepsPortableFormatV1.MAX_MANIFESTS_PER_RUN,
	val maxFactsPerRun: Int = StepsPortableFormatV1.MAX_FACTS_PER_RUN,
) {
	init {
		require(maxFileBytes > 0L && maxFileBytes <= StepsPortableFormatV1.MAX_FILE_BYTES)
		require(maxEntries in 1..StepsPortableFormatV1.MAX_ENTRIES)
		require(maxRunsPerEntry in 1..StepsPortableFormatV1.MAX_RUNS_PER_ENTRY)
		require(maxManifestsPerRun in 1..StepsPortableFormatV1.MAX_MANIFESTS_PER_RUN)
		require(maxFactsPerRun in 1..StepsPortableFormatV1.MAX_FACTS_PER_RUN)
	}
}

internal class PortableStepsJsonException(
	message: String,
	cause: Throwable? = null,
) : IOException(message, cause) {
	private companion object {
		const val serialVersionUID: Long = 1L
	}
}

private fun formatFailure(message: String): Nothing = throw PortableStepsJsonException(message)

private class BoundedInputStream(
	input: InputStream,
	private val maximumBytes: Long,
) : FilterInputStream(input) {
	private var bytesRead = 0L

	override fun read(): Int {
		val value = super.read()
		if (value >= 0) {
			record(1L)
		}
		return value
	}

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
		val count = super.read(buffer, offset, length)
		if (count > 0) {
			record(count.toLong())
		}
		return count
	}

	private fun record(count: Long) {
		bytesRead += count
		if (bytesRead > maximumBytes) {
			formatFailure("Portable Steps document exceeds its byte bound")
		}
	}
}

private class BoundedOutputStream(
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
			formatFailure("Portable Steps document exceeds its byte bound")
		}
		bytesWritten += count
	}
}
