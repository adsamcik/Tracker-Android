package com.adsamcik.tracker.shared.preferences.tracking

import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.retention.CurrentRetentionAuthority
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityScope
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityState
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigurationApprovalResult
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthoritativeTrackingParamsRepositoryTest {
	private val startupGate = object : TrackingStartupGate {
		override val isReady: Boolean = true
		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			TrackingStartupResult.Ready(false, 0L)
	}
	private lateinit var database: AppDatabase
	private lateinit var legacy: FakeTrackingParamsRepository
	private lateinit var policy: RoomSourcePolicyRepository
	private lateinit var repository: AuthoritativeTrackingParamsRepository
	private lateinit var retention: RecordingRetentionAuthorityProducer
	private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

	@Before
	fun setUp() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		legacy = FakeTrackingParamsRepository(
			TrackingParamsState(legacySettingsMigrationCompleted = true),
		)
		policy = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime("test-boot", 10L, 20L)
		}
		retention = RecordingRetentionAuthorityProducer()
		repository = AuthoritativeTrackingParamsRepository(
			legacy,
			policy,
			applicationScope,
			startupGate,
			retention,
		)
	}

	@After
	fun tearDown() {
		database.close()
		applicationScope.cancel()
	}

	@Test
	fun `post-bootstrap legacy conflict cannot revive or disable a source`() = runTest {
		repository.data.first()[TrackingSourceComponent.LOCATION].shouldBeTrue()

		legacy.force(
			legacy.current.copy(
				locationEnabled = false,
				sourceCollectionSettings = legacy.current.sourceCollectionSettings.copy(
					location = SourceCollectionFrequency.RESPONSIVE,
				),
			),
		)

		val effective = repository.data.first()
		effective.locationEnabled.shouldBeTrue()
		effective.sourceCollectionSettings.location shouldBe SourceCollectionFrequency.BALANCED
	}

	@Test
	fun `source mutation commits policy and mirrors the effective state`() = runTest {
		repository.data.first()

		repository.setStepsEnabled(false)

		val effective = repository.data.first()
		effective.stepsEnabled.shouldBeFalse()
		effective.sourceCollectionSettings.steps shouldBe SourceCollectionFrequency.OFF
		legacy.current.stepsEnabled.shouldBeFalse()
		val active = policy.currentState() as SourcePolicyAuthorityState.Active
		active.snapshot[TrackingSourceComponent.STEPS].enabled.shouldBeFalse()
	}

	@Test
	fun `approved ambient mutations commit independent persistent consent and mirror`() = runTest {
		repository.data.first { it.sourcePolicyRevision == 1L }
		val approved = listOf(
			TrackingSourceComponent.LOCATION,
			TrackingSourceComponent.STEPS,
			TrackingSourceComponent.WIFI,
			TrackingSourceComponent.CELL,
		)

		approved.forEach { source ->
			val before = (policy.currentState() as SourcePolicyAuthorityState.Active).snapshot[source]
			setAmbient(source, true)

			val enabled = repository.data.first { state -> state.ambientEnabled(source) }
			enabled.ambientEnabled(source).shouldBeTrue()
			legacy.current.ambientEnabled(source).shouldBeTrue()
			val active = (policy.currentState() as SourcePolicyAuthorityState.Active).snapshot[source]
			active.enabled shouldBe before.enabled
			active.captureConsentEpoch shouldBe before.captureConsentEpoch
			active.controlConsentEpoch shouldBe before.controlConsentEpoch
			active.ambientConsentEpoch shouldBe 1L
			active.ambientPersistenceEligible.shouldBeTrue()

			setAmbient(source, false)

			val disabled = repository.data.first { state -> !state.ambientEnabled(source) }
			disabled.ambientEnabled(source).shouldBeFalse()
			legacy.current.ambientEnabled(source).shouldBeFalse()
			val revoked = (policy.currentState() as SourcePolicyAuthorityState.Active).snapshot[source]
			revoked.enabled shouldBe before.enabled
			revoked.captureConsentEpoch shouldBe before.captureConsentEpoch
			revoked.controlConsentEpoch shouldBe before.controlConsentEpoch
			revoked.ambientConsentEpoch shouldBe null
			revoked.ambientPersistenceEligible.shouldBeFalse()
		}

	}

	@Test
	fun `explicit ambient mutation invokes only its retention producer`() = runTest {
		repository.data.first { it.sourcePolicyRevision == 1L }

		repository.setAmbientWifiEnabled(true)

		retention.liveSources shouldBe listOf(TrackingSourceComponent.WIFI)
		retention.fullReconciliations shouldBe 0
	}

	@Test
	fun `closed startup gate rejects policy mutation without changing durable authority`() = runTest {
		repository.data.first { it.sourcePolicyRevision == 1L }
		val closedGate = object : TrackingStartupGate {
			override val isReady: Boolean = false
			override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
				TrackingStartupResult.RetryableFailure(
					TrackingStartupStage.LEGACY_V27,
					"TEST_GATE_CLOSED",
				)
		}
		val isolated = AuthoritativeTrackingParamsRepository(
			legacy,
			policy,
			applicationScope,
			closedGate,
		)
		val before = (policy.currentState() as SourcePolicyAuthorityState.Active).snapshot

		shouldThrow<IllegalStateException> { isolated.setStepsEnabled(false) }

		(policy.currentState() as SourcePolicyAuthorityState.Active).snapshot shouldBe before
		legacy.current.stepsEnabled.shouldBeTrue()
	}

	@Test
	fun `bulk boolean-only update can re-enable a source without a conflicting frequency`() = runTest {
		repository.data.first()
		repository.setWifiEnabled(false)

		repository.update { copy(wifiEnabled = true) }

		val effective = repository.data.first()
		effective.wifiEnabled.shouldBeTrue()
		effective.sourceCollectionSettings.wifi shouldBe SourceCollectionFrequency.BALANCED
		val active = policy.currentState() as SourcePolicyAuthorityState.Active
		active.snapshot[TrackingSourceComponent.WIFI].enabled.shouldBeTrue()
	}

	@Test
	fun `long lived collector bootstraps when legacy settings become verified`() = runTest {
		val delayedLegacy = FakeTrackingParamsRepository(TrackingParamsState())
		val delayedRepository = AuthoritativeTrackingParamsRepository(
			delayedLegacy,
			policy,
			applicationScope,
			startupGate,
		)
		val activeProjection = async {
			delayedRepository.data.first { it.sourcePolicyRevision != null }
		}

		delayedLegacy.force(TrackingParamsState(legacySettingsMigrationCompleted = true))

		activeProjection.await().sourcePolicyRevision shouldBe 1L
		(policy.currentState() as SourcePolicyAuthorityState.Active).snapshot.revision shouldBe 1L
	}

	@Test
	fun `bootstrap storage failure emits fail closed and retries in application scope`() = runTest {
		val context = ApplicationProvider.getApplicationContext<Application>()
		val secondaryDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		try {
			val roomPolicy = RoomSourcePolicyRepository(secondaryDatabase) {
				SourcePolicyEffectiveTime("test-boot", 30L, 40L)
			}
			val flakyPolicy = FailingBootstrapPolicyRepository(roomPolicy)
			val failClosedRepository = AuthoritativeTrackingParamsRepository(
				FakeTrackingParamsRepository(TrackingParamsState(legacySettingsMigrationCompleted = true)),
				flakyPolicy,
				applicationScope,
				startupGate,
			)

			val unavailable = failClosedRepository.data.first()
			unavailable.sourcePolicyRevision shouldBe null
			unavailable.locationEnabled.shouldBeFalse()
			flakyPolicy.failBootstrap = false
			advanceTimeBy(251)
			runCurrent()

			failClosedRepository.data.first { it.sourcePolicyRevision == 1L }.locationEnabled.shouldBeTrue()
		} finally {
			secondaryDatabase.close()
		}
	}

	@Test
	fun `authoritative revision is published before a blocked compatibility mirror`() = runTest {
		repository.data.first { it.sourcePolicyRevision == 1L }
		val blockingLegacy = BlockingUpdateTrackingParamsRepository(legacy)
		val isolated = AuthoritativeTrackingParamsRepository(
			blockingLegacy,
			policy,
			applicationScope,
			startupGate,
		)
		isolated.data.first { it.sourcePolicyRevision == 1L }

		val mutation = async {
			isolated.update {
				copy(
					stepsEnabled = false,
					sourceCollectionSettings = sourceCollectionSettings.copy(
						steps = SourceCollectionFrequency.OFF,
					),
				)
			}
		}
		blockingLegacy.updateEntered.await()

		val effective = isolated.data.first { it.sourcePolicyRevision == 2L }
		effective.stepsEnabled.shouldBeFalse()
		blockingLegacy.releaseUpdate.complete(Unit)
		mutation.await()
	}

	@Test
	fun `upstream failure replaces an enabled replay with fail closed state`() = runTest {
		repository.data.first { it.sourcePolicyRevision == 1L }
		val failingLegacy = OneShotFailingTrackingParamsRepository(legacy)
		val isolated = AuthoritativeTrackingParamsRepository(
			failingLegacy,
			policy,
			applicationScope,
			startupGate,
		)
		isolated.data.first { it.sourcePolicyRevision == 1L }.locationEnabled.shouldBeTrue()

		failingLegacy.fail.complete(Unit)
		runCurrent()

		val unavailable = isolated.data.first()
		unavailable.sourcePolicyRevision shouldBe null
		unavailable.locationEnabled.shouldBeFalse()
	}

	private operator fun TrackingParamsState.get(source: TrackingSourceComponent): Boolean = when (source) {
		TrackingSourceComponent.LOCATION -> locationEnabled
		TrackingSourceComponent.ACTIVITY -> activityEnabled
		TrackingSourceComponent.STEPS -> stepsEnabled
		TrackingSourceComponent.PRESSURE -> barometerEnabled
		TrackingSourceComponent.WIFI -> wifiEnabled
		TrackingSourceComponent.CELL -> cellEnabled
	}

	private suspend fun setAmbient(source: TrackingSourceComponent, enabled: Boolean) = when (source) {
		TrackingSourceComponent.LOCATION -> repository.setAmbientLocationEnabled(enabled)
		TrackingSourceComponent.STEPS -> repository.setAmbientStepsEnabled(enabled)
		TrackingSourceComponent.WIFI -> repository.setAmbientWifiEnabled(enabled)
		TrackingSourceComponent.CELL -> repository.setAmbientCellEnabled(enabled)
		TrackingSourceComponent.ACTIVITY,
		TrackingSourceComponent.PRESSURE,
		-> error("$source is not an approved ambient source")
	}

	private fun TrackingParamsState.ambientEnabled(source: TrackingSourceComponent): Boolean = when (source) {
		TrackingSourceComponent.LOCATION -> ambientLocationEnabled
		TrackingSourceComponent.STEPS -> ambientStepsEnabled
		TrackingSourceComponent.WIFI -> ambientWifiEnabled
		TrackingSourceComponent.CELL -> ambientCellEnabled
		TrackingSourceComponent.ACTIVITY,
		TrackingSourceComponent.PRESSURE,
		-> false
	}
}

private class FailingBootstrapPolicyRepository(
	private val delegate: SourcePolicyRepository,
) : SourcePolicyRepository by delegate {
	@Volatile var failBootstrap: Boolean = true

	override suspend fun bootstrapFromLegacy(settings: TrackingParamsState): SourcePolicySnapshot {
		if (failBootstrap) throw java.io.IOException("injected bootstrap storage failure")
		return delegate.bootstrapFromLegacy(settings)
	}
}

private class FakeTrackingParamsRepository(initial: TrackingParamsState) : TrackingParamsRepository {
	private val mutable = MutableStateFlow(initial)
	override val data: Flow<TrackingParamsState> = mutable
	val current: TrackingParamsState get() = mutable.value

	fun force(state: TrackingParamsState) {
		mutable.value = state
	}

	override suspend fun update(block: TrackingParamsState.() -> TrackingParamsState) {
		mutable.value = mutable.value.block()
	}

	override suspend fun setLocationEnabled(enabled: Boolean) = update { copy(locationEnabled = enabled) }
	override suspend fun setActivityEnabled(enabled: Boolean) = update { copy(activityEnabled = enabled) }
	override suspend fun setStepsEnabled(enabled: Boolean) = update { copy(stepsEnabled = enabled) }
	override suspend fun setWifiEnabled(enabled: Boolean) = update { copy(wifiEnabled = enabled) }
	override suspend fun setCellEnabled(enabled: Boolean) = update { copy(cellEnabled = enabled) }
	override suspend fun setBarometerEnabled(enabled: Boolean) = update { copy(barometerEnabled = enabled) }
	override suspend fun setTransitionDetectionEnabled(enabled: Boolean) = update {
		copy(transitionDetectionEnabled = enabled)
	}
	override suspend fun setNotificationStyled(enabled: Boolean) = update { copy(notificationStyled = enabled) }
	override suspend fun setMinDistanceMeters(meters: Int) = update { copy(minDistanceMeters = meters) }
	override suspend fun setMinTimeSeconds(seconds: Int) = update { copy(minTimeSeconds = seconds) }
	override suspend fun setRequiredAccuracyMeters(meters: Int) = update { copy(requiredAccuracyMeters = meters) }
	override suspend fun setPreset(preset: TrackingPreset) = update { copy(presetName = preset.name) }
}

private class BlockingUpdateTrackingParamsRepository(
	private val delegate: TrackingParamsRepository,
) : TrackingParamsRepository by delegate {
	val updateEntered = CompletableDeferred<Unit>()
	val releaseUpdate = CompletableDeferred<Unit>()

	override suspend fun update(block: TrackingParamsState.() -> TrackingParamsState) {
		updateEntered.complete(Unit)
		releaseUpdate.await()
		delegate.update(block)
	}
}

private class OneShotFailingTrackingParamsRepository(
	private val delegate: TrackingParamsRepository,
) : TrackingParamsRepository by delegate {
	val fail = CompletableDeferred<Unit>()
	private val collectionCount = AtomicInteger()

	override val data: Flow<TrackingParamsState> = flow {
		if (collectionCount.incrementAndGet() == 1) {
			emit(delegate.data.first())
			fail.await()
			throw IOException("injected legacy observation failure")
		}
		awaitCancellation()
	}
}

private class RecordingRetentionAuthorityProducer : RetentionAuthorityProducer {
	val liveSources = mutableListOf<TrackingSourceComponent>()
	var fullReconciliations = 0

	override suspend fun reconcileCurrentSettings(): List<RetentionAuthorityResult> {
		fullReconciliations++
		return emptyList()
	}

	override suspend fun preparePendingConfiguration(
		expectedConfigurationGeneration: Long,
	): RetentionConfigurationApprovalResult =
		RetentionConfigurationApprovalResult.Unavailable(
			com.adsamcik.tracker.shared.preferences.retention
				.RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
		)

	override suspend fun reconcilePendingConfiguration(
		expectedConfigurationGeneration: Long?,
	): RetentionConfigurationApprovalResult =
		RetentionConfigurationApprovalResult.Unavailable(
			com.adsamcik.tracker.shared.preferences.retention
				.RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
		)

	override suspend fun reconcileLiveAmbient(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult {
		liveSources += source
		return active(source, RetentionAuthorityScope.LIVE_AMBIENT)
	}

	override suspend fun approvePortableImport(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult = active(source, RetentionAuthorityScope.PORTABLE_IMPORT)

	override suspend fun revokePortableImport(
		source: TrackingSourceComponent,
	): RetentionAuthorityResult = RetentionAuthorityResult.Unchanged(
		source,
		RetentionAuthorityScope.PORTABLE_IMPORT,
		RetentionAuthorityState.REVOKED,
		null,
	)

	override suspend fun reconcilePassiveLocationRetention(): RetentionAuthorityResult =
		active(TrackingSourceComponent.LOCATION, RetentionAuthorityScope.LIVE_AMBIENT)

	override suspend fun currentLiveAmbient(
		source: TrackingSourceComponent,
		expectedSourcePolicyRevision: Long,
		expectedAmbientConsentEpoch: Long,
		expectedCollectedDataEpoch: Long,
	): CurrentRetentionAuthority = CurrentRetentionAuthority.Approved(
		"test-policy",
		1L,
		"test-boot",
		0L,
		0L,
	)

	private fun active(
		source: TrackingSourceComponent,
		scope: RetentionAuthorityScope,
	) = RetentionAuthorityResult.Unchanged(
		source,
		scope,
		RetentionAuthorityState.ACTIVE,
		1L,
	)
}
