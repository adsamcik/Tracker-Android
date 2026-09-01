package com.adsamcik.tracker.tracker.source.projection

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceBrokerDao
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.api.ActivityAutomationDeliveryEnvelope
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityAutomationEffectValidatorRegistrationTest {
	private val database = mockk<AppDatabase>()
	private val brokerDao = mockk<SourceBrokerDao>()
	private val automationEpochAuthority = mockk<ActivityAutomationEpochAuthority>()
	private val subject = ActivityAutomationEffectValidator(
		database = database,
		bootClockDomainProvider = BootClockDomainProvider { BOOT_ID },
		automaticStartActions = mockk<ActivityAutomaticStartActionRepository>(relaxed = true),
		automationEpochAuthority = automationEpochAuthority,
	)

	@Test
	fun `concrete validator terminalizes a missing exact registration generation`() = runTest {
		val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
		val effect = ActivityAutomationDeliveryEnvelope(
			admissionOrdinal = 1L,
			activityType = DetectedActivityType.WALKING,
			confidence = 90,
			transitionType = null,
			clockDomainId = BOOT_ID,
			observedElapsedRealtimeNanos = nowElapsedRealtimeNanos,
			receivedElapsedRealtimeNanos = nowElapsedRealtimeNanos,
			registrationGeneration = REGISTRATION_GENERATION,
			authorizationRevision = 2L,
			authorizationFingerprint = "authorization",
			collectedDataEpoch = 7L,
			automationEpoch = AUTOMATION_EPOCH,
		)
		every { database.sourceBrokerDao() } returns brokerDao
		coEvery {
			brokerDao.registration(SourceKind.ACTIVITY.stableCode, REGISTRATION_GENERATION)
		} returns null
		coEvery { brokerDao.authorizationAt(any(), any(), any(), any()) } returns emptyList()
		coEvery { automationEpochAuthority.currentForValidation() } returns
			ActivityAutomationEpochEntity(
				epoch = AUTOMATION_EPOCH,
				automaticControlEnabled = true,
				bootClockDomainId = BOOT_ID,
				effectiveElapsedRealtimeNanos = 0L,
				lastRotationReason = "TEST",
			)

		subject.validate(effect) shouldBe ActivityAutomationEffectValidation.Terminal(
			"AUTOMATIC_START_REGISTRATION_MISSING",
		)
		coVerify(exactly = 1) {
			brokerDao.registration(SourceKind.ACTIVITY.stableCode, REGISTRATION_GENERATION)
		}
	}

	private companion object {
		const val BOOT_ID = "boot"
		const val REGISTRATION_GENERATION = 1L
		const val AUTOMATION_EPOCH = 17L
	}
}
