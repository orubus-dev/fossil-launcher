package org.fossify.home.extensions

import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.content.res.ResourcesCompat
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.home.R
import org.fossify.home.glass.GlassWallpapers

fun View.animateScale(
    from: Float,
    to: Float,
    duration: Long,
) = animate()
    .scaleX(to)
    .scaleY(to)
    .setDuration(duration)
    .setInterpolator(AccelerateDecelerateInterpolator())
    .withStartAction {
        scaleX = from
        scaleY = from
    }

/**
 * Fossil liquid glass drawer skin.
 *
 * The drawer root is layered like a real pane of glass over the wallpaper:
 *
 *   root background  — an actual blurred sample of the live wallpaper
 *   scrim (child 0)  — the translucent glass fill + specular stroke
 *   content          — search bar, app grid, everything else
 *
 * This works on every API level with zero cross-window blur dependencies:
 * the depth comes from the engine's own wallpaper pipeline, not from
 * whatever the system is willing to blur behind the window. Re-runnable —
 * call it again whenever the wallpaper changes to refresh the sample.
 */
fun View.setupDrawerBackground() {
    val scrim = ensureGlassScrim()

    GlassWallpapers.prefetch(context)
    ensureBackgroundThread {
        val snapshot = GlassWallpapers.getSnapshot(context) ?: return@ensureBackgroundThread
        val sampleWidth = (rootView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) / 2
        val sampleHeight = (rootView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) / 2
        val blurred = GlassWallpapers.blurSample(snapshot, sampleWidth, sampleHeight)
        post {
            if (blurred.isRecycled) {
                return@post
            }
            background = BitmapDrawable(resources, blurred).apply {
                gravity = Gravity.FILL
            }
            scrim.animate().alpha(1f).setDuration(220L).start()
        }
    }
}

/**
 * Inject (or reuse) the translucent scrim that sits between the blurred
 * wallpaper and the drawer content, so labels and icons keep contrast.
 */
private fun View.ensureGlassScrim(): View {
    if (this !is ViewGroup) {
        return this
    }

    val existing = findViewWithTag<View>(GLASS_SCRIM_TAG)
    if (existing != null) {
        return existing
    }

    val scrim = View(context).apply {
        tag = GLASS_SCRIM_TAG
        isClickable = false
        isFocusable = false
        alpha = 0f
        background = ResourcesCompat.getDrawable(
            context.resources, R.drawable.glass_drawer, context.theme
        )
    }
    val params = when (this) {
        is FrameLayout -> FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        else -> ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
    addView(scrim, 0, params)
    return scrim
}

private const val GLASS_SCRIM_TAG = "fossil_glass_scrim"
