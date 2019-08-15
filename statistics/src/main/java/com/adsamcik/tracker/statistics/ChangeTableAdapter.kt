package com.adsamcik.tracker.statistics

import android.view.View
import androidx.annotation.StyleRes
import com.adsamcik.recycler.card.CardListAdapter
import com.adsamcik.recycler.card.table.TableCard
import com.adsamcik.recycler.card.table.TableCardCreator
import com.adsamcik.tracker.common.style.IViewChange

/**
 * ChangeTableAdapter was created to extend TableAdapter with IViewChange interface for proper color updating
 */
internal class ChangeTableAdapter(@StyleRes themeInt: Int) : CardListAdapter<TableCard.ViewHolder, TableCard>(
		TableCardCreator(themeInt)), IViewChange {
	override var onViewChangedListener: ((View) -> Unit)? = null

	override fun onBindViewHolder(holder: TableCard.ViewHolder, position: Int) {
		super.onBindViewHolder(holder, position)
		onViewChangedListener?.invoke(holder.cardView)
	}
}

