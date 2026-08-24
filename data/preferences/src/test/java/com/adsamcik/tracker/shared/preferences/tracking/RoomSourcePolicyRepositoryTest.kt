package com.adsamcik.tracker.shared.preferences.tracking

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
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
class RoomSourcePolicyRepositoryTest {
	private lateinit var database: AppDatabase
	private lateinit var repository: RoomSourcePolicyRepository
	private var elapsedNanos = 100L
	private var wallTimeMs = 1_000L

	@Before
	fun setUp() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		repository = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime(
				bootId = "boot-7",
				elapsedRealtimeNanos = elapsedNanos++,
				wallTimeMs = wallTimeMs++,
			)
		}
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `unverified legacy defaults cannot activate policy`() = runTest {
		shouldThrow<LegacySourceSettingsUnavailableException> {
			repository.bootstrapFromLegacy(TrackingParamsState())
		}

		repository.currentState() shouldBe SourcePolicyAuthorityState.Uninitialized
		database.sourcePolicyDao().currentPolicies() shouldHaveSize 0
	}

	@Test
	fun `bootstrap intersects boolean and frequency and imports only existing automatic control`() = runTest {
		val snapshot = repository.bootstrapFromLegacy(
			readySettings().copy(
				locationEnabled = false,
				wifiEnabled = true,
				sourceCollectionSettings = SourceCollectionSettings(
					location = SourceCollectionFrequency.RESPONSIVE,
					activity = SourceCollectionFrequency.BALANCED,
					steps = SourceCollectionFrequency.BALANCED,
					pressure = SourceCollectionFrequency.BALANCED,
					wifi = SourceCollectionFrequency.OFF,
					cell = SourceCollectionFrequency.OFF,
				),
			),
		)

		snapshot.policies.size shouldBe 6
		snapshot[TrackingSourceComponent.LOCATION].enabled.shouldBeFalse()
		snapshot[TrackingSourceComponent.LOCATION].qos shouldBe SourceQos.OFF
		snapshot[TrackingSourceComponent.WIFI].enabled.shouldBeFalse()
		snapshot[TrackingSourceComponent.ACTIVITY].enabled.shouldBeTrue()
		snapshot.policies.values.forEach { policy ->
			policy.controlConsentEpoch shouldBe 1L.takeIf {
				policy.source == TrackingSourceComponent.ACTIVITY
			}
			policy.ambientConsentEpoch shouldBe null
			policy.controlPersistenceEligible.shouldBeFalse()
			policy.ambientPersistenceEligible.shouldBeFalse()
		}
	}

	@Test
	fun `disabled legacy automatic mode does not grant control`() = runTest {
		val snapshot = repository.bootstrapFromLegacy(readySettings().copy(autoTrackingMode = 0))

		snapshot.policies.values.forEach { policy ->
			policy.controlConsentEpoch shouldBe null
			policy.controlPersistenceEligible.shouldBeFalse()
		}
	}

	@Test
	fun `capture revoke and regrant append epochs without mutating old policy`() = runTest {
		val first = repository.bootstrapFromLegacy(readySettings())
		val revoked = repository.replaceCaptureSettings(
			expectedPolicyRevision = first.revision,
			settings = readySettings().copy(
				stepsEnabled = false,
				sourceCollectionSettings = readySettings().sourceCollectionSettings.copy(
					steps = SourceCollectionFrequency.RESPONSIVE,
				),
			),
			reason = "TEST_REVOKE",
		)
		val regranted = repository.replaceCaptureSettings(
			expectedPolicyRevision = revoked.revision,
			settings = readySettings().copy(
				sourceCollectionSettings = readySettings().sourceCollectionSettings.copy(
					steps = SourceCollectionFrequency.RESPONSIVE,
				),
			),
			reason = "TEST_REGRANT",
		)

		first[TrackingSourceComponent.STEPS].captureConsentEpoch shouldBe 1L
		revoked[TrackingSourceComponent.STEPS].captureConsentEpoch shouldBe null
		regranted[TrackingSourceComponent.STEPS].captureConsentEpoch shouldBe 3L
		database.sourcePolicyDao()
			.consentHistory(TrackingSourceComponent.STEPS.stableCode, SourcePurpose.SESSION_CAPTURE.stableName)
			.map { it.epoch to it.eligible } shouldBe listOf(
			0L to false,
			1L to true,
			2L to false,
			3L to true,
		)
		database.sourcePolicyDao().policiesAtRevision(first.revision)
			.single { it.sourceKind == TrackingSourceComponent.STEPS.stableCode }
			.enabled.shouldBeTrue()
	}

	@Test
	fun `capture revoke atomically retires demand and denies the live registration at its boundary`() =
		runTest {
			val first = repository.bootstrapFromLegacy(readySettings())
			installLiveCaptureAuthority(first, TrackingSourceComponent.STEPS)
			elapsedNanos = 200L
			wallTimeMs = 2_000L

			repository.replaceCaptureSettings(
				expectedPolicyRevision = first.revision,
				settings = settingsWithoutSteps(),
				reason = "TEST_CAPTURE_FENCE",
			)

			val demand = database.sourceBrokerDao().demandHistory(TEST_CONSUMER_ID).single()
			demand.status shouldBe SourceDemandEntity.STATUS_RETIRING
			demand.retireBootId shouldBe TEST_BOOT_ID
			demand.retireElapsedRealtimeNanos shouldBe 200L
			database.sourceBrokerDao().authorizationAt(
				TrackingSourceComponent.STEPS.stableCode,
				TEST_REGISTRATION_GENERATION,
				TEST_BOOT_ID,
				199L,
			).toAuthorizationSnapshotOrNull()?.authorizedMembers?.size shouldBe 1
			database.sourceBrokerDao().authorizationAt(
				TrackingSourceComponent.STEPS.stableCode,
				TEST_REGISTRATION_GENERATION,
				TEST_BOOT_ID,
				200L,
			).toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
		}

	@Test
	fun `policy write failure rolls back demand retirement and deny authorization`() = runTest {
		val first = repository.bootstrapFromLegacy(readySettings())
		installLiveCaptureAuthority(first, TrackingSourceComponent.STEPS)
		elapsedNanos = 300L
		wallTimeMs = 3_000L
		database.openHelper.writableDatabase.execSQL(
			"CREATE TRIGGER fail_source_policy_insert BEFORE INSERT ON source_policy " +
				"WHEN NEW.policy_revision = ${first.revision + 1L} " +
				"BEGIN SELECT RAISE(ABORT, 'policy write fault'); END",
		)

		try {
			shouldThrow<SQLiteException> {
				repository.replaceCaptureSettings(
					expectedPolicyRevision = first.revision,
					settings = settingsWithoutSteps(),
					reason = "TEST_CAPTURE_FENCE_ROLLBACK",
				)
			}

			val demand = database.sourceBrokerDao().demandHistory(TEST_CONSUMER_ID).single()
			demand.status shouldBe SourceDemandEntity.STATUS_ACTIVE
			demand.retireBootId shouldBe null
			database.sourceBrokerDao().latestAuthorization(
				TrackingSourceComponent.STEPS.stableCode,
				TEST_REGISTRATION_GENERATION,
			).toAuthorizationSnapshotOrNull()?.isDenied shouldBe false
			database.sourceBrokerDao().maximumAuthorizationRevision(
				TrackingSourceComponent.STEPS.stableCode,
			) shouldBe 1L
			database.sourcePolicyDao().authority()?.currentPolicyRevision shouldBe first.revision
		} finally {
			database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_source_policy_insert")
		}
	}

	@Test
	fun `qos-only change advances policy without changing consent epoch`() = runTest {
		val first = repository.bootstrapFromLegacy(readySettings())
		val changed = repository.replaceCaptureSettings(
			expectedPolicyRevision = first.revision,
			settings = readySettings().copy(
				sourceCollectionSettings = readySettings().sourceCollectionSettings.copy(
					location = SourceCollectionFrequency.RESPONSIVE,
				),
			),
			reason = "TEST_QOS",
		)

		changed.revision shouldBe first.revision + 1L
		changed[TrackingSourceComponent.LOCATION].qos shouldBe SourceQos.RESPONSIVE
		changed[TrackingSourceComponent.LOCATION].captureConsentEpoch shouldBe
			first[TrackingSourceComponent.LOCATION].captureConsentEpoch
		database.sourcePolicyDao()
			.consentHistory(TrackingSourceComponent.LOCATION.stableCode, SourcePurpose.SESSION_CAPTURE.stableName)
			.map { it.epoch } shouldBe listOf(0L, 1L)
	}

	@Test
	fun `stale expected revision cannot overwrite a newer revocation`() = runTest {
		val first = repository.bootstrapFromLegacy(readySettings())
		repository.replaceCaptureSettings(
			expectedPolicyRevision = first.revision,
			settings = readySettings().copy(
				activityEnabled = false,
				sourceCollectionSettings = readySettings().sourceCollectionSettings.copy(
					activity = SourceCollectionFrequency.OFF,
				),
			),
			reason = "NEWER_REVOKE",
		)

		shouldThrow<ConcurrentSourcePolicyMutationException> {
			repository.replaceCaptureSettings(
				expectedPolicyRevision = first.revision,
				settings = readySettings(),
				reason = "STALE_REGRANT",
			)
		}
	}

	@Test
	fun `control epoch is independent from captured activity`() = runTest {
		val first = repository.bootstrapFromLegacy(readySettings().copy(autoTrackingMode = 0))
		val granted = repository.setNonCaptureConsent(
			expectedPolicyRevision = first.revision,
			source = TrackingSourceComponent.ACTIVITY,
			purpose = SourcePurpose.CONTROL,
			eligible = true,
			persistenceEligible = false,
			reason = "TEST_CONTROL",
		)

		granted[TrackingSourceComponent.ACTIVITY].enabled.shouldBeTrue()
		granted[TrackingSourceComponent.ACTIVITY].captureConsentEpoch shouldBe 1L
		granted[TrackingSourceComponent.ACTIVITY].controlConsentEpoch shouldBe 1L
		granted[TrackingSourceComponent.ACTIVITY].controlPersistenceEligible.shouldBeFalse()
	}

	@Test
	fun `automatic mode changes append nonpersistent activity control epochs`() = runTest {
		val first = repository.bootstrapFromLegacy(readySettings().copy(autoTrackingMode = 0))
		val enabled = repository.replaceCaptureSettings(
			expectedPolicyRevision = first.revision,
			settings = readySettings().copy(autoTrackingMode = 2),
			reason = "TEST_AUTOMATIC_ENABLE",
		)
		val disabled = repository.replaceCaptureSettings(
			expectedPolicyRevision = enabled.revision,
			settings = readySettings().copy(autoTrackingMode = 0),
			reason = "TEST_AUTOMATIC_DISABLE",
		)

		enabled[TrackingSourceComponent.ACTIVITY].controlConsentEpoch shouldBe 1L
		enabled[TrackingSourceComponent.ACTIVITY].controlPersistenceEligible.shouldBeFalse()
		disabled[TrackingSourceComponent.ACTIVITY].controlConsentEpoch shouldBe null
		disabled[TrackingSourceComponent.ACTIVITY].captureConsentEpoch shouldBe
			first[TrackingSourceComponent.ACTIVITY].captureConsentEpoch
		database.sourcePolicyDao()
			.consentHistory(TrackingSourceComponent.ACTIVITY.stableCode, SourcePurpose.CONTROL.stableName)
			.map { it.epoch to it.eligible } shouldBe listOf(
			0L to false,
			1L to true,
			2L to false,
		)
	}

	@Test
	fun `automatic control semantics advance policy revision without inventing a consent change`() = runTest {
		val first = repository.bootstrapFromLegacy(readySettings().copy(autoTrackingMode = 1))
		val consentBefore = database.sourcePolicyDao()
			.consentHistory(TrackingSourceComponent.ACTIVITY.stableCode, SourcePurpose.CONTROL.stableName)

		val changed = repository.replaceCaptureSettings(
			expectedPolicyRevision = first.revision,
			settings = readySettings().copy(
				autoTrackingMode = 2,
				transitionDetectionEnabled = false,
			),
			reason = "TEST_AUTOMATIC_SEMANTICS",
		)

		changed.revision shouldBe first.revision + 1L
		changed[TrackingSourceComponent.ACTIVITY].controlConsentEpoch shouldBe
			first[TrackingSourceComponent.ACTIVITY].controlConsentEpoch
		database.sourcePolicyDao()
			.consentHistory(TrackingSourceComponent.ACTIVITY.stableCode, SourcePurpose.CONTROL.stableName)
			.shouldBe(consentBefore)
	}

	@Test
	fun `missing referenced consent epoch invalidates the active authority`() = runTest {
		repository.bootstrapFromLegacy(readySettings())
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_consent_epoch " +
				"WHERE source_kind = ${TrackingSourceComponent.STEPS.stableCode} " +
				"AND purpose = '${SourcePurpose.SESSION_CAPTURE.stableName}' AND epoch = 1",
		)

		repository.currentState().shouldBeInstanceOf<SourcePolicyAuthorityState.Invalid>()
	}

	private fun readySettings(): TrackingParamsState = TrackingParamsState(
		legacySettingsMigrationCompleted = true,
	)

	private fun settingsWithoutSteps(): TrackingParamsState = readySettings().copy(
		stepsEnabled = false,
		sourceCollectionSettings = readySettings().sourceCollectionSettings.copy(
			steps = SourceCollectionFrequency.OFF,
		),
	)

	private suspend fun installLiveCaptureAuthority(
		snapshot: SourcePolicySnapshot,
		source: TrackingSourceComponent,
	) {
		val policy = snapshot[source]
		val demand = SourceDemandEntity(
			demandId = TEST_DEMAND_ID,
			consumerId = TEST_CONSUMER_ID,
			sourceKind = source.stableCode,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = "tracking-1",
			serviceRunId = "run-1",
			manifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			sourcePolicyRevision = snapshot.revision,
			consentEpoch = requireNotNull(policy.captureConsentEpoch),
			persistenceEligible = true,
			qosCode = policy.qos.stableCode,
			maximumAgeMs = 60_000L,
			desiredLatencyMs = 60_000L,
			requestedBootId = TEST_BOOT_ID,
			requestedElapsedRealtimeNanos = 50L,
			requestedAtMs = 500L,
			status = SourceDemandEntity.STATUS_ACTIVE,
			retireBootId = null,
			retireElapsedRealtimeNanos = null,
			retiredAtMs = null,
		)
		database.sourceBrokerDao().insertDemands(listOf(demand))
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = source.stableCode,
				registrationGeneration = TEST_REGISTRATION_GENERATION,
				sourceInstanceId = "instance-1",
				ownerScope = "source-broker:${source.stableCode}",
				providerResidency = if (source == TrackingSourceComponent.ACTIVITY) {
					ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE
				} else ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = if (source == TrackingSourceComponent.ACTIVITY) {
					null
				} else "test-process",
				clockDomainId = TEST_BOOT_ID,
				physicalConfigurationFingerprint = "physical-1",
				collectedDataEpoch = 1L,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 500L,
				reservedElapsedRealtimeNanos = 50L,
				acceptedAtMs = 510L,
				acceptedElapsedRealtimeNanos = 51L,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = source.stableCode,
				registrationGeneration = TEST_REGISTRATION_GENERATION,
				authorizationRevision = 1L,
				demands = listOf(demand),
				effectiveBootId = TEST_BOOT_ID,
				effectiveElapsedRealtimeNanos = 50L,
				effectiveWallTimeMs = 500L,
			),
		)
	}

	private companion object {
		const val TEST_BOOT_ID = "boot-7"
		const val TEST_DEMAND_ID = "capture-steps"
		const val TEST_CONSUMER_ID = "session:tracking-1"
		const val TEST_REGISTRATION_GENERATION = 1L
	}
}
