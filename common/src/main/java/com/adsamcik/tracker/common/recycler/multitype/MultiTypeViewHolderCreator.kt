package com.adsamcik.tracker.common.recycler.multitype

import android.view.ViewGroup

interface MultiTypeViewHolderCreator<DataType : BaseMultiTypeData> {
	fun createViewHolder(parent: ViewGroup): MultiTypeViewHolder<DataType>
}
