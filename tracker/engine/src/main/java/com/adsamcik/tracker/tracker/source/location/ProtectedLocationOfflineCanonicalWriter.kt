package com.adsamcik.tracker.tracker.source.location

import android.content.Context
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.tracker.component.consumer.data.LocationTrackerComponent
import com.adsamcik.tracker.tracker.pipeline.CycleContext
import com.adsamcik.tracker.tracker.pipeline.ProcessorPipeline
import com.adsamcik.tracker.tracker.pipeline.TrackingPipeline
import com.adsamcik.tracker.tracker.pipeline.toProtectedLocationObservationSignal
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import com.adsamcik.tracker.tracker.pipeline.stages.DataCollectionStage
import com.adsamcik.tracker.tracker.pipeline.stages.SignalDispatchStage
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Provider-free recovery writer for a stopped or crashed protected Location run.
 *
 * This reuses the established Location curation component, signal dispatch, durable pending-signal
 * buffer, and PersistenceProcessor. It never starts a source runtime or synthesizes non-Location
 * input. The shared coordinator must invoke it while the live processor is inactive; the method
 * checks that condition again before creating its isolated pipeline.
 */
@Singleton
internal class ProtectedLocationOfflineCanonicalWriter @Inject constructor(
	@ApplicationContext context: Context,
	private val database: com.adsamcik.tracker.shared.base.database.AppDatabase,
	private val dispatchers: DispatchersProvider,
	private val persistenceProcessor: PersistenceProcessor,
) : ProtectedLocationCanonicalWriter {
	private val applicationContext = context.applicationContext

	override suspend fun write(
		command: LocationCapturedFactCommand,
		acquisitionMetadata: LocationWalAcquisitionMetadata,
	): ProtectedLocationCanonicalWriteResult {
		if (persistenceProcessor.isPipelineActiveForProtectedLocation()) {
			return ProtectedLocationCanonicalWriteResult.Inactive(
				"LOCATION_CANONICAL_LIVE_PIPELINE_ACTIVE",
			)
		}
		val runState = database.sourceSessionDao()
			.serviceRun(command.authority.serviceRunId.value)
			?.state
		if (runState !in OFFLINE_WRITER_RUN_STATES) {
			return ProtectedLocationCanonicalWriteResult.Inactive(
				"LOCATION_CANONICAL_RUN_NOT_QUIESCED",
			)
		}
		val stateBefore = try {
			database.withTransaction {
				database.loadPreparedProtectedLocationCanonicalCurationState(command)
					?.stateBefore
					?: database.loadProtectedLocationCanonicalCurationState(command)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			return ProtectedLocationCanonicalWriteResult.Failed(
				failure.safeProtectedLocationCode(),
				terminal = false,
			)
		}
		val curationContext = try {
			acquisitionMetadata.toCanonicalCurationContext(stateBefore)
		} catch (failure: IllegalStateException) {
			return ProtectedLocationCanonicalWriteResult.Failed(
				"LOCATION_CANONICAL_CURATION_CONTRACT_UNSUPPORTED",
				terminal = true,
			)
		}
		val locationComponent = LocationTrackerComponent(
			trackingParamsRepository = null,
			dispatchers = dispatchers,
		)
		val pipeline = ProcessorPipeline(
			processors = setOf(persistenceProcessor),
			requireDurableAdmission = true,
		)
		var componentEnabled = false
		var pipelineStarted = false
		return try {
			locationComponent.onEnable(applicationContext)
			componentEnabled = true
			pipeline.start(
				tier = acquisitionMetadata.policyTier,
				startTimestamp = EpochMs(
					command.productEffect.durableEvidence.clockAuthority.observedWallTimeMs,
				),
				isResuming = true,
				sessionId = command.authority.sessionSegmentId,
			)
			pipelineStarted = true
			if (!persistenceProcessor.isReadyForProtectedLocationWrite()) {
				return ProtectedLocationCanonicalWriteResult.Deferred(
					"LOCATION_CANONICAL_PENDING_RECOVERY_INCOMPLETE",
				)
			}
			when (val recoveredReceipt = database.withTransaction {
				database.readProtectedLocationCanonicalReceipt(command, acquisitionMetadata)
			}) {
				is ProtectedLocationCanonicalReceipt.Complete ->
					return ProtectedLocationCanonicalWriteResult.Committed
				is ProtectedLocationCanonicalReceipt.Invalid ->
					return ProtectedLocationCanonicalWriteResult.Failed(
						recoveredReceipt.reason,
						terminal = true,
					)
				is ProtectedLocationCanonicalReceipt.Incomplete -> Unit
			}
			if (!pipeline.checkpointDurableSignals(
				listOf(command.toProtectedLocationObservationSignal(
					acquisitionMetadata = acquisitionMetadata,
					policyTier = acquisitionMetadata.policyTier,
					policyName = acquisitionMetadata.policyName,
				)),
			)) {
				return ProtectedLocationCanonicalWriteResult.Deferred(
					"LOCATION_CANONICAL_RAW_ADMISSION_DEFERRED",
				)
			}

			if (command.productEffect.isMock) {
				database.withTransaction {
					database.prepareProtectedLocationCanonicalCurationState(
						command,
						stateBefore,
						stateBefore,
					)
				}
				if (!pipeline.onSignal(
					command.toProtectedLocationMockRejectionSignal(
						acquisitionMetadata = acquisitionMetadata,
						policyTier = acquisitionMetadata.policyTier,
						policyName = acquisitionMetadata.policyName,
					),
				)) {
					return ProtectedLocationCanonicalWriteResult.Deferred(
						"LOCATION_CANONICAL_DECISION_ADMISSION_DEFERRED",
					)
				}
			} else {
				val cycle = command.toProtectedLocationTrackingCycle(
					acquisitionMetadata = acquisitionMetadata,
					curationContext = curationContext,
				)
				TrackingPipeline(
					listOf(
						DataCollectionStage(listOf(locationComponent)),
						SignalDispatchStage(
							processorPipelineProvider = { pipeline },
							currentTierProvider = { acquisitionMetadata.policyTier },
							currentPolicyNameProvider = { acquisitionMetadata.policyName },
							beforeSignalAdmission = { cycleContext, _ ->
								check(
									cycleContext.collectionData.location != null ||
										curationContext.outcome.decisionReason != null,
								) {
									"Protected Location curation produced no terminal outcome"
								}
								database.withTransaction {
									database.prepareProtectedLocationCanonicalCurationState(
										command,
										curationContext.stateBefore,
										curationContext.outcome.stateAfter,
									)
								}
							},
						),
					),
				).execute(
					applicationContext,
					CycleContext(
						cycle = cycle,
						collectionData = MutableCollectionData(cycle.timestampMs),
					),
				)
			}

			pipeline.stop()
			pipelineStarted = false
			when (val receipt = database.withTransaction {
				database.readProtectedLocationCanonicalReceipt(command, acquisitionMetadata)
			}) {
				is ProtectedLocationCanonicalReceipt.Complete ->
					ProtectedLocationCanonicalWriteResult.Committed
				is ProtectedLocationCanonicalReceipt.Incomplete ->
					ProtectedLocationCanonicalWriteResult.Deferred(receipt.reason)
				is ProtectedLocationCanonicalReceipt.Invalid ->
					ProtectedLocationCanonicalWriteResult.Failed(
						receipt.reason,
						terminal = true,
					)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			val receipt = runCatching {
				database.withTransaction {
					database.readProtectedLocationCanonicalReceipt(command, acquisitionMetadata)
				}
			}.getOrNull()
			if (receipt is ProtectedLocationCanonicalReceipt.Complete) {
				ProtectedLocationCanonicalWriteResult.Committed
			} else {
				ProtectedLocationCanonicalWriteResult.Failed(
					failure.safeProtectedLocationCode(),
					terminal = false,
				)
			}
		} finally {
			withContext(NonCancellable) {
				if (pipelineStarted) runCatching { pipeline.stop() }
				if (componentEnabled) runCatching {
					locationComponent.onDisable(applicationContext)
				}

				private companion object {
					val OFFLINE_WRITER_RUN_STATES = setOf(
						SessionLifecycleState.STOPPING.name,
						SessionLifecycleState.FINALIZED.name,
						SessionLifecycleState.FAILED.name,
						"CLOSED",
					)
				}
			}
		}
	}
}

private fun Throwable.safeProtectedLocationCode(): String =
	javaClass.simpleName.takeIf(String::isNotBlank)
		?: "LOCATION_CANONICAL_OFFLINE_WRITE_FAILED"
