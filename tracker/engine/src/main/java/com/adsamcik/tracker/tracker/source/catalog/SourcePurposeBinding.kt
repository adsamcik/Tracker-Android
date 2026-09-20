package com.adsamcik.tracker.tracker.source.catalog

import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.model.tracking.TrackingSourcePurposeIdentity

data class SourcePurposeKey(
	val source: TrackingSource,
	val purpose: TrackingPurpose,
)

enum class SourceActivationDefault {
	SESSION_DEMAND_DRIVEN,
	CONTROL_OFF,
	AMBIENT_OFF,
}

enum class UnsupportedSourcePurposeReason {
	NOT_CANONICALLY_SUPPORTED,
}

sealed interface SourcePurposeBinding {
	val source: TrackingSource
	val purpose: TrackingPurpose

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
