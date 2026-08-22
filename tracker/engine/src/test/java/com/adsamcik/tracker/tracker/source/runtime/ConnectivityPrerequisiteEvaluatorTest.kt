package com.adsamcik.tracker.tracker.source.runtime

import android.content.pm.PackageManager
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ConnectivityPrerequisiteEvaluatorTest {
	@Test
	fun `Wi-Fi scan requires fine location without an unrelated nearby devices grant`() {
		val noFine = WifiPrerequisiteEvaluator.evaluate(wifiPlan(), wifiDevice(fine = false))
		val fineOnly = WifiPrerequisiteEvaluator.evaluate(wifiPlan(), wifiDevice())

		assertEquals(SourceApplyStatus.BLOCKED, noFine.status)
		assertEquals(SourceApplyStatus.APPLIED, fineOnly.status)
		assertTrue(SourceDegradedReason.PERMISSION_MISSING in noFine.reasons)
	}

	@Test
	fun `Wi-Fi location services disabled is a visible provider block`() {
		val result = WifiPrerequisiteEvaluator.evaluate(wifiPlan(), wifiDevice(locationServices = false))

		assertEquals(SourceApplyStatus.BLOCKED, result.status)
		assertTrue(SourceDegradedReason.PROVIDER_UNAVAILABLE in result.reasons)
	}

	@Test
	fun `Doze defers active Wi-Fi attempts without disabling broadcasts or cache`() {
		val result = WifiPrerequisiteEvaluator.evaluate(wifiPlan(), wifiDevice(idle = true))

		assertEquals(SourceApplyStatus.DEGRADED, result.status)
		assertTrue(result.activeAttemptsDeferred)
		assertEquals(setOf(SourceDegradedReason.DOZE), result.reasons)
	}

	@Test
	fun `cell observation requires radio feature fine location and phone state`() {
		val missingRadio = CellPrerequisiteEvaluator.evaluate(cellPlan(), cellDevice(radio = false))
		val missingFine = CellPrerequisiteEvaluator.evaluate(cellPlan(), cellDevice(fine = false))
		val missingPhone = CellPrerequisiteEvaluator.evaluate(cellPlan(), cellDevice(phone = false))

		assertEquals(SourceApplyStatus.BLOCKED, missingRadio.status)
		assertEquals(SourceApplyStatus.BLOCKED, missingFine.status)
		assertEquals(SourceApplyStatus.BLOCKED, missingPhone.status)
		assertTrue(SourceDegradedReason.HARDWARE_UNAVAILABLE in missingRadio.reasons)
		assertTrue(SourceDegradedReason.PERMISSION_MISSING in missingFine.reasons)
	}

	@Test
	fun `API 26 fallback observes changes but reports sparse refresh degradation`() {
		val result = CellPrerequisiteEvaluator.evaluate(cellPlan(), cellDevice(api = 26, refresh = false))

		assertEquals(SourceApplyStatus.DEGRADED, result.status)
		assertEquals(CellMode.OBSERVE_AND_SPARSE_REFRESH, result.plan.mode)
		assertTrue(SourceDegradedReason.PROVIDER_UNAVAILABLE in result.reasons)
	}

	@Test
	fun `subscription scope pins requested SIMs and falls back to active then unscoped`() {
		assertEquals(setOf<Int?>(7, 9), resolveCellSubscriptionScope(setOf(7, 9), setOf(1, 2)))
		assertEquals(setOf<Int?>(1, 2), resolveCellSubscriptionScope(emptySet(), setOf(1, 2)))
		assertEquals(setOf<Int?>(null), resolveCellSubscriptionScope(emptySet(), emptySet()))
	}

	@Test
	fun `radio feature query uses the API 33 split`() {
		assertEquals(PackageManager.FEATURE_TELEPHONY, telephonyRadioFeatureName(32))
		assertEquals(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS, telephonyRadioFeatureName(33))
	}

	@Test
	fun `snapshot identity suppresses interleaved replay but preserves new provider observations`() {
		val gate = BoundedReplayIdentityGate()
		assertTrue(gate.shouldAdmit("same-content:time-1"))
		gate.record("same-content:time-1")
		assertTrue(gate.shouldAdmit("same-content:time-2"))
		gate.record("same-content:time-2")
		assertFalse(gate.shouldAdmit("same-content:time-1"))
	}

	private fun wifiPlan() = WifiPlan(1, WifiMode.ACTIVE_ATTEMPTS, 60_000, 120_000, 300_000, backoff())
	private fun cellPlan() = CellPlan(1, CellMode.OBSERVE_AND_SPARSE_REFRESH, 120_000, 60_000, emptySet(), backoff())
	private fun backoff() = RetryBackoff(30_000, 1_800_000)

	private fun wifiDevice(
		fine: Boolean = true,
		locationServices: Boolean = true,
		idle: Boolean = false,
	) = WifiDeviceState(true, fine, locationServices, idle)

	private fun cellDevice(
		api: Int = 31,
		radio: Boolean = true,
		fine: Boolean = true,
		phone: Boolean = true,
		refresh: Boolean = api >= 29,
	) = CellDeviceState(radio, fine, phone, refresh)
}
