package com.adsamcik.signalcollector.utility

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.support.annotation.IntRange
import android.support.annotation.StringRes
import android.support.design.widget.Snackbar
import android.util.Pair
import android.view.View
import java.util.concurrent.LinkedBlockingQueue

class SnackMaker {
    private val view: View?

    private val queue = LinkedBlockingQueue<Pair<String, Int>>()
    private var current: Snackbar? = null
    private var handler: Handler? = null

    constructor(view: View) {
        this.view = view
    }

    constructor(activity: Activity) {
        this.view = activity.findViewById(R.id.fabCoordinator)
    }

    fun showSnackbar(@StringRes message: Int) {
        showSnackbar(view!!.context.getString(message), Snackbar.LENGTH_LONG)
    }

    @JvmOverloads
    fun showSnackbar(message: String, @IntRange(from = Snackbar.LENGTH_SHORT.toLong(), to = Snackbar.LENGTH_LONG.toLong()) duration: Int = Snackbar.LENGTH_LONG) {
        if (queue.isEmpty()) {
            queue.add(Pair(message, duration))
            next()
        } else
            queue.add(Pair(message, duration))
    }

    fun showSnackbar(@StringRes message: Int, @IntRange(from = Snackbar.LENGTH_SHORT.toLong(), to = Snackbar.LENGTH_LONG.toLong()) duration: Int) {
        showSnackbar(view!!.context.getString(message), duration)
    }

    fun showSnackbar(@StringRes message: Int, @StringRes action: Int, onClickListener: View.OnClickListener) {
        Snackbar.make(view!!, message, Snackbar.LENGTH_LONG).setAction(action, onClickListener).show()
    }

    private fun interrupt() {
        if (current != null)
            current!!.dismiss()
        queue.clear()
        handler = null
    }

    private operator fun next() {
        if (current != null)
            queue.remove()
        if (!queue.isEmpty()) {
            if (view == null)
                interrupt()
            else {
                current = Snackbar.make(view, queue.peek().first, queue.peek().second)

                current!!.show()
                if (handler == null) {
                    if (Looper.myLooper() == null)
                        Looper.prepare()
                    handler = Handler()
                }
                handler!!.postDelayed({ this.next() }, (if (queue.peek().second == Snackbar.LENGTH_LONG) 3500 else 2000).toLong())
            }
        }
    }
}
