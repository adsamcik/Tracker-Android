package com.adsamcik.tracker.impexp.exporter

import android.content.Context
import com.adsamcik.tracker.impexp.portable.InvalidSessionBundle
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestCodecV1
import com.adsamcik.tracker.impexp.portable.SessionBundleManifestV1
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes one explicitly chosen session file after every staged typed child has been checked. */
internal class SessionBundleArchiveWriter {
	suspend fun write(
		context: Context,
		manifest: SessionBundleManifestV1,
		children: Map<String, File>,
		output: OutputStream,
	) {
		val manifestBytes = SessionBundleManifestCodecV1.encode(manifest)
		if (manifest.children.map { it.path }.toSet() != children.keys)
			throw InvalidSessionBundle("Session data files do not match the details")
		manifest.children.forEach { declared ->
			val file = children.getValue(declared.path)
			if (!file.isFile || file.length() != declared.sizeBytes ||
				file.sha256() != declared.sha256)
				throw InvalidSessionBundle("Session data changed before it could be saved")
		}
		FileBackedExportSpool(context, "session-bundle-",
			SessionBundleManifestCodecV1.MAX_TOTAL_BYTES).use { spool ->
			when (spool.stage { staged ->
				ZipOutputStream(staged).use { zip ->
					manifest.children.forEach { declared ->
						zip.putNextEntry(ZipEntry(declared.path))
						val digest = MessageDigest.getInstance("SHA-256")
						var count = 0L
						children.getValue(declared.path).inputStream().buffered().use { source ->
							val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
							while (true) {
								val read = source.read(buffer)
								if (read < 0) break
								count += read
								if (count > declared.sizeBytes)
									throw InvalidSessionBundle("Session data changed before it could be saved")
								digest.update(buffer, 0, read)
								zip.write(buffer, 0, read)
							}
						}
						if (count != declared.sizeBytes || digest.digest().toHex() != declared.sha256)
							throw InvalidSessionBundle("Session data changed before it could be saved")
						zip.closeEntry()
					}
					zip.putNextEntry(ZipEntry(SessionBundleManifestCodecV1.MANIFEST_PATH))
					zip.write(manifestBytes)
					zip.closeEntry()
				}
			}) {
				is FileBackedExportStage.Complete -> spool.copyTo(output)
				FileBackedExportStage.EncodedSizeExceeded ->
					throw InvalidSessionBundle("Session file exceeds the size limit")
			}
		}
	}

	private fun File.sha256(): String {
		val digest = MessageDigest.getInstance("SHA-256")
		inputStream().buffered().use { stream ->
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
			while (true) {
				val read = stream.read(buffer)
				if (read < 0) break
				digest.update(buffer, 0, read)
			}
		}
		return digest.digest().toHex()
	}

	private fun ByteArray.toHex(): String = joinToString("") { byte ->
			(byte.toInt() and 0xff).toString(16).padStart(2, '0')
	}
}
