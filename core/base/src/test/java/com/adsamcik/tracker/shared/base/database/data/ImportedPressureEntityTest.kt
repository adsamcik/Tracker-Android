package com.adsamcik.tracker.shared.base.database.data

import java.time.DateTimeException
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportedPressureEntityTest {
	@Test
	fun `entry preserves opaque revision and copied receipt provenance`() {
		val entry = entry()
		listOf<() -> Unit>(
			{ entry.copy(identity = "local") },
			{ entry.copy(importRevision = 0L) },
			{ entry.copy(supersedesImportRevision = 1L) },
			{ entry.copy(sourceFormat = "generic") },
			{ entry.copy(sourceSchemaVersion = 2) },
			{ entry.copy(importJobId = "") },
			{ entry.copy(startTimeMs = -1L) },
			{ entry.copy(endTimeMs = 9L) },
		).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
	}

	@Test
	fun `run preserves exact retention loss and deletion generation vocabulary`() {
		val run = run()
		listOf<() -> Unit>(
			{ run.copy(identity = "run") },
			{ run.copy(availability = "CONTROL") },
			{ run.copy(coverage = "INFERRED") },
			{ run.copy(retentionLoss = true) },
			{ run.copy(scopeDeletionGeneration = -1L) },
			{
				run.copy(
					availability = ImportedPressureRunEntity.AVAILABILITY_NO_RETAINED_OBSERVATION,
					coverage = ImportedPressureRunEntity.COVERAGE_NONE,
					retentionLoss = true,
				)
			},
		).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
	}

	@Test
	fun `window preserves portable numeric quality uncertainty and zone shape`() {
		val window = window()
		listOf<() -> Unit>(
			{ window.copy(contentChecksum = "unchecked") },
			{ window.copy(wallTimeUncertaintyMs = -1L) },
			{ window.copy(meanHectopascals = Double.NaN) },
			{ window.copy(minimumHectopascals = 1_001f) },
			{ window.copy(sensorAccuracy = "PRECISE") },
			{ window.copy(sourceQualityConfidence = 1.1f) },
		).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
		assertThrows(DateTimeException::class.java) { window.copy(storedZoneId = "not/a-zone") }
	}

	@Test
	fun `deletion generation is positive and self identified`() {
		val generation = ImportedPressureDeletionGenerationEntity.create(opaque('3'), 7L, 1L, 30L)
		listOf<() -> Unit>(
			{ generation.copy(generation = 0L) },
			{ generation.copy(effectChecksum = "local") },
		).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
	}

	private fun entry() = ImportedPressureEntryRevisionEntity(
		identity = opaque('1'),
		importRevision = 1L,
		supersedesImportRevision = null,
		contentChecksum = opaque('2'),
		sourceFormat = ImportedPressureEntryRevisionEntity.SOURCE_FORMAT,
		sourceSchemaVersion = ImportedPressureEntryRevisionEntity.SOURCE_SCHEMA_VERSION,
		startTimeMs = 10L,
		endTimeMs = 20L,
		collectedDataEpoch = 7L,
		importJobId = "job-1",
		importEntryKey = "entry-1",
		importSourceName = "pressure.trackerpressure",
		receivedAtMs = 30L,
	)

	private fun run() = ImportedPressureRunEntity(
		entryIdentity = opaque('1'),
		entryImportRevision = 1L,
		identity = opaque('3'),
		startTimeMs = 10L,
		endTimeMs = 20L,
		capturedForWholeRun = true,
		availability = ImportedPressureRunEntity.AVAILABILITY_RETAINED,
		coverage = ImportedPressureRunEntity.COVERAGE_COMPLETE,
		retentionLoss = false,
		collectedDataEpoch = 7L,
		scopeDeletionGeneration = 0L,
	)

	private fun window() = ImportedPressureWindowEntity(
		entryIdentity = opaque('1'), entryImportRevision = 1L, runIdentity = opaque('3'),
		identity = opaque('4'), contentChecksum = opaque('5'), intervalStartTimeMs = 10L,
		intervalEndTimeMs = 10L, wallTimeUncertaintyMs = 2L, observedDurationNanos = 0L,
		sampleCount = 1, expectedSampleCount = 1, meanHectopascals = 1_000.0,
		sumSquaredDeviations = 0.0, minimumHectopascals = 1_000f,
		maximumHectopascals = 1_000f, firstHectopascals = 1_000f, latestHectopascals = 1_000f,
		slopeHectopascalsPerSecond = null, rSquared = null, sensorAccuracy = "HIGH",
		effectiveSamplePeriodMicros = 1_000, effectiveMaximumReportLatencyMicros = 0,
		targetWindowDurationNanos = 1_000_000L, maximumInterSampleGapNanos = 0L,
		closureKind = "TARGET_ELAPSED", qualification = "COMPLETE", sourceQualityFlags = 0L,
		sourceQualityConfidence = 1f, storedZoneId = "Europe/Prague",
	)

	private fun opaque(character: Char): String = "sha256:${character.toString().repeat(64)}"
}
