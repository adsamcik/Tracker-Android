package com.adsamcik.signalcollector.data;

import android.util.JsonReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Stat {
	public final String name;
	public final String type;
	public final boolean showPosition;
	public final List<StatData> statData;

	public Stat(String name, String type, boolean showPosition, List<StatData> statData) {
		this.name = name;
		this.type = type;
		this.showPosition = showPosition;
		this.statData = statData;
	}

	public Stat(JsonReader reader) throws IOException {
		String name = null, type = null, className;
		List<StatData> statData = null;
		boolean showPosition = false;

		reader.beginObject();
		while (reader.hasNext()) {
			className = reader.nextName();
			switch (className) {
				case "name":
					name = reader.nextString();
					break;
				case "type":
					type = reader.nextString();
					break;
				case "showPosition":
					showPosition = reader.nextBoolean();
					break;
				case "data":
					statData = readData(reader);
					break;
				default:
					reader.skipValue();
					break;
			}
		}
		reader.endObject();

		//Data are final thus can't be assigned in loop
		this.name = name;
		this.type = type;
		this.showPosition = showPosition;
		this.statData = statData;
	}

	private List<StatData> readData(JsonReader reader) throws IOException {
		String id = null, value = null;
		List<StatData> data = new ArrayList<>();

		reader.beginArray();
		while (reader.hasNext()) {
			reader.beginObject();
			while (reader.hasNext()) {
				String name = reader.nextName();
				switch (name) {
					case "id":
						id = reader.nextString();
						break;
					case "value":
						value = reader.nextString();
						break;
					default:
						reader.skipValue();
				}
			}
			reader.endObject();
			data.add(new StatData(id, value));
		}
		reader.endArray();

		return data;
	}
}
