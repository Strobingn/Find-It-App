package com.example.ui.components

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.HeightmapTexture
import com.example.data.TerrainClipmapLevel
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
        setEGLContextClientVersion(3)
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
        /** Zero in the clipmap path: the vertex shader reconstructs vertices from the heightmap. */
        val vertexBufferId: Int,
        val indexBufferId: Int,
        val indexCount: Int,
        val aabb: TerrainFrustumCuller.Aabb,
        val usesIntIndices: Boolean,
        val tileOriginX: Int,
        val tileOriginY: Int,
        val tileStride: Int,
    )

    private var program = 0
    private var positionAttribute = -1
    private var normalAttribute = -1
    private var mvpUniform = -1
    private var vtfProgram = 0
    private var vtfMvpUniform = -1
    private var vtfHeightmapUniform = -1
    private var vtfGridSizeUniform = -1
    private var vtfTileUniform = -1
    private var vtfMinElevationUniform = -1
    private var vtfElevRangeUniform = -1
    private var vtfCellSizeUniform = -1
    private val uploadedLevels = HashMap<Int, List<GlBatch>>()
    private val uploadedClipmapTextures = HashMap<Int, Int>()
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
        vtfProgram = runCatching { createProgram(VTF_VERTEX_SHADER, VTF_FRAGMENT_SHADER) }
            .getOrElse { error ->
                com.example.data.TerrainStageProfiler.emit("VTF terrain program failed: ${error.message}")
                0
            }
        if (vtfProgram != 0) {
            vtfMvpUniform = GLES20.glGetUniformLocation(vtfProgram, "uMvp")
            vtfHeightmapUniform = GLES20.glGetUniformLocation(vtfProgram, "uHeightmap")
            vtfGridSizeUniform = GLES20.glGetUniformLocation(vtfProgram, "uGridSize")
            vtfTileUniform = GLES20.glGetUniformLocation(vtfProgram, "uTile")
            vtfMinElevationUniform = GLES20.glGetUniformLocation(vtfProgram, "uMinElevation")
            vtfElevRangeUniform = GLES20.glGetUniformLocation(vtfProgram, "uElevRange")
            vtfCellSizeUniform = GLES20.glGetUniformLocation(vtfProgram, "uCellSize")
        }
        uploadedLevels.clear()
        uploadedClipmapTextures.clear()
        activeBatches = emptyList()
        val version = runCatching { GLES20.glGetString(GLES20.GL_VERSION) }.getOrNull().orEmpty()
        val compute = version.contains("3.1") || version.contains("3.2")
        com.example.data.TerrainStageProfiler.emit(
            "GL $version · compute=${if (compute) "available" else "cpu-fallback"} · " +
                "vtf-terrain=${if (vtfProgram != 0) "enabled" else "cpu-batches-fallback"}",
        )
        com.example.data.TerrainMemoryMonitor.snapshot().let {
            com.example.data.TerrainStageProfiler.emit(it.format())
        }
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
        val clipmap = if (vtfProgram != 0) {
            activeScene.clipmaps.firstOrNull {
                it.reductionFactor == level.reductionFactor &&
                    it.heightmap.width == level.gridWidth &&
                    it.heightmap.height == level.gridHeight
            }
        } else {
            null
        }
        if (clipmap != null) {
            drawClipmapLevel(level, clipmap)
        } else {
            drawCpuBatches(level)
        }
    }

    /**
     * ARM geometry-clipmap path: the heightmap is sampled in the vertex shader, so no
     * per-vertex buffers exist on the GPU. The per-tile index buffers and per-batch
     * frustum culling are shared with the CPU path.
     */
    private fun drawClipmapLevel(level: TerrainGpuLevel, clipmap: TerrainClipmapLevel) {
        val tiles = uploadedLevels[level.reductionFactor] ?: upload(level, uploadVertexBuffers = false)
        if (tiles.isEmpty()) return
        activeBatches = tiles
        val textureId = uploadedClipmapTextures[level.reductionFactor]
            ?: uploadHeightmap(clipmap.heightmap).also {
                uploadedClipmapTextures[level.reductionFactor] = it
            }

        GLES20.glUseProgram(vtfProgram)
        buildMvpMatrix()
        GLES20.glUniformMatrix4fv(vtfMvpUniform, 1, false, mvpMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(vtfHeightmapUniform, 0)
        GLES20.glUniform2i(vtfGridSizeUniform, level.gridWidth, level.gridHeight)
        val elevationRange = (clipmap.maxElevation - clipmap.minElevation).takeIf { it > 0f } ?: 1f
        GLES20.glUniform1f(vtfMinElevationUniform, clipmap.minElevation)
        GLES20.glUniform1f(vtfElevRangeUniform, elevationRange)
        GLES20.glUniform1f(vtfCellSizeUniform, clipmap.heightmap.cellSizeMeters)
        val planes = TerrainFrustumCuller.extractPlanes(mvpMatrix)

        for (tile in tiles) {
            if (!TerrainFrustumCuller.isVisible(tile.aabb, planes)) continue
            GLES20.glUniform3i(vtfTileUniform, tile.tileOriginX, tile.tileOriginY, tile.tileStride)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, tile.indexBufferId)
            GLES20.glDrawElements(
                GLES20.GL_TRIANGLES,
                tile.indexCount,
                if (tile.usesIntIndices) GLES30.GL_UNSIGNED_INT else GLES20.GL_UNSIGNED_SHORT,
                0,
            )
        }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun drawCpuBatches(level: TerrainGpuLevel) {
        val batches = uploadedLevels[level.reductionFactor] ?: upload(level, uploadVertexBuffers = true)
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
                if (batch.usesIntIndices) GLES30.GL_UNSIGNED_INT else GLES20.GL_UNSIGNED_SHORT,
                0,
            )
        }
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(normalAttribute)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun upload(level: TerrainGpuLevel, uploadVertexBuffers: Boolean): List<GlBatch> {
        val uploaded = ArrayList<GlBatch>(level.batches.size)
        for (batch in level.batches) {
            val ids = IntArray(2)
            GLES20.glGenBuffers(if (uploadVertexBuffers) 2 else 1, ids, 0)
            val vertexBufferId: Int
            if (uploadVertexBuffers) {
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
                vertexBufferId = ids[0]
            } else {
                vertexBufferId = 0
            }

            val intIdx = batch.intIndices
            val indexBufferId = if (uploadVertexBuffers) ids[1] else ids[0]
            val indexBytes: Int
            val indexBuffer: java.nio.ByteBuffer
            if (intIdx != null) {
                indexBytes = intIdx.size * Int.SIZE_BYTES
                indexBuffer = reusableShortStaging(indexBytes)
                indexBuffer.asIntBuffer().apply {
                    clear()
                    put(intIdx)
                    position(0)
                }
            } else {
                indexBytes = batch.indices.size * Short.SIZE_BYTES
                indexBuffer = reusableShortStaging(indexBytes)
                indexBuffer.asShortBuffer().apply {
                    clear()
                    put(batch.indices)
                    position(0)
                }
            }
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)
            GLES20.glBufferData(
                GLES20.GL_ELEMENT_ARRAY_BUFFER,
                indexBytes,
                indexBuffer,
                GLES20.GL_STATIC_DRAW,
            )
            uploaded += GlBatch(
                vertexBufferId = vertexBufferId,
                indexBufferId = indexBufferId,
                indexCount = intIdx?.size ?: batch.indices.size,
                aabb = TerrainFrustumCuller.tileAabb(batch.tile, level.gridWidth, level.gridHeight),
                usesIntIndices = intIdx != null,
                tileOriginX = batch.tile.startX,
                tileOriginY = batch.tile.startY,
                tileStride = batch.tile.endXInclusive - batch.tile.startX + 1,
            )
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        uploadedLevels[level.reductionFactor] = uploaded
        return uploaded
    }

    /**
     * Uploads one R32F clipmap heightmap with NEAREST filtering and CLAMP_TO_EDGE wrapping;
     * the vertex shader texelFetches exact texels, so no filtering is ever required.
     */
    private fun uploadHeightmap(heightmap: HeightmapTexture): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES30.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES30.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val staging = reusableFloatStaging(heightmap.floats.size * Float.SIZE_BYTES)
        staging.asFloatBuffer().apply {
            clear()
            put(heightmap.floats)
            position(0)
        }
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES30.GL_R32F,
            heightmap.width,
            heightmap.height,
            0,
            GLES30.GL_RED,
            GLES20.GL_FLOAT,
            staging,
        )
        GLES30.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
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
        if (uploadedLevels.isEmpty() && uploadedClipmapTextures.isEmpty()) return
        val bufferIds = uploadedLevels.values.flatten().flatMap { batch ->
            if (batch.vertexBufferId != 0) {
                listOf(batch.vertexBufferId, batch.indexBufferId)
            } else {
                listOf(batch.indexBufferId)
            }
        }
        if (bufferIds.isNotEmpty()) {
            GLES20.glDeleteBuffers(bufferIds.size, bufferIds.toIntArray(), 0)
        }
        uploadedLevels.clear()
        val textureIds = uploadedClipmapTextures.values.toIntArray()
        if (textureIds.isNotEmpty()) {
            GLES20.glDeleteTextures(textureIds.size, textureIds, 0)
        }
        uploadedClipmapTextures.clear()
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

        /**
         * ES 3.00 vertex-texture-fetch terrain: the vertex shader reconstructs position and
         * normal from the clipmap heightmap (ARM geometry-clipmap pattern). texelFetch with
         * NEAREST keeps every value identical to the CPU-built batch path, and gl_VertexID
         * carries the tile-local vertex index from the index buffer, so no vertex buffer is
         * ever bound. Neighbors clamp at the grid edges and no-data neighbors fall back to
         * the center elevation, matching TerrainGpuSceneBuilder.writeNormal.
         */
        private const val VTF_VERTEX_SHADER = """
            #version 300 es
            uniform mat4 uMvp;
            uniform sampler2D uHeightmap;
            uniform ivec2 uGridSize;
            uniform ivec3 uTile;
            uniform float uMinElevation;
            uniform float uElevRange;
            uniform float uCellSize;
            out float vLight;
            out float vHeight;
            float texel(ivec2 p) {
                ivec2 c = clamp(p, ivec2(0), uGridSize - 1);
                return texelFetch(uHeightmap, c, 0).r;
            }
            void main() {
                int stride = uTile.z;
                int localY = gl_VertexID / stride;
                int localX = gl_VertexID - localY * stride;
                int gx = uTile.x + localX;
                int gy = uTile.y + localY;
                float z = texelFetch(uHeightmap, ivec2(gx, gy), 0).r;
                if (isnan(z)) {
                    // No-data texels never belong to a drawn quad: quads with an invalid
                    // corner are skipped in the index buffer. This only collapses strays.
                    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                    vLight = 0.0;
                    vHeight = 0.0;
                    return;
                }
                float px = uGridSize.x > 1 ? float(gx) / float(uGridSize.x - 1) * 2.0 - 1.0 : 0.0;
                float py = uGridSize.y > 1 ? 1.0 - float(gy) / float(uGridSize.y - 1) * 2.0 : 0.0;
                float pz = ((z - uMinElevation) / uElevRange - 0.5) * 0.75;
                float zl = texel(ivec2(gx - 1, gy));
                float zr = texel(ivec2(gx + 1, gy));
                float zu = texel(ivec2(gx, gy - 1));
                float zd = texel(ivec2(gx, gy + 1));
                zl = isnan(zl) ? z : zl;
                zr = isnan(zr) ? z : zr;
                zu = isnan(zu) ? z : zu;
                zd = isnan(zd) ? z : zd;
                float dx = (zr - zl) / max(2.0 * uCellSize, 0.001);
                float dy = (zd - zu) / max(2.0 * uCellSize, 0.001);
                vec3 normal = normalize(vec3(-dx, dy, 1.0));
                vec3 lightDirection = normalize(vec3(-0.45, -0.65, 0.8));
                vLight = 0.28 + 0.72 * max(dot(normal, lightDirection), 0.0);
                vHeight = clamp(pz / 0.75 + 0.5, 0.0, 1.0);
                gl_Position = uMvp * vec4(px, py, pz, 1.0);
            }
        """

        private const val VTF_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            in float vLight;
            in float vHeight;
            out vec4 fragColor;
            void main() {
                vec3 low = vec3(0.20, 0.16, 0.10);
                vec3 middle = vec3(0.35, 0.43, 0.25);
                vec3 high = vec3(0.72, 0.70, 0.62);
                vec3 base = vHeight < 0.55
                    ? mix(low, middle, vHeight / 0.55)
                    : mix(middle, high, (vHeight - 0.55) / 0.45);
                fragColor = vec4(base * vLight, 1.0);
            }
        """
    }
}
