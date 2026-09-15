package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedCellRequest
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellRunV1
import com.adsamcik.tracker.shared.base.database.PortableCellImportReceipt
import com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.ReadLocalPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.RoomImportPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.RoomReadLocalPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedDeletedRunEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryPage
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryQuery
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangePage
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeScope
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeUnavailableReason
import com.adsamcik.tracker.stats.api.repository.CellHistoryStructuralDay
import com.adsamcik.tracker.stats.api.repository.CellHistoryStructuralDayCompleteness
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.LocalCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.LocalCellHistorySelection
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass")
class CellHistoryRepositoryRoomTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		database = AppDatabase.testDatabase(application)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `selected and recent expand thirty three replacement runs in one Room transaction`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 33, factRunIndexes = setOf(0))
		persist(listOf(group))
		var authorityChecks = 0
		val repository = repository {
			authorityChecks += 1
			database.inTransaction()
		}

		val selected = repository.session(group.runs.first().segment.id)
		val recent = repository.recent(1)

		val selectedEntry = (selected as CellHistoryQuery.Found).entry
		val recentEntry = (recent as CellHistoryPage.Available).entries.single()
		repository.detail(requireNotNull(selectedEntry.selection)) shouldBe selected
		selectedEntry.selection.toString() shouldBe
			"LocalCellHistorySelection(identity=LocalCellHistoryIdentity)"
		repository.detail(
			LocalCellHistorySelection(LocalCellHistoryIdentity("f".repeat(64))),
		) shouldBe CellHistoryQuery.NotFound
		selectedEntry shouldBe recentEntry
		selectedEntry.startTime.raw shouldBe group.runs.first().segment.startTimeMs
		selectedEntry.endTime.raw shouldBe group.runs.last().segment.endTimeMs
		selectedEntry.observations shouldHaveSize 2
		selectedEntry.state shouldBe CellHistoryProductState.PARTIAL
		selectedEntry.causes shouldBe setOf(CellHistoryCause.SUBSCRIPTION_GROUPING_UNKNOWN)
		val grouped = database.withTransaction {
			repository.selectBySegmentIdsInTransaction(
				listOf(group.runs.first().segment.id, group.runs.last().segment.id),
			)
		} as CellComposedPage.Available
		grouped.entries.single().let { composed ->
			composed.logicalTrackingId shouldBe group.session.logicalTrackingId
			composed.physicalSegmentIds shouldBe group.runs.map { it.segment.id }
			composed.recencyStartTimeMs shouldBe group.runs.last().segment.startTimeMs
			composed.recencySegmentId shouldBe group.runs.last().segment.id
			composed.recencyTieIdentity shouldBe PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.PHYSICAL_RUN,
				group.runs.last().run.serviceRunId,
			)
			composed.capturesOnlyCell shouldBe true
			composed.entry shouldBe selectedEntry
		}
		val sourcePage = database.withTransaction {
			repository.recentCellHistoryInTransaction(1)
		} as CellSourceComposedPage.Available
		(sourcePage.entries.single() as CellSourceComposedEntry.Local).let { source ->
			source.group shouldBe grouped.entries.single()
			source.recency.memberStartTimeMs shouldBe source.group.recencyStartTimeMs
			source.recency.memberStartTimeMs shouldBe group.runs.last().segment.startTimeMs
			source.entry.startTime.raw shouldBe group.runs.first().segment.startTimeMs
			source.recency.tieIdentity shouldBe source.group.recencyTieIdentity
		}
		authorityChecks shouldBe 5
	}

	@Test
	fun `public and transactional recent suppress an exact authenticated same-origin duplicate`() =
		runTest {
			val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
			persist(listOf(group))
			val authority = SourceProductLaneExecutionAuthority { database.inTransaction() }
			val portable = database.withTransaction {
				RoomReadLocalPortableCapturedCell(database, authority)
					.readInTransaction(group.session.logicalTrackingId)
			} as ReadLocalPortableCapturedCellResult.Ready
			RoomImportPortableCapturedCell(
				database,
				UnconfinedTestDispatcher(testScheduler),
			).importEntry(
				ImportPortableCapturedCellRequest(
					entry = portable.entry,
					receipt = PortableCellImportReceipt(
						jobId = "same-origin-exact",
						entryKey = "same-origin-exact",
						sourceName = "same-origin.trackercell",
						receivedAtMs = portable.entry.endTimeMs,
					),
					expectedCollectedDataEpoch = 0L,
				),
			) shouldBe ImportPortableCapturedCellResult.Applied(
				importRevision = 1L,
				physicalRunCount = portable.entry.runs.size,
				observationCount = portable.entry.runs.sumOf { it.observations.size },
			)
			val repository = repository { database.inTransaction() }

			(repository.recent(10) as CellHistoryPage.Available).entries.single().let { entry ->
				entry.origin shouldBe CellHistoryOrigin.Local
				entry.selection shouldBe LocalCellHistorySelection(
					LocalCellHistoryIdentity(portable.entry.identity.value),
				)
			}
			val sourcePage = database.withTransaction {
				repository.recentCellHistoryInTransaction(10)
			} as CellSourceComposedPage.Available
			val source = sourcePage.entries.single() as CellSourceComposedEntry.Local
			source.group.logicalTrackingId shouldBe group.session.logicalTrackingId
			source.group.physicalSegmentIds shouldBe listOf(group.runs.single().segment.id)
			source.entry.origin shouldBe CellHistoryOrigin.Local
			source.recency.memberStartTimeMs shouldBe group.runs.single().segment.startTimeMs
			source.recency.tieIdentity shouldBe portable.entry.runs.single().identity
			database.withTransaction {
				repository.recentImportedEligibleForSharedHistoryInTransaction(10)
			} shouldBe ImportedHistoryEligiblePage.Available(
				emptyList<CellImportedHistoryEligibleEntry>(),
			)
		}

	@Test
	fun `eligible imported page is not starved by newer local candidates and uses newest run recency`() =
		runTest {
			val source = AppDatabase.testDatabase(
				ApplicationProvider.getApplicationContext<Application>(),
			)
			try {
				val importedGroup = buildGroup(
					groupIndex = 1,
					runCount = 3,
					factRunIndexes = setOf(0, 1, 2),
				)
				persist(listOf(importedGroup), target = source)
				val portable = source.withTransaction {
					RoomReadLocalPortableCapturedCell(
						source,
						SourceProductLaneExecutionAuthority { source.inTransaction() },
					).readInTransaction(importedGroup.session.logicalTrackingId)
				} as ReadLocalPortableCapturedCellResult.Ready
				persist(
					(2..6).map { index ->
						buildGroup(index, runCount = 1, factRunIndexes = setOf(0))
					},
				)
				RoomImportPortableCapturedCell(
					database,
					UnconfinedTestDispatcher(testScheduler),
				).importEntry(
					ImportPortableCapturedCellRequest(
						entry = portable.entry,
						receipt = PortableCellImportReceipt(
							jobId = "shared-history-older-import",
							entryKey = "shared-history-older-import",
							sourceName = "shared-history.trackercell",
							receivedAtMs = portable.entry.endTimeMs,
						),
						expectedCollectedDataEpoch = 0L,
					),
				) shouldBe ImportPortableCapturedCellResult.Applied(
					importRevision = 1L,
					physicalRunCount = portable.entry.runs.size,
					observationCount = portable.entry.runs.sumOf { it.observations.size },
				)
				val repository = repository { database.inTransaction() }

				(repository.recent(1) as CellHistoryPage.Available).entries.single().origin shouldBe
					CellHistoryOrigin.Local
				val page = database.withTransaction {
					repository.recentImportedEligibleForSharedHistoryInTransaction(1)
				} as ImportedHistoryEligiblePage.Available<*>
				val eligible = page.entries.single() as CellImportedHistoryEligibleEntry
				val newest = portable.entry.runs.maxWithOrNull(
					compareBy<PortableCapturedCellRunV1>(
						PortableCapturedCellRunV1::startTimeMs,
						{ it.identity.value },
					),
				)
				requireNotNull(newest)
				eligible.selection shouldBe
					(eligible.entry.origin as CellHistoryOrigin.Imported).selection
				eligible.recency.source shouldBe HistorySource.CELL
				eligible.recency.newestMemberStartTimeMs shouldBe newest.startTimeMs
				eligible.recency.newestMemberTieIdentity shouldBe
					ImportedHistoryRecencyTieIdentity(newest.identity.value)
				eligible.recency.newestMemberStartTimeMs shouldBe
					importedGroup.runs.last().segment.startTimeMs
				eligible.recency.newestMemberStartTimeMs shouldBe
					(portable.entry.startTimeMs + 2_000L)
			} finally {
				source.close()
			}
		}

	@Test
	fun `recent pages thirty three logical candidates by exact logical recency`() = runTest {
		val groups = (1..33).map { index -> buildGroup(index, runCount = 1, factRunIndexes = setOf(0)) }
		persist(groups)
		var everyCheckWasTransactional = true
		val repository = repository {
			everyCheckWasTransactional = everyCheckWasTransactional && database.inTransaction()
			true
		}

		val page = repository.recent(33) as CellHistoryPage.Available

		page.entries shouldHaveSize 33
		page.entries.map { it.startTime.raw } shouldBe groups.asReversed().map { it.runs.single().segment.startTimeMs }
		everyCheckWasTransactional shouldBe true
	}

	@Test
	fun `bounded wall and structural ranges find older local Cell entries before applying page limit`() =
		runTest {
			val groups = (1..3).map { index ->
				buildGroup(index, runCount = 1, factRunIndexes = setOf(0))
			}
			persist(groups)
			val repository = repository { true }
			val oldest = groups.first().runs.single().segment
			val newest = groups.last().runs.single().segment

			(repository.recent(1) as CellHistoryPage.Available).entries.single().startTime.raw shouldBe
				newest.startTimeMs
			val wallRequest = CellHistoryRangeRequest(
				scope = CellHistoryRangeScope.WallTime(
					EpochMs(oldest.startTimeMs),
					EpochMs(newest.endTimeMs + 1L),
				),
				limit = 1,
			)
			val firstPage = repository.range(wallRequest) as CellHistoryRangePage.Available
			firstPage.entries.single().entry.startTime.raw shouldBe newest.startTimeMs
			firstPage.continuation.toString() shouldBe "CellHistoryRangeContinuation"
			val secondPage = repository.range(
				wallRequest.copy(continuation = firstPage.continuation),
			) as CellHistoryRangePage.Available
			repository.range(
				CellHistoryRangeRequest(
					CellHistoryRangeScope.WallTime(
						EpochMs(oldest.startTimeMs + 1L),
						EpochMs(newest.endTimeMs + 1L),
					),
					1,
					firstPage.continuation,
				),
			) shouldBe CellHistoryRangePage.Unavailable(
				CellHistoryRangeUnavailableReason.INVALID_CONTINUATION,
			)
			repository { true }.range(
				wallRequest.copy(continuation = firstPage.continuation),
			) shouldBe CellHistoryRangePage.Unavailable(
				CellHistoryRangeUnavailableReason.INVALID_CONTINUATION,
			)
			val thirdPage = repository.range(
				wallRequest.copy(continuation = secondPage.continuation),
			) as CellHistoryRangePage.Available
			listOf(firstPage, secondPage, thirdPage).flatMap { page ->
				page.entries.map { it.entry.startTime.raw }
			} shouldBe groups.asReversed().map { it.runs.single().segment.startTimeMs }
			thirdPage.continuation shouldBe null

			val epochDay = Instant.ofEpochMilli(oldest.startTimeMs)
				.atZone(ZoneId.of(ZONE_ID)).toLocalDate().toEpochDay()
			val structural = repository.range(
				CellHistoryRangeRequest(
					CellHistoryRangeScope.StructuralDays(epochDay, epochDay),
					limit = 100,
				),
			) as CellHistoryRangePage.Available
			structural.entries.any { entry -> entry.entry.startTime.raw == oldest.startTimeMs } shouldBe true
			structural.entries.flatMap { it.structuralDays }.toSet() shouldBe
				setOf(CellHistoryStructuralDay(epochDay, ZONE_ID))
			structural.entries.all {
				it.structuralDayCompleteness == CellHistoryStructuralDayCompleteness.EXACT
			} shouldBe true
		}

	@Test
	fun `range candidate cap plus one is a typed failure rather than an incomplete page`() = runTest {
		val groups = (1..257).map { index ->
			buildGroup(index, runCount = 1, factRunIndexes = setOf(0))
		}
		persist(groups)
		val first = groups.first().runs.single().segment
		val last = groups.last().runs.single().segment

		repository { true }.range(
			CellHistoryRangeRequest(
				CellHistoryRangeScope.WallTime(
					EpochMs(first.startTimeMs),
					EpochMs(last.endTimeMs + 1L),
				),
				limit = 100,
			),
		) shouldBe CellHistoryRangePage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
	}

	@Test
	fun `range discovers exact Cell intent before the first fact without fabricating observations`() =
		runTest {
			val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = emptySet())
			persist(listOf(group))
			val segment = group.runs.single().segment

			val range = repository { true }.range(
				CellHistoryRangeRequest(
					CellHistoryRangeScope.WallTime(
						EpochMs(segment.startTimeMs),
						EpochMs(segment.endTimeMs + 1L),
					),
					limit = 10,
				),
			) as CellHistoryRangePage.Available

			range.entries.single().entry.state shouldBe CellHistoryProductState.UNAVAILABLE
			range.entries.single().entry.causes shouldBe setOf(CellHistoryCause.PROVIDER_UNAVAILABLE)
			range.entries.single().entry.observations shouldBe emptyList()
			range.entries.single().structuralDayCompleteness shouldBe
				CellHistoryStructuralDayCompleteness.UNAVAILABLE
			val sourcePage = database.withTransaction {
				repository { true }.recentCellOnlyInTransaction(1)
			} as CellComposedPage.Available
			sourcePage.entries.single().let { composed ->
				composed.capturesOnlyCell shouldBe true
				composed.physicalSegmentIds shouldBe listOf(segment.id)
				composed.entry.state shouldBe CellHistoryProductState.UNAVAILABLE
				composed.entry.observations shouldBe emptyList()
			}
		}

	@Test
	fun `bounded source batch reports membership overflow instead of returning partial physical owners`() =
		runTest {
			val group = buildGroup(groupIndex = 1, runCount = 129, factRunIndexes = setOf(0))
			persist(listOf(group))

			database.withTransaction {
				repository { true }.selectBySegmentIdsInTransaction(
					listOf(group.runs.first().segment.id),
				)
			} shouldBe CellComposedPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
		}

	@Test
	fun `Cell fact presence cannot fabricate exact only Cell capture intent`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
		persist(listOf(group))
		val run = group.runs.single()
		val pressure = run.source.copy(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			consentEpoch = run.source.consentEpoch + 10L,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			writerOwner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
			writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
		)
		database.sourceSessionDao().insertManifestSources(listOf(pressure))
		val checksum = SessionManifestIntegrity.compute(run.manifest, listOf(run.source, pressure))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_version SET manifest_checksum = ? " +
				"WHERE logical_tracking_id = ? AND manifest_revision = ?",
			arrayOf(checksum, group.session.logicalTrackingId, run.manifest.manifestRevision),
		)
		val repository = repository { true }

		val grouped = database.withTransaction {
			repository.selectBySegmentIdsInTransaction(listOf(run.segment.id))
		} as CellComposedPage.Available
		grouped.entries.single().capturesOnlyCell shouldBe false
		(database.withTransaction {
			repository.recentCellOnlyInTransaction(1)
		} as CellComposedPage.Available).entries shouldBe emptyList()
	}

	@Test
	fun `cursor-carried scope exposes a moved current lineage as integrity failure`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
		persist(listOf(group))
		val moved = group.runs.single().facts.first().logicalFactId
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_revision SET logical_tracking_id = 'moved-logical' " +
				"WHERE logical_fact_id = ?",
			arrayOf(moved),
		)

		val page = repository { true }.recent(1) as CellHistoryPage.Available

		page.entries.single().state shouldBe CellHistoryProductState.FAILED
		page.entries.single().causes shouldBe setOf(CellHistoryCause.FACT_INTEGRITY_FAILED)
		page.entries.single().observations shouldBe emptyList()
	}

	@Test
	fun `opaque local lookup rejects a run swapped onto another logical segment`() = runTest {
		val first = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
		val second = buildGroup(groupIndex = 2, runCount = 1, factRunIndexes = setOf(0))
		persist(listOf(first, second))
		val repository = repository { true }
		val selection = requireNotNull(
			(repository.session(first.runs.single().segment.id) as CellHistoryQuery.Found)
				.entry.selection,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET session_segment_id = NULL WHERE service_run_id = ?",
			arrayOf(second.runs.single().run.serviceRunId),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET session_segment_id = ? WHERE service_run_id = ?",
			arrayOf(
				second.runs.single().segment.id,
				first.runs.single().run.serviceRunId,
			),
		)

		assertInvalidLocalSelection(repository, selection)
	}

	@Test
	fun `opaque local lookup reports invalid membership after identity owner loses all runs`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = emptySet())
		database.sourceSessionDao().insertSession(group.session)
		val selection = LocalCellHistorySelection(
			LocalCellHistoryIdentity(
				PortableCellOpaqueIdentity.derive(
					PortableCellIdentityKind.LOGICAL_ENTRY,
					group.session.logicalTrackingId,
				).value,
			),
		)

		assertInvalidLocalSelection(repository { true }, selection)
	}

	@Test
	fun `opaque local lookup reports invalid membership when selected run segment is missing`() =
		runTest {
			val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
			persist(listOf(group))
			val repository = repository { true }
			val selection = requireNotNull(
				(repository.session(group.runs.single().segment.id) as CellHistoryQuery.Found)
					.entry.selection,
			)
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM session_segment WHERE id = ?",
				arrayOf(group.runs.single().segment.id),
			)

			assertInvalidLocalSelection(repository, selection)
		}

	@Test
	fun `authenticated local deletion receipt returns deleted until one fence pair is corrupt`() =
		runTest {
			val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
			persist(listOf(group))
			val built = group.runs.single()
			val repository = repository { true }
			val selection = requireNotNull(
				(repository.session(built.segment.id) as CellHistoryQuery.Found).entry.selection,
			) as LocalCellHistorySelection
			val deletedRun = CellCapturedDeletedRunEntity.create(
				logicalTrackingId = group.session.logicalTrackingId,
				serviceRunId = built.run.serviceRunId,
				sessionSegmentId = built.segment.id,
				startTimeMs = built.segment.startTimeMs,
				endTimeMs = built.segment.endTimeMs,
				collectedDataEpoch = 0L,
				deletedAtMs = 900L,
			)
			database.withTransaction {
				val dao = database.cellCapturedFactDao()
				dao.insertEntryDeletionReceipt(
					CellCapturedEntryDeletionReceiptEntity.create(
						logicalTrackingId = group.session.logicalTrackingId,
						entryIdentity = selection.identity.value,
						collectedDataEpoch = 0L,
						runFootprints = listOf(deletedRun),
						deletedAtMs = 900L,
					),
				)
				dao.insertDeletedRuns(listOf(deletedRun))
				database.sourceDeletionFenceDao().insertIfAbsent(
					SourceDeletionFenceEntity.createLogicalServiceRun(
						SourceDestinationOwnerEntity.SOURCE_CELL,
						SessionManifestPurposeCode.SESSION_CAPTURE,
						group.session.logicalTrackingId,
						built.run.serviceRunId,
						1L,
						0L,
						900L,
					),
				)
				dao.insertDeletionGeneration(
					com.adsamcik.tracker.shared.base.database.data
						.CellCaptureDeletionGenerationEntity(
							group.session.logicalTrackingId,
							built.run.serviceRunId,
							0L,
							1L,
							900L,
						),
				)
				val ids = built.cursors.map { it.logicalFactId }
				dao.deleteExactCursors(
					SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
					SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
					ids,
				)
				dao.deleteExactRevisionLineages(
					SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
					SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
					ids,
				)
				database.sessionSegmentDao().deleteExact(
					built.segment.id,
					group.session.logicalTrackingId,
					built.run.serviceRunId,
				)
				database.sourceEvidenceStateDao().incrementRevision(900L)
			}

			repeat(2) {
				(repository.detail(selection) as CellHistoryQuery.Found).entry.let { deleted ->
					deleted.selection shouldBe selection
					deleted.state shouldBe CellHistoryProductState.DELETED
					deleted.causes shouldBe setOf(CellHistoryCause.DELETED)
					deleted.startTime.raw shouldBe built.segment.startTimeMs
					deleted.endTime.raw shouldBe built.segment.endTimeMs
					deleted.observations shouldBe emptyList()
				}
			}
			database.openHelper.writableDatabase.execSQL(
				"UPDATE cell_capture_deletion_generation SET generation = 2 " +
					"WHERE logical_tracking_id = ? AND service_run_id = ?",
				arrayOf(group.session.logicalTrackingId, built.run.serviceRunId),
			)
			(repository.detail(selection) as CellHistoryQuery.Found).entry.let { corrupt ->
				corrupt.state shouldBe CellHistoryProductState.FAILED
				corrupt.causes shouldBe setOf(CellHistoryCause.FACT_INTEGRITY_FAILED)
				corrupt.observations shouldBe emptyList()
			}
		}

	@Test
	fun `cursor-carried scope exposes a missing current revision as integrity failure`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
		persist(listOf(group))
		val cursor = ownerCursor(group)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM cell_captured_fact_revision WHERE logical_fact_id = ? AND semantic_revision = ?",
			arrayOf(cursor.logicalFactId, cursor.latestSemanticRevision),
		)

		assertRecentFactIntegrityFailure()
	}

	@Test
	fun `cursor-carried scope exposes every corrupt current head field as integrity failure`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
		persist(listOf(group))
		val cursor = ownerCursor(group)
		val corruptions = listOf(
			"latest_semantic_revision" to cursor.latestSemanticRevision + 1L,
			"latest_mutation_id" to sha256("wrong-current-mutation"),
			"latest_effect_checksum" to sha256("wrong-current-effect"),
			"latest_source_admission_ordinal" to cursor.latestSourceAdmissionOrdinal + 1L,
		)
		corruptions.forEach { (column, value) ->
			database.openHelper.writableDatabase.execSQL(
				"UPDATE cell_captured_fact_cursor SET $column = ? WHERE logical_fact_id = ?",
				arrayOf(value, cursor.logicalFactId),
			)
			assertRecentFactIntegrityFailure()
			restoreCursorHead(cursor)
		}
	}

	@Test
	fun `coverage-only fact cannot reuse aggregate below deletion high water`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
		val ownerOrdinal = group.runs.single().facts.first().sourceAdmissionOrdinal
		persist(listOf(group), SourceEvidenceState(
			collectedDataEpoch = 0L,
			deletedSourceEventHighWaterOrdinal = ownerOrdinal,
		))

		val query = repository { true }.session(group.runs.single().segment.id)

		val entry = (query as CellHistoryQuery.Found).entry
		entry.state shouldBe CellHistoryProductState.UNAVAILABLE
		entry.causes shouldBe setOf(CellHistoryCause.PRIVACY_EPOCH_MISMATCH)
		entry.observations shouldBe emptyList()
	}

	@Test
	fun `coverage-only fact cannot reuse aggregate before retention floor`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 1, factRunIndexes = setOf(0))
		val facts = group.runs.single().facts
		val owner = facts.first { it.factKind == CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE }
		val dependent = facts.first { it.factKind == CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY }
		val floor = owner.observedWallTimeMs + owner.wallTimeUncertaintyMs + 1L
		check(floor < dependent.observedWallTimeMs - dependent.wallTimeUncertaintyMs)
		persist(listOf(group), SourceEvidenceState(collectedDataEpoch = 0L, retainedFromMs = floor))

		val query = repository { true }.session(group.runs.single().segment.id)

		val entry = (query as CellHistoryQuery.Found).entry
		entry.state shouldBe CellHistoryProductState.UNAVAILABLE
		entry.causes shouldBe setOf(CellHistoryCause.RETENTION_LIMIT)
		entry.observations shouldBe emptyList()
	}

	@Test
	fun `membership limit plus one is a typed overflow and cancellation propagates`() = runTest {
		val group = buildGroup(groupIndex = 1, runCount = 129, factRunIndexes = setOf(0))
		persist(listOf(group))
		val repository = repository { true }

		repository.recent(1) shouldBe CellHistoryPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
		assertFailsWith<CancellationException> {
			withContext(Job().apply { cancel() }) { repository.recent(1) }
		}
	}

	private fun repository(authority: () -> Boolean) = DefaultCellHistoryRepository(
		database,
		SourceProductLaneExecutionAuthority { authority() },
		UnconfinedTestDispatcher(),
	)

	private suspend fun assertInvalidLocalSelection(
		repository: DefaultCellHistoryRepository,
		selection: com.adsamcik.tracker.stats.api.repository.CellHistoryEntrySelection,
	) {
		val entry = (repository.detail(selection) as CellHistoryQuery.Found).entry
		entry.selection shouldBe selection
		entry.state shouldBe CellHistoryProductState.FAILED
		entry.causes shouldBe setOf(CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		entry.startTime.raw shouldBe 0L
		entry.endTime.raw shouldBe 0L
		entry.observations shouldBe emptyList()
	}

	private suspend fun persist(
		groups: List<Group>,
		evidenceState: SourceEvidenceState = SourceEvidenceState(collectedDataEpoch = 0L),
		target: AppDatabase = database,
	) {
		val runs = groups.flatMap(Group::runs)
		val sessionDao = target.sourceSessionDao()
		val policyDao = target.sourcePolicyDao()
		val planDao = target.sourcePlanStateDao()
		val brokerDao = target.sourceBrokerDao()
		val factDao = target.cellCapturedFactDao()
		target.sourceEvidenceStateDao().ensure(evidenceState)
		groups.map(Group::session).forEach { sessionDao.insertSession(it) }
		target.sessionSegmentDao().insert(runs.map(BuiltRun::segment))
		runs.sortedBy { it.run.startedAtMs }.forEach { sessionDao.insertServiceRun(it.run) }
		runs.sortedBy { it.manifest.manifestRevision }.forEach { sessionDao.insertManifest(it.manifest) }
		sessionDao.insertManifestSources(runs.map(BuiltRun::source))
		policyDao.insertPolicies(runs.map(BuiltRun::policy).sortedBy(SourcePolicyEntity::policyRevision))
		policyDao.insertConsentEpochs(runs.map(BuiltRun::consent).sortedBy(SourceConsentEpochEntity::epoch))
		runs.map(BuiltRun::planHeader).sortedBy(AcquisitionPlanRevisionEntity::revision)
			.forEach { planDao.insertRevision(it) }
		planDao.insertDesiredPlans(runs.map(BuiltRun::desiredPlan).sortedBy(SourceDesiredPlanEntity::revision))
		brokerDao.insertDemands(runs.flatMap(BuiltRun::demands))
		runs.mapNotNull(BuiltRun::provider).sortedBy(ProviderRegistrationGenerationEntity::registrationGeneration)
			.forEach { brokerDao.insertRegistration(it) }
		brokerDao.insertAuthorizations(runs.flatMap(BuiltRun::authorizations))
		target.sourceProjectionStateDao().installProductLane(lane(runs.flatMap(BuiltRun::facts)
			.maxOfOrNull(CellCapturedFactRevisionEntity::sourceAdmissionOrdinal) ?: 1L))
		runs.forEach { sessionDao.saveCompleteness(it.completeness) }
		runs.flatMap(BuiltRun::facts).sortedWith(compareBy(
			CellCapturedFactRevisionEntity::logicalFactId,
			CellCapturedFactRevisionEntity::semanticRevision,
		)).forEach { factDao.insertRevision(it) }
		runs.flatMap(BuiltRun::cursors).forEach { factDao.insertCursor(it) }
	}

	private fun buildGroup(
		groupIndex: Int,
		runCount: Int,
		factRunIndexes: Set<Int>,
	): Group {
		require(groupIndex > 0 && runCount > 0)
		val logicalId = "logical-cell-$groupIndex"
		val specs = (0 until runCount).map { local ->
			val global = groupIndex * 1_000L + local + 1L
			val startWall = WALL_BASE_MS + groupIndex * 1_000_000L + local * 1_000L
			val startElapsed = 1_000_000_000L + groupIndex * 100_000_000_000L + local * 1_000_000_000L
			RunSpec(local + 1L, global, "run-$groupIndex-${local + 1}", startWall, startElapsed,
				local in factRunIndexes)
		}
		val sessionEndElapsed = specs.maxOf { it.startElapsedNanos + RUN_DURATION_NANOS }
		val sessionEndWall = specs.maxOf { it.startWallMs + RUN_DURATION_MS }
		val built = specs.map { buildRun(logicalId, it, sessionEndElapsed) }
		val last = built.last()
		val finalAdmission = built.flatMap(BuiltRun::facts)
			.maxOfOrNull(CellCapturedFactRevisionEntity::sourceAdmissionOrdinal) ?: 1L
		return Group(
			LogicalTrackingSessionEntity(
				logicalTrackingId = logicalId,
				state = "FINALIZED",
				lifecycleRevision = 2L,
				desiredPlanRevision = last.run.desiredPlanRevision,
				rolloutRevision = ROLLOUT_REVISION,
				startOrigin = START_ORIGIN,
				clockDomainId = BOOT_ID,
				startedAtMs = built.first().run.startedAtMs,
				startedElapsedNanos = built.first().run.startedElapsedNanos,
				cutoffAtMs = sessionEndWall,
				cutoffElapsedNanos = sessionEndElapsed,
				completedAtMs = sessionEndWall,
				finalAdmissionOrdinal = finalAdmission,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = specs.last().manifestRevision,
				currentIntentRevision = 1L,
				currentServiceRunId = null,
				lifecycleLeaseGeneration = LEASE_GENERATION,
				lifecycleBootId = BOOT_ID,
				automationEpoch = null,
			),
			built,
		)
	}

	@Suppress("LongMethod")
	private fun buildRun(logicalId: String, spec: RunSpec, sessionEndElapsed: Long): BuiltRun {
		val segmentId = spec.globalRevision
		val runEndElapsed = spec.startElapsedNanos + RUN_DURATION_NANOS
		val runEndWall = spec.startWallMs + RUN_DURATION_MS
		val segment = SessionSegment(
			id = segmentId,
			startTimeMs = spec.startWallMs,
			endTimeMs = runEndWall,
			distanceM = 0f,
			steps = null,
			primaryActivity = null,
			activityConfidence = null,
			sampleCount = 0,
			source = SegmentSource.USER_CREATED,
			inferenceVersion = null,
			createdAt = runEndWall,
			logicalTrackingId = logicalId,
			serviceRunId = spec.runId,
		)
		val run = SourceServiceRunEntity(
			serviceRunId = spec.runId,
			logicalTrackingId = logicalId,
			state = "FINALIZED",
			desiredPlanRevision = spec.globalRevision,
			rolloutRevision = ROLLOUT_REVISION,
			foregroundCapabilityFlags = 0L,
			startedAtMs = spec.startWallMs,
			startedElapsedNanos = spec.startElapsedNanos,
			completedAtMs = runEndWall,
			completionReason = "USER_STOP",
			bootId = BOOT_ID,
			leaseGeneration = LEASE_GENERATION,
			startOrigin = START_ORIGIN,
			desiredForegroundCapabilityFlags = 0L,
			appliedForegroundCapabilityFlags = 0L,
			runtimeAcknowledgement = "STOP_ACCEPTED",
			runtimeFailureCode = null,
			runRevision = 2L,
			startDeliveryToken = "start-${spec.globalRevision}",
			startCommandGeneration = 1L,
			preparedManifestRevision = spec.manifestRevision,
			preparedIntentRevision = 1L,
			androidDeliveryState = "FOREGROUND_ACCEPTED",
			androidDeliveryUpdatedAtMs = spec.startWallMs,
			startIsUserInitiated = true,
			startIsAmbient = false,
			sessionSegmentId = segmentId,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = runEndWall,
		)
		val source = SessionManifestSourceEntity(
			logicalTrackingId = logicalId,
			manifestRevision = spec.manifestRevision,
			sourceKind = CELL_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch = spec.globalRevision,
			persistenceEligible = true,
			qosCode = QOS_CODE,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
			writerOwner = SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			writerProjectionId = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
		)
		val unsignedManifest = SessionManifestVersionEntity(
			logicalTrackingId = logicalId,
			manifestRevision = spec.manifestRevision,
			serviceRunId = spec.runId,
			sessionMode = "MANUAL",
			sourcePolicyRevision = spec.globalRevision,
			acquisitionPlanRevision = spec.globalRevision,
			rolloutRevision = ROLLOUT_REVISION,
			startOrigin = START_ORIGIN,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = spec.startElapsedNanos,
			effectiveWallTimeMs = spec.startWallMs,
			zoneId = ZONE_ID,
			automationEpoch = null,
			changeReason = "REPLACEMENT_START",
			manifestChecksum = "pending",
		)
		val manifest = unsignedManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(source)),
		)
		val policy = SourcePolicyEntity(
			policyRevision = spec.globalRevision,
			sourceKind = CELL_SOURCE,
			enabled = true,
			qosCode = QOS_CODE,
			locationMinTimeSeconds = null,
			locationMinDistanceMeters = null,
			locationRequiredAccuracyMeters = null,
			capturePersistenceEligible = true,
			controlPersistenceEligible = false,
			ambientPersistenceEligible = false,
			captureConsentEpoch = spec.globalRevision,
			controlConsentEpoch = null,
			ambientConsentEpoch = null,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = spec.startElapsedNanos,
			effectiveWallTimeMs = spec.startWallMs,
			changeReason = "TEST",
		)
		val consent = SourceConsentEpochEntity(
			sourceKind = CELL_SOURCE,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			epoch = spec.globalRevision,
			eligible = true,
			persistenceEligible = true,
			policyRevision = spec.globalRevision,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = spec.startElapsedNanos,
			effectiveWallTimeMs = spec.startWallMs,
			changeReason = "TEST",
		)
		val desiredPlan = desiredPlan(spec.globalRevision)
		val plan = requireNotNull(CellHistoryPlanIntegrity.decode(desiredPlan))
		val planHeader = AcquisitionPlanRevisionEntity(
			revision = spec.globalRevision,
			planId = "cell-plan-${spec.globalRevision}",
			createdAtMs = spec.startWallMs,
			status = "EFFECTIVE",
			sourcePolicyRevision = spec.globalRevision,
		)
		if (!spec.hasFact) {
			return BuiltRun(segment, run, manifest, source, policy, consent, planHeader, desiredPlan,
				emptyList(), null, emptyList(), emptyList(), completeness(logicalId, spec, null))
		}
		val demand = demand(logicalId, spec, runEndElapsed, runEndWall)
		val authorization = SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE,
			registrationGeneration = spec.globalRevision,
			authorizationRevision = 1L,
			demands = listOf(demand),
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = spec.startElapsedNanos,
			effectiveWallTimeMs = spec.startWallMs,
		)
		val deny = SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE,
			registrationGeneration = spec.globalRevision,
			authorizationRevision = 2L,
			demands = emptyList(),
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = runEndElapsed,
			effectiveWallTimeMs = runEndWall,
		)
		val provider = ProviderRegistrationGenerationEntity(
			sourceKind = CELL_SOURCE,
			registrationGeneration = spec.globalRevision,
			sourceInstanceId = "cell-instance-${spec.globalRevision}",
			ownerScope = "source-broker:$CELL_SOURCE",
			clockDomainId = BOOT_ID,
			physicalConfigurationFingerprint = plan.physicalConfigurationFingerprint,
			collectedDataEpoch = 0L,
			providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
			providerProcessIncarnationId = "process-${spec.globalRevision}",
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			reservedAtMs = spec.startWallMs,
			reservedElapsedRealtimeNanos = spec.startElapsedNanos,
			acceptedAtMs = spec.startWallMs,
			acceptedElapsedRealtimeNanos = spec.startElapsedNanos,
			retiredAtMs = runEndWall,
			retiredElapsedRealtimeNanos = runEndElapsed,
			failureCode = null,
			captureCallbackBarrierAuthorizationRevision = 1L,
		)
		val authority = FactAuthority(
			logicalId, spec, segmentId, plan.physicalConfigurationFingerprint,
			authorization.first().authorizationFingerprint, sessionEndElapsed, runEndElapsed,
		)
		val ownerV1 = aggregateFact(authority, admissionOrdinal(spec, 1), correctionOpen = true)
		val ownerV2 = settle(ownerV1, runEndElapsed, sessionEndElapsed)
		val dependent = coverageFact(authority, admissionOrdinal(spec, 2), ownerV2)
		return BuiltRun(
			segment, run, manifest, source, policy, consent, planHeader, desiredPlan,
			listOf(demand), provider, authorization + deny,
			listOf(ownerV1, ownerV2, dependent),
			listOf(cursor(ownerV2), cursor(dependent)),
			completeness(logicalId, spec, dependent.sourceAdmissionOrdinal),
		)
	}

	@Suppress("LongMethod")
	private fun baseFact(
		authority: FactAuthority,
		admissionOrdinal: Long,
		coverageOffset: Long,
	): CellCapturedFactRevisionEntity {
		val deliveryIdentity = sha256("delivery-$admissionOrdinal")
		val logicalFactId = CellCapturedFactRevisionIntegrity.logicalFactId(
			deliveryIdentity, authority.logicalId, authority.spec.runId, authority.segmentId,
			authority.spec.manifestRevision, 0L, 0L,
		)
		return CellCapturedFactRevisionEntity(
			writerProjectionId = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			supersedesSemanticRevision = null,
			mutationId = CellCapturedFactRevisionIntegrity.mutationId(logicalFactId, 1L),
			factKind = CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE,
			aggregateOwnerLogicalFactId = null,
			aggregateOwnerSemanticRevision = null,
			logicalTrackingId = authority.logicalId,
			serviceRunId = authority.spec.runId,
			sessionSegmentId = authority.segmentId,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			sourceDeliveryIdentity = deliveryIdentity,
			sourceEventId = "cell-event-$admissionOrdinal",
			sourceAdmissionOrdinal = admissionOrdinal,
			walIntegrityIdentity = sha256("wal-$admissionOrdinal"),
			payloadChecksum = sha256("payload-$admissionOrdinal"),
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			sourceSequence = admissionOrdinal,
			planAttribution = CellCapturedFactRevisionEntity.PLAN_ATTRIBUTION_CAPTURED_REGISTRATION,
			payloadVersion = 1,
			canonicalProviderSemanticsDigest = deliveryIdentity,
			sourceInstanceId = "cell-instance-${authority.spec.globalRevision}",
			registrationGeneration = authority.spec.globalRevision,
			configurationRevision = authority.spec.globalRevision,
			physicalConfigurationFingerprint = authority.physicalFingerprint,
			authorizationRevision = 1L,
			authorizationFingerprint = authority.authorizationFingerprint,
			purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			sourcePolicyRevision = authority.spec.globalRevision,
			captureConsentEpoch = authority.spec.globalRevision,
			manifestRevision = authority.spec.manifestRevision,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			collectedDataEpoch = 0L,
			scopeDeletionGeneration = 0L,
			clockDomainId = BOOT_ID,
			storedZoneId = ZONE_ID,
			structuralEpochDay = Instant.ofEpochMilli(authority.spec.startWallMs + coverageOffset / 1_000_000L)
				.atZone(ZoneId.of(ZONE_ID)).toLocalDate().toEpochDay(),
			providerAcceptanceStartNanos = authority.spec.startElapsedNanos,
			providerAcceptanceEndNanos = Long.MAX_VALUE,
			authorizationEffectStartNanos = authority.spec.startElapsedNanos,
			authorizationEffectEndNanos = Long.MAX_VALUE,
			consentEffectStartNanos = authority.spec.startElapsedNanos,
			consentEffectEndNanos = Long.MAX_VALUE,
			sessionRunEffectStartNanos = authority.spec.startElapsedNanos,
			sessionRunEffectEndNanos = Long.MAX_VALUE,
			deletionEffectStartNanos = authority.spec.startElapsedNanos,
			deletionEffectEndNanos = Long.MAX_VALUE,
			maximumObservationAgeNanos = 1_000_000_000L,
			observedIntervalStartNanos = authority.spec.startElapsedNanos + coverageOffset,
			observedElapsedNanos = authority.spec.startElapsedNanos + coverageOffset,
			receivedElapsedNanos = authority.spec.startElapsedNanos + coverageOffset + 10_000_000L,
			coverageIntervalStartNanos = authority.spec.startElapsedNanos + coverageOffset,
			coverageIntervalEndNanos = authority.spec.startElapsedNanos + coverageOffset,
			observedWallTimeMs = authority.spec.startWallMs + coverageOffset / 1_000_000L,
			wallTimeUncertaintyMs = 1L,
			acquiredAtMs = authority.spec.startWallMs + coverageOffset / 1_000_000L,
			createdAtMs = authority.spec.startWallMs + coverageOffset / 1_000_000L,
			qualityFlags = 0L,
			qualityConfidence = 1f,
			availability = CellCapturedFactRevisionEntity.AVAILABILITY_AVAILABLE,
			submittedChildCount = 1,
			acceptedChildCount = 1,
			staleChildCount = 0,
			futureTimeChildCount = 0,
			missingTimeChildCount = 0,
			clockUnverifiableChildCount = 0,
			authorityMismatchChildCount = 0,
			unsupportedTechnologyChildCount = 0,
			subscriptionCompleteness = CellCapturedFactRevisionEntity.SUBSCRIPTION_COMPLETENESS_UNKNOWN,
			childCompleteness = CellCapturedFactRevisionEntity.CHILD_COMPLETENESS_COMPLETE,
			observationCount = 1,
			registeredObservationCount = 1,
			gsmCount = 0,
			cdmaCount = 0,
			wcdmaCount = 0,
			tdscdmaCount = 0,
			lteCount = 1,
			nrCount = 0,
			qualityUnknownCount = 0,
			qualityNoneOrUnknownCount = 0,
			qualityPoorCount = 0,
			qualityModerateCount = 0,
			qualityGoodCount = 1,
			qualityGreatCount = 0,
			weakObservationCount = 0,
			knownQualityObservationCount = 1,
			allKnownQualityIsWeak = false,
			effectChecksum = ZERO_SHA,
			appliedAtMs = authority.spec.startWallMs + coverageOffset / 1_000_000L,
		)
	}

	private fun aggregateFact(
		authority: FactAuthority,
		admissionOrdinal: Long,
		correctionOpen: Boolean,
	): CellCapturedFactRevisionEntity {
		val base = baseFact(authority, admissionOrdinal, OWNER_OFFSET_NANOS)
		val identified = base.copy(
			mutationId = CellCapturedFactRevisionIntegrity.mutationId(base.logicalFactId, 1L),
			providerAcceptanceEndNanos = if (correctionOpen) Long.MAX_VALUE else authority.runEndElapsed,
			authorizationEffectEndNanos = if (correctionOpen) Long.MAX_VALUE else authority.runEndElapsed,
			consentEffectEndNanos = if (correctionOpen) Long.MAX_VALUE else authority.runEndElapsed,
			sessionRunEffectEndNanos = if (correctionOpen) Long.MAX_VALUE else authority.runEndElapsed,
			deletionEffectEndNanos = if (correctionOpen) Long.MAX_VALUE else authority.sessionEndElapsed,
		)
		return identified.copy(effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(identified))
	}

	private fun settle(
		first: CellCapturedFactRevisionEntity,
		runEndElapsed: Long,
		sessionEndElapsed: Long,
	): CellCapturedFactRevisionEntity {
		val unsigned = first.copy(
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			mutationId = CellCapturedFactRevisionIntegrity.mutationId(first.logicalFactId, 2L),
			providerAcceptanceEndNanos = runEndElapsed,
			authorizationEffectEndNanos = runEndElapsed,
			consentEffectEndNanos = runEndElapsed,
			sessionRunEffectEndNanos = runEndElapsed,
			deletionEffectEndNanos = sessionEndElapsed,
			effectChecksum = ZERO_SHA,
		)
		return unsigned.copy(effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(unsigned))
	}

	private fun coverageFact(
		authority: FactAuthority,
		admissionOrdinal: Long,
		owner: CellCapturedFactRevisionEntity,
	): CellCapturedFactRevisionEntity {
		val base = baseFact(authority, admissionOrdinal, DEPENDENT_OFFSET_NANOS)
		val unsigned = base.copy(
			mutationId = CellCapturedFactRevisionIntegrity.mutationId(base.logicalFactId, 1L),
			factKind = CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY,
			aggregateOwnerLogicalFactId = owner.logicalFactId,
			aggregateOwnerSemanticRevision = owner.semanticRevision,
			providerAcceptanceEndNanos = authority.runEndElapsed,
			authorizationEffectEndNanos = authority.runEndElapsed,
			consentEffectEndNanos = authority.runEndElapsed,
			sessionRunEffectEndNanos = authority.runEndElapsed,
			deletionEffectEndNanos = authority.sessionEndElapsed,
			observationCount = null,
			registeredObservationCount = null,
			gsmCount = null,
			cdmaCount = null,
			wcdmaCount = null,
			tdscdmaCount = null,
			lteCount = null,
			nrCount = null,
			qualityUnknownCount = null,
			qualityNoneOrUnknownCount = null,
			qualityPoorCount = null,
			qualityModerateCount = null,
			qualityGoodCount = null,
			qualityGreatCount = null,
			weakObservationCount = null,
			knownQualityObservationCount = null,
			allKnownQualityIsWeak = null,
			effectChecksum = ZERO_SHA,
		)
		return unsigned.copy(effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(unsigned))
	}

	private fun cursor(fact: CellCapturedFactRevisionEntity) = CellCapturedFactCursorEntity(
		writerProjectionId = fact.writerProjectionId,
		writerProjectionVersion = fact.writerProjectionVersion,
		logicalFactId = fact.logicalFactId,
		logicalTrackingId = fact.logicalTrackingId,
		serviceRunId = fact.serviceRunId,
		sessionSegmentId = fact.sessionSegmentId,
		writerOwnerGeneration = fact.writerOwnerGeneration,
		collectedDataEpoch = fact.collectedDataEpoch,
		scopeDeletionGeneration = fact.scopeDeletionGeneration,
		latestSemanticRevision = fact.semanticRevision,
		latestMutationId = fact.mutationId,
		latestEffectChecksum = fact.effectChecksum,
		latestSourceAdmissionOrdinal = fact.sourceAdmissionOrdinal,
		cursorRevision = fact.semanticRevision,
		updatedAtMs = fact.appliedAtMs,
	)

	private fun ownerCursor(group: Group): CellCapturedFactCursorEntity = group.runs.single().cursors
		.single { cursor -> cursor.latestSemanticRevision == 2L }

	private suspend fun assertRecentFactIntegrityFailure() {
		val page = repository { true }.recent(1) as CellHistoryPage.Available
		val entry = page.entries.single()
		entry.state shouldBe CellHistoryProductState.FAILED
		entry.causes shouldBe setOf(CellHistoryCause.FACT_INTEGRITY_FAILED)
		entry.observations shouldBe emptyList()
	}

	private fun restoreCursorHead(cursor: CellCapturedFactCursorEntity) {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_cursor SET latest_semantic_revision = ?, " +
				"latest_mutation_id = ?, latest_effect_checksum = ?, " +
				"latest_source_admission_ordinal = ? WHERE logical_fact_id = ?",
			arrayOf(
				cursor.latestSemanticRevision,
				cursor.latestMutationId,
				cursor.latestEffectChecksum,
				cursor.latestSourceAdmissionOrdinal,
				cursor.logicalFactId,
			),
		)
	}

	private fun demand(logicalId: String, spec: RunSpec, runEndElapsed: Long, runEndWall: Long) =
		SourceDemandEntity(
			demandId = "demand-${spec.globalRevision}",
			consumerId = "session:${spec.runId}",
			sourceKind = CELL_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = logicalId,
			serviceRunId = spec.runId,
			manifestRevision = spec.manifestRevision,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			sourcePolicyRevision = spec.globalRevision,
			consentEpoch = spec.globalRevision,
			persistenceEligible = true,
			qosCode = QOS_CODE,
			minimumAcquisitionSpec = "cell:v1:change_callbacks",
			adaptiveReductionAllowed = false,
			maximumAgeMs = 1_000L,
			desiredLatencyMs = 1_000L,
			requestedDeliveryLatencyMs = 0L,
			requestedBootId = BOOT_ID,
			requestedElapsedRealtimeNanos = spec.startElapsedNanos,
			requestedAtMs = spec.startWallMs,
			status = SourceDemandEntity.STATUS_RETIRED,
			retireBootId = BOOT_ID,
			retireElapsedRealtimeNanos = runEndElapsed,
			retiredAtMs = runEndWall,
		)

	private fun completeness(logicalId: String, spec: RunSpec, lastOrdinal: Long?) =
		SourceSessionCompletenessEntity(
			logicalTrackingId = logicalId,
			serviceRunId = spec.runId,
			sourceKind = CELL_SOURCE,
			sourceInstanceId = if (lastOrdinal == null) "cell-unregistered-${spec.globalRevision}" else
				"cell-instance-${spec.globalRevision}",
			registrationGeneration = if (lastOrdinal == null) 0L else spec.globalRevision,
			lastAdmissionOrdinal = lastOrdinal,
			lastSourceSequence = lastOrdinal,
			appDrainComplete = true,
			providerCoverage = if (lastOrdinal == null) "PROVIDER_COMPLETENESS_UNOBSERVABLE" else
				"CALLBACKS_ENTERED_BEFORE_BARRIER",
			stopStatus = "COMPLETE",
			unresolvedSequenceStart = null,
			unresolvedSequenceEnd = null,
			updatedAtMs = spec.startWallMs + RUN_DURATION_MS,
		)

	private fun desiredPlan(revision: Long): SourceDesiredPlanEntity {
		val payload = ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(1)
				output.writeUTF("CELL")
				output.writeLong(revision)
				output.writeUTF("OBSERVE_CHANGES")
				output.writeLong(60_000L)
				output.writeLong(1_000L)
				output.writeInt(0)
				output.writeLong(1_000L)
				output.writeLong(60_000L)
				output.writeDouble(2.0)
			}
			bytes.toByteArray()
		}
		return SourceDesiredPlanEntity(revision, CELL_SOURCE, 1, payload, sha256(payload))
	}

	private fun lane(throughOrdinal: Long) = SourceProductProjectionLaneEntity(
		sourceKind = CELL_SOURCE,
		bindingGeneration = SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
		projectionId = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
		projectionVersion = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
		captureModeMask = 1L,
		productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
		activatedRolloutRevision = ROLLOUT_REVISION,
		activationOrdinal = 1L,
		contiguousAdmissionOrdinal = throughOrdinal,
		captureAdmissionCutoffOrdinal = null,
		retentionRequired = true,
		status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
		terminalDisposition = null,
		terminalAtMs = null,
		installedAtMs = 0L,
		updatedAtMs = 0L,
	)

	private fun admissionOrdinal(spec: RunSpec, offset: Long) = spec.globalRevision * 10L + offset

	private fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

	private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes).joinToString("") { "%02x".format(it) }

	private data class Group(val session: LogicalTrackingSessionEntity, val runs: List<BuiltRun>)

	@Suppress("LongParameterList")
	private data class BuiltRun(
		val segment: SessionSegment,
		val run: SourceServiceRunEntity,
		val manifest: SessionManifestVersionEntity,
		val source: SessionManifestSourceEntity,
		val policy: SourcePolicyEntity,
		val consent: SourceConsentEpochEntity,
		val planHeader: AcquisitionPlanRevisionEntity,
		val desiredPlan: SourceDesiredPlanEntity,
		val demands: List<SourceDemandEntity>,
		val provider: ProviderRegistrationGenerationEntity?,
		val authorizations: List<SourceAuthorizationEntity>,
		val facts: List<CellCapturedFactRevisionEntity>,
		val cursors: List<CellCapturedFactCursorEntity>,
		val completeness: SourceSessionCompletenessEntity,
	)

	private data class RunSpec(
		val manifestRevision: Long,
		val globalRevision: Long,
		val runId: String,
		val startWallMs: Long,
		val startElapsedNanos: Long,
		val hasFact: Boolean,
	)

	private data class FactAuthority(
		val logicalId: String,
		val spec: RunSpec,
		val segmentId: Long,
		val physicalFingerprint: String,
		val authorizationFingerprint: String,
		val sessionEndElapsed: Long,
		val runEndElapsed: Long,
	)

	private companion object {
		const val CELL_SOURCE = SourceDestinationOwnerEntity.SOURCE_CELL
		const val BOOT_ID = "boot-cell-history"
		const val ZONE_ID = "UTC"
		const val START_ORIGIN = "MANUAL_FOREGROUND_START"
		const val QOS_CODE = 2
		const val LEASE_GENERATION = 1L
		const val ROLLOUT_REVISION = 1L
		const val RUN_DURATION_NANOS = 800_000_000L
		const val RUN_DURATION_MS = 800L
		const val OWNER_OFFSET_NANOS = 200_000_000L
		const val DEPENDENT_OFFSET_NANOS = 400_000_000L
		const val WALL_BASE_MS = 1_700_000_000_000L
		const val ZERO_SHA = "0000000000000000000000000000000000000000000000000000000000000000"
	}
}
