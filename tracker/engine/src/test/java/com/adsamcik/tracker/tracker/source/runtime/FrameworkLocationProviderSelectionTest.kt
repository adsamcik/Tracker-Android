package com.adsamcik.tracker.tracker.source.runtime

import android.location.LocationManager
import com.adsamcik.tracker.tracker.source.model.LocationMode
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import org.junit.Test

class FrameworkLocationProviderSelectionTest {
	private val manager = mockk<LocationManager>()

	@Test
	fun `balanced tracking uses GPS when the network provider is unavailable`() {
		every { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } returns false
		every { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) } returns true

		assertEquals(
			LocationManager.GPS_PROVIDER,
			LocationMode.BALANCED.toFrameworkProvider(manager::isProviderEnabled),
		)
	}

	@Test
	fun `high accuracy uses network when GPS is unavailable`() {
		every { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) } returns false
		every { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } returns true

		assertEquals(
			LocationManager.NETWORK_PROVIDER,
			LocationMode.HIGH_ACCURACY.toFrameworkProvider(manager::isProviderEnabled),
		)
	}

	@Test
	fun `active tracking uses passive only when no active provider is available`() {
		every { manager.isProviderEnabled(any()) } returns false

		assertEquals(
			LocationManager.PASSIVE_PROVIDER,
			LocationMode.BALANCED.toFrameworkProvider(manager::isProviderEnabled),
		)
		assertEquals(
			LocationManager.PASSIVE_PROVIDER,
			LocationMode.HIGH_ACCURACY.toFrameworkProvider(manager::isProviderEnabled),
		)
	}
}
