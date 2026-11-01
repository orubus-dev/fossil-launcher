package org.fossify.home.glass

import android.content.Context
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fossil glass engine — wallpaper pipeline.
 *
 * Produces real blurred samples of the live wallpaper used as refraction
 * backdrops for liquid glass surfaces: the folder panel, the settings skin
 * and dialogs. The blur is a downscale/upscale (bilinear) pass followed by a
 * light box blur, which is fast, dependency-free and behaves identically on
 * every API level — no deprecated RenderScript, no hardware quirks.
 *
 * Everything is cached in memory and invalidated whenever the wallpaper
 * changes (MainActivity forwards wallpaper color events to [invalidate]).
 */
object GlassWallpapers {

    private const val SNAPSHOT_MAX_DIM = 640
    private const val BLUR_INTERMEDIATE_DIM = 72
    private const val BOX_BLUR_PASSES = 2

    private val loading = AtomicBoolean(false)
    private val lock = Any()

    @Volatile
    private var snapshot: Bitmap? = null

    @Volatile
    private var backdrop: Bitmap? = null

    @Volatile
    private var loadLatch: CountDownLatch? = null

    /** Drop every cached sample; the next read re-captures the wallpaper. */
    fun invalidate() {
        synchronized(lock) {
            snapshot = null
            backdrop = null
            loadLatch = null
        }
    }

    /** Kick off a background capture if nothing is cached yet. */
    fun prefetch(context: Context) {
        if (snapshot != null) {
            return
        }
        ensureLoaded(context.applicationContext)
    }

    /**
     * Returns the blurred wallpaper backdrop, or null when it is not ready
     * yet (callers must gracefully fall back to a plain glass fill).
     */
    fun getBackdrop(context: Context): Bitmap? {
        backdrop?.let { return it }
        ensureLoaded(context.applicationContext)
        return backdrop
    }

    /** Blocking helper for one-off surfaces (dialogs, settings). */
    fun getBackdropBlocking(context: Context, timeoutMs: Long = 2500): Bitmap? {
        val result = getBackdrop(context)
        if (result != null) {
            return result
        }
        loadLatch?.let { latch ->
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        }
        return backdrop
    }

    /** Capture the raw system wallpaper, downscaled for cheap processing. */
    fun getSnapshot(context: Context): Bitmap? {
        snapshot?.let { return it }
        ensureLoaded(context.applicationContext)
        return snapshot
    }

    private fun ensureLoaded(appContext: Context) {
        if (snapshot != null) {
            return
        }
        val latch = CountDownLatch(1)
        loadLatch = latch
        if (!loading.compareAndSet(false, true)) {
            return
        }

        Thread {
            try {
                val manager = WallpaperManager.getInstance(appContext)
                val drawable = try {
                    manager.getDrawable(WallpaperManager.FLAG_SYSTEM)
                } catch (_: Exception) {
                    null
                }

                val source = drawable.toBitmapSafely()
                if (source != null && source.width > 0 && source.height > 0) {
                    val scaled = scaleToFit(source, SNAPSHOT_MAX_DIM)
                    if (scaled != source) {
                        source.recycle()
                    }
                    synchronized(lock) {
                        snapshot = scaled
                        backdrop = null
                    }
                }
            } catch (_: Exception) {
            } finally {
                loading.set(false)
                latch.countDown()
            }
        }.start()
    }

    private fun android.graphics.drawable.Drawable.toBitmapSafely(): Bitmap? {
        return try {
            val width = maxOf(intrinsicWidth, 1).coerceAtMost(2560)
            val height = maxOf(intrinsicHeight, 1).coerceAtMost(2560)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            setBounds(0, 0, bitmap.width, bitmap.height)
            draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleToFit(source: Bitmap, maxDim: Int): Bitmap {
        val scale = minOf(
            maxDim.toFloat() / source.width,
            maxDim.toFloat() / source.height,
            1f
        )
        if (scale >= 1f) {
            return source
        }
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    /**
     * Heavy blur of the wallpaper for glass panels: downscale to a tiny
     * intermediate bitmap, upscale with bilinear filtering (natural gaussian
     * falloff), then smooth with two light box-blur passes.
     */
    fun blurSample(source: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val intermediate = scaleToFit(source, BLUR_INTERMEDIATE_DIM)
        val upscaled = Bitmap.createBitmap(
            outWidth.coerceAtLeast(1), outHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(upscaled)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val matrix = Matrix().apply {
            setScale(
                outWidth.toFloat() / intermediate.width,
                outHeight.toFloat() / intermediate.height
            )
        }
        canvas.drawBitmap(intermediate, matrix, paint)
        if (intermediate != source) {
            intermediate.recycle()
        }

        var result = upscaled
        repeat(BOX_BLUR_PASSES) {
            val next = boxBlur(result, radius = 4)
            if (next != result) {
                result.recycle()
                result = next
            }
        }
        return result
    }

    /** Cheap separable box blur on ARGB pixels. */
    private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) {
            return source
        }

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val temp = IntArray(pixels.size)
        val window = radius * 2 + 1

        // horizontal pass
        for (y in 0 until height) {
            val row = y * width
            var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -radius..radius) {
                val x = i.coerceIn(0, width - 1)
                val pixel = pixels[row + x]
                aSum += (pixel ushr 24) and 0xFF
                rSum += (pixel ushr 16) and 0xFF
                gSum += (pixel ushr 8) and 0xFF
                bSum += pixel and 0xFF
            }
            for (x in 0 until width) {
                temp[row + x] = composePixel(aSum / window, rSum / window, gSum / window, bSum / window)
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

        // vertical pass
        for (x in 0 until width) {
            var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -radius..radius) {
                val y = i.coerceIn(0, height - 1)
                val pixel = temp[y * width + x]
                aSum += (pixel ushr 24) and 0xFF
                rSum += (pixel ushr 16) and 0xFF
                gSum += (pixel ushr 8) and 0xFF
                bSum += pixel and 0xFF
            }
            for (y in 0 until height) {
                pixels[y * width + x] = composePixel(aSum / window, rSum / window, gSum / window, bSum / window)
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

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun composePixel(a: Int, r: Int, g: Int, b: Int): Int {
        return ((a.coerceIn(0, 255)) shl 24) or
                ((r.coerceIn(0, 255)) shl 16) or
                ((g.coerceIn(0, 255)) shl 8) or
                (b.coerceIn(0, 255))
    }
}
