package com.example.ui.components

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.TerrainGpuBatch
import com.example.data.TerrainGpuLevel
import com.example.data.TerrainGpuScene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Hardware-accelerated 3D terrain view backed by spatially batched OpenGL ES buffers. */
@Composable
fun GpuTerrainSurface(
    scene: TerrainGpuScene,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            TerrainGlSurfaceView(context).apply { setScene(scene) }
        },
        update = { view -> view.setScene(scene) },
        modifier = modifier,
    )
}

private class TerrainGlSurfaceView(context: Context) : GLSurfaceView(context) {
    private val terrainRenderer = TerrainGlRenderer()
    private var submittedScene: TerrainGpuScene? = null
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                terrainRenderer.adjustZoom(detector.scaleFactor)
                requestRender()
                return true
            }
        },
    )
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onScroll(
                first: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                terrainRenderer.rotate(distanceX * 0.25f, distanceY * 0.16f)
                requestRender()
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                terrainRenderer.resetCamera()
                requestRender()
                return true
            }
        },
    )

    init {
        setEGLContextClientVersion(2)
        setRenderer(terrainRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
    }

    fun setScene(scene: TerrainGpuScene) {
        if (submittedScene === scene) return
        submittedScene = scene
        queueEvent { terrainRenderer.submit(scene) }
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaled = scaleDetector.onTouchEvent(event)
        val gestured = gestureDetector.onTouchEvent(event)
        return scaled || gestured || super.onTouchEvent(event)
    }
}

private class TerrainGlRenderer : GLSurfaceView.Renderer {
    private data class GlBatch(
        val vertexBufferId: Int,
        val indexBufferId: Int,
        val indexCount: Int,
    )

    private var program = 0
    private var positionAttribute = -1
    private var normalAttribute = -1
    private var mvpUniform = -1
    private var uploadedReductionFactor: Int? = null
    private val glBatches = ArrayList<GlBatch>()
    private var scene: TerrainGpuScene? = null
    private var viewportWidth = 1
    private var viewportHeight = 1
    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewModelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    @Volatile private var zoom = 1.4f
    @Volatile private var yawDegrees = -35f
    @Volatile private var pitchDegrees = 58f

    fun submit(newScene: TerrainGpuScene) {
        if (scene === newScene) return
        scene = newScene
        uploadedReductionFactor = null
    }

    fun adjustZoom(scaleFactor: Float) {
        zoom = (zoom * scaleFactor).coerceIn(0.75f, 8f)
    }

    fun rotate(yawDelta: Float, pitchDelta: Float) {
        yawDegrees = (yawDegrees - yawDelta) % 360f
        pitchDegrees = (pitchDegrees - pitchDelta).coerceIn(20f, 82f)
    }

    fun resetCamera() {
        zoom = 1.4f
        yawDegrees = -35f
        pitchDegrees = 58f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.035f, 0.04f, 0.05f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttribute = GLES20.glGetAttribLocation(program, "aPosition")
        normalAttribute = GLES20.glGetAttribLocation(program, "aNormal")
        mvpUniform = GLES20.glGetUniformLocation(program, "uMvp")
        uploadedReductionFactor = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val activeScene = scene ?: return
        val level = activeScene.selectForZoom(zoom)
        if (uploadedReductionFactor != level.reductionFactor) upload(level)
        if (glBatches.isEmpty()) return

        GLES20.glUseProgram(program)
        buildMvpMatrix()
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)

        val strideBytes = TerrainGpuBatch.FLOATS_PER_VERTEX * Float.SIZE_BYTES
        for (batch in glBatches) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, batch.vertexBufferId)
            GLES20.glEnableVertexAttribArray(positionAttribute)
            GLES20.glVertexAttribPointer(positionAttribute, 3, GLES20.GL_FLOAT, false, strideBytes, 0)
            GLES20.glEnableVertexAttribArray(normalAttribute)
            GLES20.glVertexAttribPointer(
                normalAttribute,
                3,
                GLES20.GL_FLOAT,
                false,
                strideBytes,
                3 * Float.SIZE_BYTES,
            )
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, batch.indexBufferId)
            GLES20.glDrawElements(
                GLES20.GL_TRIANGLES,
                batch.indexCount,
                GLES20.GL_UNSIGNED_SHORT,
                0,
            )
        }
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(normalAttribute)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun upload(level: TerrainGpuLevel) {
        deleteBuffers()
        for (batch in level.batches) {
            val ids = IntArray(2)
            GLES20.glGenBuffers(2, ids, 0)

            val vertexBuffer = ByteBuffer.allocateDirect(batch.vertices.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(batch.vertices)
                    position(0)
                }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                batch.vertices.size * Float.SIZE_BYTES,
                vertexBuffer,
                GLES20.GL_STATIC_DRAW,
            )

            val indexBuffer = ByteBuffer.allocateDirect(batch.indices.size * Short.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
                .apply {
                    put(batch.indices)
                    position(0)
                }
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ids[1])
            GLES20.glBufferData(
                GLES20.GL_ELEMENT_ARRAY_BUFFER,
                batch.indices.size * Short.SIZE_BYTES,
                indexBuffer,
                GLES20.GL_STATIC_DRAW,
            )
            glBatches += GlBatch(ids[0], ids[1], batch.indices.size)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        uploadedReductionFactor = level.reductionFactor
    }

    private fun deleteBuffers() {
        if (glBatches.isNotEmpty()) {
            val ids = IntArray(glBatches.size * 2)
            glBatches.forEachIndexed { index, batch ->
                ids[index * 2] = batch.vertexBufferId
                ids[index * 2 + 1] = batch.indexBufferId
            }
            GLES20.glDeleteBuffers(ids.size, ids, 0)
            glBatches.clear()
        }
    }

    private fun buildMvpMatrix() {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, pitchDegrees, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, yawDegrees, 0f, 0f, 1f)

        val distance = 3.2f / zoom.coerceAtLeast(0.2f)
        Matrix.setLookAtM(
            viewMatrix,
            0,
            0f,
            -distance,
            distance * 0.72f,
            0f,
            0f,
            0f,
            0f,
            0f,
            1f,
        )
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 42f, aspect, 0.1f, 20f)
        Matrix.multiplyMM(viewModelMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewModelMatrix, 0)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vertex)
        GLES20.glAttachShader(result, fragment)
        GLES20.glLinkProgram(result)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linkStatus, 0)
        require(linkStatus[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(result) }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        require(compileStatus[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            varying float vLight;
            varying float vHeight;
            void main() {
                vec3 lightDirection = normalize(vec3(-0.45, -0.65, 0.8));
                vLight = 0.28 + 0.72 * max(dot(normalize(aNormal), lightDirection), 0.0);
                vHeight = clamp(aPosition.z / 0.75 + 0.5, 0.0, 1.0);
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying float vLight;
            varying float vHeight;
            void main() {
                vec3 low = vec3(0.20, 0.16, 0.10);
                vec3 middle = vec3(0.35, 0.43, 0.25);
                vec3 high = vec3(0.72, 0.70, 0.62);
                vec3 base = vHeight < 0.55
                    ? mix(low, middle, vHeight / 0.55)
                    : mix(middle, high, (vHeight - 0.55) / 0.45);
                gl_FragColor = vec4(base * vLight, 1.0);
            }
        """
    }
}
