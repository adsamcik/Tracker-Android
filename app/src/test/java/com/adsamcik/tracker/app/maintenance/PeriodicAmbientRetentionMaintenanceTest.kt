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
				localSteps = { actualDatabase, floor, epoch, appliedAt ->
					actualDatabase shouldBe database
					floor shouldBe 1_500L
					epoch shouldBe 4L
					appliedAt shouldBe 2_000L
					calls += "local-steps"
					LocalAmbientStepsRetentionResult.NoChange
				},
				importedSteps = { request ->
					request.retainedFromMs shouldBe 1_500L
					request.expectedCollectedDataEpoch shouldBe 4L
					request.retainedAtMs shouldBe 2_000L
					calls += "imported-steps"
					TruncateImportedAmbientStepsRetentionResult.Retained(1)
				},
				wifi = { actualDatabase, command ->
					actualDatabase shouldBe database
					command.beforeMs shouldBe 1_500L
					command.expectedCollectedDataEpoch shouldBe 4L
					command.appliedAtMs shouldBe 2_000L
					calls += "wifi"
					AmbientWifiRetentionResult.Unavailable(
						AmbientWifiMaintenanceUnavailableReason.RETENTION_BOUNDARY_MISMATCH,
					)
				},
				cell = { actualDatabase, command ->
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
				val result = subject.run(database, lifecycle, 2_000L)
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
			localSteps = { _, floor, epoch, appliedAt ->
				floor shouldBe 1_500L
				epoch shouldBe 4L
				appliedAt shouldBe 2_000L
				localStepsCalls += 1
				LocalAmbientStepsRetentionResult.Pruned(1)
			},
			importedSteps = {
				calls += "imported"
				TruncateImportedAmbientStepsRetentionResult.Complete
			},
			wifi = { _, _ ->
				calls += "wifi"
				AmbientWifiRetentionResult.NoChange
			},
			cell = { _, _ ->
				calls += "cell"
				AmbientCellRetentionResult.NoChange
			},
		)

		subject.run(database, lifecycle, 2_000L) shouldBe
			PeriodicAmbientRetentionResult.Complete
		localStepsCalls shouldBe 1
		calls shouldContainExactly listOf("imported", "wifi", "cell")
	}

	@Test
	fun `local Steps exposes typed retry debt`() = runTest {
		val subject = PeriodicAmbientRetentionMaintenance(
			localSteps = { _, _, _, _ ->
				LocalAmbientStepsRetentionResult.Unavailable(
					PeriodicAmbientRetentionFailureReason.UNVERIFIABLE,
				)
			},
			importedSteps = { TruncateImportedAmbientStepsRetentionResult.Complete },
			wifi = { _, _ -> AmbientWifiRetentionResult.NoChange },
			cell = { _, _ -> AmbientCellRetentionResult.NoChange },
		)

		(subject.run(database, lifecycle, 2_000L) as PeriodicAmbientRetentionResult.Retryable)
			.failures shouldContainExactly listOf(
			PeriodicAmbientRetentionFailure(
				PeriodicAmbientRetentionSource.LOCAL_STEPS,
				PeriodicAmbientRetentionFailureReason.UNVERIFIABLE,
			),
		)
	}

	private fun subject(
		importedResult: TruncateImportedAmbientStepsRetentionResult,
	): PeriodicAmbientRetentionMaintenance = PeriodicAmbientRetentionMaintenance(
		localSteps = { _, _, _, _ -> LocalAmbientStepsRetentionResult.NoChange },
		importedSteps = { importedResult },
		wifi = { _, _ -> AmbientWifiRetentionResult.NoChange },
		cell = { _, _ -> AmbientCellRetentionResult.NoChange },
	)
}
