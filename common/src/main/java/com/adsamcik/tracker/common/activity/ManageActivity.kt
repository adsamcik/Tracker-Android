package com.adsamcik.tracker.common.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.CallSuper
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.common.R
import com.adsamcik.tracker.common.extension.findChildrenOfType
import com.adsamcik.tracker.common.keyboard.KeyboardManager
import com.adsamcik.tracker.common.misc.SnackMaker
import com.adsamcik.tracker.common.style.RecyclerStyleView
import com.adsamcik.tracker.common.style.StyleView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.android.synthetic.main.layout_recycler_edit.*

abstract class ManageActivity : DetailActivity() {
	protected lateinit var keyboardManager: KeyboardManager
		private set

	protected lateinit var snackMaker: SnackMaker
		private set

	protected lateinit var fab: FloatingActionButton
		private set

	abstract fun getAdapter(): RecyclerView.Adapter<RecyclerView.ViewHolder>

	abstract fun onCreateRecycler(recyclerView: RecyclerView)

	@CallSuper
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val rootView = inflateContent<ViewGroup>(R.layout.layout_recycler_edit)
		keyboardManager = KeyboardManager(rootView)
		snackMaker = SnackMaker(rootView.findViewById(R.id.coordinator))

		val recycler = rootView.findViewById<RecyclerView>(R.id.recycler).apply {
			this.adapter = this@ManageActivity.getAdapter()
			val layoutManager = LinearLayoutManager(this@ManageActivity)
			this.layoutManager = layoutManager

			val dividerItemDecoration = DividerItemDecoration(
					this@ManageActivity,
					layoutManager.orientation
			)
			addItemDecoration(dividerItemDecoration)
		}

		onCreateRecycler(recycler)

		fab = rootView.findViewById<FloatingActionButton>(R.id.fab).apply {
			setOnClickListener { isExpanded = true }
		}

		onCreateEdit()
		initializeColorController()
	}

	private fun inflateEdit(): ViewGroup {
		val addItemLayout = findViewById<FrameLayout>(R.id.add_item_layout)
		val rootView = LayoutInflater.from(this)
				.inflate(R.layout.layout_recycler_edit_dialog, addItemLayout, true)

		return rootView as ViewGroup
	}

	private fun initializeEditText(data: EditData): View {
		val editText = TextInputEditText(this).apply {
			setText(data.currentValue)
			tag = data
			this.layoutParams = LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT
			)
		}

		return TextInputLayout(this).apply {
			hint = getString(data.hintRes)
			addView(editText)
			this.layoutParams = LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT
			)
		}
	}

	private fun initializeCheckbox(data: EditData): View {
		return MaterialCheckBox(this).apply {
			isChecked = data.currentValue.toBoolean()
			setHint(data.hintRes)
		}
	}

	private fun initializeEditFields(rootLayout: ViewGroup, collection: Collection<EditData>) {
		collection.forEach {
			val layout = when (it.type) {
				EditType.EditText -> initializeEditText(it)
				EditType.Checkbox -> initializeCheckbox(it)
			}

			rootLayout.addView(layout)
		}
	}

	private fun onCreateEdit() {
		val rootView = inflateEdit()

		val editContent = rootView.findViewById<ViewGroup>(R.id.edit_content)

		val editDataCollection = getEmptyEditData()

		initializeEditFields(editContent, editDataCollection)

		rootView.findViewById<Button>(R.id.button_ok).setOnClickListener {
			fab.isExpanded = false
			keyboardManager.hideKeyboard()

			val editDataList = editContent.findChildrenOfType<EditText>().map {
				val editData = it.tag as EditData
				val newData = EditData(editData, it.text.toString())
				it.clearFocus()
				it.text = null

				newData
			}

			onDataSave(editDataList)
		}

		rootView.findViewById<Button>(R.id.button_cancel).setOnClickListener {
			fab.isExpanded = false
		}
	}

	protected abstract fun onDataSave(dataCollection: List<EditData>)

	protected abstract fun getEmptyEditData(): Collection<EditData>

	override fun onConfigure(configuration: Configuration) {
		configuration.useColorControllerForContent = true
		configuration.titleBarLayer = 1
	}

	private fun initializeColorController() {
		styleController.watchRecyclerView(RecyclerStyleView(recycler, 0))
		styleController.watchView(StyleView(findViewById(R.id.fab), 1, isInverted = true))
		styleController.watchView(StyleView(add_item_layout, 2))
	}

	data class EditData(
			val id: String,
			val type: EditType,
			@StringRes val hintRes: Int,
			val currentValue: String
	) {
		constructor(data: EditData, newValue: String) : this(
				data.id,
				data.type,
				data.hintRes,
				newValue
		)
	}

	enum class EditType {
		EditText,
		Checkbox
	}
}
