package com.adsamcik.tracker.stats.data.metric

import com.adsamcik.tracker.stats.api.metric.PersistentDirtyState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Default file-backed [PersistentDirtyState].
 *
 * Format: a single newline-delimited list of table names in [filesDir]/
 * [FILE_NAME]. Writes are mutex-serialized + atomic (write to a temp file,
 * then rename) so a crash mid-write can't corrupt the live file.
 *
 * Why a flat file instead of DataStore / Room:
 *
 *  - The set is small (< 20 entries in practice — one per source table) so
 *    full rewrites are cheap (< 1 KB).
 *  - No schema migrations to maintain across app versions.
 *  - No DataStore initialization cost on the markDirty hot path.
 *  - Atomic rename gives the same crash-safety guarantees as DataStore for
 *    this trivial value shape.
 *
 * Thread safety: every read/write acquires [mutex], so concurrent markDirty
 * calls from different coroutines serialize on the file but never on the
 * in-memory CAS path (which is what's hot).
 *
 * # Dispatcher
 *
 * All file I/O is wrapped in `withContext([ioDispatcher])` as defense-in-depth.
 * Even if the caller is already on IO, this guarantees correct dispatcher usage
 * regardless of how the class is invoked.
 *
 * # Crash safety caveat
 *
 * The atomic-rename write protects against corruption from a crash *during* a
 * write. It does NOT provide fsync-level durability — if the OS hard-kills the
 * process between the caller's in-memory state change and the enqueued [add]
 * or [remove] call, that mutation is lost. This is by design; see
 * [DurableMetricDirtyTracker] class KDoc for the full crash-safety discussion.
 */
internal class DefaultPersistentDirtyState(
	private val filesDir: File,
	// Required (not defaulted): direct `Dispatchers.IO` defaults are banned by
	// ArchitecturalFitnessTest. Hilt injects `@IoDispatcher CoroutineDispatcher`
	// via StatsDataModule; tests pass a `StandardTestDispatcher`.
	private val ioDispatcher: CoroutineDispatcher,
) : PersistentDirtyState {

	private val file: File = File(filesDir, FILE_NAME)
	private val tempFile: File = File(filesDir, "$FILE_NAME.tmp")
	private val mutex = Mutex()

	override suspend fun load(): Set<String> = withContext(ioDispatcher) {
		mutex.withLock {
			if (!file.exists()) return@withLock emptySet()
			try {
				file.useLines { lines ->
					lines.map { it.trim() }
						.filter { it.isNotEmpty() }
						.toCollection(LinkedHashSet())
				}
			} catch (e: IOException) {
				// Corrupt or unreadable — return empty (the source-watermark
				// fallback in AchievementWorker will catch any missed bits on
				// the next worker run).
				emptySet()
			}
		}
	}

	override suspend fun add(tables: Set<String>) {
		if (tables.isEmpty()) return
		withContext(ioDispatcher) {
			mutex.withLock {
				val current = readUnlocked()
				val merged = current + tables.map { it.trim() }.filter { it.isNotEmpty() }
				if (merged.size == current.size) return@withLock
				writeAtomicallyUnlocked(merged)
			}
		}
	}

	override suspend fun remove(tables: Set<String>) {
		if (tables.isEmpty()) return
		withContext(ioDispatcher) {
			mutex.withLock {
				val current = readUnlocked()
				val survivors = current - tables
				if (survivors.size == current.size) return@withLock
				if (survivors.isEmpty()) {
					file.delete()
				} else {
					writeAtomicallyUnlocked(survivors)
				}
			}
		}
	}

	private fun readUnlocked(): Set<String> {
		if (!file.exists()) return emptySet()
		return try {
			file.useLines { lines ->
				lines.map { it.trim() }
					.filter { it.isNotEmpty() }
					.toCollection(LinkedHashSet())
			}
		} catch (e: IOException) {
			emptySet()
		}
	}

	private fun writeAtomicallyUnlocked(tables: Set<String>) {
		try {
			filesDir.mkdirs()
			tempFile.bufferedWriter().use { writer ->
				for (t in tables) {
					writer.write(t)
					writer.write("\n")
				}
			}
			if (!tempFile.renameTo(file)) {
				// Fallback: best-effort copy + delete (e.g. cross-device move on emulator).
				tempFile.copyTo(file, overwrite = true)
				tempFile.delete()
			}
		} catch (e: IOException) {
			// Best-effort — failure logs only; in-memory state is the source
			// of truth for the current process, and the source-watermark
			// fallback in AchievementWorker catches anything lost across
			// processes. Throwing here would propagate into markDirty hot
			// paths and break tracking.
		}
	}

	companion object {
		private const val FILE_NAME = "metric_dirty_persistence.txt"
	}
}
