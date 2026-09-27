package com.adsamcik.tracker.tracker.source.lifecycle

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.tracker.source.catalog.SourceImplementationCatalog
import com.adsamcik.tracker.tracker.source.catalog.SourcePurposeBinding
import javax.inject.Inject
import javax.inject.Singleton

internal fun interface SourceLifecycleProofAdapterFactory {
	fun create(): SourceLifecycleProofAdapter
}

/**
 * Read-only lifecycle descriptor.
 *
 * Deliberately exposes only a proof-adapter factory. Runtime, provider, writer, drain, and control
 * methods remain outside this catalog.
 */
internal data class SourceLifecycleProofDescriptor(
	val source: TrackingSource,
	val adapterFactory: SourceLifecycleProofAdapterFactory,
)

@Singleton
internal class SourceLifecycleProofCatalog @Inject constructor(
	implementationCatalog: SourceImplementationCatalog,
	adapterFactory: DefaultSourceLifecycleProofAdapterFactory,
) {
	val descriptors: Set<SourceLifecycleProofDescriptor> =
		TrackingSource.entries.mapTo(linkedSetOf()) { source ->
			require(
				implementationCatalog.binding(source, TrackingPurpose.SESSION_CAPTURE) is
					SourcePurposeBinding.Executable,
			) { "$source has no executable session-capture catalog binding" }
			SourceLifecycleProofDescriptor(
				source = source,
				adapterFactory = SourceLifecycleProofAdapterFactory {
					adapterFactory.create(source)
				},
			)
		}.also { descriptors ->
			require(descriptors.size == EXPECTED_SOURCE_COUNT)
			require(descriptors.map(SourceLifecycleProofDescriptor::source).toSet() ==
				TrackingSource.entries.toSet())
		}

	private val descriptorsBySource = descriptors.associateBy(SourceLifecycleProofDescriptor::source)

	fun descriptor(source: TrackingSource): SourceLifecycleProofDescriptor =
		descriptorsBySource.getValue(source)

	private companion object {
		const val EXPECTED_SOURCE_COUNT = 6
	}
}
