package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.PortableActivityIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureIdentityKind
import com.adsamcik.tracker.shared.model.pressure.portable.PortablePressureOpaqueIdentity
import io.kotest.assertions.throwables.shouldThrow
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
class NativePortableOwnerLookupRoomTest {
	private lateinit var database: AppDatabase
	@Before fun setUp() { database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>()) }
	@After fun tearDown() = database.close()

	@Test
	fun exactOlderOwnerResolvesWithoutInspectingUnrelatedCandidateCohorts() = runTest {
		database.insertUnrelatedNativePortableOwners(140, 2)
		val seeds = database.withTransaction { database.nativePortableOwnerSeeds(2, sequenceOf(activityKey(0)), 2) }
		seeds.single().logicalTrackingId shouldBe "unrelated-2-logical-0"
	}

	@Test
	fun noMatchAndAnotherSourceRemainIndependent() = runTest {
		database.insertUnrelatedNativePortableOwners(140, 2)
		database.withTransaction {
			database.nativePortableOwnerSeeds(2, sequenceOf("a".repeat(64)), 2) shouldBe emptyList()
			val pressureKey = PortablePressureOpaqueIdentity.derive(PortablePressureIdentityKind.LOGICAL_ENTRY, "unrelated-2-logical-0").value
			database.nativePortableOwnerSeeds(4, sequenceOf(pressureKey), 2) shouldBe emptyList()
		}
	}

	@Test
	fun actualRelevantOwnerOverflowNeverBecomesAnEmptyLookup() = runTest {
		database.insertUnrelatedNativePortableOwners(3, 2)
		shouldThrow<NativePortableOwnerBudgetExceeded> {
			database.withTransaction { database.nativePortableOwnerSeeds(2, (0..2).asSequence().map(::activityKey), 2) }
		}
	}

	@Test
	fun orphanedExactOwnerNeverBecomesAClaimThatNoCounterpartExists() = runTest {
		database.insertUnrelatedNativePortableOwners(1, 2)
		database.openHelper.writableDatabase.execSQL("DELETE FROM session_segment")
		shouldThrow<NativePortableOwnerMembershipInvalid> {
			database.withTransaction { database.nativePortableOwnerSeeds(2, sequenceOf(activityKey(0)), 2) }
		}
	}

	private fun activityKey(index: Int) = PortableActivityOpaqueIdentity.derive(
		PortableActivityIdentityKind.LOGICAL_ENTRY, "unrelated-2-logical-$index",
	).value
}
