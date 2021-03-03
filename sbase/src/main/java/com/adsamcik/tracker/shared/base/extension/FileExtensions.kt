package com.adsamcik.tracker.shared.base.extension

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream


/**
 * Returns file extension
 */
val DocumentFile.extension: String?
	get() = name?.substringAfterLast('.', "")

/**
 * Returns file name without extension
 */
val DocumentFile.nameWithoutExtension: String?
	get() = name?.substringBeforeLast('.', "")

/**
 * Returns true if is raw file.
 */
val Uri.isFile: Boolean
	get() = scheme == ContentResolver.SCHEME_FILE

/**
 * Returns true if document is tree document file.
 */
val Uri.isTreeDocumentFile: Boolean
	get() = path?.startsWith("/tree/") == true


/**
 * Tries to open output stream for document file.
 */
fun DocumentFile.openOutputStream(context: Context, append: Boolean = true): OutputStream? =
		uri.openOutputStream(context, append)

/**
 * Tries to open output stream for uri.
 */
@WorkerThread
fun Uri.openOutputStream(context: Context, append: Boolean = true): OutputStream? {
	val path = path ?: return null
	return try {
		if (isFile) {
			FileOutputStream(File(path), append)
		} else {
			context
					.contentResolver
					.openOutputStream(
							this,
							if (append && isTreeDocumentFile) {
								"wa"
							} else {
								"w"
							}
					)
		}
	} catch (e: FileNotFoundException) {
		null
	}
}

/**
 * Tries to open input stream for document file.
 */
fun DocumentFile.openInputStream(context: Context): InputStream? =
		uri.openInputStream(context)

/**
 * Tries to open input stream for uri.
 */
@WorkerThread
fun Uri.openInputStream(context: Context): InputStream? {
	val path = path ?: return null
	return try {
		if (isFile) {
			FileInputStream(File(path))
		} else {
			context.contentResolver.openInputStream(this)
		}
	} catch (e: FileNotFoundException) {
		null
	}
}

