package com.adsamcik.tracker.map.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapDataChangeObserverTest {
	@Test
	fun `native corrections cursors gaps and deletion requalify their own radio input`() {
		val changes = listOf(
			"wifi_captured_fact_revision" to MapDataSource.Wifi,
			"wifi_captured_fact_cursor" to MapDataSource.Wifi,
			"wifi_capture_deletion_generation" to MapDataSource.Wifi,
			"ambient_wifi_fact_revision" to MapDataSource.Wifi,
			"ambient_wifi_gap" to MapDataSource.Wifi,
			"ambient_wifi_retention_authority" to MapDataSource.Wifi,
			"cell_captured_fact_revision" to MapDataSource.Cell,
			"cell_captured_fact_cursor" to MapDataSource.Cell,
			"cell_capture_deletion_generation" to MapDataSource.Cell,
			"ambient_cell_fact_cursor" to MapDataSource.Cell,
			"ambient_cell_deletion_marker" to MapDataSource.Cell,
			"ambient_cell_authority" to MapDataSource.Cell,
		)
		for ((table, source) in changes) {
			assertTrue(table in MapDataChangeObserver.observedTables)
			assertEquals(setOf(source), MapDataChangeObserver.changedSources(setOf(table)))
		}
	}

	@Test
	fun `coordinate receipt purpose and retained floor changes invalidate context input`() {
		for (table in listOf("location_sample", "location_accepted_receipt", "location_sample_purpose_authority",
			"ambient_location_retention_authority")) {
			assertTrue(table in MapDataChangeObserver.observedTables)
			assertEquals(setOf(MapDataSource.Location), MapDataChangeObserver.changedSources(setOf(table)))
		}
	}

	@Test
	fun `privacy scope and lineage changes requalify all potentially affected inputs`() {
		val allSources = setOf(MapDataSource.Location, MapDataSource.Wifi, MapDataSource.Cell)
		for (table in listOf("source_evidence_state", "source_consent_epoch", "source_deletion_fence",
			"source_event_retention_proof", "captured_radio_fact_retention_proof", "source_authorization",
			"provider_registration_generation", "session_manifest_source", "source_product_projection_lane")) {
			assertTrue(table in MapDataChangeObserver.observedTables)
			assertEquals(allSources, MapDataChangeObserver.changedSources(setOf(table)))
		}
	}

	@Test
	fun `one transaction reports both changed inputs while unrelated data stays quiet`() {
		assertEquals(setOf(MapDataSource.Location, MapDataSource.Wifi),
			MapDataChangeObserver.changedSources(setOf("location_sample_purpose_authority", "wifi_captured_fact_revision")))
		assertEquals(emptySet<MapDataSource>(), MapDataChangeObserver.changedSources(setOf("organization_annotation",
			"pressure_fact_revision", "step_fact_revision")))
	}
}
