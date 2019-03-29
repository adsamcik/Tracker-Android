package com.adsamcik.signalcollector.misc

import android.os.Handler
import android.os.Looper
import android.util.Pair
import android.view.View
import androidx.annotation.IntDef
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.Snackbar.*
import java.lang.ref.WeakReference
import java.util.concurrent.LinkedBlockingQueue

/**
 * SnackMaker class is builder and manager class for Snackbars.
 * It can properly queue multiple Snackbars and display them in order.
 */
//todo all methods should use queue
class SnackMaker(view: View) {
	@Retention(AnnotationRetention.SOURCE)
	@IntDef(LENGTH_INDEFINITE, LENGTH_LONG, LENGTH_SHORT)
	annotation class SnackDuration

	private val weakView: WeakReference<View> = WeakReference(view)

	private val queue = LinkedBlockingQueue<Pair<String, Int>>()
	private var current: Snackbar? = null
	private var handler: Handler? = null

	/**
	 * Adds message to SnackMaker queue
	 *
	 * @param message Message
	 * @param duration Duration that has to be one of [SnackDuration] values
	 */
	fun showSnackbar(message: String, @SnackDuration duration: Int = Snackbar.LENGTH_LONG) {
		if (queue.isEmpty()) {
			queue.add(Pair(message, duration))
			next()
		} else
			queue.add(Pair(message, duration))
	}

	/**
	 * Adds message to SnackMaker queue
	 *
	 * @param message Message resource
	 * @param duration Duration that has to be one of [SnackDuration] values
	 */
	fun showSnackbar(@StringRes message: Int, @SnackDuration duration: Int = LENGTH_LONG) {
		showSnackbar(weakView.get()!!.context.getString(message), duration)
	}

	/**
	 * Adds message with action to SnackMaker queue
	 *
	 * @param message Message string resource
	 * @param duration Duration that has to be one of [SnackDuration] values
	 * @param action Action string resource
	 * @param onClickListener On action click listener
	 */
	fun showSnackbar(@StringRes message: Int, @StringRes action: Int, onClickListener: View.OnClickListener, @SnackDuration duration: Int = LENGTH_LONG) {
		Snackbar.make(weakView.get()!!, message, duration).setAction(action, onClickListener).show()
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
			val view = weakView.get()
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
