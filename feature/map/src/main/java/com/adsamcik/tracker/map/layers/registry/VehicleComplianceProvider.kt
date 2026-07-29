package com.adsamcik.tracker.map.layers.registry

import com.adsamcik.tracker.map.layers.impl.VehicleComplianceLayer
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.SessionActivityIds
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.VehicleSpeedSampleRow
import com.adsamcik.tracker.stats.api.roadmatch.MatchedEdge
import com.adsamcik.tracker.stats.api.roadmatch.RoadLimitProvenance
import com.adsamcik.tracker.stats.api.roadmatch.RoadMatchStatus
import com.adsamcik.tracker.stats.api.roadmatch.RoadMatcher
import com.adsamcik.tracker.stats.api.roadmatch.RoadObservation
import kotlinx.coroutines.withContext

/**
 * Production data provider for the vehicle-compliance layer.
 *
 * It is intentionally separate from [DefaultLayerRegistry] so tests exercise
 * the exact Room -> matcher -> display mapping used by the app.  A normal
 * compliance colour is emitted only for a finite speed and a positive,
 * explicit OSM `maxspeed` tag.  Local road-class defaults remain usable by the
 * matcher but are withheld from legal/compliance presentation.
 */
internal class VehicleComplianceProvider(
	private val dao: LocationSampleDao,
	private val roadMatcher: RoadMatcher,
	private val dispatchers: DispatchersProvider,
	private val nowMs: () -> Long = System::currentTimeMillis,
) {
	suspend fun load(range: LongRange): VehicleComplianceLayer.ProviderResult = withContext(dispatchers.io) {
		val now = nowMs()
		val fromMs = if (!range.isEmpty()) range.first else now - DEFAULT_LOCATION_RANGE_MS
		val toMs = if (!range.isEmpty()) range.last else now
		var afterTimeMs: Long? = null
		var afterId: Long? = null
		val initialRows = collectBoundedChunks(
			chunkSize = VEHICLE_CHUNK_SIZE,
			maxMaterializedRows = VEHICLE_MAX_MATERIALIZED_SAMPLES - 1,
		) { limit ->
			val chunk = dao.getDrivingChunkBetweenOrdered(
				fromMs = fromMs,
				toMs = toMs,
				drivingActivities = SessionActivityIds.DRIVING.toList(),
				afterTimeMs = afterTimeMs,
				afterId = afterId,
				limit = limit,
			)
			chunk.lastOrNull()?.let { lastRow ->
				afterTimeMs = lastRow.timeMs
				afterId = lastRow.id
			}
			chunk
		}
		val lastRow = dao.getLatestDrivingBetween(
			fromMs = fromMs,
			toMs = toMs,
			drivingActivities = SessionActivityIds.DRIVING.toList(),
		)
		val rows = if (lastRow != null && initialRows.lastOrNull()?.id != lastRow.id) {
			initialRows + lastRow
		} else {
			initialRows
		}
		if (rows.size < 2) {
			VehicleComplianceLayer.ProviderResult(emptyList())
		} else {
			val sampled = downSampleEvenly(rows, VEHICLE_MAX_PRE_SAMPLES)
			val observations = sampled.map { row ->
				RoadObservation(
					latE7 = row.latE7,
					lonE7 = row.lonE7,
					accuracyM = row.hAccM ?: VEHICLE_DEFAULT_ACCURACY_M,
					timeMs = row.timeMs,
				)
			}
			mapMatchedEdges(sampled, roadMatcher.match(observations))
		}
	}

	/** Visible for the focused provider tests; production [load] calls this exact mapping. */
	internal fun mapMatchedEdges(
		samples: List<VehicleSpeedSampleRow>,
		matchedEdges: List<MatchedEdge>,
	): VehicleComplianceLayer.ProviderResult {
		if (matchedEdges.isEmpty()) {
			return VehicleComplianceLayer.ProviderResult(
				edges = emptyList(),
				diagnostics = if (samples.size >= 2) {
					listOf(VehicleComplianceLayer.SuppressionDiagnostic(
						reason = VehicleComplianceLayer.SuppressionReason.NO_MATCH,
					))
				} else {
					emptyList()
				},
			)
		}

		val visible = ArrayList<VehicleComplianceLayer.ComplianceEdge>(matchedEdges.size)
		val diagnostics = ArrayList<VehicleComplianceLayer.SuppressionDiagnostic>()
		var previousAcceptedToIndex: Int? = null

		for (edge in matchedEdges) {
			val commonDiagnostic = edge.diagnosticBase()
			val matchReason = edge.matchSuppressionReason()
			if (matchReason != null) {
				diagnostics += commonDiagnostic.copy(reason = matchReason)
				continue
			}
			if (edge.fromIndex !in samples.indices || edge.toIndex !in samples.indices ||
				edge.toIndex <= edge.fromIndex
			) {
				diagnostics += commonDiagnostic.copy(
					reason = VehicleComplianceLayer.SuppressionReason.INVALID_EDGE_INDEX,
				)
				continue
			}
			if (edge.path.size < 2) {
				diagnostics += commonDiagnostic.copy(
					reason = VehicleComplianceLayer.SuppressionReason.INVALID_PATH,
				)
				continue
			}
			val limitReason = edge.limitSuppressionReason()
			if (limitReason != null) {
				diagnostics += commonDiagnostic.copy(reason = limitReason)
				continue
			}

			val speedMps = samples[edge.toIndex].speedMps
			if (!speedMps.isFinite() || speedMps < 0f) {
				diagnostics += commonDiagnostic.copy(
					reason = VehicleComplianceLayer.SuppressionReason.INVALID_SPEED,
				)
				continue
			}
			val limitMps = edge.maxspeedKmh!! * VEHICLE_MPS_PER_KMH
			val ratio = speedMps / limitMps
			if (!ratio.isFinite() || ratio < 0f) {
				diagnostics += commonDiagnostic.copy(
					reason = VehicleComplianceLayer.SuppressionReason.INVALID_SPEED,
				)
				continue
			}

			val gapBefore = edge.fromIndex != previousAcceptedToIndex
			if (gapBefore && previousAcceptedToIndex != null) {
				diagnostics += commonDiagnostic.copy(
					reason = VehicleComplianceLayer.SuppressionReason.GAP,
				)
			}
			visible += VehicleComplianceLayer.ComplianceEdge(
				ratio = ratio,
				path = edge.path.map { point -> LatLngModel(point.latE7 / E7, point.lonE7 / E7) },
				gapBefore = gapBefore,
			)
			previousAcceptedToIndex = edge.toIndex
		}

		return VehicleComplianceLayer.ProviderResult(visible, diagnostics)
	}

	private fun MatchedEdge.diagnosticBase() = VehicleComplianceLayer.SuppressionDiagnostic(
		reason = VehicleComplianceLayer.SuppressionReason.NO_MATCH,
		importId = importId,
		osmWayId = osmWayId,
		fromObservationIndex = fromIndex,
		toObservationIndex = toIndex,
		limitKmh = maxspeedKmh,
		limitProvenance = limitProvenance,
	)

	private fun MatchedEdge.matchSuppressionReason(): VehicleComplianceLayer.SuppressionReason? = when (matchStatus) {
		RoadMatchStatus.MATCHED -> null
		RoadMatchStatus.AMBIGUOUS -> VehicleComplianceLayer.SuppressionReason.AMBIGUOUS_MATCH
		RoadMatchStatus.NO_PATH -> VehicleComplianceLayer.SuppressionReason.NO_PATH
		RoadMatchStatus.GAP -> VehicleComplianceLayer.SuppressionReason.GAP
	}

	private fun MatchedEdge.limitSuppressionReason(): VehicleComplianceLayer.SuppressionReason? = when {
		maxspeedKmh == null -> VehicleComplianceLayer.SuppressionReason.UNKNOWN_LIMIT
		maxspeedKmh!! <= 0 -> VehicleComplianceLayer.SuppressionReason.INVALID_LIMIT
		limitProvenance == RoadLimitProvenance.EXPLICIT_OSM_TAG -> null
		limitProvenance == RoadLimitProvenance.ROAD_CLASS_HEURISTIC ->
			VehicleComplianceLayer.SuppressionReason.HEURISTIC_LIMIT
		limitProvenance == RoadLimitProvenance.UNSUPPORTED ->
			VehicleComplianceLayer.SuppressionReason.UNSUPPORTED_LIMIT
		else -> VehicleComplianceLayer.SuppressionReason.UNKNOWN_LIMIT
	}

	private companion object {
		const val DEFAULT_LOCATION_RANGE_MS = 30L * 24 * 60 * 60 * 1_000
		const val VEHICLE_CHUNK_SIZE = 2_000
		const val VEHICLE_MAX_PRE_SAMPLES = 30_000
		const val VEHICLE_MAX_MATERIALIZED_SAMPLES = VEHICLE_MAX_PRE_SAMPLES * 2
		const val VEHICLE_DEFAULT_ACCURACY_M = 8f
		const val VEHICLE_MPS_PER_KMH = 1000f / 3600f
		const val E7 = 10_000_000.0
	}
}
