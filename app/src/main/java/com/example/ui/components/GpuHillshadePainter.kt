package com.example.ui.components

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.annotation.TargetApi
import com.example.data.GpuTerrainFrame
import kotlin.math.*

/** Hardware Canvas fragment shading: raw height bytes bypass color conversion. */
@TargetApi(33)
internal class GpuHillshadePainter(private val data: com.example.data.GpuHeightData) {
    private val shader = RuntimeShader(SOURCE)
    private val paint = Paint().apply { this.shader = this@GpuHillshadePainter.shader }
    init {
        shader.setInputBuffer("uHeight", BitmapShader(data.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setFloatUniform("uSize", data.bitmap.width.toFloat(), data.bitmap.height.toFloat())
        shader.setFloatUniform("uRange", data.base, data.range)
        shader.setFloatUniform("uCell", data.cellX, data.cellY)
    }
    fun draw(canvas: Canvas, frame: GpuTerrainFrame, left: Float, top: Float, width: Float, height: Float) {
        require(frame.data === data)
        for (i in 0..3) {
            val az = Math.toRadians((frame.azimuth + i * 90).toDouble())
            val alt = Math.toRadians(frame.altitude.coerceIn(1f,89f).toDouble())
            shader.setFloatUniform("uLight$i", (sin(az)*cos(alt)).toFloat(), (-cos(az)*cos(alt)).toFloat(), sin(alt).toFloat())
        }
        shader.setFloatUniform("uContrast", if(frame.mode in 9..11) frame.contrast.coerceIn(.4f,2.5f) else frame.contrast.coerceIn(.5f,4f))
        shader.setFloatUniform("uZ", frame.zScale.coerceIn(.25f,8f))
        shader.setIntUniform("uMode", frame.mode); shader.setIntUniform("uPalette", frame.palette)
        val saved = canvas.save()
        try {
            canvas.translate(left,top)
            canvas.scale(width/data.bitmap.width,height/data.bitmap.height)
            canvas.drawRect(0f,0f,data.bitmap.width.toFloat(),data.bitmap.height.toFloat(),paint)
        } finally { canvas.restoreToCount(saved) }
    }
    companion object {
        internal const val SOURCE = """
            uniform shader uHeight;
            uniform float2 uSize;
            uniform float2 uRange;
            uniform float2 uCell;
            uniform float3 uLight0; uniform float3 uLight1; uniform float3 uLight2; uniform float3 uLight3;
            uniform float uContrast; uniform float uZ;
            uniform int uMode; uniform int uPalette;
            float rawHeight(float4 data) {
                float3 bytes = floor(data.rgb * 255.0 + 0.5);
                return uRange.x + dot(bytes, float3(65536.0,256.0,1.0)) / 16777215.0 * uRange.y;
            }
            float zAt(float2 p, float center) {
                float4 d = uHeight.eval(clamp(p,float2(0.5),uSize-float2(0.5)));
                return d.a < 0.5 ? center : rawHeight(d);
            }
            float3 blend8(float3 a,float3 b,float t) { return floor(mix(a,b,clamp(t,0.0,1.0))); }
            float3 palette(float p) {
                if(uPalette==0) return float3(floor(150.0+p*60.0));
                if(uPalette==1) {
                    if(p<0.3) return blend8(float3(15,20,45),float3(80,15,20),p/0.3);
                    if(p<0.7) return blend8(float3(80,15,20),float3(200,90,40),(p-0.3)/0.4);
                    return blend8(float3(200,90,40),float3(250,180,70),(p-0.7)/0.3);
                }
                if(p<0.2) return blend8(float3(34,112,63),float3(46,139,87),p/0.2);
                if(p<0.6) return blend8(float3(46,139,87),float3(196,183,101),(p-0.2)/0.4);
                if(p<0.85) return blend8(float3(196,183,101),float3(130,80,40),(p-0.6)/0.25);
                return blend8(float3(130,80,40),float3(210,210,215),(p-0.85)/0.15);
            }
            half4 main(float2 coord) {
                float2 p=floor(coord)+float2(0.5);
                float4 d=uHeight.eval(p);
                if(d.a<0.5) return half4(0.0);
                float c=rawHeight(d);
                if(uMode==11) return half4(float3(floor(clamp(1.0-(1.0-c)*uContrast,0.0,1.0)*255.0)/255.0),1);
                if(uMode==9 || uMode==10) {
                    float gray=floor(clamp(0.5+(c-1.570796327)/3.141592654*uContrast,0.0,1.0)*255.0)/255.0;
                    return half4(float3(gray),1);
                }
                float z00=zAt(p+float2(-1,-1),c), z01=zAt(p+float2(0,-1),c), z02=zAt(p+float2(1,-1),c);
                float z10=zAt(p+float2(-1,0),c), z12=zAt(p+float2(1,0),c);
                float z20=zAt(p+float2(-1,1),c), z21=zAt(p+float2(0,1),c), z22=zAt(p+float2(1,1),c);
                float gx=((z02+2.0*z12+z22)-(z00+2.0*z10+z20))/(8.0*uCell.x)*uZ;
                float gy=((z20+2.0*z21+z22)-(z00+2.0*z01+z02))/(8.0*uCell.y)*uZ;
                float3 n=normalize(float3(-gx,-gy,1));
                float primary=clamp(dot(n,uLight0),0.0,1.0);
                float multi=primary*0.45+clamp(dot(n,uLight1),0.0,1.0)*0.2+clamp(dot(n,uLight2),0.0,1.0)*0.15+clamp(dot(n,uLight3),0.0,1.0)*0.2;
                float3 base=palette(clamp((c-uRange.x)/uRange.y,0.0,1.0));
                if(uMode==7) return half4(base/255.0,1);
                float slope=atan(length(float2(gx,gy)));
                if(uMode==2) return half4(blend8(base,float3(255,70,0),clamp(slope/0.610865238,0.0,1.0))/255.0,1);
                if(uMode==6) {
                    if(slope<0.017453293) return half4(float3(148.0/255.0),1);
                    float degrees=mod(atan(-gx,gy)*57.295779513+360.0,360.0);
                    float3 hsv=clamp(abs(mod(degrees/60.0+float3(0,4,2),6.0)-3.0)-1.0,0.0,1.0);
                    float3 rgb=floor(mix(float3(1),hsv,0.82)*0.95*255.0+0.5);
                    float illum=0.18+clamp(((0.35+multi*0.65)-0.5)*0.72+0.5,0.0,1.0)*0.82;
                    return half4(floor(rgb*illum)/255.0,1);
                }
                float shade=uMode==0 ? primary : multi;
                float illumination=0.18+clamp((shade-0.5)*uContrast+0.5,0.0,1.0)*0.82;
                return half4(floor(base*illumination)/255.0,1);
            }
        """
    }
}
