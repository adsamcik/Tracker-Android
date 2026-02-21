package com.adsamcik.tracker.sbase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
class LocationSampleDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: LocationSampleDao

	@BeforeEach
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.locationSampleDao()
	}

	@AfterEach
	fun tearDown() {
		database.close()
	}

	private fun createSample(
		timeMs: Long = 1000L,
		latE7: Int? = 500_000_000,
		lonE7: Int? = 140_000_000,
		altitudeM: Float? = 100f,
		hAccM: Float? = 5f,
		vAccM: Float? = 10f,
		speedMps: Float? = 1.5f,
		speedAccuracyMps: Float? = 0.5f,
		provider: String = "fused",
		quality: SampleQuality = SampleQuality.HIGH,
		motionState: MotionState? = MotionState.MOVING,
		policy: String? = null,
		bucketId: Long? = null
	) = LocationSample(
		timeMs = timeMs,
		elapsedRealtimeNanos = timeMs * 1_000_000L,
		latE7 = latE7,
		lonE7 = lonE7,
		altitudeM = altitudeM,
		hAccM = hAccM,
		vAccM = vAccM,
		speedMps = speedMps,
		speedAccuracyMps = speedAccuracyMps,
		provider = provider,
		quality = quality,
		motionState = motionState,
		policy = policy,
		bucketId = bucketId,
		createdAt = System.currentTimeMillis()
	)

	@Test
	fun `insert and retrieve sample by time range`() = runTest {
		val sample = createSample(timeMs = 5000L, latE7 = 500_000_000, lonE7 = 140_000_000)
		dao.insert(sample)

		val results = dao.getAllBetween(4000L, 6000L)
		results shouldHaveSize 1
		results[0].timeMs shouldBe 5000L
		results[0].latE7 shouldBe 500_000_000
		results[0].lonE7 shouldBe 140_000_000
	}

	@Test
	fun `getAllBetween returns empty for no matches`() = runTest {
		dao.insert(createSample(timeMs = 1000L))

		dao.getAllBetween(5000L, 6000L).shouldBeEmpty()
	}

	@Test
	fun `getAllBetween returns samples ordered by time`() = runTest {
		dao.insert(createSample(timeMs = 3000L))
		dao.insert(createSample(timeMs = 1000L))
		dao.insert(createSample(timeMs = 2000L))

		val results = dao.getAllBetween(0L, 5000L)
		results shouldHaveSize 3
		results[0].timeMs shouldBe 1000L
		results[1].timeMs shouldBe 2000L
		results[2].timeMs shouldBe 3000L
	}

	@Test
	fun `getAllBetweenFlow emits matching samples`() = runTest {
		dao.insert(createSample(timeMs = 1000L))
		dao.insert(createSample(timeMs = 2000L))
		dao.insert(createSample(timeMs = 5000L))

		val results = dao.getAllBetweenFlow(0L, 3000L).first()
		results shouldHaveSize 2
	}

	@Test
	fun `getNearestWithCoordinates finds closest sample`() = runTest {
		dao.insert(createSample(timeMs = 1000L, latE7 = 500_000_000, lonE7 = 140_000_000))
		dao.insert(createSample(timeMs = 3000L, latE7 = 510_000_000, lonE7 = 141_000_000))
		dao.insert(createSample(timeMs = 5000L, latE7 = 520_000_000, lonE7 = 142_000_000))

		val nearest = dao.getNearestWithCoordinates(timeMs = 2800L, toleranceMs = 1000L)
		nearest.shouldNotBeNull()
		nearest.timeMs shouldBe 3000L
	}

	@Test
	fun `getNearestWithCoordinates returns null when no sample in tolerance`() = runTest {
		dao.insert(createSample(timeMs = 1000L))

		dao.getNearestWithCoordinates(timeMs = 5000L, toleranceMs = 100L).shouldBeNull()
	}

	@Test
	fun `getNearestWithCoordinates skips samples without coordinates`() = runTest {
		dao.insert(createSample(timeMs = 3000L, latE7 = null, lonE7 = null))
		dao.insert(createSample(timeMs = 5000L, latE7 = 500_000_000, lonE7 = 140_000_000))

		val nearest = dao.getNearestWithCoordinates(timeMs = 3000L, toleranceMs = 5000L)
		nearest.shouldNotBeNull()
		nearest.timeMs shouldBe 5000L
	}

	@Test
	fun `countBetween counts samples in range`() = runTest {
		dao.insert(createSample(timeMs = 1000L))
		dao.insert(createSample(timeMs = 2000L))
		dao.insert(createSample(timeMs = 5000L))

		dao.countBetween(0L, 3000L) shouldBe 2
	}

	@Test
	fun `countBetween returns zero for empty range`() = runTest {
		dao.insert(createSample(timeMs = 1000L))

		dao.countBetween(5000L, 6000L) shouldBe 0
	}

	@Test
	fun `batch insert inserts all samples`() = runTest {
		val samples = (1..50).map { i ->
			createSample(timeMs = i * 1000L)
		}
		dao.insert(samples)

		dao.countBetween(0L, Long.MAX_VALUE) shouldBe 50
	}

	@Test
	fun `deleteAll removes all samples`() = runTest {
		dao.insert(createSample(timeMs = 1000L))
		dao.insert(createSample(timeMs = 2000L))

		dao.deleteAll()

		dao.countBetween(0L, Long.MAX_VALUE) shouldBe 0
	}

	@Test
	fun `deleteOlderThan removes old samples and returns count`() = runTest {
		dao.insert(createSample(timeMs = 1000L))
		dao.insert(createSample(timeMs = 2000L))
		dao.insert(createSample(timeMs = 5000L))

		val deleted = dao.deleteOlderThan(3000L)
		deleted shouldBe 2

		dao.countBetween(0L, Long.MAX_VALUE) shouldBe 1
		val remaining = dao.getAllBetween(0L, Long.MAX_VALUE)
		remaining[0].timeMs shouldBe 5000L
	}

	@Test
	fun `deleteOlderThan returns zero when nothing to delete`() = runTest {
		dao.insert(createSample(timeMs = 5000L))

		dao.deleteOlderThan(1000L) shouldBe 0
	}

	@Test
	fun `countWithoutCoordinates counts null coordinate samples`() = runTest {
		dao.insert(createSample(timeMs = 1000L, latE7 = 500_000_000, lonE7 = 140_000_000))
		dao.insert(createSample(timeMs = 2000L, latE7 = null, lonE7 = null))
		dao.insert(createSample(timeMs = 3000L, latE7 = null, lonE7 = 140_000_000))

		dao.countWithoutCoordinates() shouldBe 2
	}

	@Test
	fun `insert preserves all sample fields`() = runTest {
		val sample = createSample(
			timeMs = 5000L,
			latE7 = 500_123_456,
			lonE7 = 139_876_543,
			altitudeM = 42.5f,
			hAccM = 3.2f,
			vAccM = 8.1f,
			speedMps = 2.7f,
			speedAccuracyMps = 0.3f,
			provider = "gps",
			quality = SampleQuality.MEDIUM,
			motionState = MotionState.STILL,
			policy = "ACTIVE_ELEVATED",
			bucketId = 42L
		)
		dao.insert(sample)

		val result = dao.getAllBetween(4000L, 6000L).single()
		result.latE7 shouldBe 500_123_456
		result.lonE7 shouldBe 139_876_543
		result.altitudeM shouldBe 42.5f
		result.hAccM shouldBe 3.2f
		result.speedMps shouldBe 2.7f
		result.provider shouldBe "gps"
		result.quality shouldBe SampleQuality.MEDIUM
		result.motionState shouldBe MotionState.STILL
		result.policy shouldBe "ACTIVE_ELEVATED"
		result.bucketId shouldBe 42L
	}

	@Test
	fun `insert sample with null optional fields`() = runTest {
		val sample = createSample(
			timeMs = 1000L,
			latE7 = null,
			lonE7 = null,
			altitudeM = null,
			hAccM = null,
			vAccM = null,
			speedMps = null,
			speedAccuracyMps = null,
			motionState = null,
			policy = null,
			bucketId = null
		)
		dao.insert(sample)

		val result = dao.getAllBetween(0L, 2000L).single()
		result.latE7.shouldBeNull()
		result.lonE7.shouldBeNull()
		result.altitudeM.shouldBeNull()
		result.motionState.shouldBeNull()
		result.policy.shouldBeNull()
		result.bucketId.shouldBeNull()
	}
}
