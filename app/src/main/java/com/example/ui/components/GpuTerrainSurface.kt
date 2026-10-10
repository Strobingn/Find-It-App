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
import com.example.data.TerrainFrustumCuller
import com.example.data.TerrainGpuBatch
import com.example.data.TerrainGpuLevel
import com.example.data.TerrainGpuScene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.tan
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

    /** True while two or more fingers are down, which orbits the camera instead of panning it. */
    private var orbiting = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f

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
                // Multi-touch drags orbit; a single finger pans, matching the 2D map.
                if (current.pointerCount > 1) return false
                // Scroll distances run opposite to finger travel, so negate to follow the touch.
                terrainRenderer.pan(-distanceX, -distanceY)
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
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)

            MotionEvent.ACTION_POINTER_DOWN -> {
                orbiting = true
                captureFocus(event)
            }

            MotionEvent.ACTION_MOVE -> if (orbiting && event.pointerCount > 1) {
                val previousX = lastFocusX
                val previousY = lastFocusY
                captureFocus(event)
                // Negated so the terrain turns with the fingers rather than away from them.
                terrainRenderer.rotate(
                    (previousX - lastFocusX) * 0.25f,
                    (previousY - lastFocusY) * 0.16f,
                )
                requestRender()
            }

            // Keep orbiting while three fingers drop to two; stop once only one remains.
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount > 2) {
                    captureFocus(event, event.actionIndex)
                } else {
                    orbiting = false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                orbiting = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    /** Stores the centroid of the active pointers, ignoring one that is on its way up. */
    private fun captureFocus(event: MotionEvent, skipIndex: Int = -1) {
        var sumX = 0f
        var sumY = 0f
        var counted = 0
        for (index in 0 until event.pointerCount) {
            if (index == skipIndex) continue
            sumX += event.getX(index)
            sumY += event.getY(index)
            counted++
        }
        if (counted == 0) return
        lastFocusX = sumX / counted
        lastFocusY = sumY / counted
    }
}

private class TerrainGlRenderer : GLSurfaceView.Renderer {
    private data class GlBatch(
        val vertexBufferId: Int,
        val indexBufferId: Int,
        val indexCount: Int,
        val aabb: TerrainFrustumCuller.Aabb,
    )

    private var program = 0
    private var positionAttribute = -1
    private var normalAttribute = -1
    private var mvpUniform = -1
    private val uploadedLevels = HashMap<Int, List<GlBatch>>()
    private var activeBatches: List<GlBatch> = emptyList()
    private var scene: TerrainGpuScene? = null
    private var stagingVertices: ByteBuffer? = null
    private var stagingIndices: ByteBuffer? = null
    @Volatile private var viewportWidth = 1
    @Volatile private var viewportHeight = 1
    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val panMatrix = FloatArray(16)
    private val pannedViewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewModelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    @Volatile private var zoom = 1.4f
    @Volatile private var yawDegrees = -35f
    @Volatile private var pitchDegrees = 58f
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f

    fun submit(newScene: TerrainGpuScene) {
        if (scene === newScene) return
        deleteAllLevels()
        scene = newScene
        activeBatches = emptyList()
    }

    fun adjustZoom(scaleFactor: Float) {
        zoom = (zoom * scaleFactor).coerceIn(0.75f, 8f)
    }

    fun rotate(yawDelta: Float, pitchDelta: Float) {
        yawDegrees = (yawDegrees - yawDelta) % 360f
        pitchDegrees = (pitchDegrees - pitchDelta).coerceIn(20f, 82f)
    }

    /**
     * Slides the camera across the view plane. Deltas arrive in pixels and are converted using the
     * world height visible at the pivot, so a drag tracks the finger at every zoom level.
     */
    fun pan(deltaXPixels: Float, deltaYPixels: Float) {
        val visibleWorldHeight = 2f * cameraDistance() * HALF_FOV_TANGENT
        val worldPerPixel = visibleWorldHeight / viewportHeight.coerceAtLeast(1)
        panX = (panX + deltaXPixels * worldPerPixel).coerceIn(-PAN_LIMIT, PAN_LIMIT)
        panY = (panY - deltaYPixels * worldPerPixel).coerceIn(-PAN_LIMIT, PAN_LIMIT)
    }

    fun resetCamera() {
        zoom = 1.4f
        yawDegrees = -35f
        pitchDegrees = 58f
        panX = 0f
        panY = 0f
    }

    private fun cameraDistance(): Float = 3.2f / zoom.coerceAtLeast(0.2f)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.035f, 0.04f, 0.05f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttribute = GLES20.glGetAttribLocation(program, "aPosition")
        normalAttribute = GLES20.glGetAttribLocation(program, "aNormal")
        mvpUniform = GLES20.glGetUniformLocation(program, "uMvp")
        uploadedLevels.clear()
        activeBatches = emptyList()
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
        val batches = uploadedLevels[level.reductionFactor] ?: upload(level)
        if (batches.isEmpty()) return
        activeBatches = batches

        GLES20.glUseProgram(program)
        buildMvpMatrix()
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)
        val planes = TerrainFrustumCuller.extractPlanes(mvpMatrix)

        val strideBytes = TerrainGpuBatch.FLOATS_PER_VERTEX * Float.SIZE_BYTES
        for (batch in batches) {
            if (!TerrainFrustumCuller.isVisible(batch.aabb, planes)) continue
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

    private fun upload(level: TerrainGpuLevel): List<GlBatch> {
        val uploaded = ArrayList<GlBatch>(level.batches.size)
        for (batch in level.batches) {
            val ids = IntArray(2)
            GLES20.glGenBuffers(2, ids, 0)

            val vertexBytes = batch.vertices.size * Float.SIZE_BYTES
            val vertexBuffer = reusableFloatStaging(vertexBytes)
            vertexBuffer.asFloatBuffer().apply {
                clear()
                put(batch.vertices)
                position(0)
            }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                vertexBytes,
                vertexBuffer,
                GLES20.GL_STATIC_DRAW,
            )

            val indexBytes = batch.indices.size * Short.SIZE_BYTES
            val indexBuffer = reusableShortStaging(indexBytes)
            indexBuffer.asShortBuffer().apply {
                clear()
                put(batch.indices)
                position(0)
            }
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ids[1])
            GLES20.glBufferData(
                GLES20.GL_ELEMENT_ARRAY_BUFFER,
                indexBytes,
                indexBuffer,
                GLES20.GL_STATIC_DRAW,
            )
            uploaded += GlBatch(
                vertexBufferId = ids[0],
                indexBufferId = ids[1],
                indexCount = batch.indices.size,
                aabb = TerrainFrustumCuller.tileAabb(batch.tile, level.gridWidth, level.gridHeight),
            )
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        uploadedLevels[level.reductionFactor] = uploaded
        return uploaded
    }

    private fun reusableFloatStaging(bytes: Int): ByteBuffer {
        val existing = stagingVertices
        if (existing != null && existing.capacity() >= bytes) {
            existing.clear()
            return existing
        }
        val allocated = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        stagingVertices = allocated
        return allocated
    }

    private fun reusableShortStaging(bytes: Int): ByteBuffer {
        val existing = stagingIndices
        if (existing != null && existing.capacity() >= bytes) {
            existing.clear()
            return existing
        }
        val allocated = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        stagingIndices = allocated
        return allocated
    }

    private fun deleteAllLevels() {
        if (uploadedLevels.isEmpty()) return
        val ids = uploadedLevels.values.flatten().flatMap { listOf(it.vertexBufferId, it.indexBufferId) }
        if (ids.isNotEmpty()) {
            GLES20.glDeleteBuffers(ids.size, ids.toIntArray(), 0)
        }
        uploadedLevels.clear()
        activeBatches = emptyList()
    }

    private fun buildMvpMatrix() {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, pitchDegrees, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, yawDegrees, 0f, 0f, 1f)

        val distance = cameraDistance()
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
        // Pre-multiplying the offset keeps the translation in eye space, so the terrain slides
        // straight across the screen no matter which way the model is currently rotated.
        Matrix.setIdentityM(panMatrix, 0)
        Matrix.translateM(panMatrix, 0, panX, panY, 0f)
        Matrix.multiplyMM(pannedViewMatrix, 0, panMatrix, 0, viewMatrix, 0)

        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, FIELD_OF_VIEW_DEGREES, aspect, 0.1f, 20f)
        Matrix.multiplyMM(viewModelMatrix, 0, pannedViewMatrix, 0, modelMatrix, 0)
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
        private const val FIELD_OF_VIEW_DEGREES = 42f
        private val HALF_FOV_TANGENT =
            tan(Math.toRadians(FIELD_OF_VIEW_DEGREES / 2.0)).toFloat()

        /** The mesh spans roughly two world units, so this keeps it reachable but never lost. */
        private const val PAN_LIMIT = 2f

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
