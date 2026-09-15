package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.installCanonicalProductLanesForTest
import com.adsamcik.tracker.tracker.source.model.SourceKind
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room-backed broker/authority contract. Execution remains deferred to final convergence. */
@RunWith(RobolectricTestRunner::class)
class AmbientRadioSourceBrokerTest {
	private lateinit var database: AppDatabase
	private lateinit var policyRepository: RoomSourcePolicyRepository
	private lateinit var broker: SourceBroker
	private var elapsed = 10L

	@BeforeTest
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 3L, updatedAtMs = 1L),
		)
		val rollout = installCanonicalProductLanesForTest(
			database,
			listOf(
				ExecutableSourceLaneBinding(
					SourceKind.WIFI,
					1L,
					"ambient-wifi-test",
					1,
					setOf(CaptureReachabilityMode.AMBIENT),
				),
				ExecutableSourceLaneBinding(
					SourceKind.CELL,
					1L,
					"ambient-cell-test",
					1,
					setOf(CaptureReachabilityMode.AMBIENT),
				),
			),
			1L,
		)
		policyRepository = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		policyRepository.bootstrapFromLegacy(
			TrackingParamsState(legacySettingsMigrationCompleted = true),
		)
		broker = SourceBroker(database, rollout)
	}

	@AfterTest
	fun tearDown() {
		database.close()
	}

	@Test
	fun `Wi-Fi ambient grant binds demand retention and source authority atomically`() = runTest {
		val policy = grant(TrackingSourceComponent.WIFI)
		val approval = AmbientRadioRetentionApproval(
			SourceKind.WIFI,
			policy.revision,
			policy[TrackingSourceComponent.WIFI].ambientConsentEpoch!!,
			"privacy:wifi:ambient:v1",
			1L,
		)

		val result = assertIs<AmbientRadioDemandResult.Active>(
			broker.replaceAmbientWifiDemand(
				"app:ambient:wifi",
				true,
				approval,
				"boot-1",
				100L,
				100L,
			),
		)

		val demand = database.sourceBrokerDao().currentDemands("app:ambient:wifi").single()
		assertEquals(SourceBrokerPurpose.AMBIENT_PRODUCT, demand.purpose)
		assertEquals(result.demand, demand)
		val authority = database.ambientWifiFactDao().latestAuthority()
		assertEquals(AmbientWifiAuthorityEntity.STATE_ACTIVE, authority?.state)
		assertEquals(approval.opaquePolicyId, authority?.retentionPolicyId)
		assertEquals(3L, authority?.collectedDataEpoch)
	}

	@Test
	fun `Cell revoke retires only ambient demand and appends durable revoked authority`() = runTest {
		val policy = grant(TrackingSourceComponent.CELL)
		val approval = AmbientRadioRetentionApproval(
			SourceKind.CELL,
			policy.revision,
			policy[TrackingSourceComponent.CELL].ambientConsentEpoch!!,
			"privacy:cell:ambient:v1",
			1L,
		)
		broker.replaceAmbientCellDemand(
			"app:ambient:cell",
			true,
			approval,
			"boot-1",
			100L,
			100L,
		)

		assertIs<AmbientRadioDemandResult.Inactive>(
			broker.replaceAmbientCellDemand(
				"app:ambient:cell",
				false,
				null,
				"boot-1",
				200L,
				200L,
			),
		)

		assertTrue(database.sourceBrokerDao().currentDemands("app:ambient:cell").isEmpty())
		assertEquals(
			AmbientCellAuthorityEntity.STATE_REVOKED,
			database.ambientCellFactDao().latestAuthority()?.state,
		)
	}

	@Test
	fun `missing retention approval creates no ambient demand`() = runTest {
		grant(TrackingSourceComponent.WIFI)

		val result = assertIs<AmbientRadioDemandResult.Inactive>(
			broker.replaceAmbientWifiDemand(
				"app:ambient:wifi",
				true,
				null,
				"boot-1",
				100L,
				100L,
			),
		)

		assertEquals(AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISSING, result.reason)
		assertTrue(database.sourceBrokerDao().currentDemands("app:ambient:wifi").isEmpty())
	}

	private suspend fun grant(source: TrackingSourceComponent) =
		policyRepository.setNonCaptureConsent(
			expectedPolicyRevision =
				(policyRepository.currentState() as SourcePolicyAuthorityState.Active).snapshot.revision,
			source = source,
			purpose = SourcePurpose.AMBIENT_PRODUCT,
			eligible = true,
			persistenceEligible = true,
			reason = "TEST_AMBIENT_RADIO_GRANT",
		)
}
