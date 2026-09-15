package com.adsamcik.tracker.impexp.portable

import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.PortablePressureAvailability
import com.adsamcik.tracker.stats.api.repository.PortablePressureCoverage
import com.adsamcik.tracker.stats.api.repository.PortablePressureDigest
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortablePressureOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureRunV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowV1
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

internal suspend fun encodePressureEntries(
	entries: List<PortablePressureEntryV1>,
	codec: PortablePressureJsonV1Codec = PortablePressureJsonV1Codec(),
): ByteArray {
	val output = ByteArrayOutputStream()
	codec.encode(output) { sink ->
		entries.forEach { entry -> sink.emit(entry) }
		ExportPortablePressureResult.Exported(entries.size)
	}
	return output.toByteArray()
}

internal fun pressureEntry(
	seed: String = "entry",
	startTimeMs: Long = 1_000L,
	runCount: Int = 1,
	windowsPerRun: Int = 1,
	meanHectopascals: Double = 1_000.25,
	sourceQualityConfidence: Float? = 0.75f,
): PortablePressureEntryV1 {
	val runs = (0 until runCount).map { runIndex ->
		val runStart = startTimeMs + runIndex * 10_000L
		val windows = (0 until windowsPerRun).map { windowIndex ->
			pressureWindow(
				seed = "$seed-$runIndex-$windowIndex",
				startTimeMs = runStart + windowIndex * 2_000L,
				meanHectopascals = meanHectopascals,
				sourceQualityConfidence = sourceQualityConfidence,
			)
		}
		PortablePressureRunV1(
			identity = pressureIdentity(
				PortablePressureIdentityKind.PHYSICAL_RUN,
				"$seed-run-$runIndex",
			),
			startTimeMs = windows.first().intervalStartTimeMs,
			endTimeMs = windows.last().intervalEndTimeMs,
			capturedForWholeRun = true,
			availability = PortablePressureAvailability.RETAINED,
			coverage = PortablePressureCoverage.COMPLETE,
			retentionLoss = false,
			windows = windows,
		)
	}
	return PortablePressureEntryV1.create(
		identity = pressureIdentity(PortablePressureIdentityKind.LOGICAL_ENTRY, seed),
		startTimeMs = runs.first().startTimeMs,
		endTimeMs = runs.last().endTimeMs,
		runs = runs,
	)
}

@Suppress("LongParameterList")
internal fun pressureWindow(
	seed: String,
	startTimeMs: Long,
	meanHectopascals: Double = 1_000.25,
	minimumHectopascals: Float = 999.5f,
	maximumHectopascals: Float = 1_001.5f,
	firstHectopascals: Float = 999.5f,
	latestHectopascals: Float = 1_001.5f,
	sumSquaredDeviations: Double = 2.0,
	slopeHectopascalsPerSecond: Double? = 2.0,
	rSquared: Double? = 0.875,
	sourceQualityConfidence: Float? = 0.75f,
): PortablePressureWindowV1 = PortablePressureWindowV1.create(
	identity = pressureIdentity(PortablePressureIdentityKind.WINDOW, "$seed-window"),
	intervalStartTimeMs = startTimeMs,
	intervalEndTimeMs = startTimeMs + 1L,
	wallTimeUncertaintyMs = 25L,
	observedDurationNanos = 1_000_000L,
	sampleCount = 2,
	expectedSampleCount = 2,
	meanHectopascals = meanHectopascals,
	sumSquaredDeviations = sumSquaredDeviations,
	minimumHectopascals = minimumHectopascals,
	maximumHectopascals = maximumHectopascals,
	firstHectopascals = firstHectopascals,
	latestHectopascals = latestHectopascals,
	slopeHectopascalsPerSecond = slopeHectopascalsPerSecond,
	rSquared = rSquared,
	sensorAccuracy = PortablePressureSensorAccuracy.HIGH,
	effectiveSamplePeriodMicros = 1_000,
	effectiveMaximumReportLatencyMicros = 250_000,
	targetWindowDurationNanos = 2_000_000L,
	maximumInterSampleGapNanos = 1_000_000L,
	closure = PortablePressureWindowClosure.TARGET_ELAPSED,
	qualification = PortablePressureWindowQualification.COMPLETE,
	sourceQualityFlags = 7L,
	sourceQualityConfidence = sourceQualityConfidence,
	zoneId = "Europe/Prague",
)

internal fun singleSamplePressureWindow(
	seed: String,
	startTimeMs: Long,
	pressure: Float = 1_000.125f,
): PortablePressureWindowV1 = PortablePressureWindowV1.create(
	identity = pressureIdentity(PortablePressureIdentityKind.WINDOW, "$seed-window"),
	intervalStartTimeMs = startTimeMs,
	intervalEndTimeMs = startTimeMs,
	wallTimeUncertaintyMs = 0L,
	observedDurationNanos = 0L,
	sampleCount = 1,
	expectedSampleCount = 1,
	meanHectopascals = pressure.toDouble(),
	sumSquaredDeviations = 0.0,
	minimumHectopascals = pressure,
	maximumHectopascals = pressure,
	firstHectopascals = pressure,
	latestHectopascals = pressure,
	slopeHectopascalsPerSecond = null,
	rSquared = null,
	sensorAccuracy = PortablePressureSensorAccuracy.UNKNOWN,
	effectiveSamplePeriodMicros = 1_000,
	effectiveMaximumReportLatencyMicros = 0,
	targetWindowDurationNanos = 1_000_000L,
	maximumInterSampleGapNanos = 0L,
	closure = PortablePressureWindowClosure.TARGET_ELAPSED,
	qualification = PortablePressureWindowQualification.COMPLETE,
	sourceQualityFlags = 0L,
	sourceQualityConfidence = null,
	zoneId = "UTC",
)

internal fun pressureEntryWithWindows(
	seed: String,
	windows: List<PortablePressureWindowV1>,
	runIdentity: PortablePressureOpaqueIdentity = pressureIdentity(
		PortablePressureIdentityKind.PHYSICAL_RUN,
		"$seed-run",
	),
): PortablePressureEntryV1 {
	val run = PortablePressureRunV1(
		identity = runIdentity,
		startTimeMs = windows.first().intervalStartTimeMs,
		endTimeMs = windows.last().intervalEndTimeMs,
		capturedForWholeRun = true,
		availability = PortablePressureAvailability.RETAINED,
		coverage = PortablePressureCoverage.COMPLETE,
		retentionLoss = false,
		windows = windows,
	)
	return PortablePressureEntryV1.create(
		identity = pressureIdentity(PortablePressureIdentityKind.LOGICAL_ENTRY, seed),
		startTimeMs = run.startTimeMs,
		endTimeMs = run.endTimeMs,
		runs = listOf(run),
	)
}

internal fun pressureIdentity(
	kind: PortablePressureIdentityKind,
	seed: String,
): PortablePressureOpaqueIdentity = PortablePressureOpaqueIdentity.derive(kind, seed)

internal fun rehashedPressureWindowChecksum(
	window: PortablePressureWindowV1,
	meanHectopascals: Double = window.meanHectopascals,
): PortablePressureDigest = testPressureDigest(
	"tracker-portable-pressure-window-v1",
	listOf(
		window.identity.value,
		window.intervalStartTimeMs,
		window.intervalEndTimeMs,
		window.wallTimeUncertaintyMs,
		window.observedDurationNanos,
		window.sampleCount,
		window.expectedSampleCount,
		meanHectopascals,
		window.sumSquaredDeviations,
		window.minimumHectopascals,
		window.maximumHectopascals,
		window.firstHectopascals,
		window.latestHectopascals,
		window.slopeHectopascalsPerSecond,
		window.rSquared,
		window.sensorAccuracy.name,
		window.effectiveSamplePeriodMicros,
		window.effectiveMaximumReportLatencyMicros,
		window.targetWindowDurationNanos,
		window.maximumInterSampleGapNanos,
		window.closure.name,
		window.qualification.name,
		window.sourceQualityFlags,
		window.sourceQualityConfidence,
		window.zoneId,
	),
)

internal fun rehashedPressureEntryChecksum(
	entry: PortablePressureEntryV1,
	startTimeMs: Long = entry.startTimeMs,
): PortablePressureDigest = testPressureDigest(
	"tracker-portable-pressure-entry-v1",
	listOf(
		entry.identity.value,
		startTimeMs,
		entry.endTimeMs,
		entry.runs.map { run ->
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

private fun testPressureDigest(namespace: String, values: Any?): PortablePressureDigest {
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
		else -> error("Unsupported Pressure test checksum value ${value::class.java.name}")
	}
}

private fun MessageDigest.appendUtf8(value: String) = update(value.toByteArray(Charsets.UTF_8))
