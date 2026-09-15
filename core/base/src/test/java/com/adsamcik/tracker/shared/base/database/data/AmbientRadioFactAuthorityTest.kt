package com.adsamcik.tracker.shared.base.database.data

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AmbientRadioFactAuthorityTest {
	@Test
	fun `Wi-Fi authority binds exact consent retention deletion and boot boundary`() {
		val authority = AmbientWifiAuthorityIntegrity.create(
			authorityRevision = 4L,
			state = AmbientWifiAuthorityEntity.STATE_ACTIVE,
			sourcePolicyRevision = 8L,
			ambientConsentEpoch = 3L,
			retentionPolicyId = "privacy:wifi:ambient:v1",
			retentionApprovalRevision = 2L,
			collectedDataEpoch = 5L,
			scopeDeletionGeneration = 1L,
			effectiveBootId = "boot-7",
			effectiveElapsedRealtimeNanos = 100L,
			effectiveWallTimeMs = 200L,
			rolloutRevision = 1L,
			ownerCasToken = "wifi-owner",
			reconciliationAttempt = 1L,
			demandId = "wifi-demand",
		)

		assertTrue(authority.isActive)
		assertEquals(AmbientWifiAuthorityIntegrity.checksum(authority), authority.effectChecksum)
		assertFailsWith<IllegalArgumentException> {
			authority.copy(ambientConsentEpoch = 4L)
		}
	}

	@Test
	fun `Cell revoke retains exact retired authority without becoming active`() {
		val revoked = AmbientCellAuthorityIntegrity.create(
			authorityRevision = 5L,
			state = AmbientCellAuthorityEntity.STATE_REVOKED,
			sourcePolicyRevision = 9L,
			ambientConsentEpoch = 4L,
			retentionPolicyId = "privacy:cell:ambient:v1",
			retentionApprovalRevision = 3L,
			collectedDataEpoch = 6L,
			scopeDeletionGeneration = 2L,
			effectiveBootId = "boot-8",
			effectiveElapsedRealtimeNanos = 101L,
			effectiveWallTimeMs = 201L,
			rolloutRevision = 1L,
			ownerCasToken = "cell-owner",
			reconciliationAttempt = 1L,
			demandId = "cell-demand",
		)

		assertFalse(revoked.isActive)
		assertEquals(AmbientCellAuthorityIntegrity.checksum(revoked), revoked.effectChecksum)
	}

	@Test
	fun `source deletion and import tombstones are distinct opaque authorities`() {
		val deletion = AmbientWifiFactIntegrity.createDeletionMarker(
			collectedDataEpoch = 5L,
			deletionGeneration = 2L,
			throughConsentEpoch = 7L,
			reason = "TEST",
			deletedAtMs = 1_000L,
		)
		val import = AmbientWifiFactIntegrity.createImportTombstone(
			archiveId = digest("archive"),
			collectedDataEpoch = 5L,
			deletionGeneration = 2L,
			deletedAtMs = 1_000L,
		)

		assertEquals(2L, deletion.deletionGeneration)
		assertEquals(2L, import.deletionGeneration)
		assertTrue(deletion.effectChecksum != import.effectChecksum)
	}

	@Test
	fun `imported ambient radio facts preserve structural DST day and contain no radio identity`() {
		val zone = ZoneId.of("Europe/Prague")
		val date = LocalDate.of(2026, 3, 29)
		val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
		val observed = start + 60L * 60L * 1_000L
		val wifi = ImportedAmbientWifiFactEntity(
			archiveId = digest("wifi-archive"),
			factId = digest("wifi-fact"),
			semanticRevision = 1L,
			supersedesSemanticRevision = null,
			contentChecksum = digest("wifi-content"),
			portableEffectChecksum = digest("wifi-effect"),
			portableOrigin = "PORTABLE_IMPORT",
			coverageStartTimeMs = observed,
			observedTimeMs = observed,
			latestPossibleTimeMs = observed + 1L,
			storedZoneId = zone.id,
			structuralEpochDay = date.toEpochDay(),
			coverageCompleteness = AmbientWifiFactRevisionEntity.COMPLETENESS_UNVERIFIABLE,
			observationCount = 2,
			twoPointFourGhzCount = 1,
			fiveGhzCount = 1,
			sixGhzCount = 0,
			otherBandCount = 0,
			strongestSignalDbm = -40,
			weakestSignalDbm = -80,
			meanSignalDbm = -60.0,
			retentionPolicyId = "privacy:wifi:ambient:v1",
			retentionApprovalRevision = 1L,
			collectedDataEpoch = 1L,
			importDeletionGeneration = 0L,
			receivedAtMs = observed + 2L,
		)
		val cell = ImportedAmbientCellFactEntity(
			archiveId = digest("cell-archive"),
			factId = digest("cell-fact"),
			semanticRevision = 1L,
			supersedesSemanticRevision = null,
			contentChecksum = digest("cell-content"),
			portableEffectChecksum = digest("cell-effect"),
			portableOrigin = "PORTABLE_IMPORT",
			coverageStartTimeMs = observed,
			observedTimeMs = observed,
			latestPossibleTimeMs = observed + 1L,
			storedZoneId = zone.id,
			structuralEpochDay = date.toEpochDay(),
			coverageCompleteness = AmbientCellFactRevisionEntity.COMPLETENESS_UNVERIFIABLE,
			subscriptionCompleteness = AmbientCellFactRevisionEntity.SUBSCRIPTION_PARTIAL,
			observationCount = 2,
			registeredObservationCount = 1,
			gsmCount = 0,
			cdmaCount = 0,
			wcdmaCount = 0,
			tdscdmaCount = 0,
			lteCount = 1,
			nrCount = 1,
			qualityUnknownCount = 0,
			qualityNoneOrUnknownCount = 0,
			qualityPoorCount = 1,
			qualityModerateCount = 0,
			qualityGoodCount = 1,
			qualityGreatCount = 0,
			retentionPolicyId = "privacy:cell:ambient:v1",
			retentionApprovalRevision = 1L,
			collectedDataEpoch = 1L,
			importDeletionGeneration = 0L,
			receivedAtMs = observed + 2L,
		)

		assertEquals(date.toEpochDay(), wifi.structuralEpochDay)
		assertEquals(AmbientCellFactRevisionEntity.SUBSCRIPTION_PARTIAL, cell.subscriptionCompleteness)
		val storedNames = (
			AmbientWifiFactRevisionEntity::class.java.declaredFields +
				AmbientCellFactRevisionEntity::class.java.declaredFields
			).map { it.name.lowercase() }
		assertTrue(storedNames.none {
			"ssid" in it || "bssid" in it || "tower" in it || "sim" in it ||
				"subscriptionid" in it
		})
	}

	private fun digest(value: String) =
		AmbientWifiAuthorityIntegrity.digest("test", value)
}
