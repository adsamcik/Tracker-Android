package com.adsamcik.tracker.statistics

import android.view.View
import androidx.annotation.StyleRes
import com.adsamcik.recycler.adapter.implementation.card.CardListAdapter
import com.adsamcik.recycler.adapter.implementation.card.table.TableCard
import com.adsamcik.recycler.adapter.implementation.card.table.TableCardCreator
import com.adsamcik.tracker.shared.utils.style.marker.IViewChange

/**
 * ChangeTableAdapter was created to extend TableAdapter with IViewChange interface for proper color updating
 */
internal class ChangeTableAdapter(@StyleRes themeInt: Int) : CardListAdapter<TableCard.ViewHolder, TableCard>(
		TableCardCreator(themeInt)
), IViewChange {
	override var onViewChangedListener: ((View) -> Unit)? = null

	override fun onViewAttachedToWindow(holder: TableCard.ViewHolder) {
		super.onViewAttachedToWindow(holder)
		onViewChangedListener?.invoke(holder.itemView)
	}
}

