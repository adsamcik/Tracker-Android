package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellFactProjector
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellProjectionResult
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiFactProjector
import com.adsamcik.tracker.tracker.source.ambient.wifi.AmbientWifiProjectionResult
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import javax.inject.Inject

/** Uses the retained reading's own purpose history, including callbacks after that purpose ended. */
internal class AmbientWifiProjection @Inject constructor(
	private val projector: AmbientWifiFactProjector,
) : Projection {
	override val id: String = AmbientWifiFactRevisionEntity.WRITER_ID
	override val version: Int = AmbientWifiFactRevisionEntity.WRITER_VERSION

	override suspend fun apply(event: AdmittedSourceEvent<out SourcePayload>, context: ProjectionContext) {
		if (event.evidence.source != SourceKind.WIFI) {
			return
		}
		if (projector.project(event.admissionOrdinal).isRetryable()) {
			throw AmbientRadioProjectionDeferredException()
		}
	}
}

internal class AmbientCellProjection @Inject constructor(
	private val projector: AmbientCellFactProjector,
) : Projection {
	override val id: String = AmbientCellFactRevisionEntity.WRITER_ID
	override val version: Int = AmbientCellFactRevisionEntity.WRITER_VERSION

	override suspend fun apply(event: AdmittedSourceEvent<out SourcePayload>, context: ProjectionContext) {
		if (event.evidence.source != SourceKind.CELL) {
			return
		}
		if (projector.project(event.admissionOrdinal).isRetryable()) {
			throw AmbientRadioProjectionDeferredException()
		}
	}
}

// Noneligible or unverifiable groups produce no retained fact. They must not discard a separately
// qualified sibling group. A transient storage/retention failure instead rolls back the complete
// projection transaction and leaves its checkpoint available for the next durable retry.
private fun AmbientWifiProjectionResult.isRetryable(): Boolean = when (this) {
	AmbientWifiProjectionResult.RetryableFailure -> true
	is AmbientWifiProjectionResult.Groups -> results.any { it.isRetryable() }
	else -> false
}

private fun AmbientCellProjectionResult.isRetryable(): Boolean = when (this) {
	AmbientCellProjectionResult.RetryableFailure -> true
	is AmbientCellProjectionResult.Groups -> results.any { it.isRetryable() }
	else -> false
}

internal class AmbientRadioProjectionDeferredException : IllegalStateException("Ambient radio projection deferred")
