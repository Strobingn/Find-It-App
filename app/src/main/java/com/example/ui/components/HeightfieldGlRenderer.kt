package com.example.ui.components

import android.opengl.GLES30 as GL
import com.example.data.HeightmapTexture
import com.example.data.TerrainStageProfiler
import com.example.data.TerrainFrustumCuller
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** One float height texture and one reusable 65x65 topology; no per-cell CPU vertex/normal meshes. */
internal class HeightfieldGlRenderer {
    private var program = 0
    private var texture = 0
    private var vertices = 0
    private var indices = 0
    private var source: HeightmapTexture? = null
    private val uniforms = HashMap<String, Int>()
    private var minZ = 0f
    private var maxZ = 1f
    fun contextLost() { program = 0; texture = 0; vertices = 0; indices = 0; source = null; uniforms.clear() }
    fun release() {
        if (texture != 0) GL.glDeleteTextures(1,intArrayOf(texture),0)
        if (vertices != 0) GL.glDeleteBuffers(2,intArrayOf(vertices,indices),0)
        if (program != 0) GL.glDeleteProgram(program)
        contextLost()
    }
    fun draw(height: HeightmapTexture, mvp: FloatArray, viewWidth: Int, viewHeight: Int, zoom: Float) {
        if (program == 0) setup()
        if (source !== height) TerrainStageProfiler.stage("height_texture_upload", "${height.width}x${height.height}") {
            if (texture == 0) { val id = IntArray(1); GL.glGenTextures(1,id,0); texture = id[0] }
            GL.glBindTexture(GL.GL_TEXTURE_2D,texture)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_NEAREST)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_NEAREST)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
            val bytes = ByteBuffer.allocateDirect(height.floats.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            bytes.put(height.floats).position(0)
            GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_R32F,height.width,height.height,0,GL.GL_RED,GL.GL_FLOAT,bytes)
            check(GL.glGetError()==GL.GL_NO_ERROR) { "Height texture upload failed" }
            minZ = Float.POSITIVE_INFINITY; maxZ = Float.NEGATIVE_INFINITY
            for (z in height.floats) if (z.isFinite()) { minZ = min(minZ,z); maxZ = max(maxZ,z) }
            if (!minZ.isFinite()) { minZ=0f; maxZ=0f }
            source = height
        }
        GL.glUseProgram(program)
        GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D,texture)
        GL.glUniform1i(uniform("uHeight"),0)
        GL.glUniformMatrix4fv(uniform("uMvp"),1,false,mvp,0)
        GL.glUniform2i(uniform("uSize"),height.width,height.height)
        GL.glUniform2f(uniform("uCell"),height.cellSizeXMeters,height.cellSizeYMeters)
        val span = max((height.width-1)*height.cellSizeXMeters,(height.height-1)*height.cellSizeYMeters).coerceAtLeast(.001f)
        GL.glUniform1f(uniform("uSpan"),span); GL.glUniform1f(uniform("uMinZ"),minZ)
        val ratio=max(height.width,height.height)/(max(viewWidth,viewHeight).coerceAtLeast(1)*zoom.coerceAtLeast(.1f))
        val step=1 shl ceil(log2(ratio.coerceAtLeast(1f))).toInt().coerceIn(0,4)
        GL.glUniform1i(uniform("uStep"),step)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER,vertices)
        val attribute=GL.glGetAttribLocation(program,"aGrid")
        GL.glEnableVertexAttribArray(attribute); GL.glVertexAttribPointer(attribute,2,GL.GL_FLOAT,false,8,0)
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER,indices)
        val planes=TerrainFrustumCuller.extractPlanes(mvp)
        for (y in 0 until height.height-1 step 64*step) for (x in 0 until height.width-1 step 64*step) {
            val x1=min(x+64*step,height.width-1); val y1=min(y+64*step,height.height-1)
            val box=TerrainFrustumCuller.Aabb(
                x*height.cellSizeXMeters/span*2f-(height.width-1)*height.cellSizeXMeters/span,
                (height.height-1)*height.cellSizeYMeters/span-y1*height.cellSizeYMeters/span*2f,
                0f,
                x1*height.cellSizeXMeters/span*2f-(height.width-1)*height.cellSizeXMeters/span,
                (height.height-1)*height.cellSizeYMeters/span-y*height.cellSizeYMeters/span*2f,
                (maxZ-minZ)/span*2f)
            if (!TerrainFrustumCuller.isVisible(box,planes)) continue
            GL.glUniform2i(uniform("uOrigin"),x,y)
            GL.glDrawElements(GL.GL_TRIANGLES,64*64*6,GL.GL_UNSIGNED_SHORT,0)
        }
        GL.glDisableVertexAttribArray(attribute)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER,0); GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER,0)
    }
    private fun uniform(name: String)=uniforms.getOrPut(name) { GL.glGetUniformLocation(program,name) }
    private fun setup() {
        fun compile(type: Int, code: String): Int {
            val id=GL.glCreateShader(type); GL.glShaderSource(id,code); GL.glCompileShader(id)
            val status=IntArray(1); GL.glGetShaderiv(id,GL.GL_COMPILE_STATUS,status,0)
            check(status[0]==GL.GL_TRUE) { GL.glGetShaderInfoLog(id) }; return id
        }
        val vertex=compile(GL.GL_VERTEX_SHADER,VERTEX); val fragment=compile(GL.GL_FRAGMENT_SHADER,FRAGMENT)
        program=GL.glCreateProgram(); GL.glAttachShader(program,vertex); GL.glAttachShader(program,fragment); GL.glLinkProgram(program)
        val status=IntArray(1); GL.glGetProgramiv(program,GL.GL_LINK_STATUS,status,0)
        check(status[0]==GL.GL_TRUE) { GL.glGetProgramInfoLog(program) }
        GL.glDeleteShader(vertex); GL.glDeleteShader(fragment)
        val ids=IntArray(2); GL.glGenBuffers(2,ids,0); vertices=ids[0]; indices=ids[1]
        val v=ByteBuffer.allocateDirect(65*65*8).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (y in 0..64) for (x in 0..64) v.put(x.toFloat()).put(y.toFloat())
        v.position(0); GL.glBindBuffer(GL.GL_ARRAY_BUFFER,vertices); GL.glBufferData(GL.GL_ARRAY_BUFFER,65*65*8,v,GL.GL_STATIC_DRAW)
        val ii=ByteBuffer.allocateDirect(64*64*12).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (y in 0 until 64) for (x in 0 until 64) {
            val a=y*65+x; val b=a+1; val c=a+65; val d=c+1
            ii.put(a.toShort()).put(c.toShort()).put(b.toShort()).put(b.toShort()).put(c.toShort()).put(d.toShort())
        }
        ii.position(0); GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER,indices); GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER,64*64*12,ii,GL.GL_STATIC_DRAW)
    }
    companion object {
        internal const val VERTEX="""#version 300 es
            precision highp float;
            in vec2 aGrid;
            uniform highp sampler2D uHeight;
            uniform mat4 uMvp; uniform ivec2 uSize; uniform ivec2 uOrigin; uniform int uStep;
            uniform vec2 uCell; uniform float uSpan; uniform float uMinZ;
            out vec2 vGrid; out float vLight;
            float zAt(ivec2 p,float center) {
                float z=texelFetch(uHeight,clamp(p,ivec2(0),uSize-1),0).r;
                return isnan(z)||isinf(z)?center:z;
            }
            void main() {
                ivec2 p=min(uOrigin+ivec2(aGrid)*uStep,uSize-1);
                float z=texelFetch(uHeight,p,0).r;
                if(isnan(z)||isinf(z)) z=uMinZ;
                float gx=(zAt(p+ivec2(1,0),z)-zAt(p-ivec2(1,0),z))/(2.0*uCell.x);
                float gy=(zAt(p+ivec2(0,1),z)-zAt(p-ivec2(0,1),z))/(2.0*uCell.y);
                vec3 n=normalize(vec3(-gx,gy,1.0));
                vLight=0.28+0.72*max(dot(n,normalize(vec3(-0.45,0.65,0.8))),0.0);
                vec2 extent=vec2(uSize-1)*uCell;
                vec2 xy=(vec2(p)*uCell*2.0-extent)/uSpan; xy.y=-xy.y;
                gl_Position=uMvp*vec4(xy,(z-uMinZ)*2.0/uSpan,1.0);
                vGrid=vec2(p);
            }
        """
        internal const val FRAGMENT="""#version 300 es
            precision highp float;
            uniform highp sampler2D uHeight; uniform ivec2 uSize; uniform int uStep;
            in vec2 vGrid; in float vLight; out vec4 color;
            bool bad(ivec2 p) { float z=texelFetch(uHeight,min(p,uSize-1),0).r; return isnan(z)||isinf(z); }
            void main() {
                ivec2 p=ivec2(floor(vGrid/float(uStep)))*uStep;
                if(bad(p)||bad(p+ivec2(uStep,0))||bad(p+ivec2(0,uStep))||bad(p+ivec2(uStep))) discard;
                color=vec4(vec3(0.72*vLight),1.0);
            }
        """
    }
}
