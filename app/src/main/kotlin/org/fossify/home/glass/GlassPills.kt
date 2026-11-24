package org.fossify.home.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import android.os.Build
import kotlin.math.roundToInt

/**
 * Shared plumbing for the two dock pills: a small frosted glass surface
 * rendered through the refraction engine (same tuning family as the clock,
 * fixed gentle defaults) with crisp content drawn on top.
 */
abstract class GlassPill(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    constructor(context: Context) : this(context, null)

    protected var glassBitmap: Bitmap? = null
    private var glassKey: Long = 0L
    private var renderJob: Thread? = null

    protected val density = resources.displayMetrics.density
    protected val pillRadius = 26f * density
    protected val dark = context.isNightMode()

    protected val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    /** The pill body bleeds to the view edges; inset keeps content off the rim. */
    protected open fun pillInset(): Float = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glassKey = 0L
    }

    /** Wallpapers changed — drop the cached glass so it re-renders. */
    fun invalidateGlass() {
        glassKey = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) {
            return
        }
        requestGlassRender()

        val glass = glassBitmap
        val inset = pillInset()
        val body = RectF(inset, inset, width - inset, height - inset)
        if (glass != null && !glass.isRecycled) {
            canvas.drawBitmap(glass, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            canvas.drawRoundRect(
                body, pillRadius, pillRadius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (dark) Color.argb(170, 22, 22, 26) else Color.argb(160, 246, 248, 252)
                }
            )
        }

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = if (dark) Color.argb(44, 255, 255, 255) else Color.argb(58, 255, 255, 255)
        }
        canvas.drawRoundRect(body, pillRadius, pillRadius, stroke)

        drawContent(canvas, body)
    }

    protected abstract fun drawContent(canvas: Canvas, body: RectF)

    private fun requestGlassRender() {
        if (width <= 0 || height <= 0) {
            return
        }

        val key = width * 7919L + height * 104729L + GlassWallpapers.version() * 31L
        if (key == glassKey) {
            return
        }
        if (renderJob?.isAlive == true) {
            return
        }

        glassKey = key
        val wallpaper = GlassWallpapers.getSnapshot(context)
        val targetWidth = width
        val targetHeight = height

        renderJob = Thread({
            val rendered = renderPillGlass(wallpaper, targetWidth, targetHeight)
            post {
                if (rendered != null && !rendered.isRecycled) {
                    glassBitmap = rendered
                }
                invalidate()
            }
        }, "fossil-pill-glass").apply {
            isDaemon = true
            start()
        }
    }

    private fun renderPillGlass(wallpaper: Bitmap?, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (wallpaper == null || wallpaper.isRecycled) {
            return null
        }
        return try {
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
                .coerceIn(0, (wallpaper.width - cropWidth).coerceAtLeast(0))
            val top = ((wallpaper.height - cropHeight) * 0.62f).roundToInt()
                .coerceIn(0, (wallpaper.height - cropHeight).coerceAtLeast(0))
            val backdrop = if (cropWidth >= wallpaper.width && cropHeight >= wallpaper.height) {
                wallpaper
            } else {
                Bitmap.createBitmap(
                    wallpaper, left, top,
                    cropWidth.coerceAtMost(wallpaper.width),
                    cropHeight.coerceAtMost(wallpaper.height)
                )
            }

            val params = GlassRefraction.Params(
                refraction = 10f,
                dispersion = 0.32f,
                edgeWidth = 16f * density,
                specular = 0.7f,
                tint = if (dark) Color.argb(96, 20, 20, 24) else Color.argb(104, 250, 250, 254),
                alpha = 1f,
                panelInset = 0f,
                blur = 14f,
            )

            val rendered = GlassRefraction.render(backdrop, pillRadius, params)
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

/**
 * The battery pill: percent + device model + a slim charge bar that fills
 * while charging. Data comes from the sticky ACTION_BATTERY_CHANGED
 * broadcast; MainActivity forwards updates to [updateBattery].
 */
class GlassBatteryPill(context: Context, attrs: AttributeSet?) : GlassPill(context, attrs) {
    constructor(context: Context) : this(context, null)


    private var batteryPercent = -1
    private var charging = false

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                updateBattery(intent)
            }
        }
    }

    fun register() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        try {
            // NOT_EXPORTED still receives protected system broadcasts
            ContextCompat.registerReceiver(
                context, batteryReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {
        }
        // seed from the sticky broadcast even if the registration raced
        try {
            val sticky = if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(null, filter)
            }
            sticky?.let { updateBattery(it) }
        } catch (_: Exception) {
        }
    }

    fun unregister() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            batteryPercent = (level * 100f / scale).roundToInt().coerceIn(0, 100)
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        post { invalidate() }
    }

    override fun drawContent(canvas: Canvas, body: RectF) {
        val percentText = if (batteryPercent >= 0) "$batteryPercent%" else "--"
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Device"

        textPaint.textSize = body.height() * 0.30f
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.color = Color.WHITE
        textPaint.setShadowLayer(2.5f * density, 0f, 1f * density, Color.argb(120, 0, 0, 0))

        val percentX = body.left + body.width() * 0.22f
        val centerY = body.centerY()
        canvas.drawText(percentText, percentX, centerY + textPaint.textSize * 0.35f, textPaint)

        // device model, right side
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textPaint.textSize = body.height() * 0.215f
        textPaint.color = Color.argb(214, 255, 255, 255)
        var modelText = model
        val maxWidth = body.width() * 0.5f
        while (textPaint.measureText(modelText) > maxWidth && modelText.length > 3) {
            modelText = modelText.dropLast(2)
        }
        if (modelText != model) {
            modelText = "$modelText…"
        }
        val modelX = body.right - body.width() * 0.06f - textPaint.measureText(modelText) / 2f
        canvas.drawText(modelText, modelX + textPaint.measureText(modelText) / 2f, centerY + textPaint.textSize * 0.35f, textPaint)

        // slim charge bar along the bottom
        val barLeft = body.left + body.width() * 0.08f
        val barRight = body.right - body.width() * 0.08f
        val barTop = body.bottom - body.height() * 0.16f
        val barBottom = body.bottom - body.height() * 0.10f
        barBackgroundPaint.color = Color.argb(60, 255, 255, 255)
        canvas.drawRoundRect(barLeft, barTop, barRight, barBottom, barBottom - barTop, barBottom - barTop, barBackgroundPaint)

        if (batteryPercent >= 0) {
            val fraction = batteryPercent / 100f
            val fillRight = barLeft + (barRight - barLeft) * fraction
            barPaint.color = if (charging) Color.argb(235, 120, 235, 160) else Color.argb(225, 255, 255, 255)
            if (fillRight - barLeft > (barBottom - barTop)) {
                canvas.drawRoundRect(barLeft, barTop, fillRight, barBottom, (barBottom - barTop) / 2f, (barBottom - barTop) / 2f, barPaint)
            }
        }
        textPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }
}

/**
 * The search pill above the dock: a magnifier glass with a "Search apps"
 * hint. Tapping it opens the app drawer — wired by MainActivity.
 */
class GlassSearchPill(context: Context, attrs: AttributeSet?) : GlassPill(context, attrs) {
    constructor(context: Context) : this(context, null)


    override fun drawContent(canvas: Canvas, body: RectF) {
        val hint = context.getString(org.fossify.home.R.string.search_pill_hint)
        val iconRadius = body.height() * 0.17f

        // magnifier: circle + handle
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.1f * density
            color = Color.argb(232, 255, 255, 255)
        }
        val cx = body.left + body.height() * 0.42f
        val cy = body.centerY() - body.height() * 0.04f
        canvas.drawCircle(cx, cy, iconRadius, stroke)
        val handle = Path()
        val handleStartX = cx + iconRadius * 0.72f
        val handleStartY = cy + iconRadius * 0.72f
        handle.moveTo(handleStartX, handleStartY)
        handle.lineTo(handleStartX + iconRadius * 0.62f, handleStartY + iconRadius * 0.62f)
        canvas.drawPath(handle, stroke)

        textPaint.textSize = body.height() * 0.30f
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textPaint.color = Color.argb(226, 255, 255, 255)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.setShadowLayer(2.5f * density, 0f, 1f * density, Color.argb(110, 0, 0, 0))
        val textX = body.left + body.height() * 0.78f
        canvas.drawText(hint, textX, body.centerY() + textPaint.textSize * 0.35f, textPaint)
        textPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        textPaint.textAlign = Paint.Align.CENTER
    }
}
