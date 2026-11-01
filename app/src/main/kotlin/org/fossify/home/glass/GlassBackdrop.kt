package org.fossify.home.glass

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fossil liquid glass engine — backdrop pipeline.
 *
 * Every glass panel needs to know what is actually standing behind it. This
 * object composes that answer from two layers:
 *
 *  1. the live system wallpaper, scaled to cover the window,
 *  2. the launcher's own content (icons, widgets, dock) captured at reduced
 *     resolution from the activity hierarchy, so popups refract real pixels,
 *     not just wallpaper.
 *
 * The composite is dimmed the way the system dims a background behind a
 * dialog, cropped to the panel's screen rect and delivered as a bitmap that
 * [GlassRefraction] (software tier) or [GlassShader] (GPU tier) processes.
 *
 * Capture strategy: the wallpaper bitmap is read on a background thread (it
 * can take a few hundred ms on first use), the launcher content snapshot is
 * taken on the UI thread at 1/3 resolution (a few ms) and cached. Panels
 * therefore never block: they show immediately with a plain fill and
 * crossfade the refracted backdrop in when it is ready.
 */
object GlassBackdrop {

    private const val CONTENT_CAPTURE_SCALE = 1f / 3f
    private const val COMPOSE_TIMEOUT_MS = 3000L

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fossil-glass-backdrop").apply { isDaemon = true }
    }

    @Volatile
    private var contentSnapshot: Bitmap? = null

    @Volatile
    private var windowWidth: Int = 0

    @Volatile
    private var windowHeight: Int = 0

    @Volatile
    private var composite: Bitmap? = null

    @Volatile
    private var compositeKey: Long = 0L

    /**
     * Capture the launcher content on the UI thread. Call this right before
     * showing a glass popup while the hierarchy is still in its "closed" state
     * (the popup itself is not yet visible, so it cannot appear in the shot).
     */
    fun captureContent(activity: Activity) {
        try {
            val decor = activity.window?.decorView ?: return
            val width = decor.width
            val height = decor.height
            if (width <= 0 || height <= 0) {
                return
            }

            val scale = CONTENT_CAPTURE_SCALE
            val outWidth = (width * scale).toInt().coerceAtLeast(1)
            val outHeight = (height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            decor.draw(canvas)

            synchronized(lock) {
                contentSnapshot?.recycle()
                contentSnapshot = bitmap
                windowWidth = width
                windowHeight = height
                contentVersion++
            }
        } catch (_: Throwable) {
            // best effort — the wallpaper layer alone still makes a valid backdrop
        }
    }

    /** Drop the captured launcher content (e.g. after a popup is dismissed). */
    fun invalidateContent() {
        synchronized(lock) {
            contentSnapshot?.recycle()
            contentSnapshot = null
        }
    }

    /** Invalidate everything, including the composed result. */
    fun invalidateAll() {
        synchronized(lock) {
            contentSnapshot?.recycle()
            contentSnapshot = null
            composite?.recycle()
            composite = null
            compositeKey = 0L
            windowWidth = 0
            windowHeight = 0
        }
    }

    /**
     * Request the raw backdrop for a panel occupying [screenRect] (window
     * coordinates). Non-blocking: returns immediately, [onReady] is invoked
     * on the UI thread with the composed backdrop, or null when none could
     * be produced (callers keep their plain glass fill in that case).
     */
    fun request(
        activity: Activity,
        screenRect: RectF,
        outWidth: Int,
        outHeight: Int,
        dimAmount: Float,
        onReady: (Bitmap?) -> Unit,
    ) {
        val appContext = activity.applicationContext
        val key = composeKey(screenRect, outWidth, outHeight, dimAmount)
        synchronized(lock) {
            if (composite != null && compositeKey == key) {
                onReady(composite)
                return
            }
        }

        executor.execute {
            val result = compose(appContext, screenRect, outWidth, outHeight, dimAmount)
            synchronized(lock) {
                composite?.recycle()
                composite = result
                compositeKey = if (result != null) key else 0L
            }
            activity.runOnUiThread { onReady(result) }
        }
    }

    /**
     * Blocking variant for surfaces that are built off the UI thread anyway
     * (canvas-drawn panels). Never blocks longer than [COMPOSE_TIMEOUT_MS].
     */
    fun getBlocking(
        activity: Activity,
        screenRect: RectF,
        outWidth: Int,
        outHeight: Int,
        dimAmount: Float,
    ): Bitmap? {
        val appContext = activity.applicationContext
        val key = composeKey(screenRect, outWidth, outHeight, dimAmount)
        synchronized(lock) {
            if (composite != null && compositeKey == key) {
                return composite
            }
        }

        val result = AtomicBoolean(false)
        var composed: Bitmap? = null
        executor.execute {
            val bitmap = compose(appContext, screenRect, outWidth, outHeight, dimAmount)
            synchronized(lock) {
                composite?.recycle()
                composite = bitmap
                compositeKey = if (bitmap != null) key else 0L
            }
            composed = bitmap
            result.set(true)
        }
        try {
            val waitStart = System.currentTimeMillis()
            while (!result.get() && System.currentTimeMillis() - waitStart < COMPOSE_TIMEOUT_MS) {
                Thread.sleep(16)
            }
        } catch (_: InterruptedException) {
        }
        return composed
    }

    private fun composeKey(rect: RectF, width: Int, height: Int, dim: Float): Long {
        var hash = rect.left.toRawBits().toLong()
        hash = hash * 31 + rect.top.toRawBits()
        hash = hash * 31 + rect.right.toRawBits()
        hash = hash * 31 + rect.bottom.toRawBits()
        hash = hash * 31 + width
        hash = hash * 31 + height
        hash = hash * 31 + (dim * 100).toInt()
        hash = hash * 31 + GlassWallpapers.version()
        hash = hash * 31 + contentVersion
        return hash
    }

    @Volatile
    private var contentVersion: Int = 0
    private fun compose(
        appContext: android.content.Context,
        screenRect: RectF,
        outWidth: Int,
        outHeight: Int,
        dimAmount: Float,
    ): Bitmap? {
        if (outWidth <= 0 || outHeight <= 0 || screenRect.isEmpty) {
            return null
        }

        try {
            val wallpaper = GlassWallpapers.getBackdropBlocking(appContext) ?: return null
            val content = synchronized(lock) { contentSnapshot }
            val windowW = synchronized(lock) { windowWidth }
                .takeIf { it > 0 } ?: screenRect.right.toInt()
            val windowH = synchronized(lock) { windowHeight }
                .takeIf { it > 0 } ?: screenRect.bottom.toInt()

            val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            // Map window coordinates -> output pixels for the panel region.
            val regionScale = outWidth / screenRect.width()
            canvas.save()
            canvas.scale(regionScale, regionScale)
            canvas.translate(-screenRect.left, -screenRect.top)

            // Layer 1: wallpaper covering the whole window.
            val coverScale = maxOf(windowW.toFloat() / wallpaper.width, windowH.toFloat() / wallpaper.height)
            val matrix = Matrix()
            matrix.setScale(coverScale, coverScale)
            matrix.postTranslate(
                (windowW - wallpaper.width * coverScale) / 2f,
                (windowH - wallpaper.height * coverScale) / 2f
            )
            canvas.drawBitmap(wallpaper, matrix, paint)

            // Layer 2: launcher content, captured at 1/3 window scale.
            if (content != null && !content.isRecycled) {
                canvas.save()
                canvas.scale(1f / CONTENT_CAPTURE_SCALE, 1f / CONTENT_CAPTURE_SCALE)
                canvas.drawBitmap(content, 0f, 0f, paint)
                canvas.restore()
            }

            // Layer 3: dim, exactly like the system scrim behind a dialog.
            if (dimAmount > 0f) {
                paint.color = Color.argb((dimAmount * 255).toInt().coerceIn(0, 255), 0, 0, 0)
                canvas.drawRect(0f, 0f, windowW.toFloat(), windowH.toFloat(), paint)
            }

            canvas.restore()
            return out
        } catch (_: Throwable) {
            return null
        }
    }
}
