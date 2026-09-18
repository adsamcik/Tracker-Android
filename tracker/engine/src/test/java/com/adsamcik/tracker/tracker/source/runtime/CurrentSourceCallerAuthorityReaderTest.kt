package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailability
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailabilityReader
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerReplayKind
import com.adsamcik.tracker.tracker.api.TrackingPurposeAuthorityRevision
import com.adsamcik.tracker.tracker.source.coordinator.BatteryEstimateMode
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.CoordinatorMode
import com.adsamcik.tracker.tracker.source.coordinator.ProductProjectionStage
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import com.adsamcik.tracker.tracker.source.coordinator.SessionMode
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.coordinator.SourceOwner
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CurrentSourceCallerAuthorityReaderTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `fresh manifest and initial foreground delivery require the live lease`() = runTest {
		seedSession(
			liveLease = true,
			suspended = false,
			storedBootId = BOOT_ID,
			sessionState = SessionLifecycleState.STARTING,
		)
		val reader = reader(currentBootId = BOOT_ID)

		reader.readCurrentManifest(MANIFEST).shouldNotBeNull()
		reader.readReplayManifest(
			MANIFEST,
			SourceCallerReplayKind.FOREGROUND_SERVICE_DELIVERY,
		).shouldNotBeNull()
	}

	@Test
	fun `active redelivery remains readable after the coordinator lease is released`() = runTest {
		seedSession(liveLease = false, suspended = false, storedBootId = BOOT_ID)
		val reader = reader(currentBootId = BOOT_ID)

		reader.readCurrentManifest(MANIFEST) shouldBe null
		reader.readReplayManifest(
			MANIFEST,
			SourceCallerReplayKind.ACTIVE_REDELIVERY,
		).shouldNotBeNull()
	}

	@Test
	fun `suspended manual session remains readable for process recovery`() = runTest {
		seedSession(liveLease = false, suspended = true, storedBootId = BOOT_ID)
		val reader = reader(currentBootId = BOOT_ID)

		reader.readCurrentManifest(MANIFEST) shouldBe null
		reader.readReplayManifest(
			MANIFEST,
			SourceCallerReplayKind.PROCESS_RECOVERY,
		).shouldNotBeNull()
	}

	@Test
	fun `old boot replay is rejected even when stored session lease and manifest agree`() = runTest {
		seedSession(liveLease = false, suspended = true, storedBootId = "old-boot")

		reader(currentBootId = BOOT_ID).readReplayManifest(
			MANIFEST,
			SourceCallerReplayKind.PROCESS_RECOVERY,
		) shouldBe null
	}

	private suspend fun seedSession(
		liveLease: Boolean,
		suspended: Boolean,
		storedBootId: String,
		sessionState: SessionLifecycleState = SessionLifecycleState.ACTIVE,
	) {
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = COLLECTED_DATA_EPOCH, updatedAtMs = 1L),
		)
		val policyDao = database.sourcePolicyDao()
		val authority = SourcePolicyAuthorityEntity(
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			currentPolicyRevision = POLICY_REVISION,
			legacySettingsFingerprint = null,
			updatedAtMs = 1L,
		)
		val currentAuthority = policyDao.authority()
		if (currentAuthority == null) {
			policyDao.ensureAuthority(authority)
		} else {
			policyDao.compareAndSetAuthority(
				currentAuthority.bootstrapState,
				currentAuthority.currentPolicyRevision,
				authority.bootstrapState,
				authority.currentPolicyRevision,
				authority.legacySettingsFingerprint,
				authority.updatedAtMs,
			)
		}
		policyDao.insertPolicies(
			listOf(
				SourcePolicyEntity(
					policyRevision = POLICY_REVISION,
					sourceKind = TrackingSource.LOCATION.stableCode,
					enabled = true,
					qosCode = 0,
					locationMinTimeSeconds = null,
					locationMinDistanceMeters = null,
					locationRequiredAccuracyMeters = null,
					capturePersistenceEligible = true,
					controlPersistenceEligible = false,
					ambientPersistenceEligible = false,
					captureConsentEpoch = CONSENT_EPOCH,
					controlConsentEpoch = null,
					ambientConsentEpoch = null,
					effectiveBootId = storedBootId,
					effectiveElapsedRealtimeNanos = 1L,
					effectiveWallTimeMs = 1L,
					changeReason = "TEST",
				),
			),
		)
		val binding = SessionManifestSourceEntity(
			logicalTrackingId = MANIFEST.logicalTrackingId,
			manifestRevision = MANIFEST.manifestRevision,
			sourceKind = TrackingSource.LOCATION.stableCode,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch = CONSENT_EPOCH,
			persistenceEligible = true,
			qosCode = 0,
		)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = MANIFEST.logicalTrackingId,
			manifestRevision = MANIFEST.manifestRevision,
			serviceRunId = SERVICE_RUN_ID,
			sessionMode = SessionMode.MANUAL.name,
			sourcePolicyRevision = POLICY_REVISION,
			acquisitionPlanRevision = 1L,
			rolloutRevision = ROLLOUT_REVISION,
			startOrigin = SessionStartOrigin.MANUAL_FOREGROUND_START.name,
			effectiveBootId = storedBootId,
			effectiveElapsedRealtimeNanos = 1L,
			effectiveWallTimeMs = 1L,
			zoneId = "UTC",
			automationEpoch = null,
			changeReason = "TEST",
			manifestChecksum = "pending",
		)
		val manifest = unsigned.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(binding)),
		)
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = MANIFEST.logicalTrackingId,
				state = sessionState.name,
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = ROLLOUT_REVISION,
				startOrigin = SessionStartOrigin.MANUAL_FOREGROUND_START.name,
				clockDomainId = storedBootId,
				startedAtMs = 1L,
				startedElapsedNanos = 1L,
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				completedAtMs = null,
				finalAdmissionOrdinal = null,
				failureCode = null,
				sessionMode = SessionMode.MANUAL.name,
				currentManifestRevision = MANIFEST.manifestRevision,
				currentIntentRevision = 1L,
				currentServiceRunId = SERVICE_RUN_ID.takeUnless { suspended },
				lifecycleLeaseGeneration = EXECUTION_REVISION,
				lifecycleBootId = storedBootId,
				automationEpoch = null,
			),
		)
		database.sourceSessionDao().insertManifest(manifest)
		database.sourceSessionDao().insertManifestSources(listOf(binding))
		val now = SystemClock.elapsedRealtimeNanos()
		database.sourceProjectionStateDao().insertLeaseIfAbsent(
			SourceCoordinatorLeaseEntity(
				leaseName = "tracking-session-coordinator",
				ownerToken = "owner",
				acquiredAtMs = 1L,
				expiresAtMs = Long.MAX_VALUE,
				bootId = storedBootId,
				generation = EXECUTION_REVISION,
				acquiredElapsedRealtimeNanos = 1L,
				expiresElapsedRealtimeNanos = if (liveLease) Long.MAX_VALUE else now,
			),
		)
	}

	private fun reader(currentBootId: String) = CurrentSourceCallerAuthorityReader(
		database = database,
		rolloutStateStore = object : TrackingRolloutStateStore {
			override suspend fun load(): TrackingRolloutState = rollout()
			override suspend fun save(state: TrackingRolloutState, updatedAtMs: Long) = Unit
		},
		purposeAvailabilityReader = object : CurrentTrackingPurposeAvailabilityReader {
			override val availability =
				MutableStateFlow(CurrentTrackingPurposeAvailability.SAFE_DEFAULT)
			override val authorityRevision =
				MutableStateFlow(TrackingPurposeAuthorityRevision.UNAVAILABLE)
		},
		exactPurposeAuthorityReader = TrackingPurposeAuthorityReader { _, _ -> null },
		executionRevisionRegistry = TrackingPurposeExecutionRevisionRegistry(),
		clockDomainProvider = BootClockDomainProvider { currentBootId },
	)

	private fun rollout() = TrackingRolloutState(
		revision = ROLLOUT_REVISION,
		schemaVersion = TrackingRolloutState.CURRENT_SCHEMA_VERSION,
		coordinatorMode = CoordinatorMode.EVENT,
		sourceOwners = SourceKind.entries.associateWith { SourceOwner.EVENT },
		productProjectionStages = SourceKind.entries.associateWith {
			ProductProjectionStage.EVENT_CANONICAL
		},
		captureModeMasks = SourceKind.entries.associateWith {
			CaptureReachabilityMode.MANUAL_SESSION_CAPTURE.mask
		},
		semanticSettingsEnabled = false,
		batteryEstimateMode = BatteryEstimateMode.SOURCE_PLAN_QUALITATIVE,
	)

	private companion object {
		val MANIFEST = SourceCallerManifestIdentity("reader-session", 1L)
		const val SERVICE_RUN_ID = "reader-run"
		const val BOOT_ID = "boot-current"
		const val POLICY_REVISION = 3L
		const val CONSENT_EPOCH = 4L
		const val COLLECTED_DATA_EPOCH = 5L
		const val ROLLOUT_REVISION = 6L
		const val EXECUTION_REVISION = 7L
	}
}
