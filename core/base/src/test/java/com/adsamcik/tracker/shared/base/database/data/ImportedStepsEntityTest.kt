package com.adsamcik.tracker.shared.base.database.data

import java.time.DateTimeException
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportedStepsEntityTest {
	@Test
	fun `entry rejects malformed identity checksum mode times and epoch`() {
		val entry = ImportedStepsEntryEntity(opaque('1'), opaque('2'), "MANUAL", 10L, 20L, 0L)
		listOf<() -> Unit>(
			{ entry.copy(identity = "local-entry") },
			{ entry.copy(contentChecksum = "unverified") },
			{ entry.copy(sessionMode = "CONTROL") },
			{ entry.copy(startTimeMs = -1L) },
			{ entry.copy(endTimeMs = 9L) },
			{ entry.copy(collectedDataEpoch = -1L) },
		).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
	}

	@Test
	fun `run rejects malformed identity original scope zone and coverage vocabulary`() {
		val run = ImportedStepsRunEntity(
			opaque('2'), opaque('1'), "3".repeat(64), 10L, 20L, "UTC", "WHOLE_RUN", "COMPLETE",
			appDrainComplete = true, stopComplete = true, hasUnresolvedProviderRange = false,
		)
		listOf<() -> Unit>(
			{ run.copy(identity = "local-run") },
			{ run.copy(entryIdentity = "local-entry") },
			{ run.copy(deletionScopeDigest = opaque('3')) },
			{ run.copy(storedZoneId = "") },
			{ run.copy(storedZoneId = "z".repeat(129)) },
			{ run.copy(startTimeMs = -1L) },
			{ run.copy(endTimeMs = 9L) },
			{ run.copy(captureCoverage = "CONTROL") },
			{ run.copy(providerCoverage = "ASSUMED") },
		).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
		assertThrows(DateTimeException::class.java) { run.copy(storedZoneId = "not-a-zone") }
	}

	@Test
	fun `manifest rejects missing original authority but never invents a local grant`() {
		val manifest = ImportedStepsManifestEntity(opaque('2'), 1L, 0L, 1L, 0L)
		listOf<() -> Unit>(
			{ manifest.copy(runIdentity = "local-run") },
			{ manifest.copy(revision = 0L) },
			{ manifest.copy(effectiveWallTimeMs = -1L) },
			{ manifest.copy(originSourcePolicyRevision = 0L) },
			{ manifest.copy(captureConsentEpoch = -1L) },
		).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
	}

	private fun opaque(character: Char): String = "sha256:${character.toString().repeat(64)}"
}
