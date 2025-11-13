package com.example.helloemulatoropenglbug

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Modified to add rendering of a "background.png" texture map and scroll the rendering.
 * See README-TextureRenderingImplementation.md for more details.
 *
 * Background image courtesy of NASA:
 * https://svs.gsfc.nasa.gov/vis/a000000/a003000/a003036/frames/2048x512/OrthoSeaIce.0013.png
 */
class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {
    private lateinit var view: GLSurfaceView
    private var program: Int = 0
    private var attribPos: Int = 0
    private var attribTex: Int = 0  // New: Attribute location for texture coordinates
    private var uniformOffset: Int = 0  // New: Uniform location for scroll offset
    private var textureId: Int = 0  // New: Texture handle
    private var vao: Int = 0
    private var vbo: Int = 0  // Renamed from verticesGL for clarity
    private var ebo: Int = 0

    // Updated: Interleaved vertices: pos (vec2) + texcoord (vec2) per vertex
    // Positions: same square (-0.5,0.5 to 0.5,-0.5)
    // Texcoords: full quad (0,1 to 1,0) for base sampling
    private val verticesRaw: FloatBuffer = genBufferLE(4 * 4 * 4).asFloatBuffer().apply {  // 4 verts * 4 floats (pos+tex) * 4 bytes
        put(floatArrayOf(
            // Top-left: pos, tex
            -0.5F, 0.5F, 0.0F, 1.0F,
            // Top-right
            0.5F, 0.5F, 1.0F, 1.0F,
            // Bottom-left
            -0.5F, -0.5F, 0.0F, 0.0F,
            // Bottom-right
            0.5F, -0.5F, 1.0F, 0.0F
        ))
        rewind()
    }

    // Indices unchanged: two triangles
    private val indicesRaw: ShortBuffer = genBufferLE(6 * 2).asShortBuffer().apply {
        put(shortArrayOf(0, 1, 2, 3, 2, 1))
        rewind()
    }

    // New: Scrolling state
    private var scrollOffsetX: Float = 0.0F
    private var prevTime: Long = 0L
    private val scrollSpeed: Float = 0.25F  // Pixels per second; adjust for game feel

    // Assume a wide PNG texture (e.g., seamless background strip) in assets/background.png
    // For Replica Island migration sim: This could represent a level tilemap strip
    private fun loadTexture(assets: AssetManager): Int {
        val bitmap: Bitmap
        return try {
            assets.open("background.png").use { inputStream ->
                bitmap = BitmapFactory.decodeStream(inputStream)
                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                val texId = textures[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)  // Repeat for seamless scroll
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                bitmap.recycle()
                texId
            }
        } catch (e: IOException) {
            // Fallback: Generate a simple procedural texture if PNG missing (for testing)
            e.printStackTrace()
            0  // Or implement fallback
        }
    }

    private fun genBufferLE(cap: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(cap).apply { order(ByteOrder.LITTLE_ENDIAN) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "GL Render: Android ${Build.VERSION.SDK_INT} (ES 3.0 - Side-Scroller Sim)"
        view = GLSurfaceView(baseContext).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@MainActivity)
        }.also { setContentView(it) }
    }

    override fun onSurfaceCreated(p0: GL10?, p1: EGLConfig?) {
        // Load texture first (requires assets)
        textureId = loadTexture(assets)

        // Generate VAO and EBO
        vao = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
        GLES30.glBindVertexArray(vao)

        // VBO: Interleaved pos + tex
        vbo = IntArray(1).also { GLES20.glGenBuffers(1, it, 0) }[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 4 * 4 * 4, verticesRaw, GLES20.GL_STATIC_DRAW)

        // EBO unchanged
        ebo = IntArray(1).also { GLES20.glGenBuffers(1, it, 0) }[0]
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ebo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, 6 * 2, indicesRaw, GLES20.GL_STATIC_DRAW)

        // Updated: Shaders for GLSL ES 3.00 with texture
        val sourceVertex = "#version 300 es\n" +
                "uniform vec2 uOffset; " +  // New: Scroll offset uniform
                "in vec2 vPos; " +  // Renamed: Position attribute
                "in vec2 vTex; " +  // New: Texture coord attribute
                "out vec2 texCoord; " +  // New: Pass to fragment
                "void main() { " +
                "  gl_Position = vec4(vPos, 0.5, 1.0); " +
                "  texCoord = vTex + uOffset; " +  // Apply offset here (or in fragment)
                "}"
        val sourceFragment = "#version 300 es\n" +
                "precision mediump float; " +
                "in vec2 texCoord; " +
                "uniform sampler2D uTexture; " +  // New: Texture sampler
                "out vec4 FragColor; " +
                "void main() { " +
                "  FragColor = texture(uTexture, texCoord); " +  // Sample with offset texcoord
                "}"

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it,
                GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also { shader ->
                    GLES20.glShaderSource(shader, sourceVertex)
                    GLES20.glCompileShader(shader)
                })
            GLES20.glAttachShader(it,
                GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also { shader ->
                    GLES20.glShaderSource(shader, sourceFragment)
                    GLES20.glCompileShader(shader)
                })
            GLES20.glLinkProgram(it)
            attribPos = GLES20.glGetAttribLocation(it, "vPos")
            attribTex = GLES20.glGetAttribLocation(it, "vTex")  // New
            uniformOffset = GLES20.glGetUniformLocation(it, "uOffset")  // New
        }

        // Configure attributes *inside* VAO binding
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)

        // Position attribute: first 2 floats, stride=16 bytes (4 floats total)
        GLES20.glVertexAttribPointer(attribPos, 2, GLES20.GL_FLOAT, false, 4 * 4, 0)
        GLES20.glEnableVertexAttribArray(attribPos)

        // New: Texcoord attribute: next 2 floats, offset=8 bytes
        GLES20.glVertexAttribPointer(attribTex, 2, GLES20.GL_FLOAT, false, 4 * 4, 2 * 4)
        GLES20.glEnableVertexAttribArray(attribTex)

        GLES30.glBindVertexArray(0)  // Unbind VAO

        // Init timing
        prevTime = SystemClock.elapsedRealtimeNanos()
    }

    override fun onSurfaceChanged(p0: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(p0: GL10?) {
        val currentTime = SystemClock.elapsedRealtimeNanos()
        val deltaTime = (currentTime - prevTime) / 1_000_000_000.0F  // Seconds
        prevTime = currentTime

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glClearColor(0.392F, 0.584F, 0.929F, 1.0F)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        GLES20.glUseProgram(program)

        // New: Bind texture to unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)

        // New: Update scroll uniform (sideways scroll)
        scrollOffsetX -= scrollSpeed * deltaTime  // Negative for right-to-left scroll (common in side-scrollers)
        // Wrap for seamless: assuming texture width >1 in tex units; adjust if needed
        // scrollOffsetX = (scrollOffsetX % 1.0F + 1.0F) % 1.0F  // For repeat wrap
        GLES20.glUniform2f(uniformOffset, scrollOffsetX, 0.0F)

        // Draw: VAO handles all
        GLES30.glBindVertexArray(vao)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

//    override fun onDetachedFromWindow() {
//        GLES30.glDeleteVertexArrays(1, intArrayOf(vao))
//        GLES20.glDeleteBuffers(1, intArrayOf(vbo))
//        GLES20.glDeleteBuffers(1, intArrayOf(ebo))
//        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId))
//        super.onDetachedFromWindow()
//    }
}