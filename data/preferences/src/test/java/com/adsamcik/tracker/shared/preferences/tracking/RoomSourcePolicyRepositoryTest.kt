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
	fun `explicit ambient Steps bootstrap grants only persistent ambient consent`() = runTest {
		val snapshot = repository.bootstrapFromLegacy(
			readySettings().copy(
				stepsEnabled = false,
				ambientStepsEnabled = true,
				sourceCollectionSettings = readySettings().sourceCollectionSettings.copy(
					steps = SourceCollectionFrequency.OFF,
				),
			),
		)

		val steps = snapshot[TrackingSourceComponent.STEPS]
		steps.enabled.shouldBeFalse()
		steps.captureConsentEpoch shouldBe null
		steps.ambientConsentEpoch shouldBe 1L
		steps.ambientPersistenceEligible.shouldBeTrue()
		snapshot.policies.values
			.filter { it.source != TrackingSourceComponent.STEPS }
			.forEach { policy ->
				policy.ambientConsentEpoch shouldBe null
				policy.ambientPersistenceEligible.shouldBeFalse()
			}
		database.sourcePolicyDao()
			.consentHistory(
				TrackingSourceComponent.STEPS.stableCode,
				SourcePurpose.AMBIENT_PRODUCT.stableName,
			)
			.map { it.epoch to it.eligible } shouldBe listOf(0L to false, 1L to true)
	}

	@Test
	fun `approved ambient bootstrap grants only each requested ambient purpose`() = runTest {
		val snapshot = repository.bootstrapFromLegacy(
			readySettings().copy(
				ambientLocationEnabled = true,
				ambientStepsEnabled = true,
				ambientWifiEnabled = true,
				ambientCellEnabled = true,
			),
		)

		val approved = setOf(
			TrackingSourceComponent.LOCATION,
			TrackingSourceComponent.STEPS,
			TrackingSourceComponent.WIFI,
			TrackingSourceComponent.CELL,
		)
		snapshot.policies.values.forEach { policy ->
			policy.ambientConsentEpoch shouldBe 1L.takeIf { policy.source in approved }
			policy.ambientPersistenceEligible shouldBe (policy.source in approved)
		}
	}

	@Test
	fun `approved ambient grants and revokes rotate only their own consent epochs`() = runTest {
		var snapshot = repository.bootstrapFromLegacy(readySettings())
		val approved = listOf(
			TrackingSourceComponent.LOCATION,
			TrackingSourceComponent.STEPS,
			TrackingSourceComponent.WIFI,
			TrackingSourceComponent.CELL,
		)

		approved.forEach { source ->
			val before = snapshot[source]
			val granted = repository.setNonCaptureConsent(
				expectedPolicyRevision = snapshot.revision,
				source = source,
				purpose = SourcePurpose.AMBIENT_PRODUCT,
				eligible = true,
				persistenceEligible = true,
				reason = "TEST_AMBIENT_GRANT",
			)
			val grantedSource = granted[source]
			grantedSource.enabled shouldBe before.enabled
			grantedSource.captureConsentEpoch shouldBe before.captureConsentEpoch
			grantedSource.controlConsentEpoch shouldBe before.controlConsentEpoch
			grantedSource.ambientConsentEpoch shouldBe 1L

			val revoked = repository.setNonCaptureConsent(
				expectedPolicyRevision = granted.revision,
				source = source,
				purpose = SourcePurpose.AMBIENT_PRODUCT,
				eligible = false,
				persistenceEligible = false,
				reason = "TEST_AMBIENT_REVOKE",
			)
			val revokedSource = revoked[source]
			revokedSource.enabled shouldBe before.enabled
			revokedSource.captureConsentEpoch shouldBe before.captureConsentEpoch
			revokedSource.controlConsentEpoch shouldBe before.controlConsentEpoch
			revokedSource.ambientConsentEpoch shouldBe null
			database.sourcePolicyDao()
				.consentHistory(source.stableCode, SourcePurpose.AMBIENT_PRODUCT.stableName)
				.map { it.epoch to it.eligible } shouldBe listOf(
				0L to false,
				1L to true,
				2L to false,
			)
			snapshot = revoked
		}
	}

	@Test
	fun `ambient Steps revoke and regrant rotate only ambient consent`() = runTest {
		val first = repository.bootstrapFromLegacy(readySettings())
		val granted = repository.replaceCaptureSettings(
			expectedPolicyRevision = first.revision,
			settings = readySettings().copy(ambientStepsEnabled = true),
			reason = "TEST_AMBIENT_STEPS_GRANT",
		)
		val revoked = repository.replaceCaptureSettings(
			expectedPolicyRevision = granted.revision,
			settings = readySettings().copy(ambientStepsEnabled = false),
			reason = "TEST_AMBIENT_STEPS_REVOKE",
		)
		val regranted = repository.replaceCaptureSettings(
			expectedPolicyRevision = revoked.revision,
			settings = readySettings().copy(ambientStepsEnabled = true),
			reason = "TEST_AMBIENT_STEPS_REGRANT",
		)

		val originalCaptureEpoch = first[TrackingSourceComponent.STEPS].captureConsentEpoch
		granted[TrackingSourceComponent.STEPS].ambientConsentEpoch shouldBe 1L
		granted[TrackingSourceComponent.STEPS].captureConsentEpoch shouldBe originalCaptureEpoch
		revoked[TrackingSourceComponent.STEPS].ambientConsentEpoch shouldBe null
		revoked[TrackingSourceComponent.STEPS].ambientPersistenceEligible.shouldBeFalse()
		revoked[TrackingSourceComponent.STEPS].captureConsentEpoch shouldBe originalCaptureEpoch
		regranted[TrackingSourceComponent.STEPS].ambientConsentEpoch shouldBe 3L
		regranted[TrackingSourceComponent.STEPS].captureConsentEpoch shouldBe originalCaptureEpoch
		database.sourcePolicyDao()
			.consentHistory(
				TrackingSourceComponent.STEPS.stableCode,
				SourcePurpose.AMBIENT_PRODUCT.stableName,
			)
			.map { it.epoch to it.eligible } shouldBe listOf(
				0L to false,
				1L to true,
				2L to false,
				3L to true,
			)
	}

	@Test
	fun `ambient Steps revoke atomically retires demand and denies its live registration`() = runTest {
		val first = repository.bootstrapFromLegacy(readySettings())
		val granted = repository.replaceCaptureSettings(
			expectedPolicyRevision = first.revision,
			settings = readySettings().copy(ambientStepsEnabled = true),
			reason = "TEST_AMBIENT_STEPS_GRANT",
		)
		installLiveAuthority(
			snapshot = granted,
			source = TrackingSourceComponent.STEPS,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
		elapsedNanos = 400L
		wallTimeMs = 4_000L

		val revoked = repository.replaceCaptureSettings(
			expectedPolicyRevision = granted.revision,
			settings = readySettings(),
			reason = "TEST_AMBIENT_STEPS_FENCE",
		)

		val demand = database.sourceBrokerDao().demandHistory(TEST_AMBIENT_CONSUMER_ID).single()
		demand.status shouldBe SourceDemandEntity.STATUS_RETIRING
		demand.retireBootId shouldBe TEST_BOOT_ID
		demand.retireElapsedRealtimeNanos shouldBe 400L
		revoked[TrackingSourceComponent.STEPS].enabled.shouldBeTrue()
		revoked[TrackingSourceComponent.STEPS].captureConsentEpoch shouldBe
			first[TrackingSourceComponent.STEPS].captureConsentEpoch
		database.sourceBrokerDao().authorizationAt(
			TrackingSourceComponent.STEPS.stableCode,
			TEST_REGISTRATION_GENERATION,
			TEST_BOOT_ID,
			399L,
		).toAuthorizationSnapshotOrNull()?.authorizedMembers?.size shouldBe 1
		database.sourceBrokerDao().authorizationAt(
			TrackingSourceComponent.STEPS.stableCode,
			TEST_REGISTRATION_GENERATION,
			TEST_BOOT_ID,
			400L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
	}

	@Test
	fun `ambient Wi-Fi revoke retires only ambient authority without capture loss`() = runTest {
		val first = repository.bootstrapFromLegacy(readySettings())
		val granted = repository.setNonCaptureConsent(
			expectedPolicyRevision = first.revision,
			source = TrackingSourceComponent.WIFI,
			purpose = SourcePurpose.AMBIENT_PRODUCT,
			eligible = true,
			persistenceEligible = true,
			reason = "TEST_AMBIENT_WIFI_GRANT",
		)
		installLiveAuthority(
			snapshot = granted,
			source = TrackingSourceComponent.WIFI,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
		elapsedNanos = 500L
		wallTimeMs = 5_000L

		val revoked = repository.setNonCaptureConsent(
			expectedPolicyRevision = granted.revision,
			source = TrackingSourceComponent.WIFI,
			purpose = SourcePurpose.AMBIENT_PRODUCT,
			eligible = false,
			persistenceEligible = false,
			reason = "TEST_AMBIENT_WIFI_REVOKE",
		)

		val demand = database.sourceBrokerDao().demandHistory(TEST_AMBIENT_CONSUMER_ID).single()
		demand.status shouldBe SourceDemandEntity.STATUS_RETIRING
		demand.retireElapsedRealtimeNanos shouldBe 500L
		revoked[TrackingSourceComponent.WIFI].enabled shouldBe
			first[TrackingSourceComponent.WIFI].enabled
		revoked[TrackingSourceComponent.WIFI].captureConsentEpoch shouldBe
			first[TrackingSourceComponent.WIFI].captureConsentEpoch
		database.sourceBrokerDao().authorizationAt(
			TrackingSourceComponent.WIFI.stableCode,
			TEST_REGISTRATION_GENERATION,
			TEST_BOOT_ID,
			499L,
		).toAuthorizationSnapshotOrNull()?.authorizedMembers?.size shouldBe 1
		database.sourceBrokerDao().authorizationAt(
			TrackingSourceComponent.WIFI.stableCode,
			TEST_REGISTRATION_GENERATION,
			TEST_BOOT_ID,
			500L,
		).toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
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
	fun `forbidden noncapture purpose grants and persistent control are rejected atomically`() =
		runTest {
			val initial = repository.bootstrapFromLegacy(readySettings().copy(autoTrackingMode = 0))
			installLiveCaptureAuthority(initial, TrackingSourceComponent.STEPS)
			val before = purposeMutationSnapshot()
			val invalid = buildList {
				TrackingSourceComponent.entries
					.filter { source -> source != TrackingSourceComponent.ACTIVITY }
					.forEach { source ->
						add(Triple(source, SourcePurpose.CONTROL, false))
					}
				listOf(
					TrackingSourceComponent.ACTIVITY,
					TrackingSourceComponent.PRESSURE,
				).forEach { source ->
					add(Triple(source, SourcePurpose.AMBIENT_PRODUCT, true))
				}
				add(Triple(TrackingSourceComponent.ACTIVITY, SourcePurpose.CONTROL, true))
			}

			invalid.forEach { (source, purpose, persistenceEligible) ->
				shouldThrow<IllegalArgumentException> {
					repository.setNonCaptureConsent(
						expectedPolicyRevision = initial.revision,
						source = source,
						purpose = purpose,
						eligible = true,
						persistenceEligible = persistenceEligible,
						reason = "TEST_FORBIDDEN_PURPOSE",
					)
				}
				purposeMutationSnapshot() shouldBe before
			}
		}

	@Test
	fun `bootstrap retains explicit deny epoch zero rows for forbidden purpose pairs`() = runTest {
		repository.bootstrapFromLegacy(readySettings().copy(autoTrackingMode = 0))
		val forbidden = buildList {
			TrackingSourceComponent.entries
				.filter { source -> source != TrackingSourceComponent.ACTIVITY }
				.forEach { source -> add(source to SourcePurpose.CONTROL) }
			add(TrackingSourceComponent.ACTIVITY to SourcePurpose.AMBIENT_PRODUCT)
			add(TrackingSourceComponent.PRESSURE to SourcePurpose.AMBIENT_PRODUCT)
		}

		forbidden.forEach { (source, purpose) ->
			database.sourcePolicyDao().consentHistory(source.stableCode, purpose.stableName)
				.map { row -> Triple(row.epoch, row.eligible, row.persistenceEligible) } shouldBe
				listOf(Triple(0L, false, false))
		}
	}

	@Test
	fun `approved identical ambient grant is idempotent`() = runTest {
		val initial = repository.bootstrapFromLegacy(readySettings())
		val granted = repository.setNonCaptureConsent(
			expectedPolicyRevision = initial.revision,
			source = TrackingSourceComponent.LOCATION,
			purpose = SourcePurpose.AMBIENT_PRODUCT,
			eligible = true,
			persistenceEligible = true,
			reason = "TEST_LOCATION_AMBIENT_GRANT",
		)
		val history = database.sourcePolicyDao().consentHistory(
			TrackingSourceComponent.LOCATION.stableCode,
			SourcePurpose.AMBIENT_PRODUCT.stableName,
		)

		val repeated = repository.setNonCaptureConsent(
			expectedPolicyRevision = granted.revision,
			source = TrackingSourceComponent.LOCATION,
			purpose = SourcePurpose.AMBIENT_PRODUCT,
			eligible = true,
			persistenceEligible = true,
			reason = "TEST_LOCATION_AMBIENT_GRANT_REPEAT",
		)

		repeated shouldBe granted
		database.sourcePolicyDao().consentHistory(
			TrackingSourceComponent.LOCATION.stableCode,
			SourcePurpose.AMBIENT_PRODUCT.stableName,
		) shouldBe history
	}

	@Test
	fun `loaded policy rejects Activity ambient eligibility`() = runTest {
		val initial = repository.bootstrapFromLegacy(readySettings())
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_policy SET ambient_consent_epoch = 1, " +
				"ambient_persistence_eligible = 1 WHERE policy_revision = ${initial.revision} " +
				"AND source_kind = ${TrackingSourceComponent.ACTIVITY.stableCode}",
		)

		repository.currentState().shouldBeInstanceOf<SourcePolicyAuthorityState.Invalid>()
	}

	@Test
	fun `loaded policy rejects non Activity control eligibility`() = runTest {
		val initial = repository.bootstrapFromLegacy(readySettings())
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_policy SET control_consent_epoch = 1 " +
				"WHERE policy_revision = ${initial.revision} " +
				"AND source_kind = ${TrackingSourceComponent.STEPS.stableCode}",
		)

		repository.currentState().shouldBeInstanceOf<SourcePolicyAuthorityState.Invalid>()
	}

	@Test
	fun `loaded policy rejects persistent Activity control`() = runTest {
		val initial = repository.bootstrapFromLegacy(readySettings())
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_policy SET control_persistence_eligible = 1 " +
				"WHERE policy_revision = ${initial.revision} " +
				"AND source_kind = ${TrackingSourceComponent.ACTIVITY.stableCode}",
		)

		repository.currentState().shouldBeInstanceOf<SourcePolicyAuthorityState.Invalid>()
	}

	@Test
	fun `Location ambient revoke retires only its ambient authority`() = runTest {
		assertAmbientRevoke(TrackingSourceComponent.LOCATION)
	}

	@Test
	fun `Cell ambient revoke retires only its ambient authority`() = runTest {
		assertAmbientRevoke(TrackingSourceComponent.CELL)
	}

	@Test
	fun `Location ambient revoke rollback preserves demand and authorization`() = runTest {
		assertAmbientRevokeRollback(TrackingSourceComponent.LOCATION)
	}

	@Test
	fun `Cell ambient revoke rollback preserves demand and authorization`() = runTest {
		assertAmbientRevokeRollback(TrackingSourceComponent.CELL)
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
	) = installLiveAuthority(snapshot, source, SourceBrokerPurpose.SESSION_CAPTURE)

	private suspend fun installLiveAuthority(
		snapshot: SourcePolicySnapshot,
		source: TrackingSourceComponent,
		purpose: String,
	) {
		val policy = snapshot[source]
		val sessionScoped = purpose == SourceBrokerPurpose.SESSION_CAPTURE
		val consentEpoch = when (purpose) {
			SourceBrokerPurpose.SESSION_CAPTURE -> policy.captureConsentEpoch
			SourceBrokerPurpose.AMBIENT_PRODUCT -> policy.ambientConsentEpoch
			else -> error("Unsupported test purpose $purpose")
		}
		val demandId = if (sessionScoped) TEST_DEMAND_ID else TEST_AMBIENT_DEMAND_ID
		val consumerId = if (sessionScoped) TEST_CONSUMER_ID else TEST_AMBIENT_CONSUMER_ID
		val demand = SourceDemandEntity(
			demandId = demandId,
			consumerId = consumerId,
			sourceKind = source.stableCode,
			purpose = purpose,
			logicalTrackingId = "tracking-1".takeIf { sessionScoped },
			serviceRunId = "run-1".takeIf { sessionScoped },
			manifestRevision = 1L.takeIf { sessionScoped },
			lifecycleLeaseGeneration = 1L.takeIf { sessionScoped },
			sourcePolicyRevision = snapshot.revision,
			consentEpoch = requireNotNull(consentEpoch),
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

	private suspend fun purposeMutationSnapshot() = PurposeMutationSnapshot(
		authority = database.sourcePolicyDao().authority(),
		policies = database.sourcePolicyDao().currentPolicies(),
		consentHistory = TrackingSourceComponent.entries.flatMap { source ->
			SourcePurpose.entries.flatMap { purpose ->
				database.sourcePolicyDao().consentHistory(source.stableCode, purpose.stableName)
			}
		},
		captureDemandHistory = database.sourceBrokerDao().demandHistory(TEST_CONSUMER_ID),
		authorizationRevisions = TrackingSourceComponent.entries.associateWith { source ->
			database.sourceBrokerDao().maximumAuthorizationRevision(source.stableCode)
		},
	)

	private suspend fun assertAmbientRevoke(source: TrackingSourceComponent) {
		val initial = repository.bootstrapFromLegacy(readySettings())
		val granted = repository.setNonCaptureConsent(
			expectedPolicyRevision = initial.revision,
			source = source,
			purpose = SourcePurpose.AMBIENT_PRODUCT,
			eligible = true,
			persistenceEligible = true,
			reason = "TEST_${source.name}_AMBIENT_GRANT",
		)
		installLiveAuthority(granted, source, SourceBrokerPurpose.AMBIENT_PRODUCT)
		val before = granted[source]
		elapsedNanos = 800L
		wallTimeMs = 8_000L

		val revoked = repository.setNonCaptureConsent(
			expectedPolicyRevision = granted.revision,
			source = source,
			purpose = SourcePurpose.AMBIENT_PRODUCT,
			eligible = false,
			persistenceEligible = false,
			reason = "TEST_${source.name}_AMBIENT_REVOKE",
		)

		val demand = database.sourceBrokerDao().demandHistory(TEST_AMBIENT_CONSUMER_ID).single()
		demand.status shouldBe SourceDemandEntity.STATUS_RETIRING
		demand.retireElapsedRealtimeNanos shouldBe 800L
		revoked[source].enabled shouldBe before.enabled
		revoked[source].captureConsentEpoch shouldBe before.captureConsentEpoch
		revoked[source].controlConsentEpoch shouldBe before.controlConsentEpoch
		revoked[source].ambientConsentEpoch shouldBe null
	}

	private suspend fun assertAmbientRevokeRollback(source: TrackingSourceComponent) {
		val initial = repository.bootstrapFromLegacy(readySettings())
		val granted = repository.setNonCaptureConsent(
			expectedPolicyRevision = initial.revision,
			source = source,
			purpose = SourcePurpose.AMBIENT_PRODUCT,
			eligible = true,
			persistenceEligible = true,
			reason = "TEST_${source.name}_AMBIENT_GRANT",
		)
		installLiveAuthority(granted, source, SourceBrokerPurpose.AMBIENT_PRODUCT)
		val beforeDemand = database.sourceBrokerDao()
			.demandHistory(TEST_AMBIENT_CONSUMER_ID)
			.single()
		val beforeAuthorization = database.sourceBrokerDao().latestAuthorization(
			source.stableCode,
			TEST_REGISTRATION_GENERATION,
		)
		database.openHelper.writableDatabase.execSQL(
			"CREATE TRIGGER fail_ambient_policy_insert BEFORE INSERT ON source_policy " +
				"WHEN NEW.policy_revision = ${granted.revision + 1L} " +
				"BEGIN SELECT RAISE(ABORT, 'ambient policy write fault'); END",
		)

		try {
			shouldThrow<SQLiteException> {
				repository.setNonCaptureConsent(
					expectedPolicyRevision = granted.revision,
					source = source,
					purpose = SourcePurpose.AMBIENT_PRODUCT,
					eligible = false,
					persistenceEligible = false,
					reason = "TEST_${source.name}_AMBIENT_REVOKE_ROLLBACK",
				)
			}

			database.sourceBrokerDao()
				.demandHistory(TEST_AMBIENT_CONSUMER_ID)
				.single() shouldBe beforeDemand
			database.sourceBrokerDao().latestAuthorization(
				source.stableCode,
				TEST_REGISTRATION_GENERATION,
			) shouldBe beforeAuthorization
			database.sourcePolicyDao().authority()?.currentPolicyRevision shouldBe granted.revision
			repository.currentState() shouldBe SourcePolicyAuthorityState.Active(granted)
		} finally {
			database.openHelper.writableDatabase.execSQL(
				"DROP TRIGGER fail_ambient_policy_insert",
			)
		}
	}

	private companion object {
		const val TEST_BOOT_ID = "boot-7"
		const val TEST_DEMAND_ID = "capture-steps"
		const val TEST_CONSUMER_ID = "session:tracking-1"
		const val TEST_AMBIENT_DEMAND_ID = "ambient-steps"
		const val TEST_AMBIENT_CONSUMER_ID = "ambient:steps"
		const val TEST_REGISTRATION_GENERATION = 1L
	}

	private data class PurposeMutationSnapshot(
		val authority: com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity?,
		val policies: List<com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity>,
		val consentHistory:
			List<com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity>,
		val captureDemandHistory: List<SourceDemandEntity>,
		val authorizationRevisions: Map<TrackingSourceComponent, Long>,
	)
}
