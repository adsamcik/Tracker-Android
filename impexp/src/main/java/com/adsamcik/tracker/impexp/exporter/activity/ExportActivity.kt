package com.adsamcik.tracker.impexp.exporter.activity

import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.appcompat.widget.AppCompatEditText
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.widget.addTextChangedListener
import androidx.documentfile.provider.DocumentFile
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.openOutputStream
import com.adsamcik.tracker.shared.base.extension.toEpochMillis
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.base.misc.SnackMaker
import com.adsamcik.tracker.shared.utils.activity.DetailActivity
import com.adsamcik.tracker.shared.utils.dialog.createDateTimeDialog
import com.adsamcik.tracker.shared.utils.extension.dynamicStyle
import com.afollestad.materialdialogs.MaterialDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit


/**
 * Activity that allows user to share his collected data to other apps that support zip files
 */
class ExportActivity : DetailActivity() {
	private lateinit var shareableDir: File
	private lateinit var root: ViewGroup

	private lateinit var exporter: Exporter

	private val dataRangeFrom: AppCompatEditText by lazy { findViewById(R.id.edittext_date_range_from) }
	private val dataRangeTo: AppCompatEditText by lazy { findViewById(R.id.edittext_date_range_to) }

	private val fileNameField: AppCompatEditText by lazy { findViewById(R.id.edittext_filename) }

	private lateinit var snackMaker: SnackMaker

	private var range: ClosedRange<ZonedDateTime> = Time.now.minusMonths(1)..Time.now
		set(value) {
			field = value
			updateDateTimeText(dataRangeFrom, value.start)
			updateDateTimeText(dataRangeTo, value.endInclusive)
		}

	private val exportResult: ActivityResultLauncher<Uri?> = registerForActivityResult(
			ActivityResultContracts.OpenDocumentTree()
	) {
		if (it == null) {
			snackMaker.addMessage(R.string.export_error_no_uri)
		} else {
			tryExport(requireNotNull(DocumentFile.fromTreeUri(this, it)), false) { result, _ ->
				if (result.isSuccess) {
					finish()
				}
			}
		}
	}

	private val shareResult = registerForActivityResult(
			StartActivityForResult()
	) {
		if (it.resultCode == RESULT_OK) {
			finish()
		} else {
			snackMaker.addMessage(R.string.export_error_no_share)
		}
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
				snackMaker.addMessage(R.string.settings_export_no_data)
				return@launch
			}

			val selectedRange = LongRange(
					range.start.toEpochMillis(),
					range.endInclusive.toEpochMillis()
			)
			launch(Dispatchers.Main) {
				createDateTimeDialog(styleController, availableRange, selectedRange) {
					range = Time.ofEpochMilli(it.first)..Time.roundToDate(Time.ofEpochMilli(it.last))
							.with { time ->
								time.plus(1L, ChronoUnit.DAYS)
								time.minus(1L, ChronoUnit.MILLIS)
							}
				}
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		shareableDir = File(filesDir, SHARABLE_DIR_NAME)

		val exporterType = requireNotNull(intent.extras)[EXPORTER_KEY] as Class<*>
		exporter = exporterType.newInstance() as Exporter

		root = inflateContent<ConstraintLayout>(R.layout.layout_data_export)

		snackMaker = SnackMaker(root)

		fileNameField.apply {
			addTextChangedListener(afterTextChanged = {
				val text = requireNotNull(it).toString()
				error = if (Regex("[\\\\/:\"*?<>|]+").containsMatchIn(text)) {
					getString(R.string.export_file_name_error)
				} else {
					null
				}
			})
		}

		if (exporter.canSelectDateRange) {
			val now = Time.now
			val monthBefore = now.minusMonths(1L)

			range = monthBefore..now

			dataRangeFrom.setOnClickListener(clickListener)
			dataRangeTo.setOnClickListener(clickListener)

		} else {
			dataRangeFrom.visibility = View.GONE
			dataRangeTo.visibility = View.GONE
			findViewById<View>(R.id.imageview_from_date).visibility = View.GONE
		}

		findViewById<View>(R.id.button_export).setOnClickListener {
			exportResult.launch(Uri.fromFile(filesDir))
		}

		findViewById<View>(R.id.button_share).setOnClickListener {
			shareableDir.mkdirs()
			tryExport(DocumentFile.fromFile(shareableDir), true) { exportResult, documentFile ->
				if (!exportResult.isSuccess) return@tryExport

				val shareUri = FileProvider.getUriForFile(
						this,
						"com.adsamcik.tracker.fileprovider",
						documentFile.uri.toFile()
				)
				val shareIntent = Intent().apply {
					action = Intent.ACTION_SEND
					putExtra(Intent.EXTRA_STREAM, shareUri)
					type = exporter.mimeType
					flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
				}

				val intent = Intent.createChooser(
						shareIntent,
						resources.getText(R.string.export_share_button)
				)

				shareResult.launch(intent)
			}
		}
		setTitle(R.string.export_share_button)

		abortIfNoData()
	}

	private fun abortIfNoData() {
		launch(Dispatchers.Default) {
			val sessionDao = AppDatabase.database(this@ExportActivity).sessionDao()
			val availableRange = sessionDao.range().let {
				if (it.start == 0L && it.endInclusive == 0L) {
					LongRange.EMPTY
				} else {
					LongRange(it.start, it.endInclusive)
				}
			}

			if (availableRange.isEmpty()) {
				launch(Dispatchers.Main) {
					Assist.ensureLooper()
					MaterialDialog(this@ExportActivity).show {
						title(res = R.string.settings_export_no_data)
						positiveButton(res = com.adsamcik.tracker.shared.base.R.string.generic_ok) {
							finish()
						}
						dynamicStyle()
					}
				}
			}
		}

	}

	private fun formatForFile(dateTime: ZonedDateTime) =
			dateTime.withNano(0).format(DateTimeFormatter.ofPattern("d-M-y--k-m")).replace(':', '-')

	private fun getExportFileName(): String {
		val text = findViewById<AppCompatEditText>(R.id.edittext_filename).text
		return if (text.isNullOrBlank()) {
			if (!exporter.canSelectDateRange) {
				getString(
						R.string.export_default_file_name_all,
						formatForFile(Time.now)
				)
			} else {
				getString(
						R.string.export_default_file_name_range,
						formatForFile(range.start),
						formatForFile(range.endInclusive)
				)
			}
		} else {
			text.trim().toString()
		}
	}

	private fun preventDoubleExtension(fileNameWithExtension: String): String {
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
			forceOverride: Boolean,
			onPick: ((ExportResult, DocumentFile) -> Unit)? = null
	) {
		val fileName = getExportFileName()
		val fileNameWithExtension = "${fileName}.${exporter.extension}"

		require(directory.isDirectory)
		val foundFile = directory.findFile(fileNameWithExtension)

		if (!forceOverride && foundFile != null) {
			Assist.ensureLooper()
			MaterialDialog(this@ExportActivity)
					.show {
						message(text = "Do you want to override the existing file $fileNameWithExtension?")
						title(text = "File already exists!")
						positiveButton(R.string.generic_yes) {
							startExport(foundFile, onPick)
						}
						negativeButton(R.string.generic_no) {
							/*val incremented = directory.autoIncrementFileName(fileNameWithExtension)
							exportToNewFile(directory, incremented, onPick)*/
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
			val trimmedName = preventDoubleExtension(fileNameWithExtension)

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

		if (!result.isSuccess) {
			val message = result.message?.localize(this)
			if (message != null) {
				snackMaker
						.addMessage(
								message,
								Snackbar.LENGTH_LONG
						)
			} else {
				Reporter.report("Export failed, but has no message!")
			}
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
			return ExportResult(
					false,
					LocalizedString(
							R.string.export_error_stream_failed,
							file.uri
					)
			)
		}
	}

	@WorkerThread
	private fun export(outputStream: OutputStream): ExportResult {
		return if (exporter.canSelectDateRange) {
			val database = AppDatabase.database(applicationContext)
			val locationDao = database.locationDao()
			val from = this.range.start
			val to = this.range.endInclusive
			val locations = locationDao.getAllBetween(from.toEpochMillis(), to.toEpochMillis())

			if (locations.isEmpty()) {
				return ExportResult(
						false,
						LocalizedString(R.string.export_error_no_locations_in_interval)
				)
			}
			exporter.export(this, locations, outputStream)
		} else {
			exporter.export(this, listOf(), outputStream)
		}
	}

	private fun updateDateTimeText(textView: AppCompatEditText, value: ZonedDateTime) {
		val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
		textView.text = SpannableStringBuilder(value.format(format))
	}

	companion object {
		private const val SHARABLE_DIR_NAME = "sharable"
		const val EXPORTER_KEY: String = "exporter"
	}
}

