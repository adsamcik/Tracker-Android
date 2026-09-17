package com.adsamcik.tracker.shared.preferences.retention

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import io.kotest.matchers.shouldBe
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RetentionAuthorityProducerRoomTest {
	private lateinit var database: AppDatabase
	private lateinit var policies: RoomSourcePolicyRepository
	private var lifecycle = CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = null)
	private var approvedPolicy: ApprovedRetentionPolicyRead =
		ApprovedRetentionPolicyRead.Unavailable(
			ApprovedRetentionPolicyUnavailableReason.NOT_APPROVED,
		)
	private var effectiveTime = 0L

	@BeforeTest
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = lifecycle.epoch, updatedAtMs = 1L),
		)
		policies = RoomSourcePolicyRepository(database) {
			nextTime()
		}
	}

	@AfterTest
	fun tearDown() {
		database.close()
	}

	@Test
	fun `default reject and unresolved policy write no grant`() = runTest {
		bootstrap(ambientWifi = true)

		val result = producer().reconcileLiveAmbient(TrackingSourceComponent.WIFI)

		assertIs<RetentionAuthorityResult.Unavailable>(result).reason shouldBe
			RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE
		database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		) shouldBe null
	}

	@Test
	fun `portable import is separate and requires no local provider consent`() = runTest {
		bootstrap()
		approvedPolicy = approved("policy-1", revision = 1L)

		val imported = producer().approvePortableImport(TrackingSourceComponent.WIFI)
		val live = producer().reconcileLiveAmbient(TrackingSourceComponent.WIFI)

		assertIs<RetentionAuthorityResult.Applied>(imported).state shouldBe
			RetentionAuthorityState.ACTIVE
		assertIs<RetentionAuthorityResult.Unavailable>(live).reason shouldBe
			RetentionAuthorityUnavailableReason.PURPOSE_AUTHORITY_UNAVAILABLE
		val storedImport = requireNotNull(
			database.ambientWifiFactDao().latestRetentionAuthority(
				AmbientWifiRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
			),
		)
		storedImport.sourcePolicyRevision shouldBe null
		storedImport.ambientConsentEpoch shouldBe null
		storedImport.isActive shouldBe true
		database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		) shouldBe null
	}

	@Test
	fun `policy consent and collected data changes rotate live approval`() = runTest {
		bootstrap(ambientWifi = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)

		approvedPolicy = approved("policy-2", revision = 2L)
		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)
		val afterPolicy = requireNotNull(
			database.ambientWifiFactDao().latestRetentionAuthority(
				AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		)
		afterPolicy.approvalRevision shouldBe 2L
		afterPolicy.opaquePolicyId shouldBe "policy-2"

		val beforeConsent =
			(policies.currentState() as SourcePolicyAuthorityState.Active).snapshot
		val disabled = policies.setNonCaptureConsent(
			beforeConsent.revision,
			TrackingSourceComponent.WIFI,
			SourcePurpose.AMBIENT_PRODUCT,
			eligible = false,
			persistenceEligible = false,
			reason = "TEST_REVOKE",
		)
		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)
		database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		)?.state shouldBe AmbientWifiRetentionAuthorityEntity.STATE_REVOKED
		val enabled = policies.setNonCaptureConsent(
			disabled.revision,
			TrackingSourceComponent.WIFI,
			SourcePurpose.AMBIENT_PRODUCT,
			eligible = true,
			persistenceEligible = true,
			reason = "TEST_REGRANT",
		)
		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)
		val afterConsent = requireNotNull(
			database.ambientWifiFactDao().latestRetentionAuthority(
				AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		)
		afterConsent.sourcePolicyRevision shouldBe enabled.revision
		afterConsent.ambientConsentEpoch shouldBe
			enabled[TrackingSourceComponent.WIFI].ambientConsentEpoch

		lifecycle = CollectedDataLifecycleSnapshot(epoch = 5L, retainedFromMs = null)
		database.sourceEvidenceStateDao().updateLifecycle(5L, null, 100L)
		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)
		database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		)?.collectedDataEpoch shouldBe 5L
	}

	@Test
	fun `source revoke leaves unrelated source approval unchanged`() = runTest {
		bootstrap(ambientWifi = true, ambientCell = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)
		producer.reconcileLiveAmbient(TrackingSourceComponent.CELL)
		val originalCell = requireNotNull(
			database.ambientCellFactDao().latestRetentionAuthority(
				AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		)
		val snapshot = (policies.currentState() as SourcePolicyAuthorityState.Active).snapshot
		policies.setNonCaptureConsent(
			snapshot.revision,
			TrackingSourceComponent.WIFI,
			SourcePurpose.AMBIENT_PRODUCT,
			eligible = false,
			persistenceEligible = false,
			reason = "TEST_WIFI_REVOKE",
		)

		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)

		database.ambientCellFactDao().latestRetentionAuthority(
			AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		) shouldBe originalCell
	}

	@Test
	fun `revoke and live import scopes remain isolated`() = runTest {
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		producer.reconcileLiveAmbient(TrackingSourceComponent.STEPS)
		producer.approvePortableImport(TrackingSourceComponent.STEPS)
		val snapshot = (policies.currentState() as SourcePolicyAuthorityState.Active).snapshot
		policies.setNonCaptureConsent(
			snapshot.revision,
			TrackingSourceComponent.STEPS,
			SourcePurpose.AMBIENT_PRODUCT,
			eligible = false,
			persistenceEligible = false,
			reason = "TEST_REVOKE",
		)

		producer.reconcileLiveAmbient(TrackingSourceComponent.STEPS)

		database.ambientStepsFactRevisionDao().latestRetentionAuthority(
			AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		)?.state shouldBe AmbientStepsRetentionAuthorityEntity.STATE_REVOKED
		database.ambientStepsFactRevisionDao().latestRetentionAuthority(
			AmbientStepsRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
		)?.state shouldBe AmbientStepsRetentionAuthorityEntity.STATE_ACTIVE
	}

	@Test
	fun `concurrent producers preserve revision CAS`() = runTest {
		bootstrap()
		approvedPolicy = approved("policy-1", revision = 1L)
		val arrivals = Channel<Unit>(capacity = 2)
		val release = CompletableDeferred<Unit>()
		val hook: suspend (TrackingSourceComponent, RetentionAuthorityScope) -> Unit = { _, _ ->
			arrivals.send(Unit)
			release.await()
		}
		val first = producer(hook)
		val second = producer(hook)
		val firstResult = async {
			first.approvePortableImport(TrackingSourceComponent.STEPS)
		}
		val secondResult = async {
			second.approvePortableImport(TrackingSourceComponent.STEPS)
		}
		arrivals.receive()
		arrivals.receive()
		release.complete(Unit)

		val results = listOf(firstResult.await(), secondResult.await())
		results.count { it is RetentionAuthorityResult.Applied } shouldBe 1
		results.count {
			it is RetentionAuthorityResult.Unavailable &&
				it.reason == RetentionAuthorityUnavailableReason.STALE_APPROVAL_REVISION
		} shouldBe 1
	}

	@Test
	fun `location seam remains unavailable and writes no other source`() = runTest {
		bootstrap(ambientLocation = true)
		approvedPolicy = approved("policy-1", revision = 1L)

		val result = producer().reconcilePassiveLocationRetention()

		assertIs<RetentionAuthorityResult.Unavailable>(result).reason shouldBe
			RetentionAuthorityUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE
		database.ambientStepsFactRevisionDao().latestRetentionAuthority(
			AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		) shouldBe null
		database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		) shouldBe null
		database.ambientCellFactDao().latestRetentionAuthority(
			AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		) shouldBe null
	}

	private suspend fun bootstrap(
		ambientSteps: Boolean = false,
		ambientLocation: Boolean = false,
		ambientWifi: Boolean = false,
		ambientCell: Boolean = false,
	) {
		policies.bootstrapFromLegacy(
			TrackingParamsState(
				ambientStepsEnabled = ambientSteps,
				ambientLocationEnabled = ambientLocation,
				ambientWifiEnabled = ambientWifi,
				ambientCellEnabled = ambientCell,
				legacySettingsMigrationCompleted = true,
			),
		)
	}

	private fun producer(
		beforeDecisionApply: suspend (
			TrackingSourceComponent,
			RetentionAuthorityScope,
		) -> Unit = { _, _ -> },
	) = DefaultRetentionAuthorityProducer(
		database = database,
		sourcePolicyRepository = policies,
		readApprovedPolicy = { approvedPolicy },
		readLifecycle = { lifecycle },
		effectiveTimeProvider = { nextTime() },
		beforeDecisionApply = beforeDecisionApply,
	)

	private fun nextTime(): SourcePolicyEffectiveTime {
		effectiveTime += 1L
		return SourcePolicyEffectiveTime(
			bootId = "boot-1",
			elapsedRealtimeNanos = effectiveTime,
			wallTimeMs = effectiveTime,
		)
	}

	private fun approved(
		opaquePolicyId: String,
		revision: Long,
	): ApprovedRetentionPolicyRead {
		val configurationChecksum =
			RetentionPolicyApprovalIntegrity.configurationChecksum(RetentionConfigState())
		return ApprovedRetentionPolicyRead.Available(
			ApprovedRetentionPolicy(
				revision = revision,
				opaquePolicyId = opaquePolicyId,
				configurationChecksum = configurationChecksum,
				integrityChecksum = RetentionPolicyApprovalIntegrity.checksum(
					revision,
					opaquePolicyId,
					configurationChecksum,
				),
			),
		)
	}
}
