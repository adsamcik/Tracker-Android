package com.adsamcik.signalcollector.app.adapter

import android.view.View
import androidx.annotation.StyleRes
import com.adsamcik.cardlist.CardListAdapter
import com.adsamcik.cardlist.table.TableCard
import com.adsamcik.cardlist.table.TableCardCreator

/**
 * ChangeTableAdapter was created to extend TableAdapter with IViewChange interface for proper color updating
 */
internal class ChangeTableAdapter(@StyleRes themeInt: Int) : CardListAdapter<TableCard.ViewHolder, TableCard>(TableCardCreator(themeInt)), IViewChange {
	override var onViewChangedListener: ((View) -> Unit)? = null

	override fun onBindViewHolder(holder: TableCard.ViewHolder, position: Int) {
		super.onBindViewHolder(holder, position)
		onViewChangedListener?.invoke(holder.cardView)
	}
}