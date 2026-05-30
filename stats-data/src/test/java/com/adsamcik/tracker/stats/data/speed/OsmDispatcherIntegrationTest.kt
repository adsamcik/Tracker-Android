package com.adsamcik.tracker.stats.data.speed

import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.osm.io.OsmGridIndex
import com.adsamcik.tracker.osm.io.PolylineE7Codec
import com.adsamcik.tracker.osm.speed.OsmSpeedLimitSource
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.OsmImportEntity
import com.adsamcik.tracker.shared.base.database.data.OsmWayCellEntity
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.testing.fake.FakeTrackingParamsRepository
import io.kotest.matchers.doubles.shouldBeBetween
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end test that exercises the full OSM Phase 2a speed-limit lookup
 * pipeline against a real Room [AppDatabase]:
 *
 *  1. Seed `osm_import` + `osm_way` (with a real [PolylineE7Codec]-encoded
 *     polyline blob) + `osm_way_cell` (with [OsmGridIndex]-computed cell
 *     keys) directly into the database.
 *  2. Wire the production [OsmSpeedLimitSource] (real DAO calls,
 *     real polyline decoding, real haversine distance maths) into the
 *     production [DefaultSpeedLimitSource] dispatcher.
 *  3. Assert the dispatcher routes correctly across the four-cell decision
 *     matrix: coords? × OSM imported? × OSM hit?
 *
 * The PBF importer ([com.adsamcik.tracker.osm.imp.OsmImportWorker]) is
 * intentionally NOT exercised here — building a valid .osm.pbf fixture in
 * the Robolectric harness is non-trivial. By seeding the database directly,
 * this test pins the contract the worker must honour (cell keys must match
 * [OsmGridIndex.cellKeysForBbox] output; polyline blobs must be encoded by
 * [PolylineE7Codec]; bbox columns must be filled in). A separate
 * instrumented test should cover the worker → DB writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsmDispatcherIntegrationTest {

	private lateinit var database: AppDatabase
	private lateinit var osmSource: OsmSpeedLimitSource
	private lateinit var dispatcher: DefaultSpeedLimitSource
	private lateinit var trackingParams: FakeTrackingParamsRepository
	private lateinit var dispatcherScope: CoroutineScope

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext())
		osmSource = OsmSpeedLimitSource(
			osmWayDao = database.osmWayDao(),
			osmWayCellDao = database.osmWayCellDao(),
		)
		trackingParams = FakeTrackingParamsRepository(
			initialState = TrackingParamsState(vehicleSpeedLimitBaselineMps = BASELINE_50_KMH_MPS),
		)
		// Owned by this test; cancelled in tearDown.
		dispatcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
		dispatcher = DefaultSpeedLimitSource(
			fixed = FixedSpeedLimitSource(trackingParams, dispatcherScope),
			osm = osmSource,
			osmImportDao = database.osmImportDao(),
			appScope = dispatcherScope,
		)
	}

	@After
	fun tearDown() {
		dispatcherScope.cancel()
		database.close()
	}

	// region missing inputs → fixed baseline

	@Test
	fun `null lat or lon short-circuits to fixed baseline`() = runTest {
		// Even with a seeded OSM region present, null coordinates skip the OSM lookup.
		seedOsmImport()
		seedDriveableWay(
			wayId = 1L,
			maxspeedKmh = 90,
			latsE7 = intArrayOf(PRAGUE_LAT_E7, PRAGUE_LAT_E7),
			lonsE7 = intArrayOf(PRAGUE_LON_E7, PRAGUE_LON_E7 + 1_000),
		)

		val nullLat = dispatcher.limitMpsAt(epochMs = 0L, latE7 = null, lonE7 = PRAGUE_LON_E7)
		val nullLon = dispatcher.limitMpsAt(epochMs = 0L, latE7 = PRAGUE_LAT_E7, lonE7 = null)

		nullLat.shouldBeBetween(BASELINE_50_KMH_MPS, BASELINE_50_KMH_MPS, EPS)
		nullLon.shouldBeBetween(BASELINE_50_KMH_MPS, BASELINE_50_KMH_MPS, EPS)
	}

	// endregion

	// region empty OSM database → fixed baseline

	@Test
	fun `no OSM imports falls back to fixed baseline`() = runTest {
		// No osm_import row → dispatcher must skip OSM entirely.
		val limit = dispatcher.limitMpsAt(
			epochMs = 0L,
			latE7 = PRAGUE_LAT_E7,
			lonE7 = PRAGUE_LON_E7,
		)

		limit.shouldBeBetween(BASELINE_50_KMH_MPS, BASELINE_50_KMH_MPS, EPS)
	}

	// endregion

	// region OSM hit

	@Test
	fun `coordinate snapped to nearest way returns the way's limit`() = runTest {
		seedOsmImport()
		// A short driveable way around Prague city centre with 90 km/h limit.
		// Place the second vertex 1_000 E7 (≈ 11 m) east of the first so the
		// segment has non-zero length and any nearby query snaps onto it.
		seedDriveableWay(
			wayId = 1L,
			maxspeedKmh = 90,
			latsE7 = intArrayOf(PRAGUE_LAT_E7, PRAGUE_LAT_E7),
			lonsE7 = intArrayOf(PRAGUE_LON_E7, PRAGUE_LON_E7 + 1_000),
		)

		val limit = dispatcher.limitMpsAt(
			epochMs = 0L,
			// Query a point a couple of metres north of the polyline — well
			// inside OsmSpeedLimitSource.SNAP_THRESHOLD_M (50 m).
			latE7 = PRAGUE_LAT_E7 + 100,
			lonE7 = PRAGUE_LON_E7 + 500,
		)

		val expectedMps = 90.0 * (1000.0 / 3600.0)
		limit.shouldBeBetween(expectedMps, expectedMps, EPS)
	}

	@Test
	fun `multiple candidate ways picks the nearest by perpendicular distance`() = runTest {
		seedOsmImport()
		// Way 1 (50 km/h) runs east-west at PRAGUE_LAT_E7.
		seedDriveableWay(
			wayId = 10L,
			maxspeedKmh = 50,
			latsE7 = intArrayOf(PRAGUE_LAT_E7, PRAGUE_LAT_E7),
			lonsE7 = intArrayOf(PRAGUE_LON_E7, PRAGUE_LON_E7 + 1_000),
		)
		// Way 2 (130 km/h) runs east-west at PRAGUE_LAT_E7 + 3_000 (≈ 33 m north).
		seedDriveableWay(
			wayId = 20L,
			maxspeedKmh = 130,
			latsE7 = intArrayOf(PRAGUE_LAT_E7 + 3_000, PRAGUE_LAT_E7 + 3_000),
			lonsE7 = intArrayOf(PRAGUE_LON_E7, PRAGUE_LON_E7 + 1_000),
		)

		// Query a point just north of Way 1 (≈ 5 m). Both ways' bboxes pass
		// the bbox filter, but the perpendicular distance to Way 1 is much
		// smaller, so the dispatcher must return 50 km/h, not 130.
		val limit = dispatcher.limitMpsAt(
			epochMs = 0L,
			latE7 = PRAGUE_LAT_E7 + 400,
			lonE7 = PRAGUE_LON_E7 + 500,
		)

		val expected = 50.0 * (1000.0 / 3600.0)
		limit.shouldBeBetween(expected, expected, EPS)
	}

	// endregion

	// region OSM miss → fixed baseline

	@Test
	fun `coordinate outside SNAP_THRESHOLD_M falls back to fixed`() = runTest {
		seedOsmImport()
		seedDriveableWay(
			wayId = 1L,
			maxspeedKmh = 90,
			latsE7 = intArrayOf(PRAGUE_LAT_E7, PRAGUE_LAT_E7),
			lonsE7 = intArrayOf(PRAGUE_LON_E7, PRAGUE_LON_E7 + 1_000),
		)

		// Query a point ~111 m north of the polyline — well past the 50 m
		// snap threshold. The bbox filter widens by SNAP_THRESHOLD_E7 (5_000),
		// so the way will still be a candidate, but the perpendicular distance
		// check rejects it.
		val limit = dispatcher.limitMpsAt(
			epochMs = 0L,
			latE7 = PRAGUE_LAT_E7 + 10_000,
			lonE7 = PRAGUE_LON_E7 + 500,
		)

		limit.shouldBeBetween(BASELINE_50_KMH_MPS, BASELINE_50_KMH_MPS, EPS)
	}

	@Test
	fun `coordinate in a cell with no ways falls back to fixed`() = runTest {
		seedOsmImport()
		seedDriveableWay(
			wayId = 1L,
			maxspeedKmh = 90,
			latsE7 = intArrayOf(PRAGUE_LAT_E7, PRAGUE_LAT_E7),
			lonsE7 = intArrayOf(PRAGUE_LON_E7, PRAGUE_LON_E7 + 1_000),
		)

		// Berlin — far enough from Prague that none of the seeded cell keys
		// (3×3 around Prague) include Berlin's cell, so the candidate list is
		// empty and OsmSpeedLimitSource returns null without loading any rows.
		val berlinLatE7 = (52.5200 * 1e7).toInt()
		val berlinLonE7 = (13.4050 * 1e7).toInt()

		val limit = dispatcher.limitMpsAt(
			epochMs = 0L,
			latE7 = berlinLatE7,
			lonE7 = berlinLonE7,
		)

		limit.shouldBeBetween(BASELINE_50_KMH_MPS, BASELINE_50_KMH_MPS, EPS)
	}

	// endregion

	// region dispatcher dynamic behaviour

	@Test
	fun `deleting OSM import mid-session reverts to fixed for next call`() {
		runBlocking {
			val importId = seedOsmImport()
			seedDriveableWay(
				wayId = 1L,
				importId = importId,
				maxspeedKmh = 90,
				latsE7 = intArrayOf(PRAGUE_LAT_E7, PRAGUE_LAT_E7),
				lonsE7 = intArrayOf(PRAGUE_LON_E7, PRAGUE_LON_E7 + 1_000),
			)

			// First call: 90 km/h via OSM.
			val first = dispatcher.limitMpsAt(
				epochMs = 0L,
				latE7 = PRAGUE_LAT_E7 + 100,
				lonE7 = PRAGUE_LON_E7 + 500,
			)
			val osmExpected = 90.0 * (1000.0 / 3600.0)
			first.shouldBeBetween(osmExpected, osmExpected, EPS)

			// User deletes the imported region.
			database.osmImportDao().delete(importId)
			// The dispatcher's snapshot is now stale until Room's observeCount
			// Flow propagates the deletion. Wait for the upstream Flow to emit 0
			// before the next call — the snapshot is observable-driven, not
			// query-driven, so we must let the InvalidationTracker observer pump
			// propagate. Uses real time (runBlocking, not runTest) because Room's
			// invalidation executor runs on real threads in Robolectric and a
			// virtual-time test scheduler would deadlock against it.
			withTimeout(2_000) {
				database.osmImportDao().observeCount().first { it == 0 }
			}

			val second = dispatcher.limitMpsAt(
				epochMs = 0L,
				latE7 = PRAGUE_LAT_E7 + 100,
				lonE7 = PRAGUE_LON_E7 + 500,
			)

			second.shouldBeBetween(BASELINE_50_KMH_MPS, BASELINE_50_KMH_MPS, EPS)
		}
	}

	@Test
	fun `changing fixed baseline takes effect on the next miss`() = runTest {
		// No OSM imports: every call falls through to the fixed source.
		val initial = dispatcher.limitMpsAt(0L, PRAGUE_LAT_E7, PRAGUE_LON_E7)
		initial.shouldBeBetween(BASELINE_50_KMH_MPS, BASELINE_50_KMH_MPS, EPS)

		val newBaseline = 30.0 * (1000.0 / 3600.0)
		trackingParams.setVehicleSpeedLimitBaselineMps(newBaseline)

		val after = dispatcher.limitMpsAt(0L, PRAGUE_LAT_E7, PRAGUE_LON_E7)
		after.shouldBeBetween(newBaseline, newBaseline, EPS)
	}

	// endregion

	// --- helpers ----------------------------------------------------------------

	/** Insert an `osm_import` header row. Returns the auto-generated rowid. */
	private suspend fun seedOsmImport(
		displayName: String = "test-region.osm.pbf",
	): Long {
		// Bounding box covers ~1 deg around Prague — plenty of headroom so any
		// of the test queries fall inside its declared bbox.
		return database.osmImportDao().insert(
			OsmImportEntity(
				id = 0L,
				displayName = displayName,
				fileUri = "content://test/$displayName",
				importedAt = 1_700_000_000_000L,
				wayCount = 0L,
				nodeCount = 0L,
				minLatE7 = PRAGUE_LAT_E7 - 5_000_000,
				maxLatE7 = PRAGUE_LAT_E7 + 5_000_000,
				minLonE7 = PRAGUE_LON_E7 - 5_000_000,
				maxLonE7 = PRAGUE_LON_E7 + 5_000_000,
			),
		)
	}

	/**
	 * Insert a driveable [OsmWayEntity] AND the matching `osm_way_cell` rows.
	 * The cell keys are computed by [OsmGridIndex.cellKeysForBbox] from the
	 * way's bbox — exactly as the production importer does. The polyline blob
	 * is produced by [PolylineE7Codec.encode].
	 */
	private suspend fun seedDriveableWay(
		wayId: Long,
		importId: Long = 1L,
		maxspeedKmh: Int,
		latsE7: IntArray,
		lonsE7: IntArray,
	) {
		require(latsE7.size == lonsE7.size && latsE7.isNotEmpty()) {
			"latsE7 and lonsE7 must be the same non-empty length"
		}
		val bboxMinLatE7 = latsE7.min()
		val bboxMaxLatE7 = latsE7.max()
		val bboxMinLonE7 = lonsE7.min()
		val bboxMaxLonE7 = lonsE7.max()

		val way = OsmWayEntity(
			id = wayId,
			importId = importId,
			name = "Test Road $wayId",
			roadClass = "primary",
			maxspeedKmh = maxspeedKmh,
			maxspeedExplicit = 1,
			isOneway = 0,
			geomPolylineE7 = PolylineE7Codec.encode(latsE7, lonsE7),
			bboxMinLatE7 = bboxMinLatE7,
			bboxMaxLatE7 = bboxMaxLatE7,
			bboxMinLonE7 = bboxMinLonE7,
			bboxMaxLonE7 = bboxMaxLonE7,
		)
		database.osmWayDao().insertAll(listOf(way))

		val cellKeys = OsmGridIndex.cellKeysForBbox(
			minLatE7 = bboxMinLatE7,
			maxLatE7 = bboxMaxLatE7,
			minLonE7 = bboxMinLonE7,
			maxLonE7 = bboxMaxLonE7,
		)
		val cellRows = cellKeys.map { key -> OsmWayCellEntity(cellKey = key, wayId = wayId) }
		database.osmWayCellDao().insertAll(cellRows)
	}

	companion object {
		// Prague Old Town Square — both coords expressed in E7 (1e-7 degrees).
		private const val PRAGUE_LAT_E7: Int = 500_879_000   // 50.0879°
		private const val PRAGUE_LON_E7: Int = 144_421_000   // 14.4421°

		/** 50 km/h in m/s; matches the default `vehicleSpeedLimitBaselineMps`. */
		private const val BASELINE_50_KMH_MPS: Double = 50.0 * (1000.0 / 3600.0)

		/** Doubles must round-trip exactly through km/h → m/s arithmetic. */
		private const val EPS: Double = 1e-9
	}
}
