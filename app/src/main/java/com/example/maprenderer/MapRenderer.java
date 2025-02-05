package com.example.maprenderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;

import com.example.maprenderer.util.Position;
import com.example.maprenderer.util.ShaderHelper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MapRenderer implements GLSurfaceView.Renderer {
    private static final int TILE_SIZE = 256;
    private Position position;
    private Context context;
    private final TileLoader tileLoader;
    private float offsetX = 0.0f;
    private float offsetY = 0.0f;
    private final float[] mProjectionMatrix = new float[16];
    private final float[] mModelMatrix = new float[16];
    private ShortBuffer indexBuffer;
    private int tilesX = 4, tilesY = 4;
    private int shaderProgram;
    FloatBuffer vertexBuffer, texCoordBuffer;
    private int positionHandle, textCoordHandle, mvpMatrixHandle;
    private final Map<String, Integer> tileTextures = new HashMap<>();
    private int vboId, txoId;
    private int surfaceWidth, surfaceHeight;
    private static final float SMOOTHING_FACTOR = 0.15f;
    private static final float MAX_OFFSET = TILE_SIZE * 1.5f;

    private long lastTime = System.nanoTime();
    private int frameCount = 0;
    public MapRenderer(Context context) {
        this.tileLoader = new TileLoader(context);
        this.position = new Position(context);
        this.context = context;

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
    }
    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
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

        if (currentTime - lastTime >= 1_000_000_000) { // 1 sekunda uplynula
            Log.e("FPS", "FPS: " + frameCount);
            frameCount = 0;
            lastTime = currentTime;
        }
    }
    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        this.surfaceHeight = height;
        this.surfaceWidth = width;
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
        for (int x = -4; x < tilesX + 4; x++) {
            for (int y = -4; y < tilesY + 4; y++) {
                int tileX = position.x + x - (int) Math.floor(offsetX / TILE_SIZE);
                int tileY = position.y + y - (int) Math.floor(offsetY / TILE_SIZE);
                preloadTileTexture(position.z, tileX, tileY);
                drawTile(x, y);
            }
        }
    }
    private void drawTile(int x, int y) {
        int tileX = position.x + x - (int) Math.floor(offsetX / TILE_SIZE);
        int tileY = position.y + y - (int) Math.floor(offsetY / TILE_SIZE);
        int zoom = position.z;
        String key = zoom + "_" + tileX + "_" + tileY;
        int textureId = tileTextures.getOrDefault(key, -1);
        if (textureId == -1) { return; }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
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

    private void preloadTileTexture(int zoom, int tileX, int tileY) {
        String key = zoom + "_" + tileX + "_" + tileY;
        if (tileTextures.containsKey(key)) return;
        Bitmap tileBitmap = tileLoader.getTile(zoom, tileX, tileY);
        if (tileBitmap != null) {
            int textureId = loadTexture(tileBitmap);
            tileTextures.put(key, textureId);
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
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return textureHandle[0];
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
        offsetX -= lerp(offsetX, offsetX - deltaX * 2 * TILE_SIZE, SMOOTHING_FACTOR);
        offsetY += lerp(offsetY, offsetY - deltaY * 2 * TILE_SIZE, SMOOTHING_FACTOR);
        offsetX = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, offsetX));
        offsetY = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, offsetY));

        if (Math.abs(offsetX) >= TILE_SIZE) {
            position.x += (int) Math.signum(offsetX);
            offsetX -= Math.signum(offsetX) * TILE_SIZE;
        }
        if (Math.abs(offsetY) >= TILE_SIZE) {
            position.y += (int) Math.signum(offsetY);
            offsetY -= Math.signum(offsetY) * TILE_SIZE;
        }

        //Log.e("Touch", "✅ Posun: offsetX=" + offsetX + " offsetY=" + offsetY + " Position: x=" + position.x + " y=" + position.y);
    }
    private static float lerp(float start, float end, float alpha) {
        return start + alpha * (end - start);
    }

}
