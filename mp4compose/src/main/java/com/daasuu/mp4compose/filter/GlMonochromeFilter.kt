package com.daasuu.mp4compose.filter

import android.opengl.GLES20

/**
 * Created by sudamasayuki on 2018/01/06.
 */

class GlMonochromeFilter : GlFilter(GlFilter.DEFAULT_VERTEX_SHADER, FRAGMENT_SHADER) {
    var intensity = 1.0f

    public override fun onDraw(presentationTime: Long) {
        GLES20.glUniform1f(getHandle("intensity"), intensity)
    }

    companion object {
        private val FRAGMENT_SHADER = "precision lowp float;" +

                "varying highp vec2 vTextureCoord;" +
                "uniform lowp sampler2D sTexture;" +
                "uniform float intensity;" +

                "const mediump vec3 luminanceWeighting = vec3(0.2125, 0.7154, 0.0721);" +

                "void main() {" +

                "lowp vec4 textureColor = texture2D(sTexture, vTextureCoord);" +
                "float luminance = dot(textureColor.rgb, luminanceWeighting);" +

                "lowp vec4 desat = vec4(vec3(luminance), 1.0);" +

                "lowp vec4 outputColor = vec4(" +
                "(desat.r < 0.5 ? (2.0 * desat.r * 0.6) : (1.0 - 2.0 * (1.0 - desat.r) * (1.0 - 0.6)))," +
                "(desat.g < 0.5 ? (2.0 * desat.g * 0.45) : (1.0 - 2.0 * (1.0 - desat.g) * (1.0 - 0.45)))," +
                "(desat.b < 0.5 ? (2.0 * desat.b * 0.3) : (1.0 - 2.0 * (1.0 - desat.b) * (1.0 - 0.3)))," +
                "1.0" +
                ");" +

                "gl_FragColor = vec4(mix(textureColor.rgb, outputColor.rgb, intensity), textureColor.a);" +
                "}"
    }
}
