package com.adsamcik.tracker.preference

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.adsamcik.tracker.R
import com.adsamcik.tracker.common.preference.Preferences
import com.adsamcik.tracker.common.style.StyleManager
import com.adsamcik.tracker.common.style.utility.ColorConstants
import com.adsamcik.tracker.common.style.utility.ColorFunctions
import com.adsamcik.tracker.common.style.utility.ColorGenerator
import com.adsamcik.tracker.preference.pages.StylePage
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.color.colorChooser

class ColorPreference : Preference {
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

	override fun onBindViewHolder(holder: PreferenceViewHolder) {
		super.onBindViewHolder(holder)

		val colorData = requireNotNull(recyclerColorData)

		(holder.findViewById(R.id.title) as AppCompatTextView).apply {
			setText(colorData.required.nameRes)
		}

		val colorView = (holder.findViewById(R.id.color) as AppCompatImageView)

		colorView.setImageDrawable(StyleColorDrawable(colorView.drawable.mutate() as GradientDrawable))

		updateColor(colorView, colorData.color)

		holder.itemView.setOnClickListener {
			val colors = ColorGenerator.generateWithGolden(16).toIntArray()

			val indices = (1..ALPHA_LEVELS).map { it / ALPHA_LEVELS }

			val subColors = colors.map { color ->
				val r = color.red / ALPHA_LEVELS
				val g = color.green / ALPHA_LEVELS
				val b = color.blue / ALPHA_LEVELS
				indices.map { color }.distinct().toIntArray()
			}.toTypedArray()

			MaterialDialog(holder.itemView.context).show {
				title(requireNotNull(recyclerColorData).required.nameRes)
				colorChooser(
						colors = colors,
						subColors = subColors,
						initialSelection = colorData.color,
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

					colorData.color = color
					updateColor(colorView, color)

					StyleManager.updateColorAt(position, color)
				}
				//positiveButton(R.string.)
			}
		}
	}

	companion object {
		private const val ALPHA_LEVELS = 25
	}
}
