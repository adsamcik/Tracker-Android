package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryFragment
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryPage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageQuery
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityHistoryComposerTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		database = AppDatabase.testDatabase(application)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `ordinary discovery finds Activity-only intent before its first fact`() = runTest {
		val fixture = fixture(listOf(RunSpec(1L, "run-a", "UTC"))).copy(
			revisions = emptyList(),
			cursors = emptyList(),
			fragmentsByRevision = emptyMap(),
			evidenceByRevision = emptyMap(),
		)
		val segment = fixture.expansion.segments.single()
		segment.sampleCount shouldBe 0
		persistFixture(fixture)

		val candidates = database.activityCapturedFactDao().logicalHistoryCandidatePage(
			10, null, null, ACTIVITY_SOURCE,
		)

		candidates shouldHaveSize 1
		candidates.single().segment.id shouldBe segment.id
		val repository = DefaultActivityHistoryRepository(
			database,
			EXECUTION_AUTHORITY,
			UnconfinedTestDispatcher(testScheduler),
		)
		val entry = (repository.recent(1) as ActivityHistoryPage.Available).entries.single()
		entry.capturesOnlyActivity shouldBe true
		entry.activeTime shouldBe null
		entry.fragments shouldBe emptyList()
	}

	@Test
	fun `repository session loads one exact Room snapshot`() = runTest {
		val snapshot = fixture(listOf(RunSpec(1L, "run-a", "UTC")))
		persistFixture(snapshot)
		var authorityChecks = 0
		val repository = DefaultActivityHistoryRepository(
			database,
			SourceProductLaneExecutionAuthority {
				authorityChecks += 1
				database.inTransaction()
			},
			UnconfinedTestDispatcher(testScheduler),
		)

		val query = repository.session(snapshot.expansion.segments.single().id)

		val entry = (query as ActivityHistoryQuery.Found).entry
		entry.startTime shouldBe EpochMs(1_000L)
		entry.endTime shouldBe EpochMs(1_500L)
		entry.state shouldBe ActivityHistoryProductState.PARTIAL
		entry.activeTime?.knownActiveDurationNanos shouldBe 100L
		entry.storedZoneIds shouldBe setOf("UTC")
		entry.capturesOnlyActivity shouldBe true
		authorityChecks shouldBe 1
	}

	@Test
	fun `Activity live facade classifies capture and product in one Room snapshot`() = runTest {
		val snapshot = fixture(listOf(RunSpec(1L, "run-a", "UTC")))
		persistFixture(snapshot)
		var authorityChecks = 0
		val authority = SourceProductLaneExecutionAuthority {
			authorityChecks += 1
			database.inTransaction()
		}
		val activityRepository = DefaultActivityHistoryRepository(
			database,
			authority,
			UnconfinedTestDispatcher(testScheduler),
		)
		val stepsSelector = StepsSegmentHistorySelector(database, authority)
		val repository = DefaultTrackingHistoryRepository(
			database = database,
			stepsSelector = stepsSelector,
			logicalHistoryReader = LogicalTrackingHistoryReader(database, stepsSelector),
			pressureSelector = PressureHistorySelector(database, authority),
			activityHistoryRepository = activityRepository,
			ioDispatcher = UnconfinedTestDispatcher(testScheduler),
		)

		val live = repository.observeLiveSession(
			snapshot.expansion.segments.single().id,
		).first()

		(live.session as SessionHistoryQuery.Found).history.segmentId shouldBe live.segmentId
		val activity = (live.activity as ActivityHistoryQuery.Found).entry
		activity.capturesOnlyActivity shouldBe true
		activity.activeTime?.knownActiveDurationNanos shouldBe 100L
		authorityChecks shouldBe 1
	}

	@Test
	fun `repository expands replacement membership and recency across Room pages`() = runTest {
		val specs = (1L..33L).map { revision ->
			RunSpec(
				revision = revision,
				runId = "run-$revision",
				zone = if (revision == 33L) "Europe/Prague" else "UTC",
			)
		}
		val snapshot = fixture(specs)
		persistFixture(snapshot)
		val repository = DefaultActivityHistoryRepository(
			database,
			EXECUTION_AUTHORITY,
			UnconfinedTestDispatcher(testScheduler),
		)

		val selected = repository.session(snapshot.expansion.segments.first().id)
		val recent = repository.recent(1)

		val selectedEntry = (selected as ActivityHistoryQuery.Found).entry
		val recentEntry = (recent as ActivityHistoryPage.Available).entries.single()
		selectedEntry.endTime shouldBe EpochMs(17_500L)
		recentEntry.endTime shouldBe EpochMs(17_500L)
		recentEntry.startTime shouldBe EpochMs(1_000L)
		recentEntry.activeTime?.knownActiveDurationNanos shouldBe 3_300L
		recentEntry.storedZoneIds shouldBe setOf("UTC", "Europe/Prague")
		recentEntry shouldBe selectedEntry
	}

	@Test
	fun `source-aware page replaces factless Activity group once from exact intent`() = runTest {
		val snapshot = fixture(
			listOf(RunSpec(1L, "run-a", "UTC"), RunSpec(2L, "run-b", "UTC")),
		).copy(
			revisions = emptyList(),
			cursors = emptyList(),
			fragmentsByRevision = emptyMap(),
			evidenceByRevision = emptyMap(),
		)
		persistFixture(snapshot)
		val activityRepository = DefaultActivityHistoryRepository(
			database,
			EXECUTION_AUTHORITY,
			UnconfinedTestDispatcher(testScheduler),
		)
		val stepsSelector = StepsSegmentHistorySelector(database, EXECUTION_AUTHORITY)
		val repository = DefaultTrackingHistoryRepository(
			database = database,
			stepsSelector = stepsSelector,
			logicalHistoryReader = LogicalTrackingHistoryReader(database, stepsSelector),
			pressureSelector = PressureHistorySelector(database, EXECUTION_AUTHORITY),
			activityHistoryRepository = activityRepository,
			ioDispatcher = UnconfinedTestDispatcher(testScheduler),
		)

		val page = repository.observeRecentSourceAwarePage(
			candidateSegmentIds = snapshot.expansion.segments.map(SessionSegment::id),
			limit = 5,
		).first() as SourceAwareHistoryPageQuery.Content

		page.entries shouldHaveSize 1
		val activity = (page.entries.single() as SourceAwareHistoryPageEntry.ActivityOnly).history
		activity.capturesOnlyActivity shouldBe true
		activity.activeTime shouldBe null
		activity.fragments shouldBe emptyList()
	}

	@Test
	fun `replacement runs compose as one logical entry with exact uncovered intervals`() {
		val snapshot = fixture(
			listOf(RunSpec(1L, "run-a", "Europe/Prague"), RunSpec(2L, "run-b", "UTC")),
		)

		val entries = ActivityHistoryComposer.composeRecent(snapshot, EXECUTION_AUTHORITY)

		entries shouldHaveSize 1
		val entry = entries.single().entry
		entry.state shouldBe ActivityHistoryProductState.PARTIAL
		entry.coverage shouldBe ActivityHistoryCoverage.PARTIAL
		entry.fragments shouldHaveSize 6
		entry.activeTime?.knownActiveDurationNanos shouldBe 200L
		entry.activeTime?.unobservedDurationNanos shouldBe 800L
		entry.causes shouldBe setOf(ActivityHistoryCause.PROVIDER_GAP)
		entry.storedZoneIds shouldBe setOf("Europe/Prague", "UTC")
		entry.key.toString() shouldBe "ActivityHistoryEntryKey"
	}

	@Test
	fun `all-revision Activity-only intent rejects a mixed captured source`() {
		val base = fixture(
			listOf(RunSpec(1L, "run-a", "UTC"), RunSpec(2L, "run-b", "UTC")),
		)
		val key = ActivityManifestKey(LOGICAL_ID, 2L)
		val activitySource = base.sourcesByManifest.getValue(key).single()
		val locationSource = activitySource.copy(
			sourceKind = SOURCE_LOCATION,
			outputDestination = null,
			writerOwner = null,
			writerOwnerGeneration = null,
			writerProjectionId = null,
			writerProjectionVersion = null,
			writerBindingGeneration = null,
		)
		val sources = listOf(activitySource, locationSource)
		val unsignedManifest = base.manifestsByRun.getValue("run-b").single().copy(
			manifestChecksum = "pending",
		)
		val mixedManifest = unsignedManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, sources),
		)
		val snapshot = base.copy(
			manifestsByRun = base.manifestsByRun + ("run-b" to listOf(mixedManifest)),
			sourcesByManifest = base.sourcesByManifest + (key to sources),
		)

		val entry = ActivityHistoryComposer.composeRecent(snapshot, EXECUTION_AUTHORITY).single().entry

		entry.capturesOnlyActivity shouldBe false
	}

	@Test
	fun `Activity-only intent ignores a known nonpersistent capture membership`() {
		val snapshot = fixtureWithAdditionalManifestSource { source ->
			source.nonpersistentCopy(sourceKind = SOURCE_LOCATION)
		}

		val entry = ActivityHistoryComposer.composeRecent(snapshot, EXECUTION_AUTHORITY).single().entry

		entry.capturesOnlyActivity shouldBe true
	}

	@Test
	fun `Activity-only intent fails closed for an unknown nonpersistent source`() {
		val snapshot = fixtureWithAdditionalManifestSource { source ->
			source.nonpersistentCopy(sourceKind = Int.MAX_VALUE)
		}

		val entry = ActivityHistoryComposer.composeRecent(snapshot, EXECUTION_AUTHORITY).single().entry

		entry.capturesOnlyActivity shouldBe false
	}

	@Test
	fun `Activity-only intent fails closed for an unknown manifest purpose`() {
		val snapshot = fixtureWithAdditionalManifestSource { source ->
			source.nonpersistentCopy(
				sourceKind = SOURCE_LOCATION,
				purpose = "UNKNOWN_PURPOSE",
			)
		}

		val entry = ActivityHistoryComposer.composeRecent(snapshot, EXECUTION_AUTHORITY).single().entry

		entry.capturesOnlyActivity shouldBe false
	}

	@Test
	fun `verified Activity-only intent survives later writer authority failure`() {
		val snapshot = fixture(listOf(RunSpec(1L, "run-a", "UTC"))).copy(lanes = emptyList())

		val entry = ActivityHistoryComposer.composeRecent(snapshot, EXECUTION_AUTHORITY).single().entry

		entry.state shouldBe ActivityHistoryProductState.FAILED
		entry.causes shouldBe setOf(ActivityHistoryCause.WRITER_PROVENANCE_INVALID)
		entry.capturesOnlyActivity shouldBe true
		entry.activeTime shouldBe null
		entry.fragments shouldBe emptyList()
	}

	@Test
	fun `explicit persisted gap remains partial and is not converted into a zero observation`() {
		val snapshot = fixture(listOf(RunSpec(1L, "run-a", "UTC", gap = true)))

		val entry = ActivityHistoryComposer.composeRecent(snapshot, EXECUTION_AUTHORITY).single().entry

		entry.state shouldBe ActivityHistoryProductState.PARTIAL
		entry.coverage shouldBe ActivityHistoryCoverage.PARTIAL
		entry.causes shouldBe setOf(ActivityHistoryCause.PROVIDER_GAP)
		entry.fragments shouldHaveSize 3
		entry.fragments.all { it is ActivityHistoryFragment.Gap } shouldBe true
		entry.activeTime?.unobservedDurationNanos shouldBe 500L
	}

	@Test
	fun `missing retained facts is typed unavailable and never fabricates active time`() {
		val snapshot = fixture(listOf(RunSpec(1L, "run-a", "UTC"))).copy(
			revisions = emptyList(),
			cursors = emptyList(),
			fragmentsByRevision = emptyMap(),
			evidenceByRevision = emptyMap(),
			completenessByRun = mapOf(
				"run-a" to listOf(completeness("run-a", generation = 0L, stop = "PROVIDER_FAILED")),
			),
		)

		val entry = ActivityHistoryComposer.composeRecent(snapshot, EXECUTION_AUTHORITY).single().entry

		entry.state shouldBe ActivityHistoryProductState.UNAVAILABLE
		entry.causes shouldBe setOf(ActivityHistoryCause.PROVIDER_UNAVAILABLE)
		entry.activeTime shouldBe null
		entry.fragments shouldBe emptyList()
	}

	@Test
	fun `correction moved outside its immutable physical owner fails the whole logical entry`() {
		val original = fixture(listOf(RunSpec(1L, "run-a", "UTC")))
		val first = original.revisions.single()
		val corrupt = first.copy(
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			mutationId = ActivityCapturedFactIntegrity.mutationId(first.logicalWindowId, 2L),
			serviceRunId = "moved-run",
		)
		val snapshot = original.copy(revisions = listOf(first, corrupt))

		val entry = ActivityHistoryComposer.composeRecent(snapshot, EXECUTION_AUTHORITY).single().entry

		entry.state shouldBe ActivityHistoryProductState.FAILED
		entry.causes shouldBe setOf(ActivityHistoryCause.FACT_INTEGRITY_FAILED)
	}

	@Test
	fun `bounded snapshot overflow is a typed failure with all content hidden`() {
		val snapshot = fixture(listOf(RunSpec(1L, "run-a", "UTC"))).copy(overflow = true)

		val entry = ActivityHistoryComposer.composeRecent(snapshot, EXECUTION_AUTHORITY).single().entry

		entry.state shouldBe ActivityHistoryProductState.FAILED
		entry.causes shouldBe setOf(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
		entry.activeTime shouldBe null
		entry.fragments shouldBe emptyList()
	}

	@Test
	fun `active run with no fact is materializing rather than a fabricated zero`() {
		val initial = fixture(listOf(RunSpec(1L, "run-a", "UTC")))
		val activeRun = initial.runs.getValue("run-a").copy(
			state = "ACTIVE",
			completedAtMs = null,
			completionReason = null,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			presentationAcknowledgedAtMs = null,
		)
		val snapshot = initial.copy(
			sessions = mapOf(LOGICAL_ID to initial.sessions.getValue(LOGICAL_ID).copy(
				state = "ACTIVE",
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				completedAtMs = null,
				finalAdmissionOrdinal = null,
				currentServiceRunId = "run-a",
			)),
			runs = mapOf("run-a" to activeRun),
			revisions = emptyList(),
			cursors = emptyList(),
			fragmentsByRevision = emptyMap(),
			evidenceByRevision = emptyMap(),
			completenessByRun = emptyMap(),
		)

		val entry = ActivityHistoryComposer.composeRecent(snapshot, EXECUTION_AUTHORITY).single().entry

		entry.state shouldBe ActivityHistoryProductState.MATERIALIZING
		entry.activeTime shouldBe null
		entry.fragments shouldBe emptyList()
	}

	@Test
	fun `stopping run retains its exact immutable cutoff while materialization drains`() {
		val initial = fixture(listOf(RunSpec(1L, "run-a", "UTC")))
		val stoppingRun = initial.runs.getValue("run-a").copy(
			state = "STOPPING",
			completedAtMs = null,
			completionReason = null,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			presentationAcknowledgedAtMs = null,
		)
		val terminalBoundary = requireNotNull(initial.sessions.getValue(LOGICAL_ID).cutoffElapsedNanos)
		val stoppingSession = initial.sessions.getValue(LOGICAL_ID).copy(
			state = "STOPPING",
			completedAtMs = null,
			finalAdmissionOrdinal = null,
			currentServiceRunId = stoppingRun.serviceRunId,
			currentManifestRevision = 1L,
			lifecycleLeaseGeneration = stoppingRun.leaseGeneration,
			lifecycleBootId = stoppingRun.bootId,
			cutoffAtMs = terminalBoundary,
			cutoffElapsedNanos = terminalBoundary,
		)
		val snapshot = initial.copy(
			sessions = mapOf(LOGICAL_ID to stoppingSession),
			runs = mapOf(stoppingRun.serviceRunId to stoppingRun),
			revisions = emptyList(),
			cursors = emptyList(),
			fragmentsByRevision = emptyMap(),
			evidenceByRevision = emptyMap(),
			completenessByRun = emptyMap(),
		)

		val entry = ActivityHistoryComposer.composeRecent(snapshot, EXECUTION_AUTHORITY).single().entry

		entry.state shouldBe ActivityHistoryProductState.MATERIALIZING
		entry.causes shouldBe setOf(ActivityHistoryCause.SESSION_ACTIVE,
			ActivityHistoryCause.MATERIALIZATION_BEHIND)
		entry.activeTime shouldBe null
	}

	@Test
	fun `terminal and nonterminal lifecycle rows require matching completion evidence`() {
		val initial = fixture(listOf(RunSpec(1L, "run-a", "UTC")))
		val finalizedRun = initial.runs.getValue("run-a")
		val finalizedSession = initial.sessions.getValue(LOGICAL_ID)
		val activeRun = finalizedRun.copy(
			state = "ACTIVE",
			completedAtMs = null,
			completionReason = null,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			presentationAcknowledgedAtMs = null,
		)
		val activeSession = finalizedSession.copy(
			state = "ACTIVE",
			cutoffAtMs = null,
			cutoffElapsedNanos = null,
			completedAtMs = null,
			finalAdmissionOrdinal = null,
			currentServiceRunId = activeRun.serviceRunId,
			currentManifestRevision = 1L,
			lifecycleLeaseGeneration = activeRun.leaseGeneration,
			lifecycleBootId = activeRun.bootId,
		)
		val corrupt = listOf(
			initial.copy(runs = mapOf(finalizedRun.serviceRunId to finalizedRun.copy(completedAtMs = null))),
			initial.copy(runs = mapOf(finalizedRun.serviceRunId to finalizedRun.copy(state = "ACTIVE"))),
			initial.copy(sessions = mapOf(LOGICAL_ID to finalizedSession.copy(completedAtMs = null))),
			initial.copy(
				sessions = mapOf(LOGICAL_ID to activeSession.copy(completedAtMs = finalizedSession.completedAtMs)),
				runs = mapOf(activeRun.serviceRunId to activeRun),
			),
		)

		corrupt.forEach { snapshot ->
			val entry = ActivityHistoryComposer.composeRecent(snapshot, EXECUTION_AUTHORITY).single().entry
			entry.state shouldBe ActivityHistoryProductState.FAILED
			entry.causes shouldBe setOf(ActivityHistoryCause.WRITER_PROVENANCE_INVALID)
		}
	}

	@Test
	fun `unknown and idle lifecycle states fail closed`() {
		listOf(
			"UNKNOWN" to "ACTIVE",
			"ACTIVE" to "UNKNOWN",
			"IDLE" to "IDLE",
		).forEach { (sessionState, runState) ->
			val entry = ActivityHistoryComposer.composeRecent(
				liveSnapshotWithoutFacts(sessionState, runState),
				EXECUTION_AUTHORITY,
			).single().entry

			entry.state shouldBe ActivityHistoryProductState.FAILED
			entry.causes shouldBe setOf(ActivityHistoryCause.WRITER_PROVENANCE_INVALID)
		}
	}

	@Test
	fun `active and starting lifecycle phases cannot cross`() {
		listOf(
			"ACTIVE" to "STARTING",
			"STARTING" to "ACTIVE",
		).forEach { (sessionState, runState) ->
			val entry = ActivityHistoryComposer.composeRecent(
				liveSnapshotWithoutFacts(sessionState, runState),
				EXECUTION_AUTHORITY,
			).single().entry

			entry.state shouldBe ActivityHistoryProductState.FAILED
			entry.causes shouldBe setOf(ActivityHistoryCause.WRITER_PROVENANCE_INVALID)
		}
	}

	@Test
	fun `reconfiguring session permits only its canonical persisted run phases`() {
		listOf("STARTING", "ACTIVE", "RECONFIGURING").forEach { runState ->
			val entry = ActivityHistoryComposer.composeRecent(
				liveSnapshotWithoutFacts("RECONFIGURING", runState),
				EXECUTION_AUTHORITY,
			).single().entry

			entry.state shouldBe ActivityHistoryProductState.MATERIALIZING
			entry.causes shouldBe setOf(
				ActivityHistoryCause.SESSION_ACTIVE,
				ActivityHistoryCause.MATERIALIZATION_BEHIND,
			)
		}
	}

	@Test
	fun `newly effective capture manifest without its first fact stays bounded and materializing`() {
		val snapshot = activeReconfiguredSnapshotAwaitingFact()

		val entry = ActivityHistoryComposer.composeRecent(snapshot, EXECUTION_AUTHORITY).single().entry

		entry.state shouldBe ActivityHistoryProductState.MATERIALIZING
		entry.coverage shouldBe ActivityHistoryCoverage.PARTIAL
		entry.activeTime?.knownActiveDurationNanos shouldBe 100L
		entry.activeTime?.unobservedDurationNanos shouldBe 400L
		entry.fragments.sumOf(ActivityHistoryFragment::durationNanos) shouldBe 500L
		entry.causes shouldBe setOf(
			ActivityHistoryCause.PROVIDER_GAP,
			ActivityHistoryCause.MATERIALIZATION_BEHIND,
			ActivityHistoryCause.SESSION_ACTIVE,
		)
	}

	@Test
	fun `active session pointer and authority must match its sole nonterminal run`() {
		val initial = fixture(listOf(RunSpec(1L, "run-a", "UTC")))
		val activeRun = initial.runs.getValue("run-a").copy(
			state = "ACTIVE",
			completedAtMs = null,
			completionReason = null,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			presentationAcknowledgedAtMs = null,
		)
		val activeSession = initial.sessions.getValue(LOGICAL_ID).copy(
			state = "ACTIVE",
			cutoffAtMs = null,
			cutoffElapsedNanos = null,
			completedAtMs = null,
			finalAdmissionOrdinal = null,
			currentServiceRunId = activeRun.serviceRunId,
			currentManifestRevision = 1L,
			lifecycleLeaseGeneration = activeRun.leaseGeneration,
			lifecycleBootId = activeRun.bootId,
		)
		val active = initial.copy(
			sessions = mapOf(LOGICAL_ID to activeSession),
			runs = mapOf(activeRun.serviceRunId to activeRun),
		)
		val mismatches = listOf(
			activeSession.copy(currentServiceRunId = "stale-run"),
			activeSession.copy(currentManifestRevision = 2L),
			activeSession.copy(lifecycleLeaseGeneration = activeRun.leaseGeneration + 1L),
			activeSession.copy(lifecycleBootId = "stale-boot"),
		)

		mismatches.forEach { mismatch ->
			val entry = ActivityHistoryComposer.composeRecent(
				active.copy(sessions = mapOf(LOGICAL_ID to mismatch)),
				EXECUTION_AUTHORITY,
			).single().entry
			entry.state shouldBe ActivityHistoryProductState.FAILED
			entry.causes shouldBe setOf(ActivityHistoryCause.WRITER_PROVENANCE_INVALID)
		}
	}

	@Test
	fun `two nonterminal replacement runs cannot publish one active logical session`() {
		val initial = fixture(
			listOf(RunSpec(1L, "run-a", "UTC"), RunSpec(2L, "run-b", "UTC")),
		)
		val activeRuns = initial.runs.mapValues { (_, run) ->
			run.copy(
				state = "ACTIVE",
				completedAtMs = null,
				completionReason = null,
				presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
				presentationAcknowledgedAtMs = null,
			)
		}
		val current = activeRuns.getValue("run-b")
		val session = initial.sessions.getValue(LOGICAL_ID).copy(
			state = "ACTIVE",
			cutoffAtMs = null,
			cutoffElapsedNanos = null,
			completedAtMs = null,
			finalAdmissionOrdinal = null,
			currentServiceRunId = current.serviceRunId,
			currentManifestRevision = 2L,
			lifecycleLeaseGeneration = current.leaseGeneration,
			lifecycleBootId = current.bootId,
		)

		val entry = ActivityHistoryComposer.composeRecent(
			initial.copy(
				sessions = mapOf(LOGICAL_ID to session),
				runs = activeRuns,
			),
			EXECUTION_AUTHORITY,
		).single().entry

		entry.state shouldBe ActivityHistoryProductState.FAILED
		entry.causes shouldBe setOf(ActivityHistoryCause.WRITER_PROVENANCE_INVALID)
	}

	@Test
	fun `execution authority and full lane shape fail closed`() {
		val snapshot = fixture(listOf(RunSpec(1L, "run-a", "UTC")))

		val notOwned = ActivityHistoryComposer.composeRecent(
			snapshot,
			SourceProductLaneExecutionAuthority { false },
		).single().entry
		val malformed = ActivityHistoryComposer.composeRecent(
			snapshot.copy(lanes = listOf(snapshot.lanes.single().copy(retentionRequired = false))),
			EXECUTION_AUTHORITY,
		).single().entry

		notOwned.state shouldBe ActivityHistoryProductState.FAILED
		notOwned.causes shouldBe setOf(ActivityHistoryCause.WRITER_PROVENANCE_INVALID)
		malformed.state shouldBe ActivityHistoryProductState.FAILED
	}

	@Test
	fun `cursor revision and applied time are part of exact semantic authority`() {
		val original = fixture(listOf(RunSpec(1L, "run-a", "UTC")))
		val cursor = original.cursors.single()

		listOf(
			cursor.copy(cursorRevision = 2L),
			cursor.copy(updatedAtMs = cursor.updatedAtMs + 1L),
		).forEach { corrupt ->
			val entry = ActivityHistoryComposer.composeRecent(
				original.copy(cursors = listOf(corrupt)), EXECUTION_AUTHORITY,
			).single().entry
			entry.state shouldBe ActivityHistoryProductState.FAILED
			entry.causes shouldBe setOf(ActivityHistoryCause.FACT_INTEGRITY_FAILED)
		}
	}

	@Test
	fun `retained earlier consent policy is accepted while newer consent policy is rejected`() {
		val original = fixture(
			listOf(RunSpec(1L, "run-a", "UTC"), RunSpec(2L, "run-b", "UTC")),
		)
		val retained = original.consents.getValue(2L).copy(policyRevision = 1L)
		val retainedEntry = ActivityHistoryComposer.composeRecent(
			original.copy(consents = original.consents + (2L to retained)), EXECUTION_AUTHORITY,
		).single().entry
		val future = original.consents.getValue(1L).copy(policyRevision = 2L)
		val futureEntry = ActivityHistoryComposer.composeRecent(
			original.copy(consents = original.consents + (1L to future)), EXECUTION_AUTHORITY,
		).single().entry

		retainedEntry.state shouldBe ActivityHistoryProductState.PARTIAL
		futureEntry.state shouldBe ActivityHistoryProductState.FAILED
		futureEntry.causes shouldBe setOf(ActivityHistoryCause.WRITER_PROVENANCE_INVALID)
	}

	@Test
	fun `later no-fact settlement ordinal keeps the logical result materializing`() {
		val original = fixture(listOf(RunSpec(1L, "run-a", "UTC")))
		val later = completeness("run-a", generation = 2L, stop = "COMPLETE")
		val firstPlan = original.registrationPlans.values.single()
		val laterPlan = ActivityCapturedRegistrationPlanEntity.create(
			sourceInstanceId = later.sourceInstanceId,
			registrationGeneration = later.registrationGeneration,
			configurationRevision = firstPlan.configurationRevision,
			desiredPlanPayloadVersion = firstPlan.desiredPlanPayloadVersion,
			desiredPlanPayloadChecksum = firstPlan.desiredPlanPayloadChecksum,
			physicalConfigurationFingerprint = firstPlan.physicalConfigurationFingerprint,
			appliedAtElapsedRealtimeNanos = firstPlan.appliedAtElapsedRealtimeNanos,
			applyStatus = firstPlan.applyStatus,
		)
		val laterProvider = original.providerRegistrations.values.single().copy(
			registrationGeneration = 2L,
			sourceInstanceId = later.sourceInstanceId,
		)
		val snapshot = original.copy(
			sessions = mapOf(LOGICAL_ID to original.sessions.getValue(LOGICAL_ID).copy(finalAdmissionOrdinal = 2L)),
			completenessByRun = mapOf("run-a" to listOf(original.completenessByRun.getValue("run-a").single(), later)),
			registrationPlans = original.registrationPlans + ((later.sourceInstanceId to 2L) to laterPlan),
			providerRegistrations = original.providerRegistrations + (2L to laterProvider),
			lanes = listOf(lane(throughOrdinal = 1L)),
		)

		val entry = ActivityHistoryComposer.composeRecent(snapshot, EXECUTION_AUTHORITY).single().entry
		val terminal = ActivityHistoryComposer.composeRecent(
			snapshot.copy(
				lanes = listOf(lane(throughOrdinal = 2L)),
				terminalFailures = listOf(
					SourceProjectionFailureEntity(
						projectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
						projectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
						admissionOrdinal = 2L,
						attemptCount = 1,
						failureCode = "TERMINAL",
						terminal = true,
						lastAttemptAtMs = 1L,
					),
				),
			),
			EXECUTION_AUTHORITY,
		).single().entry

		entry.state shouldBe ActivityHistoryProductState.MATERIALIZING
		entry.causes shouldBe setOf(ActivityHistoryCause.PROVIDER_GAP, ActivityHistoryCause.MATERIALIZATION_BEHIND)
		terminal.state shouldBe ActivityHistoryProductState.FAILED
		terminal.causes shouldBe setOf(ActivityHistoryCause.WRITER_PROVENANCE_INVALID)
	}

	@Test
	fun `desired plan checksum and policy qos are authenticated`() {
		val original = fixture(listOf(RunSpec(1L, "run-a", "UTC")))
		val badPlan = original.desiredPlans.getValue(1L).copy(payloadChecksum = "0".repeat(64))
		val badPlanEntry = ActivityHistoryComposer.composeRecent(
			original.copy(desiredPlans = mapOf(1L to badPlan)), EXECUTION_AUTHORITY,
		).single().entry
		val canonical = original.desiredPlans.getValue(1L)
		val trailingPayload = canonical.payload + 0.toByte()
		val trailing = canonical.copy(payload = trailingPayload, payloadChecksum = sha256(trailingPayload))
		val trailingEntry = ActivityHistoryComposer.composeRecent(
			original.copy(desiredPlans = mapOf(1L to trailing)), EXECUTION_AUTHORITY,
		).single().entry
		val badPolicy = original.policies.getValue(1L).copy(qosCode = 99)
		val badPolicyEntry = ActivityHistoryComposer.composeRecent(
			original.copy(policies = mapOf(1L to badPolicy)), EXECUTION_AUTHORITY,
		).single().entry

		badPlanEntry.state shouldBe ActivityHistoryProductState.FAILED
		trailingEntry.state shouldBe ActivityHistoryProductState.FAILED
		badPolicyEntry.state shouldBe ActivityHistoryProductState.FAILED
	}

	private fun fixtureWithAdditionalManifestSource(
		additionalSource: (SessionManifestSourceEntity) -> SessionManifestSourceEntity,
	): ActivityHistorySnapshot {
		val base = fixture(listOf(RunSpec(1L, "run-a", "UTC")))
		val key = ActivityManifestKey(LOGICAL_ID, 1L)
		val activitySource = base.sourcesByManifest.getValue(key).single()
		val sources = listOf(activitySource, additionalSource(activitySource))
		val original = base.manifestsByRun.getValue("run-a").single()
		val manifest = original.copy(
			manifestChecksum = SessionManifestIntegrity.compute(original, sources),
		)
		return base.copy(
			manifestsByRun = mapOf("run-a" to listOf(manifest)),
			sourcesByManifest = mapOf(key to sources),
		)
	}

	private fun SessionManifestSourceEntity.nonpersistentCopy(
		sourceKind: Int,
		purpose: String = this.purpose,
	): SessionManifestSourceEntity = copy(
		sourceKind = sourceKind,
		purpose = purpose,
		consentEpoch = 0L,
		persistenceEligible = false,
		outputDestination = null,
		writerOwner = null,
		writerOwnerGeneration = null,
		writerProjectionId = null,
		writerProjectionVersion = null,
		writerBindingGeneration = null,
	)

	private fun fixture(specs: List<RunSpec>): ActivityHistorySnapshot {
		val built = specs.map(::buildRun)
		val sessionStart = built.minOf { it.run.startedElapsedNanos }
		val sessionEnd = built.maxOf { requireNotNull(it.run.completedAtMs) }
		return ActivityHistorySnapshot(
			expansion = ActivityMembershipExpansion(built.map(BuiltRun::segment), emptyMap(), false),
			sessions = mapOf(LOGICAL_ID to LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_ID,
				state = "FINALIZED",
				lifecycleRevision = 1L,
				desiredPlanRevision = specs.maxOf(RunSpec::revision),
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				clockDomainId = ACTIVITY_BOOT_ID,
				startedAtMs = sessionStart,
				startedElapsedNanos = sessionStart,
				cutoffAtMs = sessionEnd,
				cutoffElapsedNanos = sessionEnd,
				completedAtMs = sessionEnd,
				finalAdmissionOrdinal = specs.maxOf(RunSpec::revision),
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = specs.maxOf(RunSpec::revision),
				currentIntentRevision = 1L,
				currentServiceRunId = null,
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = ACTIVITY_BOOT_ID,
				automationEpoch = null,
			)),
			runs = built.associate { it.run.serviceRunId to it.run },
			manifestsByRun = built.associate { it.run.serviceRunId to listOf(it.manifest) },
			sourcesByManifest = built.associate {
				ActivityManifestKey(LOGICAL_ID, it.manifest.manifestRevision) to listOf(it.source)
			},
			policies = built.associate { it.policy.policyRevision to it.policy },
			consents = built.associate { it.consent.epoch to it.consent },
			completenessByRun = built.associate { it.run.serviceRunId to listOf(it.completeness) },
			revisions = built.map(BuiltRun::revision),
			cursors = built.map(BuiltRun::cursor),
			fragmentsByRevision = built.associate { revisionKey(it.revision) to listOf(it.fragment) },
			evidenceByRevision = built.associate { revisionKey(it.revision) to it.evidence },
			registrationPlans = built.associate {
				(it.plan.sourceInstanceId to it.plan.registrationGeneration) to it.plan
			},
			acquisitionPlanRevisions = built.associate { it.planHeader.revision to it.planHeader },
			desiredPlans = built.associate { it.desiredPlan.revision to it.desiredPlan },
			providerRegistrations = built.associate {
				it.provider.registrationGeneration to it.provider
			},
			authorizationsByRegistration = built.flatMap(BuiltRun::authorizations)
				.groupBy(SourceAuthorizationEntity::registrationGeneration),
			deletionFenceDigests = emptySet(),
			lanes = listOf(lane(specs.size.toLong())),
			terminalFailures = emptyList(),
			evidenceState = SourceEvidenceState(collectedDataEpoch = 0L),
			overflow = false,
		)
	}

	private fun liveSnapshotWithoutFacts(
		sessionState: String,
		runState: String,
	): ActivityHistorySnapshot {
		val initial = fixture(listOf(RunSpec(1L, "run-a", "UTC")))
		val run = initial.runs.getValue("run-a").copy(
			state = runState,
			completedAtMs = null,
			completionReason = null,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			presentationAcknowledgedAtMs = null,
		)
		val session = initial.sessions.getValue(LOGICAL_ID).copy(
			state = sessionState,
			cutoffAtMs = null,
			cutoffElapsedNanos = null,
			completedAtMs = null,
			finalAdmissionOrdinal = null,
			currentServiceRunId = run.serviceRunId,
			currentManifestRevision = 1L,
			lifecycleLeaseGeneration = run.leaseGeneration,
			lifecycleBootId = run.bootId,
		)
		return initial.copy(
			sessions = mapOf(LOGICAL_ID to session),
			runs = mapOf(run.serviceRunId to run),
			revisions = emptyList(),
			cursors = emptyList(),
			fragmentsByRevision = emptyMap(),
			evidenceByRevision = emptyMap(),
			completenessByRun = emptyMap(),
		)
	}

	private fun activeReconfiguredSnapshotAwaitingFact(): ActivityHistorySnapshot {
		val first = fixture(listOf(RunSpec(1L, "run-a", "UTC")))
		val second = fixture(listOf(RunSpec(2L, "run-b", "UTC")))
		val secondSource = second.sourcesByManifest.getValue(ActivityManifestKey(LOGICAL_ID, 2L)).single()
		val unsignedSecondManifest = second.manifestsByRun.getValue("run-b").single().copy(
			serviceRunId = "run-a",
			startOrigin = "POLICY_RECONCILIATION",
			changeReason = "POLICY_RECONCILIATION",
			manifestChecksum = "pending",
		)
		val secondManifest = unsignedSecondManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsignedSecondManifest, listOf(secondSource)),
		)
		val activeRun = first.runs.getValue("run-a").copy(
			state = "ACTIVE",
			desiredPlanRevision = 2L,
			completedAtMs = null,
			completionReason = null,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_PENDING,
			presentationAcknowledgedAtMs = null,
		)
		val activeSession = first.sessions.getValue(LOGICAL_ID).copy(
			state = "ACTIVE",
			desiredPlanRevision = 2L,
			cutoffAtMs = null,
			cutoffElapsedNanos = null,
			completedAtMs = null,
			finalAdmissionOrdinal = null,
			currentManifestRevision = 2L,
			currentServiceRunId = activeRun.serviceRunId,
			lifecycleLeaseGeneration = activeRun.leaseGeneration,
			lifecycleBootId = activeRun.bootId,
		)
		val originalRevision = first.revisions.single()
		val draft = originalRevision.copy(
			logicalWindowId = "pending",
			mutationId = "pending",
			sessionRunEffectEndNanos = Long.MAX_VALUE,
			effectChecksum = "pending",
		)
		val logicalWindowId = ActivityCapturedFactIntegrity.logicalWindowId(draft)
		val identified = draft.copy(
			logicalWindowId = logicalWindowId,
			mutationId = ActivityCapturedFactIntegrity.mutationId(logicalWindowId, 1L),
		)
		val fragment = first.fragmentsByRevision.values.single().single().copy(
			logicalWindowId = logicalWindowId,
		)
		val evidence = first.evidenceByRevision.values.single().map { row ->
			row.copy(logicalWindowId = logicalWindowId)
		}
		val revision = identified.copy(
			effectChecksum = ActivityCapturedFactIntegrity.effectChecksum(identified, listOf(fragment), evidence),
		)
		val cursor = first.cursors.single().copy(
			logicalWindowId = logicalWindowId,
			latestMutationId = revision.mutationId,
			latestEffectChecksum = revision.effectChecksum,
			updatedAtMs = revision.appliedAtMs,
		)
		return first.copy(
			sessions = mapOf(LOGICAL_ID to activeSession),
			runs = mapOf(activeRun.serviceRunId to activeRun),
			manifestsByRun = mapOf(activeRun.serviceRunId to listOf(
				first.manifestsByRun.getValue("run-a").single(), secondManifest,
			)),
			sourcesByManifest = first.sourcesByManifest +
				(ActivityManifestKey(LOGICAL_ID, 2L) to listOf(secondSource)),
			policies = first.policies + second.policies,
			consents = first.consents + second.consents,
			acquisitionPlanRevisions = first.acquisitionPlanRevisions + second.acquisitionPlanRevisions,
			desiredPlans = first.desiredPlans + second.desiredPlans,
			revisions = listOf(revision),
			cursors = listOf(cursor),
			fragmentsByRevision = mapOf(revisionKey(revision) to listOf(fragment)),
			evidenceByRevision = mapOf(revisionKey(revision) to evidence),
		)
	}

	private suspend fun persistFixture(snapshot: ActivityHistorySnapshot) {
		val sessionDao = database.sourceSessionDao()
		val policyDao = database.sourcePolicyDao()
		val planDao = database.sourcePlanStateDao()
		val brokerDao = database.sourceBrokerDao()
		val factDao = database.activityCapturedFactDao()
		snapshot.sessions.values.forEach { sessionDao.insertSession(it) }
		database.sessionSegmentDao().insert(snapshot.expansion.segments)
		snapshot.runs.values.sortedBy(SourceServiceRunEntity::startedAtMs)
			.forEach { sessionDao.insertServiceRun(it) }
		snapshot.manifestsByRun.values.flatten()
			.sortedBy(SessionManifestVersionEntity::manifestRevision)
			.forEach { sessionDao.insertManifest(it) }
		sessionDao.insertManifestSources(
			snapshot.sourcesByManifest.values.flatten()
				.sortedBy(SessionManifestSourceEntity::manifestRevision),
		)
		policyDao.insertPolicies(snapshot.policies.values.sortedBy(SourcePolicyEntity::policyRevision))
		policyDao.insertConsentEpochs(snapshot.consents.values.sortedBy(SourceConsentEpochEntity::epoch))
		snapshot.acquisitionPlanRevisions.values.sortedBy(AcquisitionPlanRevisionEntity::revision)
			.forEach { planDao.insertRevision(it) }
		planDao.insertDesiredPlans(snapshot.desiredPlans.values.sortedBy(SourceDesiredPlanEntity::revision))
		snapshot.registrationPlans.values
			.sortedWith(compareBy(ActivityCapturedRegistrationPlanEntity::registrationGeneration))
			.forEach { factDao.insertRegistrationPlanBinding(it) }
		snapshot.providerRegistrations.values
			.sortedBy(ProviderRegistrationGenerationEntity::registrationGeneration)
			.forEach { brokerDao.insertRegistration(it) }
		brokerDao.insertAuthorizations(
			snapshot.authorizationsByRegistration.values.flatten().sortedWith(
				compareBy(
					SourceAuthorizationEntity::registrationGeneration,
					SourceAuthorizationEntity::authorizationRevision,
				),
			),
		)
		snapshot.lanes.forEach { database.sourceProjectionStateDao().installProductLane(it) }
		snapshot.completenessByRun.values.flatten().forEach { sessionDao.saveCompleteness(it) }
		snapshot.revisions.sortedWith(
			compareBy(
				ActivityCapturedWindowRevisionEntity::logicalWindowId,
				ActivityCapturedWindowRevisionEntity::semanticRevision,
			),
		).forEach { revision ->
			factDao.insertRevision(revision)
			val key = revisionKey(revision)
			factDao.insertFragments(snapshot.fragmentsByRevision[key].orEmpty())
			factDao.insertEvidence(snapshot.evidenceByRevision[key].orEmpty())
		}
		snapshot.cursors.forEach { factDao.insertCursor(it) }
		database.sourceEvidenceStateDao().ensure(requireNotNull(snapshot.evidenceState))
	}

	private fun buildRun(spec: RunSpec): BuiltRun {
		val baseElapsed = 1_000L + (spec.revision - 1L) * 500L
		val segment = SessionSegment(
			id = spec.revision,
			startTimeMs = baseElapsed,
			endTimeMs = baseElapsed + 500L,
			distanceM = 0f,
			steps = null,
			primaryActivity = null,
			activityConfidence = null,
			sampleCount = 0,
			source = SegmentSource.INFERRED_HIGH_CONFIDENCE,
			inferenceVersion = null,
			createdAt = baseElapsed,
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = spec.runId,
		)
		val run = SourceServiceRunEntity(
			serviceRunId = spec.runId,
			logicalTrackingId = LOGICAL_ID,
			state = "FINALIZED",
			desiredPlanRevision = spec.revision,
			rolloutRevision = 1L,
			foregroundCapabilityFlags = 0L,
			startedAtMs = baseElapsed,
			startedElapsedNanos = baseElapsed,
			completedAtMs = baseElapsed + 500L,
			completionReason = "STOPPED",
			bootId = ACTIVITY_BOOT_ID,
			leaseGeneration = 1L,
			startOrigin = "MANUAL_FOREGROUND_START",
			desiredForegroundCapabilityFlags = 0L,
			appliedForegroundCapabilityFlags = 0L,
			runtimeAcknowledgement = "ACKNOWLEDGED",
			runRevision = 1L,
			startDeliveryToken = "delivery-${spec.revision}",
			startCommandGeneration = 1L,
			preparedManifestRevision = spec.revision,
			preparedIntentRevision = 1L,
			androidDeliveryState = "ACKNOWLEDGED",
			androidDeliveryUpdatedAtMs = baseElapsed,
			startIsUserInitiated = true,
			sessionSegmentId = segment.id,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = baseElapsed + 500L,
		)
		val source = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = spec.revision,
			sourceKind = ACTIVITY_SOURCE,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			consentEpoch = spec.revision,
			persistenceEligible = true,
			qosCode = 2,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
			writerOwner = SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			writerProjectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION,
		)
		val unsignedManifest = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = spec.revision,
			serviceRunId = spec.runId,
			sessionMode = "MANUAL",
			sourcePolicyRevision = spec.revision,
			acquisitionPlanRevision = spec.revision,
			rolloutRevision = 1L,
			startOrigin = "MANUAL_FOREGROUND_START",
			effectiveBootId = run.bootId,
			effectiveElapsedRealtimeNanos = baseElapsed,
			effectiveWallTimeMs = baseElapsed,
			zoneId = spec.zone,
			automationEpoch = null,
			changeReason = "START",
			manifestChecksum = "pending",
		)
		val manifest = unsignedManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(source)),
		)
		val policy = SourcePolicyEntity(
			policyRevision = spec.revision,
			sourceKind = ACTIVITY_SOURCE,
			enabled = true,
			qosCode = 2,
			locationMinTimeSeconds = null,
			locationMinDistanceMeters = null,
			locationRequiredAccuracyMeters = null,
			capturePersistenceEligible = true,
			controlPersistenceEligible = false,
			ambientPersistenceEligible = false,
			captureConsentEpoch = spec.revision,
			controlConsentEpoch = null,
			ambientConsentEpoch = null,
			effectiveBootId = run.bootId,
			effectiveElapsedRealtimeNanos = baseElapsed,
			effectiveWallTimeMs = baseElapsed,
			changeReason = "START",
		)
		val consent = SourceConsentEpochEntity(
			sourceKind = ACTIVITY_SOURCE,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			epoch = spec.revision,
			eligible = true,
			persistenceEligible = true,
			policyRevision = spec.revision,
			effectiveBootId = run.bootId,
			effectiveElapsedRealtimeNanos = baseElapsed,
			effectiveWallTimeMs = baseElapsed,
			changeReason = "START",
		)
		val desiredPlan = desiredPlan(spec.revision)
		val physicalFingerprint = activityPhysicalFingerprint(
			mode = "TRANSITIONS_ONLY",
			latency = 5_000L,
			confidence = 50,
			transitions = setOf(0, 1),
		)
		val planHeader = AcquisitionPlanRevisionEntity(
			revision = spec.revision,
			planId = "plan-${spec.revision}",
			createdAtMs = baseElapsed,
			status = "APPLIED",
			sourcePolicyRevision = spec.revision,
		)
		val plan = ActivityCapturedRegistrationPlanEntity.create(
			sourceInstanceId = "activity-${spec.revision}",
			registrationGeneration = spec.revision,
			configurationRevision = spec.revision,
			desiredPlanPayloadVersion = desiredPlan.payloadVersion,
			desiredPlanPayloadChecksum = desiredPlan.payloadChecksum,
			physicalConfigurationFingerprint = physicalFingerprint,
			appliedAtElapsedRealtimeNanos = baseElapsed,
			applyStatus = "APPLIED",
		)
		val provider = ProviderRegistrationGenerationEntity(
			sourceKind = ACTIVITY_SOURCE,
			registrationGeneration = spec.revision,
			sourceInstanceId = plan.sourceInstanceId,
			ownerScope = "session-activity",
			clockDomainId = run.bootId,
			physicalConfigurationFingerprint = plan.physicalConfigurationFingerprint,
			collectedDataEpoch = 0L,
			providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
			providerProcessIncarnationId = "process-${spec.revision}",
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			reservedAtMs = baseElapsed,
			reservedElapsedRealtimeNanos = baseElapsed,
			acceptedAtMs = baseElapsed,
			acceptedElapsedRealtimeNanos = baseElapsed,
			retiredAtMs = baseElapsed + 500L,
			retiredElapsedRealtimeNanos = baseElapsed + 500L,
			failureCode = null,
			captureCallbackBarrierAuthorizationRevision = 1L,
		)
		val authorizations = authorizations(spec, run, baseElapsed)
		val draft = revision(spec, run, plan, baseElapsed)
		val logicalWindowId = ActivityCapturedFactIntegrity.logicalWindowId(draft)
		val identified = draft.copy(
			logicalWindowId = logicalWindowId,
			mutationId = ActivityCapturedFactIntegrity.mutationId(logicalWindowId, 1L),
		)
		val fragment = fragment(spec, identified, baseElapsed)
		val evidence = if (spec.gap) emptyList() else listOf(evidence(spec, identified, baseElapsed))
		val revision = identified.copy(
			effectChecksum = ActivityCapturedFactIntegrity.effectChecksum(identified, listOf(fragment), evidence),
		)
		return BuiltRun(
			segment, run, manifest, source, policy, consent, planHeader, desiredPlan, plan, provider, authorizations,
			revision, fragment, evidence,
			ActivityCapturedWindowCursorEntity(
				writerProjectionId = revision.writerProjectionId,
				writerProjectionVersion = revision.writerProjectionVersion,
				logicalWindowId = revision.logicalWindowId,
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = spec.runId,
				sessionSegmentId = segment.id,
				writerOwnerGeneration = revision.writerOwnerGeneration,
				latestSemanticRevision = 1L,
				latestMutationId = revision.mutationId,
				latestEffectChecksum = revision.effectChecksum,
				cursorRevision = 1L,
				collectedDataEpoch = 0L,
				updatedAtMs = baseElapsed + 500L,
			),
			completeness(spec.runId, spec.revision, "COMPLETE"),
		)
	}

	@Suppress("LongParameterList")
	private fun revision(
		spec: RunSpec,
		run: SourceServiceRunEntity,
		plan: ActivityCapturedRegistrationPlanEntity,
		base: Long,
	) = ActivityCapturedWindowRevisionEntity(
		writerProjectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		logicalWindowId = "pending",
		semanticRevision = 1L,
		supersedesSemanticRevision = null,
		mutationId = "pending",
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = spec.runId,
		sessionSegmentId = requireNotNull(run.sessionSegmentId),
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		sourceInstanceId = plan.sourceInstanceId,
		registrationGeneration = plan.registrationGeneration,
		configurationRevision = plan.configurationRevision,
		physicalConfigurationFingerprint = plan.physicalConfigurationFingerprint,
		authorizationRevision = 1L,
		authorizationFingerprint = "a".repeat(64),
		purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		sourcePolicyRevision = spec.revision,
		captureConsentEpoch = spec.revision,
		manifestRevision = spec.revision,
		lifecycleLeaseGeneration = run.leaseGeneration,
		collectedDataEpoch = 0L,
		clockDomainId = run.bootId,
		storedZoneId = spec.zone,
		providerAcceptanceStartNanos = base,
		providerAcceptanceEndNanos = base + 500L,
		authorizationEffectStartNanos = base,
		authorizationEffectEndNanos = base + 500L,
		sessionRunEffectStartNanos = base,
		sessionRunEffectEndNanos = base + 500L,
		windowStartElapsedRealtimeNanos = base + 10L,
		windowEndElapsedRealtimeNanos = base + 110L,
		coverage = if (spec.gap) "NONE" else "COMPLETE",
		knownActiveDurationNanos = if (spec.gap) 0L else 100L,
		knownInactiveDurationNanos = 0L,
		unknownActivityDurationNanos = 0L,
		unobservedDurationNanos = if (spec.gap) 100L else 0L,
		exactDuplicateCount = 0,
		semanticDuplicateCount = 0,
		unchangedEvidenceCount = 0,
		scopeDeletionGeneration = 0L,
		effectChecksum = "pending",
		appliedAtMs = base + 500L,
	)

	private fun fragment(
		spec: RunSpec,
		revision: ActivityCapturedWindowRevisionEntity,
		base: Long,
	): ActivityCapturedFragmentEntity = if (spec.gap) {
		ActivityCapturedFragmentEntity(
			writerProjectionId = revision.writerProjectionId,
			writerProjectionVersion = revision.writerProjectionVersion,
			logicalWindowId = revision.logicalWindowId,
			semanticRevision = 1L,
			fragmentOrdinal = 0,
			fragmentKind = ActivityCapturedFragmentEntity.KIND_GAP,
			bandOrdinal = null,
			intervalStartElapsedRealtimeNanos = base + 10L,
			intervalEndElapsedRealtimeNanos = base + 110L,
			gapReason = "PROVIDER_DISCONTINUITY",
			activity = null,
			mechanism = null,
			refinedTransitionActivity = null,
			confidenceKind = null,
			confidenceMinimumPercent = null,
			confidenceMaximumPercent = null,
			confidenceObservationCount = null,
			startWallTimeMs = null,
			startWallTimeUncertaintyMs = null,
			startBoundaryKind = null,
			startAnchorSourceEventId = null,
			startAnchorProviderElapsedNanos = null,
			endWallTimeMs = null,
			endWallTimeUncertaintyMs = null,
			endBoundaryKind = null,
			endAnchorSourceEventId = null,
			endAnchorProviderElapsedNanos = null,
			wallTimeContinuity = null,
		)
	} else {
		ActivityCapturedFragmentEntity(
			writerProjectionId = revision.writerProjectionId,
			writerProjectionVersion = revision.writerProjectionVersion,
			logicalWindowId = revision.logicalWindowId,
			semanticRevision = 1L,
			fragmentOrdinal = 0,
			fragmentKind = ActivityCapturedFragmentEntity.KIND_BAND,
			bandOrdinal = 0,
			intervalStartElapsedRealtimeNanos = base + 10L,
			intervalEndElapsedRealtimeNanos = base + 110L,
			gapReason = null,
			activity = "WALKING",
			mechanism = "TRANSITION",
			refinedTransitionActivity = null,
			confidenceKind = ActivityCapturedFragmentEntity.CONFIDENCE_TRANSITION,
			confidenceMinimumPercent = null,
			confidenceMaximumPercent = null,
			confidenceObservationCount = null,
			startWallTimeMs = base + 10L,
			startWallTimeUncertaintyMs = 0L,
			startBoundaryKind = "EXACT_PROVIDER_OBSERVATION",
			startAnchorSourceEventId = "event-${spec.revision}",
			startAnchorProviderElapsedNanos = base + 10L,
			endWallTimeMs = base + 110L,
			endWallTimeUncertaintyMs = 0L,
			endBoundaryKind = "SAME_CLOCK_EXTRAPOLATION",
			endAnchorSourceEventId = "event-${spec.revision}",
			endAnchorProviderElapsedNanos = base + 10L,
			wallTimeContinuity = "SAME_ANCHOR",
		)
	}

	private fun evidence(
		spec: RunSpec,
		revision: ActivityCapturedWindowRevisionEntity,
		base: Long,
	) = ActivityCapturedEvidenceEntity(
		writerProjectionId = revision.writerProjectionId,
		writerProjectionVersion = revision.writerProjectionVersion,
		logicalWindowId = revision.logicalWindowId,
		semanticRevision = 1L,
		fragmentOrdinal = 0,
		evidenceOrdinal = 0,
		sourceEventId = "event-${spec.revision}",
		sourceAdmissionOrdinal = spec.revision,
		sourceSequence = 1L,
		providerElapsedRealtimeNanos = base + 10L,
		receivedElapsedRealtimeNanos = base + 11L,
		observationKind = ActivityCapturedEvidenceEntity.KIND_TRANSITION,
		observedActivity = "WALKING",
		transitionChange = "ENTER",
		confidencePercent = null,
		coverageEndExclusiveElapsedRealtimeNanos = null,
	)

	private fun authorizations(
		spec: RunSpec,
		run: SourceServiceRunEntity,
		base: Long,
	): List<SourceAuthorizationEntity> = listOf(
		SourceAuthorizationEntity(
			sourceKind = ACTIVITY_SOURCE,
			registrationGeneration = spec.revision,
			authorizationRevision = 1L,
			memberId = SourceBrokerAuthorization.memberId("demand-${spec.revision}"),
			authorizationFingerprint = "a".repeat(64),
			purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			demandId = "demand-${spec.revision}",
			consumerId = "session",
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			sourcePolicyRevision = spec.revision,
			consentEpoch = spec.revision,
			persistenceEligible = true,
			effectiveBootId = run.bootId,
			effectiveElapsedRealtimeNanos = base,
			effectiveWallTimeMs = base,
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = spec.runId,
			manifestRevision = spec.revision,
			lifecycleLeaseGeneration = run.leaseGeneration,
		),
		SourceAuthorizationEntity(
			sourceKind = ACTIVITY_SOURCE,
			registrationGeneration = spec.revision,
			authorizationRevision = 2L,
			memberId = SourceBrokerAuthorization.DENY_ALL_MEMBER_ID,
			authorizationFingerprint = "b".repeat(64),
			purposeEligibilityMask = 0L,
			demandId = null,
			consumerId = null,
			purpose = null,
			sourcePolicyRevision = null,
			consentEpoch = null,
			persistenceEligible = false,
			effectiveBootId = run.bootId,
			effectiveElapsedRealtimeNanos = base + 500L,
			effectiveWallTimeMs = base + 500L,
			logicalTrackingId = null,
			serviceRunId = null,
			manifestRevision = null,
			lifecycleLeaseGeneration = null,
		),
	)

	private fun desiredPlan(revision: Long): SourceDesiredPlanEntity {
		val payload = ByteArrayOutputStream().use { buffer ->
			DataOutputStream(buffer).use { output ->
				output.writeInt(1)
				output.writeUTF("ACTIVITY")
				output.writeLong(revision)
				output.writeUTF("TRANSITIONS_ONLY")
				output.writeLong(5_000L)
				output.writeInt(50)
				output.writeInt(2)
				output.writeInt(0)
				output.writeInt(1)
			}
			buffer.toByteArray()
		}
		return SourceDesiredPlanEntity(
			revision = revision,
			sourceKind = ACTIVITY_SOURCE,
			payloadVersion = 1,
			payload = payload,
			payloadChecksum = sha256(payload),
		)
	}

	private fun activityPhysicalFingerprint(
		mode: String,
		latency: Long,
		confidence: Int,
		transitions: Set<Int>,
	): String = sha256(
		listOf("ACTIVITY", mode, latency, confidence, transitions.sorted().joinToString(","))
			.joinToString("\u001f").toByteArray(Charsets.UTF_8),
	)

	private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes).joinToString(separator = "") { byte -> "%02x".format(byte) }

	private fun completeness(runId: String, generation: Long, stop: String) =
		SourceSessionCompletenessEntity(
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = runId,
			sourceKind = ACTIVITY_SOURCE,
			sourceInstanceId = if (generation == 0L) "activity-unregistered" else "activity-$generation",
			registrationGeneration = generation,
			lastAdmissionOrdinal = generation.takeIf { it > 0L },
			lastSourceSequence = generation.takeIf { it > 0L },
			appDrainComplete = true,
			providerCoverage = if (generation == 0L) {
				"PROVIDER_COMPLETENESS_UNOBSERVABLE"
			} else {
				"CALLBACKS_ENTERED_BEFORE_BARRIER"
			},
			stopStatus = stop,
			unresolvedSequenceStart = null,
			unresolvedSequenceEnd = null,
			updatedAtMs = 10_000L,
		)

	private fun lane(throughOrdinal: Long) = SourceProductProjectionLaneEntity(
		sourceKind = ACTIVITY_SOURCE,
		bindingGeneration = SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION,
		projectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
		projectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
		captureModeMask = 1L,
		productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
		activatedRolloutRevision = 1L,
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

	private data class RunSpec(val revision: Long, val runId: String, val zone: String, val gap: Boolean = false)

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
		val plan: ActivityCapturedRegistrationPlanEntity,
		val provider: ProviderRegistrationGenerationEntity,
		val authorizations: List<SourceAuthorizationEntity>,
		val revision: ActivityCapturedWindowRevisionEntity,
		val fragment: ActivityCapturedFragmentEntity,
		val evidence: List<ActivityCapturedEvidenceEntity>,
		val cursor: ActivityCapturedWindowCursorEntity,
		val completeness: SourceSessionCompletenessEntity,
	)

	private companion object {
		const val LOGICAL_ID = "logical-activity"
		const val ACTIVITY_BOOT_ID = "boot-activity"
		const val SOURCE_LOCATION = 1
		val EXECUTION_AUTHORITY = SourceProductLaneExecutionAuthority { true }
	}
}
