package com.adsamcik.signalcollector.components

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Color
import android.util.AttributeSet
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.jaredrummler.android.colorpicker.*

/**
 * Custom implementation of the [ColorPickerDialogListener] copied from the library and modified to work with AppCompat Preferences.
 */
class ColorSupportPreference : Preference, ColorPickerDialogListener {
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(attrs)
    }

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle) {
        init(attrs)
    }

    private var onShowDialogListener: OnShowDialogListener? = null
    private var color = Color.BLACK
    private var showDialog: Boolean = false
    @ColorPickerDialog.DialogType
    private var dialogType: Int = 0
    private var colorShape: Int = 0
    private var allowPresets: Boolean = false
    private var allowCustom: Boolean = false
    private var showAlphaSlider: Boolean = false
    private var showColorShades: Boolean = false
    private var previewSize: Int = 0
    private var presets: IntArray? = null
    private var dialogTitle: Int = 0

    private fun init(attrs: AttributeSet) {
        isPersistent = true
        val a = context.obtainStyledAttributes(attrs, R.styleable.ColorPreference)
        showDialog = a.getBoolean(R.styleable.ColorPreference_cpv_showDialog, true)

        dialogType = a.getInt(R.styleable.ColorPreference_cpv_dialogType, ColorPickerDialog.TYPE_PRESETS)
        colorShape = a.getInt(R.styleable.ColorPreference_cpv_colorShape, ColorShape.CIRCLE)
        allowPresets = a.getBoolean(R.styleable.ColorPreference_cpv_allowPresets, true)
        allowCustom = a.getBoolean(R.styleable.ColorPreference_cpv_allowCustom, true)
        showAlphaSlider = a.getBoolean(R.styleable.ColorPreference_cpv_showAlphaSlider, false)
        showColorShades = a.getBoolean(R.styleable.ColorPreference_cpv_showColorShades, true)
        previewSize = a.getInt(R.styleable.ColorPreference_cpv_previewSize, SIZE_NORMAL)
        val presetsResId = a.getResourceId(R.styleable.ColorPreference_cpv_colorPresets, 0)
        dialogTitle = a.getResourceId(R.styleable.ColorPreference_cpv_dialogTitle, R.string.cpv_default_title)
        presets = if (presetsResId != 0) {
            context.resources.getIntArray(presetsResId)
        } else {
            ColorPickerDialog.MATERIAL_COLORS
        }
        widgetLayoutResource = if (colorShape == ColorShape.CIRCLE) {
            if (previewSize == SIZE_LARGE) R.layout.cpv_preference_circle_large else R.layout.cpv_preference_circle
        } else {
            if (previewSize == SIZE_LARGE) R.layout.cpv_preference_square_large else R.layout.cpv_preference_square
        }
        a.recycle()
    }

    private fun getActivity() = context as AppCompatActivity

    override fun onClick() {
        super.onClick()
        if (onShowDialogListener != null) {
            onShowDialogListener!!.onShowColorPickerDialog(title as String, color)
        } else if (showDialog) {
            val dialog = ColorPickerDialog.newBuilder()
                    .setDialogType(dialogType)
                    .setDialogTitle(dialogTitle)
                    .setColorShape(colorShape)
                    .setPresets(presets!!)
                    .setAllowPresets(allowPresets)
                    .setAllowCustom(allowCustom)
                    .setShowAlphaSlider(showAlphaSlider)
                    .setShowColorShades(showColorShades)
                    .setColor(color)
                    .create()
            dialog.setColorPickerDialogListener(this)
            val activity = getActivity()
            dialog.show(activity.supportFragmentManager, getFragmentTag())
        }
    }

    override fun onAttached() {
        super.onAttached()

        if (showDialog) {
            val activity = getActivity()
            (activity.supportFragmentManager.findFragmentByTag(getFragmentTag()) as ColorPickerDialog?)?.setColorPickerDialogListener(this)
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val preview = holder.findViewById(R.id.cpv_preference_preview_color_panel) as ColorPanelView
        preview.color = color
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        if (isPersistent) {
            color = getPersistedInt(-0x1000000)
        } else {
            color = defaultValue as Int
            persistInt(color)
        }
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInteger(index, Color.BLACK)
    }

    override fun onColorSelected(dialogId: Int, @ColorInt color: Int) {
        saveValue(color)
    }

    override fun onDialogDismissed(dialogId: Int) {
        // no-op
    }

    /**
     * Set the new color
     *
     * @param color
     * The newly selected color
     */
    fun saveValue(@ColorInt color: Int) {
        this.color = color
        persistInt(this.color)
        notifyChanged()
        callChangeListener(color)
    }

    /**
     * Set the colors shown in the [ColorPickerDialog].
     *
     * @param presets An array of color ints
     */
    fun setPresets(presets: IntArray) {
        this.presets = presets
    }

    /**
     * Get the colors that will be shown in the [ColorPickerDialog].
     *
     * @return An array of color ints
     */
    fun getPresets(): IntArray? {
        return presets
    }

    /**
     * The listener used for showing the [ColorPickerDialog].
     * Call [.saveValue] after the user chooses a color.
     * If this is set then it is up to you to show the dialog.
     *
     * @param listener
     * The listener to show the dialog
     */
    fun setOnShowDialogListener(listener: OnShowDialogListener) {
        onShowDialogListener = listener
    }

    /**
     * The tag used for the [ColorPickerDialog].
     *
     * @return The tag
     */
    fun getFragmentTag(): String {
        return "color_$key"
    }

    interface OnShowDialogListener {

        fun onShowColorPickerDialog(title: String, currentColor: Int)
    }


    companion object {
        private const val SIZE_NORMAL = 0
        private const val SIZE_LARGE = 1
    }
}