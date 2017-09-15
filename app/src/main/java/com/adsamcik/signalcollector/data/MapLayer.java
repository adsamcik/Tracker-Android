package com.adsamcik.signalcollector.data;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.vimeo.stag.UseStag;

import java.util.ArrayList;

@UseStag
public class MapLayer {
	private String name;
	private ArrayList<ValueColor> values;

	private double top = MAX_LONGITUDE;
	private double right = MAX_LONGITUDE;
	private double bottom = MIN_LATITUDE;
	private double left = MIN_LONGITUDE;

	public static final double MIN_LATITUDE = -90;
	public static final double MAX_LATITUDE = 90;
	public static final double MIN_LONGITUDE = -180;
	public static final double MAX_LONGITUDE = 180;

	MapLayer() {}

	public MapLayer(@NonNull String name, double top, double right, double bottom, double left) {
		this.name = name;
		this.top = top;
		this.right = right;
		this.bottom = bottom;
		this.left = left;
	}

	public static String[] toStringArray(MapLayer[] layerArray) {
		String[] array = new String[layerArray.length];
		for (int i = 0; i < layerArray.length; i++)
			array[i] = layerArray[i].name;
		return array;
	}

	public static int indexOf(MapLayer[] layerArray, String name) {
		for (int i = 0; i < layerArray.length; i++) {
			if (layerArray[i].name.equals(name))
				return i;
		}
		return -1;
	}

	public static boolean contains(MapLayer[] layerArray, String name) {
		for (MapLayer aLayerArray : layerArray) {
			if (aLayerArray.name.equals(name)) {
				return true;
			}
		}
		return false;
	}

	public class ValueColor {
		public final int color;
		public final String name;

		public ValueColor(String name, int color) {
			this.name = name;
			this.color = color;
		}
	}

	//GETTERS
	public @NonNull String getName() {
		return name;
	}

	public @Nullable ArrayList<ValueColor> getValues() {
		return values;
	}

	public double getTop() {
		return top;
	}

	public double getRight() {
		return right;
	}

	public double getBottom() {
		return bottom;
	}

	public double getLeft() {
		return left;
	}

	//SETTERS for STAG
	void setName(@NonNull String name) {
		this.name = name;
	}

	void setValues(@Nullable ArrayList<ValueColor> values) {
		this.values = values;
	}

	void setTop(double top) {
		this.top = top;
	}
	void setRight(double right) {
		this.right = right;
	}
	void setBottom(double bottom) {
		this.bottom = bottom;
	}
	void setLeft(double left) {
		this.left = left;
	}
}
