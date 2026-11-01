package org.fossify.home.glass

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.max

/**
 * Fossil liquid glass engine — the reusable glass panel view.
 *
 * A panel is a FrameLayout with two layers:
 *
 *  - a glass layer holding the backdrop, oversized by [GlassStyle.panelInset]
 *    on every side so the edge lens can sample content from outside the
 *    visible panel. On API 33+ the backdrop is blurred and refracted live on
 *    the GPU ([GlassShader]); everywhere else the engine pre-renders the
 *    identical optics in software ([GlassRefraction]).
 *  - a content layer holding the actual UI (menu rows, previews, inputs),
 *    which is deliberately NOT part of the refraction — labels stay crisp
 *    while the world behind them bends.
 *
 * Content is added through [contentLayout]; the panel handles backdrop
 * composition (via [GlassBackdrop]), tier selection, rounded clipping and
 * the appear/disappear motion.
 */
class LiquidGlassPanel(
    context: android.content.Context,
    var style: GlassStyle,
) : FrameLayout(context) {

    /** Visual style of a glass surface. */
    data class GlassStyle(
        val cornerRadiusPx: Float,
        val params: GlassRefraction.Params,
        /** Gaussian blur radius applied to the backdrop before refraction. */
        val blurRadius: Float = 26f,
        /** Backdrop bleed beyond the visible panel, feeding the edge lens. */
        val panelInset: Float = 20f,
        val fallbackFill: Int = Color.argb(214, 24, 24, 27),
        val stroke: Int = Color.argb(34, 255, 255, 255),
    )

    /** Oversized layer whose subtree is processed by the glass optics. */
    private val glassLayer = FrameLayout(context)

    /** Draws the backdrop bitmap (raw on the GPU tier, refracted otherwise). */
    private val backdropView = BackdropView(context, style)

    /** Hosts the crisp UI content. */
    val contentLayout = FrameLayout(context)

    private var effectBuilt = false
    private var lastWidth = -1
    private var lastHeight = -1

    /** Set while a software refract job is in flight, to avoid pile-ups. */
    @Volatile
    private var softwareJobPending = false

    init {
        clipChildren = false
        clipToPadding = false

        glassLayer.clipChildren = false
        addView(
            glassLayer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        contentLayout.clipToOutline = true
        contentLayout.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    0, 0, view.width, view.height, style.cornerRadiusPx
                )
            }
        }
        addView(
            contentLayout,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        // 1px specular stroke on top of everything, inside the panel shape
        val strokeView = View(context).apply {
            isClickable = false
            isFocusable = false
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = style.cornerRadiusPx
                setStroke(max(1, (resources.displayMetrics.density * 1).toInt()), style.stroke)
            }
        }
        addView(
            strokeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        layoutBackdrop()
    }

    /**
     * Ask [GlassBackdrop] for the panel's backdrop and wire the optics.
     * [dimAmount] should match the visual dim behind the panel so refraction
     * lands on what the eye actually sees.
     */
    fun loadBackdrop(activity: Activity, screenRect: RectF, dimAmount: Float) {
        val inset = style.panelInset
        val bleedRect = RectF(
            screenRect.left - inset,
            screenRect.top - inset,
            screenRect.right + inset,
            screenRect.bottom + inset
        )
        val outWidth = bleedRect.width().toInt().coerceAtLeast(1)
        val outHeight = bleedRect.height().toInt().coerceAtLeast(1)

        GlassBackdrop.request(activity, bleedRect, outWidth, outHeight, dimAmount) { bitmap ->
            if (bitmap != null) {
                applyBackdrop(bitmap)
            }
        }
    }

    /**
     * Feed a raw backdrop (covering the panel plus bleed) directly — used by
     * canvas-drawn surfaces. The panel does not take ownership of the bitmap.
     */
    fun setRawBackdrop(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) {
            return
        }
        applyBackdrop(bitmap)
    }

    private fun applyBackdrop(raw: Bitmap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backdropView.setBitmap(raw, refracted = false)
            buildGpuEffects()
        } else {
            renderSoftwareBackdrop(raw)
        }
    }

    private fun renderSoftwareBackdrop(raw: Bitmap) {
        if (softwareJobPending) {
            return
        }
        softwareJobPending = true
        val styleSnapshot = style
        Thread {
            val refracted = GlassRefraction.render(
                raw,
                styleSnapshot.cornerRadiusPx,
                styleSnapshot.params.copy(
                    panelInset = styleSnapshot.panelInset,
                    blur = styleSnapshot.blurRadius
                )
            )
            softwareJobPending = false
            if (refracted != null) {
                post {
                    backdropView.setBitmap(refracted, refracted = true)
                    if (backdropView.alpha < 1f) {
                        backdropView.animate().alpha(1f).setDuration(220L).start()
                    }
                }
            }
        }.start()
    }

    /** GPU tier wiring: blur on the backdrop, refraction shader on the layer. */
    private fun buildGpuEffects() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (width <= 0 || height <= 0) {
            return
        }
        if (lastWidth == width && lastHeight == height && effectBuilt) {
            return
        }
        lastWidth = width
        lastHeight = height
        buildGpuEffectsGuarded()
    }

    @Suppress("NewApi") // callers check SDK_INT >= TIRAMISU inline
    private fun buildGpuEffectsGuarded() {
        try {
            val inset = style.panelInset
            val backdropWidth = width + (2 * inset).toInt()
            val backdropHeight = height + (2 * inset).toInt()

            val blurEffect = RenderEffect.createBlurEffect(
                style.blurRadius, style.blurRadius, Shader.TileMode.CLAMP
            )
            backdropView.setRenderEffect(blurEffect)

            val shaderEffect = GlassShader.create(
                backdropWidth = backdropWidth,
                backdropHeight = backdropHeight,
                panelInset = inset,
                cornerRadius = style.cornerRadiusPx,
                params = style.params
            )
            if (shaderEffect != null) {
                glassLayer.setRenderEffect(shaderEffect)
                backdropView.alpha = 1f
            } else {
                // shader rejected by the driver: degrade to blur + frosted tint
                glassLayer.setRenderEffect(null)
                backdropView.alpha = 0.82f
            }
            effectBuilt = true
        } catch (_: Throwable) {
            try {
                glassLayer.setRenderEffect(null)
            } catch (_: Throwable) {
            }
        }
    }

    /** Keep the glass layer bleeding beyond the panel for the edge lens. */
    private fun layoutBackdrop() {
        val inset = style.panelInset.toInt()
        val params = glassLayer.layoutParams as? LayoutParams ?: LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
        params.width = LayoutParams.MATCH_PARENT
        params.height = LayoutParams.MATCH_PARENT
        glassLayer.layoutParams = params
        glassLayer.translationX = -inset.toFloat()
        glassLayer.translationY = -inset.toFloat()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = style.panelInset.toInt()
        if (w > 0 && h > 0) {
            glassLayer.layoutParams = (glassLayer.layoutParams as? LayoutParams ?: LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
            )).apply {
                width = max(1, w + 2 * inset)
                height = max(1, h + 2 * inset)
            }
            glassLayer.translationX = -inset.toFloat()
            glassLayer.translationY = -inset.toFloat()
        }
        buildGpuEffects()
    }

    /** Appear motion: the panel surfaces like liquid under pressure. */
    fun appear(duration: Long = 300L) {
        scaleX = 0.92f
        scaleY = 0.92f
        alpha = 0f
        animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator(1.9f))
            .start()
    }

    /** Disappear motion; [onFinished] fires after the panel settles. */
    fun disappear(duration: Long = 160L, onFinished: () -> Unit = {}) {
        animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(AccelerateInterpolator(1.5f))
            .withEndAction { onFinished() }
            .start()
    }

    /**
     * The backdrop drawing surface. On the GPU tier it shows the raw capture
     * (blur + refraction happen as RenderEffects); on the software tier it
     * shows the pre-rendered refracted result.
     */
    @SuppressLint("ViewConstructor")
    private class BackdropView(
        context: android.content.Context,
        private val style: GlassStyle,
    ) : View(context) {

        private var bitmap: Bitmap? = null
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        fun setBitmap(bitmap: Bitmap?, refracted: Boolean) {
            this.bitmap = bitmap
            contentDescription = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val current = bitmap
            if (current != null && !current.isRecycled) {
                canvas.drawBitmap(current, 0f, 0f, drawPaint)
            } else {
                // fallback frosted fill while the backdrop is being composed
                fillPaint.color = style.fallbackFill
                val inset = style.panelInset
                canvas.drawRoundRect(
                    inset, inset,
                    width.toFloat() - inset, height.toFloat() - inset,
                    style.cornerRadiusPx, style.cornerRadiusPx,
                    fillPaint
                )
            }
        }
    }
}
