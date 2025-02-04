package com.example.maprenderer;

import android.content.Context;
import android.opengl.GLSurfaceView;

public class MapGLView extends GLSurfaceView {

    public MapGLView(Context context) {
        super(context);

        // Nastavíme verzi OpenGL ES (2.0)
        setEGLContextClientVersion(3);

        // Přidáme renderer
        setRenderer(new MapRenderer(context));
        setRenderMode(RENDERMODE_CONTINUOUSLY); // Kontinuální vykreslování
    }
}
