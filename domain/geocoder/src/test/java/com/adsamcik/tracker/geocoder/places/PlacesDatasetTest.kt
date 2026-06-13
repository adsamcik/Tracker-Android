package com.adsamcik.tracker.geocoder.places

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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
}
