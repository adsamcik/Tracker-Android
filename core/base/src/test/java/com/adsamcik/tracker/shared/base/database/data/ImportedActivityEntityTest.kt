package com.adsamcik.tracker.shared.base.database.data

import org.junit.Assert.assertThrows
import org.junit.Test

class ImportedActivityEntityTest {
	@Test
	fun `entry rejects noncaptured format control mode revision gaps and unbounded provenance`() {
		val entry = entry()
		listOf<() -> Unit>(
			{ entry.copy(identity = "local") },
			{ entry.copy(importRevision = 2L) },
			{ entry.copy(sourceFormat = "generic") },
			{ entry.copy(sourceSchemaVersion = 2) },
			{ entry.copy(sessionMode = "CONTROL") },
			{ entry.copy(importJobId = "") },
			{ entry.copy(importSourceName = "x".repeat(4_097)) },
		).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
	}

	@Test
	fun `run and window retain exact coverage epoch and deletion fields`() {
		val run = run()
		val window = window()
		val fragment = fragment()
		listOf<() -> Unit>(
			{ run.copy(deletionScopeDigest = "scope") },
			{ run.copy(captureCoverage = "CONTROL") },
			{ run.copy(scopeDeletionGeneration = -1L) },
			{ window.copy(coverage = "ASSUMED") },
			{ window.copy(storedZoneId = "not/a-zone") },
			{ window.copy(endOffsetNanos = 0L) },
			{ fragment.copy(activity = "x".repeat(129)) },
		).forEach { invalid -> assertThrows(RuntimeException::class.java) { invalid() } }
	}

	@Test
	fun `entry and run tombstone checksums bind all no resurrection authority`() {
		val entryDeletion = ImportedActivityEntryDeletionEntity.create(digest('1'), 7L, 2L, 30L)
		val runDeletion = ImportedActivityDeletionGenerationEntity.create(digest('2'), 7L, 1L, 30L)
		listOf<() -> Unit>(
			{ entryDeletion.copy(deletedImportRevision = 1L) },
			{ entryDeletion.copy(effectChecksum = digest('9')) },
			{ runDeletion.copy(generation = 2L) },
			{ runDeletion.copy(collectedDataEpoch = 8L) },
		).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
	}

	private fun entry() = ImportedActivityEntryRevisionEntity(
		digest('1'), 1L, null, digest('2'), ImportedActivityEntryRevisionEntity.SOURCE_FORMAT,
		1, "MANUAL", 10L, 20L, 7L, "job", "entry", "backup.trackeractivity", 30L,
	)

	private fun run() = ImportedActivityRunEntity(
		digest('1'), 1L, digest('3'), digest('4'), digest('5'), 10L, 20L, "WHOLE_RUN",
		7L, 0L,
	)

	private fun window() = ImportedActivityWindowEntity(
		digest('1'), 1L, digest('3'), digest('6'), digest('7'), 0L, 100L, "UTC", "NONE",
		0L, 0L, 0L, 100L,
	)

	private fun fragment() = ImportedActivityFragmentEntity(
		entryIdentity = digest('1'),
		entryImportRevision = 1L,
		runIdentity = digest('3'),
		windowIdentity = digest('6'),
		ordinal = 0,
		fragmentKind = ImportedActivityFragmentEntity.KIND_BAND,
		startOffsetNanos = 0L,
		endOffsetNanos = 100L,
		gapReason = null,
		activity = "WALKING",
		mechanism = "TRANSITION",
		refinedTransitionActivity = null,
		confidenceKind = "TRANSITION_SIGNAL",
		confidenceMinimumPercent = null,
		confidenceMaximumPercent = null,
		confidenceObservationCount = null,
		startWallTimeMs = 10L,
		startWallTimeUncertaintyMs = 0L,
		startBoundaryKind = "EXACT_PROVIDER_OBSERVATION",
		endWallTimeMs = 11L,
		endWallTimeUncertaintyMs = 0L,
		endBoundaryKind = "SAME_CLOCK_EXTRAPOLATION",
		wallTimeContinuity = "SAME_ANCHOR",
	)

	private fun digest(character: Char) = character.toString().repeat(64)
}
