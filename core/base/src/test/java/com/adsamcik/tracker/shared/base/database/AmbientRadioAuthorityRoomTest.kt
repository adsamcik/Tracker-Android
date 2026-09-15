package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityIntegrity
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
}
