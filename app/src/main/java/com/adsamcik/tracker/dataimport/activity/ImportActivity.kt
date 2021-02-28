package com.adsamcik.tracker.dataimport.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.dataimport.DataImport
import com.adsamcik.tracker.dataimport.service.ImportService
import com.adsamcik.tracker.shared.base.extension.startForegroundService
import com.anggrayudi.storage.SimpleStorage
import com.anggrayudi.storage.callback.FilePickerCallback
import com.anggrayudi.storage.callback.StorageAccessCallback
import com.anggrayudi.storage.file.StorageType
import com.anggrayudi.storage.file.absolutePath

/**
 * Import activity
 */
class ImportActivity : AppCompatActivity() {
	private val dataImport = DataImport()

	private lateinit var storage: SimpleStorage

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			addCategory(Intent.CATEGORY_OPENABLE)
			type = "*/*"

			// Optionally, specify a URI for the file that should appear in the
			// system file picker when it loads.
			putExtra(
					Intent.EXTRA_MIME_TYPES,
					dataImport.supportedImporterExtensions.map {
						MimeTypeMap.getSingleton()
								.getMimeTypeFromExtension(it)
					}.toTypedArray()
			)
		}

		val startForResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult())
		{ result: ActivityResult ->
			if (result.resultCode == Activity.RESULT_OK) {
				result.data?.data?.also { uri ->
					startForegroundService<ImportService> {
						putExtra(ImportService.ARG_FILE_URI, uri)
					}
				}
			}
			finish()

		}

		startForResult.launch(intent)

	}

	private fun setupSimpleStorage() {
		storage = SimpleStorage(this)
		storage.storageAccessCallback = object : StorageAccessCallback {
			override fun onRootPathNotSelected(
					requestCode: Int,
					rootPath: String,
					rootStorageType: StorageType,
					uri: Uri
			) {
				finish()
			}

			override fun onRootPathPermissionGranted(requestCode: Int, root: DocumentFile) {
			}

			override fun onStoragePermissionDenied(requestCode: Int) {
				finish()
			}
		}
	}

	private fun setupFilePickerCallback() {
		storage.filePickerCallback = object : FilePickerCallback {
			override fun onCancelledByUser(requestCode: Int) {
				Toast.makeText(baseContext, "File picker cancelled by user", Toast.LENGTH_SHORT)
						.show()
			}

			override fun onStoragePermissionDenied(requestCode: Int, file: DocumentFile?) {
				//requestStoragePermission()
			}

			override fun onFileSelected(requestCode: Int, file: DocumentFile) {
				startForegroundService<ImportService> {
					putExtra(ImportService.ARG_FILE_URI, file.absolutePath)
				}
			}
		}
	}

/*override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
	super.onActivityResult(requestCode, resultCode, data)
	storage.onActivityResult(requestCode, resultCode, data)
}

override fun onSaveInstanceState(outState: Bundle) {
	storage.onSaveInstanceState(outState)
	super.onSaveInstanceState(outState)
}

override fun onRestoreInstanceState(savedInstanceState: Bundle) {
	super.onRestoreInstanceState(savedInstanceState)
	storage.onRestoreInstanceState(savedInstanceState)
}*/
}
