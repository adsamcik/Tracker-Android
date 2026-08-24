package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("pagedLocationSequence")
class PagedLocationSequenceTest {

	private val dao: LocationSampleDao = mockk()

	@BeforeEach
	fun setUp() {
		// Default: return empty to avoid infinite loops
		coEvery {
			dao.getChunkBetweenOrdered(any(), any(), any(), any(), any())
		} returns emptyList()
	}

	@Nested
	@DisplayName("Empty result")
	inner class EmptyResult {

		@Test
		fun `returns empty sequence when dao returns no data`() {
			val sequence = pagedLocationSequence(dao, 0L, 1000L, 10)
			sequence.toList().shouldBeEmpty()
		}

		@Test
		fun `queries dao exactly once for empty result`() {
			val sequence = pagedLocationSequence(dao, 0L, 1000L, 10)
			sequence.toList()

			coVerify(exactly = 1) {
				dao.getChunkBetweenOrdered(0L, 1000L, null, null, 10)
			}
		}

		@Test
		fun `generation verifier runs after the final empty page`() {
			var generation = 4L
			coEvery {
				dao.getChunkBetweenOrdered(0L, 1000L, null, null, 10)
			} coAnswers {
				generation = 5L
				emptyList()
			}

			assertThrows<IllegalStateException> {
				pagedLocationSequence(
					locationSampleDao = dao,
					fromMs = 0L,
					toMs = 1000L,
					pageSize = 10,
					verifyCollectedDataAccess = {
						check(generation == 4L) { "startup generation changed" }
					},
				).toList()
			}
		}
	}

	@Nested
	@DisplayName("Single page")
	inner class SinglePage {

		@Test
		fun `returns all items from a single partial page`() {
			val samples = listOf(createSample(1, 100L), createSample(2, 200L))
			coEvery {
				dao.getChunkBetweenOrdered(0L, 1000L, null, null, 10)
			} returns samples

			val result = pagedLocationSequence(dao, 0L, 1000L, 10).toList()

			result shouldHaveSize 2
			result[0].id shouldBe 1L
			result[1].id shouldBe 2L
		}
	}

	@Nested
	@DisplayName("Multiple pages")
	inner class MultiplePages {

		@Test
		fun `pages through data using keyset pagination`() {
			val page1 = listOf(createSample(1, 100L), createSample(2, 200L))
			val page2 = listOf(createSample(3, 300L))

			coEvery {
				dao.getChunkBetweenOrdered(0L, 1000L, null, null, 2)
			} returns page1

			coEvery {
				dao.getChunkBetweenOrdered(0L, 1000L, 200L, 2L, 2)
			} returns page2

			// page2 is partial (size < pageSize), so next page returns empty
			coEvery {
				dao.getChunkBetweenOrdered(0L, 1000L, 300L, 3L, 2)
			} returns emptyList()

			val result = pagedLocationSequence(dao, 0L, 1000L, 2).toList()

			result shouldHaveSize 3
			result[0].id shouldBe 1L
			result[1].id shouldBe 2L
			result[2].id shouldBe 3L
		}

		@Test
		fun `initial composite cursor includes only newer ids at the same timestamp`() {
			val newer = createSample(6L, 100L)
			coEvery {
				dao.getChunkBetweenOrdered(100L, 200L, 100L, 5L, 10)
			} returns listOf(newer)
			coEvery {
				dao.getChunkBetweenOrdered(100L, 200L, 100L, 6L, 10)
			} returns emptyList()

			val result = pagedLocationSequence(
				locationSampleDao = dao,
				fromMs = 100L,
				toMs = 200L,
				pageSize = 10,
				initialAfterTimeMs = 100L,
				initialAfterId = 5L,
			).toList()

			result.map { it.id } shouldBe listOf(6L)
			coVerify(exactly = 0) {
				dao.getChunkBetweenOrdered(100L, 200L, null, null, 10)
			}
		}

		@Test
		fun `stops when empty page is returned`() {
			val page1 = listOf(createSample(1, 100L), createSample(2, 200L))

			coEvery {
				dao.getChunkBetweenOrdered(0L, 1000L, null, null, 2)
			} returns page1

			coEvery {
				dao.getChunkBetweenOrdered(0L, 1000L, 200L, 2L, 2)
			} returns emptyList()

			val result = pagedLocationSequence(dao, 0L, 1000L, 2).toList()

			result shouldHaveSize 2
		}
	}

	@Nested
	@DisplayName("Lazy evaluation")
	inner class LazyEvaluation {

		@Test
		fun `does not load data until iterated`() {
			pagedLocationSequence(dao, 0L, 1000L, 10)

			coVerify(exactly = 0) {
				dao.getChunkBetweenOrdered(any(), any(), any(), any(), any())
			}
		}

		@Test
		fun `take limits loaded pages`() {
			val page1 = listOf(createSample(1, 100L), createSample(2, 200L))

			coEvery {
				dao.getChunkBetweenOrdered(0L, 1000L, null, null, 2)
			} returns page1

			val result = pagedLocationSequence(dao, 0L, 1000L, 2).take(1).toList()

			result shouldHaveSize 1
			result[0].id shouldBe 1L
		}
	}

	@Nested
	@DisplayName("Iterator contract")
	inner class IteratorContract {

		@Test
		fun `throws NoSuchElementException when exhausted`() {
			val seq = pagedLocationSequence(dao, 0L, 1000L, 10)
			val iterator = seq.iterator()

			assertThrows<NoSuchElementException> {
				iterator.next()
			}
		}

		@Test
		fun `hasNext is idempotent`() {
			val samples = listOf(createSample(1, 100L))
			coEvery {
				dao.getChunkBetweenOrdered(0L, 1000L, null, null, 10)
			} returns samples

			val iterator = pagedLocationSequence(dao, 0L, 1000L, 10).iterator()

			iterator.hasNext() shouldBe true
			iterator.hasNext() shouldBe true
			iterator.next().id shouldBe 1L
		}
	}

	private fun createSample(id: Long, timeMs: Long) = LocationSample(
		id = id,
		timeMs = timeMs,
		elapsedRealtimeNanos = 0L,
		latE7 = 501234567,
		lonE7 = 141234567,
		altitudeM = null,
		rawGpsAltitudeM = null,
		hAccM = null,
		vAccM = null,
		speedMps = null,
		speedAccuracyMps = null,
		provider = "gps",
		quality = SampleQuality.HIGH,
		motionState = null,
		policy = null,
		bucketId = null,
		createdAt = timeMs,
	)
}
