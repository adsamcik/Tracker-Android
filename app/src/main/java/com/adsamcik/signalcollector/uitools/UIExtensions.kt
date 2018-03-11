package com.adsamcik.signalcollector.uitools

import android.content.res.Resources
import android.support.constraint.ConstraintLayout
import android.view.View
import android.widget.LinearLayout
import android.widget.RelativeLayout
import com.adsamcik.signalcollector.utility.Assist.navbarSize


fun View.setMargin(left: Int, top: Int, right: Int, bottom: Int) {
    val layoutParams = layoutParams
    when (layoutParams) {
        is RelativeLayout.LayoutParams -> layoutParams.setMargins(left, top, right, bottom)
        is LinearLayout.LayoutParams -> layoutParams.setMargins(left, top, right, bottom)
        is ConstraintLayout.LayoutParams -> layoutParams.setMargins(left, top, right, bottom)
    }
    requestLayout()
}

fun View.setBottomMargin(margin: Int) {
    val layoutParams = layoutParams
    when (layoutParams) {
        is RelativeLayout.LayoutParams -> layoutParams.bottomMargin = margin
        is LinearLayout.LayoutParams -> layoutParams.bottomMargin = margin
        is ConstraintLayout.LayoutParams -> layoutParams.bottomMargin = margin
    }
    requestLayout()
}

fun View.addBottomMargin(margin: Int) {
    val layoutParams = layoutParams
    when (layoutParams) {
        is RelativeLayout.LayoutParams -> layoutParams.bottomMargin += margin
        is LinearLayout.LayoutParams -> layoutParams.bottomMargin += margin
        is ConstraintLayout.LayoutParams -> layoutParams.bottomMargin += margin
    }
    requestLayout()
}

val Int.pxAsDp get() = (this / Resources.getSystem().displayMetrics.density).toInt()
val Int.dpAsPx get() = (this * Resources.getSystem().displayMetrics.density).toInt()

val View.marginLeft: Int
    get() {
        val layoutParams = layoutParams ?: return 0
        return when (layoutParams) {
            is RelativeLayout.LayoutParams -> layoutParams.leftMargin
            is LinearLayout.LayoutParams -> layoutParams.leftMargin
            is ConstraintLayout.LayoutParams -> layoutParams.leftMargin
            else -> 0
        }
    }

val View.marginTop: Int
    get() {
        val layoutParams = layoutParams ?: return 0
        return when (layoutParams) {
            is RelativeLayout.LayoutParams -> layoutParams.topMargin
            is LinearLayout.LayoutParams -> layoutParams.topMargin
            is ConstraintLayout.LayoutParams -> layoutParams.topMargin
            else -> 0
        }
    }

val View.marginRight: Int
    get() {
        val layoutParams = layoutParams ?: return 0
        return when (layoutParams) {
            is RelativeLayout.LayoutParams -> layoutParams.rightMargin
            is LinearLayout.LayoutParams -> layoutParams.rightMargin
            is ConstraintLayout.LayoutParams -> layoutParams.rightMargin
            else -> 0
        }
    }

val View.marginBottom: Int
    get() {
        val layoutParams = layoutParams ?: return 0
        return when (layoutParams) {
            is RelativeLayout.LayoutParams -> layoutParams.bottomMargin
            is LinearLayout.LayoutParams -> layoutParams.bottomMargin
            is ConstraintLayout.LayoutParams -> layoutParams.bottomMargin
            else -> 0
        }
    }