package com.adsamcik.tracker.tracker.source.wifi

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCaptureDeletionGenerationEntity
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.WifiCaptureDeletionBarrierBlockedReason
import com.adsamcik.tracker.tracker.source.runtime.WifiCaptureDeletionBarrierResult
import com.adsamcik.tracker.tracker.source.runtime.WifiCaptureDeletionBarrierRetryableReason
import com.adsamcik.tracker.tracker.source.runtime.WifiSourceRuntime
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiCaptureConsentRevocationDeletionCommandTest {
	private lateinit var database: AppDatabase
	private lateinit var runtime: WifiSourceRuntime
	private lateinit var maintenance: WifiCapturedFactMaintenance
	private lateinit var subject: WifiCaptureConsentRevocationDeletionCommand

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		runtime = mockk()
		maintenance = WifiCapturedFactMaintenance(
			database,
			mockk<SourcePayloadCodec>(relaxed = true),
			mockk<SourcePlanCodec>(relaxed = true),
		)
		subject = WifiCaptureConsentRevocationDeletionCommand(database, runtime, maintenance)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `revoked exact consent invokes barrier then idempotent authenticated deletion`() = runTest {
		installPolicy(revoked = true)
		database.sourcePolicyDao().authority()?.currentPolicyRevision shouldBe CURRENT_POLICY_REVISION
		database.sourcePolicyDao().latestConsentEpoch(
			WIFI_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
		)?.policyRevision shouldBe REVOKED_POLICY_REVISION
		coEvery { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) } returns
			WifiCaptureDeletionBarrierResult.NoLocalProvider

		subject.delete(request()) shouldBe
			WifiCaptureConsentRevocationDeletionResult.AlreadyDeleted
		subject.delete(request(deletedAtMs = DELETED_AT_MS + 1L)) shouldBe
			WifiCaptureConsentRevocationDeletionResult.AlreadyDeleted

		coVerify(exactly = 2) { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) }
		database.sourceBrokerDao().maximumRegistrationGeneration(WIFI_SOURCE) shouldBe 0L
		database.wifiCapturedFactDao().maintenanceWalCount(WIFI_SOURCE) shouldBe 0L
	}

	@Test
	fun `eligible consent is blocked before touching Wi-Fi runtime`() = runTest {
		installPolicy(revoked = false)

		subject.delete(request(
			expectedCurrentPolicyRevision = ELIGIBLE_POLICY_REVISION,
			expectedRevokedConsentEpoch = CAPTURE_CONSENT_EPOCH,
		)) shouldBe
			WifiCaptureConsentRevocationDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE,
			)
		coVerify(exactly = 0) { runtime.establishCaptureDeletionBarrier(any()) }
	}

	@Test
	fun `stale source evidence or consent epoch is blocked before runtime`() = runTest {
		installPolicy(revoked = true)

		subject.delete(request(expectedCollectedDataEpoch = COLLECTED_DATA_EPOCH + 1L)) shouldBe
			WifiCaptureConsentRevocationDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
			)
		subject.delete(request(
			expectedDeletedSourceEventHighWaterOrdinal = DELETED_SOURCE_HIGH_WATER + 1L,
		)) shouldBe WifiCaptureConsentRevocationDeletionResult.Blocked(
			WifiCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
		)
		subject.delete(request(expectedRevokedConsentEpoch = REVOKED_CONSENT_EPOCH + 1L)) shouldBe
			WifiCaptureConsentRevocationDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE,
			)
		subject.delete(request(expectedCurrentPolicyRevision = REVOKED_POLICY_REVISION)) shouldBe
			WifiCaptureConsentRevocationDeletionResult.Blocked(
				WifiCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE,
			)
		coVerify(exactly = 0) { runtime.establishCaptureDeletionBarrier(any()) }
	}

	@Test
	fun `barrier blocked and retryable results map without invoking maintenance`() = runTest {
		installPolicy(revoked = true)
		val mockedMaintenance = mockk<WifiCapturedFactMaintenance>()
		val command = WifiCaptureConsentRevocationDeletionCommand(
			database,
			runtime,
			mockedMaintenance,
		)
		coEvery { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) } returnsMany listOf(
			WifiCaptureDeletionBarrierResult.Blocked(
				WifiCaptureDeletionBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE,
			),
			WifiCaptureDeletionBarrierResult.Retryable(
				WifiCaptureDeletionBarrierRetryableReason.CALLBACK_DRAIN_TIMED_OUT,
			),
			WifiCaptureDeletionBarrierResult.Retryable(
				WifiCaptureDeletionBarrierRetryableReason.BARRIER_PUBLICATION_FAILED,
			),
		)

		command.delete(request()) shouldBe WifiCaptureConsentRevocationDeletionResult.Blocked(
			WifiCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED,
		)
		command.delete(request()) shouldBe WifiCaptureConsentRevocationDeletionResult.Retryable(
			WifiCaptureConsentRevocationDeletionRetryableReason.CALLBACK_BARRIER_UNAVAILABLE,
		)
		command.delete(request()) shouldBe WifiCaptureConsentRevocationDeletionResult.Retryable(
			WifiCaptureConsentRevocationDeletionRetryableReason.STORAGE_UNAVAILABLE,
		)
		coVerify(exactly = 0) {
			mockedMaintenance.deleteAfterCaptureConsentReset(any(), any(), any(), any(), any())
		}
	}

	@Test
	fun `policy race after callback barrier is rejected by low-level transaction`() = runTest {
		installPolicy(revoked = true)
		coEvery { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) } coAnswers {
			database.sourcePolicyDao().insertPolicies(
				listOf(currentPolicy(RACED_POLICY_REVISION, "TEST_NONCAPTURE_RACE")),
			)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_policy_authority SET current_policy_revision = ?, " +
					"updated_at_ms = ? WHERE id = 1",
				arrayOf(RACED_POLICY_REVISION, DELETED_AT_MS),
			)
			WifiCaptureDeletionBarrierResult.Established(1L)
		}

		subject.delete(request()) shouldBe WifiCaptureConsentRevocationDeletionResult.Blocked(
			WifiCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE,
		)
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `source evidence race after callback barrier preserves all Wi-Fi storage`() = runTest {
		installPolicy(revoked = true)
		val captureDemand = sentinelCaptureDemand()
		val ambientDemand = sentinelAmbientDemand()
		database.sourceBrokerDao().insertDemands(listOf(captureDemand, ambientDemand))
		val fence = SourceDeletionFenceEntity.createLogicalServiceRun(
			sourceKind = WIFI_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = SENTINEL_LOGICAL_TRACKING_ID,
			serviceRunId = SENTINEL_SERVICE_RUN_ID,
			fenceGeneration = 1L,
			collectedDataEpoch = COLLECTED_DATA_EPOCH,
			deletedAtMs = ELIGIBLE_AT_MS,
		)
		database.sourceDeletionFenceDao().insertIfAbsent(fence)
		val deletionGeneration = WifiCaptureDeletionGenerationEntity(
			logicalTrackingId = SENTINEL_LOGICAL_TRACKING_ID,
			serviceRunId = SENTINEL_SERVICE_RUN_ID,
			collectedDataEpoch = COLLECTED_DATA_EPOCH,
			generation = 1L,
			updatedAtMs = ELIGIBLE_AT_MS,
		)
		database.wifiCapturedFactDao().insertDeletionGeneration(deletionGeneration)
		val wal = sentinelAmbientWal()
		database.sourceEventWalDao().insertIgnoringDuplicate(wal)
		val demands = database.sourceBrokerDao().demandsByIds(
			listOf(captureDemand.demandId, ambientDemand.demandId),
		).associateBy(SourceDemandEntity::demandId)

		coEvery { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) } coAnswers {
			database.sourceEvidenceStateDao().updateLifecycle(
				epoch = COLLECTED_DATA_EPOCH + 1L,
				retainedFromMs = null,
				updatedAtMs = DELETED_AT_MS,
			) shouldBe 1
			WifiCaptureDeletionBarrierResult.Established(1L)
		}

		subject.delete(request()) shouldBe WifiCaptureConsentRevocationDeletionResult.Blocked(
			WifiCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
		)
		database.sourceDeletionFenceDao().get(
			fence.sourceKind,
			fence.purpose,
			fence.scopeKind,
			fence.scopeIdentityDigest,
		) shouldBe fence
		database.wifiCapturedFactDao().deletionGeneration(
			SENTINEL_LOGICAL_TRACKING_ID,
			SENTINEL_SERVICE_RUN_ID,
		) shouldBe deletionGeneration
		database.sourceBrokerDao().demandsByIds(
			listOf(captureDemand.demandId, ambientDemand.demandId),
		).associateBy(SourceDemandEntity::demandId) shouldBe demands
		database.wifiCapturedFactDao().maintenanceWalCount(WIFI_SOURCE) shouldBe 1L
		requireNotNull(database.sourceEventWalDao().getByEventId(SENTINEL_WAL_EVENT_ID)).let { retained ->
			retained.integrityIdentity shouldBe wal.integrityIdentity
			retained.payload.contentEquals(wal.payload) shouldBe true
		}
	}

	@Test
	fun `storage failure is typed and cancellation propagates without an internal retry`() = runTest {
		installPolicy(revoked = true)
		coEvery { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) } throws
			SQLiteException("unavailable")
		subject.delete(request()) shouldBe WifiCaptureConsentRevocationDeletionResult.Retryable(
			WifiCaptureConsentRevocationDeletionRetryableReason.STORAGE_UNAVAILABLE,
		)
		coVerify(exactly = 1) { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) }

		coEvery { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) } throws
			CancellationException("cancelled")
		shouldThrow<CancellationException> { subject.delete(request()) }
		coVerify(exactly = 2) { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) }

		val mockedMaintenance = mockk<WifiCapturedFactMaintenance>()
		val command = WifiCaptureConsentRevocationDeletionCommand(
			database,
			runtime,
			mockedMaintenance,
		)
		coEvery { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) } returns
			WifiCaptureDeletionBarrierResult.NoLocalProvider
		coEvery {
			mockedMaintenance.deleteAfterCaptureConsentReset(
				COLLECTED_DATA_EPOCH,
				DELETED_SOURCE_HIGH_WATER,
				CURRENT_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH,
				DELETED_AT_MS,
			)
		} throws SQLiteException("maintenance unavailable")
		command.delete(request()) shouldBe WifiCaptureConsentRevocationDeletionResult.Retryable(
			WifiCaptureConsentRevocationDeletionRetryableReason.STORAGE_UNAVAILABLE,
		)
		coVerify(exactly = 1) {
			mockedMaintenance.deleteAfterCaptureConsentReset(
				COLLECTED_DATA_EPOCH,
				DELETED_SOURCE_HIGH_WATER,
				CURRENT_POLICY_REVISION,
				REVOKED_CONSENT_EPOCH,
				DELETED_AT_MS,
			)
		}
	}

	private suspend fun installPolicy(revoked: Boolean) {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
				deletedSourceEventHighWaterOrdinal = DELETED_SOURCE_HIGH_WATER,
				updatedAtMs = EVIDENCE_UPDATED_AT_MS,
			),
		)
		val eligiblePolicy = eligiblePolicy()
		val policies = if (revoked) listOf(
			eligiblePolicy,
			revokedCapturePolicy(),
			currentPolicy(CURRENT_POLICY_REVISION, "TEST_NONCAPTURE_POLICY_CHANGE"),
		) else listOf(eligiblePolicy)
		val consents = buildList {
			add(captureConsent(revoked = false))
			add(controlConsent(INITIAL_CONTROL_CONSENT_EPOCH, ELIGIBLE_POLICY_REVISION))
			add(ambientConsent(INITIAL_AMBIENT_CONSENT_EPOCH, ELIGIBLE_POLICY_REVISION))
			if (revoked) {
				add(captureConsent(revoked = true))
				add(controlConsent(CONTROL_CONSENT_EPOCH, CURRENT_POLICY_REVISION))
				add(ambientConsent(AMBIENT_CONSENT_EPOCH, CURRENT_POLICY_REVISION))
			}
		}
		database.sourcePolicyDao().insertPolicies(policies)
		database.sourcePolicyDao().insertConsentEpochs(consents)
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = if (revoked) CURRENT_POLICY_REVISION else
					ELIGIBLE_POLICY_REVISION,
				updatedAtMs = if (revoked) CURRENT_POLICY_AT_MS else ELIGIBLE_AT_MS,
			),
		)
	}

	private fun eligiblePolicy() = SourcePolicyEntity(
		policyRevision = ELIGIBLE_POLICY_REVISION,
		sourceKind = WIFI_SOURCE,
		enabled = true,
		qosCode = 1,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = true,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = true,
		captureConsentEpoch = CAPTURE_CONSENT_EPOCH,
		controlConsentEpoch = INITIAL_CONTROL_CONSENT_EPOCH,
		ambientConsentEpoch = INITIAL_AMBIENT_CONSENT_EPOCH,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = ELIGIBLE_ELAPSED_NANOS,
		effectiveWallTimeMs = ELIGIBLE_AT_MS,
		changeReason = "TEST_ELIGIBLE_WITH_INDEPENDENT_PURPOSES",
	)

	private fun revokedCapturePolicy() = SourcePolicyEntity(
		policyRevision = REVOKED_POLICY_REVISION,
		sourceKind = WIFI_SOURCE,
		enabled = false,
		qosCode = QOS_OFF,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = false,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = true,
		captureConsentEpoch = null,
		controlConsentEpoch = INITIAL_CONTROL_CONSENT_EPOCH,
		ambientConsentEpoch = INITIAL_AMBIENT_CONSENT_EPOCH,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = REVOKED_ELAPSED_NANOS,
		effectiveWallTimeMs = REVOKED_AT_MS,
		changeReason = "TEST_CAPTURE_REVOKED",
	)

	private fun currentPolicy(revision: Long, reason: String) = SourcePolicyEntity(
		policyRevision = revision,
		sourceKind = WIFI_SOURCE,
		enabled = false,
		qosCode = QOS_OFF,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = false,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = true,
		captureConsentEpoch = null,
		controlConsentEpoch = CONTROL_CONSENT_EPOCH,
		ambientConsentEpoch = AMBIENT_CONSENT_EPOCH,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = if (revision == RACED_POLICY_REVISION) {
			RACED_POLICY_ELAPSED_NANOS
		} else CURRENT_POLICY_ELAPSED_NANOS,
		effectiveWallTimeMs = if (revision == RACED_POLICY_REVISION) {
			RACED_POLICY_AT_MS
		} else CURRENT_POLICY_AT_MS,
		changeReason = reason,
	)

	private fun captureConsent(revoked: Boolean) = SourceConsentEpochEntity(
		sourceKind = WIFI_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		epoch = if (revoked) REVOKED_CONSENT_EPOCH else CAPTURE_CONSENT_EPOCH,
		eligible = !revoked,
		persistenceEligible = !revoked,
		policyRevision = if (revoked) REVOKED_POLICY_REVISION else ELIGIBLE_POLICY_REVISION,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = if (revoked) REVOKED_ELAPSED_NANOS else
			ELIGIBLE_ELAPSED_NANOS,
		effectiveWallTimeMs = if (revoked) REVOKED_AT_MS else ELIGIBLE_AT_MS,
		changeReason = if (revoked) "TEST_REVOKED" else "TEST_ELIGIBLE",
	)

	private fun controlConsent(epoch: Long, policyRevision: Long) = SourceConsentEpochEntity(
		sourceKind = WIFI_SOURCE,
		purpose = "CONTROL",
		epoch = epoch,
		eligible = true,
		persistenceEligible = false,
		policyRevision = policyRevision,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = if (policyRevision == ELIGIBLE_POLICY_REVISION) {
			ELIGIBLE_ELAPSED_NANOS
		} else CURRENT_POLICY_ELAPSED_NANOS,
		effectiveWallTimeMs = if (policyRevision == ELIGIBLE_POLICY_REVISION) {
			ELIGIBLE_AT_MS
		} else CURRENT_POLICY_AT_MS,
		changeReason = "TEST_CONTROL_ELIGIBLE",
	)

	private fun ambientConsent(epoch: Long, policyRevision: Long) = SourceConsentEpochEntity(
		sourceKind = WIFI_SOURCE,
		purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
		epoch = epoch,
		eligible = true,
		persistenceEligible = true,
		policyRevision = policyRevision,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = if (policyRevision == ELIGIBLE_POLICY_REVISION) {
			ELIGIBLE_ELAPSED_NANOS
		} else CURRENT_POLICY_ELAPSED_NANOS,
		effectiveWallTimeMs = if (policyRevision == ELIGIBLE_POLICY_REVISION) {
			ELIGIBLE_AT_MS
		} else CURRENT_POLICY_AT_MS,
		changeReason = "TEST_AMBIENT_ELIGIBLE",
	)

	private fun sentinelCaptureDemand(): SourceDemandEntity {
		val contract = SourceDemandContractFactory.forQos(
			SourceKind.WIFI,
			1,
			DirectSourceDemandPurpose.SESSION_CAPTURE,
		)
		return SourceDemandEntity(
			demandId = "sentinel-wifi-capture-demand",
			consumerId = "session:$SENTINEL_LOGICAL_TRACKING_ID",
			sourceKind = WIFI_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = SENTINEL_LOGICAL_TRACKING_ID,
			serviceRunId = SENTINEL_SERVICE_RUN_ID,
			manifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			sourcePolicyRevision = ELIGIBLE_POLICY_REVISION,
			consentEpoch = CAPTURE_CONSENT_EPOCH,
			persistenceEligible = true,
			qosCode = 1,
			minimumAcquisitionSpec = contract.encodeFloor(),
			adaptiveReductionAllowed = contract.adaptiveReductionAllowed,
			maximumAgeMs = contract.maximumProviderItemAgeMs,
			desiredLatencyMs = contract.targetPlanningLatencyMs,
			requestedDeliveryLatencyMs = contract.requestedDeliveryLatencyMs,
			requestedBootId = BOOT_ID,
			requestedElapsedRealtimeNanos = ELIGIBLE_ELAPSED_NANOS,
			requestedAtMs = ELIGIBLE_AT_MS,
			status = SourceDemandEntity.STATUS_RETIRING,
			retireBootId = BOOT_ID,
			retireElapsedRealtimeNanos = REVOKED_ELAPSED_NANOS,
			retiredAtMs = REVOKED_AT_MS,
		)
	}

	private fun sentinelAmbientDemand(): SourceDemandEntity {
		val contract = SourceDemandContractFactory.forQos(
			SourceKind.WIFI,
			QOS_OFF,
			DirectSourceDemandPurpose.AMBIENT_PRODUCT,
		)
		return SourceDemandEntity(
			demandId = "sentinel-wifi-ambient-demand",
			consumerId = "app:sentinel-wifi-ambient",
			sourceKind = WIFI_SOURCE,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
			logicalTrackingId = null,
			serviceRunId = null,
			manifestRevision = null,
			lifecycleLeaseGeneration = null,
			sourcePolicyRevision = CURRENT_POLICY_REVISION,
			consentEpoch = AMBIENT_CONSENT_EPOCH,
			persistenceEligible = true,
			qosCode = QOS_OFF,
			minimumAcquisitionSpec = contract.encodeFloor(),
			adaptiveReductionAllowed = contract.adaptiveReductionAllowed,
			maximumAgeMs = contract.maximumProviderItemAgeMs,
			desiredLatencyMs = contract.targetPlanningLatencyMs,
			requestedDeliveryLatencyMs = contract.requestedDeliveryLatencyMs,
			requestedBootId = BOOT_ID,
			requestedElapsedRealtimeNanos = CURRENT_POLICY_ELAPSED_NANOS,
			requestedAtMs = CURRENT_POLICY_AT_MS,
			status = SourceDemandEntity.STATUS_ACTIVE,
			retireBootId = null,
			retireElapsedRealtimeNanos = null,
			retiredAtMs = null,
		)
	}

	private fun sentinelAmbientWal(): SourceEventWalEntity {
		val payload = byteArrayOf(1, 2, 3)
		val unsigned = SourceEventWalEntity(
			eventId = SENTINEL_WAL_EVENT_ID,
			providerDedupKey = null,
			logicalTrackingId = null,
			serviceRunId = null,
			sourceKind = WIFI_SOURCE,
			sourceInstanceId = "sentinel-wifi-ambient-instance",
			registrationGeneration = 1L,
			sourceSequence = 1L,
			configRevision = null,
			planAttribution = 0,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = REVOKED_ELAPSED_NANOS,
			receivedElapsedNanos = REVOKED_ELAPSED_NANOS,
			wallTimeMs = REVOKED_AT_MS,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = COLLECTED_DATA_EPOCH,
			acquiredAtMs = REVOKED_AT_MS,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = payload,
			payloadChecksum = "pending",
			createdAtMs = REVOKED_AT_MS,
		)
		val checksummed = unsigned.copy(payloadChecksum = unsigned.calculatedPayloadChecksum())
		return checksummed.copy(integrityIdentity = checksummed.calculatedIntegrityIdentity())
	}

	private fun request(
		expectedCollectedDataEpoch: Long = COLLECTED_DATA_EPOCH,
		expectedDeletedSourceEventHighWaterOrdinal: Long = DELETED_SOURCE_HIGH_WATER,
		expectedCurrentPolicyRevision: Long = CURRENT_POLICY_REVISION,
		expectedRevokedConsentEpoch: Long = REVOKED_CONSENT_EPOCH,
		deletedAtMs: Long = DELETED_AT_MS,
	) = WifiCaptureConsentRevocationDeletionRequest(
		expectedCollectedDataEpoch,
		expectedDeletedSourceEventHighWaterOrdinal,
		expectedCurrentPolicyRevision,
		expectedRevokedConsentEpoch,
		deletedAtMs,
	)

	private companion object {
		const val WIFI_SOURCE = SourceDestinationOwnerEntity.SOURCE_WIFI
		const val COLLECTED_DATA_EPOCH = 7L
		const val DELETED_SOURCE_HIGH_WATER = 0L
		const val ELIGIBLE_POLICY_REVISION = 1L
		const val REVOKED_POLICY_REVISION = 2L
		const val CURRENT_POLICY_REVISION = 3L
		const val RACED_POLICY_REVISION = 4L
		const val CAPTURE_CONSENT_EPOCH = 4L
		const val REVOKED_CONSENT_EPOCH = 5L
		const val INITIAL_CONTROL_CONSENT_EPOCH = 6L
		const val INITIAL_AMBIENT_CONSENT_EPOCH = 7L
		const val AMBIENT_CONSENT_EPOCH = 8L
		const val CONTROL_CONSENT_EPOCH = 9L
		const val QOS_OFF = 0
		const val BOOT_ID = "boot-wifi-delete"
		const val ELIGIBLE_ELAPSED_NANOS = 100L
		const val REVOKED_ELAPSED_NANOS = 200L
		const val CURRENT_POLICY_ELAPSED_NANOS = 300L
		const val RACED_POLICY_ELAPSED_NANOS = 400L
		const val ELIGIBLE_AT_MS = 1_000L
		const val REVOKED_AT_MS = 2_000L
		const val CURRENT_POLICY_AT_MS = 2_200L
		const val RACED_POLICY_AT_MS = 2_800L
		const val EVIDENCE_UPDATED_AT_MS = 2_500L
		const val DELETED_AT_MS = 3_000L
		const val SENTINEL_LOGICAL_TRACKING_ID = "sentinel-wifi-session"
		const val SENTINEL_SERVICE_RUN_ID = "sentinel-wifi-run"
		const val SENTINEL_WAL_EVENT_ID = "sentinel-wifi-ambient-event"
	}
}
