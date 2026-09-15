package com.adsamcik.tracker.tracker.source.ambient.steps

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AndroidAmbientStepsProviderBackendsTest {
	@Test
	fun `health connect activation rechecks exact selected capability`() = runTest {
		var resolves = 0
		val subject = HealthConnectAmbientStepsProviderBackend {
			resolves += 1
			AmbientStepsCapability.ReadyForRegistration(
				provider = AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
				importAccess = AmbientStepsImportAccess.FOREGROUND_ONLY,
			)
		}

		subject.ensureActive()
		subject.remove()

		resolves shouldBe 1
		subject.provider shouldBe AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS
	}

	@Test
	fun `health connect activation rejects permission loss before acceptance`() = runTest {
		val subject = HealthConnectAmbientStepsProviderBackend {
			AmbientStepsCapability.PermissionRequired(
				provider = AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
				requiredPermissions = setOf(AmbientStepsPermission.HEALTH_CONNECT_READ_STEPS),
			)
		}

		shouldThrow<IllegalStateException> { subject.ensureActive() }
	}

	@Test
	fun `local recording backend delegates exact idempotent subscription operations`() = runTest {
		val calls = mutableListOf<String>()
		val subject = LocalRecordingAmbientStepsProviderBackend(
			subscribe = { calls += "subscribe-step-delta" },
			unsubscribe = { calls += "unsubscribe-step-delta" },
		)

		subject.ensureActive()
		subject.remove()

		subject.provider shouldBe AmbientStepsProvider.LOCAL_RECORDING_STEPS
		calls shouldBe listOf("subscribe-step-delta", "unsubscribe-step-delta")
	}

	@Test
	fun `local recording provider failures propagate to durable coordinator`() = runTest {
		val subject = LocalRecordingAmbientStepsProviderBackend(
			subscribe = { error("subscription failed") },
			unsubscribe = { error("unsubscription failed") },
		)

		shouldThrow<IllegalStateException> { subject.ensureActive() }
		shouldThrow<IllegalStateException> { subject.remove() }
	}
}
