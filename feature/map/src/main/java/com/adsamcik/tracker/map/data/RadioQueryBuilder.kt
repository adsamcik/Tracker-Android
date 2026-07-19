package com.adsamcik.tracker.map.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Fixed-shape, parameterized radio queries that spatially thin each stable identity before applying
 * the viewport row budget. This keeps repeated scans of one radio from displacing rarer radios.
 */
internal object RadioQueryBuilder {

	fun wifi(query: GeoQuery): SupportSQLiteQuery {
		require(query.source == GeoSource.WIFI) { "Wi-Fi radio query requires WIFI source" }
		return build(
			query = query,
			table = "wifi_observation",
			projection = """
				SUM(CAST(lat_e7 AS REAL) * sample_count) / SUM(sample_count) / $E7_DIVISOR AS lat,
				SUM(CAST(lon_e7 AS REAL) * sample_count) / SUM(sample_count) / $E7_DIVISOR AS lon,
				MAX(time_ms) AS time,
				bssid,
				MAX(level) AS level,
				frequency
			""".trimIndent(),
			candidateProjection = """
				CAST(AVG(lat_e7) AS INTEGER) AS lat_e7,
				CAST(AVG(lon_e7) AS INTEGER) AS lon_e7,
				MAX(time_ms) AS time_ms,
				bssid,
				MAX(level) AS level,
				frequency,
				COUNT(*) AS sample_count
			""".trimIndent(),
			groupIdentity = "bssid, frequency",
			spatialBucketE7 = WIFI_SPATIAL_BUCKET_E7,
			eligibilityPredicate = null,
		)
	}

	fun cell(query: GeoQuery): SupportSQLiteQuery {
		require(query.source == GeoSource.CELL) { "Cell radio query requires CELL source" }
		return build(
			query = query,
			table = "cell_sample",
			projection = """
				SUM(CAST(lat_e7 AS REAL) * sample_count) / SUM(sample_count) / $E7_DIVISOR AS lat,
				SUM(CAST(lon_e7 AS REAL) * sample_count) / SUM(sample_count) / $E7_DIVISOR AS lon,
				MAX(time_ms) AS time,
				cell_id,
				lac,
				mcc,
				mnc,
				network_type,
				MAX(asu) AS asu
			""".trimIndent(),
			candidateProjection = """
				CAST(AVG(lat_e7) AS INTEGER) AS lat_e7,
				CAST(AVG(lon_e7) AS INTEGER) AS lon_e7,
				MAX(time_ms) AS time_ms,
				cell_id,
				lac,
				mcc,
				mnc,
				network_type,
				MAX(CASE
					WHEN network_type = 1 AND signal_strength BETWEEN 0 AND 31 THEN signal_strength
					WHEN network_type = 2 AND signal_strength BETWEEN 0 AND 16 THEN signal_strength
					WHEN network_type = 3 AND signal_strength BETWEEN 0 AND 96 THEN signal_strength
					WHEN network_type IN (4, 5) AND signal_strength BETWEEN 0 AND 97 THEN signal_strength
				END) AS asu,
				COUNT(*) AS sample_count
			""".trimIndent(),
			groupIdentity = "mcc, mnc, network_type, lac, cell_id",
			spatialBucketE7 = CELL_SPATIAL_BUCKET_E7,
			eligibilityPredicate = """
				(
					(t.network_type = 1 AND t.signal_strength BETWEEN 0 AND 31) OR
					(t.network_type = 2 AND t.signal_strength BETWEEN 0 AND 16) OR
					(t.network_type = 3 AND t.signal_strength BETWEEN 0 AND 96) OR
					(t.network_type IN (4, 5) AND t.signal_strength BETWEEN 0 AND 97)
				)
			""".trimIndent(),
		)
	}

	private fun build(
		query: GeoQuery,
		table: String,
		projection: String,
		candidateProjection: String,
		groupIdentity: String,
		spatialBucketE7: Int,
		eligibilityPredicate: String?,
	): SupportSQLiteQuery {
		require(query.timeFrom == null || query.timeTo == null || query.timeFrom <= query.timeTo) {
			"from must be <= to"
		}
		val requestedRows = query.sampleLimit ?: query.newestLimit ?: query.limit ?: DEFAULT_RADIO_QUERY_LIMIT
		require(requestedRows > 0) { "radio query limit must be > 0" }
		val maxRows = requestedRows.coerceAtMost(MAX_RADIO_QUERY_ROWS)
		val candidateRows = (maxRows.toLong() * CANDIDATE_MULTIPLIER)
			.coerceAtMost(MAX_RADIO_QUERY_ROWS.toLong())
			.toInt()
			.coerceAtLeast(maxRows)
		val rowsPerStratum = candidateRows
		val args = mutableListOf<Any>()
		val observationClauses = mutableListOf("t.lat_e7 IS NOT NULL", "t.lon_e7 IS NOT NULL")
		val observationArgs = mutableListOf<Any>()
		eligibilityPredicate?.let(observationClauses::add)
		query.timeFrom?.let {
			observationClauses += "t.time_ms >= ?"
			observationArgs += it
		}
		query.timeTo?.let {
			observationClauses += "t.time_ms <= ?"
			observationArgs += it
		}
		query.bounds?.let { bounds ->
			observationClauses += "t.lat_e7 BETWEEN ? AND ?"
			observationArgs += SafeQueryBuilder.degreesToE7(bounds.south)
			observationArgs += SafeQueryBuilder.degreesToE7(bounds.north)
			if (bounds.crossesAntimeridian) {
				observationClauses += "(t.lon_e7 >= ? OR t.lon_e7 <= ?)"
				observationArgs += SafeQueryBuilder.degreesToE7(bounds.west)
				observationArgs += SafeQueryBuilder.degreesToE7(bounds.east)
			} else {
				observationClauses += "t.lon_e7 BETWEEN ? AND ?"
				observationArgs += SafeQueryBuilder.degreesToE7(bounds.west)
				observationArgs += SafeQueryBuilder.degreesToE7(bounds.east)
			}
		}
		val observationFilter = observationClauses.joinToString(" AND ")
		args.addAll(observationArgs)
		val candidateQueries = buildList(ID_STRATA) {
			for (stratum in 0 until ID_STRATA) {
				val lowerBound = idBoundaryExpression(stratum)
				val upperBound = if (stratum == ID_STRATA - 1) {
					"b.max_id"
				} else {
					"${idBoundaryExpression(stratum + 1)} - 1"
				}
				add(
					"""
						SELECT * FROM (
							SELECT $candidateProjection
							FROM $table AS t, id_bounds AS b
							WHERE t.id BETWEEN $lowerBound AND $upperBound
								AND $observationFilter
							GROUP BY $groupIdentity,
								CAST(lat_e7 / $spatialBucketE7 AS INTEGER),
								CAST(lon_e7 / $spatialBucketE7 AS INTEGER)
							ORDER BY MAX(time_ms) DESC
							LIMIT $rowsPerStratum
						)
					""".trimIndent(),
				)
				args.addAll(observationArgs)
			}
		}
		val grouping = """
			GROUP BY $groupIdentity,
				CAST(lat_e7 / $spatialBucketE7 AS INTEGER),
				CAST(lon_e7 / $spatialBucketE7 AS INTEGER)
			ORDER BY time DESC
			LIMIT $maxRows
		""".trimIndent()
		val sql = """
			WITH id_bounds AS (
				SELECT MIN(id) AS min_id, MAX(id) AS max_id
				FROM $table AS t
				WHERE $observationFilter
			),
			radio_candidates AS (
				${candidateQueries.joinToString("\nUNION ALL\n")}
			)
			SELECT $projection
			FROM radio_candidates
			$grouping
		""".trimIndent()
		return SimpleSQLiteQuery(sql, args.toTypedArray())
	}

	private fun idBoundaryExpression(stratum: Int): String =
		"(b.min_id + ((b.max_id - b.min_id + 1) * $stratum) / $ID_STRATA)"

	private const val E7_DIVISOR = 10_000_000.0
	private const val WIFI_SPATIAL_BUCKET_E7 = 1_500
	private const val CELL_SPATIAL_BUCKET_E7 = 4_000
	private const val DEFAULT_RADIO_QUERY_LIMIT = 20_000
	private const val MAX_RADIO_QUERY_ROWS = 80_000
	private const val CANDIDATE_MULTIPLIER = 6L
	private const val ID_STRATA = 4
}
