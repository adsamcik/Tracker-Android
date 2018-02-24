package com.adsamcik.signalcollector.uitools

import android.content.Context
import android.support.constraint.ConstraintLayout
import android.view.View
import android.widget.LinearLayout
import android.widget.RelativeLayout

fun View.setMargin(left: Int, top: Int, right: Int, bottom: Int) {
    val layoutParams = layoutParams
    when (layoutParams) {
        is RelativeLayout.LayoutParams -> layoutParams.setMargins(left, top, right, bottom)
        is LinearLayout.LayoutParams -> layoutParams.setMargins(left, top, right, bottom)
        is ConstraintLayout.LayoutParams -> layoutParams.setMargins(left, top, right, bottom)
    }
}

fun View.setBottomMargin(margin: Int) {
    val layoutParams = layoutParams
    when (layoutParams) {
        is RelativeLayout.LayoutParams -> layoutParams.bottomMargin = margin
        is LinearLayout.LayoutParams -> layoutParams.bottomMargin = margin
        is ConstraintLayout.LayoutParams -> layoutParams.bottomMargin = margin
    }
}

fun View.addBottomMargin(margin: Int) {
    val layoutParams = layoutParams
    when (layoutParams) {
        is RelativeLayout.LayoutParams -> layoutParams.bottomMargin += margin
        is LinearLayout.LayoutParams -> layoutParams.bottomMargin += margin
        is ConstraintLayout.LayoutParams -> layoutParams.bottomMargin += margin
    }
}

fun View.marginNavbar() {
    val height = navbarHeight(context)
    if (height > 0)
        addBottomMargin(height)
}

fun navbarHeight(context: Context): Int {
    val resources = context.resources
    val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (resourceId > 0)
        resources.getDimensionPixelSize(resourceId)
    else
        0
}

fun View.paddingNavbar() {
    val height = navbarHeight(context)
    if (height > 0) {
        setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom + height)
    }
}