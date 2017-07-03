package com.adsamcik.signalcollector.utility;

import android.content.Context;

import com.adsamcik.signalcollector.network.Network;
import com.google.android.gms.maps.model.Tile;
import com.google.android.gms.maps.model.TileProvider;
import com.google.firebase.crash.FirebaseCrash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static java.util.stream.Collectors.joining;

public class SignalsTileProvider implements TileProvider {
	private final static int size = 256;
	private final static int halfSize = size / 2;

	private final OkHttpClient client;

	private String type;
	private final int maxZoom;

	public SignalsTileProvider(Context context, int maxZoom) {
		client = Network.client(null, context);
		this.maxZoom = maxZoom;
	}

	public void setType(String type) {
		this.type = type;
	}

	@Override
	public Tile getTile(int x, int y, int z) {
		byte[] tileImage = getTileImage(x, y, z);
		if (tileImage != null)
			return new Tile(halfSize, halfSize, tileImage);
		return NO_TILE;
	}

	private boolean canTileExist(int z) {
		return  !(z < 10 || z > maxZoom);
	}

	private byte[] getTileImage(int x, int y, int z) {
		if(!canTileExist(z))
			return null;

		Call c = client.newCall(new Request.Builder().url(String.format(Locale.ENGLISH, Network.URL_TILES, z, x, y, type)).build());
		Response r = null;

		try {
			r = c.execute();
			ResponseBody body = r.body();
			if (body != null)
				return readBytes(body.byteStream());
		} catch (IOException e) {
			FirebaseCrash.report(e);
		} finally {
			if (r != null)
				r.close();
		}
		return null;
	}

	private byte[] readBytes(InputStream inputStream) throws IOException {
		// this dynamically extends to take the bytes you read
		ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();

		// this is storage overwritten on each iteration with bytes
		int bufferSize = 1024;
		byte[] buffer = new byte[bufferSize];

		// we need to know how many bytes were read to write them to the byteBuffer
		int len = 0;
		while ((len = inputStream.read(buffer)) != -1) {
			byteBuffer.write(buffer, 0, len);
		}

		// and then we can return your byte array.
		return byteBuffer.toByteArray();
	}
}
