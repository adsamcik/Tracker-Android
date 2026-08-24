package com.adsamcik.tracker.tracker.service

import android.content.pm.ServiceInfo
import android.os.Build
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.model.SourceKind
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
	private val android10 = Build.VERSION_CODES.Q
	private val android9 = Build.VERSION_CODES.P

	@Test
	fun `descriptor action and type validation share one sub-five-second promotion budget`() {
		(PRE_FOREGROUND_START_BUDGET_MILLIS in 1L until 5_000L) shouldBe true
	}

	@Test
	fun `Android 14 maps every nonempty accepted source combination to the exact union`() {
		val sources = SourceKind.entries
		for (mask in 1 until (1 shl sources.size)) {
			val accepted = sources.filterIndexedTo(mutableSetOf()) { index, _ ->
				mask and (1 shl index) != 0
			}
			var expected = 0
			if (accepted.any {
					it == SourceKind.LOCATION || it == SourceKind.WIFI || it == SourceKind.CELL
				}) {
				expected = expected or location
			}
			if (accepted.any { it == SourceKind.ACTIVITY || it == SourceKind.STEPS }) {
				expected = expected or health
			}
			if (accepted.any {
					it == SourceKind.PRESSURE || it == SourceKind.WIFI || it == SourceKind.CELL
				}) {
				expected = expected or specialUse
			}

			foregroundServiceTypeCandidates(android14, accepted) shouldBe listOf(expected)
		}
	}

	@Test
	fun `empty accepted demand cannot manufacture a neutral foreground type`() {
		foregroundServiceTypeCandidates(android14, emptySet()) shouldBe emptyList()
		foregroundServiceTypeCandidates(android13, emptySet()) shouldBe emptyList()
		foregroundServiceTypeMask(android14, emptySet()) shouldBe null
	}

	@Test
	fun `prepared source masks reject empty unknown and negative values`() {
		sourceKindsFromMask(0L) shouldBe emptySet()
		sourceKindsFromMask(-1L) shouldBe null
		sourceKindsFromMask(1L shl SourceKind.entries.size) shouldBe null
		SourceKind.entries.forEach { source ->
			sourceKindsFromMask(sourceMask(setOf(source))) shouldBe setOf(source)
		}
	}

	@Test
	fun `Android 10 through 13 type every location protected source as location`() {
		foregroundServiceTypeCandidates(android13, setOf(SourceKind.LOCATION, SourceKind.STEPS)) shouldBe
			listOf(location)
		foregroundServiceTypeCandidates(android13, setOf(SourceKind.WIFI)) shouldBe listOf(location)
		foregroundServiceTypeCandidates(android10, setOf(SourceKind.CELL, SourceKind.PRESSURE)) shouldBe
			listOf(location)
		foregroundServiceTypeCandidates(android13, setOf(SourceKind.STEPS, SourceKind.PRESSURE)) shouldBe
			listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
		foregroundServiceTypeMask(android13, setOf(SourceKind.STEPS)) shouldBe 0L
	}

	@Test
	fun `before Android 10 no runtime foreground service type is supplied`() {
		foregroundServiceTypeCandidates(android9, setOf(SourceKind.LOCATION)) shouldBe listOf(null)
		foregroundServiceTypeCandidates(android9, setOf(SourceKind.WIFI, SourceKind.CELL)) shouldBe listOf(null)
	}

	@Test
	fun `Android 14 Wi-Fi and cell retain special use while adding location authorization`() {
		foregroundServiceTypeCandidates(android14, setOf(SourceKind.WIFI)) shouldBe
			listOf(location or specialUse)
		foregroundServiceTypeCandidates(android14, setOf(SourceKind.CELL, SourceKind.PRESSURE)) shouldBe
			listOf(location or specialUse)
	}

	@Test
	fun `unavailable Location is degraded without removing an accepted Steps source`() {
		val accepted = acceptedForegroundSources(
			requestedSources = setOf(SourceKind.LOCATION, SourceKind.STEPS),
			capabilities = ForegroundSourceCapabilities(
				sdkInt = android14,
				startOrigin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				hasForegroundLocationPermission = true,
				hasBackgroundLocationPermission = false,
				locationHardwareAvailable = true,
				activity = false,
				steps = true,
				pressure = false,
				wifi = false,
				cell = false,
			),
		)

		accepted shouldBe setOf(SourceKind.STEPS)
		foregroundServiceTypeCandidates(android14, accepted) shouldBe listOf(health)
	}

	@Nested
	@DisplayName("Location start origin")
	inner class LocationStartOrigin {
		@Test
		fun `manual foreground start may use foreground Location permission`() {
			isLocationSourceLegalForStartOrigin(
				sdkInt = android14,
				startOrigin = SessionStartOrigin.MANUAL_FOREGROUND_START,
				hasForegroundLocationPermission = true,
				hasBackgroundLocationPermission = false,
			) shouldBe true
		}

		@Test
		fun `API 34 automatic background start requires background Location`() {
			isLocationSourceLegalForStartOrigin(
				sdkInt = android14,
				startOrigin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				hasForegroundLocationPermission = true,
				hasBackgroundLocationPermission = false,
			) shouldBe false
			isLocationSourceLegalForStartOrigin(
				sdkInt = android14,
				startOrigin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				hasForegroundLocationPermission = true,
				hasBackgroundLocationPermission = true,
			) shouldBe true
		}

		@Test
		fun `API 34 recovery cannot widen to Location without background capability`() {
			isLocationSourceLegalForStartOrigin(
				sdkInt = android14,
				startOrigin = SessionStartOrigin.RECOVERY,
				hasForegroundLocationPermission = true,
				hasBackgroundLocationPermission = false,
			) shouldBe false
		}

		@Test
		fun `missing foreground Location permission always rejects Location`() {
			SessionStartOrigin.entries.forEach { origin ->
				isLocationSourceLegalForStartOrigin(
					sdkInt = android14,
					startOrigin = origin,
					hasForegroundLocationPermission = false,
					hasBackgroundLocationPermission = true,
				) shouldBe false
			}
		}
	}

	@Nested
	@DisplayName("precise-location-protected signal start origin")
	inner class SignalStartOrigin {
		@Test
		fun `manual foreground start may collect Wi-Fi and cell with precise permission`() {
			isPreciseLocationProtectedSignalSourceLegalForStartOrigin(
				sdkInt = android14,
				startOrigin = SessionStartOrigin.MANUAL_FOREGROUND_START,
				hasPreciseLocationPermission = true,
				hasBackgroundLocationPermission = false,
			) shouldBe true
		}

		@Test
		fun `automatic recovery and reconciliation require background location on Android 10 plus`() {
			SessionStartOrigin.entries
				.filter { it != SessionStartOrigin.MANUAL_FOREGROUND_START }
				.forEach { origin ->
					isPreciseLocationProtectedSignalSourceLegalForStartOrigin(
						sdkInt = android14,
						startOrigin = origin,
						hasPreciseLocationPermission = true,
						hasBackgroundLocationPermission = false,
					) shouldBe false
				}
		}

		@Test
		fun `accepted source filtering drops illegal Wi-Fi and cell but preserves pressure`() {
			acceptedForegroundSources(
				requestedSources = setOf(SourceKind.WIFI, SourceKind.CELL, SourceKind.PRESSURE),
				capabilities = ForegroundSourceCapabilities(
					sdkInt = android14,
					startOrigin = SessionStartOrigin.RECOVERY,
					hasForegroundLocationPermission = true,
					hasBackgroundLocationPermission = false,
					locationHardwareAvailable = true,
					activity = false,
					steps = false,
					pressure = true,
					wifi = true,
					cell = true,
				),
			) shouldBe setOf(SourceKind.PRESSURE)
		}
	}

	@Nested
	@DisplayName("initial policy tier")
	inner class InitialPolicyTier {
		@Test
		fun `user-initiated sessions start in precision`() {
			resolveInitialPolicyTier(true, false, true) shouldBe PolicyTier.PRECISION
		}

		@Test
		fun `explicit ambient sessions stay ambient even when location is enabled`() {
			resolveInitialPolicyTier(false, true, true) shouldBe PolicyTier.AMBIENT
		}

		@Test
		fun `automatic session uses active only when Location was accepted`() {
			resolveInitialPolicyTier(false, false, true) shouldBe PolicyTier.ACTIVE
			resolveInitialPolicyTier(false, false, false) shouldBe PolicyTier.AMBIENT
		}
	}
}
