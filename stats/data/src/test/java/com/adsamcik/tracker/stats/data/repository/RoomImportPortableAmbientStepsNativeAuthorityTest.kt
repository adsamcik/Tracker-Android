package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.AmbientStepsSourceDeletionResult
import com.adsamcik.tracker.shared.base.database.deleteAmbientStepsAfterConsentReset
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableIdentityKind
import com.adsamcik.tracker.shared.model.steps.portable.AmbientStepsPortableOpaqueIdentity
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsArchiveV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsCoverage
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsDayV1
import com.adsamcik.tracker.shared.model.steps.portable.PortableAmbientStepsFactV1
import com.adsamcik.tracker.shared.model.steps.portable.identity
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientStepsResult
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRead
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryRecentRequest
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportMetadata
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplay
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplayValue
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.stats.data.repository.NativeAmbientStepsAuthorityFixture.Companion.DAY_END
import com.adsamcik.tracker.stats.data.repository.NativeAmbientStepsAuthorityFixture.Companion.DELETE_TIME
import com.adsamcik.tracker.stats.data.repository.NativeAmbientStepsAuthorityFixture.Companion.EMPTY_CHECKSUM
import com.adsamcik.tracker.stats.data.repository.NativeAmbientStepsAuthorityFixture.Companion.EPOCH
import com.adsamcik.tracker.stats.data.repository.NativeAmbientStepsAuthorityFixture.Companion.PROVIDER
import com.adsamcik.tracker.stats.data.repository.NativeAmbientStepsAuthorityFixture.Companion.REGISTRATION
import com.adsamcik.tracker.stats.data.repository.NativeAmbientStepsAuthorityFixture.Companion.REVOKED_CONSENT
import com.adsamcik.tracker.stats.data.repository.NativeAmbientStepsAuthorityFixture.Companion.SOURCE_INSTANCE
import com.adsamcik.tracker.stats.data.repository.NativeAmbientStepsAuthorityFixture.NativeFixture
import com.adsamcik.tracker.tracker.source.summary.RoomStepsNumericSummaryRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomImportPortableAmbientStepsNativeAuthorityTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = newDatabase()
	}

	@After
	fun tearDown() {
		if (::database.isInitialized && database.isOpen) {
			database.close()
		}
	}

	@Test
	fun `authenticated native fact blocks its exact portable identity`() = runTest {
		val fixture = seedNative(revoked = false)
		database.ambientStepsFactRevisionDao().insert(fixture.fact)

		importer().importArchive(request(fixture.archive)) shouldBe
			ImportPortableAmbientStepsResult.Blocked(
				PortableAmbientStepsImportBlockedReason.LOCAL_ORIGIN_OVERLAP,
			)
		database.importedAmbientStepsDao().archiveCount() shouldBe 0L
	}

	@Test
	fun `production numeric chain discovers native structural zone before fallback`() = runTest {
		val fixture = seedNative(revoked = false)
		database.ambientStepsFactRevisionDao().insert(fixture.fact)
		val history = DefaultAmbientStepsHistoryRepository(
			database,
			database.importedAmbientStepsDao(),
			StepsSegmentHistorySelector(
				database,
				SourceProductLaneExecutionAuthority { false },
			),
			RoomStepsCountDomainCompatibilityQuery(database),
			Dispatchers.Unconfined,
		)
		val numeric = RoomStepsNumericSummaryRepository(
			database,
			Dispatchers.Unconfined,
			history,
		)

		numeric.read(
			StepsNumericSummaryRequest(0L, 0L, "Europe/Prague"),
		) shouldBe StepsNumericSummary.Ready(listOf(StepsNumericDay(0L, 5L)))
	}

	@Test
	fun `equivalent zone partitions form one complete day without changing either saved fact`() = runTest {
		val fixture = seedNative(revoked = false)
		val first = nativeWindow(fixture.fact, 0L, DAY_END / 2L, "UTC", 3L)
		val second = nativeWindow(fixture.fact, DAY_END / 2L, DAY_END, "Etc/UTC", 7L)
		database.ambientStepsFactRevisionDao().insert(first)
		database.ambientStepsFactRevisionDao().insert(second)
		val numeric = numericRepository()
		val request = StepsNumericSummaryRequest(0L, 0L, "Europe/Prague")

		(numeric.readDisplay(request) as StepsDailyDisplay.Snapshot).days.single().value shouldBe
			StepsDailyDisplayValue.Complete(10L)
		numeric.read(request) shouldBe StepsNumericSummary.Ready(listOf(StepsNumericDay(0L, 10L)))
		for (saved in listOf(first, second)) {
			database.ambientStepsFactRevisionDao().latest(saved.writerId, saved.writerVersion,
				saved.logicalFactId) shouldBe saved
		}
	}

	@Test
	fun `equivalent zone partitions retain a recorded subtotal while strict decisions require full coverage`() = runTest {
		val fixture = seedNative(revoked = false)
		database.ambientStepsFactRevisionDao().insert(nativeWindow(fixture.fact, 0L, DAY_END / 4L, "UTC", 3L))
		database.ambientStepsFactRevisionDao().insert(nativeWindow(fixture.fact, DAY_END / 2L, DAY_END, "Etc/UTC", 7L))
		val numeric = numericRepository()
		val request = StepsNumericSummaryRequest(0L, 0L, "UTC")

		(numeric.readDisplay(request) as StepsDailyDisplay.Snapshot).days.single().value shouldBe
			StepsDailyDisplayValue.Recorded(10L, setOf(AmbientStepsHistoryCause.PARTIAL_COVERAGE))
		numeric.read(request) shouldBe StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.PARTIAL_CAPTURE)
	}

	@Test
	fun `equivalent zone labels never permit overlapping provider windows to be added`() = runTest {
		val fixture = seedNative(revoked = false)
		database.ambientStepsFactRevisionDao().insert(nativeWindow(fixture.fact, 0L, DAY_END * 3L / 4L, "UTC", 3L))
		database.ambientStepsFactRevisionDao().insert(nativeWindow(fixture.fact, DAY_END / 2L, DAY_END, "Etc/UTC", 7L))
		val numeric = numericRepository()
		val request = StepsNumericSummaryRequest(0L, 0L, "UTC")
		val display = numeric.readDisplay(request) as StepsDailyDisplay.Snapshot

		(display.days.single().value as StepsDailyDisplayValue.Unavailable).causes shouldBe
			setOf(AmbientStepsHistoryCause.FACT_OVERLAP)
		numeric.read(request) shouldBe StepsNumericSummary.Unverifiable(
			StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}

	@Test
	fun `native and imported copies under equivalent zone labels count the original measurement once`() = runTest {
		val fixture = seedNative(revoked = false)
		database.ambientStepsFactRevisionDao().insert(fixture.fact)
		val original = fixture.archive.days.single()
		val alias = PortableAmbientStepsDayV1.create(
			AmbientStepsPortableOpaqueIdentity.derive(AmbientStepsPortableIdentityKind.DAY, "0|Etc/UTC|0|$DAY_END"),
			0L, "Etc/UTC", 0L, DAY_END, null, PortableAmbientStepsCoverage.COMPLETE,
			emptyList(), 5L, original.facts, emptyList(),
		)
		val archive = PortableAmbientStepsArchiveV1.create(listOf(alias))
		RoomImportPortableAmbientSteps(database, database.importedAmbientStepsDao(), Dispatchers.Unconfined,
			localOriginSource = { emptyList() }).importArchive(request(archive, "equivalent-copy")) shouldBe
			ImportPortableAmbientStepsResult.Applied(archive.identity, 1, 1, 1, 0)
		val numeric = numericRepository()
		val request = StepsNumericSummaryRequest(0L, 0L, "UTC")

		(numeric.readDisplay(request) as StepsDailyDisplay.Snapshot).days.single().value shouldBe
			StepsDailyDisplayValue.Complete(5L)
		numeric.read(request) shouldBe StepsNumericSummary.Ready(listOf(StepsNumericDay(0L, 5L)))
	}

	@Test
	fun `display snapshot composes native and imported source-only days without current fallback zones`() = runTest {
		val fixture = seedNative(revoked = false)
		database.ambientStepsFactRevisionDao().insert(fixture.fact)
		val portable = portableArchive(1L, 7L, "display-imported")
		importer().importArchive(request(portable, "display-import", 2L * DAY_END)) shouldBe
			ImportPortableAmbientStepsResult.Applied(portable.identity, 1, 1, 1, 0)
		val history = DefaultAmbientStepsHistoryRepository(
			database, database.importedAmbientStepsDao(),
			StepsSegmentHistorySelector(database, SourceProductLaneExecutionAuthority { false }),
			RoomStepsCountDomainCompatibilityQuery(database), Dispatchers.Unconfined,
		)
		val display = RoomStepsNumericSummaryRepository(database, Dispatchers.Unconfined, history)
			.readDisplay(StepsNumericSummaryRequest(0L, 1L, "Europe/Prague")) as StepsDailyDisplay.Snapshot

		display.days.map { it.value } shouldBe listOf(
			StepsDailyDisplayValue.Complete(5L), StepsDailyDisplayValue.Complete(7L),
		)
		database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM logical_tracking_session").use {
			it.moveToFirst(); it.getLong(0) shouldBe 0L
		}
	}

	@Test
	fun `recent pagination emits exact native imported duplicate only once`() = runTest {
		val fixture = seedNative(revoked = false)
		database.ambientStepsFactRevisionDao().insert(fixture.fact)
		val bypassNativeCollision = RoomImportPortableAmbientSteps(
			database,
			database.importedAmbientStepsDao(),
			Dispatchers.Unconfined,
			localOriginSource = { emptyList() },
		)
		bypassNativeCollision.importArchive(
			request(fixture.archive, "duplicate-first", DAY_END + 10L),
		) shouldBe ImportPortableAmbientStepsResult.Applied(
			fixture.archive.identity,
			1,
			1,
			1,
			0,
		)
		bypassNativeCollision.importArchive(
			request(fixture.archive, "duplicate-later", DAY_END + 100L),
		) shouldBe ImportPortableAmbientStepsResult.Duplicate(fixture.archive.identity, 1)
		val laterArchive = portableArchive(1L, 7L, "later")
		importer().importArchive(
			request(laterArchive, "later-day", 2L * DAY_END),
		) shouldBe ImportPortableAmbientStepsResult.Applied(
			laterArchive.identity,
			1,
			1,
			1,
			0,
		)
		val history = DefaultAmbientStepsHistoryRepository(
			database,
			database.importedAmbientStepsDao(),
			StepsSegmentHistorySelector(
				database,
				SourceProductLaneExecutionAuthority { false },
			),
			RoomStepsCountDomainCompatibilityQuery(database),
			Dispatchers.Unconfined,
		)

		val first = history.readRecent(AmbientStepsHistoryRecentRequest(1)) as
			AmbientStepsHistoryRecentRead.Page
		val second = history.readRecent(
			AmbientStepsHistoryRecentRequest(1, requireNotNull(first.next)),
		) as AmbientStepsHistoryRecentRead.Page
		first.days.single().day.epochDay shouldBe 1L
		second.days.single().day.epochDay shouldBe 0L
		second.next shouldBe null
	}

	@Test
	fun `missing native cursor is retained evidence corruption not an identity conflict`() = runTest {
		assertNativeAuthorityCorruption { database.ambientStepsImportStateDao().deleteAllCursors() }
	}

	@Test
	fun `missing native destination owner is retained evidence corruption`() = runTest {
		assertNativeAuthorityCorruption {
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM source_destination_owner WHERE source_kind = ? AND destination = ?",
				arrayOf<Any?>(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
				),
			)
		}
	}

	@Test
	fun `corrupt native registration is retained evidence corruption`() = runTest {
		assertNativeAuthorityCorruption {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE provider_registration_generation SET source_instance_id = 'corrupt' " +
					"WHERE source_kind = ? AND registration_generation = ?",
				arrayOf<Any?>(SourceDestinationOwnerEntity.SOURCE_STEPS, REGISTRATION),
			)
		}
	}

	@Test
	fun `wrong native cursor epoch is retained evidence corruption`() = runTest {
		assertNativeAuthorityCorruption {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE ambient_steps_import_cursor SET collected_data_epoch = ? " +
					"WHERE registration_generation = ?",
				arrayOf(EPOCH + 1L, REGISTRATION),
			)
		}
	}

	@Test
	fun `wrong native fact epoch is retained evidence corruption`() = runTest {
		assertNativeAuthorityCorruption {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE ambient_steps_fact_revision SET collected_data_epoch = ?",
				arrayOf(EPOCH + 1L),
			)
		}
	}

	@Test
	fun `broken native gap linkage is retained evidence corruption`() = runTest {
		assertNativeAuthorityCorruption {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE ambient_steps_import_cursor SET last_gap_sequence = 1, " +
					"continuity_segment_generation = 2 WHERE registration_generation = ?",
				arrayOf(REGISTRATION),
			)
		}
	}

	@Test
	fun `broken native transition linkage is retained evidence corruption`() = runTest {
		assertNativeAuthorityCorruption {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE ambient_steps_import_cursor SET authority_transition_sequence = 1, " +
					"continuity_segment_generation = 2 WHERE registration_generation = ?",
				arrayOf(REGISTRATION),
			)
		}
	}

	@Test
	fun `unknown native writer payload is retained evidence corruption`() = runTest {
		assertNativeAuthorityCorruption {
			database.openHelper.writableDatabase.execSQL(
				"UPDATE ambient_steps_fact_revision SET writer_id = 'unknown-writer'",
			)
		}
	}

	@Test
	fun `terminal native retraction blocks old portable replay without provider authority`() = runTest {
		val fixture = seedNative(revoked = true)
		database.ambientStepsFactRevisionDao().insert(fixture.fact)
		database.deleteAmbientStepsAfterConsentReset(EPOCH, REVOKED_CONSENT, DELETE_TIME) shouldBe
			AmbientStepsSourceDeletionResult.Deleted(1, 1)

		importer().importArchive(request(fixture.archive)) shouldBe
			ImportPortableAmbientStepsResult.Blocked(
				PortableAmbientStepsImportBlockedReason.LOCAL_ORIGIN_OVERLAP,
			)
		database.importedAmbientStepsDao().archiveCount() shouldBe 0L
	}

	private suspend fun assertNativeAuthorityCorruption(
		mutate: suspend (NativeFixture) -> Unit,
	) {
		val fixture = seedNative(revoked = false)
		database.ambientStepsFactRevisionDao().insert(fixture.fact)
		mutate(fixture)

		importer().importArchive(request(fixture.archive)) shouldBe
			ImportPortableAmbientStepsResult.Unverifiable(
				PortableAmbientStepsImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		database.importedAmbientStepsDao().archiveCount() shouldBe 0L
	}

	private suspend fun seedNative(revoked: Boolean): NativeFixture =
		NativeAmbientStepsAuthorityFixture(database).seedNative(revoked)

	private fun request(
		archive: PortableAmbientStepsArchiveV1,
		receiptKey: String = "native-authority",
		receivedAtMs: Long = DAY_END,
	) = ImportPortableAmbientStepsRequest(
		archive = archive,
		receipt = PortableAmbientStepsImportReceipt(
			"$receiptKey-job",
			"$receiptKey-archive",
			"backup.trackerambientsteps",
			receivedAtMs,
		),
		metadata = PortableAmbientStepsImportMetadata(
			encodedByteCount = 1_024L,
			archiveContentChecksum = archive.contentChecksum,
			dayCount = archive.days.size,
			factCount = archive.days.sumOf { it.facts.size },
			gapCount = archive.days.sumOf { it.gaps.size },
		),
		expectedCollectedDataEpoch = EPOCH,
	)

	private fun portableArchive(
		epochDay: Long,
		count: Long,
		suffix: String,
	): PortableAmbientStepsArchiveV1 {
		val start = Math.multiplyExact(epochDay, DAY_END)
		val end = Math.addExact(start, DAY_END)
		val fact = PortableAmbientStepsFactV1.create(
			AmbientStepsPortableOpaqueIdentity.derive(
				AmbientStepsPortableIdentityKind.FACT,
				"$suffix-fact",
			),
			start,
			end,
			count,
		)
		return PortableAmbientStepsArchiveV1.create(
			listOf(
				PortableAmbientStepsDayV1.create(
					identity = AmbientStepsPortableOpaqueIdentity.derive(
						AmbientStepsPortableIdentityKind.DAY,
						"$suffix-day",
					),
					structuralEpochDay = epochDay,
					storedZoneId = "UTC",
					structuralDayStartTimeMs = start,
					structuralDayEndTimeMs = end,
					retainedFromTimeMs = null,
					coverage = PortableAmbientStepsCoverage.COMPLETE,
					partialCauses = emptyList(),
					retainedStepCount = count,
					facts = listOf(fact),
					gaps = emptyList(),
				),
			),
		)
	}

	private fun importer() = RoomImportPortableAmbientSteps(
		database,
		database.importedAmbientStepsDao(),
		Dispatchers.Unconfined,
	)

	private fun numericRepository() = RoomStepsNumericSummaryRepository(database, Dispatchers.Unconfined,
		DefaultAmbientStepsHistoryRepository(database, database.importedAmbientStepsDao(),
			StepsSegmentHistorySelector(database, SourceProductLaneExecutionAuthority { false }),
			RoomStepsCountDomainCompatibilityQuery(database), Dispatchers.Unconfined))

	private fun nativeWindow(base: AmbientStepsFactRevisionEntity, start: Long, end: Long,
		zoneId: String, count: Long): AmbientStepsFactRevisionEntity {
		val identity = AmbientStepsFactIntegrity.logicalFactId(PROVIDER, REGISTRATION, 1L, SOURCE_INSTANCE,
			start, 0L, zoneId, EPOCH)
		val unsigned = base.copy(logicalFactId = identity,
			mutationId = AmbientStepsFactIntegrity.mutationId(identity, 1L, AmbientStepsFactRevisionEntity.OPERATION_UPSERT),
			windowStartTimeMs = start, windowEndTimeMs = end, storedZoneId = zoneId, stepCount = count,
			effectChecksum = EMPTY_CHECKSUM)
		return unsigned.copy(effectChecksum = AmbientStepsFactIntegrity.effectChecksum(unsigned))
	}

	private fun newDatabase(): AppDatabase = AppDatabase.testDatabase(
		ApplicationProvider.getApplicationContext<Application>(),
	)
}
