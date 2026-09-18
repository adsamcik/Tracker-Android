package com.adsamcik.tracker.shared.preferences.retention

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePurpose
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeFalse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RetentionAuthorityProducerRoomTest {
	private lateinit var database: AppDatabase
	private lateinit var policies: RoomSourcePolicyRepository
	private var lifecycle = CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = null)
	private var approvedPolicy: ApprovedRetentionPolicyRead =
		ApprovedRetentionPolicyRead.Unavailable(
			ApprovedRetentionPolicyUnavailableReason.NOT_APPROVED,
		)
	private var effectiveTime = 0L
	private var currentBoot = "boot-1"
	private lateinit var operationLease: RetentionAuthorityOperationLease

	@BeforeTest
	fun setUp() = runTest {
		effectiveTime = 0L
		currentBoot = "boot-1"
		operationLease = RetentionAuthorityOperationLease()
		lifecycle = CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = null)
		approvedPolicy = ApprovedRetentionPolicyRead.Unavailable(
			ApprovedRetentionPolicyUnavailableReason.NOT_APPROVED,
		)
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = lifecycle.epoch, updatedAtMs = 1L),
		)
		policies = RoomSourcePolicyRepository(database) {
			nextTime()
		}
	}

	@AfterTest
	fun tearDown() {
		database.close()
	}

	@Test
	fun `default reject and unresolved policy write no grant`() = runTest {
		bootstrap(ambientWifi = true)

		val result = producer().reconcileLiveAmbient(TrackingSourceComponent.WIFI)

		assertIs<RetentionAuthorityResult.Unavailable>(result).reason shouldBe
			RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE
		database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		) shouldBe null
	}

	@Test
	fun `portable import is separate and requires no local provider consent`() = runTest {
		bootstrap()
		approvedPolicy = approved("policy-1", revision = 1L)

		val imported = producer().approvePortableImport(TrackingSourceComponent.WIFI)
		val live = producer().reconcileLiveAmbient(TrackingSourceComponent.WIFI)

		assertIs<RetentionAuthorityResult.Applied>(imported).state shouldBe
			RetentionAuthorityState.ACTIVE
		assertIs<RetentionAuthorityResult.Unchanged>(live).state shouldBe
			RetentionAuthorityState.REVOKED
		val storedImport = requireNotNull(
			database.ambientWifiFactDao().latestRetentionAuthority(
				AmbientWifiRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
			),
		)
		storedImport.sourcePolicyRevision shouldBe null
		storedImport.ambientConsentEpoch shouldBe null
		storedImport.isActive shouldBe true
		database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		) shouldBe null
	}

	@Test
	fun `cold reconciliation preserves a current approved portable grant`() = runTest {
		bootstrap()
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		assertIs<RetentionAuthorityResult.Applied>(
			producer.approvePortableImport(TrackingSourceComponent.STEPS),
		)
		val before = requireNotNull(
			database.ambientStepsFactRevisionDao().latestRetentionAuthority(
				AmbientStepsRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
			),
		)

		producer.reconcileCurrentSettings()

		database.ambientStepsFactRevisionDao().latestRetentionAuthority(
			AmbientStepsRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
		) shouldBe before
	}

	@Test
	fun `cold reconciliation reports disabled durable ambient sources as revoked`() = runTest {
		bootstrap()

		val results = producer().reconcileCurrentSettings().filter {
			it.scope == RetentionAuthorityScope.LIVE_AMBIENT &&
				it.source in setOf(
					TrackingSourceComponent.STEPS,
					TrackingSourceComponent.WIFI,
					TrackingSourceComponent.CELL,
				)
		}

		results.size shouldBe 3
		results.all {
			it is RetentionAuthorityResult.Unchanged &&
				it.state == RetentionAuthorityState.REVOKED
		} shouldBe true
	}

	@Test
	fun `pristine database bootstraps exact lifecycle and grants Ambient Steps retention`() =
		runTest {
			lifecycle = CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = 1_234L)
			removeSourceEvidenceState()
			bootstrap(ambientSteps = true)
			approvedPolicy = approved("policy-1", revision = 1L)

			val steps = producer().reconcileCurrentSettings().single {
				it.source == TrackingSourceComponent.STEPS &&
					it.scope == RetentionAuthorityScope.LIVE_AMBIENT
			}

			assertIs<RetentionAuthorityResult.Applied>(steps).state shouldBe
				RetentionAuthorityState.ACTIVE
			requireNotNull(database.sourceEvidenceStateDao().get()).run {
				collectedDataEpoch shouldBe lifecycle.epoch
				retainedFromMs shouldBe lifecycle.retainedFromMs
			}
			requireNotNull(
				database.ambientStepsFactRevisionDao().latestRetentionAuthority(
					AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
				),
			).run {
				collectedDataEpoch shouldBe lifecycle.epoch
				retainedFromMs shouldBe lifecycle.retainedFromMs
			}
		}

	@Test
	fun `missing evidence state with collected rows fails closed as conflicting`() = runTest {
		removeSourceEvidenceState()
		database.stepIntervalDao().insert(
			StepInterval(
				startTimeMs = 10L,
				endTimeMs = 20L,
				stepCount = 3,
				sensorValueStart = 100,
				sensorValueEnd = 103,
				sensorReset = false,
				createdAt = 20L,
			),
		)
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)

		val steps = producer().reconcileCurrentSettings().single {
			it.source == TrackingSourceComponent.STEPS &&
				it.scope == RetentionAuthorityScope.LIVE_AMBIENT
		}

		assertIs<RetentionAuthorityResult.Unavailable>(steps).reason shouldBe
			RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH
		database.sourceEvidenceStateDao().get() shouldBe null
		database.ambientStepsFactRevisionDao().latestRetentionAuthority(
			AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		) shouldBe null
	}

	@Test
	fun `WAL sequence high water without rows rejects pristine bootstrap`() = runTest {
		removeSourceEvidenceState()
		database.openHelper.writableDatabase.execSQL(
			"INSERT OR REPLACE INTO sqlite_sequence(name, seq) VALUES ('source_event_wal', 7)",
		)
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)

		val steps = producer().reconcileCurrentSettings().single {
			it.source == TrackingSourceComponent.STEPS &&
				it.scope == RetentionAuthorityScope.LIVE_AMBIENT
		}

		assertIs<RetentionAuthorityResult.Unavailable>(steps).reason shouldBe
			RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH
		database.sourceEvidenceStateDao().get() shouldBe null
	}

	@Test
	fun `malformed id two evidence singleton rejects pristine bootstrap`() = runTest {
		removeSourceEvidenceState()
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO source_evidence_state " +
				"(id, revision, collected_data_epoch, retained_from_ms, " +
				"deleted_source_event_high_water_ordinal, updated_at_ms) " +
				"VALUES (2, 0, 4, NULL, 0, 1)",
		)
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)

		val steps = producer().reconcileCurrentSettings().single {
			it.source == TrackingSourceComponent.STEPS &&
				it.scope == RetentionAuthorityScope.LIVE_AMBIENT
		}

		assertIs<RetentionAuthorityResult.Unavailable>(steps).reason shouldBe
			RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH
		database.openHelper.writableDatabase.query(
			"SELECT COUNT(*) FROM source_evidence_state WHERE id = 2",
		).use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0) shouldBe 1L
		}
	}

	@Test
	fun `nondefault player profile rejects pristine bootstrap`() = runTest {
		removeSourceEvidenceState()
		database.openHelper.writableDatabase.execSQL("DELETE FROM player_profile")
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO player_profile " +
				"(id, total_xp, level, xp_into_current_level, xp_for_next_level) " +
				"VALUES (1, 1, 1, 0, 30)",
		)
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)

		val steps = producer().reconcileCurrentSettings().single {
			it.source == TrackingSourceComponent.STEPS &&
				it.scope == RetentionAuthorityScope.LIVE_AMBIENT
		}

		assertIs<RetentionAuthorityResult.Unavailable>(steps).reason shouldBe
			RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH
		database.sourceEvidenceStateDao().get() shouldBe null
	}

	@Test
	fun `exact default player profile scaffold permits pristine bootstrap`() = runTest {
		removeSourceEvidenceState()
		database.openHelper.writableDatabase.execSQL("DELETE FROM player_profile")
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO player_profile " +
				"(id, total_xp, level, xp_into_current_level, xp_for_next_level) " +
				"VALUES (1, 0, 1, 0, 30)",
		)
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)

		val steps = producer().reconcileCurrentSettings().single {
			it.source == TrackingSourceComponent.STEPS &&
				it.scope == RetentionAuthorityScope.LIVE_AMBIENT
		}

		assertIs<RetentionAuthorityResult.Applied>(steps)
		requireNotNull(database.sourceEvidenceStateDao().get())
	}

	@Test
	fun `minigame score rejects pristine bootstrap`() = runTest {
		removeSourceEvidenceState()
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO minigame_score (game_id, score, xp_awarded, played_at) " +
				"VALUES ('test', 1.0, 0, 1)",
		)
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)

		val steps = producer().reconcileCurrentSettings().single {
			it.source == TrackingSourceComponent.STEPS &&
				it.scope == RetentionAuthorityScope.LIVE_AMBIENT
		}

		assertIs<RetentionAuthorityResult.Unavailable>(steps).reason shouldBe
			RetentionAuthorityUnavailableReason.INTEGRITY_MISMATCH
		database.sourceEvidenceStateDao().get() shouldBe null
	}

	@Test
	fun `source evidence epoch mismatch cannot grant Ambient Steps retention`() = runTest {
		check(database.sourceEvidenceStateDao().updateLifecycle(3L, null, 2L) == 1)
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)

		val steps = producer().reconcileCurrentSettings().single {
			it.source == TrackingSourceComponent.STEPS &&
				it.scope == RetentionAuthorityScope.LIVE_AMBIENT
		}

		assertIs<RetentionAuthorityResult.Unavailable>(steps).reason shouldBe
			RetentionAuthorityUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED
		database.ambientStepsFactRevisionDao().latestRetentionAuthority(
			AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		) shouldBe null
	}

	@Test
	fun `concurrent retained boundary transition prevents a stale grant`() = runTest {
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val transitionEntered = CompletableDeferred<Unit>()
		val releaseTransition = CompletableDeferred<Unit>()
		val transition = async {
			operationLease.withOperation {
				lifecycle = lifecycle.copy(retainedFromMs = 1_000L)
				transitionEntered.complete(Unit)
				releaseTransition.await()
			}
		}
		transitionEntered.await()
		val reconciliation = async {
			producer().reconcileCurrentSettings().single {
				it.source == TrackingSourceComponent.STEPS &&
					it.scope == RetentionAuthorityScope.LIVE_AMBIENT
			}
		}
		runCurrent()
		reconciliation.isCompleted.shouldBeFalse()
		releaseTransition.complete(Unit)
		transition.await()

		val steps = reconciliation.await()

		assertIs<RetentionAuthorityResult.Unavailable>(steps).reason shouldBe
			RetentionAuthorityUnavailableReason.RETAINED_FROM_CHANGED
		database.ambientStepsFactRevisionDao().latestRetentionAuthority(
			AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		) shouldBe null
		database.sourceEvidenceStateDao().get()?.retainedFromMs shouldBe null
	}

	@Test
	fun `concurrent pristine lifecycle transition completes before exact bootstrap and grant`() =
		runTest {
			removeSourceEvidenceState()
			bootstrap(ambientSteps = true)
			approvedPolicy = approved("policy-1", revision = 1L)
			val transitionEntered = CompletableDeferred<Unit>()
			val releaseTransition = CompletableDeferred<Unit>()
			val transition = async {
				operationLease.withOperation {
					lifecycle = CollectedDataLifecycleSnapshot(
						epoch = lifecycle.epoch + 1L,
						retainedFromMs = 1_000L,
					)
					transitionEntered.complete(Unit)
					releaseTransition.await()
				}
			}
			transitionEntered.await()
			val reconciliation = async {
				producer().reconcileCurrentSettings().single {
					it.source == TrackingSourceComponent.STEPS &&
						it.scope == RetentionAuthorityScope.LIVE_AMBIENT
				}
			}
			runCurrent()
			reconciliation.isCompleted.shouldBeFalse()
			releaseTransition.complete(Unit)
			transition.await()

			assertIs<RetentionAuthorityResult.Applied>(reconciliation.await())
			requireNotNull(database.sourceEvidenceStateDao().get()).run {
				collectedDataEpoch shouldBe lifecycle.epoch
				retainedFromMs shouldBe lifecycle.retainedFromMs
			}
			requireNotNull(
				database.ambientStepsFactRevisionDao().latestRetentionAuthority(
					AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
				),
			).run {
				collectedDataEpoch shouldBe lifecycle.epoch
				retainedFromMs shouldBe lifecycle.retainedFromMs
			}
		}

	@Test
	fun `retained boundary is authenticated by current authority reads`() = runTest {
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		val snapshot = (policies.currentState() as SourcePolicyAuthorityState.Active).snapshot
		val consentEpoch = requireNotNull(
			snapshot[TrackingSourceComponent.STEPS].ambientConsentEpoch,
		)
		producer.reconcileLiveAmbient(TrackingSourceComponent.STEPS)
		lifecycle = lifecycle.copy(retainedFromMs = 1_000L)
		database.sourceEvidenceStateDao().updateLifecycle(
			lifecycle.epoch,
			lifecycle.retainedFromMs,
			100L,
		)

		producer.currentLiveAmbient(
			TrackingSourceComponent.STEPS,
			snapshot.revision,
			consentEpoch,
			lifecycle.epoch,
			lifecycle.retainedFromMs,
		) shouldBe CurrentRetentionAuthority.Unavailable(
			RetentionAuthorityUnavailableReason.RETENTION_AUTHORITY_UNAVAILABLE,
		)
		assertIs<RetentionAuthorityResult.Applied>(
			producer.reconcileLiveAmbient(TrackingSourceComponent.STEPS),
		)
		val current = assertIs<CurrentRetentionAuthority.Approved>(
			producer.currentLiveAmbient(
				TrackingSourceComponent.STEPS,
				snapshot.revision,
				consentEpoch,
				lifecycle.epoch,
				lifecycle.retainedFromMs,
			),
		)
		current.retainedFromMs shouldBe lifecycle.retainedFromMs
	}

	@Test
	fun `DataStore floor ahead of Room makes the previous grant unavailable`() = runTest {
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		val snapshot = (policies.currentState() as SourcePolicyAuthorityState.Active).snapshot
		val consentEpoch = requireNotNull(
			snapshot[TrackingSourceComponent.STEPS].ambientConsentEpoch,
		)
		assertIs<RetentionAuthorityResult.Applied>(
			producer.reconcileLiveAmbient(TrackingSourceComponent.STEPS),
		)

		lifecycle = lifecycle.copy(retainedFromMs = 2_000L)

		producer.currentLiveAmbient(
			TrackingSourceComponent.STEPS,
			snapshot.revision,
			consentEpoch,
			lifecycle.epoch,
			lifecycle.retainedFromMs,
		) shouldBe CurrentRetentionAuthority.Unavailable(
			RetentionAuthorityUnavailableReason.RETAINED_FROM_CHANGED,
		)
	}

	@Test
	fun `floor only reissue rotates the exact retention approval revision`() = runTest {
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		assertIs<RetentionAuthorityResult.Applied>(
			producer.reconcileLiveAmbient(TrackingSourceComponent.STEPS),
		)
		val initial = requireNotNull(
			database.ambientStepsFactRevisionDao().latestRetentionAuthority(
				AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		)
		lifecycle = lifecycle.copy(retainedFromMs = 1_000L)
		database.sourceEvidenceStateDao().updateLifecycle(
			lifecycle.epoch,
			lifecycle.retainedFromMs,
			100L,
		)

		assertIs<RetentionAuthorityResult.Applied>(
			producer.reconcileLiveAmbient(TrackingSourceComponent.STEPS),
		)
		val reissued = requireNotNull(
			database.ambientStepsFactRevisionDao().latestRetentionAuthority(
				AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		)

		reissued.retainedFromMs shouldBe 1_000L
		reissued.approvalRevision shouldBe initial.approvalRevision + 1L
		reissued.opaquePolicyId shouldBe initial.opaquePolicyId
	}

	@Test
	fun `held operation permit supports floor guard and exact authority reissue`() = runTest {
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()

		val results = operationLease.withPermit { permit ->
			lifecycle = lifecycle.copy(retainedFromMs = 2_000L)
			database.sourceEvidenceStateDao().updateLifecycle(
				lifecycle.epoch,
				lifecycle.retainedFromMs,
				200L,
			)
			producer.reconcileCurrentSettings(permit)
		}

		assertIs<RetentionAuthorityResult.Applied>(
			results.single {
				it.source == TrackingSourceComponent.STEPS &&
					it.scope == RetentionAuthorityScope.LIVE_AMBIENT
			},
		)
		database.ambientStepsFactRevisionDao().latestRetentionAuthority(
			AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		)?.retainedFromMs shouldBe 2_000L
	}

	@Test
	fun `lease owned Room transaction keeps the private operation capability`() = runTest {
		operationLease.withPermit { permit ->
			permit.awaitOwned {
				permit.validate()
				database.withTransaction {
					permit.validate()
					database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe
						lifecycle.epoch
					permit.validate()
				}
				permit.validate()
			}
		}
	}

	@Test
	fun `operation permit cannot escape its lexical lease scope`() = runTest {
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		lateinit var escaped: RetentionAuthorityOperationPermit

		operationLease.withPermit { permit ->
		escaped = permit
		}

		assertFailsWith<IllegalArgumentException> {
		producer.reconcileCurrentSettings(escaped)
		}
	}

	@Test
	fun `operation permit cannot transfer to a child coroutine`() = runTest {
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()

		operationLease.withPermit { permit ->
			val child = async {
				producer.reconcileCurrentSettings(permit)
			}
			assertFailsWith<IllegalArgumentException> { child.await() }
		}
	}

	@Test
	fun `operation permit cannot transfer through shared NonCancellable context`() = runTest {
		operationLease.withPermit { permit ->
			assertFailsWith<IllegalArgumentException> {
				withContext(NonCancellable) {
					permit.validate()
				}
			}
		}
	}

	@Test
	fun `shielded operation permit cannot transfer to a NonCancellable child`() = runTest {
		operationLease.withPermit(cancellationShielded = true) { permit ->
			val child = async(NonCancellable) {
				permit.validate()
			}
			assertFailsWith<IllegalArgumentException> { child.await() }
		}
	}

	@Test
	fun `unreadable lifecycle cannot initialize fresh source evidence`() = runTest {
		removeSourceEvidenceState()
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)

		val steps = producer(
			readLifecycle = { error("lifecycle unavailable") },
		).reconcileCurrentSettings().single {
			it.source == TrackingSourceComponent.STEPS &&
				it.scope == RetentionAuthorityScope.LIVE_AMBIENT
		}

		assertIs<RetentionAuthorityResult.Unavailable>(steps).reason shouldBe
			RetentionAuthorityUnavailableReason.STORAGE_UNAVAILABLE
		database.sourceEvidenceStateDao().get() shouldBe null
	}

	@Test
	fun `policy consent and collected data changes rotate live approval`() = runTest {
		bootstrap(ambientWifi = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)

		approvedPolicy = approved("policy-2", revision = 2L)
		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)
		val afterPolicy = requireNotNull(
			database.ambientWifiFactDao().latestRetentionAuthority(
				AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		)
		afterPolicy.approvalRevision shouldBe 2L
		afterPolicy.opaquePolicyId shouldBe "policy-2"

		val beforeConsent =
			(policies.currentState() as SourcePolicyAuthorityState.Active).snapshot
		val disabled = policies.setNonCaptureConsent(
			beforeConsent.revision,
			TrackingSourceComponent.WIFI,
			SourcePurpose.AMBIENT_PRODUCT,
			eligible = false,
			persistenceEligible = false,
			reason = "TEST_REVOKE",
		)
		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)
		database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		)?.state shouldBe AmbientWifiRetentionAuthorityEntity.STATE_REVOKED
		val enabled = policies.setNonCaptureConsent(
			disabled.revision,
			TrackingSourceComponent.WIFI,
			SourcePurpose.AMBIENT_PRODUCT,
			eligible = true,
			persistenceEligible = true,
			reason = "TEST_REGRANT",
		)
		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)
		val afterConsent = requireNotNull(
			database.ambientWifiFactDao().latestRetentionAuthority(
				AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		)
		afterConsent.sourcePolicyRevision shouldBe enabled.revision
		afterConsent.ambientConsentEpoch shouldBe
			enabled[TrackingSourceComponent.WIFI].ambientConsentEpoch

		lifecycle = CollectedDataLifecycleSnapshot(epoch = 5L, retainedFromMs = null)
		database.sourceEvidenceStateDao().updateLifecycle(5L, null, 100L)
		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)
		database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		)?.collectedDataEpoch shouldBe 5L
	}

	@Test
	fun `source revoke leaves unrelated source approval unchanged`() = runTest {
		bootstrap(ambientWifi = true, ambientCell = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)
		producer.reconcileLiveAmbient(TrackingSourceComponent.CELL)
		val originalCell = requireNotNull(
			database.ambientCellFactDao().latestRetentionAuthority(
				AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		)
		val snapshot = (policies.currentState() as SourcePolicyAuthorityState.Active).snapshot
		policies.setNonCaptureConsent(
			snapshot.revision,
			TrackingSourceComponent.WIFI,
			SourcePurpose.AMBIENT_PRODUCT,
			eligible = false,
			persistenceEligible = false,
			reason = "TEST_WIFI_REVOKE",
		)

		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)

		database.ambientCellFactDao().latestRetentionAuthority(
			AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		) shouldBe originalCell
	}

	@Test
	fun `revoke and live import scopes remain isolated`() = runTest {
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		producer.reconcileLiveAmbient(TrackingSourceComponent.STEPS)
		producer.approvePortableImport(TrackingSourceComponent.STEPS)
		val snapshot = (policies.currentState() as SourcePolicyAuthorityState.Active).snapshot
		policies.setNonCaptureConsent(
			snapshot.revision,
			TrackingSourceComponent.STEPS,
			SourcePurpose.AMBIENT_PRODUCT,
			eligible = false,
			persistenceEligible = false,
			reason = "TEST_REVOKE",
		)

		producer.reconcileLiveAmbient(TrackingSourceComponent.STEPS)

		database.ambientStepsFactRevisionDao().latestRetentionAuthority(
			AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		)?.state shouldBe AmbientStepsRetentionAuthorityEntity.STATE_REVOKED
		database.ambientStepsFactRevisionDao().latestRetentionAuthority(
			AmbientStepsRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
		)?.state shouldBe AmbientStepsRetentionAuthorityEntity.STATE_ACTIVE
	}

	@Test
	fun `shared operation lease serializes concurrent producers before Room authority apply`() =
		runTest {
		bootstrap()
		approvedPolicy = approved("policy-1", revision = 1L)
		val arrival = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		val first = producer { _, _ ->
			arrival.complete(Unit)
			release.await()
		}
		val second = producer()
		val firstResult = async {
			first.approvePortableImport(TrackingSourceComponent.STEPS)
		}
		arrival.await()
		val secondResult = async {
			second.approvePortableImport(TrackingSourceComponent.STEPS)
		}
		runCurrent()
		secondResult.isCompleted.shouldBeFalse()
		release.complete(Unit)

		val results = listOf(firstResult.await(), secondResult.await())
		results.count { it is RetentionAuthorityResult.Applied } shouldBe 1
		results.count {
			it is RetentionAuthorityResult.Unchanged &&
				it.state == RetentionAuthorityState.ACTIVE
		} shouldBe 1
	}

	@Test
	fun `pending configuration stays fail closed until exact Room grants are approved`() = runTest {
		bootstrap(ambientWifi = true)
		val pendingPolicy = approvedPolicy("pending-policy", revision = 1L)
		var markedApproved = false
		val producer = producer(
			readPolicyCandidate = {
				RetentionPolicyCandidateRead.Available(
					pendingPolicy,
					RetentionPolicyApprovalStatus.PENDING,
				)
			},
			markPolicyApproved = {
				markedApproved = it == pendingPolicy
				it
			},
		)

		producer.currentLiveAmbient(
			TrackingSourceComponent.WIFI,
			expectedSourcePolicyRevision =
				(policies.currentState() as SourcePolicyAuthorityState.Active).snapshot.revision,
			expectedAmbientConsentEpoch = 1L,
			expectedCollectedDataEpoch = lifecycle.epoch,
		) shouldBe CurrentRetentionAuthority.Unavailable(
			RetentionAuthorityUnavailableReason.RETENTION_POLICY_UNAVAILABLE,
		)
		producer.preparePendingConfiguration(1L) shouldBe
			RetentionConfigurationApprovalResult.Prepared(pendingPolicy)
		producer.reconcilePendingConfiguration(1L) shouldBe
			RetentionConfigurationApprovalResult.Approved(pendingPolicy)
		markedApproved shouldBe true
		database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		)?.opaquePolicyId shouldBe "pending-policy"
	}

	@Test
	fun `pending configuration revokes prior live grant before publication`() = runTest {
		bootstrap(ambientWifi = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		producer().reconcileLiveAmbient(TrackingSourceComponent.WIFI)
		val pendingPolicy = approvedPolicy("policy-2", revision = 2L)
		approvedPolicy = ApprovedRetentionPolicyRead.Unavailable(
			ApprovedRetentionPolicyUnavailableReason.PENDING_APPROVAL,
		)
		val pendingProducer = producer(
			readPolicyCandidate = {
				RetentionPolicyCandidateRead.Available(
					pendingPolicy,
					RetentionPolicyApprovalStatus.PENDING,
				)
			},
		)

		pendingProducer.preparePendingConfiguration(2L) shouldBe
			RetentionConfigurationApprovalResult.Prepared(pendingPolicy)
		database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		)?.state shouldBe AmbientWifiRetentionAuthorityEntity.STATE_REVOKED
	}

	@Test
	fun `location seam remains unavailable and writes no other source`() = runTest {
		bootstrap(ambientLocation = true)
		approvedPolicy = approved("policy-1", revision = 1L)

		val result = producer().reconcilePassiveLocationRetention()

		assertIs<RetentionAuthorityResult.Unavailable>(result).reason shouldBe
			RetentionAuthorityUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE
		database.ambientStepsFactRevisionDao().latestRetentionAuthority(
			AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		) shouldBe null
		database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		) shouldBe null
		database.ambientCellFactDao().latestRetentionAuthority(
			AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		) shouldBe null
	}

	@Test
	fun `prior boot grant is rejected and reconciled onto current clock domain`() = runTest {
		bootstrap(ambientWifi = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		val snapshot = (policies.currentState() as SourcePolicyAuthorityState.Active).snapshot
		val consentEpoch = requireNotNull(
			snapshot[TrackingSourceComponent.WIFI].ambientConsentEpoch,
		)
		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)
		currentBoot = "boot-2"

		producer.currentLiveAmbient(
			TrackingSourceComponent.WIFI,
			snapshot.revision,
			consentEpoch,
			lifecycle.epoch,
		) shouldBe CurrentRetentionAuthority.Unavailable(
			RetentionAuthorityUnavailableReason.EFFECTIVE_TIME_INVALID,
		)
		assertIs<RetentionAuthorityResult.Applied>(
			producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI),
		)
		database.ambientWifiFactDao().latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		)?.effectiveBootId shouldBe "boot-2"
	}

	@Test
	fun `reboot reissue preserves consent created under an older policy revision`() = runTest {
		val settings = TrackingParamsState(
			ambientWifiEnabled = true,
			legacySettingsMigrationCompleted = true,
		)
		val original = policies.bootstrapFromLegacy(settings)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI)
		val consentEpoch = requireNotNull(
			original[TrackingSourceComponent.WIFI].ambientConsentEpoch,
		)
		currentBoot = "boot-2"
		val revised = policies.replaceCaptureSettings(
			original.revision,
			settings.copy(minTimeSeconds = settings.minTimeSeconds + 1),
			reason = "TEST_UNRELATED_POLICY_CHANGE",
		)

		assertIs<RetentionAuthorityResult.Applied>(
			producer.reconcileLiveAmbient(TrackingSourceComponent.WIFI),
		)
		requireNotNull(
			database.ambientWifiFactDao().latestRetentionAuthority(
				AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		).run {
			sourcePolicyRevision shouldBe revised.revision
			ambientConsentEpoch shouldBe consentEpoch
			effectiveBootId shouldBe "boot-2"
		}
	}

	@Test
	fun `unrelated policy revision rejects stale Ambient Steps authority until reissued`() = runTest {
		val settings = TrackingParamsState(
			ambientStepsEnabled = true,
			legacySettingsMigrationCompleted = true,
		)
		val original = policies.bootstrapFromLegacy(settings)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		producer.reconcileLiveAmbient(TrackingSourceComponent.STEPS)
		val consentEpoch = requireNotNull(
			original[TrackingSourceComponent.STEPS].ambientConsentEpoch,
		)
		val revised = policies.replaceCaptureSettings(
			original.revision,
			settings.copy(minTimeSeconds = settings.minTimeSeconds + 1),
			reason = "TEST_UNRELATED_POLICY_CHANGE",
		)

		producer.currentLiveAmbient(
			TrackingSourceComponent.STEPS,
			revised.revision,
			consentEpoch,
			lifecycle.epoch,
		) shouldBe CurrentRetentionAuthority.Unavailable(
			RetentionAuthorityUnavailableReason.RETENTION_AUTHORITY_UNAVAILABLE,
		)

		assertIs<RetentionAuthorityResult.Applied>(
			producer.reconcileLiveAmbient(TrackingSourceComponent.STEPS),
		)
		assertIs<CurrentRetentionAuthority.Approved>(
			producer.currentLiveAmbient(
				TrackingSourceComponent.STEPS,
				revised.revision,
				consentEpoch,
				lifecycle.epoch,
			),
		)
	}

	@Test
	fun `reader predicate binds current boot and exact retention identity`() = runTest {
		bootstrap(ambientSteps = true)
		approvedPolicy = approved("policy-1", revision = 1L)
		val producer = producer()
		val snapshot = (policies.currentState() as SourcePolicyAuthorityState.Active).snapshot
		val consentEpoch = requireNotNull(
			snapshot[TrackingSourceComponent.STEPS].ambientConsentEpoch,
		)
		assertIs<RetentionAuthorityResult.Applied>(
			producer.reconcileLiveAmbient(TrackingSourceComponent.STEPS),
		)
		val stored = requireNotNull(
			database.ambientStepsFactRevisionDao().latestRetentionAuthority(
				AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		)

		producer.isCurrentLiveAmbientAt(
			source = TrackingSourceComponent.STEPS,
			expectedSourcePolicyRevision = snapshot.revision,
			expectedAmbientConsentEpoch = consentEpoch,
			expectedCollectedDataEpoch = lifecycle.epoch,
			expectedRetainedFromMs = lifecycle.retainedFromMs,
			expectedOpaquePolicyId = stored.opaquePolicyId,
			expectedApprovalRevision = stored.approvalRevision,
			currentBootId = currentBoot,
			currentElapsedRealtimeNanos = effectiveTime + 10L,
			currentWallTimeMs = effectiveTime + 10L,
		) shouldBe true
		producer.isCurrentLiveAmbientAt(
			source = TrackingSourceComponent.STEPS,
			expectedSourcePolicyRevision = snapshot.revision,
			expectedAmbientConsentEpoch = consentEpoch,
			expectedCollectedDataEpoch = lifecycle.epoch,
			expectedRetainedFromMs = lifecycle.retainedFromMs,
			expectedOpaquePolicyId = stored.opaquePolicyId,
			expectedApprovalRevision = stored.approvalRevision + 1L,
			currentBootId = currentBoot,
			currentElapsedRealtimeNanos = effectiveTime + 10L,
			currentWallTimeMs = effectiveTime + 10L,
		) shouldBe false
	}

	private suspend fun bootstrap(
		ambientSteps: Boolean = false,
		ambientLocation: Boolean = false,
		ambientWifi: Boolean = false,
		ambientCell: Boolean = false,
	) {
		policies.bootstrapFromLegacy(
			TrackingParamsState(
				ambientStepsEnabled = ambientSteps,
				ambientLocationEnabled = ambientLocation,
				ambientWifiEnabled = ambientWifi,
				ambientCellEnabled = ambientCell,
				legacySettingsMigrationCompleted = true,
			),
		)
	}

	private suspend fun removeSourceEvidenceState() {
		database.withTransaction {
			database.openHelper.writableDatabase.execSQL(
				"DELETE FROM source_evidence_state",
			)
		}
	}

	private fun producer(
		beforeDecisionApply: suspend (
			TrackingSourceComponent,
			RetentionAuthorityScope,
		) -> Unit = { _, _ -> },
		readLifecycle: suspend () -> CollectedDataLifecycleSnapshot = { lifecycle },
		readPolicyCandidate: suspend () -> RetentionPolicyCandidateRead = {
			when (val current = approvedPolicy) {
				is ApprovedRetentionPolicyRead.Available ->
					RetentionPolicyCandidateRead.Available(
						current.policy,
						RetentionPolicyApprovalStatus.APPROVED,
					)
				is ApprovedRetentionPolicyRead.Unavailable ->
					RetentionPolicyCandidateRead.Unavailable(current.reason)
			}
		},
		markPolicyApproved: suspend (ApprovedRetentionPolicy) -> ApprovedRetentionPolicy? = { it },
	) = DefaultRetentionAuthorityProducer(
		database = database,
		sourcePolicyRepository = policies,
		readApprovedPolicy = { approvedPolicy },
		readLifecycle = readLifecycle,
		effectiveTimeProvider = { nextTime() },
		beforeDecisionApply = beforeDecisionApply,
		readPolicyCandidate = readPolicyCandidate,
		markPolicyApproved = markPolicyApproved,
		operationLease = operationLease,
	)

	private fun nextTime(): SourcePolicyEffectiveTime {
		effectiveTime += 1L
		return SourcePolicyEffectiveTime(
			bootId = currentBoot,
			elapsedRealtimeNanos = effectiveTime,
			wallTimeMs = effectiveTime,
		)
	}

	private fun approved(
		opaquePolicyId: String,
		revision: Long,
	): ApprovedRetentionPolicyRead =
		ApprovedRetentionPolicyRead.Available(approvedPolicy(opaquePolicyId, revision))

	private fun approvedPolicy(
		opaquePolicyId: String,
		revision: Long,
	): ApprovedRetentionPolicy {
		val configurationChecksum =
			RetentionPolicyApprovalIntegrity.configurationChecksum(RetentionConfigState())
		return ApprovedRetentionPolicy(
				configurationGeneration = revision,
				revision = revision,
				opaquePolicyId = opaquePolicyId,
				configurationChecksum = configurationChecksum,
				integrityChecksum = RetentionPolicyApprovalIntegrity.checksum(
					RetentionPolicyApprovalStatus.APPROVED,
					revision,
					revision,
					opaquePolicyId,
					configurationChecksum,
				),
		)
	}
}
