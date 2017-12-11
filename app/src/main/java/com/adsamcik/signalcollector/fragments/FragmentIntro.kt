package com.adsamcik.signalcollector.fragments


import android.graphics.Color
import android.os.Bundle
import android.support.annotation.ColorInt
import android.support.annotation.DrawableRes
import android.view.Window
import com.adsamcik.signalcollector.R

import com.github.paolorotolo.appintro.AppIntroBaseFragment
import com.github.paolorotolo.appintro.ISlidePolicy

class FragmentIntro : AppIntroBaseFragment(), ISlidePolicy {
    private var window: Window? = null
    private var onLeaveCallback: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window = activity!!.window
    }

    override fun onSlideSelected() {
        var color = arguments!!.getInt(AppIntroBaseFragment.ARG_BG_COLOR)
        var r = color shr 16 and 0xFF
        var g = color shr 8 and 0xFF
        var b = color and 0xFF

        val max = Math.max(Math.max(r, g), b)
        val percToIncrease = 255.0 / max
        val percNew = max / 255.0 - 0.1
        r = (percToIncrease * r.toDouble() * percNew).toInt()
        g = (percToIncrease * g.toDouble() * percNew).toInt()
        b = (percToIncrease * b.toDouble() * percNew).toInt()
        color = Color.argb(255, r, g, b)

        window!!.navigationBarColor = color
        window!!.statusBarColor = color
        super.onSlideSelected()
    }

    override fun getLayoutId(): Int = R.layout.fragment_intro2

    override fun isPolicyRespected(): Boolean = onLeaveCallback == null

    override fun onUserIllegallyRequestedNextPage() {
        if (hasCallback()) {
            onLeaveCallback!!.invoke()
            onLeaveCallback = null
        }
    }

    fun hasCallback(): Boolean = onLeaveCallback != null

    companion object {

        fun newInstance(title: CharSequence, description: CharSequence,
                        @DrawableRes imageDrawable: Int,
                        @ColorInt bgColor: Int,
                        window: Window, onLeaveCallback: (() -> Unit)?): FragmentIntro =
                newInstance(title, description, imageDrawable, bgColor, 0, 0, window, onLeaveCallback)

        private fun newInstance(title: CharSequence, description: CharSequence,
                                @DrawableRes imageDrawable: Int, @ColorInt bgColor: Int,
                                @ColorInt titleColor: Int, @ColorInt descColor: Int,
                                window: Window, onLeaveCallback: (() -> Unit)?): FragmentIntro {
            val slide = FragmentIntro()
            val args = Bundle()
            args.putString(AppIntroBaseFragment.ARG_TITLE, title.toString())
            args.putString(AppIntroBaseFragment.ARG_TITLE_TYPEFACE, null)
            args.putString(AppIntroBaseFragment.ARG_DESC, description.toString())
            args.putString(AppIntroBaseFragment.ARG_DESC_TYPEFACE, null)
            args.putInt(AppIntroBaseFragment.ARG_DRAWABLE, imageDrawable)
            args.putInt(AppIntroBaseFragment.ARG_BG_COLOR, bgColor)
            args.putInt(AppIntroBaseFragment.ARG_TITLE_COLOR, titleColor)
            args.putInt(AppIntroBaseFragment.ARG_DESC_COLOR, descColor)
            slide.arguments = args

            slide.window = window
            slide.onLeaveCallback = onLeaveCallback

            return slide
        }

        fun newInstance(title: CharSequence, titleTypeface: String,
                        description: CharSequence, descTypeface: String,
                        @DrawableRes imageDrawable: Int,
                        @ColorInt bgColor: Int,
                        window: Window, onLeaveCallback: (() -> Unit)?): FragmentIntro {
            return newInstance(title, titleTypeface, description, descTypeface, imageDrawable, bgColor,
                    0, 0, window, onLeaveCallback)
        }

        private fun newInstance(title: CharSequence, titleTypeface: String,
                                description: CharSequence, descTypeface: String,
                                @DrawableRes imageDrawable: Int, @ColorInt bgColor: Int,
                                @ColorInt titleColor: Int, @ColorInt descColor: Int,
                                window: Window, onLeaveCallback: (() -> Unit)?): FragmentIntro {
            val slide = FragmentIntro()
            val args = Bundle()
            args.putString(AppIntroBaseFragment.ARG_TITLE, title.toString())
            args.putString(AppIntroBaseFragment.ARG_TITLE_TYPEFACE, titleTypeface)
            args.putString(AppIntroBaseFragment.ARG_DESC, description.toString())
            args.putString(AppIntroBaseFragment.ARG_DESC_TYPEFACE, descTypeface)
            args.putInt(AppIntroBaseFragment.ARG_DRAWABLE, imageDrawable)
            args.putInt(AppIntroBaseFragment.ARG_BG_COLOR, bgColor)
            args.putInt(AppIntroBaseFragment.ARG_TITLE_COLOR, titleColor)
            args.putInt(AppIntroBaseFragment.ARG_DESC_COLOR, descColor)
            slide.arguments = args

            slide.window = window
            slide.onLeaveCallback = onLeaveCallback

            return slide
        }
    }
}