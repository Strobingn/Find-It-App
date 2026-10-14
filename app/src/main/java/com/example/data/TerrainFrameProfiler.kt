package com.example.data

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.view.FrameMetrics
import android.view.Window
import java.io.Closeable
import java.util.Locale
import kotlin.math.ceil

/** Rolling actual window/presentation metrics, separate from CPU worker/submission timings. */
internal class TerrainFrameStats(private val capacity: Int = 240) {
    init { require(capacity > 0) }
    private val total = DoubleArray(capacity)
    private val gpu = DoubleArray(capacity) { Double.NaN }
    private var cursor=0; private var count=0; private var drops=0L
    @Synchronized fun reset() { cursor=0; count=0; drops=0 }
    @Synchronized fun record(totalNanos: Long, gpuNanos: Long, dropped: Int) {
        if(totalNanos<0) return
        total[cursor]=totalNanos/1_000_000.0
        gpu[cursor]=if(gpuNanos>=0) gpuNanos/1_000_000.0 else Double.NaN
        cursor=(cursor+1)%capacity; count=minOf(count+1,capacity); drops+=dropped.coerceAtLeast(0)
    }
    @Synchronized fun report(): String {
        val durations=total.copyOf(count).sortedArray()
        val gpuDurations=gpu.copyOf(count).filter { it.isFinite() }.sorted()
        fun percentile(p: Double)=if(count==0) 0.0 else durations[(ceil(count*p).toInt()-1).coerceIn(0,count-1)]
        return String.format(Locale.US,"Window frames n=%d · p50=%.2f ms · p95=%.2f ms · max=%.2f ms · callback drops=%d",count,percentile(.5),percentile(.95),durations.lastOrNull()?:0.0,drops)+
            if(gpuDurations.isEmpty()) " · GPU duration unavailable" else String.format(Locale.US," · GPU p95=%.2f ms",gpuDurations[(ceil(gpuDurations.size*.95).toInt()-1).coerceAtLeast(0)])
    }
}

internal object TerrainFrameProfiler {
    private val frames=TerrainFrameStats()
    private var context: Context? = null
    fun attach(window: Window, application: Context): Closeable {
        context=application.applicationContext
        val thermal=if(Build.VERSION.SDK_INT>=29) ThermalHook.attach(application.applicationContext) else null
        val thread=HandlerThread("terrain-frames").apply { start() }
        val listener=Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            frames.record(metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                if(Build.VERSION.SDK_INT>=31) metrics.getMetric(FrameMetrics.GPU_DURATION) else -1,dropped)
        }
        window.addOnFrameMetricsAvailableListener(listener,Handler(thread.looper))
        return Closeable { window.removeOnFrameMetricsAvailableListener(listener); thermal?.close(); thread.quitSafely() }
    }
    fun reset() = frames.reset()
    fun report(): String {
        val thermal=if(Build.VERSION.SDK_INT>=29) runCatching {
            (context?.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus
        }.getOrNull() else null
        val native=runCatching { android.os.Debug.getNativeHeapAllocatedSize()/1048576 }.getOrNull()
        return frames.report()+"\nNative heap ${native?:"unavailable"} MiB · thermal status ${thermal?:"unavailable"}\n"+
            "Latest 240 app-window frames; measure a representative interaction after resetting. GPU submission timings are separate."
    }
}

@android.annotation.TargetApi(29)
private object ThermalHook {
    fun attach(context: Context): Closeable? {
        val manager=context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        AppMemoryBudget.thermalStatus=manager.currentThermalStatus
        val listener=PowerManager.OnThermalStatusChangedListener { AppMemoryBudget.thermalStatus=it }
        manager.addThermalStatusListener(listener)
        return Closeable { manager.removeThermalStatusListener(listener) }
    }
}
