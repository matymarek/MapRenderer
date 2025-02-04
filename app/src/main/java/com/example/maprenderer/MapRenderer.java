package com.example.maprenderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES32;
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
    private int tilesX, tilesY;
    private int shaderProgram;
    FloatBuffer vertexBuffer, texCoordBuffer;
    private int positionHandle, textCoordHandle, mvpMatrixHandle;
    private final Map<String, Integer> tileTextures = new HashMap<>();
    private int vboId;
    public MapRenderer(Context context) {
        this.tileLoader = new TileLoader(context);
        this.position = new Position(context);
        this.context = context;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES32.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0);
        GLES32.glEnable(GLES32.GL_DEPTH_TEST);
        GLES32.glClearDepthf(1.0f);
        GLES32.glEnable(GLES32.GL_TEXTURE_2D);
        Matrix.setIdentityM(mProjectionMatrix, 0);
        Matrix.setIdentityM(mModelMatrix, 0);
        shaderProgram = createShaderProgram();
        GLES32.glUseProgram(shaderProgram);
        positionHandle = GLES32.glGetAttribLocation(shaderProgram, "a_Position");
        textCoordHandle = GLES32.glGetAttribLocation(shaderProgram, "a_TexCoord");
        mvpMatrixHandle = GLES32.glGetUniformLocation(shaderProgram, "u_MVPMatrix");
        GLES32.glEnableVertexAttribArray(positionHandle);
        GLES32.glEnableVertexAttribArray(textCoordHandle);
        position.getNetPosition();
        generateBuffers();
        int[] vboHandles = new int[1];
        GLES32.glGenBuffers(1, vboHandles, 0);
        vboId = vboHandles[0];
    }
    @Override
    public void onDrawFrame(GL10 gl) {
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT | GLES32.GL_DEPTH_BUFFER_BIT);

        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.translateM(mModelMatrix, 0, offsetX, offsetY, 0.0f);
        float[] mvpMatrix = new float[16];
        Matrix.multiplyMM(mvpMatrix, 0, mProjectionMatrix, 0, mModelMatrix, 0);
        GLES32.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);

        GLES32.glActiveTexture(GLES32.GL_TEXTURE0);

        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, vboId);
        GLES32.glVertexAttribPointer(positionHandle, 3, GLES32.GL_FLOAT, false, 5*4, 0);
        GLES32.glVertexAttribPointer(textCoordHandle, 2, GLES32.GL_FLOAT, false, 5*4, 3*4);
        GLES32.glEnableVertexAttribArray(positionHandle);
        GLES32.glEnableVertexAttribArray(textCoordHandle);

        GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, vertexBuffer.capacity() / 3);
        GLES32.glBufferData(GLES32.GL_ARRAY_BUFFER, (vertexBuffer.capacity() + texCoordBuffer.capacity()) * 4, null, GLES32.GL_STATIC_DRAW);

        drawTileGrid();
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, 0);
        Log.e("OpenGL", "VBO ID: " + vboId);
        Log.e("OpenGL", "VertexBuffer Capacity: " + vertexBuffer.capacity());
        Log.e("OpenGL", "Position Handle: " + positionHandle);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES32.glViewport(0, 0, width, height);
        float ratio = (float) width / height;
        Matrix.orthoM(mProjectionMatrix, 0, -ratio, ratio, -1, 1, -1, 1);
    }
    private void drawTileGrid() {
        for (int x = -3; x < tilesX - 1; x++) {
            for (int y = -4; y < tilesY - 1; y++) {
                drawTile(x, y);
            }
        }
    }
    private void drawTile(int x, int y) {
        int tileX = (int) (x - offsetX / TILE_SIZE) + position.x;
        int tileY = (int) (y - offsetY / TILE_SIZE) + position.y;
        int zoom = position.z;

        String key = zoom + "_" + tileX + "_" + tileY;
        int textureId;

        if (tileTextures.containsKey(key)) {
            textureId = tileTextures.get(key);
        } else {
            Bitmap tileBitmap = tileLoader.getTile(zoom, tileX, tileY);
            if (tileBitmap != null) {
                textureId = loadTexture(tileBitmap);
                tileTextures.put(key, textureId);
            } else {
                return;
            }
        }
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textureId);
        GLES32.glDrawElements(GLES32.GL_TRIANGLES, 6, GLES32.GL_UNSIGNED_SHORT, indexBuffer);
    }
    private int loadTexture(Bitmap bitmap) {
        final int[] textureHandle = new int[1];
        GLES32.glGenTextures(1, textureHandle, 0);

        if (textureHandle[0] != 0) {
            GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textureHandle[0]);
            GLES32.glUniform1i(textureHandle[0], 0);
            GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR);
            GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR);
            GLUtils.texImage2D(GLES32.GL_TEXTURE_2D, 0, bitmap, 0);
            bitmap.recycle();
        } else {
            throw new RuntimeException("Nepodařilo se vytvořit texturu.");
        }

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
                "attribute vec4 a_Position;" +
                        "attribute vec2 a_TexCoord;" +
                        "varying vec2 v_TexCoord;" +
                        "uniform mat4 u_MVPMatrix;" +
                        "void main() {" +
                        "  gl_Position = u_MVPMatrix * a_Position;" +
                        "  v_TexCoord = a_TexCoord;" +
                        "}";

        String fragmentShaderCode =
                "precision mediump float;" +
                        "varying vec2 v_TexCoord;" +
                        "uniform sampler2D u_Texture;" +
                        "void main() {" +
                        "  gl_FragColor = texture2D(u_Texture, v_TexCoord);" +
                        "}";

        int vertexShader = ShaderHelper.loadShader(GLES32.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = ShaderHelper.loadShader(GLES32.GL_FRAGMENT_SHADER, fragmentShaderCode);
        return ShaderHelper.createProgram(vertexShader, fragmentShader);
    }
    public void handleTouchMove(float deltaX, float deltaY) {
        offsetX += deltaX;
        offsetY += deltaY;
    }

}
