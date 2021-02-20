package com.adsamcik.tracker.shared.utils.activity

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.Checkable
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.annotation.CallSuper
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.children
import androidx.core.view.forEach
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import com.adsamcik.recycler.decoration.MarginDecoration
import com.adsamcik.tracker.shared.base.R
import com.adsamcik.tracker.shared.base.misc.SnackMaker
import com.adsamcik.tracker.shared.utils.keyboard.KeyboardManager
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.transition.MaterialArcMotion
import com.google.android.material.transition.MaterialContainerTransform

/**
 * Activity for managing various things.
 */
@Suppress("TooManyFunctions")
abstract class ManageActivity : DetailActivity() {
	protected lateinit var keyboardManager: KeyboardManager
		private set

	protected lateinit var snackMaker: SnackMaker
		private set

	protected lateinit var fab: FloatingActionButton
		private set

	private lateinit var rootView: ViewGroup

	private var editContentLayout: ViewGroup? = null
	private lateinit var addItemLayout: ViewGroup

	private val editFieldList = mutableListOf<EditData>()

	private var isEditInitialized: Boolean = false

	protected var isAddEnabled: Boolean = true
		set(value) {
			if (value) {
				fab.updateLayoutParams {
					height = ViewGroup.LayoutParams.WRAP_CONTENT
					width = ViewGroup.LayoutParams.WRAP_CONTENT
				}
			} else {
				fab.updateLayoutParams {
					height = 0
					width = 0
				}
			}
			field = value
		}

	protected var resetOnAdd: Boolean = true

	abstract fun getAdapter(): RecyclerView.Adapter<RecyclerView.ViewHolder>

	abstract fun onCreateRecycler(recyclerView: RecyclerView)

	@CallSuper
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val rootView = inflateContent<ViewGroup>(R.layout.layout_recycler_edit)
		this.rootView = rootView

		keyboardManager = KeyboardManager(rootView)
		snackMaker = SnackMaker(rootView.findViewById(R.id.coordinator))

		val manageConfiguration = ManageConfiguration()
		onManageConfigure(manageConfiguration)

		addItemLayout = rootView.findViewById(R.id.add_item_layout)

		val recycler = rootView.findViewById<RecyclerView>(R.id.recycler).apply {
			this.adapter = this@ManageActivity.getAdapter()
			val layoutManager = LinearLayoutManager(this@ManageActivity)
			this.layoutManager = layoutManager

			val dividerItemDecoration = DividerItemDecoration(
					this@ManageActivity,
					layoutManager.orientation
			)
			addItemDecoration(dividerItemDecoration)

			if (manageConfiguration.isRecyclerMarginEnabled) {
				addItemDecoration(MarginDecoration())
			}

			if (manageConfiguration.isHorizontallyScrollable) {
				val horizontalScroll = HorizontalScrollView(this@ManageActivity)
				(parent as ViewGroup).let {
					val recyclerIndex = it.indexOfChild(this@apply)
					it.addView(horizontalScroll, recyclerIndex)
					it.removeView(this@apply)
				}

				horizontalScroll.addView(this)
			}
		}

		onCreateRecycler(recycler)

		fab = rootView.findViewById<FloatingActionButton>(R.id.fab).apply {
			setOnClickListener(this@ManageActivity::onFabClick)
			setImageDrawable(
					ResourcesCompat.getDrawable(
							resources,
							manageConfiguration.dialogFabIcon,
							theme
					)
			)
		}

		onCreateEdit()

		initializeColorController()

		isAddEnabled = manageConfiguration.isAddEnabled
		resetOnAdd = manageConfiguration.resetDialogOnAdd
	}

	@Suppress("unused_parameter")
	private fun onFabClick(view: View) {
		if (resetOnAdd) resetDialogInput()
		setEditVisibility(true)
	}

	private fun inflateEdit(): ViewGroup {
		val rootView = LayoutInflater.from(this)
				.inflate(R.layout.layout_recycler_edit_dialog, addItemLayout, true)

		return rootView as ViewGroup
	}

	private fun initializeEditText(data: EditData): TextInputLayout {
		val editText = TextInputEditText(this).apply {
			this.layoutParams = LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT
			)
			inputType = InputType.TYPE_CLASS_TEXT
			maxLines = 1
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

	private fun setEditText(editText: EditText, data: EditDataInstance) {
		editText.setText(data.value)
	}

	private fun getEditTextValue(view: View, editData: EditData): EditDataInstance? {
		require(view is TextInputLayout)
		val editText = requireNotNull(view.editText)
		val editableText = editText.text
		return if (editData.isRequired && editableText.isNullOrBlank()) {
			null
		} else {
			EditDataInstance(
					editData.id,
					editableText.toString()
			)
		}
	}

	private fun initializeCheckbox(data: EditData): View {
		return MaterialCheckBox(this).apply {
			setHint(data.hintRes)
		}
	}

	private fun setCheckbox(checkBox: CheckBox, data: EditDataInstance) {
		checkBox.isChecked = data.value.toBoolean()
	}

	private fun getCheckboxValue(view: View, editData: EditData): EditDataInstance {
		require(view is CheckBox)
		return EditDataInstance(
				editData.id,
				view.isChecked.toString()
		)
	}

	private fun initializeEditFields(rootLayout: ViewGroup, collection: Collection<EditData>) {
		collection.forEach {
			val layout = when (it.type) {
				EditType.EditText -> initializeEditText(it)
				EditType.Checkbox -> initializeCheckbox(it)
				EditType.EditTextNumber -> {
					initializeEditText(it).also { layout ->
						requireNotNull(layout.editText).inputType = InputType.TYPE_CLASS_NUMBER
					}
				}
				EditType.DateTime -> TODO()
				EditType.DateTimeRange -> TODO()
			}

			rootLayout.addView(layout)
		}

		editFieldList.addAll(collection)
	}

	private fun setDefaultDataEditFields(
			view: View,
			data: EditData,
			instance: EditDataInstance
	) {
		when (data.type) {
			EditType.EditText, EditType.EditTextNumber -> {
				require(view is TextInputLayout)
				setEditText(requireNotNull(view.editText), instance)

			}
			EditType.Checkbox -> {
				require(view is CheckBox)
				setCheckbox(view, instance)
			}
			EditType.DateTime -> TODO()
			EditType.DateTimeRange -> TODO()
		}
	}

	private fun setDefaultDataEditFields(
			rootLayout: ViewGroup,
			collection: Collection<EditDataInstance>
	) {
		require(collection.distinct().size == collection.size)

		val matchList = collection.map { instance ->
			editFieldList.indexOfFirst { instance.id == it.id } to instance
		}

		require(matchList.all { it.first >= 0 })

		val sorted = matchList.sortedBy { it.first }
		rootLayout.children.forEachIndexed { index, view ->
			val match = sorted[index]
			require(index == match.first)
			val editData = editFieldList[index]
			setDefaultDataEditFields(view, editData, match.second)
		}
	}

	private fun getFieldValues(
			rootLayout: ViewGroup
	): Collection<EditDataInstance?> {
		return rootLayout.children.mapIndexed { index, view ->
			val editData = editFieldList[index]
			when (editData.type) {
				EditType.EditText, EditType.EditTextNumber -> getEditTextValue(view, editData)
				EditType.Checkbox -> getCheckboxValue(view, editData)
				EditType.DateTime -> TODO()
				EditType.DateTimeRange -> TODO()
			}
		}.toList()
	}

	private fun resetDialogInput() {
		requireNotNull(editContentLayout).forEach {
			when (it) {
				is TextInputLayout -> requireNotNull(it.editText).setText("")
				is Checkable -> it.isChecked = false
			}
		}
	}

	private fun setEditVisibility(visible: Boolean) {
		val transform = MaterialContainerTransform().apply {
			if (visible) {
				startView = fab
				endView = addItemLayout
			} else {
				startView = addItemLayout
				endView = fab
			}

			// Ensure the container transform only runs on a single target
			addTarget(requireNotNull(endView))

			// Optionally add a curved path to the transform
			setPathMotion(MaterialArcMotion())

			scrimColor = Color.TRANSPARENT
		}

		TransitionManager.beginDelayedTransition(rootView, transform)

		if (visible) {
			fab.visibility = View.GONE
			addItemLayout.visibility = View.VISIBLE
		} else {
			fab.visibility = View.VISIBLE
			addItemLayout.visibility = View.GONE
		}
	}

	private fun onCreateEdit() {
		if (isEditInitialized) return
		isEditInitialized = true

		val editContentRootLayout = inflateEdit()

		val editContent = editContentRootLayout.findViewById<ViewGroup>(R.id.edit_content)
		editContentLayout = editContent

		val editDataCollection = getEmptyEditData()

		initializeEditFields(editContent, editDataCollection)

		editContentRootLayout.findViewById<Button>(R.id.button_ok).setOnClickListener {
			setEditVisibility(false)

			keyboardManager.hideKeyboard()

			val editDataList = getFieldValues(editContent)
			val editDataListNonNull = editDataList.filterNotNull()

			if (editDataList.size == editDataListNonNull.size) {
				val tag = requireNotNull(editContentLayout).tag as? String
				onDataConfirmed(tag, editDataListNonNull)
			}
		}

		editContentRootLayout.findViewById<Button>(R.id.button_cancel).setOnClickListener {
			setEditVisibility(false)
		}
	}

	protected abstract fun onDataConfirmed(tag: String?, dataCollection: List<EditDataInstance>)

	protected abstract fun getEmptyEditData(): Collection<EditData>

	protected fun edit(tag: String, data: Collection<EditDataInstance>) {
		onCreateEdit()
		val editContentLayout = requireNotNull(editContentLayout)
		editContentLayout.tag = tag
		setDefaultDataEditFields(editContentLayout, data)
		setEditVisibility(true)
	}


	override fun onConfigure(configuration: Configuration) {
		configuration.useColorControllerForContent = true
		configuration.titleBarLayer = 1
	}

	/**
	 * Update manage configuration with own values
	 */
	open fun onManageConfigure(configuration: ManageConfiguration) = Unit

	private fun initializeColorController() {
		styleController.watchRecyclerView(RecyclerStyleView(findViewById(R.id.recycler), 0))
		styleController.watchView(StyleView(findViewById(R.id.fab), 1, isInverted = true))
		styleController.watchView(StyleView(addItemLayout, 2))
	}

	/**
	 * Manage activity configuration data
	 */
	data class ManageConfiguration(
			var isHorizontallyScrollable: Boolean = false,
			@DrawableRes var dialogFabIcon: Int = R.drawable.ic_outline_add,
			var isAddEnabled: Boolean = true,
			var resetDialogOnAdd: Boolean = true,
			var isRecyclerMarginEnabled: Boolean = true
	)

	/**
	 * Manage activity edit data for RecyclerView
	 */
	data class EditData(
			val id: String,
			val type: EditType,
			@StringRes val hintRes: Int,
			val isRequired: Boolean = false
	)

	/**
	 * Instance of edit data
	 */
	data class EditDataInstance(
			val id: String,
			val value: String
	) {
		constructor(id: String, value: Any) : this(id, value.toString())
	}

	/**
	 * Type of edit field
	 */
	enum class EditType {
		EditText,
		EditTextNumber,
		Checkbox,
		DateTime,
		DateTimeRange
	}
}
