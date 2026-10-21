package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity

internal val DIRECT_AMBIENT_SOURCES = setOf(
	com.adsamcik.tracker.tracker.source.model.SourceKind.LOCATION,
	com.adsamcik.tracker.tracker.source.model.SourceKind.ACTIVITY,
	com.adsamcik.tracker.tracker.source.model.SourceKind.PRESSURE,
)

internal fun ambientSourceConsumerId(source: com.adsamcik.tracker.tracker.source.model.SourceKind): String =
	"ambient:${source.name.lowercase(java.util.Locale.ROOT)}"

internal const val AMBIENT_LOCATION_CONSUMER_ID = "ambient:location"

internal data class AmbientSourceDemandDispatchRequest(
	val identity: AmbientReconciliationIdentity,
	val bootId: String,
	val elapsedRealtimeNanos: Long,
	val wallTimeMs: Long,
)

internal sealed interface AmbientSourceDemandResult {
	data class Active(val demand: SourceDemandEntity) : AmbientSourceDemandResult
	/** Safe operational code only, never coordinates or provider payload. */
	data class Inactive(val reason: String) : AmbientSourceDemandResult
}
