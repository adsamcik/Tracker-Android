package com.adsamcik.signalcollector.utility

import android.content.Context
import android.support.constraint.ConstraintLayout
import android.support.design.widget.CoordinatorLayout
import android.support.design.widget.Snackbar
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.adsamcik.signalcollector.uitools.dpAsPx

class BottomBarBehaviorStandard(context: Context, attrs: AttributeSet) : CoordinatorLayout.Behavior<LinearLayout>() {
    private val dp16 = 16.dpAsPx

    /**
     * Used to determine whether the target view moved
     */
    private var last: Float = Float.MIN_VALUE

    private var layoutChange = 0f
    private var ignore = false

    override fun layoutDependsOn(parent: CoordinatorLayout?, child: LinearLayout?, dependency: View?): Boolean =
            dependency is Snackbar.SnackbarLayout

    override fun onDependentViewChanged(parent: CoordinatorLayout, child: LinearLayout, dependency: View): Boolean {
        var changed = false
        if (dependency is Snackbar.SnackbarLayout) {
            if (last == Float.MIN_VALUE) {
                ignore = false
                last = child.translationY
            }

            if (last != child.translationY) {
                ignore = false
                layoutChange = 0.0f
                return false
            }

            val diff = dependency.y - (child.y + child.height + dp16)

            if (diff < 0) {
                layoutChange += diff
                child.translationY += diff
                changed = true
            } else if (layoutChange < 0) {
                if (diff > layoutChange) {
                    child.translationY -= layoutChange
                    layoutChange = 0f
                } else {
                    layoutChange += diff
                    child.translationY += diff
                }
                changed = true
            }

            last = child.translationY
        }
        return changed
    }

    override fun onDependentViewRemoved(parent: CoordinatorLayout, child: LinearLayout, dependency: View?) {
        //Ensures that the view does not get stuck in out of the way position
        child.translationY += layoutChange
        last = Float.MIN_VALUE
        super.onDependentViewRemoved(parent, child, dependency)
    }
}