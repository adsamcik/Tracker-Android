package com.adsamcik.tracker.export.activity

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.FileProvider
import com.adsamcik.tracker.R
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.activity.DetailActivity
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.dialog.dateTimeRangePicker
import com.adsamcik.tracker.common.extension.cloneCalendar
import com.adsamcik.tracker.common.extension.createCalendarWithTime
import com.adsamcik.tracker.common.extension.hasExternalStorageReadPermission
import com.adsamcik.tracker.common.extension.hasExternalStorageWritePermission
import com.adsamcik.tracker.common.misc.SnackMaker
import com.adsamcik.tracker.export.ExportResult
import com.adsamcik.tracker.export.Exporter
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.files.folderChooser
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.layout_data_export.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*


/**
 * Activity that allows user to share his collected data to other apps that support zip files
 */
class ExportActivity : DetailActivity() {
	private lateinit var sharableDir: File
	private lateinit var root: ViewGroup

	private lateinit var exporter: Exporter

	private var range: ClosedRange<Calendar> = createDefaultRange()
		set(value) {
			field = value
			updateDateTimeText(edittext_date_range_from, value.start)
			updateDateTimeText(edittext_date_range_to, value.endInclusive)
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

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		sharableDir = File(filesDir, SHARABLE_DIR_NAME)

		val exporterType = requireNotNull(intent.extras)[EXPORTER_KEY] as Class<*>
		exporter = exporterType.newInstance() as Exporter

		root = createLinearContentParent(false)
		layoutInflater.inflate(R.layout.layout_data_export, root)


		if (exporter.canSelectDateRange) {
			val now = Calendar.getInstance()

			val in15minutes = now.cloneCalendar().apply {
				add(Calendar.MINUTE, 15)
			}

			val monthBefore = now.cloneCalendar().apply {
				add(Calendar.MONTH, -1)
			}

			range = monthBefore..in15minutes


			val clickListener: (View) -> Unit = { view: View ->
				launch(Dispatchers.Default) {
					val sessionDao = AppDatabase.getDatabase(view.context).sessionDao()
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
						MaterialDialog(view.context).dateTimeRangePicker(
								availableRange,
								selectedRange
						) {
							range = createCalendarWithTime(it.first)..createCalendarWithTime(
									it.last + Time.DAY_IN_MILLISECONDS - Time.SECOND_IN_MILLISECONDS
							)
						}.show()
					}
				}

				/*DateTimeRangeDialog().apply {
					arguments = Bundle().apply {
						putParcelable(DateTimeRangeDialog.ARG_OPTIONS, SublimeOptions().apply {
							setCanPickDateRange(true)
							setDateParams(range.start, range.endInclusive)
							setDisplayOptions(ACTIVATE_DATE_PICKER.or(ACTIVATE_TIME_PICKER))
							setDateRange(-1L, in15minutes.timeInMillis)
							pickerToShow = SublimeOptions.Picker.DATE_PICKER
							setAnimateLayoutChanges(true)
						})
					}
					successCallback = { range ->
						this@ExportActivity.range = range
					}
				}.show(supportFragmentManager, "Map date range dialog")*/
			}

			edittext_date_range_from.setOnClickListener(clickListener)
			edittext_date_range_to.setOnClickListener(clickListener)

		} else {
			edittext_date_range_from.visibility = View.GONE
			edittext_date_range_to.visibility = View.GONE
			imageview_from_date.visibility = View.GONE
		}

		button_export.setOnClickListener { if (checkExternalStoragePermissions()) exportClick() }

		button_share.setOnClickListener {
			sharableDir.mkdirs()
			export(sharableDir) {
				val fileUri = FileProvider.getUriForFile(
						this@ExportActivity,
						"com.adsamcik.tracker.fileprovider",
						it.file
				)
				val shareIntent = Intent()
				shareIntent.action = Intent.ACTION_SEND
				shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri)
				shareIntent.type = it.mime

				val intent = Intent.createChooser(
						shareIntent,
						resources.getText(R.string.share_button)
				)
				startActivityForResult(intent, SHARE_RESULT)
			}
		}
		setTitle(R.string.share_button)
	}

	private fun exportClick() {
		MaterialDialog(this).show {
			folderChooser(waitForPositiveButton = true, allowFolderCreation = true) { _, file ->
				export(file)
			}
		}
	}

	private fun checkExternalStoragePermissions(): Boolean {
		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {
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
		return false
	}

	private fun getExportFileName(): String {
		val text = edittext_filename.text
		return if (text.isNullOrBlank()) {
			getString(R.string.export_default_file_name)
		} else {
			text.toString()
		}
	}

	private fun export(directory: File, onPick: ((ExportResult) -> Unit)? = null) {
		val from = this.range.start
		val to = this.range.endInclusive

		launch(Dispatchers.Default) {
			val database = AppDatabase.getDatabase(applicationContext)
			val locationDao = database.locationDao()

			val result = if (exporter.canSelectDateRange) {
				val locations = locationDao.getAllBetween(from.timeInMillis, to.timeInMillis)

				if (locations.isEmpty()) {
					SnackMaker(root).addMessage(
							R.string.error_no_locations_in_interval,
							Snackbar.LENGTH_LONG
					)
					return@launch
				}
				//todo do not pass location, make it somewhat smarter so there are no useless queries
				exporter.export(this@ExportActivity, locations, directory, getExportFileName())
			} else {
				exporter.export(this@ExportActivity, listOf(), directory, getExportFileName())
			}


			onPick?.invoke(result)

			finish()
		}
	}

	private fun updateDateTimeText(textView: AppCompatEditText, value: Calendar) {
		val format = SimpleDateFormat.getDateTimeInstance()
		textView.text = SpannableStringBuilder(format.format(value.time))
	}


	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)

		sharableDir.deleteRecursively()

		finish()
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

