package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.impexp.portable.InvalidSessionBundle
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestCodecV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestV1
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Writes one explicitly chosen session file after every staged typed child has been checked. */
internal class SessionBundleArchiveWriter {
	suspend fun write(
		context: Context,
		manifest: SessionBundleManifestV1,
		children: Map<String, File>,
		output: OutputStream,
	) = withArchive(context, manifest, children) { it.copyTo(output) }

	/** Opens the caller's destination only after the complete ZIP exists in owned staging. */
	internal suspend fun <T> withArchive(context: Context, manifest: SessionBundleManifestV1,
		children: Map<String, File>, consume: suspend (FileBackedExportSpool) -> T): T {
		val caller = currentCoroutineContext()
		caller.ensureActive()
		val manifestBytes = SessionBundleManifestCodecV1.encode(manifest)
		if (manifest.children.map { it.path }.toSet() != children.keys)
			throw InvalidSessionBundle("Session data files do not match the details")
		val checked = manifest.children.associate { declared ->
			caller.ensureActive()
			val file = children.getValue(declared.path)
			if (!file.isFile || file.length() != declared.sizeBytes)
				throw InvalidSessionBundle("Session data changed before it could be saved")
			val checksum = file.checksum(declared.sizeBytes)
			if (checksum.sizeBytes != declared.sizeBytes || checksum.sha256 != declared.sha256)
				throw InvalidSessionBundle("Session data changed before it could be saved")
			declared.path to checksum
		}
		val archiveByteBound = minOf(SessionBundleManifestCodecV1.MAX_ARCHIVE_BYTES,
			manifest.children.sumOf { it.sizeBytes } + manifestBytes.size + (manifest.children.size + 1L) * 1024L + 4096L)
		val result = FileBackedExportSpool(context, "session-bundle-", archiveByteBound,
			limits = FileBackedExportSpoolLimits(
				maximumTotalBytes = SessionBundleManifestCodecV1.MAX_ARCHIVE_BYTES * 2L,
			)).use { spool ->
			val stage = spool.stage { staged ->
				ZipOutputStream(staged).use { zip ->
					manifest.children.forEach { declared ->
						caller.ensureActive()
						val sourceFile = children.getValue(declared.path)
						zip.putNextEntry(storedEntry(declared.path, declared.sizeBytes,
							checked.getValue(declared.path).crc32))
						val digest = MessageDigest.getInstance("SHA-256")
						var count = 0L
						sourceFile.inputStream().buffered().use { source ->
							val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
							while (true) {
								caller.ensureActive()
								val read = source.read(buffer)
								caller.ensureActive()
								if (read < 0) break
								if (read == 0) continue
								count += read
								if (count > declared.sizeBytes)
									throw InvalidSessionBundle("Session data changed before it could be saved")
								digest.update(buffer, 0, read)
								zip.write(buffer, 0, read)
								caller.ensureActive()
							}
						}
						if (count != declared.sizeBytes || digest.digest().toHex() != declared.sha256)
							throw InvalidSessionBundle("Session data changed before it could be saved")
						zip.closeEntry()
					}
					caller.ensureActive()
					zip.putNextEntry(storedEntry(SessionBundleManifestCodecV1.MANIFEST_PATH,
						manifestBytes.size.toLong(), CRC32().apply { update(manifestBytes) }.value))
					zip.write(manifestBytes)
					zip.closeEntry()
					caller.ensureActive()
				}
			}
			when (stage) {
				is FileBackedExportStage.Complete -> consume(spool)
				FileBackedExportStage.EncodedSizeExceeded ->
					throw InvalidSessionBundle("Session file exceeds the size limit")
			}
		}
		caller.ensureActive()
		return result
	}

	private data class CheckedChild(val sizeBytes: Long, val sha256: String, val crc32: Long)

	private suspend fun File.checksum(maximumBytes: Long): CheckedChild {
		val caller = currentCoroutineContext()
		caller.ensureActive()
		val digest = MessageDigest.getInstance("SHA-256")
		val crc = CRC32()
		var count = 0L
		inputStream().buffered().use { stream ->
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
			while (true) {
				caller.ensureActive()
				val read = stream.read(buffer)
				caller.ensureActive()
				if (read < 0) break
				if (read == 0) continue
				count += read
				if (count > maximumBytes)
					throw InvalidSessionBundle("Session data changed before it could be saved")
				digest.update(buffer, 0, read)
				crc.update(buffer, 0, read)
			}
		}
		caller.ensureActive()
		return CheckedChild(count, digest.digest().toHex(), crc.value)
	}

	private fun storedEntry(path: String, size: Long, crc: Long): ZipEntry = ZipEntry(path).apply {
		method = ZipEntry.STORED
		this.size = size
		compressedSize = size
		this.crc = crc
	}

	private fun ByteArray.toHex(): String = joinToString("") { byte ->
			(byte.toInt() and 0xff).toString(16).padStart(2, '0')
	}
}
