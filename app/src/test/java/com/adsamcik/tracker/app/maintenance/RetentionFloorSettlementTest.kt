package com.adsamcik.tracker.app.maintenance

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityOperationLease
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityScope
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciler
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationDebt
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationFailure
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationFailureReason
import com.adsamcik.tracker.tracker.api.TrackingRetentionFloorReconciliationResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
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
class RetentionFloorSettlementTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `exact floor purpose publication is the only provider reconciliation before settlement`() =
		runTest {
			val events = mutableListOf<String>()
			val lifecycle = fixedLifecycleStore(
				CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = FLOOR),
				onAdvance = { events += "lifecycle" },
			)
			val producer = mockk<RetentionAuthorityProducer> {
				coEvery { reconcileCurrentSettings() } coAnswers {
					database.sourceEvidenceStateDao().get()?.retainedFromMs shouldBe FLOOR
					events += "authority"
					retentionResults(active = setOf(TrackingSourceComponent.STEPS))
				}
			}
			val reconciler = TrackingRetentionFloorReconciler { generation, floor, sources ->
				generation shouldBe GENERATION
				floor shouldBe FLOOR
				sources shouldBe setOf(AmbientTrackingSource.STEPS)
				events += "purpose"
				TrackingRetentionFloorReconciliationResult.Complete(floor, sources)
			}
			val result = RetentionFloorSettlement(
				RetentionAuthorityOperationLease(),
				producer,
				reconciler,
			).settle(
				database = database,
				lifecycleStore = lifecycle,
				startupGate = readyGate(),
				expectedStartupGeneration = GENERATION,
				requestedRetainedFromMs = FLOOR,
				updatedAtMs = FLOOR,
				verifyApprovedOperation = { events += "approved" },
			).shouldBeInstanceOf<RetentionFloorSettlementResult.Settled>()

			result.lifecycle.retainedFromMs shouldBe FLOOR
			result.reconciledSources shouldBe setOf(AmbientTrackingSource.STEPS)
			(events.indexOf("lifecycle") < events.indexOf("authority")) shouldBe true
			(events.indexOf("authority") < events.indexOf("purpose")) shouldBe true
			events shouldBe listOf(
				"approved",
				"lifecycle",
				"approved",
				"approved",
				"approved",
				"authority",
				"purpose",
			)
		}

	@Test
	fun `disabled retention sources cannot enter provider reconciliation`() = runTest {
		var providerStarts = 0
		val producer = mockk<RetentionAuthorityProducer> {
			coEvery { reconcileCurrentSettings() } returns retentionResults(active = emptySet())
		}
		val result = RetentionFloorSettlement(
			RetentionAuthorityOperationLease(),
			producer,
			TrackingRetentionFloorReconciler { _, floor, sources ->
				providerStarts += sources.size
				TrackingRetentionFloorReconciliationResult.Complete(floor, sources)
			},
		).settle(
			database = database,
			lifecycleStore = fixedLifecycleStore(
				CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = FLOOR),
			),
			startupGate = readyGate(),
			expectedStartupGeneration = GENERATION,
			requestedRetainedFromMs = FLOOR,
			updatedAtMs = FLOOR,
			verifyApprovedOperation = { },
		)

		result.shouldBeInstanceOf<RetentionFloorSettlementResult.Settled>()
			.reconciledSources shouldBe emptySet()
		providerStarts shouldBe 0
	}

	@Test
	fun `provider reissue failure remains typed retry debt after floor commit`() = runTest {
		val producer = mockk<RetentionAuthorityProducer> {
			coEvery { reconcileCurrentSettings() } returns retentionResults(
				active = setOf(TrackingSourceComponent.WIFI),
			)
		}
		val result = RetentionFloorSettlement(
			RetentionAuthorityOperationLease(),
			producer,
			TrackingRetentionFloorReconciler { _, floor, _ ->
				TrackingRetentionFloorReconciliationResult.Retryable(
					TrackingRetentionFloorReconciliationDebt(
						floor,
						listOf(
							TrackingRetentionFloorReconciliationFailure(
								AmbientTrackingSource.WIFI,
								TrackingRetentionFloorReconciliationFailureReason
									.OWNER_RECONCILIATION_FAILED,
							),
						),
					),
				)
			},
		).settle(
			database = database,
			lifecycleStore = fixedLifecycleStore(
				CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = FLOOR),
			),
			startupGate = readyGate(),
			expectedStartupGeneration = GENERATION,
			requestedRetainedFromMs = FLOOR,
			updatedAtMs = FLOOR,
			verifyApprovedOperation = { },
		).shouldBeInstanceOf<RetentionFloorSettlementResult.Retryable>()

		database.sourceEvidenceStateDao().get()?.retainedFromMs shouldBe FLOOR
		result.debt.failures.single()
			.shouldBeInstanceOf<RetentionFloorSettlementFailure.ProviderLifecycle>()
	}

	@Test
	fun `settlement cannot publish complete after its ready generation closes`() = runTest {
		var ready = true
		val gate = object : TrackingStartupGate {
			override val isReady: Boolean
				get() = ready
			override val currentGeneration: Long = GENERATION
			override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
				TrackingStartupResult.Ready(false, 0L)
		}
		val producer = mockk<RetentionAuthorityProducer> {
			coEvery { reconcileCurrentSettings() } returns retentionResults(active = emptySet())
		}
		val result = RetentionFloorSettlement(
			RetentionAuthorityOperationLease(),
			producer,
			TrackingRetentionFloorReconciler { _, floor, sources ->
				ready = false
				TrackingRetentionFloorReconciliationResult.Complete(floor, sources)
			},
		).settle(
			database = database,
			lifecycleStore = fixedLifecycleStore(
				CollectedDataLifecycleSnapshot(epoch = 4L, retainedFromMs = FLOOR),
			),
			startupGate = gate,
			expectedStartupGeneration = GENERATION,
			requestedRetainedFromMs = FLOOR,
			updatedAtMs = FLOOR,
			verifyApprovedOperation = { },
		).shouldBeInstanceOf<RetentionFloorSettlementResult.Retryable>()

		result.debt.failures.single()
			.shouldBeInstanceOf<RetentionFloorSettlementFailure.ProviderLifecycle>()
			.debt.failures.single().reason shouldBe
			TrackingRetentionFloorReconciliationFailureReason.STARTUP_GENERATION_CHANGED
	}

	private fun retentionResults(
		active: Set<TrackingSourceComponent>,
	): List<RetentionAuthorityResult> = listOf(
		TrackingSourceComponent.STEPS,
		TrackingSourceComponent.WIFI,
		TrackingSourceComponent.CELL,
	).map { source ->
		RetentionAuthorityResult.Unchanged(
			source = source,
			scope = RetentionAuthorityScope.LIVE_AMBIENT,
			state = if (source in active) {
				RetentionAuthorityState.ACTIVE
			} else {
				RetentionAuthorityState.REVOKED
			},
			approvalRevision = 1L,
		)
	}

	private fun fixedLifecycleStore(
		snapshot: CollectedDataLifecycleSnapshot,
		onAdvance: () -> Unit = { },
	): CollectedDataLifecycleStore = object : CollectedDataLifecycleStore {
		override val snapshots: Flow<CollectedDataLifecycleSnapshot> = MutableStateFlow(snapshot)

		override suspend fun snapshot(): CollectedDataLifecycleSnapshot = snapshot

		override suspend fun beginFullDeletion(
			deletedAtMs: Long,
		): CollectedDataLifecycleSnapshot = error("Not used")

		override suspend fun advanceRetainedFrom(
			retainedFromMs: Long,
		): CollectedDataLifecycleSnapshot {
			onAdvance()
			return snapshot
		}
	}

	private fun readyGate(): TrackingStartupGate = object : TrackingStartupGate {
		override val isReady: Boolean = true
		override val currentGeneration: Long = GENERATION
		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
			TrackingStartupResult.Ready(false, 0L)
	}

	private companion object {
		const val GENERATION = 7L
		const val FLOOR = 5_000L
	}
}
