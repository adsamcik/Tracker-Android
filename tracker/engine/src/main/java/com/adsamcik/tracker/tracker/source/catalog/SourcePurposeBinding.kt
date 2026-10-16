package com.adsamcik.tracker.tracker.source.catalog

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.model.tracking.TrackingSourcePurposeIdentity

/** Identifies one canonical tracking source and the purpose that may use it. */
data class SourcePurposeKey(
	val source: TrackingSource,
	val purpose: TrackingPurpose,
)

/** Default demand behavior for a supported purpose; registration still requires runtime authority. */
enum class SourceActivationDefault {
	SESSION_DEMAND_DRIVEN,
	CONTROL_OFF,
	AMBIENT_OFF,
}

/** Reason the canonical source model has no acquisition or product contract for a purpose. */
enum class UnsupportedSourcePurposeReason {
	NOT_CANONICALLY_SUPPORTED,
}

/**
 * Declares the acquisition and product contract for one supported or explicitly unsupported source-purpose
 * pair.
 */
sealed interface SourcePurposeBinding {
	val source: TrackingSource
	val purpose: TrackingPurpose

	/** A supported pair whose canonical identity, provider check and product binding must agree. */
	data class Executable(
		val identity: TrackingSourcePurposeIdentity,
		val activationDefault: SourceActivationDefault,
		val providerAvailability: SourceProviderAvailabilityReader,
		val products: SourceProductBinding,
	) : SourcePurposeBinding {
		override val source: TrackingSource = identity.source
		override val purpose: TrackingPurpose = identity.purpose

		init {
			require(products.source == source)
			require(products.purpose == purpose)
		}
	}

	/** A pair excluded by the canonical source model; it cannot create demand or product writers. */
	data class Unsupported(
		override val source: TrackingSource,
		override val purpose: TrackingPurpose,
		val reason: UnsupportedSourcePurposeReason,
	) : SourcePurposeBinding {
		init {
			require(!source.supports(purpose)) {
				"$source ${purpose.stableName} is canonical and cannot be marked unsupported"
			}
		}
	}
}
