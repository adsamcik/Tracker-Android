package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientStepsSettingsReconciliationResult
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsImportAccess
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProvider
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProviderLifecycleOwner
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsProviderRegistrationResult
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AmbientStepsPurposeOwnerTest {
	@Test
	fun `production owner binds provider readiness to the exact floor lease`() = runTest {
		val lifecycle = mockk<AmbientStepsProviderLifecycleOwner>()
		coEvery { lifecycle.reconcile() } returns
			AmbientStepsProviderRegistrationResult.Active(
				provider = AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				registrationGeneration = 4L,
				importAccess = AmbientStepsImportAccess.BACKGROUND_ALLOWED,
				optionalPermissions = emptySet(),
				rearmedInThisProcess = true,
			)
		coEvery { lifecycle.retireAfterRetentionAuthorityFailure() } returns
			AmbientStepsSettingsReconciliationResult(complete = true, operational = false)
		val lease = AmbientReconciliationLease(
			AmbientReconciliationIdentity.from(
				TrackingPurposeLeaseIdentity(
					sourcePurpose =
						TrackingSource.STEPS.forPurpose(TrackingPurpose.AMBIENT_PRODUCT),
					policyRevision = 3L,
					consentEpoch = 5L,
					collectedDataEpoch = 7L,
					rolloutRevision = 11L,
					executionRevision = 1L,
					ownerCasToken = "steps-purpose-owner",
					retainedFromMs = 13L,
				),
			),
		)
		val subject = DefaultAmbientStepsPurposeOwner(lifecycle)

		subject.reconcile(lease) shouldBe AmbientSourceOperationalAvailability.ready(
			AmbientTrackingSource.STEPS,
			AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			lease.purposeLeaseIdentity,
		)
		subject.retireAfterRetentionAuthorityFailure(lease) shouldBe true
	}
}
