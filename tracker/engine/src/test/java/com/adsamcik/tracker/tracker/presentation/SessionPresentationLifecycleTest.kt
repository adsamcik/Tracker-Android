package com.adsamcik.tracker.tracker.presentation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummary
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummarySelection
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionPresentationLifecycleTest {
	private lateinit var database: AppDatabase
	private lateinit var lifecycle: SessionPresentationLifecycle

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		lifecycle = SessionPresentationLifecycle(database)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `close uses qualified saved totals and original End instead of stale counters and cleanup time`() = runTest {
		insertActiveOwner(LOGICAL_ID, RUN_ONE)
		val opened = lifecycle.openOrResumeExact(LOGICAL_ID, RUN_ONE, null,
			segment(LOGICAL_ID, RUN_ONE).copy(steps = 7, primaryActivity = 2, sampleCount = 99))
		val dao = database.sourceSessionDao()
		dao.updateSession(requireNotNull(dao.session(LOGICAL_ID)).copy(cutoffAtMs = 2_000L)) shouldBe 1
		dao.updateServiceRun(requireNotNull(dao.serviceRun(RUN_ONE)).copy(completedAtMs = 9_000L)) shouldBe 1
		database.sourceEvidenceStateDao().ensure()
		val history = mockk<TrackingHistoryRepository>()
		coEvery { history.readSavedLocationSummary(SavedLocationSummarySelection.Session(opened.segment.id)) } coAnswers {
			database.inTransaction() shouldBe true
			closeSummary(opened.binding, pointCount = 13L, distanceMeters = 102.0)
		}
		val closer = SessionPresentationLifecycle(database, history)

		val closed = closer.closeExact(opened.binding)

		closed shouldBe opened.segment.copy(endTimeMs = 2_000L, sampleCount = 13, distanceM = 102f)
		closed.steps shouldBe 7
		closed.primaryActivity shouldBe 2
		closer.closeExact(opened.binding) shouldBe closed
		database.sessionSegmentDao().countTotal() shouldBe 1L
	}

	@Test
	fun `failed saved summary leaves close retryable and an authoritative empty retry clears stale totals`() = runTest {
		insertActiveOwner(LOGICAL_ID, RUN_ONE)
		val opened = lifecycle.openOrResumeExact(LOGICAL_ID, RUN_ONE, null,
			segment(LOGICAL_ID, RUN_ONE).copy(sampleCount = 99, distanceM = 500f))
		val dao = database.sourceSessionDao()
		dao.updateSession(requireNotNull(dao.session(LOGICAL_ID)).copy(cutoffAtMs = 2_000L)) shouldBe 1
		database.sourceEvidenceStateDao().ensure()
		val history = mockk<TrackingHistoryRepository>()
		val selection = SavedLocationSummarySelection.Session(opened.segment.id)
		coEvery { history.readSavedLocationSummary(selection) } returns
			SavedLocationSummaryRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)
		val closer = SessionPresentationLifecycle(database, history)

		shouldThrow<IllegalStateException> { closer.closeExact(opened.binding) }
		database.sessionSegmentDao().getById(opened.segment.id) shouldBe opened.segment
		coEvery { history.readSavedLocationSummary(selection) } coAnswers {
			closeSummary(opened.binding, pointCount = 0L, distanceMeters = null)
		}
		closer.closeExact(opened.binding) shouldBe opened.segment.copy(
			endTimeMs = 2_000L, sampleCount = 0, distanceM = 0f,
		)
	}

	private suspend fun closeSummary(
		binding: SessionPresentationBinding,
		pointCount: Long,
		distanceMeters: Double?,
	): SavedLocationSummaryRead.Snapshot {
		val evidence = requireNotNull(database.sourceEvidenceStateDao().get())
		return SavedLocationSummaryRead.Snapshot(
			SavedLocationSummarySelection.Session(binding.sessionSegmentId),
			TrackingHistoryReadSnapshot(evidence.collectedDataEpoch, evidence.revision),
			SavedLocationSummary(pointCount, if (pointCount > 0L) { mockk() } else { null }, distanceMeters, partial = true),
		)
	}

	@Test
	fun `open atomically binds one segment and same-run retry resumes it`() = runTest {
		insertActiveOwner(LOGICAL_ID, RUN_ONE)

		val opened = lifecycle.openOrResumeExact(
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = RUN_ONE,
			requestedSessionSegmentId = null,
			newSegment = segment(LOGICAL_ID, RUN_ONE).copy(
				startTimeMs = 244_000L, endTimeMs = 244_000L, createdAt = 244_000L,
			),
		)

		opened.created shouldBe true
		opened.segment.startTimeMs shouldBe 1_000L
		opened.segment.endTimeMs shouldBe 1_000L
		opened.segment.createdAt shouldBe 1_000L
		database.sessionSegmentDao().getById(opened.segment.id) shouldBe opened.segment
		opened.binding.sessionSegmentId shouldBe opened.segment.id
		val boundRun = requireNotNull(database.sourceSessionDao().serviceRun(RUN_ONE))
		boundRun.sessionSegmentId shouldBe opened.segment.id
		boundRun.runRevision shouldBe 7L

		val retried = lifecycle.openOrResumeExact(
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = RUN_ONE,
			requestedSessionSegmentId = opened.segment.id,
			newSegment = segment(LOGICAL_ID, RUN_ONE).copy(
				startTimeMs = 300_000L, endTimeMs = 300_000L, createdAt = 300_000L,
			),
		)

		retried.created shouldBe false
		retried.binding shouldBe opened.binding
		retried.segment shouldBe opened.segment
		database.sessionSegmentDao().getById(opened.segment.id) shouldBe opened.segment
		database.sessionSegmentDao().countTotal() shouldBe 1L
	}

	@Test
	fun `unbound Room owner rejects a descriptor supplied segment without inserting`() = runTest {
		insertActiveOwner(LOGICAL_ID, RUN_ONE)

		shouldThrow<IllegalStateException> {
			lifecycle.openOrResumeExact(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ONE,
				requestedSessionSegmentId = 99L,
				newSegment = segment(LOGICAL_ID, RUN_ONE),
			)
		}

		database.sessionSegmentDao().countTotal() shouldBe 0L
		database.sourceSessionDao().serviceRun(RUN_ONE)?.sessionSegmentId shouldBe null
	}

	@Test
	fun `replacement physical run receives another segment under the same logical entry`() = runTest {
		insertActiveOwner(LOGICAL_ID, RUN_ONE)
		val first = lifecycle.openOrResumeExact(
			LOGICAL_ID,
			RUN_ONE,
			null,
			segment(LOGICAL_ID, RUN_ONE),
		)
		val dao = database.sourceSessionDao()
		val firstRun = requireNotNull(dao.serviceRun(RUN_ONE))
		dao.updateServiceRun(
			firstRun.copy(
				state = SessionLifecycleState.FINALIZED.name,
				completedAtMs = 2_000L,
				completionReason = "PROCESS_REPLACED",
			),
		) shouldBe 1
		dao.insertServiceRun(serviceRun(LOGICAL_ID, RUN_TWO))
		val logical = requireNotNull(dao.session(LOGICAL_ID))
		dao.updateSession(
			logical.copy(
				state = SessionLifecycleState.ACTIVE.name,
				currentServiceRunId = RUN_TWO,
				lifecycleRevision = logical.lifecycleRevision + 1L,
			),
		) shouldBe 1

		val second = lifecycle.openOrResumeExact(
			LOGICAL_ID,
			RUN_TWO,
			null,
			segment(LOGICAL_ID, RUN_TWO),
		)

		second.binding.sessionSegmentId shouldBe second.segment.id
		(second.segment.id == first.segment.id) shouldBe false
		first.segment.logicalTrackingId shouldBe second.segment.logicalTrackingId
		database.sessionSegmentDao().countTotal() shouldBe 2L
	}

	@Test
	fun `quiescence requires a terminal exact owner and is idempotent after row cleanup`() = runTest {
		insertActiveOwner(LOGICAL_ID, RUN_ONE)
		val opened = lifecycle.openOrResumeExact(
			LOGICAL_ID,
			RUN_ONE,
			null,
			segment(LOGICAL_ID, RUN_ONE),
		)

		lifecycle.acknowledgeQuiescedExact(opened.binding, 2_000L) shouldBe
			PresentationQuiescenceResult.NOT_TERMINAL

		val dao = database.sourceSessionDao()
		val activeRun = requireNotNull(dao.serviceRun(RUN_ONE))
		dao.updateServiceRun(
			activeRun.copy(
				state = SessionLifecycleState.FINALIZED.name,
				completedAtMs = 2_000L,
				completionReason = "STOPPED",
			),
		) shouldBe 1

		lifecycle.acknowledgeQuiescedExact(opened.binding, 2_100L) shouldBe
			PresentationQuiescenceResult.ACKNOWLEDGED
		val quiesced = requireNotNull(dao.serviceRun(RUN_ONE))
		quiesced.presentationAcknowledgement shouldBe SourceServiceRunEntity.PRESENTATION_QUIESCED
		quiesced.presentationAcknowledgedAtMs shouldBe 2_100L
		quiesced.runRevision shouldBe 7L
		database.sessionSegmentDao().getById(opened.segment.id) shouldBe opened.segment

		database.sessionSegmentDao().deleteById(opened.segment.id)
		lifecycle.acknowledgeQuiescedExact(opened.binding, 2_200L) shouldBe
			PresentationQuiescenceResult.ALREADY_ACKNOWLEDGED
	}

	@Test
	fun `completed failed run may acknowledge exact writer quiescence`() = runTest {
		insertActiveOwner(LOGICAL_ID, RUN_ONE)
		val opened = lifecycle.openOrResumeExact(
			LOGICAL_ID,
			RUN_ONE,
			null,
			segment(LOGICAL_ID, RUN_ONE),
		)
		val dao = database.sourceSessionDao()
		val activeRun = requireNotNull(dao.serviceRun(RUN_ONE))
		dao.updateServiceRun(
			activeRun.copy(
				state = SessionLifecycleState.FAILED.name,
				completedAtMs = 2_000L,
				completionReason = "TERMINAL_FAILURE",
			),
		) shouldBe 1

		lifecycle.acknowledgeQuiescedExact(opened.binding, 2_100L) shouldBe
			PresentationQuiescenceResult.ACKNOWLEDGED
	}

	@Test
	fun `failed current initialization recovers its closed segment and quiesces only after finalization`() = runTest {
		val wal = insertFailedInitialization()
		lifecycle.recoverMissingForRetainedEvent(wal.eventId) shouldBe true
		val dao = database.sourceSessionDao()
		val recovered = requireNotNull(dao.serviceRun(RUN_ONE))
		val segmentId = requireNotNull(recovered.sessionSegmentId)
		val closed = requireNotNull(database.sessionSegmentDao().getById(segmentId))
		closed.startTimeMs shouldBe 1_000L
		closed.endTimeMs shouldBe 2_000L
		closed.logicalTrackingId shouldBe LOGICAL_ID
		closed.serviceRunId shouldBe RUN_ONE
		recovered.state shouldBe SessionLifecycleState.STOPPING.name
		recovered.presentationAcknowledgement shouldBe SourceServiceRunEntity.PRESENTATION_PENDING
		lifecycle.recoveredInitializationBinding(LOGICAL_ID, RUN_ONE) shouldBe null
		lifecycle.recoverMissingForRetainedEvent(wal.eventId) shouldBe true
		database.sessionSegmentDao().countTotal() shouldBe 1L
		database.sourceEventWalDao().getByEventId(wal.eventId)?.integrityIdentity shouldBe wal.integrityIdentity
		database.locationSampleDao().countAll() shouldBe 0L

		dao.updateServiceRun(recovered.copy(state = SessionLifecycleState.FINALIZED.name, completedAtMs = 2_100L))
		val session = requireNotNull(dao.session(LOGICAL_ID))
		dao.updateSession(session.copy(state = SessionLifecycleState.FINALIZED.name,
			currentServiceRunId = null, completedAtMs = 2_100L))
		lifecycle.recoveredInitializationBinding(LOGICAL_ID, RUN_ONE) shouldBe
			SessionPresentationBinding(LOGICAL_ID, RUN_ONE, segmentId)
		lifecycle.recoveredInitializationBinding("another-logical", RUN_ONE) shouldBe null
		lifecycle.recoverMissingForRetainedEvent(wal.eventId) shouldBe true
		lifecycle.recoverMissingForRetainedEvent(wal.eventId) shouldBe true
		val quiesced = requireNotNull(dao.serviceRun(RUN_ONE))
		quiesced.sessionSegmentId shouldBe segmentId
		quiesced.presentationAcknowledgement shouldBe SourceServiceRunEntity.PRESENTATION_QUIESCED
		quiesced.presentationAcknowledgedAtMs shouldBe 2_100L
		database.sessionSegmentDao().getById(segmentId) shouldBe closed
	}

	@Test
	fun `ordinary current Stop cannot use failed initialization recovery`() = runTest {
		val wal = insertFailedInitialization(stopReason = "USER_STOP")
		lifecycle.recoverMissingForRetainedEvent(wal.eventId) shouldBe false
		database.sessionSegmentDao().countTotal() shouldBe 0L
	}

	@Test
	fun `failed initialization recovery requires the original Stop clock`() = runTest {
		val wal = insertFailedInitialization(stopBootId = "other-boot")
		lifecycle.recoverMissingForRetainedEvent(wal.eventId) shouldBe false
		database.sessionSegmentDao().countTotal() shouldBe 0L
	}

	@Test
	fun `corrupt Stop and changed original capture authorization do not create a segment`() = runTest {
		val wal = insertFailedInitialization()
		val stop = requireNotNull(database.sourceSessionDao().firstStopIntent(LOGICAL_ID))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_lifecycle_intent_version SET intent_checksum = 'corrupt' WHERE intent_revision = 2")
		lifecycle.recoverMissingForRetainedEvent(wal.eventId) shouldBe false
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_lifecycle_intent_version SET intent_checksum = ? WHERE intent_revision = 2",
			arrayOf(stop.intentChecksum))
		val changed = insertRecoveryWal(wal.copy(admissionOrdinal = 2L, eventId = "changed-authorization",
			sourceSequence = 1L, authorizationFingerprint = "changed"))
		lifecycle.recoverMissingForRetainedEvent(changed.eventId) shouldBe false
		database.sessionSegmentDao().countTotal() shouldBe 0L
	}

	@Test
	fun `recovery preserves Stop time deletion and active run fences`() = runTest {
		val wal = insertFailedInitialization()
		val outside = insertRecoveryWal(wal.copy(admissionOrdinal = 2L, eventId = "after-cutoff", sourceSequence = 1L,
			observedElapsedNanos = 2_000L, observedIntervalStartNanos = 2_000L, receivedElapsedNanos = 2_100L))
		val crossing = insertRecoveryWal(wal.copy(admissionOrdinal = 3L, eventId = "before-run", sourceSequence = 2L,
			observedIntervalStartNanos = 999L))
		val erased = insertRecoveryWal(wal.copy(admissionOrdinal = 4L, eventId = "old-data", sourceSequence = 3L,
			capturedCollectedDataEpoch = 1L))
		for (event in listOf(outside, crossing, erased)) {
			lifecycle.recoverMissingForRetainedEvent(event.eventId) shouldBe false
		}
		val dao = database.sourceSessionDao()
		dao.updateSession(requireNotNull(dao.session(LOGICAL_ID)).copy(state = SessionLifecycleState.ACTIVE.name))
		dao.updateServiceRun(requireNotNull(dao.serviceRun(RUN_ONE)).copy(state = SessionLifecycleState.ACTIVE.name))
		lifecycle.recoverMissingForRetainedEvent(wal.eventId) shouldBe false
		database.sessionSegmentDao().countTotal() shouldBe 0L
		database.sourceEventWalDao().getByEventId(wal.eventId)?.integrityIdentity shouldBe wal.integrityIdentity
	}

	private suspend fun insertFailedInitialization(
		stopReason: String = "INITIALIZATION_FAILURE",
		stopBootId: String = "boot-test",
	): SourceEventWalEntity {
		insertActiveOwner(LOGICAL_ID, RUN_ONE)
		database.sourceEvidenceStateDao().ensure()
		val dao = database.sourceSessionDao()
		dao.updateSession(requireNotNull(dao.session(LOGICAL_ID)).copy(state = SessionLifecycleState.STOPPING.name,
			cutoffAtMs = 2_000L, cutoffElapsedNanos = 2_000L, currentManifestRevision = 1L,
			currentIntentRevision = 2L, lifecycleLeaseGeneration = 1L, lifecycleBootId = "boot-test"))
		dao.updateServiceRun(requireNotNull(dao.serviceRun(RUN_ONE)).copy(state = SessionLifecycleState.STOPPING.name,
			leaseGeneration = 1L))
		val binding = SessionManifestSourceEntity(LOGICAL_ID, 1L, 1, SourceBrokerPurpose.SESSION_CAPTURE, 1L, true, 2)
		val manifest = SessionManifestVersionEntity(LOGICAL_ID, 1L, RUN_ONE, "MANUAL", 1L, 1L, 1L,
			"MANUAL_FOREGROUND_START", "boot-test", 1_000L, 1_000L, "Europe/Prague", null, "SESSION_START", "")
		dao.insertManifest(manifest.copy(manifestChecksum = SessionManifestIntegrity.compute(manifest, listOf(binding))))
		dao.insertManifestSources(listOf(binding))
		val active = SessionLifecycleIntentVersionEntity(logicalTrackingId = LOGICAL_ID, intentRevision = 1L,
			manifestRevision = 1L, desiredState = "ACTIVE", startOrigin = "MANUAL_FOREGROUND_START",
			requestBootId = "boot-test", requestedElapsedRealtimeNanos = 1_000L, requestedWallTimeMs = 1_000L,
			automationEpoch = null, triggerId = null, triggerKind = null, triggerBootId = null,
			triggerObservedElapsedRealtimeNanos = null, triggerReceivedElapsedRealtimeNanos = null,
			triggerExpiresElapsedRealtimeNanos = null, stopReason = null, stopDeadlineBootId = null,
			stopDeadlineElapsedRealtimeNanos = null, intentChecksum = SessionLifecycleIntegrity.checksum(
				LOGICAL_ID, 1L, 1L, "ACTIVE", "MANUAL_FOREGROUND_START", "boot-test", 1_000L, 1_000L, null, null, null, null))
		dao.insertLifecycleIntent(active)
		dao.insertLifecycleIntent(active.copy(intentRevision = 2L, desiredState = "FINALIZED",
			startOrigin = "POLICY_RECONCILIATION", requestBootId = stopBootId, requestedElapsedRealtimeNanos = 2_000L,
			requestedWallTimeMs = 2_000L, stopReason = stopReason, stopDeadlineBootId = stopBootId,
			stopDeadlineElapsedRealtimeNanos = 3_000L, intentChecksum = SessionLifecycleIntegrity.checksum(
				LOGICAL_ID, 2L, 1L, "FINALIZED", stopReason, stopBootId, 2_000L, 3_000L)))
		val demand = SourceDemandEntity("recovery-demand", "recovery-capture", 1, SourceBrokerPurpose.SESSION_CAPTURE,
			LOGICAL_ID, RUN_ONE, 1L, 1L, 1L, 1L, true, 2, maximumAgeMs = 30_000L, desiredLatencyMs = 0L,
			requestedBootId = "boot-test", requestedElapsedRealtimeNanos = 1_000L, requestedAtMs = 1_000L,
			status = SourceDemandEntity.STATUS_ACTIVE, retireBootId = null, retireElapsedRealtimeNanos = null, retiredAtMs = null)
		val broker = database.sourceBrokerDao()
		broker.insertDemands(listOf(demand))
		val authorization = SourceBrokerAuthorization.rows(1, 1L, 1L, listOf(demand), "boot-test", 1_000L, 1_000L)
		broker.insertAuthorizations(authorization)
		return insertRecoveryWal(recoveryWal(authorization.first().authorizationFingerprint))
	}

	private suspend fun insertRecoveryWal(wal: SourceEventWalEntity): SourceEventWalEntity {
		val delivery = wal.copy(deliveryIdentity = "delivery:${wal.eventId}")
		val qualified = delivery.copy(integrityIdentity = delivery.calculatedIntegrityIdentity())
		database.sourceEventWalDao().insertDeliveryUnits(listOf(qualified))
		return qualified
	}

	private fun recoveryWal(authorizationFingerprint: String): SourceEventWalEntity {
		val payload = byteArrayOf(1)
		val wal = SourceEventWalEntity(admissionOrdinal = 1L, eventId = "failed-initialization-reading", providerDedupKey = null,
			deliveryIdentity = "recovery-delivery", deliveryUnitIndex = 0, deliveryUnitCount = 1,
			logicalTrackingId = LOGICAL_ID, serviceRunId = RUN_ONE, sourceKind = 1, sourceInstanceId = "recovery-location",
			registrationGeneration = 1L, physicalConfigurationFingerprint = "recovery-configuration", authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = authorizationFingerprint, sourceSequence = 0L, configRevision = 1L, planAttribution = 1,
			clockDomainId = "boot-test", observedElapsedNanos = 1_500L, observedIntervalStartNanos = 1_500L,
			receivedElapsedNanos = 1_600L, receivedWallTimeMs = 1_600L, wallTimeMs = 1_500L, wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 0L, sourcePolicyRevision = 1L, captureConsentEpoch = 1L, sessionManifestRevision = 1L,
			lifecycleLeaseGeneration = 1L, acquiredAtMs = 1_500L, qualityFlags = 0L, qualityConfidence = null,
			payloadVersion = 2, payload = payload, payloadChecksum = "", createdAtMs = 1_600L)
		return wal.copy(payloadChecksum = wal.calculatedPayloadChecksum())
	}

	private suspend fun insertActiveOwner(logicalTrackingId: String, serviceRunId: String) {
		val dao = database.sourceSessionDao()
		dao.insertSession(logicalSession(logicalTrackingId, serviceRunId))
		dao.insertServiceRun(serviceRun(logicalTrackingId, serviceRunId))
	}

	private fun logicalSession(
		logicalTrackingId: String,
		serviceRunId: String,
	) = LogicalTrackingSessionEntity(
		logicalTrackingId = logicalTrackingId,
		state = SessionLifecycleState.ACTIVE.name,
		lifecycleRevision = 3L,
		desiredPlanRevision = 1L,
		rolloutRevision = 1L,
		startOrigin = "MANUAL_FOREGROUND_START",
		clockDomainId = "boot-test",
		startedAtMs = 1_000L,
		startedElapsedNanos = 1_000L,
		cutoffAtMs = null,
		cutoffElapsedNanos = null,
		completedAtMs = null,
		finalAdmissionOrdinal = null,
		failureCode = null,
		currentServiceRunId = serviceRunId,
	)

	private fun serviceRun(
		logicalTrackingId: String,
		serviceRunId: String,
	) = SourceServiceRunEntity(
		serviceRunId = serviceRunId,
		logicalTrackingId = logicalTrackingId,
		state = SessionLifecycleState.ACTIVE.name,
		desiredPlanRevision = 1L,
		rolloutRevision = 1L,
		foregroundCapabilityFlags = 0L,
		startedAtMs = 1_000L,
		startedElapsedNanos = 1_000L,
		completedAtMs = null,
		completionReason = null,
		bootId = "boot-test",
		runRevision = 7L,
	)

	private fun segment(
		logicalTrackingId: String,
		serviceRunId: String,
	) = SessionSegment(
		startTimeMs = 1_000L,
		endTimeMs = 1_000L,
		distanceM = 0f,
		steps = 0,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 0,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = "test",
		createdAt = 1_000L,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
	)

	private companion object {
		const val LOGICAL_ID = "logical-test"
		const val RUN_ONE = "run-one"
		const val RUN_TWO = "run-two"
	}
}
