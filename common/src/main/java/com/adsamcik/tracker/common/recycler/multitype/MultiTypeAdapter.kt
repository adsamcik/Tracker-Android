package com.adsamcik.tracker.common.recycler.multitype

import com.adsamcik.tracker.common.style.StyleController

open class MultiTypeAdapter<DataTypeEnum : Enum<*>, Data : MultiTypeData<DataTypeEnum>>(styleController: StyleController) :
		BaseMultiTypeAdapter<Data>(styleController) {

	/**
	 * Registers [MultiTypeViewHolderCreator] for given [DataTypeEnum].
	 * Provides additional type safety and error reporting compared to [registerType] with integer type value.
	 *
	 * @param typeValue Type of [Data] the [creator] creates view holder for
	 * @param creator View holder creator used for creating views for data of type [typeValue]
	 *
	 * @throws com.adsamcik.tracker.common.recycler.multitype.BaseMultiTypeAdapter.AlreadyRegisteredException Thrown when type was previously registered
	 */
	@Throws(AlreadyRegisteredException::class)
	fun registerType(typeValue: DataTypeEnum, creator: MultiTypeViewHolderCreator<Data>) {
		try {
			registerType(typeValue.ordinal, creator)
		} catch (e: AlreadyRegisteredException) {
			throw AlreadyRegisteredException("Type $typeValue already registered")
		}
	}
}

