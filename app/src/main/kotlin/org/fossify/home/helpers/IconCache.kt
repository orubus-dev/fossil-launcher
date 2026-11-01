package org.fossify.home.helpers

import android.graphics.drawable.Drawable
import android.util.LruCache
import org.fossify.home.models.AppLauncher

/**
 * Fossil icon cache.
 *
 * Two layers:
 *  - [launchers]: the app list snapshot, so the drawer can populate its grid
 *    the instant it is opened instead of waiting on PackageManager.
 *  - [drawables]: a bounded LRU of resolved icon drawables, so home screen
 *    grid refreshes and drawer rebinds reuse already-loaded icons with zero
 *    LauncherApps round-trips.
 *
 * Both are cleared by the app lifecycle when packages change.
 */
object IconCache {
    private const val DRAWABLE_CACHE_ENTRIES = 192

    @Volatile
    private var cachedLaunchers = emptyList<AppLauncher>()

    private val drawables = object : LruCache<String, Drawable>(DRAWABLE_CACHE_ENTRIES) {}

    var launchers: List<AppLauncher>
        get() = cachedLaunchers
        set(value) {
            synchronized(this) {
                cachedLaunchers = value
            }
        }

    fun getDrawable(packageName: String): Drawable? {
        val drawable = drawables.get(packageName) ?: return null
        return try {
            drawable.constantState?.newDrawable()?.mutate() ?: drawable
        } catch (_: Exception) {
            drawable
        }
    }

    fun putDrawable(packageName: String, drawable: Drawable) {
        try {
            drawables.put(packageName, drawable)
        } catch (_: Exception) {
        }
    }

    fun removeDrawable(packageName: String) {
        drawables.remove(packageName)
    }

    fun clear() {
        launchers = emptyList()
        drawables.evictAll()
    }
}
