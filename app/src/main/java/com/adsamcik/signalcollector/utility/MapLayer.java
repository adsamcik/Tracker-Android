package com.adsamcik.signalcollector.utility;

import java.util.ArrayList;

public class MapLayer {
	public String name;
	public ArrayList<ValueColor> values;

	public static String[] toStringArray(MapLayer[] layerArray) {
		String[] array = new String[layerArray.length];
		for (int i = 0; i < layerArray.length; i++)
			array[i] = layerArray[i].name;
		return array;
	}

	public static int indexOf(MapLayer[] layerArray,String name) {
		for (int i = 0; i < layerArray.length; i++) {
			if(layerArray[i].name.equals(name))
				return i;
		}
		return -1;
	}

	public static boolean contains(MapLayer[] layerArray,String name) {
		for (MapLayer aLayerArray : layerArray) {
			if (aLayerArray.name.equals(name)) {
				return true;
			}
		}
		return false;
	}

	public class ValueColor {
		public int color;
		public String name;

		public ValueColor(String name, int color) {
			this.name = name;
			this.color = color;
		}
	}
}
