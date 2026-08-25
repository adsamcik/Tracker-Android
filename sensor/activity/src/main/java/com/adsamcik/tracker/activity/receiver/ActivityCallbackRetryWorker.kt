package com.adsamcik.tracker.activity.receiver

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressStartContext
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressStatus
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEventIngress
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.startup.TrackingAdmissionStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tracebox.Tracebox
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Replays only source-local callback handoffs; it never owns provider cadence or registration. */
@HiltWorker
internal class ActivityCallbackRetryWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val retryOwner: ActivityCallbackRetryOwner,
	private val startupGate: TrackingStartupGate,
	private val ingressProvider: Provider<ActivityRecognitionEventIngress>,
) : CoroutineWorker(appContext, params) {
	override suspend fun doWork(): Result =
		when (
			runActivityCallbackRetryAfterStartup(
				retryOwner = retryOwner,
				reconcileStartup = { startupGate.reconcileAdmission() },
				resolveIngress = ingressProvider::get,
			)
		) {
			ActivityCallbackRetryWorkOutcome.COMPLETE -> Result.success()
			ActivityCallbackRetryWorkOutcome.RETRY -> Result.retry()
			ActivityCallbackRetryWorkOutcome.FAILED -> Result.failure()
		}
}

internal enum class ActivityCallbackRetryWorkOutcome { COMPLETE, RETRY, FAILED }

/** Keeps the Room-backed ingress graph behind the same cold-process gate as the receiver. */
internal suspend fun runActivityCallbackRetryAfterStartup(
	retryOwner: ActivityCallbackRetryOwner,
	reconcileStartup: suspend () -> TrackingAdmissionStartupResult,
	resolveIngress: () -> ActivityRecognitionEventIngress,
): ActivityCallbackRetryWorkOutcome {
	if (reconcileStartup() !is TrackingAdmissionStartupResult.Ready) {
		return ActivityCallbackRetryWorkOutcome.RETRY
	}
	return runActivityCallbackRetryWork(retryOwner, resolveIngress())
}

internal suspend fun runActivityCallbackRetryWork(
	retryOwner: ActivityCallbackRetryOwner,
	ingress: ActivityRecognitionEventIngress,
	afterFinalInventory: suspend (morePending: Boolean) -> Unit = {},
): ActivityCallbackRetryWorkOutcome {
	val ids = try {
		retryOwner.pendingIds().take(MAX_CALLBACKS_PER_RUN)
	} catch (error: ActivityCallbackRetryStoreException) {
		return if (error.corrupt) {
			ActivityCallbackRetryWorkOutcome.FAILED
		} else {
			ActivityCallbackRetryWorkOutcome.RETRY
		}
	}
	var retryRequired = false
	for (id in ids) {
		val claim = try {
			retryOwner.claim(id)
		} catch (_: FileNotFoundException) {
			continue
		} catch (error: ActivityCallbackRetryStoreException) {
			if (error.terminalRetryRecord) {
				// A read-verified callback that later corrupts or expires emits an explicit stable
				// gap code and best-effort bounded receipt before its poison file is removed.
				val recordedAndDiscarded = retryOwner.recordGapAndDiscard(id, error.code)
				Tracebox.log.warn(error.code.telemetryCode)
				if (!recordedAndDiscarded) retryRequired = true
				continue
			}
			retryRequired = true
			continue
		}
		if (claim == null) return ActivityCallbackRetryWorkOutcome.RETRY
		try {
			val replayBatch = claim.batch.copy(
				startContext = ActivityIngressStartContext.DURABLE_REPLAY,
			)
			val result = try {
				withTimeout(CALLBACK_REPLAY_ATTEMPT_TIMEOUT_MS) {
					ingress.admit(replayBatch)
				}
			} catch (_: TimeoutCancellationException) {
				retryRequired = true
				continue
			}
			val roomOwnsEveryMember = result.admittedCount + result.duplicateCount ==
				replayBatch.eventCount
			val terminallyOwnedOrRejected =
				result.status == ActivityIngressStatus.DURABLE ||
					result.status == ActivityIngressStatus.REJECTED ||
					roomOwnsEveryMember
			if (terminallyOwnedOrRejected) {
				try {
					claim.resolve()
				} catch (cancellation: CancellationException) {
					throw cancellation
				} catch (_: ActivityCallbackRetryStoreException) {
					// The exact file remains retry authority if removal could not be proven durable.
					// Re-admission is safe because the immutable callback identity is idempotent.
					retryRequired = true
				}
			} else {
				retryRequired = true
			}
		} finally {
			claim.release()
		}
	}
	val morePending = try {
		retryOwner.pendingIds().isNotEmpty()
	} catch (_: ActivityCallbackRetryStoreException) {
		true
	}
	afterFinalInventory(morePending)
	return if (retryRequired || morePending) {
		ActivityCallbackRetryWorkOutcome.RETRY
	} else {
		ActivityCallbackRetryWorkOutcome.COMPLETE
	}
}

/**
 * Coordinates durable retry files with collected-data deletion.
 *
 * Provider retirement may proceed once a callback is in this spool, but deletion first fences and
 * drains every replay claim, then durably purges the spool. The next consent epoch explicitly
 * resumes an empty owner.
 */
@Singleton
class ActivityCallbackRetryOwner @Inject internal constructor(
	private val store: ActivityCallbackRetryStore,
	private val scheduler: ActivityCallbackRetryScheduler,
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) {
	private val monitor = Any()
	private var deletionPaused = false
	private var inFlight = 0
	private var drained: CompletableDeferred<Unit>? = null

	internal suspend fun retain(batch: ActivityRecognitionEvidenceBatch): String {
		val permit = enter() ?: throw ActivityCallbackRetryStoreException(
			"Activity callback retry owner is fenced for collected-data deletion",
		)
		return try {
			val id = withContext(dispatchers.io) { store.retain(batch) }
			// The AtomicFile is already the durable owner. A scheduling failure must not turn that
			// fact back into a process-local callback, and startup retries scheduling from inventory.
			runCatching { scheduler.ensureScheduled() }
			id
		} finally {
			permit.complete()
		}
	}

	internal suspend fun resolve(id: String) {
		withContext(dispatchers.io) { store.remove(id) }
	}

	internal suspend fun pendingIds(): List<String> = withContext(dispatchers.io) { store.pendingIds() }

	internal suspend fun claim(id: String): ActivityCallbackRetryClaim? {
		val permit = enter() ?: return null
		return try {
			val batch = withContext(dispatchers.io) { store.load(id) }
			ActivityCallbackRetryClaim(id, batch, this, permit)
		} catch (error: Throwable) {
			permit.complete()
			throw error
		}
	}

	internal suspend fun recordGapAndDiscard(
		id: String,
		code: ActivityCallbackGapCode,
	): Boolean {
		val permit = enter() ?: return false
		return try {
			withContext(dispatchers.io) {
				// A gap receipt is best effort under the same low-storage condition that may have
				// damaged the record. Never let its failure turn poison into an indefinite inbox item.
				store.recordGap(id, code)
				store.remove(id)
				true
			}
		} catch (error: CancellationException) {
			throw error
		} catch (_: Exception) {
			false
		} finally {
			permit.complete()
		}
	}

	internal suspend fun recordGap(
		batch: ActivityRecognitionEvidenceBatch,
		code: ActivityCallbackGapCode,
	): Boolean {
		val permit = enter() ?: return false
		return try {
			withContext(dispatchers.io) { store.recordGap(batch, code) }
		} catch (error: CancellationException) {
			throw error
		} catch (_: Exception) {
			false
		} finally {
			permit.complete()
		}
	}

	internal suspend fun fenceAndPurgeForCollectedDataDeletion() {
		val waitForDrain = synchronized(monitor) {
			deletionPaused = true
			if (inFlight == 0) null else drained ?: CompletableDeferred<Unit>().also { drained = it }
		}
		waitForDrain?.await()
		withContext(NonCancellable + dispatchers.io) { store.purge() }
	}

	internal fun resumeAfterCollectedDataDeletion() {
		synchronized(monitor) {
			check(inFlight == 0) { "Activity callback retry resumed while replay is in flight" }
			deletionPaused = false
			drained = null
		}
	}

	internal suspend fun ensurePendingWorkScheduled() {
		if (pendingIds().isNotEmpty()) scheduler.ensureScheduled()
	}

	private fun enter(): ActivityCallbackRetryPermit? = synchronized(monitor) {
		if (deletionPaused) return@synchronized null
		inFlight += 1
		ActivityCallbackRetryPermit(::complete)
	}

	private fun complete() {
		val completion = synchronized(monitor) {
			check(inFlight > 0) { "Activity callback retry permit count underflow" }
			inFlight -= 1
			if (inFlight == 0 && deletionPaused) drained else null
		}
		completion?.complete(Unit)
	}
}

internal class ActivityCallbackRetryClaim(
	val id: String,
	val batch: ActivityRecognitionEvidenceBatch,
	private val owner: ActivityCallbackRetryOwner,
	private val permit: ActivityCallbackRetryPermit,
) {
	private var resolved = false
	private var released = false

	suspend fun resolve() {
		if (resolved) return
		owner.resolve(id)
		resolved = true
	}

	fun release() {
		if (released) return
		released = true
		permit.complete()
	}
}

internal class ActivityCallbackRetryPermit(
	private val completion: () -> Unit,
) {
	private var completed = false

	@Synchronized
	fun complete() {
		if (completed) return
		completed = true
		completion()
	}
}

@Singleton
internal class ActivityCallbackRetryScheduler internal constructor(
	private val enqueueWork: (ExistingWorkPolicy) -> Unit,
) {
	@Inject
	constructor(
		@ApplicationContext context: Context,
	) : this(
		enqueueWork = { policy -> enqueueWorkManagerRequest(context, policy) },
	)

	fun ensureScheduled() {
		// WorkManager remains stopped after a user force-stop until Android allows this app to run
		// again. This job owns an already observed fact only: startup is gated, it opens no provider,
		// and replay is explicitly denied the original callback's foreground-start context.
		// KEEP drops a request while same-name work is unfinished. REPLACE cancels that bounded
		// replay and guarantees a successor for a retain racing its final empty inventory. The file
		// remains authoritative across cancellation and immutable WAL identity makes replay safe.
		enqueueWork(ExistingWorkPolicy.REPLACE)
	}

	companion object {
		internal const val UNIQUE_WORK_NAME = "ACTIVITY.CALLBACK_RETRY"
		private const val MINIMUM_BACKOFF_SECONDS = 30L

		private fun enqueueWorkManagerRequest(
			context: Context,
			policy: ExistingWorkPolicy,
		) {
			val request = OneTimeWorkRequestBuilder<ActivityCallbackRetryWorker>()
				.setBackoffCriteria(
					BackoffPolicy.EXPONENTIAL,
					MINIMUM_BACKOFF_SECONDS,
					TimeUnit.SECONDS,
				)
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork(
				UNIQUE_WORK_NAME,
				policy,
				request,
			)
		}
	}
}

private const val MAX_CALLBACKS_PER_RUN = 32
private const val CALLBACK_REPLAY_ATTEMPT_TIMEOUT_MS = 7_500L
