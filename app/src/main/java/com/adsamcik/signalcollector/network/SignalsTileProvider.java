package com.adsamcik.signalcollector.network;

import android.content.Context;

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

public class SignalsTileProvider implements TileProvider {
	private final static int size = 256;
	private final static int halfSize = size / 2;

	private final OkHttpClient client;

	private String type;
	private boolean personal;
	private final int maxZoom;

	public SignalsTileProvider(Context context, int maxZoom) {
		client = Network.INSTANCE.client(context, null);
		this.maxZoom = maxZoom;
	}

	public void setType(String type) {
		this.type = type;
		this.personal = false;
	}

	public void setTypePersonal() {
		this.personal = true;
	}

	@Override
	public Tile getTile(int x, int y, int z) {
		byte[] tileImage = getTileImage(x, y, z);
		if (tileImage != null)
			return new Tile(halfSize, halfSize, tileImage);
		return NO_TILE;
	}

	private boolean canTileExist(int z) {
		return  !(z > maxZoom);
	}

	private byte[] getTileImage(int x, int y, int z) {
		if(!canTileExist(z))
			return null;


		String url = personal ? String.format(Locale.ENGLISH, Network.INSTANCE.getURL_PERSONAL_TILES(), z, x, y) : String.format(Locale.ENGLISH, Network.INSTANCE.getURL_TILES(), z, x, y, type);
		Call c = client.newCall(new Request.Builder().url(url).build());
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
		int len;
		while ((len = inputStream.read(buffer)) != -1) {
			byteBuffer.write(buffer, 0, len);
		}

		// and then we can return your byte array.
		return byteBuffer.toByteArray();
	}
}
