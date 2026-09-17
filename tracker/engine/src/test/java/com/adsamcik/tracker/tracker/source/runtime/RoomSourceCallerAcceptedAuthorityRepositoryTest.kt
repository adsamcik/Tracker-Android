package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityIntegrity
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerGuardResult
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.SourceCallerRejectionReason
import com.adsamcik.tracker.tracker.api.SourceCallerReplayKind
import com.adsamcik.tracker.tracker.api.SourceCallerRequest
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilitySnapshot
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSourceCallerAcceptedAuthorityRepositoryTest {
	private lateinit var database: AppDatabase
	private lateinit var repository: RoomSourceCallerAcceptedAuthorityRepository

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		repository = RoomSourceCallerAcceptedAuthorityRepository(database)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `normalized accepted authority survives recreation and tombstones exactly`() = runTest {
		val reference = SourceCallerReplayReference("room-authority")
		repository.storeIfAbsent(reference, authority(), createdAtMs = 100L) shouldBe true
		RoomSourceCallerAcceptedAuthorityRepository(database).load(reference)
			.shouldBeInstanceOf<StoredSourceCallerAuthorityLoadResult.Available>()
			.authority shouldBe authority()
		repository.storeIfAbsent(reference, authority(), createdAtMs = 101L) shouldBe false

		repository.tombstone(reference, "SESSION_TERMINAL", 200L) shouldBe true
		repository.load(reference) shouldBe StoredSourceCallerAuthorityLoadResult.Tombstoned
	}

	@Test
	fun `tamper and unknown format never reconstruct accepted authority`() = runTest {
		val reference = SourceCallerReplayReference("tampered-authority")
		repository.storeIfAbsent(reference, authority(), createdAtMs = 100L) shouldBe true
		val row = database.sourceCallerAuthorityDao().rows(reference.value).single()
		database.sourceCallerAuthorityDao().update(
			listOf(row.copy(ownerCasToken = "tampered")),
		) shouldBe 1
		repository.load(reference) shouldBe StoredSourceCallerAuthorityLoadResult.Corrupt

		val futureReference = SourceCallerReplayReference("future-authority")
		database.sourceCallerAuthorityDao().insert(
			SourceCallerAcceptedAuthorityIntegrity.seal(
				listOf(row.copy(
					reference = futureReference.value,
					formatVersion = SourceCallerAcceptedAuthorityEntity.FORMAT_VERSION + 1,
					integrityChecksum = "pending",
				)),
			),
		)
		repository.load(futureReference) shouldBe StoredSourceCallerAuthorityLoadResult.Corrupt
	}

	@Test
	fun `authority insertion rolls back with its owning lifecycle transaction`() = runTest {
		val reference = SourceCallerReplayReference("rolled-back-authority")
		runCatching {
			database.withTransaction {
				repository.storeIfAbsent(reference, authority(), createdAtMs = 100L) shouldBe true
				error("rollback")
			}
		}

		repository.load(reference) shouldBe StoredSourceCallerAuthorityLoadResult.Missing
	}

	@Test
	fun `tombstoned authority cannot be replayed by reference`() = runTest {
		val identity = authority().permittedDemandIdentities.single()
		val guard = ExactSourceCallerGuard(
			SourceCallerAuthoritySnapshotReader {
				SourceCallerAuthoritySnapshot(
					setOf(identity),
					TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
				)
			},
			repository,
		)
		val accepted = guard.accept(
			SourceCallerRequest.ManualSessionStart(
				setOf(TrackingSource.LOCATION),
				requireNotNull(identity.manifestIdentity),
				setOf(identity),
			),
		).shouldBeInstanceOf<SourceCallerGuardResult.Permitted>()
		repository.tombstone(accepted.receipt.reference, "TERMINAL", 200L) shouldBe true

		val replay = guard.accept(
			SourceCallerRequest.Replay(
				SourceCallerReplayKind.RECOVERY,
				accepted.receipt.reference,
				TrackingPurpose.SESSION_CAPTURE,
				setOf(identity),
			),
		).shouldBeInstanceOf<SourceCallerGuardResult.Rejected>()
		replay.rejection.reason shouldBe SourceCallerRejectionReason.REPLAY_AUTHORITY_TOMBSTONED
	}

	private fun authority() = StoredSourceCallerAuthority(
		origin = StoredSourceCallerOrigin.MANUAL,
		purpose = TrackingPurpose.SESSION_CAPTURE,
		permittedDemandIdentities = setOf(
			SourceCallerDemandIdentity(
				purposeLeaseIdentity = TrackingPurposeLeaseIdentity(
					sourcePurpose =
						TrackingSource.LOCATION.forPurpose(TrackingPurpose.SESSION_CAPTURE),
					policyRevision = 3L,
					consentEpoch = 4L,
					collectedDataEpoch = 5L,
					rolloutRevision = 6L,
					executionRevision = 7L,
					ownerCasToken = "owner",
				),
				manifestIdentity = SourceCallerManifestIdentity("logical", 8L),
			),
		),
	)
}
