package com.adsamcik.signals.base.components

import android.content.Context
import android.support.design.widget.CoordinatorLayout
import android.support.design.widget.Snackbar
import android.support.v4.widget.DrawerLayout
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

class BottomBarBehavior(context: Context, attrs: AttributeSet) : CoordinatorLayout.Behavior<LinearLayout>() {

    override fun layoutDependsOn(parent: CoordinatorLayout?, child: LinearLayout?, dependency: View?): Boolean =
            dependency is Snackbar.SnackbarLayout || dependency is DrawerLayout

    override fun onDependentViewChanged(parent: CoordinatorLayout?, child: LinearLayout?, dependency: View?): Boolean {
        if (dependency is Snackbar.SnackbarLayout) {
            val translationY = dependency.translationY - dependency.height
            if (translationY <= 0)
                child!!.translationY = translationY
        }
        return true
    }

    override fun onDependentViewRemoved(parent: CoordinatorLayout?, child: LinearLayout?, dependency: View?) {
        onDependentViewChanged(parent, child, dependency)
        super.onDependentViewRemoved(parent, child, dependency)
    }
}