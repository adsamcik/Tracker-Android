package com.adsamcik.tracker.shared.utils.multitype

import android.view.View
import androidx.annotation.CallSuper
import com.adsamcik.recycler.adapter.implementation.multitype.AlreadyRegisteredException
import com.adsamcik.recycler.adapter.implementation.multitype.BaseMultiTypeAdapter
import com.adsamcik.recycler.adapter.implementation.multitype.BaseSortableMultiTypeAdapter
import com.adsamcik.recycler.adapter.implementation.multitype.MultiTypeData
import com.adsamcik.recycler.adapter.implementation.multitype.MultiTypeViewHolderCreator
import com.adsamcik.recycler.adapter.implementation.sort.callback.SortCallback
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.shared.utils.style.marker.IViewChange

/**
 * Multi-type adapter implementation with IViewChange implemented for proper style changes.
 */
open class StyleMultiTypeAdapter<DataTypeEnum : Enum<*>, Data : MultiTypeData<DataTypeEnum>>(
		val styleController: StyleController
) : BaseMultiTypeAdapter<Data, StyleMultiTypeViewHolder<Data>>(), IViewChange {
	override var onViewChangedListener: ((View) -> Unit)? = null

	override fun onBindViewHolder(holder: StyleMultiTypeViewHolder<Data>, position: Int) {
		holder.bind(getItem(position), styleController)
	}

	@CallSuper
	override fun onViewAttachedToWindow(holder: StyleMultiTypeViewHolder<Data>) {
		super.onViewAttachedToWindow(holder)
		onViewChangedListener?.invoke(holder.itemView)
	}

	@CallSuper
	override fun onViewRecycled(holder: StyleMultiTypeViewHolder<Data>) {
		super.onViewRecycled(holder)
		holder.onRecycle(styleController)
	}

	/**
	 * Registers [MultiTypeViewHolderCreator] for given [DataTypeEnum].
	 * Provides additional type safety and error reporting compared to [registerType]
	 * with integer type value.
	 *
	 * @param typeValue Type of [Data] the [creator] creates view holder for
	 * @param creator View holder creator used for creating views for data of type [typeValue]
	 *
	 * @throws AlreadyRegisteredException Thrown when type was previously registered
	 */
	@Throws(BaseMultiTypeAdapter.AlreadyRegisteredException::class)
	fun registerType(
			typeValue: DataTypeEnum,
			creator: MultiTypeViewHolderCreator<Data, StyleMultiTypeViewHolder<Data>>
	) {
		try {
			registerType(typeValue.ordinal, creator)
		} catch (e: BaseMultiTypeAdapter.AlreadyRegisteredException) {
			throw BaseMultiTypeAdapter.AlreadyRegisteredException(
					"Type $typeValue already registered",
					e
			)
		}
	}
}

/**
 * Multi-type adapter implementation with IViewChange implemented for proper style changes.
 */
open class StyleSortMultiTypeAdapter<DataTypeEnum : Enum<*>, Data : MultiTypeData<DataTypeEnum>>(
		val styleController: StyleController,
		override val sortCallback: SortCallback<Data>,
		dataClass: Class<Data>
) : BaseSortableMultiTypeAdapter<Data, StyleMultiTypeViewHolder<Data>>(dataClass), IViewChange {
	override var onViewChangedListener: ((View) -> Unit)? = null

	override fun onBindViewHolder(holder: StyleMultiTypeViewHolder<Data>, position: Int) {
		holder.bind(getItem(position), styleController)
	}

	@CallSuper
	override fun onViewAttachedToWindow(holder: StyleMultiTypeViewHolder<Data>) {
		super.onViewAttachedToWindow(holder)
		onViewChangedListener?.invoke(holder.itemView)
	}

	@CallSuper
	override fun onViewRecycled(holder: StyleMultiTypeViewHolder<Data>) {
		super.onViewRecycled(holder)
		holder.onRecycle(styleController)
	}

	/**
	 * Registers [MultiTypeViewHolderCreator] for given [DataTypeEnum].
	 * Provides additional type safety and error reporting compared to [registerType]
	 * with integer type value.
	 *
	 * @param typeValue Type of [Data] the [creator] creates view holder for
	 * @param creator View holder creator used for creating views for data of type [typeValue]
	 *
	 * @throws AlreadyRegisteredException Thrown when type was previously registered
	 */
	@Throws(BaseMultiTypeAdapter.AlreadyRegisteredException::class)
	fun registerType(
			typeValue: DataTypeEnum,
			creator: MultiTypeViewHolderCreator<Data, StyleMultiTypeViewHolder<Data>>
	) {
		try {
			registerType(typeValue.ordinal, creator)
		} catch (e: BaseMultiTypeAdapter.AlreadyRegisteredException) {
			throw BaseMultiTypeAdapter.AlreadyRegisteredException(
					"Type $typeValue already registered",
					e
			)
		}
	}
}

