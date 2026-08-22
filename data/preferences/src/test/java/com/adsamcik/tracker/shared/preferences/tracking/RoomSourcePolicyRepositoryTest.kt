package com.adsamcik.tracker.shared.preferences.tracking

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
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
}
