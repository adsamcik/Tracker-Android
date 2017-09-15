package com.adsamcik.signalcollector.utility;

import android.support.annotation.NonNull;
import android.util.Log;

import com.adsamcik.signalcollector.data.MapLayer;
import com.adsamcik.signalcollector.interfaces.IFilterRule;

public class MapFilterRule implements IFilterRule<MapLayer> {
	private double top = MapLayer.MIN_LATITUDE, right = MapLayer.MIN_LONGITUDE, bottom = MapLayer.MAX_LATITUDE, left = MapLayer.MAX_LONGITUDE;

	@Override
	public boolean filter(@NonNull MapLayer value, @NonNull String stringValue, @NonNull CharSequence constraint) {
		return value.getTop() > bottom && value.getRight() > left && value.getBottom() < top && value.getLeft() < right;
	}

	public void updateBounds(double top, double right, double bottom, double left) {
		this.top = top;
		this.right = right;
		this.bottom = bottom;
		this.left = left;
	}
}
