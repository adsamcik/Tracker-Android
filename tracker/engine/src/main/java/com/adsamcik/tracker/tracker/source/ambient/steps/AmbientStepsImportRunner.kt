package com.adsamcik.tracker.tracker.source.ambient.steps

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciler
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/** One bounded copy attempt; the existing importer owns durable progress and count identity. */
enum class AmbientStepsImportAttemptResult { COMPLETE, DEFERRED, RETRY }

/**
 * Runs one bounded system Steps import after shared setup and current-purpose checks, preserving the
 * cursor for later attempts.
 */
@Singleton
class AmbientStepsImportRunner internal constructor(
	private val prepare: suspend () -> Boolean,
	private val importAllowed: suspend () -> Boolean,
	private val boundary: () -> AmbientStepsImportBoundary,
	private val importNext: suspend (AmbientStepsImportBoundary) -> AmbientStepsImportResult,
	private val timeoutMillis: Long = ATTEMPT_TIMEOUT_MILLIS,
) {
	@Inject
	internal constructor(
		startupGate: TrackingStartupGate,
		reconciler: TrackingPurposeSettingsReconciler,
		capabilityResolver: AndroidAmbientStepsCapabilityResolver,
		clock: Clock,
		bootClockDomainProvider: BootClockDomainProvider,
		importer: AmbientStepsFactImporter,
	) : this(
		prepare = {
			if (startupGate.reconcile() !is TrackingStartupResult.Ready || !startupGate.permitsNewCollection) {
				false
			} else {
				// Another source's repair debt must not block independently authorized Steps.
				reconciler.reconcileCurrentSettings()
				startupGate.permitsNewCollection
			}
		},
		importAllowed = {
			val capability = capabilityResolver.resolve() as? AmbientStepsCapability.ReadyForRegistration
			startupGate.permitsNewCollection && capability != null &&
				(capability.importAccess == AmbientStepsImportAccess.BACKGROUND_ALLOWED ||
					ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
		},
		boundary = {
			val now = clock.currentTimeMillis()
			AmbientStepsImportBoundary(bootClockDomainProvider.current(), clock.elapsedRealtimeNanos(),
				now, now - now % MILLISECONDS_PER_SECOND, ZoneId.systemDefault())
		},
		importNext = importer::importNext,
	)

	private val mutex = Mutex()

	/** Timeout includes waiting for another attempt and startup/provider reconciliation. */
	suspend fun run(): AmbientStepsImportAttemptResult = try {
		withTimeoutOrNull(timeoutMillis) {
			mutex.withLock {
				if (!prepare()) {
					return@withLock AmbientStepsImportAttemptResult.DEFERRED
				}
				val currentBoundary = boundary()
				repeat(MAX_WINDOWS_PER_ATTEMPT) {
					if (!importAllowed()) {
						return@withLock AmbientStepsImportAttemptResult.DEFERRED
					}
					importNext(currentBoundary).attemptDisposition(currentBoundary.throughTimeMs)?.let { disposition ->
						return@withLock disposition
					}
					yield()
				}
				AmbientStepsImportAttemptResult.RETRY
			}
		} ?: AmbientStepsImportAttemptResult.RETRY
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		AmbientStepsImportAttemptResult.RETRY
	}

	private fun AmbientStepsImportResult.attemptDisposition(throughTimeMs: Long): AmbientStepsImportAttemptResult? =
		when (this) {
			is AmbientStepsImportResult.Applied -> {
				if (window.endTimeMs >= throughTimeMs) {
					AmbientStepsImportAttemptResult.COMPLETE
				} else {
					null
				}
			}
			is AmbientStepsImportResult.Unchanged -> {
				if (window.endTimeMs >= throughTimeMs) {
					AmbientStepsImportAttemptResult.COMPLETE
				} else {
					null
				}
			}
			is AmbientStepsImportResult.Gap -> {
				if (toTimeMs >= throughTimeMs) {
					AmbientStepsImportAttemptResult.COMPLETE
				} else {
					null
				}
			}
			is AmbientStepsImportResult.NoEvidence -> AmbientStepsImportAttemptResult.COMPLETE
			is AmbientStepsImportResult.Ineligible -> AmbientStepsImportAttemptResult.DEFERRED
			is AmbientStepsImportResult.Retryable,
			is AmbientStepsImportResult.Stale -> AmbientStepsImportAttemptResult.RETRY
		}

	internal companion object {
		const val ATTEMPT_TIMEOUT_MILLIS = 30_000L
		const val MAX_WINDOWS_PER_ATTEMPT = 32
	}
}

private const val MILLISECONDS_PER_SECOND = 1_000L
