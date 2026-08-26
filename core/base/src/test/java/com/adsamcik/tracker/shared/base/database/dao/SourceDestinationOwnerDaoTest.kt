package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceDestinationOwnerDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: SourceDestinationOwnerDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.sourceDestinationOwnerDao()
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `owner changes are exact monotonic and ABA safe`() = runTest {
		dao.insertIfAbsent(legacyOwner()) shouldBe 1L
		dao.isExactOwner(STEPS, DESTINATION, LEGACY, 1L) shouldBe true

		dao.compareAndSetOwner(STEPS, DESTINATION, LEGACY, 1L, CANDIDATE, 2L, 10L) shouldBe 1
		dao.compareAndSetOwner(STEPS, DESTINATION, LEGACY, 1L, CANDIDATE, 3L, 11L) shouldBe 0
		dao.compareAndSetOwner(STEPS, DESTINATION, CANDIDATE, 2L, LEGACY, 2L, 12L) shouldBe 0
		dao.compareAndSetOwner(STEPS, DESTINATION, CANDIDATE, 2L, LEGACY, 3L, 13L) shouldBe 1

		dao.isExactOwner(STEPS, DESTINATION, LEGACY, 1L) shouldBe false
		dao.isExactOwner(STEPS, DESTINATION, LEGACY, 3L) shouldBe true
	}

	private fun legacyOwner() = SourceDestinationOwnerEntity(
		sourceKind = STEPS,
		destination = DESTINATION,
		owner = LEGACY,
		ownerGeneration = 1L,
		updatedAtMs = 0L,
	)

	private companion object {
		const val STEPS = SourceDestinationOwnerEntity.SOURCE_STEPS
		const val DESTINATION = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS
		const val LEGACY = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL
		const val CANDIDATE = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
	}
}
