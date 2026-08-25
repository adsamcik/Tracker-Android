package com.adsamcik.tracker.tracker.resilience

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTrackingStartupGuardTest {
	@Test
	fun `background force-stop origin stays suppressed until foreground intent is consumed`() = runTest {
		val gate = TrackingAutoRecoveryProcessGate()
		gate.suppressAfterConfirmedForceStop()

		val authorization = async {
			gate.awaitAuthorizationAfterStartupReady(confirmedForceStop = true)
		}
		runCurrent()

		authorization.isCompleted shouldBe false
		gate.isSuppressed(confirmedForceStop = true) shouldBe true
		gate.recordExplicitForegroundLaunch(confirmedForceStop = true) shouldBe true
		authorization.await() shouldBe
			TrackingAutoRecoveryAuthorization.EXPLICIT_FOREGROUND_AFTER_FORCE_STOP
		gate.isSuppressed(confirmedForceStop = true) shouldBe true
		gate.releaseAfterFreshStartupReady(confirmedForceStop = true) shouldBe true
		gate.isSuppressed(confirmedForceStop = true) shouldBe false
		gate.releaseAfterFreshStartupReady(confirmedForceStop = true) shouldBe true
		gate.recordExplicitForegroundLaunch(confirmedForceStop = true) shouldBe false
	}

	@Test
	fun `foreground before initializer remains pending through force-stop preparation`() = runTest {
		val gate = TrackingAutoRecoveryProcessGate()

		gate.recordExplicitForegroundLaunch(confirmedForceStop = true) shouldBe true
		gate.suppressAfterConfirmedForceStop()
		gate.isSuppressed(confirmedForceStop = true) shouldBe true

		gate.awaitAuthorizationAfterStartupReady(confirmedForceStop = true) shouldBe
			TrackingAutoRecoveryAuthorization.EXPLICIT_FOREGROUND_AFTER_FORCE_STOP
		gate.isSuppressed(confirmedForceStop = true) shouldBe true
		gate.releaseAfterFreshStartupReady(confirmedForceStop = true) shouldBe true
		gate.isSuppressed(confirmedForceStop = true) shouldBe false
	}

	@Test
	fun `ordinary startup neither waits for nor accepts force-stop foreground release`() = runTest {
		val gate = TrackingAutoRecoveryProcessGate()

		gate.recordExplicitForegroundLaunch(confirmedForceStop = false) shouldBe false
		gate.awaitAuthorizationAfterStartupReady(confirmedForceStop = false) shouldBe
			TrackingAutoRecoveryAuthorization.ORDINARY_START
		gate.releaseAfterFreshStartupReady(confirmedForceStop = false) shouldBe false
		gate.isSuppressed(confirmedForceStop = false) shouldBe false
	}
}
