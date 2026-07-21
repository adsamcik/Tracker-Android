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
 * Format: newline-delimited `generation<TAB>table` entries in [filesDir]/
 * [FILE_NAME]. Legacy table-only lines are read as generation zero. Writes are
 * mutex-serialized + atomic (write to a temp file,
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
 * or [acknowledge] call, that mutation is lost. This is by design; see
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

	override suspend fun load(): Map<String, Long> = withContext(ioDispatcher) {
		mutex.withLock {
			if (!file.exists()) return@withLock emptyMap()
			try {
				file.useLines { lines ->
					lines.mapNotNull(::parseLine).toMap()
				}
			} catch (e: IOException) {
				// Corrupt or unreadable state must never break tracking.
				emptyMap()
			}
		}
	}

	override suspend fun add(generations: Map<String, Long>) {
		if (generations.isEmpty()) return
		withContext(ioDispatcher) {
			mutex.withLock {
				val current = readUnlocked()
				val merged = current.toMutableMap()
				for ((rawTable, generation) in generations) {
					val table = rawTable.trim()
					if (table.isNotEmpty() && generation > (merged[table] ?: Long.MIN_VALUE)) {
						merged[table] = generation
					}
				}
				if (merged == current) return@withLock
				writeAtomicallyUnlocked(merged)
			}
		}
	}

	override suspend fun acknowledge(generations: Map<String, Long>) {
		if (generations.isEmpty()) return
		withContext(ioDispatcher) {
			mutex.withLock {
				val current = readUnlocked()
				val survivors = current.filter { (table, currentGeneration) ->
					currentGeneration > (generations[table] ?: Long.MIN_VALUE)
				}
				if (survivors.size == current.size) return@withLock
				if (survivors.isEmpty()) {
					file.delete()
				} else {
					writeAtomicallyUnlocked(survivors)
				}
			}
		}
	}

	private fun readUnlocked(): Map<String, Long> {
		if (!file.exists()) return emptyMap()
		return try {
			file.useLines { lines ->
				lines.mapNotNull(::parseLine).toMap()
			}
		} catch (e: IOException) {
			emptyMap()
		}
	}

	private fun writeAtomicallyUnlocked(generations: Map<String, Long>) {
		try {
			filesDir.mkdirs()
			tempFile.bufferedWriter().use { writer ->
				for ((table, generation) in generations) {
					writer.write(generation.toString())
					writer.write("\t")
					writer.write(table)
					writer.write("\n")
				}
			}
			if (!tempFile.renameTo(file)) {
				// Fallback: best-effort copy + delete (e.g. cross-device move on emulator).
				tempFile.copyTo(file, overwrite = true)
				tempFile.delete()
			}
		} catch (e: IOException) {
			// Best-effort: in-memory state remains valid for this process. Throwing
			// here would propagate into markDirty hot paths and break tracking.
		}
	}

	private fun parseLine(rawLine: String): Pair<String, Long>? {
		val line = rawLine.trim()
		if (line.isEmpty()) return null
		val separator = line.indexOf('\t')
		if (separator < 0) return line to 0L
		val generation = line.substring(0, separator).toLongOrNull() ?: return null
		val table = line.substring(separator + 1).trim()
		return table.takeIf { it.isNotEmpty() }?.let { it to generation }
	}

	companion object {
		private const val FILE_NAME = "metric_dirty_persistence.txt"
	}
}
