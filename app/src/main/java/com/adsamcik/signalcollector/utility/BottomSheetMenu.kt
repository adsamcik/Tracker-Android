package com.adsamcik.signalcollector.utility

import android.os.Handler
import android.os.Looper
import android.support.annotation.ColorInt
import android.support.annotation.StringRes
import android.support.design.widget.BottomSheetBehavior
import android.support.design.widget.CoordinatorLayout
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.adsamcik.utilities.Assist
import java.util.*

class BottomSheetMenu(root: CoordinatorLayout) {
    private val menuRoot: LinearLayout
    private val menuItems: ArrayList<Button>
    private val bottomSheetBehavior: BottomSheetBehavior<*>

    @ColorInt private val textColor: Int

    init {
        val context = root.context
        /*menuRoot = new LinearLayout(context);
		CoordinatorLayout.LayoutParams layoutParams = new CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		layoutParams.setBehavior(new BottomSheetBehavior());
		menuRoot.setLayoutParams(layoutParams);
		int dp8padding = Assist.dpToPx(context, 8);
		menuRoot.setPadding(0, dp8padding, 0, dp8padding);
		menuRoot.setOrientation(LinearLayout.VERTICAL);
		menuRoot.setBackgroundColor(ContextCompat.getColor(context, R.color.cardBackground));
		root.addView(menuRoot);*/

        LayoutInflater.from(context).inflate(R.layout.bottom_sheet_menu, root)
        menuRoot = root.getChildAt(root.childCount - 1) as LinearLayout
        bottomSheetBehavior = BottomSheetBehavior.from(menuRoot)
        bottomSheetBehavior.peekHeight = Assist.dpToPx(context, 54)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        menuItems = ArrayList()
        val typedValue = TypedValue()
        //inverse text color returned weird values
        context.theme.resolveAttribute(R.attr.titleColor, typedValue, true)
        textColor = typedValue.data
    }

    fun addItem(@StringRes title: Int, onClickListener: View.OnClickListener) {
        val context = menuRoot.context
        val button = Button(context)
        val typedValue = TypedValue()
        context.theme.resolveAttribute(R.attr.selectableItemBackground, typedValue, true)
        button.setBackgroundResource(typedValue.resourceId)
        button.setOnClickListener(onClickListener)
        button.setText(title)
        button.setTextColor(textColor)
        menuItems.add(button)

        menuRoot.addView(button)
    }

    fun removeItemAt(index: Int) {
        menuRoot.removeView(menuItems[index])
        menuItems.removeAt(index)
    }

    fun destroy() {
        val viewParent = menuRoot.parent
        if (viewParent != null)
            (viewParent as ViewGroup).removeView(menuRoot)
    }

    fun showHide(delayInMS: Int) {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        if (Looper.myLooper() == null)
            Looper.prepare()
        Handler().postDelayed({ bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED) }, delayInMS.toLong())
    }
}
