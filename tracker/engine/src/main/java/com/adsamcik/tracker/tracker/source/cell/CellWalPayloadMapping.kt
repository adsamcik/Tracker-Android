package com.adsamcik.tracker.tracker.source.cell

import com.adsamcik.tracker.shared.base.database.data.CellWalPayload
import com.adsamcik.tracker.shared.base.database.data.CellWalPayloadObservation
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload

/** Shared field mapping for canonical Cell encoding and purpose qualification. */
internal fun CellSnapshotPayload.toCellWalPayload(): CellWalPayload = CellWalPayload(
	subscriptionId = subscriptionId,
	observations = observations.map { observation ->
		CellWalPayloadObservation(
			identifierToken = observation.identifierToken,
			radioType = observation.radioType,
			registered = observation.registered,
			signalLevelDbm = observation.signalLevelDbm,
			providerTimestampNanos = observation.providerTimestampNanos,
		)
	},
	refreshOutcomeCode = refreshOutcome.ordinal,
	observationZoneId = requireNotNull(observationZoneId),
)
