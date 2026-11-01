package org.fossify.home.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Fossil liquid glass engine — GPU tier (API 33+).
 *
 * An AGSL shader that reproduces exactly the same optics as the software
 * renderer ([GlassRefraction]) but live on the GPU: rounded-rect SDF
 * displacement refraction, chromatic dispersion, fresnel rim, top-left
 * specular band and a translucent tint, applied to the panel's backdrop
 * content through RenderEffect.createRuntimeShaderEffect.
 *
 * Because it is a runtime shader, every parameter is a uniform — the panel
 * can animate the lens (breathing edge, wobble on appear) without
 * regenerating bitmaps. Shader compilation can fail on exotic drivers, so
 * [create] returns null and callers fall back to the software tier.
 */
object GlassShader {

    private const val SRC = "" +
            "uniform shader uContent;\n" +
            "uniform float2 uSize;\n" +              // backdrop view size, px
            "uniform float uPanelInset;\n" +         // bleed between backdrop edge and panel edge, px
            "uniform float uRadius;\n" +             // corner radius, px
            "uniform float uRefraction;\n" +         // max displacement, px
            "uniform float uDispersion;\n" +         // per-channel dispersion fraction
            "uniform float uEdgeWidth;\n" +          // lens ring decay, px
            "uniform float uSpecular;\n" +           // specular strength 0..1
            "uniform float4 uTint;\n" +              // rgba fill
            "uniform float uTime;\n" +               // seconds, for the liquid breathe
            "\n" +
            "float sdRoundRect(float2 p, float2 halfSize, float r) {\n" +
            "    float2 q = abs(p) - halfSize + r;\n" +
            "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n" +
            "}\n" +
            "\n" +
            "float4 main(float2 fragCoord) {\n" +
            "    float2 center = uSize * 0.5;\n" +
            "    float2 p = fragCoord - center;\n" +
            "    float2 halfPanel = center - float2(uPanelInset);\n" +
            "\n" +
            "    // subtle liquid breathe: the lens swells very slightly over time\n" +
            "    float breathe = 1.0 + 0.06 * sin(uTime * 1.7);\n" +
            "    float refraction = uRefraction * breathe;\n" +
            "\n" +
            "    float dist = sdRoundRect(p, halfPanel, uRadius);\n" +
            "\n" +
            "    // analytic SDF normal (points outward)\n" +
            "    float2 inner = halfPanel - float2(uRadius);\n" +
            "    float2 dq = p - clamp(p, -inner, inner);\n" +
            "    float dqLen = length(dq);\n" +
            "    float2 n = float2(0.0);\n" +
            "    if (dqLen > 0.001) { n = dq / dqLen; }\n" +
            "\n" +
            "    // lens ring: strongest at the edge, decaying inward\n" +
            "    float rim = exp(-max(0.0, -dist) / uEdgeWidth);\n" +
            "    float2 offset = n * refraction * rim;\n" +
            "\n" +
            "    // chromatic dispersion: each channel refracts a bit differently\n" +
            "    float2 oR = offset * (1.0 + uDispersion);\n" +
            "    float2 oG = offset;\n" +
            "    float2 oB = offset * (1.0 - uDispersion);\n" +
            "    float rC = uContent.eval(fragCoord + oR).r;\n" +
            "    float gC = uContent.eval(fragCoord + oG).g;\n" +
            "    float bC = uContent.eval(fragCoord + oB).b;\n" +
            "    float aC = uContent.eval(fragCoord + oG).a;\n" +
            "\n" +
            "    // translucent fill\n" +
            "    float3 color = float3(rC, gC, bC);\n" +
            "    color = mix(color, uTint.rgb, uTint.a);\n" +
            "\n" +
            "    // specular band hugging the edge, lit from top-left\n" +
            "    float2 lightDir = normalize(float2(-0.6, -0.8));\n" +
            "    float band = exp(-abs(dist) / (uEdgeWidth * 0.55));\n" +
            "    float lightDot = max(0.0, dot(n, lightDir));\n" +
            "    float spec = pow(lightDot, 3.0) * band * uSpecular;\n" +
            "    float counterDot = max(0.0, dot(n, -lightDir));\n" +
            "    float shadow = pow(counterDot, 2.0) * band * uSpecular * 0.35;\n" +
            "    color += float3(spec * 0.9, spec * 0.92, spec);\n" +
            "    color *= (1.0 - shadow * 0.5);\n" +
            "\n" +
            "    // fresnel brightening just inside the edge\n" +
            "    color += band * 0.05;\n" +
            "\n" +
            "    // alpha mask: panel shape with AA boundary\n" +
            "    float mask = 1.0 - smoothstep(-1.2, 1.2, dist);\n" +
            "    return float4(color * mask, mask * aC);\n" +
            "}\n"

    /**
     * Build the RenderEffect for a panel whose backdrop view is
     * [backdropWidth] × [backdropHeight] px. The shader expects the backdrop
     * to extend [panelInset] px beyond the visible panel on every side so the
     * lens can sample content from outside the edge. Returns null when the
     * driver cannot compile the shader — callers must fall back.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun create(
        backdropWidth: Int,
        backdropHeight: Int,
        panelInset: Float,
        cornerRadius: Float,
        params: GlassRefraction.Params,
        timeSeconds: Float = 0f,
    ): RenderEffect? {
        return try {
            val shader = RuntimeShader(SRC)
            shader.setFloatUniform("uSize", backdropWidth.toFloat(), backdropHeight.toFloat())
            shader.setFloatUniform("uPanelInset", panelInset)
            shader.setFloatUniform("uRadius", cornerRadius)
            shader.setFloatUniform("uRefraction", params.refraction)
            shader.setFloatUniform("uDispersion", params.dispersion.coerceIn(0f, 0.8f))
            shader.setFloatUniform("uEdgeWidth", params.edgeWidth.coerceAtLeast(2f))
            shader.setFloatUniform("uSpecular", params.specular.coerceIn(0f, 1f))
            shader.setFloatUniform(
                "uTint",
                ((params.tint ushr 16) and 0xFF) / 255f,
                ((params.tint ushr 8) and 0xFF) / 255f,
                (params.tint and 0xFF) / 255f,
                ((params.tint ushr 24) and 0xFF) / 255f
            )
            shader.setFloatUniform("uTime", timeSeconds)
            RenderEffect.createRuntimeShaderEffect(shader, "uContent")
        } catch (_: Throwable) {
            null
        }
    }
}
