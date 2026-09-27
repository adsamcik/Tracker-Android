package com.adsamcik.tracker.tracker.source.lifecycle

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.tracker.source.catalog.SourceImplementationCatalog
import com.adsamcik.tracker.tracker.source.catalog.SourcePurposeBinding
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class SourceLifecycleProofCatalogTest {
	@Test
	fun `catalog exposes exactly six read-only proof factories`() {
		val implementationCatalog = mockk<SourceImplementationCatalog>()
		val executable = mockk<SourcePurposeBinding.Executable>()
		val adapterFactory = mockk<DefaultSourceLifecycleProofAdapterFactory>()
		every {
			implementationCatalog.binding(any<TrackingSource>(), TrackingPurpose.SESSION_CAPTURE)
		} returns executable
		every { adapterFactory.create(any()) } returns mockk()

		val catalog = SourceLifecycleProofCatalog(implementationCatalog, adapterFactory)

		catalog.descriptors.map(SourceLifecycleProofDescriptor::source).toSet() shouldBe
			TrackingSource.entries.toSet()
		catalog.descriptors.size shouldBe 6
		catalog.descriptors.forEach { descriptor ->
			descriptor.adapterFactory.create()
			verify(exactly = 1) { adapterFactory.create(descriptor.source) }
		}
		verify(exactly = 6) {
			implementationCatalog.binding(any<TrackingSource>(), TrackingPurpose.SESSION_CAPTURE)
		}
	}
}
