package com.adsamcik.tracker.app.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.app.di.TrackingInfrastructureIntegrationEntryPoint
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SessionHistory
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryListState
import com.adsamcik.tracker.tracker.api.ManualTrackingCaptureReachability
import com.adsamcik.tracker.tracker.api.ManualTrackingStartReadiness
import com.adsamcik.tracker.tracker.api.ManualTrackingStartResult
import com.adsamcik.tracker.tracker.api.AutomaticControlRecoveryResult
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackingCaptureSource
import com.adsamcik.tracker.tracker.api.TrackingStopQuiescenceResult
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import com.adsamcik.tracker.tracker.source.coordinator.SessionMode
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.coordinator.StepsWriterTransitionResult
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Operator-run gate intended for one identified physical TYPE_STEP_COUNTER device and a disposable
 * debug-app install. The capability checks cannot distinguish physical hardware from a sufficiently
 * instrumented virtual device, so the operator must retain the logged device identity.
 *
 * Before running, clear debug app data and capture `dumpsys sensorservice`. While this test waits
 * for [LOG_LISTENER_ACTIVE], walk far enough to produce a positive post-baseline counter delta and
 * capture sensorservice again. After [LOG_LISTENER_RETIRED], capture it a third time. The test
 * proves the app-owned durable registration retired; the before/during/after platform evidence is
 * still required to prove that Android removed the physical listener.
 *
 * The history assertions observe production repository read models; this test does not launch or
 * inspect a rendered Compose surface and cannot close the UI-device evidence requirement.
 *
 * This gate never inserts WAL, fact, manifest, run, demand, authorization, or history rows. It does
 * not call a projection drain or recovery hook. Its only setup mutations are the user-equivalent
 * source settings, production automatic-control reconciliation, and the explicit unreleased Steps
 * writer activation under test. Those mutations persist in this disposable install, so clear debug
 * app data again after every outcome. An environment without an exposed step counter is a typed
 * failure, not provider evidence.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@Suppress("LargeClass")
class ManualStepsOnlyDeviceGateTest {
	@get:Rule
	val activityRecognitionPermission: GrantPermissionRule = GrantPermissionRule.grant(
		Manifest.permission.ACTIVITY_RECOGNITION,
	)

	private lateinit var context: Application
	private lateinit var database: AppDatabase
	private lateinit var entryPoint: TrackingInfrastructureIntegrationEntryPoint
	private lateinit var ingress: DurableSourceIngress

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.database(context)
		entryPoint = EntryPointAccessors.fromApplication(
			context,
			TrackingInfrastructureIntegrationEntryPoint::class.java,
		)
		ingress = entryPoint.durableSourceIngress()
	}

	@Test
	@Suppress("CyclomaticComplexMethod", "LongMethod")
	fun manualStepsOnlyReachesRecordingMaterializedAndQueryableThenRetires() = runBlocking {
		requireStepCounterCapabilityAndPermission()
		entryPoint.collectedDataLifecycleStore().snapshot().also { lifecycle ->
			gate(lifecycle.epoch >= 0L, "STEPS_GATE_INVALID_COLLECTED_DATA_EPOCH")
		}
		val startup = context.trackingStartupGate.reconcile(retryFailedStorage = true)
		gate(startup is TrackingStartupResult.Ready, "STEPS_GATE_STARTUP_NOT_READY:$startup")

		configureExactStepsOnlyPolicy()
		reconcileDisabledAutomaticControl()
		requireDisposablePrecondition()
		val canonicalRolloutRevision = activateCandidateStepsWriter()
		assertManualStepsReachability(canonicalRolloutRevision)

		val brokerBaseline = brokerAuditBaseline()
		val initialWalHighWater = database.sourceEventWalDao().maximumAdmissionOrdinal() ?: 0L
		var startMayHaveBeenEnqueued = false
		var stopped = false
		var capturedActive: ActiveScenario? = null
		val outcome = runCatching {
			startMayHaveBeenEnqueued = true
			val start = TrackerServiceApi.requestManualTrackingStart(context)
			gate(start == ManualTrackingStartResult.ENQUEUED, "STEPS_GATE_START_NOT_ENQUEUED:$start")

			val active = awaitRoom(
				code = "STEPS_GATE_ACTIVE_SESSION_TIMEOUT",
				timeoutMs = START_TIMEOUT_MS,
				tables = ACTIVE_SESSION_TABLES,
			) { activeScenarioOrNull() }
			capturedActive = active
			assertExactActiveScenario(active, canonicalRolloutRevision)
			Log.i(TAG, "$LOG_LISTENER_ACTIVE run=${active.run.serviceRunId} " +
				"registration=${active.registration.registrationGeneration}")

			val recording = awaitRoom(
				code = "STEPS_GATE_POSITIVE_POST_BASELINE_TIMEOUT",
				timeoutMs = RECORDING_TIMEOUT_MS,
				tables = arrayOf(WAL_TABLE),
			) {
				findPositivePostBaseline(
					afterOrdinal = initialWalHighWater,
					logicalTrackingId = active.session.logicalTrackingId,
					serviceRunId = active.run.serviceRunId,
				)
			}
			assertRecordingEvidence(recording, active, initialWalHighWater)
			Log.i(TAG, "STEPS_GATE_RECORDING admission=${recording.positive.admissionOrdinal}")

			val fact = awaitRoom(
				code = "STEPS_GATE_MATERIALIZATION_TIMEOUT",
				timeoutMs = MATERIALIZATION_TIMEOUT_MS,
				tables = arrayOf(FACT_TABLE, PRODUCT_LANE_TABLE, PROJECTION_FAILURE_TABLE),
			) {
				database.stepFactRevisionDao().writerAdmission(
					SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
					SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
					recording.positive.admissionOrdinal,
				)
			}
			assertMaterializedFact(fact, recording, active)
			Log.i(TAG, "STEPS_GATE_MATERIALIZED admission=${recording.positive.admissionOrdinal}")

			val activeHistory = awaitGate("STEPS_GATE_ACTIVE_HISTORY_TIMEOUT", HISTORY_TIMEOUT_MS) {
				entryPoint.trackingHistoryRepository()
					.observeSession(active.segmentId)
					.filterIsInstance<SessionHistoryQuery.Found>()
					.map { found -> found.history }
					.first { history ->
						history.steps.count?.let { it >= recording.positivePayload.deltaCount } == true &&
							history.steps.evidence == HistoryEvidence.RECORDED
					}
			}
			assertTruthfulStepsHistory(activeHistory, requireComplete = false)

			val stop = awaitGate("STEPS_GATE_STOP_TIMEOUT", STOP_TIMEOUT_MS) {
				TrackerServiceApi.stopServiceAndAwaitQuiescence(context)
			}
			gate(stop == TrackingStopQuiescenceResult.HANDLED, "STEPS_GATE_STOP_NOT_HANDLED:$stop")

			val terminal = awaitRoom(
				code = "STEPS_GATE_TERMINAL_SETTLEMENT_TIMEOUT",
				timeoutMs = STOP_TIMEOUT_MS,
				tables = TERMINAL_TABLES,
			) { terminalScenarioOrNull(active) }
			assertExactTerminalScenario(terminal, active)
			assertCompleteDurableAudit(brokerBaseline, initialWalHighWater, active)
			stopped = true
			Log.i(TAG, "$LOG_LISTENER_RETIRED run=${active.run.serviceRunId} " +
				"registration=${active.registration.registrationGeneration}")

			val queryable = awaitGate("STEPS_GATE_QUERYABLE_HISTORY_TIMEOUT", HISTORY_TIMEOUT_MS) {
				entryPoint.trackingHistoryRepository()
					.observeSession(active.segmentId)
					.filterIsInstance<SessionHistoryQuery.Found>()
					.map { found -> found.history }
					.first { history ->
						history.steps.productState == HistoryProductState.READY &&
							history.steps.coverage == StepsHistoryCoverage.COMPLETE &&
							history.steps.count?.let { it > 0L } == true
					}
			}
			assertTruthfulStepsHistory(queryable, requireComplete = true)
			val recent = awaitGate("STEPS_GATE_RECENT_LIST_TIMEOUT", HISTORY_TIMEOUT_MS) {
				entryPoint.trackingHistoryRepository().observeRecentStepsOnlyEntries(10).first { rows ->
					rows.size == 1 && rows.single().state == StepsOnlyHistoryListState.AVAILABLE
				}
			}
			gate(recent.size == 1, "STEPS_GATE_RECENT_LIST_NOT_EXACTLY_ONE")
			Log.i(TAG, "STEPS_GATE_QUERYABLE segment=${active.segmentId}")
		}
		val cleanupFailure = if (startMayHaveBeenEnqueued && !stopped) {
			runCatching {
				withContext(NonCancellable) {
					cleanupAfterFailure(capturedActive)
				}
			}.exceptionOrNull()
		} else {
			null
		}
		if (cleanupFailure != null) {
			val primaryFailure = outcome.exceptionOrNull()
			if (primaryFailure != null) {
				primaryFailure.addSuppressed(cleanupFailure)
			} else {
				throw cleanupFailure
			}
		}
		outcome.getOrThrow()
	}

	private fun requireStepCounterCapabilityAndPermission() {
		Log.i(
			TAG,
			"STEPS_GATE_DEVICE manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} " +
				"device=${Build.DEVICE} fingerprint=${Build.FINGERPRINT}",
		)
		gate(
			context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER),
			"STEPS_GATE_STEP_COUNTER_FEATURE_MISSING",
		)
		val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
		gate(
			sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null,
			"STEPS_GATE_STEP_COUNTER_SENSOR_MISSING",
		)
		gate(
			Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
				ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
				PackageManager.PERMISSION_GRANTED,
			"STEPS_GATE_ACTIVITY_RECOGNITION_PERMISSION_MISSING",
		)
	}

	private suspend fun configureExactStepsOnlyPolicy() {
		entryPoint.trackingParamsRepository().update {
			copy(
				locationEnabled = false,
				activityEnabled = false,
				stepsEnabled = true,
				wifiEnabled = false,
				cellEnabled = false,
				barometerEnabled = false,
				autoTrackingMode = 0,
				transitionDetectionEnabled = false,
				sourceCollectionSettings = SourceCollectionSettings(
					location = SourceCollectionFrequency.OFF,
					activity = SourceCollectionFrequency.OFF,
					steps = SourceCollectionFrequency.BALANCED,
					pressure = SourceCollectionFrequency.OFF,
					wifi = SourceCollectionFrequency.OFF,
					cell = SourceCollectionFrequency.OFF,
				),
			)
		}
		val settings = entryPoint.trackingParamsRepository().data.first()
		gate(settings.sourcePolicyRevision != null, "STEPS_GATE_SOURCE_POLICY_UNAVAILABLE")
		gate(!settings.locationEnabled, "STEPS_GATE_LOCATION_ENABLED")
		gate(!settings.activityEnabled, "STEPS_GATE_ACTIVITY_ENABLED")
		gate(settings.stepsEnabled, "STEPS_GATE_STEPS_DISABLED")
		gate(!settings.barometerEnabled, "STEPS_GATE_PRESSURE_ENABLED")
		gate(!settings.wifiEnabled, "STEPS_GATE_WIFI_ENABLED")
		gate(!settings.cellEnabled, "STEPS_GATE_CELL_ENABLED")
		gate(settings.autoTrackingMode == 0, "STEPS_GATE_AUTOMATIC_TRACKING_ENABLED")
		gate(!settings.transitionDetectionEnabled, "STEPS_GATE_TRANSITION_CONTROL_ENABLED")
		gate(
			settings.sourceCollectionSettings == SourceCollectionSettings(
				location = SourceCollectionFrequency.OFF,
				activity = SourceCollectionFrequency.OFF,
				steps = SourceCollectionFrequency.BALANCED,
				pressure = SourceCollectionFrequency.OFF,
				wifi = SourceCollectionFrequency.OFF,
				cell = SourceCollectionFrequency.OFF,
			),
			"STEPS_GATE_SOURCE_FREQUENCIES_NOT_EXACT",
		)
	}

	private suspend fun reconcileDisabledAutomaticControl() {
		val recovery = BackgroundTrackingApi.reconcileAutomaticControlDemandAfterStartup(context)
		gate(
			recovery == AutomaticControlRecoveryResult.TERMINAL_DISABLED_OR_CONTAINED,
			"STEPS_GATE_AUTOMATIC_CONTROL_NOT_DISABLED:$recovery",
		)
		gate(!BackgroundTrackingApi.isActive, "STEPS_GATE_AUTOMATIC_CONTROL_ACTIVE")
		val snapshot = entryPoint.activityRegistrationArbiter().snapshot()
		gate(!snapshot.active, "STEPS_GATE_ACTIVITY_PROVIDER_ACTIVE")
		gate(snapshot.owners.isEmpty(), "STEPS_GATE_ACTIVITY_OWNER_RETAINED")
		gate(snapshot.continuousRecognitionIntervalSeconds == null, "STEPS_GATE_ACTIVITY_INTERVAL_RETAINED")
		gate(snapshot.transitions.isEmpty(), "STEPS_GATE_ACTIVITY_TRANSITIONS_RETAINED")
		gate(
			queryLong(
				"SELECT COUNT(*) FROM source_demand WHERE source_kind = ${SourceKind.ACTIVITY.stableCode} " +
					"AND status IN ('ACTIVE','RETIRING','BLOCKED')",
			) == 0L,
			"STEPS_GATE_ACTIVITY_DEMAND_NOT_SETTLED",
		)
		gate(
			queryLong(
				"SELECT COUNT(*) FROM provider_registration_generation " +
					"WHERE source_kind = ${SourceKind.ACTIVITY.stableCode} " +
					"AND status IN ('RESERVED','ACTIVE','RETIRING')",
			) == 0L,
			"STEPS_GATE_ACTIVITY_REGISTRATION_NOT_SETTLED",
		)
	}

	private suspend fun requireDisposablePrecondition() {
		val dirtyTables = listOf(
			LOGICAL_SESSION_TABLE,
			SERVICE_RUN_TABLE,
			SESSION_SEGMENT_TABLE,
			WAL_TABLE,
			FACT_TABLE,
			"step_interval",
		)
		dirtyTables.forEach { table ->
			gate(queryLong("SELECT COUNT(*) FROM $table") == 0L, "STEPS_GATE_DIRTY_TABLE:$table")
		}
		gate(
			queryLong("SELECT COUNT(*) FROM source_runtime_state WHERE source_kind = $STEPS_SOURCE") == 0L,
			"STEPS_GATE_DIRTY_STEPS_RUNTIME",
		)
		gate(nonterminalDemandCount() == 0L, "STEPS_GATE_PREEXISTING_DEMAND")
		gate(nonterminalRegistrationCount() == 0L, "STEPS_GATE_PREEXISTING_REGISTRATION")
		val owner = database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)
		gate(owner != null, "STEPS_GATE_DESTINATION_OWNER_MISSING")
		gate(owner?.owner == LEGACY_OWNER, "STEPS_GATE_REQUIRES_FRESH_LEGACY_OWNER")
		gate(
			owner?.ownerGeneration == SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
			"STEPS_GATE_REQUIRES_INITIAL_OWNER_GENERATION",
		)
		gate(
			database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE) == null,
			"STEPS_GATE_PREEXISTING_STEPS_LANE",
		)
	}

	private suspend fun activateCandidateStepsWriter(): Long {
		val rolloutStore = RoomTrackingRolloutStateStore(database, ExecutableSourceLaneCatalog())
		val contained = rolloutStore.load()
		gate(
			!contained.isCaptureReachable(SourceKind.STEPS, CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
			"STEPS_GATE_STEPS_ALREADY_REACHABLE",
		)
		gate(contained.revision < Long.MAX_VALUE, "STEPS_GATE_ROLLOUT_REVISION_EXHAUSTED")
		val shadow = rolloutStore.installInertShadowLane(
			binding = ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS,
			rolloutRevision = contained.revision + 1L,
			updatedAtMs = System.currentTimeMillis(),
		)
		gate(
			shadow.lane.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
			"STEPS_GATE_SHADOW_NOT_INERT",
		)
		val transition = entryPoint.stepsWriterTransitionCoordinator().activateCandidate(
			expectedRolloutRevision = shadow.rollout.revision,
			updatedAtMs = System.currentTimeMillis(),
		)
		gate(
			transition is StepsWriterTransitionResult.Applied,
			"STEPS_GATE_CANDIDATE_ACTIVATION_FAILED:$transition",
		)
		val applied = transition as StepsWriterTransitionResult.Applied
		val owner = requireNotNull(
			database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION),
		)
		gate(owner.owner == CANDIDATE_OWNER, "STEPS_GATE_CANDIDATE_NOT_OWNER")
		gate(owner.ownerGeneration == applied.ownerGeneration, "STEPS_GATE_OWNER_GENERATION_MISMATCH")
		val rollout = rolloutStore.load()
		gate(rollout.revision == applied.rolloutRevision, "STEPS_GATE_ROLLOUT_REVISION_MISMATCH")
		gate(
			rollout.isCaptureReachable(SourceKind.STEPS, CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
			"STEPS_GATE_MANUAL_STEPS_NOT_REACHABLE",
		)
		gate(
			SourceKind.entries.filter(rollout::isAcquisitionReachable) == listOf(SourceKind.STEPS),
			"STEPS_GATE_OTHER_SOURCE_REACHABLE",
		)
		return rollout.revision
	}

	private suspend fun assertManualStepsReachability(expectedRolloutRevision: Long) {
		val reachability = TrackerServiceApi.readManualTrackingCaptureReachability(context)
		gate(
			reachability is ManualTrackingCaptureReachability.Available,
			"STEPS_GATE_CAPTURE_REACHABILITY_UNAVAILABLE:$reachability",
		)
		val available = reachability as ManualTrackingCaptureReachability.Available
		assertEquals(
			"STEPS_GATE_REACHABILITY_REVISION_MISMATCH",
			expectedRolloutRevision,
			available.rolloutRevision,
		)
		assertEquals(
			"STEPS_GATE_CAPTURE_SET_NOT_EXACT",
			setOf(TrackingCaptureSource.STEPS),
			available.reachableSources,
		)
		val readiness = TrackerServiceApi.readManualTrackingStartReadiness(context)
		gate(readiness is ManualTrackingStartReadiness.Ready, "STEPS_GATE_START_NOT_READY:$readiness")
		assertEquals(
			"STEPS_GATE_READINESS_REVISION_MISMATCH",
			expectedRolloutRevision,
			(readiness as ManualTrackingStartReadiness.Ready).rolloutRevision,
		)
	}

	@Suppress("CyclomaticComplexMethod", "ReturnCount")
	private suspend fun activeScenarioOrNull(): ActiveScenario? {
		val session = database.sourceSessionDao().activeSession() ?: return null
		val runId = session.currentServiceRunId ?: return null
		val run = database.sourceSessionDao().serviceRun(runId) ?: return null
		if (run.state == SessionLifecycleState.FAILED.name) {
			error("STEPS_GATE_SERVICE_RUN_FAILED:${run.runtimeFailureCode}")
		}
		if (run.state != SessionLifecycleState.ACTIVE.name || run.completedAtMs != null) {
			return null
		}
		val segmentId = run.sessionSegmentId ?: return null
		val manifestRevision = session.currentManifestRevision ?: return null
		val manifest = database.sourceSessionDao().manifest(
			session.logicalTrackingId,
			manifestRevision,
		) ?: return null
		val sources = database.sourceSessionDao().manifestSources(
			session.logicalTrackingId,
			manifestRevision,
		)
		if (sources.isEmpty()) {
			return null
		}
		val demands = database.sourceBrokerDao().currentDemands("session:${session.logicalTrackingId}")
		if (demands.isEmpty() || demands.any { it.status != SourceDemandEntity.STATUS_ACTIVE }) {
			return null
		}
		val registration = database.sourceBrokerDao().currentPhysicalRegistration(STEPS_SOURCE) ?: return null
		if (registration.status != ProviderRegistrationGenerationEntity.STATUS_ACTIVE) {
			return null
		}
		val authorization = database.sourceBrokerDao().latestAuthorization(
			STEPS_SOURCE,
			registration.registrationGeneration,
		)
		if (authorization.isEmpty()) {
			return null
		}
		return ActiveScenario(
			session = session,
			run = run,
			segmentId = segmentId,
			collectedDataEpoch = entryPoint.collectedDataLifecycleStore().snapshot().epoch,
			manifest = manifest,
			manifestSources = sources,
			demands = demands,
			registration = registration,
			authorization = authorization,
		)
	}

	private suspend fun assertExactActiveScenario(active: ActiveScenario, rolloutRevision: Long) {
		val source = active.manifestSources.singleOrNull()
		gate(source != null, "STEPS_GATE_MANIFEST_SOURCE_COUNT:${active.manifestSources.size}")
		requireNotNull(source)
		gate(active.session.sessionMode == SessionMode.MANUAL.name, "STEPS_GATE_SESSION_NOT_MANUAL")
		gate(
			active.session.startOrigin == SessionStartOrigin.MANUAL_FOREGROUND_START.name,
			"STEPS_GATE_SESSION_ORIGIN_NOT_MANUAL",
		)
		gate(active.session.rolloutRevision == rolloutRevision, "STEPS_GATE_SESSION_ROLLOUT_MISMATCH")
		gate(active.run.logicalTrackingId == active.session.logicalTrackingId, "STEPS_GATE_RUN_OWNER_MISMATCH")
		gate(active.run.sessionSegmentId == active.segmentId, "STEPS_GATE_RUN_SEGMENT_MISMATCH")
		gate(active.run.startIsUserInitiated, "STEPS_GATE_RUN_NOT_USER_INITIATED")
		gate(!active.run.startIsAmbient, "STEPS_GATE_RUN_IS_AMBIENT")
		gate(active.run.startOrigin == SessionStartOrigin.MANUAL_FOREGROUND_START.name, "STEPS_GATE_RUN_ORIGIN")
		gate(active.run.rolloutRevision == rolloutRevision, "STEPS_GATE_RUN_ROLLOUT_MISMATCH")
		gate(active.manifest.serviceRunId == active.run.serviceRunId, "STEPS_GATE_MANIFEST_RUN_MISMATCH")
		gate(active.manifest.sessionMode == SessionMode.MANUAL.name, "STEPS_GATE_MANIFEST_NOT_MANUAL")
		gate(active.manifest.rolloutRevision == rolloutRevision, "STEPS_GATE_MANIFEST_ROLLOUT_MISMATCH")
		gate(
			SessionManifestIntegrity.verify(active.manifest, active.manifestSources),
			"STEPS_GATE_MANIFEST_INTEGRITY_FAILED",
		)
		assertExactStepsManifestSource(source)

		val demand = active.demands.singleOrNull()
		gate(demand != null, "STEPS_GATE_DEMAND_COUNT:${active.demands.size}")
		requireNotNull(demand)
		assertExactDemand(demand, active, source)
		gate(nonterminalDemandCount() == 1L, "STEPS_GATE_NONTERMINAL_DEMAND_NOT_EXACT")
		gate(nonterminalRegistrationCount() == 1L, "STEPS_GATE_NONTERMINAL_REGISTRATION_NOT_EXACT")
		gate(
			queryLong(
				"SELECT COUNT(*) FROM source_demand WHERE status IN ('ACTIVE','RETIRING','BLOCKED') " +
					"AND purpose != '${SourceBrokerPurpose.SESSION_CAPTURE}'",
			) == 0L,
			"STEPS_GATE_CONTROL_OR_AMBIENT_DEMAND_PRESENT",
		)
		gate(
			queryLong(
				"SELECT COUNT(*) FROM source_demand WHERE status IN ('ACTIVE','RETIRING','BLOCKED') " +
					"AND source_kind != $STEPS_SOURCE",
			) == 0L,
			"STEPS_GATE_OTHER_SOURCE_DEMAND_PRESENT",
		)
		gate(active.registration.sourceKind == STEPS_SOURCE, "STEPS_GATE_REGISTRATION_NOT_STEPS")
		gate(active.registration.acceptedAtMs != null, "STEPS_GATE_REGISTRATION_NOT_ACCEPTED")
		gate(
			active.registration.collectedDataEpoch == active.collectedDataEpoch,
			"STEPS_GATE_REGISTRATION_EPOCH",
		)
		val authorization = active.authorization.singleOrNull()
		gate(authorization != null, "STEPS_GATE_AUTHORIZATION_MEMBER_COUNT:${active.authorization.size}")
		requireNotNull(authorization)
		assertExactAuthorization(authorization, active, demand, source)
		val activity = entryPoint.activityRegistrationArbiter().snapshot()
		gate(!activity.active && activity.owners.isEmpty(), "STEPS_GATE_ACTIVITY_DEMAND_REGISTERED")
	}

	private fun assertExactStepsManifestSource(source: SessionManifestSourceEntity) {
		gate(source.sourceKind == STEPS_SOURCE, "STEPS_GATE_MANIFEST_SOURCE_NOT_STEPS")
		gate(source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE, "STEPS_GATE_MANIFEST_PURPOSE")
		gate(source.persistenceEligible, "STEPS_GATE_MANIFEST_NOT_PERSISTENCE_ELIGIBLE")
		gate(source.outputDestination == STEPS_DESTINATION, "STEPS_GATE_MANIFEST_DESTINATION")
		gate(source.writerOwner == CANDIDATE_OWNER, "STEPS_GATE_MANIFEST_WRITER_OWNER")
		gate(source.writerOwnerGeneration != null, "STEPS_GATE_MANIFEST_OWNER_GENERATION")
		gate(
			source.writerProjectionId == SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			"STEPS_GATE_MANIFEST_PROJECTION_ID",
		)
		gate(
			source.writerProjectionVersion == SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
			"STEPS_GATE_MANIFEST_PROJECTION_VERSION",
		)
		gate(
			source.writerBindingGeneration == SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
			"STEPS_GATE_MANIFEST_BINDING_GENERATION",
		)
	}

	private fun assertExactDemand(
		demand: SourceDemandEntity,
		active: ActiveScenario,
		source: SessionManifestSourceEntity,
	) {
		gate(demand.consumerId == "session:${active.session.logicalTrackingId}", "STEPS_GATE_DEMAND_CONSUMER")
		gate(demand.sourceKind == STEPS_SOURCE, "STEPS_GATE_DEMAND_SOURCE")
		gate(demand.purpose == SourceBrokerPurpose.SESSION_CAPTURE, "STEPS_GATE_DEMAND_PURPOSE")
		gate(demand.logicalTrackingId == active.session.logicalTrackingId, "STEPS_GATE_DEMAND_SESSION")
		gate(demand.serviceRunId == active.run.serviceRunId, "STEPS_GATE_DEMAND_RUN")
		gate(demand.manifestRevision == active.manifest.manifestRevision, "STEPS_GATE_DEMAND_MANIFEST")
		gate(demand.lifecycleLeaseGeneration == active.run.leaseGeneration, "STEPS_GATE_DEMAND_LEASE")
		gate(demand.sourcePolicyRevision == active.manifest.sourcePolicyRevision, "STEPS_GATE_DEMAND_POLICY")
		gate(demand.consentEpoch == source.consentEpoch, "STEPS_GATE_DEMAND_CONSENT")
		gate(demand.persistenceEligible, "STEPS_GATE_DEMAND_NOT_PERSISTENCE_ELIGIBLE")
		gate(demand.status == SourceDemandEntity.STATUS_ACTIVE, "STEPS_GATE_DEMAND_NOT_ACTIVE")
	}

	private fun assertExactAuthorization(
		authorization: SourceAuthorizationEntity,
		active: ActiveScenario,
		demand: SourceDemandEntity,
		source: SessionManifestSourceEntity,
	) {
		gate(!authorization.isDenyAll, "STEPS_GATE_AUTHORIZATION_DENY_ALL")
		gate(authorization.demandId == demand.demandId, "STEPS_GATE_AUTHORIZATION_DEMAND")
		gate(authorization.consumerId == demand.consumerId, "STEPS_GATE_AUTHORIZATION_CONSUMER")
		gate(authorization.purpose == SourceBrokerPurpose.SESSION_CAPTURE, "STEPS_GATE_AUTHORIZATION_PURPOSE")
		gate(authorization.persistenceEligible, "STEPS_GATE_AUTHORIZATION_NOT_PERSISTENCE_ELIGIBLE")
		gate(authorization.logicalTrackingId == active.session.logicalTrackingId, "STEPS_GATE_AUTHORIZATION_SESSION")
		gate(authorization.serviceRunId == active.run.serviceRunId, "STEPS_GATE_AUTHORIZATION_RUN")
		gate(authorization.manifestRevision == active.manifest.manifestRevision, "STEPS_GATE_AUTHORIZATION_MANIFEST")
		gate(authorization.lifecycleLeaseGeneration == active.run.leaseGeneration, "STEPS_GATE_AUTHORIZATION_LEASE")
		gate(authorization.sourcePolicyRevision == active.manifest.sourcePolicyRevision, "STEPS_GATE_AUTHORIZATION_POLICY")
		gate(authorization.consentEpoch == source.consentEpoch, "STEPS_GATE_AUTHORIZATION_CONSENT")
		gate(
			authorization.purposeEligibilityMask == SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			"STEPS_GATE_AUTHORIZATION_PURPOSE_MASK",
		)
	}

	@Suppress("CyclomaticComplexMethod", "ReturnCount")
	private suspend fun findPositivePostBaseline(
		afterOrdinal: Long,
		logicalTrackingId: String,
		serviceRunId: String,
	): RecordingEvidence? {
		val events = committedEventsAfter(afterOrdinal)
		val sessionEvents = events.filter { event ->
			event.evidence.logicalTrackingId?.value == logicalTrackingId &&
				event.evidence.serviceRunId?.value == serviceRunId
		}
		val positive = sessionEvents.firstOrNull { event ->
			val payload = event.evidence.payload as? StepCounterWindowPayload
			payload?.boundaryKind == StepBoundaryKind.COVERED && payload.deltaCount > 0L
		} ?: return null
		val baseline = sessionEvents.lastOrNull { event ->
			val payload = event.evidence.payload as? StepCounterWindowPayload
			event.admissionOrdinal < positive.admissionOrdinal &&
				event.evidence.sourceInstanceId == positive.evidence.sourceInstanceId &&
				event.evidence.registrationGeneration == positive.evidence.registrationGeneration &&
				payload?.boundaryKind == StepBoundaryKind.BASELINE
		} ?: return null
		return RecordingEvidence(baseline, positive)
	}

	private suspend fun committedEventsAfter(
		afterOrdinal: Long,
	): List<AdmittedSourceEvent<out SourcePayload>> {
		val highWater = database.sourceEventWalDao().maximumAdmissionOrdinal() ?: return emptyList()
		if (highWater <= afterOrdinal) {
			return emptyList()
		}
		val events = mutableListOf<AdmittedSourceEvent<out SourcePayload>>()
		var cursor = afterOrdinal
		while (cursor < highWater) {
			val batch = ingress.committedSourceBatch(
				source = SourceKind.STEPS,
				afterOrdinal = cursor,
				throughOrdinal = highWater,
				limit = WAL_SCAN_PAGE_SIZE,
			)
			if (batch.isEmpty()) {
				break
			}
			events += batch
			cursor = batch.last().admissionOrdinal
		}
		return events
	}

	private fun assertRecordingEvidence(
		recording: RecordingEvidence,
		active: ActiveScenario,
		initialWalHighWater: Long,
	) {
		val baseline = recording.baseline
		val positive = recording.positive
		val baselinePayload = recording.baselinePayload
		val positivePayload = recording.positivePayload
		gate(baseline.admissionOrdinal > initialWalHighWater, "STEPS_GATE_BASELINE_NOT_NEW")
		gate(positive.admissionOrdinal > baseline.admissionOrdinal, "STEPS_GATE_POSITIVE_NOT_AFTER_BASELINE")
		gate(baseline.evidence.planAttribution == PlanAttribution.CAPTURED_REGISTRATION, "STEPS_GATE_BASELINE_PLAN")
		gate(positive.evidence.planAttribution == PlanAttribution.CAPTURED_REGISTRATION, "STEPS_GATE_POSITIVE_PLAN")
		gate(baseline.evidence.payloadVersion == STEP_PAYLOAD_VERSION, "STEPS_GATE_BASELINE_PAYLOAD_VERSION")
		gate(positive.evidence.payloadVersion == STEP_PAYLOAD_VERSION, "STEPS_GATE_POSITIVE_PAYLOAD_VERSION")
		gate(baselinePayload.deltaCount == 0L, "STEPS_GATE_BASELINE_NONZERO")
		gate(
			baselinePayload.firstCumulativeCount == baselinePayload.lastCumulativeCount,
			"STEPS_GATE_BASELINE_COUNTER_SHAPE",
		)
		gate(
			baselinePayload.windowStartElapsedRealtimeNanos == baselinePayload.windowEndElapsedRealtimeNanos,
			"STEPS_GATE_BASELINE_WINDOW_SHAPE",
		)
		gate(positivePayload.deltaCount > 0L, "STEPS_GATE_POSITIVE_DELTA_MISSING")
		gate(
			positivePayload.deltaCount ==
				positivePayload.lastCumulativeCount - positivePayload.firstCumulativeCount,
			"STEPS_GATE_POSITIVE_DELTA_INCONSISTENT",
		)
		gate(
			positivePayload.windowStartElapsedRealtimeNanos == baselinePayload.windowEndElapsedRealtimeNanos,
			"STEPS_GATE_POSITIVE_WINDOW_NOT_CONTIGUOUS",
		)
		gate(
			positivePayload.firstProviderSequence == baselinePayload.lastProviderSequence,
			"STEPS_GATE_POSITIVE_SEQUENCE_NOT_CONTIGUOUS",
		)
		gate(
			positivePayload.firstCumulativeCount == baselinePayload.lastCumulativeCount,
			"STEPS_GATE_POSITIVE_COUNTER_NOT_CONTIGUOUS",
		)
		assertRecordingTiming(baseline, positive, active)
		listOf(baseline, positive).forEach { event -> assertExactWalAttribution(event, active) }
		gate(
			queryLong(
				"SELECT COUNT(*) FROM source_event_wal WHERE admission_ordinal > $initialWalHighWater " +
					"AND source_kind != $STEPS_SOURCE",
			) == 0L,
			"STEPS_GATE_OTHER_SOURCE_WAL_PRESENT",
		)
	}

	private fun assertRecordingTiming(
		baseline: AdmittedSourceEvent<out SourcePayload>,
		positive: AdmittedSourceEvent<out SourcePayload>,
		active: ActiveScenario,
	) {
		val baselineObserved = baseline.evidence.observedElapsedRealtimeNanos
		gate(baselineObserved >= active.run.startedElapsedNanos, "STEPS_GATE_BASELINE_BEFORE_RUN")
		gate(
			baselineObserved >= active.manifest.effectiveElapsedRealtimeNanos,
			"STEPS_GATE_BASELINE_BEFORE_MANIFEST",
		)
		gate(
			baselineObserved >= active.authorization.single().effectiveElapsedRealtimeNanos,
			"STEPS_GATE_BASELINE_BEFORE_AUTHORIZATION",
		)
		gate(
			positive.evidence.observedElapsedRealtimeNanos > active.run.startedElapsedNanos,
			"STEPS_GATE_POSITIVE_NOT_POST_START",
		)
	}

	private fun assertExactWalAttribution(
		event: AdmittedSourceEvent<out SourcePayload>,
		active: ActiveScenario,
	) {
		val evidence = event.evidence
		val authorization = active.authorization.single()
		val manifestSource = active.manifestSources.single()
		gate(evidence.source == SourceKind.STEPS, "STEPS_GATE_WAL_SOURCE")
		val payload = evidence.payload as? StepCounterWindowPayload
		gate(payload != null, "STEPS_GATE_WAL_PAYLOAD_NOT_STEPS")
		requireNotNull(payload)
		gate(
			payload.windowEndElapsedRealtimeNanos == evidence.observedElapsedRealtimeNanos,
			"STEPS_GATE_WAL_OBSERVED_WINDOW_END_MISMATCH",
		)
		gate(payload.bootClockDomainId == evidence.clockDomainId, "STEPS_GATE_WAL_PAYLOAD_CLOCK_DOMAIN")
		gate(evidence.clockDomainId == active.registration.clockDomainId, "STEPS_GATE_WAL_REGISTRATION_CLOCK_DOMAIN")
		gate(evidence.logicalTrackingId?.value == active.session.logicalTrackingId, "STEPS_GATE_WAL_SESSION")
		gate(evidence.serviceRunId?.value == active.run.serviceRunId, "STEPS_GATE_WAL_RUN")
		gate(evidence.sourceInstanceId.value == active.registration.sourceInstanceId, "STEPS_GATE_WAL_INSTANCE")
		gate(evidence.registrationGeneration == active.registration.registrationGeneration, "STEPS_GATE_WAL_REGISTRATION")
		gate(
			evidence.physicalConfigurationFingerprint == active.registration.physicalConfigurationFingerprint,
			"STEPS_GATE_WAL_PHYSICAL_CONFIGURATION",
		)
		gate(evidence.authorizationRevision == authorization.authorizationRevision, "STEPS_GATE_WAL_AUTHORIZATION")
		gate(
			evidence.registrationPurposeEligibilityMask == SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			"STEPS_GATE_WAL_PURPOSE_MASK",
		)
		gate(
			evidence.registrationEligibilityFingerprint == authorization.authorizationFingerprint,
			"STEPS_GATE_WAL_AUTHORIZATION_FINGERPRINT",
		)
		gate(evidence.sourcePolicyRevision == active.manifest.sourcePolicyRevision, "STEPS_GATE_WAL_POLICY")
		gate(evidence.captureConsentEpoch == manifestSource.consentEpoch, "STEPS_GATE_WAL_CONSENT")
		gate(evidence.sessionManifestRevision == active.manifest.manifestRevision, "STEPS_GATE_WAL_MANIFEST")
		gate(evidence.lifecycleLeaseGeneration == active.run.leaseGeneration, "STEPS_GATE_WAL_LEASE")
		gate(
			evidence.capturedCollectedDataEpoch == active.collectedDataEpoch,
			"STEPS_GATE_WAL_COLLECTED_DATA_EPOCH",
		)
	}

	private suspend fun assertMaterializedFact(
		fact: com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity,
		recording: RecordingEvidence,
		active: ActiveScenario,
	) {
		val positive = recording.positive
		val source = active.manifestSources.single()
		gate(fact.sourceAdmissionOrdinal == positive.admissionOrdinal, "STEPS_GATE_FACT_ADMISSION")
		gate(fact.sourceEventId == positive.eventId.value, "STEPS_GATE_FACT_EVENT")
		gate(fact.effectiveStepCount == recording.positivePayload.deltaCount, "STEPS_GATE_FACT_COUNT")
		gate(fact.coverageKind == StepFactRevisionEntity.COVERAGE_COVERED, "STEPS_GATE_FACT_COVERAGE")
		gate(fact.logicalTrackingId == active.session.logicalTrackingId, "STEPS_GATE_FACT_SESSION")
		gate(fact.serviceRunId == active.run.serviceRunId, "STEPS_GATE_FACT_RUN")
		gate(fact.purpose == SourceBrokerPurpose.SESSION_CAPTURE, "STEPS_GATE_FACT_PURPOSE")
		gate(fact.manifestRevision == active.manifest.manifestRevision, "STEPS_GATE_FACT_MANIFEST")
		gate(fact.sourcePolicyRevision == active.manifest.sourcePolicyRevision, "STEPS_GATE_FACT_POLICY")
		gate(fact.captureConsentEpoch == source.consentEpoch, "STEPS_GATE_FACT_CONSENT")
		gate(fact.collectedDataEpoch == active.collectedDataEpoch, "STEPS_GATE_FACT_EPOCH")
		gate(fact.stepIntervalId == null, "STEPS_GATE_FACT_LEGACY_ROW_BOUND")
		gate(
			database.stepIntervalDao().getBySourceSignalId("source-event:${positive.eventId.value}") == null,
			"STEPS_GATE_LEGACY_WRITER_DUPLICATE",
		)
		val owner = requireNotNull(
			database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION),
		)
		gate(owner.owner == CANDIDATE_OWNER, "STEPS_GATE_MATERIALIZED_OWNER_CHANGED")
		val lane = requireNotNull(database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE))
		gate(lane.productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL, "STEPS_GATE_LANE_NOT_CANONICAL")
		gate(lane.contiguousAdmissionOrdinal >= positive.admissionOrdinal, "STEPS_GATE_LANE_CURSOR_BEHIND")
		gate(
			queryLong(
				"SELECT COUNT(*) FROM source_product_projection_lane WHERE source_kind = $STEPS_SOURCE " +
					"AND status = '${SourceProductProjectionLaneEntity.STATUS_ACTIVE}' " +
					"AND product_stage = '${SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL}'",
			) == 1L,
			"STEPS_GATE_CANONICAL_WRITER_COUNT",
		)
	}

	@Suppress("ReturnCount")
	private suspend fun terminalScenarioOrNull(active: ActiveScenario): TerminalScenario? {
		val run = database.sourceSessionDao().serviceRun(active.run.serviceRunId) ?: return null
		if (run.state == SessionLifecycleState.FAILED.name) {
			error("STEPS_GATE_TERMINAL_RUN_FAILED:${run.runtimeFailureCode}")
		}
		if (
			run.state != SessionLifecycleState.FINALIZED.name ||
			run.completedAtMs == null ||
			run.presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_QUIESCED
		) {
			return null
		}
		if (nonterminalDemandCount() != 0L || nonterminalRegistrationCount() != 0L) {
			return null
		}
		val completeness = database.sourceSessionDao().completenessForServiceRun(
			active.session.logicalTrackingId,
			active.run.serviceRunId,
		)
		if (completeness.isEmpty()) {
			return null
		}
		return TerminalScenario(run, completeness)
	}

	private suspend fun assertExactTerminalScenario(
		terminal: TerminalScenario,
		active: ActiveScenario,
	) {
		gate(terminal.run.sessionSegmentId == active.segmentId, "STEPS_GATE_TERMINAL_SEGMENT_MISMATCH")
		gate(terminal.run.presentationAcknowledgedAtMs != null, "STEPS_GATE_PRESENTATION_NOT_ACKNOWLEDGED")
		val completeness = terminal.completeness.singleOrNull()
		gate(completeness != null, "STEPS_GATE_COMPLETENESS_COUNT:${terminal.completeness.size}")
		requireNotNull(completeness)
		gate(completeness.sourceKind == STEPS_SOURCE, "STEPS_GATE_COMPLETENESS_SOURCE")
		gate(completeness.serviceRunId == active.run.serviceRunId, "STEPS_GATE_COMPLETENESS_RUN")
		gate(completeness.appDrainComplete, "STEPS_GATE_APP_DRAIN_INCOMPLETE")
		gate(completeness.stopStatus == "COMPLETE", "STEPS_GATE_PROVIDER_STOP_INCOMPLETE")
		gate(completeness.unresolvedSequenceStart == null, "STEPS_GATE_UNRESOLVED_SEQUENCE_START")
		gate(completeness.unresolvedSequenceEnd == null, "STEPS_GATE_UNRESOLVED_SEQUENCE_END")
		val registration = requireNotNull(
			database.sourceBrokerDao().registration(
				STEPS_SOURCE,
				active.registration.registrationGeneration,
			),
		)
		gate(
			registration.status == ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			"STEPS_GATE_STEPS_REGISTRATION_NOT_RETIRED:${registration.status}",
		)
		gate(database.sourceSessionDao().activeSession() == null, "STEPS_GATE_SESSION_REMAINS_ACTIVE")
		val activity = entryPoint.activityRegistrationArbiter().snapshot()
		gate(!activity.active && activity.owners.isEmpty(), "STEPS_GATE_ACTIVITY_ACTIVE_AFTER_STOP")
		assertNoLiveBrokerState()
	}

	private suspend fun assertCompleteDurableAudit(
		baseline: BrokerAuditBaseline,
		initialWalHighWater: Long,
		active: ActiveScenario,
	) {
		assertDurableDemandAudit(baseline, active)
		assertDurableRegistrationAudit(baseline, active)
		assertDurableWalAudit(initialWalHighWater, active)
	}

	private fun assertDurableDemandAudit(baseline: BrokerAuditBaseline, active: ActiveScenario) {
		val expectedDemand = active.demands.single()
		val newDemands = demandAuditRows().filterNot { it.demandId in baseline.demandIds }
		val demand = newDemands.singleOrNull()
		gate(demand != null, "STEPS_GATE_DURABLE_DEMAND_AUDIT_COUNT:${newDemands.size}")
		requireNotNull(demand)
		gate(demand.demandId == expectedDemand.demandId, "STEPS_GATE_DURABLE_DEMAND_ID")
		gate(demand.consumerId == expectedDemand.consumerId, "STEPS_GATE_DURABLE_DEMAND_CONSUMER")
		gate(demand.sourceKind == STEPS_SOURCE, "STEPS_GATE_DURABLE_DEMAND_SOURCE")
		gate(demand.purpose == SourceBrokerPurpose.SESSION_CAPTURE, "STEPS_GATE_DURABLE_DEMAND_PURPOSE")
		gate(demand.logicalTrackingId == active.session.logicalTrackingId, "STEPS_GATE_DURABLE_DEMAND_SESSION")
		gate(demand.serviceRunId == active.run.serviceRunId, "STEPS_GATE_DURABLE_DEMAND_RUN")
		gate(demand.manifestRevision == active.manifest.manifestRevision, "STEPS_GATE_DURABLE_DEMAND_MANIFEST")
		gate(demand.persistenceEligible, "STEPS_GATE_DURABLE_DEMAND_NOT_PERSISTENT")
		gate(demand.status == SourceDemandEntity.STATUS_RETIRED, "STEPS_GATE_DURABLE_DEMAND_NOT_RETIRED")
	}

	private fun assertDurableRegistrationAudit(baseline: BrokerAuditBaseline, active: ActiveScenario) {
		val newRegistrations = registrationAuditRows().filterNot { it.key in baseline.registrationKeys }
		val registration = newRegistrations.singleOrNull()
		gate(
			registration != null,
			"STEPS_GATE_DURABLE_REGISTRATION_AUDIT_COUNT:${newRegistrations.size}",
		)
		requireNotNull(registration)
		gate(registration.key.sourceKind == STEPS_SOURCE, "STEPS_GATE_DURABLE_REGISTRATION_SOURCE")
		gate(
			registration.key.registrationGeneration == active.registration.registrationGeneration,
			"STEPS_GATE_DURABLE_REGISTRATION_GENERATION",
		)
		gate(
			registration.sourceInstanceId == active.registration.sourceInstanceId,
			"STEPS_GATE_DURABLE_REGISTRATION_INSTANCE",
		)
		gate(
			registration.status == ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			"STEPS_GATE_DURABLE_REGISTRATION_NOT_RETIRED",
		)
	}

	private suspend fun assertDurableWalAudit(initialWalHighWater: Long, active: ActiveScenario) {
		val newEvents = committedEventsAfter(initialWalHighWater)
		gate(newEvents.isNotEmpty(), "STEPS_GATE_DURABLE_WAL_AUDIT_EMPTY")
		val totalNewWalRows = queryLong(
			"SELECT COUNT(*) FROM source_event_wal WHERE admission_ordinal > $initialWalHighWater",
		)
		gate(
			totalNewWalRows == newEvents.size.toLong(),
			"STEPS_GATE_DURABLE_WAL_NON_STEPS_OR_UNDECODED_ROWS",
		)
		gate(
			queryLong(
				"SELECT COUNT(*) FROM source_event_wal WHERE admission_ordinal > $initialWalHighWater " +
					"AND source_kind != $STEPS_SOURCE",
			) == 0L,
			"STEPS_GATE_DURABLE_WAL_OTHER_SOURCE",
		)
		newEvents.forEach { event ->
			gate(
				event.evidence.payload is StepCounterWindowPayload,
				"STEPS_GATE_DURABLE_WAL_PAYLOAD_NOT_STEPS",
			)
			assertExactWalAttribution(event, active)
		}
		val payloads = newEvents.map { it.evidence.payload as StepCounterWindowPayload }
		gate(
			payloads.first().boundaryKind == StepBoundaryKind.BASELINE,
			"STEPS_GATE_DURABLE_WAL_FIRST_NOT_BASELINE",
		)
		gate(
			payloads.drop(1).isNotEmpty() && payloads.drop(1).all {
				it.boundaryKind == StepBoundaryKind.COVERED && it.deltaCount > 0L
			},
			"STEPS_GATE_DURABLE_WAL_NON_POSITIVE_OR_UNEXPECTED_WINDOW",
		)
	}

	private suspend fun assertNoLiveBrokerState() {
		gate(nonterminalDemandCount() == 0L, "STEPS_GATE_NONTERMINAL_DEMAND_AFTER_STOP")
		gate(nonterminalRegistrationCount() == 0L, "STEPS_GATE_NONTERMINAL_REGISTRATION_AFTER_STOP")
		SourceKind.entries.forEach { source ->
			val sourceCode = source.stableCode
			gate(
				database.sourceBrokerDao().currentPhysicalRegistrations(sourceCode).isEmpty(),
				"STEPS_GATE_CURRENT_REGISTRATION_AFTER_STOP:$source",
			)
			gate(
				database.sourceBrokerDao().pendingProviderRemovals(sourceCode).isEmpty(),
				"STEPS_GATE_PENDING_PROVIDER_REMOVAL:$source",
			)
		}
	}

	private suspend fun cleanupAfterFailure(active: ActiveScenario?) {
		val stop = awaitGate("STEPS_GATE_CLEANUP_STOP_TIMEOUT", STOP_TIMEOUT_MS) {
			TrackerServiceApi.stopServiceAndAwaitQuiescence(context)
		}
		gate(stop == TrackingStopQuiescenceResult.HANDLED, "STEPS_GATE_CLEANUP_STOP_NOT_HANDLED:$stop")
		if (active != null) {
			val terminal = awaitRoom(
				code = "STEPS_GATE_CLEANUP_TERMINAL_TIMEOUT",
				timeoutMs = STOP_TIMEOUT_MS,
				tables = TERMINAL_TABLES,
			) { terminalScenarioOrNull(active) }
			assertExactTerminalScenario(terminal, active)
		} else {
			awaitRoom(
				code = "STEPS_GATE_CLEANUP_BROKER_TIMEOUT",
				timeoutMs = STOP_TIMEOUT_MS,
				tables = TERMINAL_TABLES,
			) {
				true.takeIf {
					database.sourceSessionDao().activeSession() == null &&
						nonterminalDemandCount() == 0L &&
						nonterminalRegistrationCount() == 0L
				}
			}
			assertNoLiveBrokerState()
		}
		Log.w(TAG, "STEPS_GATE_CLEANUP_COMPLETE")
	}

	private fun assertTruthfulStepsHistory(history: SessionHistory, requireComplete: Boolean) {
		gate(history.capture is HistoryCapture.Exact, "STEPS_GATE_HISTORY_CAPTURE_UNVERIFIABLE")
		val capture = history.capture as HistoryCapture.Exact
		gate(
			capture.revisions.all { revision ->
				revision.capturedSources == setOf(HistorySource.STEPS) &&
					revision.controlSources.isEmpty()
			},
			"STEPS_GATE_HISTORY_CAPTURE_SET_NOT_EXACT",
		)
		gate(history.capturesOnlySteps, "STEPS_GATE_HISTORY_NOT_STEPS_ONLY")
		gate(history.qualifiedSources == setOf(HistorySource.STEPS), "STEPS_GATE_HISTORY_SOURCE_NOT_QUALIFIED")
		gate(history.steps.count?.let { it > 0L } == true, "STEPS_GATE_HISTORY_POSITIVE_COUNT_MISSING")
		gate(history.steps.evidence == HistoryEvidence.RECORDED, "STEPS_GATE_HISTORY_NOT_RECORDED")
		if (requireComplete) {
			gate(history.steps.productState == HistoryProductState.READY, "STEPS_GATE_HISTORY_NOT_READY")
			gate(history.steps.coverage == StepsHistoryCoverage.COMPLETE, "STEPS_GATE_HISTORY_NOT_COMPLETE")
			gate(history.steps.hasCompleteValue, "STEPS_GATE_HISTORY_VALUE_NOT_COMPLETE")
		}
	}

	private suspend fun <T : Any> awaitRoom(
		code: String,
		timeoutMs: Long,
		tables: Array<String>,
		read: suspend () -> T?,
	): T = awaitGate(code, timeoutMs) {
		database.invalidationTracker.createFlow(
			*tables,
			emitInitialState = true,
		)
			.map { read() }
			.filterNotNull()
			.first()
	}

	private suspend fun <T : Any> awaitGate(
		code: String,
		timeoutMs: Long,
		block: suspend () -> T,
	): T = withTimeoutOrNull(timeoutMs) { block() } ?: error(code)

	private fun nonterminalDemandCount(): Long = queryLong(
		"SELECT COUNT(*) FROM source_demand WHERE status IN ('ACTIVE','RETIRING','BLOCKED')",
	)

	private fun nonterminalRegistrationCount(): Long = queryLong(
		"SELECT COUNT(*) FROM provider_registration_generation " +
			"WHERE status IN ('RESERVED','ACTIVE','RETIRING')",
	)

	private fun brokerAuditBaseline() = BrokerAuditBaseline(
		demandIds = demandAuditRows().mapTo(linkedSetOf(), DemandAuditRow::demandId),
		registrationKeys = registrationAuditRows().mapTo(linkedSetOf(), RegistrationAuditRow::key),
	)

	private fun demandAuditRows(): List<DemandAuditRow> =
		database.openHelper.writableDatabase.query(
			"SELECT demand_id, consumer_id, source_kind, purpose, logical_tracking_id, " +
				"service_run_id, manifest_revision, persistence_eligible, status " +
				"FROM source_demand ORDER BY requested_elapsed_realtime_nanos, demand_id",
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					add(
						DemandAuditRow(
							demandId = cursor.getString(0),
							consumerId = cursor.getString(1),
							sourceKind = cursor.getInt(2),
							purpose = cursor.getString(3),
							logicalTrackingId = cursor.getStringOrNull(4),
							serviceRunId = cursor.getStringOrNull(5),
							manifestRevision = cursor.getLongOrNull(6),
							persistenceEligible = cursor.getInt(7) != 0,
							status = cursor.getString(8),
						),
					)
				}
			}
		}

	private fun registrationAuditRows(): List<RegistrationAuditRow> =
		database.openHelper.writableDatabase.query(
			"SELECT source_kind, registration_generation, source_instance_id, status " +
				"FROM provider_registration_generation ORDER BY source_kind, registration_generation",
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					add(
						RegistrationAuditRow(
							key = RegistrationAuditKey(
								sourceKind = cursor.getInt(0),
								registrationGeneration = cursor.getLong(1),
							),
							sourceInstanceId = cursor.getString(2),
							status = cursor.getString(3),
						),
					)
				}
			}
		}

	private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) {
		null
	} else {
		getString(index)
	}

	private fun android.database.Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) {
		null
	} else {
		getLong(index)
	}

	private fun queryLong(sql: String): Long =
		database.openHelper.writableDatabase.query(sql).use { cursor ->
			gate(cursor.moveToFirst(), "STEPS_GATE_QUERY_EMPTY:$sql")
			cursor.getLong(0)
		}

	private fun gate(condition: Boolean, code: String) {
		assertTrue(code, condition)
	}

	private data class ActiveScenario(
		val session: com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity,
		val run: SourceServiceRunEntity,
		val segmentId: Long,
		val collectedDataEpoch: Long,
		val manifest: SessionManifestVersionEntity,
		val manifestSources: List<SessionManifestSourceEntity>,
		val demands: List<SourceDemandEntity>,
		val registration: ProviderRegistrationGenerationEntity,
		val authorization: List<SourceAuthorizationEntity>,
	)

	private data class RecordingEvidence(
		val baseline: AdmittedSourceEvent<out SourcePayload>,
		val positive: AdmittedSourceEvent<out SourcePayload>,
	) {
		val baselinePayload: StepCounterWindowPayload
			get() = baseline.evidence.payload as StepCounterWindowPayload
		val positivePayload: StepCounterWindowPayload
			get() = positive.evidence.payload as StepCounterWindowPayload
	}

	private data class TerminalScenario(
		val run: SourceServiceRunEntity,
		val completeness: List<com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity>,
	)

	private data class BrokerAuditBaseline(
		val demandIds: Set<String>,
		val registrationKeys: Set<RegistrationAuditKey>,
	)

	private data class DemandAuditRow(
		val demandId: String,
		val consumerId: String,
		val sourceKind: Int,
		val purpose: String,
		val logicalTrackingId: String?,
		val serviceRunId: String?,
		val manifestRevision: Long?,
		val persistenceEligible: Boolean,
		val status: String,
	)

	private data class RegistrationAuditKey(
		val sourceKind: Int,
		val registrationGeneration: Long,
	)

	private data class RegistrationAuditRow(
		val key: RegistrationAuditKey,
		val sourceInstanceId: String,
		val status: String,
	)

	private companion object {
		const val TAG = "ManualStepsGate"
		const val LOG_LISTENER_ACTIVE = "STEPS_GATE_LISTENER_ACTIVE"
		const val LOG_LISTENER_RETIRED = "STEPS_GATE_LISTENER_RETIRED"
		const val START_TIMEOUT_MS = 30_000L
		const val RECORDING_TIMEOUT_MS = 180_000L
		const val MATERIALIZATION_TIMEOUT_MS = 30_000L
		const val HISTORY_TIMEOUT_MS = 30_000L
		const val STOP_TIMEOUT_MS = 60_000L
		const val WAL_SCAN_PAGE_SIZE = 64
		const val STEP_PAYLOAD_VERSION = 3
		const val STEPS_SOURCE = SourceDestinationOwnerEntity.SOURCE_STEPS
		const val STEPS_DESTINATION = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS
		const val LEGACY_OWNER = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL
		const val CANDIDATE_OWNER = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
		const val LOGICAL_SESSION_TABLE = "logical_tracking_session"
		const val SERVICE_RUN_TABLE = "source_service_run"
		const val SESSION_SEGMENT_TABLE = "session_segment"
		const val MANIFEST_TABLE = "session_manifest_version"
		const val MANIFEST_SOURCE_TABLE = "session_manifest_source"
		const val DEMAND_TABLE = "source_demand"
		const val REGISTRATION_TABLE = "provider_registration_generation"
		const val AUTHORIZATION_TABLE = "source_authorization"
		const val WAL_TABLE = "source_event_wal"
		const val FACT_TABLE = "step_fact_revision"
		const val PRODUCT_LANE_TABLE = "source_product_projection_lane"
		const val PROJECTION_FAILURE_TABLE = "source_projection_failure"
		const val COMPLETENESS_TABLE = "source_session_completeness"
		val ACTIVE_SESSION_TABLES = arrayOf(
			LOGICAL_SESSION_TABLE,
			SERVICE_RUN_TABLE,
			SESSION_SEGMENT_TABLE,
			MANIFEST_TABLE,
			MANIFEST_SOURCE_TABLE,
			DEMAND_TABLE,
			REGISTRATION_TABLE,
			AUTHORIZATION_TABLE,
		)
		val TERMINAL_TABLES = arrayOf(
			LOGICAL_SESSION_TABLE,
			SERVICE_RUN_TABLE,
			DEMAND_TABLE,
			REGISTRATION_TABLE,
			COMPLETENESS_TABLE,
		)
	}
}
