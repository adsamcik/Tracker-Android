package com.adsamcik.tracker.app.maintenance

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.maintenance.DataRetentionWorker
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableIntegrity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.ImportPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductFailure
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductReader
import com.adsamcik.tracker.shared.base.database.ImportedActivityRetentionBlockedReason
import com.adsamcik.tracker.shared.base.database.PortableActivityCaptureCoverage
import com.adsamcik.tracker.shared.base.database.PortableActivityDeletionScopeDigest
import com.adsamcik.tracker.shared.base.database.PortableActivityEntryV1
import com.adsamcik.tracker.shared.base.database.PortableActivityFragmentV1
import com.adsamcik.tracker.shared.base.database.PortableActivityImportBlockedReason
import com.adsamcik.tracker.shared.base.database.PortableActivityImportReceipt
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableActivityRunV1
import com.adsamcik.tracker.shared.base.database.PortableActivitySessionMode
import com.adsamcik.tracker.shared.base.database.PortableActivityTransferRetryableReason
import com.adsamcik.tracker.shared.base.database.PortableActivityWindowCoverage
import com.adsamcik.tracker.shared.base.database.PortableActivityWindowV1
import com.adsamcik.tracker.shared.base.database.PortableActivityZoneEpochV1
import com.adsamcik.tracker.shared.base.database.RoomImportPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.RoomTruncateImportedActivityRetention
import com.adsamcik.tracker.shared.base.database.TruncateImportedActivityRetentionRequest
import com.adsamcik.tracker.shared.base.database.TruncateImportedActivityRetentionResult
import com.adsamcik.tracker.shared.base.database.dao.ActivityCapturedFactDao
import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.SourceDestinationOwnerDao
import com.adsamcik.tracker.shared.base.database.dao.SourceEvidenceStateDao
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.ApprovedRetentionOperation
import com.adsamcik.tracker.shared.preferences.retention.ApprovedRetentionPolicy
import com.adsamcik.tracker.shared.preferences.retention.ExactApprovedRetentionConfigRead
import com.adsamcik.tracker.shared.preferences.retention.ExactApprovedRetentionOperationResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityOperationLease
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityScope
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciler
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationResult
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactDrainResult
import com.adsamcik.tracker.tracker.source.projection.StepsSessionFactProjectionLane
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import javax.inject.Provider
import kotlin.coroutines.EmptyCoroutineContext

/** Authored caller contracts; these tests have not been executed in the implementation-only phase. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityRetentionWorkerRobolectricTest {
	@Test
	fun `both workers execute through the exact approved operation callback`() = runTest {
		for (path in WorkerPath.entries) {
			val db = AppDatabase.testDatabase(context())
			try {
				var admittedOperations = 0
				val imported = RoomTruncateImportedActivityRetention(
					db,
					StandardTestDispatcher(testScheduler),
				)

				assertEquals(
					ListenableWorker.Result.success(),
					worker(path, db, imported) { admission ->
						admission.requireIdentity()
						admittedOperations += 1
					}.doWork(),
				)

				assertEquals(1, admittedOperations)
			} finally {
				db.close()
			}
		}
	}

	@Test
	fun `zero imported candidates and an empty legacy owner do not require Activity activation`() = runTest {
		for (path in WorkerPath.entries) {
			val db = AppDatabase.testDatabase(context())
			try {
				db.sourceDestinationOwnerDao().insertIfAbsent(owner(canonical = false))
				db.activitySnapshotDao().insert(snapshot())
				val imported = RoomTruncateImportedActivityRetention(db, StandardTestDispatcher(testScheduler))

				assertEquals(ListenableWorker.Result.success(), worker(path, db, imported).doWork())
				assertEquals(owner(canonical = false), db.sourceDestinationOwnerDao().get(ACTIVITY, DESTINATION))
				assertEquals(emptyList<ActivitySnapshot>(), db.activitySnapshotDao().getAllBetween(0L, 2_000L))
				assertEquals(0L, db.activityCapturedFactDao().revisionCount())
				assertEquals(0L, db.importedActivityDao().retentionReceiptCount())
				assertEquals(0L, db.importedActivityDao().retainedIdentityCount())
			} finally {
				db.close()
			}
		}
	}

	@Test
	fun `canonical Activity authentication starts only after exact imported retention acceptance`() = runTest {
		for (path in WorkerPath.entries) {
			val storage = mockStorage(canonical = true)
			val request = slot<TruncateImportedActivityRetentionRequest>()
			val imported = mockk<RoomTruncateImportedActivityRetention>()
			coEvery { imported.truncate(capture(request)) } returns TruncateImportedActivityRetentionResult.NoChange

			assertEquals(ListenableWorker.Result.success(), worker(path, storage.db, imported).doWork())
			assertEquals(EPOCH, request.captured.expectedCollectedDataEpoch)
			assertEquals(1L, request.captured.expectedSourceEvidenceRevision)
			assertEquals(FLOOR, request.captured.retainedFromMs)
			assertTrue(request.captured.retainedAtMs > 0L)
			coVerifyOrder {
				imported.truncate(any())
				storage.facts.maintenanceRevisionPage(any(), any(), any(), any(), any())
				storage.snapshots.deleteOlderThan(any())
			}
			coVerify(exactly = 0) { storage.owners.insertIfAbsent(any()) }
			coVerify(exactly = 0) { storage.owners.compareAndSetOwner(any(), any(), any(), any(), any(), any(), any()) }
		}
	}

	@Test
	fun `a nonempty mismatched Activity owner blocks physical deletion instead of looking dormant`() = runTest {
		for (path in WorkerPath.entries) for (rowKind in CapturedRowKind.entries) {
			val storage = mockStorage(canonical = false)
			when (rowKind) {
				CapturedRowKind.REVISION -> coEvery { storage.facts.revisionCount() } returns 1L
				CapturedRowKind.FRAGMENT -> coEvery { storage.facts.fragmentCount() } returns 1L
				CapturedRowKind.EVIDENCE -> coEvery { storage.facts.evidenceCount() } returns 1L
				CapturedRowKind.CURSOR -> coEvery { storage.facts.cursorCount() } returns 1L
			}
			val imported = noChangeImportedRetention()

			assertEquals(ListenableWorker.Result.retry(), worker(path, storage.db, imported).doWork())
			coVerify(exactly = 1) { imported.truncate(any()) }
			coVerify(exactly = 1) { storage.facts.maintenanceRevisionPage(any(), any(), any(), any(), any()) }
			coVerify(exactly = 0) { storage.facts.deleteExactCursors(any(), any(), any()) }
			coVerify(exactly = 0) { storage.facts.deleteExactRevisionLineages(any(), any(), any()) }
			coVerify(exactly = 0) { storage.snapshots.deleteOlderThan(any()) }
		}
	}

	@Test
	fun `every typed imported rejection aborts the caller transaction before Activity deletion`() = runTest {
		val failures = listOf(
			TruncateImportedActivityRetentionResult.Blocked(
				ImportedActivityRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
			),
			TruncateImportedActivityRetentionResult.Unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE),
			TruncateImportedActivityRetentionResult.RetryableFailure(PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE),
		)
		for (path in WorkerPath.entries) for (failure in failures) {
			val db = AppDatabase.testDatabase(context())
			try {
				val initial = SourceEvidenceState(collectedDataEpoch = EPOCH)
				db.sourceEvidenceStateDao().ensure(initial)
				db.activitySnapshotDao().insert(snapshot())
				val imported = mockk<RoomTruncateImportedActivityRetention>()
				coEvery { imported.truncate(any()) } returns failure

				assertEquals(ListenableWorker.Result.retry(), worker(path, db, imported).doWork())
				assertEquals(initial, db.sourceEvidenceStateDao().get())
				assertEquals(1, db.activitySnapshotDao().getAllBetween(0L, 2_000L).size)
				assertEquals(0L, db.importedActivityDao().retentionReceiptCount())
			} finally {
				db.close()
			}
		}
	}

	@Test
	fun `storage exception and cancellation roll back the exact caller authority mutation`() = runTest {
		for (path in WorkerPath.entries) for (cancel in listOf(false, true)) {
			val db = AppDatabase.testDatabase(context())
			try {
				val initial = SourceEvidenceState(collectedDataEpoch = EPOCH)
				db.sourceEvidenceStateDao().ensure(initial)
				db.activitySnapshotDao().insert(snapshot())
				val imported = mockk<RoomTruncateImportedActivityRetention>()
				coEvery { imported.truncate(any()) } coAnswers {
					db.sourceEvidenceStateDao().incrementRevision(System.currentTimeMillis())
					if (cancel) throw CancellationException("cancel Activity retention caller")
					throw SQLiteException("injected Activity retention storage failure")
				}
				var cancellationPropagated = false
				try {
					assertEquals(ListenableWorker.Result.retry(), worker(path, db, imported).doWork())
				} catch (_: CancellationException) {
					cancellationPropagated = true
				}

				assertEquals(cancel, cancellationPropagated)
				assertEquals(initial, db.sourceEvidenceStateDao().get())
				assertEquals(1, db.activitySnapshotDao().getAllBetween(0L, 2_000L).size)
				assertEquals(0L, db.activityCapturedFactDao().revisionCount())
			} finally {
				db.close()
			}
		}
	}

	@Test
	fun `imported Activity loss is compacted before pending signals defer physical capture deletion`() = runTest {
		for (path in WorkerPath.entries) {
			val db = AppDatabase.testDatabase(context())
			try {
				db.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
				val entry = portableEntry()
				val request = ImportPortableCapturedActivityRequest(
					entry, PortableActivityImportReceipt("retention-job", "entry", "backup.trackeractivity", 30L), EPOCH,
				)
				val importer = RoomImportPortableCapturedActivity(db, StandardTestDispatcher(testScheduler))
				assertEquals(ImportPortableCapturedActivityResult.Applied(1L, 1, 1, 1), importer.importEntry(request))
				db.activitySnapshotDao().insert(snapshot())
				db.locationSampleDao().insert(locationSample())
				val retainedLocation = db.locationSampleDao().getLatestBetween(0L, 2_000L)
				val control = controlDemand()
				db.sourceBrokerDao().insertDemands(listOf(control))
				val wal = controlWal(System.currentTimeMillis())
				db.sourceEventWalDao().insertIgnoringDuplicate(wal)
				val retainedWalBefore = requireNotNull(db.sourceEventWalDao().getByEventId(wal.eventId))
				db.pendingSignalDao().insertAll(listOf(pendingSignal()))
				val imported = RoomTruncateImportedActivityRetention(db, StandardTestDispatcher(testScheduler))

				assertEquals(ListenableWorker.Result.retry(), worker(path, db, imported).doWork())
				assertEquals(1, db.activitySnapshotDao().getAllBetween(0L, 2_000L).size)
				assertEquals(retainedLocation, db.locationSampleDao().getLatestBetween(0L, 2_000L))
				assertEquals(1L, db.locationSampleDao().countAll())
				assertEquals(listOf(control), db.sourceBrokerDao().demandsByIds(listOf(control.demandId)))
				val retainedWal = requireNotNull(db.sourceEventWalDao().getByEventId(wal.eventId))
				assertEquals(retainedWalBefore.copy(payload = retainedWal.payload), retainedWal)
				assertTrue(retainedWalBefore.payload.contentEquals(retainedWal.payload))
				assertTrue(retainedWal.hasQualifiedIntegrity())
				assertEquals(1L, db.importedActivityDao().retentionReceiptCount())
				assertEquals(4L, db.importedActivityDao().retainedIdentityCount())
				assertEquals(0L, rowCount(db, "imported_activity_entry_revision"))
				assertEquals(0L, rowCount(db, "imported_activity_run"))
				assertEquals(0L, rowCount(db, "imported_activity_window"))
				assertEquals(0L, rowCount(db, "imported_activity_fragment"))
				val evaluation = db.withTransaction {
					ImportedActivityProductReader(db).selectRecentInTransaction(1).single()
				}
				assertTrue(evaluation is ImportedActivityProductEvaluation.Retained)
				assertTrue(evaluation !is ImportedActivityProductEvaluation.Readable)
				assertEquals(
					ImportPortableCapturedActivityResult.Blocked(PortableActivityImportBlockedReason.RETENTION_BOUNDARY),
					importer.importEntry(request),
				)
			} finally {
				db.close()
			}
		}
	}

	private fun worker(
		path: WorkerPath,
		db: AppDatabase,
		imported: RoomTruncateImportedActivityRetention,
		onApprovedOperation: (ApprovedRetentionOperation) -> Unit = {},
	): CoroutineWorker {
		val authority = ExactApprovedRetentionConfigRead.Approved(
			configuration = if (path == WorkerPath.LEGACY) {
				RetentionConfigState(autoCleanupEnabled = true, dataRetentionYears = 1)
			} else {
				RetentionConfigState(
					autoPurgeEnabled = true,
					rawDataRetentionDays = 1,
					wifiCellRetentionDays = 0,
					tripRetentionDays = 0,
					dailySummaryRetentionDays = 0,
					explorationRetentionDays = 0,
				)
			},
			policy = ApprovedRetentionPolicy(
				configurationGeneration = 1L,
				revision = 1L,
				opaquePolicyId = "activity-retention-worker-test",
				configurationChecksum = "e".repeat(64),
				integrityChecksum = "f".repeat(64),
			),
		)
		val store = mockk<RetentionConfigStore> {
			coEvery {
				withExactApprovedOperation<ListenableWorker.Result>(any())
			} coAnswers {
				val admission = ApprovedRetentionOperation(
					authority.configuration,
					authority.policy,
				)
				onApprovedOperation(admission)
				ExactApprovedRetentionOperationResult.Completed(
					admission,
					firstArg<suspend (ApprovedRetentionOperation) -> ListenableWorker.Result>()
						.invoke(admission),
				)
			}
		}
		val lifecycle = mockk<CollectedDataLifecycleStore> {
			coEvery { advanceRetainedFrom(any()) } returns CollectedDataLifecycleSnapshot(EPOCH, FLOOR)
			coEvery { advanceRetainedFrom(any(), any(), any()) } returns
				CollectedDataLifecycleSnapshot(EPOCH, FLOOR)
		}
		val lane = mockk<StepsSessionFactProjectionLane> {
			coEvery { drainAvailable() } returns StepsSessionFactDrainResult.Inactive
		}
		val factory = object : WorkerFactory() {
			override fun createWorker(appContext: Context, workerClassName: String, parameters: WorkerParameters): ListenableWorker =
				if (path == WorkerPath.LEGACY) DataRetentionWorker(
					appContext, parameters, store, Provider { db }, mockk<ExportPlanStore>(relaxed = true),
					mockk(relaxed = true), lifecycle, READY_GATE, Provider { lane }, Provider { imported },
					mockk<CellCapturedRetentionService> {
						coEvery { prune(any(), any(), any()) } returns
							com.adsamcik.tracker.shared.base.database.CellCapturedRetentionResult.NoChange
					},
					mockk<com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionService> {
						coEvery { prune(any(), any(), any()) } returns
							com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionResult.NoChange
					},
					retentionFloorSettlement(),
					mockk {
						coEvery {
							run(any(), any(), any(), any())
						} returns PeriodicAmbientRetentionResult.Complete
					},
				) else RetentionPipelineWorker(
					appContext, parameters, store, lifecycle, Provider { db }, mockk(relaxed = true),
					READY_GATE, Provider { lane }, Provider { imported },
					mockk<CellCapturedRetentionService> {
						coEvery { prune(any(), any(), any()) } returns
							com.adsamcik.tracker.shared.base.database.CellCapturedRetentionResult.NoChange
					},
					mockk<com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionService> {
						coEvery { prune(any(), any(), any()) } returns
							com.adsamcik.tracker.tracker.source.wifi.WifiCapturedRetentionResult.NoChange
					},
					retentionFloorSettlement(),
					mockk {
						coEvery {
							run(any(), any(), any(), any())
						} returns PeriodicAmbientRetentionResult.Complete
					},
				)
		}

		return if (path == WorkerPath.LEGACY) {
			TestListenableWorkerBuilder<DataRetentionWorker>(context()).setWorkerFactory(factory).build() as DataRetentionWorker
		} else {
			TestListenableWorkerBuilder<RetentionPipelineWorker>(context()).setWorkerFactory(factory).build() as RetentionPipelineWorker
		}
	}

	private fun retentionFloorSettlement(): RetentionFloorSettlement =
		RetentionFloorSettlement(
			RetentionAuthorityOperationLease(),
			mockk<RetentionAuthorityProducer> {
				coEvery { reconcileCurrentSettings() } returns listOf(
					TrackingSourceComponent.STEPS,
					TrackingSourceComponent.WIFI,
					TrackingSourceComponent.CELL,
				).map { source ->
					RetentionAuthorityResult.Unchanged(
						source = source,
						scope = RetentionAuthorityScope.LIVE_AMBIENT,
						state = RetentionAuthorityState.ACTIVE,
						approvalRevision = 1L,
					)
				}
			},
			TrackingRetentionFloorReconciler { _, floor, sources ->
				TrackingRetentionFloorReconciliationResult.Complete(floor, sources)
			},
		)

	private fun mockStorage(canonical: Boolean): MockStorage {
		val db = mockk<AppDatabase>(relaxed = true)
		every { db.transactionExecutor } returns Executor(Runnable::run)
		every { db.suspendingTransactionContext } returns ThreadLocal.withInitial { EmptyCoroutineContext }
		every { db.beginTransaction() } returns Unit
		every { db.setTransactionSuccessful() } returns Unit
		every { db.endTransaction() } returns Unit
		val facts = mockk<ActivityCapturedFactDao>(relaxed = true)
		val snapshots = mockk<ActivitySnapshotDao>(relaxed = true)
		val owners = mockk<SourceDestinationOwnerDao>(relaxed = true)
		coEvery { owners.get(ACTIVITY, DESTINATION) } returns owner(canonical)
		every { db.activityCapturedFactDao() } returns facts
		every { db.activitySnapshotDao() } returns snapshots
		every { db.sourceDestinationOwnerDao() } returns owners
		every { db.sourceEvidenceStateDao() } returns evidenceDao()
		coEvery { db.trackerRunDao().minStartTimeMs() } returns null
		coEvery { db.locationObservationDao().minFixTimeMs() } returns null
		return MockStorage(db, facts, snapshots, owners)
	}

	private fun evidenceDao(): SourceEvidenceStateDao = object : SourceEvidenceStateDao {
		private var state: SourceEvidenceState? = null
		override suspend fun ensure(state: SourceEvidenceState) { if (this.state == null) this.state = state }
		override suspend fun get() = state
		override suspend fun updateLifecycle(epoch: Long, retainedFromMs: Long?, updatedAtMs: Long): Int {
			val current = requireNotNull(state)
			state = current.copy(revision = current.revision + 1L, collectedDataEpoch = epoch,
				retainedFromMs = retainedFromMs, updatedAtMs = updatedAtMs)
			return 1
		}
		override suspend fun incrementRevision(updatedAtMs: Long): Int {
			val current = requireNotNull(state)
			state = current.copy(revision = current.revision + 1L, updatedAtMs = updatedAtMs)
			return 1
		}
		override suspend fun updateAfterFullDeletion(epoch: Long, retainedFromMs: Long?, deletedSourceEventHighWaterOrdinal: Long, updatedAtMs: Long): Int =
			error("Activity retention must not erase collected data")
	}

	private fun noChangeImportedRetention() = mockk<RoomTruncateImportedActivityRetention> {
		coEvery { truncate(any()) } returns TruncateImportedActivityRetentionResult.NoChange
	}

	private fun portableEntry(): PortableActivityEntryV1 {
		val entryIdentity = PortableActivityOpaqueIdentity("1".repeat(64))
		val runIdentity = PortableActivityOpaqueIdentity("2".repeat(64))
		val windowIdentity = PortableActivityOpaqueIdentity("3".repeat(64))
		val scope = PortableActivityDeletionScopeDigest("4".repeat(64))
		val fragments = listOf(PortableActivityFragmentV1.Band(
			0L, 100L, "WALKING", "TRANSITION", null, "TRANSITION_SIGNAL", null, null, null,
			1_000L, 0L, "EXACT_PROVIDER_OBSERVATION", 1_001L, 0L, "SAME_CLOCK_EXTRAPOLATION", "SAME_ANCHOR",
		))
		val windows = listOf(PortableActivityWindowV1(
			windowIdentity, ActivityCapturedPortableIntegrity.windowChecksum(windowIdentity, 0L, 100L, "UTC",
				PortableActivityWindowCoverage.COMPLETE, 100L, 0L, 0L, 0L, fragments),
			0L, 100L, "UTC", PortableActivityWindowCoverage.COMPLETE, 100L, 0L, 0L, 0L, fragments,
		))
		val zones = listOf(PortableActivityZoneEpochV1(1_000L, "UTC"))
		val runs = listOf(PortableActivityRunV1(
			runIdentity, scope, ActivityCapturedPortableIntegrity.runChecksum(runIdentity, scope, 1_000L, 2_000L,
				PortableActivityCaptureCoverage.WHOLE_RUN, zones, windows),
			1_000L, 2_000L, PortableActivityCaptureCoverage.WHOLE_RUN, zones, windows,
		))
		return PortableActivityEntryV1(entryIdentity, ActivityCapturedPortableIntegrity.entryChecksum(entryIdentity,
			PortableActivitySessionMode.MANUAL, 1_000L, 2_000L, runs), PortableActivitySessionMode.MANUAL, 1_000L, 2_000L, runs)
	}

	private fun controlDemand() = SourceDemandEntity(
		demandId = "control-demand",
		consumerId = "control-consumer",
		sourceKind = ACTIVITY,
		purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		lifecycleLeaseGeneration = null,
		sourcePolicyRevision = 2L,
		consentEpoch = 0L,
		persistenceEligible = false,
		qosCode = 1,
		maximumAgeMs = 0L,
		desiredLatencyMs = 0L,
		requestedBootId = "boot",
		requestedElapsedRealtimeNanos = 700L,
		requestedAtMs = 4_100L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private fun controlWal(createdAtMs: Long): SourceEventWalEntity {
		val unsigned = SourceEventWalEntity(eventId = "control-event", providerDedupKey = "control-delivery",
			logicalTrackingId = null, serviceRunId = null, sourceKind = ACTIVITY, sourceInstanceId = "control-provider",
			registrationGeneration = 1L, physicalConfigurationFingerprint = "control-config", authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			authorizationFingerprint = "control-authority", sourceSequence = 1L,
			configRevision = null, planAttribution = PlanAttribution.RECEIVE_TIME_ONLY.ordinal,
			clockDomainId = "boot", observedElapsedNanos = 700L,
			receivedElapsedNanos = 701L, wallTimeMs = createdAtMs, wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = EPOCH, activityAutomationEpoch = 0L, acquiredAtMs = createdAtMs,
			qualityFlags = 0L, qualityConfidence = null, payloadVersion = 1, payload = byteArrayOf(1, 2, 3),
			payloadChecksum = "unsigned", createdAtMs = createdAtMs)
		return unsigned.copy(payloadChecksum = unsigned.calculatedPayloadChecksum(), integrityIdentity = unsigned.calculatedIntegrityIdentity())
	}

	private fun pendingSignal() = PendingSignalEntity(signalId = "pending-retention", sessionId = 7L, envelopeVersion = 1,
		payloadChecksum = "1280fde14031e7b67bce77ff73860e29dbb51d1f3698679d28f2f93ed1beb128",
		signalJson = """{"type":"tracking_signal","payload":{"ts":1,"ern":0}}""", createdAt = 1L)

	private fun snapshot() = ActivitySnapshot(timeMs = 1_000L, activityType = 7, confidence = 90, isTransition = true, createdAt = 1_000L)
	private fun locationSample() = LocationSample(
		timeMs = 1_000L,
		elapsedRealtimeNanos = 1L,
		latE7 = 500_000_000,
		lonE7 = 140_000_000,
		altitudeM = null,
		rawGpsAltitudeM = null,
		hAccM = 5f,
		vAccM = null,
		speedMps = null,
		speedAccuracyMps = null,
		provider = "fused",
		quality = SampleQuality.HIGH,
		motionState = null,
		policy = null,
		bucketId = null,
		createdAt = 1_000L,
	)
	private fun owner(canonical: Boolean) = SourceDestinationOwnerEntity(ACTIVITY, DESTINATION,
		if (canonical) SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS else SourceDestinationOwnerEntity.OWNER_LEGACY_ACTIVITY_SNAPSHOT,
		if (canonical) SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION else SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION, 0L)
	private fun context(): Context = ApplicationProvider.getApplicationContext()
	private fun rowCount(db: AppDatabase, table: String): Long = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
		it.moveToFirst(); it.getLong(0)
	}
	private enum class WorkerPath { PIPELINE, LEGACY }
	private enum class CapturedRowKind { REVISION, FRAGMENT, EVIDENCE, CURSOR }
	private data class MockStorage(val db: AppDatabase, val facts: ActivityCapturedFactDao, val snapshots: ActivitySnapshotDao, val owners: SourceDestinationOwnerDao)
	private companion object {
		const val EPOCH = 9L
		const val FLOOR = 1_500L
		const val ACTIVITY = SourceDestinationOwnerEntity.SOURCE_ACTIVITY
		const val DESTINATION = SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY
		val READY_GATE = object : TrackingStartupGate {
			override val isReady = true
			override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Ready(false, 0L)
		}
	}
}
