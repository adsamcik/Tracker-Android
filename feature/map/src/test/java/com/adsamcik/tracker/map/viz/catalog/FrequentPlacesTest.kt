package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.shared.base.database.dao.FrequentPlaceDao
import com.adsamcik.tracker.shared.base.database.data.FrequentPlaceEntity
import com.adsamcik.tracker.map.viz.VizRequest
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the Frequent Places visualization: the stable log-scaled visit weight, the DAO source
 * (E7 -> degrees, count -> weight) and the pipeline factory id.
 */
@DisplayName("Frequent Places")
class FrequentPlacesTest {

	@Nested
	@DisplayName("visitWeight")
	inner class VisitWeightScale {

		@Test
		fun `is zero for an unvisited place and monotonic increasing`() {
			visitWeight(0) shouldBe (0.0 plusOrMinus 1e-9)
			(visitWeight(1) < visitWeight(10)) shouldBe true
			(visitWeight(10) < visitWeight(50)) shouldBe true
		}

		@Test
		fun `saturates at the reference count`() {
			visitWeight(50) shouldBe (1.0 plusOrMinus 1e-9)
			visitWeight(500) shouldBe (1.0 plusOrMinus 1e-9) // clamped
		}
	}

	@Nested
	@DisplayName("frequentPlaceSource")
	inner class Source {

		private val dao: FrequentPlaceDao = mockk()

		private fun place(latE7: Int, lonE7: Int, visits: Int) = FrequentPlaceEntity(
			centerLatE7 = latE7,
			centerLonE7 = lonE7,
			radiusM = 50f,
			visitCount = visits,
			firstVisitMs = 0L,
			lastVisitMs = 1_000L,
			autoCategory = null,
			createdAt = 0L,
		)

		@Test
		fun `maps E7 centres to degrees and counts to weights`() = runTest {
			coEvery { dao.getAll(any()) } returns listOf(
				place(latE7 = 500_875_000, lonE7 = 144_213_000, visits = 50),
			)
			val features = frequentPlaceSource(dao).load(VizRequest(0L..Long.MAX_VALUE, bounds = null))
			features shouldHaveSize 1
			features.single().lat shouldBe (50.0875 plusOrMinus 1e-6)
			features.single().lon shouldBe (14.4213 plusOrMinus 1e-6)
			features.single().weight shouldBe (1.0 plusOrMinus 1e-9) // 50 visits -> full weight
		}
	}

	@Test
	fun `frequent places pipeline has the expected id`() {
		frequentPlaces(mockk()).id shouldBe "frequent_places"
	}
}
