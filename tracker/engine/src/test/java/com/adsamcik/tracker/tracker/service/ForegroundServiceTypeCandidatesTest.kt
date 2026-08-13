package com.adsamcik.tracker.tracker.service

import android.content.pm.ServiceInfo
import android.os.Build
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.api.TrackerForegroundServiceRequirements
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("foregroundServiceTypeCandidates")
class ForegroundServiceTypeCandidatesTest {

	private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
	private val health = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
	private val specialUse = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

	@Nested
	@DisplayName("source combinations")
	inner class SourceCombinations {
		@Test
		fun `API 34 and 37 map every non-empty source combination to the minimum type set`() {
			val combinations = listOf(
				requirements(location = true) to location,
				requirements(health = true) to health,
				requirements(signal = true) to specialUse,
				requirements(location = true, health = true) to (location or health),
				requirements(location = true, signal = true) to location,
				requirements(health = true, signal = true) to health,
				requirements(location = true, health = true, signal = true) to (location or health),
			)

			listOf(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, 37).forEach { sdkInt ->
				combinations.forEach { (requirements, expectedType) ->
					candidates(sdkInt, requirements) shouldBe listOf(expectedType)
				}
			}
		}

		@Test
		fun `pre-29 start is untyped and API 29 through 33 only type location`() {
			candidates(28, requirements(health = true)) shouldBe listOf(null)
			candidates(Build.VERSION_CODES.TIRAMISU, requirements(location = true)) shouldBe
				listOf(location)
			candidates(Build.VERSION_CODES.TIRAMISU, requirements(health = true)) shouldBe
				listOf(null)
			candidates(Build.VERSION_CODES.TIRAMISU, requirements(signal = true)) shouldBe
				listOf(null)
		}
	}

	@Nested
	@DisplayName("permission revocation")
	inner class PermissionRevocation {
		@Test
		fun `location requirement never falls back after location permission is revoked`() {
			candidates(
				sdkInt = 37,
				requirements = requirements(location = true, health = true, signal = true),
				hasLocationPermission = false,
			) shouldBe emptyList()
		}

		@Test
		fun `health-only mode stops after activity permission is revoked`() {
			candidates(
				sdkInt = 37,
				requirements = requirements(health = true),
				hasActivityPermission = false,
			) shouldBe emptyList()
		}

		@Test
		fun `health plus signals narrows to special-use after activity permission is revoked`() {
			candidates(
				sdkInt = 37,
				requirements = requirements(health = true, signal = true),
				hasActivityPermission = false,
			) shouldBe listOf(specialUse)
		}
	}

	@Nested
	@DisplayName("start restart and transitions")
	inner class StartRestartAndTransitions {
		@Test
		fun `API 34 and 37 fresh start and restart use the same exact declaration`() {
			listOf(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, 37).forEach { sdkInt ->
				listOf("start", "restart").forEach {
					candidates(
						sdkInt,
						requirements(location = true, health = true, signal = true),
					) shouldBe listOf(location or health)
				}
			}
		}

		@Test
		fun `type transitions add and remove only source-backed bits`() {
			val sequence = listOf(
				requirements(signal = true),
				requirements(health = true, signal = true),
				requirements(location = true, health = true, signal = true),
				requirements(location = true, signal = true),
				requirements(signal = true),
			)

			sequence.map { candidates(37, it).single() } shouldBe listOf(
				specialUse,
				health,
				location or health,
				location,
				specialUse,
			)
		}
	}

	@Nested
	@DisplayName("activity watcher")
	inner class ActivityWatcher {
		@Test
		fun `activity monitoring uses health on API 34 and 37`() {
			listOf(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, 37).forEach { sdkInt ->
				activityWatcherForegroundServiceTypeCandidates(
					sdkInt = sdkInt,
					hasActivityPermission = true,
				) shouldBe listOf(health)
			}
		}

		@Test
		fun `activity monitoring cannot promote after permission revocation`() {
			activityWatcherForegroundServiceTypeCandidates(
				sdkInt = 37,
				hasActivityPermission = false,
			) shouldBe emptyList()
		}

		@Test
		fun `activity monitoring remains untyped before API 34`() {
			activityWatcherForegroundServiceTypeCandidates(
				sdkInt = Build.VERSION_CODES.TIRAMISU,
				hasActivityPermission = true,
			) shouldBe listOf(null)
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

	private fun requirements(
		location: Boolean = false,
		health: Boolean = false,
		signal: Boolean = false,
	) = TrackerForegroundServiceRequirements(location, health, signal)

	private fun candidates(
		sdkInt: Int,
		requirements: TrackerForegroundServiceRequirements,
		hasLocationPermission: Boolean = true,
		hasActivityPermission: Boolean = true,
	): List<Int?> = foregroundServiceTypeCandidates(
		sdkInt = sdkInt,
		requirements = requirements,
		hasLocationPermission = hasLocationPermission,
		hasActivityPermission = hasActivityPermission,
	)
}
