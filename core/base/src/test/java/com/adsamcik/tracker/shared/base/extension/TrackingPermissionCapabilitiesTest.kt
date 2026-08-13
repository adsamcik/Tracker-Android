package com.adsamcik.tracker.shared.base.extension

import io.kotest.matchers.shouldBe
import org.junit.Test

class TrackingPermissionCapabilitiesTest {
	@Test
	fun `API 29 precise foreground and background grants expose precise automatic access`() {
		val state = capabilities(api = 29, fine = true, background = true)

		state.foregroundLocation shouldBe ForegroundLocationCapability.PRECISE
		state.backgroundLocation shouldBe BackgroundLocationCapability.PRECISE
		state.nearbyWifiGranted shouldBe true
	}

	@Test
	fun `API 30 foreground denial remains distinct from unavailable hardware`() {
		capabilities(api = 30).foregroundLocation shouldBe ForegroundLocationCapability.DENIED
		capabilities(api = 30, locationFeature = false).foregroundLocation shouldBe
			ForegroundLocationCapability.UNAVAILABLE
	}

	@Test
	fun `API 30 foreground-only grant is manual-only background capability`() {
		val state = capabilities(api = 30, fine = true, background = false)

		state.foregroundLocation shouldBe ForegroundLocationCapability.PRECISE
		state.backgroundLocation shouldBe BackgroundLocationCapability.MANUAL_ONLY
		state.isManualLocationOnly shouldBe true
	}

	@Test
	fun `API 31 coarse-only remains approximate even with background access`() {
		val state = capabilities(api = 31, coarse = true, background = true)

		state.foregroundLocation shouldBe ForegroundLocationCapability.APPROXIMATE
		state.backgroundLocation shouldBe BackgroundLocationCapability.APPROXIMATE
		state.hasPreciseLocation shouldBe false
	}

	@Test
	fun `API 33 scan capability requires both precise and nearby Wi-Fi grants`() {
		capabilities(api = 33, fine = true, nearby = false).wifiScan shouldBe
			WifiScanCapability.MISSING_NEARBY_WIFI
		capabilities(api = 33, fine = false, nearby = true).wifiScan shouldBe
			WifiScanCapability.MISSING_PRECISE_LOCATION
		capabilities(api = 33, fine = true, nearby = true).wifiScan shouldBe
			WifiScanCapability.AVAILABLE
	}

	@Test
	fun `API 34 Settings revocation is distinguished using persisted grant history`() {
		val previous = capabilities(api = 34, fine = true, background = true, nearby = true)
			.recordGrants(PermissionGrantHistory())
		val revoked = capabilities(api = 34, history = previous)

		revoked.foregroundLocation shouldBe ForegroundLocationCapability.REVOKED
		revoked.backgroundLocation shouldBe BackgroundLocationCapability.REVOKED
		revoked.wifiScan shouldBe WifiScanCapability.REVOKED
	}

	@Test
	fun `API 37 precise to approximate Settings downgrade is not reported as precise`() {
		val previous = capabilities(api = 37, fine = true, background = true, nearby = true)
			.recordGrants(PermissionGrantHistory())
		val downgraded = capabilities(
			api = 37,
			coarse = true,
			background = true,
			nearby = true,
			history = previous,
		)

		downgraded.foregroundLocation shouldBe ForegroundLocationCapability.APPROXIMATE
		downgraded.backgroundLocation shouldBe BackgroundLocationCapability.APPROXIMATE
		downgraded.hasPreciseLocation shouldBe false
	}

	private fun capabilities(
		api: Int,
		locationFeature: Boolean = true,
		wifiFeature: Boolean = true,
		services: Boolean = true,
		coarse: Boolean = false,
		fine: Boolean = false,
		background: Boolean = false,
		nearby: Boolean = false,
		history: PermissionGrantHistory = PermissionGrantHistory(),
	) = TrackingPermissionCapabilities.evaluate(
		apiLevel = api,
		locationFeatureAvailable = locationFeature,
		wifiFeatureAvailable = wifiFeature,
		locationServicesEnabled = services,
		coarseLocationGranted = coarse,
		preciseLocationGranted = fine,
		backgroundLocationGranted = background,
		nearbyWifiGranted = nearby,
		history = history,
	)
}
