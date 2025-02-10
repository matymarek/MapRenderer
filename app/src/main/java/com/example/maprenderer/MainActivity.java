package com.example.maprenderer;

import static androidx.core.content.PermissionChecker.PERMISSION_GRANTED;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    public GLSurfaceView glSurfaceView;
    private MapRenderer mapRenderer;
    boolean network;
    double netSpeed;
    float lastTouchX = 0;
    float lastTouchY = 0;
    Runnable onPermissionGrantedCallback;
    private ScaleGestureDetector scaleGestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkPermission(() -> {
            initNetwork(this);
            glSurfaceView = new GLSurfaceView(this);
            glSurfaceView.setEGLContextClientVersion(2);
            mapRenderer = new MapRenderer(this, glSurfaceView);
            glSurfaceView.setRenderer(mapRenderer);
            setContentView(glSurfaceView);
            scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    Log.e("ZoomEvent", "🔍 Gesto detekováno!");
                    Log.e("ZoomEvent", "🔍 Průměrná pozice prstů: X=" + detector.getFocusX() + ", Y=" + detector.getFocusY());
                    mapRenderer.handleTouchZoom(detector);
                    return true;
                }
            });
        });

    }
    @Override
    protected void onPause() {
        super.onPause();
        glSurfaceView.onPause();
    }
    @Override
    protected void onResume() {
        super.onResume();
        glSurfaceView.onResume();
    }
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float deltaX;
        float deltaY;
        float currentX = event.getX();
        float currentY = event.getY();
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (event.getHistorySize() > 0) {
                deltaX = currentX - event.getHistoricalX(event.getHistorySize() - 1);
                deltaY = currentY - event.getHistoricalY(event.getHistorySize() - 1);
            } else {
                deltaX = currentX - lastTouchX;
                deltaY = currentY - lastTouchY;
            }
            mapRenderer.handleTouchMove(deltaX, deltaY);
        }
        scaleGestureDetector.onTouchEvent(event);
        Log.e("TouchEvent", "📱 Gesto předáno ScaleDetectoru: " + event.getAction());
        lastTouchX = currentX;
        lastTouchY = currentY;
        return true;
    }
    public void initNetwork(Context context) {
        ConnectivityManager connectivityManager = ((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE));
        network = connectivityManager.getActiveNetworkInfo() != null && connectivityManager.getActiveNetworkInfo().isConnected();
        NetworkCapabilities nc = connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork());
        netSpeed = nc.getLinkDownstreamBandwidthKbps();
    }
    public void checkPermission(Runnable callback) {
        onPermissionGrantedCallback = callback;
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
            String[] permissions = new String[]{ android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.ACCESS_NETWORK_STATE };
            requestPermissions(permissions, PERMISSION_GRANTED);
        }
        else if(onPermissionGrantedCallback != null) {
            onPermissionGrantedCallback.run();
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_GRANTED) {
            boolean allGranted = true;

            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted && onPermissionGrantedCallback != null) {
                onPermissionGrantedCallback.run();
            }
        }
    }
}

