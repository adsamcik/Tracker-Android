package com.adsamcik.tracker.tracker.source.runtime

import android.app.PendingIntent
import android.content.Intent
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RearmablePassiveLocationBackendTest {
	private val context get() = RuntimeEnvironment.getApplication()
	private val plan = LocationPlan(8, LocationBackend.FRAMEWORK, LocationMode.PASSIVE,
		120_000, 0, 0f, 0, preciseLocationAvailable = false,
		passiveSystemDelivery = true)

	@Test
	fun `exact identity is in filter URI and malformed or changed fields are rejected`() {
		val identity = identity()
		assertEquals(identity, PassiveLocationIdentity.fromUri(identity.uri()))
		assertNull(PassiveLocationIdentity.fromUri(identity.uri().buildUpon()
			.appendQueryParameter("generation", "9").build()))
		assertNull(PassiveLocationIdentity.fromUri(identity.uri().buildUpon()
			.appendPath("extra").build()))
		assertNull(PassiveLocationIdentity.fromUri(null))
	}

	@Test
	fun `framework passive registration removes the same mutable PendingIntent before replacement`() = runTest {
		var requested: PendingIntent? = null
		var removed: PendingIntent? = null
		val backend = RearmablePassiveLocationBackend(context, PassiveLocationOperations(
			requestFused = { _, _ -> error("wrong backend") },
			removeFused = { error("wrong backend") },
			requestFramework = { _, pending -> requested = pending },
			removeFramework = { pending -> removed = pending },
			frameworkPassiveAvailable = { true },
		))
		val identity = identity()
		backend.prepare(identity)
		assertNotNull(backend.resolvePlan(plan))
		assertEquals(LocationBackendStartOutcome.STARTED, backend.start(plan) {})
		assertTrue(backend.hasRetainedRegistration)
		assertNotNull(requested)
		assertEquals(RegistrationRemovalOutcome.REMOVED, backend.stop())
		assertEquals(requested, removed)
		assertFalse(backend.hasRetainedRegistration)
		val probe = Intent(PassiveLocationIdentity.ACTION)
			.setClass(context, PassiveLocationReceiver::class.java).setData(identity.uri())
		assertNull(PendingIntent.getBroadcast(context, 0, probe,
			PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE))
	}

	@Test
	fun `a retained passive request never claims a different durable identity`() = runTest {
		val backend = RearmablePassiveLocationBackend(context, PassiveLocationOperations(
			requestFused = { _, _ -> error("wrong backend") },
			removeFused = { error("wrong backend") },
			requestFramework = { _, _ -> Unit },
			removeFramework = { _ -> Unit },
			frameworkPassiveAvailable = { true },
		))
		backend.prepare(identity())
		assertEquals(LocationBackendStartOutcome.STARTED, backend.start(plan) {})
		assertTrue(backend.retains(identity()))
		assertFalse(backend.retains(identity().copy(generation = 9)))
		assertEquals(RegistrationRemovalOutcome.REMOVED, backend.stop())
	}

	private fun identity() = PassiveLocationIdentity(LocationBackend.FRAMEWORK, "source-8", 8,
		"boot-8", plan.physicalConfigurationFingerprint(), 3)
}
