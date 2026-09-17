package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiFactEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Focused Room authority/retention source; execution is deferred to final convergence. */
@RunWith(RobolectricTestRunner::class)
class AmbientRadioAuthorityRoomTest {
	private lateinit var database: AppDatabase

	@BeforeTest
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@AfterTest
	fun tearDown() {
		database.close()
	}

	@Test
	fun `wrong boot can never resolve ambient authority`() = runTest {
		database.ambientWifiFactDao().insertAuthority(wifiAuthority())
		database.ambientCellFactDao().insertAuthority(cellAuthority())

		assertNull(database.ambientWifiFactDao().authorityAt("wrong-boot", 100L))
		assertNull(database.ambientCellFactDao().authorityAt("wrong-boot", 100L))
		assertEquals(1L,
			database.ambientWifiFactDao().authorityAt("boot-1", 100L)?.authorityRevision)
		assertEquals(1L,
			database.ambientCellFactDao().authorityAt("boot-1", 100L)?.authorityRevision)
	}

	@Test
	fun `retention is default deny until durable source approval exists`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(
				collectedDataEpoch = 4L,
				retainedFromMs = 1_000L,
				updatedAtMs = 1_000L,
			),
		)

		val mismatch = database.pruneAmbientWifi(
			AmbientWifiRetentionCommand(
				beforeMs = 1_000L,
				expectedCollectedDataEpoch = 4L,
				appliedAtMs = 2_000L,
			),
		)
		assertEquals(
			AmbientWifiMaintenanceUnavailableReason.RETENTION_AUTHORITY_UNAVAILABLE,
			assertIs<AmbientWifiRetentionResult.Unavailable>(mismatch).reason,
		)
		database.ambientWifiFactDao().insertRetentionAuthority(
			AmbientWifiRetentionAuthorityIntegrity.create(
				AmbientWifiRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
				1L,
				AmbientWifiRetentionAuthorityEntity.STATE_ACTIVE,
				"privacy:wifi:import:v1",
				null,
				null,
				4L,
				"boot-1",
				10L,
				10L,
			),
		)

		assertEquals(
			AmbientWifiRetentionResult.NoChange,
			database.pruneAmbientWifi(
				AmbientWifiRetentionCommand(
					beforeMs = 1_000L,
					expectedCollectedDataEpoch = 4L,
					appliedAtMs = 2_000L,
				),
			),
		)
	}

	@Test
	fun `zero-row consent deletion still advances the authenticated source fence`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 4L, updatedAtMs = 1L),
		)
		var elapsed = 1L
		val policies = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}

		policies.bootstrapFromLegacy(
			TrackingParamsState(legacySettingsMigrationCompleted = true),
		)
		val active = policies.setNonCaptureConsent(
			(policies.currentState() as SourcePolicyAuthorityState.Active).snapshot.revision,
			TrackingSourceComponent.WIFI,
			SourcePurpose.AMBIENT_PRODUCT,
			eligible = true,
			persistenceEligible = true,
			reason = "TEST_GRANT",
		)
		requireNotNull(active[TrackingSourceComponent.WIFI].ambientConsentEpoch)
		val revoked = policies.setNonCaptureConsent(
			active.revision,
			TrackingSourceComponent.WIFI,
			SourcePurpose.AMBIENT_PRODUCT,
			eligible = false,
			persistenceEligible = false,
			reason = "TEST_REVOKE",
		)
		val revokedEpoch = requireNotNull(
			database.sourcePolicyDao().latestConsentEpoch(
				com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity.SOURCE_WIFI,
				com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose.AMBIENT_PRODUCT,
			),
		).epoch
		database.ambientWifiFactDao().insertAuthority(
			AmbientWifiAuthorityIntegrity.create(
				1L,
				AmbientWifiAuthorityEntity.STATE_REVOKED,
				revoked.revision,
				revokedEpoch,
				"privacy:wifi:ambient:v1",
				1L,
				4L,
				0L,
				"boot-1",
				10L,
				10L,
				1L,
				"wifi-owner",
				1L,
				null,
			),
		)

		val deleted = assertIs<AmbientWifiDeletionResult.Deleted>(
			database.deleteAmbientWifiAfterConsentReset(4L, revokedEpoch, 20L),
		)

		assertEquals(0L, deleted.localRevisionCount)
		assertEquals(1L, deleted.deletionGeneration)
		assertEquals(
			AmbientWifiDeletionResult.AlreadyDeleted,
			database.deleteAmbientWifiAfterConsentReset(4L, revokedEpoch, 21L),
		)
	}

	@Test
	fun `retention authority replacement requires the exact durable prior revision`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 4L, updatedAtMs = 1L),
		)
		assertIs<AmbientRadioRetentionAuthorityResult.Applied>(
			database.applyAmbientWifiRetentionDecision(
				AmbientRadioRetentionDecision.GrantPortableImport(
					"privacy:wifi:import:v1",
					4L,
					"boot-1",
					1L,
					1L,
				),
			),
		)
		assertEquals(
			AmbientRadioRetentionAuthorityUnavailableReason.STALE_APPROVAL_REVISION,
			assertIs<AmbientRadioRetentionAuthorityResult.Unavailable>(
				database.applyAmbientWifiRetentionDecision(
					AmbientRadioRetentionDecision.GrantPortableImport(
						"privacy:wifi:import:v2",
						4L,
						"boot-1",
						2L,
						2L,
					),
				),
			).reason,
		)
		assertEquals(
			2L,
			assertIs<AmbientRadioRetentionAuthorityResult.Applied>(
				database.applyAmbientWifiRetentionDecision(
					AmbientRadioRetentionDecision.GrantPortableImport(
						"privacy:wifi:import:v2",
						4L,
						"boot-1",
						2L,
						2L,
						expectedPreviousApprovalRevision = 1L,
					),
				),
			).approvalRevision,
		)
	}

	@Test
	fun `same boot retention revisions require strictly increasing elapsed time`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 4L, updatedAtMs = 1L),
		)
		database.applyAmbientWifiRetentionDecision(
			AmbientRadioRetentionDecision.GrantPortableImport(
				"policy-1",
				4L,
				"boot-1",
				10L,
				10L,
			),
		)

		assertEquals(
			AmbientRadioRetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
			assertIs<AmbientRadioRetentionAuthorityResult.Unavailable>(
				database.applyAmbientWifiRetentionDecision(
					AmbientRadioRetentionDecision.GrantPortableImport(
						"policy-2",
						4L,
						"boot-1",
						10L,
						11L,
						expectedPreviousApprovalRevision = 1L,
					),
				),
			).reason,
		)
		assertEquals(
			AmbientRadioRetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
			assertIs<AmbientRadioRetentionAuthorityResult.Unavailable>(
				database.applyAmbientWifiRetentionDecision(
					AmbientRadioRetentionDecision.GrantPortableImport(
						"policy-2",
						4L,
						"boot-1",
						11L,
						9L,
						expectedPreviousApprovalRevision = 1L,
					),
				),
			).reason,
		)
	}

	@Test
	fun `live radio grant cannot predate referenced policy and consent`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 4L, updatedAtMs = 1L),
		)
		var elapsed = 10L
		val policies = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		val snapshot = policies.bootstrapFromLegacy(
			TrackingParamsState(
				ambientWifiEnabled = true,
				legacySettingsMigrationCompleted = true,
			),
		)

		assertEquals(
			AmbientRadioRetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
			assertIs<AmbientRadioRetentionAuthorityResult.Unavailable>(
				database.applyAmbientWifiRetentionDecision(
					AmbientRadioRetentionDecision.GrantLiveAmbient(
						opaquePolicyId = "policy-1",
						expectedCollectedDataEpoch = 4L,
						expectedSourcePolicyRevision = snapshot.revision,
						expectedAmbientConsentEpoch = requireNotNull(
							snapshot[TrackingSourceComponent.WIFI].ambientConsentEpoch,
						),
						effectiveBootId = "boot-1",
						effectiveElapsedRealtimeNanos = 0L,
						effectiveWallTimeMs = 0L,
					),
				),
			).reason,
		)
	}

	@Test
	fun `radio grants preserve unchanged consent across policy revision and reboot`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 4L, updatedAtMs = 1L),
		)
		var bootId = "boot-1"
		var elapsed = 10L
		var wall = 10L
		val policies = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime(bootId, elapsed++, wall++)
		}
		val settings = TrackingParamsState(
			ambientWifiEnabled = true,
			ambientCellEnabled = true,
			legacySettingsMigrationCompleted = true,
		)
		val original = policies.bootstrapFromLegacy(settings)
		val wifiConsent = requireNotNull(
			original[TrackingSourceComponent.WIFI].ambientConsentEpoch,
		)
		val cellConsent = requireNotNull(
			original[TrackingSourceComponent.CELL].ambientConsentEpoch,
		)
		val wifiConsentRow = requireNotNull(
			database.sourcePolicyDao().consentEpoch(
				com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity.SOURCE_WIFI,
				com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose.AMBIENT_PRODUCT,
				wifiConsent,
			),
		)
		val cellConsentRow = requireNotNull(
			database.sourcePolicyDao().consentEpoch(
				com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity.SOURCE_CELL,
				com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose.AMBIENT_PRODUCT,
				cellConsent,
			),
		)
		bootId = "boot-2"
		elapsed = 1L
		wall = 100L
		val revised = policies.replaceCaptureSettings(
			original.revision,
			settings.copy(minTimeSeconds = settings.minTimeSeconds + 1),
			reason = "TEST_UNRELATED_POLICY_CHANGE",
		)

		assertEquals(wifiConsent, revised[TrackingSourceComponent.WIFI].ambientConsentEpoch)
		assertEquals(cellConsent, revised[TrackingSourceComponent.CELL].ambientConsentEpoch)
		assertEquals(original.revision, wifiConsentRow.policyRevision)
		assertEquals(original.revision, cellConsentRow.policyRevision)
		assertEquals("boot-1", wifiConsentRow.effectiveBootId)
		assertEquals("boot-1", cellConsentRow.effectiveBootId)
		assertIs<AmbientRadioRetentionAuthorityResult.Applied>(
			database.applyAmbientWifiRetentionDecision(
				AmbientRadioRetentionDecision.GrantLiveAmbient(
					"wifi-policy",
					4L,
					revised.revision,
					wifiConsent,
					"boot-2",
					2L,
					110L,
				),
			),
		)
		assertIs<AmbientRadioRetentionAuthorityResult.Applied>(
			database.applyAmbientCellRetentionDecision(
				AmbientRadioRetentionDecision.GrantLiveAmbient(
					"cell-policy",
					4L,
					revised.revision,
					cellConsent,
					"boot-2",
					3L,
					111L,
				),
			),
		)
	}

	@Test
	fun `corrupt imported portable value leaves retention transaction unchanged`() = runTest {
		installWifiImportRetention()
		val fact = importedWifiFact()
		database.ambientWifiFactDao().insertImportedFact(fact)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_ambient_wifi_fact SET strongest_signal_dbm = -49 WHERE fact_id = ?",
			arrayOf(fact.factId),
		)

		val result = assertIs<AmbientWifiRetentionResult.Unavailable>(
			database.pruneAmbientWifi(
				AmbientWifiRetentionCommand(1_500L, 4L, 2_000L),
			),
		)

		assertEquals(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
			result.reason,
		)
		assertEquals(1L, database.ambientWifiFactDao().importedFactCount())
		assertNull(database.ambientWifiFactDao().replayFootprint(
			AmbientWifiReplayFootprintEntity.KIND_FACT_IDENTITY,
			fact.factId,
			1L,
		))
	}

	@Test
	fun `corrupt imported effect checksum leaves retention transaction unchanged`() = runTest {
		installWifiImportRetention()
		val fact = importedWifiFact()
		database.ambientWifiFactDao().insertImportedFact(fact)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_ambient_wifi_fact SET portable_effect_checksum = ? " +
				"WHERE fact_id = ?",
			arrayOf("0".repeat(64), fact.factId),
		)

		assertIs<AmbientWifiRetentionResult.Unavailable>(
			database.pruneAmbientWifi(
				AmbientWifiRetentionCommand(1_500L, 4L, 2_000L),
			),
		)
		assertEquals(1L, database.ambientWifiFactDao().importedFactCount())
	}

	@Test
	fun `corrupt imported content checksum leaves retention transaction unchanged`() = runTest {
		installWifiImportRetention()
		val fact = importedWifiFact()
		database.ambientWifiFactDao().insertImportedFact(fact)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_ambient_wifi_fact SET content_checksum = ? WHERE fact_id = ?",
			arrayOf("0".repeat(64), fact.factId),
		)

		assertIs<AmbientWifiRetentionResult.Unavailable>(
			database.pruneAmbientWifi(
				AmbientWifiRetentionCommand(1_500L, 4L, 2_000L),
			),
		)
		assertEquals(1L, database.ambientWifiFactDao().importedFactCount())
	}

	@Test
	fun `corrupt existing replay footprint prevents retention cascade`() = runTest {
		installWifiImportRetention()
		val fact = importedWifiFact()
		database.ambientWifiFactDao().insertImportedFact(fact)
		database.ambientWifiFactDao().insertReplayFootprints(
			listOf(
				AmbientWifiFactIntegrity.createReplayFootprint(
					AmbientWifiReplayFootprintEntity.KIND_FACT_IDENTITY,
					fact.factId,
					1L,
					4L,
					1L,
					2_000L,
				),
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE ambient_wifi_replay_footprint SET effect_checksum = ? " +
				"WHERE footprint_kind = ? AND identity_digest = ? AND semantic_revision = 1",
			arrayOf(
				"0".repeat(64),
				AmbientWifiReplayFootprintEntity.KIND_FACT_IDENTITY,
				fact.factId,
			),
		)

		val result = assertIs<AmbientWifiRetentionResult.Unavailable>(
			database.pruneAmbientWifi(
				AmbientWifiRetentionCommand(1_500L, 4L, 2_000L),
			),
		)

		assertEquals(
			AmbientWifiMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
			result.reason,
		)
		assertEquals(1L, database.ambientWifiFactDao().importedFactCount())
	}

	private suspend fun installWifiImportRetention() {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(
				collectedDataEpoch = 4L,
				retainedFromMs = 1_500L,
				updatedAtMs = 1L,
			),
		)
		database.ambientWifiFactDao().insertRetentionAuthority(
			AmbientWifiRetentionAuthorityIntegrity.create(
				AmbientWifiRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
				1L,
				AmbientWifiRetentionAuthorityEntity.STATE_ACTIVE,
				"privacy:wifi:import:v1",
				null,
				null,
				4L,
				"boot-1",
				1L,
				1L,
			),
		)
	}

	private fun importedWifiFact(): ImportedAmbientWifiFactEntity {
		val draft = ImportedAmbientWifiFactEntity(
			archiveId = digest("archive"),
			factId = digest("fact"),
			semanticRevision = 1L,
			supersedesSemanticRevision = null,
			contentChecksum = "0".repeat(64),
			portableEffectChecksum = "0".repeat(64),
			portableOrigin = "PORTABLE_IMPORT",
			coverageStartTimeMs = 900L,
			observedTimeMs = 1_000L,
			latestPossibleTimeMs = 1_001L,
			storedZoneId = "UTC",
			structuralEpochDay = 0L,
			coverageCompleteness = "UNVERIFIABLE",
			observationCount = 1,
			twoPointFourGhzCount = 1,
			fiveGhzCount = 0,
			sixGhzCount = 0,
			otherBandCount = 0,
			strongestSignalDbm = -50,
			weakestSignalDbm = -50,
			meanSignalDbm = -50.0,
			retentionPolicyId = "privacy:wifi:import:v1",
			retentionApprovalRevision = 1L,
			collectedDataEpoch = 4L,
			importDeletionGeneration = 0L,
			receivedAtMs = 2_000L,
		)
		val withEffect = draft.copy(
			portableEffectChecksum = AmbientWifiFactIntegrity.importedFactEffectChecksum(draft),
		)
		return withEffect.copy(
			contentChecksum = AmbientWifiFactIntegrity.importedFactContentChecksum(withEffect),
		)
	}

	private fun wifiAuthority() = AmbientWifiAuthorityIntegrity.create(
		authorityRevision = 1L,
		state = AmbientWifiAuthorityEntity.STATE_ACTIVE,
		sourcePolicyRevision = 3L,
		ambientConsentEpoch = 2L,
		retentionPolicyId = "privacy:wifi:ambient:v1",
		retentionApprovalRevision = 1L,
		collectedDataEpoch = 4L,
		scopeDeletionGeneration = 0L,
		effectiveBootId = "boot-1",
		effectiveElapsedRealtimeNanos = 10L,
		effectiveWallTimeMs = 10L,
		rolloutRevision = 1L,
		ownerCasToken = "wifi-owner",
		reconciliationAttempt = 1L,
		demandId = "wifi-demand",
	)

	private fun cellAuthority() = AmbientCellAuthorityIntegrity.create(
		authorityRevision = 1L,
		state = AmbientCellAuthorityEntity.STATE_ACTIVE,
		sourcePolicyRevision = 3L,
		ambientConsentEpoch = 2L,
		retentionPolicyId = "privacy:cell:ambient:v1",
		retentionApprovalRevision = 1L,
		collectedDataEpoch = 4L,
		scopeDeletionGeneration = 0L,
		effectiveBootId = "boot-1",
		effectiveElapsedRealtimeNanos = 10L,
		effectiveWallTimeMs = 10L,
		rolloutRevision = 1L,
		ownerCasToken = "cell-owner",
		reconciliationAttempt = 1L,
		demandId = "cell-demand",
	)

	private fun digest(value: String) = AmbientWifiAuthorityIntegrity.digest("test", value)
}
