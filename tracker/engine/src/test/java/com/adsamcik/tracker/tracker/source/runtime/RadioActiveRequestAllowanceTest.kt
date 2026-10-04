package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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

	@Test fun `a later session quality cannot shorten the original cooldown`() = runTest {
		val first = RadioActiveRequestAllowance(context, UnconfinedTestDispatcher(testScheduler))
		first.reserve(SourceKind.CELL, "trip-a", 300_000L, "boot-1", 1_000L, 10_000L) shouldBe
			RadioActiveRequestDecision.ReadyAt(1_000L)
		val reopened = RadioActiveRequestAllowance(context, UnconfinedTestDispatcher(testScheduler))
		reopened.reserve(SourceKind.CELL, "trip-b", 120_000L, "boot-1", 2_000L, 11_000L) shouldBe
			RadioActiveRequestDecision.ReadyAt(301_000L)
		reopened.inspect(SourceKind.CELL, "trip-a", 0L, "boot-1", 400_000L, 409_000L) shouldBe
			RadioActiveRequestDecision.Spent
		reopened.reserve(SourceKind.CELL, "trip-b", 120_000L, "boot-1", 301_000L, 310_000L) shouldBe
			RadioActiveRequestDecision.ReadyAt(301_000L)
		reopened.inspect(SourceKind.CELL, "trip-b", 0L, "boot-1", 302_000L, 311_000L) shouldBe
			RadioActiveRequestDecision.Spent
	}

	@Test fun `reboot retains the original interval instead of the new shorter one`() = runTest {
		val ledger = RadioActiveRequestAllowance(context, UnconfinedTestDispatcher(testScheduler))
		ledger.reserve(SourceKind.WIFI, "trip-a", 120_000L, "boot-1", 10_000L, 100_000L)
		val reopened = RadioActiveRequestAllowance(context, UnconfinedTestDispatcher(testScheduler))
		reopened.inspect(SourceKind.WIFI, "trip-b", 60_000L, "boot-2", 500L, 130_000L) shouldBe
			RadioActiveRequestDecision.ReadyAt(90_500L)
	}

	@Test fun `a longer current cooldown still applies and wall rollback cannot shorten it`() = runTest {
		val ledger = RadioActiveRequestAllowance(context, UnconfinedTestDispatcher(testScheduler))
		ledger.reserve(SourceKind.WIFI, "trip-a", 60_000L, "boot-1", 1_000L, 100_000L)
		ledger.inspect(SourceKind.WIFI, "trip-b", 120_000L, "boot-1", 2_000L, 101_000L) shouldBe
			RadioActiveRequestDecision.ReadyAt(121_000L)
		ledger.inspect(SourceKind.WIFI, "trip-b", 120_000L, "boot-2", 500L, 90_000L) shouldBe
			RadioActiveRequestDecision.ReadyAt(120_500L)
	}

	@Test fun `a partial saved reservation cannot look like a fresh source`() = runTest {
		val prefix = "source_${SourceKind.WIFI.stableCode}"
		val saved = context.getSharedPreferences("radio_active_request_allowance", Context.MODE_PRIVATE)
		saved.edit().putString("${prefix}_session", "trip-a").commit()
		val ledger = RadioActiveRequestAllowance(context, UnconfinedTestDispatcher(testScheduler))
		ledger.inspect(SourceKind.WIFI, "trip-a", 0L, "boot-1", 1_000L, 1_000L) shouldBe
			RadioActiveRequestDecision.Spent
		ledger.reserve(SourceKind.WIFI, "trip-b", 0L, "boot-1", 1_000L, 1_000L) shouldBe
			RadioActiveRequestDecision.Unavailable
		ledger.inspect(SourceKind.CELL, "trip-b", 0L, "boot-1", 1_000L, 1_000L) shouldBe
			RadioActiveRequestDecision.ReadyAt(1_000L)
	}

	@Test fun `missing original interval keeps an old candidate reservation spent`() = runTest {
		val ledger = RadioActiveRequestAllowance(context, UnconfinedTestDispatcher(testScheduler))
		ledger.reserve(SourceKind.WIFI, "trip-a", 60_000L, "boot-1", 1_000L, 1_000L)
		val prefix = "source_${SourceKind.WIFI.stableCode}"
		context.getSharedPreferences("radio_active_request_allowance", Context.MODE_PRIVATE)
			.edit().remove("${prefix}_interval").commit()
		val reopened = RadioActiveRequestAllowance(context, UnconfinedTestDispatcher(testScheduler))
		reopened.inspect(SourceKind.WIFI, "trip-a", 0L, "boot-1", 70_000L, 70_000L) shouldBe
			RadioActiveRequestDecision.Spent
		reopened.reserve(SourceKind.WIFI, "trip-b", 0L, "boot-1", 70_000L, 70_000L) shouldBe
			RadioActiveRequestDecision.Unavailable
	}

	@Test fun `wrong stored types and blank identities do not grant a request`() = runTest {
		val ledger = RadioActiveRequestAllowance(context, UnconfinedTestDispatcher(testScheduler))
		ledger.reserve(SourceKind.CELL, "trip-a", 60_000L, "boot-1", 1_000L, 1_000L)
		val prefix = "source_${SourceKind.CELL.stableCode}"
		val saved = context.getSharedPreferences("radio_active_request_allowance", Context.MODE_PRIVATE)
		saved.edit().putString("${prefix}_interval", "not a number").commit()
		ledger.reserve(SourceKind.CELL, "trip-b", 0L, "boot-1", 70_000L, 70_000L) shouldBe
			RadioActiveRequestDecision.Unavailable
		saved.edit().putLong("${prefix}_interval", 60_000L).putString("${prefix}_session", "").commit()
		ledger.inspect(SourceKind.CELL, "trip-b", 0L, "boot-1", 70_000L, 70_000L) shouldBe
			RadioActiveRequestDecision.Unavailable
	}

	@Test fun `cooldown arithmetic saturates instead of overflowing and rejects elapsed rollback`() = runTest {
		val ledger = RadioActiveRequestAllowance(context, UnconfinedTestDispatcher(testScheduler))
		ledger.reserve(SourceKind.WIFI, "trip-a", Long.MAX_VALUE, "boot-1", 10_000L, 10_000L)
		ledger.inspect(SourceKind.WIFI, "trip-b", 0L, "boot-1", 10_001L, 10_001L) shouldBe
			RadioActiveRequestDecision.ReadyAt(Long.MAX_VALUE)
		ledger.reserve(SourceKind.WIFI, "trip-b", 0L, "boot-1", 9_999L, 10_001L) shouldBe
			RadioActiveRequestDecision.Unavailable
	}

	@Test fun `a failed commit cannot grant a request or refund its cached spend`() = runTest {
		val real = context.getSharedPreferences("radio_active_request_allowance", Context.MODE_PRIVATE)
		val failed = object : SharedPreferences by real {
			override fun edit(): SharedPreferences.Editor {
				val delegate = real.edit()
				return object : SharedPreferences.Editor by delegate {
					override fun putString(key: String, value: String?): SharedPreferences.Editor {
						delegate.putString(key, value)
						return this
					}
					override fun putLong(key: String, value: Long): SharedPreferences.Editor {
						delegate.putLong(key, value)
						return this
					}
					override fun commit(): Boolean {
						delegate.commit()
						return false
					}
				}
			}
		}
		val storageFailureContext = object : ContextWrapper(context) {
			override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = failed
		}
		val ledger = RadioActiveRequestAllowance(storageFailureContext, UnconfinedTestDispatcher(testScheduler))
		ledger.reserve(SourceKind.WIFI, "trip-a", 120_000L, "boot-1", 1_000L, 1_000L) shouldBe
			RadioActiveRequestDecision.Unavailable
		ledger.reserve(SourceKind.WIFI, "trip-a", 0L, "boot-1", 2_000L, 2_000L) shouldBe
			RadioActiveRequestDecision.Spent
		val reopened = RadioActiveRequestAllowance(context, UnconfinedTestDispatcher(testScheduler))
		reopened.inspect(SourceKind.WIFI, "trip-b", 60_000L, "boot-1", 2_000L, 2_000L) shouldBe
			RadioActiveRequestDecision.ReadyAt(121_000L)
	}
}
