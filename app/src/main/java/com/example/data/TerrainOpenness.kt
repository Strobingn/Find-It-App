package com.example.data

import kotlin.math.*

/** Eight physical-direction horizon profiles. No survey returns are changed by this visualization. */
internal object TerrainOpenness {
    fun compute(grid: ElevationGrid, vegetation: Float, radiusMeters: Float, negative: Boolean,
                shouldContinue: () -> Boolean): FloatArray {
        require(radiusMeters.isFinite() && radiusMeters>0)
        val out=FloatArray(grid.width*grid.height){Float.NaN}
        val heights=FloatArray(out.size){ i -> grid.bareEarth[i]+grid.canopySpikes[i]*(1f-vegetation.coerceIn(0f,1f)) }
        val cx=grid.cellSizeXMeters.toDouble(); val cy=grid.cellSizeYMeters.toDouble()
        for(y in 0 until grid.height) {
            if(!shouldContinue()) throw java.util.concurrent.CancellationException("Openness superseded")
            for(x in 0 until grid.width) {
                val index=y*grid.width+x
                if(!grid.validData[index] || !heights[index].isFinite()) continue
                var sum=0.0; var directions=0
                for(direction in 0..7) {
                    val angle=direction*PI/4; val ux=cos(angle); val uy=sin(angle)
                    val step=min(if(abs(ux)>1e-8) cx/abs(ux) else Double.POSITIVE_INFINITY,
                        if(abs(uy)>1e-8) cy/abs(uy) else Double.POSITIVE_INFINITY)
                    val steps=ceil(radiusMeters/step).toInt().coerceAtMost(max(grid.width,grid.height)*2)
                    var horizon=Double.NEGATIVE_INFINITY
                    for(k in 1..steps) {
                        val distance=k*step
                        if(distance>radiusMeters) break
                        val sx=x+distance*ux/cx; val sy=y+distance*uy/cy
                        if(sx<0 || sy<0 || sx>grid.width-1 || sy>grid.height-1) break
                        val ix=floor(sx).toInt(); val iy=floor(sy).toInt()
                        val jx=min(ix+1,grid.width-1); val jy=min(iy+1,grid.height-1)
                        val corners=intArrayOf(iy*grid.width+ix,iy*grid.width+jx,jy*grid.width+ix,jy*grid.width+jx)
                        if(corners.any { !grid.validData[it] || !heights[it].isFinite() }) continue
                        val fx=sx-ix; val fy=sy-iy
                        val a=heights[corners[0]]*(1-fx)+heights[corners[1]]*fx
                        val b=heights[corners[2]]*(1-fx)+heights[corners[3]]*fx
                        val sample=a*(1-fy)+b*fy
                        val slope=(sample-heights[index])/distance * if(negative) -1.0 else 1.0
                        horizon=max(horizon,slope)
                    }
                    if(horizon.isFinite()) { sum+=PI/2-atan(horizon); directions++ }
                }
                if(directions>=4) out[index]=(sum/directions).toFloat()
            }
        }
        return out
    }
}
