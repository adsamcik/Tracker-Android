package com.adsamcik.tracker.tracker.source.lifecycle

import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.stats.api.repository.MaterializedProofState
import com.adsamcik.tracker.stats.api.repository.RecordingProofState
import com.adsamcik.tracker.stats.api.repository.SourceProofKey
import com.adsamcik.tracker.stats.api.repository.SourceProofResult

internal interface SourceLifecycleProofAdapter {
	val source: TrackingSource

	suspend fun recording(
		key: SourceProofKey,
	): SourceProofResult<RecordingProofState>

	suspend fun materialized(
		key: SourceProofKey,
		recording: RecordingProofState,
	): SourceProofResult<MaterializedProofState>
}
