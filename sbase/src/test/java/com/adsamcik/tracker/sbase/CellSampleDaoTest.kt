package com.adsamcik.tracker.sbase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.data.CellSample
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class CellSampleDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: CellSampleDao

	@BeforeEach
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.cellSampleDao()
	}

	@AfterEach
	fun tearDown() {
		database.close()
	}

	private fun createSample(
		timeMs: Long = 1000L,
		cellId: Long = 12345L,
		lac: Int = 100,
		mcc: Int = 230,
		mnc: Int = 1,
		networkType: Int = 13,
		signalStrength: Int = -85,
		latE7: Int? = null,
		lonE7: Int? = null,
		provenance: CoordinateProvenance = CoordinateProvenance.UNKNOWN,
		createdAt: Long = System.currentTimeMillis()
	) = CellSample(
		timeMs = timeMs,
		cellId = cellId,
		lac = lac,
		mcc = mcc,
		mnc = mnc,
		networkType = networkType,
		signalStrength = signalStrength,
		latE7 = latE7,
		lonE7 = lonE7,
		provenance = provenance,
		createdAt = createdAt
	)

	// --- Insert and retrieve by time range ---

	@Test
	fun `insert single sample and retrieve by time range`()  { runTest {
		val sample = createSample(timeMs = 5000L, cellId = 999)
		dao.insert(sample)

		val results = dao.getAllBetween(4000L, 6000L)
		results shouldHaveSize 1
		results[0].cellId shouldBe 999
		results[0].timeMs shouldBe 5000L
	} }

	@Test
	fun `insert batch of samples`()  { runTest {
		val samples = listOf(
			createSample(timeMs = 1000L, cellId = 1),
			createSample(timeMs = 2000L, cellId = 2),
			createSample(timeMs = 3000L, cellId = 3)
		)
		dao.insert(samples)

		dao.getAllBetween(0L, 5000L) shouldHaveSize 3
	} }

	@Test
	fun `getAllBetween returns results ordered by time`()  { runTest {
		dao.insert(listOf(
			createSample(timeMs = 3000L, cellId = 3),
			createSample(timeMs = 1000L, cellId = 1),
			createSample(timeMs = 2000L, cellId = 2)
		))

		val results = dao.getAllBetween(0L, 5000L)
		results[0].timeMs shouldBe 1000L
		results[1].timeMs shouldBe 2000L
		results[2].timeMs shouldBe 3000L
	} }

	@Test
	fun `getAllBetween returns empty when no samples in range`()  { runTest {
		dao.insert(createSample(timeMs = 1000L))

		dao.getAllBetween(5000L, 9000L).shouldBeEmpty()
	} }

	@Test
	fun `getAllBetween is inclusive of boundary values`()  { runTest {
		dao.insert(listOf(
			createSample(timeMs = 1000L),
			createSample(timeMs = 2000L),
			createSample(timeMs = 3000L)
		))

		dao.getAllBetween(1000L, 3000L) shouldHaveSize 3
	} }

	// --- Flow queries ---

	@Test
	fun `getAllBetweenFlow emits matching samples`()  { runTest {
		dao.insert(listOf(
			createSample(timeMs = 1000L, cellId = 10),
			createSample(timeMs = 5000L, cellId = 50)
		))

		val results = dao.getAllBetweenFlow(0L, 3000L).first()
		results shouldHaveSize 1
		results[0].cellId shouldBe 10
	} }

	@Test
	fun `getAllBetweenFlow emits empty list when no matches`()  { runTest {
		val results = dao.getAllBetweenFlow(0L, 1000L).first()
		results.shouldBeEmpty()
	} }

	// --- Coordinate enrichment ---

	@Test
	fun `getSamplesWithoutCoordinates returns samples with null coords`()  { runTest {
		dao.insert(listOf(
			createSample(timeMs = 1000L, cellId = 1, latE7 = null, lonE7 = null),
			createSample(timeMs = 2000L, cellId = 2, latE7 = 490000000, lonE7 = 140000000,
				provenance = CoordinateProvenance.DIRECT),
			createSample(timeMs = 3000L, cellId = 3, latE7 = null, lonE7 = null)
		))

		val unenriched = dao.getSamplesWithoutCoordinates(10)
		unenriched shouldHaveSize 2
		unenriched.all { it.latE7 == null || it.lonE7 == null } shouldBe true
	} }

	@Test
	fun `getSamplesWithoutCoordinates respects limit`()  { runTest {
		repeat(5) { i ->
			dao.insert(createSample(timeMs = (i * 1000 + 1000).toLong(), cellId = i.toLong()))
		}

		dao.getSamplesWithoutCoordinates(3) shouldHaveSize 3
	} }

	@Test
	fun `getSamplesWithoutCoordinates returns empty when all enriched`()  { runTest {
		dao.insert(createSample(
			latE7 = 490000000,
			lonE7 = 140000000,
			provenance = CoordinateProvenance.DIRECT
		))

		dao.getSamplesWithoutCoordinates(10).shouldBeEmpty()
	} }

	@Test
	fun `updateCoordinates sets lat lon and provenance`()  { runTest {
		dao.insert(createSample(timeMs = 1000L, cellId = 42))
		val inserted = dao.getAllBetween(0L, 2000L)[0]

		dao.updateCoordinates(
			id = inserted.id,
			latE7 = 490000000,
			lonE7 = 140000000,
			provenance = CoordinateProvenance.NEAREST_LOCATION
		)

		val updated = dao.getAllBetween(0L, 2000L)[0]
		updated.latE7 shouldBe 490000000
		updated.lonE7 shouldBe 140000000
		updated.provenance shouldBe CoordinateProvenance.NEAREST_LOCATION
	} }

	@Test
	fun `countWithoutCoordinates returns correct count`()  { runTest {
		dao.insert(listOf(
			createSample(timeMs = 1000L, latE7 = null, lonE7 = null),
			createSample(timeMs = 2000L, latE7 = null, lonE7 = null),
			createSample(timeMs = 3000L, latE7 = 490000000, lonE7 = 140000000,
				provenance = CoordinateProvenance.DIRECT)
		))

		dao.countWithoutCoordinates() shouldBe 2
	} }

	@Test
	fun `countWithoutCoordinates returns zero when all enriched`()  { runTest {
		dao.insert(createSample(
			latE7 = 490000000,
			lonE7 = 140000000,
			provenance = CoordinateProvenance.DIRECT
		))

		dao.countWithoutCoordinates() shouldBe 0
	} }

	// --- Deletion ---

	@Test
	fun `deleteAll removes all samples`()  { runTest {
		dao.insert(listOf(
			createSample(timeMs = 1000L),
			createSample(timeMs = 2000L)
		))

		dao.deleteAll()
		dao.getAllBetween(0L, Long.MAX_VALUE).shouldBeEmpty()
	} }

	@Test
	fun `deleteOlderThan removes samples before timestamp`()  { runTest {
		dao.insert(listOf(
			createSample(timeMs = 1000L, cellId = 1),
			createSample(timeMs = 2000L, cellId = 2),
			createSample(timeMs = 5000L, cellId = 5)
		))

		val deleted = dao.deleteOlderThan(3000L)
		deleted shouldBe 2

		val remaining = dao.getAllBetween(0L, Long.MAX_VALUE)
		remaining shouldHaveSize 1
		remaining[0].cellId shouldBe 5
	} }

	@Test
	fun `deleteOlderThan returns zero when nothing to delete`()  { runTest {
		dao.insert(createSample(timeMs = 5000L))

		dao.deleteOlderThan(1000L) shouldBe 0
	} }

	// --- Network type and field preservation ---

	@Test
	fun `samples with different network types are stored independently`()  { runTest {
		dao.insert(listOf(
			createSample(timeMs = 1000L, cellId = 1, networkType = 1),
			createSample(timeMs = 2000L, cellId = 2, networkType = 3),
			createSample(timeMs = 3000L, cellId = 3, networkType = 13)
		))

		val all = dao.getAllBetween(0L, 5000L)
		all shouldHaveSize 3
		all.map { it.networkType }.toSet() shouldBe setOf(1, 3, 13)
	} }

	@Test
	fun `samples preserve all cell tower fields`()  { runTest {
		val sample = createSample(
			timeMs = 1000L,
			cellId = 54321,
			lac = 200,
			mcc = 262,
			mnc = 2,
			networkType = 13,
			signalStrength = -75
		)
		dao.insert(sample)

		val result = dao.getAllBetween(0L, 2000L)[0]
		result.cellId shouldBe 54321
		result.lac shouldBe 200
		result.mcc shouldBe 262
		result.mnc shouldBe 2
		result.networkType shouldBe 13
		result.signalStrength shouldBe -75
	} }
}
