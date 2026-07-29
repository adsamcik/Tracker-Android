package com.adsamcik.tracker.map.layers.impl

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.map.layers.registry.VehicleComplianceProvider
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.osm.io.OsmCellCoverage
import com.adsamcik.tracker.osm.io.OsmGridIndex
import com.adsamcik.tracker.osm.io.PolylineE7Codec
import com.adsamcik.tracker.osm.match.OsmHmmMapMatcher
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.OsmImportEntity
import com.adsamcik.tracker.shared.base.database.data.OsmWayCellEntity
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.roadmatch.RoadMatcher
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the persisted-graph path that the isolated compliance tests cannot:
 *
 * `osm_import (BUILDING) -> osm_way + osm_way_cell -> READY -> HMM matcher ->
 * VehicleComplianceProvider -> VehicleComplianceLayer -> MapLibre config`.
 *
 * The graph is published through the same visibility fields that production
 * consumers require, including a directed-bbox encoding and a complete cell
 * index. This makes a regression in the import/publication contract visible at
 * the rendered compliance boundary rather than only in DAO unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VehicleComplianceRealOsmMatcherTest {

	private lateinit var context: Application
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `published persisted OSM graph renders at-limit matched road path`() = runTest {
		val importId = seedReadyRoadGraph()
		seedDrivingSamples()

		val realMatcher = OsmHmmMapMatcher(
			osmWayDao = database.osmWayDao(),
			osmWayCellDao = database.osmWayCellDao(),
		)
		val provider = VehicleComplianceProvider(
			dao = database.locationSampleDao(),
			roadMatcher = RoadMatcher { observations -> realMatcher.match(observations) },
			dispatchers = DefaultDispatchersProvider,
		)
		val layer = TestableVehicleComplianceLayer(provider::load).apply {
			dateRange = SAMPLE_ONE_TIME_MS..SAMPLE_TWO_TIME_MS
		}

		val input = layer.testLoadData()
		assertTrue("A READY graph with a complete cell index must be matchable", input.diagnostics.isEmpty())
		assertEquals(1, input.edges.size)
		val matchedPath = input.edges.single().path
		assertTrue("The HMM matcher must return a drawable road-centreline span", matchedPath.size >= 2)
		assertEquals(ROAD_LAT_E7 / E7, matchedPath.first().lat, COORDINATE_TOLERANCE)
		assertEquals(SAMPLE_ONE_LON_E7 / E7, matchedPath.first().lng, COORDINATE_TOLERANCE)
		assertEquals(ROAD_LAT_E7 / E7, matchedPath.last().lat, COORDINATE_TOLERANCE)
		assertEquals(SAMPLE_TWO_LON_E7 / E7, matchedPath.last().lng, COORDINATE_TOLERANCE)

		val prepared = layer.testProcessData(
			input,
			PerformanceManager().budgets(rawQuality = 1.0f),
		)
		assertEquals(listOf(ComplianceBucket.AT_LIMIT), prepared.perBucket.map { it.bucket })
		assertTrue(prepared.perBucket.single().geoJson.contains("[14.0002,50.0]"))
		assertTrue(prepared.perBucket.single().geoJson.contains("[14.0018,50.0]"))

		val config = layer.testProduceConfig(prepared)
		assertTrue(config is MapLibreLayerConfig.Composite)
		val renderedLines = (config as MapLibreLayerConfig.Composite).layers
		assertEquals(1, renderedLines.size)
		assertTrue(renderedLines.single() is MapLibreLayerConfig.Line)
		val renderedLine = renderedLines.single() as MapLibreLayerConfig.Line
		assertTrue(renderedLine.geoJson.contains("\"type\":\"FeatureCollection\""))
		assertTrue(renderedLine.geoJson.contains("[14.0002,50.0]"))
		assertTrue(renderedLine.geoJson.contains("[14.0018,50.0]"))

		// The READY import id reaches the real matcher; preserving this assertion
		// makes the graph identity intentionally visible in a future failure.
		assertTrue(importId > 0)
	}

	private suspend fun seedReadyRoadGraph(): Long {
		val imports = database.osmImportDao()
		val importId = imports.insert(
			OsmImportEntity(
				displayName = "compliance-test.osm.pbf",
				fileUri = "content://test/compliance-test.osm.pbf",
				importedAt = 1L,
				wayCount = 0L,
				nodeCount = 0L,
				diagnosticMinLatitudeE7 = ROAD_LAT_E7,
				diagnosticMaxLatitudeE7 = ROAD_LAT_E7,
				diagnosticMinLongitudeE7 = ROAD_START_LON_E7,
				diagnosticMaxLongitudeE7 = ROAD_END_LON_E7,
				wayBboxEncodingVersion = OsmImportEntity.WAY_BBOX_ENCODING_DIRECTED_V1,
				status = OsmImportEntity.STATUS_BUILDING,
				cellIndexBuilt = 0,
			),
		)
		val road = OsmWayEntity(
			id = OSM_WAY_ID,
			wayInstanceId = 0L,
			importId = importId,
			osmVersion = 1,
			name = "Published matcher test road",
			roadClass = "residential",
			maxspeedKmh = SPEED_LIMIT_KMH,
			maxspeedExplicit = 1,
			isOneway = 0,
			geomPolylineE7 = PolylineE7Codec.encode(
				latsE7 = intArrayOf(ROAD_LAT_E7, ROAD_LAT_E7, ROAD_LAT_E7),
				lonsE7 = intArrayOf(ROAD_START_LON_E7, ROAD_MID_LON_E7, ROAD_END_LON_E7),
			),
			bboxMinLatE7 = ROAD_LAT_E7,
			bboxMaxLatE7 = ROAD_LAT_E7,
			bboxMinLonE7 = ROAD_START_LON_E7,
			bboxMaxLonE7 = ROAD_END_LON_E7,
		)
		val localWayId = database.osmWayDao().insertNewImportScopedInstances(listOf(road)).single()
		val cellKeys = when (
			val coverage = OsmGridIndex.cellCoverageForBbox(
				minLatE7 = ROAD_LAT_E7,
				maxLatE7 = ROAD_LAT_E7,
				startLonE7 = ROAD_START_LON_E7,
				endLonE7 = ROAD_END_LON_E7,
			)
		) {
			is OsmCellCoverage.Available -> coverage.cellKeys
			is OsmCellCoverage.TooLarge -> error("Test road unexpectedly exceeds the grid-cell cap")
		}
		database.osmWayCellDao().insertAll(
			cellKeys.map { cellKey -> OsmWayCellEntity(cellKey = cellKey, wayId = localWayId) },
		)
		assertEquals(
			1,
			imports.markReady(
				importId = importId,
				wayCount = 1L,
				nodeCount = 3L,
				diagnosticMinLatitudeE7 = ROAD_LAT_E7,
				diagnosticMaxLatitudeE7 = ROAD_LAT_E7,
				diagnosticMinLongitudeE7 = ROAD_START_LON_E7,
				diagnosticMaxLongitudeE7 = ROAD_END_LON_E7,
			),
		)
		return importId
	}

	private suspend fun seedDrivingSamples() {
		database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = 0L,
				endTimeMs = 3_000L,
				distanceM = 0f,
				steps = 0,
				primaryActivity = DetectedActivity.IN_VEHICLE.value,
				activityConfidence = 100,
				sampleCount = 2,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = "real-osm-matcher-test",
				createdAt = 0L,
			),
		)
		insertSample(SAMPLE_ONE_TIME_MS, SAMPLE_ONE_LON_E7)
		insertSample(SAMPLE_TWO_TIME_MS, SAMPLE_TWO_LON_E7)
	}

	private suspend fun insertSample(timeMs: Long, lonE7: Int) {
		database.locationSampleDao().insert(
			LocationSample(
				timeMs = timeMs,
				elapsedRealtimeNanos = timeMs * 1_000_000L,
				latE7 = ROAD_LAT_E7,
				lonE7 = lonE7,
				altitudeM = 100f,
				rawGpsAltitudeM = 100f,
				hAccM = 5f,
				vAccM = 10f,
				speedMps = (SPEED_LIMIT_KMH / 3.6f),
				speedAccuracyMps = 0.5f,
				provider = "fused",
				quality = SampleQuality.HIGH,
				motionState = MotionState.MOVING,
				policy = null,
				bucketId = null,
				createdAt = timeMs,
			),
		)
	}

	private class TestableVehicleComplianceLayer(
		resultProvider: suspend (LongRange) -> VehicleComplianceLayer.ProviderResult,
	) : VehicleComplianceLayer(resultProvider, PerformanceManager()) {
		suspend fun testLoadData(): VehicleComplianceLayer.Input =
			loadData(context = mockk<Context>(relaxed = true), bounds = null)

		fun testProcessData(
			input: VehicleComplianceLayer.Input,
			budgets: PerformanceManager.PerformanceBudgets,
		): VehicleComplianceLayer.Prepared = processData(input, budgets)

		fun testProduceConfig(prepared: VehicleComplianceLayer.Prepared): MapLibreLayerConfig? =
			produceConfig(prepared)
	}

	private companion object {
		const val OSM_WAY_ID = 77L
		const val ROAD_LAT_E7 = 500_000_000
		const val ROAD_START_LON_E7 = 140_000_000
		const val ROAD_MID_LON_E7 = 140_010_000
		const val ROAD_END_LON_E7 = 140_020_000
		const val SAMPLE_ONE_LON_E7 = 140_002_000
		const val SAMPLE_TWO_LON_E7 = 140_018_000
		const val SAMPLE_ONE_TIME_MS = 1_000L
		const val SAMPLE_TWO_TIME_MS = 2_000L
		const val SPEED_LIMIT_KMH = 50
		const val E7 = 10_000_000.0
		const val COORDINATE_TOLERANCE = 0.000_000_1
	}
}
