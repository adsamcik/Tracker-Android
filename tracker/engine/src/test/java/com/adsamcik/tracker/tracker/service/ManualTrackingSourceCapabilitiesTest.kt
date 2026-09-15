package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ManualTrackingSourceCapabilitiesTest {
	@ParameterizedTest(name = "fully capable {0} is available")
	@EnumSource(SourceKind::class)
	fun `fully capable source is available`(source: SourceKind) {
		val result = ALL_CAPABILITIES.toManualTrackingSourceCapabilities()

		(source in result.supportedSources) shouldBe true
		(source in result.availableSources) shouldBe true
	}

	@Test
	fun `Activity and Steps require activity recognition only when their provider exists`() {
		val permissionMissing = ALL_CAPABILITIES.copy(
			hasActivityRecognitionPermission = false,
		).toManualTrackingSourceCapabilities()

		permissionMissing.sourcesMissingActivityRecognitionPermission shouldBe setOf(
			SourceKind.ACTIVITY,
			SourceKind.STEPS,
		)

		val unsupported = ALL_CAPABILITIES.copy(
			activityProviderAvailable = false,
			stepCounterAvailable = false,
			hasActivityRecognitionPermission = false,
		).toManualTrackingSourceCapabilities()
		unsupported.sourcesMissingActivityRecognitionPermission shouldBe emptySet()
		(SourceKind.ACTIVITY in unsupported.supportedSources) shouldBe false
		(SourceKind.STEPS in unsupported.supportedSources) shouldBe false
	}

	@Test
	fun `Location allows coarse while Wi-Fi and Cell require precise Location`() {
		val result = ALL_CAPABILITIES.copy(
			hasPreciseLocationPermission = false,
		).toManualTrackingSourceCapabilities()

		(SourceKind.LOCATION in result.availableSources) shouldBe true
		result.sourcesMissingPreciseLocationPermission shouldBe setOf(
			SourceKind.WIFI,
			SourceKind.CELL,
		)
	}

	@Test
	fun `Wi-Fi manual admission follows the platform scan matrix`() {
		val api27 = ALL_CAPABILITIES.copy(
			apiLevel = 27,
			locationHardwareAvailable = false,
			cellHardwareAvailable = false,
			hasAnyLocationPermission = false,
			hasCoarseLocationPermission = false,
			hasPreciseLocationPermission = false,
			hasChangeWifiStatePermission = true,
			locationServicesEnabled = false,
		).toManualTrackingSourceCapabilities()
		(SourceKind.WIFI in api27.availableSources) shouldBe true
		(SourceKind.WIFI in api27.sourcesBlockedByLocationServices) shouldBe false

		val api28 = api28CoarseDevice().toManualTrackingSourceCapabilities()
		(SourceKind.WIFI in api28.availableSources) shouldBe true
		val api28ServicesOff = api28CoarseDevice().copy(
			locationServicesEnabled = false,
		).toManualTrackingSourceCapabilities()
		api28ServicesOff.sourcesBlockedByLocationServices shouldBe setOf(SourceKind.WIFI)

		val api29Coarse = api28CoarseDevice().copy(apiLevel = 29)
			.toManualTrackingSourceCapabilities()
		api29Coarse.sourcesMissingPreciseLocationPermission shouldBe setOf(SourceKind.WIFI)
	}

	@Test
	fun `Location Services blocks only Location Wi-Fi and Cell`() {
		val result = ALL_CAPABILITIES.copy(
			locationServicesEnabled = false,
		).toManualTrackingSourceCapabilities()

		result.sourcesBlockedByLocationServices shouldBe setOf(
			SourceKind.LOCATION,
			SourceKind.WIFI,
			SourceKind.CELL,
		)
		result.availableSources shouldBe setOf(
			SourceKind.ACTIVITY,
			SourceKind.STEPS,
			SourceKind.PRESSURE,
		)
	}

	@Test
	fun `Cell reports both permission prerequisites for deterministic re-evaluation`() {
		val result = ALL_CAPABILITIES.copy(
			wifiHardwareAvailable = false,
			hasPreciseLocationPermission = false,
			hasReadPhoneStatePermission = false,
		).toManualTrackingSourceCapabilities()

		result.sourcesMissingPreciseLocationPermission shouldBe setOf(SourceKind.CELL)
		result.sourcesMissingReadPhoneStatePermission shouldBe setOf(SourceKind.CELL)
		(SourceKind.CELL in result.availableSources) shouldBe false
	}

	private companion object {
		fun api28CoarseDevice() = ALL_CAPABILITIES.copy(
			apiLevel = 28,
			locationHardwareAvailable = false,
			cellHardwareAvailable = false,
			hasAnyLocationPermission = true,
			hasCoarseLocationPermission = true,
			hasPreciseLocationPermission = false,
			hasChangeWifiStatePermission = false,
			locationServicesEnabled = true,
		)

		val ALL_CAPABILITIES = ManualTrackingDeviceCapabilities(
			apiLevel = 29,
			locationHardwareAvailable = true,
			activityProviderAvailable = true,
			stepCounterAvailable = true,
			pressureSensorAvailable = true,
			wifiHardwareAvailable = true,
			cellHardwareAvailable = true,
			hasAnyLocationPermission = true,
			hasCoarseLocationPermission = true,
			hasPreciseLocationPermission = true,
			hasChangeWifiStatePermission = true,
			hasActivityRecognitionPermission = true,
			hasReadPhoneStatePermission = true,
			locationServicesEnabled = true,
		)
	}
}
