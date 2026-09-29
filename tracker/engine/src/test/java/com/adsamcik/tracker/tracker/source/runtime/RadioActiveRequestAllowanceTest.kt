package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadioActiveRequestAllowanceTest {
	private val context get() = ApplicationProvider.getApplicationContext<Application>()

	@Before fun clear() {
		context.getSharedPreferences("radio_active_request_allowance", Context.MODE_PRIVATE)
			.edit().clear().commit()
	}

	@Test fun `restart and reconfiguration cannot replenish the spent session request`() = runTest {
		val first = RadioActiveRequestAllowance(context, UnconfinedTestDispatcher(testScheduler))
		first.reserve(SourceKind.WIFI, "trip-a", 60_000L, "boot-1", 1_000L, 1_000L) shouldBe
			RadioActiveRequestDecision.ReadyAt(1_000L)
		val afterProcessRestart = RadioActiveRequestAllowance(context,
			UnconfinedTestDispatcher(testScheduler))
		afterProcessRestart.inspect(SourceKind.WIFI, "trip-a", 60_000L, "boot-1",
			90_000L, 90_000L) shouldBe RadioActiveRequestDecision.Spent
		afterProcessRestart.reserve(SourceKind.WIFI, "trip-a", 0L, "boot-1",
			90_000L, 90_000L) shouldBe RadioActiveRequestDecision.Spent
	}

	@Test fun `another session waits for cooldown while the other radio remains independent`() = runTest {
		val ledger = RadioActiveRequestAllowance(context, UnconfinedTestDispatcher(testScheduler))
		ledger.reserve(SourceKind.WIFI, "trip-a", 60_000L, "boot-1", 1_000L, 1_000L) shouldBe
			RadioActiveRequestDecision.ReadyAt(1_000L)
		ledger.inspect(SourceKind.WIFI, "trip-b", 60_000L, "boot-1", 2_000L, 2_000L) shouldBe
			RadioActiveRequestDecision.ReadyAt(61_000L)
		ledger.reserve(SourceKind.WIFI, "trip-b", 60_000L, "boot-1", 2_000L, 2_000L) shouldBe
			RadioActiveRequestDecision.ReadyAt(61_000L)
		ledger.inspect(SourceKind.CELL, "trip-b", 60_000L, "boot-1", 2_000L, 2_000L) shouldBe
			RadioActiveRequestDecision.ReadyAt(2_000L)
		ledger.reserve(SourceKind.WIFI, "trip-b", 60_000L, "boot-1", 61_000L, 61_000L) shouldBe
			RadioActiveRequestDecision.ReadyAt(61_000L)
		ledger.inspect(SourceKind.WIFI, "trip-b", 60_000L, "boot-1", 61_001L, 61_001L) shouldBe
			RadioActiveRequestDecision.Spent
	}

	@Test fun `reboot keeps the spent session and uses wall time for another session cooldown`() = runTest {
		val ledger = RadioActiveRequestAllowance(context, UnconfinedTestDispatcher(testScheduler))
		ledger.reserve(SourceKind.CELL, "trip-a", 60_000L, "boot-1", 20_000L, 100_000L) shouldBe
			RadioActiveRequestDecision.ReadyAt(20_000L)
		val reopened = RadioActiveRequestAllowance(context, UnconfinedTestDispatcher(testScheduler))
		reopened.inspect(SourceKind.CELL, "trip-a", 60_000L, "boot-2", 500L, 130_000L) shouldBe
			RadioActiveRequestDecision.Spent
		reopened.inspect(SourceKind.CELL, "trip-b", 60_000L, "boot-2", 500L, 130_000L) shouldBe
			RadioActiveRequestDecision.ReadyAt(30_500L)
	}
}
