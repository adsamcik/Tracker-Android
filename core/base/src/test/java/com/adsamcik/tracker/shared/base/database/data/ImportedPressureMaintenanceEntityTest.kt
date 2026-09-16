package com.adsamcik.tracker.shared.base.database.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class ImportedPressureMaintenanceEntityTest {
	@Test
	fun `retention receipt authenticates typed entry run scope and window authority`() {
		val markers = listOf(
			ImportedPressureRetainedIdentityEntity(
				ENTRY,
				ENTRY,
				ImportedPressureRetainedIdentityEntity.ENTRY,
			),
			ImportedPressureRetainedIdentityEntity(
				RUN,
				ENTRY,
				ImportedPressureRetainedIdentityEntity.RUN_SCOPE,
			),
			ImportedPressureRetainedIdentityEntity(
				WINDOW,
				ENTRY,
				ImportedPressureRetainedIdentityEntity.WINDOW,
			),
		)
		val header = header()
		val importReceipt = receipt()
		val identityFences = listOf(
			ImportedPressureIdentityFenceEntity.create(
				ENTRY,
				ImportedPressureIdentityFenceEntity.ENTRY,
				ENTRY,
				null,
				7L,
				2_100L,
				ImportedPressureIdentityFenceEntity.REASON_RETENTION,
			),
			ImportedPressureIdentityFenceEntity.create(
				RUN,
				ImportedPressureIdentityFenceEntity.RUN,
				ENTRY,
				RUN,
				7L,
				2_100L,
				ImportedPressureIdentityFenceEntity.REASON_RETENTION,
			),
			ImportedPressureIdentityFenceEntity.create(
				WINDOW,
				ImportedPressureIdentityFenceEntity.WINDOW,
				ENTRY,
				RUN,
				7L,
				2_100L,
				ImportedPressureIdentityFenceEntity.REASON_RETENTION,
			),
		)
		val retained = ImportedPressureRetentionReceiptEntity.create(
			entryIdentity = ENTRY,
			collectedDataEpoch = 7L,
			sourceEvidenceRevision = 4L,
			retainedFromMs = 1_100L,
			retainedAtMs = 2_100L,
			latestImportRevision = 1L,
			latestContentChecksum = CHECKSUM,
			startTimeMs = 1_000L,
			endTimeMs = 2_000L,
			receivedAtMs = 2_000L,
			recencyStartTimeMs = 1_000L,
			recencyEndTimeMs = 2_000L,
			recencyTieIdentity = RUN,
			revisionCount = 1,
			importReceiptCount = 1,
			runRowCount = 1,
			windowRowCount = 1,
			runDeletions = emptyList(),
			markers = markers,
			identityFences = identityFences,
			lineageAuthorityChecksum = ImportedPressureRetentionReceiptEntity
				.lineageAuthorityChecksum(listOf(header), listOf(importReceipt)),
		)

		retained.authenticates(markers) shouldBe true
		retained.authenticates(markers.dropLast(1)) shouldBe false
	}

	@Test
	fun `source erase receipt rejects count or checksum drift`() {
		val valid = ImportedPressureSourceEraseEntity.create(
			collectedDataEpoch = 7L,
			sourceEvidenceRevision = 5L,
			erasedAtMs = 3_000L,
			providerRegistrationGeneration = 2L,
			legacyWriteFenceOwner =
				SourceDestinationOwnerEntity.OWNER_CONTAINED_PRESSURE_SESSION_FACTS,
			legacyWriteFenceGeneration = 3L,
			localFactRevisionCount = 2,
			localWalEventCount = 1,
			legacySampleCount = 3,
			legacySampleWitnesses = listOf(
				ImportedPressureSourceEraseWitnessEntity.legacy(
					"sha256:${"9".repeat(64)}",
					"sha256:${"a".repeat(64)}",
				),
				ImportedPressureSourceEraseWitnessEntity.legacy(
					"sha256:${"b".repeat(64)}",
					"sha256:${"c".repeat(64)}",
				),
				ImportedPressureSourceEraseWitnessEntity.legacy(
					"sha256:${"d".repeat(64)}",
					"sha256:${"e".repeat(64)}",
				),
			),
			importedEntryCount = 0,
			importedRevisionCount = 0,
			importedRunCount = 0,
			importedWindowCount = 0,
			localFences = emptyList(),
			entryDeletions = emptyList(),
			runDeletions = emptyList(),
			identityFences = emptyList(),
		)

		shouldThrow<IllegalArgumentException> {
			valid.copy(importedWindowCount = 3)
		}
	}

	@Test
	fun `new source erase receipts pair every nonzero fence with its exact owner`() {
		listOf(
			SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE to 2L,
			SourceDestinationOwnerEntity.OWNER_CONTAINED_PRESSURE_SESSION_FACTS to 3L,
		).forEach { (owner, generation) ->
			emptySourceErase(owner, generation).legacyWriteFenceOwner shouldBe owner
		}

		shouldThrow<IllegalArgumentException> {
			emptySourceErase(owner = null, generation = 2L)
		}
		shouldThrow<IllegalArgumentException> {
			emptySourceErase(
				owner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
				generation = 0L,
			)
		}
	}

	@Test
	fun `ownerless v1 source erase receipt retains its original checksum`() {
		val legacy = legacyOwnerlessSourceErase(generation = 2L)

		legacy.legacyWriteFenceOwner shouldBe null
		legacy.legacyWriteFenceGeneration shouldBe 2L
		legacy.effectChecksum shouldBe legacyOwnerlessSourceEraseChecksum(generation = 2L)
	}

	private fun emptySourceErase(
		owner: String?,
		generation: Long,
	) = ImportedPressureSourceEraseEntity.create(
		collectedDataEpoch = 7L,
		sourceEvidenceRevision = 5L,
		erasedAtMs = 3_000L,
		providerRegistrationGeneration = null,
		legacyWriteFenceOwner = owner,
		legacyWriteFenceGeneration = generation,
		localFactRevisionCount = 0,
		localWalEventCount = 0,
		legacySampleCount = 0,
		legacySampleWitnesses = emptyList(),
		importedEntryCount = 0,
		importedRevisionCount = 0,
		importedRunCount = 0,
		importedWindowCount = 0,
		localFences = emptyList(),
		entryDeletions = emptyList(),
		runDeletions = emptyList(),
		identityFences = emptyList(),
	)

	private fun legacyOwnerlessSourceErase(generation: Long): ImportedPressureSourceEraseEntity {
		val legacySamples = ImportedPressureSourceEraseEntity.checksumLegacySamples(emptyList())
		val localFences = ImportedPressureSourceEraseEntity.checksumLocalFences(emptyList())
		val entryDeletions = ImportedPressureSourceEraseEntity.checksumEntryDeletions(emptyList())
		val runDeletions = ImportedPressureRetentionReceiptEntity.checksumRunDeletions(emptyList())
		val identityFences = ImportedPressureIdentityFenceEntity.checksumSet(emptyList())
		return ImportedPressureSourceEraseEntity(
			collectedDataEpoch = 7L,
			sourceEvidenceRevision = 5L,
			erasedAtMs = 3_000L,
			providerRegistrationGeneration = null,
			legacyWriteFenceOwner = null,
			legacyWriteFenceGeneration = generation,
			localFactRevisionCount = 0,
			localWalEventCount = 0,
			legacySampleCount = 0,
			legacySampleSetChecksum = legacySamples,
			importedEntryCount = 0,
			importedRevisionCount = 0,
			importedRunCount = 0,
			importedWindowCount = 0,
			fencedLocalRunCount = 0,
			localScopeSetChecksum = localFences,
			entryDeletionCount = 0,
			entryDeletionSetChecksum = entryDeletions,
			runDeletionCount = 0,
			runDeletionSetChecksum = runDeletions,
			identityFenceCount = 0,
			identityFenceSetChecksum = identityFences,
			effectChecksum = legacyOwnerlessSourceEraseChecksum(generation),
		)
	}

	private fun legacyOwnerlessSourceEraseChecksum(generation: Long): String =
		ImportedPressureIdentity.digest(
			"tracker-imported-pressure-source-erase-v1",
			listOf(
				7L,
				5L,
				3_000L,
				"NONE",
				generation,
				0,
				0,
				0,
				ImportedPressureSourceEraseEntity.checksumLegacySamples(emptyList()),
				0,
				0,
				0,
				0,
				0,
				ImportedPressureSourceEraseEntity.checksumLocalFences(emptyList()),
				0,
				ImportedPressureSourceEraseEntity.checksumEntryDeletions(emptyList()),
				0,
				ImportedPressureRetentionReceiptEntity.checksumRunDeletions(emptyList()),
				0,
				ImportedPressureIdentityFenceEntity.checksumSet(emptyList()),
			).map { it.toString() },
		)

	private fun header() = ImportedPressureEntryRevisionEntity(
		identity = ENTRY,
		importRevision = 1L,
		supersedesImportRevision = null,
		contentChecksum = CHECKSUM,
		sourceFormat = ImportedPressureEntryRevisionEntity.SOURCE_FORMAT,
		sourceSchemaVersion = ImportedPressureEntryRevisionEntity.SOURCE_SCHEMA_VERSION,
		startTimeMs = 1_000L,
		endTimeMs = 2_000L,
		collectedDataEpoch = 7L,
		importJobId = "job",
		importEntryKey = "entry",
		importSourceName = "backup.trackerpressure",
		receivedAtMs = 2_000L,
	)

	private fun receipt() = ImportedPressureReceiptEntity(
		importJobId = "job",
		importEntryKey = "entry",
		importSourceName = "backup.trackerpressure",
		receivedAtMs = 2_000L,
		entryIdentity = ENTRY,
		entryImportRevision = 1L,
		entryContentChecksum = CHECKSUM,
		collectedDataEpoch = 7L,
	)

	private companion object {
		val ENTRY = "sha256:${"1".repeat(64)}"
		val RUN = "sha256:${"2".repeat(64)}"
		val WINDOW = "sha256:${"3".repeat(64)}"
		val CHECKSUM = "sha256:${"4".repeat(64)}"
	}
}
