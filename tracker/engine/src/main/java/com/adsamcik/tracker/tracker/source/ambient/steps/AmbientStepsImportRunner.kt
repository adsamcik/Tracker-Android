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
				now, now - now % 1_000L, ZoneId.systemDefault())
		},
		importNext = importer::importNext,
	)

	private val mutex = Mutex()

	/** Timeout includes waiting for another attempt and startup/provider reconciliation. */
	suspend fun run(): AmbientStepsImportAttemptResult = try {
		withTimeoutOrNull(timeoutMillis) {
			mutex.withLock {
				if (!prepare()) return@withLock AmbientStepsImportAttemptResult.DEFERRED
				val currentBoundary = boundary()
				repeat(MAX_WINDOWS_PER_ATTEMPT) {
					if (!importAllowed()) return@withLock AmbientStepsImportAttemptResult.DEFERRED
					when (val result = importNext(currentBoundary)) {
						is AmbientStepsImportResult.Applied -> {
							if (result.window.endTimeMs >= currentBoundary.throughTimeMs) {
								return@withLock AmbientStepsImportAttemptResult.COMPLETE
							}
						}
						is AmbientStepsImportResult.Unchanged -> {
							if (result.window.endTimeMs >= currentBoundary.throughTimeMs) {
								return@withLock AmbientStepsImportAttemptResult.COMPLETE
							}
						}
						is AmbientStepsImportResult.Gap -> {
							if (result.toTimeMs >= currentBoundary.throughTimeMs) {
								return@withLock AmbientStepsImportAttemptResult.COMPLETE
							}
						}
						is AmbientStepsImportResult.NoEvidence -> return@withLock AmbientStepsImportAttemptResult.COMPLETE
						is AmbientStepsImportResult.Ineligible -> return@withLock AmbientStepsImportAttemptResult.DEFERRED
						is AmbientStepsImportResult.Retryable,
						is AmbientStepsImportResult.Stale -> return@withLock AmbientStepsImportAttemptResult.RETRY
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

	internal companion object {
		const val ATTEMPT_TIMEOUT_MILLIS = 30_000L
		const val MAX_WINDOWS_PER_ATTEMPT = 32
	}
}
