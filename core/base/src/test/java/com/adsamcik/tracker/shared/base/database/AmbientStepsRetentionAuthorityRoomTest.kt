package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
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

@RunWith(RobolectricTestRunner::class)
class AmbientStepsRetentionAuthorityRoomTest {
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
	fun `retention is default reject and portable import is consent independent`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 4L, updatedAtMs = 1L),
		)

		assertNull(
			database.ambientStepsFactRevisionDao().latestRetentionAuthority(
				AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		)
		val applied = assertIs<AmbientStepsRetentionAuthorityResult.Applied>(
			database.applyAmbientStepsRetentionDecision(
				AmbientStepsRetentionDecision.GrantPortableImport(
					opaquePolicyId = "policy-1",
					expectedCollectedDataEpoch = 4L,
					effectiveBootId = "boot-1",
					effectiveElapsedRealtimeNanos = 1L,
					effectiveWallTimeMs = 1L,
				),
			),
		)

		assertEquals(AmbientStepsRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT, applied.scope)
		val stored = requireNotNull(
			database.ambientStepsFactRevisionDao().latestRetentionAuthority(applied.scope),
		)
		assertNull(stored.sourcePolicyRevision)
		assertNull(stored.ambientConsentEpoch)
	}

	@Test
	fun `grant rejects an inexact retained boundary without changing Room authority`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(
				collectedDataEpoch = 4L,
				retainedFromMs = 1_000L,
				updatedAtMs = 1L,
			),
		)

		val result = database.applyAmbientStepsRetentionDecision(
			AmbientStepsRetentionDecision.GrantPortableImport(
				opaquePolicyId = "policy-1",
				expectedCollectedDataEpoch = 4L,
				effectiveBootId = "boot-1",
				effectiveElapsedRealtimeNanos = 1L,
				effectiveWallTimeMs = 1L,
				expectedRetainedFromMs = null,
			),
		)

		assertEquals(
			AmbientStepsRetentionAuthorityUnavailableReason.RETAINED_FROM_CHANGED,
			assertIs<AmbientStepsRetentionAuthorityResult.Unavailable>(result).reason,
		)
		assertNull(
			database.ambientStepsFactRevisionDao().latestRetentionAuthority(
				AmbientStepsRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
			),
		)
		assertEquals(0L, database.sourceEvidenceStateDao().get()?.revision)
	}

	@Test
	fun `live grant and revoke require exact policy consent and approval revisions`() = runTest {
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
		val enabled = policies.setNonCaptureConsent(
			expectedPolicyRevision =
				(policies.currentState() as SourcePolicyAuthorityState.Active).snapshot.revision,
			source = TrackingSourceComponent.STEPS,
			purpose = SourcePurpose.AMBIENT_PRODUCT,
			eligible = true,
			persistenceEligible = true,
			reason = "TEST_ENABLE",
		)
		val consentEpoch = requireNotNull(
			enabled[TrackingSourceComponent.STEPS].ambientConsentEpoch,
		)

		val grant = assertIs<AmbientStepsRetentionAuthorityResult.Applied>(
			database.applyAmbientStepsRetentionDecision(
				AmbientStepsRetentionDecision.GrantLiveAmbient(
					opaquePolicyId = "policy-1",
					expectedCollectedDataEpoch = 4L,
					expectedSourcePolicyRevision = enabled.revision,
					expectedAmbientConsentEpoch = consentEpoch,
					effectiveBootId = "boot-1",
					effectiveElapsedRealtimeNanos = 10L,
					effectiveWallTimeMs = 10L,
				),
			),
		)
		assertEquals(
			AmbientStepsRetentionAuthorityUnavailableReason.STALE_APPROVAL_REVISION,
			assertIs<AmbientStepsRetentionAuthorityResult.Unavailable>(
				database.applyAmbientStepsRetentionDecision(
					AmbientStepsRetentionDecision.Revoke(
						scope = AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
						expectedCollectedDataEpoch = 4L,
						effectiveBootId = "boot-1",
						effectiveElapsedRealtimeNanos = 11L,
						effectiveWallTimeMs = 11L,
						expectedPreviousApprovalRevision = grant.approvalRevision + 1L,
					),
				),
			).reason,
		)
		val revoke = assertIs<AmbientStepsRetentionAuthorityResult.Applied>(
			database.applyAmbientStepsRetentionDecision(
				AmbientStepsRetentionDecision.Revoke(
					scope = AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
					expectedCollectedDataEpoch = 4L,
					effectiveBootId = "boot-1",
					effectiveElapsedRealtimeNanos = 12L,
					effectiveWallTimeMs = 12L,
					expectedPreviousApprovalRevision = grant.approvalRevision,
				),
			),
		)

		assertEquals(2L, revoke.approvalRevision)
		assertEquals(
			AmbientStepsRetentionAuthorityEntity.STATE_REVOKED,
			database.ambientStepsFactRevisionDao()
				.latestRetentionAuthority(AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT)
				?.state,
		)
	}

	@Test
	fun `grant cannot predate referenced policy and consent`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 4L, updatedAtMs = 1L),
		)
		var elapsed = 10L
		val policies = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("boot-1", elapsed++, elapsed)
		}
		val snapshot = policies.bootstrapFromLegacy(
			TrackingParamsState(
				ambientStepsEnabled = true,
				legacySettingsMigrationCompleted = true,
			),
		)

		assertEquals(
			AmbientStepsRetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
			assertIs<AmbientStepsRetentionAuthorityResult.Unavailable>(
				database.applyAmbientStepsRetentionDecision(
					AmbientStepsRetentionDecision.GrantLiveAmbient(
						opaquePolicyId = "policy-1",
						expectedCollectedDataEpoch = 4L,
						expectedSourcePolicyRevision = snapshot.revision,
						expectedAmbientConsentEpoch = requireNotNull(
							snapshot[TrackingSourceComponent.STEPS].ambientConsentEpoch,
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
	fun `unrelated policy revision and reboot preserve referenced consent`() = runTest {
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
			ambientStepsEnabled = true,
			legacySettingsMigrationCompleted = true,
		)
		val original = policies.bootstrapFromLegacy(settings)
		val consentEpoch = requireNotNull(
			original[TrackingSourceComponent.STEPS].ambientConsentEpoch,
		)
		val consent = requireNotNull(
			database.sourcePolicyDao().consentEpoch(
				com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity.SOURCE_STEPS,
				com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose.AMBIENT_PRODUCT,
				consentEpoch,
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

		assertEquals(consentEpoch, revised[TrackingSourceComponent.STEPS].ambientConsentEpoch)
		assertEquals(original.revision, consent.policyRevision)
		assertEquals("boot-1", consent.effectiveBootId)
		assertIs<AmbientStepsRetentionAuthorityResult.Applied>(
			database.applyAmbientStepsRetentionDecision(
				AmbientStepsRetentionDecision.GrantLiveAmbient(
					opaquePolicyId = "policy-2",
					expectedCollectedDataEpoch = 4L,
					expectedSourcePolicyRevision = revised.revision,
					expectedAmbientConsentEpoch = consentEpoch,
					effectiveBootId = "boot-2",
					effectiveElapsedRealtimeNanos = 2L,
					effectiveWallTimeMs = 110L,
				),
			),
		)
	}

	@Test
	fun `same boot retention revisions require strictly increasing elapsed time`() = runTest {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = 4L, updatedAtMs = 1L),
		)
		database.applyAmbientStepsRetentionDecision(
			AmbientStepsRetentionDecision.GrantPortableImport(
				"policy-1",
				4L,
				"boot-1",
				10L,
				10L,
			),
		)

		assertEquals(
			AmbientStepsRetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
			assertIs<AmbientStepsRetentionAuthorityResult.Unavailable>(
				database.applyAmbientStepsRetentionDecision(
					AmbientStepsRetentionDecision.GrantPortableImport(
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
			AmbientStepsRetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
			assertIs<AmbientStepsRetentionAuthorityResult.Unavailable>(
				database.applyAmbientStepsRetentionDecision(
					AmbientStepsRetentionDecision.GrantPortableImport(
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
}
