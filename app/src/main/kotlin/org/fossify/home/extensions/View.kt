package org.fossify.home.extensions

import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import org.fossify.home.R

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

fun View.setupDrawerBackground() {
    // Fossil liquid glass: the drawer becomes a frosted translucent sheet, letting
    // the (blurred) wallpaper bleed through instead of an opaque color fill.
    background = ResourcesCompat.getDrawable(
        context.resources, R.drawable.glass_drawer, context.theme
    )
}