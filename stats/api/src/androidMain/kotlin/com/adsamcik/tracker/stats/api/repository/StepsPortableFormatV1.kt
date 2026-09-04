package com.adsamcik.tracker.stats.api.repository

import java.security.MessageDigest
import java.time.DateTimeException
import java.time.ZoneId

/** Stable envelope constants for the source-local portable Steps interchange. */
object StepsPortableFormatV1 {
	const val FORMAT: String = "tracker-portable-steps"
	const val SCHEMA_VERSION: Int = 1
	const val FILE_EXTENSION: String = "trackersteps"
	const val MIME_TYPE: String = "application/vnd.adsamcik.tracker.steps+json"

	/** Defensive interchange bounds; these are not provider or database retention limits. */
	const val MAX_FILE_BYTES: Long = 64L * 1024L * 1024L
	const val MAX_ENTRIES: Int = 10_000
	const val MAX_RUNS_PER_ENTRY: Int = 64
	const val MAX_MANIFESTS_PER_RUN: Int = 256
	// This bounds latest fact states carried by one portable run. Product readers must separately
	// preflight historical correction revisions required by deletion and day-repair authority.
	const val MAX_FACTS_PER_RUN: Int = 2_048
	const val MAX_ORIGIN_IDENTITY_LENGTH: Int = 4_096
	const val MAX_ZONE_ID_LENGTH: Int = 128
}

/** Privacy-safe identity kind used to namespace hashes of local durable identities. */
enum class PortableStepsIdentityKind {
	LOGICAL_ENTRY,
	PHYSICAL_RUN,
	FACT,
}

/**
 * Opaque, replay-stable identity. The source identifier is never serialized in clear text.
 *
 * Identity and content checksum remain separate so an importer can distinguish an exact replay
 * from the same semantic identity carrying different content.
 */
@JvmInline
value class PortableStepsOpaqueIdentity(val value: String) {
	init {
		require(SHA_256_VALUE.matches(value)) { "Portable Steps identity must be a SHA-256 value" }
	}

	/** Privacy-preserving derivation for local source identities. */
	companion object {
		/**
		 * Derives a stable kind-namespaced identity without exposing [localIdentity]. Already portable
		 * identities must be retained as values and never derived a second time.
		 */
		fun derive(
			kind: PortableStepsIdentityKind,
			localIdentity: String,
		): PortableStepsOpaqueIdentity {
			require(localIdentity.isNotBlank()) { "Local identity must not be blank" }
			require(localIdentity.length <= StepsPortableFormatV1.MAX_ORIGIN_IDENTITY_LENGTH) {
				"Local identity exceeds the portable Steps bound"
			}
			return PortableStepsOpaqueIdentity(
				PortableStepsIntegrity.digest(
					namespace = "tracker-portable-steps-identity-v1",
					value = listOf(kind.name, localIdentity),
				).value,
			)
		}
	}
}

/** Canonical SHA-256 digest stored in the wire format. */
@JvmInline
value class PortableStepsDigest(val value: String) {
	init {
		require(SHA_256_VALUE.matches(value)) { "Portable Steps checksum must be a SHA-256 value" }
	}
}

/**
 * Opaque exact logical-entry/physical-run deletion scope.
 *
 * Unlike portable identities and content checksums, this value deliberately uses the existing
 * durable source-deletion-fence representation: 64 lowercase hexadecimal characters without a
 * prefix. An importer can therefore reject a pre-delete export without learning either local id.
 */
@JvmInline
value class PortableStepsDeletionScopeDigest(val value: String) {
	init {
		require(SHA_256_HEX.matches(value)) {
			"Portable Steps deletion scope must be a lowercase SHA-256 digest"
		}
	}

	/** Exact derivation compatible with the durable v28 deletion-fence key. */
	companion object {
		/**
		 * Derives the exact v28 Steps/session-capture deletion-fence key. Imported values must be
		 * retained verbatim; they cannot be reconstructed from the separately opaque wire identities.
		 */
		fun derive(
			logicalTrackingId: String,
			serviceRunId: String,
		): PortableStepsDeletionScopeDigest = PortableStepsIntegrity.deletionScopeDigest(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
	}
}

/** Only session modes whose existing physical-run product semantics are defined in schema v1. */
enum class PortableStepsSessionMode {
	MANUAL,
	AUTOMATIC,
}

/** Schema v1 deliberately has one captured source and no control-source vocabulary. */
enum class PortableStepsSource {
	STEPS,
}

/** Schema v1 carries persisted product membership only; control observations are excluded. */
enum class PortableStepsPurpose {
	SESSION_CAPTURE,
}

/** Whether immutable manifest membership covered the complete physical run. */
enum class PortableStepsCaptureCoverage {
	WHOLE_RUN,
	PARTIAL,
}

/** Minimized provider settlement without registration, sequence, or device identifiers. */
enum class PortableStepsProviderCoverage {
	COMPLETE,
	PARTIAL,
	UNOBSERVABLE,
}

/** Typed fact coverage; only covered evidence can carry a numeric count. */
enum class PortableStepsFactCoverage {
	BASELINE,
	COVERED,
	RESET_GAP,
	PARTIAL,
}

/** Exact immutable capture attribution for one manifest revision. */
data class PortableStepsManifestV1(
	val revision: Long,
	val effectiveWallTimeMs: Long,
	val originSourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val source: PortableStepsSource = PortableStepsSource.STEPS,
	val purpose: PortableStepsPurpose = PortableStepsPurpose.SESSION_CAPTURE,
) {
	init {
		require(revision > 0L)
		require(effectiveWallTimeMs >= 0L)
		require(originSourcePolicyRevision > 0L)
		require(captureConsentEpoch >= 0L)
	}
}

/**
 * Source-local completeness without exporting operational provider identities or ordinals.
 * Provider barrier, app drain, stop, and unresolved-range evidence remain orthogonal.
 */
data class PortableStepsCompletenessV1(
	val captureCoverage: PortableStepsCaptureCoverage,
	val providerCoverage: PortableStepsProviderCoverage,
	val appDrainComplete: Boolean,
	val stopComplete: Boolean,
	val hasUnresolvedProviderRange: Boolean,
)

/** Latest effective typed Steps fact. Prior corrections and redacted retractions are not portable. */
@Suppress("LongParameterList")
data class PortableStepsFactV1(
	val identity: PortableStepsOpaqueIdentity,
	val contentChecksum: PortableStepsDigest,
	val manifestRevision: Long,
	val intervalStartTimeMs: Long,
	val intervalEndTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val coverage: PortableStepsFactCoverage,
	val stepCount: Long?,
) {
	init {
		require(manifestRevision > 0L)
		require(intervalStartTimeMs >= 0L)
		require(intervalEndTimeMs >= intervalStartTimeMs)
		require(wallTimeUncertaintyMs >= 0L)
		when (coverage) {
			PortableStepsFactCoverage.COVERED -> require(stepCount != null && stepCount >= 0L) {
				"Covered evidence requires its observed non-negative count"
			}
			PortableStepsFactCoverage.BASELINE,
			PortableStepsFactCoverage.RESET_GAP,
			PortableStepsFactCoverage.PARTIAL,
			-> require(stepCount == null) {
				"Non-covered evidence must not encode a numeric zero"
			}
		}
		require(PortableStepsIntegrity.expectedFactChecksum(this) == contentChecksum) {
			"Portable Steps fact checksum does not match its semantic content"
		}
	}

	/** Checked construction that binds fact semantics to their checksum. */
	companion object {
		/** Creates a fact and binds its semantic content to a canonical checksum. */
		@Suppress("LongParameterList")
		fun create(
			identity: PortableStepsOpaqueIdentity,
			manifestRevision: Long,
			intervalStartTimeMs: Long,
			intervalEndTimeMs: Long,
			wallTimeUncertaintyMs: Long,
			coverage: PortableStepsFactCoverage,
			stepCount: Long?,
		): PortableStepsFactV1 {
			val checksum = PortableStepsIntegrity.factChecksum(
				identity = identity,
				manifestRevision = manifestRevision,
				intervalStartTimeMs = intervalStartTimeMs,
				intervalEndTimeMs = intervalEndTimeMs,
				wallTimeUncertaintyMs = wallTimeUncertaintyMs,
				coverage = coverage,
				stepCount = stepCount,
			)
			return PortableStepsFactV1(
				identity = identity,
				contentChecksum = checksum,
				manifestRevision = manifestRevision,
				intervalStartTimeMs = intervalStartTimeMs,
				intervalEndTimeMs = intervalEndTimeMs,
				wallTimeUncertaintyMs = wallTimeUncertaintyMs,
				coverage = coverage,
				stepCount = stepCount,
			)
		}
	}
}

/**
 * One exact physical service-run member retained under a logical product entry.
 *
 * [startTimeMs] and [endTimeMs] are the run's product/presentation envelope. Manifest and fact wall
 * projections may fall outside it after a system-clock adjustment; their exact run membership is
 * established before serialization from durable identities and elapsed-realtime authority.
 *
 * Portability qualification does not grant eligibility for a local lifecycle operation. In
 * particular, selected-session deletion keeps its own exact capture-set and revision prerequisites.
 */
@Suppress("LongParameterList")
data class PortableStepsRunV1(
	val identity: PortableStepsOpaqueIdentity,
	val deletionScopeDigest: PortableStepsDeletionScopeDigest,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val storedZoneId: String,
	val manifests: List<PortableStepsManifestV1>,
	val completeness: PortableStepsCompletenessV1,
	val facts: List<PortableStepsFactV1>,
) {
	init {
		require(startTimeMs >= 0L)
		require(endTimeMs >= startTimeMs)
		require(storedZoneId.isNotBlank())
		require(storedZoneId.length <= StepsPortableFormatV1.MAX_ZONE_ID_LENGTH)
		try {
			ZoneId.of(storedZoneId)
		} catch (_: DateTimeException) {
			throw IllegalArgumentException("Portable Steps run has an invalid stored zone")
		}
		require(manifests.isNotEmpty())
		require(manifests.size <= StepsPortableFormatV1.MAX_MANIFESTS_PER_RUN)
		require(manifests == manifests.sortedBy(PortableStepsManifestV1::revision)) {
			"Portable Steps manifests must use canonical revision order"
		}
		require(manifests.map(PortableStepsManifestV1::revision).distinct().size == manifests.size)
		require(facts.size <= StepsPortableFormatV1.MAX_FACTS_PER_RUN)
		require(facts == facts.sortedWith(PORTABLE_STEPS_FACT_ORDER)) {
			"Portable Steps facts must use canonical interval and identity order"
		}
		require(facts.map(PortableStepsFactV1::identity).distinct().size == facts.size)
		val manifestRevisions = manifests.mapTo(hashSetOf(), PortableStepsManifestV1::revision)
		require(facts.all { fact -> fact.manifestRevision in manifestRevisions }) {
			"Portable Steps facts must reference an exported manifest"
		}
	}
}

/** One logical tracking entry, including every exact replacement physical run. */
data class PortableStepsEntryV1(
	val identity: PortableStepsOpaqueIdentity,
	val contentChecksum: PortableStepsDigest,
	val sessionMode: PortableStepsSessionMode,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val runs: List<PortableStepsRunV1>,
) {
	init {
		require(startTimeMs >= 0L)
		require(endTimeMs >= startTimeMs)
		require(runs.isNotEmpty())
		require(runs.size <= StepsPortableFormatV1.MAX_RUNS_PER_ENTRY)
		require(runs == runs.sortedWith(PORTABLE_STEPS_RUN_ORDER)) {
			"Portable Steps runs must use canonical wall-time and identity order"
		}
		require(runs.map(PortableStepsRunV1::identity).distinct().size == runs.size)
		require(runs.map(PortableStepsRunV1::deletionScopeDigest).distinct().size == runs.size) {
			"A portable Steps deletion scope must belong to exactly one physical run"
		}
		val factIdentities = hashSetOf<PortableStepsOpaqueIdentity>()
		require(runs.all { run -> run.facts.all { fact -> factIdentities.add(fact.identity) } }) {
			"A portable Steps fact must belong to exactly one physical run"
		}
		require(startTimeMs == runs.minOf(PortableStepsRunV1::startTimeMs))
		require(endTimeMs == runs.maxOf(PortableStepsRunV1::endTimeMs))
		require(PortableStepsIntegrity.expectedEntryChecksum(this) == contentChecksum) {
			"Portable Steps entry checksum does not match its semantic content"
		}
	}

	/** Checked construction that binds a logical entry and all exact member runs. */
	companion object {
		/** Creates a logical entry and binds every exact physical run to its checksum. */
		fun create(
			identity: PortableStepsOpaqueIdentity,
			sessionMode: PortableStepsSessionMode,
			startTimeMs: Long,
			endTimeMs: Long,
			runs: List<PortableStepsRunV1>,
		): PortableStepsEntryV1 {
			val checksum = PortableStepsIntegrity.entryChecksum(
				identity = identity,
				sessionMode = sessionMode,
				startTimeMs = startTimeMs,
				endTimeMs = endTimeMs,
				runs = runs,
			)
			return PortableStepsEntryV1(
				identity = identity,
				contentChecksum = checksum,
				sessionMode = sessionMode,
				startTimeMs = startTimeMs,
				endTimeMs = endTimeMs,
				runs = runs,
			)
		}
	}
}

/** Canonical ordering shared by transfer producers and the strict streaming codec. */
val PORTABLE_STEPS_FACT_ORDER: Comparator<PortableStepsFactV1> =
	compareBy<PortableStepsFactV1>(PortableStepsFactV1::intervalStartTimeMs)
		.thenBy { fact -> fact.identity.value }

/** Canonical physical membership order; wall-time overlap never creates membership. */
val PORTABLE_STEPS_RUN_ORDER: Comparator<PortableStepsRunV1> =
	compareBy<PortableStepsRunV1>(PortableStepsRunV1::startTimeMs)
		.thenBy { run -> run.identity.value }

/** Canonical top-level export order. */
val PORTABLE_STEPS_ENTRY_ORDER: Comparator<PortableStepsEntryV1> =
	compareBy<PortableStepsEntryV1>(PortableStepsEntryV1::startTimeMs)
		.thenBy { entry -> entry.identity.value }

/**
 * Canonical integrity functions shared by exporters, the decoder, and the authoritative importer.
 * No export timestamp, local database row id, provider id, or current authority enters a digest.
 */
@Suppress("TooManyFunctions")
object PortableStepsIntegrity {
	/** Computes the canonical digest for one fact's full portable semantic content. */
	@Suppress("LongParameterList")
	fun factChecksum(
		identity: PortableStepsOpaqueIdentity,
		manifestRevision: Long,
		intervalStartTimeMs: Long,
		intervalEndTimeMs: Long,
		wallTimeUncertaintyMs: Long,
		coverage: PortableStepsFactCoverage,
		stepCount: Long?,
	): PortableStepsDigest = digest(
		namespace = "tracker-portable-steps-fact-v1",
		value = listOf(
			identity.value,
			manifestRevision,
			intervalStartTimeMs,
			intervalEndTimeMs,
			wallTimeUncertaintyMs,
			coverage.name,
			stepCount,
		),
	)

	/** Recomputes the canonical digest expected by [fact]. */
	fun expectedFactChecksum(fact: PortableStepsFactV1): PortableStepsDigest = factChecksum(
		identity = fact.identity,
		manifestRevision = fact.manifestRevision,
		intervalStartTimeMs = fact.intervalStartTimeMs,
		intervalEndTimeMs = fact.intervalEndTimeMs,
		wallTimeUncertaintyMs = fact.wallTimeUncertaintyMs,
		coverage = fact.coverage,
		stepCount = fact.stepCount,
	)

	/** Computes the canonical digest for one logical entry and every exact member run. */
	@Suppress("LongMethod", "NestedBlockDepth")
	fun entryChecksum(
		identity: PortableStepsOpaqueIdentity,
		sessionMode: PortableStepsSessionMode,
		startTimeMs: Long,
		endTimeMs: Long,
		runs: List<PortableStepsRunV1>,
	): PortableStepsDigest {
		val canonical = MessageDigest.getInstance(SHA_256_ALGORITHM)
		canonical.appendList(size = 2) {
			appendString(ENTRY_CHECKSUM_NAMESPACE)
			appendList(size = 5) {
				appendString(identity.value)
				appendString(sessionMode.name)
				appendLong(startTimeMs)
				appendLong(endTimeMs)
				appendList(runs.size) {
					runs.forEach { run ->
						appendList(size = 8) {
							appendString(run.identity.value)
							appendString(run.deletionScopeDigest.value)
							appendLong(run.startTimeMs)
							appendLong(run.endTimeMs)
							appendString(run.storedZoneId)
							appendList(run.manifests.size) {
								run.manifests.forEach { manifest ->
									appendList(size = 6) {
										appendLong(manifest.revision)
										appendLong(manifest.effectiveWallTimeMs)
										appendLong(manifest.originSourcePolicyRevision)
										appendLong(manifest.captureConsentEpoch)
										appendString(manifest.source.name)
										appendString(manifest.purpose.name)
									}
								}
							}
							appendList(size = 5) {
								appendString(run.completeness.captureCoverage.name)
								appendString(run.completeness.providerCoverage.name)
								appendBoolean(run.completeness.appDrainComplete)
								appendBoolean(run.completeness.stopComplete)
								appendBoolean(run.completeness.hasUnresolvedProviderRange)
							}
							appendList(run.facts.size) {
								run.facts.forEach { fact ->
									appendList(size = 8) {
										appendString(fact.identity.value)
										appendString(fact.contentChecksum.value)
										appendLong(fact.manifestRevision)
										appendLong(fact.intervalStartTimeMs)
										appendLong(fact.intervalEndTimeMs)
										appendLong(fact.wallTimeUncertaintyMs)
										appendString(fact.coverage.name)
										appendNullableLong(fact.stepCount)
									}
								}
							}
						}
					}
				}
			}
		}
		return canonical.toPortableDigest()
	}

	/** Recomputes the canonical digest expected by [entry]. */
	fun expectedEntryChecksum(entry: PortableStepsEntryV1): PortableStepsDigest = entryChecksum(
		identity = entry.identity,
		sessionMode = entry.sessionMode,
		startTimeMs = entry.startTimeMs,
		endTimeMs = entry.endTimeMs,
		runs = entry.runs,
	)

	/**
	 * Computes the exact digest used by v28 `source_deletion_fence` for Steps session capture.
	 * This intentionally mirrors its original length-prefixed string contract rather than the
	 * portable typed canonicalization used by other digests.
	 */
	fun deletionScopeDigest(
		logicalTrackingId: String,
		serviceRunId: String,
	): PortableStepsDeletionScopeDigest {
		requireBoundedLocalIdentity(logicalTrackingId)
		requireBoundedLocalIdentity(serviceRunId)
		val digest = MessageDigest.getInstance(SHA_256_ALGORITHM)
		listOf(
			DELETION_SCOPE_DOMAIN,
			STEPS_SOURCE_KIND.toString(),
			PortableStepsPurpose.SESSION_CAPTURE.name,
			DELETION_SCOPE_KIND,
			logicalTrackingId,
			serviceRunId,
		).forEach { value ->
			// SourceDeletionFenceEntity v28 defines length as Kotlin String.length, not UTF-8 bytes.
			digest.appendUtf8("${value.length}:$value")
		}
		return PortableStepsDeletionScopeDigest(digest.toLowercaseHex())
	}

	internal fun digest(namespace: String, value: Any?): PortableStepsDigest {
		require(namespace.isNotBlank())
		val canonical = MessageDigest.getInstance(SHA_256_ALGORITHM)
		canonical.appendList(size = 2) {
			appendString(namespace)
			appendCanonical(value)
		}
		return canonical.toPortableDigest()
	}

	@Suppress("CyclomaticComplexMethod")
	private fun MessageDigest.appendCanonical(value: Any?) {
		when (value) {
			null -> appendUtf8("N;")
			is Boolean -> appendBoolean(value)
			is Byte -> appendLong(value.toLong())
			is Short -> appendLong(value.toLong())
			is Int -> appendLong(value.toLong())
			is Long -> appendLong(value)
			is String -> appendString(value)
			is Collection<*> -> appendList(value.size) {
				value.forEach { item -> appendCanonical(item) }
			}
			is Iterable<*> -> value.toList().let { items ->
				appendList(items.size) { items.forEach { item -> appendCanonical(item) } }
			}
			else -> error("Unsupported canonical portable Steps value ${value::class.java.name}")
		}
	}

	private inline fun MessageDigest.appendList(size: Int, values: MessageDigest.() -> Unit) {
		appendUtf8("L$size[")
		values()
		appendUtf8("];")
	}

	private fun MessageDigest.appendString(value: String) {
		val bytes = value.toByteArray(Charsets.UTF_8)
		appendUtf8("S${bytes.size}:")
		update(bytes)
		appendUtf8(";")
	}

	private fun MessageDigest.appendLong(value: Long) {
		appendUtf8("I$value;")
	}

	private fun MessageDigest.appendBoolean(value: Boolean) {
		if (value) {
			appendUtf8("B1;")
		} else {
			appendUtf8("B0;")
		}
	}

	private fun MessageDigest.appendNullableLong(value: Long?) {
		if (value == null) {
			appendUtf8("N;")
		} else {
			appendLong(value)
		}
	}

	private fun MessageDigest.appendUtf8(value: String) {
		update(value.toByteArray(Charsets.UTF_8))
	}

	private fun MessageDigest.toPortableDigest(): PortableStepsDigest {
		return PortableStepsDigest("sha256:" + toLowercaseHex())
	}

	private fun MessageDigest.toLowercaseHex(): String = digest().joinToString(separator = "") { byte ->
		(byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0')
	}

	private fun requireBoundedLocalIdentity(value: String) {
		require(value.isNotBlank()) { "Local identity must not be blank" }
		require(value.length <= StepsPortableFormatV1.MAX_ORIGIN_IDENTITY_LENGTH) {
			"Local identity exceeds the portable Steps bound"
		}
	}

	private const val ENTRY_CHECKSUM_NAMESPACE = "tracker-portable-steps-entry-v1"
	private const val DELETION_SCOPE_DOMAIN = "tracker-source-deletion-scope-v1"
	private const val DELETION_SCOPE_KIND = "LOGICAL_SERVICE_RUN"
	private const val STEPS_SOURCE_KIND = 3
	private const val SHA_256_ALGORITHM = "SHA-256"
	private const val HEX_RADIX = 16
	private const val BYTE_MASK = 0xff
}

private val SHA_256_VALUE = Regex("sha256:[0-9a-f]{64}")
private val SHA_256_HEX = Regex("[0-9a-f]{64}")
