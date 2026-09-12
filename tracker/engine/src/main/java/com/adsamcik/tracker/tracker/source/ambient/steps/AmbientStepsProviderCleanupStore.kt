package com.adsamcik.tracker.tracker.source.ambient.steps

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

/**
 * Minimal provider-global removal debt that remains available after collected Room data is erased.
 * It contains neither observations nor account, device, consent, clock, or session identity.
 */
internal data class AmbientStepsProviderCleanupState(
	val pending: Set<AmbientStepsProvider>,
) {
	companion object {
		val INITIAL = AmbientStepsProviderCleanupState(emptySet())
	}
}

internal class AmbientStepsProviderCleanupStoreException(
	message: String,
	cause: Throwable? = null,
	val corrupt: Boolean,
) : IOException(message, cause)

/** App-private, no-backup authority for provider cleanup after process death or data deletion. */
@Singleton
internal class AmbientStepsProviderCleanupStore private constructor(
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
	internal fun read(): AmbientStepsProviderCleanupState {
		val input = try {
			journal.openRead()
		} catch (_: FileNotFoundException) {
			return AmbientStepsProviderCleanupState.INITIAL
		} catch (error: IOException) {
			throw AmbientStepsProviderCleanupStoreException(
				"Unable to read Ambient Steps provider cleanup journal",
				error,
				corrupt = false,
			)
		}
		return try {
			DataInputStream(input).use(::decode)
		} catch (error: AmbientStepsProviderCleanupStoreException) {
			throw error
		} catch (error: EOFException) {
			throw AmbientStepsProviderCleanupStoreException(
				"Ambient Steps provider cleanup journal is truncated",
				error,
				corrupt = true,
			)
		} catch (error: IOException) {
			throw AmbientStepsProviderCleanupStoreException(
				"Ambient Steps provider cleanup journal cannot be decoded",
				error,
				corrupt = true,
			)
		} catch (error: IllegalArgumentException) {
			throw AmbientStepsProviderCleanupStoreException(
				"Ambient Steps provider cleanup journal contains an invalid provider",
				error,
				corrupt = true,
			)
		}
	}

	@Synchronized
	internal fun addPending(provider: AmbientStepsProvider): AmbientStepsProviderCleanupState {
		val current = read()
		if (provider in current.pending) return current
		return current.copy(pending = current.pending + provider).also(::write)
	}

	@Synchronized
	internal fun removePending(provider: AmbientStepsProvider): AmbientStepsProviderCleanupState {
		val current = read()
		if (provider !in current.pending) return current
		return current.copy(pending = current.pending - provider).also(::write)
	}

	@Synchronized
	internal fun write(state: AmbientStepsProviderCleanupState) {
		val output = try {
			journal.startWrite()
		} catch (error: IOException) {
			throw AmbientStepsProviderCleanupStoreException(
				"Unable to start Ambient Steps provider cleanup journal write",
				error,
				corrupt = false,
			)
		}
		try {
			val data = DataOutputStream(output)
			data.writeInt(JOURNAL_MAGIC)
			data.writeInt(JOURNAL_VERSION)
			val pending = state.pending.sortedBy(AmbientStepsProvider::wireCode)
			data.writeInt(pending.size)
			pending.forEach { provider -> data.writeByte(provider.wireCode) }
			data.flush()
			journal.finishWrite(output)
		} catch (error: Exception) {
			journal.failWrite(output)
			if (error is AmbientStepsProviderCleanupStoreException) throw error
			throw AmbientStepsProviderCleanupStoreException(
				"Unable to persist Ambient Steps provider cleanup journal",
				error,
				corrupt = false,
			)
		}
		try {
			directorySync(checkNotNull(journal.baseFile.parentFile))
		} catch (error: Exception) {
			throw AmbientStepsProviderCleanupStoreException(
				"Unable to sync Ambient Steps provider cleanup journal directory",
				error,
				corrupt = false,
			)
		}
	}

	private fun decode(input: DataInputStream): AmbientStepsProviderCleanupState {
		if (input.readInt() != JOURNAL_MAGIC) {
			throw AmbientStepsProviderCleanupStoreException(
				"Ambient Steps provider cleanup journal has an invalid header",
				corrupt = true,
			)
		}
		val version = input.readInt()
		if (version != JOURNAL_VERSION) {
			throw AmbientStepsProviderCleanupStoreException(
				"Unsupported Ambient Steps provider cleanup journal version $version",
				corrupt = true,
			)
		}
		val count = input.readInt()
		if (count !in 0..AmbientStepsProvider.entries.size) {
			throw AmbientStepsProviderCleanupStoreException(
				"Invalid Ambient Steps provider cleanup count $count",
				corrupt = true,
			)
		}
		val pending = LinkedHashSet<AmbientStepsProvider>(count)
		repeat(count) {
			val provider = providerFromWireCode(input.readUnsignedByte())
			if (!pending.add(provider)) {
				throw AmbientStepsProviderCleanupStoreException(
					"Ambient Steps provider cleanup journal contains a duplicate provider",
					corrupt = true,
				)
			}
		}
		if (input.read() != -1) {
			throw AmbientStepsProviderCleanupStoreException(
				"Ambient Steps provider cleanup journal has trailing data",
				corrupt = true,
			)
		}
		return AmbientStepsProviderCleanupState(pending)
	}

	private companion object {
		const val JOURNAL_FILE_NAME = "ambient-steps-provider-cleanup-v1"
		const val JOURNAL_MAGIC = 0x4153504A
		const val JOURNAL_VERSION = 1

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

private val AmbientStepsProvider.wireCode: Int
	get() = when (this) {
		AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS -> 1
		AmbientStepsProvider.LOCAL_RECORDING_STEPS -> 2
	}

private fun providerFromWireCode(code: Int): AmbientStepsProvider = when (code) {
	1 -> AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS
	2 -> AmbientStepsProvider.LOCAL_RECORDING_STEPS
	else -> throw AmbientStepsProviderCleanupStoreException(
		"Unknown Ambient Steps provider cleanup code $code",
		corrupt = true,
	)
