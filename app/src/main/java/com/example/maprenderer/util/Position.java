package com.example.maprenderer.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.util.Log;
import androidx.appcompat.app.AlertDialog;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import java.util.concurrent.CountDownLatch;

public class Position {
    public int x;
    public int y;
    public int z = 15;
    double latitude, longitude;
    private FusedLocationProviderClient mLocationClient;
    Context context;
    Runnable onPositionReadyCallback;


    public Position(Context context) {
        this.context = context;
        this.mLocationClient = LocationServices.getFusedLocationProviderClient(context);
    }
    @SuppressLint("MissingPermission")
    private void getCurrentLocation(Runnable callback) {
        onPositionReadyCallback = callback;
        mLocationClient.getLastLocation().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Location location = task.getResult();
                if(location != null) {
                    latitude = location.getLatitude();
                    longitude = location.getLongitude();
                }
                else {
                    noLocation();
                    Log.e("GPS", "Poloha je NULL", task.getException());
                }
            }
            else {
                noLocation();
                Log.e("GPS", "Získání polohy selhalo", task.getException());
            }
            if (onPositionReadyCallback != null) {
                onPositionReadyCallback.run();
            }
        });
    }
    public void getNetPosition(){
        CountDownLatch latch = new CountDownLatch(1);
        getCurrentLocation(() -> {
            x = (int) Math.round((longitude + 180) / 360 * Math.pow(2, z));
            y = (int) Math.round((1 - Math.log(Math.tan(Math.toRadians(latitude)) +
                    1 / Math.cos(Math.toRadians(latitude))) / Math.PI) / 2 * Math.pow(2, z));
            Log.e("Position", "🌍 Po výpočtu: X=" + x + ", Y=" + y + ", Zoom=" + z);
            latch.countDown();
        });
        try{
            latch.await();
        }
        catch (InterruptedException e){
            Log.e("GPS", "Čekání na polohu bylo přerušeno", e);
        }
    }
    public void changeZoom(int desiredZoom){
        double lon = x / Math.pow(2, z) * 360.0 - 180.0;
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI - 2.0 * Math.PI * y / Math.pow(2, z))));
        z = desiredZoom;
        x = (int) Math.round((lon + 180) / 360 * Math.pow(2, z));
        y = (int) Math.round((1 - Math.log(Math.tan(Math.toRadians(lat)) +
                1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * Math.pow(2, z));
        Log.e("ChangeZoom", "🔄 Zoom změněn: " + z + " → " + desiredZoom);
    }
    private void noLocation(){
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Chyba");
        builder.setMessage("Nepodařilo se určit polohu zařízení.");
        builder.setPositiveButton("OK", (dialog, id) -> {
        });
        builder.create().show();
    }
}
