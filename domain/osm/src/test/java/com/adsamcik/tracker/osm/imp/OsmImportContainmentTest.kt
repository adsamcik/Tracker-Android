package com.adsamcik.tracker.osm.imp

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.dao.OsmImportDao
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsmImportContainmentTest {

	@Test
	fun `release capability defaults to unavailable`() {
		val capability = OfflinePbfImportCapability()

		capability.availability shouldBe OfflinePbfImportCapability.Availability.UNAVAILABLE
		capability.isAvailable shouldBe false
	}

	@Test
	fun `controller rejects enqueue and exposes unavailable state when release gate is closed`() {
		runBlocking {
			val controller = OsmImportController(
				context = ApplicationProvider.getApplicationContext<Application>(),
				osmImportDao = mockk<OsmImportDao>(relaxed = true),
				offlinePbfImportCapability = OfflinePbfImportCapability(),
			)

			controller.enqueue(
				OsmImportRequest(
					contentUri = "content://provider.example/private-token",
					displayName = "private-region.osm.pbf",
					fileSizeBytes = 1L,
				),
			) shouldBe OsmImportEnqueueResult.Rejected(
				OsmImportFailureCode.PBF_IMPORT_UNAVAILABLE,
			)
			controller.observeImportState().first() shouldBe OsmImportState.Unavailable
		}
	}
}
