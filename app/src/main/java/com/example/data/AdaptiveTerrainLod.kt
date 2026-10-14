package com.example.data

import kotlin.math.*

/** Balanced screen-space quadtree. Neighboring patches differ by at most one level. */
internal object AdaptiveTerrainLod {
    const val CELLS = 64
    const val TARGET_PIXELS = 2f
    data class Patch(val x: Int, val y: Int, val level: Int, val edges: Int = 0) {
        val step get() = 1 shl level
        val size get() = CELLS * step
    }
    data class Plan(val patches: List<Patch>, val maxLevel: Int, val triangles: Long)

    data class Coverage(val columns: Int,val rows: Int,val missing: BooleanArray,val valid: BooleanArray)
    fun coverage(height: HeightmapTexture): Coverage {
        val cols=((height.width-1+CELLS-1)/CELLS).coerceAtLeast(1)
        val rows=((height.height-1+CELLS-1)/CELLS).coerceAtLeast(1)
        val missing=BooleanArray(cols*rows);val valid=BooleanArray(cols*rows)
        for(i in height.floats.indices) {
            val x=i%height.width;val y=i/height.width
            val bx=(x/CELLS).coerceAtMost(cols-1);val by=(y/CELLS).coerceAtMost(rows-1)
            val x0=if(x>0 && x%CELLS==0) x/CELLS-1 else bx
            val y0=if(y>0 && y%CELLS==0) y/CELLS-1 else by
            for(yy in y0.coerceAtLeast(0)..by) for(xx in x0.coerceAtLeast(0)..bx)
                (if(height.floats[i].isFinite()) valid else missing)[yy*cols+xx]=true
        }
        return Coverage(cols,rows,missing,valid)
    }

    fun select(height: HeightmapTexture, mvp: FloatArray, viewWidth: Int, viewHeight: Int,
               minZ: Float, maxZ: Float, targetPixels: Float = TARGET_PIXELS, coverage: Coverage = coverage(height)): Plan {
        require(mvp.size == 16 && mvp.all { it.isFinite() } && targetPixels > 0)
        val extent = max(height.width-1,height.height-1).coerceAtLeast(1)
        val level = ceil(log2(extent.toDouble()/CELLS)).toInt().coerceIn(0,8)
        val span = max((height.width-1)*height.cellSizeXMeters,(height.height-1)*height.cellSizeYMeters).coerceAtLeast(.001f)
        val planes = TerrainFrustumCuller.extractPlanes(mvp)
        fun anyBlock(p: Patch,values: BooleanArray): Boolean {
            val x1=min(coverage.columns-1,(p.x+p.size-1)/CELLS)
            val y1=min(coverage.rows-1,(p.y+p.size-1)/CELLS)
            for(y in p.y/CELLS..y1) for(x in p.x/CELLS..x1) if(values[y*coverage.columns+x]) return true
            return false
        }
        fun visible(p: Patch): Boolean {
            if(p.x >= height.width-1 || p.y >= height.height-1 || !anyBlock(p,coverage.valid)) return false
            val x1=min(p.x+p.size,height.width-1); val y1=min(p.y+p.size,height.height-1)
            return TerrainFrustumCuller.isVisible(TerrainFrustumCuller.Aabb(
                (2*p.x-(height.width-1))*height.cellSizeXMeters/span,
                ((height.height-1)-2*y1)*height.cellSizeYMeters/span,0f,
                (2*x1-(height.width-1))*height.cellSizeXMeters/span,
                ((height.height-1)-2*p.y)*height.cellSizeYMeters/span,(maxZ-minZ)*2/span),planes)
        }
        fun error(p: Patch): Float {
            var pixels=0f
            for(x in intArrayOf(p.x,min(p.x+p.size,height.width-1)))
                for(y in intArrayOf(p.y,min(p.y+p.size,height.height-1)))
                    for(z in floatArrayOf(minZ,maxZ)) pixels=max(pixels,projectedStep(height,mvp,viewWidth,viewHeight,x.toFloat(),y.toFloat(),z,minZ,p.step))
            return pixels
        }
        fun children(p: Patch) = listOf(Patch(p.x,p.y,p.level-1),Patch(p.x+p.size/2,p.y,p.level-1),
            Patch(p.x,p.y+p.size/2,p.level-1),Patch(p.x+p.size/2,p.y+p.size/2,p.level-1))
        val selected=ArrayList<Patch>()
        fun visit(p: Patch) {
            if(!visible(p)) return
            if(p.level > 0 && (anyBlock(p,coverage.missing) || error(p) > targetPixels)) children(p).forEach(::visit) else selected.add(p)
        }
        visit(Patch(0,0,level))
        // Balance corners as well as edges so every seam/corner has a common parent lattice.
        while(true) {
            val split=HashSet<Patch>()
            for(i in selected.indices) for(j in i+1 until selected.size) {
                val a=selected[i]; val b=selected[j]
                if(abs(a.level-b.level)>1 && touching(a,b)) split.add(if(a.level>b.level) a else b)
            }
            if(split.isEmpty()) break
            val next=ArrayList<Patch>()
            for(p in selected) if(p in split) next.addAll(children(p).filter(::visible)) else next.add(p)
            selected.clear(); selected.addAll(next)
        }
        val patched=selected.map { p ->
            var edges=0
            for(q in selected) if(q.level == p.level+1) {
                if(q.x+q.size==p.x && overlap(p.y,p.y+p.size,q.y,q.y+q.size)) edges=edges or 1
                if(p.x+p.size==q.x && overlap(p.y,p.y+p.size,q.y,q.y+q.size)) edges=edges or 2
                if(q.y+q.size==p.y && overlap(p.x,p.x+p.size,q.x,q.x+q.size)) edges=edges or 4
                if(p.y+p.size==q.y && overlap(p.x,p.x+p.size,q.x,q.x+q.size)) edges=edges or 8
            }
            p.copy(edges=edges)
        }
        return Plan(patched,level,patched.size*CELLS*CELLS*2L)
    }
    private fun overlap(a: Int,b: Int,c: Int,d: Int)=max(a,c)<min(b,d)
    internal fun touching(a: Patch,b: Patch)=max(a.x,b.x)<=min(a.x+a.size,b.x+b.size) && max(a.y,b.y)<=min(a.y+a.size,b.y+b.size)

    internal fun projectedStep(h: HeightmapTexture,m: FloatArray,w: Int,v: Int,x: Float,y: Float,z: Float,minZ: Float,step: Int): Float {
        val span=max((h.width-1)*h.cellSizeXMeters,(h.height-1)*h.cellSizeYMeters).coerceAtLeast(.001f)
        fun project(px: Float,py: Float): FloatArray? {
            val a=(2*px-(h.width-1))*h.cellSizeXMeters/span
            val b=((h.height-1)-2*py)*h.cellSizeYMeters/span
            val c=(z-minZ)*2/span
            val clipW=m[3]*a+m[7]*b+m[11]*c+m[15]
            if(clipW<=1e-5f) return null
            return floatArrayOf((m[0]*a+m[4]*b+m[8]*c+m[12])/clipW*w/2,
                (m[1]*a+m[5]*b+m[9]*c+m[13])/clipW*v/2)
        }
        val p=project(x,y) ?: return Float.POSITIVE_INFINITY
        val a=project(x+step,y) ?: return Float.POSITIVE_INFINITY
        val b=project(x,y+step) ?: return Float.POSITIVE_INFINITY
        return max(hypot(a[0]-p[0],a[1]-p[1]),hypot(b[0]-p[0],b[1]-p[1]))
    }

    /** CPU reference for the shader's hierarchical geomorph and edge stitching. */
    internal fun vertex(h: HeightmapTexture,p: Patch,gx: Int,gy: Int,maxLevel: Int,m: FloatArray,w: Int,v: Int,minZ: Float): FloatArray {
        val x=min(p.x+gx*p.step,h.width-1); val y=min(p.y+gy*p.step,h.height-1)
        val edge=(gx==0 && p.edges and 1 != 0)||(gx==CELLS && p.edges and 2 != 0)||
            (gy==0 && p.edges and 4 != 0)||(gy==CELLS && p.edges and 8 != 0)
        fun point(level: Int): FloatArray {
            val step=1 shl level
            val px=if(x==h.width-1) x else x/step*step; val py=if(y==h.height-1) y else y/step*step
            val z=h.floats[py*h.width+px].takeIf { it.isFinite() } ?: minZ
            return floatArrayOf(px.toFloat(),py.toFloat(),z,if(h.floats[py*h.width+px].isFinite()) 1f else 0f)
        }
        var out=point(maxLevel+1)
        for(level in maxLevel downTo p.level+(if(edge) 1 else 0)) {
            val fine=point(level)
            val alpha=(2-2*projectedStep(h,m,w,v,fine[0],fine[1],fine[2],minZ,1 shl level)/TARGET_PIXELS).coerceIn(0f,1f)
            out=if(fine[3]==0f || out[3]==0f) fine else FloatArray(4) { if(it==3) 1f else fine[it]+(out[it]-fine[it])*alpha }
        }
        return out.copyOf(3)
    }
}
