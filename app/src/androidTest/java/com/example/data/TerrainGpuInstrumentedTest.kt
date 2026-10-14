package com.example.data

import android.graphics.*
import android.media.ImageReader
import android.os.Build
import android.opengl.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ui.components.GpuHillshadePainter
import com.example.ui.components.HeightfieldGlRenderer
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class TerrainGpuInstrumentedTest {
    @Test fun agslMatchesCpuForAnisotropicPlaneAndNoData() {
        assumeTrue(Build.VERSION.SDK_INT>=33)
        val w=128; val h=96
        val valid=BooleanArray(w*h){true}.apply { this[40*w+40]=false }
        val grid=ElevationGrid(w,h,FloatArray(w*h){100f+(it%w)*.2f+(it/w)*.3f},FloatArray(w*h),3f,valid,
            cellSizeXMeters=2f,cellSizeYMeters=3f)
        val data=GpuHeightData.build(grid,1f){true}
        for(mode in listOf(0,1,2,6,7)) for(palette in 0..2) {
            val cpu=grid.renderHillshade(315f,35f,1f,palette,contrast=1.3f,visualizationMode=mode)
            val reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)
            val renderer=HardwareRenderer().apply { setSurface(reader.surface); setOpaque(false) }
            val node=RenderNode("terrain-parity").apply { setPosition(0,0,w,h) }
            try {
                val canvas=node.beginRecording(w,h)
                canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
                GpuHillshadePainter(data).draw(canvas,GpuTerrainFrame(data,315f,35f,1.3f,1f,mode,palette),0f,0f,w.toFloat(),h.toFloat())
                node.endRecording(); renderer.setContentRoot(node)
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                var image=reader.acquireNextImage()
                val deadline=System.nanoTime()+2_000_000_000L
                while(image==null && System.nanoTime()<deadline) { Thread.sleep(10); image=reader.acquireNextImage() }
                requireNotNull(image).use {
                    val plane=it.planes[0]; val buffer=plane.buffer
                    for(y in 0 until h) for(x in 0 until w) {
                        val offset=y*plane.rowStride+x*plane.pixelStride
                        val expected=cpu.getPixel(x,y)
                        val channels=intArrayOf(Color.red(expected),Color.green(expected),Color.blue(expected),Color.alpha(expected))
                        for(c in 0..3) assertTrue("mode=$mode palette=$palette $x,$y channel=$c expected=${channels[c]} actual=${buffer.get(offset+c).toInt() and 255}",
                            abs(channels[c]-(buffer.get(offset+c).toInt() and 255))<=4)
                    }
                }
            } finally { renderer.destroy(); reader.close(); cpu.recycle() }
        }
    }
    @Test fun nativeLibraryLoadsAndMatchesJavaOnAndroid() {
        assertTrue("Native library missing from test APK",LazRsNativeDecoder.isAvailable)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val file=File(instrumentation.targetContext.cacheDir,"native-parity.laz")
        try {
            instrumentation.context.assets.open("laz/viewport-chunks.laz").use { input -> file.outputStream().use { input.copyTo(it) } }
            for(focus in listOf(null,NormalizedRasterBounds(.1,.65,.6,.85))) {
                val options=LidarImportOptions(rasterResolution=128,focusBounds=focus)
                val java=LazTerrainReader.readForBenchmark(file,options,LazDecoderBackend.LASZIP4J){true}!!
                val native=LazTerrainReader.readForBenchmark(file,options,LazDecoderBackend.NATIVE_LAZ_RS){true}!!
                assertTrue(native.note.contains("native integer")); assertTrue(TerrainDecoderBenchmark.equivalent(java,native))
            }
        } finally { file.delete() }
    }
    @Test fun floatHeightTextureCompilesAndDrawsOnGles3() {
        val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),0))
        val configs=arrayOfNulls<EGLConfig>(1); val n=IntArray(1)
        assertTrue(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_NONE),0,configs,0,1,n,0))
        val context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE),0)
        val surface=EGL14.eglCreatePbufferSurface(display,configs[0],intArrayOf(EGL14.EGL_WIDTH,96,EGL14.EGL_HEIGHT,96,EGL14.EGL_NONE),0)
        assertTrue(EGL14.eglMakeCurrent(display,surface,surface,context))
        try {
            GLES30.glViewport(0,0,96,96)
            val grid=ElevationGrid(128,96,FloatArray(128*96){40f},FloatArray(128*96),3f,cellSizeXMeters=2f,cellSizeYMeters=3f)
            val m=FloatArray(16); android.opengl.Matrix.setIdentityM(m,0)
            val renderer=HeightfieldGlRenderer()
            renderer.draw(HeightmapCdlod.pack(grid),m,96,96,1f)
            assertEquals(GLES30.GL_NO_ERROR,GLES30.glGetError())
            renderer.release()
        } finally {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,surface); EGL14.eglDestroyContext(display,context); EGL14.eglTerminate(display)
        }
    }
}
