package com.example.helloemulatoropenglbug

/*
Testing GL rendering on Android 12.0 emulator [Pixel 4 API 31 Android 12.0 (Google Play)
x86_x64 SPB3.210618.013], I found a little inconsistency in drawing behaviors.
So I'd like to report it.

When 3d model is drawn by using triangle-indices in ShortBuffer
[GLES20#glDrawElements(int,int,int,java.nio.Buffer)], some parts of the model
are not rendered. On earlier versions of Android OSs (e.g. Android 11.0, 9.0...),
 the problem doesn't seem to occur. So this should be Android 12 specific problem
 (See the screenshot attached).

How to reproduce it:

Create a new empty Kotlin project and replace MainActivity code with the following one.
Then run it on Android 12 (and on earlier versions for comparisons).

Additional Notes:

• No crashes occur regarding this problem. No logcat message to be output.
And the problem seems to appear independently of target sdk version (tested with both SDK30 and SDK31).

• The same problem is observed even when C++/NDK is being used.
So it might be due to some intrinsic GL implementation problem.

    GLushort indices[] = {0, 1, 2, 3, 2, 1};
    glDrawElements(GL_TRIANGLES, 3, GL_UNSIGNED_SHORT, &indices[3]);
    glDrawElements(GL_TRIANGLES, 3, GL_UNSIGNED_SHORT, &indices[0]);

• Since I don't have other environments to test it, I don't know if the problem
still exists even on the subsequent versions of Android 12(beta4+) or on real devices.
 */

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.helloemulatoropenglbug.ui.theme.HelloEmulatorOpenglBugTheme
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10


class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {
    private lateinit var view: GLSurfaceView
    private var program: Int = 0
    private var attrib: Int = 0
    private var verticesGL: Int = 0
    private var verticesRaw: FloatBuffer = genBufferLE(4 * 2 * 4).asFloatBuffer().apply {
        put(floatArrayOf(-0.5F, 0.5F, 0.5F, 0.5F, -0.5F, -0.5F, 0.5F, -0.5F))
        rewind()
    }
    private var indicesRaw: ShortBuffer = genBufferLE(6 * 2).asShortBuffer().apply {
//        put(shortArrayOf(0, 1, 2, 3, 2, 1))
        put(shortArrayOf(0, 1, 2, 2, 1, 3))
        rewind()
    }

    private fun genBufferLE(cap: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(cap).apply { order(ByteOrder.LITTLE_ENDIAN) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "GL Render: Android ${Build.VERSION.SDK_INT}"
        view = GLSurfaceView(baseContext).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@MainActivity)
        }.also { setContentView(it) }
    }



    override fun onSurfaceCreated(p0: GL10?, p1: EGLConfig?) {

        // Initialize: Vertex buffer object
        verticesGL = IntArray(1).also { GLES20.glGenBuffers(1, it, 0) }[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, verticesGL)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, 4 * 2 * 4, verticesRaw, GLES20.GL_STATIC_DRAW
        )

        // Initialize: Shader
        val sourceVertex = "attribute vec2 vP; " +
                "void main() { gl_Position = vec4(vP, 0.5, 1.0); }"
        val sourceFragment = "precision mediump float; " +
                "void main() { gl_FragColor = vec4(1.0, 0.843, 0.0, 1.0); }"

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it,
                GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
                    GLES20.glShaderSource(it, sourceVertex)
                    GLES20.glCompileShader(it)
                })
            GLES20.glAttachShader(it,
                GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
                    GLES20.glShaderSource(it, sourceFragment)
                    GLES20.glCompileShader(it)
                })
            GLES20.glLinkProgram(it)
            attrib = GLES20.glGetAttribLocation(it, "vP")
        }
    }

    override fun onSurfaceChanged(p0: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(p0: GL10?) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glClearColor(0.392F, 0.584F, 0.929F, 1.0F)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        GLES20.glUseProgram(program)

        // Setup indices(to be ShortBuffer) and vertices.
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, GLES20.GL_ZERO)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, verticesGL)
        GLES20.glVertexAttribPointer(attrib, 2, GLES20.GL_FLOAT, false, 2 * 4, 0)
        GLES20.glEnableVertexAttribArray(attrib)

        indicesRaw.position(3) // Render step 1
        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES, 3, GLES20.GL_UNSIGNED_SHORT, indicesRaw
        )
        /* To make this work on Android12 too, we have to configure attrib pointer here again. */
        //GLES20.glVertexAttribPointer(attrib, 2, GLES20.GL_FLOAT, false, 2 * 4, 0)
        GLES20.glVertexAttribPointer(attrib, 2, GLES20.GL_FLOAT, false, 2 * 4, 0)

        indicesRaw.position(0) // Render step 2
        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES, 3, GLES20.GL_UNSIGNED_SHORT, indicesRaw
        )
    }

    override fun onPause() {
        view.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        view.onResume()
    }
}
/*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HelloEmulatorOpenglBugTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}
*/

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    HelloEmulatorOpenglBugTheme {
        Greeting("Android")
    }
}