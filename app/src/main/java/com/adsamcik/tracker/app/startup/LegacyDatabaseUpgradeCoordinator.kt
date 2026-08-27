package com.adsamcik.tracker.app.startup

import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseRepository
import com.adsamcik.tracker.shared.base.database.legacy.LegacyImportStatus
import com.adsamcik.tracker.shared.base.database.legacy.hasCompletedLegacyImport
import dev.tracebox.Tracebox
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface LegacyDatabaseStartupResult {
	data object Ready : LegacyDatabaseStartupResult
	data class Failed(
		val message: String,
		val requiresExplicitRetry: Boolean = false,
	) : LegacyDatabaseStartupResult
}

/** Serializes the one-time Room open that atomically creates and seeds the v27 database. */
@Singleton
class LegacyDatabaseUpgradeCoordinator @Inject constructor(
	private val databaseProvider: Provider<AppDatabase>,
	private val repository: LegacyDatabaseRepository,
) {
	private val mutex = Mutex()
	private val readyForThisProcess = AtomicBoolean(false)

	/** True only after this process has opened and validated the active database. */
	fun isReady(): Boolean = readyForThisProcess.get()

	suspend fun ensureReady(retry: Boolean = false): LegacyDatabaseStartupResult = mutex.withLock {
		var sourceExists = false
		return@withLock try {
			if (readyForThisProcess.get()) return@withLock LegacyDatabaseStartupResult.Ready
			if (retry) repository.resetForRetry()
			// Establish existence without opening SQLite. If strict inspection then fails, the
			// corrupt-but-present released vault must require explicit repair rather than an
			// unbounded automatic retry loop.
			sourceExists = repository.hasSourceDatabase()
			sourceExists = repository.inspect() != null
			if (!retry && sourceExists) {
				val state = repository.currentState()
				if (state.importStatus == LegacyImportStatus.FAILED) {
					return@withLock LegacyDatabaseStartupResult.Failed(
						state.lastError ?: "Legacy database import failed",
						requiresExplicitRetry = true,
					)
				}
			}

			// Always open the target, including after the user exported/deleted a failed source.
			// Hilt owns this Room singleton, so retrying the same helper is safer than trying to
			// delete and replace an instance already injected into services or workers.
			val raw = databaseProvider.get().openHelper.writableDatabase
			if (sourceExists && !hasCompletedLegacyImport(raw)) {
				error("The v27 database opened without a completed legacy import marker")
			}
			if (sourceExists) repository.markComplete()
			readyForThisProcess.set(true)
			LegacyDatabaseStartupResult.Ready
		} catch (error: CancellationException) {
			throw error
		} catch (error: Throwable) {
			readyForThisProcess.set(false)
			Tracebox.log.error(error, TrackerTraceboxTemplates.LEGACY_DATABASE_IMPORT_FAILED)
			repository.markFailed(error)
			LegacyDatabaseStartupResult.Failed(
				error.message ?: "Legacy database import failed",
				requiresExplicitRetry = sourceExists,
			)
		}
	}
}
