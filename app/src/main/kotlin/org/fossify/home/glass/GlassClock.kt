package org.fossify.home.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import org.fossify.home.extensions.config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fossil liquid glass clock — the home screen centerpiece.
 *
 * A frosted panel rendered through the same refraction engine as every other
 * glass surface: the live wallpaper is sampled behind the clock's screen
 * rect, blurred and bent through a rounded-rect lens, then the digits are
 * drawn on top with a clear-glass sheen (vertical white gradient + soft
 * specular glow), exactly like the reference launcher.
 *
 * The panel is a pure visual: it never consumes touches, so page swipes and
 * drawer gestures starting on it still work. Long-press detection lives in
 * MainActivity.homeScreenLongPressed, which checks [clockRect] before falling
 * back to the home menu.
 *
 * All optics are user-tunable from the glass settings dialog (long-press the
 * clock): refraction, vibrancy, height/depth, blur radius, highlight width
 * and fill opacity. Settings live in Config and re-render live.
 */
class GlassClockView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    companion object {
        private const val TICK_MS = 20_000L
        private const val MAX_RENDER_PIXELS = 220_000

        /** Bits of the tuning sliders that change the refraction job. */
        fun paramsSignature(
            refraction: Float,
            vibrancy: Float,
            depth: Float,
            blur: Float,
            highlight: Float,
            fill: Float,
        ): Long {
            var hash = refraction.toRawBits().toLong()
            hash = hash * 31 + vibrancy.toRawBits()
            hash = hash * 31 + depth.toRawBits()
            hash = hash * 31 + blur.toRawBits()
            hash = hash * 31 + highlight.toRawBits()
            hash = hash * 31 + fill.toRawBits()
            return hash
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // glass render state -----------------------------------------------------
    private var glassBitmap: Bitmap? = null
    private var glassKey: Long = 0L
    private var renderJob: Thread? = null
    private var renderPending = false

    // paints -----------------------------------------------------------------
    private val panelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private val panelRect = RectF()
    private val clockRect = Rect()

    /** Bounds of the visible glass panel in this view's window coordinates. */
    fun clockRect(): Rect {
        clockRect.set(
            (x + panelRect.left).roundToInt(),
            (y + panelRect.top).roundToInt(),
            (x + panelRect.right).roundToInt(),
            (y + panelRect.bottom).roundToInt()
        )
        return clockRect
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            invalidate()
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    init {
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val density = resources.displayMetrics.density
        // panel aspect: a wide band that comfortably fits the digits + date
        val desired = (width * 0.34f).coerceIn(110f * density, 175f * density).roundToInt()
        setMeasuredDimension(width, View.resolveSize(desired, heightMeasureSpec))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mainHandler.postDelayed(tickRunnable, TICK_MS)
        GlassWallpapers.prefetch(context)
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(tickRunnable)
        super.onDetachedFromWindow()
    }

    /** Wallpapers changed or tuning sliders moved — drop the cached glass. */
    fun invalidateGlass() {
        glassKey = 0L
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glassKey = 0L
        requestGlassRender()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) {
            return
        }

        val config = context.config
        if (!config.glassClockEnabled) {
            return
        }

        requestGlassRender()

        val density = resources.displayMetrics.density
        val radius = 26f * density
        panelRect.set(0f, 0f, width.toFloat(), height.toFloat())

        val dark = context.isNightMode()
        val glass = glassBitmap
        if (glass != null && !glass.isRecycled) {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            canvas.drawBitmap(glass, 0f, 0f, paint)
        } else {
            // frosted fallback while the refracted panel renders
            val fill = if (dark) Color.argb(168, 22, 22, 26) else Color.argb(158, 246, 248, 252)
            panelStrokePaint.color = if (dark) Color.argb(40, 255, 255, 255) else Color.argb(52, 0, 0, 0)
            canvas.drawRoundRect(panelRect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fill
            })
        }

        // 1px specular stroke around the panel
        panelStrokePaint.color = if (dark) Color.argb(42, 255, 255, 255) else Color.argb(56, 255, 255, 255)
        canvas.drawRoundRect(panelRect, radius, radius, panelStrokePaint)

        drawClockText(canvas, dark)
    }

    private fun drawClockText(canvas: Canvas, dark: Boolean) {
        val density = resources.displayMetrics.density
        val now = Date()

        val pattern = if (context.config.glassClock24h) "HH:mm" else "h:mm"
        val timeText = SimpleDateFormat(pattern, Locale.getDefault()).format(now)
        val amPm = if (!context.config.glassClock24h) {
            SimpleDateFormat("a", Locale.getDefault()).format(now)
        } else {
            ""
        }
        val dateText = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)

        val panelWidth = panelRect.width()
        val panelHeight = panelRect.height()
        if (panelWidth < 10f || panelHeight < 10f) {
            return
        }

        // digits: clear-glass sheen via a vertical white gradient
        val timeSize = panelHeight * 0.42f
        timePaint.textSize = timeSize
        timePaint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        timePaint.textAlign = Paint.Align.CENTER
        timePaint.setShadowLayer(
            timeSize * 0.10f, 0f, timeSize * 0.02f,
            if (dark) Color.argb(90, 0, 0, 0) else Color.argb(70, 0, 0, 0)
        )

        val top = if (dark) Color.argb(255, 255, 255, 255) else Color.argb(255, 255, 255, 255)
        val bottom = if (dark) Color.argb(205, 255, 255, 255) else Color.argb(210, 255, 255, 255)
        timePaint.shader = LinearGradient(
            0f, panelRect.top + panelHeight * 0.14f,
            0f, panelRect.top + panelHeight * 0.72f,
            top, bottom, Shader.TileMode.CLAMP
        )

        val timeWithSuffix = if (amPm.isNotEmpty()) "$timeText $amPm" else timeText
        val timeY = panelRect.top + panelHeight * 0.56f
        canvas.drawText(timeWithSuffix, panelRect.centerX(), timeY, timePaint)
        timePaint.shader = null
        timePaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)

        datePaint.textSize = panelHeight * 0.115f
        datePaint.typeface = Typeface.SANS_SERIF
        datePaint.textAlign = Paint.Align.CENTER
        datePaint.color = if (dark) Color.argb(196, 255, 255, 255) else Color.argb(214, 255, 255, 255)
        datePaint.setShadowLayer(
            3f * density, 0f, 1f * density,
            if (dark) Color.argb(110, 0, 0, 0) else Color.argb(80, 0, 0, 0)
        )
        canvas.drawText(dateText, panelRect.centerX(), panelRect.top + panelHeight * 0.78f, datePaint)
        datePaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }

    /**
     * Kick a background refraction render for the panel. The job samples the
     * wallpaper at this view's window rect and runs it through
     * [GlassRefraction] with the user's tuning. Debounced: while a job runs,
     * new requests collapse into one follow-up.
     */
    private fun requestGlassRender() {
        if (width <= 0 || height <= 0) {
            return
        }

        val config = context.config
        val signature = paramsSignature(
            config.glassClockRefraction,
            config.glassClockVibrancy,
            config.glassClockDepth,
            config.glassClockBlur,
            config.glassClockHighlight,
            config.glassClockFill,
        )
        val key = signature * 31 + GlassWallpapers.version() + width * 7919L + height * 104729L
        if (key == glassKey) {
            return
        }

        if (renderJob?.isAlive == true) {
            renderPending = true
            return
        }

        glassKey = key
        val targetWidth = width
        val targetHeight = height
        val wallpaper = GlassWallpapers.getSnapshot(context)
        val appContext = context.applicationContext

        val job = Thread({
            val rendered = renderClockGlass(appContext, wallpaper, targetWidth, targetHeight, key)
            mainHandler.post {
                if (rendered != null && !rendered.isRecycled) {
                    glassBitmap = rendered
                }
                invalidate()
                if (renderPending) {
                    renderPending = false
                    glassKey = 0L
                    requestGlassRender()
                }
            }
        }, "fossil-clock-glass").apply { isDaemon = true }
        renderJob = job
        job.start()
    }

    private fun renderClockGlass(
        appContext: Context,
        wallpaper: Bitmap?,
        targetWidth: Int,
        targetHeight: Int,
        key: Long,
    ): Bitmap? {
        if (wallpaper == null || wallpaper.isRecycled) {
            return null
        }

        return try {
            val config = appContext.config
            val density = appContext.resources.displayMetrics.density
            val cornerRadius = 26f * density

            // crop the wallpaper to this panel's aspect, cover-fit
            val viewAspect = targetWidth.toFloat() / targetHeight
            val cropWidth: Int
            val cropHeight: Int
            if (wallpaper.width / wallpaper.height.toFloat() > viewAspect) {
                cropHeight = wallpaper.height
                cropWidth = (wallpaper.height * viewAspect).roundToInt()
            } else {
                cropWidth = wallpaper.width
                cropHeight = (wallpaper.width / viewAspect).roundToInt()
            }
            val left = ((wallpaper.width - cropWidth) / 2f).roundToInt()
            val top = ((wallpaper.height - cropHeight) * 0.35f).roundToInt()
                .coerceIn(0, (wallpaper.height - cropHeight).coerceAtLeast(0))
            val backdrop = if (cropWidth >= wallpaper.width && cropHeight >= wallpaper.height) {
                wallpaper
            } else {
                Bitmap.createBitmap(wallpaper, left, top, cropWidth.coerceAtMost(wallpaper.width), cropHeight.coerceAtMost(wallpaper.height))
            }

            // cap the render resolution; under blur + refraction the upscale is invisible
            val scale = min(
                1f,
                kotlin.math.sqrt(MAX_RENDER_PIXELS.toFloat() / (backdrop.width * backdrop.height))
            )
            val renderWidth = (backdrop.width * scale).roundToInt().coerceAtLeast(3)
            val renderHeight = (backdrop.height * scale).roundToInt().coerceAtLeast(3)

            val fillAlpha = (config.glassClockFill * 150f).roundToInt().coerceIn(0, 150)
            val params = GlassRefraction.Params(
                refraction = config.glassClockRefraction,
                dispersion = 0.35f,
                edgeWidth = (8f + config.glassClockDepth * 54f) * density,
                specular = config.glassClockHighlight,
                tint = Color.argb(fillAlpha, 255, 255, 255),
                alpha = 1f,
                panelInset = 0f,
                blur = config.glassClockBlur,
            )

            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(backdrop, renderWidth, renderHeight, true)
            } else {
                backdrop
            }

            val rendered = GlassRefraction.render(scaled, cornerRadius, params)

            if (scaled !== backdrop) {
                scaled.recycle()
            }

            rendered?.let { glass ->
                val out = Bitmap.createScaledBitmap(glass, targetWidth, targetHeight, true)
                if (out !== glass) {
                    glass.recycle()
                }
                out
            }
        } catch (_: Throwable) {
            null
        }
    }
}
