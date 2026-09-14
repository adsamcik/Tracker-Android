package com.adsamcik.tracker.tracker.source.cell

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.CellCapturedSourceDeletionBlockedReason
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.tracker.source.runtime.CellCaptureDeletionBarrierBlockedReason
import com.adsamcik.tracker.tracker.source.runtime.CellCaptureDeletionBarrierResult
import com.adsamcik.tracker.tracker.source.runtime.CellCaptureDeletionBarrierRetryableReason
import com.adsamcik.tracker.tracker.source.runtime.CellSourceRuntime
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CellCaptureConsentRevocationDeletionCommandTest {
	private lateinit var database: AppDatabase
	private lateinit var runtime: CellSourceRuntime
	private lateinit var subject: CellCaptureConsentRevocationDeletionCommand

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		runtime = mockk()
		subject = CellCaptureConsentRevocationDeletionCommand(database, runtime)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `revoked exact consent invokes barrier then idempotent authenticated deletion`() = runTest {
		installPolicy(revoked = true)
		coEvery { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) } returns
			CellCaptureDeletionBarrierResult.NoLocalProvider

		subject.delete(request()) shouldBe
			CellCaptureConsentRevocationDeletionResult.AlreadyDeleted
		subject.delete(request(deletedAtMs = DELETED_AT_MS + 1L)) shouldBe
			CellCaptureConsentRevocationDeletionResult.AlreadyDeleted

		coVerify(exactly = 2) { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) }
		database.sourceBrokerDao().maximumRegistrationGeneration(CELL_SOURCE) shouldBe 0L
		database.cellCapturedFactDao().maintenanceWalCount(CELL_SOURCE) shouldBe 0L
	}

	@Test
	fun `revoked capture permits exact CONTROL-only provider barrier while source stays enabled`() =
		runTest {
			installPolicy(revoked = true, revokedSourceEnabled = true)
			coEvery { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) } returns
				CellCaptureDeletionBarrierResult.Established(0L)

			subject.delete(request()) shouldBe
				CellCaptureConsentRevocationDeletionResult.AlreadyDeleted

			coVerify(exactly = 1) {
				runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH)
			}
			database.sourcePolicyDao().policyAtRevision(
				REVOKED_POLICY_REVISION,
				CELL_SOURCE,
			)?.enabled shouldBe true
		}

	@Test
	fun `eligible consent is blocked before touching Cell runtime`() = runTest {
		installPolicy(revoked = false)

		subject.delete(request(expectedRevokedConsentEpoch = CAPTURE_CONSENT_EPOCH)) shouldBe
			CellCaptureConsentRevocationDeletionResult.Blocked(
				CellCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE,
			)

		coVerify(exactly = 0) { runtime.establishCaptureDeletionBarrier(any()) }
	}

	@Test
	fun `stale deletion epoch is blocked before touching Cell runtime`() = runTest {
		installPolicy(revoked = true)

		subject.delete(request(expectedCollectedDataEpoch = COLLECTED_DATA_EPOCH + 1L)) shouldBe
			CellCaptureConsentRevocationDeletionResult.Blocked(
				CellCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
			)
		subject.delete(request(
			expectedDeletedSourceEventHighWaterOrdinal = DELETED_SOURCE_HIGH_WATER + 1L,
		)) shouldBe CellCaptureConsentRevocationDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
		)
		subject.delete(request(expectedRevokedConsentEpoch = REVOKED_CONSENT_EPOCH + 1L)) shouldBe
			CellCaptureConsentRevocationDeletionResult.Blocked(
				CellCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE,
			)

		coVerify(exactly = 0) { runtime.establishCaptureDeletionBarrier(any()) }
	}

	@Test
	fun `active capture authorization maps to typed blocked without deletion`() = runTest {
		installPolicy(revoked = true)
		coEvery { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) } returns
			CellCaptureDeletionBarrierResult.Blocked(
				CellCaptureDeletionBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE,
			)

		subject.delete(request()) shouldBe CellCaptureConsentRevocationDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED,
		)
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `callback drain timeout maps to typed retryable without a retry loop`() = runTest {
		installPolicy(revoked = true)
		coEvery { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) } returns
			CellCaptureDeletionBarrierResult.Retryable(
				CellCaptureDeletionBarrierRetryableReason.CALLBACK_DRAIN_TIMED_OUT,
			)

		subject.delete(request()) shouldBe CellCaptureConsentRevocationDeletionResult.Retryable(
			CellCaptureConsentRevocationDeletionRetryableReason.CALLBACK_BARRIER_UNAVAILABLE,
		)
		coVerify(exactly = 1) { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) }
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `callback barrier publication failure maps to storage retry without deletion`() = runTest {
		installPolicy(revoked = true)
		coEvery { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) } returns
			CellCaptureDeletionBarrierResult.Retryable(
				CellCaptureDeletionBarrierRetryableReason.BARRIER_PUBLICATION_FAILED,
			)

		subject.delete(request()) shouldBe CellCaptureConsentRevocationDeletionResult.Retryable(
			CellCaptureConsentRevocationDeletionRetryableReason.STORAGE_UNAVAILABLE,
		)
		coVerify(exactly = 1) { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) }
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `consent epoch race after callback barrier is rejected by deletion transaction`() = runTest {
		installPolicy(revoked = true)
		coEvery { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) } coAnswers {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_policy_authority SET current_policy_revision = ?, updated_at_ms = ? WHERE id = 1",
				arrayOf(ELIGIBLE_POLICY_REVISION, DELETED_AT_MS),
			)
			CellCaptureDeletionBarrierResult.Established(1L)
		}

		subject.delete(request()) shouldBe CellCaptureConsentRevocationDeletionResult.Blocked(
			CellCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE,
		)
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	@Test
	fun `cancellation from Cell callback barrier propagates`() = runTest {
		installPolicy(revoked = true)
		coEvery { runtime.establishCaptureDeletionBarrier(COLLECTED_DATA_EPOCH) } throws
			CancellationException("cancel Cell deletion")

		shouldThrow<CancellationException> { subject.delete(request()) }
		database.sourceEvidenceStateDao().get()?.revision shouldBe 0L
	}

	private suspend fun installPolicy(
		revoked: Boolean,
		revokedSourceEnabled: Boolean = false,
	) {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
				deletedSourceEventHighWaterOrdinal = DELETED_SOURCE_HIGH_WATER,
				updatedAtMs = EVIDENCE_UPDATED_AT_MS,
			),
		)
		val eligiblePolicy = policy(revoked = false)
		val policies = if (revoked) listOf(
			eligiblePolicy,
			policy(revoked = true, sourceEnabled = revokedSourceEnabled),
		) else
			listOf(eligiblePolicy)
		val eligibleConsent = consent(revoked = false)
		val consents = if (revoked) listOf(eligibleConsent, consent(revoked = true)) else
			listOf(eligibleConsent)
		database.sourcePolicyDao().insertPolicies(policies)
		database.sourcePolicyDao().insertConsentEpochs(consents)
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = if (revoked) REVOKED_POLICY_REVISION else
					ELIGIBLE_POLICY_REVISION,
				legacySettingsFingerprint = null,
				updatedAtMs = if (revoked) REVOKED_AT_MS else ELIGIBLE_AT_MS,
			),
		)
	}

	private fun policy(
		revoked: Boolean,
		sourceEnabled: Boolean = !revoked,
	) = SourcePolicyEntity(
		policyRevision = if (revoked) REVOKED_POLICY_REVISION else ELIGIBLE_POLICY_REVISION,
		sourceKind = CELL_SOURCE,
		enabled = sourceEnabled,
		qosCode = 1,
		locationMinTimeSeconds = null,
		locationMinDistanceMeters = null,
		locationRequiredAccuracyMeters = null,
		capturePersistenceEligible = !revoked,
		controlPersistenceEligible = false,
		ambientPersistenceEligible = false,
		captureConsentEpoch = CAPTURE_CONSENT_EPOCH.takeUnless { revoked },
		controlConsentEpoch = null,
		ambientConsentEpoch = null,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = if (revoked) REVOKED_ELAPSED_NANOS else
			ELIGIBLE_ELAPSED_NANOS,
		effectiveWallTimeMs = if (revoked) REVOKED_AT_MS else ELIGIBLE_AT_MS,
		changeReason = if (revoked) "TEST_REVOKED" else "TEST_ELIGIBLE",
	)

	private fun consent(revoked: Boolean) = SourceConsentEpochEntity(
		sourceKind = CELL_SOURCE,
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

	private fun request(
		expectedCollectedDataEpoch: Long = COLLECTED_DATA_EPOCH,
		expectedDeletedSourceEventHighWaterOrdinal: Long = DELETED_SOURCE_HIGH_WATER,
		expectedRevokedConsentEpoch: Long = REVOKED_CONSENT_EPOCH,
		deletedAtMs: Long = DELETED_AT_MS,
	) = CellCaptureConsentRevocationDeletionRequest(
		expectedCollectedDataEpoch,
		expectedDeletedSourceEventHighWaterOrdinal,
		expectedRevokedConsentEpoch,
		deletedAtMs,
	)

	private companion object {
		const val CELL_SOURCE = SourceDestinationOwnerEntity.SOURCE_CELL
		const val COLLECTED_DATA_EPOCH = 3L
		const val DELETED_SOURCE_HIGH_WATER = 7L
		const val ELIGIBLE_POLICY_REVISION = 1L
		const val REVOKED_POLICY_REVISION = 2L
		const val CAPTURE_CONSENT_EPOCH = 4L
		const val REVOKED_CONSENT_EPOCH = 5L
		const val BOOT_ID = "boot-cell-delete"
		const val ELIGIBLE_ELAPSED_NANOS = 100L
		const val REVOKED_ELAPSED_NANOS = 200L
		const val ELIGIBLE_AT_MS = 1_000L
		const val REVOKED_AT_MS = 2_000L
		const val EVIDENCE_UPDATED_AT_MS = 2_500L
		const val DELETED_AT_MS = 3_000L
	}
}
