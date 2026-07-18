package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.CellRadioGeoFeature
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.data.WifiRadioGeoFeature
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Radio heatmaps")
class RadioHeatmapTest {

	private val context = AggContext(zoom = 17f, quality = 1f, maxPoints = 1_000)

	@Test
	fun `wifi overlap counts distinct access points instead of repeated scans`() {
		val repeated = List(20) { index ->
			wifi(bssid = "00:11:22:33:44:55", time = index.toLong())
		} + wifi(bssid = "00:11:22:33:44:66")
		val unique = listOf(
			wifi(bssid = "00:11:22:33:44:55"),
			wifi(bssid = "00:11:22:33:44:66"),
		)

		val aggregator = WifiOverlapAggregator()
		val repeatedWeight = aggregator.aggregate(repeated, context).cells.single().weight
		val uniqueWeight = aggregator.aggregate(unique, context).cells.single().weight

		repeatedWeight shouldBe uniqueWeight
	}

	@Test
	fun `wifi constellation separates bands and estimates an access point from diverse samples`() {
		val observations = listOf(
			wifi("00:11:22:33:44:55", 50.0000, 14.0000, levelDbm = -74, frequency = 2412),
			wifi("00:11:22:33:44:55", 50.0000, 14.0004, levelDbm = -63, frequency = 2412),
			wifi("00:11:22:33:44:55", 50.0004, 14.0000, levelDbm = -66, frequency = 2412),
			wifi("00:11:22:33:44:55", 50.0004, 14.0004, levelDbm = -50, frequency = 2412),
			wifi("00:11:22:33:44:66", 50.0002, 14.0002, levelDbm = -55, frequency = 5180),
		)

		val field = WifiRadioAggregator().aggregate(observations, context)

		field.coverage.single { it.group == RadioVisualGroup.WIFI_24 }.cells shouldHaveSize 4
		field.coverage.single { it.group == RadioVisualGroup.WIFI_5 }.cells shouldHaveSize 1
		field.estimates shouldHaveSize 1
		field.estimates.single().confidence shouldBeGreaterThan 0.35
	}

	@Test
	fun `wifi samples from one position never create a precise access point estimate`() {
		val observations = List(30) { index ->
			wifi(
				bssid = "00:11:22:33:44:55",
				lat = 50.0,
				lon = 14.0,
				levelDbm = -45 - index % 5,
				time = index.toLong(),
			)
		}

		WifiRadioAggregator().aggregate(observations, context).estimates shouldHaveSize 0
	}

	@Test
	fun `a moved or reused bssid forms separate estimates instead of a false midpoint`() {
		val firstCluster = listOf(
			wifi("00:11:22:33:44:55", 50.0000, 14.0000, levelDbm = -70),
			wifi("00:11:22:33:44:55", 50.0000, 14.0004, levelDbm = -62),
			wifi("00:11:22:33:44:55", 50.0004, 14.0000, levelDbm = -64),
			wifi("00:11:22:33:44:55", 50.0004, 14.0004, levelDbm = -48),
		)
		val secondCluster = listOf(
			wifi("00:11:22:33:44:55", 50.0200, 14.0200, levelDbm = -71),
			wifi("00:11:22:33:44:55", 50.0200, 14.0204, levelDbm = -63),
			wifi("00:11:22:33:44:55", 50.0204, 14.0200, levelDbm = -65),
			wifi("00:11:22:33:44:55", 50.0204, 14.0204, levelDbm = -49),
		)

		val estimates = WifiRadioAggregator().aggregate(firstCluster + secondCluster, context).estimates

		estimates shouldHaveSize 2
		kotlin.math.abs(estimates[0].center.lat - estimates[1].center.lat) shouldBeGreaterThan 0.01
	}

	@Test
	fun `malformed bssid remains coverage only`() {
		val observations = listOf(
			wifi("not-a-mac", 50.0000, 14.0000, levelDbm = -70),
			wifi("not-a-mac", 50.0000, 14.0004, levelDbm = -62),
			wifi("not-a-mac", 50.0004, 14.0000, levelDbm = -64),
			wifi("not-a-mac", 50.0004, 14.0004, levelDbm = -48),
		)

		val field = WifiRadioAggregator().aggregate(observations, context)

		field.coverage.isNotEmpty() shouldBe true
		field.estimates shouldHaveSize 0
	}

	@Test
	fun `lte sectors sharing an eNodeB produce one conservative site estimate`() {
		val siteId = 12_345L
		val observations = listOf(
			cell((siteId shl 8) + 1, 50.0000, 14.0000, asu = 35),
			cell((siteId shl 8) + 1, 50.0000, 14.0030, asu = 55),
			cell((siteId shl 8) + 1, 50.0030, 14.0000, asu = 45),
			cell((siteId shl 8) + 1, 50.0030, 14.0030, asu = 75),
			cell((siteId shl 8) + 2, 50.0010, 14.0005, asu = 40),
			cell((siteId shl 8) + 2, 50.0010, 14.0025, asu = 60),
			cell((siteId shl 8) + 2, 50.0025, 14.0005, asu = 50),
			cell((siteId shl 8) + 2, 50.0025, 14.0025, asu = 80),
		)

		val field = CellRadioAggregator().aggregate(observations, context)

		field.coverage.single { it.group == RadioVisualGroup.CELL_LTE }.cells.isNotEmpty() shouldBe true
		field.estimates shouldHaveSize 1
		field.estimates.single().group shouldBe RadioVisualGroup.CELL_LTE
	}

	@Test
	fun `placeholder cell samples are excluded from topology`() {
		val observations = listOf(
			cell(0, 50.0000, 14.0000, asu = 35, networkType = 6),
			cell(0, 50.0000, 14.0030, asu = 55, networkType = 6),
			cell(0, 50.0030, 14.0000, asu = 45, networkType = 6),
			cell(0, 50.0030, 14.0030, asu = 75, networkType = 6),
		)

		val field = CellRadioAggregator().aggregate(observations, context)

		field.coverage shouldHaveSize 0
		field.estimates shouldHaveSize 0
	}

	@Test
	fun `android unavailable LTE identity remains coverage only`() {
		val unavailable = Int.MAX_VALUE.toLong()
		val observations = listOf(
			cell(unavailable, 50.0000, 14.0000, asu = 35),
			cell(unavailable, 50.0000, 14.0030, asu = 55),
			cell(unavailable, 50.0030, 14.0000, asu = 45),
			cell(unavailable, 50.0030, 14.0030, asu = 75),
		)

		CellRadioAggregator().aggregate(observations, context).estimates shouldHaveSize 0
	}

	@Test
	fun `zero is accepted as a documented LTE cell identity`() {
		val observations = listOf(
			cell(0, 50.0000, 14.0000, asu = 35),
			cell(0, 50.0000, 14.0030, asu = 55),
			cell(0, 50.0030, 14.0000, asu = 45),
			cell(0, 50.0030, 14.0030, asu = 75),
		)

		CellRadioAggregator().aggregate(observations, context).estimates shouldHaveSize 1
	}

	@Test
	fun `wcdma ASU uses its full 0 to 96 domain`() {
		val observations = listOf(
			cell(101, 50.0000, 14.0000, asu = 48, networkType = 3),
			cell(102, 50.0100, 14.0100, asu = 96, networkType = 3),
		)

		val weights = CellRadioAggregator()
			.aggregate(observations, context)
			.coverage
			.single { it.group == RadioVisualGroup.CELL_WCDMA }
			.cells
			.sortedBy { it.weight }
			.map { it.weight }

		weights[1] shouldBeGreaterThan weights[0]
		weights[0] shouldBeGreaterThan 0.3
	}

	@Test
	fun `wifi estimate remains near the dateline instead of averaging to Greenwich`() {
		val observations = listOf(
			wifi("00:11:22:33:44:55", 0.0000, 179.9990, levelDbm = -72),
			wifi("00:11:22:33:44:55", 0.0000, -179.9998, levelDbm = -60),
			wifi("00:11:22:33:44:55", 0.0004, 179.9990, levelDbm = -64),
			wifi("00:11:22:33:44:55", 0.0004, -179.9998, levelDbm = -48),
		)

		val estimate = WifiRadioAggregator().aggregate(observations, context).estimates.single()

		kotlin.math.abs(estimate.center.lon) shouldBeGreaterThan 179.0
	}

	@Test
	fun `radio encoder combines coverage uncertainty glow and estimate core`() {
		val field = SpatialData.RadioField(
			coverage = listOf(
				RadioCoverage(
					RadioVisualGroup.WIFI_24,
					listOf(WeightedGeoFeature(50.0, 14.0, 1L, 0.8)),
				),
			),
			estimates = listOf(
				RadioEstimate(
					group = RadioVisualGroup.WIFI_24,
					center = WeightedGeoFeature(50.0, 14.0, 1L, 0.8),
					confidence = 0.8,
					uncertaintyMeters = 30.0,
				),
			),
		)

		val config = RadioFieldEncoder.wifi().encode(field, RenderContext(quality = 1f))
			as MapLibreLayerConfig.Composite

		config.layers.any { it is MapLibreLayerConfig.Heatmap } shouldBe true
		config.layers.any { it is MapLibreLayerConfig.Circle } shouldBe true
	}

	private fun wifi(
		bssid: String,
		lat: Double = 50.0,
		lon: Double = 14.0,
		levelDbm: Int = -55,
		frequency: Int = 2412,
		time: Long = 0L,
	) = WifiRadioGeoFeature(lat, lon, time, bssid, levelDbm, frequency)

	private fun cell(
		cellId: Long,
		lat: Double,
		lon: Double,
		asu: Int?,
		networkType: Int = 4,
	) = CellRadioGeoFeature(
		lat = lat,
		lon = lon,
		time = 0L,
		cellId = cellId,
		areaCode = 42,
		mcc = 230,
		mnc = 1,
		networkType = networkType,
		asu = asu,
	)
}
