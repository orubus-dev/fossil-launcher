package org.fossify.home.glass

import android.app.Activity
import android.graphics.drawable.Drawable
import org.fossify.home.R

/**
 * The liquid glass replacement for the framework PopupMenu: every icon
 * long-press action and the home screen menu appear as a frosted glass
 * action sheet with real blur, specular stroke and press ripples.
 */
class GlassMenuDialog(
    activity: Activity,
    title: String?,
    items: List<Item>,
    private val onDismiss: (() -> Unit)? = null,
) : GlassCardDialog(activity) {

    data class Item(
        val title: String,
        val icon: Drawable? = null,
        val danger: Boolean = false,
        val dividedAbove: Boolean = false,
        val onClick: () -> Unit,
    )

    init {
        val rows = mutableListOf<View>()
        title?.takeIf { it.isNotBlank() }?.let { rows.add(buildTitle(it)) }
        items.forEach { item ->
            if (item.dividedAbove) {
                rows.add(buildDivider())
            }
            rows.add(buildRow(item.title, item.icon, item.danger, item.onClick))
        }
        rows.add(buildSpacer())
        installCard(rows)
    }

    private fun buildSpacer(): View {
        return android.view.View(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(10f)
            )
        }
    }

    override fun onDismissed() {
        onDismiss?.invoke()
    }
}

/**
 * Fossil wallpaper selector: current wallpaper preview plus gallery import
 * and the system picker, all on liquid glass.
 */
class WallpaperPickerDialog(
    activity: Activity,
    private val onPickFromGallery: () -> Unit,
    private val onOpenSystemPicker: () -> Unit,
    private val onDismiss: (() -> Unit)? = null,
) : GlassCardDialog(activity) {

    init {
        val rows = mutableListOf<View>()
        rows.add(buildTitle(activity.getString(org.fossify.commons.R.string.wallpapers)))
        rows.add(buildPreview())
        rows.add(buildLabel(activity.getString(R.string.wallpaper_current)))
        rows.add(
            buildRow(
                title = activity.getString(R.string.wallpaper_pick_from_gallery),
                icon = iconOf(R.drawable.ic_photo_vector),
                danger = false
            ) { onPickFromGallery() }
        )
        rows.add(buildDivider())
        rows.add(
            buildRow(
                title = activity.getString(R.string.wallpaper_open_system_picker),
                icon = iconOf(R.drawable.ic_wallpaper_vector),
                danger = false
            ) { onOpenSystemPicker() }
        )
        rows.add(buildSpacer())
        installCard(rows)
    }

    private fun buildSpacer(): android.view.View {
        return android.view.View(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(10f)
            )
        }
    }

    private fun buildLabel(text: String): android.view.View {
        return org.fossify.commons.views.MyTextView(activity).apply {
            this.text = text
            setTextColor(textSecondary)
            textSize = 13.5f
            setPadding(dp(20f), dp(10f), dp(20f), dp(2f))
        }
    }

    private fun buildPreview(): android.view.View {
        val size = activity.resources.displayMetrics
        val height = dp(150f)
        val preview = android.widget.ImageView(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, height
            ).apply {
                setMargins(dp(16f), dp(6f), dp(16f), dp(4f))
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(18f).toFloat())
                }
            }
            val wallpaper = GlassWallpapers.getSnapshot(activity)
            if (wallpaper != null) {
                setImageBitmap(wallpaper)
            } else {
                setImageResource(R.drawable.placeholder_drawable)
                GlassWallpapers.prefetch(activity)
            }
        }
        return preview
    }

    override fun onDismissed() {
        onDismiss?.invoke()
    }
}
