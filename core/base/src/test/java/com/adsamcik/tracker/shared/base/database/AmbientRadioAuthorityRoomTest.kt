package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
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
	fun `retention requires exact opaque approval and durable global floor`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(
				collectedDataEpoch = 4L,
				retainedFromMs = 1_000L,
				updatedAtMs = 1_000L,
			),
		)
		database.ambientWifiFactDao().insertAuthority(wifiAuthority())

		val mismatch = database.pruneAmbientWifi(
			AmbientWifiRetentionCommand(
				retentionPolicyId = "privacy:wifi:ambient:other",
				retentionApprovalRevision = 1L,
				beforeMs = 1_000L,
				expectedCollectedDataEpoch = 4L,
				expectedAmbientConsentEpoch = 2L,
				appliedAtMs = 2_000L,
			),
		)
		assertEquals(
			AmbientWifiMaintenanceUnavailableReason.RETENTION_APPROVAL_MISMATCH,
			assertIs<AmbientWifiRetentionResult.Unavailable>(mismatch).reason,
		)

		assertEquals(
			AmbientWifiRetentionResult.NoChange,
			database.pruneAmbientWifi(
				AmbientWifiRetentionCommand(
					retentionPolicyId = "privacy:wifi:ambient:v1",
					retentionApprovalRevision = 1L,
					beforeMs = 1_000L,
					expectedCollectedDataEpoch = 4L,
					expectedAmbientConsentEpoch = 2L,
					appliedAtMs = 2_000L,
				),
			),
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
	)
}
