package com.adsamcik.tracker.tracker.source.control

import com.adsamcik.tracker.activity.api.registration.ActivityMotionAssistance
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationOwner
import com.adsamcik.tracker.activity.api.registration.LocationMotionPurpose
import com.adsamcik.tracker.shared.base.database.AppDatabase
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocationMotionAssistanceOwnerTest {
	@Test
	fun `unavailable optional authority still configures Location motion and closes exact scope`() = runTest {
		// Storage/registration unavailability must never make Location-only adaptation conditional.
		val database = mockk<AppDatabase>()
		val controller = mockk<CollectionMotionController>(relaxed = true)
		val arbiter = mockk<ActivityRegistrationArbiter>(relaxed = true)
		val assistance = mockk<ActivityMotionAssistance> {
			every { hints } returns emptyFlow()
		}
		val owner = LocationMotionAssistanceOwner(database, mockk(), mockk(), arbiter, assistance,
			controller, backgroundScope)
		val scope = LocationMotionScope("shared", "boot", 10L, "host",
			setOf(LocationMotionPurpose.CAPTURE))
		owner.reconcile(scope)
		verify { controller.configureScope("shared", "boot", 10L) }
		coVerify(exactly = 0) { arbiter.setDemand(any(), any()) }
		owner.reconcile(null)
		verify { controller.stopScope("shared") }
		coVerify { arbiter.clearDemand(ActivityRegistrationOwner.LOCATION_MOTION_ASSISTANCE) }
	}

	@Test
	fun `live join and leave keeps the same Location motion scope`() = runTest {
		val controller = mockk<CollectionMotionController>(relaxed = true)
		val assistance = mockk<ActivityMotionAssistance> { every { hints } returns emptyFlow() }
		val owner = LocationMotionAssistanceOwner(mockk(), mockk(), mockk(), mockk(relaxed = true),
			assistance, controller, backgroundScope)
		val background = LocationMotionScope("shared", "boot", 10L, "host",
			setOf(LocationMotionPurpose.BACKGROUND))
		owner.reconcile(background)
		owner.reconcile(background.copy(purposes = LocationMotionPurpose.entries.toSet()))
		owner.reconcile(background)
		verify(exactly = 0) { controller.stopScope(any()) }
		verify(exactly = 3) { controller.configureScope("shared", "boot", 10L) }
	}
}
