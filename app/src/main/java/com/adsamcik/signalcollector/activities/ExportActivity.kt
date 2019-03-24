package com.adsamcik.signalcollector.activities

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.format.DateFormat
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.FileProvider
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.exports.IExport
import com.wdullaer.materialdatetimepicker.date.DatePickerDialog
import com.wdullaer.materialdatetimepicker.time.TimePickerDialog
import kotlinx.android.synthetic.main.layout_file_share.*
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

	private val from: Calendar = Calendar.getInstance().apply {
		add(Calendar.MONTH, -1)
	}

	private val to: Calendar = Calendar.getInstance()

	//private val files = ArrayList<IReadableFile>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		sharableDir = File(filesDir, SHARABLE_DIR_NAME)

		val exporterType = intent.extras!![EXPORTER_KEY] as Class<*>
		exporter = exporterType.newInstance() as IExport

		root = createLinearContentParent(false)
		(layoutInflater.inflate(R.layout.layout_file_share, root) as ViewGroup).getChildAt(root.childCount - 1)

		updateEditTextDate(edittext_from_date, from)
		edittext_from_date.setOnClickListener {
			showDatePickerDialog(from, edittext_from_date, "FromDatePicker")
		}

		updateEditTextDate(edittext_to_date, to)
		edittext_to_date.setOnClickListener {
			showDatePickerDialog(to, edittext_to_date, "ToDatePicker")
		}


		updateEditTextTime(edittext_from_time, from)
		edittext_from_time.setOnClickListener {
			showTimePickerDialog(from, edittext_from_time, "FromTimePicker")
		}

		updateEditTextTime(edittext_to_time, to)
		edittext_to_time.setOnClickListener {
			showTimePickerDialog(to, edittext_to_time, "ToTimePicker")
		}


		button_export.setOnClickListener {

			val database = AppDatabase.getAppDatabase(applicationContext)
			val locationDao = database.locationDao()

			GlobalScope.launch {
				sharableDir.mkdirs()
				val locations = locationDao.getAllBetween(from.timeInMillis, to.timeInMillis)

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

	private fun updateEditTextDate(editText: AppCompatEditText, cal: Calendar) {
		val text = SimpleDateFormat.getDateInstance().format(cal.time)
		editText.text = SpannableStringBuilder(text)
	}

	private fun updateEditTextTime(editText: AppCompatEditText, cal: Calendar) {
		val text = SimpleDateFormat.getTimeInstance().format(cal.time)
		editText.text = SpannableStringBuilder(text)
	}

	private fun showTimePickerDialog(cal: Calendar, editText: AppCompatEditText, tag: String) {
		val dpd = TimePickerDialog.newInstance(
				{ _, hourOfDay, minute, second ->
					cal.set(java.util.Calendar.HOUR_OF_DAY, hourOfDay)
					cal.set(java.util.Calendar.MINUTE, minute)
					cal.set(java.util.Calendar.SECOND, second)
					updateEditTextTime(editText, cal)
				},
				cal.get(java.util.Calendar.HOUR_OF_DAY),
				cal.get(java.util.Calendar.MONTH),
				cal.get(java.util.Calendar.SECOND),
				DateFormat.is24HourFormat(this)
		)
		dpd.show(supportFragmentManager, tag)
	}

	private fun showDatePickerDialog(cal: Calendar, editText: AppCompatEditText, tag: String) {
		val dpd = DatePickerDialog.newInstance(
				{ _, year, monthOfYear, dayOfMonth ->
					cal.set(year, monthOfYear, dayOfMonth)
					updateEditTextDate(editText, cal)
				},
				cal.get(Calendar.YEAR),
				cal.get(Calendar.MONTH),
				cal.get(Calendar.DAY_OF_MONTH)
		)
		dpd.show(supportFragmentManager, tag)
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
		const val EXPORTER_KEY = "exporter"
	}
}
