package com.adsamcik.tracker.activity.api.registration

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Minimal identity needed to recover an ActivityRecognition PendingIntent after Room is erased. */
internal data class ActivityRegistrationCleanupKey(
	val kind: ActivityRegistrationCleanupKind,
	val sourceInstanceId: String? = null,
	val registrationGeneration: Long? = null,
) {
	init {
		when (kind) {
			ActivityRegistrationCleanupKind.BROKERED -> {
				require(!sourceInstanceId.isNullOrBlank())
				require(registrationGeneration != null && registrationGeneration > 0L)
			}
			ActivityRegistrationCleanupKind.RELEASED_V27_STATIC -> {
				require(sourceInstanceId == null)
				require(registrationGeneration == null)
			}
		}
	}

	companion object {
		val RELEASED_V27 = ActivityRegistrationCleanupKey(
			kind = ActivityRegistrationCleanupKind.RELEASED_V27_STATIC,
		)
	}
}

internal enum class ActivityRegistrationCleanupKind(val wireCode: Int) {
	BROKERED(1),
	RELEASED_V27_STATIC(2),
	;

	companion object {
		fun fromWireCode(code: Int): ActivityRegistrationCleanupKind =
			entries.firstOrNull { it.wireCode == code }
				?: throw ActivityRegistrationCleanupStoreException(
					"Unknown Activity cleanup identity kind $code",
					corrupt = true,
				)
	}
}

internal data class ActivityRegistrationCleanupState(
	val releasedV27Checked: Boolean,
	val pending: Set<ActivityRegistrationCleanupKey>,
) {
	companion object {
		val INITIAL = ActivityRegistrationCleanupState(
			releasedV27Checked = false,
			pending = emptySet(),
		)
	}
}

internal class ActivityRegistrationCleanupStoreException(
	message: String,
	cause: Throwable? = null,
	val corrupt: Boolean,
) : IOException(message, cause)

/**
 * App-private, no-backup cleanup journal.
 *
 * It deliberately contains no observation, activity type, consent, clock, or device identity.
 * A brokered row stores only the random PendingIntent instance key and its generation.
 */
@Singleton
class ActivityRegistrationCleanupStore private constructor(
	private val journal: AtomicFile,
	private val directorySync: (File) -> Unit,
) {
	@Inject
	constructor(
		@ApplicationContext context: Context,
	) : this(
		journal = AtomicFile(File(context.noBackupFilesDir, JOURNAL_FILE_NAME)),
		directorySync = ::syncDirectory,
	)

	internal constructor(
		journalFile: File,
		directorySync: (File) -> Unit = {},
	) : this(AtomicFile(journalFile), directorySync)

	@Synchronized
	internal fun read(): ActivityRegistrationCleanupState {
		val input = try {
			journal.openRead()
		} catch (_: FileNotFoundException) {
			return ActivityRegistrationCleanupState.INITIAL
		} catch (error: IOException) {
			throw ActivityRegistrationCleanupStoreException(
				"Unable to read Activity cleanup journal",
				error,
				corrupt = false,
			)
		}
		return try {
			DataInputStream(input).use(::decode)
		} catch (error: ActivityRegistrationCleanupStoreException) {
			throw error
		} catch (error: EOFException) {
			throw ActivityRegistrationCleanupStoreException(
				"Activity cleanup journal is truncated",
				error,
				corrupt = true,
			)
		} catch (error: IOException) {
			throw ActivityRegistrationCleanupStoreException(
				"Activity cleanup journal cannot be decoded",
				error,
				corrupt = true,
			)
		} catch (error: IllegalArgumentException) {
			throw ActivityRegistrationCleanupStoreException(
				"Activity cleanup journal contains an invalid identity",
				error,
				corrupt = true,
			)
		}
	}

	@Synchronized
	internal fun addPending(keys: Collection<ActivityRegistrationCleanupKey>): ActivityRegistrationCleanupState {
		if (keys.isEmpty()) return read()
		val current = read()
		val next = current.copy(pending = current.pending + keys)
		write(next)
		return next
	}

	@Synchronized
	internal fun write(state: ActivityRegistrationCleanupState) {
		val output = try {
			journal.startWrite()
		} catch (error: IOException) {
			throw ActivityRegistrationCleanupStoreException(
				"Unable to start Activity cleanup journal write",
				error,
				corrupt = false,
			)
		}
		try {
			val data = DataOutputStream(output)
			data.writeInt(JOURNAL_MAGIC)
			data.writeInt(JOURNAL_VERSION)
			data.writeBoolean(state.releasedV27Checked)
			val pending = state.pending.sortedWith(
				compareBy<ActivityRegistrationCleanupKey> { it.kind.wireCode }
					.thenBy { it.sourceInstanceId.orEmpty() }
					.thenBy { it.registrationGeneration ?: 0L },
			)
			data.writeInt(pending.size)
			pending.forEach { key ->
				data.writeByte(key.kind.wireCode)
				when (key.kind) {
					ActivityRegistrationCleanupKind.BROKERED -> {
						data.writeUTF(requireNotNull(key.sourceInstanceId))
						data.writeLong(requireNotNull(key.registrationGeneration))
					}
					ActivityRegistrationCleanupKind.RELEASED_V27_STATIC -> Unit
				}
			}
			data.flush()
			journal.finishWrite(output)
		} catch (error: Exception) {
			journal.failWrite(output)
			if (error is ActivityRegistrationCleanupStoreException) throw error
			throw ActivityRegistrationCleanupStoreException(
				"Unable to persist Activity cleanup journal",
				error,
				corrupt = false,
			)
		}
		try {
			directorySync(checkNotNull(journal.baseFile.parentFile))
		} catch (error: Exception) {
			// finishWrite already committed the new authority. Do not ask AtomicFile to roll back a
			// closed stream; surface the durability uncertainty so the caller remains fail-closed.
			throw ActivityRegistrationCleanupStoreException(
				"Unable to sync Activity cleanup journal directory",
				error,
				corrupt = false,
			)
		}
	}

	private fun decode(input: DataInputStream): ActivityRegistrationCleanupState {
		if (input.readInt() != JOURNAL_MAGIC) {
			throw ActivityRegistrationCleanupStoreException(
				"Activity cleanup journal has an invalid header",
				corrupt = true,
			)
		}
		val version = input.readInt()
		if (version != JOURNAL_VERSION) {
			throw ActivityRegistrationCleanupStoreException(
				"Unsupported Activity cleanup journal version $version",
				corrupt = true,
			)
		}
		val releasedV27Checked = input.readBoolean()
		val count = input.readInt()
		if (count !in 0..MAX_PENDING_IDENTITIES) {
			throw ActivityRegistrationCleanupStoreException(
				"Invalid Activity cleanup identity count $count",
				corrupt = true,
			)
		}
		val pending = LinkedHashSet<ActivityRegistrationCleanupKey>(count)
		repeat(count) {
			val kind = ActivityRegistrationCleanupKind.fromWireCode(input.readUnsignedByte())
			val key = when (kind) {
				ActivityRegistrationCleanupKind.BROKERED -> ActivityRegistrationCleanupKey(
					kind = kind,
					sourceInstanceId = input.readUTF(),
					registrationGeneration = input.readLong(),
				)
				ActivityRegistrationCleanupKind.RELEASED_V27_STATIC ->
					ActivityRegistrationCleanupKey.RELEASED_V27
			}
			if (!pending.add(key)) {
				throw ActivityRegistrationCleanupStoreException(
					"Activity cleanup journal contains a duplicate identity",
					corrupt = true,
				)
			}
		}
		if (input.read() != -1) {
			throw ActivityRegistrationCleanupStoreException(
				"Activity cleanup journal has trailing data",
				corrupt = true,
			)
		}
		return ActivityRegistrationCleanupState(releasedV27Checked, pending)
	}

	private companion object {
		const val JOURNAL_FILE_NAME = "activity-registration-cleanup-v1"
		const val JOURNAL_MAGIC = 0x4152434A
		const val JOURNAL_VERSION = 1
		const val MAX_PENDING_IDENTITIES = 4_096

		fun syncDirectory(directory: File) {
			val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
			try {
				Os.fsync(descriptor)
			} finally {
				Os.close(descriptor)
			}
		}
	}
}
