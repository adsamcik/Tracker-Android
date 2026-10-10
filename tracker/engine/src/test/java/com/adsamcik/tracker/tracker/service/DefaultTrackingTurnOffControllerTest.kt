package com.adsamcik.tracker.tracker.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.tracker.api.TrackingPurposeUserStopFencer
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationResult
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand
import com.adsamcik.tracker.tracker.resilience.TrackingTurnOffReservation
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultTrackingTurnOffControllerTest {
	private lateinit var database: AppDatabase

	@Before fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After fun tearDown() {
		unmockkObject(BackgroundTrackingApi)
		database.close()
	}

	@Test fun `one Off boundary fences every purpose without changing historical demands`() = runTest {
		val purposes = listOf(
			SourceKind.LOCATION to SourceBrokerPurpose.SESSION_CAPTURE,
			SourceKind.ACTIVITY to SourceBrokerPurpose.CONTROL_AUTOSTART,
			SourceKind.ACTIVITY to SourceBrokerPurpose.CONTROL_CONTINUATION,
			SourceKind.STEPS to SourceBrokerPurpose.AMBIENT_PRODUCT,
			SourceKind.PRESSURE to SourceBrokerPurpose.SESSION_CAPTURE,
			SourceKind.WIFI to SourceBrokerPurpose.AMBIENT_PRODUCT,
			SourceKind.CELL to SourceBrokerPurpose.SESSION_CAPTURE,
		)
		val live = purposes.mapIndexed { index, (source, purpose) ->
			demand(index.toString(), source, purpose, SourceDemandEntity.STATUS_ACTIVE)
		}
		val blocked = demand("blocked-wifi", SourceKind.WIFI,
			SourceBrokerPurpose.SESSION_CAPTURE, SourceDemandEntity.STATUS_BLOCKED)
		val history = demand("history", SourceKind.LOCATION,
			SourceBrokerPurpose.SESSION_CAPTURE, SourceDemandEntity.STATUS_RETIRED)
			.copy(retireBootId = "old-boot", retireElapsedRealtimeNanos = 20L, retiredAtMs = 20L)
		database.sourceBrokerDao().insertDemands(live + blocked + history)

		val commands = mockk<TrackingLifecycleCommandAuthority>()
		coEvery { commands.reserveTurnOff(any(), any(), any(), isNull(), any()) } coAnswers {
			arg<suspend () -> Unit>(4).invoke()
			TrackingTurnOffReservation(
				firstArg<String>(),
				TrackingStopCommand(1L, TrackingStopCandidateReason.EXPLICIT_REQUEST, 100L),
			)
		}
		coEvery { commands.withPendingTurnOff(any(), any()) } coAnswers {
			arg<suspend () -> Boolean>(1).invoke()
		}
		val owners = mockk<TrackingPurposeUserStopFencer>()
		coEvery { owners.closeForUserStop() } returns
			TrackingPurposeSettingsReconciliationResult.Complete(emptySet())
		mockkObject(BackgroundTrackingApi)
		coEvery { BackgroundTrackingApi.stopAutomaticControlForUser(any()) } returns true
		val subject = DefaultTrackingTurnOffController(
			ApplicationProvider.getApplicationContext<Application>(), commands, database,
			BootClockDomainProvider { "boot-off" }, owners)

		val reserved = requireNotNull(subject.reserveAndFence())
		(live + blocked).forEach { original ->
			val after = database.sourceBrokerDao().demandHistory(original.consumerId).single()
			after.status shouldBe if (original.status == SourceDemandEntity.STATUS_BLOCKED) {
				SourceDemandEntity.STATUS_RETIRED
			} else SourceDemandEntity.STATUS_RETIRING
			after.retireBootId shouldBe "boot-off"
		}
		database.sourceBrokerDao().demandHistory(history.consumerId).single() shouldBe history
		coVerify(exactly = 0) { owners.closeForUserStop() }
		// A demand left by a crash after the durable Off latch must be fenced on repair.
		val interrupted = demand("interrupted", SourceKind.WIFI,
			SourceBrokerPurpose.AMBIENT_PRODUCT, SourceDemandEntity.STATUS_ACTIVE)
		database.sourceBrokerDao().insertDemands(listOf(interrupted))
		subject.closeOwnersAfterSessionStop(reserved.exitId) shouldBe true
		database.sourceBrokerDao().demandHistory(interrupted.consumerId).single().status shouldBe
			SourceDemandEntity.STATUS_RETIRING
		coVerify(exactly = 1) { owners.closeForUserStop() }
		coVerify(exactly = 1) { BackgroundTrackingApi.stopAutomaticControlForUser(any()) }
	}

	private fun demand(id: String, source: SourceKind, purpose: String, status: String): SourceDemandEntity {
		val session = purpose == SourceBrokerPurpose.SESSION_CAPTURE ||
			purpose == SourceBrokerPurpose.CONTROL_CONTINUATION
		return SourceDemandEntity(
			demandId = "demand-$id", consumerId = "consumer-$id", sourceKind = source.stableCode,
			purpose = purpose, logicalTrackingId = "track-$id".takeIf { session },
			serviceRunId = "run-$id".takeIf { session }, manifestRevision = 1L.takeIf { session },
			lifecycleLeaseGeneration = 1L.takeIf { session }, sourcePolicyRevision = 1L,
			consentEpoch = 1L, persistenceEligible = purpose != SourceBrokerPurpose.CONTROL_AUTOSTART,
			qosCode = 1, maximumAgeMs = 1_000L, desiredLatencyMs = 1_000L,
			requestedBootId = "boot-off", requestedElapsedRealtimeNanos = 1L, requestedAtMs = 1L,
			status = status, retireBootId = null, retireElapsedRealtimeNanos = null, retiredAtMs = null,
		)
	}
}
