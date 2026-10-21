package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The existing deletion table fences old live callbacks, with no session or new schema. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmbientSensorConsentErasureRoomTest {
	private lateinit var database: AppDatabase
	@Before fun setup() = runTest {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext())
		database.sourceEvidenceStateDao().ensure()
	}
	@After fun close() = database.close()

	@Test fun `erasing either source fences its old consent while new collection and sibling source remain allowed`() = runTest {
		for (source in listOf(SourceDestinationOwnerEntity.SOURCE_ACTIVITY, SourceDestinationOwnerEntity.SOURCE_PRESSURE)) {
			database.sourcePolicyDao().insertConsentEpochs(listOf(consent(source, 0, true), consent(source, 1, false)))
		}
		val activity = SourceDestinationOwnerEntity.SOURCE_ACTIVITY
		val pressure = SourceDestinationOwnerEntity.SOURCE_PRESSURE
		database.withTransaction { database.fenceAmbientSensorConsentErasureInTransaction(activity, 0, 3_000) }
		database.isAmbientSensorConsentErased(activity, 0, 0) shouldBe true
		database.isAmbientSensorConsentErased(activity, 1, 0) shouldBe true
		database.isAmbientSensorConsentErased(activity, 2, 0) shouldBe false
		database.isAmbientSensorConsentErased(pressure, 0, 0) shouldBe false
		database.isAmbientSensorConsentErased(activity, 0, 1) shouldBe false
		database.withTransaction { database.fenceAmbientSensorConsentErasureInTransaction(pressure, 0, 3_000) }
		database.isAmbientSensorConsentErased(pressure, 0, 0) shouldBe true
		database.isAmbientSensorConsentErased(pressure, 2, 0) shouldBe false
	}

	@Test fun `an active all day choice cannot be erased and repeated erase cannot lower the boundary`() = runTest {
		val source = SourceDestinationOwnerEntity.SOURCE_ACTIVITY
		database.sourcePolicyDao().insertConsentEpochs(listOf(consent(source, 0, true)))
		shouldThrow<IllegalArgumentException> {
			database.withTransaction { database.fenceAmbientSensorConsentErasureInTransaction(source, 0, 2_000) }
		}
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.sourcePolicyDao().insertConsentEpochs(listOf(consent(source, 1, false)))
		database.withTransaction { database.fenceAmbientSensorConsentErasureInTransaction(source, 0, 3_000) }
		database.withTransaction { database.fenceAmbientSensorConsentErasureInTransaction(source, 0, 4_000) }
		database.sourceDeletionFenceDao().countAll() shouldBe 1L
		database.isAmbientSensorConsentErased(source, 0, 0) shouldBe true
		database.isAmbientSensorConsentErased(source, 2, 0) shouldBe false
	}

	private fun consent(source: Int, epoch: Long, enabled: Boolean) = SourceConsentEpochEntity(
		sourceKind = source, purpose = SourceBrokerPurpose.AMBIENT_PRODUCT, epoch = epoch,
		eligible = enabled, persistenceEligible = enabled, policyRevision = epoch + 1,
		effectiveBootId = "boot", effectiveElapsedRealtimeNanos = epoch * 1_000,
		effectiveWallTimeMs = epoch * 1_000, changeReason = "TEST",
	)
}
