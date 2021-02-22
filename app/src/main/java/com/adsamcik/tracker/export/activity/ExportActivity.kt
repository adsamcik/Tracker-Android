package com.adsamcik.tracker.export.activity

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.appcompat.widget.AppCompatEditText
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.R
import com.adsamcik.tracker.export.ExportResult
import com.adsamcik.tracker.export.Exporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.cloneCalendar
import com.adsamcik.tracker.shared.base.extension.createCalendarWithTime
import com.adsamcik.tracker.shared.base.extension.hasExternalStorageReadPermission
import com.adsamcik.tracker.shared.base.extension.hasExternalStorageWritePermission
import com.adsamcik.tracker.shared.base.misc.SnackMaker
import com.adsamcik.tracker.shared.utils.activity.DetailActivity
import com.adsamcik.tracker.shared.utils.dialog.createDateTimeDialog
import com.adsamcik.tracker.shared.utils.extension.dynamicStyle
import com.afollestad.materialdialogs.MaterialDialog
import com.anggrayudi.storage.SimpleStorage
import com.anggrayudi.storage.SimpleStorageHelper.Companion.REQUEST_CODE_STORAGE_ACCESS
import com.anggrayudi.storage.SimpleStorageHelper.Companion.requestStoragePermission
import com.anggrayudi.storage.callback.FolderPickerCallback
import com.anggrayudi.storage.callback.StorageAccessCallback
import com.anggrayudi.storage.file.StorageType
import com.anggrayudi.storage.file.absolutePath
import com.anggrayudi.storage.file.autoIncrementFileName
import com.anggrayudi.storage.file.openOutputStream
import com.anggrayudi.storage.file.storageId
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*


/**
 * Activity that allows user to share his collected data to other apps that support zip files
 */
class ExportActivity : DetailActivity() {
	private lateinit var shareableDir: File
	private lateinit var root: ViewGroup

	private lateinit var exporter: Exporter

	private val dataRangeFrom: AppCompatEditText by lazy { findViewById(R.id.edittext_date_range_from) }
	private val dataRangeTo: AppCompatEditText by lazy { findViewById(R.id.edittext_date_range_to) }

	private var range: ClosedRange<Calendar> = createDefaultRange()
		set(value) {
			field = value
			updateDateTimeText(dataRangeFrom, value.start)
			updateDateTimeText(dataRangeTo, value.endInclusive)
		}

	private lateinit var storage: SimpleStorage

	private fun setupSimpleStorage() {
		storage = SimpleStorage(this)
		storage.storageAccessCallback = object : StorageAccessCallback {
			override fun onRootPathNotSelected(
					requestCode: Int,
					rootPath: String,
					rootStorageType: StorageType,
					uri: Uri
			) {
				MaterialDialog(this@ExportActivity)
						.message(text = "Please select $rootPath")
						.negativeButton(android.R.string.cancel)
						.positiveButton {
							storage.requestStorageAccess(
									REQUEST_CODE_STORAGE_ACCESS,
									rootStorageType
							)
						}.show()
			}

			override fun onRootPathPermissionGranted(requestCode: Int, root: DocumentFile) {
				Toast.makeText(
						baseContext,
						"Storage access has been granted for ${root.storageId}",
						Toast.LENGTH_SHORT
				).show()
			}

			override fun onStoragePermissionDenied(requestCode: Int) {

			}
		}
	}

	//init block cannot be used with custom setter (Kotlin 1.3)
	private fun createDefaultRange(): ClosedRange<Calendar> {
		val now = Calendar.getInstance()
		val monthAgo = now.cloneCalendar().apply {
			add(Calendar.MONTH, -1)
		}

		return monthAgo..now
	}

	override fun onConfigure(configuration: Configuration) {
		configuration.useColorControllerForContent = true
	}

	private val clickListener: (View) -> Unit = { view: View ->
		launch(Dispatchers.Default) {
			val sessionDao = AppDatabase.database(view.context).sessionDao()
			val availableRange = sessionDao.range().let {
				if (it.start == 0L && it.endInclusive == 0L) {
					LongRange.EMPTY
				} else {
					LongRange(it.start, it.endInclusive)
				}
			}

			if (availableRange.isEmpty()) {
				//todo improve this message to be properly shown in time
				SnackMaker(root).addMessage(R.string.settings_export_no_data)
				return@launch
			}

			val selectedRange = LongRange(
					range.start.timeInMillis,
					range.endInclusive.timeInMillis
			)
			launch(Dispatchers.Main) {
				createDateTimeDialog(styleController, availableRange, selectedRange) {
					range = createCalendarWithTime(it.first)..createCalendarWithTime(
							it.last + Time.DAY_IN_MILLISECONDS - Time.SECOND_IN_MILLISECONDS
					)
				}
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		shareableDir = File(filesDir, SHARABLE_DIR_NAME)

		val exporterType = requireNotNull(intent.extras)[EXPORTER_KEY] as Class<*>
		exporter = exporterType.newInstance() as Exporter

		//root = createLinearContentParent(false)
		val root = inflateContent<ConstraintLayout>(R.layout.layout_data_export)


		if (exporter.canSelectDateRange) {
			val now = Calendar.getInstance()

			val in15minutes = now.cloneCalendar().apply {
				add(Calendar.MINUTE, 15)
			}

			val monthBefore = now.cloneCalendar().apply {
				add(Calendar.MONTH, -1)
			}

			range = monthBefore..in15minutes

			dataRangeFrom.setOnClickListener(clickListener)
			dataRangeTo.setOnClickListener(clickListener)

		} else {
			dataRangeFrom.visibility = View.GONE
			dataRangeTo.visibility = View.GONE
			findViewById<View>(R.id.imageview_from_date).visibility = View.GONE
		}

		findViewById<View>(R.id.button_export).setOnClickListener { if (checkExternalStoragePermissions()) exportClick() }

		findViewById<View>(R.id.button_share).setOnClickListener {
			shareableDir.mkdirs()
			tryExport(DocumentFile.fromFile(shareableDir)) { exportResult, documentFile ->
				if (!exportResult.isSuccess) return@tryExport

				val fileUri = FileProvider.getUriForFile(
						this@ExportActivity,
						"com.adsamcik.tracker.fileprovider",
						File(documentFile.absolutePath)
				)

				val shareIntent = Intent().apply {
					action = Intent.ACTION_SEND
					putExtra(Intent.EXTRA_STREAM, fileUri)
					type = exporter.mimeType
				}

				val intent = Intent.createChooser(
						shareIntent,
						resources.getText(R.string.share_button)
				)
				startActivityForResult(intent, SHARE_RESULT)
			}
		}
		setTitle(R.string.share_button)

		setupSimpleStorage()
		setupFolderPickerCallback()
	}

	private fun setupFolderPickerCallback() {
		storage.folderPickerCallback = object : FolderPickerCallback {
			override fun onStoragePermissionDenied(requestCode: Int) {
				//requestStoragePermission(this@ExportActivity)
			}

			override fun onStorageAccessDenied(
					requestCode: Int,
					folder: DocumentFile?,
					storageType: StorageType?
			) {
				if (storageType == null) {
					requestStoragePermission(this@ExportActivity) {
						setupFolderPickerCallback()
					}
					return
				}
				MaterialDialog(this@ExportActivity)
						.message(
								text = "You have no write access to this storage, thus selecting this folder is useless." +
										"\nWould you like to grant access to this folder?"
						)
						.negativeButton(android.R.string.cancel)
						.positiveButton {
							storage.requestStorageAccess(REQUEST_CODE_STORAGE_ACCESS, storageType)
						}.show()
			}

			override fun onFolderSelected(requestCode: Int, folder: DocumentFile) {
				//Toast.makeText(baseContext, folder.absolutePath, Toast.LENGTH_SHORT).show()
				tryExport(folder)
			}

			override fun onCancelledByUser(requestCode: Int) {
				Toast.makeText(baseContext, "Folder picker cancelled by user", Toast.LENGTH_SHORT)
						.show()
			}
		}
	}

	private fun exportClick() {
		/*MaterialDialog(this).show {
			folderChooser(
					context = this@ExportActivity,
					waitForPositiveButton = true,
					allowFolderCreation = true
			) { _, file ->
				export(file)
			}
		}*/
		storage.openFolderPicker(REQUEST_CODE_STORAGE_ACCESS)
	}

	override fun onSaveInstanceState(outState: Bundle) {
		storage.onSaveInstanceState(outState)
		super.onSaveInstanceState(outState)
	}

	override fun onRestoreInstanceState(savedInstanceState: Bundle) {
		super.onRestoreInstanceState(savedInstanceState)
		storage.onRestoreInstanceState(savedInstanceState)
	}

	private fun checkExternalStoragePermissions(): Boolean {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			val requiredPermissions = mutableListOf<String>()
			if (!hasExternalStorageReadPermission) {
				requiredPermissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
			}

			if (!hasExternalStorageWritePermission) {
				requiredPermissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
			}

			if (requiredPermissions.isNotEmpty()) {
				requestPermissions(
						requiredPermissions.toTypedArray(),
						PERMISSION_REQUEST_EXTERNAL_STORAGE
				)
			}

			return requiredPermissions.isEmpty()
		}
		return true
	}

	private fun getExportFileName(): String {
		val text = findViewById<AppCompatEditText>(R.id.edittext_filename).text
		return if (text.isNullOrBlank()) {
			getString(R.string.export_default_file_name)
		} else {
			text.toString()
		}
	}

	private fun trimName(fileNameWithExtension: String): String {
		val extension = MimeTypeMap
				.getSingleton()
				.getExtensionFromMimeType(exporter.mimeType)
				.orEmpty()

		return if (fileNameWithExtension.endsWith(".$extension")) {
			fileNameWithExtension.substring(
					0,
					fileNameWithExtension.length - extension.length - 1
			)
		} else {
			fileNameWithExtension
		}
	}

	@MainThread
	private fun tryExport(
			directory: DocumentFile,
			onPick: ((ExportResult, DocumentFile) -> Unit)? = null
	) {
		val fileName = getExportFileName()
		val fileNameWithExtension = "${fileName}.${exporter.extension}"
		val foundFile = directory.findFile(fileNameWithExtension)

		if (foundFile != null) {
			Assist.ensureLooper()
			MaterialDialog(this@ExportActivity)
					.show {
						message(text = "Do you want to override the existing file $fileNameWithExtension?")
						title(text = "File already exists!")
						positiveButton(R.string.yes) {
							startExport(foundFile, onPick)
						}
						negativeButton(R.string.no) {
							val incremented = directory.autoIncrementFileName(fileNameWithExtension)
							exportToNewFile(directory, incremented, onPick)
						}
						dynamicStyle()
					}
		} else {
			exportToNewFile(directory, fileNameWithExtension, onPick)
		}
	}

	private fun exportToNewFile(
			directory: DocumentFile,
			fileNameWithExtension: String,
			onPick: ((ExportResult, DocumentFile) -> Unit)?
	) {
		launch(Dispatchers.Default) {
			val trimmedName = trimName(fileNameWithExtension)

			val createdFile = directory.createFile(exporter.mimeType, trimmedName)
					?: throw IOException("Could not access or create file $fileNameWithExtension")
			startExport(createdFile, onPick)
		}
	}

	/**
	 * Starting point for export, when file is selected
	 */
	@AnyThread
	private fun startExport(
			file: DocumentFile,
			onPick: ((ExportResult, DocumentFile) -> Unit)? = null
	) {
		launch(Dispatchers.Default) {
			val result = exportStream(file)
			notifyExportResult(result, file, onPick)
		}
	}

	/**
	 *  Handles result from export.
	 */
	private fun notifyExportResult(
			result: ExportResult,
			file: DocumentFile,
			onPick: ((ExportResult, DocumentFile) -> Unit)? = null
	) {
		onPick?.invoke(result, file)

		if (result.isSuccess) {
			finish()
		} else {
			SnackMaker(root).addMessage(
					requireNotNull(result.message),
					Snackbar.LENGTH_LONG
			)
		}
	}

	@WorkerThread
	private fun exportStream(
			file: DocumentFile
	): ExportResult {
		val stream = file.openOutputStream(this, append = false)
		if (stream != null) {
			stream.use {
				return export(it)
			}
		} else {
			return ExportResult(false, "Stream to file ${file.absolutePath} could not be created.")
		}
	}

	@WorkerThread
	private fun export(outputStream: OutputStream): ExportResult {
		return if (exporter.canSelectDateRange) {
			val database = AppDatabase.database(applicationContext)
			val locationDao = database.locationDao()
			val from = this.range.start
			val to = this.range.endInclusive
			val locations = locationDao.getAllBetween(from.timeInMillis, to.timeInMillis)

			if (locations.isEmpty()) {
				return ExportResult(false, getString(R.string.error_no_locations_in_interval))
			}
			//todo do not pass location, make it somewhat smarter so there are no useless queries
			exporter.export(this, locations, outputStream)
		} else {
			exporter.export(this, listOf(), outputStream)
		}
	}

	private fun updateDateTimeText(textView: AppCompatEditText, value: Calendar) {
		val format = SimpleDateFormat.getDateTimeInstance()
		textView.text = SpannableStringBuilder(format.format(value.time))
	}


	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)

		storage.onActivityResult(requestCode, resultCode, data)

		shareableDir.deleteRecursively()
	}

	override fun onRequestPermissionsResult(
			requestCode: Int,
			permissions: Array<out String>,
			grantResults: IntArray
	) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)

		if (requestCode == PERMISSION_REQUEST_EXTERNAL_STORAGE &&
				grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
			exportClick()
		}
	}

	companion object {
		private const val SHARE_RESULT = 1
		private const val SHARABLE_DIR_NAME = "sharable"

		private const val PERMISSION_REQUEST_EXTERNAL_STORAGE = 1

		const val EXPORTER_KEY: String = "exporter"
	}
}

