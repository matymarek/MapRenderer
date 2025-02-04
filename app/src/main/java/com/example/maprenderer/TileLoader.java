package com.example.maprenderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class TileLoader {

    private static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png"; // OpenStreetMap tile server
    private static final String TAG = "TileLoader";
    private final Context context;

    public TileLoader(Context context) {
        this.context = context;
    }

    public Bitmap getTile(int zoom, int x, int y) {
        String tileUrl = String.format(TILE_URL, zoom, x, y);
        return downloadTile(tileUrl);
    }

    private Bitmap downloadTile(String tileUrl) {
        try {
            URL url = new URL(tileUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "MapRenderer/1.0");
            connection.connect();

            InputStream input = connection.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            input.close();

            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Chyba při stahování dlaždice: " + e.getMessage());
            return null;
        }
    }
}
