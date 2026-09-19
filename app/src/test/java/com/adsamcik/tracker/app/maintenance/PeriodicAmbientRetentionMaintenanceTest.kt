package com.adsamcik.tracker.app.maintenance

import com.adsamcik.tracker.shared.base.database.AmbientCellMaintenanceUnavailableReason
import com.adsamcik.tracker.shared.base.database.AmbientCellRetentionResult
import com.adsamcik.tracker.shared.base.database.AmbientWifiMaintenanceUnavailableReason
import com.adsamcik.tracker.shared.base.database.AmbientWifiRetentionResult
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.stats.api.repository.ImportedAmbientStepsMutationBlockedReason
import com.adsamcik.tracker.stats.api.repository.ImportedAmbientStepsMutationUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableAmbientStepsTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.TruncateImportedAmbientStepsRetentionResult
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFailsWith

class PeriodicAmbientRetentionMaintenanceTest {
	private val database = mockk<AppDatabase>()
	private val lifecycle = CollectedDataLifecycleSnapshot(
		epoch = 4L,
		retainedFromMs = 1_500L,
	)

	@Test
	fun `all source-specific maintenance uses the exact settled floor and reports every debt`() =
		runTest {
			val calls = mutableListOf<String>()
			val subject = PeriodicAmbientRetentionMaintenance(
				localSteps = { actualDatabase, floor, epoch, appliedAt, _ ->
					actualDatabase shouldBe database
					floor shouldBe 1_500L
					epoch shouldBe 4L
					appliedAt shouldBe 2_000L
					calls += "local-steps"
					LocalAmbientStepsRetentionResult.NoChange
				},
				importedSteps = { actualDatabase, request, _ ->
					actualDatabase shouldBe database
					request.retainedFromMs shouldBe 1_500L
					request.expectedCollectedDataEpoch shouldBe 4L
					request.retainedAtMs shouldBe 2_000L
					calls += "imported-steps"
					TruncateImportedAmbientStepsRetentionResult.Retained(1)
				},
				wifi = { actualDatabase, command, _ ->
					actualDatabase shouldBe database
					command.beforeMs shouldBe 1_500L
					command.expectedCollectedDataEpoch shouldBe 4L
					command.appliedAtMs shouldBe 2_000L
					calls += "wifi"
					AmbientWifiRetentionResult.Unavailable(
						AmbientWifiMaintenanceUnavailableReason.RETENTION_BOUNDARY_MISMATCH,
					)
				},
				cell = { actualDatabase, command, _ ->
					actualDatabase shouldBe database
					command.beforeMs shouldBe 1_500L
					command.expectedCollectedDataEpoch shouldBe 4L
					command.appliedAtMs shouldBe 2_000L
					calls += "cell"
					AmbientCellRetentionResult.Unavailable(
						AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
					)
				},
			)

			val result = subject.run(
				database,
				lifecycle,
				2_000L,
				{},
			)

			calls shouldContainExactly listOf("local-steps", "imported-steps", "wifi", "cell")
			(result as PeriodicAmbientRetentionResult.Retryable).failures shouldContainExactly listOf(
				PeriodicAmbientRetentionFailure(
					PeriodicAmbientRetentionSource.IMPORTED_STEPS,
					PeriodicAmbientRetentionFailureReason.INCOMPLETE,
				),
				PeriodicAmbientRetentionFailure(
					PeriodicAmbientRetentionSource.WIFI,
					PeriodicAmbientRetentionFailureReason.UNVERIFIABLE,
				),
				PeriodicAmbientRetentionFailure(
					PeriodicAmbientRetentionSource.CELL,
					PeriodicAmbientRetentionFailureReason.UNVERIFIABLE,
				),
			)
		}

	@Test
	fun `imported Steps blocked unverifiable and retryable outcomes remain distinct durable debt`() =
		runTest {
			val outcomes = listOf(
				TruncateImportedAmbientStepsRetentionResult.Blocked(
					ImportedAmbientStepsMutationBlockedReason.RETENTION_FLOOR_CHANGED,
				) to PeriodicAmbientRetentionFailureReason.BLOCKED,
				TruncateImportedAmbientStepsRetentionResult.Unverifiable(
					ImportedAmbientStepsMutationUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				) to PeriodicAmbientRetentionFailureReason.UNVERIFIABLE,
				TruncateImportedAmbientStepsRetentionResult.RetryableFailure(
					PortableAmbientStepsTransferRetryableReason.STORAGE_UNAVAILABLE,
				) to PeriodicAmbientRetentionFailureReason.RETRYABLE,
			)

			outcomes.forEach { (outcome, expectedReason) ->
				val subject = subject(importedResult = outcome)
				val result = subject.run(database, lifecycle, 2_000L, {})
					as PeriodicAmbientRetentionResult.Retryable

				result.failures shouldContainExactly listOf(
					PeriodicAmbientRetentionFailure(
						PeriodicAmbientRetentionSource.IMPORTED_STEPS,
						expectedReason,
					),
				)
			}
		}

	@Test
	fun `revoked local Steps still prunes delayed rows before imported and radio maintenance`() =
		runTest {
		var localStepsCalls = 0
		val calls = mutableListOf<String>()
		val subject = PeriodicAmbientRetentionMaintenance(
			localSteps = { _, floor, epoch, appliedAt, _ ->
				floor shouldBe 1_500L
				epoch shouldBe 4L
				appliedAt shouldBe 2_000L
				localStepsCalls += 1
				LocalAmbientStepsRetentionResult.Pruned(1)
			},
			importedSteps = { _, _, _ ->
				calls += "imported"
				TruncateImportedAmbientStepsRetentionResult.Complete
			},
			wifi = { _, _, _ ->
				calls += "wifi"
				AmbientWifiRetentionResult.NoChange
			},
			cell = { _, _, _ ->
				calls += "cell"
				AmbientCellRetentionResult.NoChange
			},
		)

		subject.run(database, lifecycle, 2_000L, {}) shouldBe
			PeriodicAmbientRetentionResult.Complete
		localStepsCalls shouldBe 1
		calls shouldContainExactly listOf("imported", "wifi", "cell")
	}

	@Test
	fun `local Steps exposes typed retry debt`() = runTest {
		val subject = PeriodicAmbientRetentionMaintenance(
			localSteps = { _, _, _, _, _ ->
				LocalAmbientStepsRetentionResult.Unavailable(
					PeriodicAmbientRetentionFailureReason.UNVERIFIABLE,
				)
			},
			importedSteps = { _, _, _ -> TruncateImportedAmbientStepsRetentionResult.Complete },
			wifi = { _, _, _ -> AmbientWifiRetentionResult.NoChange },
			cell = { _, _, _ -> AmbientCellRetentionResult.NoChange },
		)

		(subject.run(database, lifecycle, 2_000L, {}) as PeriodicAmbientRetentionResult.Retryable)
			.failures shouldContainExactly listOf(
			PeriodicAmbientRetentionFailure(
				PeriodicAmbientRetentionSource.LOCAL_STEPS,
				PeriodicAmbientRetentionFailureReason.UNVERIFIABLE,
			),
		)
	}

	@Test
	fun `execution cancellation between ambient sources stops every remaining mutation`() = runTest {
		val sourceOrder = listOf("local-steps", "imported-steps", "wifi", "cell")
		sourceOrder.indices.forEach { cancellationIndex ->
			val calls = mutableListOf<String>()
			var sourceBoundary = 0
			val subject = PeriodicAmbientRetentionMaintenance(
				localSteps = { _, _, _, _, _ ->
					calls += "local-steps"
					LocalAmbientStepsRetentionResult.NoChange
				},
				importedSteps = { _, _, _ ->
					calls += "imported-steps"
					TruncateImportedAmbientStepsRetentionResult.Complete
				},
				wifi = { _, _, _ ->
					calls += "wifi"
					AmbientWifiRetentionResult.NoChange
				},
				cell = { _, _, _ ->
					calls += "cell"
					AmbientCellRetentionResult.NoChange
				},
			)

			assertFailsWith<RetentionExecutionStoppedException> {
				subject.run(database, lifecycle, 2_000L) {
					if (sourceBoundary++ == cancellationIndex) {
						throw RetentionExecutionStoppedException()
					}
				}
			}

			calls shouldContainExactly sourceOrder.take(cancellationIndex)
		}
	}

	@Test
	fun `exact continuation verifier reaches every source transaction boundary`() = runTest {
		val boundaries = mutableListOf<String>()
		val subject = PeriodicAmbientRetentionMaintenance(
			localSteps = { _, _, _, _, verify ->
				boundaries += "local-operation"
				verify()
				LocalAmbientStepsRetentionResult.NoChange
			},
			importedSteps = { _, _, verify ->
				boundaries += "imported-operation"
				verify()
				TruncateImportedAmbientStepsRetentionResult.Complete
			},
			wifi = { _, _, verify ->
				boundaries += "wifi-operation"
				verify()
				AmbientWifiRetentionResult.NoChange
			},
			cell = { _, _, verify ->
				boundaries += "cell-operation"
				verify()
				AmbientCellRetentionResult.NoChange
			},
		)

		subject.run(database, lifecycle, 2_000L) {
			boundaries += "verified"
		} shouldBe PeriodicAmbientRetentionResult.Complete

		boundaries shouldContainExactly listOf(
		"verified",
		"local-operation",
		"verified",
		"verified",
		"imported-operation",
		"verified",
		"verified",
		"wifi-operation",
		"verified",
		"verified",
		"cell-operation",
		"verified",
		)
	}

	private fun subject(
		importedResult: TruncateImportedAmbientStepsRetentionResult,
	): PeriodicAmbientRetentionMaintenance = PeriodicAmbientRetentionMaintenance(
		localSteps = { _, _, _, _, _ -> LocalAmbientStepsRetentionResult.NoChange },
		importedSteps = { _, _, _ -> importedResult },
		wifi = { _, _, _ -> AmbientWifiRetentionResult.NoChange },
		cell = { _, _, _ -> AmbientCellRetentionResult.NoChange },
	)
}
