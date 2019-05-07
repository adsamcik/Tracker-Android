package com.adsamcik.signalcollector.export.activity

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.activity.DetailActivity
import com.adsamcik.signalcollector.app.dialog.DateTimeRangeDialog
import com.adsamcik.signalcollector.common.misc.SnackMaker
import com.adsamcik.signalcollector.common.misc.extension.cloneCalendar
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.export.ExportResult
import com.adsamcik.signalcollector.export.IExport
import com.appeaser.sublimepickerlibrary.helpers.SublimeOptions
import com.appeaser.sublimepickerlibrary.helpers.SublimeOptions.ACTIVATE_DATE_PICKER
import com.appeaser.sublimepickerlibrary.helpers.SublimeOptions.ACTIVATE_TIME_PICKER
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.layout_data_export.*
import kotlinx.coroutines.GlobalScope
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

	private lateinit var exporter: IExport

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

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		sharableDir = File(filesDir, SHARABLE_DIR_NAME)

		val exporterType = intent.extras!![EXPORTER_KEY] as Class<*>
		exporter = exporterType.newInstance() as IExport

		root = createLinearContentParent(false)
		layoutInflater.inflate(R.layout.layout_data_export, root)

		val now = Calendar.getInstance()

		val in15minutes = now.cloneCalendar().apply {
			add(Calendar.MINUTE, 15)
		}

		val monthBefore = now.cloneCalendar().apply {
			add(Calendar.MONTH, -1)
		}

		range = monthBefore..now

		val clickListener = { _: View ->
			DateTimeRangeDialog().apply {
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
			}.show(supportFragmentManager, "Map date range dialog")
		}

		edittext_date_range_from.setOnClickListener(clickListener)
		edittext_date_range_to.setOnClickListener(clickListener)

		button_export.setOnClickListener { if (checkExternalStoragePermissions()) exportClick() }

		button_share.setOnClickListener {
			onExport {
				val fileUri = FileProvider.getUriForFile(
						this@ExportActivity,
						"com.adsamcik.signalcollector.fileprovider",
						it.file)
				val shareIntent = Intent()
				shareIntent.action = Intent.ACTION_SEND
				shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri)
				shareIntent.type = it.mime

				val intent = Intent.createChooser(shareIntent, resources.getText(R.string.share_button))
				startActivityForResult(intent, SHARE_RESULT)
			}
		}
		setTitle(R.string.share_button)
	}

	private fun exportClick() {
		onExport { result ->
			/*MaterialFileChooser(this,
					allowBrowsing = true,
					allowCreateFolder = true,
					allowMultipleFiles = false,
					allowSelectFolder = true,
					showFiles = false,
					showFoldersFirst = true,
					showFolders = true,
					showHiddenFiles = false,
					restoreFolder = false)
					.sorter(Sorter.ByNameInDescendingOrder)
					.onSelectedFilesListener {
						val newFile = File(it.first(), result.file.name)
						result.file.renameTo(newFile)
					}
					.show()*/
		}
	}

	private fun checkExternalStoragePermissions(): Boolean {
		if (Build.VERSION.SDK_INT > 22) {
			val requiredPermissions = mutableListOf<String>()
			if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
				requiredPermissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)

			if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
				requiredPermissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)

			if (requiredPermissions.isNotEmpty())
				requestPermissions(requiredPermissions.toTypedArray(), PERMISSION_REQUEST_EXTERNAL_STORAGE)

			return requiredPermissions.isEmpty()

		}
		return false
	}

	private fun getExportFile() = edittext_filename.text?.toString()
			?: getString(R.string.export_default_file_name)

	private fun onExport(onPick: (ExportResult) -> Unit) {
		val database = AppDatabase.getDatabase(applicationContext)
		val locationDao = database.locationDao()

		val from = this.range.start
		val to = this.range.endInclusive

		GlobalScope.launch {
			sharableDir.mkdirs()
			val locations = locationDao.getAllBetween(from.timeInMillis, to.timeInMillis)

			if (locations.isEmpty()) {
				SnackMaker(root).addMessage(R.string.error_no_locations_in_interval, Snackbar.LENGTH_LONG)
				return@launch
			}

			val result = exporter.export(this@ExportActivity, locations, sharableDir, getExportFile())
			onPick(result)
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

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)

		if (requestCode == PERMISSION_REQUEST_EXTERNAL_STORAGE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
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
