package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload

/** Named event-time contracts for every legacy consumer that implicitly correlated cycle fields. */
data class TrackingJoinContract(
	val consumerId: String,
	val spec: JoinSpec,
	val acceptsPrimary: (SourcePayload) -> Boolean,
)

object TrackingJoinSpecs {
	const val LOCATION_CONTEXT_MAX_AGE_MS = 30_000L
	const val ACTIVITY_CONTEXT_MAX_AGE_MS = 60_000L
	const val WINDOW_CONTEXT_MAX_AGE_MS = 60_000L
	const val WIFI_LOCATION_BRACKET_MAX_AGE_MS = 30_000L
	const val CELL_LOCATION_MAX_AGE_MS = 60_000L
	const val ALLOWED_LATENESS_MS = 15_000L
	const val MISSING_INPUT_TIMEOUT_MS = 90_000L

	val contracts: List<TrackingJoinContract> = listOf(
		contract(
			consumerId = "pressure-altitude-fusion",
			primary = SourceKind.LOCATION,
			inputs = mapOf(
				SourceKind.LOCATION to primaryInput(),
				SourceKind.PRESSURE to JoinInputSpec(
					maximumAgeMs = WINDOW_CONTEXT_MAX_AGE_MS,
					direction = JoinDirection.WINDOW_OVERLAP,
					requireWindowOverlap = true,
					required = false,
				),
			),
			accepts = { it is LocationFixPayload },
		),
		contract(
			consumerId = "activity-inference",
			primary = SourceKind.ACTIVITY,
			inputs = mapOf(
				SourceKind.ACTIVITY to primaryInput(),
				SourceKind.STEPS to JoinInputSpec(
					maximumAgeMs = WINDOW_CONTEXT_MAX_AGE_MS,
					direction = JoinDirection.WINDOW_OVERLAP,
					requireWindowOverlap = true,
					required = false,
				),
				SourceKind.LOCATION to JoinInputSpec(
					maximumAgeMs = LOCATION_CONTEXT_MAX_AGE_MS,
					direction = JoinDirection.BEFORE_OR_EQUAL,
					required = false,
				),
			),
			accepts = { it is ActivityRecognitionPayload || it is ActivityTransitionPayload },
		),
		contract(
			consumerId = "wifi-interpolation",
			primary = SourceKind.WIFI,
			inputs = mapOf(
				SourceKind.WIFI to primaryInput(),
				SourceKind.LOCATION to JoinInputSpec(
					maximumAgeMs = WIFI_LOCATION_BRACKET_MAX_AGE_MS,
					direction = JoinDirection.BRACKET,
					required = false,
				),
			),
			accepts = { it is WifiResultSnapshotPayload },
		),
		contract(
			consumerId = "cell-presence",
			primary = SourceKind.CELL,
			inputs = mapOf(
				SourceKind.CELL to primaryInput(),
				SourceKind.LOCATION to JoinInputSpec(
					maximumAgeMs = CELL_LOCATION_MAX_AGE_MS,
					direction = JoinDirection.BEFORE_OR_EQUAL,
					required = false,
				),
			),
			accepts = { it is CellSnapshotPayload },
		),
		sportContract("ski-classifier", SourceKind.PRESSURE) { it is PressureWindowPayload },
		sportContract("plane-classifier", SourceKind.PRESSURE) { it is PressureWindowPayload },
		sportContract("sailing-classifier", SourceKind.LOCATION) { it is LocationFixPayload },
	)

	val byId: Map<String, TrackingJoinContract> = contracts.associateBy(TrackingJoinContract::consumerId)

	private fun sportContract(
		consumerId: String,
		primary: SourceKind,
		accepts: (SourcePayload) -> Boolean,
	): TrackingJoinContract {
		val inputs = linkedMapOf(
			primary to primaryInput(),
			SourceKind.STEPS to JoinInputSpec(
				maximumAgeMs = WINDOW_CONTEXT_MAX_AGE_MS,
				direction = JoinDirection.WINDOW_OVERLAP,
				requireWindowOverlap = true,
				required = false,
			),
		)
		if (primary != SourceKind.LOCATION) {
			inputs[SourceKind.LOCATION] = JoinInputSpec(
				maximumAgeMs = LOCATION_CONTEXT_MAX_AGE_MS,
				direction = JoinDirection.BEFORE_OR_EQUAL,
				required = false,
			)
		}
		if (consumerId == "sailing-classifier") {
			inputs[SourceKind.ACTIVITY] = JoinInputSpec(
				maximumAgeMs = ACTIVITY_CONTEXT_MAX_AGE_MS,
				direction = JoinDirection.BEFORE_OR_EQUAL,
				required = false,
			)
		}
		return contract(consumerId, primary, inputs, accepts)
	}

	private fun contract(
		consumerId: String,
		primary: SourceKind,
		inputs: Map<SourceKind, JoinInputSpec>,
		accepts: (SourcePayload) -> Boolean,
	): TrackingJoinContract = TrackingJoinContract(
		consumerId = consumerId,
		spec = JoinSpec(
			id = "tracking-$consumerId-v1",
			primarySource = primary,
			input = inputs,
			allowedLatenessMs = ALLOWED_LATENESS_MS,
			missingInputTimeoutMs = MISSING_INPUT_TIMEOUT_MS,
			lateCorrectionPolicy = LateCorrectionPolicy.APPEND_ONLY_CORRECTION,
		),
		acceptsPrimary = accepts,
	)

	private fun primaryInput() = JoinInputSpec(
		maximumAgeMs = 0L,
		direction = JoinDirection.NEAREST,
	)
}
