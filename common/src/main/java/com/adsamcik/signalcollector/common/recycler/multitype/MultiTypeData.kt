package com.adsamcik.signalcollector.common.recycler.multitype

interface BaseMultiTypeData {
	val typeValue: Int
}

interface MultiTypeData<T : Enum<*>> : BaseMultiTypeData {
	override val typeValue: Int get() = type.ordinal
	val type: T
}
