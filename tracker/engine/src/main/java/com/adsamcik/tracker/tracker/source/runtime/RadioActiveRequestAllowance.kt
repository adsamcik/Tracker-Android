package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.SourceKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A live session may spend one active radio request; passive delivery has no allowance. */
internal sealed interface RadioActiveRequestDecision {
	data class ReadyAt(val elapsedRealtimeMs: Long) : RadioActiveRequestDecision
	data object Spent : RadioActiveRequestDecision
	data object Unavailable : RadioActiveRequestDecision
}

internal fun SourceRegistration.singleCaptureSessionId(): String? = authorization.authorizedMembers
	.singleOrNull { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }?.logicalTrackingId

/** Synchronous preference commits make a crash immediately before the provider request spend it. */
@Singleton
class RadioActiveRequestAllowance @Inject constructor(
	@ApplicationContext context: Context,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
	private val preferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
		context.getSharedPreferences("radio_active_request_allowance", Context.MODE_PRIVATE)
	}
	private val gate = Mutex()

	internal suspend fun inspect(
		source: SourceKind,
		logicalTrackingId: String,
		minimumIntervalMs: Long,
		bootId: String,
		nowElapsedRealtimeMs: Long,
		nowWallTimeMs: Long,
	): RadioActiveRequestDecision = gate.withLock {
		try {
			withContext(ioDispatcher) {
				decision(source, logicalTrackingId, minimumIntervalMs, bootId,
					nowElapsedRealtimeMs, nowWallTimeMs)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			RadioActiveRequestDecision.Unavailable
		}
	}

	internal suspend fun reserve(
		source: SourceKind,
		logicalTrackingId: String,
		minimumIntervalMs: Long,
		bootId: String,
		nowElapsedRealtimeMs: Long,
		nowWallTimeMs: Long,
	): RadioActiveRequestDecision = gate.withLock {
		try {
			withContext(ioDispatcher) {
				val result = decision(source, logicalTrackingId, minimumIntervalMs, bootId,
					nowElapsedRealtimeMs, nowWallTimeMs)
				if (result !is RadioActiveRequestDecision.ReadyAt ||
					result.elapsedRealtimeMs > nowElapsedRealtimeMs) return@withContext result
				val prefix = prefix(source)
				if (!preferences.edit()
						.putString("${prefix}_session", logicalTrackingId)
						.putString("${prefix}_boot", bootId)
						.putLong("${prefix}_elapsed", nowElapsedRealtimeMs)
						.putLong("${prefix}_wall", nowWallTimeMs)
						.commit()) RadioActiveRequestDecision.Unavailable else result
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			RadioActiveRequestDecision.Unavailable
		}
	}

	private fun decision(
		source: SourceKind,
		logicalTrackingId: String,
		minimumIntervalMs: Long,
		bootId: String,
		nowElapsedRealtimeMs: Long,
		nowWallTimeMs: Long,
	): RadioActiveRequestDecision {
		if (source != SourceKind.WIFI && source != SourceKind.CELL ||
			logicalTrackingId.isBlank() || bootId.isBlank() || minimumIntervalMs < 0L ||
			nowElapsedRealtimeMs < 0L || nowWallTimeMs < 0L) return RadioActiveRequestDecision.Unavailable
		val prefix = prefix(source)
		if (preferences.getString("${prefix}_session", null) == logicalTrackingId) {
			return RadioActiveRequestDecision.Spent
		}
		val priorBoot = preferences.getString("${prefix}_boot", null)
			?: return RadioActiveRequestDecision.ReadyAt(nowElapsedRealtimeMs)
		val priorElapsed = preferences.getLong("${prefix}_elapsed", -1L)
		val priorWall = preferences.getLong("${prefix}_wall", -1L)
		if (priorElapsed < 0L || priorWall < 0L) return RadioActiveRequestDecision.Unavailable
		val remaining = if (priorBoot == bootId) {
			if (nowElapsedRealtimeMs < priorElapsed) return RadioActiveRequestDecision.Unavailable
			(minimumIntervalMs - (nowElapsedRealtimeMs - priorElapsed)).coerceAtLeast(0L)
		} else {
			(minimumIntervalMs - (nowWallTimeMs - priorWall).coerceAtLeast(0L)).coerceAtLeast(0L)
		}
		val deadline = if (remaining > Long.MAX_VALUE - nowElapsedRealtimeMs) Long.MAX_VALUE
			else nowElapsedRealtimeMs + remaining
		return RadioActiveRequestDecision.ReadyAt(deadline)
	}

	private fun prefix(source: SourceKind): String = "source_${source.stableCode}"
}
