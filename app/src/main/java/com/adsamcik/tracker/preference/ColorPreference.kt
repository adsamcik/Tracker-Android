package com.adsamcik.tracker.preference

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
import com.adsamcik.tracker.common.preference.Preferences
import com.adsamcik.tracker.common.style.StyleManager
import com.adsamcik.tracker.common.style.utility.ColorConstants
import com.adsamcik.tracker.common.style.utility.ColorGenerator
import com.adsamcik.tracker.common.style.utility.brightenColor
import com.adsamcik.tracker.preference.pages.StylePage
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.color.colorChooser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

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

	private var recyclerColorData: StylePage.RecyclerColorData? = null
	private var position: Int = -1

	private var colorImageView: AppCompatImageView? = null

	fun setColor(position: Int, recyclerColorData: StylePage.RecyclerColorData) {
		this.recyclerColorData = recyclerColorData
		this.position = position
	}

	private fun updateColor(
			colorView: AppCompatImageView,
			color: Int
	) {
		colorView.apply {
			setImageDrawable((drawable.mutate() as StyleColorDrawable).apply {
				drawable.setColor(color)
			})
		}
	}

	@MainThread
	private fun showDialog(context: Context) {
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
			title(requireNotNull(recyclerColorData).required.nameRes)
			colorChooser(
					colors = colorList,
					subColors = subColorList,
					initialSelection = initialColor,
					allowCustomArgb = true,
					showAlphaSelector = false
			) { dialog, color ->
				// Use color integer
				Preferences.getPref(dialog.context).edit {
					val key = dialog.context.getString(
							R.string.settings_color_key,
							position
					)
					setInt(key, color)
				}

				requireNotNull(recyclerColorData).color = color
				updateColor(requireNotNull(colorImageView), color)

				StyleManager.updateColorAt(position, color)
			}
		}
	}

	override fun onBindViewHolder(holder: PreferenceViewHolder) {
		super.onBindViewHolder(holder)

		val colorData = requireNotNull(recyclerColorData)

		(holder.findViewById(R.id.title) as AppCompatTextView).apply {
			setText(colorData.required.nameRes)
		}

		val colorView = (holder.findViewById(R.id.color) as AppCompatImageView)

		colorImageView = colorView

		colorView.setImageDrawable(StyleColorDrawable(colorView.drawable.mutate() as GradientDrawable))

		updateColor(colorView, colorData.color)

		holder.itemView.setOnClickListener { view -> showDialog(view.context) }
	}

	companion object {
		private const val SUB_COLOR_LEVELS = 10
		private const val BRIGHTEN_PER_LEVEL = 15
		private const val PALETTE_COLOR_COUNT = 16
	}
}
