package com.adsamcik.tracker.tracker.source.wifi

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductFailure
import com.adsamcik.tracker.stats.api.repository.ImportedWifiReexportBlockedReason
import com.adsamcik.tracker.stats.api.repository.ImportedWifiReexportUnavailableReason
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiSink
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiRetryableReason
import com.adsamcik.tracker.stats.api.repository.PortableWifiUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.ReexportImportedCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ReexportImportedCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ReexportImportedCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryReader
import com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryResult
import com.adsamcik.tracker.stats.api.repository.WifiHistorySelection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Latest-v1 imported Wi-Fi re-export. Sink I/O begins only after the Room snapshot closes. */
@Singleton
internal class RoomReexportImportedCapturedWifi @Inject constructor(
	private val database: AppDatabase,
	private val evaluator: ImportedWifiProductEvaluator,
	private val localPortableReader: ReadLocalPortableCapturedWifi,
	private val deletedHistoryReader: WifiDeletedHistoryReader,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ReexportImportedCapturedWifi {
	internal constructor(
		database: AppDatabase,
		evaluator: ImportedWifiProductEvaluator,
		ioDispatcher: CoroutineDispatcher,
	) : this(
		database,
		evaluator,
		RejectingLocalPortableWifiReader,
		WifiDeletedHistoryReader { WifiDeletedHistoryResult.NotDeleted },
		ioDispatcher,
	)

	override suspend fun reexport(
		request: ReexportImportedCapturedWifiRequest,
		sink: PortableCapturedWifiSink,
	): ReexportImportedCapturedWifiResult = withContext(ioDispatcher) {
		val snapshot = try {
			database.withTransaction {
				val state = database.sourceEvidenceStateDao().get()
					?: return@withTransaction ImportedWifiReexportSnapshot.Outcome(
						ReexportImportedCapturedWifiResult.Unverifiable(
							ImportedWifiProductFailure.SOURCE_EVIDENCE_STATE_MISSING,
						),
					)
				if (!state.hasValidImportedWifiReexportShape()) {
					return@withTransaction ImportedWifiReexportSnapshot.Outcome(
						ReexportImportedCapturedWifiResult.Unverifiable(
							ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
						),
					)
				}
				if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) {
					return@withTransaction ImportedWifiReexportSnapshot.Outcome(
						ReexportImportedCapturedWifiResult.Blocked(
							ImportedWifiReexportBlockedReason.COLLECTED_DATA_EPOCH_CHANGED,
						),
					)
				}
				when (val deleted = deletedHistoryReader.readDeletedInTransaction(
					WifiHistorySelection.Imported(request.selection),
				)) {
					is WifiDeletedHistoryResult.Deleted ->
						return@withTransaction ImportedWifiReexportSnapshot.Outcome(
							ReexportImportedCapturedWifiResult.Deleted,
						)
					is WifiDeletedHistoryResult.Unverifiable ->
						return@withTransaction ImportedWifiReexportSnapshot.Outcome(
							ReexportImportedCapturedWifiResult.Unverifiable(
								ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
							),
						)
					WifiDeletedHistoryResult.NotDeleted -> Unit
				}
				when (val evaluation = evaluator.selectIdentityInTransaction(request.selection.key)) {
					null -> ImportedWifiReexportSnapshot.Outcome(
						ReexportImportedCapturedWifiResult.NotFound,
					)
					is ImportedWifiProductEvaluation.Unverifiable ->
						ImportedWifiReexportSnapshot.Outcome(
							when (evaluation.reason) {
								ImportedWifiProductFailure.STALE_COLLECTED_DATA_EPOCH ->
									ReexportImportedCapturedWifiResult.Unavailable(
										ImportedWifiReexportUnavailableReason.PRIVACY_EPOCH_MISMATCH,
									)
								else -> ReexportImportedCapturedWifiResult.Unverifiable(
									evaluation.reason,
								)
							},
						)
					is ImportedWifiProductEvaluation.Readable -> when {
						evaluation.candidate.selection != request.selection ->
							ImportedWifiReexportSnapshot.Outcome(
								ReexportImportedCapturedWifiResult.Blocked(
									ImportedWifiReexportBlockedReason.STALE_SELECTION,
								),
							)
						evaluation.entryDeleted || evaluation.deletedRunIdentities.isNotEmpty() ->
							ImportedWifiReexportSnapshot.Outcome(
								ReexportImportedCapturedWifiResult.Deleted,
							)
						evaluation.retentionLimited ->
							ImportedWifiReexportSnapshot.Outcome(
								ReexportImportedCapturedWifiResult.Unavailable(
									ImportedWifiReexportUnavailableReason.RETENTION_LIMIT,
								),
							)
						else -> {
							val collision = evaluation.collidingLocalLogicalTrackingId
							if (collision == null) {
								ImportedWifiReexportSnapshot.Entry(
									evaluation.candidate.importRevision,
									evaluation.entry,
								)
							} else {
								when (val local = localPortableReader.readInTransaction(
									ExportPortableCapturedWifiRequest(collision),
								)) {
									is ReadLocalPortableCapturedWifiResult.Ready ->
										if (local.entry == evaluation.entry) {
											ImportedWifiReexportSnapshot.Entry(
												evaluation.candidate.importRevision,
												evaluation.entry,
											)
										} else {
											originConflict()
										}
									is ReadLocalPortableCapturedWifiResult.Outcome -> originConflict()
								}
							}
						}
					}
				}
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (blocked: WifiCapturedRetentionBlockedException) {
			return@withContext ReexportImportedCapturedWifiResult.Unverifiable(
				when (blocked.reason) {
					WifiCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED ->
						ImportedWifiProductFailure.DEPENDENCY_OVERFLOW
					else -> ImportedWifiProductFailure.ORIGIN_IDENTITY_CONFLICT
				},
			)
		} catch (_: WifiCapturedMaintenanceLimitExceeded) {
			return@withContext ReexportImportedCapturedWifiResult.Unverifiable(
				ImportedWifiProductFailure.DEPENDENCY_OVERFLOW,
			)
		} catch (_: IllegalArgumentException) {
			return@withContext ReexportImportedCapturedWifiResult.Unverifiable(
				ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		} catch (_: ArithmeticException) {
			return@withContext ReexportImportedCapturedWifiResult.Unverifiable(
				ImportedWifiProductFailure.VALUE_OVERFLOW,
			)
		} catch (_: SQLiteException) {
			return@withContext ReexportImportedCapturedWifiResult.RetryableFailure(
				PortableWifiRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: RuntimeException) {
			return@withContext ReexportImportedCapturedWifiResult.RetryableFailure(
				PortableWifiRetryableReason.STORAGE_UNAVAILABLE,
			)
		}

		when (snapshot) {
			is ImportedWifiReexportSnapshot.Outcome -> snapshot.result
			is ImportedWifiReexportSnapshot.Entry -> {
				currentCoroutineContext().ensureActive()
				sink.emit(snapshot.entry)
				ReexportImportedCapturedWifiResult.Exported(
					importRevision = snapshot.importRevision,
					entryIdentity = snapshot.entry.identity,
					contentChecksum = snapshot.entry.contentChecksum,
					physicalRunCount = snapshot.entry.runs.size,
					observationCount = snapshot.entry.runs.sumOf { it.observations.size },
				)
			}
		}
	}
}

private object RejectingLocalPortableWifiReader : ReadLocalPortableCapturedWifi {
	override suspend fun readInTransaction(
		request: ExportPortableCapturedWifiRequest,
	): ReadLocalPortableCapturedWifiResult =
		ReadLocalPortableCapturedWifiResult.Outcome(
			ExportPortableCapturedWifiResult.Unverifiable(
				PortableWifiUnverifiableReason.PHYSICAL_MEMBERSHIP_UNVERIFIABLE,
			),
		)
}

private fun originConflict() = ImportedWifiReexportSnapshot.Outcome(
	ReexportImportedCapturedWifiResult.Unverifiable(
		ImportedWifiProductFailure.ORIGIN_IDENTITY_CONFLICT,
	),
)

private sealed interface ImportedWifiReexportSnapshot {
	data class Entry(
		val importRevision: Long,
		val entry: PortableCapturedWifiEntryV1,
	) : ImportedWifiReexportSnapshot

	data class Outcome(
		val result: ReexportImportedCapturedWifiResult,
	) : ImportedWifiReexportSnapshot
}

private fun SourceEvidenceState.hasValidImportedWifiReexportShape(): Boolean =
	id == SourceEvidenceState.SINGLETON_ID && revision >= 0L && collectedDataEpoch >= 0L &&
		retainedFromMs?.let { it >= 0L } != false && deletedSourceEventHighWaterOrdinal >= 0L &&
		updatedAtMs >= 0L
