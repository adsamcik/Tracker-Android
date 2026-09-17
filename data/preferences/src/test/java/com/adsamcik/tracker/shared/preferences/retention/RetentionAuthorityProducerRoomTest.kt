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
	private var currentBoot = "boot-1"

	@BeforeTest
	fun setUp() = runTest {
		effectiveTime = 0L
		currentBoot = "boot-1"
		lifecycle = CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = null)
		approvedPolicy = ApprovedRetentionPolicyRead.Unavailable(
			ApprovedRetentionPolicyUnavailableReason.NOT_APPROVED,
		)
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
		assertIs<RetentionAuthorityResult.Unchanged>(live).state shouldBe
			RetentionAuthorityState.REVOKED
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
	fun `cold reconciliation preserves a current approved portable grant`() = runTest {
		bootstrap()
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		assertIs<RetentionAuthorityResult.Applied>(
			producer.approvePortableImport(TrackingSourceComponent.STEPS),
		)
		val before = requireNotNull(
			database.ambientStepsFactRevisionDao().latestRetentionAuthority(
				AmbientStepsRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
			),
		)

		producer.reconcileCurrentSettings()

		database.ambientStepsFactRevisionDao().latestRetentionAuthority(
			AmbientStepsRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
		) shouldBe before
	}

	@Test
	fun `cold reconciliation reports disabled durable ambient sources as revoked`() = runTest {
		bootstrap()

		val results = producer().reconcileCurrentSettings().filter {
			it.scope == RetentionAuthorityScope.LIVE_AMBIENT &&
				it.source in setOf(
					TrackingSourceComponent.STEPS,
					TrackingSourceComponent.WIFI,
					TrackingSourceComponent.CELL,
				)
		}

		results.size shouldBe 3
		results.all {
			it is RetentionAuthorityResult.Unchanged &&
				it.state == RetentionAuthorityState.REVOKED
		} shouldBe true
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
	fun `pending configuration stays fail closed until exact Room grants are approved`() = runTest {
		bootstrap(ambientWifi = true)
		val pendingPolicy = approvedPolicy("pending-policy", revision = 1L)
		var markedApproved = false
		val producer = producer(
			readPolicyCandidate = {
				RetentionPolicyCandidateRead.Available(
					pendingPolicy,
					RetentionPolicyApprovalStatus.PENDING,
				)
			},
			markPolicyApproved = {
				markedApproved = it == pendingPolicy
				it
			},
		)

		producer.currentLiveAmbient(
			TrackingSourceComponent.WIFI,
			expectedSourcePolicyRevision =
				(policies.currentState() as SourcePolicyAuthorityState.Active).snapshot.revision,
			expectedAmbientConsentEpoch = 1L,
			expectedCollectedDataEpoch = lifecycle.epoch,
		) shouldBe CurrentRetentionAuthority.Unavailable(
			RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
		)
		producer.preparePendingConfiguration(1L) shouldBe
			RetentionConfigurationApprovalResult.Prepared(pendingPolicy)
		producer.reconcilePendingConfiguration(1L) shouldBe
			RetentionConfigurationApprovalResult.Approved(pendingPolicy)
		markedApproved shouldBe true
		database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		)?.opaquePolicyId shouldBe "pending-policy"
	}

	@Test
	fun `pending configuration revokes prior live grant before publication`() = runTest {
		bootstrap(ambientWifi = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		producer().reconcileLiveAmbient(TrackingSourceComponent.WIFI)
		val pendingPolicy = approvedPolicy("policy-2", revision = 2L)
		approvedPolicy = ApprovedRetentionPolicyRead.Unavailable(
			ApprovedRetentionPolicyUnavailableReason.PENDING_APPROVAL,
		)
		val pendingProducer = producer(
			readPolicyCandidate = {
				RetentionPolicyCandidateRead.Available(
					pendingPolicy,
					RetentionPolicyApprovalStatus.PENDING,
				)
			},
		)

		pendingProducer.preparePendingConfiguration(2L) shouldBe
			RetentionConfigurationApprovalResult.Prepared(pendingPolicy)
		database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		)?.state shouldBe AmbientWifiRetentionAuthorityEntity.STATE_REVOKED
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

	@Test
	fun `prior boot grant is rejected and reconciled onto current clock domain`() = runTest {
		bootstrap(ambientWifi = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		val snapshot = (policies.currentState() as SourcePolicyAuthorityState.Active).snapshot
		val consentEpoch = requireNotNull(
			snapshot[TrackingSourceComponent.WIFI].ambientConsentEpoch,
		)
		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)
		currentBoot = "boot-2"

		producer.currentLiveAmbient(
			TrackingSourceComponent.WIFI,
			snapshot.revision,
			consentEpoch,
			lifecycle.epoch,
		) shouldBe CurrentRetentionAuthority.Unavailable(
			RetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
		)
		assertIs<RetentionAuthorityResult.Applied>(
			producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI),
		)
		database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		)?.effectiveBootId shouldBe "boot-2"
	}

	@Test
	fun `reboot reissue preserves consent created under an older policy revision`() = runTest {
		val settings = TrackingParamsState(
			ambientWifiEnabled = true,
			legacySettingsMigrationCompleted = true,
		)
		val original = policies.bootstrapFromLegacy(settings)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)
		val consentEpoch = requireNotNull(
			original[TrackingSourceComponent.WIFI].ambientConsentEpoch,
		)
		currentBoot = "boot-2"
		val revised = policies.replaceCaptureSettings(
			original.revision,
			settings.copy(minTimeSeconds = settings.minTimeSeconds + 1),
			reason = "TEST_UNRELATED_POLICY_CHANGE",
		)

		assertIs<RetentionAuthorityResult.Applied>(
			producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI),
		)
		requireNotNull(
			database.ambientWifiFactDao().latestRetentionAuthority(
				AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		).run {
			sourcePolicyRevision shouldBe revised.revision
			ambientConsentEpoch shouldBe consentEpoch
			effectiveBootId shouldBe "boot-2"
		}
	}

	@Test
	fun `reader predicate binds current boot and exact retention identity`() = runTest {
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		val snapshot = (policies.currentState() as SourcePolicyAuthorityState.Active).snapshot
		val consentEpoch = requireNotNull(
			snapshot[TrackingSourceComponent.STEPS].ambientConsentEpoch,
		)
		assertIs<RetentionAuthorityResult.Applied>(
			producer.reconcileLiveAmbient(TrackingSourceComponent.STEPS),
		)
		val stored = requireNotNull(
			database.ambientStepsFactRevisionDao().latestRetentionAuthority(
				AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		)

		producer.isCurrentLiveAmbientAt(
			source = TrackingSourceComponent.STEPS,
			expectedSourcePolicyRevision = snapshot.revision,
			expectedAmbientConsentEpoch = consentEpoch,
			expectedCollectedDataEpoch = lifecycle.epoch,
			expectedOpaquePolicyId = stored.opaquePolicyId,
			expectedApprovalRevision = stored.approvalRevision,
			currentBootId = currentBoot,
			currentElapsedRealtimeNanos = effectiveTime + 10L,
			currentWallTimeMs = effectiveTime + 10L,
		) shouldBe true
		producer.isCurrentLiveAmbientAt(
			source = TrackingSourceComponent.STEPS,
			expectedSourcePolicyRevision = snapshot.revision,
			expectedAmbientConsentEpoch = consentEpoch,
			expectedCollectedDataEpoch = lifecycle.epoch,
			expectedOpaquePolicyId = stored.opaquePolicyId,
			expectedApprovalRevision = stored.approvalRevision + 1L,
			currentBootId = currentBoot,
			currentElapsedRealtimeNanos = effectiveTime + 10L,
			currentWallTimeMs = effectiveTime + 10L,
		) shouldBe false
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
		readPolicyCandidate: suspend () -> RetentionPolicyCandidateRead = {
			when (val current = approvedPolicy) {
				is ApprovedRetentionPolicyRead.Available ->
					RetentionPolicyCandidateRead.Available(
						current.policy,
						RetentionPolicyApprovalStatus.APPROVED,
					)
				is ApprovedRetentionPolicyRead.Unavailable ->
					RetentionPolicyCandidateRead.Unavailable(current.reason)
			}
		},
		markPolicyApproved: suspend (ApprovedRetentionPolicy) -> ApprovedRetentionPolicy? = { it },
	) = DefaultRetentionAuthorityProducer(
		database = database,
		sourcePolicyRepository = policies,
		readApprovedPolicy = { approvedPolicy },
		readLifecycle = { lifecycle },
		effectiveTimeProvider = { nextTime() },
		beforeDecisionApply = beforeDecisionApply,
		readPolicyCandidate = readPolicyCandidate,
		markPolicyApproved = markPolicyApproved,
	)

	private fun nextTime(): SourcePolicyEffectiveTime {
		effectiveTime += 1L
		return SourcePolicyEffectiveTime(
			bootId = currentBoot,
			elapsedRealtimeNanos = effectiveTime,
			wallTimeMs = effectiveTime,
		)
	}

	private fun approved(
		opaquePolicyId: String,
		revision: Long,
	): ApprovedRetentionPolicyRead =
		ApprovedRetentionPolicyRead.Available(approvedPolicy(opaquePolicyId, revision))

	private fun approvedPolicy(
		opaquePolicyId: String,
		revision: Long,
	): ApprovedRetentionPolicy {
		val configurationChecksum =
			RetentionPolicyApprovalIntegrity.configurationChecksum(RetentionConfigState())
		return ApprovedRetentionPolicy(
				configurationGeneration = revision,
				revision = revision,
				opaquePolicyId = opaquePolicyId,
				configurationChecksum = configurationChecksum,
				integrityChecksum = RetentionPolicyApprovalIntegrity.checksum(
					RetentionPolicyApprovalStatus.APPROVED,
					revision,
					revision,
					opaquePolicyId,
					configurationChecksum,
				),
		)
	}
}
