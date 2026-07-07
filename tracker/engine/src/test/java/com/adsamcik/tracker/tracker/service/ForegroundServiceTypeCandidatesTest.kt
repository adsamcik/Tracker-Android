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
		fun `always offers only the location type regardless of permissions`() {
			candidates(
				sdkInt = android13,
				preferLocationType = false,
				hasLocationPermission = false,
				hasActivityPermission = false,
			) shouldBe listOf(location)
		}

		@Test
		fun `offers only the location type even for an ambient session`() {
			candidates(
				sdkInt = android13,
				preferLocationType = false,
				hasLocationPermission = true,
				hasActivityPermission = true,
			) shouldBe listOf(location)
		}
	}

	@Nested
	@DisplayName("Android 14+ GPS session")
	inner class GpsSession {
		@Test
		fun `prefers location then falls back to health when both permissions are held`() {
			candidates(
				sdkInt = android14,
				preferLocationType = true,
				hasLocationPermission = true,
				hasActivityPermission = true,
			) shouldBe listOf(location, health, specialUse)
		}

		@Test
		fun `offers location then special-use fallback when activity permission is missing`() {
			candidates(
				sdkInt = android14,
				preferLocationType = true,
				hasLocationPermission = true,
				hasActivityPermission = false,
			) shouldBe listOf(location, specialUse)
		}

		@Test
		fun `falls back to health when location permission is missing`() {
			candidates(
				sdkInt = android14,
				preferLocationType = true,
				hasLocationPermission = false,
				hasActivityPermission = true,
			) shouldBe listOf(health, specialUse)
		}
	}

	@Nested
	@DisplayName("Android 14+ ambient session")
	inner class AmbientSession {
		@Test
		fun `prefers health and never crashes when location permission is absent`() {
			candidates(
				sdkInt = android14,
				preferLocationType = false,
				hasLocationPermission = false,
				hasActivityPermission = true,
			) shouldBe listOf(health, specialUse)
		}

		@Test
		fun `prefers health then offers location as fallback when both permissions are held`() {
			candidates(
				sdkInt = android14,
				preferLocationType = false,
				hasLocationPermission = true,
				hasActivityPermission = true,
			) shouldBe listOf(health, location, specialUse)
		}

		@Test
		fun `offers location then special-use fallback when activity permission is missing`() {
			candidates(
				sdkInt = android14,
				preferLocationType = false,
				hasLocationPermission = true,
				hasActivityPermission = false,
			) shouldBe listOf(location, specialUse)
		}
	}

	@Nested
	@DisplayName("Android 14+ with no usable permissions")
	inner class NoPermissions {
		@Test
		fun `falls back to special-use so a permissionless session still runs`() {
			candidates(
				sdkInt = android14,
				preferLocationType = true,
				hasLocationPermission = false,
				hasActivityPermission = false,
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
		preferLocationType: Boolean,
		hasLocationPermission: Boolean,
		hasActivityPermission: Boolean,
	): List<Int> = foregroundServiceTypeCandidates(
		sdkInt = sdkInt,
		preferLocationType = preferLocationType,
		hasLocationPermission = hasLocationPermission,
		hasActivityPermission = hasActivityPermission,
	)
}
