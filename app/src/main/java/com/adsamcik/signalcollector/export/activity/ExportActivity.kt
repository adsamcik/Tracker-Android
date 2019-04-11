package com.adsamcik.signalcollector.export.activity

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.FileProvider
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.activity.DetailActivity
import com.adsamcik.signalcollector.app.dialog.DateTimeRangeDialog
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.export.IExport
import com.adsamcik.signalcollector.misc.SnackMaker
import com.adsamcik.signalcollector.misc.extension.cloneCalendar
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


		button_export.setOnClickListener {

			val database = AppDatabase.getDatabase(applicationContext)
			val locationDao = database.locationDao()

			val from = this.range.start
			val to = this.range.endInclusive

			GlobalScope.launch {
				sharableDir.mkdirs()
				val locations = locationDao.getAllBetween(from.timeInMillis, to.timeInMillis)

				if (locations.isEmpty()) {
					SnackMaker(root).showSnackbar(R.string.error_no_locations_in_interval, Snackbar.LENGTH_LONG)
					return@launch
				}

				val exportFile = "FileName"
				val result = exporter.export(locations, sharableDir, exportFile)

				if (result.isSuccessful) {
					val fileUri = FileProvider.getUriForFile(
							this@ExportActivity,
							"com.adsamcik.signalcollector.fileprovider",
							result.file!!)
					val shareIntent = Intent()
					shareIntent.action = Intent.ACTION_SEND
					shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri)
					shareIntent.type = result.mime
					startActivityForResult(Intent.createChooser(shareIntent, resources.getText(R.string.export_share_button)), SHARE_RESULT)

					result.file.deleteOnExit()
				} else {
					Toast.makeText(this@ExportActivity, R.string.error_general, Toast.LENGTH_SHORT).show()
				}

				sharableDir.deleteOnExit()
			}
		}



		setTitle(R.string.export_share_button)
	}

	private fun updateDateTimeText(textView: AppCompatEditText, value: Calendar) {
		val format = SimpleDateFormat.getDateTimeInstance()
		textView.text = SpannableStringBuilder(format.format(value.time))
	}


	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)

		/*if (requestCode == SHARE_RESULT)
			DataStore.recursiveDelete(shareableDir!!)*/

		finish()
	}

	companion object {
		private const val SHARE_RESULT = 1
		private const val SHARABLE_DIR_NAME = "sharable"
		const val EXPORTER_KEY: String = "exporter"
	}
}
