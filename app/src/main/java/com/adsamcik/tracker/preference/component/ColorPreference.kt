package com.adsamcik.tracker.preference.component

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.extension.findChildOfType
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.dynamicBaseStyle
import com.adsamcik.tracker.shared.utils.extension.dynamicStyle
import com.adsamcik.tracker.shared.utils.style.ActiveColorData
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleManager
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.adsamcik.tracker.shared.utils.style.color.ColorConstants
import com.adsamcik.tracker.shared.utils.style.color.ColorGenerator
import com.adsamcik.tracker.shared.utils.style.color.brightenColor
import com.adsamcik.tracker.shared.utils.style.implementations.StyleColorDrawable
import com.adsamcik.tracker.shared.utils.style.update.data.DefaultColorData
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.actions.getActionButton
import com.afollestad.materialdialogs.color.colorChooser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Preference for custom color setting.
 */
@Suppress("unused")
class ColorPreference : Preference, CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	constructor(
			context: Context?,
			attrs: AttributeSet?,
			defStyleAttr: Int,
			defStyleRes: Int
	) : super(context, attrs, defStyleAttr, defStyleRes)

	constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
			context,
			attrs,
			defStyleAttr
	)

	constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
	constructor(context: Context?) : super(context)

	init {
		layoutResource = R.layout.layout_color_picker
	}

	private var recyclerColorData: RecyclerColorData? = null
	private var position: Int = -1

	private var colorImageView: AppCompatImageView? = null

	/**
	 * Set current color and its position within style.
	 */
	fun setColor(position: Int, activeColorData: ActiveColorData) {
		this.recyclerColorData = RecyclerColorData(activeColorData)
		this.position = position
		notifyChanged()
	}

	/**
	 * Restore default value and remove preference.
	 */
	fun restoreDefault() {
		val colorData = requireNotNull(recyclerColorData) { "First set color data by calling ${this::setColor.name}" }
		val defaultColor = colorData.default.defaultColor

		Preferences.getPref(context).edit {
			val key = context.getString(
					R.string.settings_color_key,
					position
			)
			remove(key)
		}

		onColorChange(defaultColor)
	}

	private fun updateColor(
			colorView: AppCompatImageView,
			color: Int
	) {
		colorView.apply {
			(drawable as StyleColorDrawable).apply {
				drawable.setColor(color)
				invalidateSelf()
			}
		}
	}

	@MainThread
	private fun showDialog() {
		val dialog = MaterialDialog(context)
		dialog.show()
		launch(Dispatchers.Default) {
			initializeColorDialog(dialog)
		}
	}

	@WorkerThread
	private fun initializeColorDialog(dialog: MaterialDialog) {
		val colors = IntArray(PALETTE_COLOR_COUNT)

		ColorConstants
		colors[0] = ColorConstants.ALMOST_BLACK
		ColorGenerator.generatePalette(PALETTE_COLOR_COUNT - 2).forEachIndexed { index, color ->
			colors[index + 1] = color
		}
		colors[PALETTE_COLOR_COUNT - 1] = ColorConstants.ALMOST_WHITE

		val indices = (-SUB_COLOR_LEVELS / 2..SUB_COLOR_LEVELS / 2)

		val subColors = colors.map { color ->
			indices
					.map { brightenColor(color, it * BRIGHTEN_PER_LEVEL) }
					.distinct()
					.toIntArray()
		}.toTypedArray()

		launch {
			showColorDialog(dialog, colors, subColors, requireNotNull(recyclerColorData).color)
		}
	}

	@MainThread
	private fun showColorDialog(
			dialog: MaterialDialog,
			colorList: IntArray,
			subColorList: Array<IntArray>,
			initialColor: Int
	) {
		dialog.show {
			title(requireNotNull(recyclerColorData).default.nameRes)
			colorChooser(
					colors = colorList,
					subColors = subColorList,
					initialSelection = initialColor,
					allowCustomArgb = true,
					showAlphaSelector = false,
					changeActionButtonsColor = true
			) { _, color ->
				saveColor(color)
			}
			// This won't work well because ColorDialog currently hijacks button colors.
			/*dynamicStyle {
				it.watchRecyclerView(RecyclerStyleView(view.findChildOfType()))
			}*/
		}
	}

	private fun saveColor(color: Int) {
		val data = requireNotNull(recyclerColorData)

		if (color == data.color) return

		updatePreference(color)
		onColorChange(color)
	}

	private fun onColorChange(color: Int) {
		requireNotNull(recyclerColorData).color = color
		StyleManager.updateColorAt(context, position, color)

		notifyChanged()
	}

	private fun updatePreference(color: Int) {
		Preferences.getPref(context).edit {
			val key = context.getString(
					R.string.settings_color_key,
					position
			)
			setInt(key, color)
		}
	}

	override fun onBindViewHolder(holder: PreferenceViewHolder) {
		super.onBindViewHolder(holder)

		val colorData = requireNotNull(recyclerColorData)

		(holder.findViewById(R.id.title) as AppCompatTextView).apply {
			setText(colorData.default.nameRes)
		}

		val colorView = (holder.findViewById(R.id.color) as AppCompatImageView)

		colorImageView = colorView

		colorView.drawable.let {
			if (it !is StyleColorDrawable) {
				colorView.setImageDrawable(
						StyleColorDrawable(
								it.mutate() as GradientDrawable,
								StyleColorDrawable.BASE_STROKE_WIDTH
						)
				)
			}
		}

		updateColor(colorView, colorData.color)

		holder.itemView.setOnClickListener { showDialog() }
	}

	private data class RecyclerColorData(var color: Int, val default: DefaultColorData) {
		constructor(activeColorData: ActiveColorData) : this(
				activeColorData.active,
				activeColorData.default
		)
	}

	companion object {
		private const val SUB_COLOR_LEVELS = 10
		private const val BRIGHTEN_PER_LEVEL = 15
		private const val PALETTE_COLOR_COUNT = 16
	}
}
