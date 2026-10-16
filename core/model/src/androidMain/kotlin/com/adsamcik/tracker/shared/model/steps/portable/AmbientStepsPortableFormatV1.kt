package com.adsamcik.tracker.shared.model.steps.portable

import java.security.MessageDigest
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

/** Source-specific portable representation for retained sessionless Ambient Steps products. */
object AmbientStepsPortableFormatV1 {
	const val FORMAT: String = "tracker-portable-ambient-steps"
	const val SCHEMA_VERSION: Int = 1
	const val FILE_EXTENSION: String = "trackerambientsteps"
	const val MIME_TYPE: String = "application/vnd.adsamcik.tracker.ambient-steps+json"
	const val MAX_FILE_BYTES: Long = 32L * 1_024L * 1_024L
	const val MAX_DAYS: Int = 4_096
	const val MAX_FACTS: Int = 65_536
	const val MAX_GAPS: Int = 16_384
	const val MAX_FACTS_PER_DAY: Int = 2_048
	const val MAX_GAPS_PER_DAY: Int = 512
	const val MAX_LOCAL_IDENTITY_LENGTH: Int = 4_096
	const val MAX_ZONE_ID_LENGTH: Int = 128
	const val MAX_IMPORT_RECEIPT_FIELD_LENGTH: Int = 1_024
}

/** Separates archive, civil-day, fact, gap and deletion-scope identities for all-day Steps transfer. */
enum class AmbientStepsPortableIdentityKind {
	ARCHIVE,
	DAY,
	FACT,
	GAP,
	DELETION_SCOPE,
}

/** Kind-namespaced digest which never exposes a provider, registration, or local database key. */
@JvmInline
value class AmbientStepsPortableOpaqueIdentity(val value: String) {
	init {
		require(SHA_256_VALUE.matches(value))
	}

	/** Derives kind-namespaced identities without exposing provider or local database keys. */
	companion object {
		/** Derives a deterministic kind-specific portable identity from a nonblank bounded local key. */
		fun derive(
			kind: AmbientStepsPortableIdentityKind,
			localIdentity: String,
		): AmbientStepsPortableOpaqueIdentity {
			require(localIdentity.isNotBlank())
			require(localIdentity.length <= AmbientStepsPortableFormatV1.MAX_LOCAL_IDENTITY_LENGTH)
			return AmbientStepsPortableOpaqueIdentity(
				AmbientStepsPortableIntegrity.digest(
					"tracker-portable-ambient-steps-identity-v1",
					listOf(kind.name, localIdentity),
				).value,
			)
		}
	}
}

/** Canonical sha256-prefixed content checksum for portable Steps consistency, independent of an import job receipt. */
@JvmInline
value class AmbientStepsPortableDigest(val value: String) {
	init {
		require(SHA_256_VALUE.matches(value))
	}
}

/** Whether retained facts cover the entire stored civil day or provide only a partial recorded count. */
enum class PortableAmbientStepsCoverage { COMPLETE, PARTIAL }

/** A partial day names only portable product limitations, never provider or local authority IDs. */
enum class PortableAmbientStepsPartialCause {
	EXPLICIT_GAP,
	RETENTION,
	OUTSIDE_AUTHORITY,
}

/** Recorded provider, process, clock or authority boundaries that leave a real missing-count interval. */
enum class PortableAmbientStepsGapReason {
	PROVIDER_NO_EVIDENCE,
	PROVIDER_RETENTION_LOSS,
	PROCESS_ABSENCE,
	BOOT_CHANGED,
	ZONE_CHANGED,
	PROVIDER_CHANGED,
	CLOCK_DISCONTINUITY,
	AUTHORITY_BOUNDARY_NOT_DRAINED,
	INITIAL_ZONE_AUTHORITY_UNOBSERVED,
}

/**
 * Nonnegative count for one nonempty measured interval in UTC milliseconds; the interval is preserved rather
 * than split.
 */
data class PortableAmbientStepsFactV1(
	val identity: AmbientStepsPortableOpaqueIdentity,
	val contentChecksum: AmbientStepsPortableDigest,
	val intervalStartTimeMs: Long,
	val intervalEndTimeMs: Long,
	val stepCount: Long,
) {
	init {
		require(intervalStartTimeMs >= 0L)
		require(intervalEndTimeMs > intervalStartTimeMs)
		require(stepCount >= 0L)
		require(contentChecksum == AmbientStepsPortableIntegrity.expectedFactChecksum(this))
	}

	/** Constructs checksum-bound measured count intervals for all-day Steps. */
	companion object {
		/** Builds a nonempty measured UTC-millisecond interval and its count checksum. */
		fun create(
			identity: AmbientStepsPortableOpaqueIdentity,
			intervalStartTimeMs: Long,
			intervalEndTimeMs: Long,
			stepCount: Long,
		): PortableAmbientStepsFactV1 = PortableAmbientStepsFactV1(
			identity,
			AmbientStepsPortableIntegrity.factChecksum(
				identity,
				intervalStartTimeMs,
				intervalEndTimeMs,
				stepCount,
			),
			intervalStartTimeMs,
			intervalEndTimeMs,
			stepCount,
		)
	}
}

/** One explicit nonempty missing-count interval in UTC milliseconds, retaining its original reason. */
data class PortableAmbientStepsGapV1(
	val identity: AmbientStepsPortableOpaqueIdentity,
	val contentChecksum: AmbientStepsPortableDigest,
	val intervalStartTimeMs: Long,
	val intervalEndTimeMs: Long,
	val reason: PortableAmbientStepsGapReason,
) {
	init {
		require(intervalStartTimeMs >= 0L)
		require(intervalEndTimeMs > intervalStartTimeMs)
		require(contentChecksum == AmbientStepsPortableIntegrity.expectedGapChecksum(this))
	}

	/** Constructs checksum-bound missing-count intervals with their original reasons. */
	companion object {
		/** Builds a nonempty UTC-millisecond gap and checksum for its recorded missing-data reason. */
		fun create(
			identity: AmbientStepsPortableOpaqueIdentity,
			intervalStartTimeMs: Long,
			intervalEndTimeMs: Long,
			reason: PortableAmbientStepsGapReason,
		): PortableAmbientStepsGapV1 = PortableAmbientStepsGapV1(
			identity,
			AmbientStepsPortableIntegrity.gapChecksum(
				identity,
				intervalStartTimeMs,
				intervalEndTimeMs,
				reason,
			),
			intervalStartTimeMs,
			intervalEndTimeMs,
			reason,
		)
	}
}

/**
 * One structural civil day. A partial numeric value is retained evidence, not an all-day total.
 * Exact fact and effective-gap windows let consumers preserve that distinction.
 */
data class PortableAmbientStepsDayV1(
	val identity: AmbientStepsPortableOpaqueIdentity,
	val contentChecksum: AmbientStepsPortableDigest,
	val structuralEpochDay: Long,
	val storedZoneId: String,
	val structuralDayStartTimeMs: Long,
	val structuralDayEndTimeMs: Long,
	val retainedFromTimeMs: Long?,
	val coverage: PortableAmbientStepsCoverage,
	val partialCauses: List<PortableAmbientStepsPartialCause>,
	val retainedStepCount: Long,
	val facts: List<PortableAmbientStepsFactV1>,
	val gaps: List<PortableAmbientStepsGapV1>,
) {
	init {
		require(storedZoneId.isNotBlank())
		require(storedZoneId.length <= AmbientStepsPortableFormatV1.MAX_ZONE_ID_LENGTH)
		val zone = try {
			ZoneId.of(storedZoneId)
		} catch (_: DateTimeException) {
			throw IllegalArgumentException("Portable Ambient Steps day has an invalid zone")
		}
		val date = LocalDate.ofEpochDay(structuralEpochDay)
		require(date.atStartOfDay(zone).toInstant().toEpochMilli() == structuralDayStartTimeMs)
		require(date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli() == structuralDayEndTimeMs)
		require(facts.isNotEmpty())
		require(facts.size <= AmbientStepsPortableFormatV1.MAX_FACTS_PER_DAY)
		require(gaps.size <= AmbientStepsPortableFormatV1.MAX_GAPS_PER_DAY)
		require(facts == facts.sortedWith(PORTABLE_AMBIENT_STEPS_FACT_ORDER))
		require(gaps == gaps.sortedWith(PORTABLE_AMBIENT_STEPS_GAP_ORDER))
		require(facts.map(PortableAmbientStepsFactV1::identity).distinct().size == facts.size)
		require(gaps.map(PortableAmbientStepsGapV1::identity).distinct().size == gaps.size)
		require(facts.all {
			it.intervalStartTimeMs >= structuralDayStartTimeMs &&
				it.intervalEndTimeMs <= structuralDayEndTimeMs
		})
		require(gaps.all {
			it.intervalStartTimeMs >= structuralDayStartTimeMs &&
				it.intervalEndTimeMs <= structuralDayEndTimeMs
		})
		require(facts.zipWithNext().none { (left, right) ->
			right.intervalStartTimeMs < left.intervalEndTimeMs
		})
		require(gaps.zipWithNext().none { (left, right) ->
			right.intervalStartTimeMs < left.intervalEndTimeMs
		})
		require(facts.none { fact -> gaps.any { gap ->
			fact.intervalEndTimeMs > gap.intervalStartTimeMs &&
				fact.intervalStartTimeMs < gap.intervalEndTimeMs
		} })
		require(retainedFromTimeMs == null ||
			(retainedFromTimeMs > structuralDayStartTimeMs &&
				retainedFromTimeMs < structuralDayEndTimeMs))
		require(partialCauses == partialCauses.distinct().sortedBy { it.ordinal })
		require((coverage == PortableAmbientStepsCoverage.COMPLETE) == partialCauses.isEmpty())
		require((retainedFromTimeMs != null) ==
			(PortableAmbientStepsPartialCause.RETENTION in partialCauses))
		require((gaps.isNotEmpty()) ==
			(PortableAmbientStepsPartialCause.EXPLICIT_GAP in partialCauses))
		var total = 0L
		facts.forEach { fact -> total = Math.addExact(total, fact.stepCount) }
		require(retainedStepCount == total)
		val completelyCovered = facts.first().intervalStartTimeMs == structuralDayStartTimeMs &&
			facts.last().intervalEndTimeMs == structuralDayEndTimeMs &&
			facts.zipWithNext().all { (left, right) ->
				left.intervalEndTimeMs == right.intervalStartTimeMs
			}
		require(coverage != PortableAmbientStepsCoverage.COMPLETE || completelyCovered)
		require(contentChecksum == AmbientStepsPortableIntegrity.expectedDayChecksum(this))
	}

	/** Constructs a validated stored civil day without inventing coverage or redistributing measured counts. */
	companion object {
		/**
		 * Builds a civil day using its stored zone, exact facts and gaps; retained counts must equal the sum
		 * of its facts.
		 */
		@Suppress("LongParameterList")
		fun create(
			identity: AmbientStepsPortableOpaqueIdentity,
			structuralEpochDay: Long,
			storedZoneId: String,
			structuralDayStartTimeMs: Long,
			structuralDayEndTimeMs: Long,
			retainedFromTimeMs: Long?,
			coverage: PortableAmbientStepsCoverage,
			partialCauses: List<PortableAmbientStepsPartialCause>,
			retainedStepCount: Long,
			facts: List<PortableAmbientStepsFactV1>,
			gaps: List<PortableAmbientStepsGapV1>,
		): PortableAmbientStepsDayV1 {
			val checksum = AmbientStepsPortableIntegrity.dayChecksum(
				identity,
				structuralEpochDay,
				storedZoneId,
				structuralDayStartTimeMs,
				structuralDayEndTimeMs,
				retainedFromTimeMs,
				coverage,
				partialCauses,
				retainedStepCount,
				facts,
				gaps,
			)
			return PortableAmbientStepsDayV1(
				identity,
				checksum,
				structuralEpochDay,
				storedZoneId,
				structuralDayStartTimeMs,
				structuralDayEndTimeMs,
				retainedFromTimeMs,
				coverage,
				partialCauses,
				retainedStepCount,
				facts,
				gaps,
			)
		}
	}
}

/** Bounded ordered civil-day data with its canonical checksum; each partial day keeps its exact facts and gaps. */
data class PortableAmbientStepsArchiveV1(
	val format: String = AmbientStepsPortableFormatV1.FORMAT,
	val schemaVersion: Int = AmbientStepsPortableFormatV1.SCHEMA_VERSION,
	val contentChecksum: AmbientStepsPortableDigest,
	val days: List<PortableAmbientStepsDayV1>,
) {
	init {
		require(format == AmbientStepsPortableFormatV1.FORMAT)
		require(schemaVersion == AmbientStepsPortableFormatV1.SCHEMA_VERSION)
		require(days.isNotEmpty())
		require(days.size <= AmbientStepsPortableFormatV1.MAX_DAYS)
		require(days == days.sortedWith(PORTABLE_AMBIENT_STEPS_DAY_ORDER))
		require(days.map(PortableAmbientStepsDayV1::identity).distinct().size == days.size)
		require(days.sumOf { it.facts.size } <= AmbientStepsPortableFormatV1.MAX_FACTS)
		require(days.sumOf { it.gaps.size } <= AmbientStepsPortableFormatV1.MAX_GAPS)
		require(contentChecksum == AmbientStepsPortableIntegrity.expectedArchiveChecksum(this))
	}

	/** Constructs a bounded archive from already ordered civil-day records. */
	companion object {
		/** Builds an archive from already ordered days, computing their exact membership checksum. */
		fun create(days: List<PortableAmbientStepsDayV1>): PortableAmbientStepsArchiveV1 =
			PortableAmbientStepsArchiveV1(
				contentChecksum = AmbientStepsPortableIntegrity.archiveChecksum(days),
				days = days,
			)
	}
}

/** Stable portable-owner identity; it is independent of a destination import receipt. */
val PortableAmbientStepsArchiveV1.identity: AmbientStepsPortableOpaqueIdentity
	get() = AmbientStepsPortableOpaqueIdentity.derive(
		AmbientStepsPortableIdentityKind.ARCHIVE,
		contentChecksum.value,
	)

/** Source-specific deletion scope for one immutable structural-day lineage. */
val PortableAmbientStepsDayV1.deletionScopeIdentity: AmbientStepsPortableOpaqueIdentity
	get() = AmbientStepsPortableOpaqueIdentity.derive(
		AmbientStepsPortableIdentityKind.DELETION_SCOPE,
		identity.value,
	)

val PORTABLE_AMBIENT_STEPS_FACT_ORDER: Comparator<PortableAmbientStepsFactV1> =
	compareBy<PortableAmbientStepsFactV1>(PortableAmbientStepsFactV1::intervalStartTimeMs)
		.thenBy { it.identity.value }

val PORTABLE_AMBIENT_STEPS_GAP_ORDER: Comparator<PortableAmbientStepsGapV1> =
	compareBy<PortableAmbientStepsGapV1>(PortableAmbientStepsGapV1::intervalStartTimeMs)
		.thenBy { it.identity.value }

val PORTABLE_AMBIENT_STEPS_DAY_ORDER: Comparator<PortableAmbientStepsDayV1> =
	compareBy<PortableAmbientStepsDayV1>(PortableAmbientStepsDayV1::structuralDayStartTimeMs)
		.thenBy(PortableAmbientStepsDayV1::storedZoneId)
		.thenBy { it.identity.value }

/**
 * Computes deterministic content checksums for measured intervals and ordered day membership; does not grant
 * recording authority.
 */
@Suppress("TooManyFunctions")
object AmbientStepsPortableIntegrity {
	private const val UNSIGNED_BYTE_MASK = 0xff
	private const val HEX_RADIX = 16

	/** Hashes the exact fact identity, UTC-millisecond interval and nonnegative step count. */
	fun factChecksum(
		identity: AmbientStepsPortableOpaqueIdentity,
		intervalStartTimeMs: Long,
		intervalEndTimeMs: Long,
		stepCount: Long,
	) = digest(
		"tracker-portable-ambient-steps-fact-v1",
		listOf(identity.value, intervalStartTimeMs, intervalEndTimeMs, stepCount),
	)

	/** Recomputes a fact checksum without changing its recorded interval or count. */
	fun expectedFactChecksum(fact: PortableAmbientStepsFactV1) = factChecksum(
		fact.identity,
		fact.intervalStartTimeMs,
		fact.intervalEndTimeMs,
		fact.stepCount,
	)

	/** Hashes the exact gap identity, UTC-millisecond interval and missing-data reason. */
	fun gapChecksum(
		identity: AmbientStepsPortableOpaqueIdentity,
		intervalStartTimeMs: Long,
		intervalEndTimeMs: Long,
		reason: PortableAmbientStepsGapReason,
	) = digest(
		"tracker-portable-ambient-steps-gap-v1",
		listOf(identity.value, intervalStartTimeMs, intervalEndTimeMs, reason.name),
	)

	/** Recomputes the checksum for the saved gap and its original reason. */
	fun expectedGapChecksum(gap: PortableAmbientStepsGapV1) = gapChecksum(
		gap.identity,
		gap.intervalStartTimeMs,
		gap.intervalEndTimeMs,
		gap.reason,
	)

	/**
	 * Hashes the stored civil-day structure, retained count and ordered fact/gap membership; nullable
	 * retention time remains distinct.
	 */
	@Suppress("LongParameterList")
	fun dayChecksum(
		identity: AmbientStepsPortableOpaqueIdentity,
		structuralEpochDay: Long,
		storedZoneId: String,
		structuralDayStartTimeMs: Long,
		structuralDayEndTimeMs: Long,
		retainedFromTimeMs: Long?,
		coverage: PortableAmbientStepsCoverage,
		partialCauses: List<PortableAmbientStepsPartialCause>,
		retainedStepCount: Long,
		facts: List<PortableAmbientStepsFactV1>,
		gaps: List<PortableAmbientStepsGapV1>,
	) = digest(
		"tracker-portable-ambient-steps-day-v1",
		listOf(
			identity.value,
			structuralEpochDay,
			storedZoneId,
			structuralDayStartTimeMs,
			structuralDayEndTimeMs,
			retainedFromTimeMs,
			coverage.name,
			partialCauses.map { it.name },
			retainedStepCount,
			facts.map { listOf(it.identity.value, it.contentChecksum.value) },
			gaps.map { listOf(it.identity.value, it.contentChecksum.value) },
		),
	)

	/**
	 * Recomputes the checksum using the day’s stored zone and structural bounds, including its original
	 * partial causes.
	 */
	fun expectedDayChecksum(day: PortableAmbientStepsDayV1) = dayChecksum(
		day.identity,
		day.structuralEpochDay,
		day.storedZoneId,
		day.structuralDayStartTimeMs,
		day.structuralDayEndTimeMs,
		day.retainedFromTimeMs,
		day.coverage,
		day.partialCauses,
		day.retainedStepCount,
		day.facts,
		day.gaps,
	)

	/** Hashes the supplied ordered day identities and checksums under the v1 archive namespace. */
	fun archiveChecksum(days: List<PortableAmbientStepsDayV1>) = digest(
		"tracker-portable-ambient-steps-archive-v1",
		listOf(
			AmbientStepsPortableFormatV1.FORMAT,
			AmbientStepsPortableFormatV1.SCHEMA_VERSION,
			days.map { listOf(it.identity.value, it.contentChecksum.value) },
		),
	)

	/**
	 * Computes the same archive checksum from ordered identity/checksum pairs without loading complete day
	 * payloads.
	 */
	fun archiveChecksumForMembers(
		members: List<Pair<AmbientStepsPortableOpaqueIdentity, AmbientStepsPortableDigest>>,
	) = digest(
		"tracker-portable-ambient-steps-archive-v1",
		listOf(
			AmbientStepsPortableFormatV1.FORMAT,
			AmbientStepsPortableFormatV1.SCHEMA_VERSION,
			members.map { (identity, checksum) -> listOf(identity.value, checksum.value) },
		),
	)

	/** Recomputes an archive checksum from its saved ordered day members. */
	fun expectedArchiveChecksum(archive: PortableAmbientStepsArchiveV1) = archiveChecksum(archive.days)

	internal fun digest(namespace: String, value: Any?): AmbientStepsPortableDigest {
		val digest = MessageDigest.getInstance("SHA-256")
		digest.appendCanonical(listOf(namespace, value))
		return AmbientStepsPortableDigest(
			"sha256:" + digest.digest().joinToString("") { byte ->
				(byte.toInt() and UNSIGNED_BYTE_MASK).toString(HEX_RADIX).padStart(2, '0')
			},
		)
	}

	private fun MessageDigest.appendCanonical(value: Any?) {
		when (value) {
			null -> update("N;".toByteArray(Charsets.UTF_8))
			is Int -> appendCanonical(value.toLong())
			is Long -> update("I$value;".toByteArray(Charsets.UTF_8))
			is String -> {
				val bytes = value.toByteArray(Charsets.UTF_8)
				update("S${bytes.size}:".toByteArray(Charsets.UTF_8))
				update(bytes)
				update(";".toByteArray(Charsets.UTF_8))
			}
			is Collection<*> -> {
				update("L${value.size}[".toByteArray(Charsets.UTF_8))
				value.forEach { item -> appendCanonical(item) }
				update("];".toByteArray(Charsets.UTF_8))
			}
			else -> error("Unsupported canonical Ambient Steps value ${value::class.java.name}")
		}
	}
}

private val SHA_256_VALUE = Regex("sha256:[0-9a-f]{64}")
