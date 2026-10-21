package com.adsamcik.tracker.tracker.di

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.tracker.source.catalog.SourceAvailabilityRequest
import com.adsamcik.tracker.tracker.source.catalog.SourceAvailabilityTier
import com.adsamcik.tracker.tracker.source.catalog.SourceCatalogAvailability
import com.adsamcik.tracker.tracker.source.catalog.SourceImplementationCatalog
import com.adsamcik.tracker.tracker.source.catalog.SourceProviderAvailability
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.runtime.ActivitySourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.ClaimedSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.SharedPressureSourceController
import com.adsamcik.tracker.tracker.source.runtime.SharedLocationSourceController
import com.adsamcik.tracker.tracker.source.runtime.SharedStepSourceController
import io.mockk.coEvery
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReporter
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellDemandReconciler
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiDemandReconciler
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.AndroidCellSourceBackend
import com.adsamcik.tracker.tracker.source.runtime.AndroidConnectivityDeviceStateProvider
import com.adsamcik.tracker.tracker.source.runtime.AndroidWifiSourceBackend
import com.adsamcik.tracker.tracker.source.runtime.CellDeviceState
import com.adsamcik.tracker.tracker.source.runtime.CellSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.CoalescingSourceWakeupScheduler
import com.adsamcik.tracker.tracker.source.runtime.DefaultTrackingPurposePublicationRuntime
import com.adsamcik.tracker.tracker.source.runtime.SerializedTrackingPurposeLeaseIssuer
import com.adsamcik.tracker.tracker.source.runtime.SharedCellSourceController
import com.adsamcik.tracker.tracker.source.runtime.SharedWifiSourceController
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import com.adsamcik.tracker.tracker.source.runtime.SourceEventSink
import com.adsamcik.tracker.tracker.source.runtime.SourceRegistrationRepository
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeRegistry
import com.adsamcik.tracker.tracker.source.runtime.TrackingPurposeExecutionRevisionRegistry
import com.adsamcik.tracker.tracker.source.runtime.WifiDeviceState
import com.adsamcik.tracker.tracker.source.runtime.WifiSourceRuntime
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import javax.inject.Provider
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class SourcePipelineModuleTest {
	// Keep one six-source graph assembly and its shared-owner/dormancy assertions together.
	@Suppress("LongMethod")
	@Test
	fun `six provider bindings preserve shared owners without radio backend or demand work`() =
		runTest {
			val registrations = mockk<SourceRegistrationRepository>()
			val wifiBackend = mockk<AndroidWifiSourceBackend>()
			val cellBackend = mockk<AndroidCellSourceBackend>()
			val deviceState = mockk<AndroidConnectivityDeviceStateProvider>()
			val wakeups = mockk<CoalescingSourceWakeupScheduler>()
			every { deviceState.wifi() } returns WifiDeviceState(
				wifiFeatureAvailable = true,
				fineLocationPermission = true,
				locationServicesEnabled = true,
				deviceIdle = false,
			)
			every { deviceState.cell() } returns CellDeviceState(
				radioFeatureAvailable = true,
				fineLocationPermission = true,
				readPhoneStatePermission = true,
				refreshApiAvailable = true,
			)

			val wifiRuntime = WifiSourceRuntime(
				backgroundScope,
				registrations,
				wifiBackend,
				deviceState,
				wakeups,
				com.adsamcik.tracker.tracker.source.runtime.testRadioActiveRequestAllowance(),
			)
			val cellRuntime = CellSourceRuntime(
				backgroundScope,
				registrations,
				cellBackend,
				deviceState,
				wakeups,
				com.adsamcik.tracker.tracker.source.runtime.testRadioActiveRequestAllowance(),
			)
			val broker = mockk<SourceBroker>()
			val sinkFactory = mockk<DurableSourceEventSinkFactory>()
			val unboundSink = mockk<SourceEventSink>()
			every { sinkFactory.unbound } returns unboundSink
			val wifiController = SharedWifiSourceController(wifiRuntime, broker, sinkFactory)
			val cellController = SharedCellSourceController(cellRuntime, broker, sinkFactory)
			val activity = mockk<ActivitySourceRuntime>()
			val steps = mockk<SharedStepSourceController>()
			val pressure = mockk<SharedPressureSourceController>()
			val location = mockk<SharedLocationSourceController>()
			every { activity.source } returns SourceKind.ACTIVITY
			every { steps.source } returns SourceKind.STEPS
			every { pressure.source } returns SourceKind.PRESSURE
			every { location.source } returns SourceKind.LOCATION

			var wifiReconcilerRequested = false
			var cellReconcilerRequested = false
			DefaultTrackingPurposePublicationRuntime(
				leaseIssuer = mockk<SerializedTrackingPurposeLeaseIssuer>(),
				reporter = mockk<TrackingPurposeAvailabilityReporter>(),
				executionRevisionRegistry = mockk<TrackingPurposeExecutionRevisionRegistry>(),
				retentionAuthorityProducer = mockk<RetentionAuthorityProducer>(),
				ambientWifiDemandReconcilerProvider = Provider<AmbientWifiDemandReconciler> {
					wifiReconcilerRequested = true
					mockk()
				},
				ambientCellDemandReconcilerProvider = Provider<AmbientCellDemandReconciler> {
					cellReconcilerRequested = true
					mockk()
				},
				ownerCallbackScope = backgroundScope,
			)

			val runtimeSet = setOf(
				SourcePipelineModule.provideActivitySourceRuntime(activity),
				SourcePipelineModule.provideStepSourceRuntime(steps),
				SourcePipelineModule.providePressureSourceRuntime(pressure),
				SourcePipelineModule.provideLocationSourceRuntime(location),
				SourcePipelineModule.provideWifiSourceRuntime(wifiController),
				SourcePipelineModule.provideCellSourceRuntime(cellController),
			)
			val registry = SourceRuntimeRegistry(runtimeSet)

			assertEquals(SourceKind.entries.toSet(), registry.registeredSources())
			val expectedOwners = mapOf(
				SourceKind.ACTIVITY to activity, SourceKind.STEPS to steps,
				SourceKind.PRESSURE to pressure, SourceKind.LOCATION to location,
				SourceKind.WIFI to wifiController, SourceKind.CELL to cellController,
			)
			expectedOwners.forEach { (source, owner) ->
				assertEquals(1, runtimeSet.count { it.source == source })
				assertSame(owner, registry.runtime(source))
			}
			assertFalse(wifiReconcilerRequested)
			assertFalse(cellReconcilerRequested)
			coVerify(exactly = 0) { broker.authorizationDemands(any()) }
			verify(exactly = 1) { deviceState.wifi() }
			verify(exactly = 1) { deviceState.cell() }
			verify(exactly = 2) { sinkFactory.unbound }
			confirmVerified(
				registrations,
				wifiBackend,
				cellBackend,
				deviceState,
				wakeups,
				broker,
				sinkFactory,
			)
		}

	@Test
	fun `catalog readiness uses no lazy physical owner`() = runTest {
		val catalog = mockk<SourceImplementationCatalog>()
		val plan = WifiPlan(1L, WifiMode.CACHED_ONLY, 60_000L, 60_000L, 60_000L, RetryBackoff(0L, 0L))
		val request = SourceAvailabilityRequest(SourceKind.WIFI, TrackingPurpose.SESSION_CAPTURE,
			plan, SourceAvailabilityTier.MANUAL_FOREGROUND_START)
		val availability = SourceCatalogAvailability.Executable(SourceProviderAvailability.Available())
		coEvery { catalog.availability(request) } returns availability
		val owners = SourceKind.entries.associateWith { kind ->
			mockk<ClaimedSourceRuntime<out SourcePlan>>().also { owner -> every { owner.source } returns kind }
		}
		val resolved = mutableListOf<SourceKind>()
		val providers = owners.mapValues { (kind, owner) ->
			Provider<ClaimedSourceRuntime<out SourcePlan>> { resolved += kind; owner }
		}
		val registry = SourceRuntimeRegistry(providers, catalog, requireAllSources = true)

		assertSame(catalog, SourcePipelineModule.provideSourceAcquisitionPlanFactory(catalog))
		assertEquals(SourceKind.entries.toSet(), registry.registeredSources())
		assertSame(availability, registry.availability(request))
		assertEquals(emptyList(), resolved)
		coVerify(exactly = 1) { catalog.availability(request) }

		assertSame(owners.getValue(SourceKind.LOCATION), registry.runtime(SourceKind.LOCATION))
		assertEquals(listOf(SourceKind.LOCATION), resolved)
	}
}
