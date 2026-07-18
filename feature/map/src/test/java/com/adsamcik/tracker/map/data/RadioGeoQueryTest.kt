package com.adsamcik.tracker.map.data

import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Radio geo queries")
class RadioGeoQueryTest {

	@Test
	fun `wifi projection includes identity signal and frequency`() {
		val sql = SafeQueryBuilder.wifi()
			.columns("bssid", "level", "frequency")
			.build()
			.sql

		sql shouldContain "bssid"
		sql shouldContain "level"
		sql shouldContain "frequency"
	}

	@Test
	fun `cell projection includes complete stable key and signal`() {
		val sql = SafeQueryBuilder.cell()
			.columns("cell_id", "lac", "mcc", "mnc", "network_type", "asu")
			.build()
			.sql

		sql shouldContain "cell_id"
		sql shouldContain "lac"
		sql shouldContain "network_type"
		sql shouldContain "signal_strength AS asu"
	}

	@Test
	fun `wifi radio query deduplicates by identity and spatial bucket before limiting`() {
		val sql = RadioQueryBuilder.wifi(
			GeoQuery(source = GeoSource.WIFI, sampleLimit = 500),
		).sql

		sql shouldContain "GROUP BY bssid, frequency"
		sql shouldContain "lat_e7 / 1500"
		sql shouldContain "LIMIT 500"
		sql shouldContain "radio_candidates AS"
		sql shouldContain "WITH id_bounds AS"
		sql shouldContain "GROUP BY bssid, frequency"
		sql shouldContain "UNION ALL"
		sql shouldContain "LIMIT 3000"
	}

	@Test
	fun `cell radio query deduplicates by stable identity and spatial bucket before limiting`() {
		val sql = RadioQueryBuilder.cell(
			GeoQuery(source = GeoSource.CELL, sampleLimit = 500),
		).sql

		sql shouldContain "GROUP BY mcc, mnc, network_type, lac, cell_id"
		sql shouldContain "lat_e7 / 4000"
		sql shouldContain "LIMIT 500"
		sql shouldContain "WITH id_bounds AS"
		sql shouldContain "GROUP BY mcc, mnc, network_type, lac, cell_id"
		sql shouldContain "UNION ALL"
		sql shouldContain "t.network_type IN (4, 5)"
		sql shouldContain "LIMIT 3000"
	}

	@Test
	fun `cell signal sampling rejects placeholders and invalid ASU before limiting`() {
		val sql = SafeQueryBuilder.cell()
			.columns("network_type")
			.weight("asu")
			.validCellSignal()
			.sample(100)
			.build()
			.sql

		sql shouldContain "network_type = 1 AND signal_strength BETWEEN 0 AND 31"
		sql shouldContain "network_type IN (4, 5) AND signal_strength BETWEEN 0 AND 97"
	}

	@Test
	fun `radio query wraps longitude bounds across the antimeridian`() {
		val query = RadioQueryBuilder.wifi(
			GeoQuery(
				source = GeoSource.WIFI,
				bounds = Bounds(north = 10.0, east = -170.0, south = -10.0, west = 170.0),
				sampleLimit = 100,
			),
		)

		query.sql shouldContain "(t.lon_e7 >= ? OR t.lon_e7 <= ?)"
	}
}
