package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.WifiHistoryBand
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryPage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryQuery
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
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
class WifiHistoryRepositoryRoomTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `zero sample Wi-Fi fact is ordinarily discovered with identity-free metrics`() = runTest {
		val group = buildGroup(1, 1, setOf(0))
		persist(listOf(group))
		var authorityChecks = 0
		val repository = repository {
			authorityChecks += 1
			database.inTransaction()
		}

		val selected = repository.session(group.runs.single().segment.id)
		val recent = repository.recent(1)

		val selectedEntry = (selected as WifiHistoryQuery.Found).entry
		val recentEntry = (recent as WifiHistoryPage.Available).entries.single()
		selectedEntry shouldBe recentEntry
		selectedEntry.state shouldBe WifiHistoryProductState.READY
		selectedEntry.observations shouldHaveSize 1
		selectedEntry.observations.single().observationCount shouldBe 2
		selectedEntry.observations.single().bandMix shouldBe mapOf(
			WifiHistoryBand.TWO_POINT_FOUR_GHZ to 1,
			WifiHistoryBand.FIVE_GHZ to 1,
		)
		group.runs.single().segment.sampleCount shouldBe 0
		authorityChecks shouldBe 2
	}

	@Test
	fun `replacement siblings and correction revisions compose one logical entry`() = runTest {
		val group = buildGroup(2, 33, setOf(0))
		persist(listOf(group))

		val entry = (repository { true }.session(group.runs.last().segment.id) as WifiHistoryQuery.Found).entry

		entry.startTime.raw shouldBe group.runs.first().segment.startTimeMs
		entry.endTime.raw shouldBe group.runs.last().segment.endTimeMs
		entry.observations shouldHaveSize 1
		group.runs.first().facts shouldHaveSize 2
	}

	@Test
	fun `one-hop unchanged coverage reuses only an authenticated current aggregate`() = runTest {
		val group = buildGroup(7, 1, setOf(0), includeReuse = true)
		persist(listOf(group))

		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()

		entry.state shouldBe WifiHistoryProductState.READY
		entry.observations shouldHaveSize 2
		entry.observations.map { it.bandMix }.distinct() shouldHaveSize 1
	}

	@Test
	fun `cursor carrier exposes dangling and corrupt current heads as typed failures`() = runTest {
		val group = buildGroup(3, 1, setOf(0))
		persist(listOf(group))
		val cursor = group.runs.single().cursors.single()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_captured_fact_cursor SET latest_effect_checksum = ? WHERE logical_fact_id = ?",
			arrayOf(sha256("corrupt-head"), cursor.logicalFactId),
		)
		assertRecentFactFailure()

		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_captured_fact_cursor SET latest_effect_checksum = ? WHERE logical_fact_id = ?",
			arrayOf(cursor.latestEffectChecksum, cursor.logicalFactId),
		)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM wifi_captured_fact_revision WHERE logical_fact_id = ? AND semantic_revision = ?",
			arrayOf(cursor.logicalFactId, cursor.latestSemanticRevision),
		)
		assertRecentFactFailure()
	}

	@Test
	fun `privacy epoch and full uncertainty retention fences suppress Wi-Fi values`() = runTest {
		val deleted = buildGroup(8, 1, setOf(0))
		val fact = deleted.runs.single().facts.last()
		persist(listOf(deleted), SourceEvidenceState(
			collectedDataEpoch = 0L,
			deletedSourceEventHighWaterOrdinal = fact.sourceAdmissionOrdinal,
		))
		val deletedEntry = (repository { true }.session(deleted.runs.single().segment.id) as
			WifiHistoryQuery.Found).entry
		deletedEntry.state shouldBe WifiHistoryProductState.UNAVAILABLE
		deletedEntry.causes shouldBe setOf(WifiHistoryCause.PRIVACY_EPOCH_MISMATCH)
		deletedEntry.observations shouldBe emptyList()

		database.close()
		setUp()
		val expired = buildGroup(9, 1, setOf(0))
		val expiredFact = expired.runs.single().facts.last()
		persist(listOf(expired), SourceEvidenceState(
			collectedDataEpoch = 0L,
			retainedFromMs = expiredFact.observedWallTimeMs + expiredFact.wallTimeUncertaintyMs + 1L,
		))
		val expiredEntry = (repository { true }.session(expired.runs.single().segment.id) as
			WifiHistoryQuery.Found).entry
		expiredEntry.state shouldBe WifiHistoryProductState.UNAVAILABLE
		expiredEntry.causes shouldBe setOf(WifiHistoryCause.RETENTION_LIMIT)
		expiredEntry.observations shouldBe emptyList()
	}

	@Test
	fun `partial results and no-fact active capture remain truthful`() = runTest {
		val partial = buildGroup(4, 1, setOf(0), partialResult = true)
		persist(listOf(partial))
		val partialEntry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		partialEntry.state shouldBe WifiHistoryProductState.PARTIAL
		partialEntry.causes shouldBe setOf(WifiHistoryCause.RESULT_SET_PARTIAL)

		database.close()
		setUp()
		val active = buildGroup(5, 1, emptySet(), active = true)
		persist(listOf(active))
		val activeEntry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		activeEntry.state shouldBe WifiHistoryProductState.MATERIALIZING
		activeEntry.observations shouldBe emptyList()

		database.close()
		setUp()
		val unavailableBase = buildGroup(6, 1, emptySet())
		val unavailable = unavailableBase.copy(runs = unavailableBase.runs.map { built ->
			built.copy(completeness = requireNotNull(built.completeness).copy(stopStatus = "PERMISSION_LOST"))
		})
		persist(listOf(unavailable))
		val unavailableEntry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		unavailableEntry.state shouldBe WifiHistoryProductState.UNAVAILABLE
		unavailableEntry.causes shouldBe setOf(WifiHistoryCause.PROVIDER_UNAVAILABLE)
	}

	@Test
	fun `candidate paging uses logical recency and membership overflow and cancellation are typed`() = runTest {
		val groups = (1..33).map { buildGroup(100 + it, 1, setOf(0)) }
		persist(groups)
		val page = repository { true }.recent(33) as WifiHistoryPage.Available
		page.entries shouldHaveSize 33
		page.entries.map { it.startTime.raw } shouldBe groups.asReversed().map { it.runs.single().segment.startTimeMs }

		database.close()
		setUp()
		persist(listOf(buildGroup(200, 129, setOf(0))))
		repository { true }.recent(1) shouldBe WifiHistoryPage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
		assertFailsWith<CancellationException> {
			withContext(Job().apply { cancel() }) { repository { true }.recent(1) }
		}
	}

	@Test
	fun `fact-only history rejects stale and half-open boundary observations`() = runTest {
		val stale = transformFacts(buildGroup(210, 1, setOf(0))) { fact ->
			fact.copy(receivedElapsedNanos = Math.addExact(
				fact.coverageIntervalStartNanos, fact.maximumObservationAgeNanos + 1L,
			))
		}
		persist(listOf(stale))
		assertRecentWriterFailure()

		database.close()
		setUp()
		val base = buildGroup(211, 1, setOf(0))
		val runEnd = base.runs.single().segment.endTimeMs
		val elapsedEnd = base.runs.single().facts.last().sessionRunEffectEndNanos
		val boundary = transformFacts(base) { fact ->
			fact.copy(
				observedElapsedNanos = elapsedEnd,
				receivedElapsedNanos = elapsedEnd,
				coverageIntervalEndNanos = elapsedEnd,
				observedWallTimeMs = runEnd,
				acquiredAtMs = runEnd,
				appliedAtMs = runEnd,
			)
		}
		persist(listOf(boundary))
		assertRecentWriterFailure()
	}

	@Test
	fun `one physical registration may serve a later replacement manifest`() = runTest {
		val group = shareRegistrationAcrossReplacement(buildGroup(212, 2, setOf(1)))

		persist(listOf(group))

		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		entry.state shouldBe WifiHistoryProductState.READY
		entry.observations shouldHaveSize 1
		val reserved = requireNotNull(group.runs.first().provider).reservedElapsedRealtimeNanos
		(reserved < group.runs.last().manifest.effectiveElapsedRealtimeNanos) shouldBe true
	}

	@Test
	fun `source-local attribution queries enforce their SQL result limits`() = runTest {
		val group = buildGroup(213, 2, setOf(1))
		persist(listOf(group))
		val dao = database.wifiCapturedFactDao()
		val runs = group.runs.map { it.run.serviceRunId }

		dao.historyPolicies(WIFI_SOURCE, runs, 1) shouldHaveSize 1
		dao.historyConsentEpochs(
			WIFI_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			group.runs.map { it.consent.epoch },
			1,
		) shouldHaveSize 1
		dao.historyDemands(
			group.runs.flatMap(BuiltRun::demands).map(SourceDemandEntity::demandId),
			1,
		) shouldHaveSize 1
		dao.historyProductLanes(WIFI_SOURCE, SourceBrokerPurpose.SESSION_CAPTURE, runs, 1) shouldHaveSize 1
	}

	@Test
	fun `lane stage rollout chronology and retired cursor shape fail closed`() = runTest {
		val mutations = listOf(
			"UPDATE source_product_projection_lane SET product_stage = 'UNKNOWN'",
			"UPDATE source_product_projection_lane SET activated_rollout_revision = 0",
			"UPDATE source_product_projection_lane SET installed_at_ms = 2, updated_at_ms = 1",
			"UPDATE source_product_projection_lane SET status = 'RETIRED', retention_required = 0, " +
				"capture_admission_cutoff_ordinal = contiguous_admission_ordinal + 1, " +
				"terminal_disposition = 'CONTAINED_AFTER_DRAIN', terminal_at_ms = 1, updated_at_ms = 1",
		)
		mutations.forEachIndexed { index, mutation ->
			if (index > 0) {
				database.close()
				setUp()
			}
			persist(listOf(buildGroup(220 + index, 1, setOf(0))))
			database.openHelper.writableDatabase.execSQL(mutation)
			assertRecentWriterFailure()
		}
	}

	@Test
	fun `fully drained retired lane remains valid`() = runTest {
		val group = buildGroup(230, 1, setOf(0))
		persist(listOf(group))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_product_projection_lane SET status = 'RETIRED', retention_required = 0, " +
				"capture_admission_cutoff_ordinal = contiguous_admission_ordinal, " +
				"terminal_disposition = 'CONTAINED_AFTER_DRAIN', terminal_at_ms = 1, updated_at_ms = 1",
		)

		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		entry.state shouldBe WifiHistoryProductState.READY
	}

	private fun repository(authority: () -> Boolean) = DefaultWifiHistoryRepository(
		database, SourceProductLaneExecutionAuthority { authority() }, UnconfinedTestDispatcher(),
	)

	private suspend fun assertRecentFactFailure() {
		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		entry.state shouldBe WifiHistoryProductState.FAILED
		entry.causes shouldBe setOf(WifiHistoryCause.FACT_INTEGRITY_FAILED)
		entry.observations shouldBe emptyList()
	}

	private suspend fun assertRecentWriterFailure() {
		val entry = (repository { true }.recent(1) as WifiHistoryPage.Available).entries.single()
		entry.state shouldBe WifiHistoryProductState.FAILED
		entry.causes shouldBe setOf(WifiHistoryCause.WRITER_PROVENANCE_INVALID)
		entry.observations shouldBe emptyList()
	}

	private suspend fun persist(
		groups: List<Group>,
		evidence: SourceEvidenceState = SourceEvidenceState(collectedDataEpoch = 0L),
	) {
		val runs = groups.flatMap(Group::runs)
		val sessionDao = database.sourceSessionDao()
		val policyDao = database.sourcePolicyDao()
		val planDao = database.sourcePlanStateDao()
		val brokerDao = database.sourceBrokerDao()
		val factDao = database.wifiCapturedFactDao()
		database.sourceEvidenceStateDao().ensure(evidence)
		groups.map(Group::session).forEach { sessionDao.insertSession(it) }
		database.sessionSegmentDao().insert(runs.map(BuiltRun::segment))
		runs.sortedBy { it.run.startedAtMs }.forEach { sessionDao.insertServiceRun(it.run) }
		runs.sortedBy { it.manifest.manifestRevision }.forEach { sessionDao.insertManifest(it.manifest) }
		sessionDao.insertManifestSources(runs.map(BuiltRun::source))
		policyDao.insertPolicies(runs.map(BuiltRun::policy).sortedBy(SourcePolicyEntity::policyRevision))
		policyDao.insertConsentEpochs(runs.map(BuiltRun::consent).sortedBy(SourceConsentEpochEntity::epoch))
		runs.map(BuiltRun::planHeader).sortedBy(AcquisitionPlanRevisionEntity::revision)
			.forEach { planDao.insertRevision(it) }
		planDao.insertDesiredPlans(runs.map(BuiltRun::desiredPlan).sortedBy(SourceDesiredPlanEntity::revision))
		brokerDao.insertDemands(runs.flatMap(BuiltRun::demands))
		runs.mapNotNull(BuiltRun::provider).forEach { brokerDao.insertRegistration(it) }
		brokerDao.insertAuthorizations(runs.flatMap(BuiltRun::authorizations))
		sessionDao.insertLifecycleActions(runs.flatMap(BuiltRun::actions))
		database.sourceProjectionStateDao().installProductLane(lane(runs.flatMap(BuiltRun::facts)
			.maxOfOrNull(WifiCapturedFactRevisionEntity::sourceAdmissionOrdinal) ?: 1L))
		runs.mapNotNull(BuiltRun::completeness).forEach { sessionDao.saveCompleteness(it) }
		runs.flatMap(BuiltRun::admissions).forEach { database.sourceEventWalDao().insertIgnoringDuplicate(it) }
		runs.flatMap(BuiltRun::facts).sortedWith(compareBy(
			WifiCapturedFactRevisionEntity::logicalFactId,
			WifiCapturedFactRevisionEntity::semanticRevision,
		)).forEach { factDao.insertRevision(it) }
		runs.flatMap(BuiltRun::cursors).forEach { factDao.insertCursor(it) }
	}

	private fun buildGroup(
		groupIndex: Int,
		runCount: Int,
		factRunIndexes: Set<Int>,
		partialResult: Boolean = false,
		active: Boolean = false,
		includeReuse: Boolean = false,
	): Group {
		require(groupIndex > 0 && runCount > 0)
		val logicalId = "logical-wifi-$groupIndex"
		val specs = (0 until runCount).map { local ->
			val global = groupIndex * 1_000L + local + 1L
			RunSpec(
				manifestRevision = local + 1L,
				globalRevision = global,
				runId = "wifi-run-$groupIndex-${local + 1}",
				startWallMs = WALL_BASE_MS + groupIndex * 1_000_000L + local * RUN_DURATION_MS,
				startElapsedNanos = 1_000_000_000L + groupIndex * 100_000_000_000L +
					local * RUN_DURATION_NANOS,
				hasFact = local in factRunIndexes,
			)
		}
		val sessionEndElapsed = specs.last().startElapsedNanos + RUN_DURATION_NANOS
		val sessionEndWall = specs.last().startWallMs + RUN_DURATION_MS
		val built = specs.map { buildRun(logicalId, it, partialResult, active, includeReuse) }
		val finalAdmission = built.flatMap(BuiltRun::facts)
			.maxOfOrNull(WifiCapturedFactRevisionEntity::sourceAdmissionOrdinal) ?: 1L
		val last = built.last()
		return Group(
			LogicalTrackingSessionEntity(
				logicalTrackingId = logicalId,
				state = if (active) "ACTIVE" else "FINALIZED",
				lifecycleRevision = 2L,
				desiredPlanRevision = last.run.desiredPlanRevision,
				rolloutRevision = ROLLOUT_REVISION,
				startOrigin = START_ORIGIN,
				clockDomainId = BOOT_ID,
				startedAtMs = built.first().run.startedAtMs,
				startedElapsedNanos = built.first().run.startedElapsedNanos,
				cutoffAtMs = sessionEndWall.takeUnless { active },
				cutoffElapsedNanos = sessionEndElapsed.takeUnless { active },
				completedAtMs = sessionEndWall.takeUnless { active },
				finalAdmissionOrdinal = finalAdmission.takeUnless { active },
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = specs.last().manifestRevision,
				currentIntentRevision = 1L,
				currentServiceRunId = last.run.serviceRunId.takeIf { active },
				lifecycleLeaseGeneration = LEASE_GENERATION,
				lifecycleBootId = BOOT_ID,
				automationEpoch = null,
			),
			built,
		)
	}

	private fun transformFacts(
		group: Group,
		transform: (WifiCapturedFactRevisionEntity) -> WifiCapturedFactRevisionEntity,
	): Group = group.copy(runs = group.runs.map { built ->
		val facts = built.facts.map { original ->
			val changed = transform(original).copy(effectChecksum = ZERO_SHA)
			changed.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(changed))
		}
		val current = facts.groupBy(WifiCapturedFactRevisionEntity::logicalFactId)
			.values.map { it.maxBy(WifiCapturedFactRevisionEntity::semanticRevision) }
		built.copy(facts = facts, cursors = current.map(::cursor))
	})

	private fun shareRegistrationAcrossReplacement(group: Group): Group {
		require(group.runs.size == 2)
		val first = group.runs.first()
		val later = group.runs.last()
		val firstProvider = requireNotNull(first.provider)
		val laterProvider = requireNotNull(later.provider)
		val sharedProvider = firstProvider.copy(
			retiredAtMs = laterProvider.retiredAtMs,
			retiredElapsedRealtimeNanos = laterProvider.retiredElapsedRealtimeNanos,
		)
		val authorization = SourceBrokerAuthorization.rows(
			WIFI_SOURCE,
			sharedProvider.registrationGeneration,
			1L,
			later.demands,
			BOOT_ID,
			later.manifest.effectiveElapsedRealtimeNanos,
			later.manifest.effectiveWallTimeMs,
		)
		val deny = SourceBrokerAuthorization.rows(
			WIFI_SOURCE,
			sharedProvider.registrationGeneration,
			2L,
			emptyList(),
			BOOT_ID,
			requireNotNull(laterProvider.retiredElapsedRealtimeNanos),
			requireNotNull(laterProvider.retiredAtMs),
		)
		val fingerprint = authorization.first().authorizationFingerprint
		val laterWithSharedRegistration = transformFacts(
			group.copy(runs = listOf(later)),
		) { fact ->
			fact.copy(
				sourceInstanceId = sharedProvider.sourceInstanceId,
				registrationGeneration = sharedProvider.registrationGeneration,
				authorizationFingerprint = fingerprint,
				providerAcceptanceStartNanos = requireNotNull(
					sharedProvider.acceptedElapsedRealtimeNanos,
				),
			)
		}.runs.single().copy(
			provider = null,
			authorizations = authorization + deny,
			actions = later.actions.map { action -> action.copy(
				sourceInstanceId = sharedProvider.sourceInstanceId,
				registrationGeneration = sharedProvider.registrationGeneration,
			) },
			completeness = later.completeness?.copy(
				sourceInstanceId = sharedProvider.sourceInstanceId,
				registrationGeneration = sharedProvider.registrationGeneration,
			),
		)
		return group.copy(runs = listOf(
			first.copy(provider = sharedProvider, demands = emptyList(), authorizations = emptyList()),
			laterWithSharedRegistration,
		))
	}

	@Suppress("LongMethod")
	private fun buildRun(
		logicalId: String,
		spec: RunSpec,
		partialResult: Boolean,
		active: Boolean,
		includeReuse: Boolean,
	): BuiltRun {
		val runEndElapsed = spec.startElapsedNanos + RUN_DURATION_NANOS
		val runEndWall = spec.startWallMs + RUN_DURATION_MS
		val segment = SessionSegment(
			id = spec.globalRevision,
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
			state = if (active) "ACTIVE" else "FINALIZED",
			desiredPlanRevision = spec.globalRevision,
			rolloutRevision = ROLLOUT_REVISION,
			foregroundCapabilityFlags = 0L,
			startedAtMs = spec.startWallMs,
			startedElapsedNanos = spec.startElapsedNanos,
			completedAtMs = runEndWall.takeUnless { active },
			completionReason = "USER_STOP".takeUnless { active },
			bootId = BOOT_ID,
			leaseGeneration = LEASE_GENERATION,
			startOrigin = START_ORIGIN,
			desiredForegroundCapabilityFlags = 0L,
			appliedForegroundCapabilityFlags = 0L,
			runtimeAcknowledgement = if (active) "START_ACCEPTED" else "STOP_ACCEPTED",
			runtimeFailureCode = null,
			runRevision = 2L,
			startDeliveryToken = "wifi-start-${spec.globalRevision}",
			startCommandGeneration = 1L,
			preparedManifestRevision = spec.manifestRevision,
			preparedIntentRevision = 1L,
			androidDeliveryState = "FOREGROUND_ACCEPTED",
			androidDeliveryUpdatedAtMs = spec.startWallMs,
			startIsUserInitiated = true,
			startIsAmbient = false,
			sessionSegmentId = segment.id,
			presentationAcknowledgement = if (active) SourceServiceRunEntity.PRESENTATION_PENDING else
				SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = runEndWall.takeUnless { active },
		)
		val source = SessionManifestSourceEntity(
			logicalTrackingId = logicalId,
			manifestRevision = spec.manifestRevision,
			sourceKind = WIFI_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch = spec.globalRevision,
			persistenceEligible = true,
			qosCode = QOS_CODE,
			outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_WIFI,
			writerOwner = SourceDestinationOwnerEntity.OWNER_WIFI_SESSION_FACTS,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			writerProjectionId = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION,
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
			sourceKind = WIFI_SOURCE,
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
			sourceKind = WIFI_SOURCE,
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
		val plan = requireNotNull(WifiHistoryPlanIntegrity.decode(desiredPlan))
		val planHeader = AcquisitionPlanRevisionEntity(spec.globalRevision,
			"wifi-plan-${spec.globalRevision}", spec.startWallMs, "EFFECTIVE", spec.globalRevision)
		val demand = demand(logicalId, spec, runEndElapsed, runEndWall, active)
		val authorization = SourceBrokerAuthorization.rows(WIFI_SOURCE, spec.globalRevision, 1L,
			listOf(demand), BOOT_ID, spec.startElapsedNanos, spec.startWallMs)
		val deny = SourceBrokerAuthorization.rows(WIFI_SOURCE, spec.globalRevision, 2L, emptyList(),
			BOOT_ID, runEndElapsed, runEndWall)
		val provider = ProviderRegistrationGenerationEntity(
			WIFI_SOURCE, spec.globalRevision, "wifi-instance-${spec.globalRevision}",
			"source-broker:$WIFI_SOURCE", BOOT_ID, plan.physicalFingerprint, 0L,
			ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND, "process-${spec.globalRevision}",
			if (active) ProviderRegistrationGenerationEntity.STATUS_ACTIVE else
				ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			spec.startWallMs, spec.startElapsedNanos, spec.startWallMs, spec.startElapsedNanos,
			runEndWall.takeUnless { active }, runEndElapsed.takeUnless { active }, null, 1L,
		)
		val action = startAction(logicalId, spec)
		val authorizationHistory = if (active) authorization else authorization + deny
		if (!spec.hasFact) return BuiltRun(
			segment, run, manifest, source, policy, consent, planHeader, desiredPlan, listOf(demand), provider,
			authorizationHistory, listOf(action), if (active) listOf(admission(logicalId, spec, plan,
				authorization.first().authorizationFingerprint)) else emptyList(), emptyList(), emptyList(),
			completeness(logicalId, spec, null).takeUnless { active },
		)
		val first = fact(logicalId, spec, segment.id, plan, authorization.first().authorizationFingerprint,
			partialResult, 1L, Long.MAX_VALUE, admissionOffset = 1L)
		val settled = settle(first, runEndElapsed)
		val dependent = if (includeReuse) coverageFact(
			fact(logicalId, spec, segment.id, plan, authorization.first().authorizationFingerprint,
				partialResult, 1L, runEndElapsed, admissionOffset = 2L),
			settled,
		) else null
		val facts = listOfNotNull(first, settled, dependent)
		val cursors = listOf(cursor(settled)) + listOfNotNull(dependent?.let(::cursor))
		val lastFact = dependent ?: settled
		return BuiltRun(segment, run, manifest, source, policy, consent, planHeader, desiredPlan,
			listOf(demand), provider, authorizationHistory, listOf(action), emptyList(), facts,
			cursors, completeness(logicalId, spec, lastFact.sourceAdmissionOrdinal))
	}

	@Suppress("LongMethod")
	private fun fact(
		logicalId: String,
		spec: RunSpec,
		segmentId: Long,
		plan: WifiPlanEvidence,
		authorizationFingerprint: String,
		partial: Boolean,
		semanticRevision: Long,
		temporalEnd: Long,
		admissionOffset: Long,
	): WifiCapturedFactRevisionEntity {
		val admission = spec.globalRevision * 10L + admissionOffset
		val delivery = sha256("wifi-delivery-$admission")
		val logicalFactId = WifiCapturedFactRevisionIntegrity.logicalFactId(delivery, logicalId,
			spec.runId, segmentId, spec.manifestRevision, 0L, 0L)
		val unsigned = WifiCapturedFactRevisionEntity(
			writerProjectionId = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			logicalFactId = logicalFactId,
			semanticRevision = semanticRevision,
			supersedesSemanticRevision = (semanticRevision - 1L).takeIf { it > 0L },
			mutationId = WifiCapturedFactRevisionIntegrity.mutationId(logicalFactId, semanticRevision),
			factKind = WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE,
			aggregateOwnerLogicalFactId = null,
			aggregateOwnerSemanticRevision = null,
			aggregateOwnerCursorRevision = null,
			logicalTrackingId = logicalId,
			serviceRunId = spec.runId,
			sessionSegmentId = segmentId,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			capturedSourceCodes = WIFI_SOURCE.toString(),
			controlSourceCodes = "",
			sourceEventId = "wifi-event-$admission",
			sourceAdmissionOrdinal = admission,
			walIntegrityIdentity = sha256("wifi-wal-$admission"),
			payloadChecksum = sha256("wifi-payload-$admission"),
			sourceDeliveryIdentity = delivery,
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			sourceSequence = admission,
			planAttribution = WifiCapturedFactRevisionEntity.PLAN_ATTRIBUTION_CAPTURED_REGISTRATION,
			sourceInstanceId = "wifi-instance-${spec.globalRevision}",
			registrationGeneration = spec.globalRevision,
			configurationRevision = spec.globalRevision,
			physicalConfigurationFingerprint = plan.physicalFingerprint,
			authorizationRevision = 1L,
			authorizationFingerprint = authorizationFingerprint,
			purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			sourcePolicyRevision = spec.globalRevision,
			captureConsentEpoch = spec.globalRevision,
			manifestRevision = spec.manifestRevision,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			collectedDataEpoch = 0L,
			scopeDeletionGeneration = 0L,
			clockDomainId = BOOT_ID,
			storedZoneId = ZONE_ID,
			planPayloadVersion = plan.payloadVersion,
			planPayloadChecksum = plan.payloadChecksum,
			maximumObservationAgeNanos = plan.maximumAgeMs * 1_000_000L,
			resultContract = "ANDROID_SCAN_RESULTS_V1",
			registrationAppliedAtNanos = spec.startElapsedNanos,
			providerAcceptanceStartNanos = spec.startElapsedNanos,
			providerAcceptanceEndNanos = temporalEnd,
			authorizationEffectStartNanos = spec.startElapsedNanos,
			authorizationEffectEndNanos = temporalEnd,
			sessionRunEffectStartNanos = spec.startElapsedNanos,
			sessionRunEffectEndNanos = temporalEnd,
			observedIntervalStartNanos = spec.startElapsedNanos + 100_000_000L,
			observedElapsedNanos = spec.startElapsedNanos + 200_000_000L,
			receivedElapsedNanos = spec.startElapsedNanos + 210_000_000L,
			coverageIntervalStartNanos = spec.startElapsedNanos + 100_000_000L,
			coverageIntervalEndNanos = spec.startElapsedNanos + 200_000_000L,
			observedWallTimeMs = spec.startWallMs + 200L,
			wallTimeUncertaintyMs = 1L,
			acquiredAtMs = spec.startWallMs + 200L,
			qualityFlags = 0L,
			qualityConfidence = 1f,
			availability = WifiCapturedFactRevisionEntity.AVAILABILITY_AVAILABLE,
			submittedResultCount = if (partial) 3 else 2,
			acceptedResultCount = 2,
			staleResultCount = if (partial) 1 else 0,
			clockUnverifiableResultCount = 0,
			malformedResultCount = 0,
			coverageCompleteness = if (partial) WifiCapturedFactRevisionEntity.COVERAGE_PARTIAL else
				WifiCapturedFactRevisionEntity.COVERAGE_COMPLETE,
			observationCount = 2,
			twoPointFourGhzCount = 1,
			fiveGhzCount = 1,
			sixGhzCount = 0,
			otherBandCount = 0,
			strongestSignalDbm = -40,
			weakestSignalDbm = -60,
			signalSumDbm = -100L,
			effectChecksum = ZERO_SHA,
			appliedAtMs = spec.startWallMs + 200L,
		)
		return unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
	}

	private fun coverageFact(
		base: WifiCapturedFactRevisionEntity,
		owner: WifiCapturedFactRevisionEntity,
	): WifiCapturedFactRevisionEntity {
		val unsigned = base.copy(
			factKind = WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY,
			aggregateOwnerLogicalFactId = owner.logicalFactId,
			aggregateOwnerSemanticRevision = owner.semanticRevision,
			aggregateOwnerCursorRevision = owner.semanticRevision,
			observationCount = null,
			twoPointFourGhzCount = null,
			fiveGhzCount = null,
			sixGhzCount = null,
			otherBandCount = null,
			strongestSignalDbm = null,
			weakestSignalDbm = null,
			signalSumDbm = null,
			effectChecksum = ZERO_SHA,
		)
		return unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
	}

	private fun settle(
		first: WifiCapturedFactRevisionEntity,
		runEndElapsed: Long,
	): WifiCapturedFactRevisionEntity {
		val unsigned = first.copy(
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			mutationId = WifiCapturedFactRevisionIntegrity.mutationId(first.logicalFactId, 2L),
			providerAcceptanceEndNanos = runEndElapsed,
			authorizationEffectEndNanos = runEndElapsed,
			sessionRunEffectEndNanos = runEndElapsed,
			effectChecksum = ZERO_SHA,
		)
		return unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
	}

	private fun cursor(fact: WifiCapturedFactRevisionEntity) = WifiCapturedFactCursorEntity(
		fact.writerProjectionId, fact.writerProjectionVersion, fact.logicalFactId, fact.logicalTrackingId,
		fact.serviceRunId, fact.sessionSegmentId, fact.writerOwnerGeneration, fact.collectedDataEpoch,
		fact.scopeDeletionGeneration, fact.semanticRevision, fact.mutationId, fact.effectChecksum,
		fact.sourceAdmissionOrdinal, fact.semanticRevision, fact.appliedAtMs,
	)

	private fun startAction(logicalId: String, spec: RunSpec) = LifecycleDesiredActionEntity(
		actionId = "wifi-start-action-${spec.globalRevision}",
		logicalTrackingId = logicalId,
		serviceRunId = spec.runId,
		manifestRevision = spec.manifestRevision,
		actionRevision = spec.globalRevision,
		actionFamily = "SOURCE_RUNTIME",
		sourceKind = WIFI_SOURCE,
		desiredState = "STARTED",
		desiredPlanRevision = spec.globalRevision,
		sourcePolicyRevision = spec.globalRevision,
		consentEpoch = spec.globalRevision,
		startOrigin = START_ORIGIN,
		bootId = BOOT_ID,
		leaseGeneration = LEASE_GENERATION,
		requestedAtMs = spec.startWallMs,
		requestedElapsedRealtimeNanos = spec.startElapsedNanos,
		status = "START_ACCEPTED",
		attemptCount = 1,
		acknowledgedAtMs = spec.startWallMs + 50L,
		acknowledgedElapsedRealtimeNanos = spec.startElapsedNanos + 50_000_000L,
		failureCode = null,
		retryTrigger = null,
		sourceInstanceId = "wifi-instance-${spec.globalRevision}",
		registrationGeneration = spec.globalRevision,
	)

	private fun admission(
		logicalId: String,
		spec: RunSpec,
		plan: WifiPlanEvidence,
		authorizationFingerprint: String,
	): SourceEventWalEntity {
		val payload = byteArrayOf(1, 2, 3)
		val unsigned = SourceEventWalEntity(
			eventId = "wifi-pending-event-${spec.globalRevision}",
			providerDedupKey = null,
			deliveryIdentity = sha256("wifi-pending-delivery-${spec.globalRevision}"),
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = logicalId,
			serviceRunId = spec.runId,
			sourceKind = WIFI_SOURCE,
			sourceInstanceId = "wifi-instance-${spec.globalRevision}",
			registrationGeneration = spec.globalRevision,
			physicalConfigurationFingerprint = plan.physicalFingerprint,
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = authorizationFingerprint,
			sourceSequence = spec.globalRevision,
			configRevision = spec.globalRevision,
			planAttribution = 0,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = spec.startElapsedNanos + 200_000_000L,
			observedIntervalStartNanos = spec.startElapsedNanos + 100_000_000L,
			receivedElapsedNanos = spec.startElapsedNanos + 210_000_000L,
			wallTimeMs = spec.startWallMs + 200L,
			wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = 0L,
			activityAutomationEpoch = null,
			sourcePolicyRevision = spec.globalRevision,
			captureConsentEpoch = spec.globalRevision,
			sessionManifestRevision = spec.manifestRevision,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			acquiredAtMs = spec.startWallMs + 200L,
			qualityFlags = 0L,
			qualityConfidence = 1f,
			payloadVersion = 2,
			payload = payload,
			payloadChecksum = sha256(payload),
			createdAtMs = spec.startWallMs + 201L,
		)
		return unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
	}

	private fun demand(
		logicalId: String,
		spec: RunSpec,
		runEndElapsed: Long,
		runEndWall: Long,
		active: Boolean,
	) =
		SourceDemandEntity(
			demandId = "wifi-demand-${spec.globalRevision}",
			consumerId = "session:${spec.runId}",
			sourceKind = WIFI_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = logicalId,
			serviceRunId = spec.runId,
			manifestRevision = spec.manifestRevision,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			sourcePolicyRevision = spec.globalRevision,
			consentEpoch = spec.globalRevision,
			persistenceEligible = true,
			qosCode = QOS_CODE,
			minimumAcquisitionSpec = "wifi:v1:required=BROADCAST_CALLBACK",
			adaptiveReductionAllowed = false,
			maximumAgeMs = 1_000L,
			desiredLatencyMs = 1_000L,
			requestedDeliveryLatencyMs = null,
			requestedBootId = BOOT_ID,
			requestedElapsedRealtimeNanos = spec.startElapsedNanos,
			requestedAtMs = spec.startWallMs,
			status = if (active) SourceDemandEntity.STATUS_ACTIVE else SourceDemandEntity.STATUS_RETIRED,
			retireBootId = BOOT_ID.takeUnless { active },
			retireElapsedRealtimeNanos = runEndElapsed.takeUnless { active },
			retiredAtMs = runEndWall.takeUnless { active },
		)

	private fun completeness(logicalId: String, spec: RunSpec, lastOrdinal: Long?) =
		SourceSessionCompletenessEntity(
			logicalTrackingId = logicalId,
			serviceRunId = spec.runId,
			sourceKind = WIFI_SOURCE,
			sourceInstanceId = "wifi-instance-${spec.globalRevision}",
			registrationGeneration = spec.globalRevision,
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
				output.writeUTF("WIFI")
				output.writeLong(revision)
				output.writeUTF("BROADCAST_DRIVEN")
				output.writeLong(60_000L)
				output.writeLong(1_000L)
				output.writeLong(5_000L)
				output.writeLong(1_000L)
				output.writeLong(60_000L)
				output.writeDouble(2.0)
			}
			bytes.toByteArray()
		}
		return SourceDesiredPlanEntity(revision, WIFI_SOURCE, 1, payload, sha256(payload))
	}

	private fun lane(throughOrdinal: Long) = SourceProductProjectionLaneEntity(
		sourceKind = WIFI_SOURCE,
		bindingGeneration = SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION,
		projectionId = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
		projectionVersion = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
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

	private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

	private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(value).joinToString("") { "%02x".format(it) }

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
		val authorizations: List<com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity>,
		val actions: List<LifecycleDesiredActionEntity>,
		val admissions: List<SourceEventWalEntity>,
		val facts: List<WifiCapturedFactRevisionEntity>,
		val cursors: List<WifiCapturedFactCursorEntity>,
		val completeness: SourceSessionCompletenessEntity?,
	)

	private data class RunSpec(
		val manifestRevision: Long,
		val globalRevision: Long,
		val runId: String,
		val startWallMs: Long,
		val startElapsedNanos: Long,
		val hasFact: Boolean,
	)

	private companion object {
		const val WIFI_SOURCE = SourceDestinationOwnerEntity.SOURCE_WIFI
		const val BOOT_ID = "boot-wifi-history"
		const val ZONE_ID = "UTC"
		const val START_ORIGIN = "MANUAL_FOREGROUND_START"
		const val QOS_CODE = 2
		const val LEASE_GENERATION = 1L
		const val ROLLOUT_REVISION = 1L
		const val RUN_DURATION_NANOS = 800_000_000L
		const val RUN_DURATION_MS = 800L
		const val WALL_BASE_MS = 1_700_000_000_000L
		const val ZERO_SHA = "0000000000000000000000000000000000000000000000000000000000000000"
	}
}
