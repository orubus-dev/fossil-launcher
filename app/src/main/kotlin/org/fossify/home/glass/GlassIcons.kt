package org.fossify.home.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.home.models.AppLauncher
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fossil glass engine — icon remix.
 *
 * Turns any installed app icon into a liquid-glass tile without shipping or
 * creating new icons:
 *  - adaptive icons with a monochrome layer (Android 13+) render as a soft
 *    white glyph floating on frosted glass, exactly like the reference;
 *  - every other icon keeps its artwork, desaturated and alpha-boosted, so
 *    the glyph stays readable on both light and dark wallpapers.
 *
 * Results are cached twice: an LruCache for instant drawer scrolling and a
 * PNG disk cache keyed by package, activity, icon version and style, so the
 * very first drawer open after install is the only one that ever pays the
 * generation cost.
 */
object GlassIcons {

    private const val DISK_DIR = "glass_icons"
    private const val GLYPH_SCALE = 0.58f
    private const val MEM_CACHE_TILES = 64

    private val workers: ExecutorService = Executors.newFixedThreadPool(2)
    private val memCache = object : LruCache<String, Bitmap>(MEM_CACHE_TILES) {}
    private val pending = HashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val pendingLock = Any()
    private val diskDirCache = AtomicInteger(-1)

    fun clearMemoryCache() {
        synchronized(memCache) { memCache.evictAll() }
    }

    /**
     * Fetch a glass tile. Returns immediately from memory when possible,
     * otherwise generates on a worker thread and delivers once.
     */
    fun getTile(
        context: Context,
        launcher: AppLauncher,
        sizePx: Int,
        darkTheme: Boolean,
        onReady: (Bitmap?) -> Unit,
    ) {
        if (sizePx <= 0) {
            onReady(null)
            return
        }

        val key = cacheKey(context, launcher, sizePx, darkTheme)
        memCache.get(key)?.let {
            onReady(it)
            return
        }

        val appContext = context.applicationContext
        synchronized(pendingLock) {
            val queue = pending[key]
            if (queue != null) {
                queue.add(onReady)
                return
            }
            pending[key] = mutableListOf(onReady)
        }

        workers.execute {
            val bitmap = loadOrGenerate(appContext, launcher, sizePx, darkTheme, key)
            val callbacks = synchronized(pendingLock) {
                pending.remove(key) ?: mutableListOf()
            }
            if (bitmap != null) {
                memCache.put(key, bitmap)
            }
            callbacks.forEach { callback ->
                try {
                    callback(bitmap)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun cacheKey(
        context: Context,
        launcher: AppLauncher,
        sizePx: Int,
        darkTheme: Boolean,
    ): String {
        val version = try {
            context.packageManager.getPackageInfo(launcher.packageName, 0).let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong()
            }
        } catch (_: Exception) {
            0L
        }
        return "${launcher.packageName}|${launcher.activityName}|$sizePx|${if (darkTheme) 1 else 0}|$version"
            .replace("/", "_")
    }

    private fun loadOrGenerate(
        context: Context,
        launcher: AppLauncher,
        sizePx: Int,
        darkTheme: Boolean,
        key: String,
    ): Bitmap? {
        val diskFile = diskFile(context, key)
        if (diskFile != null && diskFile.exists() && diskFile.length() > 0) {
            val loaded = android.graphics.BitmapFactory.decodeFile(diskFile.absolutePath)
            if (loaded != null && loaded.width == sizePx) {
                return loaded
            }
            diskFile?.delete()
        }

        val drawable = launcher.drawable
        if (drawable == null) {
            return null
        }

        val bitmap = try {
            renderTile(drawable, sizePx, darkTheme)
        } catch (_: Exception) {
            null
        }

        if (bitmap != null && diskFile != null) {
            try {
                diskFile.parentFile?.mkdirs()
                diskFile.outputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 92, stream)
                }
            } catch (_: Exception) {
            }
        }
        return bitmap
    }

    private fun diskFile(context: Context, key: String): File? {
        return try {
            val dir = File(context.cacheDir, DISK_DIR)
            if (diskDirCache.getAndSet(1) != 1) {
                dir.mkdirs()
            }
            File(dir, Integer.toHexString(key.hashCode()) + ".png")
        } catch (_: Exception) {
            null
        }
    }

    /** Render one glass tile: frosted rounded square + remixed glyph. */
    private fun renderTile(drawable: Drawable, size: Int, darkTheme: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val densityScale = size / 108f   // tiles look proportional at any size
        val radius = size * 0.27f

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (darkTheme) {
                Color.argb(66, 12, 12, 14)
            } else {
                Color.argb(52, 255, 255, 255)
            }
        }
        val path = Path().apply {
            addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, Path.Direction.CW)
        }
        canvas.drawPath(path, fillPaint)

        // subtle inner edge highlight, sells the glass thickness
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = densityScale * 1.1f
            color = if (darkTheme) {
                Color.argb(38, 255, 255, 255)
            } else {
                Color.argb(64, 255, 255, 255)
            }
        }
        val inset = edgePaint.strokeWidth / 2f
        canvas.drawRoundRect(
            inset, inset, size - inset, size - inset, radius, radius, edgePaint
        )

        val mono = extractMonochrome(drawable)
        if (mono != null) {
            // premium path: soft white glyph, faint drop shadow for contrast
            val glyphSize = size * GLYPH_SCALE
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(70, 0, 0, 0)
                maskFilter = BlurMaskFilter(densityScale * 1.6f, BlurMaskFilter.Blur.NORMAL)
            }
            val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val glyph = renderGlyphBitmap(mono, glyphSize.toInt(), Color.WHITE)
            if (glyph != null) {
                val left = (size - glyph.width) / 2f
                val top = (size - glyph.height) / 2f
                canvas.drawBitmap(glyph, left, top + densityScale * 0.8f, shadowPaint)
                canvas.drawBitmap(glyph, left, top, glyphPaint)
                glyph.recycle()
            }
        } else {
            // universal path: desaturate the original artwork, keep it readable
            val artwork = drawableToBitmap(drawable, size)
            if (artwork != null) {
                val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = if (darkTheme) 225 else 235
                    colorFilter = saturationFilter(0.32f)
                }
                val insetArt = size * 0.14f
                val artSide = size - insetArt * 2
                val artPath = Path().apply {
                    addRoundRect(
                        insetArt, insetArt, insetArt + artSide, insetArt + artSide,
                        radius * 0.62f, radius * 0.62f, Path.Direction.CW
                    )
                }
                val save = canvas.save()
                canvas.clipPath(artPath)
                canvas.drawBitmap(
                    artwork, null,
                    android.graphics.RectF(insetArt, insetArt, insetArt + artSide, insetArt + artSide),
                    artPaint
                )
                canvas.restoreToCount(save)
                artwork.recycle()
            }
        }

        return bitmap
    }

    /** Android 13+ themed-icon layer, if the app provides one. */
    private fun extractMonochrome(drawable: Drawable): Drawable? {
        if (!isTiramisuPlus()) {
            return null
        }
        return try {
            if (drawable is AdaptiveIconDrawable) {
                drawable.monochrome
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun renderGlyphBitmap(mono: Drawable, size: Int, tint: Int): Bitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val layer = mono.constantState?.newDrawable()?.mutate() ?: mono
            layer.setBounds(0, 0, size, size)
            layer.setColorFilter(tintFilter(tint))
            layer.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun tintFilter(color: Int): ColorFilter {
        val matrix = ColorMatrix()
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val a = Color.alpha(color) / 255f
        matrix.set(floatArrayOf(
            0f, 0f, 0f, 0f, 255f * r,
            0f, 0f, 0f, 0f, 255f * g,
            0f, 0f, 0f, 0f, 255f * b,
            0f, 0f, 0f, 255f * a, 0f
        ))
        return ColorMatrixColorFilter(matrix)
    }

    private fun saturationFilter(saturation: Float): ColorFilter {
        val matrix = ColorMatrix()
        matrix.setSaturation(saturation)
        return ColorMatrixColorFilter(matrix)
    }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val layer = drawable.constantState?.newDrawable()?.mutate() ?: drawable
            layer.setBounds(0, 0, size, size)
            layer.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}
