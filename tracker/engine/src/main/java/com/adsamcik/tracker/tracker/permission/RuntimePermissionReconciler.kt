package com.adsamcik.tracker.tracker.permission

import android.content.Context
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.fenceSourcePurposesInTransaction
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.extension.hasBackgroundLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasCellScanPermission
import com.adsamcik.tracker.shared.base.extension.hasCoarseLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasPreciseLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasReadPhonePermission
import com.adsamcik.tracker.shared.base.extension.hasWifiScanPermission
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One process-local snapshot of the permission predicates used by source planning. */
data class RuntimePermissionSnapshot(
	val hasLocationPermission: Boolean,
	val hasPreciseLocationPermission: Boolean,
	val hasCoarseLocationPermission: Boolean,
	val hasBackgroundLocationPermission: Boolean,
	val hasActivityPermission: Boolean,
	val hasReadPhonePermission: Boolean,
	val hasWifiScanPermission: Boolean,
	val hasCellScanPermission: Boolean,
) {
	companion object {
		fun capture(context: Context) = RuntimePermissionSnapshot(
			hasLocationPermission = context.hasLocationPermission,
			hasPreciseLocationPermission = context.hasPreciseLocationPermission,
			hasCoarseLocationPermission = context.hasCoarseLocationPermission,
			hasBackgroundLocationPermission = context.hasBackgroundLocationPermission,
			hasActivityPermission = context.hasActivityPermission,
			hasReadPhonePermission = context.hasReadPhonePermission,
			hasWifiScanPermission = context.hasWifiScanPermission,
			hasCellScanPermission = context.hasCellScanPermission,
		)
	}
}

/** The current-boot half-open boundary at which lost authority becomes invalid. */
data class RuntimePermissionFenceBoundary(
	val bootId: String,
	val elapsedRealtimeNanos: Long,
	val wallTimeMs: Long,
) {
	init {
		require(bootId.isNotBlank())
		require(elapsedRealtimeNanos >= 0L)
		require(wallTimeMs >= 0L)
	}
}

/** Emitted only after every required durable authorization fence has committed. */
data class RuntimePermissionChange(
	val previous: RuntimePermissionSnapshot,
	val current: RuntimePermissionSnapshot,
	val fencedSources: Set<SourceKind>,
	val fenceBoundary: RuntimePermissionFenceBoundary?,
) {
	init {
		require((fencedSources.isEmpty()) == (fenceBoundary == null))
	}
}

/**
 * Serializes runtime-permission snapshots and closes callback authority before notifying live
 * source owners. Permission grants never create demands or change policy/consent; they only emit a
 * re-evaluation signal.
 */
@Singleton
class RuntimePermissionReconciler internal constructor(
	initialSnapshot: RuntimePermissionSnapshot,
	private val databaseProvider: Provider<AppDatabase>,
	private val bootClockDomainProvider: BootClockDomainProvider,
) {
	internal constructor(
		initialSnapshot: RuntimePermissionSnapshot,
		database: AppDatabase,
		bootClockDomainProvider: BootClockDomainProvider,
	) : this(initialSnapshot, Provider { database }, bootClockDomainProvider)

	@Inject
	constructor(
		@ApplicationContext context: Context,
		databaseProvider: Provider<AppDatabase>,
		bootClockDomainProvider: BootClockDomainProvider,
	) : this(RuntimePermissionSnapshot.capture(context), databaseProvider, bootClockDomainProvider)

	private val mutex = Mutex()
	private var previousSnapshot = initialSnapshot
	private val mutableChanges = MutableSharedFlow<RuntimePermissionChange>(extraBufferCapacity = 1)
	val changes: SharedFlow<RuntimePermissionChange> = mutableChanges.asSharedFlow()

	suspend fun reconcile(current: RuntimePermissionSnapshot): RuntimePermissionChange? =
		reconcile(current) {
			RuntimePermissionFenceBoundary(
				bootId = bootClockDomainProvider.current(),
				elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
				wallTimeMs = Time.nowMillis,
			)
		}

	internal suspend fun reconcileAt(
		current: RuntimePermissionSnapshot,
		boundary: RuntimePermissionFenceBoundary,
	): RuntimePermissionChange? = reconcile(current) { boundary }

	private suspend fun reconcile(
		current: RuntimePermissionSnapshot,
		boundary: () -> RuntimePermissionFenceBoundary,
	): RuntimePermissionChange? = mutex.withLock {
		val previous = previousSnapshot
		if (current == previous) return@withLock null

		val fencedSources = lostSourceAuthorizations(previous, current)
		val fenceBoundary = if (fencedSources.isEmpty()) null else boundary()
		if (fenceBoundary != null) {
			val database = databaseProvider.get()
			database.withTransaction {
				fencedSources.sortedBy(SourceKind::stableCode).forEach { source ->
					database.fenceSourcePurposesInTransaction(
						sourceKind = source.stableCode,
						purposes = ALL_SOURCE_PURPOSES,
						bootId = fenceBoundary.bootId,
						elapsedRealtimeNanos = fenceBoundary.elapsedRealtimeNanos,
						wallTimeMs = fenceBoundary.wallTimeMs,
					)
				}
			}
		}

		val change = RuntimePermissionChange(previous, current, fencedSources, fenceBoundary)
		previousSnapshot = current
		mutableChanges.emit(change)
		change
	}

	private companion object {
		val ALL_SOURCE_PURPOSES = setOf(
			SourceBrokerPurpose.CONTROL_AUTOSTART,
			SourceBrokerPurpose.CONTROL_CONTINUATION,
			SourceBrokerPurpose.SESSION_CAPTURE,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
	}
}

/** Exact privacy-impact mapping for a true-to-false permission transition. */
internal fun lostSourceAuthorizations(
	previous: RuntimePermissionSnapshot,
	current: RuntimePermissionSnapshot,
): Set<SourceKind> = buildSet {
	if (previous.hasActivityPermission && !current.hasActivityPermission) {
		add(SourceKind.ACTIVITY)
		add(SourceKind.STEPS)
	}
	if (previous.hasPreciseLocationPermission && !current.hasPreciseLocationPermission) {
		add(SourceKind.LOCATION)
		add(SourceKind.WIFI)
		add(SourceKind.CELL)
	}
	if (previous.hasCoarseLocationPermission && !current.hasCoarseLocationPermission) {
		add(SourceKind.LOCATION)
	}
	if (previous.hasLocationPermission && !current.hasLocationPermission) {
		add(SourceKind.LOCATION)
	}
	if (previous.hasBackgroundLocationPermission && !current.hasBackgroundLocationPermission) {
		add(SourceKind.LOCATION)
	}
	if (previous.hasReadPhonePermission && !current.hasReadPhonePermission) {
		add(SourceKind.CELL)
	}
}
