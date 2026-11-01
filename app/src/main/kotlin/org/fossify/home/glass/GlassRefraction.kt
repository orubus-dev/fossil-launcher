package org.fossify.home.glass

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Fossil liquid glass engine — software refraction tier.
 *
 * Renders the actual liquid-glass look per pixel: the backdrop is sampled
 * through a rounded-rect signed-distance field so content bends around the
 * panel edge like light through a lens, with chromatic dispersion (R/G/B
 * sampled at slightly different offsets), a fresnel rim and a top-left
 * biased specular band. Works on every API level back to minSdk 26; the GPU
 * tier ([GlassShader]) produces the identical look live on API 33+.
 *
 * Everything runs on an IntArray, no hardware dependencies, deterministic
 * output on every device. Panels render at a capped resolution and upscale —
 * under blur and refraction the difference is invisible.
 */
object GlassRefraction {

    /** Pixel budget: panels larger than this render at a reduced scale. */
    private const val MAX_RENDER_PIXELS = 320_000

    data class Params(
        /** Max sample displacement at the panel edge, in px. */
        val refraction: Float = 14f,
        /** Per-channel dispersion fraction of the refraction offset (0..0.5). */
        val dispersion: Float = 0.35f,
        /** Inward decay width of the lens ring, in px. */
        val edgeWidth: Float = 26f,
        /** Specular highlight strength (0..1). */
        val specular: Float = 0.85f,
        /** Translucent fill blended over the refracted content. */
        val tint: Int = Color.argb(70, 255, 255, 255),
        /** Global panel alpha multiplier (0..1). */
        val alpha: Float = 1f,
        /** Backdrop bleed around the visible panel, in px. The mask hides it. */
        val panelInset: Float = 0f,
        /** Gaussian-ish blur applied to the backdrop before refraction, in px. */
        val blur: Float = 10f,
    )

    /**
     * Render [backdrop] (already the panel's screen region) through the
     * liquid glass lens. Returns a bitmap whose rounded corners are
     * transparent; null when [backdrop] is empty.
     */
    fun render(backdrop: Bitmap, cornerRadius: Float, params: Params): Bitmap? {
        if (backdrop.isRecycled || backdrop.width <= 2 || backdrop.height <= 2) {
            return null
        }

        val radius = cornerRadius.coerceAtLeast(1f)
        val scale = min(
            1f,
            sqrt(MAX_RENDER_PIXELS.toFloat() / (backdrop.width * backdrop.height))
        )

        val srcWidth = max((backdrop.width * scale).toInt(), 3)
        val srcHeight = max((backdrop.height * scale).toInt(), 3)
        val source = if (scale < 1f) {
            Bitmap.createScaledBitmap(backdrop, srcWidth, srcHeight, true)
        } else {
            backdrop
        }

        try {
            val pixels = IntArray(srcWidth * srcHeight)
            source.getPixels(pixels, 0, srcWidth, 0, 0, srcWidth, srcHeight)
            if (params.blur > 0.5f) {
                val blurRadius = (params.blur * min(srcWidth, srcHeight) / 360f).toInt().coerceIn(1, 24)
                var blurred = boxBlur(pixels, srcWidth, srcHeight, blurRadius)
                blurred = boxBlur(blurred, srcWidth, srcHeight, blurRadius)
                System.arraycopy(blurred, 0, pixels, 0, pixels.size)
            }
            val result = renderPixels(pixels, srcWidth, srcHeight, radius * scale, params)
            val out = Bitmap.createBitmap(result, srcWidth, srcHeight, Bitmap.Config.ARGB_8888)
            return if (scale < 1f) {
                Bitmap.createScaledBitmap(out, backdrop.width, backdrop.height, true).also {
                    out.recycle()
                }
            } else {
                out
            }
        } catch (_: Throwable) {
            return null
        } finally {
            if (source !== backdrop) {
                source.recycle()
            }
        }
    }

    private fun renderPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        cornerRadius: Float,
        params: Params,
    ): IntArray {
        val halfW = width / 2f
        val halfH = height / 2f
        val inset = params.panelInset.coerceAtLeast(0f)
        val innerW = (halfW - inset - cornerRadius).coerceAtLeast(0.5f)
        val innerH = (halfH - inset - cornerRadius).coerceAtLeast(0.5f)
        val edgeWidth = params.edgeWidth.coerceAtLeast(2f)
        val refraction = params.refraction * min(width, height) / 360f
        val dispersion = params.dispersion.coerceIn(0f, 0.8f)
        val tintA = (params.tint ushr 24) / 255f
        val tintR = ((params.tint ushr 16) and 0xFF) / 255f
        val tintG = ((params.tint ushr 8) and 0xFF) / 255f
        val tintB = (params.tint and 0xFF) / 255f
        val specular = params.specular.coerceIn(0f, 1f)
        val globalAlpha = params.alpha.coerceIn(0f, 1f)

        // top-left light direction, mirrored highlight on bottom-right
        val lightLen = sqrt(0.6f * 0.6f + 0.8f * 0.8f)
        val lightX = -0.6f / lightLen
        val lightY = -0.8f / lightLen

        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            val py = y - halfH + 0.5f
            val row = y * width
            for (x in 0 until width) {
                val px = x - halfW + 0.5f

                // signed distance to the rounded rect (negative inside)
                val qx = abs(px) - innerW
                val qy = abs(py) - innerH
                val outside = sqrt(max(qx, 0f).pow(2) + max(qy, 0f).pow(2))
                val dist = outside + min(max(qx, qy), 0f) - cornerRadius

                // SDF normal (points outward)
                val dvx = px - clampSym(px, innerW)
                val dvy = py - clampSym(py, innerH)
                val dvLen = sqrt(dvx * dvx + dvy * dvy)
                val nx: Float
                val ny: Float
                if (dvLen > 0.001f) {
                    nx = dvx / dvLen
                    ny = dvy / dvLen
                } else {
                    nx = 0f
                    ny = 0f
                }

                // lens ring: strongest at the edge, decaying inward
                val insideDepth = max(0f, -dist)
                val rim = exp(-insideDepth / edgeWidth)

                // refraction: sample the backdrop displaced along the normal
                val baseOffset = nx * refraction * rim
                val baseOffsetY = ny * refraction * rim
                val rC = sampleBilinear(pixels, width, height, x + baseOffset * (1f + dispersion), y + baseOffsetY * (1f + dispersion)) ushr 16 and 0xFF
                val gC = sampleBilinear(pixels, width, height, x + baseOffset, y + baseOffsetY) ushr 8 and 0xFF
                val bC = sampleBilinear(pixels, width, height, x + baseOffset * (1f - dispersion), y + baseOffsetY * (1f - dispersion)) and 0xFF
                val aC = sampleBilinear(pixels, width, height, x + baseOffset, y + baseOffsetY) ushr 24 and 0xFF

                var fr = rC / 255f
                var fg = gC / 255f
                var fb = bC / 255f

                // translucent fill
                fr = fr + (tintR - fr) * tintA
                fg = fg + (tintG - fg) * tintA
                fb = fb + (tintB - fb) * tintA

                // specular band hugging the edge, lit from top-left
                if (specular > 0f) {
                    val band = exp(-abs(dist) / (edgeWidth * 0.55f))
                    val lightDot = max(0f, nx * lightX + ny * lightY)
                    val spec = lightDot.pow(3f) * band * specular
                    val counterDot = max(0f, nx * -lightX + ny * -lightY)
                    val shadow = counterDot.pow(2f) * band * specular * 0.35f
                    fr = (fr + spec * 0.9f) * (1f - shadow * 0.5f)
                    fg = (fg + spec * 0.92f) * (1f - shadow * 0.5f)
                    fb = (fb + spec) * (1f - shadow * 0.5f)
                }

                // alpha mask: rounded corners transparent, AA at the boundary
                val mask = 1f - smoothStep(-1.2f, 1.2f, dist)
                val alpha = (mask * globalAlpha * 255f).toInt().coerceIn(0, 255)

                out[row + x] = compose(
                    alpha,
                    (fr * 255f).toInt(),
                    (fg * 255f).toInt(),
                    (fb * 255f).toInt(),
                    aC
                )
            }
        }
        return out
    }

    /** Bilinear sample of an ARGB pixel buffer in source pixel coordinates. */
    private fun sampleBilinear(
        pixels: IntArray,
        width: Int,
        height: Int,
        fx: Float,
        fy: Float,
    ): Int {
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = x0 + 1
        val y1 = y0 + 1
        val dx = fx - x0
        val dy = fy - y0

        val cx0 = x0.coerceIn(0, width - 1)
        val cx1 = x1.coerceIn(0, width - 1)
        val cy0 = y0.coerceIn(0, height - 1)
        val cy1 = y1.coerceIn(0, height - 1)

        val p00 = pixels[cy0 * width + cx0]
        val p10 = pixels[cy0 * width + cx1]
        val p01 = pixels[cy1 * width + cx0]
        val p11 = pixels[cy1 * width + cx1]

        var a = (p00 ushr 24) + ((p10 ushr 24) - (p00 ushr 24)) * dx
        var r = (p00 ushr 16 and 0xFF) + ((p10 ushr 16 and 0xFF) - (p00 ushr 16 and 0xFF)) * dx
        var g = (p00 ushr 8 and 0xFF) + ((p10 ushr 8 and 0xFF) - (p00 ushr 8 and 0xFF)) * dx
        var b = (p00 and 0xFF) + ((p10 and 0xFF) - (p00 and 0xFF)) * dx

        val a1 = (p01 ushr 24) + ((p11 ushr 24) - (p01 ushr 24)) * dx
        val r1 = (p01 ushr 16 and 0xFF) + ((p11 ushr 16 and 0xFF) - (p01 ushr 16 and 0xFF)) * dx
        val g1 = (p01 ushr 8 and 0xFF) + ((p11 ushr 8 and 0xFF) - (p01 ushr 8 and 0xFF)) * dx
        val b1 = (p01 and 0xFF) + ((p11 and 0xFF) - (p01 and 0xFF)) * dx

        a += (a1 - a) * dy
        r += (r1 - r) * dy
        g += (g1 - g) * dy
        b += (b1 - b) * dy

        return compose(a.toInt(), r.toInt(), g.toInt(), b.toInt(), 255)
    }

    private fun clampSym(value: Float, limit: Float): Float {
        return value.coerceIn(-limit, limit)
    }

    /** Separable box blur on an ARGB IntArray, one horizontal + one vertical pass. */
    private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (width <= 0 || height <= 0 || radius <= 0) {
            return pixels.copyOf()
        }
        val window = radius * 2 + 1
        val temp = IntArray(pixels.size)
        val out = IntArray(pixels.size)

        for (y in 0 until height) {
            val row = y * width
            var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -radius..radius) {
                val pixel = pixels[row + i.coerceIn(0, width - 1)]
                aSum += (pixel ushr 24) and 0xFF
                rSum += (pixel ushr 16) and 0xFF
                gSum += (pixel ushr 8) and 0xFF
                bSum += pixel and 0xFF
            }
            for (x in 0 until width) {
                temp[row + x] = compose(
                    aSum / window, rSum / window, gSum / window, bSum / window, 255
                )
                val outX = (x - radius).coerceIn(0, width - 1)
                val inX = (x + radius + 1).coerceIn(0, width - 1)
                val outPixel = pixels[row + outX]
                val inPixel = pixels[row + inX]
                aSum += ((inPixel ushr 24) and 0xFF) - ((outPixel ushr 24) and 0xFF)
                rSum += ((inPixel ushr 16) and 0xFF) - ((outPixel ushr 16) and 0xFF)
                gSum += ((inPixel ushr 8) and 0xFF) - ((outPixel ushr 8) and 0xFF)
                bSum += (inPixel and 0xFF) - (outPixel and 0xFF)
            }
        }

        for (x in 0 until width) {
            var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -radius..radius) {
                val pixel = temp[i.coerceIn(0, height - 1) * width + x]
                aSum += (pixel ushr 24) and 0xFF
                rSum += (pixel ushr 16) and 0xFF
                gSum += (pixel ushr 8) and 0xFF
                bSum += pixel and 0xFF
            }
            for (y in 0 until height) {
                out[y * width + x] = compose(
                    aSum / window, rSum / window, gSum / window, bSum / window, 255
                )
                val outY = (y - radius).coerceIn(0, height - 1)
                val inY = (y + radius + 1).coerceIn(0, height - 1)
                val outPixel = temp[outY * width + x]
                val inPixel = temp[inY * width + x]
                aSum += ((inPixel ushr 24) and 0xFF) - ((outPixel ushr 24) and 0xFF)
                rSum += ((inPixel ushr 16) and 0xFF) - ((outPixel ushr 16) and 0xFF)
                gSum += ((inPixel ushr 8) and 0xFF) - ((outPixel ushr 8) and 0xFF)
                bSum += (inPixel and 0xFF) - (outPixel and 0xFF)
            }
        }
        return out
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun compose(a: Int, r: Int, g: Int, b: Int, fallbackA: Int): Int {
        val alpha = a.coerceIn(0, 255)
        return (alpha shl 24) or
                (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or
                b.coerceIn(0, 255)
    }
}
