package com.adsamcik.tracker.tracker.di

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
import java.io.File
import javax.inject.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SourcePipelineModuleTest {
	private val projectRoot: File by lazy {
		generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
			.first { it.resolve("settings.gradle.kts").isFile }
	}

	@Test
	fun `runtime set uniquely binds shared radio owners and keeps raw deletion barriers`() {
		val mainRoot = projectRoot.resolve("tracker/engine/src/main")
		val runtimeBindings = mainRoot.walkTopDown()
			.filter { it.isFile && it.extension == "kt" }
			.flatMap { source ->
				RUNTIME_BINDING.findAll(source.readText()).map { match ->
					"${match.groupValues[1]}:${match.groupValues[2]}"
				}
			}
			.sorted()
			.toList()

		assertEquals(
			listOf(
				"provideActivitySourceRuntime:ActivitySourceRuntime",
				"provideCellSourceRuntime:SharedCellSourceController",
				"provideLocationSourceRuntime:LocationSourceRuntime",
				"providePressureSourceRuntime:PressureSourceRuntime",
				"provideStepSourceRuntime:SharedStepSourceController",
				"provideWifiSourceRuntime:SharedWifiSourceController",
			),
			runtimeBindings,
		)

		val wifiDeletion = source(
			"source/wifi/WifiCaptureConsentRevocationDeletionCommand.kt",
		)
		val cellDeletion = source(
			"source/cell/CellCaptureConsentRevocationDeletionCommand.kt",
		)
		assertTrue("private val runtime: WifiSourceRuntime" in wifiDeletion)
		assertTrue("private val runtime: CellSourceRuntime" in cellDeletion)
		assertTrue(
			Regex("""@Singleton\s+class WifiSourceRuntime @Inject internal constructor\(""")
				.containsMatchIn(source("source/runtime/WifiSourceRuntime.kt")),
		)
		assertTrue(
			Regex("""@Singleton\s+class CellSourceRuntime @Inject internal constructor\(""")
				.containsMatchIn(source("source/runtime/CellSourceRuntime.kt")),
		)
		assertTrue(
			Regex("""@Singleton\s+class SharedWifiSourceController @Inject constructor\(""")
				.containsMatchIn(source("source/runtime/SharedWifiSourceController.kt")),
		)
		assertTrue(
			Regex("""@Singleton\s+class SharedCellSourceController @Inject constructor\(""")
				.containsMatchIn(source("source/runtime/SharedCellSourceController.kt")),
		)

		val purposeRuntime = source("source/runtime/TrackingPurposePublicationRuntime.kt")
		assertTrue(
			Regex(
				"""@Inject\s+constructor\([\s\S]*?""" +
					"""ambientWifiDemandReconcilerProvider: Provider<AmbientWifiDemandReconciler>,""" +
					"""\s+ambientCellDemandReconcilerProvider: Provider<AmbientCellDemandReconciler>,""",
			).containsMatchIn(purposeRuntime),
		)
	}

	@Test
	fun `constructing and resolving radio entries performs no provider backend or demand work`() =
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
			)
			val cellRuntime = CellSourceRuntime(
				backgroundScope,
				registrations,
				cellBackend,
				deviceState,
				wakeups,
			)
			val broker = mockk<SourceBroker>()
			val sinkFactory = mockk<DurableSourceEventSinkFactory>()
			val unboundSink = mockk<SourceEventSink>()
			every { sinkFactory.unbound } returns unboundSink
			val wifiController = SharedWifiSourceController(wifiRuntime, broker, sinkFactory)
			val cellController = SharedCellSourceController(cellRuntime, broker, sinkFactory)

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
				SourcePipelineModule.provideWifiSourceRuntime(wifiController),
				SourcePipelineModule.provideCellSourceRuntime(cellController),
			)
			val registry = SourceRuntimeRegistry(runtimeSet)

			assertEquals(1, runtimeSet.count { it.source == SourceKind.WIFI })
			assertEquals(1, runtimeSet.count { it.source == SourceKind.CELL })
			assertSame(wifiController, registry.runtime(SourceKind.WIFI))
			assertSame(cellController, registry.runtime(SourceKind.CELL))
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

	private fun source(relativePath: String): String = projectRoot.resolve(
		"tracker/engine/src/main/java/com/adsamcik/tracker/tracker/$relativePath",
	).readText()

	private companion object {
		val RUNTIME_BINDING = Regex(
			"""@Provides\s+@IntoSet\s+fun (provide[A-Za-z0-9]+SourceRuntime)\(\s*""" +
				"""runtime: ([A-Za-z0-9]+),?\s*\): ClaimedSourceRuntime<out SourcePlan> = runtime""",
		)
	}
}
