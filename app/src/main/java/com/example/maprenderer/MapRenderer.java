package com.example.maprenderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;
import android.view.ScaleGestureDetector;

import com.example.maprenderer.util.Position;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MapRenderer implements GLSurfaceView.Renderer {
    private static final int TILE_SIZE = 256;
    private static final int MIN_ZOOM = 4;
    private static final int MAX_ZOOM = 20;
    private final Position position;
    private final TileLoader tileLoader;
    private GLSurfaceView glSurfaceView;
    private float offsetX = 0.0f;
    private float offsetY = 0.0f;
    private final float[] mProjectionMatrix = new float[16];
    private final float[] mModelMatrix = new float[16];
    private ShortBuffer indexBuffer;
    private final int tilesX = 4;
    private final int tilesY = 4;
    private int shaderProgram;
    FloatBuffer vertexBuffer, texCoordBuffer;
    private int positionHandle, textCoordHandle, mvpMatrixHandle;
    private final Map<String, Integer> tileTextures = new HashMap<>();
    private int vboId, txoId;
    private int lastBoundTexture;
    private long lastTime = System.nanoTime();
    private int frameCount = 0;
    boolean chngzoom;
    int zoomlevel;
    private long lastZoomTime = 0;
    private final Queue<String> tileLoadQueue = new LinkedList<>();
    private final ExecutorService tileLoaderExecutor = Executors.newFixedThreadPool(6);


    public MapRenderer(Context context, GLSurfaceView glSurfaceView) {
        this.tileLoader = new TileLoader(context);
        this.position = new Position(context);
        this.glSurfaceView = glSurfaceView;
    }
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glClearDepthf(1.0f);
        Matrix.setIdentityM(mProjectionMatrix, 0);
        Matrix.setIdentityM(mModelMatrix, 0);
        shaderProgram = createShaderProgram();
        GLES20.glUseProgram(shaderProgram);
        textCoordHandle = GLES20.glGetAttribLocation(shaderProgram, "a_TexCoord");
        mvpMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "u_MVPMatrix");
        positionHandle = GLES20.glGetAttribLocation(shaderProgram, "a_Position");
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glEnableVertexAttribArray(textCoordHandle);
        generateBuffers();
        int[] vboHandles = new int[2];
        GLES20.glGenBuffers(2, vboHandles, 0);
        vboId = vboHandles[0];
        txoId = vboHandles[1];
        position.getNetPosition();
        zoomlevel = position.z;
    }
    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        processTileQueue();
        cleanupOldTextures();
        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.translateM(mModelMatrix, 0, offsetX, offsetY, 0.0f);
        float[] mvpMatrix = new float[16];
        Matrix.multiplyMM(mvpMatrix, 0, mProjectionMatrix, 0, mModelMatrix, 0);
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexBuffer.capacity() * 4, vertexBuffer, GLES20.GL_STATIC_DRAW);
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 3*4, 0);
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, txoId);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, texCoordBuffer.capacity() * 4, texCoordBuffer, GLES20.GL_STATIC_DRAW);
        GLES20.glVertexAttribPointer(textCoordHandle, 2, GLES20.GL_FLOAT, false, 2*4, 0);
        GLES20.glEnableVertexAttribArray(textCoordHandle);
        drawTileGrid();
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        long currentTime = System.nanoTime();
        frameCount++;
        if (currentTime - lastTime >= 1_000_000_000) {
            Log.e("DEBUG", "FPS: " + frameCount);
            Log.e("DEBUG", "Tile cache size: " + tileTextures.size());
            Log.e("DEBUG", "OffsetX: " + offsetX + ", OffsetY: " + offsetY);
            Log.e("DEBUG", "Position X: " + position.x + ", Position Y: " + position.y + " , Position Z: " + position.z);
            frameCount = 0;
            lastTime = currentTime;
        }
    }
    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        float aspectRatio = (float) width / height;
        float worldWidth = tilesX * TILE_SIZE;
        float worldHeight = tilesY * TILE_SIZE;
        if (width > height) {
            Matrix.orthoM(mProjectionMatrix, 0, -worldWidth / 2, worldWidth / 2,
                    -worldWidth / (2 * aspectRatio), worldWidth / (2 * aspectRatio), -1, 1);
        } else {
            Matrix.orthoM(mProjectionMatrix, 0, -worldHeight * aspectRatio / 2, worldHeight * aspectRatio / 2,
                    -worldHeight / 2, worldHeight / 2, -1, 1);
        }
    }
    private void drawTileGrid() {
        for (int x = -tilesX; x < tilesX; x++) {
            for (int y = -tilesY; y < tilesY; y++) {
                int tileX = position.x + x;
                int tileY = position.y + y;
                String key = position.z + "_" + tileX + "_" + tileY;
                if (!tileTextures.containsKey(key) && !tileLoadQueue.contains(key)) {
                    tileLoadQueue.add(key);
                }
                drawTile(x, y);
                if (chngzoom) tileLoadQueue.clear();
            }
        }
    }
    private void drawTile(int x, int y) {
        int tileX = position.x + x;
        int tileY = position.y + y;
        int zoom = position.z;
        String key = zoom + "_" + tileX + "_" + tileY;
        int textureId = tileTextures.getOrDefault(key, -1);
        if (textureId == -1) { return; }
        if (textureId != lastBoundTexture) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            lastBoundTexture = textureId;
        }
        float tileScreenX = (x * TILE_SIZE) - offsetX;
        float tileScreenY = (-y * TILE_SIZE) + offsetY;
        float[] tileModelMatrix = new float[16];
        Matrix.setIdentityM(tileModelMatrix, 0);
        Matrix.translateM(tileModelMatrix, 0, tileScreenX, tileScreenY, 0);
        Matrix.scaleM(tileModelMatrix, 0, TILE_SIZE, TILE_SIZE, 1);
        float[] mvpMatrix = new float[16];
        Matrix.multiplyMM(mvpMatrix, 0, mProjectionMatrix, 0, tileModelMatrix, 0);
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, indexBuffer);
    }
    private void processTileQueue() {
        if (!tileLoadQueue.isEmpty()) {
            String key = tileLoadQueue.poll();
            String[] parts = key.split("_");
            int zoom = Integer.parseInt(parts[0]);
            int tileX = Integer.parseInt(parts[1]);
            int tileY = Integer.parseInt(parts[2]);
            if(tileX < 0 || tileY < 0 || tileX > Math.pow(2, zoom) || tileY > Math.pow(2, zoom)) return;
            tileLoaderExecutor.execute(() -> {
                Bitmap tileBitmap = tileLoader.getTile(zoom, tileX, tileY);
                if (tileBitmap != null) {
                    glSurfaceView.queueEvent(() -> {
                        int textureId = loadTexture(tileBitmap);
                        tileTextures.put(key, textureId);
                    });
                } else {
                    Log.e("TileLoader", "❌ Nepodařilo se získat bitmapu dlaždice: " + key);
                }
            });
        }
    }
    private int loadTexture(Bitmap bitmap) {
        final int[] textureHandle = new int[1];
        GLES20.glGenTextures(1, textureHandle, 0);
        if (textureHandle[0] == 0) {
            Log.e("OpenGL", "❌ Nepodařilo se vytvořit texturu!");
            return -1;
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return textureHandle[0];
    }
    private void cleanupOldTextures() {
        Iterator<Map.Entry<String, Integer>> iterator = tileTextures.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            String key = entry.getKey();
            String[] parts = key.split("_");
            int zoom = Integer.parseInt(parts[0]);
            int tileX = Integer.parseInt(parts[1]);
            int tileY = Integer.parseInt(parts[2]);
            if (Math.abs(tileX - position.x) > tilesX + 2 || Math.abs(tileY - position.y) > tilesY + 2) {
                GLES20.glDeleteTextures(1, new int[]{entry.getValue()}, 0);
                iterator.remove();
            }
        }
    }
    private void generateBuffers() {
        float[] vertices = {
                -0.5f,  0.5f, 0,  // Top-left
                -0.5f, -0.5f, 0,  // Bottom-left
                0.5f, -0.5f, 0,  // Bottom-right
                0.5f,  0.5f, 0   // Top-right
        };

        float[] texCoords = {
                0, 0,   // Top-left
                0, 1,   // Bottom-left
                1, 1,   // Bottom-right
                1, 0    // Top-right
        };

        short[] indices = { 0, 1, 2, 0, 2, 3 };

        vertexBuffer = createBuffer(vertices);
        texCoordBuffer = createBuffer(texCoords);
        indexBuffer = createShortBuffer(indices);
    }
    private FloatBuffer createBuffer(float[] data) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(data).position(0);
        return buffer;
    }
    private ShortBuffer createShortBuffer(short[] data) {
        ShortBuffer buffer = ByteBuffer.allocateDirect(data.length * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer();
        buffer.put(data).position(0);
        return buffer;
    }
    private int createShaderProgram() {
        String vertexShaderCode =
                "attribute vec3 a_Position;" +
                        "attribute vec2 a_TexCoord;" +
                        "varying vec2 v_TexCoord;" +
                        "uniform mat4 u_MVPMatrix;" +
                        "void main() {" +
                        "  gl_Position = u_MVPMatrix * vec4(a_Position, 1.0);" +
                        "  v_TexCoord = a_TexCoord;" +
                        "}";

        String fragmentShaderCode =
                "precision mediump float;" +
                        "varying vec2 v_TexCoord;" +
                        "uniform sampler2D u_Texture;" +
                        "void main() {" +
                        "  gl_FragColor = texture2D(u_Texture, v_TexCoord);" +
                        "}";

        int vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vertexShader, vertexShaderCode);
        GLES20.glCompileShader(vertexShader);

        int fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fragmentShader, fragmentShaderCode);
        GLES20.glCompileShader(fragmentShader);

        int shaderProgram = GLES20.glCreateProgram(); //ShaderHelper.createProgram(vertexShader, fragmentShader);
        GLES20.glAttachShader(shaderProgram, vertexShader);
        GLES20.glAttachShader(shaderProgram, fragmentShader);
        GLES20.glLinkProgram(shaderProgram);

        return shaderProgram;
    }
    public void handleTouchMove(float deltaX, float deltaY) {
        float normalizedX = -deltaX / glSurfaceView.getWidth();
        float normalizedY = -deltaY / glSurfaceView.getHeight();
        offsetX = lerp(offsetX, offsetX + normalizedX * TILE_SIZE * 10, 0.8f);
        offsetY = lerp(offsetY, offsetY + normalizedY * TILE_SIZE * 10, 0.8f);
        if (offsetX > TILE_SIZE && offsetY > TILE_SIZE) {
            position.x++;
            position.y++;
            offsetX -= TILE_SIZE;
            offsetY -= TILE_SIZE;
        }
        if (offsetX > TILE_SIZE && offsetY < -TILE_SIZE) {
            position.x++;
            position.y--;
            offsetX -= TILE_SIZE;
            offsetY += TILE_SIZE;
        }
        if (offsetX < -TILE_SIZE && offsetY < -TILE_SIZE) {
            position.x--;
            position.y--;
            offsetX += TILE_SIZE;
            offsetY += TILE_SIZE;
        }
        if (offsetX < -TILE_SIZE && offsetY > TILE_SIZE) {
            position.x--;
            position.y++;
            offsetX += TILE_SIZE;
            offsetY -= TILE_SIZE;
        }
        if (offsetX > TILE_SIZE) {
            position.x++;
            offsetX -= TILE_SIZE;
        }
        if (offsetX < -TILE_SIZE) {
            position.x--;
            offsetX += TILE_SIZE;
        }
        if (offsetY > TILE_SIZE) {
            position.y++;
            offsetY -= TILE_SIZE;
        }
        if (offsetY < -TILE_SIZE) {
            position.y--;
            offsetY += TILE_SIZE;
        }
    }
    public void handleTouchZoom(ScaleGestureDetector detector){
        chngzoom = true;
        float scaleFactor = detector.getScaleFactor();
        Log.e("scalefactor", "ScaleFactor: " + scaleFactor);
        int newZoom = position.z + (scaleFactor > 1.0 ? 1 : scaleFactor < 1.0 ? -1 : 0);
        newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
        //offsetX = offsetX * (float) Math.pow(2, position.z - newZoom);
        //offsetY = offsetY * (float) Math.pow(2, position.z - newZoom);
        offsetX = 0;
        offsetY = 0;
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastZoomTime > 300) {
            if (newZoom != position.z) {
                position.changeZoom(newZoom);
                lastZoomTime = currentTime;
            }
        }
        chngzoom = false;
    }
    private float lerp(float start, float end, float alpha) {
        return start + alpha * (end - start);
    }
}
