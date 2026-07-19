package com.adsamcik.tracker.tracker.service

import android.content.pm.ServiceInfo
import android.os.Build
import com.adsamcik.tracker.stats.api.PolicyTier
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("foregroundServiceTypeCandidates")
class ForegroundServiceTypeCandidatesTest {

	private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
	private val health = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
	private val specialUse = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

	private val android14 = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
	private val android13 = Build.VERSION_CODES.TIRAMISU

	@Nested
	@DisplayName("before Android 14")
	inner class BeforeAndroid14 {
		@Test
		fun `location session uses location type when permission is held`() {
			candidates(
				sdkInt = android13,
				requiresLocation = true,
				requiresHealth = false,
				hasLocationPermission = true,
				hasActivityPermission = false,
			) shouldBe listOf(location)
		}

		@Test
		fun `non-location session starts without a typed foreground service`() {
			candidates(
				sdkInt = android13,
				requiresLocation = false,
				requiresHealth = true,
				hasLocationPermission = true,
				hasActivityPermission = true,
			) shouldBe listOf(null)
		}

		@Test
		fun `location session has no fallback when permission is missing`() {
			candidates(
				sdkInt = android13,
				requiresLocation = true,
				requiresHealth = false,
				hasLocationPermission = false,
				hasActivityPermission = true,
			) shouldBe emptyList()
		}
	}

	@Nested
	@DisplayName("Android 14+ GPS session")
	inner class GpsSession {
		@Test
		fun `declares location and health when both sources are active`() {
			candidates(
				sdkInt = android14,
				requiresLocation = true,
				requiresHealth = true,
				hasLocationPermission = true,
				hasActivityPermission = true,
			) shouldBe listOf(location or health)
		}

		@Test
		fun `declares only location when health access is unavailable`() {
			candidates(
				sdkInt = android14,
				requiresLocation = true,
				requiresHealth = true,
				hasLocationPermission = true,
				hasActivityPermission = false,
			) shouldBe listOf(location)
		}

		@Test
		fun `has no non-location fallback when location is required`() {
			candidates(
				sdkInt = android14,
				requiresLocation = true,
				requiresHealth = true,
				hasLocationPermission = false,
				hasActivityPermission = true,
			) shouldBe emptyList()
		}
	}

	@Nested
	@DisplayName("Android 14+ non-location session")
	inner class NonLocationSession {
		@Test
		fun `uses health when activity or step collection is active`() {
			candidates(
				sdkInt = android14,
				requiresLocation = false,
				requiresHealth = true,
				hasLocationPermission = true,
				hasActivityPermission = true,
			) shouldBe listOf(health)
		}

		@Test
		fun `never offers location merely because location permission is held`() {
			candidates(
				sdkInt = android14,
				requiresLocation = false,
				requiresHealth = true,
				hasLocationPermission = true,
				hasActivityPermission = true,
			) shouldBe listOf(health)
		}

		@Test
		fun `uses special-use when health access is unavailable`() {
			candidates(
				sdkInt = android14,
				requiresLocation = false,
				requiresHealth = true,
				hasLocationPermission = true,
				hasActivityPermission = false,
			) shouldBe listOf(specialUse)
		}

		@Test
		fun `signal-only session uses special-use even when other permissions are held`() {
			candidates(
				sdkInt = android14,
				requiresLocation = false,
				requiresHealth = false,
				hasLocationPermission = true,
				hasActivityPermission = true,
			) shouldBe listOf(specialUse)
		}
	}

	@Nested
	@DisplayName("initial policy tier")
	inner class InitialPolicyTier {
		@Test
		fun `user-initiated sessions start in precision`() {
			resolveInitialPolicyTier(
				isUserInitiated = true,
				isAmbient = false,
				locationEnabled = true,
			) shouldBe PolicyTier.PRECISION
		}

		@Test
		fun `explicit ambient sessions stay ambient even when location is enabled`() {
			resolveInitialPolicyTier(
				isUserInitiated = false,
				isAmbient = true,
				locationEnabled = true,
			) shouldBe PolicyTier.AMBIENT
		}

		@Test
		fun `auto-started background tracking with location enabled starts GPS-capable`() {
			resolveInitialPolicyTier(
				isUserInitiated = false,
				isAmbient = false,
				locationEnabled = true,
			) shouldBe PolicyTier.ACTIVE
		}

		@Test
		fun `auto-started non-location tracking stays ambient`() {
			resolveInitialPolicyTier(
				isUserInitiated = false,
				isAmbient = false,
				locationEnabled = false,
			) shouldBe PolicyTier.AMBIENT
		}
	}

	private fun candidates(
		sdkInt: Int,
		requiresLocation: Boolean,
		requiresHealth: Boolean,
		hasLocationPermission: Boolean,
		hasActivityPermission: Boolean,
	): List<Int?> = foregroundServiceTypeCandidates(
		sdkInt = sdkInt,
		requiresLocation = requiresLocation,
		requiresHealth = requiresHealth,
		hasLocationPermission = hasLocationPermission,
		hasActivityPermission = hasActivityPermission,
	)
}
