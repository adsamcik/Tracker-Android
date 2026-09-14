package com.adsamcik.tracker.stats.api.repository

import java.security.MessageDigest
import java.time.DateTimeException
import java.time.ZoneId

/** Stable, privacy-minimized source-local Pressure transfer envelope. */
object PressurePortableFormatV1 {
	const val FORMAT: String = "tracker-portable-pressure"
	const val SCHEMA_VERSION: Int = 1
	const val FILE_EXTENSION: String = "trackerpressure"
	const val MIME_TYPE: String = "application/vnd.adsamcik.tracker.pressure+json"

	/** Product/export limits, independent from provider or retention configuration. */
	const val MAX_ENTRIES: Int = 256
	const val MAX_RUNS_PER_ENTRY: Int = 64
	const val MAX_WINDOWS_PER_RUN: Int = 2_048
	const val MAX_TOTAL_RUNS: Int = 4_096
	const val MAX_TOTAL_WINDOWS: Int = 16_384
	const val MAX_LOCAL_IDENTITY_LENGTH: Int = 4_096
	const val MAX_ZONE_ID_LENGTH: Int = 128
	const val MAX_IMPORT_RECEIPT_FIELD_LENGTH: Int = 4_096
}

enum class PortablePressureIdentityKind { LOGICAL_ENTRY, PHYSICAL_RUN, WINDOW }

/** Kind-namespaced hash; local logical, run, and fact identifiers never cross this boundary. */
@JvmInline
value class PortablePressureOpaqueIdentity(val value: String) {
	init {
		require(SHA_256_VALUE.matches(value))
	}

	companion object {
		fun derive(
			kind: PortablePressureIdentityKind,
			localIdentity: String,
		): PortablePressureOpaqueIdentity {
			require(localIdentity.isNotBlank())
			require(localIdentity.length <= PressurePortableFormatV1.MAX_LOCAL_IDENTITY_LENGTH)
			return PortablePressureOpaqueIdentity(
				PortablePressureIntegrity.digest(
					namespace = "tracker-portable-pressure-identity-v1",
					values = listOf(kind.name, localIdentity),
				).value,
			)
		}
	}
}

@JvmInline
value class PortablePressureDigest(val value: String) {
	init {
		require(SHA_256_VALUE.matches(value))
	}
}

enum class PortablePressureAvailability {
	RETAINED,
	NO_RETAINED_OBSERVATION,
	DISABLED,
	DELETED,
	UNAVAILABLE,
}

enum class PortablePressureCoverage { NONE, COMPLETE, PARTIAL, UNKNOWN }
enum class PortablePressureSensorAccuracy { UNKNOWN, UNRELIABLE, LOW, MEDIUM, HIGH }
enum class PortablePressureWindowClosure { TARGET_ELAPSED, SOURCE_BOUNDARY }
enum class PortablePressureWindowQualification { COMPLETE, PARTIAL }

/** One direct Pressure window. It deliberately has no elevation, ascent, or coordinates. */
@Suppress("LongParameterList")
data class PortablePressureWindowV1(
	val identity: PortablePressureOpaqueIdentity,
	val contentChecksum: PortablePressureDigest,
	val intervalStartTimeMs: Long,
	val intervalEndTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val observedDurationNanos: Long,
	val sampleCount: Int,
	val expectedSampleCount: Int,
	val meanHectopascals: Double,
	val sumSquaredDeviations: Double,
	val minimumHectopascals: Float,
	val maximumHectopascals: Float,
	val firstHectopascals: Float,
	val latestHectopascals: Float,
	val slopeHectopascalsPerSecond: Double?,
	val rSquared: Double?,
	val sensorAccuracy: PortablePressureSensorAccuracy,
	val effectiveSamplePeriodMicros: Int,
	val effectiveMaximumReportLatencyMicros: Int,
	val targetWindowDurationNanos: Long,
	val maximumInterSampleGapNanos: Long,
	val closure: PortablePressureWindowClosure,
	val qualification: PortablePressureWindowQualification,
	val sourceQualityFlags: Long,
	val sourceQualityConfidence: Float?,
	val zoneId: String,
) {
	init {
		require(intervalStartTimeMs >= 0L && intervalEndTimeMs >= intervalStartTimeMs)
		require(wallTimeUncertaintyMs >= 0L)
		require(observedDurationNanos >= 0L)
		require((intervalEndTimeMs - intervalStartTimeMs) == observedDurationNanos / 1_000_000L)
		require(sampleCount > 0 && expectedSampleCount > 0)
		require(meanHectopascals.isFinite() && meanHectopascals > 0.0)
		require(sumSquaredDeviations.isFinite() && sumSquaredDeviations >= 0.0)
		require(minimumHectopascals.isFinite() && minimumHectopascals > 0f)
		require(maximumHectopascals.isFinite() && maximumHectopascals >= minimumHectopascals)
		require(meanHectopascals in minimumHectopascals.toDouble()..maximumHectopascals.toDouble())
		require(firstHectopascals in minimumHectopascals..maximumHectopascals)
		require(latestHectopascals in minimumHectopascals..maximumHectopascals)
		require(slopeHectopascalsPerSecond == null || slopeHectopascalsPerSecond.isFinite())
		require(rSquared == null || rSquared.isFinite() && rSquared in 0.0..1.0)
		require(effectiveSamplePeriodMicros > 0)
		require(effectiveMaximumReportLatencyMicros >= 0)
		require(targetWindowDurationNanos > 0L)
		require(maximumInterSampleGapNanos >= 0L)
		require(sourceQualityFlags >= 0L)
		require(sourceQualityConfidence == null || sourceQualityConfidence in 0f..1f)
		requireValidZone(zoneId)
		val samplePeriodNanos = effectiveSamplePeriodMicros.toLong() * 1_000L
		val expectedFromPlan = (targetWindowDurationNanos / samplePeriodNanos) +
			if (targetWindowDurationNanos % samplePeriodNanos == 0L) 0L else 1L
		require(expectedFromPlan.coerceAtLeast(1L) == expectedSampleCount.toLong())
		if (sampleCount == 1) {
			require(firstHectopascals == latestHectopascals)
			require(firstHectopascals == minimumHectopascals)
			require(latestHectopascals == maximumHectopascals)
			require(meanHectopascals == firstHectopascals.toDouble())
			require(sumSquaredDeviations == 0.0)
			require(observedDurationNanos == 0L && maximumInterSampleGapNanos == 0L)
			require(slopeHectopascalsPerSecond == null && rSquared == null)
		} else {
			require(observedDurationNanos > 0L)
			require(maximumInterSampleGapNanos in 1L..observedDurationNanos)
			requireNotNull(slopeHectopascalsPerSecond)
			if (sumSquaredDeviations > 0.0) requireNotNull(rSquared) else require(rSquared == null)
		}
		val requiredObservedDuration = saturatedMultiply(
			expectedSampleCount.toLong() - 1L,
			samplePeriodNanos,
		)
		val noMissingCadence = samplePeriodNanos > Long.MAX_VALUE / 2L ||
			maximumInterSampleGapNanos < samplePeriodNanos * 2L
		val complete = closure == PortablePressureWindowClosure.TARGET_ELAPSED &&
			sampleCount >= expectedSampleCount && observedDurationNanos >= requiredObservedDuration &&
			noMissingCadence
		require((qualification == PortablePressureWindowQualification.COMPLETE) == complete)
		require(PortablePressureIntegrity.expectedWindowChecksum(this) == contentChecksum)
	}

	val sampleVarianceHectopascalsSquared: Double?
		get() = if (sampleCount > 1) sumSquaredDeviations / (sampleCount - 1) else null

	val actualToExpectedSampleRatio: Double
		get() = sampleCount.toDouble() / expectedSampleCount

	companion object {
		@Suppress("LongParameterList")
		fun create(
			identity: PortablePressureOpaqueIdentity,
			intervalStartTimeMs: Long,
			intervalEndTimeMs: Long,
			wallTimeUncertaintyMs: Long,
			observedDurationNanos: Long,
			sampleCount: Int,
			expectedSampleCount: Int,
			meanHectopascals: Double,
			sumSquaredDeviations: Double,
			minimumHectopascals: Float,
			maximumHectopascals: Float,
			firstHectopascals: Float,
			latestHectopascals: Float,
			slopeHectopascalsPerSecond: Double?,
			rSquared: Double?,
			sensorAccuracy: PortablePressureSensorAccuracy,
			effectiveSamplePeriodMicros: Int,
			effectiveMaximumReportLatencyMicros: Int,
			targetWindowDurationNanos: Long,
			maximumInterSampleGapNanos: Long,
			closure: PortablePressureWindowClosure,
			qualification: PortablePressureWindowQualification,
			sourceQualityFlags: Long,
			sourceQualityConfidence: Float?,
			zoneId: String,
		): PortablePressureWindowV1 {
			val values = windowValues(
				identity, intervalStartTimeMs, intervalEndTimeMs, wallTimeUncertaintyMs,
				observedDurationNanos,
				sampleCount, expectedSampleCount, meanHectopascals, sumSquaredDeviations,
				minimumHectopascals, maximumHectopascals, firstHectopascals,
				latestHectopascals, slopeHectopascalsPerSecond, rSquared, sensorAccuracy,
				effectiveSamplePeriodMicros, effectiveMaximumReportLatencyMicros,
				targetWindowDurationNanos, maximumInterSampleGapNanos, closure, qualification,
				sourceQualityFlags, sourceQualityConfidence, zoneId,
			)
			return PortablePressureWindowV1(
				identity = identity,
				contentChecksum = PortablePressureIntegrity.digest(WINDOW_NAMESPACE, values),
				intervalStartTimeMs = intervalStartTimeMs,
				intervalEndTimeMs = intervalEndTimeMs,
				wallTimeUncertaintyMs = wallTimeUncertaintyMs,
				observedDurationNanos = observedDurationNanos,
				sampleCount = sampleCount,
				expectedSampleCount = expectedSampleCount,
				meanHectopascals = meanHectopascals,
				sumSquaredDeviations = sumSquaredDeviations,
				minimumHectopascals = minimumHectopascals,
				maximumHectopascals = maximumHectopascals,
				firstHectopascals = firstHectopascals,
				latestHectopascals = latestHectopascals,
				slopeHectopascalsPerSecond = slopeHectopascalsPerSecond,
				rSquared = rSquared,
				sensorAccuracy = sensorAccuracy,
				effectiveSamplePeriodMicros = effectiveSamplePeriodMicros,
				effectiveMaximumReportLatencyMicros = effectiveMaximumReportLatencyMicros,
				targetWindowDurationNanos = targetWindowDurationNanos,
				maximumInterSampleGapNanos = maximumInterSampleGapNanos,
				closure = closure,
				qualification = qualification,
				sourceQualityFlags = sourceQualityFlags,
				sourceQualityConfidence = sourceQualityConfidence,
				zoneId = zoneId,
			)
		}
	}
}

/** One exact physical replacement member, identified only by a source-local opaque hash. */
data class PortablePressureRunV1(
	val identity: PortablePressureOpaqueIdentity,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val capturedForWholeRun: Boolean,
	val availability: PortablePressureAvailability,
	val coverage: PortablePressureCoverage,
	val retentionLoss: Boolean,
	val windows: List<PortablePressureWindowV1>,
) {
	init {
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(windows.size <= PressurePortableFormatV1.MAX_WINDOWS_PER_RUN)
		require(windows == windows.sortedWith(PORTABLE_PRESSURE_WINDOW_ORDER))
		require(windows.map(PortablePressureWindowV1::identity).distinct().size == windows.size)
		if (availability == PortablePressureAvailability.RETAINED) {
			require(windows.isNotEmpty())
		} else {
			require(windows.isEmpty()) { "Only retained Pressure evidence may carry numeric windows" }
		}
		if (availability == PortablePressureAvailability.NO_RETAINED_OBSERVATION) {
			require(retentionLoss && coverage == PortablePressureCoverage.PARTIAL)
		}
		if (availability in setOf(
				PortablePressureAvailability.DISABLED,
				PortablePressureAvailability.DELETED,
				PortablePressureAvailability.UNAVAILABLE,
			)
		) {
			require(coverage == PortablePressureCoverage.NONE && !retentionLoss)
		}
		if (retentionLoss) {
			require(coverage == PortablePressureCoverage.PARTIAL)
			require(availability in setOf(
				PortablePressureAvailability.RETAINED,
				PortablePressureAvailability.NO_RETAINED_OBSERVATION,
			))
		}
		if (coverage == PortablePressureCoverage.COMPLETE) {
			require(capturedForWholeRun && windows.isNotEmpty() && !retentionLoss)
		}
	}
}

/** One logical entry with all exact replacement members and no clear-text local identities. */
data class PortablePressureEntryV1(
	val identity: PortablePressureOpaqueIdentity,
	val contentChecksum: PortablePressureDigest,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val runs: List<PortablePressureRunV1>,
) {
	init {
		require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
		require(runs.isNotEmpty() && runs.size <= PressurePortableFormatV1.MAX_RUNS_PER_ENTRY)
		require(runs == runs.sortedWith(PORTABLE_PRESSURE_RUN_ORDER))
		require(runs.map(PortablePressureRunV1::identity).distinct().size == runs.size)
		require(startTimeMs == runs.minOf(PortablePressureRunV1::startTimeMs))
		require(endTimeMs == runs.maxOf(PortablePressureRunV1::endTimeMs))
		require(runs.any { it.windows.isNotEmpty() || it.retentionLoss }) {
			"Portable Pressure entries require retained facts or authenticated retention loss"
		}
		require(PortablePressureIntegrity.expectedEntryChecksum(this) == contentChecksum)
	}

	companion object {
		fun create(
			identity: PortablePressureOpaqueIdentity,
			startTimeMs: Long,
			endTimeMs: Long,
			runs: List<PortablePressureRunV1>,
		): PortablePressureEntryV1 = PortablePressureEntryV1(
			identity = identity,
			contentChecksum = PortablePressureIntegrity.entryChecksum(
				identity, startTimeMs, endTimeMs, runs,
			),
			startTimeMs = startTimeMs,
			endTimeMs = endTimeMs,
			runs = runs,
		)
	}
}

val PORTABLE_PRESSURE_WINDOW_ORDER: Comparator<PortablePressureWindowV1> =
	compareBy<PortablePressureWindowV1>(PortablePressureWindowV1::intervalStartTimeMs)
		.thenBy { it.identity.value }

val PORTABLE_PRESSURE_RUN_ORDER: Comparator<PortablePressureRunV1> =
	compareBy<PortablePressureRunV1>(PortablePressureRunV1::startTimeMs)
		.thenBy { it.identity.value }

val PORTABLE_PRESSURE_ENTRY_ORDER: Comparator<PortablePressureEntryV1> =
	compareBy<PortablePressureEntryV1>(PortablePressureEntryV1::startTimeMs)
		.thenBy { it.identity.value }

/** Canonical v1 checksums exclude export time and local database/provider identifiers. */
object PortablePressureIntegrity {
	fun expectedWindowChecksum(window: PortablePressureWindowV1): PortablePressureDigest = digest(
		WINDOW_NAMESPACE,
		windowValues(
			window.identity, window.intervalStartTimeMs, window.intervalEndTimeMs,
			window.wallTimeUncertaintyMs, window.observedDurationNanos, window.sampleCount,
			window.expectedSampleCount,
			window.meanHectopascals, window.sumSquaredDeviations, window.minimumHectopascals,
			window.maximumHectopascals, window.firstHectopascals, window.latestHectopascals,
			window.slopeHectopascalsPerSecond, window.rSquared, window.sensorAccuracy,
			window.effectiveSamplePeriodMicros, window.effectiveMaximumReportLatencyMicros,
			window.targetWindowDurationNanos, window.maximumInterSampleGapNanos,
			window.closure, window.qualification, window.sourceQualityFlags,
			window.sourceQualityConfidence, window.zoneId,
		),
	)

	fun entryChecksum(
		identity: PortablePressureOpaqueIdentity,
		startTimeMs: Long,
		endTimeMs: Long,
		runs: List<PortablePressureRunV1>,
	): PortablePressureDigest = digest(
		ENTRY_NAMESPACE,
		listOf(
			identity.value,
			startTimeMs,
			endTimeMs,
			runs.map { run ->
				listOf(
					run.identity.value,
					run.startTimeMs,
					run.endTimeMs,
					run.capturedForWholeRun,
					run.availability.name,
					run.coverage.name,
					run.retentionLoss,
					run.windows.map { window ->
						listOf(window.identity.value, window.contentChecksum.value)
					},
				)
			},
		),
	)

	fun expectedEntryChecksum(entry: PortablePressureEntryV1): PortablePressureDigest =
		entryChecksum(entry.identity, entry.startTimeMs, entry.endTimeMs, entry.runs)

	internal fun digest(namespace: String, values: Any?): PortablePressureDigest {
		require(namespace.isNotBlank())
		val digest = MessageDigest.getInstance("SHA-256")
		digest.appendCanonical(listOf(namespace, values))
		return PortablePressureDigest("sha256:" + digest.digest().joinToString("") { byte ->
			(byte.toInt() and 0xff).toString(16).padStart(2, '0')
		})
	}

	@Suppress("CyclomaticComplexMethod")
	private fun MessageDigest.appendCanonical(value: Any?) {
		when (value) {
			null -> appendUtf8("N;")
			is Boolean -> appendUtf8(if (value) "B1;" else "B0;")
			is Int -> appendUtf8("I$value;")
			is Long -> appendUtf8("I$value;")
			is Float -> appendUtf8("F${value.toRawBits()};")
			is Double -> appendUtf8("D${value.toRawBits()};")
			is String -> {
				val bytes = value.toByteArray(Charsets.UTF_8)
				appendUtf8("S${bytes.size}:")
				update(bytes)
				appendUtf8(";")
			}
			is Collection<*> -> {
				appendUtf8("L${value.size}[")
				value.forEach { item -> appendCanonical(item) }
				appendUtf8("];")
			}
			else -> error("Unsupported portable Pressure checksum value ${value::class.java.name}")
		}
	}

	private fun MessageDigest.appendUtf8(value: String) = update(value.toByteArray(Charsets.UTF_8))
}

@Suppress("LongParameterList")
private fun windowValues(
	identity: PortablePressureOpaqueIdentity,
	intervalStartTimeMs: Long,
	intervalEndTimeMs: Long,
	wallTimeUncertaintyMs: Long,
	observedDurationNanos: Long,
	sampleCount: Int,
	expectedSampleCount: Int,
	meanHectopascals: Double,
	sumSquaredDeviations: Double,
	minimumHectopascals: Float,
	maximumHectopascals: Float,
	firstHectopascals: Float,
	latestHectopascals: Float,
	slopeHectopascalsPerSecond: Double?,
	rSquared: Double?,
	sensorAccuracy: PortablePressureSensorAccuracy,
	effectiveSamplePeriodMicros: Int,
	effectiveMaximumReportLatencyMicros: Int,
	targetWindowDurationNanos: Long,
	maximumInterSampleGapNanos: Long,
	closure: PortablePressureWindowClosure,
	qualification: PortablePressureWindowQualification,
	sourceQualityFlags: Long,
	sourceQualityConfidence: Float?,
	zoneId: String,
): List<Any?> = listOf(
	identity.value, intervalStartTimeMs, intervalEndTimeMs, wallTimeUncertaintyMs,
	observedDurationNanos,
	sampleCount, expectedSampleCount, meanHectopascals, sumSquaredDeviations,
	minimumHectopascals, maximumHectopascals, firstHectopascals, latestHectopascals,
	slopeHectopascalsPerSecond, rSquared, sensorAccuracy.name, effectiveSamplePeriodMicros,
	effectiveMaximumReportLatencyMicros, targetWindowDurationNanos, maximumInterSampleGapNanos,
	closure.name, qualification.name, sourceQualityFlags, sourceQualityConfidence, zoneId,
)

private fun requireValidZone(zoneId: String) {
	require(zoneId.isNotBlank() && zoneId.length <= PressurePortableFormatV1.MAX_ZONE_ID_LENGTH)
	try {
		ZoneId.of(zoneId)
	} catch (_: DateTimeException) {
		throw IllegalArgumentException("Portable Pressure window has invalid stored zone authority")
	}
}

private fun saturatedMultiply(first: Long, second: Long): Long =
	if (first == 0L || second <= Long.MAX_VALUE / first) first * second else Long.MAX_VALUE

private const val WINDOW_NAMESPACE = "tracker-portable-pressure-window-v1"
private const val ENTRY_NAMESPACE = "tracker-portable-pressure-entry-v1"
private val SHA_256_VALUE = Regex("sha256:[0-9a-f]{64}")
