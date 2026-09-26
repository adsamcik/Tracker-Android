package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity

internal const val AMBIENT_LOCATION_CONSUMER_ID = "ambient:location"

internal data class AmbientLocationDemandDispatchRequest(
	val identity: AmbientReconciliationIdentity,
	val bootId: String,
	val elapsedRealtimeNanos: Long,
	val wallTimeMs: Long,
)

internal sealed interface AmbientLocationDemandResult {
	data class Active(val demand: SourceDemandEntity) : AmbientLocationDemandResult
	/** Safe operational code only, never coordinates or provider payload. */
	data class Inactive(val reason: String) : AmbientLocationDemandResult
}
