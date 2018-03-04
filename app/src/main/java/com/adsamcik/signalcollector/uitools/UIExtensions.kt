package com.adsamcik.signalcollector.uitools

import android.content.Context
import android.graphics.Point
import android.support.constraint.ConstraintLayout
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RelativeLayout


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

fun View.marginNavbar() {
    val height = navbarSize(context).y
    if (height > 0)
        addBottomMargin(height)
}

fun getAppUsableScreenSize(context: Context): Point {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val display = windowManager.defaultDisplay
    val size = Point()
    display.getSize(size)
    return size
}

fun getRealScreenSize(context: Context): Point {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val display = windowManager.defaultDisplay
    val size = Point()
    display.getRealSize(size)
    return size
}

fun navbarSize(context: Context): Point {
    val appUsableSize = getAppUsableScreenSize(context)
    val realScreenSize = getRealScreenSize(context)

    // navigation bar on the right
    if (appUsableSize.x < realScreenSize.x) {
        return Point(realScreenSize.x - appUsableSize.x, appUsableSize.y)
    }

    // navigation bar at the bottom
    return if (appUsableSize.y < realScreenSize.y) {
        Point(appUsableSize.x, realScreenSize.y - appUsableSize.y)
    } else Point()
}

fun View.paddingNavbar() {
    val height = navbarSize(context).y
    if (height > 0) {
        setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom + height)
    }
}

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