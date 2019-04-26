package com.adsamcik.signalcollector.map.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.map.CoordinateBounds
import com.adsamcik.signalcollector.map.MapLayer
import com.adsamcik.signalcollector.map.adapter.MapFilterableAdapter
import com.adsamcik.signalcollector.misc.extension.getNonNullContext
import com.adsamcik.signalcollector.mock.useMock
import kotlinx.android.synthetic.main.fragment_map_menu.*

class FragmentMapMenu : Fragment(), IOnDemandView {
	val adapter: MapFilterableAdapter get() = recycler.adapter as MapFilterableAdapter

	var onClickListener: ((mapLayer: MapLayer, position: Int) -> Unit)? = null

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		return inflater.inflate(R.layout.fragment_map_menu, container, false)
	}

	override fun onStart() {
		super.onStart()
		val adapter = MapFilterableAdapter(getNonNullContext(), R.layout.spinner_item) { it.name }

		if (useMock) {
			adapter.addAll(arrayListOf(MapLayer("WiFi"),
					MapLayer("CenterFon", MapLayer.MAX_LATITUDE / 2, MapLayer.MAX_LONGITUDE / 2, MapLayer.MIN_LATITUDE / 2, MapLayer.MIN_LONGITUDE / 2),
					MapLayer("Filler01"),
					MapLayer("Filler02"),
					MapLayer("Filler03"),
					MapLayer("Filler04"),
					MapLayer("Filler"),
					MapLayer("Filler"),
					MapLayer("Filler"),
					MapLayer("Filler"),
					MapLayer("Filler")))
		}

		recycler.adapter = adapter
		recycler.layoutManager = LinearLayoutManager(context)
		adapter.onItemClickListener = {
			onClickListener?.invoke(adapter.getItem(it), it)
		}
	}

	fun filter(filterRule: CoordinateBounds) {
		(recycler.adapter as MapFilterableAdapter).filter(filterRule)
	}

	override fun onEnter(activity: FragmentActivity) {

	}

	override fun onLeave(activity: FragmentActivity) {

	}

	override fun onPermissionResponse(requestCode: Int, success: Boolean) {

	}
}