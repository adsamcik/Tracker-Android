package com.adsamcik.tracker.common.recycler.multitype

import com.adsamcik.recycler.adapter.implementation.multitype.BaseMultiTypeData
import com.adsamcik.recycler.adapter.implementation.multitype.MultiTypeViewHolderCreator

interface StyleMultiTypeViewHolderCreator<Type : BaseMultiTypeData>
	: MultiTypeViewHolderCreator<Type, StyleMultiTypeViewHolder<Type>>
