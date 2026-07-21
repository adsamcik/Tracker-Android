package com.adsamcik.tracker.geocoder.places

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@DisplayName("PlacesDataset")
class PlacesDatasetTest {

    private fun place(lat: Double, lon: Double, name: String, country: String = "CZ", pop: Int = 1000) =
        TestPlacesAssetBuilder.TestPlace(
            latE7 = (lat * 1e7).toInt(),
            lonE7 = (lon * 1e7).toInt(),
            name = name,
            country = country,
            population = pop,
        )

    private fun datasetOf(vararg places: TestPlacesAssetBuilder.TestPlace) =
        PlacesDataset(TestPlacesAssetBuilder.build(places.toList()))

    @Nested
    @DisplayName("parsing")
    inner class Parsing {

        @Test
        fun `round-trips place fields`() {
            val ds = datasetOf(place(50.0875, 14.4213, "Prague", "CZ", 1_165_581))
            ds.placeCount shouldBe 1
            val p = ds.placeAt(0)
            p.name shouldBe "Prague"
            p.countryCode shouldBe "CZ"
            p.population shouldBe 1_165_581
            p.latitude shouldBe (50.0875 plusOrMinus 1e-4)
            p.longitude shouldBe (14.4213 plusOrMinus 1e-4)
        }

        @Test
        fun `preserves unicode names`() {
            val ds = datasetOf(place(49.7, 13.4, "Plzeň"))
            ds.placeAt(0).name shouldBe "Plzeň"
        }

        @Test
        fun `empty dataset has no nearest`() {
            val ds = PlacesDataset(TestPlacesAssetBuilder.build(emptyList()))
            ds.placeCount shouldBe 0
            ds.nearest(500_000_000, 144_000_000).shouldBeNull()
        }
    }

    @Nested
    @DisplayName("nearest")
    inner class Nearest {

        @Test
        fun `returns the closest place`() {
            val ds = datasetOf(
                place(50.0875, 14.4213, "Prague"),
                place(49.7475, 13.3776, "Plzen"),
                place(40.7128, -74.0060, "New York"),
            )
            ds.nearest((50.09 * 1e7).toInt(), (14.42 * 1e7).toInt())!!.name shouldBe "Prague"
            ds.nearest((49.75 * 1e7).toInt(), (13.38 * 1e7).toInt())!!.name shouldBe "Plzen"
            ds.nearest((40.71 * 1e7).toInt(), (-74.0 * 1e7).toInt())!!.name shouldBe "New York"
        }

        @Test
        fun `finds nearest across cell boundary`() {
            // Two places in adjacent cells; query sits near the boundary, closer to B.
            val ds = datasetOf(
                place(50.10, 14.40, "A"),
                place(50.12, 14.40, "B"),
            )
            ds.nearest((50.119 * 1e7).toInt(), (14.40 * 1e7).toInt())!!.name shouldBe "B"
        }

        @Test
        fun `finds a distant place via ring expansion`() {
            // Single place far from the query cell (empty neighbourhood) is still found.
            val ds = datasetOf(place(50.0, 14.0, "Lonely"))
            ds.nearest((50.4 * 1e7).toInt(), (14.4 * 1e7).toInt())!!.name shouldBe "Lonely"
        }

        @Test
        fun `finds nearer ring two place after same-cell candidate`() {
            val ds = datasetOf(
                place(0.2499, 0.2499, "Same cell"),
                place(0.0001, -0.2501, "Ring two"),
            )

            ds.nearest((0.0001 * 1e7).toInt(), (0.0001 * 1e7).toInt())!!.name shouldBe "Ring two"
        }

        @Test
        fun `finds nearer ring two place after ring one candidate`() {
            val ds = datasetOf(
                place(0.4999, 0.0001, "Ring one"),
                place(0.0001, -0.2501, "Ring two"),
            )

            ds.nearest((0.0001 * 1e7).toInt(), (0.0001 * 1e7).toInt())!!.name shouldBe "Ring two"
        }

        @Test
        fun `finds the only place beyond the former maximum ring`() {
            val ds = datasetOf(place(1.3, 0.0, "Distant"))

            ds.nearest(0, 0)!!.name shouldBe "Distant"
        }

        @Test
        fun `wraps longitude across the antimeridian`() {
            val ds = datasetOf(
                place(0.0, -179.99, "Across seam"),
                place(0.0, 179.5, "Same hemisphere"),
            )

            ds.nearest(0, (179.99 * 1e7).toInt())!!.name shouldBe "Across seam"
        }

        @Test
        fun `uses spherical distance near the pole`() {
            val ds = datasetOf(
                place(89.0, 10.0, "Longitude offset"),
                place(88.8, 0.0, "Latitude offset"),
            )

            ds.nearest((89.0 * 1e7).toInt(), 0)!!.name shouldBe "Longitude offset"
        }

        @Test
        fun `rejects out-of-range latitude`() {
            val ds = datasetOf(place(0.0, 0.0, "Origin"))

            ds.nearest(900_000_001, 0).shouldBeNull()
            ds.nearest(-900_000_001, 0).shouldBeNull()
        }

        @Test
        fun `normalizes out-of-range longitude`() {
            val ds = datasetOf(
                place(0.0, -160.0, "Wrapped west"),
                place(0.0, 160.0, "Wrapped east"),
                place(0.0, 150.0, "Unwrapped decoy"),
            )

            ds.nearest(0, 2_000_000_000)!!.name shouldBe "Wrapped west"
            ds.nearest(0, -2_000_000_000)!!.name shouldBe "Wrapped east"
        }

        @Test
        fun `breaks distance ties by population then record index`() {
            val populationTie = datasetOf(
                place(0.0, -1.0, "Small", pop = 100),
                place(0.0, 1.0, "Large", pop = 1_000),
            )
            populationTie.nearest(0, 0)!!.name shouldBe "Large"

            val recordTie = datasetOf(
                place(0.0, 1.0, "First", pop = 100),
                place(0.0, 1.0, "Second", pop = 100),
            )
            recordTie.nearest(0, 0)!!.name shouldBe "First"
        }

        @Test
        fun `matches an independent brute-force spherical scan`() {
            val random = Random(0x5EED)
            repeat(8) { datasetIndex ->
                val places = List(15) { placeIndex ->
                    place(
                        lat = -85.0 + random.nextDouble() * 170.0,
                        lon = -180.0 + random.nextDouble() * 360.0,
                        name = "Place-$datasetIndex-$placeIndex",
                        pop = random.nextInt(1_000_000),
                    )
                }
                val ds = datasetOf(*places.toTypedArray())

                repeat(6) {
                    val queryLat = -90.0 + random.nextDouble() * 180.0
                    val queryLon = -540.0 + random.nextDouble() * 1_080.0
                    val queryLatE7 = (queryLat * 1e7).toInt()
                    val queryLonE7 = normalizeLongitudeE7((queryLon * 1e7).toLong())
                    val expected = (0 until ds.placeCount)
                        .map { ds.placeAt(it) }
                        .minWithOrNull(
                            compareBy<GeoPlace> {
                                naiveHaversineMeters(
                                    queryLatE7 / 1e7,
                                    queryLonE7 / 1e7,
                                    it.latitude,
                                    it.longitude,
                                )
                            }.thenByDescending { it.population },
                        )

                    ds.nearest(queryLatE7, queryLonE7) shouldBe expected
                }
            }
        }
    }

    @Nested
    @DisplayName("search")
    inner class Search {

        @Test
        fun `prefix match ranks above substring match`() {
            val ds = datasetOf(
                place(0.0, 0.0, "Newcastle", pop = 100),
                place(1.0, 1.0, "New York", pop = 100),
            )
            // "new" prefixes both; tie broken by population (equal) then order — both prefix.
            val r = ds.search("new", null, null, 10)
            r.map { it.name }.toSet() shouldBe setOf("Newcastle", "New York")
        }

        @Test
        fun `exact match outranks prefix`() {
            val ds = datasetOf(
                place(0.0, 0.0, "York", pop = 100),
                place(1.0, 1.0, "Yorkshire", pop = 9_000_000),
            )
            ds.search("york", null, null, 1).first().name shouldBe "York"
        }

        @Test
        fun `is diacritic-insensitive`() {
            val ds = datasetOf(place(49.7, 13.4, "Plzeň"))
            ds.search("plzen", null, null, 5).first().name shouldBe "Plzeň"
        }

        @Test
        fun `preserves exact prefix and substring ranking with precomputed names`() {
            val ds = datasetOf(
                place(0.0, 0.0, "York", pop = 10),
                place(1.0, 1.0, "Yorkshire", pop = 1_000),
                place(2.0, 2.0, "New York", pop = 100_000),
            )

            ds.search("york", null, null, 10).map { it.name } shouldBe
                listOf("York", "Yorkshire", "New York")
        }

        @Test
        fun `ranks by population when no proximity bias`() {
            val ds = datasetOf(
                place(0.0, 0.0, "Springfield", pop = 1_000),
                place(1.0, 1.0, "Springfield", pop = 100_000),
            )
            ds.search("springfield", null, null, 1).first().population shouldBe 100_000
        }

        @Test
        fun `biases toward proximity when near is given`() {
            val ds = datasetOf(
                place(50.0, 14.0, "Springfield", pop = 1_000),
                place(40.0, -74.0, "Springfield", pop = 100_000),
            )
            // Near Prague — the small nearby Springfield should win over the bigger distant one.
            val near = ds.search("springfield", (50.01 * 1e7).toInt(), (14.01 * 1e7).toInt(), 1).first()
            near.latitude shouldBe (50.0 plusOrMinus 1e-4)
        }

        @Test
        fun `normalizes proximity longitude and rejects invalid proximity latitude`() {
            val ds = datasetOf(
                place(0.0, -160.0, "Springfield", pop = 1_000),
                place(0.0, 150.0, "Springfield", pop = 100_000),
            )

            ds.search("springfield", 0, 2_000_000_000, 1).first().longitude shouldBe
                (-160.0 plusOrMinus 1e-4)
            ds.search("springfield", 900_000_001, 0, 1) shouldHaveSize 0
        }

        @Test
        fun `blank query returns nothing`() {
            val ds = datasetOf(place(0.0, 0.0, "Anywhere"))
            ds.search("   ", null, null, 5) shouldHaveSize 0
        }

        @Test
        fun `respects the limit`() {
            val ds = datasetOf(
                place(0.0, 0.0, "Lake A"),
                place(1.0, 1.0, "Lake B"),
                place(2.0, 2.0, "Lake C"),
            )
            ds.search("lake", null, null, 2) shouldHaveSize 2
        }
    }

    @Nested
    @DisplayName("normalizeForSearch")
    inner class Normalize {

        @Test
        fun `lowercases and strips diacritics`() {
            PlacesDataset.normalizeForSearch("Plzeň") shouldBe "plzen"
            PlacesDataset.normalizeForSearch("MÜNCHEN") shouldBe "munchen"
            PlacesDataset.normalizeForSearch("  São Paulo ") shouldBe "sao paulo"
        }
    }

    private fun normalizeLongitudeE7(longitudeE7: Long): Int =
        (Math.floorMod(longitudeE7 + 1_800_000_000L, 3_600_000_000L) - 1_800_000_000L).toInt()

    private fun naiveHaversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radians = Math.PI / 180.0
        val deltaLat = (lat2 - lat1) * radians
        val deltaLon = Math.toRadians(((lon2 - lon1 + 540.0) % 360.0) - 180.0)
        val a = sin(deltaLat / 2).let { it * it } +
            cos(lat1 * radians) * cos(lat2 * radians) * sin(deltaLon / 2).let { it * it }
        val clamped = a.coerceIn(0.0, 1.0)
        return 6_371_000.0 * 2 * atan2(sqrt(clamped), sqrt(1.0 - clamped))
    }
}
