package com.adsamcik.tracker.shared.base.database.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class ImportedWifiEntityTest {
	@Test
	fun `imported Wi-Fi hierarchy retains only opaque product evidence`() {
		entry().identity shouldBe ENTRY
		run().deletionScopeDigest shouldBe SCOPE
		zone().zoneId shouldBe "Europe/Prague"
		observation().observationCount shouldBe 2
	}

	@Test
	fun `observation rejects noncanonical uncertainty completeness and aggregate counts`() {
		shouldThrow<IllegalArgumentException> {
			observation().copy(latestPossibleTimeMs = 1_049L)
		}
		shouldThrow<IllegalArgumentException> {
			observation().copy(resultCompleteness = "PARTIAL")
		}
		shouldThrow<IllegalArgumentException> {
			observation().copy(fiveGhzCount = 2)
		}
	}

	@Test
	fun `payload-free deletion authority is self-verifying`() {
		val entryDeletion = ImportedWifiEntryDeletionEntity.create(ENTRY, 7L, 2L, 1_200L)
		val generation = ImportedWifiDeletionGenerationEntity.create(
			RUN, ENTRY, SCOPE, 7L, 1L, 1_200L,
		)

		entryDeletion.entryIdentity shouldBe ENTRY
		generation.deletionScopeDigest shouldBe SCOPE
		shouldThrow<IllegalArgumentException> {
			entryDeletion.copy(deletedAtMs = 1_201L)
		}
		shouldThrow<IllegalArgumentException> {
			generation.copy(generation = 2L)
		}
	}

	@Test
	fun `selected deletion receipt binds typed child owners before payload removal`() {
		val entryMarker = protected(
			WifiSelectedDeletionProtectedIdentityEntity.KIND_ENTRY,
			ENTRY,
			null,
			null,
		)
		val runMarker = protected(
			WifiSelectedDeletionProtectedIdentityEntity.KIND_RUN,
			RUN,
			RUN,
			SCOPE,
		)
		val scopeMarker = protected(
			WifiSelectedDeletionProtectedIdentityEntity.KIND_DELETION_SCOPE,
			SCOPE,
			RUN,
			SCOPE,
		)
		val observationMarker = protected(
			WifiSelectedDeletionProtectedIdentityEntity.KIND_OBSERVATION,
			OBSERVATION,
			RUN,
			null,
		)
		val runDeletion = WifiSelectedDeletionRunMarker(RUN, ENTRY, SCOPE, 7L, 1L, 1_400L)
		val fence = SourceDeletionFenceEntity.createForOriginalRunDigest(
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			SCOPE,
			1L,
			7L,
			1_400L,
		)
		val receipt = WifiSelectedDeletionReceiptEntity.create(
			selectionIdentity = ENTRY,
			origin = WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED,
			collectedDataEpoch = 7L,
			selectedImportRevision = 2L,
			selectedContentChecksum = CHECKSUM,
			startTimeMs = 800L,
			endTimeMs = 1_200L,
			protectedIdentities = listOf(entryMarker, runMarker, observationMarker, scopeMarker),
			runDeletionRows = listOf(runDeletion),
			sourceFences = listOf(fence),
			retainedFromMs = null,
			deletedAtMs = 1_400L,
		)

		receipt.expectedObservationCount shouldBe 1
		shouldThrow<IllegalArgumentException> {
			receipt.copy(expectedObservationCount = 2)
		}
		shouldThrow<IllegalArgumentException> {
			observationMarker.copy(ownerRunIdentity = "8".repeat(64))
		}
	}

	private fun protected(
		kind: String,
		identity: String,
		runIdentity: String?,
		scope: String?,
	) = WifiSelectedDeletionProtectedIdentityEntity.create(
		selectionIdentity = ENTRY,
		receiptOrigin = WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED,
		identityKind = kind,
		protectedIdentity = identity,
		ownerEntryIdentity = ENTRY,
		ownerRunIdentity = runIdentity,
		deletionScopeDigest = scope,
		aggregateOwnerIdentity = null,
		aggregateOwnerSemanticRevision = null,
		revisionCount = 1,
		revisionSetChecksum = CHECKSUM,
		collectedDataEpoch = 7L,
	)

	private fun entry() = ImportedWifiEntryRevisionEntity(
		identity = ENTRY,
		importRevision = 1L,
		supersedesImportRevision = null,
		contentChecksum = CHECKSUM,
		sourceFormat = ImportedWifiEntryRevisionEntity.SOURCE_FORMAT,
		sourceSchemaVersion = ImportedWifiEntryRevisionEntity.SOURCE_SCHEMA_VERSION,
		sessionMode = "MANUAL",
		startTimeMs = 800L,
		endTimeMs = 1_200L,
		collectedDataEpoch = 7L,
		importJobId = "job",
		importEntryKey = "entry",
		importSourceName = "source.trackerwifi",
		receivedAtMs = 1_300L,
	)

	private fun run() = ImportedWifiRunEntity(
		entryIdentity = ENTRY,
		entryImportRevision = 1L,
		identity = RUN,
		deletionScopeDigest = SCOPE,
		contentChecksum = RUN_CHECKSUM,
		startTimeMs = 800L,
		endTimeMs = 1_200L,
		captureCoverage = "WHOLE_RUN",
		availability = "RETAINED",
		acquisitionCompleteness = "COMPLETE",
		hasUnresolvedProviderRange = false,
		retentionLoss = false,
		collectedDataEpoch = 7L,
		scopeDeletionGeneration = 0L,
	)

	private fun zone() = ImportedWifiRunZoneEntity(ENTRY, 1L, RUN, 0, "Europe/Prague")

	private fun observation() = ImportedWifiObservationEntity(
		entryIdentity = ENTRY,
		entryImportRevision = 1L,
		runIdentity = RUN,
		identity = OBSERVATION,
		semanticRevision = 1L,
		supersedesSemanticRevision = null,
		aggregateOwnerIdentity = null,
		aggregateOwnerSemanticRevision = null,
		contentChecksum = OBSERVATION_CHECKSUM,
		coverageStartTimeMs = 900L,
		observedTimeMs = 1_000L,
		latestPossibleTimeMs = 1_050L,
		wallTimeUncertaintyMs = 50L,
		storedZoneId = "Europe/Prague",
		availability = "AVAILABLE",
		resultCompleteness = "COMPLETE",
		submittedResultCount = 2,
		acceptedResultCount = 2,
		staleResultCount = 0,
		clockUnverifiableResultCount = 0,
		malformedResultCount = 0,
		observationCount = 2,
		twoPointFourGhzCount = 1,
		fiveGhzCount = 1,
		sixGhzCount = 0,
		otherBandCount = 0,
		strongestSignalDbm = -40,
		weakestSignalDbm = -60,
		meanSignalDbm = -50.0,
		sourceQualityFlags = 0L,
		sourceQualityConfidence = 1f,
	)

	private companion object {
		val ENTRY = "1".repeat(64)
		val RUN = "2".repeat(64)
		val SCOPE = "3".repeat(64)
		val OBSERVATION = "4".repeat(64)
		val CHECKSUM = "5".repeat(64)
		val RUN_CHECKSUM = "6".repeat(64)
		val OBSERVATION_CHECKSUM = "7".repeat(64)
	}
}
