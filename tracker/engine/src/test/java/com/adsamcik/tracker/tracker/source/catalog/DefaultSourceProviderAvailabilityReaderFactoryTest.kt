package com.adsamcik.tracker.tracker.source.catalog

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.tracker.source.coordinator.SemanticAcquisitionPlanFactory
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultSourceProviderAvailabilityReaderFactoryTest {
	@Test
	fun `real availability factory assembles every catalog binding without probing providers`() {
		val plans = SemanticAcquisitionPlanFactory()
		val catalog = DefaultSourceImplementationCatalog(
			acquisitionRevisionFactory = SourceAcquisitionRevisionFactory(plans::create),
			availabilityReaders = factory(),
			productFactories = SourceProductFactories(),
		)

		catalog.bindings.size shouldBe TrackingSource.entries.size * TrackingPurpose.entries.size
		for (source in TrackingSource.entries) {
			for (purpose in TrackingPurpose.entries) {
				(catalog.binding(source, purpose) is SourcePurposeBinding.Executable) shouldBe source.supports(purpose)
			}
		}
	}

	@Test
	fun `every Activity purpose uses the same current permission and provider availability`() = runTest {
		var state = ActivityRecognitionSourceState(permissionGranted = false, providerAvailable = true)
		val factory = factory(ActivityRecognitionSourceStateProvider { state })
		val readers = TrackingPurpose.entries.associateWith { factory.create(TrackingSource.ACTIVITY, it) }
		val cases = listOf(
			state to SourceProviderAvailability.PermissionRequired(
				setOf(SourceProviderPermission.RuntimePermission(SourceKind.ACTIVITY)),
			),
			ActivityRecognitionSourceState(permissionGranted = true, providerAvailable = false) to
				SourceProviderAvailability.ProviderUnavailable(
					SourceProviderAvailabilityEvidence.Runtime(
						SourceKind.ACTIVITY, setOf(SourceDegradedReason.PROVIDER_UNAVAILABLE),
					),
				),
			ActivityRecognitionSourceState(permissionGranted = true, providerAvailable = true) to
				SourceProviderAvailability.Available(),
		)

		for ((current, expected) in cases) {
			state = current
			for ((purpose, reader) in readers) {
				reader.read(activityRequest(purpose)) shouldBe expected
			}
		}
	}

	private fun factory(
		activity: ActivityRecognitionSourceStateProvider = mockk(),
	) = DefaultSourceProviderAvailabilityReaderFactory(
		ambientStepsCapabilityResolver = mockk(),
		locationDeviceStateProvider = mockk(),
		locationPrerequisiteEvaluator = mockk(),
		activityRecognitionSourceStateProvider = activity,
		sensorSourceStateProvider = mockk(),
		connectivityDeviceStateProvider = mockk(),
	)

	private fun activityRequest(purpose: TrackingPurpose) = SourceAvailabilityRequest(
		source = SourceKind.ACTIVITY,
		purpose = purpose,
		plan = ActivityPlan(1L, ActivityMode.CONTINUOUS_RECOGNITION, 1_000L, 60, emptySet()),
		tier = if (purpose == TrackingPurpose.AMBIENT_PRODUCT) {
			SourceAvailabilityTier.AMBIENT_REGISTRATION
		} else {
			SourceAvailabilityTier.MANUAL_FOREGROUND_START
		},
	)
}
