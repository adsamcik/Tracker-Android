package com.adsamcik.tracker.tracker.service

import android.Manifest
import android.app.Application
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.PreparedTrackingStartToken
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommand
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.AmbientLocationPurposeOwner
import com.adsamcik.tracker.tracker.source.runtime.AmbientSensorPurposeOwner
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import javax.inject.Provider
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationForegroundPermissionContinuityTest {
	@Test
	fun `acknowledged Location foreground service keeps foreground only access across permission refresh`() {
		val service = subject()
		val context = ApplicationProvider.getApplicationContext<Application>()
		Shadows.shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
		Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
		writeField(service, "foregroundStarted", true)
		writeField(service, "foregroundIsStartupShell", false)
		writeField(service, "activeForegroundRequirements", foregroundRequirements())

		accepted(service) shouldBe setOf(SourceKind.LOCATION)
		writeField(service, "foregroundIsStartupShell", true)
		accepted(service) shouldBe emptySet<SourceKind>()
		writeField(service, "foregroundIsStartupShell", false)
		writeField(service, "foregroundStarted", false)
		accepted(service) shouldBe emptySet<SourceKind>()
	}

	@Test
	fun `revoked foreground permission retires exact Location owner before any host release`() = runTest {
		val service = subject()
		val host = service.backgroundLocationHost
		val context = ApplicationProvider.getApplicationContext<Application>()
		Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
			Manifest.permission.ACCESS_COARSE_LOCATION)
		val owner = mockk<AmbientLocationPurposeOwner>()
		service.ambientLocationPurposeOwner = Provider { owner }
		val locationIdentity = AmbientReconciliationIdentity(AmbientTrackingSource.LOCATION, 2, 3, 4, 5,
			"owner", 1, null, "retention", 1)
		val pressureIdentity = locationIdentity.copy(source = AmbientTrackingSource.PRESSURE)
		val hosts = mapOf(AmbientTrackingSource.LOCATION to BackgroundLocationHostResult.Active(locationIdentity, mockk()),
			AmbientTrackingSource.PRESSURE to BackgroundLocationHostResult.Active(pressureIdentity, mockk()))
		every { host.hostedSources } returns setOf(SourceKind.LOCATION, SourceKind.PRESSURE)
		every { host.leases } returns MutableStateFlow(hosts)
		coEvery { host.invalidLeaseIdentities() } returns emptyList()
		coEvery { owner.retire(AmbientReconciliationLease(locationIdentity), reconcileAfterRetirement = true) } returns false
		coEvery { service["reconcileLocationMotionScope"]() } returns Unit
		every { service.controller.isServiceRunning } returns false

		invokeServiceSuspend<Unit>(service, "onRuntimePermissionChanged")

		coVerify(exactly = 1) { owner.retire(AmbientReconciliationLease(locationIdentity), reconcileAfterRetirement = true) }
		coVerify(exactly = 0) { host.release(any()) }
	}

	@Test
	fun `invalid required hosts retire independently while optional radio hosting releases only its lease`() = runTest {
		val service = subject()
		val host = service.backgroundLocationHost
		val location = AmbientReconciliationIdentity(AmbientTrackingSource.LOCATION, 2, 3, 4, 5,
			"owner", 1, null, "retention", 1)
		val pressure = location.copy(source = AmbientTrackingSource.PRESSURE)
		val wifi = location.copy(source = AmbientTrackingSource.WIFI)
		val locationOwner = mockk<AmbientLocationPurposeOwner>()
		val sensorOwner = mockk<AmbientSensorPurposeOwner>()
		service.ambientLocationPurposeOwner = Provider { locationOwner }
		service.ambientSensorPurposeOwner = Provider { sensorOwner }
		every { host.hostedSources } returns
			setOf(SourceKind.LOCATION, SourceKind.PRESSURE, SourceKind.WIFI)
		every { host.leases } returns MutableStateFlow(mapOf(
			AmbientTrackingSource.LOCATION to BackgroundLocationHostResult.Active(location, mockk()),
			AmbientTrackingSource.PRESSURE to BackgroundLocationHostResult.Active(pressure, mockk()),
			AmbientTrackingSource.WIFI to BackgroundLocationHostResult.Active(wifi, mockk())))
		coEvery { host.invalidLeaseIdentities() } returns listOf(location, pressure, wifi)
		every { host.hasActiveSources } returns true
		coEvery { locationOwner.retire(AmbientReconciliationLease(location), reconcileAfterRetirement = true) } returns false
		coEvery { sensorOwner.retire(AmbientTrackingSource.PRESSURE, AmbientReconciliationLease(pressure),
			reconcileAfterRetirement = true) } returns true
		coEvery { host.release(wifi) } returns true

		invokeServiceSuspend<Boolean>(service, "retireInvalidBackgroundHosts") shouldBe false

		coVerify(exactly = 1) { locationOwner.retire(AmbientReconciliationLease(location), reconcileAfterRetirement = true) }
		coVerify(exactly = 1) { sensorOwner.retire(AmbientTrackingSource.PRESSURE, AmbientReconciliationLease(pressure),
			reconcileAfterRetirement = true) }
		coVerify(exactly = 1) { host.release(wifi) }
		coVerify(exactly = 0) { host.release(location) }
		coVerify(exactly = 0) { host.release(pressure) }
	}

	@Test
	fun `after End pending invalid Location cleanup cannot retire healthy Pressure on foreground rejection`() = runTest {
		val service = subject()
		val host = service.backgroundLocationHost
		Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).denyPermissions(
			Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
		val location = AmbientReconciliationIdentity(AmbientTrackingSource.LOCATION, 2, 3, 4, 5,
			"owner", 1, null, "retention", 1)
		val pressure = location.copy(source = AmbientTrackingSource.PRESSURE)
		val leases = mapOf(AmbientTrackingSource.LOCATION to BackgroundLocationHostResult.Active(location, mockk()),
			AmbientTrackingSource.PRESSURE to BackgroundLocationHostResult.Active(pressure, mockk()))
		val locationOwner = mockk<AmbientLocationPurposeOwner>()
		val sensorOwner = mockk<AmbientSensorPurposeOwner>()
		service.ambientLocationPurposeOwner = Provider { locationOwner }
		service.ambientSensorPurposeOwner = Provider { sensorOwner }
		every { host.leases } returns MutableStateFlow(leases)
		every { host.hostedSources } returns setOf(SourceKind.LOCATION, SourceKind.PRESSURE)
		every { host.hasActiveSources } returns true
		coEvery { host.invalidLeaseIdentities() } returns emptyList()
		coEvery { locationOwner.retire(AmbientReconciliationLease(location), reconcileAfterRetirement = true) } returns false
		writeField(service, "foregroundStarted", true)
		writeField(service, "foregroundIsStartupShell", false)
		writeField(service, "activeForegroundRequirements",
			foregroundRequirements(setOf(SourceKind.LOCATION, SourceKind.PRESSURE)))
		every { promoteBackground(service, setOf(SourceKind.LOCATION, SourceKind.PRESSURE)) } returns false
		coEvery { service["reconcileLocationMotionScope"]() } returns Unit
		coEvery { service["refreshBackgroundCollectionPlans"]() } returns Unit
		every { service["observeCollectionMotionPolicy"]() } returns Unit

		invokeServiceSuspend<Unit>(service, "finishBackgroundHostHandoff")

		host.leases.value shouldBe leases
		coVerify(exactly = 1) { locationOwner.retire(AmbientReconciliationLease(location), reconcileAfterRetirement = true) }
		coVerify(exactly = 0) { sensorOwner.retire(any(), any(), reconcileAfterRetirement = true) }
		coVerify(exactly = 0) { host.release(any()) }
		verify(exactly = 0) { promoteBackground(service, setOf(SourceKind.LOCATION, SourceKind.PRESSURE)) }
		verify(exactly = 0) { service.stopForeground(any<Int>()) }
		verify(exactly = 0) { service.stopSelf() }
		verify(exactly = 0) { service.stopSelfResult(any()) }
		coVerify(exactly = 1) { service["refreshBackgroundCollectionPlans"]() }
		verify(exactly = 1) { service["observeCollectionMotionPolicy"]() }

		writeField(service, "preparedStartRuntime", PreparedStartRuntimeState(
			PreparedTrackingStartToken("new-start"), TrackingStartCommand(12L)))
		invokeServiceSuspend<Unit>(service, "finishBackgroundHostHandoff")
		coVerify(exactly = 2) { locationOwner.retire(AmbientReconciliationLease(location), reconcileAfterRetirement = true) }
		coVerify(exactly = 0) { sensorOwner.retire(any(), any(), reconcileAfterRetirement = true) }
		coVerify(exactly = 1) { service["refreshBackgroundCollectionPlans"]() }
		verify(exactly = 1) { service["observeCollectionMotionPolicy"]() }
	}

	private fun subject(): TrackerService {
		val context = ApplicationProvider.getApplicationContext<Application>()
		Shadows.shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LOCATION, true)
		val sensors = requireNotNull(context.getSystemService(SensorManager::class.java))
		Shadows.shadowOf(sensors).addSensor(ShadowSensor.newInstance(Sensor.TYPE_PRESSURE))
		return spyk(TrackerService(), recordPrivateCalls = true).also { service ->
			ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }.set(service, context)
			service.backgroundLocationHost = mockk()
			service.controller = mockk()
		}
	}

	private fun accepted(service: TrackerService): Set<*> = TrackerService::class.java
		.getDeclaredMethod("resolveAcceptedForegroundSources", Set::class.java, SessionStartOrigin::class.java)
		.apply { isAccessible = true }
		.invoke(service, setOf(SourceKind.LOCATION), SessionStartOrigin.POLICY_RECONCILIATION) as Set<*>

	private fun foregroundRequirements(sources: Set<SourceKind> = setOf(SourceKind.LOCATION)): Any = Class.forName(
		"com.adsamcik.tracker.tracker.service.ForegroundServiceRequirements")
		.getDeclaredConstructor(Set::class.java).apply { isAccessible = true }.newInstance(sources)

	private fun promoteBackground(service: TrackerService, sources: Set<SourceKind>): Boolean =
		TrackerService::class.java.getDeclaredMethod("ensureForegroundStarted",
			Set::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType,
			Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
			.apply { isAccessible = true }.invoke(service, sources, false, null, false, true) as Boolean

	private fun writeField(service: TrackerService, name: String, value: Any) {
		TrackerService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
	}

	private suspend fun <T> invokeServiceSuspend(service: TrackerService, name: String): T =
		suspendCoroutineUninterceptedOrReturn { continuation ->
			val method = TrackerService::class.java.getDeclaredMethod(name, Continuation::class.java)
				.apply { isAccessible = true }
			try { method.invoke(service, continuation) } catch (failure: InvocationTargetException) {
				throw failure.targetException
			}
		}
}
