package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.SourcePayload

interface Projection {
	val id: String
	val version: Int
	val retentionRequired: Boolean get() = true
	val maximumAttemptsPerEvent: Int get() = 3
	suspend fun apply(event: AdmittedSourceEvent<out SourcePayload>, context: ProjectionContext)
}

interface ProjectionContext {
	suspend fun recordOutbox(effect: ProjectionOutboxEffect)
	suspend fun loadJoinState(key: String): ByteArray?
	suspend fun saveJoinState(
		key: String,
		payload: ByteArray,
		minimumRequiredOrdinal: Long? = null,
		logicalTrackingId: String? = null,
		payloadVersion: Int = 1,
	)
	suspend fun removeJoinState(key: String)
}

data class ProjectionOutboxEffect(
	val stableId: String,
	val kind: String,
	val payloadVersion: Int,
	val payload: ByteArray,
	/** False for replayable projection records that have no external delivery endpoint. */
	val requiresDelivery: Boolean = true,
)
